package pl.ksef.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.ksef.entity.UserNotificationEmail;
import pl.ksef.exception.ResourceNotFoundException;
import pl.ksef.repository.UserNotificationEmailRepository;

import java.util.List;
import java.util.UUID;

/**
 * Kontroler zarządzania adresami email do powiadomień.
 *
 * Endpointy:
 *   GET    /api/notification-emails          — lista adresów użytkownika
 *   POST   /api/notification-emails          — dodanie nowego adresu
 *   PUT    /api/notification-emails/{id}     — edycja etykiety / kolejności
 *   DELETE /api/notification-emails/{id}     — usunięcie adresu
 *
 * SECURITY: userId pobierany z nagłówka X-User-Id bez weryfikacji — dodać JWT przed wdrożeniem.
 */
@RestController
@RequestMapping("/api/notification-emails")
@RequiredArgsConstructor
public class NotificationEmailController {

    private final UserNotificationEmailRepository notificationEmailRepository;

    /**
     * GET /api/notification-emails
     * Zwraca listę adresów email do powiadomień dla użytkownika, posortowaną wg sort_order.
     */
    @GetMapping
    public ResponseEntity<List<NotificationEmailResponse>> list(
            @RequestHeader("X-User-Id") UUID userId) {

        List<NotificationEmailResponse> result = notificationEmailRepository
                .findByUserIdOrderBySortOrderAsc(userId)
                .stream()
                .map(NotificationEmailResponse::from)
                .toList();

        return ResponseEntity.ok(result);
    }

    /**
     * POST /api/notification-emails
     * Dodaje nowy adres email do listy powiadomień.
     * Zwraca 400 gdy email już istnieje na liście użytkownika.
     */
    @PostMapping
    public ResponseEntity<?> add(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody AddEmailRequest req) {

        if (notificationEmailRepository.existsByUserIdAndEmail(userId, req.getEmail())) {
            return ResponseEntity.badRequest()
                    .body(java.util.Map.of("error",
                            "Adres " + req.getEmail() + " jest już na liście powiadomień"));
        }

        // sort_order = max(istniejące) + 1, żeby nowy adres był na końcu listy
        long count = notificationEmailRepository.countByUserId(userId);

        UserNotificationEmail entry = UserNotificationEmail.builder()
                .userId(userId)
                .email(req.getEmail().trim().toLowerCase())
                .label(req.getLabel() != null ? req.getLabel().trim() : null)
                .sortOrder((int) count)
                .build();

        return ResponseEntity.ok(NotificationEmailResponse.from(
                notificationEmailRepository.save(entry)));
    }

    /**
     * PUT /api/notification-emails/{id}
     * Aktualizuje etykietę i/lub kolejność adresu.
     * Nie pozwala zmienić samego adresu email — usuń i dodaj nowy.
     */
    @PutMapping("/{id}")
    public ResponseEntity<?> update(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody UpdateEmailRequest req) {

        UserNotificationEmail entry = notificationEmailRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification email not found: " + id));

        if (req.getLabel() != null) {
            entry.setLabel(req.getLabel().trim().isEmpty() ? null : req.getLabel().trim());
        }
        if (req.getSortOrder() != null) {
            entry.setSortOrder(req.getSortOrder());
        }

        return ResponseEntity.ok(NotificationEmailResponse.from(
                notificationEmailRepository.save(entry)));
    }

    /**
     * DELETE /api/notification-emails/{id}
     * Usuwa adres z listy powiadomień. Zwraca 404 gdy nie należy do użytkownika.
     */
    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id) {

        UserNotificationEmail entry = notificationEmailRepository.findByIdAndUserId(id, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Notification email not found: " + id));

        notificationEmailRepository.delete(entry);
        return ResponseEntity.noContent().build();
    }

    // ---- DTOs ----

    @Data
    public static class AddEmailRequest {
        @NotBlank @Email(message = "Nieprawidłowy format adresu email")
        private String email;

        @Size(max = 100, message = "Etykieta nie może być dłuższa niż 100 znaków")
        private String label; // opcjonalna, np. "Biuro rachunkowe"
    }

    @Data
    public static class UpdateEmailRequest {
        @Size(max = 100)
        private String label;      // null = bez zmian
        private Integer sortOrder; // null = bez zmian
    }

    @Data
    public static class NotificationEmailResponse {
        private UUID id;
        private String email;
        private String label;
        private int sortOrder;
        private String createdAt;

        static NotificationEmailResponse from(UserNotificationEmail e) {
            var r = new NotificationEmailResponse();
            r.setId(e.getId());
            r.setEmail(e.getEmail());
            r.setLabel(e.getLabel());
            r.setSortOrder(e.getSortOrder());
            r.setCreatedAt(e.getCreatedAt() != null ? e.getCreatedAt().toString() : null);
            return r;
        }
    }
}
