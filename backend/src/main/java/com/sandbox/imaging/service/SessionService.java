package com.sandbox.imaging.service;

import com.sandbox.imaging.domain.InteractiveSession;
import com.sandbox.imaging.repo.AnalysisVersionRepository;
import com.sandbox.imaging.repo.InteractiveSessionRepository;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/** 交互会话：维护会话级语义与约束图，从共同预算扣减 */
@Service
public class SessionService {

    private final InteractiveSessionRepository sessionRepo;
    private final AnalysisVersionRepository versionRepo;
    private final AuditService auditService;

    public SessionService(InteractiveSessionRepository sessionRepo,
                          AnalysisVersionRepository versionRepo, AuditService auditService) {
        this.sessionRepo = sessionRepo;
        this.versionRepo = versionRepo;
        this.auditService = auditService;
    }

    @Transactional
    public InteractiveSession open(Long analysisVersionId, String purpose) {
        var version = versionRepo.findById(analysisVersionId)
                .orElseThrow(() -> com.sandbox.imaging.common.ApiException
                        .notFound("分析版本不存在: " + analysisVersionId));
        InteractiveSession s = new InteractiveSession();
        s.setCode("SES-" + UUID.randomUUID().toString().substring(0, 12));
        s.setSpaceId(version.getApproval().getSpace().getId());
        s.setAnalysisVersionId(analysisVersionId);
        s.setOwner(SecurityUtils.currentUser());
        s.setPurpose(purpose == null || purpose.isBlank() ? "交互分析" : purpose);
        s.setStatus("OPEN");
        s = sessionRepo.save(s);
        auditService.log(s.getSpaceId(), s.getOwner(), "SESSION_OPEN",
                "打开交互会话 " + s.getCode());
        return s;
    }

    @Transactional
    public InteractiveSession close(Long id) {
        InteractiveSession s = sessionRepo.findById(id)
                .orElseThrow(() -> com.sandbox.imaging.common.ApiException.notFound("会话不存在"));
        s.setStatus("CLOSED");
        s.setClosedAt(System.currentTimeMillis());
        auditService.log(s.getSpaceId(), SecurityUtils.currentUser(), "SESSION_CLOSE",
                "关闭会话 " + s.getCode());
        return sessionRepo.save(s);
    }

    @Transactional(readOnly = true)
    public List<InteractiveSession> listBySpace(Long spaceId) {
        return sessionRepo.findBySpaceIdOrderByIdDesc(spaceId);
    }
}
