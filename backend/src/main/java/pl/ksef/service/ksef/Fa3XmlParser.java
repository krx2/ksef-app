package pl.ksef.service.ksef;

import org.springframework.stereotype.Service;
import org.w3c.dom.Document;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;
import pl.ksef.entity.Invoice;
import pl.ksef.entity.InvoiceItem;

import javax.xml.XMLConstants;
import javax.xml.namespace.NamespaceContext;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.xpath.XPath;
import javax.xml.xpath.XPathConstants;
import javax.xml.xpath.XPathFactory;
import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.UUID;

/**
 * Parsuje XML faktury FA(3) (namespace {@code http://crd.gov.pl/wzor/2025/06/25/13775/})
 * do encji {@link Invoice} z wypełnionymi pozycjami {@link InvoiceItem}.
 *
 * <p>Używany wyłącznie przez {@code handleFetchInvoices()} do zapisu faktur odebranych z KSeF.
 * Status, kierunek i źródło są ustawiane przez wywołującego.
 */
@Service
public class Fa3XmlParser {

    private static final String FA3_NS = "http://crd.gov.pl/wzor/2025/06/25/13775/";

    /**
     * Parsuje XML FA(3) i buduje encję Invoice.
     * Ustawia status=RECEIVED_FROM_KSEF, source=KSEF.
     * Kierunek faktury określany jest przez parametr {@code direction}.
     *
     * @param xml       surowy XML FA(3) w UTF-8
     * @param userId    właściciel faktury (użytkownik KSeF, który pobrał fakturę)
     * @param direction kierunek faktury: RECEIVED (nabywca) lub ISSUED (wystawca)
     * @return encja Invoice z wypełnionymi pozycjami — niezapisana do bazy
     * @throws KsefException jeśli XML jest niepoprawny lub brakuje wymaganych pól
     */
    public Invoice parse(String xml, UUID userId, Invoice.InvoiceDirection direction) {
        try {
            DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();
            dbf.setNamespaceAware(true);
            Document doc = dbf.newDocumentBuilder()
                    .parse(new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)));

            XPath xpath = XPathFactory.newInstance().newXPath();
            xpath.setNamespaceContext(new NamespaceContext() {
                @Override
                public String getNamespaceURI(String prefix) {
                    return "fa".equals(prefix) ? FA3_NS : XMLConstants.NULL_NS_URI;
                }
                @Override public String getPrefix(String ns) { return null; }
                @Override public Iterator<String> getPrefixes(String ns) { return Collections.emptyIterator(); }
            });

            Invoice invoice = new Invoice();
            invoice.setUserId(userId);
            invoice.setDirection(direction);
            invoice.setStatus(Invoice.InvoiceStatus.RECEIVED_FROM_KSEF);
            invoice.setSource(Invoice.InvoiceSource.KSEF);

            // Podmiot1 — sprzedawca
            invoice.setSellerNip(text(xpath, doc, "/fa:Faktura/fa:Podmiot1/fa:DaneIdentyfikacyjne/fa:NIP"));
            invoice.setSellerName(text(xpath, doc, "/fa:Faktura/fa:Podmiot1/fa:DaneIdentyfikacyjne/fa:Nazwa"));
            invoice.setSellerAddress(text(xpath, doc, "/fa:Faktura/fa:Podmiot1/fa:Adres/fa:AdresL1"));
            invoice.setSellerCountryCode(textOr(xpath, doc, "/fa:Faktura/fa:Podmiot1/fa:Adres/fa:KodKraju", "PL"));

            // Podmiot2 — nabywca
            invoice.setBuyerNip(text(xpath, doc, "/fa:Faktura/fa:Podmiot2/fa:DaneIdentyfikacyjne/fa:NIP"));
            invoice.setBuyerName(text(xpath, doc, "/fa:Faktura/fa:Podmiot2/fa:DaneIdentyfikacyjne/fa:Nazwa"));
            invoice.setBuyerAddress(text(xpath, doc, "/fa:Faktura/fa:Podmiot2/fa:Adres/fa:AdresL1"));
            invoice.setBuyerCountryCode(textOr(xpath, doc, "/fa:Faktura/fa:Podmiot2/fa:Adres/fa:KodKraju", "PL"));

            // Fa — dane faktury
            invoice.setCurrency(textOr(xpath, doc, "/fa:Faktura/fa:Fa/fa:KodWaluty", "PLN"));
            invoice.setIssueDate(LocalDate.parse(text(xpath, doc, "/fa:Faktura/fa:Fa/fa:P_1")));
            invoice.setInvoiceNumber(text(xpath, doc, "/fa:Faktura/fa:Fa/fa:P_2"));
            String saleDateStr = text(xpath, doc, "/fa:Faktura/fa:Fa/fa:P_6");
            invoice.setSaleDate(saleDateStr.isBlank() ? null : LocalDate.parse(saleDateStr));
            String grossStr = text(xpath, doc, "/fa:Faktura/fa:Fa/fa:P_15");
            invoice.setGrossAmount(grossStr.isBlank() ? BigDecimal.ZERO : new BigDecimal(grossStr));
            invoice.setRodzajFaktury(textOr(xpath, doc, "/fa:Faktura/fa:Fa/fa:RodzajFaktury", "VAT"));

