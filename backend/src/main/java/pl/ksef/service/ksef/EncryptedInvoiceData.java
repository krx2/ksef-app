package pl.ksef.service.ksef;

import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Wynik szyfrowania faktury przez KsefEncryptionService.
 * Zawiera wszystkie pola wymagane przez POST /sessions/online/{ref}/invoices.
 */
@Data
@AllArgsConstructor
public class EncryptedInvoiceData {

    /** Klucz symetryczny AES-256 zaszyfrowany RSA-OAEP-SHA256 kluczem publicznym MF, Base64. */
    private String encryptedSymmetricKey;

    /** Wektor inicjalizacji AES (16 bajtów), Base64. */
    private String initializationVector;

    /** SHA-256 oryginalnego XML faktury (przed szyfrowaniem), Base64. */
    private String invoiceHash;

    /** Rozmiar oryginalnego XML faktury w bajtach. */
    private long invoiceSize;

    /** SHA-256 zaszyfrowanej treści faktury (po szyfrowaniu AES), Base64. */
    private String encryptedInvoiceHash;

    /** Rozmiar zaszyfrowanej treści faktury w bajtach. */
    private long encryptedInvoiceSize;

    /** Zaszyfrowana faktura (AES-256-CBC PKCS#7), Base64. */
    private String encryptedInvoiceContent;
}
