package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.ProviderProfile;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProviderProfileRepository extends JpaRepository<ProviderProfile, Long> {
    Optional<ProviderProfile> findByProviderCode(String providerCode);
}
