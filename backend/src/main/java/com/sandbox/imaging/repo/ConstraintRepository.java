package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.Constraint;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConstraintRepository extends JpaRepository<Constraint, Long> {
    List<Constraint> findBySpaceIdAndAnalysisVersionIdOrderByIdAsc(Long spaceId, Long versionId);
    List<Constraint> findBySpaceIdOrderByIdAsc(Long spaceId);
}
