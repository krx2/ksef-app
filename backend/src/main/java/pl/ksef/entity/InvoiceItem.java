package pl.ksef.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "invoice_items")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class InvoiceItem {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_id", nullable = false)
    private Invoice invoice;

    @Column(nullable = false)
    private String name;

    private String unit;

    @Column(nullable = false)
    private BigDecimal quantity = BigDecimal.ONE;

    @Column(name = "net_unit_price", nullable = false)
    private BigDecimal netUnitPrice;

    @Column(name = "vat_rate", nullable = false)
    private BigDecimal vatRate = BigDecimal.valueOf(23);

    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount;

    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount;

    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount;

    @Column(nullable = false)
    private Integer position = 1;
}
