package pl.ksef.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.ksef.entity.UserNotificationEmail;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Repozytorium adresów email do powiadomień. */
public interface UserNotificationEmailRepository extends JpaRepository<UserNotificationEmail, UUID> {

    /** Wszystkie adresy powiadomień użytkownika, posortowane wg sort_order ASC. */
    List<UserNotificationEmail> findByUserIdOrderBySortOrderAsc(UUID userId);

    /** Sprawdza czy dany email jest już na liście użytkownika (constraint unique). */
    boolean existsByUserIdAndEmail(UUID userId, String email);

    /** Pobiera konkretny wpis — do edycji/usuwania z weryfikacją właściciela. */
    Optional<UserNotificationEmail> findByIdAndUserId(UUID id, UUID userId);

    /** Liczba adresów powiadomień użytkownika — przydatna do oceny czy fallback potrzebny. */
    long countByUserId(UUID userId);
}
