package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.InteractiveSession;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface InteractiveSessionRepository extends JpaRepository<InteractiveSession, Long> {
    Optional<InteractiveSession> findByCode(String code);
    List<InteractiveSession> findBySpaceIdOrderByIdDesc(Long spaceId);
}
