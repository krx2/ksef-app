package pl.ksef.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.ksef.entity.AppUser;

import java.util.Optional;
import java.util.UUID;

public interface UserRepository extends JpaRepository<AppUser, UUID> {
    Optional<AppUser> findByEmail(String email);
}
