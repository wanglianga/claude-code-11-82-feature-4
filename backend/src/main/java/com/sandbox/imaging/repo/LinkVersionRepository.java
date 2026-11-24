package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.LinkVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LinkVersionRepository extends JpaRepository<LinkVersion, Long> {
    Optional<LinkVersion> findByCode(String code);
    List<LinkVersion> findBySpaceIdOrderByIdDesc(Long spaceId);
    List<LinkVersion> findAllByOrderByIdDesc();
    long countByProviderBatchIdAndStatusNot(Long providerBatchId, String status);
    List<LinkVersion> findByProviderBatchId(Long providerBatchId);
}
