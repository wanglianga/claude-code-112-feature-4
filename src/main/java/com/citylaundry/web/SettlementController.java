package com.citylaundry.web;

import com.citylaundry.model.Enums.Role;
import com.citylaundry.model.SettlementBlock;
import com.citylaundry.security.AuthService;
import com.citylaundry.service.SettlementService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 关单：关单前校验证据/客户确认/质检结论/包装/取回/支付，缺项阻止自动结算并转人工队列。
 */
@RestController
@RequestMapping("/api/settlement")
public class SettlementController {
    private final AuthService auth;
    private final SettlementService settlementService;

    public SettlementController(AuthService auth, SettlementService settlementService) {
        this.auth = auth;
        this.settlementService = settlementService;
    }

    /** 单件关单校验（不改变状态，返回缺项明细） */
    @GetMapping("/items/{itemId}/check")
    public Map<String, Object> checkItem(@RequestHeader("X-Auth-Token") String token,
                                         @PathVariable Long itemId) {
        auth.require(token);
        return settlementService.checkItem(itemId);
    }

    /** 执行订单关单（自动结算）；不传 orderId 则扫描全部已交付/部分退回订单 */
    @PostMapping("/close")
    public Map<String, Object> close(@RequestHeader("X-Auth-Token") String token,
                                     @RequestBody(required = false) Map<String, Long> body) {
        auth.requireRole(token, Role.STORE_MANAGER, Role.CS, Role.FINANCE);
        Long orderId = body == null ? null : body.get("orderId");
        return settlementService.closeOrder(orderId, Long.valueOf(0));
    }

    /** 人工队列：未解决的关单阻断项 */
    @GetMapping("/manual-queue")
    public List<SettlementBlock> manualQueue(@RequestHeader("X-Auth-Token") String token) {
        auth.requireRole(token, Role.CS, Role.STORE_MANAGER, Role.FINANCE);
        return settlementService.manualQueue();
    }

    /** 客服跟进阻断项（记录处理人/备注） */
    @PostMapping("/blocks/{blockId}/handle")
    public SettlementBlock handle(@RequestHeader("X-Auth-Token") String token,
                                  @PathVariable Long blockId,
                                  @RequestBody Map<String, String> body) {
        var u = auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        return settlementService.handleBlock(u, blockId, body.get("note"));
    }

    /** 材料补齐、复检通过后解除阻断（仍校验缺项是否真的补齐） */
    @PostMapping("/blocks/{blockId}/resolve")
    public SettlementBlock resolve(@RequestHeader("X-Auth-Token") String token,
                                   @PathVariable Long blockId,
                                   @RequestBody Map<String, String> body) {
        var u = auth.requireRole(token, Role.CS, Role.STORE_MANAGER);
        return settlementService.resolveBlock(u, blockId, body.get("note"));
    }
}
