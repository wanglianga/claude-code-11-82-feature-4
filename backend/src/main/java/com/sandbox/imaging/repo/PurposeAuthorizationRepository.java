package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.PurposeAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface PurposeAuthorizationRepository extends JpaRepository<PurposeAuthorization, Long> {
    Optional<PurposeAuthorization> findByCode(String code);
    List<PurposeAuthorization> findByApprovalIdOrderByIdDesc(Long approvalId);
}
