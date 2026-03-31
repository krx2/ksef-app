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
import pl.ksef.entity.Invoice.InvoiceSource;
import pl.ksef.entity.InvoiceItem;
import pl.ksef.exception.ResourceNotFoundException;
import pl.ksef.repository.InvoiceRepository;
import pl.ksef.service.ksef.Fa3Validator;
import pl.ksef.service.queue.InvoiceQueuePublisher;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Set;
import java.util.UUID;

@Service
@Slf4j
@RequiredArgsConstructor
public class InvoiceService {

    /**
     * Kody stawki VAT, które nie powodują naliczenia podatku (zwolnienie/odwrotne obciążenie/NP).
     * Pozostałe kody (w tym numeryczne "23", "8", "5" etc.) naliczają VAT wg vatRate.
     */
    private static final Set<String> NON_MONETARY_CODES = Set.of("zw", "oo", "np I", "np II");

    private final InvoiceRepository invoiceRepository;
    private final InvoiceQueuePublisher queuePublisher;
    private final Fa3Validator fa3Validator;

    /**
     * Shortcut dla faktury wystawionej przez formularz (source=FORM).
     */
    @Transactional
    public Invoice createAndQueue(UUID userId, InvoiceDto.CreateRequest req) {
        return createAndQueue(userId, req, InvoiceSource.FORM);
    }

    /**
     * Waliduje dane FA(3), tworzy fakturę z podanym źródłem, zapisuje w DB
     * i publikuje wiadomość do kolejki RabbitMQ w celu wysyłki do KSeF.
     */
    @Transactional
    public Invoice createAndQueue(UUID userId, InvoiceDto.CreateRequest req, InvoiceSource source) {
        fa3Validator.validate(req);

        Invoice invoice = buildInvoice(userId, req, source);
        invoice.setStatus(Invoice.InvoiceStatus.QUEUED);
        invoice = invoiceRepository.save(invoice);

        queuePublisher.publishSendInvoice(invoice.getId(), userId);
        log.info("Invoice {} (source={}) queued for KSeF send", invoice.getId(), source);
        return invoice;
    }

    /**
     * Tworzy szkic faktury bez walidacji FA(3) i kolejkowania.
     * Status faktury jest ustawiony na DRAFT. Fakturę można wysłać później przez {@link #sendDraft}.
     */
    @Transactional
    public Invoice createDraft(UUID userId, InvoiceDto.CreateRequest req) {
        Invoice invoice = buildInvoice(userId, req, InvoiceSource.FORM);
        return invoiceRepository.save(invoice);
    }

    /**
     * Waliduje szkic faktury i umieszcza ją w kolejce do wysyłki do KSeF.
     * Zmienia status ze {@code DRAFT} na {@code QUEUED}.
     *
     * @throws IllegalStateException jeśli faktura nie jest w statusie DRAFT lub nie należy do userId
     */
    @Transactional
    public Invoice sendDraft(UUID invoiceId, UUID userId) {
        Invoice invoice = invoiceRepository.findById(invoiceId)
                .filter(inv -> inv.getUserId().equals(userId))
                .orElseThrow(() -> new ResourceNotFoundException("Invoice " + invoiceId));

        if (invoice.getStatus() != Invoice.InvoiceStatus.DRAFT) {
            throw new IllegalStateException(
                    "Faktura " + invoiceId + " ma status " + invoice.getStatus()
                    + " — tylko faktury ze statusem DRAFT mogą być wysłane tą metodą");
        }

        fa3Validator.validate(invoice);

        invoice.setStatus(Invoice.InvoiceStatus.QUEUED);
        invoice = invoiceRepository.save(invoice);
        queuePublisher.publishSendInvoice(invoice.getId(), userId);
        log.info("Draft invoice {} queued for KSeF send by user {}", invoiceId, userId);
        return invoice;
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
                .orElseThrow(() -> new ResourceNotFoundException("Invoice not found: " + id));
        return toResponse(invoice);
    }

    // ---- private ----

    private Invoice buildInvoice(UUID userId, InvoiceDto.CreateRequest req, InvoiceSource source) {
        Invoice invoice = Invoice.builder()
                .userId(userId)
                .direction(InvoiceDirection.ISSUED)
                .status(Invoice.InvoiceStatus.DRAFT)
                .source(source)
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
                .zwolnieniePodatkowe(req.getZwolnieniePodatkowe())
                .jst(req.isJst())
                .gv(req.isGv())
                .build();

        int pos = 1;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;

        for (var itemReq : req.getItems()) {
            BigDecimal net = itemReq.getNetUnitPrice()
                    .multiply(itemReq.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP);

            String rateCode = itemReq.getVatRateCode();
            BigDecimal vat = BigDecimal.ZERO;
            // VAT naliczamy zawsze, chyba że kod stawki to kod niepieniężny (zw/oo/np I/np II).
            // Kody zerowe (0 KR, 0 WDT, 0 EX) i numeryczne ("23", "8", "5") korzystają z vatRate.
            boolean isNonMonetary = rateCode != null && NON_MONETARY_CODES.contains(rateCode);
            if (!isNonMonetary && itemReq.getVatRate() != null) {
                vat = net.multiply(itemReq.getVatRate())
                         .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            }

            BigDecimal gross = net.add(vat);

            InvoiceItem item = InvoiceItem.builder()
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
            item.setInvoice(invoice);

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
        r.setZwolnieniePodatkowe(i.getZwolnieniePodatkowe());
        r.setJst(i.isJst());
        r.setGv(i.isGv());
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
