package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.Cohort;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface CohortRepository extends JpaRepository<Cohort, Long> {
    Optional<Cohort> findByApprovalIdAndWaterlineId(Long approvalId, Long waterlineId);
    List<Cohort> findByApprovalIdOrderByIdDesc(Long approvalId);
}
