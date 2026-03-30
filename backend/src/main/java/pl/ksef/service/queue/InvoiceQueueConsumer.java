package pl.ksef.service.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import pl.ksef.config.RabbitMQConfig;
import pl.ksef.entity.AppUser;
import pl.ksef.entity.Invoice;
import pl.ksef.repository.InvoiceRepository;
import pl.ksef.repository.UserRepository;
import pl.ksef.service.ksef.Fa2XmlBuilder;
import pl.ksef.service.ksef.KsefApiClient;
import pl.ksef.service.ksef.KsefException;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceQueueConsumer {

    private final InvoiceRepository invoiceRepository;
    private final UserRepository userRepository;
    private final KsefApiClient ksefApiClient;
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

        AppUser user = userRepository.findById(message.getUserId())
                // TODO: Jeśli użytkownik zostanie usunięty po wstawieniu wiadomości do kolejki,
                //       consumer rzuci RuntimeException → wiadomość trafi do DLQ, ale nie jest
                //       przetwarzana ponownie ani logowana jako problem biznesowy.
                //       Należy obsłużyć ten przypadek: oznaczyć fakturę statusem FAILED
                //       z komunikatem "Użytkownik usunięty" i zakończyć bez rzucania wyjątku.
                .orElseThrow(() -> new RuntimeException("User not found: " + message.getUserId()));

        invoice.setStatus(Invoice.InvoiceStatus.SENDING);
        invoiceRepository.save(invoice);

        String sessionToken = null;
        try {
            // TODO: Przed uwierzytelnieniem sprawdzić, czy user.getKsefToken() nie jest null/blank.
            //       Brak tokenu powinien od razu ustawić status FAILED z czytelnym komunikatem
            //       "Brak tokenu KSeF — skonfiguruj token w ustawieniach konta",
            //       bez próby wywołania API (która i tak skończy się błędem).

            // 1. Auth
            String challenge = ksefApiClient.getAuthorisationChallenge(user.getNip());
            sessionToken = ksefApiClient.initSession(user.getNip(), user.getKsefToken(), challenge);

            // 2. Build FA(2) XML if not already built
            if (invoice.getFa2Xml() == null) {
                String xml = fa2XmlBuilder.build(invoice);
                invoice.setFa2Xml(xml);
            }

            // 3. Send invoice
            String elementRef = ksefApiClient.sendInvoice(sessionToken, invoice.getFa2Xml());
            invoice.setKsefReferenceNumber(elementRef);

            // 4. Poll for KSeF number (simple 3-attempt poll)
            String ksefNumber = pollForKsefNumber(sessionToken, elementRef);
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
            if (sessionToken != null) {
                ksefApiClient.terminateSession(sessionToken);
            }
        }
    }

    @RabbitListener(queues = RabbitMQConfig.INVOICE_FETCH_QUEUE,
                    containerFactory = "rabbitListenerContainerFactory")
    public void handleFetchInvoices(InvoiceMessages.FetchInvoicesMessage message) {
        log.info("Fetching invoices for userId={} from={} to={}",
                message.getUserId(), message.getDateFrom(), message.getDateTo());

        AppUser user = userRepository.findById(message.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found: " + message.getUserId()));

        String sessionToken = null;
        try {
            String challenge = ksefApiClient.getAuthorisationChallenge(user.getNip());
            sessionToken = ksefApiClient.initSession(user.getNip(), user.getKsefToken(), challenge);

            var result = ksefApiClient.queryReceivedInvoices(sessionToken,
                    message.getDateFrom(), message.getDateTo());

            if (result != null && result.getInvoiceHeaderList() != null) {
                log.info("Fetched {} invoices from KSeF for user {}",
                        result.getNumberOfElements(), user.getId());
                // TODO: Zapisać pobrane faktury do bazy danych jako Invoice z direction=RECEIVED.
                //       Dla każdej pozycji result.getInvoiceHeaderList() należy:
                //       1. Sprawdzić czy faktura o danym ksefReferenceNumber już istnieje
                //          (invoiceRepository.findByKsefNumber) — uniknąć duplikatów przy ponownym pobraniu.
                //       2. Pobrać pełne dane faktury z KSeF (GET /api/online/Invoice/Get/{ksefRef})
                //          — aktualnie KsefApiClient nie ma tej metody, trzeba ją dodać.
                //       3. Sparsować XML FA(3) → wypełnić encję Invoice (sprzedawca, nabywca, pozycje).
                //          Warto rozważyć użycie JAXB lub XPath zamiast ręcznego parsowania.
                //       4. Zapisać z status=RECEIVED_FROM_KSEF i source=KSEF (dodać enum).
                //       5. Uruchomić ten mechanizm cyklicznie — patrz TODO w @Scheduled poniżej.
            }
        } catch (Exception e) {
            log.error("Failed to fetch invoices for user {}: {}", message.getUserId(), e.getMessage(), e);
        } finally {
            if (sessionToken != null) {
                ksefApiClient.terminateSession(sessionToken);
            }
        }
    }

    private String pollForKsefNumber(String sessionToken, String elementRef) {
        for (int attempt = 0; attempt < 5; attempt++) {
            try {
                Thread.sleep(2000L * (attempt + 1));
                var status = ksefApiClient.getInvoiceStatus(sessionToken, elementRef);
                if (status != null && status.getInvoiceStatus() != null
                        && status.getInvoiceStatus().getKsefReferenceNumber() != null) {
                    return status.getInvoiceStatus().getKsefReferenceNumber();
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            } catch (Exception e) {
                log.warn("Poll attempt {} failed: {}", attempt + 1, e.getMessage());
            }
        }
        // TODO: Gdy wszystkie 5 prób się nie powiedzie, metoda zwraca null.
        //       Faktura trafia do bazy ze status=SENT, ale ksefNumber=null — stan niespójny.
        //       Należy wprowadzić jeden z dwóch mechanizmów uzupełnienia numeru:
        //       A) Status pośredni SENT_PENDING_KSEF_NUMBER + zadanie @Scheduled (np. co 1 min)
        //          które przeszuka invoiceRepository.findByStatusAndKsefNumberIsNull(SENT)
        //          i ponowi polling dla każdej takiej faktury (nowa sesja KSeF per faktura).
        //       B) Webhook / callback jeśli KSeF go oferuje w docelowym środowisku.
        //       Bez tego mechanizmu numer KSeF nigdy nie zostanie uzupełniony po timeout'ie.
        return null; // ksefNumber may arrive asynchronously
    }

    // TODO: Dodać metodę @Scheduled(fixedDelay = 3_600_000) (np. co godzinę) lub
    //       @Scheduled(cron = "0 0 6 * * *") (codziennie o 6:00) która dla każdego
    //       użytkownika z ustawionym ksefToken:
    //       1. Wyznacza zakres dat (np. ostatnie 24 h lub od ostatniego pobrania).
    //       2. Publikuje FetchInvoicesMessage do invoice.fetch.queue.
    //       Aktualnie invoice.fetch.queue nigdy nie dostaje wiadomości — handleFetchInvoices()
    //       jest konsumentem bez producenta. Klasa musi być oznaczona @EnableScheduling
    //       lub użyć istniejącego @EnableScheduling z KsefApplication.java.
}
