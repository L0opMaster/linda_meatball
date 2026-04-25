package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.Customer;
import com.kaknnea.pos.domain.CustomerCreditAllocation;
import com.kaknnea.pos.domain.CustomerCreditOpeningBalance;
import com.kaknnea.pos.domain.Payment;
import com.kaknnea.pos.domain.Sale;
import com.kaknnea.pos.domain.Transaction;
import com.kaknnea.pos.dto.CreditCollectionDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.CustomerCreditAccountRepository;
import com.kaknnea.pos.repository.CustomerCreditAllocationRepository;
import com.kaknnea.pos.repository.CustomerCreditOpeningBalanceRepository;
import com.kaknnea.pos.repository.CustomerRepository;
import com.kaknnea.pos.repository.PaymentRepository;
import com.kaknnea.pos.repository.SaleRepository;
import com.kaknnea.pos.repository.StoreRepository;
import com.kaknnea.pos.repository.TransactionRepository;
import com.kaknnea.pos.util.SecurityUtil;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.beans.factory.annotation.Autowired;

@Service
public class CreditCollectionService {
    @Autowired
    private CustomerRepository customerRepository;
    @Autowired
    private SaleRepository saleRepository;
    @Autowired
    private PaymentRepository paymentRepository;
    @Autowired
    private TransactionRepository transactionRepository;
    @Autowired
    private StoreRepository storeRepository;
    @Autowired
    private CustomerCreditOpeningBalanceRepository openingBalanceRepository;
    @Autowired
    private CustomerCreditAllocationRepository allocationRepository;
    @Autowired
    private CustomerCreditAccountRepository creditAccountRepository;

    public CreditCollectionService() {
        // Default constructor for Spring
    }

    public CreditCollectionService(
            CustomerRepository customerRepository,
            SaleRepository saleRepository,
            PaymentRepository paymentRepository,
            TransactionRepository transactionRepository,
            StoreRepository storeRepository,
            CustomerCreditOpeningBalanceRepository openingBalanceRepository,
            CustomerCreditAllocationRepository allocationRepository,
            CustomerCreditAccountRepository creditAccountRepository) {
        this.customerRepository = customerRepository;
        this.saleRepository = saleRepository;
        this.paymentRepository = paymentRepository;
        this.transactionRepository = transactionRepository;
        this.storeRepository = storeRepository;
        this.openingBalanceRepository = openingBalanceRepository;
        this.allocationRepository = allocationRepository;
        this.creditAccountRepository = creditAccountRepository;
    }

