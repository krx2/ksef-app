package pl.ksef.service.ksef;

import org.springframework.stereotype.Service;
import pl.ksef.entity.Invoice;
import pl.ksef.entity.InvoiceItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Buduje XML faktury zgodny ze schematem FA(3) KSeF.
 *
 * <p>Namespace docelowy: {@code http://crd.gov.pl/wzor/2025/06/25/13775/}
 * <p>kodSystemowy: {@code FA (3)}, wersjaSchemy: {@code 1-0E}, WariantFormularza: {@code 3}
 *
 * <p>Obsługuje tylko faktury podstawowe (VAT) wystawiane przez podmiot posiadający NIP PL.
 * Faktury korygujące (KOR, KOR_ZAL, KOR_ROZ) wymagają rozszerzenia o sekcję
 * {@code DaneFaKorygowanej} — patrz XSD {@code TRodzajFaktury}.
 */
@Service
public class Fa3XmlBuilder {

    private static final String FA3_NS   = "http://crd.gov.pl/wzor/2025/06/25/13775/";
    private static final String SYSTEM_INFO = "KSeF-App v1.0";

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;
    /**
     * DataWytworzeniaFa wymaga formatu ISO-8601 z sufiksem Z (UTC).
     * Zakres dopuszczalny wg XSD: 2025-09-01T00:00:00Z … 2050-01-01T23:59:59Z.
     */
    private static final DateTimeFormatter DT_UTC_FMT =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss'Z'");

    // =========================================================================
    // Public API
    // =========================================================================

    /**
     * Buduje kompletny XML FA(3) dla podanej faktury.
     *
     * @param invoice encja faktury z pozycjami (invoice.getItems() musi być niepusta)
     * @return łańcuch XML zakodowany w UTF-8, gotowy do szyfrowania i wysyłki do KSeF
     */
    public String build(Invoice invoice) {
        // FaWiersz ma minOccurs="0" w XSD — dla typów ZAL/ROZ/KOR* lista pozycji może być pusta.
        // Dla VAT/UPR pozycje są wymagane (walidowane przez Fa3Validator przed wywołaniem build()).
        StringBuilder sb = new StringBuilder(4096);
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Faktura xmlns=\"").append(FA3_NS).append("\">\n");
        appendNaglowek(sb);
        appendPodmiot1(sb, invoice);
        appendPodmiot2(sb, invoice);
        appendFa(sb, invoice);
        sb.append("</Faktura>");
        return sb.toString();
    }

    // =========================================================================
    // Nagłówek
    // =========================================================================

    /**
     * Emituje sekcję {@code <Naglowek>}.
     * DataWytworzeniaFa to bieżący czas UTC (ZoneOffset.UTC), który spełnia
     * ograniczenie XSD minInclusive="2025-09-01T00:00:00Z".
     */
    private void appendNaglowek(StringBuilder sb) {
        ZonedDateTime now = ZonedDateTime.now(ZoneOffset.UTC);
        ZonedDateTime minDate = ZonedDateTime.of(2025, 9, 1, 0, 0, 0, 0, ZoneOffset.UTC);
        if (now.isBefore(minDate)) {
            throw new KsefException(
                "DataWytworzeniaFa nie może być wcześniejsza niż 2025-09-01T00:00:00Z (ograniczenie XSD FA(3)). "
                + "Sprawdź zegar systemowy.");
        }
        String nowUtc = now.format(DT_UTC_FMT);
        sb.append("  <Naglowek>\n");
        sb.append("    <KodFormularza kodSystemowy=\"FA (3)\" wersjaSchemy=\"1-0E\">FA</KodFormularza>\n");
        sb.append("    <WariantFormularza>3</WariantFormularza>\n");
        sb.append("    <DataWytworzeniaFa>").append(nowUtc).append("</DataWytworzeniaFa>\n");
        sb.append("    <SystemInfo>").append(SYSTEM_INFO).append("</SystemInfo>\n");
        sb.append("  </Naglowek>\n");
    }

    // =========================================================================
    // Podmiot1 — Sprzedawca (podatnik)
    // =========================================================================

    /**
     * Emituje sekcję {@code <Podmiot1>}.
     * W FA(3) {@code Adres} jest obowiązkowy dla Podmiot1 (KodKraju + AdresL1).
     */
    private void appendPodmiot1(StringBuilder sb, Invoice invoice) {
        sb.append("  <Podmiot1>\n");
        sb.append("    <DaneIdentyfikacyjne>\n");
        sb.append("      <NIP>").append(esc(invoice.getSellerNip())).append("</NIP>\n");
        sb.append("      <Nazwa>").append(esc(invoice.getSellerName())).append("</Nazwa>\n");
        sb.append("    </DaneIdentyfikacyjne>\n");
        sb.append("    <Adres>\n");
        sb.append("      <KodKraju>").append(esc(coal(invoice.getSellerCountryCode(), "PL"))).append("</KodKraju>\n");
        sb.append("      <AdresL1>").append(esc(coal(invoice.getSellerAddress(), ""))).append("</AdresL1>\n");
        sb.append("    </Adres>\n");
        sb.append("  </Podmiot1>\n");
    }

    // =========================================================================
    // Podmiot2 — Nabywca
    // =========================================================================

    /**
     * Emituje sekcję {@code <Podmiot2>}.
     *
     * <p>Struktura TPodmiot2 wg XSD:
     * <pre>
     *   DaneIdentyfikacyjne:
     *     choice: NIP | KodUE+NrVatUE | KodKraju?+NrID | BrakID
     *     sequence(0..1): Nazwa
     *   Adres (opcjonalny)
     *   JST  (wymagany)
     *   GV   (wymagany)
     * </pre>
     *
     * Nazwa w TPodmiot2 jest opcjonalna — dla nabywcy z polskim NIP wystarczy NIP.
     * Adres nabywcy emitujemy tylko jeśli wypełniony.
     */
    private void appendPodmiot2(StringBuilder sb, Invoice invoice) {
        sb.append("  <Podmiot2>\n");
        sb.append("    <DaneIdentyfikacyjne>\n");
        sb.append("      <NIP>").append(esc(invoice.getBuyerNip())).append("</NIP>\n");
        if (hasValue(invoice.getBuyerName())) {
            sb.append("      <Nazwa>").append(esc(invoice.getBuyerName())).append("</Nazwa>\n");
        }
        sb.append("    </DaneIdentyfikacyjne>\n");
        if (hasValue(invoice.getBuyerAddress())) {
            sb.append("    <Adres>\n");
            sb.append("      <KodKraju>").append(esc(coal(invoice.getBuyerCountryCode(), "PL"))).append("</KodKraju>\n");
            sb.append("      <AdresL1>").append(esc(invoice.getBuyerAddress())).append("</AdresL1>\n");
            sb.append("    </Adres>\n");
        }
        sb.append("    <JST>").append(invoice.isJst() ? "1" : "2").append("</JST>\n");
        sb.append("    <GV>") .append(invoice.isGv()  ? "1" : "2").append("</GV>\n");
        sb.append("  </Podmiot2>\n");
    }

    // =========================================================================
    // Fa — dane faktury
    // =========================================================================

    /**
     * Emituje sekcję {@code <Fa>} zgodnie z kolejnością elementów w XSD:
     * KodWaluty → P_1 → P_2 → P_6? → P_13_x/P_14_x → P_15 → Adnotacje → RodzajFaktury → FaWiersz → Platnosc?
     */
    private void appendFa(StringBuilder sb, Invoice invoice) {
        sb.append("  <Fa>\n");
        sb.append("    <KodWaluty>").append(coal(invoice.getCurrency(), "PLN")).append("</KodWaluty>\n");
        sb.append("    <P_1>").append(invoice.getIssueDate().format(DATE_FMT)).append("</P_1>\n");
        sb.append("    <P_2>").append(esc(invoice.getInvoiceNumber())).append("</P_2>\n");

        // P_6 (data sprzedaży) — emitujemy tylko gdy różna od daty wystawienia
        if (invoice.getSaleDate() != null
                && !invoice.getSaleDate().equals(invoice.getIssueDate())) {
            sb.append("    <P_6>").append(invoice.getSaleDate().format(DATE_FMT)).append("</P_6>\n");
        }

        appendVatSummaries(sb, invoice.getItems());

        sb.append("    <P_15>")
                .append(scale2(invoice.getGrossAmount()))
                .append("</P_15>\n");

        appendAdnotacje(sb, invoice);

        sb.append("    <RodzajFaktury>").append(coal(invoice.getRodzajFaktury(), "VAT")).append("</RodzajFaktury>\n");

        // FaWiersz przed Platnosc — wymagana kolejność wg XSD FA(3)
        if (invoice.getItems() != null && !invoice.getItems().isEmpty()) {
            appendFaWiersze(sb, invoice.getItems());
        }

        // Platnosc po FaWiersz (i Rozliczenie) — zgodnie z XSD FA(3)
        appendPlatnosc(sb, invoice);

        sb.append("  </Fa>\n");
    }

    // =========================================================================
    // Platnosc — termin zapłaty i rachunek bankowy (opcjonalne)
    // =========================================================================

    /**
     * Emituje sekcję {@code <Platnosc>} gdy ustawiony jest co najmniej jeden z trzech pól:
     * termin zapłaty, numer rachunku bankowego lub nazwa banku.
     * Zgodnie z XSD: Platnosc/TerminPlatnosci/Termin i Platnosc/RachunekBankowy są opcjonalne.
     */
    private void appendPlatnosc(StringBuilder sb, Invoice invoice) {
        boolean hasTermin = invoice.getPaymentDueDate() != null;
        boolean hasRb = hasValue(invoice.getBankAccountNumber());
        boolean hasBankName = hasValue(invoice.getBankName());
        if (!hasTermin && !hasRb && !hasBankName) return;

        sb.append("    <Platnosc>\n");
        if (hasTermin) {
            sb.append("      <TerminPlatnosci>\n");
            sb.append("        <Termin>").append(invoice.getPaymentDueDate().format(DATE_FMT)).append("</Termin>\n");
            sb.append("      </TerminPlatnosci>\n");
        }
        if (hasRb || hasBankName) {
            sb.append("      <RachunekBankowy>\n");
            if (hasRb) {
                sb.append("        <NrRB>").append(esc(invoice.getBankAccountNumber())).append("</NrRB>\n");
            }
            if (hasBankName) {
                sb.append("        <NazwaBanku>").append(esc(invoice.getBankName())).append("</NazwaBanku>\n");
            }
            sb.append("      </RachunekBankowy>\n");
        }
        sb.append("    </Platnosc>\n");
    }

    // =========================================================================
    // Podsumowania VAT (P_13_x / P_14_x)
    // =========================================================================

    /**
     * Agreguje pozycje faktury wg bucketów VAT FA(3) i emituje pola P_13_x / P_14_x.
     *
     * <p>Mapowanie kodów TStawkaPodatku → bucket P_13_x:
     * <pre>
     *  23, 22  → P_13_1 / P_14_1  (stawka podstawowa)
     *  8, 7    → P_13_2 / P_14_2  (stawka obniżona I)
     *  5       → P_13_3 / P_14_3  (stawka obniżona II)
     *  4, 3    → P_13_4 / P_14_4  (ryczałt / stawki specjalne)
     *  0 KR    → P_13_6_1          (0% krajowy, bez VAT)
     *  0 WDT   → P_13_6_2          (0% WDT, bez VAT)
     *  0 EX    → P_13_6_3          (0% eksport, bez VAT)
     *  zw      → P_13_7            (zwolnione, bez VAT)
     *  np I    → P_13_8            (poza terytorium krajowym, bez VAT)
     *  np II   → P_13_9            (usługi art. 100 ust. 1 pkt 4, bez VAT)
     *  oo      → P_13_10           (odwrotne obciążenie, bez VAT)
     * </pre>
     *
     * P_13_11 (procedura marży) NIE jest wywoływana przez kod stawki VAT —
     * wymaga oddzielnej flagi {@code PMarzy} w {@code Adnotacje}.
     */
    private void appendVatSummaries(StringBuilder sb, List<InvoiceItem> items) {
        // bucket → [sumaNetto, sumaVat]
        Map<String, BigDecimal[]> buckets = new LinkedHashMap<>();
        for (InvoiceItem item : items) {
            String code   = resolveVatRateCode(item);
            String bucket = vatCodeToBucket(code);
            buckets.computeIfAbsent(bucket, k -> new BigDecimal[]{BigDecimal.ZERO, BigDecimal.ZERO});
            BigDecimal[] acc = buckets.get(bucket);
            acc[0] = acc[0].add(item.getNetAmount());
            acc[1] = acc[1].add(item.getVatAmount());
        }

        for (Map.Entry<String, BigDecimal[]> e : buckets.entrySet()) {
            String net = scale2(e.getValue()[0]);
            String vat = scale2(e.getValue()[1]);
            switch (e.getKey()) {
                case "1"   -> { sb.append("    <P_13_1>").append(net).append("</P_13_1>\n");
                                sb.append("    <P_14_1>").append(vat).append("</P_14_1>\n"); }
                case "2"   -> { sb.append("    <P_13_2>").append(net).append("</P_13_2>\n");
                                sb.append("    <P_14_2>").append(vat).append("</P_14_2>\n"); }
                case "3"   -> { sb.append("    <P_13_3>").append(net).append("</P_13_3>\n");
                                sb.append("    <P_14_3>").append(vat).append("</P_14_3>\n"); }
                case "4"   -> { sb.append("    <P_13_4>").append(net).append("</P_13_4>\n");
                                sb.append("    <P_14_4>").append(vat).append("</P_14_4>\n"); }
                case "6_1" -> sb.append("    <P_13_6_1>").append(net).append("</P_13_6_1>\n");
                case "6_2" -> sb.append("    <P_13_6_2>").append(net).append("</P_13_6_2>\n");
                case "6_3" -> sb.append("    <P_13_6_3>").append(net).append("</P_13_6_3>\n");
                case "7"   -> sb.append("    <P_13_7>").append(net).append("</P_13_7>\n");
                case "8"   -> sb.append("    <P_13_8>").append(net).append("</P_13_8>\n");
                case "9"   -> sb.append("    <P_13_9>").append(net).append("</P_13_9>\n");
                case "10"  -> sb.append("    <P_13_10>").append(net).append("</P_13_10>\n");
                default    -> throw new KsefException(
                        "Nieznany bucket VAT: '" + e.getKey() + "'. "
                        + "Zaktualizuj vatCodeToBucket() o nowy kod TStawkaPodatku.");
            }
        }
    }

    /**
     * Mapuje kod TStawkaPodatku FA(3) na numer bucketu P_13_x.
     *
     * <p>Kody zerowe i zwolnione nie generują P_14_x (brak kwoty podatku),
     * co jest zgodne ze specyfikacją XSD — sekcje P_13_6_x … P_13_10 nie mają
     * odpowiadającego P_14_x.
     */
    private static String vatCodeToBucket(String code) {
        return switch (code) {
            case "23", "22" -> "1";   // stawka podstawowa
            case "8",  "7"  -> "2";   // stawka obniżona I
            case "5"        -> "3";   // stawka obniżona II
            case "4",  "3"  -> "4";   // ryczałt / stawki specjalne
            case "0 KR"     -> "6_1"; // 0% krajowy
            case "0 WDT"    -> "6_2"; // 0% WDT
            case "0 EX"     -> "6_3"; // 0% eksport
            case "zw"       -> "7";   // zwolnione
            case "np I"     -> "8";   // poza terytorium (bez art. 100 pkt 4)
            case "np II"    -> "9";   // usługi art. 100 ust. 1 pkt 4
            case "oo"       -> "10";  // odwrotne obciążenie
            default         -> "1";   // fallback — traktuj jak stawkę podstawową
        };
    }

    // =========================================================================
    // Adnotacje (wymagane przez XSD)
    // =========================================================================

    /**
     * Emituje sekcję {@code <Adnotacje>} z wymaganymi polami FA(3).
     *
     * <p>Pola {@code Zwolnienie}, {@code NoweSrodkiTransportu}, {@code P_23} i {@code PMarzy}
     * są hardcoded na wartość "brak" (brak zwolnienia, brak WDT nowych środków, brak procedur
     * trójstronnych, brak marży). Rozszerzenia dla faktur ze zwolnieniem z VAT (P_19, P_19A/B/C)
     * lub faktur z procedurą marży (P_PMarzy_x) wymagają dodania odpowiednich pól do encji Invoice.
     *
     * <p>Zwolnienie: gdy pozycje mają vatRateCode="zw", emitujemy P_19 z podstawą prawną
     * z pola {@code invoice.zwolnieniePodatkowe}. Fa3Validator wymaga tego pola przy "zw".
     */
    private void appendAdnotacje(StringBuilder sb, Invoice invoice) {
        boolean hasZwolnienie = invoice.getItems() != null && invoice.getItems().stream()
                .anyMatch(it -> "zw".equals(resolveVatRateCode(it)));

        sb.append("    <Adnotacje>\n");
        sb.append("      <P_16>") .append(invoice.isMetodaKasowa()                  ? "1" : "2").append("</P_16>\n");
        sb.append("      <P_17>") .append(invoice.isSamofakturowanie()              ? "1" : "2").append("</P_17>\n");
        sb.append("      <P_18>") .append(invoice.isOdwrotneObciazenie()            ? "1" : "2").append("</P_18>\n");
        sb.append("      <P_18A>").append(invoice.isMechanizmPodzielonejPlatnosci() ? "1" : "2").append("</P_18A>\n");

        if (hasZwolnienie) {
            // P_19 jest typu TWybor1 — musi zawierać wyłącznie "1" (flaga wyboru).
            // Podstawa prawna zwolnienia (tekst) idzie do P_19A.
            sb.append("      <Zwolnienie><P_19>1</P_19><P_19A>")
              .append(esc(invoice.getZwolnieniePodatkowe()))
              .append("</P_19A></Zwolnienie>\n");
        } else {
            sb.append("      <Zwolnienie><P_19N>1</P_19N></Zwolnienie>\n");
        }

        sb.append("      <NoweSrodkiTransportu><P_22N>1</P_22N></NoweSrodkiTransportu>\n");
        sb.append("      <P_23>2</P_23>\n");
        sb.append("      <PMarzy><P_PMarzyN>1</P_PMarzyN></PMarzy>\n");
        sb.append("    </Adnotacje>\n");
    }

    // =========================================================================
    // FaWiersz — pozycje faktury
    // =========================================================================

    /**
     * Emituje elementy {@code <FaWiersz>} dla każdej pozycji faktury.
     *
     * <p>Mapowanie pól wg XSD {@code FaWiersz}:
     * <ul>
     *   <li>NrWierszaFa — TNaturalny (1..N)</li>
     *   <li>P_7  — nazwa towaru/usługi (TZnakowy512, opcjonalna tylko dla KOR z art. 106j ust. 3)</li>
     *   <li>P_8A — miara / j.m. (TZnakowy, opcjonalne)</li>
     *   <li>P_8B — ilość (TIlosci: max 22 cyfry, 6 po przecinku)</li>
     *   <li>P_9A — cena jednostkowa netto (TKwotowy2: max 22 cyfry, 8 po przecinku)</li>
     *   <li>P_11 — wartość netto pozycji (TKwotowy: max 18 cyfry, 2 po przecinku)</li>
     *   <li>P_12 — stawka podatku (TStawkaPodatku)</li>
     * </ul>
     */
    private void appendFaWiersze(StringBuilder sb, List<InvoiceItem> items) {
        int nr = 1;
        for (InvoiceItem item : items) {
            sb.append("    <FaWiersz>\n");
            sb.append("      <NrWierszaFa>").append(nr++).append("</NrWierszaFa>\n");
            // P_7 (nazwa towaru/usługi) — wymagana dla VAT/ZAL/ROZ/UPR,
            // opcjonalna tylko dla KOR/KOR_ZAL/KOR_ROZ (art. 106j ust. 3 pkt 2).
            // Emitujemy zawsze gdy niepuste — brak wartości dla typów niekorygujących
            // jest wychwytywany przez Fa3Validator.
            if (hasValue(item.getName())) {
                sb.append("      <P_7>").append(esc(item.getName())).append("</P_7>\n");
            }
            if (hasValue(item.getUnit())) {
                sb.append("      <P_8A>").append(esc(item.getUnit())).append("</P_8A>\n");
            }
            // P_8B: TIlosci — 6 miejsc po przecinku
            sb.append("      <P_8B>").append(scale6(item.getQuantity())).append("</P_8B>\n");
            // P_9A: TKwotowy2 — 8 miejsc po przecinku (cena jednostkowa netto)
            sb.append("      <P_9A>").append(scale8(item.getNetUnitPrice())).append("</P_9A>\n");
            // P_11: TKwotowy — 2 miejsca po przecinku (wartość netto pozycji)
            sb.append("      <P_11>").append(scale2(item.getNetAmount())).append("</P_11>\n");
            sb.append("      <P_12>").append(resolveVatRateCode(item)).append("</P_12>\n");
            sb.append("    </FaWiersz>\n");
        }
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    /**
     * Zwraca kod stawki VAT zgodny z {@code TStawkaPodatku} FA(3).
     * Używa {@code vatRateCode} jeśli ustawiony, w przeciwnym razie mapuje numeryczny {@code vatRate}.
     */
    static String resolveVatRateCode(InvoiceItem item) {
        if (hasValue(item.getVatRateCode())) {
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
            case 0  -> "0 KR"; // domyślnie 0% krajowy; użyj vatRateCode aby wybrać WDT / EX
            default -> throw new KsefException(
                "Stawka VAT " + rate.intValue() + "% nie jest obsługiwana przez TStawkaPodatku FA(3). "
                + "Dozwolone stawki numeryczne: 23, 22, 8, 7, 5, 4, 3, 0. "
                + "Dla zwolnienia/odwrotnego obciążenia podaj vatRateCode (np. \"zw\", \"oo\").");
        };
    }

    /** Zwraca {@code value} jeśli niepusty, inaczej {@code fallback}. */
    private static String coal(String value, String fallback) {
        return hasValue(value) ? value : fallback;
    }

    private static boolean hasValue(String s) {
        return s != null && !s.isBlank();
    }

    /** Escaping XML: &amp; &lt; &gt; &quot; &apos; */
    private static String esc(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\"", "&quot;")
                .replace("'", "&apos;");
    }

    /** TKwotowy — 2 miejsca po przecinku (P_11, P_13_x, P_14_x, P_15). */
    private static String scale2(BigDecimal v) {
        if (v == null) return "0.00";
        return v.setScale(2, RoundingMode.HALF_UP).toPlainString();
    }

    /** TKwotowy2 — 8 miejsc po przecinku (P_9A — cena jednostkowa netto). */
    private static String scale8(BigDecimal v) {
        if (v == null) return "0.00000000";
        // Usuń końcowe zera powyżej 2 miejsc, ale zostaw minimum 2
        BigDecimal scaled = v.setScale(8, RoundingMode.HALF_UP).stripTrailingZeros();
        if (scaled.scale() < 2) scaled = scaled.setScale(2);
        return scaled.toPlainString();
    }

    /** TIlosci — 6 miejsc po przecinku (P_8B — ilość). */
    private static String scale6(BigDecimal v) {
        if (v == null) return "0.000000";
        return v.setScale(6, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }
}
