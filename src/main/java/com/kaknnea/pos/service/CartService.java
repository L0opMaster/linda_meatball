package com.kaknnea.pos.service;

import com.kaknnea.pos.domain.*;
import com.kaknnea.pos.dto.SaleDtos;
import com.kaknnea.pos.exception.ApiException;
import com.kaknnea.pos.repository.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Slf4j
@Service
@Transactional
@RequiredArgsConstructor
public class CartService {
    private final CartRepository cartRepository;
    private final CartItemRepository cartItemRepository;
    private final ProductRepository productRepository;
    private final CustomerRepository customerRepository;
    private final StoreRepository storeRepository;
    private final SaleService saleService;

    /**
     * Create a new cart for a customer and store
     */
    public Cart createCart(Long customerId, Long storeId) {
        log.info("Creating new cart for customer: {} in store: {}", customerId, storeId);

        Cart cart = new Cart();

        // Customer is optional — can be assigned later at checkout
        if (customerId != null) {
            Customer customer = customerRepository.findById(customerId)
                    .orElseThrow(() -> new ApiException("Customer not found: " + customerId));
            cart.setCustomer(customer);
        }

        // Store is optional
        if (storeId != null) {
            Store store = storeRepository.findById(storeId)
                    .orElseThrow(() -> new ApiException("Store not found: " + storeId));
            cart.setStore(store);
        }

        cart.setStatus(Cart.CartStatus.ACTIVE);
        cart.setTotalAmount(BigDecimal.ZERO);
        cart.setItemCount(0);

        return cartRepository.save(cart);
    }

    /**
     * Get cart by id
     */
    @Transactional(readOnly = true)
    public Cart getCart(Long cartId) {
        log.debug("Fetching cart: {}", cartId);
        return cartRepository.findById(cartId)
                .orElseThrow(() -> new ApiException("Cart not found: " + cartId));
    }

    /**
     * Get active cart for a customer
     */
    @Transactional(readOnly = true)
    public Optional<Cart> getActiveCartForCustomer(Long customerId) {
        log.debug("Fetching active cart for customer: {}", customerId);
        return cartRepository.findActiveCartByCustomerId(customerId);
    }

    /**
     * Add item to cart
     */
    public CartItem addItemToCart(Long cartId, Long productId, int quantity, BigDecimal unitPrice) {
        log.info("Adding item to cart. CartId: {}, ProductId: {}, Quantity: {}, UnitPrice: {}", cartId, productId, quantity, unitPrice);

        validateQuantity(quantity);

        Cart cart = getCart(cartId);
        Product product = productRepository.findById(productId)
                .orElseThrow(() -> new ApiException("Product not found: " + productId));

        BigDecimal price = (unitPrice != null) ? unitPrice : product.getPrice();

        // Check if item already exists
        Optional<CartItem> existingItem = cartItemRepository.findByCartIdAndProductId(cartId, productId);

        if (existingItem.isPresent()) {
            log.info("Item already exists in cart, updating quantity and price");
            CartItem item = existingItem.get();
            item.setUnitPrice(price);
            item.updateQuantity(item.getQuantity() + quantity);
            CartItem saved = cartItemRepository.save(item);
            cart.calculateTotal();
            cartRepository.save(cart);
            return saved;
        }

        // Create new cart item
        CartItem newItem = new CartItem();
        newItem.setCart(cart);
        newItem.setProduct(product);
        newItem.setQuantity(quantity);
        newItem.setUnitPrice(price);
        newItem.setDiscountAmount(BigDecimal.ZERO);
        newItem.calculateTotal();

        CartItem saved = cartItemRepository.save(newItem);

        // Add to cart and recalculate
        cart.addItem(saved);
        cart.calculateTotal();
        cartRepository.save(cart);

        log.info("Successfully added item to cart");
        return saved;
    }

    /**
     * Update cart item quantity
     */
    public CartItem updateCartItemQuantity(Long cartId, Long cartItemId, int newQuantity, BigDecimal unitPrice) {
        log.info("Updating cart item. CartId: {}, ItemId: {}, NewQuantity: {}, UnitPrice: {}", cartId, cartItemId,
                newQuantity, unitPrice);

        validateQuantity(newQuantity);

        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ApiException("Cart item not found: " + cartItemId));

        if (!item.getCart().getId().equals(cartId)) {
            throw new ApiException("Cart item does not belong to this cart");
        }

        if (unitPrice != null) {
            item.setUnitPrice(unitPrice);
        }
        item.updateQuantity(newQuantity);
        CartItem saved = cartItemRepository.save(item);

        // Recalculate cart total
        Cart cart = getCart(cartId);
        cart.calculateTotal();
        cartRepository.save(cart);

