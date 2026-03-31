package pl.ksef.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.entity.Invoice.InvoiceDirection;
import pl.ksef.entity.Invoice.InvoiceSource;
import pl.ksef.entity.Invoice.InvoiceStatus;

import java.time.LocalDate;
import pl.ksef.service.InvoiceService;
import pl.ksef.service.XlsxConfigService;
import pl.ksef.service.XlsxParserService;

import java.util.UUID;

// TODO: Brak mechanizmu autentykacji i autoryzacji.
//       Nagłówek X-User-Id jest przyjmowany bez weryfikacji — każdy klient może podać dowolny UUID
//       i działać w kontekście innego użytkownika. Przed wdrożeniem produkcyjnym należy:
//       1. Dodać Spring Security z JWT (lub session cookies).
//       2. Usunąć X-User-Id z nagłówka — userId pobierać z tokenu JWT (Principal / SecurityContext).
//       3. Dodać @PreAuthorize lub filter weryfikujący, że userId z tokenu == userId w żądaniu.
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final XlsxParserService xlsxParserService;
    private final XlsxConfigService xlsxConfigService;

    /**
     * Pobiera paginowaną listę faktur użytkownika z opcjonalnym filtrowaniem.
     *
     * @param direction      ISSUED | RECEIVED; pominięcie = wszystkie
     * @param search         wyszukiwanie tekstowe (nr faktury, nazwa/NIP sprzedawcy lub nabywcy)
     * @param status         DRAFT | QUEUED | SENDING | SENT | FAILED | RECEIVED_FROM_KSEF
     * @param rodzajFaktury  VAT | KOR | ZAL | ROZ | UPR | KOR_ZAL | KOR_ROZ
     * @param issueDateFrom  data wystawienia od (YYYY-MM-DD, włącznie)
     * @param issueDateTo    data wystawienia do (YYYY-MM-DD, włącznie)
     */
    @GetMapping
    public ResponseEntity<InvoiceDto.PageResponse> list(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) InvoiceDirection direction,
            @RequestParam(required = false) String search,
            @RequestParam(required = false) InvoiceStatus status,
            @RequestParam(required = false) String rodzajFaktury,
            @RequestParam(required = false) LocalDate issueDateFrom,
            @RequestParam(required = false) LocalDate issueDateTo,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.listByUser(
                userId, direction, search, status, rodzajFaktury, issueDateFrom, issueDateTo, page, size));
    }

    /** Get single invoice */
    @GetMapping("/{id}")
    public ResponseEntity<InvoiceDto.Response> get(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(invoiceService.getById(id, userId));
    }

    /** Create invoice from form and immediately queue for KSeF */
    @PostMapping
    public ResponseEntity<InvoiceDto.Response> createFromForm(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody InvoiceDto.CreateRequest request) {
        var invoice = invoiceService.createAndQueue(userId, request);
        return ResponseEntity.ok(invoiceService.toResponse(invoice));
    }

    /** Upload XLSX and create invoice using saved configuration */
    @PostMapping("/from-xlsx")
    public ResponseEntity<InvoiceDto.Response> createFromXlsx(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("configId") UUID configId) throws Exception {

        var config = xlsxConfigService.getById(configId, userId);
        InvoiceDto.CreateRequest req = xlsxParserService.parseWithConfig(file, config.getFieldMappings());
        var invoice = invoiceService.createAndQueue(userId, req, InvoiceSource.XLSX);
        return ResponseEntity.ok(invoiceService.toResponse(invoice));
    }

    /** Save invoice as draft without FA(3) validation or queuing */
    @PostMapping("/draft")
    public ResponseEntity<InvoiceDto.Response> createDraft(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody InvoiceDto.CreateRequest request) {
        var invoice = invoiceService.createDraft(userId, request);
        return ResponseEntity.ok(invoiceService.toResponse(invoice));
    }

    /** Queue a DRAFT invoice for KSeF send after FA(3) validation */
    @PostMapping("/{id}/send")
    public ResponseEntity<InvoiceDto.Response> sendDraft(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id) {
        var invoice = invoiceService.sendDraft(id, userId);
        return ResponseEntity.ok(invoiceService.toResponse(invoice));
    }

    /** Preview XLSX parse result without creating invoice */
    @PostMapping("/xlsx-preview")
    public ResponseEntity<java.util.Map<String, String>> previewXlsx(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("configId") UUID configId) throws Exception {

        var config = xlsxConfigService.getById(configId, userId);
        return ResponseEntity.ok(xlsxParserService.previewFields(file, config.getFieldMappings()));
    }
}
