package com.citylaundry.repo;

import com.citylaundry.model.Settlement;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface SettlementRepo extends JpaRepository<Settlement, Long> {
    Optional<Settlement> findByOrderId(Long orderId);
}
