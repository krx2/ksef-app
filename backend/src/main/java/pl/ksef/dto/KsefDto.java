package pl.ksef.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@JsonIgnoreProperties(ignoreUnknown = true)
public class KsefDto {

    @Data
    public static class AuthTokenRequest {
        @JsonProperty("contextIdentifier")
        private ContextIdentifier contextIdentifier;

        @Data
        public static class ContextIdentifier {
            @JsonProperty("type")
            private String type = "onip";
            @JsonProperty("identifier")
            private String identifier; // NIP
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InitSessionResponse {
        @JsonProperty("sessionToken")
        private SessionToken sessionToken;
        @JsonProperty("timestamp")
        private String timestamp;
        @JsonProperty("referenceNumber")
        private String referenceNumber;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class SessionToken {
            @JsonProperty("token")
            private String token;
            @JsonProperty("context")
            private Object context;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SendInvoiceResponse {
        @JsonProperty("elementReferenceNumber")
        private String elementReferenceNumber;
        @JsonProperty("processingCode")
        private Integer processingCode;
        @JsonProperty("processingDescription")
        private String processingDescription;
        @JsonProperty("referenceNumber")
        private String referenceNumber;
        @JsonProperty("timestamp")
        private String timestamp;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvoiceStatusResponse {
        @JsonProperty("invoiceStatus")
        private InvoiceStatus invoiceStatus;
        @JsonProperty("elementReferenceNumber")
        private String elementReferenceNumber;
        @JsonProperty("referenceNumber")
        private String referenceNumber;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class InvoiceStatus {
            @JsonProperty("invoiceNumber")
            private String invoiceNumber;
            @JsonProperty("ksefReferenceNumber")
            private String ksefReferenceNumber;
            @JsonProperty("acquisitionTimestamp")
            private String acquisitionTimestamp;
            @JsonProperty("processingCode")
            private Integer processingCode;
            @JsonProperty("processingDescription")
            private String processingDescription;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryInvoiceResponse {
        @JsonProperty("invoiceHeaderList")
        private java.util.List<InvoiceHeader> invoiceHeaderList;
        @JsonProperty("numberOfElements")
        private Integer numberOfElements;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class InvoiceHeader {
            @JsonProperty("ksefReferenceNumber")
            private String ksefReferenceNumber;
            @JsonProperty("invoiceHash")
            private Object invoiceHash;
            @JsonProperty("subjectBy")
            private Object subjectBy;
            @JsonProperty("subjectTo")
            private Object subjectTo;
            @JsonProperty("invoicingDate")
            private String invoicingDate;
            @JsonProperty("net")
            private String net;
            @JsonProperty("vat")
            private String vat;
            @JsonProperty("gross")
            private String gross;
            @JsonProperty("currency")
            private String currency;
        }
    }
}
