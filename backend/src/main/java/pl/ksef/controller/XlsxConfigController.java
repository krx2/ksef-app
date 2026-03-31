package pl.ksef.controller;

import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import pl.ksef.dto.XlsxConfigDto;
import pl.ksef.service.XlsxConfigService;
import pl.ksef.service.XlsxParserService;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/xlsx-configs")
@RequiredArgsConstructor
public class XlsxConfigController {

    private final XlsxConfigService service;
    private final XlsxParserService parserService;

    @GetMapping
    public ResponseEntity<List<XlsxConfigDto.Response>> list(
            @RequestHeader("X-User-Id") UUID userId) {
        return ResponseEntity.ok(service.listByUser(userId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<XlsxConfigDto.Response> get(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(service.getById(id, userId));
    }

    @PostMapping
    public ResponseEntity<XlsxConfigDto.Response> create(
            @RequestHeader("X-User-Id") UUID userId,
            @Valid @RequestBody XlsxConfigDto.SaveRequest request) {
        return ResponseEntity.ok(service.save(userId, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<XlsxConfigDto.Response> update(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id,
            @Valid @RequestBody XlsxConfigDto.SaveRequest request) {
        return ResponseEntity.ok(service.update(id, userId, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @RequestHeader("X-User-Id") UUID userId,
            @PathVariable UUID id) {
        service.delete(id, userId);
        return ResponseEntity.noContent().build();
    }

    /**
     * Test a single cell reference against an uploaded XLSX file.
     * Used by the config UI to preview what value a cell contains.
     */
    @PostMapping("/test-cell")
    public ResponseEntity<Map<String, String>> testCell(
            @RequestHeader("X-User-Id") UUID userId,
            @RequestParam("file") MultipartFile file,
            @RequestParam("cellRef") String cellRef,
            @RequestParam(value = "sheetIndex", defaultValue = "0") int sheetIndex) throws Exception {
        if (!cellRef.matches("^[A-Z]{1,3}\\d{1,7}$")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Nieprawidłowy format cellRef — oczekiwany format: A1 … XFD9999999"));
        }
        String value = parserService.readCell(file, cellRef, sheetIndex);
        return ResponseEntity.ok(Map.of("cellRef", cellRef, "value", value != null ? value : ""));
    }
}
