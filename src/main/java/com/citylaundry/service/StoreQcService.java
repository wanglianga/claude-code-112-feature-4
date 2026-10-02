package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 门店质检（逐件，入厂质检 + 关单前复检）。
 * 入厂质检发现异常须先建异常项（结论 ANOMALY），衣物阻塞排产；
 * 关单前复检确认包装照片与取回/交接记录齐全，供关单校验使用。
 */
@Service
public class StoreQcService {
    private final StoreQcRecordRepo qcRepo;
    private final GarmentItemRepo itemRepo;
    private final GarmentPhotoRepo photoRepo;
    private final TransferRecordRepo transferRepo;

    public StoreQcService(StoreQcRecordRepo qcRepo, GarmentItemRepo itemRepo,
                          GarmentPhotoRepo photoRepo, TransferRecordRepo transferRepo) {
        this.qcRepo = qcRepo;
        this.itemRepo = itemRepo;
        this.photoRepo = photoRepo;
        this.transferRepo = transferRepo;
    }

    /** 入厂登记一条 PENDING 质检记录（工厂入厂时自动调用） */
    @Transactional
    public StoreQcRecord openIntake(Long itemId, Long inspectorId) {
        StoreQcRecord qc = new StoreQcRecord();
        qc.setItemId(itemId);
        qc.setPhase("INTAKE");
        qc.setConclusion(QcConclusion.PENDING);
        qc.setInspectorId(inspectorId);
        return qcRepo.save(qc);
    }

    /** 门店/工厂提交入厂质检结论 */
    @Transactional
    public StoreQcRecord recordIntake(UserAccount user, Long itemId, QcConclusion conclusion,
                                      String materialCheck, String accessoryCheck, String note) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        if (item.getStatus() == GarmentStatus.ANOMALY_QUOTED
                || item.getStatus() == GarmentStatus.MANUAL_REVIEW) {
            throw ApiException.conflict("衣物存在未决异常，质检结论应在异常处理完成后给出: " + item.getStatus());
        }
        StoreQcRecord qc = new StoreQcRecord();
        qc.setItemId(itemId);
        qc.setPhase("INTAKE");
        qc.setConclusion(conclusion);
        qc.setMaterialCheck(materialCheck);
        qc.setAccessoryCheck(accessoryCheck);
        qc.setNote(note);
        qc.setInspectorId(user.getId());
        qc = qcRepo.save(qc);

        item.setQcConclusion(conclusion.name());
        itemRepo.save(item);
        return qc;
    }

    /**
     * 关单前最终复检：逐件确认包装照片(PACKAGING)与交接/取回记录齐全。
     * 返回是否通过；不通过时记录具体缺项，供关单校验阻断结算。
     */
    @Transactional
    public StoreQcRecord recordFinal(UserAccount user, Long itemId,
                                     boolean packagingConfirmed, boolean handoverConfirmed, String note) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        List<GarmentPhoto> photos = photoRepo.findByItemIdOrderByCreatedAtAsc(itemId);
        boolean hasPackagingPhoto = photos.stream().anyMatch(p -> p.getStage() == PhotoStage.PACKAGING);
        boolean hasHandover = !transferRepo.findByItemIdOrderByCreatedAtAsc(itemId).isEmpty()
                || photos.stream().anyMatch(p -> p.getStage() == PhotoStage.DELIVERY
                        || p.getStage() == PhotoStage.RECLAIM);

        StoreQcRecord qc = new StoreQcRecord();
        qc.setItemId(itemId);
        qc.setPhase("FINAL");
        qc.setPackagingConfirmed(packagingConfirmed);
        qc.setHandoverConfirmed(handoverConfirmed);
        qc.setNote(note);
        qc.setInspectorId(user.getId());

        boolean returned = item.getStatus() == GarmentStatus.RETURNED;
        boolean packOk = packagingConfirmed && (returned || hasPackagingPhoto);
        boolean handOk = handoverConfirmed && hasHandover;
        qc.setConclusion(packOk && handOk ? QcConclusion.PASS : QcConclusion.ANOMALY);
        qc = qcRepo.save(qc);

        if (qc.getConclusion() != QcConclusion.PASS) {
            StringBuilder miss = new StringBuilder();
            if (!packagingConfirmed) miss.append("包装未确认;");
            if (!returned && !hasPackagingPhoto) miss.append("缺包装照片;");
            if (!handoverConfirmed) miss.append("取回/交接未确认;");
            if (!hasHandover) miss.append("缺交接/取回记录;");
            qc.setNote((note == null ? "" : note + " | ") + "缺项:" + miss);
            qcRepo.save(qc);
        }
        return qc;
    }

    /** 将最近一条入厂质检置为指定结论（不存在则新建），供异常登记/排产闭环调用 */
    @Transactional
    public StoreQcRecord markIntake(Long itemId, QcConclusion conclusion, Long inspectorId, String note) {
        List<StoreQcRecord> list = qcRepo.findByItemIdAndPhaseOrderByCreatedAtAsc(itemId, "INTAKE");
        StoreQcRecord qc = list.isEmpty() ? null : list.get(list.size() - 1);
        if (qc == null) {
            qc = new StoreQcRecord();
            qc.setItemId(itemId);
            qc.setPhase("INTAKE");
            qc.setInspectorId(inspectorId == null ? 0L : inspectorId);
        }
        qc.setConclusion(conclusion);
        if (note != null) qc.setNote(note);
        qc = qcRepo.save(qc);

        GarmentItem item = itemRepo.findById(itemId).orElseThrow();
        item.setQcConclusion(conclusion.name());
        itemRepo.save(item);
        return qc;
    }

    /** 排产前把入厂质检闭环：无未决异常时 PENDING/ANOMALY 结论更新为 PASS（或 BASIC_ONLY） */
    @Transactional
    public StoreQcRecord resolveIntakeForProcessing(GarmentItem item, Long operatorId) {
        QcConclusion target = "BASIC_ONLY".equals(item.getQcConclusion())
                ? QcConclusion.BASIC_ONLY : QcConclusion.PASS;
        return markIntake(item.getId(), target, operatorId, "无未决异常，进入排产，质检闭环");
    }

    public List<StoreQcRecord> qcOfItem(Long itemId) {
        return qcRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }

    /** 最近一条指定阶段质检记录 */
    public StoreQcRecord latest(Long itemId, String phase) {
        List<StoreQcRecord> list = qcRepo.findByItemIdAndPhaseOrderByCreatedAtAsc(itemId, phase);
        return list.isEmpty() ? null : list.get(list.size() - 1);
    }
}
