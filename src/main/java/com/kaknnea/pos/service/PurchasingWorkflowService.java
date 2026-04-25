package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.*;
import com.kaknnea.pos.dto.PurchasingWorkflowDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.time.Year;
import java.util.ArrayList;
import java.util.List;

@Service
public class PurchasingWorkflowService {
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final PurchaseRfqRepository purchaseRfqRepository;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;
    private final GoodsReceiptRepository goodsReceiptRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final PurchaseReturnRepository purchaseReturnRepository;
    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final PurchaseActivityService purchaseActivityService;
    private final PurchaseAttachmentService purchaseAttachmentService;
    private final EmailService emailService;

    public PurchasingWorkflowService(
            SupplierRepository supplierRepository,
            ProductRepository productRepository,
            PurchaseRfqRepository purchaseRfqRepository,
            UserRepository userRepository,
            StoreRepository storeRepository,
            PurchaseOrderRepository purchaseOrderRepository,
            GoodsReceiptRepository goodsReceiptRepository,
            SupplierInvoiceRepository supplierInvoiceRepository,
            SupplierPaymentRepository supplierPaymentRepository,
            PurchaseReturnRepository purchaseReturnRepository,
            StockItemRepository stockItemRepository,
            StockMovementRepository stockMovementRepository,
            PurchaseActivityService purchaseActivityService,
            PurchaseAttachmentService purchaseAttachmentService,
            EmailService emailService) {
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.purchaseRfqRepository = purchaseRfqRepository;
        this.userRepository = userRepository;
        this.storeRepository = storeRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
        this.goodsReceiptRepository = goodsReceiptRepository;
        this.supplierInvoiceRepository = supplierInvoiceRepository;
        this.supplierPaymentRepository = supplierPaymentRepository;
        this.purchaseReturnRepository = purchaseReturnRepository;
        this.stockItemRepository = stockItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.purchaseActivityService = purchaseActivityService;
        this.purchaseAttachmentService = purchaseAttachmentService;
        this.emailService = emailService;
    }

    public List<PurchasingWorkflowDtos.PurchaseOrderResponse> listPurchaseOrders() {
        return purchaseOrderRepository.findAll().stream().map(this::toPurchaseOrderResponse).toList();
    }

    public List<PurchasingWorkflowDtos.PurchaseRfqResponse> listPurchaseRfqs() {
        return purchaseRfqRepository.findAll().stream().map(this::toPurchaseRfqResponse).toList();
    }

