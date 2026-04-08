package pl.ksef.service.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Service;
import pl.ksef.config.RabbitMQConfig;
import pl.ksef.entity.Invoice;
import pl.ksef.repository.InvoiceRepository;

/**
 * Konsument kolejki DLQ (Dead Letter Queue) dla faktury.
 * Odbiera wiadomości, które nie zostały przetworzone przez InvoiceQueueConsumer
 * (po nieoczekiwanym błędzie), i oznacza fakturę statusem FAILED.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceDlqConsumer {

    private final InvoiceRepository invoiceRepository;

    @RabbitListener(queues = RabbitMQConfig.INVOICE_SEND_DLQ,
                    containerFactory = "rabbitListenerContainerFactory")
    public void handleDeadLetter(InvoiceMessages.SendInvoiceMessage message) {
        log.error("DEAD LETTER: invoiceId={} userId={} retryCount={} — oznaczam fakturę jako FAILED",
                message.getInvoiceId(), message.getUserId(), message.getRetryCount());

        invoiceRepository.findById(message.getInvoiceId()).ifPresentOrElse(
            invoice -> {
                if (invoice.getStatus() != Invoice.InvoiceStatus.FAILED) {
                    invoice.setStatus(Invoice.InvoiceStatus.FAILED);
                    invoice.setErrorMessage("Przekroczono limit prób wysyłki do KSeF (DLQ)");
                    invoiceRepository.save(invoice);
                    log.warn("Invoice {} oznaczona jako FAILED po trafieniu do DLQ", invoice.getId());
                }
            },
            () -> log.warn("DLQ: faktura {} nie istnieje w bazie — pominięto", message.getInvoiceId())
        );
    }
}
