package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.Deliverable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface DeliverableRepository extends JpaRepository<Deliverable, Long> {
    Optional<Deliverable> findByCode(String code);
    List<Deliverable> findBySpaceIdOrderByIdDesc(Long spaceId);
    List<Deliverable> findByAnalysisVersionIdOrderByIdDesc(Long versionId);
}
