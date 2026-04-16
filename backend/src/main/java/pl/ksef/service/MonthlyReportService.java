package pl.ksef.service;

import com.lowagie.text.*;
import com.lowagie.text.pdf.*;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import pl.ksef.entity.Invoice;
import pl.ksef.repository.InvoiceRepository;
import pl.ksef.exception.ResourceNotFoundException;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.math.BigDecimal;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Serwis generowania miesięcznych raportów faktur w formacie PDF (OpenPDF).
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class MonthlyReportService {

    private final InvoiceRepository invoiceRepository;

    private static final DateTimeFormatter MONTH_LABEL_FORMAT =
            DateTimeFormatter.ofPattern("LLLL yyyy", new Locale("pl"));
    private static final DateTimeFormatter DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd.MM.yyyy");
    private static final NumberFormat PLN_FORMAT =
            NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));

    /** Czcionka TrueType z obsługą polskich znaków (ładowana raz przy starcie). */
    private BaseFont unicodeFont;
    private BaseFont unicodeFontBold;

    /** Ścieżki systemowe do czcionki DejaVu Sans — sprawdzane kolejno. */
    private static final String[] REGULAR_SYSTEM_PATHS = {
        "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",      // Debian/Ubuntu/Docker Alpine
        "/usr/share/fonts/dejavu/DejaVuSans.ttf",               // inne dystrybucje Linux
        "/usr/share/fonts/TTF/DejaVuSans.ttf",                  // Arch Linux
        "C:/Windows/Fonts/arial.ttf",                            // Windows (dev)
    };
    private static final String[] BOLD_SYSTEM_PATHS = {
        "/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf",
        "/usr/share/fonts/TTF/DejaVuSans-Bold.ttf",
        "C:/Windows/Fonts/arialbd.ttf",
    };

    @PostConstruct
    void initFonts() {
        unicodeFont     = loadFont("fonts/DejaVuSans.ttf",      REGULAR_SYSTEM_PATHS);
        unicodeFontBold = loadFont("fonts/DejaVuSans-Bold.ttf", BOLD_SYSTEM_PATHS);
    }

    /**
     * Ładuje czcionkę TrueType z zasobów classpatha (first) lub ze ścieżki systemowej (fallback).
     * Jeśli żadna nie jest dostępna, zwraca null (kod wywołujący używa wtedy Helvetica).
     */
    private BaseFont loadFont(String classpathResource, String[] systemPaths) {
        // 1. Classpath — działa wszędzie jeśli TTF jest w resources/fonts/
        try (InputStream is = getClass().getClassLoader().getResourceAsStream(classpathResource)) {
            if (is != null) {
                byte[] bytes = is.readAllBytes();
                BaseFont bf = BaseFont.createFont(classpathResource, BaseFont.IDENTITY_H,
                        BaseFont.EMBEDDED, true, bytes, null);
                log.info("Loaded PDF font from classpath: {}", classpathResource);
                return bf;
            }
        } catch (Exception e) {
            log.debug("Font not found in classpath: {}", classpathResource);
        }

        // 2. Ścieżki systemowe (Docker z font-dejavu, Windows z Arial)
        for (String path : systemPaths) {
            try {
                if (new File(path).exists()) {
                    BaseFont bf = BaseFont.createFont(path, BaseFont.IDENTITY_H, BaseFont.EMBEDDED);
                    log.info("Loaded PDF font from system path: {}", path);
                    return bf;
                }
            } catch (Exception e) {
                log.debug("Cannot load font from: {}", path);
            }
        }

        log.warn("No Unicode TrueType font found — Polish characters may not render in PDFs. "
                + "Add DejaVuSans.ttf to src/main/resources/fonts/ or install font-dejavu in Docker.");
        return null;
    }

    /** Buduje czcionkę — używa Unicode TTF jeśli dostępna, w przeciwnym razie Helvetica. */
    private Font font(BaseFont unicode, String fallbackName, float size, int style, Color color) {
        if (unicode != null) {
            return new Font(unicode, size, style, color);
        }
        return FontFactory.getFont(fallbackName, size, style, color);
    }

    /**
     * Pobiera faktury użytkownika z danego miesiąca do wyświetlenia checkboxów w UI.
     *
     * @param userId UUID użytkownika
     * @param month  rok i miesiąc (np. YearMonth.of(2026, 4))
     * @return lista faktur posortowana po dacie wystawienia (ASC)
     */
    public List<Invoice> listForMonth(UUID userId, YearMonth month) {
        LocalDate from = month.atDay(1);
        LocalDate to   = month.atEndOfMonth();
        return invoiceRepository.findByUserIdAndIssueDateBetweenOrderByIssueDateAsc(userId, from, to);
    }

    /**
     * Generuje miesięczny raport PDF dla wskazanych faktur.
     *
     * @param userId     UUID właściciela (weryfikacja autoryzacji każdej faktury)
     * @param invoiceIds lista UUID faktur do uwzględnienia w raporcie
     * @param month      miesiąc raportu (do nagłówka)
     * @return bajty pliku PDF
     * @throws ResourceNotFoundException jeśli którakolwiek faktura nie należy do userId
     */
    public byte[] generateReportPdf(UUID userId, List<UUID> invoiceIds, YearMonth month) {
        List<Invoice> invoices = invoiceIds.stream()
                .map(id -> invoiceRepository.findById(id)
                        .filter(inv -> inv.getUserId().equals(userId))
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "Invoice not found or access denied: " + id)))
                .sorted(Comparator
                        .<Invoice, Integer>comparing(inv ->
                                inv.getDirection().name().equals("ISSUED") ? 0 : 1)
                        .thenComparing(inv ->
                                inv.getIssueDate() != null ? inv.getIssueDate() : LocalDate.MIN))
                .toList();

        log.info("Generating monthly report PDF for userId={}, month={}, invoices={}",
                userId, month, invoices.size());

        try {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            Document doc = new Document(PageSize.A4.rotate());
            PdfWriter.getInstance(doc, baos);
            doc.open();

            // Czcionki — Unicode TTF (polskie znaki) z fallbackiem na Helvetica
            Font titleFont  = font(unicodeFontBold, FontFactory.HELVETICA_BOLD, 14, Font.BOLD,   new Color(37, 99, 235));
            Font metaFont   = font(unicodeFont,     FontFactory.HELVETICA,       9, Font.NORMAL, new Color(107, 114, 128));
            Font headerFont = font(unicodeFontBold, FontFactory.HELVETICA_BOLD,  8, Font.BOLD,   Color.WHITE);
            Font cellFont   = font(unicodeFont,     FontFactory.HELVETICA,        8, Font.NORMAL, Color.BLACK);

            // Nagłówek dokumentu
            String monthLabel = capitalize(month.format(MONTH_LABEL_FORMAT));
            doc.add(new Paragraph("Raport faktur \u2014 " + monthLabel, titleFont));
            doc.add(new Paragraph("Wygenerowano: " + LocalDate.now().format(DATE_FORMAT)
                    + "  \u00b7  Liczba faktur: " + invoices.size(), metaFont));
            doc.add(Chunk.NEWLINE);

            // Tabela: Nr faktury | Kontrahent | Kierunek | Data | Netto | VAT | Brutto | Nr KSeF | Status
            float[] colWidths = {12f, 22f, 7f, 8f, 10f, 10f, 10f, 15f, 6f};
            PdfPTable table = new PdfPTable(colWidths.length);
            table.setWidthPercentage(100);
            table.setWidths(colWidths);

            // Nagłówki kolumn
            Color headerBg = new Color(37, 99, 235);
            String[] headers = {"Nr faktury", "Kontrahent", "Kierunek", "Data", "Netto", "VAT", "Brutto", "Nr KSeF", "Status"};
            for (String h : headers) {
                PdfPCell cell = new PdfPCell(new Phrase(h, headerFont));
                cell.setBackgroundColor(headerBg);
                cell.setPadding(5);
                cell.setBorderColor(headerBg);
                table.addCell(cell);
            }

            // Wiersze danych
            boolean altRow = false;
            Color altBg = new Color(243, 244, 246);

            for (Invoice inv : invoices) {
                Color rowBg = altRow ? altBg : Color.WHITE;
                altRow = !altRow;

                String counterparty = "ISSUED".equals(inv.getDirection().name())
                        ? inv.getBuyerName() + "\nNIP: " + inv.getBuyerNip()
                        : inv.getSellerName() + "\nNIP: " + inv.getSellerNip();
                String direction = "ISSUED".equals(inv.getDirection().name()) ? "Wyst." : "Odeb.";

                addCell(table, inv.getInvoiceNumber(), cellFont, rowBg, Element.ALIGN_LEFT);
                addCell(table, counterparty,           cellFont, rowBg, Element.ALIGN_LEFT);
                addCell(table, direction,              cellFont, rowBg, Element.ALIGN_CENTER);
                addCell(table, inv.getIssueDate() != null ? inv.getIssueDate().format(DATE_FORMAT) : "",
                        cellFont, rowBg, Element.ALIGN_CENTER);
                addCell(table, formatPln(inv.getNetAmount()),   cellFont, rowBg, Element.ALIGN_RIGHT);
                addCell(table, formatPln(inv.getVatAmount()),   cellFont, rowBg, Element.ALIGN_RIGHT);
                addCell(table, formatPln(inv.getGrossAmount()), cellFont, rowBg, Element.ALIGN_RIGHT);
                addCell(table, inv.getKsefNumber() != null ? inv.getKsefNumber() : "\u2014",
                        cellFont, rowBg, Element.ALIGN_LEFT);
                addCell(table, statusLabel(inv.getStatus().name()), cellFont, rowBg, Element.ALIGN_CENTER);
            }

            doc.add(table);
            doc.close();
            return baos.toByteArray();

        } catch (DocumentException e) {
            throw new RuntimeException("Błąd generowania PDF raportu: " + e.getMessage(), e);
        }
    }

    private void addCell(PdfPTable table, String text, Font font, Color bg, int align) {
        PdfPCell cell = new PdfPCell(new Phrase(text, font));
        cell.setBackgroundColor(bg);
        cell.setHorizontalAlignment(align);
        cell.setPadding(4);
        cell.setBorderColor(new Color(229, 231, 235));
        table.addCell(cell);
    }

    private String formatPln(BigDecimal amount) {
        if (amount == null) return "\u2014";
        return PLN_FORMAT.format(amount);
    }

    private String statusLabel(String status) {
        return switch (status) {
            case "DRAFT"              -> "Szkic";
            case "QUEUED"             -> "Kolejka";
            case "SENDING"            -> "Wysyłanie";
            case "SENT"               -> "Wysłana";
            case "FAILED"             -> "Błąd";
            case "RECEIVED_FROM_KSEF" -> "Z KSeF";
            default                   -> status;
        };
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }
}
