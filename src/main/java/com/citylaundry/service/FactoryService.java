package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/** 工厂：入厂、分配工艺、洗护、复拍、多站点流转、拒洗 */
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

    public FactoryService(GarmentItemRepo itemRepo, LaundryOrderRepo orderRepo,
                          ProcessRecordRepo processRepo, TransferRecordRepo transferRepo,
                          FactorySiteRepo siteRepo, GarmentPhotoRepo photoRepo,
                          HighValueReviewRepo reviewRepo, OrderService orderService,
                          TicketService ticketService) {
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.processRepo = processRepo;
        this.transferRepo = transferRepo;
        this.siteRepo = siteRepo;
        this.photoRepo = photoRepo;
        this.reviewRepo = reviewRepo;
        this.orderService = orderService;
        this.ticketService = ticketService;
    }

    /** 衣物入厂：登记到达站点，生成交接记录。仅质检/客户决策已放行的衣物可入厂 */
    @Transactional
    public GarmentItem receiveAtSite(UserAccount factoryUser, Long itemId, Long siteId) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.CONFIRMED
                && item.getStatus() != GarmentStatus.QUOTE_CONFIRMED
                && item.getStatus() != GarmentStatus.BASIC_ONLY) {
            throw ApiException.conflict("衣物未完成质检/客户确认，不能入厂: " + item.getStatus());
        }
        FactorySite site = siteRepo.findById(siteId).orElseThrow(() -> ApiException.notFound("工厂站点不存在"));
        item.setStatus(GarmentStatus.AT_FACTORY);
        item.setCurrentSiteId(site.getId());
        GarmentItem saved = itemRepo.save(item);

        TransferRecord tr = new TransferRecord();
        tr.setItemId(item.getId());
        tr.setFromSiteId(null);
        tr.setToSiteId(site.getId());
        tr.setOperatorId(factoryUser.getId());
        tr.setNote("衣物入厂交接至站点[" + site.getName() + "]");
        transferRepo.save(tr);

        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (order.getStatus() == OrderStatus.CONFIRMED
                || order.getStatus() == OrderStatus.STORE_QC
                || order.getStatus() == OrderStatus.EXCEPTION_PENDING) {
            order.setStatus(OrderStatus.IN_FACTORY);
            orderRepo.save(order);
        }
        return saved;
    }

    /**
     * 分配洗护工艺：根据材质、洗标、污渍类型、风险等级判定
     * 干洗 / 水洗 / 皮具护理 / 奢侈品护理；洗标缺失自动生成异常工单。
     */
    @Transactional
    public ProcessRecord assignProcess(UserAccount factoryUser, Long itemId, ProcessType manual) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.AT_FACTORY) {
            throw ApiException.conflict("衣物未入厂，无法分配工艺: " + item.getStatus());
        }
        ProcessType type = manual != null ? manual
                : (item.getProcessType() == ProcessType.BASIC ? ProcessType.BASIC : autoJudge(item));
        if (type == ProcessType.REJECTED) {
            throw ApiException.badRequest("拒洗请使用拒洗接口并填写原因");
        }
        if (!item.isCareLabelPresent()) {
            ticketService.raiseSystemTicket(item, ExceptionType.MISSING_CARE_LABEL,
                    "衣物[" + item.getItemCode() + "]洗标缺失，工厂按材质与经验分配工艺，需客服跟进顾客告知");
        }
        item.setProcessType(type);
        item.setStatus(GarmentStatus.PROCESS_ASSIGNED);
        itemRepo.save(item);

        ProcessRecord record = new ProcessRecord();
        record.setItemId(item.getId());
        record.setSiteId(item.getCurrentSiteId());
        record.setProcessType(type);
        record.setOperatorId(factoryUser.getId());
        record.setNote((manual != null ? "工厂指定工艺" : "系统按材质/洗标/污渍/风险自动判定工艺") + ": " + type);
        return processRepo.save(record);
    }

    /** 自动工艺判定：客户确认材质不符后以 confirmedMaterial 为准 */
    private ProcessType autoJudge(GarmentItem item) {
        if (item.isLuxury()) return ProcessType.LUXURY_CARE;
        String material = item.getConfirmedMaterial() != null && !item.getConfirmedMaterial().isBlank()
                ? item.getConfirmedMaterial()
                : (item.getMaterial() == null ? "" : item.getMaterial());
        if (OrderService.containsAny(material, "皮革", "皮草", "PU")) return ProcessType.LEATHER_CARE;
        if (OrderService.containsAny(material, "真丝", "羊绒", "羊毛")) return ProcessType.DRY_CLEAN;
        if (item.getStains() != null && !item.getStains().isBlank()) return ProcessType.DRY_CLEAN;
        return ProcessType.WET_CLEAN;
    }

    /** 开始洗护 */
    @Transactional
    public GarmentItem startProcess(Long itemId) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.PROCESS_ASSIGNED) {
            throw ApiException.conflict("衣物未分配工艺，无法开始洗护: " + item.getStatus());
        }
        item.setStatus(GarmentStatus.IN_PROCESS);
        return itemRepo.save(item);
    }

    /**
     * 完成洗护：必须上传复拍照片；高价值衣物自动进入专人复核流程。
     */
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

        List<ProcessRecord> records = processRepo.findByItemIdOrderByStartedAtAsc(item.getId());
        if (!records.isEmpty()) {
            ProcessRecord last = records.get(records.size() - 1);
            last.setFinishedAt(LocalDateTime.now());
            if (note != null && !note.isBlank()) last.setNote(last.getNote() + " | 完成备注: " + note);
            processRepo.save(last);
        }

        if (orderService.isHighValue(item)) {
            item.setStatus(GarmentStatus.IN_REVIEW);
            itemRepo.save(item);
            HighValueReview review = new HighValueReview();
            review.setItemId(item.getId());
            review.setReviewerId(factoryUser.getId());
            review.setStatus(ReviewStatus.PENDING);
            review.setNote("高价值衣物（奢侈品或声明价值≥阈值），交付前须完成二次拍照与包装确认");
            reviewRepo.save(review);
        } else {
            item.setStatus(GarmentStatus.READY_FOR_DELIVERY);
            itemRepo.save(item);
        }
        maybeOrderReady(item.getOrderId());
        return item;
    }

    /** 拒洗：填写原因，衣物退回 */
    @Transactional
    public GarmentItem reject(UserAccount factoryUser, Long itemId, String reason) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.AT_FACTORY && item.getStatus() != GarmentStatus.PROCESS_ASSIGNED) {
            throw ApiException.conflict("当前状态不可拒洗: " + item.getStatus());
        }
        if (reason == null || reason.isBlank()) throw ApiException.badRequest("拒洗必须填写原因");
        item.setProcessType(ProcessType.REJECTED);
        item.setStatus(GarmentStatus.REJECTED);
        itemRepo.save(item);

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

    /** 多站点流转：生成交接记录 */
    @Transactional
    public TransferRecord transfer(UserAccount factoryUser, Long itemId, Long toSiteId, String note) {
        GarmentItem item = getItem(itemId);
        if (item.getStatus() != GarmentStatus.AT_FACTORY && item.getStatus() != GarmentStatus.PROCESS_ASSIGNED
                && item.getStatus() != GarmentStatus.IN_PROCESS && item.getStatus() != GarmentStatus.PROCESS_DONE) {
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
        item.setStatus(GarmentStatus.READY_FOR_DELIVERY);
        itemRepo.save(item);
        maybeOrderReady(item.getOrderId());
    }

    private void maybeOrderReady(Long orderId) {
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        boolean allTerminal = items.stream().allMatch(i ->
                i.getStatus() == GarmentStatus.READY_FOR_DELIVERY
                        || i.getStatus() == GarmentStatus.REJECTED
                        || i.getStatus() == GarmentStatus.RETURNED
                        || i.getStatus() == GarmentStatus.RECOVERED);
        if (allTerminal && !items.isEmpty()) {
            LaundryOrder order = orderRepo.findById(orderId).orElseThrow();
            if (order.getStatus() == OrderStatus.IN_FACTORY
                    || order.getStatus() == OrderStatus.EXCEPTION_PENDING) {
                order.setStatus(OrderStatus.READY_FOR_DELIVERY);
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
