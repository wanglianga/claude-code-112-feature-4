package com.citylaundry.dto;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 全部请求 DTO 集中定义 */
public final class ApiDtos {
    private ApiDtos() {}

    public record LoginRequest(String username, String password) {}

    public record ItemRequest(
            String type, String brand, String material, String color,
            String stains, String existingDamage, String accessories,
            Boolean careLabelPresent, BigDecimal declaredValue, Boolean luxury
    ) {}

    public record CreateOrderRequest(
            Long storeId,
            String orderType,        // NORMAL / GROUP_WORKWEAR
            Boolean selfPickup,
            String pickupAddress,
            String deliveryAddress,
            LocalDateTime expectedDeliveryAt,
            List<ItemRequest> items
    ) {}

    public record PhotoRequest(String stage, String url) {}

    public record AssignProcessRequest(Long itemId, String processType) {} // processType 可空=自动判定

    public record ItemNoteRequest(Long itemId, String note) {}

    public record FinishProcessRequest(Long itemId, String note, String reshootUrl) {}

    public record TransferRequest(Long itemId, Long toSiteId, String note) {}

    public record RejectRequest(Long itemId, String reason) {}

    public record CreateTicketRequest(
            Long itemId, String type, String description,
            String newAddress, LocalDateTime newExpectedDeliveryAt
    ) {}

    public record CommentRequest(String content) {}

    public record ResolveTicketRequest(String resolution) {}

    public record CreateCompensationRequest(
            Long ticketId, BigDecimal amount, String responsibility,
            Boolean insuranceClaim, String insurancePolicyNo, String reason
    ) {}

    public record ApproveRequest(Boolean approve, String reason) {}

    public record InsuranceRequest(String policyNo) {}

    public record ReviewCompleteRequest(String note) {}

    public record ClaimRequest(String description) {}

    public record StoreStrategyRequest(Boolean acceptHighRisk, Integer maxHighRiskPerDay) {}

    public record QcMeetingRequest(
            String title, Long storeId, LocalDateTime heldAt,
            String conclusion, List<Long> caseIds
    ) {}
}
