package com.citylaundry.service;

import com.citylaundry.model.MemberCard;
import com.citylaundry.model.MemberTransaction;
import com.citylaundry.repo.MemberCardRepo;
import com.citylaundry.repo.MemberTransactionRepo;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.List;

@Service
public class MemberService {
    private final MemberCardRepo cardRepo;
    private final MemberTransactionRepo txRepo;

    public MemberService(MemberCardRepo cardRepo, MemberTransactionRepo txRepo) {
        this.cardRepo = cardRepo;
        this.txRepo = txRepo;
    }

    public MemberCard myCard(Long customerId) {
        return cardRepo.findByCustomerId(customerId).orElse(null);
    }

    @Transactional
    public MemberCard recharge(Long customerId, BigDecimal amount) {
        if (amount == null || amount.compareTo(BigDecimal.ZERO) <= 0) {
            throw new IllegalArgumentException("充值金额必须大于 0");
        }
        MemberCard card = cardRepo.findByCustomerId(customerId).orElseThrow();
        card.setBalance(card.getBalance().add(amount));
        card = cardRepo.save(card);

        MemberTransaction tx = new MemberTransaction();
        tx.setCardId(card.getId());
        tx.setAmount(amount);
        tx.setType("RECHARGE");
        tx.setNote("会员卡充值");
        txRepo.save(tx);
        return card;
    }

    public List<MemberTransaction> transactions(Long cardId) {
        return txRepo.findByCardIdOrderByCreatedAtDesc(cardId);
    }
}
