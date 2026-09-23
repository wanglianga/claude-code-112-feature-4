package com.citylaundry.repo;

import com.citylaundry.model.Enums.GarmentStatus;
import com.citylaundry.model.GarmentItem;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface GarmentItemRepo extends JpaRepository<GarmentItem, Long> {
    Optional<GarmentItem> findByItemCode(String itemCode);
    List<GarmentItem> findByOrderIdOrderByIdAsc(Long orderId);
    List<GarmentItem> findByStatusOrderByIdAsc(GarmentStatus status);
    List<GarmentItem> findByCurrentSiteIdAndStatusOrderByIdAsc(Long siteId, GarmentStatus status);
}
