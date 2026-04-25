package com.kaknnea.pos.repository;

import com.kaknnea.pos.domain.Sale;
import java.util.List;
import java.util.Optional;
import java.math.BigDecimal;
import java.time.Instant;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface SaleRepository extends JpaRepository<Sale, Long> {
  List<Sale> findByCustomerId(Long customerId);

  @Query("SELECT s FROM Sale s WHERE s.customer.id = :customerId AND s.status = :status ORDER BY s.creditDueAt ASC NULLS LAST, s.createdAt ASC")
  List<Sale> findByCustomerIdAndStatusOrderByCreditDueAtAscCreatedAtAsc(@Param("customerId") Long customerId,
      @Param("status") String status);

  Optional<Sale> findByClientRef(String clientRef);

  List<Sale> findByStatusOrderByCreatedAtDesc(String status);

  List<Sale> findByShiftIdAndStatus(Long shiftId, String status);

  List<Sale> findByShiftIdAndStatusOrderByCreatedAtDesc(Long shiftId, String status);

  List<Sale> findByShiftIdOrderByCreatedAtDesc(Long shiftId);

  @Query("select count(s.id) from Sale s where s.shift.id = :shiftId and s.status = 'HOLD'")
  long countHeldTicketsByShift(@Param("shiftId") Long shiftId);

  @Query("select count(s.id) from Sale s where s.shift.id = :shiftId and s.status in ('DRAFT','IN_PROGRESS')")
  long countInProgressTicketsByShift(@Param("shiftId") Long shiftId);

  @Query("select coalesce(sum(s.grandTotal - s.paidAmount), 0) from Sale s where s.shift.id = :shiftId and s.status = 'CREDIT'")
  BigDecimal outstandingCreditByShift(@Param("shiftId") Long shiftId);

  List<Sale> findAllByOrderByCreatedAtDesc();

  @Query("""
      select s from Sale s
      where (:shiftId is null or s.shift.id = :shiftId)
        and (:status is null or s.status = :status)
        and (:dateFrom is null or s.createdAt >= :dateFrom)
        and (:dateTo is null or s.createdAt < :dateTo)
      order by s.createdAt desc
      """)
  List<Sale> findFiltered(
      @Param("shiftId") Long shiftId,
      @Param("status") String status,
      @Param("dateFrom") Instant dateFrom,
      @Param("dateTo") Instant dateTo);

  @Query("select sum(s.totalAmount) as total, count(s.id) as count from Sale s where s.shift.id = :shiftId and s.status in ('PAID','CREDIT')")
  ShiftSalesView salesByShift(@Param("shiftId") Long shiftId);

  @Query("select coalesce(sum(s.grandTotal), 0) from Sale s where s.customer.id = :customerId and s.status not in ('VOID')")
  BigDecimal totalSalesByCustomerId(@Param("customerId") Long customerId);

  // Added for HeldTicketService compatibility
  @Query("SELECT s FROM Sale s WHERE (:terminalId IS NULL OR s.terminalId = :terminalId) AND s.status = 'OPEN' ORDER BY s.createdAt DESC")
  List<Sale> findOpenTickets(@Param("terminalId") Long terminalId);

  @Query("SELECT COUNT(s) FROM Sale s WHERE s.predefinedTicket.id = :predefinedTicketId AND s.status = 'ACTIVE'")
  long countActiveByPredefinedTicketId(@Param("predefinedTicketId") Long predefinedTicketId);

  @Query("SELECT COUNT(s) FROM Sale s WHERE s.predefinedTicket.id = :predefinedTicketId AND s.id <> :excludeSaleId AND s.status = 'ACTIVE'")
  long countActiveByPredefinedTicketIdExcluding(@Param("predefinedTicketId") Long predefinedTicketId,
      @Param("excludeSaleId") Long excludeSaleId);
}