        log.info("Successfully updated cart item quantity");
        return saved;
    }

    /**
     * Remove item from cart
     */
    public void removeItemFromCart(Long cartId, Long cartItemId) {
        log.info("Removing item from cart. CartId: {}, ItemId: {}", cartId, cartItemId);

        Cart cart = getCart(cartId);
        CartItem item = cartItemRepository.findById(cartItemId)
                .orElseThrow(() -> new ApiException("Cart item not found: " + cartItemId));

        if (!item.getCart().getId().equals(cartId)) {
            throw new ApiException("Cart item does not belong to this cart");
        }

        cartItemRepository.deleteById(cartItemId);

        // Recalculate cart total
        cart.removeItemById(cartItemId);
        cart.calculateTotal();
        cartRepository.save(cart);

        log.info("Successfully removed item from cart");
    }

    /**
     * Remove item from cart by product id
     */
    public void removeItemFromCartByProductId(Long cartId, Long productId) {
        log.info("Removing item from cart by product id. CartId: {}, ProductId: {}", cartId, productId);

        Cart cart = getCart(cartId);
        cartItemRepository.deleteByCartIdAndProductId(cartId, productId);

        // Recalculate cart total
        cart.removeItem(productId);
        cart.calculateTotal();
        cartRepository.save(cart);

        log.info("Successfully removed item from cart");
    }

    /**
     * Clear all items from cart
     */
    public void clearCart(Long cartId) {
        log.info("Clearing cart: {}", cartId);

        Cart cart = getCart(cartId);
        cart.clear();
        cartRepository.save(cart);

        log.info("Successfully cleared cart");
    }

    /**
     * Get cart items
     */
    @Transactional(readOnly = true)
    public List<CartItem> getCartItems(Long cartId) {
        log.debug("Fetching cart items for cart: {}", cartId);
        return cartItemRepository.findByCartId(cartId);
    }

    /**
     * Complete cart — creates a Sale with PACKAGING status, then marks the cart as COMPLETED.
     * Returns the created Sale ID alongside the cart.
     */
    public Cart completeCart(Long cartId) {
        log.info("Completing cart: {}", cartId);

        Cart cart = getCart(cartId);

        if (!cart.isActive()) {
            throw new IllegalStateException("Cannot complete a cart that is not active");
        }

        if (cart.getItems().isEmpty()) {
            throw new ApiException("Cannot checkout an empty cart");
        }

        // Build a SaleCreateRequest from the cart items
        List<SaleDtos.SaleLineRequest> lines = cart.getItems().stream().map(item -> {
            SaleDtos.SaleLineRequest line = new SaleDtos.SaleLineRequest();
            line.setProductId(item.getProduct().getId());
            line.setQuantity(BigDecimal.valueOf(item.getQuantity()));
            line.setUnitPrice(item.getUnitPrice());
            return line;
        }).collect(Collectors.toList());

        SaleDtos.SaleCreateRequest saleReq = new SaleDtos.SaleCreateRequest();
        saleReq.setCustomerId(cart.getCustomer() != null ? cart.getCustomer().getId() : null);
        saleReq.setLines(lines);

        // Create the sale (status = DRAFT), then switch to PACKAGING
        SaleDtos.SaleResponse saleResp = saleService.create(saleReq);
        saleService.setStatus(saleResp.getId(), "PACKAGING");

        cart.setSaleId(saleResp.getId());
        cart.complete();
        return cartRepository.save(cart);
    }

    /**
     * Abandon cart (change status to ABANDONED)
     */
    public Cart abandonCart(Long cartId) {
        log.info("Abandoning cart: {}", cartId);

        Cart cart = getCart(cartId);
        cart.abandon();
        return cartRepository.save(cart);
    }

    /**
     * Calculate cart total
     */
    @Transactional(readOnly = true)
    public BigDecimal calculateCartTotal(Long cartId) {
        log.debug("Calculating cart total for cart: {}", cartId);

        List<CartItem> items = getCartItems(cartId);
        return items.stream()
                .map(CartItem::getTotalPrice)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /**
     * Get cart item count
     */
    @Transactional(readOnly = true)
    public int getItemCount(Long cartId) {
        log.debug("Getting item count for cart: {}", cartId);

        List<CartItem> items = getCartItems(cartId);
        return items.stream()
                .mapToInt(CartItem::getQuantity)
                .sum();
    }

    /**
     * Validate quantity
     */
    private void validateQuantity(int quantity) {
        if (quantity <= 0) {
            throw new IllegalArgumentException("Quantity must be greater than 0");
        }
        if (quantity > 10000) {
            throw new IllegalArgumentException("Quantity cannot exceed 10000");
        }
    }
}
