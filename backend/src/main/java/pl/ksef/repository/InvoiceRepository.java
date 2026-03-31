package pl.ksef.repository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import pl.ksef.entity.Invoice;
import pl.ksef.entity.Invoice.InvoiceDirection;
import pl.ksef.entity.Invoice.InvoiceStatus;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface InvoiceRepository extends JpaRepository<Invoice, UUID>,
        JpaSpecificationExecutor<Invoice> {

    Page<Invoice> findByUserIdOrderByCreatedAtDesc(UUID userId, Pageable pageable);

    Page<Invoice> findByUserIdAndDirectionOrderByCreatedAtDesc(UUID userId, InvoiceDirection direction, Pageable pageable);

    List<Invoice> findByStatus(InvoiceStatus status);

    Optional<Invoice> findByKsefNumber(String ksefNumber);

    long countByUserIdAndDirection(UUID userId, InvoiceDirection direction);
}
