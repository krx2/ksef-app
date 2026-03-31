package pl.ksef.service.ksef;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pl.ksef.dto.KsefDto;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;

/**
 * Szyfrowanie dla KSeF API v2:
 * <ul>
 *   <li>RSA-OAEP-SHA256 — szyfrowanie tokenu KSeF (dla /auth/ksef-token)</li>
 *   <li>RSA-OAEP-SHA256 — szyfrowanie klucza AES (dla /sessions/online)</li>
 *   <li>AES-256-CBC PKCS#7 — szyfrowanie treści faktury</li>
 * </ul>
 * Klucze publiczne MF pobierane z GET /security/public-key-certificates i cachowane.
 */
@Service
@Slf4j
public class KsefEncryptionService {

    private static final String TOKEN_USAGE    = "KsefTokenEncryption";
    private static final String AES_KEY_USAGE  = "SymmetricKeyEncryption";

    private final RestClient restClient;

    /** Klucz publiczny MF do szyfrowania tokenu KSeF. */
    private volatile PublicKey tokenEncryptionKey;
    /** Klucz publiczny MF do szyfrowania klucza symetrycznego AES. */
    private volatile PublicKey symmetricKeyEncryptionKey;
    /** Czas ważności pobranych certyfikatów — odśwież jeśli zbliżamy się do końca. */
    private volatile OffsetDateTime certValidTo;

    public KsefEncryptionService(@Qualifier("ksefRestClient") RestClient restClient) {
        this.restClient = restClient;
    }

    @PostConstruct
    public void loadCertificates() {
        try {
            refreshCertificates();
            log.info("Klucze publiczne MF załadowane pomyślnie");
        } catch (Exception e) {
            log.warn("Nie udało się załadować kluczy publicznych MF przy starcie — " +
                     "szyfrowanie KSeF będzie niedostępne do pierwszego połączenia: {}", e.getMessage());
        }
    }

    /**
     * Szyfruje token KSeF razem z timestampMs dla /auth/ksef-token.
     * Payload: UTF-8(ksefToken + "|" + timestampMs)
     * Algorytm: RSA-OAEP-SHA256 z kluczem KsefTokenEncryption.
     */
    public String encryptToken(String ksefToken, long timestampMs) {
        ensureKeysLoaded();
        byte[] payload = (ksefToken + "|" + timestampMs).getBytes(StandardCharsets.UTF_8);
        return Base64.getEncoder().encodeToString(rsaOaepEncrypt(tokenEncryptionKey, payload));
    }

