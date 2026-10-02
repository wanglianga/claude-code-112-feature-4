package com.citylaundry.repo;

import com.citylaundry.model.PickupRecord;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PickupRecordRepo extends JpaRepository<PickupRecord, Long> {
    List<PickupRecord> findByOrderIdOrderByPickedAtAsc(Long orderId);
    Optional<PickupRecord> findFirstByOrderIdOrderByPickedAtAsc(Long orderId);
}
