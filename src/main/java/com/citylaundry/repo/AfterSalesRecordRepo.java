package com.citylaundry.repo;

import com.citylaundry.model.AfterSalesRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AfterSalesRecordRepo extends JpaRepository<AfterSalesRecord, Long> {
    List<AfterSalesRecord> findByOrderId(Long orderId);
    Optional<AfterSalesRecord> findByItemId(Long itemId);
}
