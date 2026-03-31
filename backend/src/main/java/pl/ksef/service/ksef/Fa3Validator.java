package pl.ksef.service.ksef;

import org.springframework.stereotype.Component;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.entity.Invoice;
import pl.ksef.entity.InvoiceItem;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Walidacja danych faktury zgodnie ze schematem FA(3) KSeF
 * (http://crd.gov.pl/wzor/2025/06/25/13775/).
 */
@Component
public class Fa3Validator {

    private static final Set<String> VALID_CURRENCIES = Set.of(
        "AED","AFN","ALL","AMD","ANG","AOA","ARS","AUD","AWG","AZN",
        "BAM","BBD","BDT","BGN","BHD","BIF","BMD","BND","BOB","BOV","BRL","BSD","BTN","BWP","BYN","BZD",
        "CAD","CDF","CHE","CHF","CHW","CLF","CLP","CNY","COP","COU","CRC","CUC","CUP","CVE","CZK",
        "DJF","DKK","DOP","DZD","EGP","ERN","ETB","EUR",
        "FJD","FKP","GBP","GEL","GGP","GHS","GIP","GMD","GNF","GTQ","GYD",
        "HKD","HNL","HRK","HTG","HUF","IDR","ILS","IMP","INR","IQD","IRR","ISK",
        "JEP","JMD","JOD","JPY","KES","KGS","KHR","KMF","KPW","KRW","KWD","KYD","KZT",
        "LAK","LBP","LKR","LRD","LSL","LYD","MAD","MDL","MGA","MKD","MMK","MNT","MOP","MRU",
        "MUR","MVR","MWK","MXN","MXV","MYR","MZN","NAD","NGN","NIO","NOK","NPR","NZD",
        "OMR","PAB","PEN","PGK","PHP","PKR","PLN","PYG","QAR","RON","RSD","RUB","RWF",
        "SAR","SBD","SCR","SDG","SEK","SGD","SHP","SLL","SOS","SRD","SSP","STN","SVC","SYP","SZL",
        "THB","TJS","TMT","TND","TOP","TRY","TTD","TWD","TZS","UAH","UGX","USD","USN",
        "UYI","UYU","UYW","UZS","VES","VND","VUV","WST",
        "XAF","XAG","XAU","XBA","XBB","XBC","XBD","XCD","XCG","XDR","XOF","XPD","XPF","XPT","XSU","XUA","XXX",
        "YER","ZAR","ZMW","ZWL"
    );

    private static final Set<String> VALID_RODZAJ_FAKTURY = Set.of(
        "VAT","KOR","ZAL","ROZ","UPR","KOR_ZAL","KOR_ROZ"
    );

    /** Typy faktur, dla których lista pozycji (FaWiersz) jest opcjonalna wg XSD. */
    private static final Set<String> TYPES_WITHOUT_REQUIRED_ITEMS = Set.of(
        "ZAL","ROZ","KOR","KOR_ZAL","KOR_ROZ"
    );

    /** Typy faktur korygujących — P_7 (nazwa) może być pominięta. */
    private static final Set<String> CORRECTION_TYPES = Set.of(
        "KOR","KOR_ZAL","KOR_ROZ"
    );

    /** Kody krajów UE zgodne z TKodyKrajowUE w schemacie FA(3). */
    private static final Set<String> VALID_EU_COUNTRY_CODES = Set.of(
        "AT","BE","BG","CY","CZ","DK","EE","FI","FR","DE","EL","HR",
        "HU","IE","IT","LV","LT","LU","MT","NL","PL","PT","RO","SK",
        "SI","ES","SE","XI"
    );

    /** Dozwolone kody stawki VAT zgodne z TStawkaPodatku w FA(3). */
    public static final Set<String> VALID_VAT_RATE_CODES = Set.of(
        "23","22","8","7","5","4","3","0 KR","0 WDT","0 EX","zw","oo","np I","np II"
    );

    /** Numeryczne stawki VAT obsługiwane przez FA(3) (mapowane na kody). */
    private static final Set<Integer> VALID_NUMERIC_VAT_RATES = Set.of(23, 22, 8, 7, 5, 4, 3, 0);

    /**
     * Waliduje wniosek CreateRequest pod kątem zgodności z FA(3).
     * Rzuca IllegalArgumentException z opisem wszystkich napotkanych błędów.
     */
    public void validate(InvoiceDto.CreateRequest req) {
        List<String> errors = new ArrayList<>();

        validateNip(req.getSellerNip(), "sellerNip", errors);
        validateNip(req.getBuyerNip(), "buyerNip", errors);

        if (req.getCurrency() != null && !VALID_CURRENCIES.contains(req.getCurrency())) {
            errors.add("currency: Kod waluty '" + req.getCurrency() + "' nie jest obsługiwany przez FA(3)");
        }

        if (req.getRodzajFaktury() != null && !VALID_RODZAJ_FAKTURY.contains(req.getRodzajFaktury())) {
            errors.add("rodzajFaktury: Nieprawidłowa wartość '" + req.getRodzajFaktury()
                    + "'. Dozwolone: " + VALID_RODZAJ_FAKTURY);
        }

        // TDataT: min 2006-01-01
        if (req.getIssueDate() != null && req.getIssueDate().isBefore(LocalDate.of(2006, 1, 1))) {
            errors.add("issueDate: Data wystawienia nie może być wcześniejsza niż 2006-01-01 (ograniczenie TDataT)");
        }

        // FA(3) Podmiot1.Adres.KodKraju + AdresL1 — adres jest wymagany
        if (req.getSellerAddress() == null || req.getSellerAddress().isBlank()) {
            errors.add("sellerAddress: Adres sprzedawcy jest wymagany w schemacie FA(3)");
        }

        // TKodyKrajowUE — tylko kody krajów UE są dozwolone
        if (req.getSellerCountryCode() != null && !VALID_EU_COUNTRY_CODES.contains(req.getSellerCountryCode())) {
            errors.add("sellerCountryCode: Kod '" + req.getSellerCountryCode()
                    + "' nie należy do TKodyKrajowUE FA(3). Dozwolone: " + VALID_EU_COUNTRY_CODES);
        }
        if (req.getBuyerCountryCode() != null && !VALID_EU_COUNTRY_CODES.contains(req.getBuyerCountryCode())) {
            errors.add("buyerCountryCode: Kod '" + req.getBuyerCountryCode()
                    + "' nie należy do TKodyKrajowUE FA(3). Dozwolone: " + VALID_EU_COUNTRY_CODES);
        }

        String rodzaj = req.getRodzajFaktury() != null ? req.getRodzajFaktury() : "VAT";
        boolean itemsRequired = !TYPES_WITHOUT_REQUIRED_ITEMS.contains(rodzaj);
        boolean hasItems = req.getItems() != null && !req.getItems().isEmpty();

        if (itemsRequired && !hasItems) {
            errors.add("items: Faktura typu " + rodzaj + " musi zawierać co najmniej jedną pozycję (FA(3))");
        }

        if (hasItems) {
            boolean hasZwolnienie = req.getItems().stream()
                    .anyMatch(it -> "zw".equals(it.getVatRateCode()));
            if (hasZwolnienie && (req.getZwolnieniePodatkowe() == null || req.getZwolnieniePodatkowe().isBlank())) {
                errors.add("zwolnieniePodatkowe: Podstawa prawna zwolnienia jest wymagana gdy pozycja ma vatRateCode=\"zw\" (pole P_19 FA(3))");
            }

            for (int i = 0; i < req.getItems().size(); i++) {
                validateItem(req.getItems().get(i), i + 1, CORRECTION_TYPES.contains(rodzaj), errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Błędy walidacji FA(3):\n" + String.join("\n", errors));
        }
    }

    /**
     * @param isCorrection true dla typów KOR/KOR_ZAL/KOR_ROZ — wtedy P_7 (name) jest opcjonalne
     */
    private void validateItem(InvoiceDto.ItemRequest item, int pos, boolean isCorrection, List<String> errors) {
        String prefix = "items[" + pos + "]";

        if (!isCorrection && (item.getName() == null || item.getName().isBlank())) {
            errors.add(prefix + ".name: Nazwa pozycji jest wymagana (P_7 FA(3))");
        } else if (item.getName() != null && item.getName().length() > 256) {
            errors.add(prefix + ".name: Nazwa pozycji nie może przekraczać 256 znaków (P_7 FA(3))");
        }

        if (item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(prefix + ".quantity: Ilość musi być większa od 0 (FA(3))");
        }

        if (item.getNetUnitPrice() == null || item.getNetUnitPrice().compareTo(BigDecimal.ZERO) == 0) {
            errors.add(prefix + ".netUnitPrice: Cena jednostkowa netto nie może być równa 0 (FA(3))");
        }

        if (item.getVatRateCode() != null && !item.getVatRateCode().isBlank()) {
            if (!VALID_VAT_RATE_CODES.contains(item.getVatRateCode())) {
                errors.add(prefix + ".vatRateCode: Niedozwolona stawka '" + item.getVatRateCode()
                        + "'. Dozwolone kody FA(3): " + VALID_VAT_RATE_CODES);
            }
        } else if (item.getVatRate() != null) {
            int intRate = item.getVatRate().intValue();
            if (!VALID_NUMERIC_VAT_RATES.contains(intRate)) {
                errors.add(prefix + ".vatRate: Stawka " + item.getVatRate()
                        + "% nie jest obsługiwana w FA(3). Dozwolone: 23, 22, 8, 7, 5, 4, 3, 0. "
                        + "Dla zwolnienia/odwrotnego obciążenia podaj vatRateCode (np. \"zw\", \"oo\")");
            }
        }
    }

    /**
     * Waliduje encję Invoice (np. przed wysyłką szkicu do KSeF).
     * Stosuje te same reguły FA(3) co {@link #validate(InvoiceDto.CreateRequest)}.
     */
    public void validate(Invoice invoice) {
        List<String> errors = new ArrayList<>();

        validateNip(invoice.getSellerNip(), "sellerNip", errors);
        validateNip(invoice.getBuyerNip(), "buyerNip", errors);

        if (invoice.getCurrency() != null && !VALID_CURRENCIES.contains(invoice.getCurrency())) {
            errors.add("currency: Kod waluty '" + invoice.getCurrency() + "' nie jest obsługiwany przez FA(3)");
        }

        if (invoice.getRodzajFaktury() != null && !VALID_RODZAJ_FAKTURY.contains(invoice.getRodzajFaktury())) {
            errors.add("rodzajFaktury: Nieprawidłowa wartość '" + invoice.getRodzajFaktury()
                    + "'. Dozwolone: " + VALID_RODZAJ_FAKTURY);
        }

        if (invoice.getIssueDate() != null && invoice.getIssueDate().isBefore(LocalDate.of(2006, 1, 1))) {
            errors.add("issueDate: Data wystawienia nie może być wcześniejsza niż 2006-01-01 (ograniczenie TDataT)");
        }

        if (invoice.getSellerAddress() == null || invoice.getSellerAddress().isBlank()) {
            errors.add("sellerAddress: Adres sprzedawcy jest wymagany w schemacie FA(3)");
        }

        if (invoice.getSellerCountryCode() != null && !VALID_EU_COUNTRY_CODES.contains(invoice.getSellerCountryCode())) {
            errors.add("sellerCountryCode: Kod '" + invoice.getSellerCountryCode()
                    + "' nie należy do TKodyKrajowUE FA(3). Dozwolone: " + VALID_EU_COUNTRY_CODES);
        }
        if (invoice.getBuyerCountryCode() != null && !VALID_EU_COUNTRY_CODES.contains(invoice.getBuyerCountryCode())) {
            errors.add("buyerCountryCode: Kod '" + invoice.getBuyerCountryCode()
                    + "' nie należy do TKodyKrajowUE FA(3). Dozwolone: " + VALID_EU_COUNTRY_CODES);
        }

        String rodzaj = invoice.getRodzajFaktury() != null ? invoice.getRodzajFaktury() : "VAT";
        boolean itemsRequired = !TYPES_WITHOUT_REQUIRED_ITEMS.contains(rodzaj);
        boolean isCorrection = CORRECTION_TYPES.contains(rodzaj);
        boolean hasItems = invoice.getItems() != null && !invoice.getItems().isEmpty();

        if (itemsRequired && !hasItems) {
            errors.add("items: Faktura typu " + rodzaj + " musi zawierać co najmniej jedną pozycję (FA(3))");
        }

        if (hasItems) {
            boolean hasZwolnienie = invoice.getItems().stream()
                    .anyMatch(it -> "zw".equals(it.getVatRateCode()));
            if (hasZwolnienie && (invoice.getZwolnieniePodatkowe() == null || invoice.getZwolnieniePodatkowe().isBlank())) {
                errors.add("zwolnieniePodatkowe: Podstawa prawna zwolnienia jest wymagana gdy pozycja ma vatRateCode=\"zw\" (pole P_19 FA(3))");
            }

            int pos = 1;
            for (InvoiceItem item : invoice.getItems()) {
                validateEntityItem(item, pos++, isCorrection, errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Błędy walidacji FA(3):\n" + String.join("\n", errors));
        }
    }

    /**
     * @param isCorrection true dla typów KOR/KOR_ZAL/KOR_ROZ — wtedy P_7 (name) jest opcjonalne
     */
    private void validateEntityItem(InvoiceItem item, int pos, boolean isCorrection, List<String> errors) {
        String prefix = "items[" + pos + "]";

        if (!isCorrection && (item.getName() == null || item.getName().isBlank())) {
            errors.add(prefix + ".name: Nazwa pozycji jest wymagana (P_7 FA(3))");
        } else if (item.getName() != null && item.getName().length() > 256) {
            errors.add(prefix + ".name: Nazwa pozycji nie może przekraczać 256 znaków (P_7 FA(3))");
        }

        if (item.getQuantity() == null || item.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
            errors.add(prefix + ".quantity: Ilość musi być większa od 0 (FA(3))");
        }

        if (item.getNetUnitPrice() == null || item.getNetUnitPrice().compareTo(BigDecimal.ZERO) == 0) {
            errors.add(prefix + ".netUnitPrice: Cena jednostkowa netto nie może być równa 0 (FA(3))");
        }

        if (item.getVatRateCode() != null && !item.getVatRateCode().isBlank()) {
            if (!VALID_VAT_RATE_CODES.contains(item.getVatRateCode())) {
                errors.add(prefix + ".vatRateCode: Niedozwolona stawka '" + item.getVatRateCode()
                        + "'. Dozwolone kody FA(3): " + VALID_VAT_RATE_CODES);
            }
        } else if (item.getVatRate() != null) {
            int intRate = item.getVatRate().intValue();
            if (!VALID_NUMERIC_VAT_RATES.contains(intRate)) {
                errors.add(prefix + ".vatRate: Stawka " + item.getVatRate()
                        + "% nie jest obsługiwana w FA(3). Dozwolone: 23, 22, 8, 7, 5, 4, 3, 0. "
                        + "Dla zwolnienia/odwrotnego obciążenia podaj vatRateCode (np. \"zw\", \"oo\")");
            }
        }
    }

    /**
     * Waliduje format i cyfrę kontrolną NIP.
     * @return true jeśli NIP jest prawidłowy
     */
    public static boolean validateNip(String nip, String fieldName, List<String> errors) {
        if (nip == null || !nip.matches("\\d{10}")) {
            errors.add(fieldName + ": NIP musi składać się z dokładnie 10 cyfr");
            return false;
        }
        int[] weights = {6, 5, 7, 2, 3, 4, 5, 6, 7};
        int sum = 0;
        for (int i = 0; i < 9; i++) {
            sum += weights[i] * (nip.charAt(i) - '0');
        }
        int control = sum % 11;
        if (control == 10 || control != (nip.charAt(9) - '0')) {
            errors.add(fieldName + ": Nieprawidłowy NIP — błędna cyfra kontrolna");
            return false;
        }
        return true;
    }
}
