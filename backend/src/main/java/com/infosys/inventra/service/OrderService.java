package com.infosys.inventra.service;

import com.infosys.inventra.dto.OrderDTO;
import com.infosys.inventra.dto.OrderItemDTO;
import com.infosys.inventra.model.Order;
import com.infosys.inventra.model.OrderItem;
import com.infosys.inventra.model.Product;
import com.infosys.inventra.repository.OrderRepository;
import com.infosys.inventra.repository.ProductRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
public class OrderService {

    @Autowired
    private OrderRepository orderRepository;

    @Autowired
    private ProductRepository productRepository;

    @Autowired
    private AlertService alertService;

    @Autowired
    private AuditLogService auditLogService;

    /**
     * Get all orders
     */
    public List<OrderDTO> getAllOrders() {
        return orderRepository.findAll().stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get order by ID
     */
    public Optional<OrderDTO> getOrderById(Long id) {
        return orderRepository.findById(id).map(this::convertToDTO);
    }

    /**
     * Get orders by user
     */
    public List<OrderDTO> getOrdersByUserId(Long userId) {
        return orderRepository.findByUserId(userId).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get orders by status
     */
    public List<OrderDTO> getOrdersByStatus(String status) {
        return orderRepository.findByStatus(status).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get orders by user and status (for employees to see their own orders)
     */
    public List<OrderDTO> getOrdersByUserIdAndStatus(Long userId, String status) {
        return orderRepository.findByUserIdAndStatus(userId, status).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Get orders by date range
     */
    public List<OrderDTO> getOrdersByDateRange(LocalDateTime startDate, LocalDateTime endDate) {
        return orderRepository.findByOrderDateBetween(startDate, endDate).stream()
                .map(this::convertToDTO)
                .collect(Collectors.toList());
    }

    /**
     * Create new order
     */
    @Transactional
    public OrderDTO createOrder(OrderDTO orderDTO, Long actorUserId, String actorRole) {
        if (orderDTO.getOrderItems() == null || orderDTO.getOrderItems().isEmpty()) {
            throw new RuntimeException("At least one order item is required");
        }

        // Generate order number
        String orderNumber = generateOrderNumber();
        
        final Order order = new Order();
        order.setOrderNumber(orderNumber);
        order.setUserId(orderDTO.getUserId());
        order.setSupplierId(orderDTO.getSupplierId());
       
if (orderDTO.getSupplierId() == null) {
    // Employee request
    order.setOrderType("REQUEST");
} else {
    // Admin purchase
    order.setOrderType("PURCHASE");
}
        order.setStatus("PENDING"); // Default status
        order.setNotes(orderDTO.getNotes());
        order.setExpectedDeliveryDate(orderDTO.getExpectedDeliveryDate());
        
        // Calculate total from order items
        BigDecimal totalAmount;
        List<OrderItem> orderItems = orderDTO.getOrderItems().stream()
                .map(itemDTO -> {
                    OrderItem item = convertToOrderItemEntity(itemDTO);
                    item.setOrder(order);
                    
                    // Get product details
                    Optional<Product> productOpt = productRepository.findById(itemDTO.getProductId());
                    if (productOpt.isEmpty()) {
                        throw new RuntimeException("Product not found for id " + itemDTO.getProductId());
                    }

                    Product product = productOpt.get();
                    item.setProductName(product.getName());
                    item.setProductSku(product.getSku());

                    // Use product price if not provided
                    if (itemDTO.getUnitPrice() == null) {
                        item.setUnitPrice(product.getPrice());
                    }
                    
                    // Calculate subtotal
                    BigDecimal subtotal = item.getUnitPrice().multiply(new BigDecimal(item.getQuantity()));
                    item.setSubtotal(subtotal);
                    
                    return item;
                })
                .collect(Collectors.toList());
        
        // Calculate total amount
        totalAmount = orderItems.stream()
                .map(OrderItem::getSubtotal)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
        
        order.setTotalAmount(totalAmount);
        order.setOrderItems(orderItems);

        Order savedOrder = orderRepository.save(order);
if ("REQUEST".equals(savedOrder.getOrderType())) {
    for (OrderItem item : savedOrder.getOrderItems()) {
        if (item.getProductId() != null) {
            alertService.createStockRequestAlert(savedOrder, item.getProductId());
        }
    }
}

        auditLogService.log(
                "ORDER_CREATED",
                "ORDER",
                savedOrder.getId(),
                "Created order " + savedOrder.getOrderNumber() + " with status " + savedOrder.getStatus(),
                actorUserId,
                actorRole
        );

        return convertToDTO(savedOrder);
    }

    /**
     * Update order
     */
    @Transactional
    public Optional<OrderDTO> updateOrder(Long id, OrderDTO orderDTO, Long actorUserId, String actorRole) {
        Optional<Order> orderOpt = orderRepository.findById(id);

        if (orderOpt.isEmpty()) {
            return Optional.empty();
        }

        Order order = orderOpt.get();
        order.setStatus(orderDTO.getStatus());
        order.setNotes(orderDTO.getNotes());
        order.setExpectedDeliveryDate(orderDTO.getExpectedDeliveryDate());
        order.setActualDeliveryDate(orderDTO.getActualDeliveryDate());

        order = orderRepository.save(order);
        auditLogService.log(
            "ORDER_UPDATED",
            "ORDER",
            order.getId(),
            "Updated order " + order.getOrderNumber() + " to status " + order.getStatus(),
            actorUserId,
            actorRole
        );
        return Optional.of(convertToDTO(order));
    }

    /**
     * Approve order (Admin only)
     */
    @Transactional
public Optional<OrderDTO> approveOrder(Long id, Long adminId) {

    Optional<Order> orderOpt = orderRepository.findById(id);

    if (orderOpt.isEmpty()) {
        return Optional.empty();
    }

    Order order = orderOpt.get();

    if (!"PENDING".equals(order.getStatus())) {
        throw new RuntimeException("Only pending orders can be approved");
    }

    order.setStatus("APPROVED");
    order.setApprovedBy(adminId);
    order.setApprovedAt(LocalDateTime.now());

    // 🔥 FIXED LOGIC FOR BOTH TYPES
    for (OrderItem item : order.getOrderItems()) {

        Optional<Product> productOpt = productRepository.findById(item.getProductId());

        if (productOpt.isPresent()) {

            Product product = productOpt.get();

            // ✅ STOCK IN (Supplier Purchase)
            if ("PURCHASE".equals(order.getOrderType())) {

                product.setQuantity(product.getQuantity() + item.getQuantity());
            }

            // 🔥 STOCK OUT (Employee Request)
            else if ("REQUEST".equals(order.getOrderType())) {

                if (product.getQuantity() < item.getQuantity()) {
                    throw new RuntimeException(
                        "Not enough stock for product: " + product.getName()
                    );
                }

                product.setQuantity(product.getQuantity() - item.getQuantity());
            }

            Product updatedProduct = productRepository.save(product);

            // Optional alert
            alertService.evaluateLowStockAlert(updatedProduct);
        }
    }

    order = orderRepository.save(order);

    // Create approval alert
    alertService.createApprovalAlert(order);

    auditLogService.log(
            "ORDER_APPROVED",
            "ORDER",
            order.getId(),
            "Approved order " + order.getOrderNumber() + " and updated stock",
            adminId,
            "ADMIN"
    );

    return Optional.of(convertToDTO(order));
}

    /**
     * Cancel order
     */
    @Transactional
    public Optional<OrderDTO> cancelOrder(Long id, Long actorUserId, String actorRole) {
        Optional<Order> orderOpt = orderRepository.findById(id);

        if (orderOpt.isEmpty()) {
            return Optional.empty();
        }

        Order order = orderOpt.get();
        
        if ("DELIVERED".equals(order.getStatus())) {
            throw new RuntimeException("Delivered orders cannot be cancelled");
        }
        
        order.setStatus("CANCELLED");
        order = orderRepository.save(order);
        auditLogService.log(
            "ORDER_CANCELLED",
            "ORDER",
            order.getId(),
            "Cancelled order " + order.getOrderNumber(),
            actorUserId,
            actorRole
        );
        return Optional.of(convertToDTO(order));
    }

    /**
     * Delete order
     */
    public boolean deleteOrder(Long id, Long actorUserId, String actorRole) {
        if (orderRepository.existsById(id)) {
            Optional<Order> orderOpt = orderRepository.findById(id);
            orderRepository.deleteById(id);
            if (orderOpt.isPresent()) {
                auditLogService.log(
                        "ORDER_DELETED",
                        "ORDER",
                        id,
                        "Deleted order " + orderOpt.get().getOrderNumber(),
                        actorUserId,
                        actorRole
                );
            }
            return true;
        }
        return false;
    }

    /**
     * Generate unique order number
     */
    private String generateOrderNumber() {
        String prefix = "ORD";
        String timestamp = String.valueOf(System.currentTimeMillis());
        String random = UUID.randomUUID().toString().substring(0, 8).toUpperCase();
        return prefix + "-" + timestamp.substring(timestamp.length() - 8) + "-" + random;
    }

    /**
     * Convert Order entity to DTO
     */
    private OrderDTO convertToDTO(Order order) {
        OrderDTO dto = new OrderDTO();
        dto.setId(order.getId());
        dto.setOrderNumber(order.getOrderNumber());
        dto.setUserId(order.getUserId());
        dto.setSupplierId(order.getSupplierId());
        dto.setOrderType(order.getOrderType());
        dto.setStatus(order.getStatus());
        dto.setTotalAmount(order.getTotalAmount());
        dto.setNotes(order.getNotes());
        dto.setOrderDate(order.getOrderDate());
        dto.setExpectedDeliveryDate(order.getExpectedDeliveryDate());
        dto.setActualDeliveryDate(order.getActualDeliveryDate());
        dto.setApprovedBy(order.getApprovedBy());
        dto.setApprovedAt(order.getApprovedAt());
        
        List<OrderItemDTO> orderItemDTOs = order.getOrderItems().stream()
                .map(this::convertToOrderItemDTO)
                .collect(Collectors.toList());
        dto.setOrderItems(orderItemDTOs);
        
        return dto;
    }

    /**
     * Convert OrderItem entity to DTO
     */
    private OrderItemDTO convertToOrderItemDTO(OrderItem item) {
        OrderItemDTO dto = new OrderItemDTO();
        dto.setId(item.getId());
        dto.setProductId(item.getProductId());
        dto.setProductName(item.getProductName());
        dto.setProductSku(item.getProductSku());
        dto.setQuantity(item.getQuantity());
        dto.setUnitPrice(item.getUnitPrice());
        dto.setSubtotal(item.getSubtotal());
        return dto;
    }

    /**
     * Convert DTO to OrderItem entity
     */
    private OrderItem convertToOrderItemEntity(OrderItemDTO dto) {
        OrderItem item = new OrderItem();
        item.setProductId(dto.getProductId());
        item.setQuantity(dto.getQuantity());
        item.setUnitPrice(dto.getUnitPrice());
        return item;
    }
}
