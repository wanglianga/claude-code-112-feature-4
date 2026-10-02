package com.citylaundry.repo;

import com.citylaundry.model.GarmentStatusEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GarmentStatusEventRepo extends JpaRepository<GarmentStatusEvent, Long> {
    List<GarmentStatusEvent> findByItemIdOrderByIdAsc(Long itemId);
}
