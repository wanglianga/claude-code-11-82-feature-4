package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.AnalysisVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface AnalysisVersionRepository extends JpaRepository<AnalysisVersion, Long> {
    List<AnalysisVersion> findByApprovalIdOrderByVersionNoDesc(Long approvalId);
    Optional<AnalysisVersion> findByApprovalIdAndVersionNo(Long approvalId, int versionNo);
}
