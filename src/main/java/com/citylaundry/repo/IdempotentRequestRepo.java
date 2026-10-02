package com.citylaundry.repo;

import com.citylaundry.model.IdempotentRequest;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface IdempotentRequestRepo extends JpaRepository<IdempotentRequest, Long> {
    Optional<IdempotentRequest> findByActionAndIdempotencyKey(String action, String key);
}
