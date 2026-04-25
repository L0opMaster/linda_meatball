package com.kaknnea.pos.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.util.List;

public class ReceiptDtos {
    @Data
    public static class ReceiptLine {
        private Long saleLineId;
        private String nameEn;
        private String nameKm;
        private BigDecimal qty;
        private BigDecimal unitPrice;
        private BigDecimal lineTotal;
        private BigDecimal refundedQty;
    }

    @Data
    public static class ReceiptPayment {
        private String method;
        private BigDecimal amount;
    }

    @Data
    public static class ReceiptResponse {
        private String businessName;
        private String address;
        private String phone;
        private String currency;
        private String footer;
        private Long saleId;
        private Long shiftId;
        private Long storeId;
        private String createdAt;
        private String cashierName;
        private String storeName;
        private String customerName;
        private String customerPhone;
        private List<ReceiptLine> lines;
        private List<ReceiptPayment> payments;
        private BigDecimal subtotal;
        private BigDecimal taxAmount;
        private BigDecimal discountAmount;
        private BigDecimal total;
        private BigDecimal paidAmount;
        private BigDecimal changeAmount;
        private BigDecimal refundedAmount;
        private String status;
        private String qrImageData;
    }
}
