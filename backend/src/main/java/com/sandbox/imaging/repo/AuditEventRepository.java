package com.sandbox.imaging.repo;

import com.sandbox.imaging.domain.AuditEvent;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {
    List<AuditEvent> findBySpaceIdOrderByIdDesc(Long spaceId);
    List<AuditEvent> findAllByOrderByIdDesc();
}
