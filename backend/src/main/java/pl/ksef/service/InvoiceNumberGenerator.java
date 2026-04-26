package pl.ksef.service;

import org.springframework.stereotype.Component;
import pl.ksef.entity.AppUser;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;

/**
 * Generuje ostateczny numer faktury na podstawie numeru bazowego i trybu prefiksu użytkownika.
 *
 * <p>Tryb YEAR_MONTH dodaje prefiks "YYYY/MM/" przed numerem bazowym.
 * Przykład: applyPrefix("7", YEAR_MONTH, 2026-04-15) → "2026/04/7"
 */
@Component
public class InvoiceNumberGenerator {

    private static final DateTimeFormatter YEAR_MONTH_FORMAT =
            DateTimeFormatter.ofPattern("yyyy/MM");

    public String applyPrefix(String rawNumber,
                               AppUser.InvoiceNumberPrefixMode mode,
                               LocalDate issueDate) {
        if (mode == AppUser.InvoiceNumberPrefixMode.YEAR_MONTH && issueDate != null) {
            return issueDate.format(YEAR_MONTH_FORMAT) + "/" + rawNumber;
        }
        return rawNumber;
    }
}
