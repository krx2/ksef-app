package pl.ksef.service.ksef;

import org.springframework.stereotype.Component;
import pl.ksef.entity.Invoice;
import pl.ksef.entity.InvoiceItem;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.format.DateTimeFormatter;

/**
 * Builds minimal FA(2) XML compliant with KSeF schema.
 * Reference: https://www.podatki.gov.pl/ksef/dokumenty-do-pobrania/
 */
@Component
public class Fa2XmlBuilder {

    private static final String FA2_SCHEMA = "FA(2)";
    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

    public String build(Invoice invoice) {
        StringBuilder sb = new StringBuilder();
        sb.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        sb.append("<Faktura xmlns=\"http://crd.gov.pl/wzor/2023/06/29/12648/\" ");
        sb.append("xmlns:xsi=\"http://www.w3.org/2001/XMLSchema-instance\">\n");

        // Naglowek
        sb.append("  <Naglowek>\n");
        sb.append("    <KodFormularza wersjaSchemy=\"1-0E\" kodSystemowy=\"FA (2)\">FA</KodFormularza>\n");
        sb.append("    <WariantFormularza>2</WariantFormularza>\n");
        sb.append("    <DataWytworzeniaFa>").append(invoice.getIssueDate().format(DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss"))).append("</DataWytworzeniaFa>\n");
        sb.append("    <SystemInfo>KSeF-App v1.0</SystemInfo>\n");
        sb.append("  </Naglowek>\n");

        // Podmiot1 (Seller)
        sb.append("  <Podmiot1>\n");
        sb.append("    <DaneIdentyfikacyjne>\n");
        sb.append("      <NIP>").append(escapeXml(invoice.getSellerNip())).append("</NIP>\n");
        sb.append("      <PelnaNazwa>").append(escapeXml(invoice.getSellerName())).append("</PelnaNazwa>\n");
        sb.append("    </DaneIdentyfikacyjne>\n");
        if (invoice.getSellerAddress() != null) {
            sb.append("    <Adres>\n");
            sb.append("      <AdresL1>").append(escapeXml(invoice.getSellerAddress())).append("</AdresL1>\n");
            sb.append("    </Adres>\n");
        }
        sb.append("  </Podmiot1>\n");

        // Podmiot2 (Buyer)
        sb.append("  <Podmiot2>\n");
        sb.append("    <DaneIdentyfikacyjne>\n");
        sb.append("      <NIP>").append(escapeXml(invoice.getBuyerNip())).append("</NIP>\n");
        sb.append("      <PelnaNazwa>").append(escapeXml(invoice.getBuyerName())).append("</PelnaNazwa>\n");
        sb.append("    </DaneIdentyfikacyjne>\n");
        if (invoice.getBuyerAddress() != null) {
            sb.append("    <Adres>\n");
            sb.append("      <AdresL1>").append(escapeXml(invoice.getBuyerAddress())).append("</AdresL1>\n");
            sb.append("    </Adres>\n");
        }
        sb.append("  </Podmiot2>\n");

        // Fa (invoice details)
        sb.append("  <Fa>\n");
        sb.append("    <KodWaluty>").append(invoice.getCurrency()).append("</KodWaluty>\n");
        sb.append("    <P_1>").append(invoice.getIssueDate().format(DATE_FMT)).append("</P_1>\n");
        sb.append("    <P_2>").append(escapeXml(invoice.getInvoiceNumber())).append("</P_2>\n");
        if (invoice.getSaleDate() != null) {
            sb.append("    <P_6>").append(invoice.getSaleDate().format(DATE_FMT)).append("</P_6>\n");
        }

        // Line items
        int pos = 1;
        for (InvoiceItem item : invoice.getItems()) {
            sb.append("    <FaWiersz>\n");
            sb.append("      <NrWierszaFa>").append(pos++).append("</NrWierszaFa>\n");
            sb.append("      <P_7>").append(escapeXml(item.getName())).append("</P_7>\n");
            if (item.getUnit() != null) {
                sb.append("      <P_8A>").append(escapeXml(item.getUnit())).append("</P_8A>\n");
            }
            sb.append("      <P_8B>").append(item.getQuantity().toPlainString()).append("</P_8B>\n");
            sb.append("      <P_9A>").append(item.getNetUnitPrice().setScale(2, RoundingMode.HALF_UP).toPlainString()).append("</P_9A>\n");
            sb.append("      <P_11>").append(item.getNetAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()).append("</P_11>\n");
            sb.append("      <P_12>").append(vatRateCode(item.getVatRate())).append("</P_12>\n");
            sb.append("    </FaWiersz>\n");
        }

        // Totals by VAT rate
        appendVatSummary(sb, invoice);

        sb.append("    <P_15>").append(invoice.getGrossAmount().setScale(2, RoundingMode.HALF_UP).toPlainString()).append("</P_15>\n");
        sb.append("  </Fa>\n");
        sb.append("</Faktura>");
        return sb.toString();
    }

    private void appendVatSummary(StringBuilder sb, Invoice invoice) {
        // Group items by VAT rate
        invoice.getItems().stream()
                .collect(java.util.stream.Collectors.groupingBy(InvoiceItem::getVatRate))
                .forEach((rate, items) -> {
                    BigDecimal net = items.stream().map(InvoiceItem::getNetAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    BigDecimal vat = items.stream().map(InvoiceItem::getVatAmount)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
                    sb.append("    <P_13_").append(vatRateCode(rate)).append(">")
                      .append(net.setScale(2, RoundingMode.HALF_UP).toPlainString())
                      .append("</P_13_").append(vatRateCode(rate)).append(">\n");
                    sb.append("    <P_14_").append(vatRateCode(rate)).append(">")
                      .append(vat.setScale(2, RoundingMode.HALF_UP).toPlainString())
                      .append("</P_14_").append(vatRateCode(rate)).append(">\n");
                });
    }

    private String vatRateCode(BigDecimal rate) {
        int intRate = rate.intValue();
        return switch (intRate) {
            case 23 -> "23";
            case 8  -> "8";
            case 5  -> "5";
            case 0  -> "0";
            default -> String.valueOf(intRate);
        };
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
