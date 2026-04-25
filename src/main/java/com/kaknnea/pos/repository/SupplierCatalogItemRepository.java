package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.SupplierCatalogItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierCatalogItemRepository extends JpaRepository<SupplierCatalogItem, Long> {
    List<SupplierCatalogItem> findAllBySupplierIdOrderByCreatedAtDesc(Long supplierId);

    long countBySupplierId(Long supplierId);
    long countByPurchaseUnitId(Long purchaseUnitId);

    void deleteAllBySupplierId(Long supplierId);
}
