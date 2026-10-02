package com.citylaundry.web;

import com.citylaundry.security.AuthService;
import com.citylaundry.service.ArchiveService;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * 可审计取洗档案：衣物明细 / 异常证据 / 报价决策 / 客户反馈 / 履约状态 /
 * 支付退款 / 责任归属，同一份事实供客服、门店、客户核对。
 */
@RestController
@RequestMapping("/api/archive")
public class ArchiveController {
    private final AuthService auth;
    private final ArchiveService archiveService;

    public ArchiveController(AuthService auth, ArchiveService archiveService) {
        this.auth = auth;
        this.archiveService = archiveService;
    }

    /** 单件衣物取洗档案（JSON） */
    @GetMapping("/items/{itemId}")
    public Map<String, Object> garment(@RequestHeader("X-Auth-Token") String token,
                                       @PathVariable Long itemId) {
        auth.require(token);
        return archiveService.garmentArchive(itemId);
    }

    /** 订单取洗档案（逐件聚合，JSON） */
    @GetMapping("/orders/{orderId}")
    public Map<String, Object> order(@RequestHeader("X-Auth-Token") String token,
                                     @PathVariable Long orderId) {
        auth.require(token);
        return archiveService.orderArchive(orderId);
    }
}
