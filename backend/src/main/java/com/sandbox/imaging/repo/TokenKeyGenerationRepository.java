package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.TokenKeyGeneration;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface TokenKeyGenerationRepository extends JpaRepository<TokenKeyGeneration, Long> {
    Optional<TokenKeyGeneration> findByKid(String kid);
    List<TokenKeyGeneration> findByStatus(String status);
    List<TokenKeyGeneration> findAllByOrderByIdAsc();
}
