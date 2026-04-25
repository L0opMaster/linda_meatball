package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.GoodsReceipt;
import org.springframework.data.jpa.repository.JpaRepository;

public interface GoodsReceiptRepository extends JpaRepository<GoodsReceipt, Long> {
    boolean existsByLinesProductId(Long productId);
}
