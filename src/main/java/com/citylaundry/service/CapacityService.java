package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.GarmentItem;
import com.citylaundry.model.LaundryOrder;
import com.citylaundry.model.ProductionSchedule;
import com.citylaundry.model.Store;
import com.citylaundry.repo.GarmentItemRepo;
import com.citylaundry.repo.LaundryOrderRepo;
import com.citylaundry.repo.ProductionScheduleRepo;
import com.citylaundry.repo.StoreRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

/**
 * 门店洗护产能：按 门店×日期 槽位占用。
 * 客户确认追加报价 → 占用槽位（允许排产）；拒绝/退回/撤回 → 释放槽位。
 * 占用/释放均幂等（以衣物 productionDate 为准），避免重复出库/重复占用。
 */
@Service
public class CapacityService {
    private final ProductionScheduleRepo scheduleRepo;
    private final StoreRepo storeRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;

    public CapacityService(ProductionScheduleRepo scheduleRepo, StoreRepo storeRepo,
                           GarmentItemRepo itemRepo, LaundryOrderRepo orderRepo) {
        this.scheduleRepo = scheduleRepo;
        this.storeRepo = storeRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
    }

    /** 查询某门店未来 N 天产能占用 */
    @Transactional(readOnly = true)
    public List<ProductionSchedule> upcoming(Long storeId, int days) {
        LocalDate today = LocalDate.now();
        List<ProductionSchedule> rows = scheduleRepo
                .findByStoreIdAndScheduleDateBetweenOrderByScheduleDateAsc(
                        storeId, today, today.plusDays(days));
        List<ProductionSchedule> result = new ArrayList<>(rows);
        Store store = storeRepo.findById(storeId).orElse(null);
        int cap = store == null ? 100 : store.getDailyCapacity();
        for (int d = 0; d <= days; d++) {
            LocalDate date = today.plusDays(d);
            if (result.stream().noneMatch(r -> r.getScheduleDate().equals(date))) {
                ProductionSchedule empty = new ProductionSchedule();
                empty.setStoreId(storeId);
                empty.setScheduleDate(date);
                empty.setOccupied(0);
                empty.setCapacity(cap);
                result.add(empty);
            }
        }
        result.sort((a, b) -> a.getScheduleDate().compareTo(b.getScheduleDate()));
        return result;
    }

    /** 为衣物选择最早可用排产日期并占用（幂等：已排产直接返回原日期） */
    @Transactional
    public LocalDate occupy(GarmentItem item, int etaBufferDays) {
        if (item.getProductionDate() != null) {
            return item.getProductionDate(); // 已占用，幂等
        }
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        Store store = storeRepo.findById(order.getStoreId()).orElseThrow();
        LocalDate date = LocalDate.now().plusDays(Math.max(0, etaBufferDays));
        // 找未来 14 天内第一个有槽位的日期
        ProductionSchedule target = null;
        for (int d = 0; d < 14; d++) {
            LocalDate candidate = date.plusDays(d);
            ProductionSchedule s = slot(store, candidate);
            if (s.getOccupied() < s.getCapacity()) {
                target = s;
                date = candidate;
                break;
            }
        }
        if (target == null) target = slot(store, date);
        target.setOccupied(target.getOccupied() + 1);
        scheduleRepo.save(target);
        item.setProductionDate(date);
        itemRepo.save(item);
        return date;
    }

    /** 释放衣物产能占用（幂等：未排产直接跳过） */
    @Transactional
    public void release(GarmentItem item) {
        if (item.getProductionDate() == null) return;
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        ProductionSchedule s = scheduleRepo
                .findByStoreIdAndScheduleDate(order.getStoreId(), item.getProductionDate())
                .orElse(null);
        if (s != null && s.getOccupied() > 0) {
            s.setOccupied(s.getOccupied() - 1);
            scheduleRepo.save(s);
        }
        item.setProductionDate(null);
        itemRepo.save(item);
    }

    private ProductionSchedule slot(Store store, LocalDate date) {
        return scheduleRepo.findByStoreIdAndScheduleDate(store.getId(), date).orElseGet(() -> {
            ProductionSchedule s = new ProductionSchedule();
            s.setStoreId(store.getId());
            s.setScheduleDate(date);
            s.setOccupied(0);
            s.setCapacity(store.getDailyCapacity());
            return scheduleRepo.save(s);
        });
    }
}