            // Platnosc — termin zapłaty i rachunek bankowy (opcjonalne)
            String termin = text(xpath, doc, "/fa:Faktura/fa:Fa/fa:Platnosc/fa:TerminPlatnosci/fa:Termin");
            if (!termin.isBlank()) invoice.setPaymentDueDate(LocalDate.parse(termin));
            String nrRb = text(xpath, doc, "/fa:Faktura/fa:Fa/fa:Platnosc/fa:RachunekBankowy/fa:NrRB");
            if (!nrRb.isBlank()) invoice.setBankAccountNumber(nrRb);
            String nazwaBank = text(xpath, doc, "/fa:Faktura/fa:Fa/fa:Platnosc/fa:RachunekBankowy/fa:NazwaBanku");
            if (!nazwaBank.isBlank()) invoice.setBankName(nazwaBank);

            // Adnotacje
            invoice.setMetodaKasowa("1".equals(text(xpath, doc, "/fa:Faktura/fa:Fa/fa:Adnotacje/fa:P_16")));
            invoice.setSamofakturowanie("1".equals(text(xpath, doc, "/fa:Faktura/fa:Fa/fa:Adnotacje/fa:P_17")));
            invoice.setOdwrotneObciazenie("1".equals(text(xpath, doc, "/fa:Faktura/fa:Fa/fa:Adnotacje/fa:P_18")));
            invoice.setMechanizmPodzielonejPlatnosci("1".equals(text(xpath, doc, "/fa:Faktura/fa:Fa/fa:Adnotacje/fa:P_18A")));

            // FaWiersz — pozycje faktury
            NodeList rows = (NodeList) xpath.evaluate(
                    "/fa:Faktura/fa:Fa/fa:FaWiersz", doc, XPathConstants.NODESET);
            List<InvoiceItem> items = new ArrayList<>();
            BigDecimal totalNet = BigDecimal.ZERO;
            for (int i = 0; i < rows.getLength(); i++) {
                Node row = rows.item(i);
                InvoiceItem item = new InvoiceItem();
                item.setInvoice(invoice);

                String nrStr = childText(row, "NrWierszaFa");
                item.setPosition(nrStr.isBlank() ? (i + 1) : Integer.parseInt(nrStr));
                item.setName(childText(row, "P_7"));
                item.setUnit(childText(row, "P_8A"));

                String qtyStr = childText(row, "P_8B");
                item.setQuantity(qtyStr.isBlank() ? BigDecimal.ONE : new BigDecimal(qtyStr));

                String priceStr = childText(row, "P_9A");
                item.setNetUnitPrice(priceStr.isBlank() ? BigDecimal.ZERO : new BigDecimal(priceStr));

                String netStr = childText(row, "P_11");
                BigDecimal netAmt = netStr.isBlank() ? BigDecimal.ZERO : new BigDecimal(netStr);
                item.setNetAmount(netAmt);

                String vatCode = childText(row, "P_12");
                item.setVatRateCode(vatCode);
                BigDecimal vatRate = vatCodeToRate(vatCode);
                item.setVatRate(vatRate);

                BigDecimal vatAmt = netAmt.multiply(vatRate)
                        .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
                item.setVatAmount(vatAmt);
                item.setGrossAmount(netAmt.add(vatAmt));

                items.add(item);
                totalNet = totalNet.add(netAmt);
            }

            invoice.setItems(items);
            invoice.setNetAmount(totalNet);
            invoice.setVatAmount(invoice.getGrossAmount().subtract(totalNet));

            return invoice;

        } catch (KsefException e) {
            throw e;
        } catch (Exception e) {
            throw new KsefException("Błąd parsowania XML FA(3): " + e.getMessage());
        }
    }

    private String text(XPath xpath, Document doc, String expression) throws Exception {
        String result = xpath.evaluate(expression, doc);
        return result == null ? "" : result.trim();
    }

    private String textOr(XPath xpath, Document doc, String expression, String defaultValue) throws Exception {
        String result = text(xpath, doc, expression);
        return result.isBlank() ? defaultValue : result;
    }

    private String childText(Node parent, String localName) {
        NodeList children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            Node child = children.item(i);
            if (localName.equals(child.getLocalName())) {
                return child.getTextContent().trim();
            }
        }
        return "";
    }

    /** Mapuje kod TStawkaPodatku FA(3) na numeryczną stawkę VAT (%) do kolumny vat_rate. */
    private BigDecimal vatCodeToRate(String code) {
        if (code == null) return BigDecimal.valueOf(23);
        return switch (code) {
            case "23" -> BigDecimal.valueOf(23);
            case "22" -> BigDecimal.valueOf(22);
            case "8"  -> BigDecimal.valueOf(8);
            case "7"  -> BigDecimal.valueOf(7);
            case "5"  -> BigDecimal.valueOf(5);
            case "4"  -> BigDecimal.valueOf(4);
            case "3"  -> BigDecimal.valueOf(3);
            default   -> BigDecimal.ZERO; // zw, oo, np I, np II, 0 KR, 0 WDT, 0 EX
        };
    }
}
