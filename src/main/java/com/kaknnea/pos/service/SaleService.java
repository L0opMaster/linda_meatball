package com.kaknnea.pos.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.kaknnea.pos.domain.*;
import com.kaknnea.pos.dto.SaleDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.*;
import com.kaknnea.pos.util.RoleUtil;
import com.kaknnea.pos.util.SecurityUtil;
// import com.kaknnea.pos.service.AuditService;
// import com.kaknnea.pos.service.PdfService;

@Service
public class SaleService {
    private static final BigDecimal REFUND_APPROVAL_THRESHOLD = new BigDecimal("50.00");
    @Transactional
    public void processSale(Sale sale) {
        // ...existing code...
    }

    private final SaleRepository saleRepository;
    private final ProductRepository productRepository;
    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final PaymentRepository paymentRepository;
    private final SaleDiscountRepository saleDiscountRepository;
    private final CustomerRepository customerRepository;
    private final CustomerCreditAccountRepository creditAccountRepository;
    private final ShiftRepository shiftRepository;
    private final BusinessSettingsRepository businessSettingsRepository;
    private final TableRepository tableRepository;
    private final PdfService pdfService;
    private final AuditService auditService;
    private final CashEventService cashEventService;
    private final UserRepository userRepository;
    private final StoreRepository storeRepository;
    private final PasswordEncoder passwordEncoder;
    private final PriceListService priceListService;

    public SaleService(SaleRepository saleRepository,
            ProductRepository productRepository,
            StockItemRepository stockItemRepository,
            StockMovementRepository stockMovementRepository,
            PaymentRepository paymentRepository,
            SaleDiscountRepository saleDiscountRepository,
            CustomerRepository customerRepository,
            CustomerCreditAccountRepository creditAccountRepository,
            ShiftRepository shiftRepository,
            BusinessSettingsRepository businessSettingsRepository,
            TableRepository tableRepository,
            PdfService pdfService,
            AuditService auditService,
            CashEventService cashEventService,
            UserRepository userRepository,
            StoreRepository storeRepository,
            PasswordEncoder passwordEncoder,
            PriceListService priceListService) {
        this.saleRepository = saleRepository;
        this.productRepository = productRepository;
        this.stockItemRepository = stockItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.paymentRepository = paymentRepository;
        this.saleDiscountRepository = saleDiscountRepository;
        this.customerRepository = customerRepository;
        this.creditAccountRepository = creditAccountRepository;
        this.shiftRepository = shiftRepository;
        this.businessSettingsRepository = businessSettingsRepository;
        this.tableRepository = tableRepository;
        this.pdfService = pdfService;
        this.auditService = auditService;
        this.cashEventService = cashEventService;
        this.userRepository = userRepository;
        this.storeRepository = storeRepository;
        this.passwordEncoder = passwordEncoder;
        this.priceListService = priceListService;
    }

    // All methods (create, update, hold, resume, voidSale, pay, credit, refund,
    // repayCreditSale, receipt, invoicePdf, listByStatus, listByShift) are already
    // implemented below and inside this class.
    // ...existing methods...

    @Transactional
    public SaleDtos.SaleResponse create(SaleDtos.SaleCreateRequest request) {
        if (request.getClientRef() != null && !request.getClientRef().isBlank()) {
            Sale existing = saleRepository.findByClientRef(request.getClientRef()).orElse(null);
            if (existing != null) {
                return toResponse(existing);
            }
        }

        Sale sale = new Sale();
        sale.setStatus("DRAFT");
        sale.setClientRef(request.getClientRef());
        sale.setDisplayName(trimToNull(request.getDisplayName()));

        // Set current shift
        User actor = userRepository.findByEmail(SecurityUtil.currentUsername()).orElseThrow();
        sale.setCreatedBy(actor);
        Shift currentShift = shiftRepository.findFirstByOpenedByIdAndStatusOrderByOpenedAtDesc(actor.getId(), "OPEN")
                .orElse(null);
        sale.setShift(currentShift);
        sale.setTerminalId(request.getTerminalId());

        if (request.getCustomerId() != null) {
            sale.setCustomer(customerRepository.findById(request.getCustomerId()).orElse(null));
        }

        sale.setOrderDate(parseDateOrDefault(request.getOrderDate(), LocalDate.now()));
        sale.setDeliveryDate(parseDate(request.getDeliveryDate()));
        sale.setPaymentTerms(resolvePaymentTerms(request.getPaymentTerms(), sale.getCustomer()));
        sale.setDeliveryCharge(safeAmount(request.getDeliveryCharge()));
        sale.setOtherCharge(safeAmount(request.getOtherCharge()));
        sale.setDepositAmount(safeAmount(request.getDepositAmount()));

        if (request.getTableId() != null) {
            sale.setTable(tableRepository.findById(request.getTableId()).orElse(null));
        }
        sale.setTerminalId(request.getTerminalId());

        if (request.getCustomerId() != null) {
            sale.setCustomer(customerRepository.findById(request.getCustomerId()).orElse(null));
        }

        sale.setOrderDate(parseDateOrDefault(request.getOrderDate(), LocalDate.now()));
        sale.setDeliveryDate(parseDate(request.getDeliveryDate()));
        sale.setPaymentTerms(resolvePaymentTerms(request.getPaymentTerms(), sale.getCustomer()));
        sale.setDeliveryCharge(safeAmount(request.getDeliveryCharge()));
        sale.setOtherCharge(safeAmount(request.getOtherCharge()));
        sale.setDepositAmount(safeAmount(request.getDepositAmount()));

        if (request.getTableId() != null) {
            sale.setTable(tableRepository.findById(request.getTableId()).orElse(null));
        }

        List<SaleLine> lines = request.getLines().stream().map(lineReq -> {
            Product product = productRepository.findById(lineReq.getProductId())
                .orElseThrow(() -> new ApiException("Product not found"));
            BigDecimal unitPrice = resolveUnitPrice(lineReq, product, sale.getCustomer());
            SaleLine line = new SaleLine();
            line.setSale(sale);
            line.setProduct(product);
            line.setQuantity(lineReq.getQuantity());
            line.setUnitPrice(unitPrice);
            line.setLineDiscount(lineReq.getLineDiscount() == null ? BigDecimal.ZERO : lineReq.getLineDiscount());
            line.setLineNote(lineReq.getNote());
            line.setModifierSummary(lineReq.getModifierSummary());
            line.setModifierData(lineReq.getModifierData());
            BigDecimal lineTotal = unitPrice.multiply(lineReq.getQuantity())
                .subtract(line.getLineDiscount());
            line.setLineTotal(lineTotal);
            return line;
        }).collect(Collectors.toList());
        sale.getLines().clear();
        sale.getLines().addAll(lines);

        BigDecimal subtotal = lines.stream().map(SaleLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = request.getInvoiceDiscount() == null ? BigDecimal.ZERO : request.getInvoiceDiscount();
        BigDecimal taxable = subtotal.subtract(discount);
        BigDecimal taxAmount = taxable.multiply(BigDecimal.valueOf(request.getTaxRate())).setScale(2,
            RoundingMode.HALF_UP);
        BigDecimal grandTotal = taxable.add(taxAmount).add(sale.getDeliveryCharge()).add(sale.getOtherCharge());
        sale.setSubtotal(subtotal);
        sale.setDiscountAmount(discount);
        sale.setTaxRate(request.getTaxRate());
        sale.setTaxAmount(taxAmount);
        sale.setGrandTotal(grandTotal);
        sale.setTotalAmount(grandTotal);
        sale.setPaidAmount(BigDecimal.ZERO);
        sale.setChangeAmount(BigDecimal.ZERO);
        sale.setNote(request.getNote());

        Sale saved = saleRepository.save(sale);
        persistInvoiceDiscount(saved, discount);
        return toResponse(saved);
    }

    @Transactional
    public SaleDtos.SaleResponse hold(Long id) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if ("PAID".equals(sale.getStatus()) || "VOID".equals(sale.getStatus()) || "REFUNDED".equals(sale.getStatus())
                || "CREDIT".equals(sale.getStatus())) {
            throw new ApiException("Cannot hold finalized sale");
        }
        sale.setStatus("HOLD");
        return toResponse(saleRepository.save(sale));
    }

