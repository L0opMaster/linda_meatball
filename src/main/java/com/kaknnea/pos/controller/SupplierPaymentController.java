package com.kaknnea.pos.controller;

import com.kaknnea.pos.dto.PurchasingWorkflowDtos;
import com.kaknnea.pos.service.PurchasingWorkflowService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/supplier-payments")
public class SupplierPaymentController {
    private final PurchasingWorkflowService purchasingWorkflowService;

    public SupplierPaymentController(PurchasingWorkflowService purchasingWorkflowService) {
        this.purchasingWorkflowService = purchasingWorkflowService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_PURCHASE_MANAGE', 'PERM_SUPPLIER_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN', 'ACCOUNTANT')")
    public List<PurchasingWorkflowDtos.SupplierPaymentResponse> list() {
        return purchasingWorkflowService.listSupplierPayments();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_PURCHASE_MANAGE', 'PERM_SUPPLIER_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN', 'ACCOUNTANT')")
    public PurchasingWorkflowDtos.SupplierPaymentResponse get(@PathVariable Long id) {
        return purchasingWorkflowService.getSupplierPayment(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PURCHASE_MANAGE') or hasRole('ACCOUNTANT') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN')")
    public PurchasingWorkflowDtos.SupplierPaymentResponse create(
            @Valid @RequestBody PurchasingWorkflowDtos.SupplierPaymentRequest request) {
        return purchasingWorkflowService.createSupplierPayment(request);
    }
}
