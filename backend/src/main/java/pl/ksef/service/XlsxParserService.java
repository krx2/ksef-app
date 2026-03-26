package pl.ksef.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.poi.ss.usermodel.*;
import org.apache.poi.ss.util.CellReference;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.entity.XlsxConfiguration;
import pl.ksef.entity.XlsxConfiguration.FieldMapping;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.*;

@Service
@Slf4j
public class XlsxParserService {

    /**
     * Parse an XLSX file using a saved configuration.
     * Returns a populated CreateRequest ready for invoice creation.
     */
    public InvoiceDto.CreateRequest parseWithConfig(MultipartFile file,
                                                     XlsxConfiguration config) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            Map<String, String> resolved = resolveFields(workbook, config.getFieldMappings());
            return buildCreateRequest(resolved);
        }
    }

    /**
     * Preview: resolve all configured fields from the file without creating an invoice.
     */
    public Map<String, String> previewFields(MultipartFile file,
                                              Map<String, FieldMapping> mappings) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            return resolveFields(workbook, mappings);
        }
    }

    /**
     * Read a specific cell from an uploaded workbook for config UI preview.
     */
    public String readCell(MultipartFile file, String cellRef, int sheetIndex) throws IOException {
        try (Workbook workbook = new XSSFWorkbook(file.getInputStream())) {
            return getCellValue(workbook, cellRef, sheetIndex);
        }
    }

    // ---- private ----

    private Map<String, String> resolveFields(Workbook workbook, Map<String, FieldMapping> mappings) {
        Map<String, String> result = new LinkedHashMap<>();
        if (mappings == null) return result;

        mappings.forEach((fieldName, mapping) -> {
            if (mapping == null) return;
            String value;
            if (mapping.getType() == FieldMapping.MappingType.VALUE) {
                value = mapping.getValue();
            } else {
                int sheetIdx = mapping.getSheetIndex() != null ? mapping.getSheetIndex() : 0;
                value = getCellValue(workbook, mapping.getCellRef(), sheetIdx);
            }
            result.put(fieldName, value);
        });
        return result;
    }

    private String getCellValue(Workbook workbook, String cellRef, int sheetIndex) {
        if (cellRef == null || cellRef.isBlank()) return null;
        try {
            Sheet sheet = workbook.getSheetAt(sheetIndex);
            CellReference ref = new CellReference(cellRef.toUpperCase());
            Row row = sheet.getRow(ref.getRow());
            if (row == null) return null;
            Cell cell = row.getCell(ref.getCol());
            if (cell == null) return null;
            return getCellStringValue(cell);
        } catch (Exception e) {
            log.warn("Could not read cell {} on sheet {}: {}", cellRef, sheetIndex, e.getMessage());
            return null;
        }
    }

    private String getCellStringValue(Cell cell) {
        return switch (cell.getCellType()) {
            case STRING  -> cell.getStringCellValue().trim();
            case NUMERIC -> {
                if (DateUtil.isCellDateFormatted(cell)) {
                    yield cell.getLocalDateTimeCellValue().toLocalDate().toString();
                }
                // Avoid scientific notation
                double val = cell.getNumericCellValue();
                if (val == Math.floor(val) && !Double.isInfinite(val)) {
                    yield String.valueOf((long) val);
                }
                yield String.valueOf(val);
            }
            case BOOLEAN -> String.valueOf(cell.getBooleanCellValue());
            case FORMULA -> {
                FormulaEvaluator evaluator = cell.getSheet().getWorkbook()
                        .getCreationHelper().createFormulaEvaluator();
                CellValue evaluated = evaluator.evaluate(cell);
                yield switch (evaluated.getCellType()) {
                    case STRING  -> evaluated.getStringValue();
                    case NUMERIC -> String.valueOf(evaluated.getNumberValue());
                    default      -> "";
                };
            }
            default -> "";
        };
    }

    private InvoiceDto.CreateRequest buildCreateRequest(Map<String, String> fields) {
        var req = new InvoiceDto.CreateRequest();

        req.setInvoiceNumber(fields.getOrDefault("invoiceNumber", ""));
        req.setIssueDate(parseDate(fields.get("issueDate")));
        req.setSaleDate(parseDate(fields.get("saleDate")));
        req.setSellerName(fields.getOrDefault("sellerName", ""));
        req.setSellerNip(fields.getOrDefault("sellerNip", ""));
        req.setSellerAddress(fields.get("sellerAddress"));
        req.setBuyerName(fields.getOrDefault("buyerName", ""));
        req.setBuyerNip(fields.getOrDefault("buyerNip", ""));
        req.setBuyerAddress(fields.get("buyerAddress"));
        req.setCurrency(fields.getOrDefault("currency", "PLN"));

        // Items — supports item1_name, item1_quantity, item1_netUnitPrice, item1_vatRate
        List<InvoiceDto.ItemRequest> items = new ArrayList<>();
        for (int i = 1; i <= 50; i++) {
            String name = fields.get("item" + i + "_name");
            if (name == null || name.isBlank()) break;
            var item = new InvoiceDto.ItemRequest();
            item.setName(name);
            item.setUnit(fields.get("item" + i + "_unit"));
            item.setQuantity(parseBigDecimal(fields.getOrDefault("item" + i + "_quantity", "1")));
            item.setNetUnitPrice(parseBigDecimal(fields.getOrDefault("item" + i + "_netUnitPrice", "0")));
            item.setVatRate(parseBigDecimal(fields.getOrDefault("item" + i + "_vatRate", "23")));
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
}
