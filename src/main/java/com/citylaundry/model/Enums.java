package com.citylaundry.model;

/** 全局枚举定义 */
public final class Enums {
    private Enums() {}

    /** 角色：顾客/收衣员(骑手)/工厂/客服/配送/财务/门店经理 */
    public enum Role { CUSTOMER, PICKUP, FACTORY, CS, DELIVERY, FINANCE, STORE_MANAGER }

    /** 订单类型：普通单 / 团体工服单 */
    public enum OrderType { NORMAL, GROUP_WORKWEAR }

    /**
     * 订单级状态：异常报价待决时订单停留在 EXCEPTION_PENDING；
     * 结算关单走 SETTLING → CLOSED；证据/确认/质检缺项时进入 MANUAL_REVIEW。
     */
    public enum OrderStatus {
        CREATED,             // 已下单
        PICKED_UP,           // 骑手已取件（取洗档案建立）
        STORE_QC,            // 门店收件质检中
        CONFIRMED,           // 顾客已确认，送洗护工厂
        EXCEPTION_PENDING,   // 存在待客户决策的异常报价
        IN_FACTORY,          // 工厂洗护中
        READY_FOR_DELIVERY,  // 全部衣物就绪待交付
        DELIVERED,           // 已交付
        SETTLING,            // 关单结算校验中
        MANUAL_REVIEW,       // 缺项阻断自动结算，转人工队列
        CLOSED,              // 已关单（档案封存）
        CANCELLED
    }

    /**
     * 单件衣物状态机（每件衣物独立流转）。
     * 异常证据触发的所有状态都挂在衣物级：EXCEPTION_PENDING / REJECTED / BASIC_ONLY /
     * RECOVERY_SCHEDULED / RECOVERED，保证同订单多件衣物互不阻塞、互不混淆。
     */
    public enum GarmentStatus {
        REGISTERED,          // 已登记
        PHOTOGRAPHED,        // 骑手拍照完成
        PICKED_UP,           // 骑手已取走（在途至门店）
        STORE_QC,            // 门店收件质检中
        QC_MISMATCH,         // 门店质检发现材质/配饰等不符，待建异常
        EXCEPTION_PENDING,   // 异常已建，等待规则报价/客户决策
        AWAIT_QUOTE,         // 异常已建，等待门店/系统生成报价轮次
        AWAIT_CUSTOMER,      // 报价已生成，等待客户确认/拒绝/复核
        AWAIT_MANUAL_REVIEW, // 客户申请人工复核
        QUOTE_CONFIRMED,     // 客户确认追加报价（允许排产）
        BASIC_ONLY,          // 客户选择仅基础处理
        REJECTED,            // 拒绝承接（待骑手回收）
        RECOVERY_SCHEDULED,  // 已生成骑手回收任务
        RECOVERED,           // 骑手已回收退回
        CONFIRMED,           // 顾客确认，可送洗护工厂
        AT_FACTORY,          // 已入厂
        PROCESS_ASSIGNED,    // 已分配工艺
        IN_PROCESS,          // 洗护中
        PROCESS_DONE,        // 洗护完成（含复拍）
        IN_REVIEW,           // 高价值专人复核中
        READY_FOR_DELIVERY,  // 待交付
        DELIVERED,           // 已交付
        CLOSED,              // 档案关闭
        RETURNED             // 订单维度退回客户，未洗护
    }

    /** 洗护工艺：干洗/水洗/皮具护理/奢侈品护理/拒洗/仅基础处理 */
    public enum ProcessType { DRY_CLEAN, WET_CLEAN, LEATHER_CARE, LUXURY_CARE, REJECTED, BASIC }

    /** 风险等级 */
    public enum RiskLevel { LOW, MEDIUM, HIGH }

    /** 照片阶段 */
    public enum PhotoStage {
        PICKUP_FRONT,   // 骑手取件-正面
        PICKUP_BACK,    // 骑手取件-反面
        STAIN,          // 污渍
        CARE_LABEL,     // 洗标
        ACCESSORY,      // 配饰
        EXCEPTION,      // 异常原始照片
        RESHOOT,        // 同件衣物多次补拍
        STORE_QC,       // 门店质检照片
        FACTORY_RESHOOT,// 工厂复拍
        SECOND_PHOTO,   // 高价值复核二次拍照
        PACKAGING,      // 包装确认
        DELIVERY        // 交付/取回留影
    }

    /** 异常类别（本任务定义的四类触发情形） */
    public enum ExceptionCategory {
        MATERIAL_MISMATCH,  // 材质与订单不符
        HIGH_RISK_STAIN,    // 高风险污渍
        ACCESSORY_MISSING,  // 配件缺失
        PERMANENT_FLAW,     // 无法去除的瑕疵
        OTHER
    }

    /** 异常发现来源 */
    public enum ExceptionSource { RIDER, STORE, FACTORY, CUSTOMER, SYSTEM }

