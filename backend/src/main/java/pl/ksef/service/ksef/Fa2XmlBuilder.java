package pl.ksef.service.ksef;

import org.springframework.stereotype.Component;
import pl.ksef.entity.Invoice;
import pl.ksef.entity.InvoiceItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buduje XML faktury zgodny ze schematem FA(3) KSeF.
 * Namespace: http://crd.gov.pl/wzor/2025/06/25/13775/
 */
@Component
public class Fa2XmlBuilder {

    private static final String FA3_NS = "http://crd.gov.pl/wzor/2025/06/25/13775/";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    // DataWytworzeniaFa wymaga formatu z Z (UTC) i min 2025-09-01T00:00:00Z
    private static final DateTimeFormatter DT_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    public String build(Invoice invoice) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Faktura xmlns=\"").append(FA3_NS).append("\">\n");

        appendNaglowek(sb, invoice);
        appendPodmiot1(sb, invoice);
        appendPodmiot2(sb, invoice);
        appendFa(sb, invoice);

        sb.append("</Faktura>");
        return sb.toString();
    }

    // ---- Nagłówek ----

    private void appendNaglowek(StringBuilder sb, Invoice invoice) {
        sb.append("  <Naglowek>\n");
        sb.append("    <KodFormularza kodSystemowy=\"FA (3)\" wersjaSchemy=\"1-0E\">FA</KodFormularza>\n");
        sb.append("    <WariantFormularza>3</WariantFormularza>\n");
        // DataWytworzeniaFa: aktualny czas UTC, minimum 2025-09-01T00:00:00Z
        LocalDateTime now = LocalDateTime.now();
        sb.append("    <DataWytworzeniaFa>").append(now.format(DT_FMT)).append("</DataWytworzeniaFa>\n");
        sb.append("    <SystemInfo>KSeF-App v1.0</SystemInfo>\n");
        sb.append("  </Naglowek>\n");
    }

    // ---- Podmiot1 (Sprzedawca) ----

    private void appendPodmiot1(StringBuilder sb, Invoice invoice) {
        sb.append("  <Podmiot1>\n");
        sb.append("    <DaneIdentyfikacyjne>\n");
        sb.append("      <NIP>").append(escapeXml(invoice.getSellerNip())).append("</NIP>\n");
        sb.append("      <Nazwa>").append(escapeXml(invoice.getSellerName())).append("</Nazwa>\n");
        sb.append("    </DaneIdentyfikacyjne>\n");
        // FA(3): KodKraju + AdresL1 wymagane w Podmiot1.Adres
        sb.append("    <Adres>\n");
        sb.append("      <KodKraju>").append(escapeXml(coalesce(invoice.getSellerCountryCode(), "PL"))).append("</KodKraju>\n");
        sb.append("      <AdresL1>").append(escapeXml(coalesce(invoice.getSellerAddress(), ""))).append("</AdresL1>\n");
        sb.append("    </Adres>\n");
        sb.append("  </Podmiot1>\n");
    }

    // ---- Podmiot2 (Nabywca) ----

    private void appendPodmiot2(StringBuilder sb, Invoice invoice) {
        sb.append("  <Podmiot2>\n");
        sb.append("    <DaneIdentyfikacyjne>\n");
        sb.append("      <NIP>").append(escapeXml(invoice.getBuyerNip())).append("</NIP>\n");
        if (invoice.getBuyerName() != null && !invoice.getBuyerName().isBlank()) {
            sb.append("      <Nazwa>").append(escapeXml(invoice.getBuyerName())).append("</Nazwa>\n");
        }
        sb.append("    </DaneIdentyfikacyjne>\n");
        if (invoice.getBuyerAddress() != null && !invoice.getBuyerAddress().isBlank()) {
            sb.append("    <Adres>\n");
            sb.append("      <KodKraju>").append(escapeXml(coalesce(invoice.getBuyerCountryCode(), "PL"))).append("</KodKraju>\n");
            sb.append("      <AdresL1>").append(escapeXml(invoice.getBuyerAddress())).append("</AdresL1>\n");
            sb.append("    </Adres>\n");
        }
        // JST i GV są wymagane w FA(3) Podmiot2
        sb.append("    <JST>2</JST>\n");   // 2 = nie dotyczy JST
        sb.append("    <GV>2</GV>\n");     // 2 = nie dotyczy grupy VAT
        sb.append("  </Podmiot2>\n");
    }

    // ---- Fa (dane faktury) ----

    private void appendFa(StringBuilder sb, Invoice invoice) {
        sb.append("  <Fa>\n");
        sb.append("    <KodWaluty>").append(coalesce(invoice.getCurrency(), "PLN")).append("</KodWaluty>\n");
        sb.append("    <P_1>").append(invoice.getIssueDate().format(DATE_FMT)).append("</P_1>\n");
        sb.append("    <P_2>").append(escapeXml(invoice.getInvoiceNumber())).append("</P_2>\n");

        if (invoice.getSaleDate() != null) {
            sb.append("    <P_6>").append(invoice.getSaleDate().format(DATE_FMT)).append("</P_6>\n");
        }

        // Podsumowania stawek VAT (przed P_15)
        appendVatSummaries(sb, invoice.getItems());

        sb.append("    <P_15>")
          .append(invoice.getGrossAmount().setScale(2, RoundingMode.HALF_UP).toPlainString())
          .append("</P_15>\n");

        // Adnotacje — wymagane w FA(3)
        appendAdnotacje(sb, invoice);

        // Rodzaj faktury — wymagany w FA(3)
        sb.append("    <RodzajFaktury>")
          .append(coalesce(invoice.getRodzajFaktury(), "VAT"))
          .append("</RodzajFaktury>\n");

        // Pozycje faktury (FaWiersz) — po RodzajFaktury zgodnie ze schematem FA(3)
        appendFaWiersze(sb, invoice.getItems());

        sb.append("  </Fa>\n");
    }

    // ---- Podsumowania VAT ----

    private void appendVatSummaries(StringBuilder sb, List<InvoiceItem> items) {
        // Agregacja sum netto i VAT wg bucketu FA(3)
        Map<String, BigDecimal[]> buckets = new LinkedHashMap<>();
        // bucket key → [net, vat]
        for (InvoiceItem item : items) {
            String code = resolveVatRateCode(item);
            String bucket = vatCodeToBucket(code);
            buckets.computeIfAbsent(bucket, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            buckets.get(bucket)[0] = buckets.get(bucket)[0].add(item.getNetAmount());
            buckets.get(bucket)[1] = buckets.get(bucket)[1].add(item.getVatAmount());
        }

        for (Map.Entry<String, BigDecimal[]> e : buckets.entrySet()) {
            String bucket = e.getKey();
            BigDecimal net = e.getValue()[0].setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = e.getValue()[1].setScale(2, RoundingMode.HALF_UP);

            switch (bucket) {
                case "1" -> {
                    sb.append("    <P_13_1>").append(net).append("</P_13_1>\n");
                    sb.append("    <P_14_1>").append(vat).append("</P_14_1>\n");
                }
                case "2" -> {
                    sb.append("    <P_13_2>").append(net).append("</P_13_2>\n");
                    sb.append("    <P_14_2>").append(vat).append("</P_14_2>\n");
                }
                case "3" -> {
                    sb.append("    <P_13_3>").append(net).append("</P_13_3>\n");
                    sb.append("    <P_14_3>").append(vat).append("</P_14_3>\n");
                }
                case "4" -> {
                    sb.append("    <P_13_4>").append(net).append("</P_13_4>\n");
                    sb.append("    <P_14_4>").append(vat).append("</P_14_4>\n");
                }
                // TODO: Buckety 6_1–11 emitują tylko P_13_x (wartość netto) bez P_14_x (VAT).
                //       Jest to poprawne dla stawek zerowych i zwolnionych (brak kwoty podatku),
                //       ALE bucket "11" jest obecnie nieosiągalny — vatCodeToBucket() nie mapuje
                //       żadnego kodu FA(3) na "11" (brakuje kodu "oo" w mapowaniu, trafia na "10").
                //       Sprawdzić z dokumentacją FA(3) XSD czy P_13_11 jest potrzebne i dla jakiego kodu.
                case "6_1" -> sb.append("    <P_13_6_1>").append(net).append("</P_13_6_1>\n");
                case "6_2" -> sb.append("    <P_13_6_2>").append(net).append("</P_13_6_2>\n");
                case "6_3" -> sb.append("    <P_13_6_3>").append(net).append("</P_13_6_3>\n");
                case "7"   -> sb.append("    <P_13_7>").append(net).append("</P_13_7>\n");
                case "8"   -> sb.append("    <P_13_8>").append(net).append("</P_13_8>\n");
                case "9"   -> sb.append("    <P_13_9>").append(net).append("</P_13_9>\n");
                case "10"  -> sb.append("    <P_13_10>").append(net).append("</P_13_10>\n");
                case "11"  -> sb.append("    <P_13_11>").append(net).append("</P_13_11>\n");
                default    -> throw new IllegalStateException(
                        "Nieznany bucket VAT: '" + bucket + "' dla kodu stawki. "
                        + "Zaktualizuj vatCodeToBucket() o nowy kod FA(3).");
            }
        }
    }

    /** Mapuje kod stawki FA(3) na numer bucketu do elementów P_13_x. */
    private String vatCodeToBucket(String code) {
        return switch (code) {
            case "23", "22"  -> "1";
            case "8",  "7"   -> "2";
            case "5"         -> "3";
            case "4", "3"    -> "4";
            case "0 KR"      -> "6_1";
            case "0 WDT"     -> "6_2";
            case "0 EX"      -> "6_3";
            case "zw"        -> "7";
            case "np I"      -> "8";
            case "np II"     -> "9";
            case "oo"        -> "10";
            default          -> "1"; // fallback
        };
    }

    // ---- Adnotacje (wymagane w FA(3)) ----

    private void appendAdnotacje(StringBuilder sb, Invoice invoice) {
        sb.append("    <Adnotacje>\n");
        sb.append("      <P_16>").append(invoice.isMetodaKasowa()               ? "1" : "2").append("</P_16>\n");
        sb.append("      <P_17>").append(invoice.isSamofakturowanie()            ? "1" : "2").append("</P_17>\n");
        sb.append("      <P_18>").append(invoice.isOdwrotneObciazenie()          ? "1" : "2").append("</P_18>\n");
        sb.append("      <P_18A>").append(invoice.isMechanizmPodzielonejPlatnosci() ? "1" : "2").append("</P_18A>\n");
        // TODO: Pola Zwolnienie, NoweSrodkiTransportu, P_23, PMarzy są zawsze hardcoded
        //       na wartość "brak" (P_19N=1 oznacza "nie dotyczy", P_22N=1, P_23=2, P_PMarzyN=1).
        //       Dla faktur z odwrotnym obciążeniem (P_18=1) lub zwolnionych z VAT (bucket "7")
        //       niektóre z tych flag powinny być "1" (dotyczy). Np. gdy są pozycje z kodem "zw"
        //       pole P_19 powinno wskazywać podstawę prawną zwolnienia (art. 43 ust. 1 lub inna).
        //       Należy dodać do encji Invoice / formularza pola:
        //       podstawaZwolnienia (String, np. "art. 43 ust. 1 pkt 1"), p23 (boolean), pMarzy (boolean).
        sb.append("      <Zwolnienie><P_19N>1</P_19N></Zwolnienie>\n");
        sb.append("      <NoweSrodkiTransportu><P_22N>1</P_22N></NoweSrodkiTransportu>\n");
        sb.append("      <P_23>2</P_23>\n");
        sb.append("      <PMarzy><P_PMarzyN>1</P_PMarzyN></PMarzy>\n");
        sb.append("    </Adnotacje>\n");
    }

    // ---- FaWiersz (pozycje) ----

    private void appendFaWiersze(StringBuilder sb, List<InvoiceItem> items) {
        int pos = 1;
        for (InvoiceItem item : items) {
            sb.append("    <FaWiersz>\n");
            sb.append("      <NrWierszaFa>").append(pos++).append("</NrWierszaFa>\n");
            sb.append("      <P_7>").append(escapeXml(item.getName())).append("</P_7>\n");
            if (item.getUnit() != null && !item.getUnit().isBlank()) {
                sb.append("      <P_8A>").append(escapeXml(item.getUnit())).append("</P_8A>\n");
            }
            sb.append("      <P_8B>").append(item.getQuantity().toPlainString()).append("</P_8B>\n");
            sb.append("      <P_9A>").append(item.getNetUnitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()).append("</P_9A>\n");
            sb.append("      <P_11>").append(item.getNetAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()).append("</P_11>\n");
            sb.append("      <P_12>").append(resolveVatRateCode(item)).append("</P_12>\n");
            sb.append("    </FaWiersz>\n");
        }
    }

    // ---- Helpers ----

    /**
     * Zwraca kod stawki VAT zgodny z TStawkaPodatku (FA(3)).
     * Jeśli pozycja ma ustawiony vatRateCode, używa go bezpośrednio.
     * W przeciwnym razie mapuje numeryczną wartość vatRate.
     */
    static String resolveVatRateCode(InvoiceItem item) {
        if (item.getVatRateCode() != null && !item.getVatRateCode().isBlank()) {
            return item.getVatRateCode();
        }
        BigDecimal rate = item.getVatRate();
        if (rate == null) return "23";
        return switch (rate.intValue()) {
            case 23 -> "23";
            case 22 -> "22";
            case 8  -> "8";
            case 7  -> "7";
            case 5  -> "5";
            case 4  -> "4";
            case 3  -> "3";
            case 0  -> "0 KR";   // domyślnie 0% krajowy
            default -> String.valueOf(rate.intValue());
        };
    }

    private String coalesce(String value, String fallback) {
        return (value != null && !value.isBlank()) ? value : fallback;
    }

    private String escapeXml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }
}
