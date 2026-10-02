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

    // ===== feature 4-b：取洗档案 / 异常报价 / 支付 / 回收 / 关单 =====

    public record CreateIssueRequest(
            Long itemId,
            String type,            // MATERIAL_MISMATCH/HIGH_RISK_STAIN/ACCESSORY_MISSING/UNREMOVABLE_FLAW/...
            String description,
            String severity,        // LOW/MEDIUM/HIGH
            String suggestion,      // 处理建议
            String actualMaterial,  // 材质不符时的实物材质
            String actualBrand,
            String photoUrl,        // 首张原始证据照片（建议必附）
            LocalDateTime takenAt,  // 拍摄时间
            String location         // 拍摄位置
    ) {}

    public record AddEvidenceRequest(
            String photoUrl,
            LocalDateTime takenAt,
            String location,
            String sourceStage,     // PICKUP/STORE_QC/REVIEW
            String disagreementNote,// 与既有判断不一致的说明（骑手/门店分歧）
            String suggestion,
            Boolean reprice         // 补拍后是否重新生成报价版本，默认 true
    ) {}

    public record DecisionRequest(String action, String note) {}

    public record ReviewVerdictRequest(String outcome, java.math.BigDecimal adjustedExtra, String note) {}

    public record IntakeQcRequest(
            String conclusion,      // PASS/ANOMALY/BASIC_ONLY/REJECT
            String materialCheck, String accessoryCheck, String note
    ) {}

    public record FinalQcRequest(Boolean packagingConfirmed, Boolean handoverConfirmed, String note) {}

    public record ReclaimAssignRequest(Long riderId, String note) {}

    public record ReclaimCompleteRequest(String handoverPhotoUrl, String note) {}

    public record CaptureRequest(String idempotencyKey) {}

    public record PaymentCallbackRequest(String callbackId, Boolean success, String failReason) {}

    public record RefundRequest(String idempotencyKey, java.math.BigDecimal amount) {}
}
