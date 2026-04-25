package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.Customer;
import com.kaknnea.pos.domain.DeliveryNote;
import com.kaknnea.pos.domain.DeliveryNoteLine;
import com.kaknnea.pos.domain.Sale;
import com.kaknnea.pos.domain.SaleLine;
import com.kaknnea.pos.dto.DeliveryNoteDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.DeliveryNoteRepository;
import com.kaknnea.pos.repository.SaleRepository;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DeliveryNoteService {
    private static final List<String> ACTIVE_NOTE_STATUSES = List.of("DRAFT", "DISPATCHED");
    private static final List<String> DELIVERY_ELIGIBLE_SALE_STATUSES = List.of("DRAFT", "HOLD", "PAID", "CREDIT");

    private final DeliveryNoteRepository deliveryNoteRepository;
    private final SaleRepository saleRepository;

    public DeliveryNoteService(DeliveryNoteRepository deliveryNoteRepository, SaleRepository saleRepository) {
        this.deliveryNoteRepository = deliveryNoteRepository;
        this.saleRepository = saleRepository;
    }

    public List<DeliveryNoteDtos.DeliveryNoteResponse> list() {
        return deliveryNoteRepository.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public DeliveryNoteDtos.DeliveryNoteResponse create(DeliveryNoteDtos.DeliveryNoteRequest request) {
        Sale sale = saleRepository.findById(request.getSaleId())
                .orElseThrow(() -> new ApiException("Sale not found"));
        assertSaleEligible(sale);
        if (deliveryNoteRepository.existsBySaleIdAndStatusIn(sale.getId(), ACTIVE_NOTE_STATUSES)) {
            throw new ApiException("This sale already has an active delivery note");
        }
        DeliveryNote note = new DeliveryNote();
        note.setNoteNumber(nextNumber());
        note.setSale(sale);
        note.setCustomer(sale.getCustomer());
        note.setStatus("DRAFT");
        note.setDeliveryDate(LocalDate.parse(request.getDeliveryDate()));
        note.setContactName(blankToNull(request.getContactName(), sale.getCustomer()));
        note.setContactPhone(request.getContactPhone() == null || request.getContactPhone().isBlank()
                ? sale.getCustomer() != null ? sale.getCustomer().getPhone() : null
                : request.getContactPhone().trim());
        note.setDeliveryAddress(request.getDeliveryAddress() == null || request.getDeliveryAddress().isBlank() ? null : request.getDeliveryAddress().trim());
        note.setNotes(request.getNotes());
        note.setLines(new ArrayList<>());
        applyLines(note, sale, request);
        return toResponse(deliveryNoteRepository.save(note));
    }

    @Transactional
    public DeliveryNoteDtos.DeliveryNoteResponse update(Long id, DeliveryNoteDtos.DeliveryNoteRequest request) {
        DeliveryNote note = deliveryNoteRepository.findById(id)
                .orElseThrow(() -> new ApiException("Delivery note not found"));
        if (!"DRAFT".equalsIgnoreCase(note.getStatus())) {
            throw new ApiException("Only draft delivery notes can be edited");
        }
        if (!note.getSale().getId().equals(request.getSaleId())) {
            throw new ApiException("Delivery note sale cannot be changed");
        }
        Sale sale = saleRepository.findById(request.getSaleId())
                .orElseThrow(() -> new ApiException("Sale not found"));
        assertSaleEligible(sale);
        if (deliveryNoteRepository.existsBySaleIdAndStatusInAndIdNot(sale.getId(), ACTIVE_NOTE_STATUSES, note.getId())) {
            throw new ApiException("This sale already has an active delivery note");
        }
        note.setSale(sale);
        note.setCustomer(sale.getCustomer());
        note.setDeliveryDate(LocalDate.parse(request.getDeliveryDate()));
        note.setContactName(blankToNull(request.getContactName(), sale.getCustomer()));
        note.setContactPhone(request.getContactPhone() == null || request.getContactPhone().isBlank()
                ? sale.getCustomer() != null ? sale.getCustomer().getPhone() : null
                : request.getContactPhone().trim());
        note.setDeliveryAddress(request.getDeliveryAddress() == null || request.getDeliveryAddress().isBlank() ? null : request.getDeliveryAddress().trim());
        note.setNotes(request.getNotes());
        note.getLines().clear();
        applyLines(note, sale, request);
        return toResponse(deliveryNoteRepository.save(note));
    }

    @Transactional
    public DeliveryNoteDtos.DeliveryNoteResponse updateStatus(Long id, String status) {
        DeliveryNote note = deliveryNoteRepository.findById(id).orElseThrow(() -> new ApiException("Delivery note not found"));
        String normalized = status == null ? "" : status.trim().toUpperCase();
        if (!List.of("DRAFT", "DISPATCHED", "DELIVERED", "CANCELLED").contains(normalized)) {
            throw new ApiException("Unsupported delivery note status");
        }
        assertStatusTransitionAllowed(note.getStatus(), normalized);
        note.setStatus(normalized);
        return toResponse(deliveryNoteRepository.save(note));
    }

    private String nextNumber() {
        long next = deliveryNoteRepository.findTopByOrderByIdDesc()
                .map(existing -> existing.getId() + 1)
                .orElse(1L);
        return "DN-" + String.format("%05d", next);
    }

    private String blankToNull(String value, Customer customer) {
        if (value != null && !value.isBlank()) {
            return value.trim();
        }
        return customer != null ? customer.getNameEn() : null;
    }

    private void applyLines(DeliveryNote note, Sale sale, DeliveryNoteDtos.DeliveryNoteRequest request) {
        List<DeliveryNoteDtos.DeliveryNoteLineRequest> lineRequests = request.getLines() == null || request.getLines().isEmpty()
                ? sale.getLines().stream().map(line -> {
                    DeliveryNoteDtos.DeliveryNoteLineRequest lr = new DeliveryNoteDtos.DeliveryNoteLineRequest();
                    lr.setSaleLineId(line.getId());
                    lr.setQuantity(line.getQuantity());
                    return lr;
                }).toList()
                : request.getLines();

        for (DeliveryNoteDtos.DeliveryNoteLineRequest lineRequest : lineRequests) {
            SaleLine saleLine = sale.getLines().stream()
                    .filter(line -> line.getId().equals(lineRequest.getSaleLineId()))
                    .findFirst()
                    .orElseThrow(() -> new ApiException("Sale line not found"));
            if (lineRequest.getQuantity().compareTo(BigDecimal.ZERO) <= 0 || lineRequest.getQuantity().compareTo(saleLine.getQuantity()) > 0) {
                throw new ApiException("Invalid delivery quantity for " + saleLine.getProduct().getNameEn());
            }
            DeliveryNoteLine line = new DeliveryNoteLine();
            line.setDeliveryNote(note);
            line.setSaleLine(saleLine);
            line.setQuantity(lineRequest.getQuantity());
            note.getLines().add(line);
        }
    }

    private void assertSaleEligible(Sale sale) {
        String status = sale.getStatus() == null ? "" : sale.getStatus().trim().toUpperCase();
        if (!DELIVERY_ELIGIBLE_SALE_STATUSES.contains(status)) {
            throw new ApiException("Sale is not eligible for delivery note creation");
        }
    }

    private void assertStatusTransitionAllowed(String currentStatus, String nextStatus) {
        String current = currentStatus == null ? "" : currentStatus.trim().toUpperCase();
        if (current.equals(nextStatus)) {
            return;
        }
        if ("DELIVERED".equals(current) || "CANCELLED".equals(current)) {
            throw new ApiException("Delivery note is already finalized");
        }
        if ("DRAFT".equals(current) && !List.of("DISPATCHED", "CANCELLED").contains(nextStatus)) {
            throw new ApiException("Draft delivery notes can only be dispatched or cancelled");
        }
        if ("DISPATCHED".equals(current) && !List.of("DELIVERED", "CANCELLED").contains(nextStatus)) {
            throw new ApiException("Dispatched delivery notes can only be delivered or cancelled");
        }
    }

    private DeliveryNoteDtos.DeliveryNoteResponse toResponse(DeliveryNote note) {
        DeliveryNoteDtos.DeliveryNoteResponse response = new DeliveryNoteDtos.DeliveryNoteResponse();
        response.setId(note.getId());
        response.setNoteNumber(note.getNoteNumber());
        response.setSaleId(note.getSale().getId());
        response.setSaleNumber(note.getSale().getSaleNumber());
        response.setCustomerId(note.getCustomer() != null ? note.getCustomer().getId() : null);
        response.setCustomerName(note.getCustomer() != null ? note.getCustomer().getNameEn() : "Walk-in");
        response.setStatus(note.getStatus());
        response.setDeliveryDate(note.getDeliveryDate().toString());
        response.setContactName(note.getContactName());
        response.setContactPhone(note.getContactPhone());
        response.setDeliveryAddress(note.getDeliveryAddress());
        response.setNotes(note.getNotes());
        response.setLines(note.getLines().stream().map(line -> {
            DeliveryNoteDtos.DeliveryNoteLineResponse lr = new DeliveryNoteDtos.DeliveryNoteLineResponse();
            lr.setId(line.getId());
            lr.setSaleLineId(line.getSaleLine().getId());
            lr.setProductId(line.getSaleLine().getProduct().getId());
            lr.setProductNameEn(line.getSaleLine().getProduct().getNameEn());
            lr.setQuantity(line.getQuantity());
            return lr;
        }).toList());
        return response;
    }
}
