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
import java.util.List;

/**
 * 异常项 / 证据 / 多版本报价 / 客户决策 / 人工复核 核心服务。
 *
 * <p>关键约束：
 * <ul>
 *   <li>发现异常不得直接加价或继续洗护：先建异常项 + 原始证据（照片/拍摄时间/位置/建议），
 *       系统规则生成候选报价版本，衣物进入 ANOMALY_QUOTED 阻塞排产；</li>
 *   <li>多次补拍、骑手与门店判断不一致 → 生成新版报价，旧版 SUPERSEDED 保留，原判断不覆盖；</li>
 *   <li>客户在时限内确认/拒绝/申请复核；确认追加后预授权追加金额才允许排产；
 *       确认后洗护未开始可撤回，撤回释放追加预授权；</li>
 *   <li>拒绝承接 → 生成骑手回收任务并释放门店产能；按原服务继续 → 衣物回到原状态。</li>
 * </ul>
 */
@Service
public class AnomalyService {
    private final AnomalyIssueRepo issueRepo;
    private final IssueEvidenceRepo evidenceRepo;
    private final QuoteVersionRepo quoteRepo;
    private final CustomerDecisionRepo decisionRepo;
    private final ManualReviewCaseRepo reviewRepo;
    private final RiderReclaimRepo reclaimRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final GarmentPhotoRepo photoRepo;
    private final GarmentStateMachine sm;
    private final PaymentService paymentService;
    private final StoreQcService qcService;

    @Value("${app.decision-hours:24}")
    private int decisionHours;

    public AnomalyService(AnomalyIssueRepo issueRepo, IssueEvidenceRepo evidenceRepo,
                          QuoteVersionRepo quoteRepo, CustomerDecisionRepo decisionRepo,
                          ManualReviewCaseRepo reviewRepo, RiderReclaimRepo reclaimRepo,
                          GarmentItemRepo itemRepo, LaundryOrderRepo orderRepo, GarmentPhotoRepo photoRepo,
                          GarmentStateMachine sm, PaymentService paymentService, StoreQcService qcService) {
        this.issueRepo = issueRepo;
        this.evidenceRepo = evidenceRepo;
        this.quoteRepo = quoteRepo;
        this.decisionRepo = decisionRepo;
        this.reviewRepo = reviewRepo;
        this.reclaimRepo = reclaimRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.photoRepo = photoRepo;
        this.sm = sm;
        this.paymentService = paymentService;
        this.qcService = qcService;
    }

    /* ========================= 异常项 + 证据 ========================= */

    /** 骑手/门店发现异常：建异常项 + 原始证据 + 规则生成首版报价，衣物阻塞为 ANOMALY_QUOTED */
    @Transactional
    public AnomalyIssue raiseIssue(UserAccount user, Long itemId, ExceptionType type, String description,
                                   String severity, String suggestion, String actualMaterial, String actualBrand,
                                   String photoUrl, LocalDateTime takenAt, String location) {
        GarmentItem item = itemRepo.findById(itemId).orElseThrow(() -> ApiException.notFound("衣物不存在"));
        if (description == null || description.isBlank()) throw ApiException.badRequest("异常描述不能为空");
        if (photoUrl == null || photoUrl.isBlank()) throw ApiException.badRequest("异常项必须附原始照片");
        if (item.getStatus() == GarmentStatus.DELIVERED || item.getStatus() == GarmentStatus.CLOSED
                || item.getStatus() == GarmentStatus.RETURNED || item.getStatus() == GarmentStatus.RECLAIM_PENDING
                || item.getStatus() == GarmentStatus.RECLAIMED) {
            throw ApiException.conflict("衣物当前状态不允许登记异常: " + item.getStatus());
        }
        // 同一件衣物存在未关闭异常时不并单：作为新异常项独立建档（逐异常审计）
        String stage = user.getRole() == Role.PICKUP ? "PICKUP" : "STORE_QC";

        AnomalyIssue issue = new AnomalyIssue();
        issue.setItemId(itemId);
        issue.setType(type);
        issue.setDescription(description);
        issue.setSeverity(normalizeSeverity(severity));
        issue.setFoundStage(stage);
        issue.setRaisedBy(user.getId());
        issue.setSuggestion(suggestion);
        issue.setActualMaterial(actualMaterial);
        issue.setActualBrand(actualBrand);
        issue.setStatus(AnomalyStatus.OPEN);
        issue.setDecisionDeadline(LocalDateTime.now().plusHours(decisionHours));
        issue.setIssueNo("TMP");
        issue = issueRepo.save(issue);
        issue.setIssueNo(String.format("A%08d", issue.getId()));
        issue = issueRepo.save(issue);

        addEvidenceEntity(user, issue, item, photoUrl, takenAt, location, stage, null, suggestion, 1);

        // 记录异常前业务状态，决策后据此恢复
        item.setResumeStatus(item.getStatus().name());
        item.setQcConclusion("ANOMALY");
        itemRepo.save(item);
        qcService.markIntake(itemId, com.citylaundry.model.Enums.QcConclusion.ANOMALY,
                user.getId(), "入厂质检发现异常：" + type);
        sm.transition(itemId, GarmentStatus.ANOMALY_QUOTED, "ISSUE_RAISED",
                "ISSUE", issue.getId(), user.getId(), type + " 异常登记，阻塞排产等待报价");

        generateQuoteVersion(issue, "INITIAL", 0L, null, "规则引擎按异常类型/材质/风险生成候选方案");
        issue.setStatus(AnomalyStatus.QUOTED);
        return issueRepo.save(issue);
    }

