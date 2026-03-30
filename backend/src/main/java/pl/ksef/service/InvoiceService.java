package pl.ksef.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.ksef.dto.InvoiceDto;
import pl.ksef.entity.Invoice;
import pl.ksef.entity.Invoice.InvoiceDirection;
import pl.ksef.entity.InvoiceItem;
import pl.ksef.repository.InvoiceRepository;
import pl.ksef.service.ksef.Fa2XmlBuilder;
import pl.ksef.service.ksef.Fa3Validator;
import pl.ksef.service.queue.InvoiceQueuePublisher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceService {

    private final InvoiceRepository invoiceRepository;
    private final InvoiceQueuePublisher queuePublisher;
    private final Fa3Validator fa3Validator;
    private final Fa2XmlBuilder fa2XmlBuilder;

    /**
     * Waliduje dane FA(3), tworzy fakturę, generuje XML FA(3) i zapisuje w DB.
     * KSeF jest zamockowany — nie wysyłamy do kolejki ani do API KSeF.
     * Wygenerowany XML (który w produkcji byłby body POST do KSeF) jest przechowywany
     * w polu fa2Xml razem ze statusem QUEUED.
     */
    @Transactional
    public Invoice createAndQueue(UUID userId, InvoiceDto.CreateRequest req) {
        fa3Validator.validate(req);

        Invoice invoice = buildInvoice(userId, req);
        invoice = invoiceRepository.save(invoice);

        // Generuj XML FA(3) i zapisz w DB (mock KSeF)
        String fa3Xml = fa2XmlBuilder.build(invoice);
        invoice.setFa2Xml(fa3Xml);
        invoice.setStatus(Invoice.InvoiceStatus.QUEUED);
        invoice = invoiceRepository.save(invoice);

        // TODO: Odkomentować po uruchomieniu RabbitMQ i skonfigurowaniu tokenu KSeF.
        //       Aktualnie faktura trafia do statusu QUEUED, ale wiadomość NIE jest
        //       publikowana do kolejki — InvoiceQueueConsumer nigdy jej nie odbierze.
        //       Należy zastąpić poniższy log wywołaniem:
        //           queuePublisher.publishSendInvoice(invoice.getId(), userId);
        //       a następnie usunąć mock-owy blok generowania XML powyżej
        //       (XML zostanie wygenerowany przez InvoiceQueueConsumer tuż przed wysyłką).
        log.info("Invoice {} — FA(3) XML wygenerowany i zapisany (mock KSeF)", invoice.getId());
        return invoice;
    }

    /**
     * Tworzy szkic faktury bez walidacji FA(3) i kolejkowania.
     */
    @Transactional
    public Invoice createDraft(UUID userId, InvoiceDto.CreateRequest req) {
        // TODO: Ta metoda nie jest wywoływana przez żaden kontroler — brak endpointu POST /invoices/draft.
        //       Jeśli funkcjonalność "zapisz szkic i wyślij później" ma być dostępna, trzeba:
        //       1. Dodać endpoint POST /invoices/draft w InvoiceController.
        //       2. Dodać endpoint POST /invoices/{id}/send (zmiana statusu DRAFT → QUEUED + publikacja do MQ).
        //       3. W UI dodać przycisk "Zapisz szkic" obok "Wyślij do KSeF".
        Invoice invoice = buildInvoice(userId, req);
        return invoiceRepository.save(invoice);
    }

    public InvoiceDto.PageResponse listByUser(UUID userId, InvoiceDirection direction,
                                               int page, int size) {
        Page<Invoice> result = direction != null
                ? invoiceRepository.findByUserIdAndDirectionOrderByCreatedAtDesc(
                        userId, direction, PageRequest.of(page, size))
                : invoiceRepository.findByUserIdOrderByCreatedAtDesc(
                        userId, PageRequest.of(page, size));

        var response = new InvoiceDto.PageResponse();
        response.setContent(result.getContent().stream().map(this::toResponse).toList());
        response.setPage(result.getNumber());
        response.setSize(result.getSize());
        response.setTotalElements(result.getTotalElements());
        response.setTotalPages(result.getTotalPages());
        return response;
    }

    public InvoiceDto.Response getById(UUID id, UUID userId) {
        Invoice invoice = invoiceRepository.findById(id)
                .filter(i -> i.getUserId().equals(userId))
                // TODO: Rzucenie RuntimeException powoduje, że GlobalExceptionHandler zwraca HTTP 400.
                //       Należy stworzyć dedykowany ResourceNotFoundException (extends RuntimeException)
                //       i dodać handler @ExceptionHandler(ResourceNotFoundException.class) → HTTP 404.
                //       Dotyczy też analogicznych miejsc w XlsxConfigService i UserController.
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        return toResponse(invoice);
    }

    // ---- private ----

    private Invoice buildInvoice(UUID userId, InvoiceDto.CreateRequest req) {
        Invoice invoice = Invoice.builder()
                .userId(userId)
                .direction(InvoiceDirection.ISSUED)
                .status(Invoice.InvoiceStatus.DRAFT)
                .source(Invoice.InvoiceSource.FORM)
                .invoiceNumber(req.getInvoiceNumber())
                .issueDate(req.getIssueDate())
                .saleDate(req.getSaleDate())
                .sellerName(req.getSellerName())
                .sellerNip(req.getSellerNip())
                .sellerAddress(req.getSellerAddress())
                .sellerCountryCode(req.getSellerCountryCode() != null ? req.getSellerCountryCode() : "PL")
                .buyerName(req.getBuyerName())
                .buyerNip(req.getBuyerNip())
                .buyerAddress(req.getBuyerAddress())
                .buyerCountryCode(req.getBuyerCountryCode() != null ? req.getBuyerCountryCode() : "PL")
                .currency(req.getCurrency() != null ? req.getCurrency() : "PLN")
                .rodzajFaktury(req.getRodzajFaktury() != null ? req.getRodzajFaktury() : "VAT")
                .metodaKasowa(req.isMetodaKasowa())
                .samofakturowanie(req.isSamofakturowanie())
                .odwrotneObciazenie(req.isOdwrotneObciazenie())
                .mechanizmPodzielonejPlatnosci(req.isMechanizmPodzielonejPlatnosci())
                .build();

        int pos = 1;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;

        for (var itemReq : req.getItems()) {
            BigDecimal net = itemReq.getNetUnitPrice()
                    .multiply(itemReq.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP);

            // Dla stawek bez podatku (zw, oo, np I, np II, 0%) VAT = 0
            BigDecimal vat = BigDecimal.ZERO;
            String rateCode = itemReq.getVatRateCode();
            if (rateCode == null || rateCode.isBlank()) {
                vat = net.multiply(itemReq.getVatRate())
                         .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }
            // TODO: Błąd obliczania VAT dla numerycznych vatRateCodes (np. "23", "8", "5").
            //       Gdy vatRateCode = "23", powyższy warunek jest spełniony (kod niepusty)
            //       i VAT pozostaje 0, zamiast wyliczyć 23% od net.
            //       Poprawka: sprawdzić czy rateCode należy do Fa3Validator.VALID_VAT_RATE_CODES
            //       i czy da się go sparsować jako liczbę — jeśli tak, użyć tej wartości do obliczeń.
            //       Zbiór kodów niepieniężnych: {"zw","oo","np I","np II","0 KR","0 WDT","0 EX"}.
            // Jeśli vatRateCode jest kodem niepieniężnym (zw/oo/np*), VAT pozostaje 0

            BigDecimal gross = net.add(vat);

            InvoiceItem item = InvoiceItem.builder()
                    .invoice(invoice)
                    .name(itemReq.getName())
                    .unit(itemReq.getUnit())
                    .quantity(itemReq.getQuantity())
                    .netUnitPrice(itemReq.getNetUnitPrice())
                    .vatRate(itemReq.getVatRate())
                    .vatRateCode(rateCode)
                    .netAmount(net)
                    .vatAmount(vat)
                    .grossAmount(gross)
                    .position(pos++)
                    .build();

            invoice.getItems().add(item);
            totalNet = totalNet.add(net);
            totalVat = totalVat.add(vat);
        }

        invoice.setNetAmount(totalNet);
        invoice.setVatAmount(totalVat);
        invoice.setGrossAmount(totalNet.add(totalVat));
        return invoice;
    }

    public InvoiceDto.Response toResponse(Invoice i) {
        var r = new InvoiceDto.Response();
        r.setId(i.getId());
        r.setKsefNumber(i.getKsefNumber());
        r.setKsefReferenceNumber(i.getKsefReferenceNumber());
        r.setDirection(i.getDirection());
        r.setStatus(i.getStatus());
        r.setInvoiceNumber(i.getInvoiceNumber());
        r.setIssueDate(i.getIssueDate());
        r.setSaleDate(i.getSaleDate());
        r.setSellerName(i.getSellerName());
        r.setSellerNip(i.getSellerNip());
        r.setSellerAddress(i.getSellerAddress());
        r.setSellerCountryCode(i.getSellerCountryCode());
        r.setBuyerName(i.getBuyerName());
        r.setBuyerNip(i.getBuyerNip());
        r.setBuyerAddress(i.getBuyerAddress());
        r.setBuyerCountryCode(i.getBuyerCountryCode());
        r.setNetAmount(i.getNetAmount());
        r.setVatAmount(i.getVatAmount());
        r.setGrossAmount(i.getGrossAmount());
        r.setCurrency(i.getCurrency());
        r.setRodzajFaktury(i.getRodzajFaktury());
        r.setMetodaKasowa(i.isMetodaKasowa());
        r.setSamofakturowanie(i.isSamofakturowanie());
        r.setOdwrotneObciazenie(i.isOdwrotneObciazenie());
        r.setMechanizmPodzielonejPlatnosci(i.isMechanizmPodzielonejPlatnosci());
        r.setErrorMessage(i.getErrorMessage());
        r.setSource(i.getSource());
        r.setCreatedAt(i.getCreatedAt());
        r.setUpdatedAt(i.getUpdatedAt());
        r.setItems(i.getItems().stream().map(item -> {
            var ir = new InvoiceDto.ItemResponse();
            ir.setId(item.getId());
            ir.setName(item.getName());
            ir.setUnit(item.getUnit());
            ir.setQuantity(item.getQuantity());
            ir.setNetUnitPrice(item.getNetUnitPrice());
            ir.setVatRate(item.getVatRate());
            ir.setVatRateCode(item.getVatRateCode());
            ir.setNetAmount(item.getNetAmount());
            ir.setVatAmount(item.getVatAmount());
            ir.setGrossAmount(item.getGrossAmount());
            ir.setPosition(item.getPosition());
            return ir;
        }).toList());
        return r;
    }
}
