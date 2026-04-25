package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.*;
import com.kaknnea.pos.dto.PurchaseDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class PurchaseService {
    private final PurchaseRepository purchaseRepository;
    private final SupplierRepository supplierRepository;
    private final ProductRepository productRepository;
    private final StockItemRepository stockItemRepository;
    private final StockMovementRepository stockMovementRepository;
    private final StoreRepository storeRepository;

    public PurchaseService(PurchaseRepository purchaseRepository,
                           SupplierRepository supplierRepository,
                           ProductRepository productRepository,
                           StockItemRepository stockItemRepository,
                           StockMovementRepository stockMovementRepository,
                           StoreRepository storeRepository) {
        this.purchaseRepository = purchaseRepository;
        this.supplierRepository = supplierRepository;
        this.productRepository = productRepository;
        this.stockItemRepository = stockItemRepository;
        this.stockMovementRepository = stockMovementRepository;
        this.storeRepository = storeRepository;
    }

    public List<PurchaseDtos.PurchaseResponse> list() {
        return purchaseRepository.findAll().stream().map(this::toResponse).collect(Collectors.toList());
    }

    @Transactional
    public PurchaseDtos.PurchaseResponse create(PurchaseDtos.PurchaseCreateRequest request) {
        Supplier supplier = supplierRepository.findById(request.getSupplierId())
                .orElseThrow(() -> new ApiException("Supplier not found"));
        Purchase purchase = new Purchase();
        purchase.setSupplier(supplier);
        purchase.setStatus("DRAFT");
        purchase.setPaidAmount(BigDecimal.ZERO);

        List<PurchaseLine> lines = request.getLines().stream().map(lineReq -> {
            Product product = productRepository.findById(lineReq.getProductId())
                    .orElseThrow(() -> new ApiException("Product not found"));
            if (!product.isPurchasable()) {
                throw new ApiException(product.getNameEn() + " is not marked as purchasable");
            }
            if (!product.isTrackInventory()) {
                throw new ApiException(product.getNameEn() + " is not inventory tracked");
            }
            PurchaseLine line = new PurchaseLine();
            line.setPurchase(purchase);
            line.setProduct(product);
            line.setQuantity(lineReq.getQuantity());
            line.setUnitCost(lineReq.getUnitCost());
            line.setLineTotal(lineReq.getUnitCost().multiply(lineReq.getQuantity()));
            return line;
        }).collect(Collectors.toList());
        purchase.setLines(lines);

        BigDecimal subtotal = lines.stream().map(PurchaseLine::getLineTotal).reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal tax = subtotal.multiply(BigDecimal.valueOf(request.getTaxRate())).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(tax);
        purchase.setSubtotal(subtotal);
        purchase.setTaxAmount(tax);
        purchase.setTotalAmount(total);

        return toResponse(purchaseRepository.save(purchase));
    }

    @Transactional
    public PurchaseDtos.PurchaseResponse receive(Long id) {
        Purchase purchase = purchaseRepository.findById(id).orElseThrow(() -> new ApiException("Purchase not found"));
        if (!"DRAFT".equals(purchase.getStatus())) {
            throw new ApiException("Purchase already received");
        }
        for (PurchaseLine line : purchase.getLines()) {
            Store store = storeRepository.findById(1L).orElseThrow();
            StockItem stock = stockItemRepository.findByProductIdAndStoreId(line.getProduct().getId(), store.getId())
                    .orElseGet(() -> {
                        StockItem s = new StockItem();
                        s.setProduct(line.getProduct());
                        s.setStore(store);
                        s.setQuantity(BigDecimal.ZERO);
                        s.setLowStockThreshold(new BigDecimal("5"));
                        return s;
                    });
            BigDecimal oldQty = stock.getQuantity();
            BigDecimal newQty = oldQty.add(line.getQuantity());
            stock.setQuantity(newQty);
            stockItemRepository.save(stock);

            // Average costing
            Product product = line.getProduct();
            BigDecimal oldCost = product.getCost();
            if (newQty.compareTo(BigDecimal.ZERO) > 0) {
                BigDecimal newCost = oldCost.multiply(oldQty).add(line.getUnitCost().multiply(line.getQuantity()))
                        .divide(newQty, 2, RoundingMode.HALF_UP);
                product.setCost(newCost);
                productRepository.save(product);
            }

            StockMovement movement = new StockMovement();
            movement.setProduct(line.getProduct());
            movement.setStore(store);
            movement.setMovementType("IN");
            movement.setQuantity(line.getQuantity());
            movement.setReason("PURCHASE");
            stockMovementRepository.save(movement);
        }
        purchase.setStatus("RECEIVED");
        return toResponse(purchaseRepository.save(purchase));
    }

    @Transactional
    public PurchaseDtos.PurchaseResponse pay(Long id, PurchaseDtos.PurchasePayRequest request) {
        Purchase purchase = purchaseRepository.findById(id).orElseThrow(() -> new ApiException("Purchase not found"));
        purchase.setPaidAmount(purchase.getPaidAmount().add(request.getAmount()));
        if (purchase.getPaidAmount().compareTo(purchase.getTotalAmount()) >= 0) {
            purchase.setStatus("PAID");
        }
        return toResponse(purchaseRepository.save(purchase));
    }

    private PurchaseDtos.PurchaseResponse toResponse(Purchase purchase) {
        PurchaseDtos.PurchaseResponse resp = new PurchaseDtos.PurchaseResponse();
        resp.setId(purchase.getId());
        resp.setSupplierId(purchase.getSupplier().getId());
        resp.setStatus(purchase.getStatus());
        resp.setSubtotal(purchase.getSubtotal());
        resp.setTaxAmount(purchase.getTaxAmount());
        resp.setTotalAmount(purchase.getTotalAmount());
        resp.setPaidAmount(purchase.getPaidAmount());
        resp.setLines(purchase.getLines().stream().map(line -> {
            PurchaseDtos.PurchaseLineResponse lr = new PurchaseDtos.PurchaseLineResponse();
            lr.setProductId(line.getProduct().getId());
            lr.setProductNameEn(line.getProduct().getNameEn());
            lr.setProductNameKm(line.getProduct().getNameKm());
            lr.setPurchaseUnitCode(line.getProduct().getPurchaseUnit() != null ? line.getProduct().getPurchaseUnit().getCode() : "EACH");
            lr.setQuantity(line.getQuantity());
            lr.setUnitCost(line.getUnitCost());
            lr.setLineTotal(line.getLineTotal());
            return lr;
        }).collect(Collectors.toList()));
        return resp;
    }
}
