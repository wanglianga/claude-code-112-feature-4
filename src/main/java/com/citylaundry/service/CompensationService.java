package com.citylaundry.service;

import com.citylaundry.common.ApiException;
import com.citylaundry.model.*;
import com.citylaundry.model.Enums.*;
import com.citylaundry.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 赔付：严格按单件衣物立案；客服立案 → 财务审批 → 赔付入账（可走保险） */
@Service
public class CompensationService {
    private final CompensationCaseRepo caseRepo;
    private final ExceptionTicketRepo ticketRepo;
    private final GarmentItemRepo itemRepo;
    private final LaundryOrderRepo orderRepo;
    private final MemberCardRepo cardRepo;
    private final MemberTransactionRepo txRepo;

    public CompensationService(CompensationCaseRepo caseRepo, ExceptionTicketRepo ticketRepo,
                               GarmentItemRepo itemRepo, LaundryOrderRepo orderRepo,
                               MemberCardRepo cardRepo, MemberTransactionRepo txRepo) {
        this.caseRepo = caseRepo;
        this.ticketRepo = ticketRepo;
        this.itemRepo = itemRepo;
        this.orderRepo = orderRepo;
        this.cardRepo = cardRepo;
        this.txRepo = txRepo;
    }

    /** 客服基于异常工单对【单件衣物】立案赔付 —— 同一订单多件衣物各自独立立案 */
    @Transactional
    public CompensationCase createCase(UserAccount cs, Long ticketId, BigDecimal amount, String responsibility,
                                       boolean insuranceClaim, String insurancePolicyNo, String reason) {
        ExceptionTicket ticket = ticketRepo.findById(ticketId)
                .orElseThrow(() -> ApiException.notFound("工单不存在"));
        if (ticket.getStatus() == TicketStatus.RESOLVED || ticket.getStatus() == TicketStatus.CLOSED) {
            throw ApiException.conflict("工单已办结，不能立案赔付");
        }
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw ApiException.badRequest("赔付金额必须大于 0");
        }
        GarmentItem item = itemRepo.findById(ticket.getItemId()).orElseThrow();
        // 赔付金额不得超过该件衣物声明价值的 10 倍（行业惯例上限）
        BigDecimal cap = item.getDeclaredValue().multiply(BigDecimal.TEN);
        if (item.getDeclaredValue().compareTo(BigDecimal.ZERO) > 0 && amount.compareTo(cap) > 0) {
            throw ApiException.badRequest("赔付金额超过该衣物声明价值 10 倍上限(" + cap + ")");
        }
        // 同一工单已有未结案赔付则不允许重复立案
        List<CompensationCase> existing = caseRepo.findByTicketId(ticketId);
        for (CompensationCase c : existing) {
            if (c.getStatus() != CompensationStatus.REJECTED) {
                throw ApiException.conflict("该工单已存在赔付案 " + c.getCaseNo() + "（状态 " + c.getStatus() + "）");
            }
        }
        if (insuranceClaim && (insurancePolicyNo == null || insurancePolicyNo.isBlank())) {
            throw ApiException.badRequest("走保险赔付必须填写保险单号 insurancePolicyNo");
        }

        CompensationCase c = new CompensationCase();
        c.setItemId(item.getId());
        c.setTicketId(ticket.getId());
        c.setAmount(amount);
        c.setResponsibility(responsibility == null || responsibility.isBlank() ? "STORE" : responsibility);
        c.setInsuranceClaim(insuranceClaim);
        c.setInsurancePolicyNo(insurancePolicyNo);
        c.setReason(reason);
        c.setCreatedBy(cs.getId());
        c.setStatus(CompensationStatus.PENDING);
        c.setCaseNo("TMP");
        c = caseRepo.save(c);
        c.setCaseNo(String.format("C%08d", c.getId()));
        c = caseRepo.save(c);

        if (ticket.getStatus() == TicketStatus.OPEN) {
            ticket.setStatus(TicketStatus.PROCESSING);
            ticket.setHandlerId(cs.getId());
            ticketRepo.save(ticket);
        }
        return c;
    }

    /** 财务审批：PENDING → APPROVED / REJECTED */
    @Transactional
    public CompensationCase approve(UserAccount finance, Long caseId, boolean pass, String reason) {
        CompensationCase c = getCase(caseId);
        if (c.getStatus() != CompensationStatus.PENDING) {
            throw ApiException.conflict("赔付案不在待审批状态: " + c.getStatus());
        }
        c.setApprovedBy(finance.getId());
        if (pass) {
            c.setStatus(c.isInsuranceClaim() ? CompensationStatus.INSURANCE_FILED : CompensationStatus.APPROVED);
        } else {
            if (reason == null || reason.isBlank()) throw ApiException.badRequest("驳回必须填写原因");
            c.setStatus(CompensationStatus.REJECTED);
            c.setReason((c.getReason() == null ? "" : c.getReason() + " | ") + "财务驳回: " + reason);
        }
        return caseRepo.save(c);
    }

    /** 财务赔付执行：APPROVED/INSURANCE_FILED → PAID，金额划入顾客会员卡 */
    @Transactional
    public CompensationCase pay(UserAccount finance, Long caseId) {
        CompensationCase c = getCase(caseId);
        if (c.getStatus() != CompensationStatus.APPROVED && c.getStatus() != CompensationStatus.INSURANCE_FILED) {
            throw ApiException.conflict("赔付案未审批通过，不能执行赔付: " + c.getStatus());
        }
        GarmentItem item = itemRepo.findById(c.getItemId()).orElseThrow();
        LaundryOrder order = orderRepo.findById(item.getOrderId()).orElseThrow();
        MemberCard card = cardRepo.findById(order.getMemberCardId()).orElseThrow();

        card.setBalance(card.getBalance().add(c.getAmount()));
        cardRepo.save(card);

        MemberTransaction tx = new MemberTransaction();
        tx.setCardId(card.getId());
        tx.setOrderId(order.getId());
        tx.setCompensationId(c.getId());
        tx.setAmount(c.getAmount());
        tx.setType("COMPENSATION");
        tx.setNote("赔付案 " + c.getCaseNo() + (c.isInsuranceClaim() ? "（保险赔付 单号:" + c.getInsurancePolicyNo() + "）" : "")
                + " 针对衣物 " + item.getItemCode());
        txRepo.save(tx);

        c.setStatus(CompensationStatus.PAID);
        c.setPaidAt(LocalDateTime.now());
        c = caseRepo.save(c);

        // 关联工单办结
        ExceptionTicket ticket = ticketRepo.findById(c.getTicketId()).orElseThrow();
        if (ticket.getStatus() != TicketStatus.RESOLVED && ticket.getStatus() != TicketStatus.CLOSED) {
            ticket.setStatus(TicketStatus.RESOLVED);
            ticket.setResolution("赔付案 " + c.getCaseNo() + " 已赔付 ¥" + c.getAmount());
            ticket.setResolvedAt(LocalDateTime.now());
            ticketRepo.save(ticket);
        }
        return c;
    }

    public CompensationCase getCase(Long id) {
        return caseRepo.findById(id).orElseThrow(() -> ApiException.notFound("赔付案不存在"));
    }

    public List<CompensationCase> listAll() { return caseRepo.findAll(); }

    public List<CompensationCase> casesOfItem(Long itemId) { return caseRepo.findByItemId(itemId); }
}
