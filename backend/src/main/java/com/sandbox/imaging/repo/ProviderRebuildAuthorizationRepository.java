package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.ProviderRebuildAuthorization;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProviderRebuildAuthorizationRepository extends JpaRepository<ProviderRebuildAuthorization, Long> {
    Optional<ProviderRebuildAuthorization> findByLinkVersionId(Long linkVersionId);
}
