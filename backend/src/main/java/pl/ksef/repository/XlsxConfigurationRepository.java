package pl.ksef.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.ksef.entity.XlsxConfiguration;

import java.util.List;
import java.util.UUID;

public interface XlsxConfigurationRepository extends JpaRepository<XlsxConfiguration, UUID> {
    List<XlsxConfiguration> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
