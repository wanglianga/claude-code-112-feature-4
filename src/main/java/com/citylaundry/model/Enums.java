package com.citylaundry.model;

/** 全局枚举定义 */
public final class Enums {
    private Enums() {}

    /** 角色：顾客/收衣员/工厂/客服/配送/财务/门店经理 */
    public enum Role { CUSTOMER, PICKUP, FACTORY, CS, DELIVERY, FINANCE, STORE_MANAGER }

    /** 订单类型：普通单 / 团体工服单 */
    public enum OrderType { NORMAL, GROUP_WORKWEAR }

    /** 订单状态 */
    public enum OrderStatus {
        CREATED,            // 已下单
        PICKED_UP,          // 收衣员已上门拍照
        CONFIRMED,          // 顾客已确认，送洗护工厂
        IN_FACTORY,         // 工厂洗护中
        READY_FOR_DELIVERY, // 全部衣物就绪待交付
        DELIVERED,          // 已交付
        SETTLED,            // 关单校验通过、自动结算完成
        PARTIALLY_RETURNED, // 部分衣物拒收/拒绝承接，骑手已回收（按原服务继续的衣物正常交付）
        CANCELLED
    }

    /**
     * 单件衣物状态（每件衣物独立状态机，互不混淆）。
     *
     * <p>常规流：REGISTERED → PHOTOGRAPHED → CONFIRMED → AT_FACTORY →
     * PROCESS_ASSIGNED → IN_PROCESS → PROCESS_DONE → IN_REVIEW(高价值) →
     * READY_FOR_DELIVERY → DELIVERED → CLOSED。
     *
     * <p>异常报价分支（AT_FACTORY 阶段质检发现异常时进入）：
     * ANOMALY_QUOTED →（客户确认追加）回到 AT_FACTORY 重新分配工艺排产；
     *               →（拒绝+原路退回）RECLAIM_PENDING → RECLAIMED → RETURNED；
     *               →（申请人工复核）MANUAL_REVIEW → 客服裁决出新版本报价后回到 ANOMALY_QUOTED；
     * 客户确认后洗护前撤回：回到 ANOMALY_QUOTED；洗护开始后不可撤回。
     */
    public enum GarmentStatus {
        REGISTERED,          // 已登记
        PHOTOGRAPHED,        // 收衣拍照完成
        CONFIRMED,           // 顾客确认
        AT_FACTORY,          // 已入厂
        ANOMALY_QUOTED,      // 发现异常：已生成多候选报价，等待客户决策（阻塞排产）
        MANUAL_REVIEW,       // 客户申请人工复核（客服队列）
        EXTRA_APPROVED,      // 客户已确认追加报价，允许进入洗护排产
        PROCESS_ASSIGNED,    // 已分配工艺
        IN_PROCESS,          // 洗护中
        PROCESS_DONE,        // 洗护完成（含复拍）
        IN_REVIEW,           // 高价值专人复核中
        READY_FOR_DELIVERY,  // 待交付
        DELIVERED,           // 已交付
        CLOSED,              // 售后期结束关闭
        REJECTED,            // 拒洗（工厂工艺前置拒洗）
        RECLAIM_PENDING,     // 客户拒绝承接：等待骑手回收，门店释放产能
        RECLAIMED,           // 骑手已取走衣物
        RETURNED             // 已退回客户并完成退款，关单
    }

    /** 洗护工艺：干洗/水洗/皮具护理/奢侈品护理/拒洗 */
    public enum ProcessType { DRY_CLEAN, WET_CLEAN, LEATHER_CARE, LUXURY_CARE, REJECTED }

    /** 风险等级 */
    public enum RiskLevel { LOW, MEDIUM, HIGH }

    /** 照片阶段 */
    public enum PhotoStage {
        PICKUP_FRONT,   // 收衣-正面
        PICKUP_BACK,    // 收衣-反面
        STAIN,          // 污渍
        CARE_LABEL,     // 洗标
        ACCESSORY,      // 配饰
        FACTORY_RESHOOT,// 工厂复拍
        SECOND_PHOTO,   // 高价值复核二次拍照
        PACKAGING,      // 包装确认
        DELIVERY,       // 交付留影
        ANOMALY,        // 异常证据照片（补拍）
        RECLAIM,        // 骑手回收留影
        QC_PACK         // 门店质检包装/取回留影
    }

