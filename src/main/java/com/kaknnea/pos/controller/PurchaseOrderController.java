package com.kaknnea.pos.controller;

import com.kaknnea.pos.dto.PurchasingWorkflowDtos;
import com.kaknnea.pos.service.PurchasingWorkflowService;
import jakarta.validation.Valid;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/purchase-orders")
public class PurchaseOrderController {
    private final PurchasingWorkflowService purchasingWorkflowService;

    public PurchaseOrderController(PurchasingWorkflowService purchasingWorkflowService) {
        this.purchasingWorkflowService = purchasingWorkflowService;
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('PERM_PURCHASE_MANAGE', 'PERM_SUPPLIER_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN', 'ACCOUNTANT')")
    public List<PurchasingWorkflowDtos.PurchaseOrderResponse> list() {
        return purchasingWorkflowService.listPurchaseOrders();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('PERM_PURCHASE_MANAGE', 'PERM_SUPPLIER_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN', 'ACCOUNTANT')")
    public PurchasingWorkflowDtos.PurchaseOrderResponse get(@PathVariable Long id) {
        return purchasingWorkflowService.getPurchaseOrder(id);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_PURCHASE_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN')")
    public PurchasingWorkflowDtos.PurchaseOrderResponse create(
            @Valid @RequestBody PurchasingWorkflowDtos.PurchaseOrderRequest request) {
        return purchasingWorkflowService.createPurchaseOrder(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_PURCHASE_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN')")
    public PurchasingWorkflowDtos.PurchaseOrderResponse update(@PathVariable Long id,
            @Valid @RequestBody PurchasingWorkflowDtos.PurchaseOrderRequest request) {
        return purchasingWorkflowService.updatePurchaseOrder(id, request);
    }

    @PostMapping("/{id}/{action}")
    @PreAuthorize("hasAuthority('PERM_PURCHASE_MANAGE') or hasAnyRole('OWNER', 'MANAGER', 'ADMIN')")
    public PurchasingWorkflowDtos.PurchaseOrderResponse transition(@PathVariable Long id, @PathVariable String action) {
        return purchasingWorkflowService.transitionPurchaseOrder(id, action);
    }
}
