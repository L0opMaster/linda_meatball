package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.SupplierPayment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupplierPaymentRepository extends JpaRepository<SupplierPayment, Long> {
    List<SupplierPayment> findAllBySupplierInvoiceIdOrderByPaidAtDesc(Long supplierInvoiceId);
}
