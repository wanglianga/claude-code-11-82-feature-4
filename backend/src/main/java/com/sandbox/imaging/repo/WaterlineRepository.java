package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.Waterline;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface WaterlineRepository extends JpaRepository<Waterline, Long> {
    Optional<Waterline> findByLabel(String label);
    List<Waterline> findAllByOrderByCutoffAtDesc();
}
