package pl.ksef.controller;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.entity.Invoice.InvoiceDirection;
import pl.ksef.entity.Invoice.InvoiceSource;
import pl.ksef.entity.Invoice.InvoiceStatus;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import pl.ksef.entity.AppUser;
import pl.ksef.repository.UserRepository;
import pl.ksef.service.InvoiceService;
import pl.ksef.service.KsefPdfService;
import pl.ksef.service.XlsxConfigService;
import pl.ksef.service.XlsxParserService;
import pl.ksef.service.queue.InvoiceQueuePublisher;

import java.util.Map;
import java.util.UUID;

// SECURITY: Brak autentykacji i autoryzacji — X-User-Id przyjmowany bez weryfikacji.
//   Przed wdrożeniem produkcyjnym dodać Spring Security + JWT i usunąć nagłówek X-User-Id.
@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
@Validated
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final XlsxParserService xlsxParserService;
    private final XlsxConfigService xlsxConfigService;
    private final InvoiceQueuePublisher queuePublisher;
    private final UserRepository userRepository;
    // TODO(F7): Wstrzyknąć KsefPdfService gdy ksef-pdf-generator zostanie zintegrowany.
    //   private final KsefPdfService ksefPdfService;

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
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(200) int size) {
        if (issueDateFrom != null && issueDateTo != null && issueDateFrom.isAfter(issueDateTo)) {
            throw new IllegalArgumentException(
                    "issueDateFrom (" + issueDateFrom + ") nie może być późniejsze niż issueDateTo (" + issueDateTo + ")");
        }
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

    /**
     * Ręczne wyzwolenie pobierania faktur przychodzącej z KSeF dla zalogowanego użytkownika.
     * Publikuje FetchInvoicesMessage do kolejki (okno: ostatnie 24h).
     * Zwraca 202 Accepted natychmiast — faktyczne pobieranie odbywa się asynchronicznie.
     */
    @PostMapping("/fetch")
    public ResponseEntity<Map<String, String>> fetchFromKsef(
            @RequestHeader("X-User-Id") UUID userId) {

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + userId));

        if (user.getKsefToken() == null || user.getKsefToken().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Brak tokenu KSeF — skonfiguruj go w ustawieniach konta"));
        }

        String dateTo   = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        String dateFrom = LocalDateTime.now().minusHours(24).format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

        // Pobieramy oba kierunki: odebrane (Subject2) i wystawione (Subject1)
        queuePublisher.publishFetchInvoices(userId, dateFrom, dateTo, "Subject2", false);
        queuePublisher.publishFetchInvoices(userId, dateFrom, dateTo, "Subject1", false);

        return ResponseEntity.accepted()
                .body(Map.of("message", "Pobieranie faktur z KSeF zostało zlecone"));
    }

    /**
     * Import historyczny faktur z KSeF — pobiera wszystkie faktury od daty startu systemu
     * (2026-02-01) do bieżącej chwili. Pomija powiadomienia email.
     * Zwraca 202 Accepted natychmiast — faktyczne pobieranie odbywa się asynchronicznie przez RabbitMQ.
     */
    @PostMapping("/fetch-history")
    public ResponseEntity<Map<String, String>> fetchHistoryFromKsef(
            @RequestHeader("X-User-Id") UUID userId) {

        AppUser user = userRepository.findById(userId)
                .orElseThrow(() -> new pl.ksef.exception.ResourceNotFoundException("User not found: " + userId));

        if (user.getKsefToken() == null || user.getKsefToken().isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Brak tokenu KSeF — skonfiguruj go w ustawieniach konta"));
        }

        String dateFrom = LocalDate.parse("2026-02-01").atStartOfDay()
                .format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";
        String dateTo   = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME) + "Z";

        // Dwie wiadomości: odebrane (Subject2) i wystawione (Subject1)
        queuePublisher.publishFetchInvoices(userId, dateFrom, dateTo, "Subject2", true);
        queuePublisher.publishFetchInvoices(userId, dateFrom, dateTo, "Subject1", true);

        return ResponseEntity.accepted()
                .body(Map.of("message", "Import historyczny faktur z KSeF został zlecony"));
    }

    /**
     * TODO(F7): Pobieranie oficjalnego PDF faktury z KSeF.
     *   Endpoint aktywować gdy KsefPdfService.generatePdfBytes() będzie zaimplementowany.
     *
     *   GET /api/invoices/{id}/pdf
     *   - Wymaga statusu SENT i obecności ksefNumber
     *   - Pobiera XML z KSeF → generuje PDF przez ksef-pdf-generator
     *   - Zwraca plik jako attachment z nazwą "faktura-{ksefNumber}.pdf"
     *
     * @GetMapping("/{id}/pdf")
     * public ResponseEntity<byte[]> downloadPdf(
     *         @RequestHeader("X-User-Id") UUID userId,
     *         @PathVariable UUID id) {
     *     byte[] pdf = ksefPdfService.generatePdfForInvoice(id, userId);
     *     String ksefNumber = invoiceService.getById(id, userId).getKsefNumber();
     *     String filename = "faktura-" + (ksefNumber != null ? ksefNumber : id) + ".pdf";
     *     HttpHeaders headers = new HttpHeaders();
     *     headers.setContentType(MediaType.APPLICATION_PDF);
     *     headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
     *     headers.setContentLength(pdf.length);
     *     return ResponseEntity.ok().headers(headers).body(pdf);
     * }
     */

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
