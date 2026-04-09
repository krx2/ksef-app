package pl.ksef.service;

import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Service;
import pl.ksef.entity.AppUser;
import pl.ksef.entity.Invoice;

import java.text.NumberFormat;
import java.time.format.DateTimeFormatter;
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

    @Value("${app.mail.from:noreply@ksef-faktury.pl}")
    private String from;

    @Value("${app.ksef.viewer-url:https://qr-test.ksef.mf.gov.pl/invoice}")
    private String ksefViewerBaseUrl;

    // Format daty w URL wizualizacji KSeF v2: DD-MM-YYYY
    private static final DateTimeFormatter VIEWER_DATE_FORMAT =
            DateTimeFormatter.ofPattern("dd-MM-yyyy");

    public void sendNewInvoiceNotification(AppUser recipient, Invoice invoice) {
        if (recipient.getEmail() == null || recipient.getEmail().isBlank()) {
            log.warn("Brak adresu email dla użytkownika {}, pomijam powiadomienie", recipient.getId());
            return;
        }
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setFrom(from);
            helper.setTo(recipient.getEmail());
            helper.setSubject("Nowa faktura od " + invoice.getSellerName()
                    + " — " + formatAmount(invoice));
            helper.setText(buildHtml(recipient, invoice), true);
            mailSender.send(message);
            log.info("Email o nowej fakturze wysłany do {} (ksefNumber={})",
                    recipient.getEmail(), invoice.getKsefNumber());
        } catch (Exception e) {
            log.error("Błąd wysyłania email do {}: {}", recipient.getEmail(), e.getMessage(), e);
        }
    }

    private String formatAmount(Invoice invoice) {
        NumberFormat fmt = NumberFormat.getCurrencyInstance(new Locale("pl", "PL"));
        return fmt.format(invoice.getGrossAmount());
    }

    private String buildHtml(AppUser recipient, Invoice invoice) {
        // Format URL wizualizacji KSeF v2:
        // {base}/{nip-wystawiającego}/{data-DD-MM-YYYY}/{invoiceHash}
        // Przykład: https://qr-test.ksef.mf.gov.pl/invoice/6793103760/08-04-2026/jd622ZsalcIn...
        // invoiceHash to SHA-256 faktury (Base64) z SessionInvoiceStatusResponse lub QueryMetadataResponse
        String viewerUrl = null;
        if (invoice.getInvoiceHash() != null
                && invoice.getSellerNip() != null
                && invoice.getIssueDate() != null) {
            String dateFormatted = invoice.getIssueDate().format(VIEWER_DATE_FORMAT);
            viewerUrl = ksefViewerBaseUrl
                    + "/" + invoice.getSellerNip()
                    + "/" + dateFormatted
                    + "/" + invoice.getInvoiceHash();
        }
        String amount = formatAmount(invoice);

        String viewerBlock = "";
        if (viewerUrl != null) {
            viewerBlock = """
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

        return """
                <!DOCTYPE html>
                <html lang="pl">
                <head><meta charset="UTF-8"/></head>
                <body style="font-family:Arial,sans-serif;color:#1a1a1a;max-width:600px;margin:0 auto;padding:24px">

                  <div style="border-bottom:3px solid #2563eb;padding-bottom:16px;margin-bottom:24px">
                    <h2 style="margin:0;color:#2563eb">KSeF Faktury — nowa faktura przychodząca</h2>
                  </div>

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

                  <hr style="border:none;border-top:1px solid #e5e7eb;margin:28px 0"/>
                  <p style="font-size:12px;color:#9ca3af">
                    Wiadomość wygenerowana automatycznie przez system KSeF Faktury.<br/>
                    Powiadomienia są wysyłane co 2 godziny. Nie odpowiadaj na tę wiadomość.
                  </p>
                </body>
                </html>
                """.formatted(
                recipient.getCompanyName(),
                invoice.getSellerName(),
                invoice.getSellerNip(),
                invoice.getInvoiceNumber(),
                invoice.getIssueDate(),
                amount,
                viewerBlock
        );
    }
}
