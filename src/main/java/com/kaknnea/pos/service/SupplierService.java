package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.Supplier;
import com.kaknnea.pos.dto.SupplierDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.PurchaseOrderRepository;
import com.kaknnea.pos.repository.SupplierCatalogItemRepository;
import com.kaknnea.pos.repository.SupplierInvoiceRepository;
import com.kaknnea.pos.repository.SupplierRepository;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class SupplierService {
    private final SupplierRepository supplierRepository;
    private final SupplierCatalogItemRepository supplierCatalogItemRepository;
    private final SupplierInvoiceRepository supplierInvoiceRepository;
    private final PurchaseOrderRepository purchaseOrderRepository;

    public SupplierService(
            SupplierRepository supplierRepository,
            SupplierCatalogItemRepository supplierCatalogItemRepository,
            SupplierInvoiceRepository supplierInvoiceRepository,
            PurchaseOrderRepository purchaseOrderRepository) {
        this.supplierRepository = supplierRepository;
        this.supplierCatalogItemRepository = supplierCatalogItemRepository;
        this.supplierInvoiceRepository = supplierInvoiceRepository;
        this.purchaseOrderRepository = purchaseOrderRepository;
    }

    public List<SupplierDtos.SupplierResponse> list() {
        return supplierRepository.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    public SupplierDtos.SupplierResponse get(Long id) {
        return toResponse(supplierRepository.findById(id).orElseThrow(() -> new ApiException("Supplier not found")));
    }

    public SupplierDtos.SupplierResponse create(SupplierDtos.SupplierRequest request) {
        Supplier supplier = new Supplier();
        supplier.setName(request.getName());
        supplier.setContactPerson(request.getContactPerson());
        supplier.setPhone(request.getPhone());
        supplier.setEmail(request.getEmail());
        supplier.setAddress(request.getAddress());
        supplier.setPaymentTerms(request.getPaymentTerms());
        supplier.setLeadTimeDays(request.getLeadTimeDays());
        supplier.setTaxId(request.getTaxId());
        supplier.setDefaultCurrency(request.getDefaultCurrency() == null || request.getDefaultCurrency().isBlank() ? "KHR" : request.getDefaultCurrency().trim().toUpperCase());
        supplier.setActive(request.isActive());
        supplier.setNotes(request.getNotes());
        return toResponse(supplierRepository.save(supplier));
    }

    public SupplierDtos.SupplierResponse update(Long id, SupplierDtos.SupplierRequest request) {
        Supplier supplier = supplierRepository.findById(id).orElseThrow(() -> new ApiException("Supplier not found"));
        supplier.setName(request.getName());
        supplier.setContactPerson(request.getContactPerson());
        supplier.setPhone(request.getPhone());
        supplier.setEmail(request.getEmail());
        supplier.setAddress(request.getAddress());
        supplier.setPaymentTerms(request.getPaymentTerms());
        supplier.setLeadTimeDays(request.getLeadTimeDays());
        supplier.setTaxId(request.getTaxId());
        supplier.setDefaultCurrency(request.getDefaultCurrency() == null || request.getDefaultCurrency().isBlank() ? "KHR" : request.getDefaultCurrency().trim().toUpperCase());
        supplier.setActive(request.isActive());
        supplier.setNotes(request.getNotes());
        return toResponse(supplierRepository.save(supplier));
    }

    public void delete(Long id) {
        Supplier supplier = supplierRepository.findById(id).orElseThrow(() -> new ApiException("Supplier not found"));
        supplierCatalogItemRepository.deleteAllBySupplierId(id);
        supplierRepository.delete(supplier);
    }

    private SupplierDtos.SupplierResponse toResponse(Supplier supplier) {
        SupplierDtos.SupplierResponse resp = new SupplierDtos.SupplierResponse();
        resp.setId(supplier.getId());
        resp.setName(supplier.getName());
        resp.setContactPerson(supplier.getContactPerson());
        resp.setPhone(supplier.getPhone());
        resp.setEmail(supplier.getEmail());
        resp.setAddress(supplier.getAddress());
        resp.setPaymentTerms(supplier.getPaymentTerms());
        resp.setLeadTimeDays(supplier.getLeadTimeDays());
        resp.setTaxId(supplier.getTaxId());
        resp.setDefaultCurrency(supplier.getDefaultCurrency());
        resp.setActive(supplier.isActive());
        resp.setNotes(supplier.getNotes());
        resp.setCatalogItemCount(supplierCatalogItemRepository.countBySupplierId(supplier.getId()));
        resp.setOpenPayable(supplierInvoiceRepository.findAll().stream()
                .filter(invoice -> invoice.getSupplier().getId().equals(supplier.getId()))
                .map(invoice -> invoice.getOutstandingAmount() == null ? BigDecimal.ZERO : invoice.getOutstandingAmount())
                .reduce(BigDecimal.ZERO, BigDecimal::add));
        resp.setLastPurchaseDate(purchaseOrderRepository.findAll().stream()
                .filter(order -> order.getSupplier().getId().equals(supplier.getId()))
                .map(order -> order.getOrderedAt() == null ? null : order.getOrderedAt().toString())
                .filter(value -> value != null)
                .max(String::compareTo)
                .orElse(null));
        return resp;
    }
}
