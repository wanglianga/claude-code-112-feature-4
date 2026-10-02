package com.citylaundry.repo;

import com.citylaundry.model.Payment;
import com.citylaundry.model.Enums.PaymentIntent;
import com.citylaundry.model.Enums.PaymentStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentRepo extends JpaRepository<Payment, Long> {
    Optional<Payment> findByPaymentNo(String paymentNo);
    Optional<Payment> findByRequestId(String requestId);
    Optional<Payment> findByCallbackId(String callbackId);
    Optional<Payment> findByCaptureIdempotencyKey(String key);
    List<Payment> findByItemIdOrderByCreatedAtAsc(Long itemId);
    List<Payment> findByItemIdAndBizRef(Long itemId, String bizRef);
    List<Payment> findByOrderIdOrderByCreatedAtAsc(Long orderId);
    List<Payment> findByIntentAndStatus(PaymentIntent intent, PaymentStatus status);
}
