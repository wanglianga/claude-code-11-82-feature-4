package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.QueryAttempt;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface QueryAttemptRepository extends JpaRepository<QueryAttempt, Long> {
    List<QueryAttempt> findByAnalysisVersionIdOrderByIdDesc(Long versionId);
    List<QueryAttempt> findBySpaceIdOrderByIdDesc(Long spaceId);
    List<QueryAttempt> findBySessionIdOrderByIdAsc(Long sessionId);
}
