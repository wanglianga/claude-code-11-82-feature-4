package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.LinkMatchSeal;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface LinkMatchSealRepository extends JpaRepository<LinkMatchSeal, Long> {
    Optional<LinkMatchSeal> findByLinkVersionId(Long linkVersionId);
    List<LinkMatchSeal> findByStatus(String status);
}
