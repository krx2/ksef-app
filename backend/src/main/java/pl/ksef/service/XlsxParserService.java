package pl.ksef.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.entity.XlsxConfiguration.FieldMapping;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.*;
import java.util.stream.Collectors;

@Service
@Slf4j
public class XlsxParserService {

    /**
     * Parse an XLS or XLSX file using a saved configuration.
     * Returns a populated CreateRequest ready for invoice creation.
     */
    public InvoiceDto.CreateRequest parseWithConfig(MultipartFile file,
                                                     Map<String, FieldMapping> fieldMappings) throws IOException {
        try (Workbook workbook = openWorkbook(file)) {
            WorkbookView view = WorkbookView.of(workbook);
            Map<String, String> resolved = resolveFields(view, fieldMappings);
            return buildCreateRequest(resolved);
        }
    }

    /**
     * Preview: resolve all configured fields from the file without creating an invoice.
     * Returns values exactly as they appear in Excel (formatted).
     */
    public Map<String, String> previewFields(MultipartFile file,
                                              Map<String, FieldMapping> mappings) throws IOException {
        try (Workbook workbook = openWorkbook(file)) {
            WorkbookView view = WorkbookView.of(workbook);
            return resolveFields(view, mappings);
        }
    }

    /**
     * Read a specific cell from an uploaded workbook for config UI preview.
     * Returns the value as displayed in Excel (formatted).
     */
    public String readCell(MultipartFile file, String cellRef, int sheetIndex) throws IOException {
        try (Workbook workbook = openWorkbook(file)) {
            WorkbookView view = WorkbookView.of(workbook);
            return view.getDisplayValue(cellRef, sheetIndex);
        }
    }

    // -------------------------------------------------------------------------
    // WorkbookView — "snapshot" arkusza: formuły obliczone, DataFormatter gotowy
    // -------------------------------------------------------------------------

    /**
     * Immutable view of a workbook with all formulas pre-evaluated.
     * All cell reads go through DataFormatter, which reproduces the exact
     * string that Excel would display in the cell (including number formats,
     * date patterns, currency symbols, etc.).
     *
     * For fields that must be parsed back to a typed value (dates, decimals)
     * the raw numeric value is still available via getRawNumeric().
     */
    private static class WorkbookView {
        private final Workbook workbook;
        private final FormulaEvaluator evaluator;
        private final DataFormatter formatter;

        private WorkbookView(Workbook workbook) {
            this.workbook = workbook;
            this.evaluator = workbook.getCreationHelper().createFormulaEvaluator();
            this.formatter = new DataFormatter();   // locale-neutral, mirrors Excel display
            // Evaluate every formula in the workbook up front — "freeze" all computed values
            try {
                evaluator.evaluateAll();
            } catch (Exception e) {
                // Some complex formulas (external refs, UDFs) may fail; log and continue
                // — individual cells will fall back gracefully in getDisplayValue()
                log.warn("evaluateAll() encountered errors (some formulas may show raw): {}", e.getMessage());
            }
        }

        static WorkbookView of(Workbook workbook) {
            return new WorkbookView(workbook);
        }

        /**
         * Returns the display string for a cell — exactly what Excel shows.
         * Formulas are already evaluated; DataFormatter applies the cell's
         * number/date format string.
         */
        String getDisplayValue(String cellRef, int sheetIndex) {
            if (cellRef == null || cellRef.isBlank()) return null;
            try {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                CellReference ref = new CellReference(cellRef.toUpperCase());
                Row row = sheet.getRow(ref.getRow());
                if (row == null) return null;
                Cell cell = row.getCell(ref.getCol());
                if (cell == null) return null;

                String display = formatter.formatCellValue(cell, evaluator).trim();
                return display.isEmpty() ? null : display;
            } catch (Exception e) {
                log.warn("Could not read cell {} on sheet {}: {}", cellRef, sheetIndex, e.getMessage());
                return null;
            }
        }

        /**
         * Returns the underlying numeric value of a cell (after formula evaluation).
         * Used for fields that must be typed (dates → LocalDate, prices → BigDecimal).
         * Returns null when the cell is absent, blank, or non-numeric.
         */
        Double getRawNumeric(String cellRef, int sheetIndex) {
            if (cellRef == null || cellRef.isBlank()) return null;
            try {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                CellReference ref = new CellReference(cellRef.toUpperCase());
                Row row = sheet.getRow(ref.getRow());
                if (row == null) return null;
                Cell cell = row.getCell(ref.getCol());
                if (cell == null) return null;

                CellType type = cell.getCellType() == CellType.FORMULA
                        ? evaluator.evaluateFormulaCell(cell)
                        : cell.getCellType();

                if (type == CellType.NUMERIC) return cell.getNumericCellValue();
                return null;
            } catch (Exception e) {
                log.warn("Could not read numeric cell {} on sheet {}: {}", cellRef, sheetIndex, e.getMessage());
                return null;
            }
        }

