package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.Shift;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface ShiftRepository extends JpaRepository<Shift, Long> {
    Optional<Shift> findFirstByOpenedByIdAndStatusOrderByOpenedAtDesc(Long openedById, String status);
    Optional<Shift> findFirstByOpenedByIdAndStoreIdAndStatusOrderByOpenedAtDesc(Long openedById, Long storeId, String status);
}
