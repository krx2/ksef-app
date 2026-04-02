package pl.ksef.service.queue;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;
import pl.ksef.config.RabbitMQConfig;

import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceQueuePublisher {

    private final RabbitTemplate rabbitTemplate;

    /**
     * Publikuje wiadomość do RabbitMQ dopiero po zatwierdzeniu transakcji DB (AFTER_COMMIT).
     * Eliminuje race condition, w którym konsumer odbierał wiadomość zanim faktura
     * była widoczna w bazie danych.
     */
    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onInvoiceSendRequested(InvoiceSendRequestedEvent event) {
        publishSendInvoice(event.invoiceId(), event.userId());
    }

    public void publishSendInvoice(UUID invoiceId, UUID userId) {
        var message = new InvoiceMessages.SendInvoiceMessage(invoiceId, userId, 0);
        rabbitTemplate.convertAndSend(
                RabbitMQConfig.INVOICE_SEND_EXCHANGE,
                RabbitMQConfig.INVOICE_SEND_QUEUE,
                message
        );
        log.info("Published SendInvoiceMessage for invoiceId={}", invoiceId);
    }

    public void publishFetchInvoices(UUID userId, String dateFrom, String dateTo) {
        var message = new InvoiceMessages.FetchInvoicesMessage(userId, dateFrom, dateTo);
        rabbitTemplate.convertAndSend(RabbitMQConfig.INVOICE_FETCH_QUEUE, message);
        log.info("Published FetchInvoicesMessage for userId={}", userId);
    }
}
