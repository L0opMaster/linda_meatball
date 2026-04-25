package com.kaknnea.pos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import org.springframework.data.domain.Page;

import java.math.BigDecimal;
import java.util.List;

public class CustomerDtos {
    @Data
    public static class CustomerSaleResponse {
        private Long saleId;
        private String status;
        private String createdAt;
        private java.math.BigDecimal grandTotal;
        private java.math.BigDecimal paidAmount;
    }

    @Data
    public static class CustomerRequest {
        private String type;
        private String code;
        private String nameEn;
        private String nameKm;
        private String displayName;
        private String phone;
        private String email;
        private String address;
        private String notes;
        private String status;
        private String contactPerson;
        private String paymentTerms;
        private String taxNumber;
        private BigDecimal creditLimit = BigDecimal.ZERO;
        private boolean creditHold = false;
    }

    @Data
    public static class CustomerRepaymentRequest {
        @NotNull
        private BigDecimal amount;
        @NotBlank
        private String paymentMethod;
        private String notes;
        private Long storeId;
    }

    @Data
    public static class CustomerResponse {
        private Long id;
        private String code;
        private String type;
        private String status;
        private String nameEn;
        private String nameKm;
        private String displayName;
        private String phone;
        private String email;
        private String address;
        private String notes;
        private String contactPerson;
        private String paymentTerms;
        private String taxNumber;
        private BigDecimal creditBalance;
        private BigDecimal creditLimit;
        private boolean creditHold;
        private BigDecimal totalSales;
    }

    @Data
    public static class CustomerSearchResponse {
        private List<CustomerResponse> data;
        private long total;
        private boolean hasMore;

        public static CustomerSearchResponse from(Page<CustomerResponse> page) {
            CustomerSearchResponse response = new CustomerSearchResponse();
            response.setData(page.getContent());
            response.setTotal(page.getTotalElements());
            response.setHasMore(page.hasNext());
            return response;
        }
    }
}
