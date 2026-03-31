package pl.ksef.service.ksef;

import org.springframework.stereotype.Component;
import pl.ksef.dto.InvoiceDto;

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

        if (req.getItems() == null || req.getItems().isEmpty()) {
            errors.add("items: Faktura musi zawierać co najmniej jedną pozycję (FA(3))");
        } else {
            for (int i = 0; i < req.getItems().size(); i++) {
                validateItem(req.getItems().get(i), i + 1, errors);
            }
        }

        if (!errors.isEmpty()) {
            throw new IllegalArgumentException("Błędy walidacji FA(3):\n" + String.join("\n", errors));
        }
    }

    private void validateItem(InvoiceDto.ItemRequest item, int pos, List<String> errors) {
        String prefix = "items[" + pos + "]";

        if (item.getName() == null || item.getName().isBlank()) {
            errors.add(prefix + ".name: Nazwa pozycji jest wymagana (P_7 FA(3))");
        } else if (item.getName().length() > 256) {
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
