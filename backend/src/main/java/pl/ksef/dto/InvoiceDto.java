package pl.ksef.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import lombok.Data;
import pl.ksef.entity.Invoice;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public class InvoiceDto {

    @Data
    public static class CreateRequest {
        @NotBlank
        private String invoiceNumber;

        @NotNull
        private LocalDate issueDate;

        private LocalDate saleDate;

        @NotBlank
        private String sellerName;

        @NotBlank @Pattern(regexp = "\\d{10}", message = "NIP musi składać się z 10 cyfr")
        private String sellerNip;

        /** Adres sprzedawcy — wymagany w FA(3) (pole AdresL1 w Podmiot1.Adres) */
        @NotBlank(message = "Adres sprzedawcy jest wymagany w FA(3)")
        private String sellerAddress;

        /** Kod kraju sprzedawcy (KodKraju) — domyślnie PL */
        @Pattern(regexp = "[A-Z]{2}", message = "Kod kraju musi składać się z 2 liter")
        private String sellerCountryCode = "PL";

        @NotBlank
        private String buyerName;

        @NotBlank @Pattern(regexp = "\\d{10}", message = "NIP musi składać się z 10 cyfr")
        private String buyerNip;

        private String buyerAddress;

        /** Kod kraju nabywcy (KodKraju) — domyślnie PL */
        @Pattern(regexp = "[A-Z]{2}", message = "Kod kraju musi składać się z 2 liter")
        private String buyerCountryCode = "PL";

        /** Kod waluty ISO 4217 */
        private String currency = "PLN";

        /**
         * Rodzaj faktury FA(3): VAT | KOR | ZAL | ROZ | UPR | KOR_ZAL | KOR_ROZ
         * Domyślnie VAT (faktura podstawowa).
         */
        @Pattern(regexp = "VAT|KOR|ZAL|ROZ|UPR|KOR_ZAL|KOR_ROZ",
                 message = "Nieprawidłowy rodzaj faktury. Dozwolone: VAT, KOR, ZAL, ROZ, UPR, KOR_ZAL, KOR_ROZ")
        private String rodzajFaktury = "VAT";

        /** P_16: metoda kasowa (1=tak, 2=nie) */
        private boolean metodaKasowa = false;

        /** P_17: samofakturowanie */
        private boolean samofakturowanie = false;

        /** P_18: odwrotne obciążenie */
        private boolean odwrotneObciazenie = false;

        /** P_18A: mechanizm podzielonej płatności */
        private boolean mechanizmPodzielonejPlatnosci = false;

        /**
         * Podstawa prawna zwolnienia z VAT (P_19 FA(3)).
         * Wymagana gdy co najmniej jedna pozycja ma vatRateCode="zw".
         * Przykład: "art. 43 ust. 1 pkt 1 ustawy"
         */
        private String zwolnieniePodatkowe;

        /** Podmiot2.JST — faktura dotyczy jednostki podrzędnej JST (domyślnie false). */
        private boolean jst = false;

        /** Podmiot2.GV — faktura dotyczy członka grupy VAT (domyślnie false). */
        private boolean gv = false;

        /** Platnosc/TerminPlatnosci/Termin — termin zapłaty. */
        private LocalDate paymentDueDate;

        /** Platnosc/RachunekBankowy/NrRB — numer rachunku bankowego sprzedawcy (max 35 znaków). */
        @Size(max = 35)
        private String bankAccountNumber;

        /** Platnosc/RachunekBankowy/NazwaBanku — nazwa banku. */
        private String bankName;

        @NotEmpty @Valid
        private List<ItemRequest> items;
    }

    @Data
    public static class ItemRequest {
        @NotBlank
        private String name;

        private String unit;

        @NotNull @Positive
        private BigDecimal quantity;

        @NotNull @Positive
        private BigDecimal netUnitPrice;

        /** Stawka VAT numeryczna (0–100). Używana do obliczeń i jako domyślny kod FA(3). */
        @NotNull @DecimalMin("0") @DecimalMax("100")
        private BigDecimal vatRate;

        /**
         * Opcjonalny kod stawki VAT zgodny z FA(3) TStawkaPodatku.
         * Jeśli podany, zastępuje vatRate w XML.
         * Dozwolone: "23","22","8","7","5","4","3","0 KR","0 WDT","0 EX","zw","oo","np I","np II"
         */
        @Pattern(regexp = "23|22|8|7|5|4|3|0 KR|0 WDT|0 EX|zw|oo|np I|np II",
                 message = "Nieprawidłowy kod stawki VAT FA(3)")
        private String vatRateCode;
    }

    @Data
    public static class Response {
        private UUID id;
        private String ksefNumber;
        private String ksefReferenceNumber;
        private Invoice.InvoiceDirection direction;
        private Invoice.InvoiceStatus status;
        private String invoiceNumber;
        private LocalDate issueDate;
        private LocalDate saleDate;
        private String sellerName;
        private String sellerNip;
        private String sellerAddress;
        private String sellerCountryCode;
        private String buyerName;
        private String buyerNip;
        private String buyerAddress;
        private String buyerCountryCode;
        private BigDecimal netAmount;
        private BigDecimal vatAmount;
        private BigDecimal grossAmount;
        private String currency;
        private String rodzajFaktury;
        private boolean metodaKasowa;
        private boolean samofakturowanie;
        private boolean odwrotneObciazenie;
        private boolean mechanizmPodzielonejPlatnosci;
        private String zwolnieniePodatkowe;
        private boolean jst;
        private boolean gv;
        private LocalDate paymentDueDate;
        private String bankAccountNumber;
        private String bankName;
        private String errorMessage;
        private Invoice.InvoiceSource source;
        private List<ItemResponse> items;
        private LocalDateTime createdAt;
        private LocalDateTime updatedAt;
    }

    @Data
    public static class ItemResponse {
        private UUID id;
        private String name;
        private String unit;
        private BigDecimal quantity;
        private BigDecimal netUnitPrice;
        private BigDecimal vatRate;
        private String vatRateCode;
        private BigDecimal netAmount;
        private BigDecimal vatAmount;
        private BigDecimal grossAmount;
        private Integer position;
    }

    @Data
    public static class PageResponse {
        private List<Response> content;
        private int page;
        private int size;
        private long totalElements;
        private int totalPages;
    }
}
