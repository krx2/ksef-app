package pl.ksef.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "invoices")
@Getter @Setter @NoArgsConstructor @AllArgsConstructor @Builder
public class Invoice {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "ksef_number")
    private String ksefNumber;

    @Column(name = "ksef_reference_number")
    private String ksefReferenceNumber;

    /**
     * Skrót SHA-256 faktury (Base64) zwracany przez KSeF w SessionInvoiceStatusResponse.invoiceHash
     * i QueryMetadataResponse.InvoiceMetadata.invoiceHash.
     * Używany jako ostatni segment URL wizualizacji KSeF v2:
     * https://qr[-test].ksef.mf.gov.pl/invoice/{nip}/{DD-MM-YYYY}/{invoiceHash}
     */
    @Column(name = "invoice_hash")
    private String invoiceHash;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceDirection direction;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceStatus status = InvoiceStatus.DRAFT;

    @Column(name = "invoice_number")
    private String invoiceNumber;

    @Column(name = "issue_date", nullable = false)
    private LocalDate issueDate;

    @Column(name = "sale_date")
    private LocalDate saleDate;

    @Column(name = "seller_name", nullable = false)
    private String sellerName;

    @Column(name = "seller_nip", nullable = false)
    private String sellerNip;

    @Column(name = "seller_address")
    private String sellerAddress;

    @Column(name = "buyer_name", nullable = false)
    private String buyerName;

    @Column(name = "buyer_nip", nullable = false)
    private String buyerNip;

    @Column(name = "buyer_address")
    private String buyerAddress;

    @Column(name = "net_amount", nullable = false)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "vat_amount", nullable = false)
    private BigDecimal vatAmount = BigDecimal.ZERO;

    @Column(name = "gross_amount", nullable = false)
    private BigDecimal grossAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private String currency = "PLN";

    @Column(name = "fa2_xml", columnDefinition = "TEXT")
    private String fa2Xml;

    @Column(name = "error_message")
    private String errorMessage;

    @Builder.Default
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private InvoiceSource source = InvoiceSource.FORM;

    // FA(3) fields
    @Column(name = "rodzaj_faktury", nullable = false)
    private String rodzajFaktury = "VAT";

    @Column(name = "seller_country_code", nullable = false)
    private String sellerCountryCode = "PL";

    @Column(name = "buyer_country_code", nullable = false)
    private String buyerCountryCode = "PL";

    @Column(name = "metoda_kasowa", nullable = false)
    private boolean metodaKasowa = false;

    @Column(name = "samofakturowanie", nullable = false)
    private boolean samofakturowanie = false;

    @Column(name = "odwrotne_obciazenie", nullable = false)
    private boolean odwrotneObciazenie = false;

    @Column(name = "mechanizm_podzielonej_platnosci", nullable = false)
    private boolean mechanizmPodzielonejPlatnosci = false;

    /** Podstawa prawna zwolnienia (P_19 FA(3)). Wymagana gdy pozycje mają vatRateCode="zw". */
    @Column(name = "zwolnienie_podatkowe", columnDefinition = "TEXT")
    private String zwolnieniePodatkowe;

    /** Podmiot2.JST — czy faktura dotyczy jednostki podrzędnej JST. */
    @Column(name = "jst", nullable = false)
    private boolean jst = false;

    /** Podmiot2.GV — czy faktura dotyczy członka grupy VAT. */
    @Column(name = "gv", nullable = false)
    private boolean gv = false;

    @OneToMany(mappedBy = "invoice", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC")
    @Builder.Default
    private List<InvoiceItem> items = new ArrayList<>();

    @PrePersist
    void prePersist() {
        if (source         == null) source         = InvoiceSource.FORM;
        if (status         == null) status         = InvoiceStatus.DRAFT;
        if (direction      == null) direction      = InvoiceDirection.ISSUED;
        if (currency       == null) currency       = "PLN";
        if (rodzajFaktury  == null) rodzajFaktury  = "VAT";
        if (sellerCountryCode == null) sellerCountryCode = "PL";
        if (buyerCountryCode  == null) buyerCountryCode  = "PL";
        if (netAmount      == null) netAmount      = BigDecimal.ZERO;
        if (vatAmount      == null) vatAmount      = BigDecimal.ZERO;
        if (grossAmount    == null) grossAmount    = BigDecimal.ZERO;
    }

    @CreationTimestamp
    @Column(name = "created_at", updatable = false)
    private LocalDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at")
    private LocalDateTime updatedAt;

    public enum InvoiceDirection { ISSUED, RECEIVED }
    public enum InvoiceStatus    { DRAFT, QUEUED, SENDING, SENT, FAILED, RECEIVED_FROM_KSEF }
    public enum InvoiceSource    { FORM, XLSX, KSEF }
}
