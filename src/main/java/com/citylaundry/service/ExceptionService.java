package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 异常项与规则报价。
 * 发现材质不符/高风险污渍/配件缺失/不可去除瑕疵 → 建异常项并附原始证据
 * （照片+拍摄时间+位置+来源），禁止直接加价或继续洗护；
 * 系统按规则生成 4 个候选报价版本；多次补拍、骑手/门店分歧、客户撤回
 * 都会产生新版本报价，历史版本 SUPERSEDED 后永久保留，不覆盖原判断。
 */
@Service
public class ExceptionService {
    private final GarmentExceptionRepo exceptionRepo;
    private final ExceptionEvidenceRepo evidenceRepo;
    private final ExceptionAssessmentRepo assessmentRepo;
    private final QuoteRoundRepo roundRepo;
    private final QuoteOptionRepo optionRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentPhotoRepo photoRepo;
    private final StoreQcRecordRepo qcRepo;
    private final GarmentStateMachine sm;
    private final PricingService pricing;
    private final TimelineService timeline;

    @Value("${app.decision-hours:24}")
    private int decisionHours;

    public ExceptionService(GarmentExceptionRepo exceptionRepo, ExceptionEvidenceRepo evidenceRepo,
                            ExceptionAssessmentRepo assessmentRepo, QuoteRoundRepo roundRepo,
                            QuoteOptionRepo optionRepo, GarmentItemRepo itemRepo,
                            LaundryOrderRepo orderRepo, GarmentPhotoRepo photoRepo,
                            StoreQcRecordRepo qcRepo, GarmentStateMachine sm,
                            PricingService pricing, TimelineService timeline) {
        this.exceptionRepo = exceptionRepo;
        this.evidenceRepo = evidenceRepo;
        this.assessmentRepo = assessmentRepo;
        this.roundRepo = roundRepo;
        this.optionRepo = optionRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.photoRepo = photoRepo;
        this.qcRepo = qcRepo;
        this.sm = sm;
        this.pricing = pricing;
        this.timeline = timeline;
    }

    /**
     * 创建异常项（骑手/门店/工厂/客户）。同时写入首条原始证据与发现方研判。
     * 不直接改价、不改洗护工艺，仅把衣物挂起到异常状态。
     */
    @Transactional
    public GarmentException create(UserAccount actor, Long itemId, ExceptionCategory category,
                                   String description, String observedMaterial,
                                   String suggestedAction, Severity severity,
                                   String photoUrl, LocalDateTime capturedAt,
                                   String location, String riskLevel, Boolean washable,
                                   BigDecimal suggestedSurcharge) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        if (description == null || description.isBlank()) throw ApiException.badRequest("异常描述不能为空");
        if (photoUrl == null || photoUrl.isBlank()) throw ApiException.badRequest("异常必须附原始照片 photoUrl");
        if (location == null || location.isBlank()) throw ApiException.badRequest("异常证据必须记录拍摄位置 location");
        ExceptionSource source = sourceOf(actor);
        severity = severity == null ? Severity.MEDIUM : severity;

        // 同衣物同类别存在未关闭异常 → 不重复建单，作为补拍证据追加
        List<GarmentException> open = exceptionRepo.findByItemIdAndCategoryAndStatusIn(itemId, category,
                List.of(ExceptionStatus.OPEN, ExceptionStatus.ASSESSED, ExceptionStatus.QUOTED,
                        ExceptionStatus.PENDING_CUSTOMER, ExceptionStatus.MANUAL_REVIEW));
        if (!open.isEmpty()) {
            GarmentException ex = open.get(0);
            addEvidence(actor, ex.getId(), photoUrl, capturedAt, location,
                    "同类异常重复上报，作为补拍证据追加", PhotoStage.RESHOOT);
            return ex;
        }

