package com.citylaundry.repo;

import com.citylaundry.model.CustomerFeedback;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface CustomerFeedbackRepo extends JpaRepository<CustomerFeedback, Long> {
    List<CustomerFeedback> findByExceptionIdOrderByCreatedAtAsc(Long exceptionId);
    List<CustomerFeedback> findByOrderIdOrderByCreatedAtAsc(Long orderId);
    List<CustomerFeedback> findByItemIdOrderByCreatedAtAsc(Long itemId);
}
