package com.citylaundry.web;

import com.citylaundry.dto.ApiDtos.RecoveryCompleteRequest;
import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.RecoveryTask;
import com.citylaundry.model.UserAccount;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.RecoveryService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/** 骑手回收（拒绝承接/客户退回）：重派幂等，完成需交接留影 */
@RestController
@RequestMapping("/api/recovery")
public class RecoveryController {
    private final AuthService auth;
    private final RecoveryService service;

    public RecoveryController(AuthService auth, RecoveryService service) {
        this.auth = auth;
        this.service = service;
    }

    /** 骑手自己的待回收任务 */
    @GetMapping("/tasks")
    public List<RecoveryTask> myTasks(@RequestHeader("X-Auth-Token") String token) {
        UserAccount u = auth.requireRole(token, Role.PICKUP, Role.CS, Role.STORE_MANAGER);
        if (u.getRole() == Role.PICKUP) return service.riderTasks(u.getId());
        return service.openTasks();
    }

    /** 完成回收（必须交接留影）；重复完成幂等 */
    @PostMapping("/tasks/{taskId}/complete")
    public RecoveryTask complete(@RequestHeader("X-Auth-Token") String token,
                                 @PathVariable Long taskId,
                                 @RequestBody RecoveryCompleteRequest req) {
        UserAccount u = auth.requireRole(token, Role.PICKUP);
        return service.complete(u, taskId, req.handoverPhotoUrl());
    }
}
