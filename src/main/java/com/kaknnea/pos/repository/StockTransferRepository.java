package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.StockTransfer;
import org.springframework.data.jpa.repository.JpaRepository;

public interface StockTransferRepository extends JpaRepository<StockTransfer, Long> {
}
