package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.ResearchSpace;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ResearchSpaceRepository extends JpaRepository<ResearchSpace, Long> {
    Optional<ResearchSpace> findByCode(String code);
}
