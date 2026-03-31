package pl.ksef.service.ksef;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pl.ksef.dto.KsefDto;

import java.util.List;

/**
 * Klient HTTP dla KSeF API v2 (base URL: https://api-test.ksef.mf.gov.pl/v2).
 * Odpowiada wyłącznie za wywołania HTTP — logika tokenów i szyfrowania
 * jest w KsefTokenManager i KsefEncryptionService.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KsefApiClient {

    @Qualifier("ksefRestClient")
    private final RestClient restClient;

    // =====================================================================
    // Certyfikaty klucza publicznego
    // =====================================================================

    /** GET /security/public-key-certificates — nie wymaga uwierzytelnienia. */
    public List<KsefDto.PublicKeyCertificate> getPublicKeyCertificates() {
        return restClient.get()
                .uri("/security/public-key-certificates")
                .retrieve()
                .body(new ParameterizedTypeReference<>() {});
    }

    // =====================================================================
    // Uwierzytelnienie — przepływ 5-krokowy
    // =====================================================================

    /**
     * Krok 1: POST /auth/challenge — nie wymaga uwierzytelnienia.
     * Zwraca challenge (string ID) i timestampMs (Unix ms) wymagane w kroku 2.
     */
    public KsefDto.AuthChallengeResponse getChallenge() {
        KsefDto.AuthChallengeResponse resp = restClient.post()
                .uri("/auth/challenge")
                .contentType(MediaType.APPLICATION_JSON)
                .body("{}")
                .retrieve()
                .body(KsefDto.AuthChallengeResponse.class);

        if (resp == null || resp.getChallenge() == null) {
            throw new KsefException("Pusta odpowiedź z /auth/challenge");
        }
        log.debug("Challenge: {}, timestampMs: {}", resp.getChallenge(), resp.getTimestampMs());
        return resp;
    }

    /**
     * Krok 2: POST /auth/ksef-token — nie wymaga uwierzytelnienia.
     * @param challenge   Wartość challenge z kroku 1
     * @param nip         NIP podmiotu
     * @param encryptedToken Token KSeF zaszyfrowany RSA-OAEP (z KsefEncryptionService.encryptToken)
     * @return  referenceNumber + authenticationToken (operation token, krótkotrwały)
     */
    public KsefDto.AuthenticationInitResponse initTokenAuth(
            String challenge, String nip, String encryptedToken) {

        KsefDto.InitTokenAuthRequest req = new KsefDto.InitTokenAuthRequest(challenge, nip, encryptedToken);
        KsefDto.AuthenticationInitResponse resp = restClient.post()
                .uri("/auth/ksef-token")
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(KsefDto.AuthenticationInitResponse.class);

        if (resp == null || resp.getReferenceNumber() == null) {
            throw new KsefException("Pusta odpowiedź z /auth/ksef-token");
        }
        log.info("Auth initiated, referenceNumber: {}", resp.getReferenceNumber());
        return resp;
    }

    /**
     * Krok 3: GET /auth/{referenceNumber} — używa operation tokena z kroku 2.
     * Wywoływany wielokrotnie (polling) aż status.code == 200.
     */
    public KsefDto.AuthenticationOperationStatusResponse getAuthStatus(
            String referenceNumber, String operationToken) {

        KsefDto.AuthenticationOperationStatusResponse resp = restClient.get()
                .uri("/auth/{ref}", referenceNumber)
                .header("Authorization", "Bearer " + operationToken)
                .retrieve()
                .body(KsefDto.AuthenticationOperationStatusResponse.class);

        if (resp == null || resp.getStatus() == null) {
            throw new KsefException("Pusta odpowiedź z /auth/" + referenceNumber);
        }
        return resp;
    }

    /**
     * Krok 4: POST /auth/token/redeem — używa operation tokena z kroku 2.
     * JEDNORAZOWE odebranie access + refresh tokenu — można wywołać tylko raz.
     */
    public KsefDto.AuthenticationTokensResponse redeemTokens(String operationToken) {
        KsefDto.AuthenticationTokensResponse resp = restClient.post()
                .uri("/auth/token/redeem")
                .header("Authorization", "Bearer " + operationToken)
                .retrieve()
                .body(KsefDto.AuthenticationTokensResponse.class);

        if (resp == null || resp.getAccessToken() == null || resp.getRefreshToken() == null) {
            throw new KsefException("Pusta odpowiedź z /auth/token/redeem");
        }
        log.info("Tokeny dostępowe odebrane, access ważny do: {}",
                resp.getAccessToken().getValidUntil());
        return resp;
    }

    /**
     * Krok 5 (opcjonalny): POST /auth/token/refresh — używa refresh tokenu.
     * Odświeża access token bez pełnego ponownego uwierzytelnienia.
     */
    public KsefDto.AuthenticationTokenRefreshResponse refreshAccessToken(String refreshToken) {
        KsefDto.AuthenticationTokenRefreshResponse resp = restClient.post()
                .uri("/auth/token/refresh")
                .header("Authorization", "Bearer " + refreshToken)
                .retrieve()
                .body(KsefDto.AuthenticationTokenRefreshResponse.class);

        if (resp == null || resp.getAccessToken() == null) {
            throw new KsefException("Pusta odpowiedź z /auth/token/refresh");
        }
        log.info("Access token odświeżony, ważny do: {}", resp.getAccessToken().getValidUntil());
        return resp;
    }

    // =====================================================================
    // Sesja interaktywna
    // =====================================================================

    /**
     * POST /sessions/online — otwiera sesję interaktywną.
     * Wymagane przed każdą wysyłką faktury. Używa access tokena.
     * @param accessToken JWT access token
     * @param encryptedSymmetricKey Klucz AES zaszyfrowany RSA-OAEP kluczem MF, Base64
     * @param initializationVector  IV dla AES-CBC, Base64
     * @return referenceNumber sesji — wymagany w kolejnych wywołaniach
     */
    public KsefDto.OpenOnlineSessionResponse openOnlineSession(
            String accessToken, String encryptedSymmetricKey, String initializationVector) {

        KsefDto.OpenOnlineSessionRequest req =
                new KsefDto.OpenOnlineSessionRequest(encryptedSymmetricKey, initializationVector);

        KsefDto.OpenOnlineSessionResponse resp = restClient.post()
                .uri("/sessions/online")
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(KsefDto.OpenOnlineSessionResponse.class);

        if (resp == null || resp.getReferenceNumber() == null) {
            throw new KsefException("Pusta odpowiedź z /sessions/online");
        }
        log.info("Sesja interaktywna otwarta: {}, ważna do: {}",
                resp.getReferenceNumber(), resp.getValidUntil());
        return resp;
    }

    /**
     * POST /sessions/online/{sessionRef}/invoices — wysyła zaszyfrowaną fakturę.
     * @param accessToken JWT access token
     * @param sessionRef  referenceNumber sesji z openOnlineSession()
     * @param data        Zaszyfrowane dane faktury z KsefEncryptionService.encryptInvoice()
     * @return referenceNumber faktury — wymagany do pollingu statusu
     */
    public KsefDto.SendInvoiceResponse sendInvoice(
            String accessToken, String sessionRef, EncryptedInvoiceData data) {

        KsefDto.SendInvoiceRequest req = new KsefDto.SendInvoiceRequest();
        req.setInvoiceHash(data.getInvoiceHash());
        req.setInvoiceSize(data.getInvoiceSize());
        req.setEncryptedInvoiceHash(data.getEncryptedInvoiceHash());
        req.setEncryptedInvoiceSize(data.getEncryptedInvoiceSize());
        req.setEncryptedInvoiceContent(data.getEncryptedInvoiceContent());
        req.setOfflineMode(false);

        KsefDto.SendInvoiceResponse resp = restClient.post()
                .uri("/sessions/online/{ref}/invoices", sessionRef)
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(req)
                .retrieve()
                .body(KsefDto.SendInvoiceResponse.class);

        if (resp == null || resp.getReferenceNumber() == null) {
            throw new KsefException("Pusta odpowiedź z /sessions/online/" + sessionRef + "/invoices");
        }
        log.info("Faktura wysłana do sesji {}, invoiceRef: {}", sessionRef, resp.getReferenceNumber());
        return resp;
    }

    /**
     * GET /sessions/{sessionRef}/invoices/{invoiceRef} — sprawdza status faktury w sesji.
     * @param accessToken JWT access token
     * @param sessionRef  referenceNumber sesji
     * @param invoiceRef  referenceNumber faktury (z sendInvoice())
     */
    public KsefDto.SessionInvoiceStatusResponse getInvoiceStatus(
            String accessToken, String sessionRef, String invoiceRef) {

        KsefDto.SessionInvoiceStatusResponse resp = restClient.get()
                .uri("/sessions/{sessionRef}/invoices/{invoiceRef}", sessionRef, invoiceRef)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(KsefDto.SessionInvoiceStatusResponse.class);

        if (resp == null || resp.getStatus() == null) {
            throw new KsefException("Pusta odpowiedź statusu faktury " + invoiceRef);
        }
        return resp;
    }

    /**
     * POST /sessions/online/{sessionRef}/close — zamyka sesję interaktywną.
     * Inicjuje generowanie zbiorczego UPO. Wywoływać zawsze w bloku finally.
     */
    public void closeSession(String accessToken, String sessionRef) {
        try {
            restClient.post()
                    .uri("/sessions/online/{ref}/close", sessionRef)
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Sesja interaktywna zamknięta: {}", sessionRef);
        } catch (Exception e) {
            log.warn("Nie udało się zamknąć sesji interaktywnej {}: {}", sessionRef, e.getMessage());
        }
    }

    /**
     * DELETE /auth/sessions/current — unieważnia bieżącą sesję uwierzytelnienia.
     * UWAGA: Wywołanie unieważnia refresh token. Wywoływać tylko przy wylogowaniu użytkownika,
     * NIE po każdej fakturze. Access tokeny działają do wygaśnięcia (validUntil).
     */
    public void terminateAuthSession(String accessToken) {
        try {
            restClient.delete()
                    .uri("/auth/sessions/current")
                    .header("Authorization", "Bearer " + accessToken)
                    .retrieve()
                    .toBodilessEntity();
            log.info("Sesja uwierzytelnienia unieważniona");
        } catch (Exception e) {
            log.warn("Nie udało się unieważnić sesji auth: {}", e.getMessage());
        }
    }

    // =====================================================================
    // Pobieranie faktur
    // =====================================================================

    /**
     * POST /invoices/query/metadata — pobiera metadane faktur według kryteriów.
     *
     * @param accessToken JWT access token
     * @param request     Kryteria wyszukiwania (filtry)
     * @param pageOffset  Indeks pierwszej strony wyników (0 = pierwsza strona, min=0)
     * @param pageSize    Rozmiar strony (min=10, max=250)
     * @param sortOrder   Kolejność sortowania: "Asc" | "Desc"
     */
    public KsefDto.QueryMetadataResponse queryInvoices(
            String accessToken, KsefDto.QueryMetadataRequest request,
            int pageOffset, int pageSize, String sortOrder) {

        KsefDto.QueryMetadataResponse resp = restClient.post()
                .uri(uriBuilder -> uriBuilder
                        .path("/invoices/query/metadata")
                        .queryParam("pageOffset", pageOffset)
                        .queryParam("pageSize", pageSize)
                        .queryParam("sortOrder", sortOrder)
                        .build())
                .header("Authorization", "Bearer " + accessToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(request)
                .retrieve()
                .body(KsefDto.QueryMetadataResponse.class);

        if (resp == null) {
            throw new KsefException("Pusta odpowiedź z /invoices/query/metadata");
        }
        log.info("Pobrano metadane faktur (offset={}, size={}, sort={}): {} rekordów, hasMore={}",
                pageOffset, pageSize, sortOrder,
                resp.getInvoices() != null ? resp.getInvoices().size() : 0, resp.isHasMore());
        return resp;
    }

    /**
     * GET /invoices/{ksefNumber}/content — pobiera surowy XML faktury FA(3).
     *
     * @param accessToken JWT access token
     * @param ksefNumber  numer KSeF faktury (format: NIP-data-hash)
     * @return XML faktury FA(3) jako string UTF-8
     */
    public String getInvoiceContent(String accessToken, String ksefNumber) {
        String content = restClient.get()
                .uri("/invoices/{ksefNumber}/content", ksefNumber)
                .header("Authorization", "Bearer " + accessToken)
                .retrieve()
                .body(String.class);
        if (content == null || content.isBlank()) {
            throw new KsefException("Pusta odpowiedź z /invoices/" + ksefNumber + "/content");
        }
        log.debug("Pobrano treść faktury ksefNumber={}, rozmiar={} B", ksefNumber, content.length());
        return content;
    }
}