    @Transactional
    public SaleDtos.SaleResponse resume(Long id) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if (!"HOLD".equals(sale.getStatus())) {
            throw new ApiException("Sale is not on hold");
        }
        sale.setStatus("DRAFT");
        return toResponse(saleRepository.save(sale));
    }

    @Transactional
    public SaleDtos.SaleResponse update(Long id, SaleDtos.SaleCreateRequest request) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if ("PAID".equals(sale.getStatus()) || "VOID".equals(sale.getStatus()) || "REFUNDED".equals(sale.getStatus())) {
            throw new ApiException("Cannot update finalized sale");
        }
        ensureEditable(sale);
        sale.setDisplayName(trimToNull(request.getDisplayName()));
        if (request.getCustomerId() != null) {
            sale.setCustomer(customerRepository.findById(request.getCustomerId()).orElse(null));
        } else {
            sale.setCustomer(null);
        }
        sale.setOrderDate(parseDateOrDefault(request.getOrderDate(), sale.getOrderDate() != null ? sale.getOrderDate() : LocalDate.now()));
        sale.setDeliveryDate(parseDate(request.getDeliveryDate()));
        sale.setPaymentTerms(resolvePaymentTerms(request.getPaymentTerms(), sale.getCustomer()));
        sale.setDeliveryCharge(safeAmount(request.getDeliveryCharge()));
        sale.setOtherCharge(safeAmount(request.getOtherCharge()));
        sale.setDepositAmount(safeAmount(request.getDepositAmount()));
        sale.getLines().clear();
        List<SaleLine> lines = request.getLines().stream().map(lineReq -> {
            Product product = productRepository.findById(lineReq.getProductId())
                    .orElseThrow(() -> new ApiException("Product not found"));
            BigDecimal unitPrice = resolveUnitPrice(lineReq, product, sale.getCustomer());
            SaleLine line = new SaleLine();
            line.setSale(sale);
            line.setProduct(product);
            line.setQuantity(lineReq.getQuantity());
            line.setUnitPrice(unitPrice);
            line.setLineDiscount(lineReq.getLineDiscount() == null ? BigDecimal.ZERO : lineReq.getLineDiscount());
            line.setLineNote(lineReq.getNote());
            line.setModifierSummary(lineReq.getModifierSummary());
            line.setModifierData(lineReq.getModifierData());
            BigDecimal lineTotal = unitPrice.multiply(lineReq.getQuantity())
                    .subtract(line.getLineDiscount());
            line.setLineTotal(lineTotal);
            return line;
        }).collect(Collectors.toList());
        sale.getLines().clear();
        sale.getLines().addAll(lines);

        BigDecimal subtotal = lines.stream().map(SaleLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal discount = request.getInvoiceDiscount() == null ? BigDecimal.ZERO : request.getInvoiceDiscount();
        BigDecimal taxable = subtotal.subtract(discount);
        BigDecimal taxAmount = taxable.multiply(BigDecimal.valueOf(request.getTaxRate())).setScale(2,
                RoundingMode.HALF_UP);
        BigDecimal grandTotal = taxable.add(taxAmount).add(sale.getDeliveryCharge()).add(sale.getOtherCharge());
        sale.setSubtotal(subtotal);
        sale.setDiscountAmount(discount);
        sale.setTaxRate(request.getTaxRate());
        sale.setTaxAmount(taxAmount);
        sale.setGrandTotal(grandTotal);
        sale.setTotalAmount(grandTotal);
        sale.setNote(request.getNote());

        Sale saved = saleRepository.save(sale);
        persistInvoiceDiscount(saved, discount);
        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public SaleDtos.SaleResponse getById(Long id) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        return toResponse(sale);
    }

    @Transactional
    public SaleDtos.SaleResponse voidSale(Long id, String reason) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if ("PAID".equals(sale.getStatus())) {
            throw new ApiException("Cannot void paid sale");
        }
        if ("CREDIT".equals(sale.getStatus())) {
            throw new ApiException("Cannot void credit sale");
        }
        sale.setStatus("VOID");
        if (reason != null && !reason.isBlank()) {
            sale.setNote(reason);
        }
        Sale saved = saleRepository.save(sale);
        var actor = userRepository.findByEmail(SecurityUtil.currentUsername()).orElse(null);
        auditService.log(actor, "SALE_VOID", "Sale", String.valueOf(saved.getId()), null, saved);
        return toResponse(saved);
    }

    @Transactional
    public SaleDtos.SaleResponse pay(Long id, SaleDtos.PayRequest request) {
        requireOpenShiftForActor();
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if ("VOID".equals(sale.getStatus())) {
            throw new ApiException("Cannot pay voided sale");
        }
        if ("CREDIT".equals(sale.getStatus())) {
            throw new ApiException("Cannot pay credit sale");
        }
        if ("PAID".equals(sale.getStatus()) || sale.getPaidAmount().compareTo(sale.getGrandTotal()) >= 0) {
            return toResponse(sale);
        }
        BigDecimal remainingBeforePayment = sale.getGrandTotal().subtract(sale.getPaidAmount());
        BigDecimal requestTotal = BigDecimal.ZERO;
        BigDecimal nonCashTotal = BigDecimal.ZERO;
        for (SaleDtos.PaymentRequest pr : request.getPayments()) {
            String normalizedMethod = normalizePaymentMethod(pr.getMethod());
            BigDecimal amount = pr.getAmount() == null ? BigDecimal.ZERO : pr.getAmount();
            if (amount.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Payment amount must be greater than zero");
            }
            requestTotal = requestTotal.add(amount);
            if (!"CASH".equals(normalizedMethod)) {
                nonCashTotal = nonCashTotal.add(amount);
            }
        }
        if (requestTotal.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("At least one payment amount is required");
        }
        if (nonCashTotal.compareTo(remainingBeforePayment) > 0) {
            throw new ApiException("Non-cash payment cannot exceed the remaining balance");
        }
        BigDecimal appliedAmount = requestTotal.min(remainingBeforePayment);
        BigDecimal changeAmount = requestTotal.subtract(appliedAmount);

        for (SaleDtos.PaymentRequest pr : request.getPayments()) {
            Payment payment = new Payment();
            payment.setSale(sale);
            payment.setShift(sale.getShift());
            payment.setStore(resolveSaleStore(sale));
            payment.setMethod(normalizePaymentMethod(pr.getMethod()));
            payment.setPaymentMethod(toPaymentEnum(pr.getMethod()));
            payment.setAmount(pr.getAmount());
            payment.setStatus(Payment.PaymentStatus.COMPLETED);
            paymentRepository.save(payment);
        }
        sale.setPaidAmount(sale.getPaidAmount().add(appliedAmount));
        sale.setChangeAmount(changeAmount);
        if (sale.getPaidAmount().compareTo(sale.getGrandTotal()) >= 0) {
            sale.setStatus("PAID");
            sale.setPaidAt(Instant.now());
            applyStockForSale(sale);
        }
        Sale saved = saleRepository.save(sale);
        var actor = userRepository.findByEmail(SecurityUtil.currentUsername()).orElse(null);
        auditService.log(actor, "SALE_PAYMENT", "Sale", String.valueOf(saved.getId()), null, saved);
        BigDecimal cashPaid = request.getPayments().stream()
                .filter(pr -> "CASH".equalsIgnoreCase(pr.getMethod()))
                .map(SaleDtos.PaymentRequest::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal netCash = cashPaid.subtract(changeAmount);
        if (netCash.compareTo(BigDecimal.ZERO) > 0) {
            cashEventService.recordInternal(
                    saved.getShift(),
                    "SALE_CASH",
                    netCash,
                    "Cash payment",
                    saved,
                    actor);
        }
        return toResponse(saved);
    }

    @Transactional
    public SaleDtos.SaleResponse credit(Long id) {
        requireOpenShiftForActor();
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if ("VOID".equals(sale.getStatus()) || "REFUNDED".equals(sale.getStatus())) {
            throw new ApiException("Cannot credit voided or refunded sale");
        }
        if ("PAID".equals(sale.getStatus())) {
            throw new ApiException("Sale is already paid");
        }
        if ("CREDIT".equals(sale.getStatus())) {
            throw new ApiException("Sale is already on credit");
        }
        Customer customer = sale.getCustomer();
        if (customer == null) {
            throw new ApiException("Customer is required for credit sale");
        }
        BigDecimal creditAmount = sale.getGrandTotal().subtract(sale.getPaidAmount());
        if (creditAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("No remaining balance to credit");
        }

        enforceCreditLimit(customer, creditAmount);

        BigDecimal currentBalance = customer.getCreditBalance() == null ? BigDecimal.ZERO : customer.getCreditBalance();
        BigDecimal nextBalance = currentBalance.add(creditAmount);

        customer.setCreditBalance(nextBalance);
        customerRepository.save(customer);
        CustomerCreditAccount account = creditAccountRepository.findByCustomerId(customer.getId()).orElse(null);
        if (account == null) {
            account = new CustomerCreditAccount();
            account.setCustomer(customer);
        }
        account.setBalance(nextBalance);
        account.setCreditLimit(customer.getCreditLimit());
        creditAccountRepository.save(account);

        sale.setStatus("CREDIT");
        applyStockForSale(sale);
        Sale saved = saleRepository.save(sale);
        var actor = userRepository.findByEmail(SecurityUtil.currentUsername()).orElse(null);
        auditService.log(actor, "SALE_CREDIT", "Sale", String.valueOf(saved.getId()), null, saved);
        return toResponse(saved);
    }

    @Transactional
    public SaleDtos.SaleResponse refund(Long id, SaleDtos.RefundRequest request) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if (!"PAID".equals(sale.getStatus()) && !"PARTIALLY_REFUNDED".equals(sale.getStatus())) {
            throw new ApiException("Only paid sales can be refunded");
        }
        BigDecimal refundAmount = request.getAmount() != null ? request.getAmount() : calculateRefundAmount(sale, request);
        if (refundAmount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("Refund amount must be greater than zero");
        }
        if (refundAmount.compareTo(sale.getPaidAmount()) > 0) {
            throw new ApiException("Refund exceeds paid amount");
        }
        boolean approvalRequired = requiresRefundApproval(sale, request, refundAmount);
        User approvingManager = approvalRequired ? verifyRefundApproval(request) : null;
        Payment payment = new Payment();
        payment.setSale(sale);
        payment.setCustomer(sale.getCustomer());
        payment.setShift(sale.getShift());
        payment.setStore(resolveSaleStore(sale));
        payment.setMethod(normalizePaymentMethod(request.getMethod()));
        payment.setPaymentMethod(toPaymentEnum(request.getMethod()));
        payment.setStatus(Payment.PaymentStatus.REFUNDED);
        payment.setNotes(buildRefundNotes(request, approvingManager));
        payment.setAmount(refundAmount.negate());
        paymentRepository.save(payment);
        sale.setPaidAmount(sale.getPaidAmount().subtract(refundAmount));
        boolean fullRefund = sale.getPaidAmount().compareTo(BigDecimal.ZERO) <= 0
                || refundAmount.compareTo(sale.getGrandTotal()) >= 0;
        if (fullRefund) {
            sale.setStatus("REFUNDED");
            applyReturnStock(sale, request.getLines());
        } else {
            sale.setStatus("PARTIALLY_REFUNDED");
            applyReturnStock(sale, request.getLines());
        }
        Sale saved = saleRepository.save(sale);
        var actor = userRepository.findByEmail(SecurityUtil.currentUsername()).orElse(null);
        auditService.log(actor, "SALE_REFUND", "Sale", String.valueOf(saved.getId()), null,
                buildRefundAuditPayload(saved, refundAmount, approvalRequired, approvingManager));
        if ("CASH".equalsIgnoreCase(request.getMethod())) {
            cashEventService.recordInternal(
                    saved.getShift(),
                    "REFUND_CASH",
                    refundAmount.negate(),
                    request.getReason() != null ? request.getReason() : "Cash refund",
                    saved,
                    actor);
        }
        return toResponse(saved);
    }

    private boolean requiresRefundApproval(Sale sale, SaleDtos.RefundRequest request, BigDecimal refundAmount) {
        boolean fullSaleRefund = refundAmount.compareTo(sale.getGrandTotal()) >= 0
                || refundAmount.compareTo(sale.getPaidAmount()) >= 0;
        boolean nonCashRefund = !"CASH".equalsIgnoreCase(normalizePaymentMethod(request.getMethod()));
        boolean highValueRefund = refundAmount.compareTo(REFUND_APPROVAL_THRESHOLD) >= 0;
        boolean manualAmountRefund = request.getLines() == null || request.getLines().isEmpty();
        return Boolean.TRUE.equals(request.getForceApproval())
                || fullSaleRefund
                || nonCashRefund
                || highValueRefund
                || manualAmountRefund;
    }

    private User verifyRefundApproval(SaleDtos.RefundRequest request) {
        if (request.getManagerEmail() == null || request.getManagerEmail().isBlank()) {
            throw new ApiException("Manager email is required for this refund");
        }
        if (request.getManagerPassword() == null || request.getManagerPassword().isBlank()) {
            throw new ApiException("Manager password is required for this refund");
        }
        User manager = userRepository.findByEmail(request.getManagerEmail())
                .orElseThrow(() -> new ApiException("Manager not found"));
        boolean isManager = manager.getRoles().stream()
                .anyMatch(r -> "MANAGER".equals(r.getName()) || "OWNER".equals(r.getName()) || "ADMIN".equals(r.getName()));
        if (!isManager) {
            throw new ApiException("User does not have manager privileges");
        }
        if (!passwordEncoder.matches(request.getManagerPassword(), manager.getPasswordHash())) {
            throw new ApiException("Invalid manager credentials");
        }
        return manager;
    }

    private String buildRefundNotes(SaleDtos.RefundRequest request, User approvingManager) {
        StringBuilder note = new StringBuilder();
        if (request.getReason() != null && !request.getReason().isBlank()) {
            note.append(request.getReason().trim());
        }
        if (approvingManager != null) {
            if (note.length() > 0) {
                note.append(" | ");
            }
            note.append("Approved by ").append(approvingManager.getEmail());
            if (request.getApprovalReason() != null && !request.getApprovalReason().isBlank()) {
                note.append(" (").append(request.getApprovalReason().trim()).append(")");
            }
        }
        return note.length() == 0 ? null : note.toString();
    }

    private String buildRefundAuditPayload(Sale sale, BigDecimal refundAmount, boolean approvalRequired, User approvingManager) {
        StringBuilder audit = new StringBuilder();
        audit.append("Refund amount=").append(refundAmount);
        audit.append(", status=").append(sale.getStatus());
        if (approvalRequired) {
            audit.append(", approvalRequired=true");
        }
        if (approvingManager != null) {
            audit.append(", approvedBy=").append(approvingManager.getEmail());
        }
        return audit.toString();
    }

    @Transactional
    public SaleDtos.SaleResponse repayCreditSale(Long id, SaleDtos.CreditRepaymentRequest request) {
        requireOpenShiftForActor();
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        if (!"CREDIT".equals(sale.getStatus())) {
            throw new ApiException("Only credit sales can be repaid");
        }
        if (request.getAmount().compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("Repayment amount must be greater than zero");
        }
        Customer customer = sale.getCustomer();
        if (customer == null) {
            throw new ApiException("Customer is required for credit repayment");
        }

        BigDecimal remaining = sale.getGrandTotal().subtract(sale.getPaidAmount());
        if (request.getAmount().compareTo(remaining) > 0) {
            throw new ApiException("Repayment exceeds remaining balance");
        }

        Payment payment = new Payment();
        payment.setSale(sale);
        payment.setCustomer(customer);
        payment.setShift(sale.getShift());
        payment.setStore(resolveSaleStore(sale));
        payment.setMethod(normalizePaymentMethod(request.getMethod()));
        payment.setPaymentMethod(toPaymentEnum(request.getMethod()));
        payment.setAmount(request.getAmount());
        payment.setNotes(request.getNotes());
        payment.setStatus(Payment.PaymentStatus.COMPLETED);
        paymentRepository.save(payment);

        sale.setPaidAmount(sale.getPaidAmount().add(request.getAmount()));
        if (sale.getPaidAmount().compareTo(sale.getGrandTotal()) >= 0) {
            sale.setStatus("PAID");
        }
        Sale saved = saleRepository.save(sale);

        BigDecimal currentBalance = customer.getCreditBalance() == null ? BigDecimal.ZERO : customer.getCreditBalance();
        BigDecimal nextBalance = currentBalance.subtract(request.getAmount());
        if (nextBalance.compareTo(BigDecimal.ZERO) < 0) {
            nextBalance = BigDecimal.ZERO;
        }
        customer.setCreditBalance(nextBalance);
        customerRepository.save(customer);
        CustomerCreditAccount account = creditAccountRepository.findByCustomerId(customer.getId()).orElse(null);
        if (account != null) {
            account.setBalance(nextBalance);
            account.setCreditLimit(customer.getCreditLimit());
            creditAccountRepository.save(account);
        }

        var actor = userRepository.findByEmail(SecurityUtil.currentUsername()).orElse(null);
        auditService.log(actor, "SALE_REPAYMENT", "Sale", String.valueOf(saved.getId()), null, saved);
        return toResponse(saved);
    }

    private void applyStockForSale(Sale sale) {
        for (SaleLine line : sale.getLines()) {
            if (isAssembledBundle(line.getProduct())) {
                for (ProductBundleComponent component : line.getProduct().getBundleComponents()) {
                    applyStockMovement(sale, component.getComponentProduct(),
                            line.getQuantity().multiply(component.getComponentQuantity()), "SALE", "Bundle sale");
                }
                continue;
            }
            // Skip inventory update if product doesn't track inventory
            if (!line.getProduct().isTrackInventory()) {
                continue;
            }
            applyStockMovement(sale, line.getProduct(), line.getQuantity(), "SALE", "Sale");
        }
    }

    private void applyReturnStock(Sale sale, List<SaleDtos.RefundLineRequest> refundLines) {
        java.util.Map<Long, BigDecimal> lineQuantities = new java.util.HashMap<>();
        if (refundLines != null) {
            for (SaleDtos.RefundLineRequest refundLine : refundLines) {
                if (refundLine.getQuantity() == null || refundLine.getQuantity().compareTo(BigDecimal.ZERO) <= 0) {
                    throw new ApiException("Refund quantity must be greater than zero");
                }
                lineQuantities.merge(refundLine.getSaleLineId(), refundLine.getQuantity(), BigDecimal::add);
            }
        }
        for (SaleLine line : sale.getLines()) {
            BigDecimal returnQty = lineQuantities.isEmpty()
                    ? line.getQuantity()
                    : lineQuantities.getOrDefault(line.getId(), BigDecimal.ZERO);
            if (returnQty.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            if (returnQty.compareTo(line.getQuantity()) > 0) {
                throw new ApiException("Refund quantity exceeds sold quantity");
            }
            if (isAssembledBundle(line.getProduct())) {
                for (ProductBundleComponent component : line.getProduct().getBundleComponents()) {
                    reverseStockMovement(line.getProduct(), component.getComponentProduct(),
                            returnQty.multiply(component.getComponentQuantity()), "RETURN", "Bundle refund", sale);
                }
                continue;
            }
            // Skip inventory update if product doesn't track inventory
            if (!line.getProduct().isTrackInventory()) {
                continue;
            }
            reverseStockMovement(line.getProduct(), line.getProduct(), returnQty, "RETURN", "Refund", sale);
        }
    }

    private boolean isAssembledBundle(Product product) {
        return "BUNDLE".equals(product.getProductType()) && "ASSEMBLED_ON_SALE".equals(product.getBundleMode());
    }

    private void applyStockMovement(Sale sale, Product product, BigDecimal quantity, String type, String reason) {
        Store store = resolveSaleStore(sale);
        StockItem item = stockItemRepository.findByProductIdAndStoreId(product.getId(), store.getId())
                .orElseThrow(() -> new ApiException("Stock item missing"));
        BigDecimal newQty = item.getQuantity().subtract(quantity);
        if (newQty.compareTo(BigDecimal.ZERO) < 0 && !(RoleUtil.hasRole("OWNER") || RoleUtil.hasRole("MANAGER"))) {
            throw new ApiException("Insufficient stock for product " + product.getNameEn());
        }
        item.setQuantity(newQty);
        stockItemRepository.save(item);
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setStore(store);
        movement.setMovementType(type);
        movement.setQuantity(quantity.negate());
        movement.setReason(reason);
        stockMovementRepository.save(movement);
    }

    private void reverseStockMovement(Product sourceProduct, Product product, BigDecimal quantity, String type, String reason, Sale sale) {
        Store store = resolveSaleStore(sale);
        StockItem item = stockItemRepository.findByProductIdAndStoreId(product.getId(), store.getId())
                .orElseThrow(() -> new ApiException("Stock item missing"));
        item.setQuantity(item.getQuantity().add(quantity));
        stockItemRepository.save(item);
        StockMovement movement = new StockMovement();
        movement.setProduct(product);
        movement.setStore(store);
        movement.setMovementType(type);
        movement.setQuantity(quantity);
        movement.setReason(reason + (sourceProduct != null && !sourceProduct.getId().equals(product.getId()) ? " • " + sourceProduct.getNameEn() : ""));
        stockMovementRepository.save(movement);
    }

    private SaleDtos.SaleResponse toResponse(Sale sale) {
        SaleDtos.SaleResponse resp = new SaleDtos.SaleResponse();
        resp.setId(sale.getId());
        resp.setInvoiceNumber(sale.getSaleNumber() != null ? sale.getSaleNumber() : sale.getId().toString());
        resp.setStatus(sale.getStatus());
        resp.setDisplayName(sale.getDisplayName());
        resp.setSubtotal(sale.getSubtotal());
        resp.setDiscountAmount(sale.getDiscountAmount());
        resp.setTaxRate(sale.getTaxRate());
        resp.setTaxAmount(sale.getTaxAmount());
        resp.setGrandTotal(sale.getGrandTotal());
        resp.setPaidAmount(sale.getPaidAmount());
        resp.setDeliveryCharge(sale.getDeliveryCharge());
        resp.setOtherCharge(sale.getOtherCharge());
        resp.setDepositAmount(sale.getDepositAmount());
        resp.setNote(sale.getNote());
        resp.setOrderDate(sale.getOrderDate() != null ? sale.getOrderDate().toString() : null);
        resp.setDeliveryDate(sale.getDeliveryDate() != null ? sale.getDeliveryDate().toString() : null);
        resp.setPaymentTerms(sale.getPaymentTerms());
        resp.setCustomerId(sale.getCustomer() != null ? sale.getCustomer().getId() : null);
        resp.setCustomerName(sale.getCustomer() != null ? firstNonBlank(sale.getCustomer().getDisplayName(), sale.getCustomer().getNameEn(), sale.getCustomer().getNameKm()) : null);
        resp.setTableId(sale.getTable() != null ? sale.getTable().getId() : null);
        resp.setTableNumber(sale.getTable() != null ? sale.getTable().getTableNumber() : null);
        resp.setCashierName(sale.getCreatedBy() != null ? sale.getCreatedBy().getFullName() : null);
        resp.setShiftId(sale.getShift() != null ? sale.getShift().getId() : null);
        resp.setCreatedAt(sale.getCreatedAt() != null ? sale.getCreatedAt().toString() : null);
        resp.setEndDate(sale.getUpdatedAt() != null ? sale.getUpdatedAt().toString() : null);
        resp.setLines(sale.getLines().stream().map(line -> {
            SaleDtos.SaleLineResponse lr = new SaleDtos.SaleLineResponse();
            lr.setId(line.getId());
            lr.setProductId(line.getProduct().getId());
            lr.setProductNameEn(line.getProduct().getNameEn());
            lr.setProductNameKm(line.getProduct().getNameKm());
            lr.setQuantity(line.getQuantity());
            lr.setUnitPrice(line.getUnitPrice());
            lr.setLineDiscount(line.getLineDiscount());
            lr.setLineTotal(line.getLineTotal());
            lr.setNote(line.getLineNote());
            lr.setModifierSummary(line.getModifierSummary());
            lr.setModifierData(line.getModifierData());
            return lr;
        }).collect(Collectors.toList()));
        resp.setPayments(paymentRepository.findBySaleIdOrderByCreatedAtAscIdAsc(sale.getId()).stream().map(payment -> {
            SaleDtos.PaymentSummary summary = new SaleDtos.PaymentSummary();
            summary.setId(payment.getId());
            summary.setMethod(payment.getMethod());
            summary.setAmount(payment.getAmount());
            summary.setStatus(payment.getStatus() != null ? payment.getStatus().name() : null);
            return summary;
        }).collect(Collectors.toList()));
        return resp;
    }

    /**
     * Change sale status directly (used by CartService for PACKAGING flow).
     */
    @Transactional
    public void setStatus(Long saleId, String status) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ApiException("Sale not found"));
        sale.setStatus(status);
        saleRepository.save(sale);
    }

    /**
     * Hold/Prepare — employee claims this order (PACKAGING → PREPARING).
     */
    @Transactional
    public SaleDtos.SaleResponse startPreparing(Long saleId) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ApiException("Sale not found"));
        if (!"PACKAGING".equals(sale.getStatus())) {
            throw new ApiException("Sale is not in PACKAGING status");
        }
        sale.setStatus("PREPARING");
        return toResponse(saleRepository.save(sale));
    }

    /**
     * Complete packaging — moves sale from PREPARING to PAID (ready for invoice).
     */
    @Transactional
    public SaleDtos.SaleResponse completePackaging(Long saleId) {
        Sale sale = saleRepository.findById(saleId)
                .orElseThrow(() -> new ApiException("Sale not found"));
        if (!"PREPARING".equals(sale.getStatus())) {
            throw new ApiException("Sale is not in PREPARING status");
        }
        sale.setStatus("PAID");
        sale.setPaidAt(Instant.now());
        return toResponse(saleRepository.save(sale));
    }

    public java.util.List<SaleDtos.SaleResponse> listByStatus(String status) {
        return saleRepository.findByStatusOrderByCreatedAtDesc(status).stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public java.util.List<SaleDtos.SaleResponse> listAll() {
        return saleRepository.findAllByOrderByCreatedAtDesc().stream()
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    public java.util.List<SaleDtos.SaleResponse> listByShift(Long shiftId, String status) {
        if (status != null && !status.isBlank()) {
            return saleRepository.findByShiftIdAndStatusOrderByCreatedAtDesc(shiftId, status).stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        } else {
            return saleRepository.findByShiftIdOrderByCreatedAtDesc(shiftId).stream()
                    .map(this::toResponse)
                    .collect(Collectors.toList());
        }
    }

    public java.util.List<SaleDtos.SaleResponse> listFiltered(
            Long shiftId,
            String status,
            Instant dateFrom,
            Instant dateTo,
            String query) {
        String normalizedStatus = (status == null || status.isBlank()) ? null : status;
        String normalizedQuery = (query == null || query.isBlank()) ? null : query.trim();
        return saleRepository.findFiltered(shiftId, normalizedStatus, dateFrom, dateTo).stream()
                .filter(sale -> matchesQuery(sale, normalizedQuery))
                .map(this::toResponse)
                .collect(Collectors.toList());
    }

    /**
     * Resolve the unit price for a sale line.
     * Priority: 1) explicit unitPrice from request, 2) price list match by customer type, 3) product base price.
     * Also enforces minimum order quantity when a price list rule applies.
     */
    private BigDecimal resolveUnitPrice(SaleDtos.SaleLineRequest lineReq, Product product, Customer customer) {
        BigDecimal requested = lineReq.getUnitPrice();
        if (requested != null && requested.compareTo(BigDecimal.ZERO) > 0) {
            return requested; // explicit override — skip price list
        }
        if (customer != null) {
            PriceListService.PriceResolution resolution =
                    priceListService.resolvePriceForCustomer(customer, product.getId(), Instant.now());
            if (resolution != null) {
                if (resolution.minimumOrderQty() != null
                        && lineReq.getQuantity().compareTo(resolution.minimumOrderQty()) < 0) {
                    throw new ApiException("Minimum order quantity for " + product.getNameEn()
                            + " is " + resolution.minimumOrderQty().stripTrailingZeros().toPlainString());
                }
                return resolution.price();
            }
        }
        BigDecimal basePrice = product.getPrice();
        if (basePrice == null || basePrice.compareTo(BigDecimal.ZERO) == 0) {
            throw new ApiException("No price configured for product: " + product.getNameEn());
        }
        return basePrice;
    }

    /**
     * Enforce credit hold flag and credit limit before allowing a credit sale.
     * A customer with creditLimit = 0 is treated as "unlimited credit".
     */
    private void enforceCreditLimit(Customer customer, BigDecimal additionalAmount) {
        if (customer == null) return;
        if (customer.isCreditHold()) {
            throw new ApiException("Customer is on credit hold — new credit sales are blocked");
        }
        BigDecimal limit = customer.getCreditLimit() == null ? BigDecimal.ZERO : customer.getCreditLimit();
        if (limit.compareTo(BigDecimal.ZERO) > 0) {
            BigDecimal current = customer.getCreditBalance() == null ? BigDecimal.ZERO : customer.getCreditBalance();
            if (current.add(additionalAmount).compareTo(limit) > 0) {
                throw new ApiException("CREDIT_LIMIT_EXCEEDED: adding "
                        + additionalAmount.setScale(2, RoundingMode.HALF_UP)
                        + " would exceed credit limit of " + limit.setScale(2, RoundingMode.HALF_UP));
            }
        }
    }

    private boolean matchesQuery(Sale sale, String query) {
        if (query == null) {
            return true;
        }
        String needle = query.toLowerCase(Locale.ROOT);
        if (containsIgnoreCase(sale.getSaleNumber(), needle)) {
            return true;
        }
        if (sale.getId() != null && sale.getId().toString().contains(query)) {
            return true;
        }
        if (sale.getCreatedBy() != null && containsIgnoreCase(sale.getCreatedBy().getFullName(), needle)) {
            return true;
        }
        return sale.getCustomer() != null && containsIgnoreCase(sale.getCustomer().getNameEn(), needle);
    }

    private BigDecimal safeAmount(BigDecimal value) {
        return value == null || value.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : value;
    }

    private boolean containsIgnoreCase(String value, String needleLower) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(needleLower);
    }

    private LocalDate parseDate(String rawDate) {
        if (rawDate == null || rawDate.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(rawDate.trim());
        } catch (Exception ex) {
            throw new ApiException("Invalid date value");
        }
    }

    private LocalDate parseDateOrDefault(String rawDate, LocalDate fallback) {
        LocalDate parsed = parseDate(rawDate);
        return parsed != null ? parsed : fallback;
    }

    private String resolvePaymentTerms(String requestedTerms, Customer customer) {
        String normalized = trimToNull(requestedTerms);
        if (normalized != null) {
            return normalized;
        }
        return customer != null ? trimToNull(customer.getPaymentTerms()) : null;
    }

    private String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }

    private void ensureEditable(Sale sale) {
        if (paymentRepository.countBySaleId(sale.getId()) > 0 || sale.getPaidAmount().compareTo(BigDecimal.ZERO) > 0) {
            throw new ApiException("Sale cannot be edited after payment has started");
        }
    }

    private void requireOpenShiftForActor() {
        User actor = userRepository.findByEmail(SecurityUtil.currentUsername())
                .orElseThrow(() -> new ApiException("User not found"));
        Shift currentShift = shiftRepository.findFirstByOpenedByIdAndStatusOrderByOpenedAtDesc(actor.getId(), "OPEN")
                .orElse(null);
        if (currentShift == null) {
            throw new ApiException("Open a shift before processing sales");
        }
    }

    private String normalizePaymentMethod(String rawMethod) {
        if (rawMethod == null || rawMethod.isBlank()) {
            throw new ApiException("Payment method is required");
        }
        String method = rawMethod.trim().toUpperCase(Locale.US);
        return switch (method) {
            case "KHQR", "ABA_KHQR" -> "KHQR";
            case "ABA", "ABA_PAY" -> "ABA";
            case "WING" -> "WING";
            case "CARD", "CREDIT_CARD", "DEBIT_CARD" -> "CARD";
            case "BANK_TRANSFER" -> "BANK_TRANSFER";
            case "CASH" -> "CASH";
            default -> throw new ApiException("Unsupported payment method: " + rawMethod);
        };
    }

    private Payment.PaymentMethod toPaymentEnum(String method) {
        return switch (normalizePaymentMethod(method)) {
            case "KHQR" -> Payment.PaymentMethod.KHQR;
            case "ABA" -> Payment.PaymentMethod.ABA;
            case "WING" -> Payment.PaymentMethod.WING;
            case "CARD" -> Payment.PaymentMethod.CARD;
            case "BANK_TRANSFER" -> Payment.PaymentMethod.BANK_TRANSFER;
            default -> Payment.PaymentMethod.CASH;
        };
    }

    private Store resolveSaleStore(Sale sale) {
        if (sale.getShift() != null && sale.getShift().getStore() != null) {
            return sale.getShift().getStore();
        }
        return storeRepository.findAll().stream().findFirst()
                .orElseThrow(() -> new ApiException("No store configured"));
    }

    private void persistInvoiceDiscount(Sale sale, BigDecimal discount) {
        sale.getDiscounts().clear();
        if (discount == null || discount.compareTo(BigDecimal.ZERO) <= 0)
            return;
        SaleDiscount d = new SaleDiscount();
        d.setSale(sale);
        d.setDiscountType("INVOICE");
        d.setAmount(discount);
        sale.getDiscounts().add(d);
        saleDiscountRepository.save(d);
    }

    private BigDecimal calculateRefundAmount(Sale sale, SaleDtos.RefundRequest request) {
        if (request.getLines() == null || request.getLines().isEmpty()) {
            return BigDecimal.ZERO;
        }
        BigDecimal total = BigDecimal.ZERO;
        java.util.Map<Long, SaleLine> saleLines = sale.getLines().stream()
                .collect(Collectors.toMap(SaleLine::getId, line -> line));
        for (SaleDtos.RefundLineRequest lineRequest : request.getLines()) {
            SaleLine line = saleLines.get(lineRequest.getSaleLineId());
            if (line == null) {
                throw new ApiException("Refund line not found on sale");
            }
            if (lineRequest.getQuantity().compareTo(line.getQuantity()) > 0) {
                throw new ApiException("Refund quantity exceeds sold quantity");
            }
            BigDecimal proportionalLineTotal = line.getLineTotal()
                    .divide(line.getQuantity(), 4, RoundingMode.HALF_UP)
                    .multiply(lineRequest.getQuantity())
                    .setScale(2, RoundingMode.HALF_UP);
            total = total.add(proportionalLineTotal);
        }
        return total;
    }

    private BigDecimal refundedQuantityForLine(SaleLine line, Sale sale) {
        if (!"PARTIALLY_REFUNDED".equals(sale.getStatus()) && !"REFUNDED".equals(sale.getStatus())) {
            return BigDecimal.ZERO;
        }
        if ("REFUNDED".equals(sale.getStatus())) {
            return line.getQuantity();
        }
        BigDecimal refundedAmount = totalRefundedAmount(sale);
        if (refundedAmount.compareTo(BigDecimal.ZERO) <= 0 || sale.getGrandTotal().compareTo(BigDecimal.ZERO) <= 0) {
            return BigDecimal.ZERO;
        }
        BigDecimal ratio = refundedAmount.divide(sale.getGrandTotal(), 4, RoundingMode.HALF_UP);
        return line.getQuantity().multiply(ratio).setScale(2, RoundingMode.HALF_UP);
    }

    private BigDecimal totalRefundedAmount(Sale sale) {
        return paymentRepository.findBySaleIdOrderByCreatedAtAscIdAsc(sale.getId()).stream()
                .map(Payment::getAmount)
                .filter(amount -> amount != null && amount.compareTo(BigDecimal.ZERO) < 0)
                .map(BigDecimal::abs)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public com.kaknnea.pos.dto.ReceiptDtos.ReceiptResponse receipt(Long id) {
        Sale sale = saleRepository.findById(id).orElseThrow(() -> new ApiException("Sale not found"));
        var settings = businessSettingsRepository.findAll().stream().findFirst().orElse(null);
        com.kaknnea.pos.dto.ReceiptDtos.ReceiptResponse resp = new com.kaknnea.pos.dto.ReceiptDtos.ReceiptResponse();
        if (settings != null) {
            resp.setBusinessName(settings.getBusinessName());
            resp.setAddress(settings.getAddress());
            resp.setPhone(settings.getPhone());
            resp.setCurrency(settings.getCurrency());
            resp.setFooter(settings.getReceiptFooter());
        }
        resp.setSaleId(sale.getId());
        resp.setShiftId(sale.getShift() != null ? sale.getShift().getId() : null);
        resp.setStoreId(sale.getShift() != null && sale.getShift().getStore() != null ? sale.getShift().getStore().getId() : null);
        resp.setStoreName(sale.getShift() != null && sale.getShift().getStore() != null ? sale.getShift().getStore().getName() : null);
        resp.setCreatedAt(sale.getCreatedAt() != null ? sale.getCreatedAt().toString() : null);
        resp.setCashierName(sale.getCreatedBy() != null ? sale.getCreatedBy().getFullName() : null);
        if (sale.getCustomer() != null) {
            resp.setCustomerName(sale.getCustomer().getNameEn());
            resp.setCustomerPhone(sale.getCustomer().getPhone());
        }
        resp.setSubtotal(sale.getSubtotal());
        resp.setTaxAmount(sale.getTaxAmount());
        resp.setDiscountAmount(sale.getDiscountAmount());
        resp.setTotal(sale.getGrandTotal());
        resp.setPaidAmount(sale.getPaidAmount());
        resp.setChangeAmount(sale.getChangeAmount());
        resp.setRefundedAmount(totalRefundedAmount(sale));
        resp.setStatus(sale.getStatus());
        resp.setQrImageData(buildQrImageData(sale.getId(), 180));
        resp.setLines(sale.getLines().stream().map(line -> {
            com.kaknnea.pos.dto.ReceiptDtos.ReceiptLine rl = new com.kaknnea.pos.dto.ReceiptDtos.ReceiptLine();
            rl.setSaleLineId(line.getId());
            rl.setNameEn(line.getProduct().getNameEn());
            rl.setNameKm(line.getProduct().getNameKm());
            rl.setQty(line.getQuantity());
            rl.setUnitPrice(line.getUnitPrice());
            rl.setLineTotal(line.getLineTotal());
            rl.setRefundedQty(refundedQuantityForLine(line, sale));
            return rl;
        }).collect(java.util.stream.Collectors.toList()));
        resp.setPayments(paymentRepository.findBySaleIdOrderByCreatedAtAscIdAsc(sale.getId()).stream().map(payment -> {
            com.kaknnea.pos.dto.ReceiptDtos.ReceiptPayment receiptPayment = new com.kaknnea.pos.dto.ReceiptDtos.ReceiptPayment();
            receiptPayment.setMethod(payment.getMethod());
            receiptPayment.setAmount(payment.getAmount());
            return receiptPayment;
        }).collect(Collectors.toList()));
        return resp;
    }

    public byte[] invoicePdf(Long id, Boolean thermal) {
        var receipt = receipt(id);
        String html = (thermal != null && thermal)
                ? generateThermalReceiptHtml(receipt)
                : generateStandardInvoiceHtml(receipt);
        return pdfService.renderHtmlToPdf(html);
    }

    private String generateStandardInvoiceHtml(com.kaknnea.pos.dto.ReceiptDtos.ReceiptResponse receipt) {
        String qrImage = buildQrImageData(receipt.getSaleId(), 160);
        String currency = receipt.getCurrency();
        String symbol = currencySymbol(currency);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/><style>")
                .append("@page { size: A4; margin: 15mm; }")
                .append("body{font-family:'Noto Sans Khmer','KhmerFallback',sans-serif;font-size:12px;line-height:1.45;color:#111827;margin:0;padding:0;}")
                .append(".title{font-size:18px;font-weight:bold;margin-bottom:10px;}")
                .append("table{width:100%;border-collapse:collapse;margin-top:10px;}")
                .append("th,td{border:1px solid #ddd;padding:6px;text-align:left;}")
                .append(".qr{margin-top:12px;text-align:center;}")
                .append("</style></head><body>")
                .append("<div class='title'>Invoice #").append(receipt.getSaleId()).append("</div>")
                .append("<div>").append(nullToEmpty(receipt.getBusinessName())).append("</div>")
                .append("<div>").append(nullToEmpty(receipt.getAddress())).append("</div>")
                .append("<div>").append(nullToEmpty(receipt.getPhone())).append("</div>")
                .append("<div>Cashier: ").append(nullToEmpty(receipt.getCashierName())).append("</div>")
                .append("<div>Shift: ").append(receipt.getShiftId() == null ? "-" : receipt.getShiftId()).append("</div>")
                .append("<div>Store: ").append(nullToEmpty(receipt.getStoreName())).append("</div>")
                .append("<hr/>")
                .append("<table><thead><tr><th>Item</th><th>Qty</th><th>Price</th><th>Total</th></tr></thead><tbody>");
        if (receipt.getLines() != null) {
            for (var line : receipt.getLines()) {
                String name = buildItemName(line.getNameKm(), line.getNameEn());
                html.append("<tr><td>").append(name).append("</td>")
                        .append("<td>").append(line.getQty()).append("</td>")
                        .append("<td>").append(symbol).append(formatMoney(line.getUnitPrice(), currency))
                        .append("</td>")
                        .append("<td>").append(symbol).append(formatMoney(line.getLineTotal(), currency))
                        .append("</td></tr>");
            }
        }
        html.append("</tbody></table>")
                .append("<div>Subtotal: ").append(symbol).append(formatMoney(receipt.getSubtotal(), currency))
                .append("</div>")
                .append("<div>Tax: ").append(symbol).append(formatMoney(receipt.getTaxAmount(), currency))
                .append("</div>")
                .append("<div>Discount: ").append(symbol).append(formatMoney(receipt.getDiscountAmount(), currency))
                .append("</div>")
                .append("<div>Total: ").append(symbol).append(formatMoney(receipt.getTotal(), currency))
                .append("</div>");

        appendPaymentSummary(html, receipt, symbol, currency);

        if (receipt.getFooter() != null && !receipt.getFooter().isBlank()) {
            html.append("<div style='margin-top:8px;'>").append(receipt.getFooter()).append("</div>");
        }
        if (qrImage != null) {
            html.append("<div class='qr'>")
                    .append("<img src='").append(qrImage).append("' width='120' height='120' />")
                    .append("<div>Scan to view invoice</div>")
                    .append("</div>");
        }
        html.append("</body></html>");
        return html.toString();
    }

    private String generateThermalReceiptHtml(com.kaknnea.pos.dto.ReceiptDtos.ReceiptResponse receipt) {
        String qrImage = buildQrImageData(receipt.getSaleId(), 120);
        String currency = receipt.getCurrency();
        String symbol = currencySymbol(currency);
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/><style>")
                .append("@page { size: 80mm auto; margin: 0; }")
                .append("body { font-family: 'Noto Sans Khmer','KhmerFallback',sans-serif; font-size: 10px; line-height: 1.45; width: 80mm; margin: 0 auto; padding: 5mm; color: #000; }")
                .append(".center { text-align: center; }")
                .append(".bold { font-weight: bold; }")
                .append(".line { border-top: 1px dashed #000; margin: 3px 0; }")
                .append(".row { display: flex; justify-content: space-between; margin: 2px 0; }")
                .append(".total { font-size: 12px; font-weight: bold; }")
                .append("table { width: 100%; border-collapse: collapse; }")
                .append("th, td { padding: 2px 0; text-align: left; font-size: 10px; }")
                .append("th { font-weight: bold; }")
                .append("td.num, th.num { text-align: right; }")
                .append("</style></head><body>");

        // Header
        html.append("<div class='center bold'>")
                .append(nullToEmpty(receipt.getBusinessName())).append("<br/>")
                .append(nullToEmpty(receipt.getAddress())).append("<br/>")
                .append(nullToEmpty(receipt.getPhone())).append("<br/>")
                .append("</div>")
                .append("<div class='line'></div>");

        // Invoice info
        String dateTime = "";
        if (receipt.getCreatedAt() != null) {
            try {
                java.time.Instant instant = java.time.Instant.parse(receipt.getCreatedAt());
                dateTime = java.time.ZonedDateTime.ofInstant(instant, java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
            } catch (java.time.format.DateTimeParseException ex) {
                try {
                    dateTime = java.time.LocalDateTime.parse(receipt.getCreatedAt())
                            .format(java.time.format.DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"));
                } catch (java.time.format.DateTimeParseException ignored) {
                    dateTime = receipt.getCreatedAt();
                }
            }
        }
        html.append("<div class='center'>")
                .append("វិក្កយបត្រលេខ / Invoice #").append(receipt.getSaleId()).append("<br/>")
                .append(dateTime)
                .append("</div>")
                .append("<div class='line'></div>");
        if (receipt.getCashierName() != null || receipt.getShiftId() != null || receipt.getStoreName() != null) {
            html.append("<div class='center'>")
                    .append(nullToEmpty(receipt.getCashierName()))
                    .append(receipt.getShiftId() != null ? " • Shift #" + receipt.getShiftId() : "")
                    .append(receipt.getStoreName() != null ? " • " + receipt.getStoreName() : "")
                    .append("</div><div class='line'></div>");
        }

        // Items
        html.append("<table><thead><tr>")
                .append("<th>Item</th>")
                .append("<th class='num'>Qty</th>")
                .append("<th class='num'>Price</th>")
                .append("<th class='num'>Total</th>")
                .append("</tr></thead><tbody>");
        if (receipt.getLines() != null) {
            for (var line : receipt.getLines()) {
                String itemName = buildItemName(line.getNameKm(), line.getNameEn());
                html.append("<tr>")
                        .append("<td>").append(itemName).append("</td>")
                        .append("<td class='num'>").append(line.getQty()).append("</td>")
                        .append("<td class='num'>").append(formatMoney(line.getUnitPrice(), currency)).append("</td>")
                        .append("<td class='num'>").append(formatMoney(line.getLineTotal(), currency)).append("</td>")
                        .append("</tr>");
            }
        }
        html.append("</tbody></table>");

        html.append("<div class='line'></div>");

        // Totals
        html.append("<div class='row'>")
                .append("  <span>សរុបរង / Subtotal:</span>")
                .append("  <span>").append(symbol).append(formatMoney(receipt.getSubtotal(), currency))
                .append("</span>")
                .append("</div>");

        if (receipt.getDiscountAmount() != null
                && receipt.getDiscountAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            html.append("<div class='row'>")
                    .append("  <span>បញ្ចុះតម្លៃ / Discount:</span>")
                    .append("  <span>-").append(symbol).append(formatMoney(receipt.getDiscountAmount(), currency))
                    .append("</span>")
                    .append("</div>");
        }

        if (receipt.getTaxAmount() != null && receipt.getTaxAmount().compareTo(java.math.BigDecimal.ZERO) > 0) {
            html.append("<div class='row'>")
                    .append("  <span>ពន្ធ / Tax:</span>")
                    .append("  <span>").append(symbol).append(formatMoney(receipt.getTaxAmount(), currency))
                    .append("</span>")
                    .append("</div>");
        }

        html.append("<div class='line'></div>");
        html.append("<div class='row total'>")
                .append("  <span>សរុប / TOTAL:</span>")
                .append("  <span>").append(symbol).append(formatMoney(receipt.getTotal(), currency)).append("</span>")
                .append("</div>");

        appendPaymentSummary(html, receipt, symbol, currency);

        // Footer
        String footer = receipt.getFooter();
        if (footer != null && !footer.isEmpty()) {
            html.append("<div class='line'></div>")
                    .append("<div class='center'>").append(footer).append("</div>");
        }

        if (qrImage != null) {
            html.append("<div class='line'></div>")
                    .append("<div class='center'>")
                    .append("<img src='").append(qrImage).append("' width='120' height='120' />")
                    .append("<div>Scan to view invoice</div>")
                    .append("</div>");
        }

        html.append("<div class='center' style='margin-top: 5mm;'>")
                .append("អរគុណ! / Thank You!")
                .append("</div>");

        html.append("</body></html>");
        return html.toString();
    }

    private void appendPaymentSummary(StringBuilder html,
            com.kaknnea.pos.dto.ReceiptDtos.ReceiptResponse receipt,
            String symbol,
            String currency) {
        BigDecimal total = receipt.getTotal() == null ? BigDecimal.ZERO : receipt.getTotal();
        BigDecimal paid = receipt.getPaidAmount() == null ? BigDecimal.ZERO : receipt.getPaidAmount();
        BigDecimal balance = total.subtract(paid);
        BigDecimal change = receipt.getChangeAmount() == null ? paid.subtract(total) : receipt.getChangeAmount();

        html.append("<div style='margin-top:6px;'>")
                .append("<div>Paid: ").append(symbol).append(formatMoney(paid, currency)).append("</div>");
        if (receipt.getPayments() != null && !receipt.getPayments().isEmpty()) {
            html.append("<div>Payments:</div>");
            for (var payment : receipt.getPayments()) {
                html.append("<div>")
                        .append(payment.getMethod())
                        .append(": ")
                        .append(payment.getAmount().compareTo(BigDecimal.ZERO) < 0 ? "-" : "")
                        .append(symbol)
                        .append(formatMoney(payment.getAmount().abs(), currency))
                        .append("</div>");
            }
        }
        if (receipt.getRefundedAmount() != null && receipt.getRefundedAmount().compareTo(BigDecimal.ZERO) > 0) {
            html.append("<div>Refunded: ").append(symbol).append(formatMoney(receipt.getRefundedAmount(), currency)).append("</div>");
        }

        if (balance.compareTo(BigDecimal.ZERO) > 0) {
            html.append("<div>Balance: ").append(symbol).append(formatMoney(balance, currency)).append("</div>");
        }
        if (change.compareTo(BigDecimal.ZERO) > 0) {
            html.append("<div>Change: ").append(symbol).append(formatMoney(change, currency)).append("</div>");
        }
        if (receipt.getStatus() != null) {
            html.append("<div>Status: ").append(receipt.getStatus()).append("</div>");
        }
        html.append("</div>");
    }

    private String buildItemName(String nameKm, String nameEn) {
        String km = nameKm == null ? "" : nameKm.trim();
        String en = nameEn == null ? "" : nameEn.trim();
        if (!km.isEmpty() && !en.isEmpty() && !km.equalsIgnoreCase(en)) {
            return km + " (" + en + ")";
        }
        return !km.isEmpty() ? km : en;
    }

    private String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private String currencySymbol(String currency) {
        if (currency == null) {
            return "";
        }
        String code = currency.trim().toUpperCase(java.util.Locale.US);
        if ("KHR".equals(code)) {
            return "៛";
        }
        if ("USD".equals(code)) {
            return "$";
        }
        return code + " ";
    }

    private String formatMoney(BigDecimal amount, String currency) {
        BigDecimal safe = amount == null ? BigDecimal.ZERO : amount;
        if (currency != null && "KHR".equalsIgnoreCase(currency)) {
            return String.format(java.util.Locale.US, "%,.0f", safe);
        }
        return String.format(java.util.Locale.US, "%,.2f", safe);
    }

    private String buildQrImageData(Long saleId, int size) {
        String baseUrl = System.getenv("QR_BASE_URL");
        if (baseUrl == null || baseUrl.isBlank()) {
            baseUrl = "http://localhost:4200";
        }
        if (baseUrl.endsWith("/")) {
            baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        }
        String data = baseUrl + "/invoice/" + saleId;
        try {
            com.google.zxing.common.BitMatrix matrix = new com.google.zxing.qrcode.QRCodeWriter()
                    .encode(data, com.google.zxing.BarcodeFormat.QR_CODE, size, size);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            com.google.zxing.client.j2se.MatrixToImageWriter.writeToStream(matrix, "PNG", out);
            return "data:image/png;base64," + java.util.Base64.getEncoder().encodeToString(out.toByteArray());
        } catch (Exception e) {
            return null;
        }
    }

}
