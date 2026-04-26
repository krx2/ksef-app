package pl.ksef.config;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Dodaje podstawowe nagłówki bezpieczeństwa HTTP do każdej odpowiedzi.
 * Obowiązuje przed wdrożeniem pełnego Spring Security + JWT.
 */
@Component
@Order(1)
public class SecurityHeadersFilter implements Filter {

    @Override
    public void doFilter(ServletRequest request, ServletResponse response,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletResponse res = (HttpServletResponse) response;

        // Zapobiega MIME-type sniffing — przeglądarka respektuje Content-Type z nagłówka
        res.setHeader("X-Content-Type-Options", "nosniff");

        // Blokuje osadzanie aplikacji w ramkach (clickjacking)
        res.setHeader("X-Frame-Options", "DENY");

        // Wymusza HTTPS przez 1 rok (dotyczy środowiska produkcyjnego)
        res.setHeader("Strict-Transport-Security", "max-age=31536000; includeSubDomains");

        // Wyłącza przestarzałą ochronę XSS wbudowaną w starsze przeglądarki
        // (nowoczesne przeglądarki ignorują ten nagłówek — ustawienie 0 jest bezpieczniejsze)
        res.setHeader("X-XSS-Protection", "0");

        // Ogranicza referrer do origin przy cross-origin requestach
        res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");

        chain.doFilter(request, response);
    }
}
