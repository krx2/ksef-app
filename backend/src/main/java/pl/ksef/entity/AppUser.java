package pl.ksef.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "users")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class AppUser {

    public enum InvoiceNumberPrefixMode {
        /** Numer faktury bez prefiksu — domyślne zachowanie. */
        NONE,
        /** Numer faktury poprzedzony rokiem i miesiącem wystawienia, np. "2026/04/1". */
        YEAR_MONTH
    }

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true)
    private String email;

    @Column(nullable = false, length = 10)
    private String nip;

    @Column(name = "company_name", nullable = false)
    private String companyName;

    /** Stały token KSeF użytkownika — używany wyłącznie do inicjalizacji uwierzytelnienia. */
    @Column(name = "ksef_token", columnDefinition = "TEXT")
    private String ksefToken;

    // ---- KSeF API v2: JWT tokeny dostępowe ----

    /** Krótkotrwały JWT bearer token do wywołań API KSeF v2. */
    @Column(name = "ksef_access_token", columnDefinition = "TEXT")
    private String ksefAccessToken;

    /** Data ważności access tokenu (UTC). Odnów przez /auth/token/refresh zanim wygaśnie. */
    @Column(name = "ksef_access_token_valid_until")
    private LocalDateTime ksefAccessTokenValidUntil;

    /** Długotrwały refresh token do odnawiania access tokenu przez /auth/token/refresh. */
    @Column(name = "ksef_refresh_token", columnDefinition = "TEXT")
    private String ksefRefreshToken;

    /** Data ważności refresh tokenu (UTC). Po wygaśnięciu wymagany pełny re-auth. */
    @Column(name = "ksef_refresh_token_valid_until")
    private LocalDateTime ksefRefreshTokenValidUntil;

    @Column(name = "invoice_number_prefix_mode", nullable = false)
    @Enumerated(EnumType.STRING)
    @Builder.Default
    private InvoiceNumberPrefixMode invoicePrefixMode = InvoiceNumberPrefixMode.NONE;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;
}
