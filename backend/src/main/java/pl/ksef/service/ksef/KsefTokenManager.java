package pl.ksef.service.ksef;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.ksef.dto.KsefDto;
import pl.ksef.entity.AppUser;
import pl.ksef.repository.UserRepository;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;

/**
 * Zarządza cyklem życia tokenów dostępowych KSeF v2 dla AppUser.
 *
 * <p>Strategia odświeżania (w kolejności preferencji):
 * <ol>
 *   <li>Cache hit — accessToken ważny przez co najmniej 30 sekund → zwróć bezpośrednio.</li>
 *   <li>Refresh — accessToken wygasł, ale refreshToken ważny → POST /auth/token/refresh.</li>
 *   <li>Pełny re-auth — oba tokeny wygasłe → 5-krokowy przepływ uwierzytelnienia.</li>
 * </ol>
 *
 * <p>Nowe tokeny są zawsze zapisywane w bazie danych przez {@link UserRepository}.
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class KsefTokenManager {

    /** Bufor bezpieczeństwa — odnów token jeśli wygasa w ciągu 30 sekund. */
    private static final int BUFFER_SECONDS = 30;

    /** Maksymalna liczba prób pollingu statusu uwierzytelnienia (co 2 sekundy = 30s łącznie). */
    private static final int MAX_AUTH_POLL_ATTEMPTS = 15;

    /** Opóźnienie między próbami pollingu w ms. */
    private static final long POLL_INTERVAL_MS = 2_000L;

    private final KsefApiClient ksefApiClient;
    private final KsefEncryptionService encryptionService;
    private final UserRepository userRepository;

    /**
     * Zwraca ważny access token dla użytkownika.
     * Odświeża lub wykonuje pełny re-auth w razie potrzeby.
     *
     * @param user AppUser z tokenami w bazie danych
     * @return ważny JWT access token gotowy do użycia w nagłówku Authorization: Bearer
     * @throws KsefException jeśli brak tokenu KSeF lub uwierzytelnienie nieudane
     */
    public synchronized String getValidAccessToken(AppUser user) {
        if (user.getKsefToken() == null || user.getKsefToken().isBlank()) {
            throw new KsefException("Brak tokenu KSeF dla użytkownika " + user.getId()
                    + " — skonfiguruj token w ustawieniach konta");
        }

        // 1. Cache hit — accessToken ważny
        if (isAccessTokenValid(user)) {
            log.debug("accessToken ważny do {} — cache hit", user.getKsefAccessTokenValidUntil());
            return user.getKsefAccessToken();
        }

        // 2. Refresh — refreshToken ważny
        if (isRefreshTokenValid(user)) {
            log.info("accessToken wygasł — próba odświeżenia przez /auth/token/refresh");
            return refreshAccessToken(user);
        }

        // 3. Pełny re-auth
        log.info("Oba tokeny wygasłe — rozpoczynam pełne uwierzytelnienie KSeF dla użytkownika {}",
                user.getId());
        return fullAuthentication(user);
    }

    // ---- private helpers ----

    private boolean isAccessTokenValid(AppUser user) {
        return user.getKsefAccessToken() != null
                && user.getKsefAccessTokenValidUntil() != null
                && user.getKsefAccessTokenValidUntil()
                        .isAfter(LocalDateTime.now().plusSeconds(BUFFER_SECONDS));
    }

    private boolean isRefreshTokenValid(AppUser user) {
        return user.getKsefRefreshToken() != null
                && user.getKsefRefreshTokenValidUntil() != null
                && user.getKsefRefreshTokenValidUntil()
                        .isAfter(LocalDateTime.now().plusSeconds(BUFFER_SECONDS));
    }

    /**
     * Odświeża access token przez POST /auth/token/refresh.
     * Zapisuje nowy accessToken w bazie danych.
     */
    private String refreshAccessToken(AppUser user) {
        try {
            KsefDto.AuthenticationTokenRefreshResponse resp =
                    ksefApiClient.refreshAccessToken(user.getKsefRefreshToken());

            String newAccessToken = resp.getAccessToken().getToken();
            LocalDateTime newValidUntil = parseValidUntil(resp.getAccessToken().getValidUntil());

            user.setKsefAccessToken(newAccessToken);
            user.setKsefAccessTokenValidUntil(newValidUntil);
            userRepository.save(user);

            log.info("accessToken odświeżony, ważny do: {}", newValidUntil);
            return newAccessToken;

        } catch (KsefException e) {
            log.warn("Odświeżenie tokenu nieudane ({}), próba pełnego re-auth", e.getMessage());
            return fullAuthentication(user);
        }
    }

    /**
     * Wykonuje pełny 5-krokowy przepływ uwierzytelnienia KSeF:
     * <ol>
     *   <li>POST /auth/challenge</li>
     *   <li>POST /auth/ksef-token (z zaszyfrowanym tokenem RSA-OAEP)</li>
     *   <li>GET /auth/{ref} — polling aż status.code == 200</li>
     *   <li>POST /auth/token/redeem — jednorazowe odebranie tokenów</li>
     *   <li>Zapis access + refresh token do bazy danych</li>
     * </ol>
     */
    private String fullAuthentication(AppUser user) {
        // Krok 1: Pobierz challenge
        KsefDto.AuthChallengeResponse challengeResp = ksefApiClient.getChallenge();
        String challenge = challengeResp.getChallenge();
        long timestampMs = challengeResp.getTimestampMs();

        // Krok 2: Zaszyfruj token i zainicjuj auth
        String encryptedToken = encryptionService.encryptToken(user.getKsefToken(), timestampMs);
        KsefDto.AuthenticationInitResponse initResp =
                ksefApiClient.initTokenAuth(challenge, user.getNip(), encryptedToken);

        String referenceNumber = initResp.getReferenceNumber();
        String operationToken = initResp.getAuthenticationToken().getToken();

        // Krok 3: Polling statusu — czekaj na kod 200
        pollAuthStatus(referenceNumber, operationToken);

        // Krok 4: Odbierz tokeny (jednorazowe!)
        KsefDto.AuthenticationTokensResponse tokensResp = ksefApiClient.redeemTokens(operationToken);

        String accessToken = tokensResp.getAccessToken().getToken();
        LocalDateTime accessValidUntil = parseValidUntil(tokensResp.getAccessToken().getValidUntil());
        String refreshToken = tokensResp.getRefreshToken().getToken();
        LocalDateTime refreshValidUntil = parseValidUntil(tokensResp.getRefreshToken().getValidUntil());

        // Krok 5: Zapisz tokeny do bazy danych
        user.setKsefAccessToken(accessToken);
        user.setKsefAccessTokenValidUntil(accessValidUntil);
        user.setKsefRefreshToken(refreshToken);
        user.setKsefRefreshTokenValidUntil(refreshValidUntil);
        userRepository.save(user);

        log.info("Pełne uwierzytelnienie KSeF zakończone sukcesem dla użytkownika {}, " +
                "accessToken ważny do: {}", user.getId(), accessValidUntil);
        return accessToken;
    }

    /**
     * Polling GET /auth/{referenceNumber} aż status.code == 200 (maks. 15 prób × 2s = 30s).
     *
     * @throws KsefException jeśli status.code == 400 lub przekroczono limit prób
     */
    private void pollAuthStatus(String referenceNumber, String operationToken) {
        for (int attempt = 1; attempt <= MAX_AUTH_POLL_ATTEMPTS; attempt++) {
            try {
                Thread.sleep(POLL_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new KsefException("Przerwano oczekiwanie na potwierdzenie auth KSeF");
            }

            KsefDto.AuthenticationOperationStatusResponse status =
                    ksefApiClient.getAuthStatus(referenceNumber, operationToken);

            int code = status.getStatus().getCode();
            log.debug("Auth polling attempt {}/{}: status.code={}", attempt, MAX_AUTH_POLL_ATTEMPTS, code);

            if (code == 200) {
                log.info("Uwierzytelnienie KSeF potwierdzone (referenceNumber={})", referenceNumber);
                return;
            }
            if (code == 400) {
                String desc = status.getStatus().getDescription();
                throw new KsefException("Uwierzytelnienie KSeF odrzucone (code=400): " + desc);
            }
            // code == 100 → w toku, kontynuuj polling
        }

        throw new KsefException("Timeout uwierzytelnienia KSeF — brak potwierdzenia po "
                + MAX_AUTH_POLL_ATTEMPTS + " próbach (referenceNumber=" + referenceNumber + ")");
    }

    /**
     * Parsuje ISO-8601 datetime z timezone do LocalDateTime (UTC).
     * Przykład wejścia: "2025-05-14T12:30:00+02:00"
     */
    private LocalDateTime parseValidUntil(String validUntil) {
        try {
            return OffsetDateTime.parse(validUntil).toLocalDateTime();
        } catch (Exception e) {
            log.warn("Nie można sparsować validUntil '{}': {}", validUntil, e.getMessage());
            // Fallback: 1 godzina od teraz — token zostanie odświeżony przy następnym wywołaniu
            return LocalDateTime.now().plusHours(1);
        }
    }
}
