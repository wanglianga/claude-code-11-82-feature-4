package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.BudgetLedger;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface BudgetLedgerRepository extends JpaRepository<BudgetLedger, Long> {
    Optional<BudgetLedger> findBySpaceIdAndQuarter(Long spaceId, String quarter);
    List<BudgetLedger> findBySpaceIdOrderByQuarterDesc(Long spaceId);
}