    /** 补拍证据：同一异常项多次补拍/骑手门店分歧均独立留存；默认据新证据重新出一版报价 */
    @Transactional
    public IssueEvidence addEvidence(UserAccount user, Long issueId, String photoUrl, LocalDateTime takenAt,
                                     String location, String sourceStage, String disagreementNote,
                                     String suggestion, boolean reprice) {
        AnomalyIssue issue = getIssue(issueId);
        if (issue.getStatus() == AnomalyStatus.CLOSED) {
            throw ApiException.conflict("异常项已关闭，不能补拍");
        }
        if (photoUrl == null || photoUrl.isBlank()) throw ApiException.badRequest("补拍必须提供 photoUrl");
        GarmentItem item = itemRepo.findById(issue.getItemId()).orElseThrow();
        int round = evidenceRepo.countByIssueId(issueId) + 1;
        String stage = sourceStage == null || sourceStage.isBlank()
                ? (user.getRole() == Role.PICKUP ? "PICKUP" : "STORE_QC") : sourceStage;

        IssueEvidence ev = addEvidenceEntity(user, issue, item, photoUrl, takenAt, location,
                stage, disagreementNote, suggestion, round);

        if (reprice) {
            // 若客户已确认过旧版（尚未洗护），新版本取代确认：释放追加预授权、清除协议价（历史版本保留）
            boolean inReview = issue.getStatus() == AnomalyStatus.REVIEW;
            revokeAgreement(item, "RESHOT");
            String reason = disagreementNote != null && !disagreementNote.isBlank() ? "DISAGREEMENT" : "RESHOT";
            generateQuoteVersion(issue, reason, user.getId(), null,
                    "第" + round + "次补拍" + (reason.equals("DISAGREEMENT") ? "（骑手/门店判断不一致）" : "") + "后重新报价");
            // 人工复核中补拍：版本更新但仍由客服裁决，状态保持 REVIEW；裁决后才回到客户决策
            if (!inReview) {
                issue.setStatus(AnomalyStatus.QUOTED);
                issueRepo.save(issue);
            }
            if (item.getStatus() == GarmentStatus.EXTRA_APPROVED) {
                sm.transition(item.getId(), GarmentStatus.ANOMALY_QUOTED, "QUOTE_SUPERSEDED",
                        "QUOTE", issue.getActiveQuoteVersionId(), user.getId(), "补拍产生新版本，原确认失效");
            }
        }
        return ev;
    }

    private IssueEvidence addEvidenceEntity(UserAccount user, AnomalyIssue issue, GarmentItem item,
                                            String photoUrl, LocalDateTime takenAt, String location,
                                            String stage, String disagreementNote, String suggestion, int round) {
        LocalDateTime shotAt = takenAt == null ? LocalDateTime.now() : takenAt;
        IssueEvidence ev = new IssueEvidence();
        ev.setIssueId(issue.getId());
        ev.setItemId(item.getId());
        ev.setPhotoUrl(photoUrl);
        ev.setTakenAt(shotAt);
        ev.setLocation(location);
        ev.setShotRound(round);
        ev.setSourceStage(stage);
        ev.setUploadedBy(user.getId());
        ev.setDisagreementNote(disagreementNote);
        ev.setSuggestion(suggestion);
        ev = evidenceRepo.save(ev);

        // 同步进衣物照片时间线（ANOMALY 阶段），统一供档案展示与证据清点
        GarmentPhoto gp = new GarmentPhoto();
        gp.setItemId(item.getId());
        gp.setStage(PhotoStage.ANOMALY);
        gp.setUrl(photoUrl);
        gp.setTakenAt(shotAt);
        gp.setLocation(location);
        gp.setIssueId(issue.getId());
        gp.setUploadedBy(user.getId());
        photoRepo.save(gp);
        return ev;
    }

