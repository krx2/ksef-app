package pl.ksef.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.entity.Invoice.InvoiceDirection;
import pl.ksef.service.InvoiceService;
import pl.ksef.service.XlsxConfigService;
import pl.ksef.service.XlsxParserService;

import java.util.UUID;

@RestController
@RequestMapping("/api/invoices")
@RequiredArgsConstructor
public class InvoiceController {

    private final InvoiceService invoiceService;
    private final XlsxParserService xlsxParserService;
    private final XlsxConfigService xlsxConfigService;

    /** List invoices for user, optionally filtered by direction */
    @GetMapping
    public ResponseEntity<InvoiceDto.PageResponse> list(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam(required = false) InvoiceDirection direction,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(invoiceService.listByUser(userId, direction, page, size));
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
        // Convert DTO back to entity-like for parser
        pl.ksef.entity.XlsxConfiguration configEntity = new pl.ksef.entity.XlsxConfiguration();
        configEntity.setFieldMappings(config.getFieldMappings());

        InvoiceDto.CreateRequest req = xlsxParserService.parseWithConfig(file, configEntity);
        req.setInvoiceNumber(req.getInvoiceNumber()); // already set from xlsx
        var invoice = invoiceService.createAndQueue(userId, req);
        var resp = invoiceService.toResponse(invoice);
        resp.setSource(pl.ksef.entity.Invoice.InvoiceSource.XLSX);
        return ResponseEntity.ok(resp);
    }

    /** Preview XLSX parse result without creating invoice */
    @PostMapping("/xlsx-preview")
    public ResponseEntity<java.util.Map<String, String>> previewXlsx(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("configId") UUID configId) throws Exception {

        var config = xlsxConfigService.getById(configId, userId);
        pl.ksef.entity.XlsxConfiguration configEntity = new pl.ksef.entity.XlsxConfiguration();
        configEntity.setFieldMappings(config.getFieldMappings());
        return ResponseEntity.ok(xlsxParserService.previewFields(file, configEntity.getFieldMappings()));
    }
}
