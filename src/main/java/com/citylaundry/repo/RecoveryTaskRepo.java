package com.citylaundry.repo;

import com.citylaundry.model.Enums.RecoveryStatus;
import com.citylaundry.model.RecoveryTask;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RecoveryTaskRepo extends JpaRepository<RecoveryTask, Long> {
    Optional<RecoveryTask> findByTaskNo(String taskNo);
    Optional<RecoveryTask> findByIdempotencyKey(String idempotencyKey);
    List<RecoveryTask> findByItemIdOrderByCreatedAtAsc(Long itemId);
    List<RecoveryTask> findByRiderIdAndStatusOrderByCreatedAtAsc(Long riderId, RecoveryStatus status);
    List<RecoveryTask> findByStatusOrderByCreatedAtAsc(RecoveryStatus status);
}
