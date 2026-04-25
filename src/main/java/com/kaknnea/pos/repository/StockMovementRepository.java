package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.StockMovement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockMovementRepository extends JpaRepository<StockMovement, Long> {
    List<StockMovement> findAllByProductIdOrderByCreatedAtDesc(Long productId);

    List<StockMovement> findAllByStoreIdOrderByCreatedAtDesc(Long storeId);

    boolean existsByProductId(Long productId);
}
