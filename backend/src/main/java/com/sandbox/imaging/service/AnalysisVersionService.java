package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.AnalysisVersion;
import com.sandbox.imaging.domain.Approval;
import com.sandbox.imaging.repo.AnalysisVersionRepository;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.TreeMap;

/**
 * 分析版本管理：研究目的或数据水位改变必须新建分析版本；
 * 特征算法升级只生成新版本并重新参加预算审查，不改写已发布结论或抹去其披露成本。
 */
@Service
public class AnalysisVersionService {

    private final AnalysisVersionRepository versionRepo;
    private final ApprovalService approvalService;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public AnalysisVersionService(AnalysisVersionRepository versionRepo,
                                  ApprovalService approvalService, AuditService auditService,
                                  ObjectMapper mapper) {
        this.versionRepo = versionRepo;
        this.approvalService = approvalService;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    @Transactional
    public AnalysisVersion create(Long approvalId, String purpose, String waterlineLabel,
                                  String algorithmVersion) {
        Approval approval = approvalService.mustGet(approvalId);
        approvalService.requireReadable(approval);
        if (!approval.getPurpose().equals(purpose)) {
            // 目的改变须新建版本是允许的；但若目的完全超出批准目的则拒绝
            throw ApiException.badRequest("研究目的与批准目的不一致，须为该目的申请新批准");
        }
        int nextNo = versionRepo.findByApprovalIdOrderByVersionNoDesc(approvalId).stream()
                .mapToInt(AnalysisVersion::getVersionNo).max().orElse(0) + 1;

        TreeMap<String, Object> spec = new TreeMap<>();
        spec.put("approvalCode", approval.getCode());
        spec.put("versionNo", nextNo);
        spec.put("purpose", purpose);
        spec.put("waterlineLabel", waterlineLabel);
        spec.put("algorithmVersion", algorithmVersion);
        spec.put("approvalSpecHash", approval.getSpecHash());
        String specJson = CanonicalJson.write(mapper, spec);

        AnalysisVersion v = new AnalysisVersion();
        v.setApproval(approval);
        v.setVersionNo(nextNo);
        v.setPurpose(purpose);
        v.setWaterlineLabel(waterlineLabel);
        v.setAlgorithmVersion(algorithmVersion);
        v.setVersionSpecJson(specJson);
        v.setSpecHash(CanonicalJson.sha256(specJson));
        v.setStatus("ACTIVE");
        v = versionRepo.save(v);
        auditService.log(approval.getSpace().getId(), SecurityUtils.currentUser(),
                "ANALYSIS_VERSION_CREATE",
                "批准 " + approval.getCode() + " 新建分析版本 v" + nextNo
                        + " 水线=" + waterlineLabel + " 算法=" + algorithmVersion);
        return v;
    }

    @Transactional(readOnly = true)
    public AnalysisVersion mustGet(Long id) {
        return versionRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("分析版本不存在: " + id));
    }

    @Transactional(readOnly = true)
    public java.util.List<AnalysisVersion> listByApproval(Long approvalId) {
        return versionRepo.findByApprovalIdOrderByVersionNoDesc(approvalId);
    }
}
