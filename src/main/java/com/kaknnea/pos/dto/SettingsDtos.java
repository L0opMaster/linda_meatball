package com.kaknnea.pos.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import java.math.BigDecimal;
import java.util.List;

public class SettingsDtos {
    @Data
    public static class BusinessSettingsRequest {
        @NotBlank
        private String businessName;
        private String logoUrl;
        private String address;
        private String phone;
        private double taxRate;
        private String currency;
        private String receiptFooter;
        private String defaultLanguage;
    }

    @Data
    public static class BusinessSettingsResponse {
        private Long id;
        private String businessName;
        private String logoUrl;
        private String address;
        private String phone;
        private double taxRate;
        private String currency;
        private String receiptFooter;
        private String defaultLanguage;
    }

    @Data
    public static class InvoiceSettingsRequest {
        private String prefix;
        private Long nextNumber;
        private String footer;
        private Boolean showTax;
        private Boolean showKhqr;
        private String printerName;
        private String printerType;
        private String printerAddress;
        private String defaultInvoiceFormat;
        private String defaultReceiptFormat;
    }

    @Data
    public static class InvoiceSettingsResponse {
        private Long id;
        private String prefix;
        private Long nextNumber;
        private String footer;
        private boolean showTax;
        private boolean showKhqr;
        private String printerName;
        private String printerType;
        private String printerAddress;
        private String defaultInvoiceFormat;
        private String defaultReceiptFormat;
    }

    @Data
    public static class SettingsRequest {
        @NotBlank
        private String businessName;
        private String logoUrl;
        private String address;
        private String phone;
        private double taxRate;
        private String currency;
        private String receiptFooter;
        private String defaultLanguage;
    }

    @Data
    public static class SettingsResponse {
        private Long id;
        private String businessName;
        private String logoUrl;
        private String address;
        private String phone;
        private double taxRate;
        private String currency;
        private String receiptFooter;
        private String defaultLanguage;
    }

    @Data
    public static class GeneralSettingsRequest {
        private String defaultLanguage;
        private String currency;
        private String receiptFooter;
        private String defaultInvoiceFormat;
        private String defaultReceiptFormat;
        private Boolean showKhqr;
    }

    @Data
    public static class GeneralSettingsResponse {
        private String defaultLanguage;
        private String currency;
        private String receiptFooter;
        private String defaultInvoiceFormat;
        private String defaultReceiptFormat;
        private boolean showKhqr;
    }

    @Data
    public static class CompanyProfileSettingsRequest {
        @NotBlank
        private String businessName;
        private String logoUrl;
        private String address;
        private String phone;
        private String receiptFooter;
    }

    @Data
    public static class CompanyProfileSettingsResponse {
        private Long id;
        private String businessName;
        private String logoUrl;
        private String address;
        private String phone;
        private String receiptFooter;
    }

    @Data
    public static class TaxSettingsRequest {
        private double taxRate;
        private Boolean showTax;
    }

    @Data
    public static class TaxSettingsResponse {
        private double taxRate;
        private boolean showTax;
    }

    @Data
    public static class PrinterSettingsRequest {
        private String printerName;
        private String printerType;
        private String printerAddress;
        private String defaultInvoiceFormat;
        private String defaultReceiptFormat;
        private String invoicePrefix;
        private Long nextInvoiceNumber;
        private String invoiceFooter;
    }

    @Data
    public static class PrinterSettingsResponse {
        private String printerName;
        private String printerType;
        private String printerAddress;
        private String defaultInvoiceFormat;
        private String defaultReceiptFormat;
        private String invoicePrefix;
        private Long nextInvoiceNumber;
        private String invoiceFooter;
    }

    @Data
    public static class PaymentMethodRequest {
        @NotBlank
        private String code;
        @NotBlank
        private String name;
        private int displayOrder;
        private boolean cash;
        private boolean active = true;
    }

    @Data
    public static class PaymentMethodResponse {
        private Long id;
        private String code;
        private String name;
        private int displayOrder;
        private boolean cash;
        private boolean active;
    }

    @Data
    public static class PaymentMethodStatusRequest {
        private boolean active;
    }

    @Data
    public static class CurrencyRequest {
        @NotBlank
        private String code;
        @NotBlank
        private String name;
        @NotBlank
        private String symbol;
        @NotNull
        private BigDecimal exchangeRate;
        private int displayOrder;
        private boolean defaultCurrency;
        private boolean active = true;
    }

    @Data
    public static class CurrencyResponse {
        private Long id;
        private String code;
        private String name;
        private String symbol;
        private BigDecimal exchangeRate;
        private int displayOrder;
        private boolean defaultCurrency;
        private boolean active;
    }

    @Data
    public static class CurrencyStatusRequest {
        private boolean active;
    }

    @Data
    public static class SettingsLookupResponse {
        private List<com.kaknnea.pos.dto.UnitDtos.UnitResponse> units;
    }

    @Data
    public static class PosQuickActionConfig {
        private String id;
        private String label;
        private String icon;
        private Boolean enabled;
        private Integer order;
        private Boolean visibleInRestaurant;
        private Boolean visibleInRetail;
    }

    @Data
    public static class PosMenuSectionConfig {
        private String id;
        private String label;
        private String icon;
        private Boolean enabled;
        private Integer order;
    }

    @Data
    public static class PosCategoryPresentationConfig {
        private Long categoryId;
        private String label;
        private Integer displayOrder;
        private Boolean favorite;
        private Boolean visibleInRestaurant;
        private Boolean visibleInRetail;
        private Boolean visibleOnQuickBar;
        private Boolean predefinedTicketEligible;
        private String color;
        private String image;
        private Integer modifierPriority;
    }

    @Data
    public static class PosLayoutSettingsRequest {
        private String preset;
        private String defaultMode;
        private Boolean showBarcode;
        private Boolean showCustomerPanel;
        private Boolean showHeldSales;
        private Boolean showTables;
        private Boolean showProductionInAdmin;
        private List<PosQuickActionConfig> quickActions;
        private List<PosMenuSectionConfig> menuSections;
        private List<PosCategoryPresentationConfig> categoryPresentation;
    }

    @Data
    public static class PosLayoutSettingsResponse {
        private String preset;
        private String defaultMode;
        private Boolean showBarcode;
        private Boolean showCustomerPanel;
        private Boolean showHeldSales;
        private Boolean showTables;
        private Boolean showProductionInAdmin;
        private List<PosQuickActionConfig> quickActions;
        private List<PosMenuSectionConfig> menuSections;
        private List<PosCategoryPresentationConfig> categoryPresentation;
    }

    @Data
    public static class OpenTicketSettingsRequest {
        private Boolean enableOpenTickets;
        private Boolean enableTables;
        private Boolean enableSplitMerge;
        private Boolean enableTransfer;
        private String predefinedTicketPrefix;
    }

    @Data
    public static class OpenTicketSettingsResponse {
        private Boolean enableOpenTickets;
        private Boolean enableTables;
        private Boolean enableSplitMerge;
        private Boolean enableTransfer;
        private String predefinedTicketPrefix;
    }
}
