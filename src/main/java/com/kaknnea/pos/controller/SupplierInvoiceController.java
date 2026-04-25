package com.kaknnea.pos.controller;

import com.kaknnea.pos.dto.PurchasingWorkflowDtos;
import com.kaknnea.pos.service.PurchasingWorkflowService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/supplier-invoices")
public class SupplierInvoiceController {
    private final PurchasingWorkflowService purchasingWorkflowService;

    public SupplierInvoiceController(PurchasingWorkflowService purchasingWorkflowService) {
        this.purchasingWorkflowService = purchasingWorkflowService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_PURCHASE_MANAGE', 'PERM_SUPPLIER_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN', 'ACCOUNTANT')")
    public List<PurchasingWorkflowDtos.SupplierInvoiceResponse> list() {
        return purchasingWorkflowService.listSupplierInvoices();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_PURCHASE_MANAGE', 'PERM_SUPPLIER_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN', 'ACCOUNTANT')")
    public PurchasingWorkflowDtos.SupplierInvoiceResponse get(@PathVariable Long id) {
        return purchasingWorkflowService.getSupplierInvoice(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PURCHASE_MANAGE') or hasRole('ACCOUNTANT') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN')")
    public PurchasingWorkflowDtos.SupplierInvoiceResponse create(
            @Valid @RequestBody PurchasingWorkflowDtos.SupplierInvoiceRequest request) {
        return purchasingWorkflowService.createSupplierInvoice(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_PURCHASE_MANAGE') or hasRole('ACCOUNTANT') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN')")
    public PurchasingWorkflowDtos.SupplierInvoiceResponse update(
            @PathVariable Long id,
            @Valid @RequestBody PurchasingWorkflowDtos.SupplierInvoiceRequest request) {
        return purchasingWorkflowService.updateSupplierInvoice(id, request);
    }

    @PostMapping("/{id}/match-check")
    @PreAuthorize("hasAnyAuthority('PERM_PURCHASE_MANAGE', 'PERM_SUPPLIER_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN', 'ACCOUNTANT')")
    public PurchasingWorkflowDtos.PurchaseMatchWarningResponse matchCheck(@PathVariable Long id) {
        return purchasingWorkflowService.invoiceMatchWarnings(id);
    }
}
