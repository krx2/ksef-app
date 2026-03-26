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

        Invoice invoice = invoiceRepository.findById(message.getInvoiceId())
                .orElseThrow(() -> new RuntimeException("Invoice not found: " + message.getInvoiceId()));

        AppUser user = userRepository.findById(message.getUserId())
                .orElseThrow(() -> new RuntimeException("User not found: " + message.getUserId()));

        invoice.setStatus(Invoice.InvoiceStatus.SENDING);
        invoiceRepository.save(invoice);

        String sessionToken = null;
        try {
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
                // TODO: persist received invoices (parse XML from KSeF)
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
        return null; // ksefNumber may arrive asynchronously
    }
}
