package com.citylaundry.repo;

import com.citylaundry.model.TransferRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TransferRecordRepo extends JpaRepository<TransferRecord, Long> {
    List<TransferRecord> findByItemIdOrderByCreatedAtAsc(Long itemId);
}
