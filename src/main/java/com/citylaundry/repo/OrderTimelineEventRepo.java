package com.citylaundry.repo;

import com.citylaundry.model.OrderTimelineEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface OrderTimelineEventRepo extends JpaRepository<OrderTimelineEvent, Long> {
    List<OrderTimelineEvent> findByOrderIdOrderByIdAsc(Long orderId);
    List<OrderTimelineEvent> findByOrderIdAndItemIdOrderByIdAsc(Long orderId, Long itemId);
}