        /**
         * True when the cell contains a date-formatted numeric value.
         */
        boolean isDateCell(String cellRef, int sheetIndex) {
            if (cellRef == null || cellRef.isBlank()) return false;
            try {
                Sheet sheet = workbook.getSheetAt(sheetIndex);
                CellReference ref = new CellReference(cellRef.toUpperCase());
                Row row = sheet.getRow(ref.getRow());
                if (row == null) return false;
                Cell cell = row.getCell(ref.getCol());
                if (cell == null) return false;
                return DateUtil.isCellDateFormatted(cell);
            } catch (Exception e) {
                return false;
            }
        }
    }

    // -------------------------------------------------------------------------
    // Internal helpers
    // -------------------------------------------------------------------------

    private Map<String, String> resolveFields(WorkbookView view,
                                               Map<String, FieldMapping> mappings) {
        Map<String, String> result = new LinkedHashMap<>();
        if (mappings == null) return result;

        mappings.forEach((fieldName, mapping) -> {
            if (mapping == null) return;
            if (mapping.getType() == FieldMapping.MappingType.VALUE) {
                result.put(fieldName, mapping.getValue());
            } else if (mapping.getType() == FieldMapping.MappingType.MULTI_CELL) {
                if (mapping.getCells() != null && !mapping.getCells().isEmpty()) {
                    String joined = mapping.getCells().stream()
                            .map(cellRef -> {
                                int sheetIdx = cellRef.getSheetIndex() != null ? cellRef.getSheetIndex() : 0;
                                String val = view.getDisplayValue(cellRef.getCellRef(), sheetIdx);
                                return val != null ? val.trim() : "";
                            })
                            .filter(s -> !s.isEmpty())
                            .collect(Collectors.joining(", "));
                    result.put(fieldName, joined.isBlank() ? null : joined);
                } else {
                    result.put(fieldName, null);
                }
            } else {
                // type == CELL
                int sheetIdx = mapping.getSheetIndex() != null ? mapping.getSheetIndex() : 0;
                // For date / numeric fields used in buildCreateRequest we want
                // a normalized representation, not the locale-formatted display string.
                String value = resolveTypedValue(view, fieldName, mapping.getCellRef(), sheetIdx);
                result.put(fieldName, value);
            }
        });
        return result;
    }

    /**
     * Resolves a cell value with awareness of the target field's expected type:
     * - date fields       → ISO-8601 string (yyyy-MM-dd), regardless of Excel format
     * - numeric fields    → plain decimal string with '.' separator
     * - everything else   → DataFormatter display string (as shown in Excel)
     */
    private String resolveTypedValue(WorkbookView view, String fieldName,
                                      String cellRef, int sheetIndex) {
        if (isDateField(fieldName)) {
            if (view.isDateCell(cellRef, sheetIndex)) {
                Double raw = view.getRawNumeric(cellRef, sheetIndex);
                if (raw != null) {
                    return DateUtil.getLocalDateTime(raw).toLocalDate().toString(); // yyyy-MM-dd
                }
            }
            // Fallback: display string (user may have typed the date as text)
            return view.getDisplayValue(cellRef, sheetIndex);
        }

        if (isNumericField(fieldName)) {
            Double raw = view.getRawNumeric(cellRef, sheetIndex);
            if (raw != null) {
                // Return plain decimal — parseBigDecimal handles both "." and ","
                return raw == Math.floor(raw) && !Double.isInfinite(raw)
                        ? String.valueOf(raw.longValue())
                        : String.valueOf(raw);
            }
            // Fallback: display string (will be parsed by parseBigDecimal)
            return view.getDisplayValue(cellRef, sheetIndex);
        }

        // For all other fields: return exactly what Excel displays
        return view.getDisplayValue(cellRef, sheetIndex);
    }

    /** Fields whose values must be ISO dates for buildCreateRequest */
    private static boolean isDateField(String fieldName) {
        return "issueDate".equals(fieldName) || "saleDate".equals(fieldName);
    }

    /** Fields whose values must be parseable as BigDecimal for buildCreateRequest */
    private static boolean isNumericField(String fieldName) {
        if (fieldName == null) return false;
        return fieldName.endsWith("_quantity")
                || fieldName.endsWith("_netUnitPrice")
                || fieldName.endsWith("_vatRate");
    }

