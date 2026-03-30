package pl.ksef.service.ksef;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import pl.ksef.dto.KsefDto;

import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Low-level client for KSeF test API (api-test.ksef.mf.gov.pl).
 * Auth flow: POST /api/online/Session/AuthorisationChallenge
 *            -> sign challenge with token -> POST /api/online/Session/InitToken
 *            -> use sessionToken for subsequent calls
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KsefApiClient {

    @Qualifier("ksefRestClient")
    private final RestClient restClient;

    /**
     * Step 1: Get authorisation challenge (timestamp from KSeF).
     */
    public String getAuthorisationChallenge(String nip) {
        var body = """
                {"contextIdentifier":{"type":"onip","identifier":"%s"}}
                """.formatted(nip).trim();

        var response = restClient.post()
                .uri("/api/online/Session/AuthorisationChallenge")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(String.class);

        log.debug("AuthorisationChallenge response: {}", response.getBody());
        // TODO: Parsowanie JSON jest ręczne (indexOf + substring) — kruche i podatne na błędy
        //       przy zmianie formatu odpowiedzi KSeF. Należy zastąpić deserializacją Jacksona
        //       przez zmapowanie na KsefDto.AuthChallengeResponse (dodać brakujące pole "challenge"
        //       lub "timestamp" zależnie od aktualnej wersji API KSeF — sprawdzić dokumentację).
        String raw = response.getBody();
        if (raw == null) throw new KsefException("Empty challenge response");
        // extract "timestamp":"2024-01-01T00:00:00.000Z"
        int idx = raw.indexOf("\"timestamp\":");
        if (idx < 0) throw new KsefException("No timestamp in challenge: " + raw);
        String ts = raw.substring(idx + 13);
        ts = ts.substring(0, ts.indexOf("\""));
        return ts;
    }

    /**
     * Step 2: Init session with NIP + token.
     * The token is XOR'd with the challenge timestamp (MF spec for authByToken).
     * For test env the challenge is simply Base64(nip|timestamp|token).
     */
    public String initSession(String nip, String ksefToken, String challenge) {
        // KSeF authByToken: authorisationToken = Base64(SHA-256(token + challenge))
        // For test environment we use simplified token approach
        String authorisationToken = buildAuthorisationToken(ksefToken, challenge);

        var body = """
                {
                  "contextIdentifier": {"type": "onip", "identifier": "%s"},
                  "authorisationToken": "%s"
                }
                """.formatted(nip, authorisationToken).trim();

        var response = restClient.post()
                .uri("/api/online/Session/InitToken")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(KsefDto.InitSessionResponse.class);

        var sessionResp = response.getBody();
        if (sessionResp == null || sessionResp.getSessionToken() == null) {
            throw new KsefException("Failed to initialise KSeF session");
        }
        log.info("KSeF session initialised, ref: {}", sessionResp.getReferenceNumber());
        return sessionResp.getSessionToken().getToken();
    }

    /**
     * Send a FA(2) invoice XML to KSeF.
     * @return elementReferenceNumber for status polling
     */
    public String sendInvoice(String sessionToken, String fa2Xml) {
        String encodedXml = Base64.getEncoder().encodeToString(fa2Xml.getBytes(StandardCharsets.UTF_8));

        var body = """
                {"invoiceHash":{"fileSize":%d,"hashSHA":{"algorithm":"SHA-256","encoding":"Base64","value":"%s"}},
                 "invoicePayload":{"type":"plain","invoiceBody":"%s"}}
                """.formatted(
                fa2Xml.getBytes(StandardCharsets.UTF_8).length,
                sha256Base64(fa2Xml),
                encodedXml
        ).trim();

        var response = restClient.put()
                .uri("/api/online/Invoice/Send")
                .header("SessionToken", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(KsefDto.SendInvoiceResponse.class);

        var resp = response.getBody();
        if (resp == null) throw new KsefException("Empty send invoice response");
        log.info("Invoice sent, elementRef: {}", resp.getElementReferenceNumber());
        return resp.getElementReferenceNumber();
    }

    /**
     * Poll invoice processing status by elementReferenceNumber.
     */
    public KsefDto.InvoiceStatusResponse getInvoiceStatus(String sessionToken, String elementReferenceNumber) {
        var response = restClient.get()
                .uri("/api/online/Invoice/Status/{ref}", elementReferenceNumber)
                .header("SessionToken", sessionToken)
                .retrieve()
                .toEntity(KsefDto.InvoiceStatusResponse.class);
        return response.getBody();
    }

    /**
     * Query received invoices for this NIP.
     */
    public KsefDto.QueryInvoiceResponse queryReceivedInvoices(String sessionToken,
                                                               String dateFrom,
                                                               String dateTo) {
        // TODO: Parametr subjectType="subject3" oznacza faktury wystawione NA nasz NIP
        //       (nabywca = my). Sprawdzić czy to prawidłowe dla odebranych faktur
        //       w aktualnej wersji API KSeF — dokumentacja rozróżnia subject1/subject2/subject3.
        //       Daty dateFrom/dateTo muszą być w formacie ISO-8601 z timezone (np. "2024-01-01T00:00:00Z").
        //       Aktualnie wywołujący (handleFetchInvoices) przekazuje je jako String bez walidacji formatu.
        var body = """
                {"queryCriteria":{"subjectType":"subject3","type":"incremental",
                 "acquisitionTimestampThresholdFrom":"%s","acquisitionTimestampThresholdTo":"%s"}}
                """.formatted(dateFrom, dateTo).trim();

        var response = restClient.post()
                .uri("/api/online/Query/Invoice/Sync")
                .header("SessionToken", sessionToken)
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .toEntity(KsefDto.QueryInvoiceResponse.class);
        return response.getBody();
    }

    /**
     * Terminate session.
     */
    public void terminateSession(String sessionToken) {
        try {
            restClient.get()
                    .uri("/api/online/Session/Terminate")
                    .header("SessionToken", sessionToken)
                    .retrieve()
                    .toBodilessEntity();
            log.info("KSeF session terminated");
        } catch (Exception e) {
            log.warn("Failed to terminate KSeF session: {}", e.getMessage());
        }
    }

    // ---- helpers ----

    private String buildAuthorisationToken(String ksefToken, String challenge) {
        // TODO: Implementacja tokenu jest uproszczona i NIE jest zgodna ze specyfikacją KSeF.
        //       Specyfikacja MF (authByToken) wymaga:
        //         1. Zdekodować token (Base64 → bajty).
        //         2. Pobrać bajty challenge (UTF-8).
        //         3. XOR bajt-po-bajcie: dla i < min(len(token), len(challenge)).
        //         4. SHA-256 wyniku XOR.
        //         5. Zakodować Base64 (Standard, bez padding lub z — sprawdzić docs).
        //       Obecna implementacja Base64(token|challenge) działa WYŁĄCZNIE z mockiem
        //       testowym który nie weryfikuje podpisu. Na środowisku produkcyjnym
        //       lub pełnym środowisku testowym MF zwróci HTTP 401.
        //       Referencja: https://www.podatki.gov.pl/ksef/dokumentacja-techniczna-ksef/
        String combined = ksefToken + "|" + challenge;
        return Base64.getEncoder().encodeToString(combined.getBytes(StandardCharsets.UTF_8));
    }

    private String sha256Base64(String input) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new KsefException("SHA-256 failed", e);
        }
    }
}
