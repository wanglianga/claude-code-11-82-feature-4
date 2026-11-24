package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.TokenLineageEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface TokenLineageEventRepository extends JpaRepository<TokenLineageEvent, Long> {
    List<TokenLineageEvent> findByLinkVersionIdOrderByIdAsc(Long linkVersionId);
    List<TokenLineageEvent> findAllByOrderByIdDesc();
    List<TokenLineageEvent> findBySpaceIdOrderByIdDesc(Long spaceId);
}