    // -------------------------------------------------------------------------
    // Workbook open / validation
    // -------------------------------------------------------------------------

    private Workbook openWorkbook(MultipartFile file) throws IOException {
        String filename = file.getOriginalFilename();
        if (filename != null) {
            String lower = filename.toLowerCase();
            if (!lower.endsWith(".xls") && !lower.endsWith(".xlsx")) {
                throw new IllegalArgumentException(
                        "Nieobsługiwany format pliku: " + filename + ". Akceptowane formaty: .xls, .xlsx");
            }
        }
        try {
            return WorkbookFactory.create(file.getInputStream());
        } catch (Exception e) {
            throw new IOException(
                    "Nie można otworzyć pliku — nieprawidłowy format lub uszkodzony plik: " + filename, e);
        }
    }

    // -------------------------------------------------------------------------
    // DTO assembly
    // -------------------------------------------------------------------------

    private InvoiceDto.CreateRequest buildCreateRequest(Map<String, String> fields) {
        var req = new InvoiceDto.CreateRequest();

        req.setInvoiceNumber(fields.getOrDefault("invoiceNumber", ""));
        req.setIssueDate(parseDate(fields.get("issueDate")));
        req.setSaleDate(parseDate(fields.get("saleDate")));
        req.setRodzajFaktury(fields.getOrDefault("rodzajFaktury", "VAT"));
        req.setSellerName(fields.getOrDefault("sellerName", ""));
        req.setSellerNip(fields.getOrDefault("sellerNip", ""));
        req.setSellerAddress(fields.get("sellerAddress"));
        req.setSellerCountryCode(fields.getOrDefault("sellerCountryCode", "PL"));
        req.setBuyerName(fields.getOrDefault("buyerName", ""));
        req.setBuyerNip(fields.getOrDefault("buyerNip", ""));
        req.setBuyerAddress(fields.get("buyerAddress"));
        req.setBuyerCountryCode(fields.getOrDefault("buyerCountryCode", "PL"));
        req.setCurrency(fields.getOrDefault("currency", "PLN"));

        List<InvoiceDto.ItemRequest> items = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String name = fields.get("item" + i + "_name");
            if (name == null || name.isBlank()) break;
            var item = new InvoiceDto.ItemRequest();
            item.setName(name);
            item.setUnit(fields.get("item" + i + "_unit"));
            item.setQuantity(parseBigDecimal(fields.getOrDefault("item" + i + "_quantity", "1")));
            item.setNetUnitPrice(parseBigDecimal(fields.getOrDefault("item" + i + "_netUnitPrice", "0")));
            String vatRateStr = fields.getOrDefault("item" + i + "_vatRate", "23");
            String vatRateCode = fields.get("item" + i + "_vatRateCode");
            item.setVatRateCode(vatRateCode);
            item.setVatRate(parseNumericVatRate(vatRateStr, vatRateCode));
            items.add(item);
        }
        req.setItems(items);
        return req;
    }

    private LocalDate parseDate(String s) {
        if (s == null || s.isBlank()) return LocalDate.now();
        try { return LocalDate.parse(s); } catch (Exception ignored) {}
        return LocalDate.now();
    }

    private BigDecimal parseBigDecimal(String s) {
        if (s == null || s.isBlank()) return BigDecimal.ZERO;
        try { return new BigDecimal(s.replace(",", ".")); } catch (Exception e) { return BigDecimal.ZERO; }
    }

    /**
     * Zwraca numeryczną stawkę VAT do obliczeń.
     * Dla niestandardowych kodów FA(3) (zw, oo, np*) zwraca 0.
     */
    private BigDecimal parseNumericVatRate(String vatRateStr, String vatRateCode) {
        if (vatRateCode != null && !vatRateCode.isBlank()) {
            return switch (vatRateCode) {
                case "23"  -> BigDecimal.valueOf(23);
                case "22"  -> BigDecimal.valueOf(22);
                case "8"   -> BigDecimal.valueOf(8);
                case "7"   -> BigDecimal.valueOf(7);
                case "5"   -> BigDecimal.valueOf(5);
                case "4"   -> BigDecimal.valueOf(4);
                case "3"   -> BigDecimal.valueOf(3);
                default    -> BigDecimal.ZERO; // 0 KR, 0 WDT, 0 EX, zw, oo, np I, np II
            };
        }
        return parseBigDecimal(vatRateStr);
    }
}
