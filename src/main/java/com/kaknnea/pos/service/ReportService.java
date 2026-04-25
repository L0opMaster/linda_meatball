package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.Sale;
import com.kaknnea.pos.domain.Payment;
import com.kaknnea.pos.domain.SaleLine;
import com.kaknnea.pos.domain.Customer;
import com.kaknnea.pos.domain.EodSnapshot;
import com.kaknnea.pos.domain.EodInvoiceSnapshot;
import com.kaknnea.pos.domain.EodCollectionSummary;
import com.kaknnea.pos.domain.EodAgingSummary;
import com.kaknnea.pos.domain.EodCustomerCredit;
import com.kaknnea.pos.repository.EodSnapshotRepository;
import com.kaknnea.pos.dto.ReportDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.BusinessSettingsRepository;
import com.kaknnea.pos.repository.SupplierInvoiceRepository;
import com.kaknnea.pos.repository.PurchaseRepository;
import com.kaknnea.pos.repository.SaleRepository;
import com.kaknnea.pos.repository.ShiftRepository;
import com.kaknnea.pos.repository.StockMovementRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.ZoneId;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.YearMonth;
import java.time.format.DateTimeFormatter;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

@Service
public class ReportService {
        // Include the key invoice statuses so reports show the same invoices visible in the UI
        private static final java.util.Set<String> REPORT_STATUSES = java.util.Set.of("PAID", "CREDIT", "HOLD", "DRAFT");
        private final SaleRepository saleRepository;
        private final PurchaseRepository purchaseRepository;
        private final SupplierInvoiceRepository supplierInvoiceRepository;
        private final StockMovementRepository stockMovementRepository;
        private final ShiftRepository shiftRepository;
        private final BusinessSettingsRepository businessSettingsRepository;
        private final PdfService pdfService;
        private final EodSnapshotRepository eodSnapshotRepository;

        public ReportService(SaleRepository saleRepository, PurchaseRepository purchaseRepository,
                        SupplierInvoiceRepository supplierInvoiceRepository,
                        StockMovementRepository stockMovementRepository, ShiftRepository shiftRepository,
                        BusinessSettingsRepository businessSettingsRepository, PdfService pdfService,
                        EodSnapshotRepository eodSnapshotRepository) {
                this.saleRepository = saleRepository;
                this.purchaseRepository = purchaseRepository;
                this.supplierInvoiceRepository = supplierInvoiceRepository;
                this.stockMovementRepository = stockMovementRepository;
                this.shiftRepository = shiftRepository;
                this.businessSettingsRepository = businessSettingsRepository;
                this.pdfService = pdfService;
                this.eodSnapshotRepository = eodSnapshotRepository;
        }

        public ReportDtos.DailyReportResponse dailyReport(LocalDate date) {
                var start = date.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                List<Sale> sales = filterSalesByRange(start, end);

                BigDecimal gross = sales.stream().map(Sale::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add);
                BigDecimal net = sales.stream().map(Sale::getGrandTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
                long count = sales.size();

                ReportDtos.DailySalesSummary summary = new ReportDtos.DailySalesSummary();
                summary.setDate(date.toString());
                summary.setGrossSales(gross);
                summary.setNetSales(net);
                summary.setSalesCount(count);

                Map<Long, List<SaleLine>> byProduct = sales.stream()
                                .flatMap(s -> s.getLines().stream())
                                .collect(Collectors.groupingBy(sl -> sl.getProduct().getId()));

                List<ReportDtos.TopProduct> topProducts = byProduct.entrySet().stream().map(entry -> {
                        var lineList = entry.getValue();
                        var product = lineList.get(0).getProduct();
                        BigDecimal qty = lineList.stream().map(SaleLine::getQuantity).reduce(BigDecimal.ZERO,
                                        BigDecimal::add);
                        BigDecimal total = lineList.stream().map(SaleLine::getLineTotal).reduce(BigDecimal.ZERO,
                                        BigDecimal::add);
                        ReportDtos.TopProduct tp = new ReportDtos.TopProduct();
                        tp.setProductId(product.getId());
                        tp.setNameEn(product.getNameEn());
                        tp.setNameKm(product.getNameKm());
                        tp.setQuantity(qty);
                        tp.setTotal(total);
                        return tp;
                }).sorted(Comparator.comparing(ReportDtos.TopProduct::getTotal).reversed()).limit(10)
                                .collect(Collectors.toList());

                Map<Long, List<Sale>> byCashier = sales.stream()
                                .filter(s -> s.getCreatedBy() != null)
                                .collect(Collectors.groupingBy(s -> s.getCreatedBy().getId()));

                List<ReportDtos.CashierPerformance> cashiers = byCashier.entrySet().stream().map(entry -> {
                        var list = entry.getValue();
                        var user = list.get(0).getCreatedBy();
                        BigDecimal total = list.stream().map(Sale::getGrandTotal).reduce(BigDecimal.ZERO,
                                        BigDecimal::add);
                        ReportDtos.CashierPerformance perf = new ReportDtos.CashierPerformance();
                        perf.setCashierId(user.getId());
                        perf.setCashierName(user.getFullName());
                        perf.setSalesTotal(total);
                        perf.setSalesCount(list.size());
                        return perf;
                }).sorted(Comparator.comparing(ReportDtos.CashierPerformance::getSalesTotal).reversed())
                                .collect(Collectors.toList());

                ReportDtos.DailyReportResponse resp = new ReportDtos.DailyReportResponse();
                resp.setSummary(summary);
                resp.setTopProducts(topProducts);
                resp.setCashiers(cashiers);
                Map<String, List<Payment>> byMethod = sales.stream()
                                .flatMap(s -> s.getPayments().stream())
                                .collect(Collectors.groupingBy(Payment::getMethod));
                List<ReportDtos.PaymentBreakdown> payments = byMethod.entrySet().stream().map(entry -> {
                        ReportDtos.PaymentBreakdown pb = new ReportDtos.PaymentBreakdown();
                        pb.setMethod(entry.getKey());
                        pb.setTotal(entry.getValue().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO,
                                        BigDecimal::add));
                        pb.setCount(entry.getValue().size());
                        return pb;
                }).collect(Collectors.toList());
                resp.setPayments(payments);
                resp.setShifts(shiftSummariesByRange(start, end));
                return resp;
        }

