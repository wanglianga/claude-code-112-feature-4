package com.citylaundry.repo;

import com.citylaundry.model.RiderReclaim;
import com.citylaundry.model.Enums.ReclaimStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RiderReclaimRepo extends JpaRepository<RiderReclaim, Long> {
    Optional<RiderReclaim> findByReclaimNo(String reclaimNo);
    List<RiderReclaim> findByStatusOrderByCreatedAtAsc(ReclaimStatus status);
    Optional<RiderReclaim> findByItemIdAndStatusIn(Long itemId, List<ReclaimStatus> statuses);
}
