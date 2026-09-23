package com.citylaundry.repo;

import com.citylaundry.model.Enums.OrderStatus;
import com.citylaundry.model.LaundryOrder;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LaundryOrderRepo extends JpaRepository<LaundryOrder, Long> {
    Optional<LaundryOrder> findByOrderNo(String orderNo);
    List<LaundryOrder> findByCustomerIdOrderByCreatedAtDesc(Long customerId);
    List<LaundryOrder> findByStatusOrderByCreatedAtAsc(OrderStatus status);
    List<LaundryOrder> findByStoreIdOrderByCreatedAtDesc(Long storeId);
}