        GarmentException ex = new GarmentException();
        ex.setItemId(itemId);
        ex.setOrderId(item.getOrderId());
        ex.setCategory(category);
        ex.setSource(source);
        ex.setSeverity(severity);
        ex.setDescription(description);
        ex.setClaimedMaterial(item.getMaterial());
        ex.setObservedMaterial(observedMaterial);
        ex.setSuggestedAction(suggestedAction);
        ex.setRaisedBy(actor.getId());
        ex.setDecideDeadline(LocalDateTime.now().plusHours(decisionHours));
        ex.setStatus(ExceptionStatus.OPEN);
        ex.setExceptionNo("TMP");
        ex = exceptionRepo.save(ex);
        ex.setExceptionNo("E" + String.format("%08d", ex.getId()));
        ex = exceptionRepo.saveAndFlush(ex);

        // 原始证据（拍摄时间/位置齐备）
        ExceptionEvidence ev = new ExceptionEvidence();
        ev.setExceptionId(ex.getId());
        ev.setItemId(itemId);
        ev.setRoundNo(1);
        ev.setSource(source);
        ev.setStage(PhotoStage.EXCEPTION);
        ev.setPhotoUrl(photoUrl);
        ev.setCapturedAt(capturedAt == null ? LocalDateTime.now() : capturedAt);
        ev.setLocation(location);
        ev.setUploadedBy(actor.getId());
        ev.setNote("异常发现原始照片");
        evidenceRepo.save(ev);
        saveItemPhoto(itemId, PhotoStage.EXCEPTION, photoUrl, actor.getId());

        // 发现方研判（骑手初判 / 门店质检判断，各自独立保留）
        saveAssessment(ex, item, source, actor.getId(), observedMaterial,
                riskLevel == null ? severityToRisk(severity) : riskLevel,
                washable == null || washable, suggestedAction,
                suggestedSurcharge == null ? BigDecimal.ZERO : suggestedSurcharge);

        // 材质不符：同步留一条门店质检记录（若发现方是门店）
        if (source == ExceptionSource.STORE) {
            StoreQcRecord qc = qcRepo.findByItemId(itemId).orElseGet(StoreQcRecord::new);
            qc.setItemId(itemId);
            qc.setOrderId(item.getOrderId());
            qc.setInspectorId(actor.getId());
            qc.setObservedMaterial(observedMaterial == null ? item.getMaterial() : observedMaterial);
            qc.setObservedAccessories(item.getAccessories());
            qc.setConclusion("质检发现异常[" + category + "]：" + description);
            qc.setPassed(false);
            qc.setResponsibility(Responsibility.PENDING);
            qcRepo.save(qc);
        }

        // 衣物挂起：任何环节发现异常都不得继续洗护
        item.setStatusBeforeHold(item.getStatus());
        sm.transit(item, GarmentStatus.EXCEPTION_PENDING, "发现异常 " + category);
        itemRepo.save(item);
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (order.getStatus() != OrderStatus.EXCEPTION_PENDING
                && sm.canOrder(order.getStatus(), OrderStatus.EXCEPTION_PENDING)) {
            sm.transitOrder(order, OrderStatus.EXCEPTION_PENDING, "衣物 " + item.getItemCode() + " 异常待决");
            orderRepo.save(order);
        }
        timeline.add(item.getOrderId(), itemId, "EXCEPTION",
                "[" + source + "] 创建异常 " + category + "：" + description, actor, ex.getExceptionNo());