    /* ========================= 报价版本（规则引擎） ========================= */

    /** 生成新版报价：旧 ACTIVE 版本置 SUPERSEDED（保留不覆盖） */
    @Transactional
    public QuoteVersion generateQuoteVersion(AnomalyIssue issue, String reason, Long createdBy,
                                             BigDecimal manualExtra, String ruleNote) {
        GarmentItem item = itemRepo.findById(issue.getItemId()).orElseThrow();
        BigDecimal base = item.getBasePrice() != null && item.getBasePrice().compareTo(BigDecimal.ZERO) > 0
                ? item.getBasePrice() : PricingService.estimatePrice(item);

        BigDecimal extra = BigDecimal.ZERO;
        List<QuoteOptionType> options = new ArrayList<>();
        QuoteOptionType recommended;
        String note;

        switch (issue.getType()) {
            case MATERIAL_MISMATCH -> {
                ProcessType actual = estimateByMaterial(issue.getActualMaterial(), item);
                BigDecimal actualPrice = PricingService.priceOf(actual);
                extra = actualPrice.subtract(base).max(BigDecimal.ZERO);
                options.add(QuoteOptionType.EXTRA_CHARGE);
                options.add(QuoteOptionType.REJECT_ORDER);
                if (extra.compareTo(BigDecimal.ZERO) == 0) options.add(QuoteOptionType.KEEP_ORIGINAL);
                recommended = extra.compareTo(BigDecimal.ZERO) > 0 ? QuoteOptionType.EXTRA_CHARGE : QuoteOptionType.KEEP_ORIGINAL;
                note = "订单材质[" + item.getMaterial() + "]估价¥" + base + "，实物材质["
                        + issue.getActualMaterial() + "]工艺" + actual + "价¥" + actualPrice + "，差价¥" + extra;
            }
            case HIGH_RISK_STAIN -> {
                extra = (item.isLuxury() || PricingService.estimateProcess(item) == ProcessType.LEATHER_CARE)
                        ? new BigDecimal("120") : new BigDecimal("60");
                options = List.of(QuoteOptionType.EXTRA_CHARGE, QuoteOptionType.BASIC_ONLY, QuoteOptionType.REJECT_ORDER);
                recommended = "HIGH".equals(issue.getSeverity())
                        ? QuoteOptionType.EXTRA_CHARGE : QuoteOptionType.BASIC_ONLY;
                note = "高风险污渍需专项去渍工艺，追加¥" + extra + "；也可仅基础处理或拒绝承接";
            }
            case ACCESSORY_MISSING -> {
                extra = new BigDecimal("30");
                options = List.of(QuoteOptionType.EXTRA_CHARGE, QuoteOptionType.BASIC_ONLY,
                        QuoteOptionType.KEEP_ORIGINAL, QuoteOptionType.REJECT_ORDER);
                recommended = QuoteOptionType.REJECT_ORDER;
                note = "配件清点缺失，建议拒绝承接；也可专项防护(+¥30)、仅基础处理或按原服务继续";
            }
            case UNREMOVABLE_FLAW, STAIN_NOT_REMOVABLE -> {
                options = List.of(QuoteOptionType.BASIC_ONLY, QuoteOptionType.KEEP_ORIGINAL, QuoteOptionType.REJECT_ORDER);
                recommended = QuoteOptionType.BASIC_ONLY;
                note = "瑕疵/污渍无法保证去除，建议仅基础处理并如实告知，不另收费用";
            }
            case MISSING_CARE_LABEL -> {
                extra = new BigDecimal("20");
                options = List.of(QuoteOptionType.EXTRA_CHARGE, QuoteOptionType.BASIC_ONLY, QuoteOptionType.KEEP_ORIGINAL);
                recommended = QuoteOptionType.BASIC_ONLY;
                note = "洗标缺失：可专家判定工艺(+¥20)或仅基础处理";
            }
            default -> {
                extra = new BigDecimal("40");
                options = List.of(QuoteOptionType.KEEP_ORIGINAL, QuoteOptionType.EXTRA_CHARGE,
                        QuoteOptionType.BASIC_ONLY, QuoteOptionType.REJECT_ORDER);
                recommended = QuoteOptionType.KEEP_ORIGINAL;
                note = "按通用规则给出四类候选方案";
            }
        }
        if (manualExtra != null) {
            extra = manualExtra.max(BigDecimal.ZERO);
            recommended = QuoteOptionType.EXTRA_CHARGE;
            if (!options.contains(QuoteOptionType.EXTRA_CHARGE)) options = new ArrayList<>(options) {{ add(QuoteOptionType.EXTRA_CHARGE); }};
            note = "人工复核调整追加金额为¥" + extra + "；" + note;
        }

        // 旧版本失效（不删除）
        for (QuoteVersion old : quoteRepo.findByIssueIdOrderByVersionNoAsc(issue.getId())) {
            if (old.getStatus() == QuoteStatus.ACTIVE || old.getStatus() == QuoteStatus.DRAFT) {
                old.setStatus(QuoteStatus.SUPERSEDED);
                quoteRepo.save(old);
            }
        }
        int versionNo = quoteRepo.countByIssueId(issue.getId()) + 1;
        QuoteVersion q = new QuoteVersion();
        q.setIssueId(issue.getId());
        q.setItemId(item.getId());
        q.setVersionNo(versionNo);
        q.setReason(reason);
        q.setOriginalPrice(base);
        q.setExtraAmount(extra);
        q.setRecommendedOption(recommended);
        q.setOptions(options.stream().map(Enum::name).reduce((a, b) -> a + "," + b).orElse(""));
        q.setRuleNote((ruleNote == null ? "" : ruleNote + " | ") + note);
        q.setStatus(QuoteStatus.ACTIVE);
        q.setCreatedBy(createdBy);
        q.setQuoteNo("TMP");
        q = quoteRepo.save(q);
        q.setQuoteNo(String.format("Q%08d-V%02d", issue.getId(), versionNo));
        q = quoteRepo.save(q);

        issue.setActiveQuoteVersionId(q.getId());
        issueRepo.save(issue);
        return q;
    }

