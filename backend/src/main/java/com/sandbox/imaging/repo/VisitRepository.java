package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.Visit;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface VisitRepository extends JpaRepository<Visit, Long> {
    List<Visit> findByPatientIdIn(List<Long> patientIds);
}
