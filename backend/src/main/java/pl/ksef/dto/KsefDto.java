package pl.ksef.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * DTOs dla KSeF API v2 (https://api-test.ksef.mf.gov.pl/v2).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class KsefDto {

    // =====================================================================
    // Auth — Krok 1: POST /auth/challenge
    // =====================================================================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthChallengeResponse {
        /** Identyfikator challenge, np. "20250514-CR-226FB7B000-3ACF9BE4C0-10". */
        @JsonProperty("challenge")
        private String challenge;

        /** Timestamp w formacie ISO-8601 — tylko do celów informacyjnych. */
        @JsonProperty("timestamp")
        private String timestamp;

        /** Timestamp w ms (Unix epoch) — używany do szyfrowania tokenu RSA-OAEP. */
        @JsonProperty("timestampMs")
        private long timestampMs;

        @JsonProperty("clientIp")
        private String clientIp;
    }

    // =====================================================================
    // Auth — Krok 2: POST /auth/ksef-token
    // =====================================================================

    @Data
    public static class InitTokenAuthRequest {
        @JsonProperty("challenge")
        private String challenge;

        @JsonProperty("contextIdentifier")
        private ContextIdentifier contextIdentifier;

        /** Token KSeF zaszyfrowany RSA-OAEP-SHA256 kluczem publicznym MF, Base64. */
        @JsonProperty("encryptedToken")
        private String encryptedToken;

        public InitTokenAuthRequest(String challenge, String nip, String encryptedToken) {
            this.challenge = challenge;
            this.contextIdentifier = new ContextIdentifier(nip);
            this.encryptedToken = encryptedToken;
        }

        @Data
        public static class ContextIdentifier {
            @JsonProperty("type")
            private String type = "Nip";
            @JsonProperty("value")
            private String value;

            public ContextIdentifier(String nip) {
                this.value = nip;
            }
        }
    }

    /** Odpowiedź 202 z POST /auth/ksef-token i POST /auth/xades-signature. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthenticationInitResponse {
        @JsonProperty("referenceNumber")
        private String referenceNumber;

        /** Krótkotrwały operation token używany do sprawdzenia statusu i odebrania tokenów. */
        @JsonProperty("authenticationToken")
        private TokenWithValidity authenticationToken;
    }

    // =====================================================================
    // Auth — Krok 3: GET /auth/{referenceNumber} — polling
    // =====================================================================

    /** Odpowiedź GET /auth/{referenceNumber}. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthenticationOperationStatusResponse {
        @JsonProperty("startDate")
        private String startDate;

        @JsonProperty("authenticationMethod")
        private String authenticationMethod;

        @JsonProperty("status")
        private AuthStatus status;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class AuthStatus {
            /** 100 = w toku, 200 = sukces, 400 = błąd. */
            @JsonProperty("code")
            private int code;

            @JsonProperty("description")
            private String description;

            @JsonProperty("details")
            private List<String> details;
        }
    }

    // =====================================================================
    // Auth — Krok 4: POST /auth/token/redeem
    // =====================================================================

    /** Odpowiedź POST /auth/token/redeem — jednorazowe odebranie access + refresh tokenu. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthenticationTokensResponse {
        @JsonProperty("accessToken")
        private TokenWithValidity accessToken;

        @JsonProperty("refreshToken")
        private TokenWithValidity refreshToken;
    }

    // =====================================================================
    // Auth — Krok 5: POST /auth/token/refresh (gdy accessToken wygaśnie)
    // =====================================================================

    /** Odpowiedź POST /auth/token/refresh. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class AuthenticationTokenRefreshResponse {
        @JsonProperty("accessToken")
        private TokenWithValidity accessToken;
    }

    // =====================================================================
    // Certyfikaty klucza publicznego MF: GET /security/public-key-certificates
    // =====================================================================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class PublicKeyCertificate {
        /**
         * Klucz publiczny zakodowany Base64 (format SubjectPublicKeyInfo / DER).
         * Używany jako X509EncodedKeySpec do zainicjowania KeyFactory("RSA").
         */
        @JsonProperty("certificate")
        private String certificate;

        @JsonProperty("validFrom")
        private String validFrom;

        @JsonProperty("validTo")
        private String validTo;

        /**
         * Lista zastosowań klucza:
         * "KsefTokenEncryption" — do szyfrowania tokenu KSeF w /auth/ksef-token,
         * "SymmetricKeyEncryption" — do szyfrowania klucza AES w /sessions/online.
         */
        @JsonProperty("usage")
        private List<String> usage;
    }

    // =====================================================================
    // Sesja interaktywna — POST /sessions/online
    // =====================================================================

    @Data
    public static class OpenOnlineSessionRequest {
        @JsonProperty("formCode")
        private FormCode formCode;

        @JsonProperty("encryption")
        private EncryptionInfo encryption;

        public OpenOnlineSessionRequest(String encryptedSymmetricKey, String initializationVector) {
            this.formCode = new FormCode();
            this.encryption = new EncryptionInfo(encryptedSymmetricKey, initializationVector);
        }

        @Data
        public static class FormCode {
            @JsonProperty("systemCode")
            private String systemCode = "FA (3)";
            @JsonProperty("schemaVersion")
            private String schemaVersion = "1-0E";
            @JsonProperty("value")
            private String value = "FA";
        }

        @Data
        public static class EncryptionInfo {
            /** Klucz symetryczny AES-256 (32 bajty) zaszyfrowany RSA-OAEP-SHA256, Base64. */
            @JsonProperty("encryptedSymmetricKey")
            private String encryptedSymmetricKey;
            /** Wektor inicjalizacji AES (16 bajtów), Base64. */
            @JsonProperty("initializationVector")
            private String initializationVector;

            public EncryptionInfo(String key, String iv) {
                this.encryptedSymmetricKey = key;
                this.initializationVector = iv;
            }
        }
    }

    /** Odpowiedź 201 z POST /sessions/online. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class OpenOnlineSessionResponse {
        /** Numer referencyjny sesji — wymagany w URL /sessions/online/{ref}/invoices. */
        @JsonProperty("referenceNumber")
        private String referenceNumber;

        @JsonProperty("validUntil")
        private String validUntil;
    }

    // =====================================================================
    // Wysyłka faktury — POST /sessions/online/{sessionRef}/invoices
    // =====================================================================

    @Data
    public static class SendInvoiceRequest {
        /** SHA-256 oryginalnego XML faktury, Base64. */
        @JsonProperty("invoiceHash")
        private String invoiceHash;

        /** Rozmiar oryginalnego XML faktury w bajtach. */
        @JsonProperty("invoiceSize")
        private long invoiceSize;

        /** SHA-256 zaszyfrowanej treści faktury, Base64. */
        @JsonProperty("encryptedInvoiceHash")
        private String encryptedInvoiceHash;

        /** Rozmiar zaszyfrowanej treści faktury w bajtach. */
        @JsonProperty("encryptedInvoiceSize")
        private long encryptedInvoiceSize;

        /** Zaszyfrowana faktura AES-256-CBC PKCS#7, Base64. */
        @JsonProperty("encryptedInvoiceContent")
        private String encryptedInvoiceContent;

        @JsonProperty("offlineMode")
        private boolean offlineMode = false;
    }

    /** Odpowiedź 202 z POST /sessions/online/{sessionRef}/invoices. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SendInvoiceResponse {
        /** Numer referencyjny faktury — wymagany w URL /sessions/{ref}/invoices/{invoiceRef}. */
        @JsonProperty("referenceNumber")
        private String referenceNumber;
    }

    // =====================================================================
    // Status faktury — GET /sessions/{sessionRef}/invoices/{invoiceRef}
    // =====================================================================

    /** Odpowiedź GET /sessions/{sessionRef}/invoices/{invoiceRef}. */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class SessionInvoiceStatusResponse {
        @JsonProperty("ordinalNumber")
        private Integer ordinalNumber;

        @JsonProperty("invoiceNumber")
        private String invoiceNumber;

        /** Numer KSeF faktury (format: NIP-data-hash). Null do czasu przetworzenia. */
        @JsonProperty("ksefNumber")
        private String ksefNumber;

        @JsonProperty("referenceNumber")
        private String referenceNumber;

        @JsonProperty("invoiceHash")
        private String invoiceHash;

        @JsonProperty("acquisitionDate")
        private String acquisitionDate;

        @JsonProperty("invoicingDate")
        private String invoicingDate;

        @JsonProperty("permanentStorageDate")
        private String permanentStorageDate;

        /** URL do pobrania UPO (Urzędowe Poświadczenie Odbioru). */
        @JsonProperty("upoDownloadUrl")
        private String upoDownloadUrl;

        @JsonProperty("upoDownloadUrlExpirationDate")
        private String upoDownloadUrlExpirationDate;

        @JsonProperty("status")
        private InvoiceProcessingStatus status;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class InvoiceProcessingStatus {
            /** 200 = OK, 440 = Duplikat faktury, inne = błąd. */
            @JsonProperty("code")
            private int code;

            @JsonProperty("description")
            private String description;

            @JsonProperty("details")
            private List<String> details;

            /** Dla kodu 440: {"originalKsefNumber": "...", "originalSessionReferenceNumber": "..."}. */
            @JsonProperty("extensions")
            private Object extensions;
        }
    }

    // =====================================================================
    // Pobieranie faktur — POST /invoices/query/metadata
    // =====================================================================

    @Data
    public static class QueryMetadataRequest {
        /** "Subject1" = wystawca, "Subject2" = nabywca, "Subject3" = podmiot trzeci. */
        @JsonProperty("subjectType")
        private String subjectType = "Subject2";

        @JsonProperty("dateRange")
        private DateRange dateRange;

        @Data
        public static class DateRange {
            /** "PermanentStorage" | "Invoicing" | "IssueDate". */
            @JsonProperty("dateType")
            private String dateType = "PermanentStorage";

            @JsonProperty("from")
            private String from;

            @JsonProperty("to")
            private String to;
        }
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class QueryMetadataResponse {
        @JsonProperty("hasMore")
        private boolean hasMore;

        @JsonProperty("isTruncated")
        private boolean truncated;

        @JsonProperty("permanentStorageHwmDate")
        private String permanentStorageHwmDate;

        @JsonProperty("invoices")
        private List<InvoiceMetadata> invoices;

        @Data
        @JsonIgnoreProperties(ignoreUnknown = true)
        public static class InvoiceMetadata {
            @JsonProperty("ksefNumber")
            private String ksefNumber;

            @JsonProperty("invoiceNumber")
            private String invoiceNumber;

            @JsonProperty("issueDate")
            private String issueDate;

            @JsonProperty("invoicingDate")
            private String invoicingDate;

            @JsonProperty("permanentStorageDate")
            private String permanentStorageDate;

            @JsonProperty("net")
            private String net;

            @JsonProperty("vat")
            private String vat;

            @JsonProperty("gross")
            private String gross;

            @JsonProperty("currency")
            private String currency;

            @JsonProperty("invoiceHash")
            private String invoiceHash;
        }
    }

    // =====================================================================
    // Treść faktury — GET /invoices/{ksefNumber}/content
    // =====================================================================

    /**
     * Odpowiedź GET /invoices/{ksefNumber}/content.
     * Pole invoiceContent zawiera surowy XML FA(3) w kodowaniu UTF-8.
     * Jeśli API zwraca Base64, treść należy zdekodować przed parsowaniem.
     */
    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class InvoiceContentResponse {
        @JsonProperty("invoiceContent")
        private String invoiceContent;

        @JsonProperty("ksefNumber")
        private String ksefNumber;
    }

    // =====================================================================
    // Shared types
    // =====================================================================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class TokenWithValidity {
        @JsonProperty("token")
        private String token;

        /** Data ważności tokenu w formacie ISO-8601 z strefą czasową. */
        @JsonProperty("validUntil")
        private String validUntil;
    }
}
