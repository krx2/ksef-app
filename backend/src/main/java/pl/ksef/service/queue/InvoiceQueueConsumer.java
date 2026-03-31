package pl.ksef.service.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import pl.ksef.config.RabbitMQConfig;
import pl.ksef.dto.KsefDto;
import pl.ksef.entity.AppUser;
import pl.ksef.entity.Invoice;
import pl.ksef.repository.InvoiceRepository;
import pl.ksef.repository.UserRepository;
import pl.ksef.service.ksef.EncryptedInvoiceData;
import pl.ksef.service.ksef.Fa2XmlBuilder;
import pl.ksef.service.ksef.KsefApiClient;
import pl.ksef.service.ksef.KsefEncryptionService;
import pl.ksef.service.ksef.KsefException;
import pl.ksef.service.ksef.KsefTokenManager;

import java.nio.charset.StandardCharsets;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceQueueConsumer {

    private static final int MAX_INVOICE_POLL_ATTEMPTS = 15;
    private static final long INVOICE_POLL_INTERVAL_MS = 2_000L;

    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final KsefApiClient ksefApiClient;
    private final KsefEncryptionService encryptionService;
    private final KsefTokenManager ksefTokenManager;
    private final Fa2XmlBuilder fa2XmlBuilder;

    @RabbitListener(queues = RabbitMQConfig.INVOICE_SEND_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void handleSendInvoice(InvoiceMessages.SendInvoiceMessage message) {
        log.info("Processing SendInvoiceMessage invoiceId={}", message.getInvoiceId());

        Invoice invoice = invoiceRepository.findById(message.getInvoiceId()).orElse(null);
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
                String xml = fa2XmlBuilder.build(invoice);
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
            String ksefNumber = pollForKsefNumber(accessToken, sessionRef, invoiceRef);
            invoice.setKsefNumber(ksefNumber);
            invoice.setStatus(Invoice.InvoiceStatus.SENT);
            log.info("Invoice {} sent to KSeF, ksefNumber={}", invoice.getId(), ksefNumber);

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

        try {
            String accessToken = ksefTokenManager.getValidAccessToken(user);

            KsefDto.QueryMetadataRequest request = new KsefDto.QueryMetadataRequest();
            KsefDto.QueryMetadataRequest.DateRange dateRange = new KsefDto.QueryMetadataRequest.DateRange();
            dateRange.setDateType("PermanentStorage");
            dateRange.setFrom(message.getDateFrom().toString());
            dateRange.setTo(message.getDateTo().toString());
            request.setDateRange(dateRange);

            KsefDto.QueryMetadataResponse result = ksefApiClient.queryInvoices(accessToken, request);

            if (result != null && result.getInvoices() != null) {
                log.info("Fetched {} invoices from KSeF for user {}",
                        result.getInvoices().size(), user.getId());
                // TODO: Zapisać pobrane faktury do bazy danych jako Invoice z direction=RECEIVED.
                //       Dla każdej pozycji result.getInvoices() należy:
                //       1. Sprawdzić czy faktura o danym ksefNumber już istnieje
                //          (invoiceRepository.findByKsefNumber) — uniknąć duplikatów przy ponownym pobraniu.
                //       2. Pobrać pełne dane faktury z KSeF (GET /invoices/{ksefNumber}/content)
                //          — aktualnie KsefApiClient nie ma tej metody, trzeba ją dodać.
                //       3. Sparsować XML FA(3) → wypełnić encję Invoice (sprzedawca, nabywca, pozycje).
                //          Warto rozważyć użycie JAXB lub XPath zamiast ręcznego parsowania.
                //       4. Zapisać z status=RECEIVED_FROM_KSEF i source=KSEF (dodać enum).
                //       5. Uruchomić ten mechanizm cyklicznie — patrz TODO @Scheduled poniżej.
            }
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
     * @return numer KSeF lub null jeśli nie pojawił się w czasie limitu
     */
    private String pollForKsefNumber(String accessToken, String sessionRef, String invoiceRef) {
        for (int attempt = 1; attempt <= MAX_INVOICE_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(INVOICE_POLL_INTERVAL_MS);

                KsefDto.SessionInvoiceStatusResponse status =
                        ksefApiClient.getInvoiceStatus(accessToken, sessionRef, invoiceRef);

                int code = status.getStatus().getCode();
                log.debug("Invoice status poll {}/{}: code={}", attempt, MAX_INVOICE_POLL_ATTEMPTS, code);

                if (code == 200) {
                    return status.getKsefNumber();
                }

                if (code == 440) {
                    // Duplikat faktury — faktura już istnieje w KSeF
                    log.warn("Faktura {} to duplikat w KSeF (code=440)", invoiceRef);
                    // extensions zawiera originalKsefNumber jeśli API go zwraca
                    if (status.getKsefNumber() != null) {
                        return status.getKsefNumber();
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

    // TODO: Dodać metodę @Scheduled(cron = "0 0 6 * * *") (codziennie o 6:00) która dla każdego
    //       użytkownika z ustawionym ksefToken publikuje FetchInvoicesMessage do invoice.fetch.queue.
    //       Aktualnie handleFetchInvoices() jest konsumentem bez producenta.
    //       Klasa musi być oznaczona @EnableScheduling lub użyć istniejącego z KsefApplication.java.
}