    private ProcessType estimateByMaterial(String material, GarmentItem item) {
        if (item.isLuxury()) return ProcessType.LUXURY_CARE;
        String m = material == null ? "" : material;
        if (OrderService.containsAny(m, "皮革", "皮草", "PU")) return ProcessType.LEATHER_CARE;
        if (OrderService.containsAny(m, "真丝", "羊绒", "羊毛")) return ProcessType.DRY_CLEAN;
        return ProcessType.WET_CLEAN;
    }

    /* ========================= 客户决策 ========================= */

    @Transactional
    public CustomerDecision decide(UserAccount customer, Long issueId, CustomerAction action, String note) {
        AnomalyIssue issue = getIssue(issueId);
        GarmentItem item = itemRepo.findById(issue.getItemId()).orElseThrow();
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (!order.getCustomerId().equals(customer.getId())) {
            throw ApiException.forbidden("只能对自己订单的衣物做决策");
        }
        if (issue.getStatus() == AnomalyStatus.CLOSED) throw ApiException.conflict("异常项已关闭");
        if (issue.getStatus() == AnomalyStatus.REVIEW) {
            throw ApiException.conflict("异常项人工复核中，暂不能决策");
        }
        if (item.getStatus() != GarmentStatus.ANOMALY_QUOTED) {
            throw ApiException.conflict("衣物当前不在待决策状态: " + item.getStatus());
        }
        QuoteVersion quote = activeQuote(issue);
        if (quote.getStatus() == QuoteStatus.EXPIRED) {
            throw ApiException.conflict("报价已过决策时限，请等待人工跟进");
        }
        if (issue.getDecisionDeadline().isBefore(LocalDateTime.now())) {
            throw ApiException.conflict("已过决策时限");
        }

        CustomerDecision d = new CustomerDecision();
        d.setIssueId(issue.getId());
        d.setItemId(item.getId());
        d.setQuoteVersionId(quote.getId());
        d.setAction(action);
        d.setDecidedBy(customer.getId());
        d.setCustomerNote(note);
        d.setExpectedDeliveryAt(item.getExpectedDeliveryAt() != null
                ? item.getExpectedDeliveryAt() : order.getExpectedDeliveryAt());

        switch (action) {
            case CONFIRM_EXTRA -> {
                ensureOption(quote, QuoteOptionType.EXTRA_CHARGE);
                if (quote.getExtraAmount().compareTo(BigDecimal.ZERO) <= 0) {
                    throw ApiException.badRequest("该报价无追加金额，请选择维持原价/仅基础处理");
                }
                // 追加金额预授权（撤回释放后可重新冻结：按该版本已有支付单数生成新幂等键，不重复冻结）
                long extraAttempt = paymentService.byItem(item.getId()).stream()
                        .filter(p -> ("EXTRA:" + quote.getId()).equals(p.getBizRef())).count() + 1;
                paymentService.preAuthorize(item.getId(), quote.getExtraAmount(),
                        "EXTRA:" + quote.getId() + ":" + item.getId() + ":" + extraAttempt,
                        "EXTRA:" + quote.getId());
                item.setAgreedExtra(quote.getExtraAmount());
                item.setAgreedQuoteVersionId(quote.getId());
                item.setExpectedDeliveryAt(order.getExpectedDeliveryAt().plusDays(1)); // 追加工序顺延 1 天
                itemRepo.save(item);
                quote.setStatus(QuoteStatus.CONFIRMED);
                quoteRepo.save(quote);
                issue.setStatus(AnomalyStatus.DECIDED);
                issueRepo.save(issue);
                syncOrderExtra(order);
                sm.transition(item.getId(), GarmentStatus.EXTRA_APPROVED, "EXTRA_CONFIRMED",
                        "QUOTE", quote.getId(), customer.getId(), "客户确认追加¥" + quote.getExtraAmount());
                d.setStatus(DecisionStatus.CONFIRMED);
            }
            case REJECT_CONTINUE -> {
                ensureOption(quote, QuoteOptionType.KEEP_ORIGINAL);
                d.setStatus(resumeWithoutExtra(item, issue, quote, CustomerAction.REJECT_CONTINUE,
                        DecisionStatus.CONTINUE_ORIGINAL, customer, note, "KEEP_ORIGINAL"));
            }
            case BASIC_ONLY -> {
                ensureOption(quote, QuoteOptionType.BASIC_ONLY);
                d.setStatus(resumeWithoutExtra(item, issue, quote, CustomerAction.BASIC_ONLY,
                        DecisionStatus.CONTINUE_ORIGINAL, customer, note, "BASIC_ONLY"));
            }
            case REJECT_REFUND -> {
                ensureOption(quote, QuoteOptionType.REJECT_ORDER);
                quote.setStatus(QuoteStatus.REJECTED);
                quoteRepo.save(quote);
                issue.setStatus(AnomalyStatus.DECIDED);
                issueRepo.save(issue);
                RiderReclaim reclaim = createReclaim(item, order, note);
                item.setReclaimId(reclaim.getId());
                itemRepo.save(item);
                sm.transition(item.getId(), GarmentStatus.RECLAIM_PENDING, "REJECT_REFUND",
                        "QUOTE", quote.getId(), customer.getId(), "客户拒绝承接，等待骑手回收并原路退回");
                d.setStatus(DecisionStatus.REJECTED_REFUND);
            }
            case REQUEST_REVIEW -> {
                ManualReviewCase rc = new ManualReviewCase();
                rc.setIssueId(issue.getId());
                rc.setItemId(item.getId());
                rc.setRequestedBy(customer.getId());
                rc.setRequestReason(note);
                rc.setReviewNo("TMP");
                rc = reviewRepo.save(rc);
                rc.setReviewNo(String.format("R%08d", rc.getId()));
                rc = reviewRepo.save(rc);
                issue.setStatus(AnomalyStatus.REVIEW);
                issue.setManualReviewId(rc.getId());
                issueRepo.save(issue);
                sm.transition(item.getId(), GarmentStatus.MANUAL_REVIEW, "REVIEW_REQUESTED",
                        "REVIEW", rc.getId(), customer.getId(), "客户申请人工复核");
                d.setStatus(DecisionStatus.IN_REVIEW);
            }
            case WITHDRAW -> throw ApiException.badRequest("撤回请使用确认后撤回接口");
        }
        return decisionRepo.save(d);
    }

