package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.SupplierInvoice;
import org.springframework.data.jpa.repository.JpaRepository;

public interface SupplierInvoiceRepository extends JpaRepository<SupplierInvoice, Long> {
    boolean existsByLinesProductId(Long productId);
}
