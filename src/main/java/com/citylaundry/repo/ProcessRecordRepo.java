package com.citylaundry.repo;

import com.citylaundry.model.ProcessRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ProcessRecordRepo extends JpaRepository<ProcessRecord, Long> {
    List<ProcessRecord> findByItemIdOrderByStartedAtAsc(Long itemId);
}
