package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.InventorySnapshot;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;

public interface InventorySnapshotRepository extends JpaRepository<InventorySnapshot, Long> {
    List<InventorySnapshot> findAllBySnapshotDateAndStoreIdOrderByProduct_NameEnAsc(LocalDate snapshotDate, Long storeId);

    boolean existsBySnapshotDateAndStoreId(LocalDate snapshotDate, Long storeId);
}
