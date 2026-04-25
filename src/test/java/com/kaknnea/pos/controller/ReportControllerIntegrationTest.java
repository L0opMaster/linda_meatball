package com.kaknnea.pos.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.annotation.Transactional;

@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@TestPropertySource(locations = "classpath:application-test.properties")
class ReportControllerIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @WithMockUser(authorities = "PERM_REPORTS_VIEW")
    void salesSummaryEndpoint_returnsExpectedShape() throws Exception {
        mockMvc.perform(get("/api/reports/sales-summary")
                        .param("from", "2026-03-01")
                        .param("to", "2026-03-05"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.fromDate").value("2026-03-01"))
                .andExpect(jsonPath("$.toDate").value("2026-03-05"))
                .andExpect(jsonPath("$.rows").isArray())
                .andExpect(jsonPath("$.stores").isArray())
                .andExpect(jsonPath("$.cashiers").isArray())
                .andExpect(jsonPath("$.payments").isArray())
                .andExpect(jsonPath("$.totals").exists())
                .andExpect(jsonPath("$.totals.totalSales").exists())
                .andExpect(jsonPath("$.totals.gross").exists())
                .andExpect(jsonPath("$.totals.discount").exists())
                .andExpect(jsonPath("$.totals.tax").exists())
                .andExpect(jsonPath("$.totals.net").exists())
                .andExpect(jsonPath("$.totals.orders").exists())
                .andExpect(jsonPath("$.totals.quantity").exists());
    }
}
