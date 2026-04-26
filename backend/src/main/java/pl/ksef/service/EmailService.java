package pl.ksef.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import pl.ksef.entity.AppUser;
import pl.ksef.entity.Invoice;
import pl.ksef.repository.UserNotificationEmailRepository;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Serwis wysyłania powiadomień email o nowych fakturach przychodzącej.
 * Aktywny tylko gdy app.mail.enabled=true (domyślnie false).
 */
@Service
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.mail.enabled", havingValue = "true")
public class EmailService {

    private final JavaMailSender mailSender;

    @Autowired(required = false)
    private UserNotificationEmailRepository notificationEmailRepository;

    @Value("${app.mail.from:noreply@ksef-faktury.pl}")
    private String from;

    @Value("${app.ksef.viewer-url:https://qr-test.ksef.mf.gov.pl/invoice}")
    private String ksefViewerBaseUrl;

    // Format daty w URL wizualizacji KSeF v2: DD-MM-YYYY
    private static final DateTimeFormatter VIEWER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    // TODO(F6): Wstrzyknąć KsefPdfService (opcjonalnie — unikać cyklu zależności).
    // @Autowired(required = false)
    // private KsefPdfService ksefPdfService;

    /**
     * Wysyła powiadomienie o nowej fakturze przychodzącej.
     * Zwraca listę adresów, na które wysyłka się nie powiodła.
     */
    public List<String> sendNewInvoiceNotification(AppUser recipient, Invoice invoice) {
        List<String> recipients = resolveRecipients(recipient);
        if (recipients.isEmpty()) {
            log.warn("Brak adresów email dla użytkownika {}, pomijam powiadomienie", recipient.getId());
            return List.of();
        }
        String subject = "Nowa faktura od " + invoice.getSellerName() + " — " + formatAmount(invoice);
        String html = buildNewInvoiceHtml(recipient, invoice);
        return sendToAll(recipients, subject, html);
    }

    /**
     * Wysyła email potwierdzający że faktura ISSUED została zaakceptowana przez KSeF.
     * Zwraca listę adresów, na które wysyłka się nie powiodła.
     */
    public List<String> sendInvoiceSentConfirmation(AppUser sender, Invoice invoice) {
        List<String> recipients = resolveRecipients(sender);
        if (recipients.isEmpty()) {
            log.warn("Brak adresów email dla użytkownika {}, pomijam potwierdzenie wysyłki", sender.getId());
            return List.of();
        }
        String subject = "Faktura " + invoice.getInvoiceNumber() + " wysłana do KSeF ✓";
        String html = buildSentConfirmationHtml(sender, invoice);
        return sendToAll(recipients, subject, html);
    }

    // ---- private helpers ----

    private List<String> sendToAll(List<String> addresses, String subject, String html) {
        List<String> failed = new ArrayList<>();
        for (String address : addresses) {
            try {
                MimeMessage message = mailSender.createMimeMessage();
                MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
                helper.setFrom(from);
                helper.setTo(address);
                helper.setSubject(subject);
                helper.setText(html, true);
                // TODO(F6): attachKsefPdf(helper, invoice);
                mailSender.send(message);
                log.info("Email wysłany do {}: {}", address, subject);
            } catch (Exception e) {
                log.error("Błąd wysyłania email do {}: {}", address, e.getMessage(), e);
                failed.add(address);
            }
        }
        return failed;
    }

    /**
     * Pobiera listę adresów email do powiadomień dla użytkownika.
     * Fallback na users.email gdy użytkownik nie skonfigurował dedykowanych adresów.
     */
    private List<String> resolveRecipients(AppUser user) {
        if (notificationEmailRepository != null) {
            List<String> configured = notificationEmailRepository
                    .findByUserIdOrderBySortOrderAsc(user.getId())
                    .stream()
                    .map(pl.ksef.entity.UserNotificationEmail::getEmail)
                    .toList();
            if (!configured.isEmpty()) return configured;
        }
        if (user.getEmail() != null && !user.getEmail().isBlank()) {
            return List.of(user.getEmail());
        }
        return List.of();
    }

    /**
     * Buduje szkielet HTML wiadomości email.
     *
     * @param accentColor kolor nagłówka (np. "#2563eb" dla niebieski, "#16a34a" dla zielony)
     * @param title       tekst nagłówka
     * @param body        zawartość między nagłówkiem a stopką (tabela + opcjonalny viewerBlock)
     * @param footerNote  dodatkowy tekst w stopce
     */
    private static String buildEmailHtml(String accentColor, String title,
                                         String body, String footerNote) {
        return """
                <!DOCTYPE html>
                <html lang="pl">
                <head><meta charset="UTF-8"/></head>
                <body style="font-family:Arial,sans-serif;color:#1a1a1a;max-width:600px;margin:0 auto;padding:24px">

                  <div style="border-bottom:3px solid %s;padding-bottom:16px;margin-bottom:24px">
                    <h2 style="margin:0;color:%s">%s</h2>
                  </div>

                  %s

                  <hr style="border:none;border-top:1px solid #e5e7eb;margin:28px 0"/>
                  <p style="font-size:12px;color:#9ca3af">
                    Wiadomość wygenerowana automatycznie przez system KSeF Faktury.<br/>
                    %s Nie odpowiadaj na tę wiadomość.
                  </p>
                </body>
                </html>
                """.formatted(accentColor, accentColor, title, body, footerNote);
    }