    /** 异常类型 */
    public enum ExceptionType {
        MISSING_CARE_LABEL,   // 洗标缺失
        STAIN_NOT_REMOVABLE,  // 污渍无法去除
        SHRINKAGE,            // 衣物缩水
        ACCESSORY_LOST,       // 配饰丢失
        ADDRESS_CHANGE,       // 客户临时改地址
        FACTORY_DELAY,        // 工厂延期
        DAMAGE_DISPUTE,       // 顾客质疑新破损
        MATERIAL_MISMATCH,    // 材质与订单不符
        HIGH_RISK_STAIN,      // 高风险污渍
        ACCESSORY_MISSING,    // 配件缺失（取件/质检清点）
        UNREMOVABLE_FLAW      // 无法去除的瑕疵
    }

    /** 异常工单状态 */
    public enum TicketStatus { OPEN, PROCESSING, RESOLVED, CLOSED }

    /** 异常项状态：OPEN（待报价）/ QUOTED（待客户决策）/ REVIEW（人工复核）/ DECIDED（已决策）/ CLOSED */
    public enum AnomalyStatus { OPEN, QUOTED, REVIEW, DECIDED, CLOSED }

    /** 报价方案类型（系统按规则生成的候选方案） */
    public enum QuoteOptionType {
        KEEP_ORIGINAL,  // 维持原价（按原服务/仅基础处理）
        EXTRA_CHARGE,   // 追加报价（客户确认后才允许排产）
        REJECT_ORDER,   // 拒绝承接（原路退回，骑手回收）
        BASIC_ONLY      // 仅基础处理（去除高风险工序，维持原价）
    }

    /** 报价版本状态 */
    public enum QuoteStatus {
        DRAFT,          // 草稿（规则生成，待生效）
        ACTIVE,         // 当前有效版本，等待客户决策
        SUPERSEDED,     // 已被新版本取代（补拍/人工复核裁决后），原判断保留不覆盖
        CONFIRMED,      // 客户确认（追加报价）
        REJECTED,       // 客户拒绝
        EXPIRED         // 客户超时未响应
    }

    /** 客户对报价的动作 */
    public enum CustomerAction {
        CONFIRM_EXTRA,  // 确认追加报价
        REJECT_REFUND,  // 拒绝并原路退回
        REJECT_CONTINUE,// 拒绝追加但按原服务继续（维持原价）
        BASIC_ONLY,     // 仅基础处理（维持原价，去除高风险工序）
        REQUEST_REVIEW, // 申请人工复核
        WITHDRAW        // 确认后撤回（仅洗护未开始时允许）
    }

    /** 客户决策状态 */
    public enum DecisionStatus {
        PENDING,        // 等待客户在时限内响应
        CONFIRMED,      // 已确认追加
        REJECTED_REFUND,// 已拒绝→原路退回
        CONTINUE_ORIGINAL,// 已拒绝追加→按原服务继续
        IN_REVIEW,      // 人工复核中
        WITHDRAWN,      // 客户确认后撤回
        EXPIRED,        // 超时未响应
        SUPERSEDED      // 该决策因新版本报价/撤回而失效（留痕不删除）
    }

    /** 人工复核结论 */
    public enum ReviewOutcome { PENDING, UPHOLD_QUOTE, ADJUST_QUOTE, BASIC_ONLY, REJECT_ORDER }

    /** 支付意图 */
    public enum PaymentIntent { PRE_AUTH, CAPTURE, REFUND, COMPENSATION }

    /** 支付状态：预授权可能失败/释放；扣款/退款有回调 */
    public enum PaymentStatus {
        CREATED, PENDING, AUTHORIZED, CAPTURED, FAILED,
        RELEASED, REFUND_PENDING, REFUNDED, PARTIALLY_REFUNDED
    }

    /** 骑手回收/重派状态 */
    public enum ReclaimStatus {
        PENDING,        // 已生成回收任务，等待骑手接单
        ASSIGNED,       // 已重派骑手
        RECLAIMED,      // 骑手已取走（留影）
        CANCELLED       // 回收任务取消（客户改变主意撤回/按原服务继续）
    }

    /** 门店质检结论（入厂质检，关单前还需复检） */
    public enum QcConclusion { PENDING, PASS, ANOMALY, BASIC_ONLY, REJECT }

    /** 关单校验结果 */
    public enum CloseCheckStatus { PASS, BLOCKED }

    /** 赔付状态 */
    public enum CompensationStatus { PENDING, APPROVED, INSURANCE_FILED, PAID, REJECTED, REVIEWED }

    /** 高价值复核任务状态 */
    public enum ReviewStatus { PENDING, PASSED }

    /** 售后记录状态 */
    public enum AfterSalesStatus { ACTIVE, CLAIMED, EXPIRED }
}
