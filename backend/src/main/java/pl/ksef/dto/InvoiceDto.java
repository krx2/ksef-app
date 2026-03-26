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

        @NotBlank private String sellerName;
        @NotBlank @Size(min=10,max=10) private String sellerNip;
        private String sellerAddress;

        @NotBlank private String buyerName;
        @NotBlank @Size(min=10,max=10) private String buyerNip;
        private String buyerAddress;

        private String currency = "PLN";

        @NotEmpty @Valid
        private List<ItemRequest> items;
    }

    @Data
    public static class ItemRequest {
        @NotBlank private String name;
        private String unit;
        @NotNull @Positive private BigDecimal quantity;
        @NotNull @Positive private BigDecimal netUnitPrice;
        @NotNull @DecimalMin("0") @DecimalMax("100") private BigDecimal vatRate;
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
        private String buyerName;
        private String buyerNip;
        private BigDecimal netAmount;
        private BigDecimal vatAmount;
        private BigDecimal grossAmount;
        private String currency;
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