        public ReportDtos.TaxReport taxReport(LocalDate date) {
                var start = date.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                BigDecimal tax = filterSalesByRange(start, end).stream()
                                .map(Sale::getTaxAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                ReportDtos.TaxReport resp = new ReportDtos.TaxReport();
                resp.setDate(date.toString());
                resp.setTaxCollected(tax);
                return resp;
        }

        public List<ReportDtos.PayableSummary> payables() {
                return supplierInvoiceRepository.findAll().stream()
                                .filter(invoice -> invoice.getOutstandingAmount() != null
                                                && invoice.getOutstandingAmount().compareTo(BigDecimal.ZERO) > 0)
                                .collect(Collectors.groupingBy(p -> p.getSupplier().getId()))
                                .entrySet().stream().map(entry -> {
                                        BigDecimal total = entry.getValue().stream()
                                                        .map(invoice -> invoice.getOutstandingAmount() == null
                                                                        ? BigDecimal.ZERO
                                                                        : invoice.getOutstandingAmount())
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                        ReportDtos.PayableSummary ps = new ReportDtos.PayableSummary();
                                        ps.setSupplierId(entry.getKey());
                                        ps.setTotalPayable(total);
                                        return ps;
                                }).collect(Collectors.toList());
        }

        public List<ReportDtos.MonthlySales> monthlySales(int year) {
                return saleRepository.findAll().stream()
                                .filter(s -> s.getCreatedAt() != null
                                                && s.getCreatedAt().atZone(ZoneId.of("UTC")).getYear() == year)
                                .filter(s -> REPORT_STATUSES.contains(s.getStatus()))
                                .collect(Collectors.groupingBy(
                                                s -> YearMonth.from(s.getCreatedAt().atZone(ZoneId.of("UTC")))))
                                .entrySet().stream().map(entry -> {
                                        BigDecimal total = entry.getValue().stream().map(Sale::getGrandTotal).reduce(
                                                        BigDecimal.ZERO,
                                                        BigDecimal::add);
                                        ReportDtos.MonthlySales ms = new ReportDtos.MonthlySales();
                                        ms.setMonth(entry.getKey().toString());
                                        ms.setTotal(total);
                                        ms.setCount(entry.getValue().size());
                                        return ms;
                                }).sorted(Comparator.comparing(ReportDtos.MonthlySales::getMonth))
                                .collect(Collectors.toList());
        }

        public ReportDtos.SalesSummaryReportResponse salesSummaryReport(
                        LocalDate from,
                        LocalDate to,
                        Long storeId,
                        Long cashierId,
                        String paymentMethod) {
                if (from == null || to == null) {
                        throw new ApiException("From and to dates are required");
                }
                if (to.isBefore(from)) {
                        throw new ApiException("Date To cannot be earlier than Date From");
                }
                var start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                List<Sale> rangeSales = filterSalesByRange(start, end);

                List<Sale> filtered = rangeSales.stream()
                                .filter(sale -> storeId == null
                                                || (sale.getShift() != null && sale.getShift().getStore() != null
                                                                && storeId.equals(sale.getShift().getStore().getId())))
                                .filter(sale -> cashierId == null || (sale.getCreatedBy() != null
                                                && cashierId.equals(sale.getCreatedBy().getId())))
                                .filter(sale -> paymentMethod == null || paymentMethod.isBlank()
                                                || "ALL".equalsIgnoreCase(paymentMethod)
                                                || sale.getPayments().stream()
                                                                .anyMatch(payment -> paymentMethod
                                                                                .equalsIgnoreCase(payment.getMethod())))
                                .toList();

                Map<LocalDate, List<Sale>> byDate = filtered.stream()
                                .collect(Collectors.groupingBy(
                                                sale -> sale.getCreatedAt().atZone(ZoneOffset.UTC).toLocalDate(),
                                                LinkedHashMap::new,
                                                Collectors.toList()));

                List<ReportDtos.SalesSummaryRow> rows = byDate.entrySet().stream()
                                .sorted(Map.Entry.comparingByKey())
                                .map(entry -> {
                                        List<Sale> sales = entry.getValue();
                                        ReportDtos.SalesSummaryRow row = new ReportDtos.SalesSummaryRow();
                                        row.setDate(entry.getKey());
                                        row.setOrders(sales.size());
                                        row.setQuantity(sales.stream()
                                                        .flatMap(sale -> sale.getLines().stream())
                                                        .map(SaleLine::getQuantity)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                                        row.setGross(sales.stream()
                                                        .map(Sale::getSubtotal)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                                        row.setDiscount(sales.stream()
                                                        .map(Sale::getDiscountAmount)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                                        row.setTax(sales.stream()
                                                        .map(Sale::getTaxAmount)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                                        row.setNet(sales.stream()
                                                        .map(Sale::getGrandTotal)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add));
                                        return row;
                                })
                                .toList();

                ReportDtos.SalesSummaryTotals totals = new ReportDtos.SalesSummaryTotals();
                totals.setOrders(filtered.size());
                totals.setQuantity(filtered.stream()
                                .flatMap(sale -> sale.getLines().stream())
                                .map(SaleLine::getQuantity)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                totals.setGross(filtered.stream()
                                .map(Sale::getSubtotal)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                totals.setDiscount(filtered.stream()
                                .map(Sale::getDiscountAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                totals.setTax(filtered.stream()
                                .map(Sale::getTaxAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                totals.setNet(filtered.stream()
                                .map(Sale::getGrandTotal)
                                .reduce(BigDecimal.ZERO, BigDecimal::add));
                totals.setTotalSales(totals.getNet());

                ReportDtos.SalesSummaryReportResponse response = new ReportDtos.SalesSummaryReportResponse();
                response.setFromDate(from);
                response.setToDate(to);
                response.setSelectedStoreId(storeId);
                response.setSelectedCashierId(cashierId);
                response.setSelectedPayment(paymentMethod == null || paymentMethod.isBlank() ? "ALL" : paymentMethod);
                response.setRows(rows);
                response.setTotals(totals);
                response.setStores(rangeSales.stream()
                                .filter(sale -> sale.getShift() != null && sale.getShift().getStore() != null)
                                .map(sale -> sale.getShift().getStore())
                                .collect(Collectors.toMap(
                                                store -> String.valueOf(store.getId()),
                                                store -> store,
                                                (left, right) -> left,
                                                LinkedHashMap::new))
                                .values()
                                .stream()
                                .map(store -> {
                                        ReportDtos.SalesSummaryFilterOption option = new ReportDtos.SalesSummaryFilterOption();
                                        option.setValue(String.valueOf(store.getId()));
                                        option.setLabel(store.getName());
                                        return option;
                                })
                                .toList());
                response.setCashiers(rangeSales.stream()
                                .filter(sale -> sale.getCreatedBy() != null)
                                .map(Sale::getCreatedBy)
                                .collect(Collectors.toMap(
                                                user -> String.valueOf(user.getId()),
                                                user -> user,
                                                (left, right) -> left,
                                                LinkedHashMap::new))
                                .values()
                                .stream()
                                .map(user -> {
                                        ReportDtos.SalesSummaryFilterOption option = new ReportDtos.SalesSummaryFilterOption();
                                        option.setValue(String.valueOf(user.getId()));
                                        option.setLabel(user.getFullName());
                                        return option;
                                })
                                .toList());
                response.setPayments(rangeSales.stream()
                                .flatMap(sale -> sale.getPayments().stream())
                                .map(Payment::getMethod)
                                .filter(method -> method != null && !method.isBlank())
                                .distinct()
                                .sorted()
                                .map(method -> {
                                        ReportDtos.SalesSummaryFilterOption option = new ReportDtos.SalesSummaryFilterOption();
                                        option.setValue(method);
                                        option.setLabel(method.replace('_', ' '));
                                        return option;
                                })
                                .toList());
                return response;
        }

        public List<ReportDtos.CategoryPerformance> categoryPerformance(LocalDate from, LocalDate to) {
                var start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                return filterSalesByRange(start, end).stream()
                                .flatMap(s -> s.getLines().stream())
                                .filter(line -> line.getProduct().getCategory() != null)
                                .collect(Collectors.groupingBy(line -> line.getProduct().getCategory().getId()))
                                .entrySet().stream().map(entry -> {
                                        var lineList = entry.getValue();
                                        var cat = lineList.get(0).getProduct().getCategory();
                                        BigDecimal total = lineList.stream().map(SaleLine::getLineTotal).reduce(
                                                        BigDecimal.ZERO,
                                                        BigDecimal::add);
                                        BigDecimal qty = lineList.stream().map(SaleLine::getQuantity).reduce(
                                                        BigDecimal.ZERO,
                                                        BigDecimal::add);
                                        ReportDtos.CategoryPerformance cp = new ReportDtos.CategoryPerformance();
                                        cp.setCategoryId(cat.getId());
                                        cp.setCategoryNameEn(cat.getNameEn());
                                        cp.setCategoryNameKm(cat.getNameKm());
                                        cp.setTotal(total);
                                        cp.setQuantity(qty);
                                        return cp;
                                }).sorted(Comparator.comparing(ReportDtos.CategoryPerformance::getTotal).reversed())
                                .collect(Collectors.toList());
        }

        @Transactional(readOnly = true)
        public byte[] dailyZReportPdf(LocalDate date) {
                ReportDtos.DailyZReport report = dailyZReport(date);
                String html = generateZReportHtml(report);
                return pdfService.renderHtmlToPdf(html);
        }

        @Transactional(readOnly = true)
        public ReportDtos.DailyZReport dailyZReport(LocalDate date) {
                var start = date.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = date.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                List<Sale> sales = filterSalesByRange(start, end);

                ReportDtos.DailyZReport report = new ReportDtos.DailyZReport();
                report.setDate(date.toString());
                report.setTransactionCount(sales.size());

                BigDecimal totalSales = sales.stream().map(Sale::getGrandTotal).reduce(BigDecimal.ZERO,
                                BigDecimal::add);
                report.setTotalSales(totalSales);

                Map<String, BigDecimal> methodTotals = sales.stream()
                                .flatMap(s -> s.getPayments() == null ? java.util.stream.Stream.empty()
                                                : s.getPayments().stream())
                                .collect(Collectors.groupingBy(Payment::getMethod,
                                                Collectors.reducing(BigDecimal.ZERO, Payment::getAmount,
                                                                BigDecimal::add)));
                report.setPaymentMethodBreakdown(methodTotals);

                Map<Long, List<SaleLine>> byProduct = sales.stream()
                                .flatMap(s -> s.getLines() == null ? java.util.stream.Stream.empty()
                                                : s.getLines().stream())
                                .collect(Collectors.groupingBy(sl -> sl.getProduct().getId()));

                List<ReportDtos.TopProduct> topProducts = byProduct.entrySet().stream().map(entry -> {
                        var lineList = entry.getValue();
                        var product = lineList.get(0).getProduct();
                        BigDecimal qty = lineList.stream().map(SaleLine::getQuantity).reduce(BigDecimal.ZERO,
                                        BigDecimal::add);
                        BigDecimal total = lineList.stream().map(SaleLine::getLineTotal).reduce(BigDecimal.ZERO,
                                        BigDecimal::add);
                        ReportDtos.TopProduct tp = new ReportDtos.TopProduct();
                        tp.setProductId(product.getId());
                        tp.setNameEn(product.getNameEn());
                        tp.setNameKm(product.getNameKm());
                        tp.setQuantity(qty);
                        tp.setTotal(total);
                        return tp;
                }).sorted(Comparator.comparing(ReportDtos.TopProduct::getTotal).reversed()).limit(10)
                                .collect(Collectors.toList());
                report.setTopProducts(topProducts);

                report.setShifts(shiftSummariesByRange(start, end));

                return report;
        }

        public String generateZReportHtml(ReportDtos.DailyZReport report) {
                var settings = businessSettingsRepository.findAll().stream().findFirst().orElse(null);

                StringBuilder html = new StringBuilder();
                html.append("<!DOCTYPE html><html><head><meta charset='UTF-8'/>")
                                .append("<style>")
                                .append("body { font-family: Arial, sans-serif; font-size: 12px; max-width: 800px; margin: 0 auto; padding: 20px; }")
                                .append(".header { text-align: center; border-bottom: 2px solid #333; padding-bottom: 10px; margin-bottom: 20px; }")
                                .append(".title { font-size: 20px; font-weight: bold; }")
                                .append(".section { margin: 20px 0; }")
                                .append(".section-title { font-size: 14px; font-weight: bold; background: #f0f0f0; padding: 5px; margin-bottom: 10px; }")
                                .append("table { width: 100%; border-collapse: collapse; }")
                                .append("th, td { border: 1px solid #ddd; padding: 8px; text-align: left; }")
                                .append("th { background: #f8f8f8; }")
                                .append(".text-right { text-align: right; }")
                                .append(".total-row { font-weight: bold; background: #f0f0f0; }")
                                .append("</style></head><body>");

                html.append("<div class='header'>")
                                .append("<div class='title'>DAILY Z-REPORT</div>");
                if (settings != null) {
                        html.append("<div>").append(settings.getBusinessName()).append("</div>")
                                        .append("<div>").append(settings.getAddress()).append("</div>")
                                        .append("<div>").append(settings.getPhone()).append("</div>");
                }
                html.append("<div>Report Date: ").append(report.getDate()).append("</div>")
                                .append("<div>Generated: ").append(java.time.LocalDateTime.now()).append("</div>")
                                .append("</div>");

                html.append("<div class='section'>")
                                .append("<div class='section-title'>SALES SUMMARY</div>")
                                .append("<table>")
                                .append("<tr><th>Metric</th><th class='text-right'>Value</th></tr>")
                                .append("<tr><td>Total Transactions</td><td class='text-right'>")
                                .append(report.getTransactionCount()).append("</td></tr>")
                                .append("<tr class='total-row'><td>TOTAL SALES</td><td class='text-right'>៛")
                                .append(String.format("%,.2f", report.getTotalSales())).append("</td></tr>")
                                .append("</table>")
                                .append("</div>");

                Map<String, BigDecimal> paymentBreakdown = report.getPaymentMethodBreakdown() != null
                                ? report.getPaymentMethodBreakdown()
                                : java.util.Collections.emptyMap();

                html.append("<div class='section'>")
                                .append("<div class='section-title'>PAYMENT METHOD BREAKDOWN</div>")
                                .append("<table>")
                                .append("<tr><th>Method</th><th class='text-right'>Amount</th></tr>");
                paymentBreakdown.forEach((method, total) -> {
                        html.append("<tr><td>").append(method).append("</td><td class='text-right'>៛")
                                        .append(String.format("%,.2f", total)).append("</td></tr>");
                });
                html.append("</table></div>");

                html.append("<div class='section'>")
                                .append("<div class='section-title'>SHIFT SUMMARY</div>")
                                .append("<table>")
                                .append("<tr><th>Shift ID</th><th>Opened By</th><th>Status</th><th class='text-right'>Variance</th></tr>");
                report.getShifts().forEach(shift -> {
                        html.append("<tr><td>").append(shift.getShiftId()).append("</td>")
                                        .append("<td>").append(shift.getOpenedBy() != null ? shift.getOpenedBy() : "-")
                                        .append("</td>")
                                        .append("<td>").append(shift.getStatus()).append("</td>")
                                        .append("<td class='text-right'>")
                                        .append(shift.getVariance() != null
                                                        ? String.format("៛%,.2f", shift.getVariance())
                                                        : "-")
                                        .append("</td></tr>");
                });
                html.append("</table></div>");

                html.append("</body></html>");
                return html.toString();
        }

        private List<Sale> filterSalesByRange(java.time.Instant start, java.time.Instant end) {
                return saleRepository.findAll().stream()
                                .filter(s -> s.getCreatedAt() != null && !s.getCreatedAt().isBefore(start)
                                                && s.getCreatedAt().isBefore(end))
                                .filter(s -> REPORT_STATUSES.contains(s.getStatus()))
                                .collect(Collectors.toList());
        }

        private List<ReportDtos.ShiftSummary> shiftSummariesByRange(java.time.Instant start, java.time.Instant end) {
                var shifts = shiftRepository.findAll().stream()
                                .filter(s -> s.getOpenedAt() != null && !s.getOpenedAt().isBefore(start)
                                                && s.getOpenedAt().isBefore(end))
                                .collect(Collectors.toList());

                return shifts.stream().map(shift -> {
                        ReportDtos.ShiftSummary summary = new ReportDtos.ShiftSummary();
                        summary.setShiftId(shift.getId());
                        summary.setOpenedBy(shift.getOpenedBy() != null ? shift.getOpenedBy().getFullName() : null);
                        summary.setOpenedAt(shift.getOpenedAt());
                        summary.setClosedAt(shift.getClosedAt());
                        summary.setOpeningCash(shift.getOpeningCash());
                        summary.setClosingCash(shift.getClosingCash());
                        summary.setExpectedCash(shift.getExpectedCash());
                        summary.setVariance(shift.getVariance());
                        summary.setStatus(shift.getStatus());
                        if (shift.getId() != null) {
                                var view = saleRepository.salesByShift(shift.getId());
                                if (view != null) {
                                        summary.setSalesTotal(view.getTotal());
                                        summary.setSalesCount(view.getCount());
                                }
                        }
                        return summary;
                }).collect(Collectors.toList());
        }

        public List<ReportDtos.StockMovementRow> stockMovements(LocalDate from, LocalDate to) {
                var start = from.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = to.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                return stockMovementRepository.findAll().stream()
                                .filter(m -> m.getCreatedAt() != null && !m.getCreatedAt().isBefore(start)
                                                && m.getCreatedAt().isBefore(end))
                                .map(m -> {
                                        ReportDtos.StockMovementRow row = new ReportDtos.StockMovementRow();
                                        row.setProductId(m.getProduct().getId());
                                        row.setProductNameEn(m.getProduct().getNameEn());
                                        row.setMovementType(m.getMovementType());
                                        row.setQuantity(m.getQuantity());
                                        row.setStoreId(m.getStore().getId());
                                        row.setCreatedAt(m.getCreatedAt().toString());
                                        return row;
                                }).collect(Collectors.toList());
        }

        public Map<String, Object> getShiftSummary(Long shiftId) {
                var shift = shiftRepository.findById(shiftId)
                                .orElseThrow(() -> new ApiException("Shift not found"));

                // Calculate cash sales for this shift
                var shiftSales = saleRepository.findByShiftIdAndStatus(shiftId, "PAID");
                var cashPayments = shiftSales.stream()
                                .flatMap(sale -> sale.getPayments().stream())
                                .filter(payment -> "CASH".equals(payment.getMethod()))
                                .map(Payment::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                var totalSales = shiftSales.stream()
                                .map(Sale::getGrandTotal)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                Map<String, Object> summary = new java.util.HashMap<>();
                summary.put("shiftId", shift.getId());
                summary.put("status", shift.getStatus());
                summary.put("openedAt", shift.getOpenedAt());
                summary.put("closedAt", shift.getClosedAt());
                summary.put("openedBy", shift.getOpenedBy() != null ? shift.getOpenedBy().getFullName() : null);
                summary.put("openingCash", shift.getOpeningCash());
                summary.put("closingCash", shift.getClosingCash());
                summary.put("totalSales", totalSales);
                summary.put("cashPayments", cashPayments);
                summary.put("salesCount", shiftSales.size());

                // Calculate expected cash (opening + cash sales)
                var expectedCash = shift.getOpeningCash().add(cashPayments);
                summary.put("expectedCash", expectedCash);

                // Calculate variance if shift is closed
                if (shift.getClosingCash() != null) {
                        summary.put("variance", shift.getClosingCash().subtract(expectedCash));
                }

                return summary;
        }

        @Transactional(readOnly = true)
        public ReportDtos.SalesReportResponse salesReport(LocalDate fromDate, LocalDate toDate,
                        Long customerId, Long cashierId, String paymentMethod) {
                var start = fromDate.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = toDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);
                List<Sale> rangeSales = filterSalesByRange(start, end);

                // Apply optional filters
                List<Sale> sales = rangeSales.stream()
                                .filter(sale -> customerId == null
                                                || (sale.getCustomer() != null
                                                                && customerId.equals(sale.getCustomer().getId())))
                                .filter(sale -> cashierId == null
                                                || (sale.getCreatedBy() != null
                                                                && cashierId.equals(sale.getCreatedBy().getId())))
                                .filter(sale -> paymentMethod == null || paymentMethod.isBlank()
                                                || "ALL".equalsIgnoreCase(paymentMethod)
                                                || (sale.getPayments() != null && sale.getPayments().stream()
                                                                .anyMatch(p -> paymentMethod
                                                                                .equalsIgnoreCase(p.getMethod()))))
                                .toList();

                // Calculate summary from filtered list
                ReportDtos.SalesReportSummary summary = new ReportDtos.SalesReportSummary();
                summary.setFromDate(fromDate);
                summary.setToDate(toDate);
                summary.setTotalGrossSales(
                                sales.stream().map(Sale::getSubtotal).reduce(BigDecimal.ZERO, BigDecimal::add));
                summary.setTotalNetSales(
                                sales.stream().map(Sale::getGrandTotal).reduce(BigDecimal.ZERO, BigDecimal::add));
                summary.setTotalTax(sales.stream().map(Sale::getTaxAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
                summary.setTotalDiscount(
                                sales.stream().map(Sale::getDiscountAmount).reduce(BigDecimal.ZERO, BigDecimal::add));
                summary.setTotalSalesCount(sales.size());
                summary.setTotalItemsSold(sales.stream().flatMap(s -> s.getLines().stream())
                                .mapToLong(line -> line.getQuantity().longValue()).sum());
                summary.setAverageSaleAmount(sales.isEmpty() ? BigDecimal.ZERO
                                : summary.getTotalNetSales().divide(BigDecimal.valueOf(sales.size()), 2,
                                                java.math.RoundingMode.HALF_UP));

                // Convert sales to details (sorted by date desc)
                List<ReportDtos.SalesDetail> salesDetails = sales.stream()
                                .sorted(Comparator.comparing(Sale::getCreatedAt).reversed())
                                .map(sale -> {
                                        ReportDtos.SalesDetail detail = new ReportDtos.SalesDetail();
                                        detail.setSaleId(sale.getId());
                                        detail.setSaleNumber(sale.getId().toString());
                                        detail.setSaleDate(
                                                        sale.getCreatedAt().atZone(ZoneId.of("UTC")).toLocalDate());
                                        detail.setCustomerId(
                                                        sale.getCustomer() != null ? sale.getCustomer().getId()
                                                                        : null);
                                        detail.setCustomerName(
                                                        sale.getCustomer() != null ? sale.getCustomer().getNameEn()
                                                                        : "Walk-in");
                                        detail.setCashierId(
                                                        sale.getCreatedBy() != null ? sale.getCreatedBy().getId()
                                                                        : null);
                                        detail.setCashierName(
                                                        sale.getCreatedBy() != null
                                                                        ? sale.getCreatedBy().getFullName()
                                                                        : "Unknown");
                                        detail.setGrossAmount(sale.getSubtotal());
                                        detail.setNetAmount(sale.getGrandTotal());
                                        detail.setTaxAmount(sale.getTaxAmount());
                                        detail.setDiscountAmount(sale.getDiscountAmount());
                                        detail.setPaymentMethod(
                                                        sale.getPayments() != null && !sale.getPayments().isEmpty()
                                                                        ? sale.getPayments().get(0).getMethod()
                                                                        : "N/A");
                                        List<ReportDtos.SaleItemDetail> items = sale.getLines().stream().map(line -> {
                                                ReportDtos.SaleItemDetail item = new ReportDtos.SaleItemDetail();
                                                item.setProductId(line.getProduct().getId());
                                                item.setProductNameEn(line.getProduct().getNameEn());
                                                item.setProductNameKm(line.getProduct().getNameKm());
                                                item.setSku(line.getProduct().getSku());
                                                item.setQuantity(line.getQuantity());
                                                item.setUnitPrice(line.getUnitPrice());
                                                item.setTotalPrice(line.getLineTotal());
                                                item.setDiscount(line.getLineDiscount());
                                                return item;
                                        }).collect(Collectors.toList());
                                        detail.setItems(items);
                                        return detail;
                                }).collect(Collectors.toList());

                // Payment breakdown from filtered sales
                Map<String, List<Payment>> byMethod = sales.stream()
                                .flatMap(s -> s.getPayments() != null ? s.getPayments().stream()
                                                : java.util.stream.Stream.empty())
                                .collect(Collectors.groupingBy(Payment::getMethod));
                List<ReportDtos.PaymentBreakdown> paymentBreakdown = byMethod.entrySet().stream().map(entry -> {
                        ReportDtos.PaymentBreakdown pb = new ReportDtos.PaymentBreakdown();
                        pb.setMethod(entry.getKey());
                        pb.setTotal(entry.getValue().stream().map(Payment::getAmount).reduce(BigDecimal.ZERO,
                                        BigDecimal::add));
                        pb.setCount(entry.getValue().size());
                        return pb;
                }).collect(Collectors.toList());

                // Top products from filtered sales
                Map<Long, List<SaleLine>> byProduct = sales.stream()
                                .flatMap(s -> s.getLines() != null ? s.getLines().stream()
                                                : java.util.stream.Stream.empty())
                                .collect(Collectors.groupingBy(sl -> sl.getProduct().getId()));
                List<ReportDtos.TopProduct> topProducts = byProduct.entrySet().stream().map(entry -> {
                        var lineList = entry.getValue();
                        var product = lineList.get(0).getProduct();
                        BigDecimal qty = lineList.stream().map(SaleLine::getQuantity).reduce(BigDecimal.ZERO,
                                        BigDecimal::add);
                        BigDecimal total = lineList.stream().map(SaleLine::getLineTotal).reduce(BigDecimal.ZERO,
                                        BigDecimal::add);
                        ReportDtos.TopProduct tp = new ReportDtos.TopProduct();
                        tp.setProductId(product.getId());
                        tp.setNameEn(product.getNameEn());
                        tp.setNameKm(product.getNameKm());
                        tp.setQuantity(qty);
                        tp.setTotal(total);
                        return tp;
                }).sorted(Comparator.comparing(ReportDtos.TopProduct::getTotal).reversed()).limit(10)
                                .collect(Collectors.toList());

                // Filter options from the full unfiltered range (so dropdowns stay populated)
                List<ReportDtos.SalesSummaryFilterOption> customerOptions = rangeSales.stream()
                                .filter(s -> s.getCustomer() != null)
                                .map(Sale::getCustomer)
                                .collect(Collectors.toMap(
                                                c -> String.valueOf(c.getId()),
                                                c -> c,
                                                (a, b) -> a,
                                                LinkedHashMap::new))
                                .values().stream()
                                .map(c -> {
                                        ReportDtos.SalesSummaryFilterOption opt = new ReportDtos.SalesSummaryFilterOption();
                                        opt.setValue(String.valueOf(c.getId()));
                                        opt.setLabel(c.getNameEn() != null ? c.getNameEn() : c.getNameKm());
                                        return opt;
                                }).toList();

                List<ReportDtos.SalesSummaryFilterOption> cashierOptions = rangeSales.stream()
                                .filter(s -> s.getCreatedBy() != null)
                                .map(Sale::getCreatedBy)
                                .collect(Collectors.toMap(
                                                u -> String.valueOf(u.getId()),
                                                u -> u,
                                                (a, b) -> a,
                                                LinkedHashMap::new))
                                .values().stream()
                                .map(u -> {
                                        ReportDtos.SalesSummaryFilterOption opt = new ReportDtos.SalesSummaryFilterOption();
                                        opt.setValue(String.valueOf(u.getId()));
                                        opt.setLabel(u.getFullName());
                                        return opt;
                                }).toList();

                List<ReportDtos.SalesSummaryFilterOption> pmOptions = rangeSales.stream()
                                .flatMap(s -> s.getPayments() != null ? s.getPayments().stream()
                                                : java.util.stream.Stream.empty())
                                .map(Payment::getMethod)
                                .filter(m -> m != null && !m.isBlank())
                                .distinct().sorted()
                                .map(m -> {
                                        ReportDtos.SalesSummaryFilterOption opt = new ReportDtos.SalesSummaryFilterOption();
                                        opt.setValue(m);
                                        opt.setLabel(m.replace('_', ' '));
                                        return opt;
                                }).toList();

                ReportDtos.SalesReportResponse response = new ReportDtos.SalesReportResponse();
                response.setSummary(summary);
                response.setSales(salesDetails);
                response.setPaymentBreakdown(paymentBreakdown);
                response.setTopProducts(topProducts);
                response.setCustomers(customerOptions);
                response.setCashiers(cashierOptions);
                response.setPaymentOptions(pmOptions);

                return response;
        }

        // EOD Owner Report Methods
        @Transactional
        public ReportDtos.EodRunResponse runEodReport(LocalDate eodDate, String processedBy) {
                // Check if EOD already exists for this date
                Optional<EodSnapshot> existing = eodSnapshotRepository.findByEodDate(eodDate);
                if (existing.isPresent() && "COMPLETED".equals(existing.get().getStatus())) {
                        throw new ApiException("EOD report already exists for date: " + eodDate);
                }

                EodSnapshot snapshot = existing.orElse(new EodSnapshot());
                snapshot.setEodDate(eodDate);
                snapshot.setStatus("PROCESSING");
                snapshot.setProcessedBy(processedBy);
                snapshot = eodSnapshotRepository.save(snapshot);

                try {
                        // Calculate EOD data
                        calculateEodSummary(snapshot);
                        calculateInvoiceSnapshots(snapshot);
                        calculateCollectionSummary(snapshot);
                        calculateAgingSummary(snapshot);
                        calculateCustomerCredits(snapshot);

                        snapshot.setStatus("COMPLETED");
                        eodSnapshotRepository.save(snapshot);

                        return createEodRunResponse(snapshot.getId(), "COMPLETED", "EOD report generated successfully");

                } catch (Exception e) {
                        snapshot.setStatus("FAILED");
                        eodSnapshotRepository.save(snapshot);
                        throw new ApiException("Failed to generate EOD report: " + e.getMessage());
                }
        }

        public ReportDtos.EodReportResponse getEodReport(LocalDate date) {
                EodSnapshot snapshot = eodSnapshotRepository.findCompletedByDate(date)
                                .orElseThrow(() -> new ApiException("EOD report not found for date: " + date));

                ReportDtos.EodReportResponse response = new ReportDtos.EodReportResponse();

                // Summary
                ReportDtos.EodSummary summary = new ReportDtos.EodSummary();
                summary.setEodDate(snapshot.getEodDate());
                summary.setStatus(snapshot.getStatus());
                summary.setNetSalesToday(snapshot.getNetSalesToday());
                summary.setCashCollectedToday(snapshot.getCashCollectedToday());
                summary.setNewCreditToday(snapshot.getNewCreditToday());
                summary.setTotalArBalance(snapshot.getTotalArBalance());
                summary.setOverdueGt30Days(snapshot.getOverdueGt30Days());
                summary.setTotalSalesCount(snapshot.getTotalSalesCount());
                summary.setTotalPaymentsCount(snapshot.getTotalPaymentsCount());
                summary.setProcessedAt(snapshot.getProcessedAt());
                response.setSummary(summary);

                // Invoice rows
                List<ReportDtos.EodInvoiceRow> invoiceRows = snapshot.getInvoiceSnapshots().stream()
                                .map(this::mapToEodInvoiceRow)
                                .sorted((a, b) -> Integer.compare(b.getDaysOutstanding(), a.getDaysOutstanding()))
                                .collect(Collectors.toList());
                response.setInvoices(invoiceRows);

                // Collection summary
                List<ReportDtos.EodCollectionSummary> collectionSummary = snapshot.getCollectionSummaries().stream()
                                .map(this::mapToEodCollectionSummary)
                                .collect(Collectors.toList());
                response.setCollectionSummary(collectionSummary);

                // Aging summary
                List<ReportDtos.EodAgingSummary> agingSummary = snapshot.getAgingSummaries().stream()
                                .map(this::mapToEodAgingSummary)
                                .collect(Collectors.toList());
                response.setAgingSummary(agingSummary);

                // Customer credits
                List<ReportDtos.EodCustomerCredit> customerCredits = snapshot.getCustomerCredits().stream()
                                .map(this::mapToEodCustomerCredit)
                                .collect(Collectors.toList());
                response.setCustomerCredits(customerCredits);

                return response;
        }

        private void calculateEodSummary(EodSnapshot snapshot) {
                LocalDate eodDate = snapshot.getEodDate();
                var start = eodDate.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = eodDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

                List<Sale> todaySales = filterSalesByRange(start, end);

                BigDecimal netSalesToday = todaySales.stream()
                                .map(Sale::getGrandTotal)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal cashCollectedToday = todaySales.stream()
                                .flatMap(sale -> sale.getPayments().stream())
                                .filter(payment -> payment.getCreatedAt().toLocalDate().equals(eodDate))
                                .filter(payment -> "CASH".equals(payment.getMethod()) ||
                                                "CARD".equals(payment.getMethod()) ||
                                                "QR".equals(payment.getMethod()))
                                .map(Payment::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                BigDecimal newCreditToday = todaySales.stream()
                                .filter(sale -> "CREDIT".equals(sale.getStatus()))
                                .map(Sale::getGrandTotal)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Calculate total AR balance (all outstanding balances)
                BigDecimal totalArBalance = saleRepository.findAll().stream()
                                .map(this::calculateBalance)
                                .filter(balance -> balance.compareTo(BigDecimal.ZERO) > 0)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                // Calculate overdue > 30 days
                BigDecimal overdueGt30Days = saleRepository.findAll().stream()
                                .filter(sale -> {
                                        BigDecimal balance = calculateBalance(sale);
                                        if (balance.compareTo(BigDecimal.ZERO) <= 0)
                                                return false;
                                        long days = java.time.temporal.ChronoUnit.DAYS.between(
                                                        sale.getCreatedAt().atZone(ZoneId.of("UTC")).toLocalDate(),
                                                        eodDate);
                                        return days > 30;
                                })
                                .map(this::calculateBalance)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);

                snapshot.setNetSalesToday(netSalesToday);
                snapshot.setCashCollectedToday(cashCollectedToday);
                snapshot.setNewCreditToday(newCreditToday);
                snapshot.setTotalArBalance(totalArBalance);
                snapshot.setOverdueGt30Days(overdueGt30Days);
                snapshot.setTotalSalesCount(todaySales.size());
                snapshot.setTotalPaymentsCount((int) todaySales.stream()
                                .mapToLong(sale -> sale.getPayments().size()).sum());
        }

        private void calculateInvoiceSnapshots(EodSnapshot snapshot) {
                List<Sale> allSales = saleRepository.findAll();
                List<EodInvoiceSnapshot> invoiceSnapshots = new ArrayList<>();

                for (Sale sale : allSales) {
                        EodInvoiceSnapshot invoiceSnapshot = new EodInvoiceSnapshot();
                        invoiceSnapshot.setEodSnapshot(snapshot);
                        invoiceSnapshot.setSale(sale);
                        invoiceSnapshot.setInvoiceNo(sale.getClientRef());
                        invoiceSnapshot.setInvoiceDate(sale.getCreatedAt().atZone(ZoneId.of("UTC")).toLocalDate());
                        invoiceSnapshot.setCustomer(sale.getCustomer());
                        invoiceSnapshot.setCustomerName(
                                        sale.getCustomer() != null ? sale.getCustomer().getNameEn() : "Walk-in");
                        invoiceSnapshot.setTotalSale(sale.getGrandTotal());

                        BigDecimal paidAmount = sale.getPayments().stream()
                                        .map(Payment::getAmount)
                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                        invoiceSnapshot.setPaidAmount(paidAmount);

                        BigDecimal balance = sale.getGrandTotal().subtract(paidAmount);
                        invoiceSnapshot.setBalance(balance);

                        int daysOutstanding = balance.compareTo(BigDecimal.ZERO) > 0
                                        ? (int) java.time.temporal.ChronoUnit.DAYS.between(
                                                        sale.getCreatedAt().atZone(ZoneId.of("UTC")).toLocalDate(),
                                                        snapshot.getEodDate())
                                        : 0;
                        invoiceSnapshot.setDaysOutstanding(daysOutstanding);

                        String agingBucket = calculateAgingBucket(daysOutstanding);
                        invoiceSnapshot.setAgingBucket(agingBucket);

                        String paymentStatus = calculatePaymentStatus(sale, balance);
                        invoiceSnapshot.setPaymentStatus(paymentStatus);

                        invoiceSnapshots.add(invoiceSnapshot);
                }

                snapshot.setInvoiceSnapshots(invoiceSnapshots);
        }

        private void calculateCollectionSummary(EodSnapshot snapshot) {
                LocalDate eodDate = snapshot.getEodDate();
                var start = eodDate.atStartOfDay().toInstant(ZoneOffset.UTC);
                var end = eodDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC);

                Map<String, List<Payment>> paymentsByMethod = filterSalesByRange(start, end).stream()
                                .flatMap(sale -> sale.getPayments().stream())
                                .collect(Collectors.groupingBy(Payment::getMethod));

                List<EodCollectionSummary> collectionSummaries = paymentsByMethod.entrySet().stream()
                                .map(entry -> {
                                        EodCollectionSummary summary = new EodCollectionSummary();
                                        summary.setEodSnapshot(snapshot);
                                        summary.setPaymentMethod(entry.getKey());
                                        BigDecimal total = entry.getValue().stream()
                                                        .map(Payment::getAmount)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                        summary.setTotalAmount(total);
                                        summary.setTransactionCount(entry.getValue().size());
                                        return summary;
                                })
                                .collect(Collectors.toList());

                snapshot.setCollectionSummaries(collectionSummaries);
        }

        private void calculateAgingSummary(EodSnapshot snapshot) {
                Map<String, List<EodInvoiceSnapshot>> invoicesByAging = snapshot.getInvoiceSnapshots().stream()
                                .filter(invoice -> invoice.getBalance().compareTo(BigDecimal.ZERO) > 0)
                                .collect(Collectors.groupingBy(EodInvoiceSnapshot::getAgingBucket));

                List<EodAgingSummary> agingSummaries = invoicesByAging.entrySet().stream()
                                .map(entry -> {
                                        EodAgingSummary summary = new EodAgingSummary();
                                        summary.setEodSnapshot(snapshot);
                                        summary.setAgingBucket(entry.getKey());
                                        BigDecimal totalBalance = entry.getValue().stream()
                                                        .map(EodInvoiceSnapshot::getBalance)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add);
                                        summary.setTotalBalance(totalBalance);
                                        summary.setInvoiceCount(entry.getValue().size());
                                        return summary;
                                })
                                .collect(Collectors.toList());

                snapshot.setAgingSummaries(agingSummaries);
        }

        private void calculateCustomerCredits(EodSnapshot snapshot) {
                // Get all customers with credit
                List<EodCustomerCredit> customerCredits = snapshot.getInvoiceSnapshots().stream()
                                .filter(invoice -> invoice.getCustomer() != null)
                                .collect(Collectors.groupingBy(invoice -> invoice.getCustomer().getId()))
                                .entrySet().stream()
                                .map(entry -> {
                                        Customer customer = entry.getValue().get(0).getCustomer();
                                        BigDecimal currentBalance = entry.getValue().stream()
                                                        .map(EodInvoiceSnapshot::getBalance)
                                                        .reduce(BigDecimal.ZERO, BigDecimal::add);

                                        EodCustomerCredit credit = new EodCustomerCredit();
                                        credit.setEodSnapshot(snapshot);
                                        credit.setCustomer(customer);
                                        credit.setCustomerName(customer.getNameEn());
                                        credit.setCreditLimit(customer.getCreditLimit());
                                        credit.setCurrentBalance(currentBalance);
                                        credit.setStatus(calculateCreditStatus(customer.getCreditLimit(),
                                                        currentBalance));
                                        return credit;
                                })
                                .collect(Collectors.toList());

                snapshot.setCustomerCredits(customerCredits);
        }

        private BigDecimal calculateBalance(Sale sale) {
                BigDecimal total = sale.getGrandTotal();
                BigDecimal paid = sale.getPayments().stream()
                                .map(Payment::getAmount)
                                .reduce(BigDecimal.ZERO, BigDecimal::add);
                return total.subtract(paid);
        }

        private String calculateAgingBucket(int daysOutstanding) {
                if (daysOutstanding == 0)
                        return "0-7";
                if (daysOutstanding <= 7)
                        return "0-7";
                if (daysOutstanding <= 15)
                        return "8-15";
                if (daysOutstanding <= 30)
                        return "16-30";
                return ">30";
        }

        private String calculatePaymentStatus(Sale sale, BigDecimal balance) {
                if (balance.compareTo(BigDecimal.ZERO) == 0)
                        return "PAID";
                if (balance.compareTo(sale.getGrandTotal()) < 0)
                        return "PARTIAL";
                return "CREDIT";
        }

        private String calculateCreditStatus(BigDecimal creditLimit, BigDecimal currentBalance) {
                if (currentBalance.compareTo(creditLimit) > 0)
                        return "OVER_LIMIT";
                if (currentBalance.compareTo(creditLimit.multiply(BigDecimal.valueOf(0.8))) > 0)
                        return "WARNING";
                return "OK";
        }

        private ReportDtos.EodInvoiceRow mapToEodInvoiceRow(EodInvoiceSnapshot snapshot) {
                ReportDtos.EodInvoiceRow row = new ReportDtos.EodInvoiceRow();
                row.setSaleId(snapshot.getSale().getId());
                row.setInvoiceNo(snapshot.getInvoiceNo());
                row.setInvoiceDate(snapshot.getInvoiceDate());
                row.setCustomerName(snapshot.getCustomerName());
                row.setTotalSale(snapshot.getTotalSale());
                row.setPaidAmount(snapshot.getPaidAmount());
                row.setBalance(snapshot.getBalance());
                row.setDaysOutstanding(snapshot.getDaysOutstanding());
                row.setAgingBucket(snapshot.getAgingBucket());
                row.setPaymentStatus(snapshot.getPaymentStatus());
                return row;
        }

        private ReportDtos.EodCollectionSummary mapToEodCollectionSummary(EodCollectionSummary summary) {
                ReportDtos.EodCollectionSummary dto = new ReportDtos.EodCollectionSummary();
                dto.setPaymentMethod(summary.getPaymentMethod());
                dto.setTotalAmount(summary.getTotalAmount());
                dto.setTransactionCount(summary.getTransactionCount());
                return dto;
        }

        private ReportDtos.EodAgingSummary mapToEodAgingSummary(EodAgingSummary summary) {
                ReportDtos.EodAgingSummary dto = new ReportDtos.EodAgingSummary();
                dto.setAgingBucket(summary.getAgingBucket());
                dto.setTotalBalance(summary.getTotalBalance());
                dto.setInvoiceCount(summary.getInvoiceCount());
                return dto;
        }

        private ReportDtos.EodCustomerCredit mapToEodCustomerCredit(EodCustomerCredit credit) {
                ReportDtos.EodCustomerCredit dto = new ReportDtos.EodCustomerCredit();
                dto.setCustomerId(credit.getCustomer().getId());
                dto.setCustomerName(credit.getCustomerName());
                dto.setCreditLimit(credit.getCreditLimit());
                dto.setCurrentBalance(credit.getCurrentBalance());
                dto.setStatus(credit.getStatus());
                return dto;
        }

        private ReportDtos.EodRunResponse createEodRunResponse(Long snapshotId, String status, String message) {
                ReportDtos.EodRunResponse response = new ReportDtos.EodRunResponse();
                response.setSnapshotId(snapshotId);
                response.setStatus(status);
                response.setMessage(message);
                return response;
        }
}
