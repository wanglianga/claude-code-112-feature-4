package com.citylaundry.repo;

import com.citylaundry.model.StoreQcRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface StoreQcRecordRepo extends JpaRepository<StoreQcRecord, Long> {
    Optional<StoreQcRecord> findByItemId(Long itemId);
    List<StoreQcRecord> findByOrderId(Long orderId);
}