    private DecisionStatus resumeWithoutExtra(GarmentItem item, AnomalyIssue issue, QuoteVersion quote,
                                              CustomerAction action, DecisionStatus ds,
                                              UserAccount customer, String note, String via) {
        item.setAgreedExtra(BigDecimal.ZERO);
        item.setAgreedQuoteVersionId(null);
        boolean basic = "BASIC_ONLY".equals(via);
        item.setQcConclusion(basic ? "BASIC_ONLY" : "PASS");
        itemRepo.save(item);
        qcService.markIntake(item.getId(),
                basic ? QcConclusion.BASIC_ONLY : QcConclusion.PASS,
                customer.getId(), "客户选择" + via + "，质检闭环，按原服务继续");
        syncOrderExtra(orderRepo.findById(item.getOrderId()).orElseThrow());
        quote.setStatus("BASIC_ONLY".equals(via) ? QuoteStatus.CONFIRMED : QuoteStatus.REJECTED);
        quoteRepo.save(quote);
        issue.setStatus(AnomalyStatus.CLOSED);
        issue.setClosedAt(LocalDateTime.now());
        issue.setSuggestion((issue.getSuggestion() == null ? "" : issue.getSuggestion() + " | ")
                + "客户选择" + via + (note == null ? "" : "：" + note));
        issueRepo.save(issue);

        GarmentStatus target = GarmentStatus.valueOf(item.getResumeStatus());
        // 取件阶段异常解决：按收衣照片是否齐全恢复到 PHOTOGRAPHED / REGISTERED；工艺阶段则回到 AT_FACTORY 重新判定
        if (target == GarmentStatus.PROCESS_ASSIGNED || target == GarmentStatus.IN_PROCESS) {
            target = GarmentStatus.AT_FACTORY;
        } else if (target == GarmentStatus.REGISTERED || target == GarmentStatus.PHOTOGRAPHED) {
            boolean photosDone = photoRepo.findByItemIdOrderByCreatedAtAsc(item.getId()).stream()
                    .anyMatch(p -> p.getStage() == PhotoStage.PICKUP_FRONT)
                    && photoRepo.findByItemIdOrderByCreatedAtAsc(item.getId()).stream()
                    .anyMatch(p -> p.getStage() == PhotoStage.PICKUP_BACK);
            target = photosDone ? GarmentStatus.PHOTOGRAPHED : GarmentStatus.REGISTERED;
        }
        sm.transition(item.getId(), target, "QUOTE_RESOLVED",
                "QUOTE", quote.getId(), customer.getId(), "客户选择" + via + "，按原服务继续");
        if (target == GarmentStatus.PHOTOGRAPHED) maybeOrderPickedUp(item.getOrderId());
        return ds;
    }