    /** 异常严重度 */
    public enum Severity { LOW, MEDIUM, HIGH }

    /** 异常项状态 */
    public enum ExceptionStatus {
        OPEN,                // 已创建待处理
        ASSESSED,            // 已研判（风险/建议）
        QUOTED,              // 已生成候选报价
        PENDING_CUSTOMER,    // 等待客户决策
        MANUAL_REVIEW,       // 客户申请人工复核中
        CONFIRMED_SURCHARGE, // 客户确认追加报价
        RESOLVED_KEEP_PRICE, // 维持原价继续
        BASIC_ONLY,          // 仅基础处理
        REJECTED,            // 拒绝承接
        WITHDRAWN,           // 客户确认后撤回（等待重新决策）
        CLOSED
    }

    /** 报价方案候选（规则引擎生成） */
    public enum QuoteOptionType {
        KEEP_ORIGINAL_PRICE, // 维持原价
        SURCHARGE,           // 追加报价
        REJECT_ORDER,        // 拒绝承接
        BASIC_TREATMENT_ONLY // 仅基础处理
    }

    /** 报价轮次状态（同一异常可有多个版本，历史版本永不覆盖） */
    public enum QuoteRoundStatus {
        DRAFT,               // 规则已生成候选，门店可调整
        ISSUED,              // 已下发客户
        SUPERSEDED,          // 被新版本取代（补拍/分歧/撤回后重新报价）
        CONFIRMED,           // 客户确认
        REJECTED,            // 客户拒绝
        DISPUTED             // 客户申请人工复核
    }

    /** 客户反馈动作 */
    public enum CustomerAction {
        CONFIRM,   // 确认追加报价
        REJECT_REFUND,    // 拒绝 → 原路退回
        REJECT_CONTINUE,  // 拒绝 → 按原服务继续（维持原价/基础处理）
        MANUAL_REVIEW,    // 申请人工复核
        WITHDRAW  // 确认后撤回（限排产前/支付未请款）
    }

    /** 人工复核结论 */
    public enum ManualReviewResult {
        PENDING,
        UPHOLD_SURCHARGE,   // 维持追加报价
        REDUCE_SURCHARGE,   // 下调追加金额（生成新版本报价）
        KEEP_ORIGINAL_PRICE,// 维持原价
        BASIC_ONLY,         // 仅基础处理
        REJECT_ORDER        // 拒绝承接
    }

    /** 支付单类型 */
    public enum PaymentType { PREAUTH, CAPTURE, REFUND, COMPENSATION }

    /** 支付单/支付事件状态 */
    public enum PaymentStatus {
        CREATED,   // 已创建
        PREAUTHED, // 预授权成功（资金冻结）
        CAPTURED,  // 请款成功
        FAILED,    // 回调失败
        RETRYING,  // 重试中
        REFUNDED,  // 已退款
        CANCELLED, // 预授权撤销
        COMPENSATED
    }

    /** 骑手回收任务状态（幂等重派） */
    public enum RecoveryStatus { PENDING, DISPATCHED, DONE, CANCELLED }

    /** 责任归属（档案责任判定，区别于赔付案责任字段） */
    public enum Responsibility {
        PENDING, CUSTOMER, RIDER, STORE, FACTORY, LOGISTICS, INSURANCE, SHARED
    }

    /** 关单校验项 */
    public enum CheckItemType {
        EVIDENCE,          // 异常证据齐全
        CUSTOMER_CONFIRM,  // 客户确认
        QC_CONCLUSION,     // 门店质检结论
        PACKAGING,         // 包装记录
        RETURN_RECORD      // 取回记录
    }

    /** 关单校验结论 */
    public enum CheckStatus { PASS, MISSING }

    /** 结算状态 */
    public enum SettlementStatus { PENDING, BLOCKED_MANUAL, SETTLED, VOID }

    /** 历史遗留枚举（异常工单 / 赔付 / 高价值复核 / 售后） */
    public enum ExceptionType {
        MISSING_CARE_LABEL,   // 洗标缺失
        STAIN_NOT_REMOVABLE,  // 污渍无法去除
        SHRINKAGE,            // 衣物缩水
        ACCESSORY_LOST,       // 配饰丢失
        ADDRESS_CHANGE,       // 客户临时改地址
        FACTORY_DELAY,        // 工厂延期
        DAMAGE_DISPUTE        // 顾客质疑新破损
    }

    /** 异常工单状态 */
    public enum TicketStatus { OPEN, PROCESSING, RESOLVED, CLOSED }

    /** 赔付状态 */
    public enum CompensationStatus { PENDING, APPROVED, INSURANCE_FILED, PAID, REJECTED, REVIEWED }

    /** 高价值复核任务状态 */
    public enum ReviewStatus { PENDING, PASSED }

    /** 售后记录状态 */
    public enum AfterSalesStatus { ACTIVE, CLAIMED, EXPIRED }
}
