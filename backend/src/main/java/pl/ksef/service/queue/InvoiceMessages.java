package pl.ksef.service.queue;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

public class InvoiceMessages {

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class SendInvoiceMessage {
        private UUID invoiceId;
        private UUID userId;
        private int retryCount;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class FetchInvoicesMessage {
        private UUID userId;
        private String dateFrom;
        private String dateTo;
    }
}
