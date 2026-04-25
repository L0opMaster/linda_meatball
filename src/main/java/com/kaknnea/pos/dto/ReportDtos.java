package com.kaknnea.pos.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

public class ReportDtos {
    @Data
    public static class DailySalesSummary {
        private String date;
        private BigDecimal grossSales;
        private BigDecimal netSales;
        private long salesCount;
    }

    @Data
    public static class TopProduct {
        private Long productId;
        private String nameEn;
        private String nameKm;
        private BigDecimal quantity;
        private BigDecimal total;
    }

    @Data
    public static class CashierPerformance {
        private Long cashierId;
        private String cashierName;
        private BigDecimal salesTotal;
        private long salesCount;
    }

    @Data
    public static class PaymentBreakdown {
        private String method;
        private BigDecimal total;
        private long count;
    }

    @Data
    public static class DailyReportResponse {
        private DailySalesSummary summary;
        private List<TopProduct> topProducts;
        private List<CashierPerformance> cashiers;
        private List<PaymentBreakdown> payments;
        private List<ShiftSummary> shifts;
    }

    @Data
    public static class PayableSummary {
        private Long supplierId;
        private BigDecimal totalPayable;
    }

    @Data
    public static class TaxReport {
        private String date;
        private BigDecimal taxCollected;
    }

    @Data
    public static class MonthlySales {
        private String month;
        private BigDecimal total;
        private long count;
    }

    @Data
    public static class CategoryPerformance {
        private Long categoryId;
        private String categoryNameEn;
        private String categoryNameKm;
        private BigDecimal total;
        private BigDecimal quantity;
    }

    @Data
    public static class StockMovementRow {
        private Long productId;
        private String productNameEn;
        private String movementType;
        private BigDecimal quantity;
        private Long storeId;
        private String createdAt;
    }

    @Data
    public static class DailyZReport {
        private String date;
        private BigDecimal totalSales;
        private Integer transactionCount;
        private Map<String, BigDecimal> paymentMethodBreakdown;
        private List<ShiftSummary> shifts;
        private List<TopProduct> topProducts;
    }

    @Data
    public static class ShiftSummary {
        private Long shiftId;
        private String openedBy;
        private Instant openedAt;
        private Instant closedAt;
        private BigDecimal openingCash;
        private BigDecimal closingCash;
        private BigDecimal expectedCash;
        private BigDecimal variance;
        private String status;
        private BigDecimal salesTotal;
        private Long salesCount;
    }

    @Data
    public static class SalesReportSummary {
        private LocalDate fromDate;
        private LocalDate toDate;
        private BigDecimal totalGrossSales;
        private BigDecimal totalNetSales;
        private BigDecimal totalTax;
        private BigDecimal totalDiscount;
        private long totalSalesCount;
        private long totalItemsSold;
        private BigDecimal averageSaleAmount;
    }

    @Data
    public static class SalesDetail {
        private Long saleId;
        private String saleNumber;
        private LocalDate saleDate;
        private Long customerId;
        private String customerName;
        private Long cashierId;
        private String cashierName;
        private BigDecimal grossAmount;
        private BigDecimal netAmount;
        private BigDecimal taxAmount;
        private BigDecimal discountAmount;
        private String paymentMethod;
        private List<SaleItemDetail> items;
    }

    @Data
    public static class SaleItemDetail {
        private Long productId;
        private String productNameEn;
        private String productNameKm;
        private String sku;
        private BigDecimal quantity;
        private BigDecimal unitPrice;
        private BigDecimal totalPrice;
        private BigDecimal discount;
    }

    @Data
    public static class SalesReportResponse {
        private SalesReportSummary summary;
        private List<SalesDetail> sales;
        private List<PaymentBreakdown> paymentBreakdown;
        private List<TopProduct> topProducts;
        private List<SalesSummaryFilterOption> customers;
        private List<SalesSummaryFilterOption> cashiers;
        private List<SalesSummaryFilterOption> paymentOptions;
    }

    @Data
    public static class SalesSummaryFilterOption {
        private String value;
        private String label;
    }

    @Data
    public static class SalesSummaryRow {
        private LocalDate date;
        private long orders;
        private BigDecimal quantity;
        private BigDecimal gross;
        private BigDecimal discount;
        private BigDecimal tax;
        private BigDecimal net;
    }

    @Data
    public static class SalesSummaryTotals {
        private BigDecimal totalSales;
        private BigDecimal gross;
        private BigDecimal discount;
        private BigDecimal tax;
        private BigDecimal net;
        private BigDecimal quantity;
        private long orders;
    }

    @Data
    public static class SalesSummaryReportResponse {
        private LocalDate fromDate;
        private LocalDate toDate;
        private Long selectedStoreId;
        private Long selectedCashierId;
        private String selectedPayment;
        private List<SalesSummaryFilterOption> stores;
        private List<SalesSummaryFilterOption> cashiers;
        private List<SalesSummaryFilterOption> payments;
        private List<SalesSummaryRow> rows;
        private SalesSummaryTotals totals;
    }

    // EOD Owner Report DTOs
    @Data
    public static class EodSummary {
        private LocalDate eodDate;
        private String status;
        private BigDecimal netSalesToday;
        private BigDecimal cashCollectedToday;
        private BigDecimal newCreditToday;
        private BigDecimal totalArBalance;
        private BigDecimal overdueGt30Days;
        private Integer totalSalesCount;
        private Integer totalPaymentsCount;
        private LocalDateTime processedAt;
    }

    @Data
    public static class EodInvoiceRow {
        private Long saleId;
        private String invoiceNo;
        private LocalDate invoiceDate;
        private String customerName;
        private BigDecimal totalSale;
        private BigDecimal paidAmount;
        private BigDecimal balance;
        private Integer daysOutstanding;
        private String agingBucket;
        private String paymentStatus;
    }

    @Data
    public static class EodCollectionSummary {
        private String paymentMethod;
        private BigDecimal totalAmount;
        private Integer transactionCount;
    }

    @Data
    public static class EodAgingSummary {
        private String agingBucket;
        private BigDecimal totalBalance;
        private Integer invoiceCount;
    }

    @Data
    public static class EodCustomerCredit {
        private Long customerId;
        private String customerName;
        private BigDecimal creditLimit;
        private BigDecimal currentBalance;
        private String status;
    }

    @Data
    public static class EodReportResponse {
        private EodSummary summary;
        private List<EodInvoiceRow> invoices;
        private List<EodCollectionSummary> collectionSummary;
        private List<EodAgingSummary> agingSummary;
        private List<EodCustomerCredit> customerCredits;
    }

    @Data
    public static class EodRunResponse {
        private Long snapshotId;
        private String status;
        private String message;
    }
}
