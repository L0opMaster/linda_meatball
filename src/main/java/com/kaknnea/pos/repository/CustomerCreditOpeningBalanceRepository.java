package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.CustomerCreditOpeningBalance;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CustomerCreditOpeningBalanceRepository extends JpaRepository<CustomerCreditOpeningBalance, Long> {
    List<CustomerCreditOpeningBalance> findByCustomerIdAndRemainingAmountGreaterThanOrderByCreatedAtAsc(
            Long customerId,
            java.math.BigDecimal remainingAmount);
}
