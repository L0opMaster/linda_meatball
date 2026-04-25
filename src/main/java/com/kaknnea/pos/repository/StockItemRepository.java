package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.StockItem;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StockItemRepository extends JpaRepository<StockItem, Long> {
    Optional<StockItem> findByProductIdAndStoreId(Long productId, Long storeId);

    List<StockItem> findAllByProductId(Long productId);

    List<StockItem> findAllByStoreId(Long storeId);
}
