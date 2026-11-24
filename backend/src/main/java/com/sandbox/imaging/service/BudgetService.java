package com.sandbox.imaging.service;

import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.domain.BudgetLedger;
import com.sandbox.imaging.domain.ResearchSpace;
import com.sandbox.imaging.repo.BudgetLedgerRepository;
import com.sandbox.imaging.repo.ResearchSpaceRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;

/**
 * 披露预算：按研究空间 + 季度累计风险点。
 * 账本不随查询文本、版本号变化而重置；同一空间跨分析版本共用同一季度账本。
 */
@Service
public class BudgetService {

    private final BudgetLedgerRepository ledgerRepo;
    private final ResearchSpaceRepository spaceRepo;

    public BudgetService(BudgetLedgerRepository ledgerRepo, ResearchSpaceRepository spaceRepo) {
        this.ledgerRepo = ledgerRepo;
        this.spaceRepo = spaceRepo;
    }

    public static String currentQuarter(long epochMillis) {
        var date = Instant.ofEpochMilli(epochMillis).atOffset(ZoneOffset.UTC).toLocalDate();
        int q = (date.getMonthValue() - 1) / 3 + 1;
        return date.getYear() + "Q" + q;
    }

    @Transactional
    public BudgetLedger getOrCreate(ResearchSpace space, String quarter) {
        return ledgerRepo.findBySpaceIdAndQuarter(space.getId(), quarter)
                .orElseGet(() -> {
                    BudgetLedger l = new BudgetLedger();
                    l.setSpaceId(space.getId());
                    l.setQuarter(quarter);
                    l.setRiskCap(space.getQuarterlyBudget());
                    l.setPointsSpent(0);
                    return ledgerRepo.save(l);
                });
    }

    @Transactional(readOnly = true)
    public List<BudgetLedger> listForSpace(Long spaceId) {
        return ledgerRepo.findBySpaceIdOrderByQuarterDesc(spaceId);
    }

    /** 剩余点数；预算耗尽后只允许零窄化结果（由审查服务另行判定） */
    public int remaining(BudgetLedger ledger) {
        return Math.max(0, ledger.getRiskCap() - ledger.getPointsSpent());
    }

    @Transactional
    public void charge(BudgetLedger ledger, int points) {
        if (points < 0) throw ApiException.badRequest("风险点不能为负");
        if (ledger.getPointsSpent() + points > ledger.getRiskCap()) {
            throw ApiException.conflict("季度披露预算不足：需要 " + points
                    + " 点，剩余 " + remaining(ledger) + " 点");
        }
        ledger.setPointsSpent(ledger.getPointsSpent() + points);
        ledger.setUpdatedAt(System.currentTimeMillis());
        ledgerRepo.save(ledger);
    }

    /** 管理员调整空间季度风险上限，并同步当前季度账本 */
    @Transactional
    public BudgetLedger updateCap(ResearchSpace space, int newCap, String quarter) {
        if (newCap < 1) throw ApiException.badRequest("风险上限必须 >=1");
        space.setQuarterlyBudget(newCap);
        spaceRepo.save(space);
        BudgetLedger ledger = getOrCreate(space, quarter);
        ledger.setRiskCap(newCap);
        ledger.setUpdatedAt(System.currentTimeMillis());
        return ledgerRepo.save(ledger);
    }
}
