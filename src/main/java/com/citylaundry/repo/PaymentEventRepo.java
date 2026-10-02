package com.citylaundry.repo;

import com.citylaundry.model.PaymentEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PaymentEventRepo extends JpaRepository<PaymentEvent, Long> {
    Optional<PaymentEvent> findByIdempotencyKey(String idempotencyKey);
    List<PaymentEvent> findByPaymentIdOrderByIdAsc(Long paymentId);
    List<PaymentEvent> findByAppliedFalseAndCallbackFailedTrue();
}
