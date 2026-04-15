package pl.ksef.service.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import pl.ksef.config.RabbitMQConfig;
import pl.ksef.dto.KsefDto;
import pl.ksef.entity.AppUser;
import pl.ksef.entity.Invoice;
import pl.ksef.repository.InvoiceRepository;
import pl.ksef.repository.UserRepository;
import pl.ksef.service.EmailService;
import pl.ksef.service.ksef.EncryptedInvoiceData;
import pl.ksef.service.ksef.Fa3XmlBuilder;
import pl.ksef.service.ksef.Fa3XmlParser;
import pl.ksef.service.ksef.KsefApiClient;
import pl.ksef.service.ksef.KsefEncryptionService;
import pl.ksef.service.ksef.KsefException;
import pl.ksef.service.ksef.KsefTokenManager;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceQueueConsumer {

    private static final int MAX_INVOICE_POLL_ATTEMPTS = 15;
    private static final int FETCH_PAGE_SIZE = 100;
    private static final long INVOICE_POLL_INTERVAL_MS = 2_000L;
    /** Zakres pobierania faktur — 3h zapewnia overlap przy cyklu co 2h */
    private static final int FETCH_WINDOW_HOURS = 3;

    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final KsefApiClient ksefApiClient;
    private final KsefEncryptionService encryptionService;
    private final KsefTokenManager ksefTokenManager;
    private final Fa3XmlBuilder fa3XmlBuilder;
    private final InvoiceQueuePublisher queuePublisher;
    private final Fa3XmlParser fa3XmlParser;

    /** Opcjonalny — aktywny tylko gdy app.mail.enabled=true */
    @Autowired(required = false)
    private EmailService emailService;

    @RabbitListener(queues = RabbitMQConfig.INVOICE_SEND_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void handleSendInvoice(InvoiceMessages.SendInvoiceMessage message) {
        log.info("Processing SendInvoiceMessage invoiceId={}", message.getInvoiceId());

        Invoice invoice = invoiceRepository.findByIdWithItems(message.getInvoiceId()).orElse(null);
        if (invoice == null) {
            log.warn("Invoice {} not found — discarding stale queue message", message.getInvoiceId());
            return;
        }

        AppUser user = userRepository.findById(message.getUserId()).orElse(null);
        if (user == null) {
            log.warn("Invoice {} — użytkownik {} nie istnieje (usunięty?), oznaczam FAILED",
                    invoice.getId(), message.getUserId());
            invoice.setStatus(Invoice.InvoiceStatus.FAILED);
            invoice.setErrorMessage("Użytkownik nie istnieje — nie można wysłać faktury do KSeF");
            invoiceRepository.save(invoice);
            return;
        }

        if (user.getKsefToken() == null || user.getKsefToken().isBlank()) {
            log.warn("Invoice {} — brak tokenu KSeF dla użytkownika {}, oznaczam FAILED",
                    invoice.getId(), user.getId());
            invoice.setStatus(Invoice.InvoiceStatus.FAILED);
            invoice.setErrorMessage("Brak tokenu KSeF — skonfiguruj token w ustawieniach konta");
            invoiceRepository.save(invoice);
            return;
        }

        invoice.setStatus(Invoice.InvoiceStatus.SENDING);
        invoiceRepository.save(invoice);

        String accessToken = null;
        String sessionRef = null;
        try {
            // 1. Pobierz ważny access token (cache / refresh / pełny re-auth)
            accessToken = ksefTokenManager.getValidAccessToken(user);

            // 2. Zbuduj XML FA(3) jeśli jeszcze nie ma w bazie
            if (invoice.getFa2Xml() == null) {
                String xml = fa3XmlBuilder.build(invoice);
                invoice.setFa2Xml(xml);
            }

            // 3. Zaszyfruj fakturę (AES-256-CBC + RSA-OAEP klucz AES)
            byte[] xmlBytes = invoice.getFa2Xml().getBytes(StandardCharsets.UTF_8);
            EncryptedInvoiceData encrypted = encryptionService.encryptInvoice(xmlBytes);

            // 4. Otwórz sesję interaktywną
            KsefDto.OpenOnlineSessionResponse sessionResp = ksefApiClient.openOnlineSession(
                    accessToken,
                    encrypted.getEncryptedSymmetricKey(),
                    encrypted.getInitializationVector());
            sessionRef = sessionResp.getReferenceNumber();
            log.info("Sesja interaktywna otwarta: {}, ważna do: {}",
                    sessionRef, sessionResp.getValidUntil());

            // 5. Wyślij zaszyfrowaną fakturę
            KsefDto.SendInvoiceResponse sendResp =
                    ksefApiClient.sendInvoice(accessToken, sessionRef, encrypted);
            String invoiceRef = sendResp.getReferenceNumber();
            invoice.setKsefReferenceNumber(invoiceRef);
            log.info("Faktura {} wysłana do sesji {}, invoiceRef={}", invoice.getId(), sessionRef, invoiceRef);

            // 6. Polling — czekaj na numer KSeF (potwierdzenie przetworzenia)
            KsefDto.SessionInvoiceStatusResponse statusResp =
                    pollForKsefStatus(accessToken, sessionRef, invoiceRef);
            invoice.setKsefNumber(statusResp != null ? statusResp.getKsefNumber() : null);
            invoice.setInvoiceHash(statusResp != null ? statusResp.getInvoiceHash() : null);
            invoice.setStatus(Invoice.InvoiceStatus.SENT);
            log.info("Invoice {} sent to KSeF, ksefNumber={}, invoiceHash={}",
                    invoice.getId(),
                    statusResp != null ? statusResp.getKsefNumber() : null,
                    statusResp != null ? statusResp.getInvoiceHash() : null);

            if (emailService != null) {
                emailService.sendInvoiceSentConfirmation(user, invoice);
            }

        } catch (KsefException e) {
            log.error("KSeF error for invoice {}: {}", invoice.getId(), e.getMessage());
            invoice.setStatus(Invoice.InvoiceStatus.FAILED);
            invoice.setErrorMessage(e.getMessage());
        } catch (Exception e) {
            log.error("Unexpected error for invoice {}: {}", invoice.getId(), e.getMessage(), e);
            invoice.setStatus(Invoice.InvoiceStatus.FAILED);
            invoice.setErrorMessage("Unexpected error: " + e.getMessage());
            throw e; // re-throw to trigger DLQ
        } finally {
            invoiceRepository.save(invoice);
            // Zamknij sesję interaktywną (nie sesję auth — access token jest współdzielony)
            if (sessionRef != null && accessToken != null) {
                ksefApiClient.closeSession(accessToken, sessionRef);
            }
        }
    }

    @RabbitListener(queues = RabbitMQConfig.INVOICE_FETCH_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void handleFetchInvoices(InvoiceMessages.FetchInvoicesMessage message) {
        log.info("Fetching invoices for userId={} from={} to={}",
                message.getUserId(), message.getDateFrom(), message.getDateTo());

        AppUser user = userRepository.findById(message.getUserId()).orElse(null);
        if (user == null) {
            log.warn("FetchInvoices — użytkownik {} nie istnieje, pomijam", message.getUserId());
            return;
        }
        if (user.getKsefToken() == null || user.getKsefToken().isBlank()) {
            log.warn("FetchInvoices — brak tokenu KSeF dla użytkownika {}, pomijam", user.getId());
            return;
        }

        String subjectType = (message.getSubjectType() != null && !message.getSubjectType().isBlank())
                ? message.getSubjectType() : "Subject2";
        Invoice.InvoiceDirection direction = "Subject1".equals(subjectType)
                ? Invoice.InvoiceDirection.ISSUED
                : Invoice.InvoiceDirection.RECEIVED;

        try {
            String accessToken = ksefTokenManager.getValidAccessToken(user);

            KsefDto.QueryMetadataRequest request = new KsefDto.QueryMetadataRequest();
            request.setSubjectType(subjectType);
            KsefDto.QueryMetadataRequest.DateRange dateRange = new KsefDto.QueryMetadataRequest.DateRange();
            dateRange.setDateType("PermanentStorage");
            dateRange.setFrom(message.getDateFrom().toString());
            dateRange.setTo(message.getDateTo().toString());
            request.setDateRange(dateRange);

            int saved = 0;
            int skipped = 0;
            int errors = 0;
            int pageOffset = 0;

            boolean hasMore;
            do {
                KsefDto.QueryMetadataResponse result = ksefApiClient.queryInvoices(
                        accessToken, request, pageOffset, FETCH_PAGE_SIZE, "Asc");

                if (result.getInvoices() == null || result.getInvoices().isEmpty()) {
                    log.info("Brak faktur do pobrania dla użytkownika {} (offset={})",
                            user.getId(), pageOffset);
                    break;
                }

                log.info("Strona offset={}: {} faktur w KSeF dla użytkownika {}",
                        pageOffset, result.getInvoices().size(), user.getId());

                for (KsefDto.QueryMetadataResponse.InvoiceMetadata meta : result.getInvoices()) {
                    // 1. Sprawdź duplikat po numerze KSeF i userId — ta sama faktura może istnieć
                    //    jako ISSUED u wystawcy i jako RECEIVED u odbiorcy, więc sprawdzamy per użytkownik.
                    if (invoiceRepository.existsByKsefNumberAndUserId(meta.getKsefNumber(), user.getId())) {
                        log.debug("Faktura {} już istnieje w bazie dla użytkownika {}, pomijam",
                                meta.getKsefNumber(), user.getId());
                        skipped++;
                        continue;
                    }

                    try {
                        // 2. Pobierz pełną treść faktury z KSeF
                        String xml = ksefApiClient.getInvoiceContent(accessToken, meta.getKsefNumber());

                        // 3. Parsuj XML FA(3) → encja Invoice
                        Invoice invoice = fa3XmlParser.parse(xml, user.getId(), direction);

                        // 4. Uzupełnij numer KSeF, hash i zapisz oryginalny XML
                        invoice.setKsefNumber(meta.getKsefNumber());
                        invoice.setInvoiceHash(meta.getInvoiceHash());
                        invoice.setFa2Xml(xml);

                        // 5. Zapisz do bazy (status=RECEIVED_FROM_KSEF, source=KSEF ustawione przez parser)
                        invoiceRepository.save(invoice);
                        saved++;
                        log.debug("Zapisano fakturę ksefNumber={}", meta.getKsefNumber());

                        // 6. Wyślij powiadomienie email (jeśli mail włączony i nie jest to import historyczny)
                        if (emailService != null && !message.isSkipEmail()) {
                            emailService.sendNewInvoiceNotification(user, invoice);
                        }
                    } catch (Exception e) {
                        log.error("Błąd przetwarzania faktury ksefNumber={}: {}",
                                meta.getKsefNumber(), e.getMessage(), e);
                        errors++;
                    }
                }

                hasMore = result.isHasMore();
                pageOffset += result.getInvoices().size();

            } while (hasMore);

            log.info("Pobieranie zakończone dla userId={}: zapisano={}, pominięto={}, błędy={}",
                    user.getId(), saved, skipped, errors);

        } catch (Exception e) {
            log.error("Failed to fetch invoices for user {}: {}", message.getUserId(), e.getMessage(), e);
        }
    }

    /**
     * Czeka na pojawienie się numeru KSeF dla wysłanej faktury.
     * Polling GET /sessions/{sessionRef}/invoices/{invoiceRef} co 2s, maks. 15 prób (30s).
     *
     * <p>Obsługuje kod 440 (duplikat) — przyjmuje oryginalny numer KSeF z odpowiedzi.
     *
     * @return pełna odpowiedź statusu (zawiera ksefNumber i invoiceHash) lub null przy timeout
     */
    private KsefDto.SessionInvoiceStatusResponse pollForKsefStatus(
            String accessToken, String sessionRef, String invoiceRef) {
        for (int attempt = 1; attempt <= MAX_INVOICE_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(INVOICE_POLL_INTERVAL_MS);

                KsefDto.SessionInvoiceStatusResponse status =
                        ksefApiClient.getInvoiceStatus(accessToken, sessionRef, invoiceRef);

                int code = status.getStatus().getCode();
                log.debug("Invoice status poll {}/{}: code={}", attempt, MAX_INVOICE_POLL_ATTEMPTS, code);

                if (code == 200) {
                    return status;
                }

                if (code == 440) {
                    // Duplikat faktury — faktura już istnieje w KSeF
                    log.warn("Faktura {} to duplikat w KSeF (code=440)", invoiceRef);
                    if (status.getKsefNumber() != null) {
                        return status;
                    }
                    throw new KsefException("Duplikat faktury w KSeF (code=440, invoiceRef=" + invoiceRef + ")");
                }

                if (code >= 400) {
                    throw new KsefException("Błąd przetwarzania faktury w KSeF (code=" + code
                            + ", desc=" + status.getStatus().getDescription() + ")");
                }

                // Inne kody → faktura w toku, kontynuuj polling

            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KsefException("Przerwano oczekiwanie na numer KSeF");
            } catch (KsefException e) {
                throw e; // propaguj wyjątki KSeF
            } catch (Exception e) {
                log.warn("Invoice status poll attempt {} failed: {}", attempt, e.getMessage());
            }
        }

        // Timeout — numer KSeF może dotrzeć asynchronicznie
        log.warn("Timeout pollingu numeru KSeF dla invoiceRef={} po {} próbach", invoiceRef, MAX_INVOICE_POLL_ATTEMPTS);
        return null;
    }

    /**
     * Co 2 godziny (o pełnych godzinach parzystych) pobiera faktury z KSeF
     * dla każdego użytkownika posiadającego token. Okno: ostatnie {@value FETCH_WINDOW_HOURS}h
     * (overlap 1h zapobiega pominięciom przy dokładnie 2h cyklu).
     * Nowe faktury wyzwalają powiadomienie email (gdy app.mail.enabled=true).
     */
    @Scheduled(cron = "0 0 */2 * * *")
    public void schedulePeriodicFetch() {
        LocalDateTime to   = LocalDateTime.now();
        LocalDateTime from = to.minusHours(FETCH_WINDOW_HOURS);

        String dateFrom = from.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        String dateTo   = to.format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

        userRepository.findAll().stream()
                .filter(u -> u.getKsefToken() != null && !u.getKsefToken().isBlank())
                .forEach(u -> {
                    queuePublisher.publishFetchInvoices(u.getId(), dateFrom, dateTo);
                    log.info("Periodic fetch queued for userId={} window=[{} — {}]", u.getId(), dateFrom, dateTo);
                });
    }
}
