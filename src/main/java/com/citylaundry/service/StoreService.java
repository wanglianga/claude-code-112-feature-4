package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.CompensationCase;
import com.citylaundry.model.Enums.CompensationStatus;
import com.citylaundry.model.QcMeeting;
import com.citylaundry.model.Store;
import com.citylaundry.repo.CompensationCaseRepo;
import com.citylaundry.repo.QcMeetingRepo;
import com.citylaundry.repo.StoreRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/** 门店管理：高风险接单策略 + 质检会议（赔付复盘进入会议） */
@Service
public class StoreService {
    private final StoreRepo storeRepo;
    private final QcMeetingRepo meetingRepo;
    private final CompensationCaseRepo caseRepo;

    public StoreService(StoreRepo storeRepo, QcMeetingRepo meetingRepo, CompensationCaseRepo caseRepo) {
        this.storeRepo = storeRepo;
        this.meetingRepo = meetingRepo;
        this.caseRepo = caseRepo;
    }

    /** 门店经理调整接单策略：是否接收高风险衣物、每日上限 */
    @Transactional
    public Store updateStrategy(Long storeId, boolean acceptHighRisk, int maxPerDay) {
        Store s = storeRepo.findById(storeId).orElseThrow(() -> ApiException.notFound("门店不存在"));
        if (maxPerDay < 0) throw ApiException.badRequest("每日上限不能为负");
        s.setAcceptHighRisk(acceptHighRisk);
        s.setMaxHighRiskPerDay(maxPerDay);
        return storeRepo.save(s);
    }

    /** 待复盘赔付案（已赔付但尚未进入质检会议） */
    public List<CompensationCase> pendingReviewCases() {
        return caseRepo.findByReviewedInQcFalseAndStatusIn(List.of(CompensationStatus.PAID, CompensationStatus.REVIEWED));
    }

    /**
     * 召开门店质检会议：把赔付案纳入复盘，会议结论与赔付案双向关联。
     */
    @Transactional
    public QcMeeting holdQcMeeting(String title, Long storeId, LocalDateTime heldAt,
                                   String conclusion, List<Long> caseIds) {
        Store store = storeRepo.findById(storeId).orElseThrow(() -> ApiException.notFound("门店不存在"));
        if (title == null || title.isBlank()) throw ApiException.badRequest("会议标题不能为空");
        if (caseIds == null || caseIds.isEmpty()) throw ApiException.badRequest("质检会议至少复盘一个赔付案");

        QcMeeting meeting = new QcMeeting();
        meeting.setTitle(title);
        meeting.setStoreId(store.getId());
        meeting.setHeldAt(heldAt == null ? LocalDateTime.now() : heldAt);
        meeting.setConclusion(conclusion);
        meeting = meetingRepo.save(meeting);

        List<String> reviewedNos = new ArrayList<>();
        for (Long cid : caseIds) {
            CompensationCase c = caseRepo.findById(cid).orElseThrow(() -> ApiException.notFound("赔付案不存在: " + cid));
            c.setReviewedInQc(true);
            c.setQcMeetingId(meeting.getId());
            c.setStatus(CompensationStatus.REVIEWED);
            caseRepo.save(c);
            reviewedNos.add(c.getCaseNo());
        }
        meeting.setConclusion((conclusion == null ? "" : conclusion)
                + " | 复盘赔付案: " + String.join(",", reviewedNos));
        return meetingRepo.save(meeting);
    }

    public List<QcMeeting> meetingsOf(Long storeId) {
        return meetingRepo.findByStoreIdOrderByHeldAtDesc(storeId);
    }

    public List<Store> stores() { return storeRepo.findAll(); }
}
