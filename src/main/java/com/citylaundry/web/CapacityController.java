package com.citylaundry.web;

import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.ProductionSchedule;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.CapacityService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 门店洗护产能：查看未来 N 天占用（客户确认追加才占用，拒绝/撤回释放） */
@RestController
@RequestMapping("/api/capacity")
public class CapacityController {
    private final AuthService auth;
    private final CapacityService service;

    public CapacityController(AuthService auth, CapacityService service) {
        this.auth = auth;
        this.service = service;
    }

    @GetMapping("/stores/{storeId}")
    public List<ProductionSchedule> upcoming(@RequestHeader("X-Auth-Token") String token,
                                             @PathVariable Long storeId,
                                             @RequestParam(defaultValue = "7") int days) {
        auth.requireRole(token, Role.STORE_MANAGER, Role.CS, Role.FACTORY);
        return service.upcoming(storeId, Math.min(days, 30));
    }
}
