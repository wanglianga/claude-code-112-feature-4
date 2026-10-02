package com.citylaundry.repo;

import com.citylaundry.model.ProductionSchedule;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

public interface ProductionScheduleRepo extends JpaRepository<ProductionSchedule, Long> {
    Optional<ProductionSchedule> findByStoreIdAndScheduleDate(Long storeId, LocalDate date);
    List<ProductionSchedule> findByStoreIdAndScheduleDateBetweenOrderByScheduleDateAsc(
            Long storeId, LocalDate from, LocalDate to);
}
