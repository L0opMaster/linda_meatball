package com.kaknnea.pos.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

@Entity
@Table(name = "business_settings")
@Getter
@Setter
public class BusinessSettings extends BaseEntity {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "business_name", nullable = false, length = 150)
    private String businessName;

    @Column(name = "logo_url", length = 255)
    private String logoUrl;

    @Column(length = 255)
    private String address;

    @Column(length = 50)
    private String phone;

    @Column(name = "tax_rate", nullable = false)
    private double taxRate;

    @Column(name = "currency", nullable = false, length = 10)
    private String currency;

    @Column(name = "receipt_footer", length = 255)
    private String receiptFooter;

    @Column(name = "default_language", length = 5)
    private String defaultLanguage;

    @Column(name = "pos_layout_config", columnDefinition = "LONGTEXT")
    private String posLayoutConfig;

    @Column(name = "open_ticket_config", columnDefinition = "LONGTEXT")
    private String openTicketConfig;
}
