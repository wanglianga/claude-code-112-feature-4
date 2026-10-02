package com.citylaundry.repo;

import com.citylaundry.model.PaymentOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface PaymentOrderRepo extends JpaRepository<PaymentOrder, Long> {
    Optional<PaymentOrder> findByPaymentNo(String paymentNo);
    Optional<PaymentOrder> findByOrderId(Long orderId);
}
