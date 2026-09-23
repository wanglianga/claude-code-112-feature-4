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
        CANCELLED
    }

    /** 单件衣物状态（每件衣物独立流转，互不混淆） */
    public enum GarmentStatus {
        REGISTERED,          // 已登记
        PHOTOGRAPHED,        // 收衣拍照完成
        CONFIRMED,           // 顾客确认
        AT_FACTORY,          // 已入厂
        PROCESS_ASSIGNED,    // 已分配工艺
        IN_PROCESS,          // 洗护中
        PROCESS_DONE,        // 洗护完成（含复拍）
        IN_REVIEW,           // 高价值专人复核中
        READY_FOR_DELIVERY,  // 待交付
        DELIVERED,           // 已交付
        CLOSED,              // 售后期结束关闭
        REJECTED             // 拒洗
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
        DELIVERY        // 交付留影
    }

    /** 异常类型 */
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
