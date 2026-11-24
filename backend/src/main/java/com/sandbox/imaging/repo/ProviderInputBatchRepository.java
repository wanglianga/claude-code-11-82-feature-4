package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.ProviderInputBatch;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ProviderInputBatchRepository extends JpaRepository<ProviderInputBatch, Long> {
    Optional<ProviderInputBatch> findByCode(String code);
    List<ProviderInputBatch> findByProviderCodeOrderByIdDesc(String providerCode);
}
