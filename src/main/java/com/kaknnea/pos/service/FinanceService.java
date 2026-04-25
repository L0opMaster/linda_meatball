package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.Customer;
import com.kaknnea.pos.domain.Sale;
import com.kaknnea.pos.domain.SupplierPayment;
import com.kaknnea.pos.domain.PurchaseReturn;
import com.kaknnea.pos.domain.SupplierInvoice;
import com.kaknnea.pos.dto.FinanceDtos;
import com.kaknnea.pos.repository.CustomerRepository;
import com.kaknnea.pos.repository.PurchaseReturnRepository;
import com.kaknnea.pos.repository.SaleRepository;
import com.kaknnea.pos.repository.SupplierInvoiceRepository;
import com.kaknnea.pos.repository.SupplierPaymentRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public class FinanceService {
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final SupplierPaymentRepository supplierPaymentRepository;
    private final PurchaseReturnRepository purchaseReturnRepository;
    private final SaleRepository saleRepository;
    private final CustomerRepository customerRepository;
    private final CreditCollectionService creditCollectionService;

    public FinanceService(
            SupplierInvoiceRepository supplierInvoiceRepository,
            SupplierPaymentRepository supplierPaymentRepository,
            PurchaseReturnRepository purchaseReturnRepository,
            SaleRepository saleRepository,
            CustomerRepository customerRepository,
            CreditCollectionService creditCollectionService) {
        this.supplierInvoiceRepository = supplierInvoiceRepository;
        this.supplierPaymentRepository = supplierPaymentRepository;
        this.purchaseReturnRepository = purchaseReturnRepository;
        this.saleRepository = saleRepository;
        this.customerRepository = customerRepository;
        this.creditCollectionService = creditCollectionService;
    }

    public FinanceDtos.PayablesSummaryResponse payablesSummary() {
        List<SupplierInvoice> invoices = supplierInvoiceRepository.findAll().stream()
                .filter(invoice -> invoice.getOutstandingAmount() != null
                        && invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) > 0)
                .toList();

        FinanceDtos.PayablesSummaryResponse response = new FinanceDtos.PayablesSummaryResponse();
        response.setInvoiceCount(invoices.size());
        response.setTotalOutstanding(invoices.stream()
                .map(SupplierInvoice::getOutstandingAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        response.setTotalOpenInvoices(invoices.stream()
                .map(SupplierInvoice::getTotalAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        response.setInvoices(invoices.stream().map(this::toPayableRow).toList());
        response.setAging(buildPayableAging(response.getInvoices()));
        return response;
    }

    public FinanceDtos.ReceivablesSummaryResponse receivablesSummary() {
        creditCollectionService.synchronizeAllCustomerStates();
        List<Sale> sales = saleRepository.findAll().stream()
                .filter(sale -> "CREDIT".equalsIgnoreCase(sale.getStatus()))
                .filter(sale -> sale.getGrandTotal() != null && sale.getPaidAmount() != null)
                .filter(sale -> sale.getGrandTotal().subtract(sale.getPaidAmount()).compareTo(BigDecimal.ZERO) > 0)
                .toList();

        FinanceDtos.ReceivablesSummaryResponse response = new FinanceDtos.ReceivablesSummaryResponse();
        response.setInvoiceCount(sales.size());
        response.setTotalOutstanding(sales.stream()
                .map(sale -> sale.getGrandTotal().subtract(sale.getPaidAmount()))
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        response.setTotalCreditLimit(customerRepository.findAll().stream()
                .map(Customer::getCreditLimit)
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        response.setInvoices(sales.stream().map(this::toReceivableRow).toList());
        response.setAging(buildReceivableAging(response.getInvoices()));
        return response;
    }

    public List<FinanceDtos.ApLedgerEntry> apLedger() {
        List<FinanceDtos.ApLedgerEntry> entries = new ArrayList<>();
        for (SupplierInvoice invoice : supplierInvoiceRepository.findAll()) {
            FinanceDtos.ApLedgerEntry entry = new FinanceDtos.ApLedgerEntry();
            entry.setEntryType("BILL");
            entry.setSupplierId(invoice.getSupplier().getId());
            entry.setSupplierName(invoice.getSupplier().getName());
            entry.setDocumentId(invoice.getId());
            entry.setDocumentNumber(invoice.getInvoiceNumber());
            entry.setOccurredAt(invoice.getCreatedAt());
            entry.setCredit(invoice.getTotalAmount());
            entry.setBalanceImpact(invoice.getTotalAmount());
            entry.setNote("Vendor bill posted");
            entries.add(entry);
        }
        for (SupplierPayment payment : supplierPaymentRepository.findAll()) {
            FinanceDtos.ApLedgerEntry entry = new FinanceDtos.ApLedgerEntry();
            entry.setEntryType("PAYMENT");
            entry.setSupplierId(payment.getSupplierInvoice().getSupplier().getId());
            entry.setSupplierName(payment.getSupplierInvoice().getSupplier().getName());
            entry.setDocumentId(payment.getId());
            entry.setDocumentNumber(payment.getReference() != null && !payment.getReference().isBlank() ? payment.getReference() : "PAY-" + payment.getId());
            entry.setOccurredAt(payment.getPaidAt());
            entry.setDebit(payment.getAmount());
            entry.setBalanceImpact(payment.getAmount().negate());
            entry.setNote("Supplier payment posted");
            entries.add(entry);
        }
        for (PurchaseReturn purchaseReturn : purchaseReturnRepository.findAll()) {
            FinanceDtos.ApLedgerEntry entry = new FinanceDtos.ApLedgerEntry();
            entry.setEntryType("RETURN");
            entry.setSupplierId(purchaseReturn.getSupplier().getId());
            entry.setSupplierName(purchaseReturn.getSupplier().getName());
            entry.setDocumentId(purchaseReturn.getId());
            entry.setDocumentNumber("RET-" + purchaseReturn.getId());
            entry.setOccurredAt(purchaseReturn.getCreatedAt());
            entry.setDebit(purchaseReturn.getTotalAmount());
            entry.setBalanceImpact(purchaseReturn.getTotalAmount().negate());
            entry.setNote("Purchase return reduces payable");
            entries.add(entry);
        }
        return entries.stream()
                .sorted((a, b) -> {
                    Instant right = b.getOccurredAt() != null ? b.getOccurredAt() : Instant.EPOCH;
                    Instant left = a.getOccurredAt() != null ? a.getOccurredAt() : Instant.EPOCH;
                    return right.compareTo(left);
                })
                .toList();
    }

    private FinanceDtos.PayableRow toPayableRow(SupplierInvoice invoice) {
        FinanceDtos.PayableRow row = new FinanceDtos.PayableRow();
        row.setSupplierInvoiceId(invoice.getId());
        row.setInvoiceNumber(invoice.getInvoiceNumber());
        row.setSupplierName(invoice.getSupplier().getName());
        row.setInvoiceDate(invoice.getInvoiceDate() == null ? null : invoice.getInvoiceDate().toString());
        row.setStatus(invoice.getStatus());
        row.setTotalAmount(invoice.getTotalAmount());
        row.setPaidAmount(invoice.getPaidAmount());
        row.setOutstandingAmount(invoice.getOutstandingAmount());
        row.setAgeDays(invoice.getInvoiceDate() == null ? 0 : (int) ChronoUnit.DAYS.between(invoice.getInvoiceDate(), LocalDate.now()));
        return row;
    }

    private FinanceDtos.ReceivableRow toReceivableRow(Sale sale) {
        FinanceDtos.ReceivableRow row = new FinanceDtos.ReceivableRow();
        row.setSaleId(sale.getId());
        row.setInvoiceNumber(sale.getSaleNumber());
        row.setCustomerId(sale.getCustomer() != null ? sale.getCustomer().getId() : null);
        row.setCustomerName(sale.getCustomer() != null ? sale.getCustomer().getNameEn() : "Walk-in");
        row.setCreatedAt(sale.getCreatedAt() == null ? null : sale.getCreatedAt().toString());
        row.setDueAt(sale.getCreditDueAt() == null ? null : sale.getCreditDueAt().toString());
        row.setStatus(sale.getStatus());
        row.setGrandTotal(sale.getGrandTotal());
        row.setPaidAmount(sale.getPaidAmount());
        row.setOutstandingAmount(sale.getGrandTotal().subtract(sale.getPaidAmount()));
        Instant dueAt = sale.getCreditDueAt() != null ? sale.getCreditDueAt() : sale.getCreatedAt();
        LocalDate dueDate = dueAt == null ? LocalDate.now() : LocalDate.ofInstant(dueAt, ZoneId.systemDefault());
        row.setAgeDays((int) ChronoUnit.DAYS.between(dueDate, LocalDate.now()));
        return row;
    }

    private List<FinanceDtos.AgingBucket> buildPayableAging(List<FinanceDtos.PayableRow> rows) {
        return buildAging(rows.stream().map(FinanceAgingRow::fromPayable).toList());
    }

    private List<FinanceDtos.AgingBucket> buildReceivableAging(List<FinanceDtos.ReceivableRow> rows) {
        return buildAging(rows.stream().map(FinanceAgingRow::fromReceivable).toList());
    }

    private List<FinanceDtos.AgingBucket> buildAging(List<FinanceAgingRow> rows) {
        return List.of(
                agingBucket("Current", rows, age -> age <= 0),
                agingBucket("1-30 days", rows, age -> age > 0 && age <= 30),
                agingBucket("31-60 days", rows, age -> age > 30 && age <= 60),
                agingBucket("61+ days", rows, age -> age > 60)
        );
    }

    private FinanceDtos.AgingBucket agingBucket(String name, List<FinanceAgingRow> rows, java.util.function.IntPredicate predicate) {
        FinanceDtos.AgingBucket bucket = new FinanceDtos.AgingBucket();
        bucket.setBucket(name);
        List<FinanceAgingRow> matches = new ArrayList<>(rows.stream().filter(row -> predicate.test(row.ageDays())).toList());
        bucket.setCount(matches.size());
        bucket.setAmount(matches.stream().map(FinanceAgingRow::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
        return bucket;
    }

    private record FinanceAgingRow(int ageDays, BigDecimal amount) {
        static FinanceAgingRow fromPayable(FinanceDtos.PayableRow row) {
            return new FinanceAgingRow(row.getAgeDays(), row.getOutstandingAmount());
        }

        static FinanceAgingRow fromReceivable(FinanceDtos.ReceivableRow row) {
            return new FinanceAgingRow(row.getAgeDays(), row.getOutstandingAmount());
        }
    }
}
