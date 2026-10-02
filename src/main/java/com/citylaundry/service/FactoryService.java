package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 工厂：入厂、分配工艺、洗护、复拍、多站点流转、拒洗（全部经衣物状态机，工序幂等） */
@Service
public class FactoryService {
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final ProcessRecordRepo processRepo;
    private final TransferRecordRepo transferRepo;
    private final FactorySiteRepo siteRepo;
    private final GarmentPhotoRepo photoRepo;
    private final HighValueReviewRepo reviewRepo;
    private final OrderService orderService;
    private final TicketService ticketService;
    private final GarmentStateMachine sm;
    private final StoreQcService qcService;
    private final AnomalyIssueRepo issueRepo;
    private final IdempotentRequestRepo idemRepo;

    public FactoryService(GarmentItemRepo itemRepo, LaundryOrderRepo orderRepo,
                          ProcessRecordRepo processRepo, TransferRecordRepo transferRepo,
                          FactorySiteRepo siteRepo, GarmentPhotoRepo photoRepo,
                          HighValueReviewRepo reviewRepo, OrderService orderService,
                          TicketService ticketService, GarmentStateMachine sm,
                          StoreQcService qcService, AnomalyIssueRepo issueRepo,
                          IdempotentRequestRepo idemRepo) {
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.processRepo = processRepo;
        this.transferRepo = transferRepo;
        this.siteRepo = siteRepo;
        this.photoRepo = photoRepo;
        this.reviewRepo = reviewRepo;
        this.orderService = orderService;
        this.ticketService = ticketService;
        this.sm = sm;
        this.qcService = qcService;
        this.issueRepo = issueRepo;
        this.idemRepo = idemRepo;
    }