    /** 取件阶段异常解决后，若全部衣物已拍照，将订单置为 PICKED_UP 以便顾客确认 */
    private void maybeOrderPickedUp(Long orderId) {
        List<GarmentItem> items = itemRepo.findByOrderIdOrderByIdAsc(orderId);
        boolean ready = !items.isEmpty() && items.stream().allMatch(i ->
                i.getStatus() == GarmentStatus.PHOTOGRAPHED || i.getStatus() == GarmentStatus.CONFIRMED
                        || i.getStatus() == GarmentStatus.AT_FACTORY || i.getStatus() == GarmentStatus.IN_PROCESS
                        || i.getStatus() == GarmentStatus.PROCESS_ASSIGNED || i.getStatus() == GarmentStatus.PROCESS_DONE
                        || i.getStatus() == GarmentStatus.READY_FOR_DELIVERY || i.getStatus() == GarmentStatus.DELIVERED
                        || i.getStatus() == GarmentStatus.EXTRA_APPROVED);
        if (ready) {
            LaundryOrder order = orderRepo.findById(orderId).orElseThrow();
            if (order.getStatus() == OrderStatus.CREATED) {
                order.setStatus(OrderStatus.PICKED_UP);
                orderRepo.save(order);
            }
        }
    }

    /** 客户确认追加后、洗护未开始前撤回：回到待决策，释放追加预授权，原确认留痕为 WITHDRAWN */
    @Transactional
    public CustomerDecision withdraw(UserAccount customer, Long issueId, String note) {
        AnomalyIssue issue = getIssue(issueId);
        GarmentItem item = itemRepo.findById(issue.getItemId()).orElseThrow();
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        if (!order.getCustomerId().equals(customer.getId())) throw ApiException.forbidden("只能操作自己的订单");
        if (item.getStatus() != GarmentStatus.EXTRA_APPROVED) {
            throw ApiException.conflict("衣物当前状态不允许撤回（可能已进入洗护）: " + item.getStatus());
        }
        QuoteVersion quote = quoteRepo.findById(issue.getActiveQuoteVersionId()).orElseThrow();

        revokeAgreement(item, "WITHDRAW");
        // 原报价版本仍有效，等待客户重新决策（客户的撤回动作已独立留痕）
        issue.setStatus(AnomalyStatus.QUOTED);
        issueRepo.save(issue);
        syncOrderExtra(order);

        sm.transition(item.getId(), GarmentStatus.ANOMALY_QUOTED, "CUSTOMER_WITHDRAW",
                "QUOTE", quote.getId(), customer.getId(), "客户确认后撤回：" + note);

        CustomerDecision d = new CustomerDecision();
        d.setIssueId(issue.getId());
        d.setItemId(item.getId());
        d.setQuoteVersionId(quote.getId());
        d.setAction(CustomerAction.WITHDRAW);
        d.setStatus(DecisionStatus.WITHDRAWN);
        d.setDecidedBy(customer.getId());
        d.setCustomerNote(note);
        return decisionRepo.save(d);
    }

    /** 清除追加协议并释放仍处于授权态的追加预授权（已实扣则不能撤回，由状态机/调用方保证） */
    private void revokeAgreement(GarmentItem item, String why) {
        if (item.getAgreedQuoteVersionId() == null) return;
        for (Payment p : paymentService.byItem(item.getId())) {
            if (("EXTRA:" + item.getAgreedQuoteVersionId()).equals(p.getBizRef())
                    && (p.getStatus() == PaymentStatus.AUTHORIZED || p.getStatus() == PaymentStatus.PENDING
                        || p.getStatus() == PaymentStatus.FAILED)) {
                paymentService.releasePreAuth(p.getId());
            }
        }
        item.setAgreedExtra(BigDecimal.ZERO);
        item.setAgreedQuoteVersionId(null);
        item.setExpectedDeliveryAt(null);
        itemRepo.save(item);
    }

