package com.citylaundry.repo;

import com.citylaundry.model.StoreQcRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface StoreQcRecordRepo extends JpaRepository<StoreQcRecord, Long> {
    List<StoreQcRecord> findByItemIdOrderByCreatedAtAsc(Long itemId);
    List<StoreQcRecord> findByItemIdAndPhaseOrderByCreatedAtAsc(Long itemId, String phase);
}
