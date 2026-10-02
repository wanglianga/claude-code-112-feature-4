package com.citylaundry.repo;

import com.citylaundry.model.Enums.ExceptionStatus;
import com.citylaundry.model.GarmentException;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GarmentExceptionRepo extends JpaRepository<GarmentException, Long> {
    Optional<GarmentException> findByExceptionNo(String exceptionNo);
    List<GarmentException> findByItemIdOrderByCreatedAtAsc(Long itemId);
    List<GarmentException> findByOrderIdOrderByCreatedAtAsc(Long orderId);
    List<GarmentException> findByStatusIn(List<ExceptionStatus> statuses);
    List<GarmentException> findByItemIdAndStatusIn(Long itemId, List<ExceptionStatus> statuses);
    List<GarmentException> findByItemIdAndCategoryAndStatusIn(Long itemId,
            com.citylaundry.model.Enums.ExceptionCategory category, List<ExceptionStatus> statuses);
}
