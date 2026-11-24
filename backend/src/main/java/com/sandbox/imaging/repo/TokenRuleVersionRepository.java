package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.TokenRuleVersion;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface TokenRuleVersionRepository extends JpaRepository<TokenRuleVersion, Long> {
    Optional<TokenRuleVersion> findByLabel(String label);
}
