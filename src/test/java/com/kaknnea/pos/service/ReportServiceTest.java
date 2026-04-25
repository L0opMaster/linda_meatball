package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.Sale;
import com.kaknnea.pos.domain.Shift;
import com.kaknnea.pos.domain.User;
import com.kaknnea.pos.repository.SaleRepository;
import com.kaknnea.pos.repository.ShiftRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ReportServiceTest {

    @Mock
    private SaleRepository saleRepository;

    @Mock
    private ShiftRepository shiftRepository;

    @InjectMocks
    private ReportService reportService;

    private Shift testShift;
    private Sale testSale;

    @BeforeEach
    void setUp() {
        User testUser = new User();
        testUser.setId(1L);
        testUser.setFullName("Test User");

        testShift = new Shift();
        testShift.setId(1L);
        testShift.setStatus("OPEN");
        testShift.setOpenedAt(Instant.now());
        testShift.setOpenedBy(testUser);
        testShift.setOpeningCash(BigDecimal.valueOf(100.00));
        testShift.setClosingCash(BigDecimal.valueOf(150.00));

        testSale = new Sale();
        testSale.setId(1L);
        testSale.setStatus("PAID");
        testSale.setGrandTotal(BigDecimal.valueOf(50.00));
    }

    @Test
    void getShiftSummary_ShouldReturnSummary_WhenShiftExists() {
        // Given
        when(shiftRepository.findById(1L)).thenReturn(Optional.of(testShift));
        when(saleRepository.findByShiftIdAndStatus(1L, "PAID")).thenReturn(List.of(testSale));

        // When
        Map<String, Object> result = reportService.getShiftSummary(1L);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.get("shiftId")).isEqualTo(1L);
        assertThat(result.get("status")).isEqualTo("OPEN");
        assertThat(result.get("totalSales")).isEqualTo(BigDecimal.valueOf(50.00));
        assertThat(result.get("salesCount")).isEqualTo(1);
    }

    @Test
    void getShiftSummary_ShouldThrowException_WhenShiftNotFound() {
        // Given
        when(shiftRepository.findById(999L)).thenReturn(Optional.empty());

        // When & Then
        assertThatThrownBy(() -> reportService.getShiftSummary(999L))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("Shift not found");
    }
}