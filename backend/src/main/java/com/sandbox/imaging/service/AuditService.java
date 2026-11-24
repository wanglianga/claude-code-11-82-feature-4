package com.sandbox.imaging.service;

import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.domain.AuditEvent;
import com.sandbox.imaging.repo.AuditEventRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
public class AuditService {

    private final AuditEventRepository auditRepo;

    public AuditService(AuditEventRepository auditRepo) {
        this.auditRepo = auditRepo;
    }

    @Transactional
    public void log(Long spaceId, String actor, String action, String detail) {
        auditRepo.save(new AuditEvent(spaceId, actor, action, detail));
    }

    @Transactional(readOnly = true)
    public List<AuditEvent> list(Long spaceId) {
        return spaceId == null ? auditRepo.findAllByOrderByIdDesc()
                : auditRepo.findBySpaceIdOrderByIdDesc(spaceId);
    }

    public static void requireTrue(boolean condition, String message) {
        if (!condition) throw ApiException.badRequest(message);
    }
}