    /** 客户拒绝承接：生成骑手回收任务（幂等：同衣物已有待处理任务直接返回），并释放门店产能占用 */
    @Transactional
    public RiderReclaim createReclaim(GarmentItem item, LaundryOrder order, String note) {
        var open = reclaimRepo.findByItemIdAndStatusIn(item.getId(),
                List.of(ReclaimStatus.PENDING, ReclaimStatus.ASSIGNED));
        if (open.isPresent()) return open.get();
        RiderReclaim r = new RiderReclaim();
        r.setItemId(item.getId());
        r.setOrderId(order.getId());
        r.setReclaimAddress(order.getDeliveryAddress());
        r.setStatus(ReclaimStatus.PENDING);
        r.setNote(note);
        r.setReclaimNo("TMP");
        r = reclaimRepo.save(r);
        r.setReclaimNo(String.format("RC%08d", r.getId()));
        return reclaimRepo.save(r);
    }

    /** 汇总订单全部衣物的已确认追加金额与对应支付，写回订单 */
    private void syncOrderExtra(LaundryOrder order) {
        BigDecimal extra = BigDecimal.ZERO;
        for (GarmentItem it : itemRepo.findByOrderIdOrderByIdAsc(order.getId())) {
            if (it.getAgreedExtra() != null) extra = extra.add(it.getAgreedExtra());
        }
        order.setExtraAmount(extra);
        orderRepo.save(order);
    }

    /* ========================= 人工复核裁决 ========================= */

    /** 客服人工裁决：维持原报价 / 调整金额出新版 / 仅基础处理 / 拒绝承接，裁决后回到客户决策 */
    @Transactional
    public ManualReviewCase adjudicate(UserAccount cs, Long reviewId, ReviewOutcome outcome,
                                       BigDecimal adjustedExtra, String note) {
        ManualReviewCase rc = reviewRepo.findById(reviewId)
                .orElseThrow(() -> ApiException.notFound("人工复核单不存在"));
        if (rc.getOutcome() != ReviewOutcome.PENDING) {
            throw ApiException.conflict("复核单已裁决: " + rc.getOutcome());
        }
        AnomalyIssue issue = getIssue(rc.getIssueId());
        GarmentItem item = itemRepo.findById(issue.getItemId()).orElseThrow();
        if (item.getStatus() != GarmentStatus.MANUAL_REVIEW) {
            throw ApiException.conflict("衣物不在人工复核状态: " + item.getStatus());
        }
        rc.setOutcome(outcome);
        rc.setHandledBy(cs.getId());
        rc.setVerdictNote(note);
        rc.setHandledAt(LocalDateTime.now());

        switch (outcome) {
            case UPHOLD_QUOTE -> {
                // 维持原报价：原 ACTIVE 版本仍有效，仅刷新决策时限
                issue.setDecisionDeadline(LocalDateTime.now().plusHours(decisionHours));
            }
            case ADJUST_QUOTE -> {
                if (adjustedExtra == null || adjustedExtra.compareTo(BigDecimal.ZERO) < 0) {
                    throw ApiException.badRequest("调整报价必须提供非负 adjustedExtra");
                }
                rc.setAdjustedExtra(adjustedExtra);
                generateQuoteVersion(issue, "MANUAL_REVIEW", cs.getId(), adjustedExtra,
                        "人工复核调整报价：" + note);
                issue.setDecisionDeadline(LocalDateTime.now().plusHours(decisionHours));
            }
            case BASIC_ONLY -> {
                // 裁决为仅基础处理：直接按原服务继续（系统代客户落决策）
                issue.setStatus(AnomalyStatus.CLOSED);
                issue.setClosedAt(LocalDateTime.now());
                reviewRepo.save(rc);
                issueRepo.save(issue);
                sm.transition(item.getId(), GarmentStatus.AT_FACTORY, "REVIEW_VERDICT_BASIC",
                        "REVIEW", rc.getId(), cs.getId(), "人工裁决仅基础处理");
                item.setQcConclusion("BASIC_ONLY");
                itemRepo.save(item);
                return rc;
            }
            case REJECT_ORDER -> {
                LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
                issue.setStatus(AnomalyStatus.DECIDED);
                reviewRepo.save(rc);
                issueRepo.save(issue);
                RiderReclaim reclaim = createReclaim(item, order, "人工裁决拒绝承接：" + note);
                item.setReclaimId(reclaim.getId());
                itemRepo.save(item);
                sm.transition(item.getId(), GarmentStatus.RECLAIM_PENDING, "REVIEW_VERDICT_REJECT",
                        "REVIEW", rc.getId(), cs.getId(), "人工裁决拒绝承接");
                return rc;
            }
            default -> throw ApiException.badRequest("非法裁决结论");
        }
        issue.setStatus(AnomalyStatus.QUOTED);
        issue.setManualReviewId(null);
        issueRepo.save(issue);
        reviewRepo.save(rc);
        sm.transition(item.getId(), GarmentStatus.ANOMALY_QUOTED, "REVIEW_VERDICT",
                "REVIEW", rc.getId(), cs.getId(), "人工复核裁决：" + outcome);
        return rc;
    }

