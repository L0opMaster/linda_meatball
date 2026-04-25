package com.kaknnea.pos.controller;

import com.kaknnea.pos.dto.ReportDtos;
import com.kaknnea.pos.service.ReportService;
import com.kaknnea.pos.service.InventoryService;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.Map;

@RestController
@RequestMapping("/api/reports")
public class ReportController {
    private final ReportService reportService;
    private final InventoryService inventoryService;

    public ReportController(ReportService reportService, InventoryService inventoryService) {
        this.reportService = reportService;
        this.inventoryService = inventoryService;
    }

    @GetMapping("/daily")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public ReportDtos.DailyReportResponse daily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.dailyReport(date);
    }

    @GetMapping(value = "/daily-z-report.pdf", produces = "application/pdf")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public byte[] dailyZReportPdf(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        LocalDate reportDate = date != null ? date : LocalDate.now();
        return reportService.dailyZReportPdf(reportDate);
    }

    @GetMapping("/sales")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public ReportDtos.SalesReportResponse salesReport(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long customerId,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) String payment) {
        return reportService.salesReport(from, to, customerId, cashierId, payment);
    }

    @GetMapping("/sales-summary")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public ReportDtos.SalesSummaryReportResponse salesSummary(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) Long storeId,
            @RequestParam(required = false) Long cashierId,
            @RequestParam(required = false) String payment) {
        return reportService.salesSummaryReport(from, to, storeId, cashierId, payment);
    }

    @GetMapping(value = "/daily.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public String dailyCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ReportDtos.DailyReportResponse report = reportService.dailyReport(date);
        StringBuilder sb = new StringBuilder();
        sb.append("date,gross_sales,net_sales,sales_count\n");
        sb.append(report.getSummary().getDate()).append(",")
                .append(report.getSummary().getGrossSales()).append(",")
                .append(report.getSummary().getNetSales()).append(",")
                .append(report.getSummary().getSalesCount()).append("\n");
        return sb.toString();
    }

    @GetMapping(value = "/top-products.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public String topProductsCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ReportDtos.DailyReportResponse report = reportService.dailyReport(date);
        StringBuilder sb = new StringBuilder();
        sb.append("product_id,name_en,name_km,quantity,total\n");
        for (ReportDtos.TopProduct p : report.getTopProducts()) {
            sb.append(p.getProductId()).append(",")
                    .append(p.getNameEn()).append(",")
                    .append(p.getNameKm()).append(",")
                    .append(p.getQuantity()).append(",")
                    .append(p.getTotal()).append("\n");
        }
        return sb.toString();
    }

    @GetMapping(value = "/cashiers.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public String cashiersCsv(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        ReportDtos.DailyReportResponse report = reportService.dailyReport(date);
        StringBuilder sb = new StringBuilder();
        sb.append("cashier_id,cashier_name,sales_total,sales_count\n");
        for (ReportDtos.CashierPerformance c : report.getCashiers()) {
            sb.append(c.getCashierId()).append(",")
                    .append(c.getCashierName()).append(",")
                    .append(c.getSalesTotal()).append(",")
                    .append(c.getSalesCount()).append("\n");
        }
        return sb.toString();
    }

    @GetMapping("/tax")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public ReportDtos.TaxReport tax(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.taxReport(date);
    }

    @GetMapping("/payables")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public java.util.List<ReportDtos.PayableSummary> payables() {
        return reportService.payables();
    }

    @GetMapping("/monthly")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public java.util.List<ReportDtos.MonthlySales> monthly(@RequestParam int year) {
        return reportService.monthlySales(year);
    }

    @GetMapping("/top-products")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public java.util.List<ReportDtos.TopProduct> topProducts(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.dailyReport(date).getTopProducts();
    }

    @GetMapping("/cashier-performance")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public java.util.List<ReportDtos.CashierPerformance> cashierPerformance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return reportService.dailyReport(date).getCashiers();
    }

    @GetMapping("/inventory-valuation")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public com.kaknnea.pos.dto.InventoryDtos.StockValuationResponse inventoryValuation(
            @RequestParam(required = false) Long storeId) {
        return inventoryService.valuation(storeId);
    }

    @GetMapping("/category-performance")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public java.util.List<ReportDtos.CategoryPerformance> categoryPerformance(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.categoryPerformance(from, to);
    }

    @GetMapping("/stock-movements")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW')")
    public java.util.List<ReportDtos.StockMovementRow> stockMovements(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return reportService.stockMovements(from, to);
    }

    @GetMapping(value = "/stock-movements.csv", produces = "text/csv")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW')")
    public String stockMovementsCsv(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        var movements = reportService.stockMovements(from, to);
        StringBuilder sb = new StringBuilder();
        sb.append(
                "date,product_id,product_name_en,product_name_km,movement_type,quantity,balance,reference,created_at\n");
        for (ReportDtos.StockMovementRow m : movements) {
            sb.append(m.getCreatedAt()).append(",")
                    .append(m.getProductId()).append(",")
                    .append("\"").append(m.getProductNameEn().replace("\"", "\"\"")).append("\",")
                    .append("\"").append(m.getProductNameEn().replace("\"", "\"\"")).append("\",")
                    .append(m.getMovementType()).append(",")
                    .append(m.getQuantity()).append(",")
                    .append("0").append(",")
                    .append("\"").append("").append("\",")
                    .append(m.getCreatedAt()).append("\n");
        }
        return sb.toString();
    }

    @GetMapping("/shift-summary")
    @PreAuthorize("hasAuthority('PERM_REPORTS_VIEW') or hasRole('OWNER') or hasRole('MANAGER')")
    public Map<String, Object> shiftSummary(@RequestParam Long shiftId) {
        return reportService.getShiftSummary(shiftId);
    }
}