    public PurchasingWorkflowDtos.PurchaseRfqResponse getPurchaseRfq(Long id) {
        PurchaseRfq rfq = purchaseRfqRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase RFQ not found"));
        return toPurchaseRfqResponse(rfq);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseRfqResponse createPurchaseRfq(
            PurchasingWorkflowDtos.PurchaseRfqRequest request) {
        PurchaseRfq rfq = new PurchaseRfq();
        applyPurchaseRfq(rfq, request);
        rfq.setStatus("DRAFT");
        PurchaseRfq saved = purchaseRfqRepository.save(rfq);
        purchaseActivityService.log("RFQ", saved.getId(), "CREATE", "RFQ created");
        return toPurchaseRfqResponse(saved);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseRfqResponse updatePurchaseRfq(Long id,
            PurchasingWorkflowDtos.PurchaseRfqRequest request) {
        PurchaseRfq rfq = purchaseRfqRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase RFQ not found"));
        if (!List.of("DRAFT", "SUBMITTED").contains(rfq.getStatus())) {
            throw new ApiException("Only draft or submitted RFQs can be edited");
        }
        applyPurchaseRfq(rfq, request);
        PurchaseRfq saved = purchaseRfqRepository.save(rfq);
        purchaseActivityService.log("RFQ", saved.getId(), "UPDATE", "RFQ updated");
        return toPurchaseRfqResponse(saved);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseRfqResponse transitionPurchaseRfq(Long id, String action) {
        PurchaseRfq rfq = purchaseRfqRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase RFQ not found"));
        switch (action) {
            case "submit" -> {
                if (!"DRAFT".equals(rfq.getStatus())) {
                    throw new ApiException("Only draft RFQs can be submitted");
                }
                rfq.setStatus("SUBMITTED");
            }
            case "approve" -> {
                if (!"SUBMITTED".equals(rfq.getStatus())) {
                    throw new ApiException("Only submitted RFQs can be approved");
                }
                rfq.setStatus("APPROVED");
                rfq.setApprovedAt(Instant.now());
                rfq.setApprovedByEmail(currentActorEmail());
            }
            case "cancel" -> {
                if ("CANCELLED".equals(rfq.getStatus())) {
                    throw new ApiException("RFQ is already cancelled");
                }
                rfq.setStatus("CANCELLED");
            }
            default -> throw new ApiException("Unsupported RFQ action");
        }
        PurchaseRfq saved = purchaseRfqRepository.save(rfq);
        purchaseActivityService.log("RFQ", saved.getId(), action.toUpperCase(), "RFQ " + action + "ed");
        return toPurchaseRfqResponse(saved);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseOrderResponse convertRfqToPurchaseOrder(Long id) {
        PurchaseRfq rfq = purchaseRfqRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase RFQ not found"));
        if (!"APPROVED".equals(rfq.getStatus())) {
            throw new ApiException("Only approved RFQs can be converted to a purchase order");
        }
        if (rfq.getSupplier() == null) {
            throw new ApiException("Choose a supplier before converting this RFQ");
        }
        PurchaseOrder order = new PurchaseOrder();
        order.setSupplier(rfq.getSupplier());
        order.setStore(rfq.getStore() != null ? rfq.getStore() : resolveStore(null, 1L));
        order.setStatus("DRAFT");
        order.setTaxRate(BigDecimal.ZERO);
        order.setSubtotal(rfq.getSubtotal());
        order.setTaxAmount(rfq.getTaxAmount());
        order.setTotalAmount(rfq.getTotalAmount());
        order.setNotes(rfq.getNotes());
        order.setOrderedAt(Instant.now());
        order.setOrderDeadline(rfq.getTargetDate());
        order.setLines(new ArrayList<>());
        for (PurchaseRfqLine rfqLine : rfq.getLines()) {
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrder(order);
            line.setProduct(rfqLine.getProduct());
            line.setOrderedQuantity(rfqLine.getRequestedQuantity());
            line.setReceivedQuantity(BigDecimal.ZERO);
            line.setUnitCost(rfqLine.getEstimatedUnitCost() == null ? BigDecimal.ZERO : rfqLine.getEstimatedUnitCost());
            line.setLineTotal(line.getOrderedQuantity().multiply(line.getUnitCost()));
            order.getLines().add(line);
        }
        PurchaseOrder savedOrder = purchaseOrderRepository.save(order);
        assignPoReference(savedOrder);
        rfq.setStatus("CONVERTED");
        purchaseRfqRepository.save(rfq);
        purchaseActivityService.log("RFQ", rfq.getId(), "CONVERT", "RFQ converted to PO #" + savedOrder.getId());
        purchaseActivityService.log("PO", savedOrder.getId(), "CREATE",
                "Purchase order created from RFQ #" + rfq.getId());
        return toPurchaseOrderResponse(savedOrder);
    }

    public PurchasingWorkflowDtos.PurchaseOrderResponse getPurchaseOrder(Long id) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase order not found"));
        return toPurchaseOrderResponse(order);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseOrderResponse createPurchaseOrder(
            PurchasingWorkflowDtos.PurchaseOrderRequest request) {
        PurchaseOrder order = new PurchaseOrder();
        applyPurchaseOrder(order, request);
        order.setStatus("DRAFT");
        order.setOrderedAt(Instant.now());
        PurchaseOrder saved = purchaseOrderRepository.save(order);
        assignPoReference(saved);
        purchaseActivityService.log("PO", saved.getId(), "CREATE", "Purchase order created");
        return toPurchaseOrderResponse(saved);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseOrderResponse updatePurchaseOrder(Long id,
            PurchasingWorkflowDtos.PurchaseOrderRequest request) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase order not found"));
        if (!List.of("DRAFT", "SUBMITTED").contains(order.getStatus())) {
            throw new ApiException("Only draft or submitted purchase orders can be edited");
        }
        applyPurchaseOrder(order, request);
        PurchaseOrder saved = purchaseOrderRepository.save(order);
        purchaseActivityService.log("PO", saved.getId(), "UPDATE", "Purchase order updated");
        return toPurchaseOrderResponse(saved);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseOrderResponse transitionPurchaseOrder(Long id, String action) {
        PurchaseOrder order = purchaseOrderRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase order not found"));
        switch (action) {
            case "submit" -> {
                if (!"DRAFT".equals(order.getStatus())) {
                    throw new ApiException("Only draft purchase orders can be submitted");
                }
                order.setStatus("SUBMITTED");
            }
            case "approve" -> {
                if (!"SUBMITTED".equals(order.getStatus())) {
                    throw new ApiException("Only submitted purchase orders can be approved");
                }
                order.setStatus("APPROVED");
                order.setApprovedAt(Instant.now());
            }
            case "cancel" -> {
                if (List.of("RECEIVED", "PARTIALLY_RECEIVED", "CLOSED", "CANCELLED").contains(order.getStatus())) {
                    throw new ApiException("This purchase order can no longer be cancelled");
                }
                order.setStatus("CANCELLED");
            }
            case "close" -> {
                if (!List.of("RECEIVED", "PARTIALLY_RECEIVED").contains(order.getStatus())) {
                    throw new ApiException("Only received purchase orders can be closed");
                }
                order.setStatus("CLOSED");
            }
            case "send" -> {
                if (!List.of("SUBMITTED", "APPROVED", "PARTIALLY_RECEIVED", "RECEIVED").contains(order.getStatus())) {
                    throw new ApiException("Only confirmed purchase orders can be sent");
                }
                String supplierEmail = order.getSupplier().getEmail();
                if (supplierEmail == null || supplierEmail.isBlank()) {
                    throw new ApiException("Supplier email is required before sending this purchase order");
                }
                PurchasingWorkflowDtos.PurchaseOrderResponse response = toPurchaseOrderResponse(order);
                emailService.sendPurchaseOrder(supplierEmail, order.getSupplier().getName(), response);
                order.setSentAt(Instant.now());
            }
            default -> throw new ApiException("Unsupported purchase order action");
        }
        PurchaseOrder saved = purchaseOrderRepository.save(order);
        purchaseActivityService.log("PO", saved.getId(), action.toUpperCase(),
                summarizePurchaseOrderAction(action, saved));
        return toPurchaseOrderResponse(saved);
    }

    public List<PurchasingWorkflowDtos.GoodsReceiptResponse> listGoodsReceipts() {
        return goodsReceiptRepository.findAll().stream().map(this::toGoodsReceiptResponse).toList();
    }

    public PurchasingWorkflowDtos.GoodsReceiptResponse getGoodsReceipt(Long id) {
        GoodsReceipt receipt = goodsReceiptRepository.findById(id)
                .orElseThrow(() -> new ApiException("Goods receipt not found"));
        return toGoodsReceiptResponse(receipt);
    }

    @Transactional
    public PurchasingWorkflowDtos.GoodsReceiptResponse createGoodsReceipt(
            PurchasingWorkflowDtos.GoodsReceiptRequest request) {
        PurchaseOrder order = purchaseOrderRepository.findById(request.getPurchaseOrderId())
                .orElseThrow(() -> new ApiException("Purchase order not found"));
        if (!List.of("APPROVED", "PARTIALLY_RECEIVED").contains(order.getStatus())) {
            throw new ApiException("Only approved purchase orders can be received");
        }
        Store store = resolveStore(request.getStoreId(), order.getStore().getId());
        GoodsReceipt receipt = new GoodsReceipt();
        receipt.setPurchaseOrder(order);
        receipt.setSupplier(order.getSupplier());
        receipt.setStore(store);
        receipt.setStatus("POSTED");
        receipt.setNotes(request.getNotes());
        receipt.setReceivedAt(Instant.now());
        receipt.setLines(new ArrayList<>());
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new ApiException("Add at least one goods receipt line");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PurchasingWorkflowDtos.PurchaseDocumentLineRequest lineRequest : request.getLines()) {
            if (lineRequest.getQuantity() == null || lineRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Received quantity must be greater than zero");
            }
            if (lineRequest.getUnitCost() == null || lineRequest.getUnitCost().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException("Unit cost cannot be negative");
            }
            PurchaseOrderLine orderLine = order.getLines().stream()
                    .filter(line -> line.getId().equals(lineRequest.getPurchaseOrderLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException("Purchase order line not found"));
            BigDecimal openQty = orderLine.getOrderedQuantity().subtract(orderLine.getReceivedQuantity());
            if (lineRequest.getQuantity().compareTo(openQty) > 0) {
                throw new ApiException("Received quantity exceeds open quantity");
            }
            GoodsReceiptLine receiptLine = new GoodsReceiptLine();
            receiptLine.setGoodsReceipt(receipt);
            receiptLine.setPurchaseOrderLine(orderLine);
            receiptLine.setProduct(orderLine.getProduct());
            receiptLine.setReceivedQuantity(lineRequest.getQuantity());
            receiptLine.setUnitCost(lineRequest.getUnitCost());
            receiptLine.setLineTotal(lineRequest.getUnitCost().multiply(lineRequest.getQuantity()));
            receipt.getLines().add(receiptLine);
            total = total.add(receiptLine.getLineTotal());
        }
        receipt.setTotalAmount(total);
        GoodsReceipt saved = goodsReceiptRepository.save(receipt);
        assignGrnReference(saved);
        for (GoodsReceiptLine receiptLine : saved.getLines()) {
            PurchaseOrderLine orderLine = receiptLine.getPurchaseOrderLine();
            orderLine.setReceivedQuantity(orderLine.getReceivedQuantity().add(receiptLine.getReceivedQuantity()));
            receiveIntoStock(store, orderLine.getProduct(), receiptLine.getReceivedQuantity(),
                    receiptLine.getUnitCost(),
                    "GRN #" + saved.getId());
        }
        updatePurchaseOrderReceiptStatus(order);
        purchaseOrderRepository.save(order);
        purchaseActivityService.log("GRN", saved.getId(), "POST", "Goods receipt posted for PO #" + order.getId());
        purchaseActivityService.log("PO", order.getId(), "RECEIVE", "Goods receipt posted: GRN #" + saved.getId());
        return toGoodsReceiptResponse(saved);
    }

    public List<PurchasingWorkflowDtos.SupplierInvoiceResponse> listSupplierInvoices() {
        return supplierInvoiceRepository.findAll().stream().map(this::toSupplierInvoiceResponse).toList();
    }

    public PurchasingWorkflowDtos.SupplierInvoiceResponse getSupplierInvoice(Long id) {
        SupplierInvoice invoice = supplierInvoiceRepository.findById(id)
                .orElseThrow(() -> new ApiException("Supplier invoice not found"));
        return toSupplierInvoiceResponse(invoice);
    }

    @Transactional
    public PurchasingWorkflowDtos.SupplierInvoiceResponse createSupplierInvoice(
            PurchasingWorkflowDtos.SupplierInvoiceRequest request) {
        SupplierInvoice invoice = new SupplierInvoice();
        applySupplierInvoice(invoice, request);
        invoice.setStatus("OPEN");
        SupplierInvoice saved = supplierInvoiceRepository.save(invoice);
        purchaseActivityService.log("BILL", saved.getId(), "CREATE", "Supplier bill created");
        if (saved.getPurchaseOrder() != null) {
            purchaseActivityService.log("PO", saved.getPurchaseOrder().getId(), "BILL",
                    "Supplier bill linked: " + saved.getInvoiceNumber());
        }
        return toSupplierInvoiceResponse(saved);
    }

    @Transactional
    public PurchasingWorkflowDtos.SupplierInvoiceResponse updateSupplierInvoice(Long id,
            PurchasingWorkflowDtos.SupplierInvoiceRequest request) {
        SupplierInvoice invoice = supplierInvoiceRepository.findById(id)
                .orElseThrow(() -> new ApiException("Supplier invoice not found"));
        if (List.of("PAID", "VOID").contains(invoice.getStatus())) {
            throw new ApiException("Paid or void supplier invoices cannot be edited");
        }
        applySupplierInvoice(invoice, request);
        if (invoice.getPaidAmount() == null) {
            invoice.setPaidAmount(BigDecimal.ZERO);
        }
        if (invoice.getPaidAmount().compareTo(invoice.getTotalAmount()) > 0) {
            throw new ApiException("Paid amount cannot exceed the updated invoice total");
        }
        invoice.setOutstandingAmount(invoice.getTotalAmount().subtract(invoice.getPaidAmount()));
        if (invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            invoice.setStatus("PAID");
        } else if (invoice.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            invoice.setStatus("PARTIAL");
        } else {
            invoice.setStatus("OPEN");
        }
        SupplierInvoice saved = supplierInvoiceRepository.save(invoice);
        purchaseActivityService.log("BILL", saved.getId(), "UPDATE", "Supplier bill updated");
        return toSupplierInvoiceResponse(saved);
    }

    public List<PurchasingWorkflowDtos.SupplierPaymentResponse> listSupplierPayments() {
        return supplierPaymentRepository.findAll().stream().map(this::toSupplierPaymentResponse).toList();
    }

    public PurchasingWorkflowDtos.SupplierPaymentResponse getSupplierPayment(Long id) {
        SupplierPayment payment = supplierPaymentRepository.findById(id)
                .orElseThrow(() -> new ApiException("Supplier payment not found"));
        return toSupplierPaymentResponse(payment);
    }

    @Transactional
    public PurchasingWorkflowDtos.SupplierPaymentResponse createSupplierPayment(
            PurchasingWorkflowDtos.SupplierPaymentRequest request) {
        SupplierInvoice invoice = supplierInvoiceRepository.findById(request.getSupplierInvoiceId())
                .orElseThrow(() -> new ApiException("Supplier invoice not found"));
        if (request.getAmount() == null || request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("Payment amount must be greater than zero");
        }
        if (invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("Supplier invoice has no remaining balance");
        }
        if (request.getAmount().compareTo(invoice.getOutstandingAmount()) > 0) {
            throw new ApiException("Payment amount cannot exceed the remaining balance");
        }
        SupplierPayment payment = new SupplierPayment();
        payment.setSupplierInvoice(invoice);
        payment.setAmount(request.getAmount());
        payment.setPaidAt(request.getPaidAt() != null ? request.getPaidAt() : Instant.now());
        payment.setReference(request.getReference());
        payment.setPaymentMethod(request.getPaymentMethod());
        payment.setNotes(request.getNotes());
        payment.setStatus("POSTED");
        invoice.setPaidAmount(invoice.getPaidAmount().add(request.getAmount()));
        invoice.setOutstandingAmount(invoice.getTotalAmount().subtract(invoice.getPaidAmount()));
        invoice.setStatus(invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) <= 0 ? "PAID" : "PARTIAL");
        supplierInvoiceRepository.save(invoice);
        SupplierPayment saved = supplierPaymentRepository.save(payment);
        purchaseActivityService.log("PAYMENT", saved.getId(), "POST", "Supplier payment posted");
        purchaseActivityService.log("BILL", invoice.getId(), "PAY", "Payment posted: " + request.getAmount());
        return toSupplierPaymentResponse(saved);
    }

    public List<PurchasingWorkflowDtos.PurchaseReturnResponse> listPurchaseReturns() {
        return purchaseReturnRepository.findAll().stream().map(this::toPurchaseReturnResponse).toList();
    }

    public PurchasingWorkflowDtos.PurchaseReturnResponse getPurchaseReturn(Long id) {
        PurchaseReturn purchaseReturn = purchaseReturnRepository.findById(id)
                .orElseThrow(() -> new ApiException("Purchase return not found"));
        return toPurchaseReturnResponse(purchaseReturn);
    }

    @Transactional
    public PurchasingWorkflowDtos.PurchaseReturnResponse createPurchaseReturn(
            PurchasingWorkflowDtos.PurchaseReturnRequest request) {
        PurchaseReturn purchaseReturn = new PurchaseReturn();
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ApiException("Supplier not found"));
        GoodsReceipt goodsReceipt = request.getGoodsReceiptId() == null ? null
                : goodsReceiptRepository.findById(request.getGoodsReceiptId())
                        .orElseThrow(() -> new ApiException("Goods receipt not found"));
        SupplierInvoice invoice = request.getSupplierInvoiceId() == null ? null
                : supplierInvoiceRepository.findById(request.getSupplierInvoiceId())
                        .orElseThrow(() -> new ApiException("Supplier invoice not found"));
        Store store = resolveStore(request.getStoreId(), goodsReceipt != null ? goodsReceipt.getStore().getId() : 1L);
        purchaseReturn.setSupplier(supplier);
        purchaseReturn.setGoodsReceipt(goodsReceipt);
        purchaseReturn.setSupplierInvoice(invoice);
        purchaseReturn.setStore(store);
        purchaseReturn.setStatus("POSTED");
        purchaseReturn.setReturnDate(request.getReturnDate());
        purchaseReturn.setNotes(request.getNotes());
        purchaseReturn.setLines(new ArrayList<>());
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new ApiException("Add at least one return line");
        }
        BigDecimal total = BigDecimal.ZERO;
        for (PurchasingWorkflowDtos.PurchaseDocumentLineRequest lineRequest : request.getLines()) {
            if (lineRequest.getQuantity() == null || lineRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Return quantity must be greater than zero");
            }
            if (lineRequest.getUnitCost() == null || lineRequest.getUnitCost().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException("Unit cost cannot be negative");
            }
            Product product = productRepository.findById(lineRequest.getProductId())
                    .orElseThrow(() -> new ApiException("Product not found"));
            PurchaseReturnLine line = new PurchaseReturnLine();
            line.setPurchaseReturn(purchaseReturn);
            line.setProduct(product);
            line.setQuantity(lineRequest.getQuantity());
            line.setUnitCost(lineRequest.getUnitCost());
            line.setLineTotal(lineRequest.getUnitCost().multiply(lineRequest.getQuantity()));
            purchaseReturn.getLines().add(line);
            total = total.add(line.getLineTotal());
            removeFromStock(store, product, lineRequest.getQuantity(), "PURCHASE_RETURN #" + purchaseReturn.getId());
        }
        purchaseReturn.setTotalAmount(total);
        PurchaseReturn saved = purchaseReturnRepository.save(purchaseReturn);
        if (invoice != null) {
            invoice.setOutstandingAmount(invoice.getOutstandingAmount().subtract(total));
            if (invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) < 0) {
                invoice.setOutstandingAmount(BigDecimal.ZERO);
            }
            supplierInvoiceRepository.save(invoice);
        }
        purchaseActivityService.log("RETURN", saved.getId(), "POST", "Purchase return posted");
        if (goodsReceipt != null) {
            purchaseActivityService.log("GRN", goodsReceipt.getId(), "RETURN",
                    "Purchase return posted: #" + saved.getId());
        }
        if (invoice != null) {
            purchaseActivityService.log("BILL", invoice.getId(), "RETURN", "Purchase return posted: #" + saved.getId());
        }
        return toPurchaseReturnResponse(saved);
    }

    private void applyPurchaseOrder(PurchaseOrder order, PurchasingWorkflowDtos.PurchaseOrderRequest request) {
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ApiException("Supplier not found"));
        Store store = resolveStore(request.getStoreId(), 1L);
        User representative = request.getPurchaseRepresentativeId() == null ? null
                : userRepository.findById(request.getPurchaseRepresentativeId())
                        .orElseThrow(() -> new ApiException("Purchase representative not found"));
        order.setSupplier(supplier);
        order.setStore(store);
        order.setOrderDeadline(request.getOrderDeadline());
        order.setExpectedArrival(request.getExpectedArrival());
        order.setPurchaseRepresentative(representative);
        order.setTaxRate(request.getTaxRate() == null ? BigDecimal.ZERO : request.getTaxRate());
        order.setNotes(request.getNotes());
        order.getLines().clear();
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new ApiException("Add at least one purchase order line");
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PurchasingWorkflowDtos.PurchaseDocumentLineRequest lineRequest : request.getLines()) {
            if (lineRequest.getQuantity() == null || lineRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Ordered quantity must be greater than zero");
            }
            if (lineRequest.getUnitCost() == null || lineRequest.getUnitCost().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException("Unit cost cannot be negative");
            }
            Product product = productRepository.findById(lineRequest.getProductId())
                    .orElseThrow(() -> new ApiException("Product not found"));
            if (!product.isPurchasable() || !product.isTrackInventory()) {
                throw new ApiException(product.getNameEn() + " is not allowed for purchasing");
            }
            PurchaseOrderLine line = new PurchaseOrderLine();
            line.setPurchaseOrder(order);
            line.setProduct(product);
            line.setOrderedQuantity(lineRequest.getQuantity());
            line.setUnitCost(lineRequest.getUnitCost());
            line.setLineTotal(lineRequest.getQuantity().multiply(lineRequest.getUnitCost()));
            order.getLines().add(line);
            subtotal = subtotal.add(line.getLineTotal());
        }
        BigDecimal taxAmount = subtotal.multiply(order.getTaxRate()).setScale(2, RoundingMode.HALF_UP);
        order.setSubtotal(subtotal);
        order.setTaxAmount(taxAmount);
        order.setTotalAmount(subtotal.add(taxAmount));
    }

    private void applyPurchaseRfq(PurchaseRfq rfq, PurchasingWorkflowDtos.PurchaseRfqRequest request) {
        Supplier supplier = request.getSupplierId() == null ? null
                : supplierRepository.findById(request.getSupplierId())
                        .orElseThrow(() -> new ApiException("Supplier not found"));
        Store store = request.getStoreId() == null ? null : resolveStore(request.getStoreId(), request.getStoreId());
        rfq.setSupplier(supplier);
        rfq.setStore(store);
        rfq.setTargetDate(request.getTargetDate());
        rfq.setRequestReference(request.getRequestReference());
        rfq.setNotes(request.getNotes());
        rfq.getLines().clear();
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new ApiException("Add at least one RFQ line");
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PurchasingWorkflowDtos.PurchaseRfqLineRequest lineRequest : request.getLines()) {
            if (lineRequest.getQuantity() == null || lineRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Requested quantity must be greater than zero");
            }
            Product product = productRepository.findById(lineRequest.getProductId())
                    .orElseThrow(() -> new ApiException("Product not found"));
            PurchaseRfqLine line = new PurchaseRfqLine();
            line.setPurchaseRfq(rfq);
            line.setProduct(product);
            line.setRequestedQuantity(lineRequest.getQuantity());
            line.setEstimatedUnitCost(
                    lineRequest.getEstimatedUnitCost() == null ? BigDecimal.ZERO : lineRequest.getEstimatedUnitCost());
            line.setLineTotal(line.getRequestedQuantity().multiply(line.getEstimatedUnitCost()));
            line.setLastPurchaseCost(lastPurchaseCostForSupplierProduct(supplier, product));
            line.setLineNote(lineRequest.getLineNote());
            rfq.getLines().add(line);
            subtotal = subtotal.add(line.getLineTotal());
        }
        rfq.setSubtotal(subtotal);
        rfq.setTaxAmount(BigDecimal.ZERO);
        rfq.setTotalAmount(subtotal);
    }

    private void applySupplierInvoice(SupplierInvoice invoice, PurchasingWorkflowDtos.SupplierInvoiceRequest request) {
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ApiException("Supplier not found"));
        PurchaseOrder order = request.getPurchaseOrderId() == null ? null
                : purchaseOrderRepository.findById(request.getPurchaseOrderId())
                        .orElseThrow(() -> new ApiException("Purchase order not found"));
        GoodsReceipt receipt = request.getGoodsReceiptId() == null ? null
                : goodsReceiptRepository.findById(request.getGoodsReceiptId())
                        .orElseThrow(() -> new ApiException("Goods receipt not found"));
        invoice.setSupplier(supplier);
        invoice.setPurchaseOrder(order);
        invoice.setGoodsReceipt(receipt);
        invoice.setInvoiceNumber(request.getInvoiceNumber().trim());
        invoice.setInvoiceDate(request.getInvoiceDate());
        invoice.setDueDate(request.getDueDate());
        invoice.setNotes(request.getNotes());
        invoice.getLines().clear();
        if (request.getLines() == null || request.getLines().isEmpty()) {
            throw new ApiException("Add at least one supplier invoice line");
        }
        if (order != null && !order.getSupplier().getId().equals(supplier.getId())) {
            throw new ApiException("Purchase order supplier does not match the invoice supplier");
        }
        if (receipt != null && !receipt.getSupplier().getId().equals(supplier.getId())) {
            throw new ApiException("Goods receipt supplier does not match the invoice supplier");
        }
        BigDecimal subtotal = BigDecimal.ZERO;
        for (PurchasingWorkflowDtos.PurchaseDocumentLineRequest lineRequest : request.getLines()) {
            if (lineRequest.getQuantity() == null || lineRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Invoice quantity must be greater than zero");
            }
            if (lineRequest.getUnitCost() == null || lineRequest.getUnitCost().compareTo(BigDecimal.ZERO) < 0) {
                throw new ApiException("Unit cost cannot be negative");
            }
            Product product = productRepository.findById(lineRequest.getProductId())
                    .orElseThrow(() -> new ApiException("Product not found"));
            SupplierInvoiceLine line = new SupplierInvoiceLine();
            line.setSupplierInvoice(invoice);
            line.setProduct(product);
            line.setQuantity(lineRequest.getQuantity());
            line.setUnitCost(lineRequest.getUnitCost());
            line.setLineTotal(lineRequest.getQuantity().multiply(lineRequest.getUnitCost()));
            invoice.getLines().add(line);
            subtotal = subtotal.add(line.getLineTotal());
        }
        BigDecimal taxAmount = request.getTaxAmount() == null ? BigDecimal.ZERO : request.getTaxAmount();
        invoice.setSubtotal(subtotal);
        invoice.setTaxAmount(taxAmount);
        invoice.setTotalAmount(subtotal.add(taxAmount));
        invoice.setOutstandingAmount(invoice.getTotalAmount().subtract(invoice.getPaidAmount()));
    }

    private Store resolveStore(Long requestedStoreId, Long fallbackStoreId) {
        Long storeId = requestedStoreId != null ? requestedStoreId : fallbackStoreId;
        return storeRepository.findById(storeId).orElseThrow(() -> new ApiException("Store not found"));
    }

    public PurchasingWorkflowDtos.PurchaseDashboardResponse purchasingDashboard() {
        List<PurchaseRfq> rfqs = purchaseRfqRepository.findAll();
        List<PurchaseOrder> purchaseOrders = purchaseOrderRepository.findAll();
        List<SupplierInvoice> invoices = supplierInvoiceRepository.findAll();
        List<StockItem> lowStockItems = stockItemRepository.findAll().stream()
                .filter(item -> item.getLowStockThreshold() != null
                        && item.getQuantity().compareTo(item.getLowStockThreshold()) <= 0)
                .sorted((a, b) -> a.getQuantity().compareTo(b.getQuantity()))
                .limit(8)
                .toList();

        PurchasingWorkflowDtos.PurchaseDashboardResponse response = new PurchasingWorkflowDtos.PurchaseDashboardResponse();
        response.setOpenRfqs((int) rfqs.stream()
                .filter(rfq -> List.of("DRAFT", "SUBMITTED", "APPROVED").contains(rfq.getStatus())).count());
        response.setPendingApprovals((int) rfqs.stream().filter(rfq -> "SUBMITTED".equals(rfq.getStatus())).count()
                + (int) purchaseOrders.stream().filter(po -> "SUBMITTED".equals(po.getStatus())).count());
        response.setPendingReceipts((int) purchaseOrders.stream()
                .filter(po -> List.of("APPROVED", "PARTIALLY_RECEIVED").contains(po.getStatus()))
                .count());
        response.setUnpaidVendorBills((int) invoices.stream()
                .filter(invoice -> invoice.getOutstandingAmount() != null
                        && invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) > 0)
                .count());
        response.setOverdueAp(invoices.stream()
                .filter(invoice -> invoice.getOutstandingAmount() != null
                        && invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) > 0)
                .filter(invoice -> invoice.getInvoiceDate() != null
                        && invoice.getInvoiceDate().isBefore(LocalDate.now()))
                .map(SupplierInvoice::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        response.setLowStockItems(lowStockItems.stream().map(item -> {
            PurchasingWorkflowDtos.LowStockItem lowStockItem = new PurchasingWorkflowDtos.LowStockItem();
            lowStockItem.setProductId(item.getProduct().getId());
            lowStockItem.setProductNameEn(item.getProduct().getNameEn());
            lowStockItem.setStoreId(item.getStore().getId());
            lowStockItem.setStockUnitCode(
                    item.getProduct().getStockUnit() != null ? item.getProduct().getStockUnit().getCode() : "EACH");
            lowStockItem.setQuantity(item.getQuantity());
            lowStockItem.setLowStockThreshold(item.getLowStockThreshold());
            lowStockItem.setStoreName(item.getStore().getName());
            return lowStockItem;
        }).toList());
        response.setQueues(List.of(
                queueItem("rfq", "Needs RFQ", response.getOpenRfqs(), "RFQ"),
                queueItem("approval", "Awaiting Approval", response.getPendingApprovals(), "RFQ"),
                queueItem("receive", "Ready to Receive", response.getPendingReceipts(), "GRN"),
                queueItem("bill", "Bills to Record", response.getPendingReceipts(), "INVOICE"),
                queueItem("pay", "Bills to Pay", response.getUnpaidVendorBills(), "PAYMENT")));
        return response;
    }

    public List<PurchasingWorkflowDtos.PurchaseActivityResponse> listActivities(String documentType, Long documentId) {
        return purchaseActivityService.list(documentType, documentId);
    }

    public PurchasingWorkflowDtos.PurchaseMatchWarningResponse invoiceMatchWarnings(Long invoiceId) {
        SupplierInvoice invoice = supplierInvoiceRepository.findById(invoiceId)
                .orElseThrow(() -> new ApiException("Supplier invoice not found"));
        List<String> warnings = new ArrayList<>();
        PurchaseOrder order = invoice.getPurchaseOrder();
        GoodsReceipt receipt = invoice.getGoodsReceipt();
        for (SupplierInvoiceLine line : invoice.getLines()) {
            BigDecimal poQty = order == null ? null
                    : order.getLines().stream()
                            .filter(poLine -> poLine.getProduct().getId().equals(line.getProduct().getId()))
                            .map(PurchaseOrderLine::getOrderedQuantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal grnQty = receipt == null ? null
                    : receipt.getLines().stream()
                            .filter(grnLine -> grnLine.getProduct().getId().equals(line.getProduct().getId()))
                            .map(GoodsReceiptLine::getReceivedQuantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (poQty == null) {
                warnings.add(line.getProduct().getNameEn() + ": bill without linked purchase order line");
            } else if (line.getQuantity().compareTo(poQty) > 0) {
                warnings.add(line.getProduct().getNameEn() + ": billed quantity exceeds ordered quantity");
            }
            if (grnQty == null) {
                warnings.add(line.getProduct().getNameEn() + ": bill without goods receipt");
            } else if (line.getQuantity().compareTo(grnQty) > 0) {
                warnings.add(line.getProduct().getNameEn() + ": billed quantity exceeds received quantity");
            } else if (grnQty.compareTo(line.getQuantity()) > 0) {
                warnings.add(line.getProduct().getNameEn() + ": receipt quantity is greater than billed quantity");
            }
        }
        PurchasingWorkflowDtos.PurchaseMatchWarningResponse response = new PurchasingWorkflowDtos.PurchaseMatchWarningResponse();
        response.setSupplierInvoiceId(invoiceId);
        response.setWarnings(warnings.stream().distinct().toList());
        return response;
    }

    private void receiveIntoStock(Store store, Product product, BigDecimal quantity, BigDecimal unitCost,
            String reason) {
        StockItem stockItem = stockItemRepository.findByProductIdAndStoreId(product.getId(), store.getId())
                .orElseGet(() -> {
                    StockItem item = new StockItem();
                    item.setProduct(product);
                    item.setStore(store);
                    item.setQuantity(BigDecimal.ZERO);
                    item.setLowStockThreshold(product.getLowStockThreshold());
                    return item;
                });
        BigDecimal previousQty = stockItem.getQuantity();
        BigDecimal nextQty = previousQty.add(quantity);
        stockItem.setQuantity(nextQty);
        stockItem.setLowStockThreshold(product.getLowStockThreshold());
        stockItemRepository.save(stockItem);
        if (nextQty.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal oldCost = product.getCost() == null ? BigDecimal.ZERO : product.getCost();
            BigDecimal weightedCost = oldCost.multiply(previousQty).add(unitCost.multiply(quantity))
                    .divide(nextQty, 2, RoundingMode.HALF_UP);
            product.setCost(weightedCost);
            productRepository.save(product);
        }
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setStore(store);
        movement.setMovementType("GRN");
        movement.setQuantity(quantity);
        movement.setReason(reason);
        stockMovementRepository.save(movement);
    }

    private void removeFromStock(Store store, Product product, BigDecimal quantity, String reason) {
        StockItem stockItem = stockItemRepository.findByProductIdAndStoreId(product.getId(), store.getId())
                .orElseThrow(() -> new ApiException("Stock item missing"));
        BigDecimal nextQty = stockItem.getQuantity().subtract(quantity);
        if (nextQty.compareTo(BigDecimal.ZERO) < 0) {
            throw new ApiException("Insufficient stock to return " + product.getNameEn());
        }
        stockItem.setQuantity(nextQty);
        stockItemRepository.save(stockItem);
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setStore(store);
        movement.setMovementType("PURCHASE_RETURN");
        movement.setQuantity(quantity.negate());
        movement.setReason(reason);
        stockMovementRepository.save(movement);
    }

    private void updatePurchaseOrderReceiptStatus(PurchaseOrder order) {
        boolean allReceived = order.getLines().stream()
                .allMatch(line -> line.getReceivedQuantity().compareTo(line.getOrderedQuantity()) >= 0);
        boolean anyReceived = order.getLines().stream()
                .anyMatch(line -> line.getReceivedQuantity().compareTo(BigDecimal.ZERO) > 0);
        if (allReceived) {
            order.setStatus("RECEIVED");
        } else if (anyReceived) {
            order.setStatus("PARTIALLY_RECEIVED");
        }
    }

    private PurchasingWorkflowDtos.QueueItem queueItem(String key, String label, int count, String actionTab) {
        PurchasingWorkflowDtos.QueueItem item = new PurchasingWorkflowDtos.QueueItem();
        item.setKey(key);
        item.setLabel(label);
        item.setCount(count);
        item.setActionTab(actionTab);
        return item;
    }

    private BigDecimal lastPurchaseCostForSupplierProduct(Supplier supplier, Product product) {
        if (supplier != null) {
            BigDecimal catalogCost = supplier.getId() == null ? null : null;
            List<SupplierInvoice> supplierInvoices = supplierInvoiceRepository.findAll().stream()
                    .filter(invoice -> invoice.getSupplier().getId().equals(supplier.getId()))
                    .toList();
            for (SupplierInvoice invoice : supplierInvoices) {
                BigDecimal match = invoice.getLines().stream()
                        .filter(line -> line.getProduct().getId().equals(product.getId()))
                        .map(SupplierInvoiceLine::getUnitCost)
                        .findFirst()
                        .orElse(null);
                if (match != null) {
                    return match;
                }
            }
            if (catalogCost != null) {
                return catalogCost;
            }
        }
        return product.getCost();
    }

    private String currentActorEmail() {
        var authentication = org.springframework.security.core.context.SecurityContextHolder.getContext()
                .getAuthentication();
        return authentication != null ? authentication.getName() : null;
    }

    private String summarizePurchaseOrderAction(String action, PurchaseOrder order) {
        return switch (action) {
            case "submit" -> "Purchase order submitted for approval";
            case "approve" -> "Purchase order approved";
            case "close" -> "Purchase order closed";
            case "cancel" -> "Purchase order cancelled";
            case "send" -> "Purchase order sent to supplier " + order.getSupplier().getName();
            default -> "Purchase order updated";
        };
    }

    private void assignPoReference(PurchaseOrder po) {
        if (po.getReferenceNumber() == null) {
            po.setReferenceNumber(String.format("PO-%d-%04d", Year.now().getValue(), po.getId()));
            purchaseOrderRepository.save(po);
        }
    }

    private void assignGrnReference(GoodsReceipt grn) {
        if (grn.getReferenceNumber() == null) {
            grn.setReferenceNumber(String.format("GRN-%d-%04d", Year.now().getValue(), grn.getId()));
            goodsReceiptRepository.save(grn);
        }
    }

    private PurchasingWorkflowDtos.PurchaseOrderResponse toPurchaseOrderResponse(PurchaseOrder order) {
        PurchasingWorkflowDtos.PurchaseOrderResponse response = new PurchasingWorkflowDtos.PurchaseOrderResponse();
        response.setId(order.getId());
        response.setReferenceNumber(order.getReferenceNumber());
        response.setSupplierId(order.getSupplier().getId());
        response.setSupplierName(order.getSupplier().getName());
        response.setSupplierEmail(order.getSupplier().getEmail());
        response.setStoreId(order.getStore().getId());
        response.setStoreName(order.getStore().getName());
        response.setStatus(order.getStatus());
        response.setOrderDeadline(order.getOrderDeadline());
        response.setExpectedArrival(order.getExpectedArrival());
        response.setPurchaseRepresentativeId(
                order.getPurchaseRepresentative() != null ? order.getPurchaseRepresentative().getId() : null);
        response.setPurchaseRepresentativeName(
                order.getPurchaseRepresentative() != null ? order.getPurchaseRepresentative().getFullName() : null);
        response.setTaxRate(order.getTaxRate());
        response.setSubtotal(order.getSubtotal());
        response.setTaxAmount(order.getTaxAmount());
        response.setTotalAmount(order.getTotalAmount());
        response.setAttachmentCount(purchaseAttachmentService.count("PO", order.getId()));
        response.setNotes(order.getNotes());
        response.setOrderedAt(order.getOrderedAt());
        response.setApprovedAt(order.getApprovedAt());
        response.setSentAt(order.getSentAt());
        response.setLines(order.getLines().stream().map(this::toLineResponse).toList());
        return response;
    }

    private PurchasingWorkflowDtos.GoodsReceiptResponse toGoodsReceiptResponse(GoodsReceipt receipt) {
        PurchasingWorkflowDtos.GoodsReceiptResponse response = new PurchasingWorkflowDtos.GoodsReceiptResponse();
        response.setId(receipt.getId());
        response.setReferenceNumber(receipt.getReferenceNumber());
        response.setPurchaseOrderId(receipt.getPurchaseOrder().getId());
        response.setPoReferenceNumber(receipt.getPurchaseOrder().getReferenceNumber());
        response.setSupplierId(receipt.getSupplier().getId());
        response.setSupplierName(receipt.getSupplier().getName());
        response.setStoreId(receipt.getStore().getId());
        response.setStoreName(receipt.getStore().getName());
        response.setStatus(receipt.getStatus());
        response.setNotes(receipt.getNotes());
        response.setTotalAmount(receipt.getTotalAmount());
        response.setAttachmentCount(purchaseAttachmentService.count("GRN", receipt.getId()));
        response.setReceivedAt(receipt.getReceivedAt());
        response.setLines(receipt.getLines().stream().map(line -> {
            PurchasingWorkflowDtos.PurchaseDocumentLineResponse lineResponse = new PurchasingWorkflowDtos.PurchaseDocumentLineResponse();
            lineResponse.setId(line.getId());
            lineResponse.setProductId(line.getProduct().getId());
            lineResponse.setProductNameEn(line.getProduct().getNameEn());
            lineResponse.setProductNameKm(line.getProduct().getNameKm());
            lineResponse.setQuantity(line.getReceivedQuantity());
            lineResponse.setReceivedQuantity(line.getReceivedQuantity());
            lineResponse.setBilledQuantity(BigDecimal.ZERO);
            lineResponse.setReturnedQuantity(BigDecimal.ZERO);
            lineResponse.setUnitCost(line.getUnitCost());
            lineResponse.setLineTotal(line.getLineTotal());
            lineResponse.setTaxAmount(BigDecimal.ZERO);
            lineResponse.setMatchStatus("RECEIVED");
            return lineResponse;
        }).toList());
        return response;
    }

    private PurchasingWorkflowDtos.SupplierInvoiceResponse toSupplierInvoiceResponse(SupplierInvoice invoice) {
        PurchasingWorkflowDtos.SupplierInvoiceResponse response = new PurchasingWorkflowDtos.SupplierInvoiceResponse();
        response.setId(invoice.getId());
        response.setSupplierId(invoice.getSupplier().getId());
        response.setSupplierName(invoice.getSupplier().getName());
        response.setPurchaseOrderId(invoice.getPurchaseOrder() != null ? invoice.getPurchaseOrder().getId() : null);
        response.setPoReferenceNumber(invoice.getPurchaseOrder() != null ? invoice.getPurchaseOrder().getReferenceNumber() : null);
        response.setGoodsReceiptId(invoice.getGoodsReceipt() != null ? invoice.getGoodsReceipt().getId() : null);
        response.setGrnReferenceNumber(invoice.getGoodsReceipt() != null ? invoice.getGoodsReceipt().getReferenceNumber() : null);
        response.setStatus(invoice.getStatus());
        response.setInvoiceNumber(invoice.getInvoiceNumber());
        response.setInvoiceDate(invoice.getInvoiceDate());
        response.setDueDate(invoice.getDueDate());
        response.setSubtotal(invoice.getSubtotal());
        response.setTaxAmount(invoice.getTaxAmount());
        response.setTotalAmount(invoice.getTotalAmount());
        response.setPaidAmount(invoice.getPaidAmount());
        response.setOutstandingAmount(invoice.getOutstandingAmount());
        response.setAttachmentCount(purchaseAttachmentService.count("BILL", invoice.getId()));
        response.setNotes(invoice.getNotes());
        response.setLines(invoice.getLines().stream().map(line -> {
            PurchasingWorkflowDtos.PurchaseDocumentLineResponse lineResponse = new PurchasingWorkflowDtos.PurchaseDocumentLineResponse();
            lineResponse.setId(line.getId());
            lineResponse.setProductId(line.getProduct().getId());
            lineResponse.setProductNameEn(line.getProduct().getNameEn());
            lineResponse.setProductNameKm(line.getProduct().getNameKm());
            lineResponse.setQuantity(line.getQuantity());
            lineResponse.setReceivedQuantity(line.getQuantity());
            lineResponse.setBilledQuantity(line.getQuantity());
            lineResponse.setReturnedQuantity(BigDecimal.ZERO);
            lineResponse.setUnitCost(line.getUnitCost());
            lineResponse.setLineTotal(line.getLineTotal());
            lineResponse.setTaxAmount(BigDecimal.ZERO);
            lineResponse.setMatchStatus("BILLED");
            return lineResponse;
        }).toList());
        return response;
    }

    private PurchasingWorkflowDtos.SupplierPaymentResponse toSupplierPaymentResponse(SupplierPayment payment) {
        PurchasingWorkflowDtos.SupplierPaymentResponse response = new PurchasingWorkflowDtos.SupplierPaymentResponse();
        response.setId(payment.getId());
        response.setSupplierInvoiceId(payment.getSupplierInvoice().getId());
        response.setInvoiceNumber(payment.getSupplierInvoice().getInvoiceNumber());
        response.setSupplierName(payment.getSupplierInvoice().getSupplier().getName());
        response.setAmount(payment.getAmount());
        response.setPaidAt(payment.getPaidAt());
        response.setReference(payment.getReference());
        response.setPaymentMethod(payment.getPaymentMethod());
        response.setStatus(payment.getStatus());
        response.setAttachmentCount(purchaseAttachmentService.count("PAYMENT", payment.getId()));
        response.setNotes(payment.getNotes());
        return response;
    }

    private PurchasingWorkflowDtos.PurchaseReturnResponse toPurchaseReturnResponse(PurchaseReturn purchaseReturn) {
        PurchasingWorkflowDtos.PurchaseReturnResponse response = new PurchasingWorkflowDtos.PurchaseReturnResponse();
        response.setId(purchaseReturn.getId());
        response.setSupplierId(purchaseReturn.getSupplier().getId());
        response.setSupplierName(purchaseReturn.getSupplier().getName());
        response.setGoodsReceiptId(
                purchaseReturn.getGoodsReceipt() != null ? purchaseReturn.getGoodsReceipt().getId() : null);
        response.setGrnReferenceNumber(
                purchaseReturn.getGoodsReceipt() != null ? purchaseReturn.getGoodsReceipt().getReferenceNumber() : null);
        response.setSupplierInvoiceId(
                purchaseReturn.getSupplierInvoice() != null ? purchaseReturn.getSupplierInvoice().getId() : null);
        response.setStoreId(purchaseReturn.getStore().getId());
        response.setStoreName(purchaseReturn.getStore().getName());
        response.setStatus(purchaseReturn.getStatus());
        response.setReturnDate(purchaseReturn.getReturnDate());
        response.setTotalAmount(purchaseReturn.getTotalAmount());
        response.setAttachmentCount(purchaseAttachmentService.count("RETURN", purchaseReturn.getId()));
        response.setNotes(purchaseReturn.getNotes());
        response.setLines(purchaseReturn.getLines().stream().map(line -> {
            PurchasingWorkflowDtos.PurchaseDocumentLineResponse lineResponse = new PurchasingWorkflowDtos.PurchaseDocumentLineResponse();
            lineResponse.setId(line.getId());
            lineResponse.setProductId(line.getProduct().getId());
            lineResponse.setProductNameEn(line.getProduct().getNameEn());
            lineResponse.setProductNameKm(line.getProduct().getNameKm());
            lineResponse.setQuantity(line.getQuantity());
            lineResponse.setReceivedQuantity(line.getQuantity());
            lineResponse.setBilledQuantity(BigDecimal.ZERO);
            lineResponse.setReturnedQuantity(line.getQuantity());
            lineResponse.setUnitCost(line.getUnitCost());
            lineResponse.setLineTotal(line.getLineTotal());
            lineResponse.setTaxAmount(BigDecimal.ZERO);
            lineResponse.setMatchStatus("RETURNED");
            return lineResponse;
        }).toList());
        return response;
    }

    private PurchasingWorkflowDtos.PurchaseRfqResponse toPurchaseRfqResponse(PurchaseRfq rfq) {
        PurchasingWorkflowDtos.PurchaseRfqResponse response = new PurchasingWorkflowDtos.PurchaseRfqResponse();
        response.setId(rfq.getId());
        response.setSupplierId(rfq.getSupplier() != null ? rfq.getSupplier().getId() : null);
        response.setSupplierName(rfq.getSupplier() != null ? rfq.getSupplier().getName() : null);
        response.setStoreId(rfq.getStore() != null ? rfq.getStore().getId() : null);
        response.setStoreName(rfq.getStore() != null ? rfq.getStore().getName() : null);
        response.setStatus(rfq.getStatus());
        response.setTargetDate(rfq.getTargetDate());
        response.setRequestReference(rfq.getRequestReference());
        response.setNotes(rfq.getNotes());
        response.setSubtotal(rfq.getSubtotal());
        response.setTaxAmount(rfq.getTaxAmount());
        response.setTotalAmount(rfq.getTotalAmount());
        response.setAttachmentCount(purchaseAttachmentService.count("RFQ", rfq.getId()));
        response.setApprovedAt(rfq.getApprovedAt());
        response.setApprovedByEmail(rfq.getApprovedByEmail());
        response.setLines(rfq.getLines().stream().map(line -> {
            PurchasingWorkflowDtos.PurchaseRfqLineResponse lineResponse = new PurchasingWorkflowDtos.PurchaseRfqLineResponse();
            lineResponse.setId(line.getId());
            lineResponse.setProductId(line.getProduct().getId());
            lineResponse.setProductNameEn(line.getProduct().getNameEn());
            lineResponse.setQuantity(line.getRequestedQuantity());
            lineResponse.setEstimatedUnitCost(line.getEstimatedUnitCost());
            lineResponse.setLineTotal(line.getLineTotal());
            lineResponse.setLastPurchaseCost(line.getLastPurchaseCost());
            lineResponse.setLineNote(line.getLineNote());
            return lineResponse;
        }).toList());
        return response;
    }

    private PurchasingWorkflowDtos.PurchaseDocumentLineResponse toLineResponse(PurchaseOrderLine line) {
        PurchasingWorkflowDtos.PurchaseDocumentLineResponse response = new PurchasingWorkflowDtos.PurchaseDocumentLineResponse();
        response.setId(line.getId());
        response.setProductId(line.getProduct().getId());
        response.setProductNameEn(line.getProduct().getNameEn());
        response.setProductNameKm(line.getProduct().getNameKm());
        response.setQuantity(line.getOrderedQuantity());
        response.setReceivedQuantity(line.getReceivedQuantity());
        response.setBilledQuantity(BigDecimal.ZERO);
        response.setReturnedQuantity(BigDecimal.ZERO);
        response.setUnitCost(line.getUnitCost());
        response.setLineTotal(line.getLineTotal());
        response.setTaxAmount(BigDecimal.ZERO);
        response.setMatchStatus(line.getReceivedQuantity().compareTo(line.getOrderedQuantity()) >= 0 ? "RECEIVED"
                : line.getReceivedQuantity().compareTo(BigDecimal.ZERO) > 0 ? "PARTIAL" : "OPEN");
        return response;
    }
}