    private String buildViewerBlock(Invoice invoice) {
        if (invoice.getInvoiceHash() == null
                || invoice.getSellerNip() == null
                || invoice.getIssueDate() == null) {
            return "";
        }
        String dateFormatted = invoice.getIssueDate().format(VIEWER_DATE_FORMAT);
        String viewerUrl = ksefViewerBaseUrl
                + "/" + invoice.getSellerNip()
                + "/" + dateFormatted
                + "/" + toUrlSafeBase64(invoice.getInvoiceHash());
        return """
                <p style="margin-top:24px">
                  <a href="%s"
                     style="display:inline-block;background:#2563eb;color:#fff;padding:12px 24px;
                            border-radius:6px;text-decoration:none;font-weight:bold">
                    Wyświetl fakturę w KSeF &#8594;
                  </a>
                </p>
                <p style="font-size:12px;color:#6b7280;margin-top:6px">
                  Lub skopiuj link: <a href="%s" style="color:#2563eb">%s</a>
                </p>
                """.formatted(viewerUrl, viewerUrl, viewerUrl);
    }

    private String buildNewInvoiceHtml(AppUser recipient, Invoice invoice) {
        String amount = formatAmount(invoice);
        String body = """
                <p>Witaj <strong>%s</strong>,</p>
                <p>Do Twojego konta w KSeF wpłynęła nowa faktura.</p>

                <table style="width:100%%;border-collapse:collapse;margin:20px 0;border:1px solid #e5e7eb;border-radius:8px;overflow:hidden">
                  <tr style="background:#f3f4f6">
                    <td style="padding:10px 16px;font-weight:bold;width:42%%;border-bottom:1px solid #e5e7eb">Wystawiający</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb">%s</td>
                  </tr>
                  <tr>
                    <td style="padding:10px 16px;font-weight:bold;border-bottom:1px solid #e5e7eb">NIP wystawiającego</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb">%s</td>
                  </tr>
                  <tr style="background:#f3f4f6">
                    <td style="padding:10px 16px;font-weight:bold;border-bottom:1px solid #e5e7eb">Numer faktury</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb;font-family:monospace">%s</td>
                  </tr>
                  <tr>
                    <td style="padding:10px 16px;font-weight:bold;border-bottom:1px solid #e5e7eb">Data wystawienia</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb">%s</td>
                  </tr>
                  <tr style="background:#f3f4f6">
                    <td style="padding:10px 16px;font-weight:bold">Kwota brutto</td>
                    <td style="padding:10px 16px;font-size:20px;font-weight:bold;color:#16a34a">%s</td>
                  </tr>
                </table>

                %s
                """.formatted(
                recipient.getCompanyName(),
                invoice.getSellerName(),
                invoice.getSellerNip(),
                invoice.getInvoiceNumber(),
                invoice.getIssueDate(),
                amount,
                buildViewerBlock(invoice));
        return buildEmailHtml("#2563eb", "KSeF Faktury — nowa faktura przychodząca",
                body, "Powiadomienia są wysyłane co 2 godziny.");
    }

    private String buildSentConfirmationHtml(AppUser sender, Invoice invoice) {
        String amount = formatAmount(invoice);
        String body = """
                <p>Witaj <strong>%s</strong>,</p>
                <p>Twoja faktura została pomyślnie przyjęta przez system KSeF i otrzymała oficjalny numer.</p>

                <table style="width:100%%;border-collapse:collapse;margin:20px 0;border:1px solid #e5e7eb;border-radius:8px;overflow:hidden">
                  <tr style="background:#f3f4f6">
                    <td style="padding:10px 16px;font-weight:bold;width:42%%;border-bottom:1px solid #e5e7eb">Numer faktury</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb;font-family:monospace">%s</td>
                  </tr>
                  <tr>
                    <td style="padding:10px 16px;font-weight:bold;border-bottom:1px solid #e5e7eb">Nabywca</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb">%s</td>
                  </tr>
                  <tr style="background:#f3f4f6">
                    <td style="padding:10px 16px;font-weight:bold;border-bottom:1px solid #e5e7eb">NIP nabywcy</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb">%s</td>
                  </tr>
                  <tr>
                    <td style="padding:10px 16px;font-weight:bold;border-bottom:1px solid #e5e7eb">Data wystawienia</td>
                    <td style="padding:10px 16px;border-bottom:1px solid #e5e7eb">%s</td>
                  </tr>
                  <tr style="background:#f3f4f6">
                    <td style="padding:10px 16px;font-weight:bold;border-bottom:1px solid #e5e7eb">Kwota brutto</td>
                    <td style="padding:10px 16px;font-size:20px;font-weight:bold;color:#16a34a">%s</td>
                  </tr>
                  <tr>
                    <td style="padding:10px 16px;font-weight:bold">Numer KSeF</td>
                    <td style="padding:10px 16px;font-family:monospace;font-size:12px;color:#6b7280">%s</td>
                  </tr>
                </table>

                %s
                """.formatted(
                sender.getCompanyName(),
                invoice.getInvoiceNumber(),
                invoice.getBuyerName(),
                invoice.getBuyerNip(),
                invoice.getIssueDate(),
                amount,
                invoice.getKsefNumber() != null ? invoice.getKsefNumber() : "—",
                buildViewerBlock(invoice));
        return buildEmailHtml("#16a34a", "KSeF Faktury &#10003; Faktura wysłana do KSeF", body, "");
    }

    /**
     * Konwertuje Base64 (standard) na Base64 URL-safe wymagane przez wizualizator KSeF v2.
     * '+' → '-', '/' → '_', usuwa padding '='.
     */
    private static String toUrlSafeBase64(String base64) {
        return base64.replace('+', '-').replace('/', '_').replace("=", "");
    }

    private String formatAmount(Invoice invoice) {
        NumberFormat fmt = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));
        return fmt.format(invoice.getGrossAmount());
    }
}