    @Transactional(readOnly = true)
    public CreditCollectionDtos.PreviewResponse previewCollection(
            Long customerId,
            BigDecimal amount,
            String strategy) {
        Customer customer = requireCustomer(customerId);
        BigDecimal normalized = normalizeAmount(amount);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("Amount must be greater than zero");
        }
        enforceStrategy(strategy, false);
        return buildPreview(customer, normalized);
    }

    @Transactional
    public CreditCollectionDtos.CollectResponse collect(
            Long customerId,
            CreditCollectionDtos.CollectRequest request) {
        Customer customer = requireCustomer(customerId);
        BigDecimal amount = normalizeAmount(request.getAmount());
        if (amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new ApiException("Collection amount must be greater than zero");
        }

        String reference = buildReference(customerId, request.getIdempotencyKey());
        Payment existing = paymentRepository.findByReferenceNumber(reference).orElse(null);
        if (existing != null) {
            return buildCollectResponse(customer, existing);
        }

        List<CreditCollectionDtos.AllocationRow> rows;
        if (request.getAllocations() != null && !request.getAllocations().isEmpty()) {
            rows = buildManualAllocations(customer, amount, request.getAllocations());
        } else {
            enforceStrategy(request.getStrategy(), false);
            CreditCollectionDtos.PreviewResponse preview = buildPreview(customer, amount);
            if (!preview.isValid()) {
                throw new ApiException(preview.getMessage());
            }
            rows = preview.getAllocations();
        }

        Payment payment = Payment.builder()
                .customer(customer)
                .store(storeRepository.findAll().stream().findFirst().orElse(null))
                .amount(amount)
                .currency("USD")
                .status(Payment.PaymentStatus.COMPLETED)
                .paymentMethod(resolvePaymentMethod(request.getPaymentMethod()))
                .method(resolveLegacyMethod(resolvePaymentMethod(request.getPaymentMethod())))
                .referenceNumber(reference)
                .notes(request.getNotes())
                .createdBy(SecurityUtil.currentUsername())
                .build();
        payment = paymentRepository.save(payment);

        Transaction capture = Transaction.builder()
                .payment(payment)
                .transactionType(Transaction.TransactionType.CAPTURE)
                .amount(payment.getAmount())
                .status(Transaction.TransactionStatus.SUCCESS)
                .processorName("Manual")
                .notes(request.getNotes())
                .createdBy(SecurityUtil.currentUsername())
                .build();
        transactionRepository.save(capture);

        applyAllocations(customer, payment, rows, request.getNotes());
        recalculateCustomerCreditBalance(customer);
        return buildCollectResponse(customer, payment);
    }

    @Transactional
    public void recordSaleRepaymentAllocation(
            Sale sale,
            Payment payment,
            BigDecimal amount,
            String notes) {
        if (sale.getCustomer() == null) {
            return;
        }
        BigDecimal normalized = normalizeAmount(amount);
        if (normalized.compareTo(BigDecimal.ZERO) <= 0) {
            return;
        }
        CustomerCreditAllocation allocation = new CustomerCreditAllocation();
        allocation.setPayment(payment);
        allocation.setCustomer(sale.getCustomer());
        allocation.setTargetType(CustomerCreditAllocation.TargetType.SALE);
        allocation.setSale(sale);
        allocation.setAmount(normalized);
        allocation.setNote(notes);
        allocationRepository.save(allocation);
    }

    @Transactional(readOnly = true)
    public CreditCollectionDtos.LedgerResponse getLedger(Long customerId) {
        synchronizeCustomerState(customerId);
        Customer customer = requireCustomer(customerId);
        List<CreditCollectionDtos.LedgerEntry> entries = new ArrayList<>();

        List<CustomerCreditOpeningBalance> openings = openingBalanceRepository
                .findByCustomerIdAndRemainingAmountGreaterThanOrderByCreatedAtAsc(
                        customerId,
                        BigDecimal.ZERO);
        for (CustomerCreditOpeningBalance opening : openings) {
            CreditCollectionDtos.LedgerEntry entry = new CreditCollectionDtos.LedgerEntry();
            entry.setEntryType("OPENING_BALANCE");
            entry.setTargetType("OPENING_BALANCE");
            entry.setOpeningBalanceId(opening.getId());
            entry.setAmount(scale(opening.getRemainingAmount()));
            entry.setNote(opening.getNote());
            entry.setCreatedAt(opening.getCreatedAt() == null ? null : opening.getCreatedAt().toString());
            entries.add(entry);
        }

        List<Sale> creditSales = saleRepository.findByCustomerIdAndStatusOrderByCreditDueAtAscCreatedAtAsc(
                customerId,
                "CREDIT");
        for (Sale sale : creditSales) {
            BigDecimal remaining = scale(sale.getGrandTotal().subtract(sale.getPaidAmount()));
            if (remaining.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            CreditCollectionDtos.LedgerEntry entry = new CreditCollectionDtos.LedgerEntry();
            entry.setEntryType("CREDIT_SALE");
            entry.setTargetType("SALE");
            entry.setSaleId(sale.getId());
            entry.setInvoiceNumber(sale.getSaleNumber() != null ? sale.getSaleNumber() : "SALE-" + sale.getId());
            entry.setAmount(remaining);
            entry.setCreatedAt(sale.getCreatedAt() == null ? null : sale.getCreatedAt().toString());
            entry.setNote(sale.getNote());
            entry.setAgingDays(ageDays(sale.getCreditDueAt() != null ? sale.getCreditDueAt() : sale.getCreatedAt()));
            entries.add(entry);
        }

        List<CustomerCreditAllocation> allocations = allocationRepository
                .findByCustomerIdOrderByCreatedAtDesc(customerId);
        for (CustomerCreditAllocation allocation : allocations) {
            CreditCollectionDtos.LedgerEntry entry = new CreditCollectionDtos.LedgerEntry();
            entry.setEntryType("COLLECTION");
            entry.setTargetType(allocation.getTargetType().name());
            entry.setSaleId(allocation.getSale() == null ? null : allocation.getSale().getId());
            entry.setInvoiceNumber(
                    allocation.getSale() == null
                            ? null
                            : (allocation.getSale().getSaleNumber() != null
                                    ? allocation.getSale().getSaleNumber()
                                    : "SALE-" + allocation.getSale().getId()));
            entry.setOpeningBalanceId(
                    allocation.getOpeningBalance() == null ? null : allocation.getOpeningBalance().getId());
            entry.setPaymentId(allocation.getPayment() == null ? null : allocation.getPayment().getId());
            entry.setAmount(scale(allocation.getAmount().negate()));
            entry.setNote(allocation.getNote());
            entry.setCreatedAt(allocation.getCreatedAt() == null ? null : allocation.getCreatedAt().toString());
            entries.add(entry);
        }

        List<Payment> refundPayments = paymentRepository.findByCustomerId(customerId).stream()
                .filter(payment -> payment.getAmount() != null && payment.getAmount().compareTo(BigDecimal.ZERO) < 0)
                .filter(payment -> payment.getSale() != null)
                .toList();
        for (Payment payment : refundPayments) {
            CreditCollectionDtos.LedgerEntry entry = new CreditCollectionDtos.LedgerEntry();
            entry.setEntryType("SALE_RETURN");
            entry.setTargetType("SALE");
            entry.setSaleId(payment.getSale().getId());
            entry.setInvoiceNumber(
                    payment.getSale().getSaleNumber() != null
                            ? payment.getSale().getSaleNumber()
                            : "SALE-" + payment.getSale().getId());
            entry.setPaymentId(payment.getId());
            entry.setAmount(scale(payment.getAmount()));
            entry.setNote(payment.getNotes());
            entry.setCreatedAt(payment.getCreatedAt() == null ? null : payment.getCreatedAt().toString());
            entries.add(entry);
        }

        entries.sort(Comparator
                .comparing(CreditCollectionDtos.LedgerEntry::getCreatedAt, Comparator.nullsLast(String::compareTo))
                .reversed());

        CreditCollectionDtos.LedgerResponse response = new CreditCollectionDtos.LedgerResponse();
        response.setCustomerId(customer.getId());
        response.setCustomerName(customer.getNameEn());
        response.setCreditBalance(
                scale(customer.getCreditBalance() == null ? BigDecimal.ZERO : customer.getCreditBalance()));
        response.setCreditLimit(customer.getCreditLimit() == null ? BigDecimal.ZERO : customer.getCreditLimit());
        response.setCreditHold(customer.isCreditHold());
        response.setEntries(entries);
        return response;
    }

    @Transactional
    public void synchronizeCustomerState(Long customerId) {
        Customer customer = requireCustomer(customerId);
        List<Sale> sales = saleRepository.findByCustomerId(customerId);
        boolean salesChanged = false;
        for (Sale sale : sales) {
            if (!"CREDIT".equalsIgnoreCase(sale.getStatus())) {
                continue;
            }
            BigDecimal paid = scale(sale.getPaidAmount() == null ? BigDecimal.ZERO : sale.getPaidAmount());
            BigDecimal total = scale(sale.getGrandTotal() == null ? BigDecimal.ZERO : sale.getGrandTotal());
            if (paid.compareTo(total) >= 0) {
                sale.setStatus("PAID");
                saleRepository.save(sale);
                salesChanged = true;
            }
        }
        if (salesChanged) {
            customer = requireCustomer(customerId);
        }
        recalculateCustomerCreditBalance(customer);
    }

    @Transactional
    public void synchronizeAllCustomerStates() {
        customerRepository.findAll().forEach(customer -> synchronizeCustomerState(customer.getId()));
    }

    private CreditCollectionDtos.PreviewResponse buildPreview(Customer customer, BigDecimal requestedAmount) {
        BigDecimal amountLeft = requestedAmount;
        BigDecimal outstandingBefore = outstandingOfCustomer(customer.getId());
        List<CreditCollectionDtos.AllocationRow> rows = new ArrayList<>();

        List<CustomerCreditOpeningBalance> openings = openingBalanceRepository
                .findByCustomerIdAndRemainingAmountGreaterThanOrderByCreatedAtAsc(
                        customer.getId(),
                        BigDecimal.ZERO);
        for (CustomerCreditOpeningBalance opening : openings) {
            if (amountLeft.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal outstanding = scale(opening.getRemainingAmount());
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal allocated = amountLeft.min(outstanding);
            amountLeft = scale(amountLeft.subtract(allocated));
            rows.add(toOpeningAllocationRow(opening, outstanding, allocated));
        }

        List<Sale> creditSales = saleRepository.findByCustomerIdAndStatusOrderByCreditDueAtAscCreatedAtAsc(
                customer.getId(),
                "CREDIT");
        for (Sale sale : creditSales) {
            if (amountLeft.compareTo(BigDecimal.ZERO) <= 0) {
                break;
            }
            BigDecimal outstanding = scale(sale.getGrandTotal().subtract(sale.getPaidAmount()));
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            BigDecimal allocated = amountLeft.min(outstanding);
            amountLeft = scale(amountLeft.subtract(allocated));
            rows.add(toSaleAllocationRow(sale, outstanding, allocated));
        }

        BigDecimal allocatable = rows.stream()
                .map(CreditCollectionDtos.AllocationRow::getAllocatedAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal outstandingAfter = scale(outstandingBefore.subtract(allocatable));

        CreditCollectionDtos.PreviewResponse preview = new CreditCollectionDtos.PreviewResponse();
        preview.setCustomerId(customer.getId());
        preview.setCustomerName(customer.getNameEn());
        preview.setAmountRequested(scale(requestedAmount));
        preview.setAmountAllocatable(scale(allocatable));
        preview.setAmountUnallocated(scale(requestedAmount.subtract(allocatable)));
        preview.setOutstandingBefore(scale(outstandingBefore));
        preview.setOutstandingAfter(
                outstandingAfter.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : outstandingAfter);
        preview.setValid(scale(requestedAmount).compareTo(scale(allocatable)) <= 0);
        preview.setMessage(preview.isValid() ? "OK" : "Amount exceeds outstanding credit balance");
        preview.setAllocations(rows);
        return preview;
    }

    private void applyAllocations(
            Customer customer,
            Payment payment,
            List<CreditCollectionDtos.AllocationRow> rows,
            String notes) {
        for (CreditCollectionDtos.AllocationRow row : rows) {
            if (row.getAllocatedAmount() == null || row.getAllocatedAmount().compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }

            CustomerCreditAllocation allocation = new CustomerCreditAllocation();
            allocation.setPayment(payment);
            allocation.setCustomer(customer);
            allocation.setAmount(scale(row.getAllocatedAmount()));
            allocation.setNote(notes);

            if ("OPENING_BALANCE".equalsIgnoreCase(row.getTargetType())) {
                CustomerCreditOpeningBalance opening = openingBalanceRepository.findById(row.getOpeningBalanceId())
                        .orElseThrow(() -> new ApiException("Opening balance target not found"));
                BigDecimal next = scale(opening.getRemainingAmount().subtract(row.getAllocatedAmount()));
                opening.setRemainingAmount(next.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : next);
                openingBalanceRepository.save(opening);
                allocation.setTargetType(CustomerCreditAllocation.TargetType.OPENING_BALANCE);
                allocation.setOpeningBalance(opening);
            } else {
                Sale sale = saleRepository.findById(row.getSaleId())
                        .orElseThrow(() -> new ApiException("Sale target not found"));
                sale.setPaidAmount(scale(sale.getPaidAmount().add(row.getAllocatedAmount())));
                if (sale.getPaidAmount().compareTo(sale.getGrandTotal()) >= 0) {
                    sale.setStatus("PAID");
                } else {
                    sale.setStatus("CREDIT");
                }
                saleRepository.save(sale);
                allocation.setTargetType(CustomerCreditAllocation.TargetType.SALE);
                allocation.setSale(sale);
            }

            allocationRepository.save(allocation);
        }
    }

    private CreditCollectionDtos.AllocationRow toOpeningAllocationRow(
            CustomerCreditOpeningBalance opening,
            BigDecimal outstanding,
            BigDecimal allocated) {
        CreditCollectionDtos.AllocationRow row = new CreditCollectionDtos.AllocationRow();
        row.setTargetType("OPENING_BALANCE");
        row.setOpeningBalanceId(opening.getId());
        row.setOutstandingBefore(scale(outstanding));
        row.setAllocatedAmount(scale(allocated));
        row.setOutstandingAfter(scale(outstanding.subtract(allocated)));
        row.setDueAt(opening.getCreatedAt() == null ? null : opening.getCreatedAt().toString());
        row.setAgeDays(ageDays(opening.getCreatedAt()));
        return row;
    }

    private CreditCollectionDtos.AllocationRow toSaleAllocationRow(
            Sale sale,
            BigDecimal outstanding,
            BigDecimal allocated) {
        CreditCollectionDtos.AllocationRow row = new CreditCollectionDtos.AllocationRow();
        row.setTargetType("SALE");
        row.setSaleId(sale.getId());
        row.setInvoiceNumber(sale.getSaleNumber() != null ? sale.getSaleNumber() : "SALE-" + sale.getId());
        row.setDueAt(sale.getCreditDueAt() == null ? null : sale.getCreditDueAt().toString());
        row.setAgeDays(ageDays(sale.getCreditDueAt() != null ? sale.getCreditDueAt() : sale.getCreatedAt()));
        row.setOutstandingBefore(scale(outstanding));
        row.setAllocatedAmount(scale(allocated));
        row.setOutstandingAfter(scale(outstanding.subtract(allocated)));
        return row;
    }

    private CreditCollectionDtos.CollectResponse buildCollectResponse(Customer customer, Payment payment) {
        List<CreditCollectionDtos.AllocationRow> rows = allocationRepository
                .findByCustomerIdOrderByCreatedAtDesc(customer.getId())
                .stream()
                .filter(allocation -> allocation.getPayment() != null
                        && allocation.getPayment().getId().equals(payment.getId()))
                .map(allocation -> {
                    CreditCollectionDtos.AllocationRow row = new CreditCollectionDtos.AllocationRow();
                    row.setTargetType(allocation.getTargetType().name());
                    row.setAllocatedAmount(scale(allocation.getAmount()));
                    if (allocation.getSale() != null) {
                        Sale sale = allocation.getSale();
                        BigDecimal outstandingAfter = scale(sale.getGrandTotal().subtract(sale.getPaidAmount()));
                        row.setSaleId(sale.getId());
                        row.setInvoiceNumber(
                                sale.getSaleNumber() != null ? sale.getSaleNumber() : "SALE-" + sale.getId());
                        row.setDueAt(sale.getCreditDueAt() == null ? null : sale.getCreditDueAt().toString());
                        row.setOutstandingAfter(
                                outstandingAfter.compareTo(BigDecimal.ZERO) < 0 ? BigDecimal.ZERO : outstandingAfter);
                    }
                    if (allocation.getOpeningBalance() != null) {
                        CustomerCreditOpeningBalance opening = allocation.getOpeningBalance();
                        row.setOpeningBalanceId(opening.getId());
                        row.setOutstandingAfter(scale(opening.getRemainingAmount()));
                    }
                    return row;
                })
                .toList();

        CreditCollectionDtos.CollectResponse response = new CreditCollectionDtos.CollectResponse();
        response.setCustomerId(customer.getId());
        response.setPaymentId(payment.getId());
        response.setReferenceNumber(payment.getReferenceNumber());
        response.setAmountCollected(scale(payment.getAmount()));
        response.setOutstandingAfter(
                scale(customer.getCreditBalance() == null ? BigDecimal.ZERO : customer.getCreditBalance()));
        response.setAllocations(rows);
        return response;
    }

    private void recalculateCustomerCreditBalance(Customer customer) {
        BigDecimal totalCreditSales = saleRepository.findByCustomerIdAndStatusOrderByCreditDueAtAscCreatedAtAsc(
                customer.getId(),
                "CREDIT")
                .stream()
                .map(sale -> scale(sale.getGrandTotal().subtract(sale.getPaidAmount())))
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal openingRemaining = openingBalanceRepository
                .findByCustomerIdAndRemainingAmountGreaterThanOrderByCreatedAtAsc(customer.getId(), BigDecimal.ZERO)
                .stream()
                .map(CustomerCreditOpeningBalance::getRemainingAmount)
                .map(this::scale)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal next = scale(totalCreditSales.add(openingRemaining));
        customer.setCreditBalance(next);
        customerRepository.save(customer);
        creditAccountRepository.findByCustomerId(customer.getId()).ifPresent(account -> {
            account.setBalance(next);
            account.setCreditLimit(customer.getCreditLimit());
            creditAccountRepository.save(account);
        });
    }

    private BigDecimal outstandingOfCustomer(Long customerId) {
        BigDecimal totalCreditSales = saleRepository
                .findByCustomerIdAndStatusOrderByCreditDueAtAscCreatedAtAsc(customerId, "CREDIT")
                .stream()
                .map(sale -> scale(sale.getGrandTotal().subtract(sale.getPaidAmount())))
                .filter(value -> value.compareTo(BigDecimal.ZERO) > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal openingRemaining = openingBalanceRepository
                .findByCustomerIdAndRemainingAmountGreaterThanOrderByCreatedAtAsc(customerId, BigDecimal.ZERO)
                .stream()
                .map(CustomerCreditOpeningBalance::getRemainingAmount)
                .map(this::scale)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        return scale(totalCreditSales.add(openingRemaining));
    }

    private Payment.PaymentMethod resolvePaymentMethod(String paymentMethod) {
        String raw = paymentMethod == null ? "" : paymentMethod.trim().toUpperCase(Locale.ROOT);
        return switch (raw) {
            case "CASH" -> Payment.PaymentMethod.CASH;
            case "CARD", "CREDIT_CARD" -> Payment.PaymentMethod.CREDIT_CARD;
            case "DEBIT_CARD" -> Payment.PaymentMethod.DEBIT_CARD;
            case "KHQR", "QR", "MOBILE_WALLET" -> Payment.PaymentMethod.MOBILE_WALLET;
            case "TRANSFER", "BANK_TRANSFER" -> Payment.PaymentMethod.BANK_TRANSFER;
            default -> throw new ApiException("Unsupported payment method: " + paymentMethod);
        };
    }

    private String resolveLegacyMethod(Payment.PaymentMethod method) {
        return switch (method) {
            case CASH -> "CASH";
            case CREDIT_CARD, DEBIT_CARD -> "CREDIT_CARD";
            case MOBILE_WALLET -> "MOBILE_WALLET";
            case BANK_TRANSFER -> "BANK_TRANSFER";
            default -> throw new ApiException("Unsupported payment method: " + method);
        };
    }

    private String buildReference(Long customerId, String idempotencyKey) {
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            return "COLLECT-" + customerId + "-" + idempotencyKey.trim();
        }
        return "COLLECT-" + customerId + "-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
    }

    private List<CreditCollectionDtos.AllocationRow> buildManualAllocations(
            Customer customer,
            BigDecimal requestedAmount,
            List<CreditCollectionDtos.AllocationInput> inputs) {
        Map<String, BigDecimal> outstandingByTarget = new HashMap<>();
        Map<Long, Sale> saleById = new HashMap<>();
        Map<Long, CustomerCreditOpeningBalance> openingById = new HashMap<>();

        List<CustomerCreditOpeningBalance> openings = openingBalanceRepository
                .findByCustomerIdAndRemainingAmountGreaterThanOrderByCreatedAtAsc(
                        customer.getId(),
                        BigDecimal.ZERO);
        for (CustomerCreditOpeningBalance opening : openings) {
            BigDecimal outstanding = scale(opening.getRemainingAmount());
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String key = "OPENING_BALANCE:" + opening.getId();
            outstandingByTarget.put(key, outstanding);
            openingById.put(opening.getId(), opening);
        }

        List<Sale> sales = saleRepository.findByCustomerIdAndStatusOrderByCreditDueAtAscCreatedAtAsc(
                customer.getId(),
                "CREDIT");
        for (Sale sale : sales) {
            BigDecimal outstanding = scale(sale.getGrandTotal().subtract(sale.getPaidAmount()));
            if (outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                continue;
            }
            String key = "SALE:" + sale.getId();
            outstandingByTarget.put(key, outstanding);
            saleById.put(sale.getId(), sale);
        }

        Set<String> seen = new HashSet<>();
        List<CreditCollectionDtos.AllocationRow> rows = new ArrayList<>();
        BigDecimal allocatedTotal = BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);

        for (CreditCollectionDtos.AllocationInput input : inputs) {
            if (input == null) {
                continue;
            }
            BigDecimal allocation = normalizeAmount(input.getAllocatedAmount());
            if (allocation.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Allocated amount must be greater than zero");
            }
            String targetType = input.getTargetType() == null ? ""
                    : input.getTargetType().trim().toUpperCase(Locale.ROOT);
            String key;
            if ("SALE".equals(targetType)) {
                if (input.getSaleId() == null) {
                    throw new ApiException("saleId is required for SALE allocation");
                }
                key = "SALE:" + input.getSaleId();
            } else if ("OPENING_BALANCE".equals(targetType)) {
                if (input.getOpeningBalanceId() == null) {
                    throw new ApiException("openingBalanceId is required for OPENING_BALANCE allocation");
                }
                key = "OPENING_BALANCE:" + input.getOpeningBalanceId();
            } else {
                throw new ApiException("Unsupported targetType: " + input.getTargetType());
            }

            if (!seen.add(key)) {
                throw new ApiException("Duplicate allocation target: " + key);
            }
            BigDecimal outstanding = outstandingByTarget.get(key);
            if (outstanding == null || outstanding.compareTo(BigDecimal.ZERO) <= 0) {
                throw new ApiException("Allocation target not found or already paid: " + key);
            }
            if (allocation.compareTo(outstanding) > 0) {
                throw new ApiException("Allocation exceeds outstanding amount for target: " + key);
            }

            CreditCollectionDtos.AllocationRow row = new CreditCollectionDtos.AllocationRow();
            row.setTargetType(targetType);
            row.setOutstandingBefore(scale(outstanding));
            row.setAllocatedAmount(scale(allocation));
            row.setOutstandingAfter(scale(outstanding.subtract(allocation)));

            if ("SALE".equals(targetType)) {
                Sale sale = saleById.get(input.getSaleId());
                if (sale == null
                        || !customer.getId().equals(sale.getCustomer() == null ? null : sale.getCustomer().getId())) {
                    throw new ApiException("Sale target does not belong to customer");
                }
                row.setSaleId(sale.getId());
                row.setInvoiceNumber(sale.getSaleNumber() != null ? sale.getSaleNumber() : "SALE-" + sale.getId());
                row.setDueAt(sale.getCreditDueAt() == null ? null : sale.getCreditDueAt().toString());
                row.setAgeDays(ageDays(sale.getCreditDueAt() != null ? sale.getCreditDueAt() : sale.getCreatedAt()));
            } else {
                CustomerCreditOpeningBalance opening = openingById.get(input.getOpeningBalanceId());
                if (opening == null || !customer.getId()
                        .equals(opening.getCustomer() == null ? null : opening.getCustomer().getId())) {
                    throw new ApiException("Opening balance target does not belong to customer");
                }
                row.setOpeningBalanceId(opening.getId());
                row.setDueAt(opening.getCreatedAt() == null ? null : opening.getCreatedAt().toString());
                row.setAgeDays(ageDays(opening.getCreatedAt()));
            }

            rows.add(row);
            allocatedTotal = scale(allocatedTotal.add(allocation));
        }

        if (rows.isEmpty()) {
            throw new ApiException("At least one manual allocation is required");
        }
        if (allocatedTotal.compareTo(scale(requestedAmount)) != 0) {
            throw new ApiException("Manual allocation total must equal requested amount");
        }
        return rows;
    }

    private void enforceStrategy(String strategy, boolean manualMode) {
        if (manualMode) {
            return;
        }
        String normalized = strategy == null ? "" : strategy.trim().toUpperCase(Locale.ROOT);
        if (!"FIFO".equals(normalized)) {
            throw new ApiException("Only FIFO strategy is supported");
        }
    }

    private Customer requireCustomer(Long customerId) {
        return customerRepository.findById(customerId).orElseThrow(() -> new ApiException("Customer not found"));
    }

    private BigDecimal normalizeAmount(BigDecimal amount) {
        if (amount == null) {
            return BigDecimal.ZERO;
        }
        return amount.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private BigDecimal scale(BigDecimal value) {
        if (value == null) {
            return BigDecimal.ZERO.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        }
        return value.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    private Integer ageDays(Instant point) {
        if (point == null) {
            return null;
        }
        return Math.toIntExact(
                ChronoUnit.DAYS.between(
                        point.atZone(ZoneId.systemDefault()).toLocalDate(),
                        Instant.now().atZone(ZoneId.systemDefault()).toLocalDate()));
    }
    private static final int MONEY_SCALE = 2;
}
