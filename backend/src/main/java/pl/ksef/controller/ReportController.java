package pl.ksef.controller;

import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.service.InvoiceService;
import pl.ksef.service.MonthlyReportService;

import java.time.YearMonth;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Kontroler raportów miesięcznych faktur.
 *
 * SECURITY: userId pobierany z nagłówka X-User-Id bez weryfikacji — dodać JWT przed wdrożeniem.
 */
@RestController
@RequestMapping("/api/reports")
@RequiredArgsConstructor
public class ReportController {

    private final MonthlyReportService monthlyReportService;
    private final InvoiceService invoiceService;

    /**
     * Pobiera listę faktur z danego miesiąca do wyświetlenia w UI (checkboxy).
     *
     * GET /api/reports/invoices?month=2026-04
     *
     * @param userId UUID użytkownika (z nagłówka X-User-Id)
     * @param month  miesiąc w formacie YYYY-MM (np. "2026-04")
     * @return lista faktur z danego miesiąca
     */
    @GetMapping("/invoices")
    public ResponseEntity<?> listInvoicesForMonth(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam String month) {

        YearMonth yearMonth = parseYearMonth(month);
        if (yearMonth == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Nieprawidłowy format miesiąca. Oczekiwany: YYYY-MM (np. 2026-04)"));
        }

        List<InvoiceDto.Response> invoices = monthlyReportService.listForMonth(userId, yearMonth)
                .stream()
                .map(invoiceService::toResponse)
                .toList();
        return ResponseEntity.ok(invoices);
    }

    /**
     * Generuje miesięczny raport PDF i zwraca go jako załącznik do pobrania.
     *
     * POST /api/reports/monthly-pdf
     * Body: { "month": "2026-04", "invoiceIds": ["uuid1", "uuid2", ...] }
     *
     * @param userId  UUID użytkownika (z nagłówka X-User-Id)
     * @param request obiekt z miesiącem i listą UUID faktur
     * @return plik PDF jako ResponseEntity<byte[]>
     *
     */
    @PostMapping("/monthly-pdf")
    public ResponseEntity<?> generateMonthlyPdf(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestBody GenerateReportRequest request) {

        YearMonth yearMonth = parseYearMonth(request.month());
        if (yearMonth == null) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Nieprawidłowy format miesiąca. Oczekiwany: YYYY-MM (np. 2026-04)"));
        }

        if (request.invoiceIds() == null || request.invoiceIds().isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("error", "Lista faktur nie może być pusta"));
        }

        byte[] pdf = monthlyReportService.generateReportPdf(userId, request.invoiceIds(), yearMonth);

        String filename = "raport-" + request.month() + ".pdf";

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_PDF);
        headers.setContentDisposition(ContentDisposition.attachment().filename(filename).build());
        headers.setContentLength(pdf.length);

        return ResponseEntity.ok()
                .headers(headers)
                .body(pdf);
    }

    // ---- request/response records ----

    record GenerateReportRequest(
            String month,          // format YYYY-MM, np. "2026-04"
            List<UUID> invoiceIds  // lista UUID faktur do raportu
    ) {}

    // ---- private helpers ----

    private YearMonth parseYearMonth(String month) {
        if (month == null || month.isBlank()) return null;
        try {
            return YearMonth.parse(month); // oczekuje YYYY-MM
        } catch (DateTimeParseException e) {
            return null;
        }
    }
}
