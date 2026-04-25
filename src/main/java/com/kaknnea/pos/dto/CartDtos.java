package com.kaknnea.pos.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public class CartDtos {

    /**
     * Request DTO for creating a new cart
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CreateCartRequest {
        private Long customerId; // Optional — can assign customer later at checkout

        private Long storeId; // Optional
    }

    /**
     * Request DTO for adding item to cart
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AddItemRequest {
        @NotNull(message = "Product ID is required")
        private Long productId;

        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;

        private BigDecimal unitPrice; // Optional — overrides product price if provided
    }

    /**
     * Request DTO for updating cart item quantity
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class UpdateItemRequest {
        @NotNull(message = "Quantity is required")
        @Min(value = 1, message = "Quantity must be at least 1")
        private Integer quantity;

        private BigDecimal unitPrice; // Optional — update price if provided
    }

    /**
     * Response DTO for cart item
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartItemResponse {
        private Long id;
        private Long productId;
        private String productNameEn;
        private String productNameKm;
        private String productSku;
        private int quantity;
        private BigDecimal unitPrice;
        private BigDecimal discountAmount;
        private BigDecimal totalPrice;
        private BigDecimal subtotal;
        private Instant createdAt;
        private Instant updatedAt;
    }

    /**
     * Response DTO for cart
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartResponse {
        private Long id;
        private Long customerId;
        private String customerNameEn;
        private String customerNameKm;
        private Long storeId;
        private String storeName;
        private String status;
        private BigDecimal totalAmount;
        private int itemCount;
        private List<CartItemResponse> items;
        private Instant createdAt;
        private Instant updatedAt;
    }

    /**
     * Response DTO for checkout/completion
     */
    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    public static class CartCheckoutResponse {
        private Long cartId;
        private Long saleId;
        private String status;
        private BigDecimal totalAmount;
        private int itemCount;
        private Instant completedAt;
        private String message;
    }
}
