package com.infosys.inventra.service;

import com.infosys.inventra.model.Alert;
import com.infosys.inventra.model.Order;
import com.infosys.inventra.model.OrderItem;
import com.infosys.inventra.model.Product;
import com.infosys.inventra.repository.AlertRepository;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

@Service
public class AlertService {

    @Autowired
    private AlertRepository alertRepository;

    public List<Alert> getAlertsForRole(String role) {
        return alertRepository.findByForRoleOrForRoleOrderByCreatedAtDesc(role, "ALL");
    }

    public List<Alert> getOpenAlerts() {
        return alertRepository.findByStatusOrderByCreatedAtDesc("OPEN");
    }

    public long getOpenAlertCount() {
        return alertRepository.countByStatus("OPEN");
    }

    public Optional<Alert> acknowledgeAlert(Long id) {
        Optional<Alert> alertOpt = alertRepository.findById(id);
        if (alertOpt.isEmpty()) {
            return Optional.empty();
        }

        Alert alert = alertOpt.get();
        if ("OPEN".equals(alert.getStatus())) {
            alert.setStatus("ACKNOWLEDGED");
        }

        return Optional.of(alertRepository.save(alert));
    }

    public Optional<Alert> resolveAlert(Long id) {
        Optional<Alert> alertOpt = alertRepository.findById(id);
        if (alertOpt.isEmpty()) {
            return Optional.empty();
        }

        Alert alert = alertOpt.get();
        alert.setStatus("RESOLVED");
        return Optional.of(alertRepository.save(alert));
    }

    public void evaluateLowStockAlert(Product product) {
        boolean isLowOrOut = product.getQuantity() <= product.getMinThreshold();

        if (isLowOrOut) {
            if (!alertRepository.existsByProductIdAndStatus(product.getId(), "OPEN")) {
                Alert alert = new Alert();
                alert.setProductId(product.getId());
                alert.setTitle("Stock Alert: " + product.getName());
                alert.setMessage("Product " + product.getSku() + " is at " + product.getQuantity()
                        + " units (threshold: " + product.getMinThreshold() + ").");
                alert.setSeverity(product.getQuantity() == 0 ? "CRITICAL" : "WARNING");
                alert.setStatus("OPEN");
                alert.setForRole("ADMIN");
                alertRepository.save(alert);
            }
            return;
        }

        List<Alert> openProductAlerts = alertRepository.findByProductIdAndStatus(product.getId(), "OPEN");
        for (Alert alert : openProductAlerts) {
            alert.setStatus("RESOLVED");
            alertRepository.save(alert);
        }
    }

    public void createStockRequestAlert(Order order, Long productId) {
    Alert alert = new Alert();

    alert.setTitle("Order Request");
    alert.setMessage("Employee requested a product");
    alert.setAlertType("ORDER");
    alert.setSeverity("MEDIUM");
    alert.setStatus("ACTIVE");

    // 🔥 FIX (NO NULL NOW)
    alert.setProductId(productId);

    alert.setOrderId(order.getId());
    alert.setCreatedAt(LocalDateTime.now());

    alertRepository.save(alert);
}

    public void createApprovalAlert(Order order) {
    for (OrderItem item : order.getOrderItems()) {

        if (item.getProductId() != null) {

            Alert alert = new Alert();

            alert.setTitle("Order Approved");
            alert.setMessage("Order " + order.getOrderNumber() + " has been approved");

            alert.setAlertType("ORDER");
            alert.setSeverity("INFO");
            alert.setStatus("OPEN");
            alert.setForRole("EMPLOYEE"); // or ADMIN if needed

            // 🔥 CRITICAL FIX
            alert.setProductId(item.getProductId());

            alert.setOrderId(order.getId());

            alertRepository.save(alert);
        }
    }
}
}