    /**
     * Szyfruje fakturę XML dla /sessions/online/{ref}/invoices.
     * Generuje losowy klucz AES-256 i IV, szyfruje XML i klucz AES kluczem publicznym MF.
     *
     * @param xmlBytes Bajty faktury XML w UTF-8
     * @return EncryptedInvoiceData ze wszystkimi polami wymaganymi przez API v2
     */
    public EncryptedInvoiceData encryptInvoice(byte[] xmlBytes) {
        ensureKeysLoaded();
        try {
            // 1. Generuj losowy klucz AES-256
            KeyGenerator keyGen = KeyGenerator.getInstance("AES");
            keyGen.init(256, new SecureRandom());
            SecretKey aesKey = keyGen.generateKey();

            // 2. Generuj losowy IV (16 bajtów)
            byte[] iv = new byte[16];
            new SecureRandom().nextBytes(iv);

            // 3. Szyfruj XML przez AES-256-CBC PKCS#7
            Cipher aesCipher = Cipher.getInstance("AES/CBC/PKCS5Padding");
            aesCipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(aesKey.getEncoded(), "AES"),
                    new IvParameterSpec(iv));
            byte[] encryptedXml = aesCipher.doFinal(xmlBytes);

            // 4. Szyfruj klucz AES przez RSA-OAEP-SHA256 kluczem MF
            byte[] encryptedAesKey = rsaOaepEncrypt(symmetricKeyEncryptionKey, aesKey.getEncoded());

            return new EncryptedInvoiceData(
                    Base64.getEncoder().encodeToString(encryptedAesKey),
                    Base64.getEncoder().encodeToString(iv),
                    sha256Base64(xmlBytes),
                    xmlBytes.length,
                    sha256Base64(encryptedXml),
                    encryptedXml.length,
                    Base64.getEncoder().encodeToString(encryptedXml)
            );
        } catch (GeneralSecurityException e) {
            throw new KsefException("Błąd szyfrowania faktury: " + e.getMessage(), e);
        }
    }

    // ---- helpers ----

    private byte[] rsaOaepEncrypt(PublicKey publicKey, byte[] data) {
        try {
            OAEPParameterSpec spec = new OAEPParameterSpec(
                    "SHA-256", "MGF1", MGF1ParameterSpec.SHA256, PSource.PSpecified.DEFAULT);
            Cipher cipher = Cipher.getInstance("RSA/ECB/OAEPWithSHA-256AndMGF1Padding");
            cipher.init(Cipher.ENCRYPT_MODE, publicKey, spec);
            return cipher.doFinal(data);
        } catch (GeneralSecurityException e) {
            throw new KsefException("Błąd szyfrowania RSA-OAEP: " + e.getMessage(), e);
        }
    }

    private String sha256Base64(byte[] data) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return Base64.getEncoder().encodeToString(digest.digest(data));
        } catch (NoSuchAlgorithmException e) {
            throw new KsefException("SHA-256 niedostępny", e);
        }
    }

    private void ensureKeysLoaded() {
        if (tokenEncryptionKey == null || symmetricKeyEncryptionKey == null
                || (certValidTo != null && OffsetDateTime.now().isAfter(certValidTo.minusHours(1)))) {
            refreshCertificates();
        }
    }

    private synchronized void refreshCertificates() {
        // Podwójne sprawdzenie po wejściu do sekcji krytycznej
        if (tokenEncryptionKey != null && symmetricKeyEncryptionKey != null
                && certValidTo != null && OffsetDateTime.now().isBefore(certValidTo.minusHours(1))) {
            return;
        }

        List<KsefDto.PublicKeyCertificate> certs = restClient.get()
                .uri("/security/public-key-certificates")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});

        if (certs == null || certs.isEmpty()) {
            throw new KsefException("Brak certyfikatów klucza publicznego MF w odpowiedzi");
        }

        OffsetDateTime latestValidTo = null;
        for (KsefDto.PublicKeyCertificate cert : certs) {
            if (cert.getUsage() == null || cert.getCertificate() == null) continue;
            PublicKey key = parseDerPublicKey(cert.getCertificate());
            if (cert.getUsage().contains(TOKEN_USAGE)) {
                tokenEncryptionKey = key;
            }
            if (cert.getUsage().contains(AES_KEY_USAGE)) {
                symmetricKeyEncryptionKey = key;
            }
            if (cert.getValidTo() != null) {
                OffsetDateTime vt = OffsetDateTime.parse(cert.getValidTo());
                if (latestValidTo == null || vt.isBefore(latestValidTo)) {
                    latestValidTo = vt; // certValidTo = najwcześniejsze wygaśnięcie
                }
            }
        }

        if (tokenEncryptionKey == null) {
            throw new KsefException("Brak certyfikatu MF z usage='" + TOKEN_USAGE + "'");
        }
        if (symmetricKeyEncryptionKey == null) {
            throw new KsefException("Brak certyfikatu MF z usage='" + AES_KEY_USAGE + "'");
        }

        this.certValidTo = latestValidTo;
        log.info("Klucze publiczne MF odświeżone, ważne do: {}", latestValidTo);
    }

    private PublicKey parseDerPublicKey(String base64Der) {
        try {
            byte[] derBytes = Base64.getDecoder().decode(base64Der);
            KeyFactory keyFactory = KeyFactory.getInstance("RSA");
            return keyFactory.generatePublic(new X509EncodedKeySpec(derBytes));
        } catch (Exception e) {
            throw new KsefException("Nie można sparsować klucza publicznego MF: " + e.getMessage(), e);
        }
    }
}
