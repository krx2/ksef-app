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
        /**
         * Data początkowa w formacie ISO-8601 z datą i czasem wymaganym przez KSeF API v2,
         * np. "2025-01-01T00:00:00+01:00".
         * Producent tej wiadomości (przyszły @Scheduled lub kontroler) MUSI podać pełny datetime —
         * wartość jest przekazywana 1:1 do QueryMetadataRequest.DateRange.from.
         */
        private String dateFrom;
        /** Patrz {@link #dateFrom} — ten sam wymóg formatu. */
        private String dateTo;
    }
}
