package com.kaknnea.pos.controller;

import com.kaknnea.pos.dto.SaleDtos;
import com.kaknnea.pos.service.SaleService;
import com.kaknnea.pos.service.ShiftService;
import jakarta.validation.Valid;
import java.time.Instant;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/pos/sales")
public class SaleController {
    private final SaleService saleService;
    private final ShiftService shiftService;

    public SaleController(SaleService saleService, ShiftService shiftService) {
        this.saleService = saleService;
        this.shiftService = shiftService;
    }

    @PostMapping
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse create(@Valid @RequestBody SaleDtos.SaleCreateRequest request) {
        return saleService.create(request);
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse update(@PathVariable Long id, @Valid @RequestBody SaleDtos.SaleCreateRequest request) {
        return saleService.update(id, request);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse getById(@PathVariable Long id) {
        return saleService.getById(id);
    }

    @PostMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse hold(@PathVariable Long id) {
        return saleService.hold(id);
    }

    @PutMapping("/{id}/hold")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse holdPut(@PathVariable Long id) {
        return saleService.hold(id);
    }

    @PutMapping("/{id}/resume")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse resume(@PathVariable Long id) {
        return saleService.resume(id);
    }

    @PutMapping("/{id}/void")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse voidSale(@PathVariable Long id,
            @RequestBody(required = false) java.util.Map<String, String> body) {
        String reason = body != null ? body.get("reason") : null;
        return saleService.voidSale(id, reason);
    }

    @PutMapping("/{id}/complete-packaging")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse completePackaging(@PathVariable Long id) {
        return saleService.completePackaging(id);
    }

    @PutMapping("/{id}/start-preparing")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse startPreparing(@PathVariable Long id) {
        return saleService.startPreparing(id);
    }

    @PostMapping("/{id}/pay")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse pay(@PathVariable Long id, @Valid @RequestBody SaleDtos.PayRequest request) {
        return saleService.pay(id, request);
    }

    @PostMapping("/{id}/credit")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse credit(@PathVariable Long id) {
        return saleService.credit(id);
    }

    @PostMapping("/{id}/refund")
    @PreAuthorize("hasAuthority('PERM_POS_REFUND')")
    public SaleDtos.SaleResponse refund(@PathVariable Long id, @Valid @RequestBody SaleDtos.RefundRequest request) {
        return saleService.refund(id, request);
    }

    @PostMapping("/{id}/repayments")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public SaleDtos.SaleResponse repay(@PathVariable Long id,
            @Valid @RequestBody SaleDtos.CreditRepaymentRequest request) {
        return saleService.repayCreditSale(id, request);
    }

    @GetMapping("/{id}/receipt")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public com.kaknnea.pos.dto.ReceiptDtos.ReceiptResponse receipt(@PathVariable Long id) {
        return saleService.receipt(id);
    }

    @GetMapping(value = "/{id}/invoice.pdf", produces = "application/pdf")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public byte[] invoicePdf(@PathVariable Long id,
            @RequestParam(required = false, defaultValue = "false") Boolean thermal) {
        return saleService.invoicePdf(id, thermal);
    }

    @GetMapping("/active-shift")
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public java.util.List<SaleDtos.SaleResponse> listByActiveShift(
            @RequestParam(required = false, defaultValue = "PAID") String status) {
        var currentShift = shiftService.getCurrentShift();
        if (currentShift == null) {
            return java.util.List.of();
        }
        return saleService.listByShift(currentShift.getId(), status);
    }

    @GetMapping
    @PreAuthorize("hasAuthority('PERM_POS_SALE')")
    public java.util.List<SaleDtos.SaleResponse> listSales(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) Long shiftId,
            @RequestParam(required = false) Instant dateFrom,
            @RequestParam(required = false) Instant dateTo,
            @RequestParam(required = false) String query) {
        return saleService.listFiltered(shiftId, status, dateFrom, dateTo, query);
    }
}