        // 系统按规则自动生成首版候选报价（维持原价/追加报价/拒绝承接/仅基础处理）
        issueQuote(ex.getId(), "INITIAL", null, null);
        return exceptionRepo.findById(ex.getId()).orElseThrow();
    }

    /** 对同一异常多次补拍：证据按轮次追加，不覆盖原照片/原判断；若已下发报价则自动出新版本 */
    @Transactional
    public ExceptionEvidence addEvidence(UserAccount actor, Long exceptionId, String photoUrl,
                                         LocalDateTime capturedAt, String location, String note,
                                         PhotoStage stage) {
        GarmentException ex = get(exceptionId);
        GarmentItem item = itemRepo.findById(ex.getItemId()).orElseThrow();
        if (photoUrl == null || photoUrl.isBlank()) throw ApiException.badRequest("补拍照片不能为空");
        if (location == null || location.isBlank()) throw ApiException.badRequest("补拍必须记录位置 location");
        List<ExceptionEvidence> all = evidenceRepo.findByExceptionIdOrderByRoundNoAscIdAsc(exceptionId);
        int nextRound = all.stream().mapToInt(ExceptionEvidence::getRoundNo).max().orElse(0) + 1;

        ExceptionEvidence ev = new ExceptionEvidence();
        ev.setExceptionId(exceptionId);
        ev.setItemId(ex.getItemId());
        ev.setRoundNo(nextRound);
        ev.setSource(sourceOf(actor));
        ev.setStage(stage == null ? PhotoStage.RESHOOT : stage);
        ev.setPhotoUrl(photoUrl);
        ev.setCapturedAt(capturedAt == null ? LocalDateTime.now() : capturedAt);
        ev.setLocation(location);
        ev.setUploadedBy(actor.getId());
        ev.setNote(note);
        ev = evidenceRepo.save(ev);
        saveItemPhoto(ex.getItemId(), PhotoStage.RESHOOT, photoUrl, actor.getId());
        timeline.add(ex.getOrderId(), ex.getItemId(), "EVIDENCE",
                "第 " + nextRound + " 轮补拍证据（" + ev.getSource() + "）：" + note, actor,
                ex.getExceptionNo());

        // 已下发给客户的报价因新证据失效 → 旧版 SUPERSEDED，按规则重出一版（不覆盖）
        QuoteRound latest = roundRepo.findFirstByExceptionIdOrderByVersionDesc(exceptionId).orElse(null);
        if (latest != null && (latest.getStatus() == QuoteRoundStatus.ISSUED
                || latest.getStatus() == QuoteRoundStatus.DISPUTED)) {
            supersedeAndReissue(ex, item, latest, "RESHOOT", null, actor);
        }
        return ev;
    }

    /**
     * 追加研判（典型：骑手先判、门店复看后判断不一致）。
     * 双方研判都保留；若风险/可洗结论不一致且已报价，按更保守口径重出新版。
     */
    @Transactional
    public ExceptionAssessment addAssessment(UserAccount actor, Long exceptionId, String judgedMaterial,
                                             String riskLevel, boolean washable,
                                             String recommendation, BigDecimal suggestedSurcharge) {
        GarmentException ex = get(exceptionId);
        GarmentItem item = itemRepo.findById(ex.getItemId()).orElseThrow();
        ExceptionSource source = sourceOf(actor);
        ExceptionAssessment saved = saveAssessment(ex, item, source, actor.getId(), judgedMaterial,
                riskLevel, washable, recommendation,
                suggestedSurcharge == null ? BigDecimal.ZERO : suggestedSurcharge);

        List<ExceptionAssessment> all = assessmentRepo.findByExceptionIdOrderByAssessedAtAsc(exceptionId);
        boolean disagree = all.size() > 1 && (all.stream().map(ExceptionAssessment::getRiskLevel).distinct().count() > 1
                || all.stream().map(ExceptionAssessment::isWashable).distinct().count() > 1);
        QuoteRound latest = roundRepo.findFirstByExceptionIdOrderByVersionDesc(exceptionId).orElse(null);
        if (disagree && latest != null && (latest.getStatus() == QuoteRoundStatus.ISSUED
                || latest.getStatus() == QuoteRoundStatus.DISPUTED)) {
            supersedeAndReissue(ex, item, latest, "RIDER_STORE_DISAGREE", null, actor);
        }
        ex.setStatus(ExceptionStatus.ASSESSED);
        exceptionRepo.save(ex);
        timeline.add(ex.getOrderId(), ex.getItemId(), "EXCEPTION",
                "[" + source + "] 追加研判：风险=" + riskLevel + "，可洗=" + washable
                        + (disagree ? "（与前序判断不一致，双判保留）" : ""), actor, ex.getExceptionNo());
        return saved;
    }

    /**
     * 生成（首版或新版）规则报价：固定 4 个候选，按异常类别/风险置可选性。
     * @param reason INITIAL / RESHOOT / RIDER_STORE_DISAGREE / MANUAL_REVIEW / CUSTOMER_WITHDRAW
     * @param forcedSurcharge 人工复核下调/指定追加额（可空=规则计算）
     */
    @Transactional
    public QuoteRound issueQuote(Long exceptionId, String reason, BigDecimal forcedSurcharge,
                                 UserAccount actor) {
        GarmentException ex = get(exceptionId);
        GarmentItem item = itemRepo.findById(ex.getItemId()).orElseThrow();
        List<ExceptionAssessment> assessments =
                assessmentRepo.findByExceptionIdOrderByAssessedAtAsc(ex.getId());

        QuoteRound prev = roundRepo.findFirstByExceptionIdOrderByVersionDesc(ex.getId()).orElse(null);
        if (prev != null && prev.getStatus() == QuoteRoundStatus.ISSUED && forcedSurcharge == null
                && "INITIAL".equals(reason)) {
            return prev; // 已有生效报价，不重复生成
        }
        if (prev != null && prev.getStatus() == QuoteRoundStatus.CONFIRMED
                && !"CUSTOMER_WITHDRAW".equals(reason) && !"MANUAL_REVIEW".equals(reason)) {
            throw ApiException.conflict("客户已确认该报价，不能重新报价（需先撤回）");
        }

        BigDecimal basePrice = pricing.basePriceOf(item);
        List<QuoteOption> generated = pricing.buildOptions(ex, item, assessments, basePrice);
        BigDecimal surcharge = pricing.ruleSurcharge(ex, item, assessments, basePrice);
        if (forcedSurcharge != null) {
            surcharge = forcedSurcharge;
            // 人工指定金额时重写追加选项
            generated = regenerateWithForcedSurcharge(generated, basePrice, forcedSurcharge);
        }

        int version = (prev == null ? 0 : prev.getVersion()) + 1;
        QuoteRound round = new QuoteRound();
        round.setExceptionId(ex.getId());
        round.setItemId(item.getId());
        round.setOrderId(ex.getOrderId());
        round.setVersion(version);
        round.setReason(reason == null ? "INITIAL" : reason);
        round.setBasePrice(basePrice);
        round.setSurchargeAmount(surcharge);
        round.setRuleTrace(pricing.ruleTrace(ex, assessments));
        round.setCreatedBy(actor == null ? 0L : actor.getId());
        round.setStatus(QuoteRoundStatus.ISSUED);
        round.setIssuedAt(LocalDateTime.now());
        round.setQuoteNo("TMP");
        round = roundRepo.save(round);
        round.setQuoteNo("Q" + String.format("%08d-%d", ex.getId(), version));
        round = roundRepo.saveAndFlush(round);

        for (QuoteOption o : generated) {
            o.setQuoteRoundId(round.getId());
            optionRepo.save(o);
        }

        ex.setVersion(version);
        ex.setStatus(ExceptionStatus.PENDING_CUSTOMER);
        exceptionRepo.save(ex);

        item.setCurrentQuoteRoundId(round.getId());
        if (item.getStatus() != GarmentStatus.AWAIT_CUSTOMER) {
            sm.transit(item, GarmentStatus.AWAIT_CUSTOMER, "第 " + version + " 版报价下发客户");
        }
        itemRepo.save(item);

        timeline.add(ex.getOrderId(), ex.getItemId(), "QUOTE_ISSUED",
                "第 " + version + " 版报价（" + round.getReason() + "）：原价 ¥" + basePrice
                        + "，建议追加 ¥" + surcharge + "，4 候选已下发，决策截止 " + ex.getDecideDeadline(),
                actor == null ? null : actor, round.getQuoteNo());
        return round;
    }

    /** 旧版作废并用当前证据/研判重出新版本 */
    private void supersedeAndReissue(GarmentException ex, GarmentItem item, QuoteRound prev,
                                     String reason, BigDecimal forcedSurcharge, UserAccount actor) {
        prev.setStatus(QuoteRoundStatus.SUPERSEDED);
        roundRepo.save(prev);
        if (forcedSurcharge != null) reason = "MANUAL_REVIEW";
        issueQuote(ex.getId(), reason, forcedSurcharge, actor);
    }

    private List<QuoteOption> regenerateWithForcedSurcharge(List<QuoteOption> options,
                                                             BigDecimal basePrice, BigDecimal surcharge) {
        List<QuoteOption> out = new ArrayList<>();
        for (QuoteOption o : options) {
            if (o.getType() == QuoteOptionType.SURCHARGE) {
                o.setTotalPrice(basePrice.add(surcharge));
                o.setDelta(surcharge);
                o.setSelectable(surcharge.signum() >= 0);
                o.setExplanation("人工复核核定追加 ¥" + surcharge + " 后专项洗护");
            }
            out.add(o);
        }
        return out;
    }

    private ExceptionAssessment saveAssessment(GarmentException ex, GarmentItem item, ExceptionSource source,
                                               Long assessorId, String judgedMaterial, String riskLevel,
                                               boolean washable, String recommendation,
                                               BigDecimal suggestedSurcharge) {
        // 同一来源已有研判 → 不覆盖，追加为独立记录（保留判断演化）
        ExceptionAssessment a = new ExceptionAssessment();
        a.setExceptionId(ex.getId());
        a.setItemId(item.getId());
        a.setSource(source);
        a.setAssessorId(assessorId);
        a.setJudgedMaterial(judgedMaterial);
        a.setRiskLevel(riskLevel == null ? "MEDIUM" : riskLevel);
        a.setWashable(washable);
        a.setRecommendation(recommendation == null ? "" : recommendation);
        a.setSuggestedSurcharge(suggestedSurcharge == null ? BigDecimal.ZERO : suggestedSurcharge);
        return assessmentRepo.save(a);
    }

    private void saveItemPhoto(Long itemId, PhotoStage stage, String url, Long userId) {
        GarmentPhoto p = new GarmentPhoto();
        p.setItemId(itemId);
        p.setStage(stage);
        p.setUrl(url);
        p.setUploadedBy(userId);
        photoRepo.save(p);
    }

    public static ExceptionSource sourceOf(UserAccount u) {
        return switch (u.getRole()) {
            case PICKUP, DELIVERY -> ExceptionSource.RIDER;
            case STORE_MANAGER, CS -> ExceptionSource.STORE;
            case FACTORY -> ExceptionSource.FACTORY;
            case CUSTOMER -> ExceptionSource.CUSTOMER;
            default -> ExceptionSource.SYSTEM;
        };
    }

    private String severityToRisk(Severity s) {
        return switch (s) {
            case HIGH -> "HIGH";
            case LOW -> "LOW";
            default -> "MEDIUM";
        };
    }

    @Transactional(readOnly = true)
    public GarmentException get(Long id) {
        return exceptionRepo.findById(id).orElseThrow(() -> ApiException.notFound("异常项不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<GarmentException> exceptionsOfItem(Long itemId) {
        return exceptionRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }

    @Transactional(readOnly = true)
    public List<GarmentException> pendingCustomer() {
        return exceptionRepo.findByStatusIn(List.of(ExceptionStatus.PENDING_CUSTOMER, ExceptionStatus.MANUAL_REVIEW));
    }

    /** 超时未决策异常（演示扫描用） */
    @Transactional(readOnly = true)
    public List<GarmentException> overdue() {
        LocalDateTime now = LocalDateTime.now();
        return exceptionRepo.findByStatusIn(List.of(ExceptionStatus.PENDING_CUSTOMER)).stream()
                .filter(e -> e.getDecideDeadline().isBefore(now)).toList();
    }

    /** 异常项完整聚合（证据/研判/报价版本+候选/客户反馈） */
    @Transactional(readOnly = true)
    public Map<String, Object> detail(Long exceptionId) {
        GarmentException ex = get(exceptionId);
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("exception", ex);
        m.put("evidence", evidenceRepo.findByExceptionIdOrderByRoundNoAscIdAsc(exceptionId));
        m.put("assessments", assessmentRepo.findByExceptionIdOrderByAssessedAtAsc(exceptionId));
        List<Map<String, Object>> rounds = new ArrayList<>();
        for (QuoteRound r : roundRepo.findByExceptionIdOrderByVersionAsc(exceptionId)) {
            Map<String, Object> rm = new LinkedHashMap<>();
            rm.put("round", r);
            rm.put("options", optionRepo.findByQuoteRoundIdOrderByIdAsc(r.getId()));
            rounds.add(rm);
        }
        m.put("rounds", rounds);
        m.put("feedbacks", new ArrayList<>()); // 由 DossierService 填充时合并
        return m;
    }
}