    /* ========================= 超时与查询 ========================= */

    /** 超时扫描：超过决策时限仍未决策的报价置 EXPIRED，衣物转人工队列（MANUAL_REVIEW） */
    @Transactional
    public int expireOverdue() {
        int n = 0;
        LocalDateTime now = LocalDateTime.now();
        for (AnomalyIssue issue : issueRepo.findByStatusOrderByCreatedAtAsc(AnomalyStatus.QUOTED)) {
            if (!issue.getDecisionDeadline().isBefore(now)) continue;
            QuoteVersion active = issue.getActiveQuoteVersionId() == null ? null
                    : quoteRepo.findById(issue.getActiveQuoteVersionId()).orElse(null);
            if (active != null && active.getStatus() == QuoteStatus.ACTIVE) {
                active.setStatus(QuoteStatus.EXPIRED);
                quoteRepo.save(active);
            }
            issue.setStatus(AnomalyStatus.REVIEW);
            issueRepo.save(issue);
            GarmentItem item = itemRepo.findById(issue.getItemId()).orElseThrow();
            if (item.getStatus() == GarmentStatus.ANOMALY_QUOTED) {
                sm.transition(item.getId(), GarmentStatus.MANUAL_REVIEW, "DECISION_TIMEOUT",
                        "ISSUE", issue.getId(), 0L, "客户超时未决策，转人工队列");
            }
            n++;
        }
        return n;
    }

    private QuoteVersion activeQuote(AnomalyIssue issue) {
        if (issue.getActiveQuoteVersionId() == null) {
            throw ApiException.conflict("异常项尚无有效报价版本");
        }
        return quoteRepo.findById(issue.getActiveQuoteVersionId())
                .orElseThrow(() -> ApiException.conflict("有效报价版本不存在"));
    }

    private void ensureOption(QuoteVersion q, QuoteOptionType opt) {
        for (String o : q.getOptions().split(",")) {
            if (o.equals(opt.name())) return;
        }
        throw ApiException.conflict("当前报价版本不支持该方案：" + opt + "，可选 " + q.getOptions());
    }

    private String normalizeSeverity(String s) {
        if (s == null || s.isBlank()) return "MEDIUM";
        return switch (s.toUpperCase()) {
            case "LOW", "MEDIUM", "HIGH" -> s.toUpperCase();
            default -> throw ApiException.badRequest("风险程度只能是 LOW/MEDIUM/HIGH");
        };
    }

    public AnomalyIssue getIssue(Long id) {
        return issueRepo.findById(id).orElseThrow(() -> ApiException.notFound("异常项不存在"));
    }

    public List<AnomalyIssue> issuesOfItem(Long itemId) {
        return issueRepo.findByItemIdOrderByCreatedAtAsc(itemId);
    }

    public List<IssueEvidence> evidencesOf(Long issueId) {
        return evidenceRepo.findByIssueIdOrderByShotRoundAsc(issueId);
    }

    public List<QuoteVersion> quotesOf(Long issueId) {
        return quoteRepo.findByIssueIdOrderByVersionNoAsc(issueId);
    }

    public List<CustomerDecision> decisionsOf(Long issueId) {
        return decisionRepo.findByIssueIdOrderByCreatedAtAsc(issueId);
    }

    public List<ManualReviewCase> pendingReviews() {
        return reviewRepo.findByOutcomeOrderByCreatedAtAsc(ReviewOutcome.PENDING);
    }

    public List<AnomalyIssue> openIssues() {
        return issueRepo.findAll().stream()
                .filter(i -> i.getStatus() != AnomalyStatus.CLOSED && i.getStatus() != AnomalyStatus.DECIDED)
                .toList();
    }

    public ManualReviewCase getReview(Long id) {
        return reviewRepo.findById(id).orElseThrow(() -> ApiException.notFound("复核单不存在"));
    }
}
