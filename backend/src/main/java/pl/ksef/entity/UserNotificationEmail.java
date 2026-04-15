package pl.ksef.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;

import java.time.LocalDateTime;
import java.util.UUID;

/**
 * Adres email do powiadomień — jeden użytkownik może mieć ich wiele.
 * Migracja DB: V6__user_notification_emails.sql
 */
@Entity
@Table(
    name = "user_notification_emails",
    uniqueConstraints = @UniqueConstraint(
        name = "uq_user_notification_email",
        columnNames = {"user_id", "email"}
    )
)
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class UserNotificationEmail {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    /** Adres email odbiorcy powiadomień. */
    @Column(nullable = false, length = 255)
    private String email;

    /**
     * Opcjonalna etykieta ułatwiająca identyfikację adresu w UI.
     * Przykłady: "Biuro rachunkowe", "Właściciel", "Dział finansowy"
     */
    @Column(length = 100)
    private String label;

    /** Kolejność wyświetlania na liście w UI. */
    @Column(name = "sort_order", nullable = false)
    @Builder.Default
    private int sortOrder = 0;

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;
}
