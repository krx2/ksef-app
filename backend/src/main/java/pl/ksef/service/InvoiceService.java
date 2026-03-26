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

    @Transactional
    public Invoice createAndQueue(UUID userId, InvoiceDto.CreateRequest req) {
        Invoice invoice = buildInvoice(userId, req);
        invoice = invoiceRepository.save(invoice);

        invoice.setStatus(Invoice.InvoiceStatus.QUEUED);
        invoiceRepository.save(invoice);
        queuePublisher.publishSendInvoice(invoice.getId(), userId);

        log.info("Invoice {} queued for sending", invoice.getId());
        return invoice;
    }

    @Transactional
    public Invoice createDraft(UUID userId, InvoiceDto.CreateRequest req) {
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
                .orElseThrow(() -> new RuntimeException("Invoice not found"));
        return toResponse(invoice);
    }

    // ---- private ----

    private Invoice buildInvoice(UUID userId, InvoiceDto.CreateRequest req) {
        Invoice invoice = Invoice.builder()
                .userId(userId)
                .direction(InvoiceDirection.ISSUED)
                .status(Invoice.InvoiceStatus.DRAFT)
                .invoiceNumber(req.getInvoiceNumber())
                .issueDate(req.getIssueDate())
                .saleDate(req.getSaleDate())
                .sellerName(req.getSellerName())
                .sellerNip(req.getSellerNip())
                .sellerAddress(req.getSellerAddress())
                .buyerName(req.getBuyerName())
                .buyerNip(req.getBuyerNip())
                .buyerAddress(req.getBuyerAddress())
                .currency(req.getCurrency() != null ? req.getCurrency() : "PLN")
                .build();

        int pos = 1;
        BigDecimal totalNet = BigDecimal.ZERO;
        BigDecimal totalVat = BigDecimal.ZERO;

        for (var itemReq : req.getItems()) {
            BigDecimal net = itemReq.getNetUnitPrice()
                    .multiply(itemReq.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP);
            BigDecimal vat = net.multiply(itemReq.getVatRate())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            BigDecimal gross = net.add(vat);

            InvoiceItem item = InvoiceItem.builder()
                    .invoice(invoice)
                    .name(itemReq.getName())
                    .unit(itemReq.getUnit())
                    .quantity(itemReq.getQuantity())
                    .netUnitPrice(itemReq.getNetUnitPrice())
                    .vatRate(itemReq.getVatRate())
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
        r.setBuyerName(i.getBuyerName());
        r.setBuyerNip(i.getBuyerNip());
        r.setNetAmount(i.getNetAmount());
        r.setVatAmount(i.getVatAmount());
        r.setGrossAmount(i.getGrossAmount());
        r.setCurrency(i.getCurrency());
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
            ir.setNetAmount(item.getNetAmount());
            ir.setVatAmount(item.getVatAmount());
            ir.setGrossAmount(item.getGrossAmount());
            ir.setPosition(item.getPosition());
            return ir;
        }).toList());
        return r;
    }
}
