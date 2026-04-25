package com.kaknnea.pos.service;

import com.kaknnea.pos.dto.ReceiptDtos;
import com.kaknnea.pos.dto.PurchasingWorkflowDtos;
import com.kaknnea.pos.repository.BusinessSettingsRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Email service placeholder for receipt notifications.
 * Integrate with SMTP/SES/SendGrid in production.
 */
@Service
public class EmailService {
    private static final Logger log = LoggerFactory.getLogger(EmailService.class);

    private final BusinessSettingsRepository businessSettingsRepository;

    public EmailService(BusinessSettingsRepository businessSettingsRepository) {
        this.businessSettingsRepository = businessSettingsRepository;
    }

    public void sendReceipt(String recipientEmail, String recipientName,
            ReceiptDtos.ReceiptResponse receipt, byte[] pdfAttachment) {
        var settings = businessSettingsRepository.findAll().stream().findFirst().orElse(null);
        String businessName = settings != null ? settings.getBusinessName() : "KAKNNEA POS";

        log.info("Email receipt queued: saleId={}, business={}, to={} ({})", receipt.getSaleId(),
                businessName, recipientName, recipientEmail);
        log.info("PDF attachment size: {} bytes", pdfAttachment != null ? pdfAttachment.length : 0);
        // TODO: integrate real email provider
    }

    public void sendPurchaseOrder(String recipientEmail, String recipientName,
            PurchasingWorkflowDtos.PurchaseOrderResponse purchaseOrder) {
        var settings = businessSettingsRepository.findAll().stream().findFirst().orElse(null);
        String businessName = settings != null ? settings.getBusinessName() : "KAKNNEA POS";

        log.info("Purchase order email queued: poId={}, business={}, to={} ({}), total={}, status={}",
                purchaseOrder.getId(), businessName, recipientName, recipientEmail,
                purchaseOrder.getTotalAmount(), purchaseOrder.getStatus());
        // TODO: integrate real email provider
    }
}