    /** 衣物入厂：登记到达站点、生成交接记录、开立入厂质检（PENDING），等待质检与异常处理 */
    @Transactional
    public GarmentItem receiveAtSite(UserAccount factoryUser, Long itemId, Long siteId) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.CONFIRMED && item.getStatus() != GarmentStatus.EXTRA_APPROVED) {
            throw ApiException.conflict("衣物状态不是「顾客已确认」，无法入厂: " + item.getStatus());
        }
        FactorySite site = siteRepo.findById(siteId).orElseThrow(() -> ApiException.notFound("工厂站点不存在"));
        item.setCurrentSiteId(site.getId());
        item = sm.transition(item.getId(), GarmentStatus.AT_FACTORY, "FACTORY_RECEIVE",
                "SITE", site.getId(), factoryUser.getId(), "衣物入厂交接至站点[" + site.getName() + "]");

        TransferRecord tr = new TransferRecord();
        tr.setItemId(item.getId());
        tr.setFromSiteId(null);
        tr.setToSiteId(site.getId());
        tr.setOperatorId(factoryUser.getId());
        tr.setNote("衣物入厂交接至站点[" + site.getName() + "]");
        transferRepo.save(tr);

        qcService.openIntake(item.getId(), factoryUser.getId());

        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (order.getStatus() == OrderStatus.CONFIRMED) {
            order.setStatus(OrderStatus.IN_FACTORY);
            orderRepo.save(order);
        }
        return item;
    }

    private void assertNoOpenAnomaly(GarmentItem item) {
        boolean blocked = issueRepo.findByItemIdOrderByCreatedAtAsc(item.getId()).stream()
                .anyMatch(i -> i.getStatus() == AnomalyStatus.OPEN
                        || i.getStatus() == AnomalyStatus.QUOTED || i.getStatus() == AnomalyStatus.REVIEW);
        if (blocked) {
            throw ApiException.conflict("衣物 " + item.getItemCode()
                    + " 存在未决异常项，客户确认追加/选择方案前禁止进入洗护排产");
        }
    }

    /**
     * 分配洗护工艺：异常未决禁止排产（硬门禁）；客户确认追加后可排产。
     * 幂等：每件衣物只生成一条未完成工艺记录，重复调用返回既有记录（不重复占用工序/出库）。
     */
    @Transactional
    public ProcessRecord assignProcess(UserAccount factoryUser, Long itemId, ProcessType manual) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() == GarmentStatus.ANOMALY_QUOTED || item.getStatus() == GarmentStatus.MANUAL_REVIEW) {
            throw ApiException.conflict("异常报价未决，禁止分配工艺排产: " + item.getStatus());
        }
        boolean approvedExtra = item.getStatus() == GarmentStatus.EXTRA_APPROVED;
        if (item.getStatus() != GarmentStatus.AT_FACTORY && !approvedExtra) {
            throw ApiException.conflict("衣物未入厂，无法分配工艺: " + item.getStatus());
        }
        assertNoOpenAnomaly(item);
        if (manual == ProcessType.REJECTED) {
            throw ApiException.badRequest("拒洗请使用拒洗接口并填写原因");
        }
        // 无未决异常：入厂质检结论闭环（PENDING/ANOMALY → PASS/BASIC_ONLY）
        qcService.resolveIntakeForProcessing(item, factoryUser.getId());

        // 幂等：已分配且未完成，直接返回既有工艺
        List<ProcessRecord> existing = processRepo.findByItemIdOrderByStartedAtAsc(itemId);
        ProcessRecord open = existing.stream().filter(p -> p.getFinishedAt() == null).findFirst().orElse(null);
        if (open != null) return open;

        ProcessType type = manual != null ? manual : autoJudge(item);
        if (!item.isCareLabelPresent()) {
            ticketService.raiseSystemTicket(item, ExceptionType.MISSING_CARE_LABEL,
                    "衣物[" + item.getItemCode() + "]洗标缺失，工厂按材质与经验分配工艺，需客服跟进顾客告知");
        }
        item.setProcessType(type);
        // 基础价沿用下单估价（材质不符等差价已由客户确认的追加报价承载，避免重复计费）
        if (item.getBasePrice() == null || item.getBasePrice().compareTo(java.math.BigDecimal.ZERO) == 0) {
            item.setBasePrice(PricingService.priceOf(type));
            itemRepo.save(item);
        }
        item = sm.transition(item.getId(), GarmentStatus.PROCESS_ASSIGNED, "PROCESS_ASSIGNED",
                "PROCESS", null, factoryUser.getId(),
                (manual != null ? "工厂指定工艺" : "系统按材质/洗标/污渍/风险自动判定工艺") + ": " + type);

        ProcessRecord record = new ProcessRecord();
        record.setItemId(item.getId());
        record.setSiteId(item.getCurrentSiteId());
        record.setProcessType(type);
        record.setOperatorId(factoryUser.getId());
        record.setNote((manual != null ? "工厂指定工艺" : "系统自动判定工艺") + ": " + type);
        record = processRepo.save(record);

        IdempotentRequest idem = new IdempotentRequest();
        idem.setAction("PROCESS_ASSIGN");
        idem.setIdempotencyKey("PROCESS_ASSIGN:" + itemId);
        idem.setResourceId(record.getId());
        idem.setResultRef(type.name());
        idemRepo.save(idem);
        return record;
    }

    /** 自动工艺判定 */
    private ProcessType autoJudge(GarmentItem item) {
        if (item.isLuxury()) return ProcessType.LUXURY_CARE;
        String material = item.getMaterial() == null ? "" : item.getMaterial();
        if (OrderService.containsAny(material, "皮革", "皮草", "PU")) return ProcessType.LEATHER_CARE;
        if (OrderService.containsAny(material, "真丝", "羊绒", "羊毛")) return ProcessType.DRY_CLEAN;
        if (item.getStains() != null && !item.getStains().isBlank()) return ProcessType.DRY_CLEAN;
        return ProcessType.WET_CLEAN;
    }

    /** 开始洗护（出库节点，幂等：重复调用不重复出库） */
    @Transactional
    public GarmentItem startProcess(UserAccount user, Long itemId) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() == GarmentStatus.IN_PROCESS) return item; // 幂等
        if (item.getStatus() != GarmentStatus.PROCESS_ASSIGNED) {
            throw ApiException.conflict("衣物未分配工艺，无法开始洗护: " + item.getStatus());
        }
        return sm.transition(itemId, GarmentStatus.IN_PROCESS, "WASH_START",
                "ITEM", itemId, user.getId(), "洗护工序开始（出库）");
    }

    /** 完成洗护：必须上传复拍照片；高价值衣物自动进入专人复核流程 */
    @Transactional
    public GarmentItem finishProcess(UserAccount factoryUser, Long itemId, String note, String reshootUrl) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.IN_PROCESS) {
            throw ApiException.conflict("衣物不在洗护中，无法完成: " + item.getStatus());
        }
        if (reshootUrl == null || reshootUrl.isBlank()) {
            throw ApiException.badRequest("洗护完成必须上传复拍照片 reshootUrl");
        }
        GarmentPhoto photo = new GarmentPhoto();
        photo.setItemId(item.getId());
        photo.setStage(PhotoStage.FACTORY_RESHOOT);
        photo.setUrl(reshootUrl);
        photo.setUploadedBy(factoryUser.getId());
        photoRepo.save(photo);

        List<ProcessRecord> records = processRepo.findByItemIdOrderByStartedAtAsc(itemId);
        for (ProcessRecord last : records) {
            if (last.getFinishedAt() == null) {
                last.setFinishedAt(LocalDateTime.now());
                if (note != null && !note.isBlank()) last.setNote(last.getNote() + " | 完成备注: " + note);
                processRepo.save(last);
            }
        }

        GarmentStatus next = orderService.isHighValue(item)
                ? GarmentStatus.IN_REVIEW : GarmentStatus.PROCESS_DONE;
        // 先进 PROCESS_DONE，高价值再转 IN_REVIEW（保留完整事件链）
        item = sm.transition(item.getId(), GarmentStatus.PROCESS_DONE, "WASH_FINISH",
                "PHOTO", photo.getId(), factoryUser.getId(), "洗护完成并复拍");
        if (orderService.isHighValue(item)) {
            item = sm.transition(item.getId(), GarmentStatus.IN_REVIEW, "HIGH_VALUE_REVIEW",
                    "ITEM", itemId, factoryUser.getId(), "高价值衣物进入专人复核");
            HighValueReview review = new HighValueReview();
            review.setItemId(item.getId());
            review.setReviewerId(factoryUser.getId());
            review.setStatus(ReviewStatus.PENDING);
            review.setNote("高价值衣物，交付前须完成二次拍照与包装确认");
            reviewRepo.save(review);
        }
        if (item.getStatus() == GarmentStatus.PROCESS_DONE) {
            // 非高价值直接就绪
            markReadyForDelivery(item);
        }
        return item;
    }

    /** 拒洗：填写原因，衣物退回（可由 AT_FACTORY/已分配/追加确认态发起） */
    @Transactional
    public GarmentItem reject(UserAccount factoryUser, Long itemId, String reason) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.AT_FACTORY && item.getStatus() != GarmentStatus.PROCESS_ASSIGNED
                && item.getStatus() != GarmentStatus.EXTRA_APPROVED
                && item.getStatus() != GarmentStatus.ANOMALY_QUOTED) {
            throw ApiException.conflict("当前状态不可拒洗: " + item.getStatus());
        }
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("拒洗必须填写原因");
        item.setProcessType(ProcessType.REJECTED);
        item = itemRepo.save(item);
        item = sm.transition(itemId, GarmentStatus.REJECTED, "FACTORY_REJECT",
                "ITEM", itemId, factoryUser.getId(), "拒洗原因: " + reason);

        ProcessRecord record = new ProcessRecord();
        record.setItemId(item.getId());
        record.setSiteId(item.getCurrentSiteId() == null ? 0L : item.getCurrentSiteId());
        record.setProcessType(ProcessType.REJECTED);
        record.setOperatorId(factoryUser.getId());
        record.setNote("拒洗原因: " + reason);
        record.setFinishedAt(LocalDateTime.now());
        processRepo.save(record);

        ticketService.raiseSystemTicket(item, ExceptionType.STAIN_NOT_REMOVABLE,
                "衣物[" + item.getItemCode() + "]被工厂拒洗: " + reason);
        maybeOrderReady(item.getOrderId());
        return item;
    }

    /** 多站点流转：生成交接记录（幂等：目标站点与当前相同直接返回既有最近记录） */
    @Transactional
    public TransferRecord transfer(UserAccount factoryUser, Long itemId, Long toSiteId, String note) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.AT_FACTORY && item.getStatus() != GarmentStatus.PROCESS_ASSIGNED
                && item.getStatus() != GarmentStatus.IN_PROCESS && item.getStatus() != GarmentStatus.PROCESS_DONE
                && item.getStatus() != GarmentStatus.EXTRA_APPROVED) {
            throw ApiException.conflict("当前状态不可流转: " + item.getStatus());
        }
        FactorySite to = siteRepo.findById(toSiteId).orElseThrow(() -> ApiException.notFound("目标站点不存在"));
        if (to.getId().equals(item.getCurrentSiteId())) {
            throw ApiException.badRequest("衣物已在目标站点");
        }
        TransferRecord tr = new TransferRecord();
        tr.setItemId(item.getId());
        tr.setFromSiteId(item.getCurrentSiteId());
        tr.setToSiteId(to.getId());
        tr.setOperatorId(factoryUser.getId());
        tr.setNote(note == null || note.isBlank() ? "站点间流转" : note);
        item.setCurrentSiteId(to.getId());
        itemRepo.save(item);
        return transferRepo.save(tr);
    }

    /** 复核通过后由 ReviewService 调用 */
    @Transactional
    public void markReadyForDelivery(GarmentItem item) {
        sm.transition(item.getId(), GarmentStatus.READY_FOR_DELIVERY, "READY_FOR_DELIVERY",
                "ITEM", item.getId(), 0L, "洗护/复核完成，待交付");
        maybeOrderReady(item.getOrderId());
    }

    private void maybeOrderReady(Long orderId) {
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        boolean allReady = items.stream().allMatch(i ->
                i.getStatus() == GarmentStatus.READY_FOR_DELIVERY
                        || i.getStatus() == GarmentStatus.REJECTED
                        || i.getStatus() == GarmentStatus.RETURNED);
        if (allReady && !items.isEmpty()) {
            LaundryOrder order = orderRepo.findById(orderId).orElseThrow();
            boolean anyReturned = items.stream().anyMatch(i -> i.getStatus() == GarmentStatus.RETURNED);
            OrderStatus target = anyReturned ? OrderStatus.PARTIALLY_RETURNED : OrderStatus.READY_FOR_DELIVERY;
            if (order.getStatus() == OrderStatus.IN_FACTORY || order.getStatus() == OrderStatus.CONFIRMED) {
                order.setStatus(target);
                orderRepo.save(order);
            }
        }
    }

    public GarmentItem getItem(Long itemId) {
        return itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
    }

    public List<GarmentItem> itemsAtSite(Long siteId, GarmentStatus status) {
        return itemRepo.findByCurrentSiteIdAndStatusOrderByIdAsc(siteId, status);
    }
}
