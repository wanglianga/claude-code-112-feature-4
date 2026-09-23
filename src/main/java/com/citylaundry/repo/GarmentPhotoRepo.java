package com.citylaundry.repo;

import com.citylaundry.model.Enums.PhotoStage;
import com.citylaundry.model.GarmentPhoto;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface GarmentPhotoRepo extends JpaRepository<GarmentPhoto, Long> {
    List<GarmentPhoto> findByItemIdOrderByCreatedAtAsc(Long itemId);
    List<GarmentPhoto> findByItemIdAndStage(Long itemId, PhotoStage stage);
}
