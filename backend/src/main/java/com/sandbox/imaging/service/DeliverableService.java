package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.CanonicalQuery;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 交付器与复现器。
 * 冻结：结果 + 算法版本 + 批准快照 + 水线 + 规范化查询 + 审查证明 + sha256 一并封存。
 * 复现：按当时审批与数据水线重建结果并与冻结载荷比对；
 * 撤回/到期后复现仍允许（审计用途），但任何新查询在访问闸门处被拒绝。
 */
@Service
public class DeliverableService {

    private final DeliverableRepository deliverableRepo;
    private final QueryAttemptRepository attemptRepo;
    private final ApprovalRepository approvalRepo;
    private final WaterlineRepository waterlineRepo;
    private final AnalysisVersionRepository versionRepo;
    private final CohortService cohortService;
    private final QueryExecutor executor;
    private final OutputSanitizer sanitizer;
    private final ConstraintRepository constraintRepo;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public DeliverableService(DeliverableRepository deliverableRepo, QueryAttemptRepository attemptRepo,
                              ApprovalRepository approvalRepo, WaterlineRepository waterlineRepo,
                              AnalysisVersionRepository versionRepo, CohortService cohortService,
                              QueryExecutor executor, OutputSanitizer sanitizer,
                              ConstraintRepository constraintRepo,
                              AuditService auditService, ObjectMapper mapper) {
        this.deliverableRepo = deliverableRepo;
        this.attemptRepo = attemptRepo;
        this.approvalRepo = approvalRepo;
        this.waterlineRepo = waterlineRepo;
        this.versionRepo = versionRepo;
        this.cohortService = cohortService;
        this.executor = executor;
        this.sanitizer = sanitizer;
        this.constraintRepo = constraintRepo;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    @Transactional
    public Deliverable freeze(Long attemptId, String title, String kind) {
        QueryAttempt attempt = attemptRepo.findById(attemptId)
                .orElseThrow(() -> ApiException.notFound("尝试记录不存在: " + attemptId));
        if (!"ALLOWED".equals(attempt.getStatus())) {
            throw ApiException.conflict("仅 ALLOWED 的结果可冻结交付，当前状态 " + attempt.getStatus());
        }
        AnalysisVersion version = versionRepo.findById(attempt.getAnalysisVersionId())
                .orElseThrow(() -> ApiException.notFound("分析版本不存在"));
        Approval approval = approvalRepo.findById(attempt.getApprovalId())
                .orElseThrow(() -> ApiException.notFound("批准不存在"));
        Waterline waterline = waterlineRepo.findByLabel(version.getWaterlineLabel())
                .orElseThrow(() -> ApiException.badRequest("水线不存在"));
        Cohort cohort = cohortService.compileCohort(approval, waterline);

        TreeMap<String, Object> manifest = new TreeMap<>();
        manifest.put("frozenAt", System.currentTimeMillis());
        manifest.put("frozenBy", SecurityUtils.currentUser());
        manifest.put("approval", Map.of(
                "code", approval.getCode(),
                "title", approval.getTitle(),
                "specHash", approval.getSpecHash(),
                "statusAtFreeze", approval.getStatus(),
                "compiledSpec", cohortService.readCompiled(approval)));
        manifest.put("analysisVersion", Map.of(
                "versionNo", version.getVersionNo(),
                "specHash", version.getSpecHash(),
                "algorithmVersion", version.getAlgorithmVersion()));
        manifest.put("waterline", Map.of(
                "label", waterline.getLabel(),
                "cutoffAt", waterline.getCutoffAt()));
        manifest.put("cohort", Map.of(
                "cohortHash", cohort.getCohortHash(),
                "memberCount", cohort.getMemberCount()));
        manifest.put("canonicalQuery", readTree(attempt.getCanonicalQueryJson()));
        manifest.put("queryHash", attempt.getQueryHash());
        manifest.put("reviewProof", readTree(attempt.getReviewJson()));
        List<Map<String, Object>> constraintSummary = new ArrayList<>();
        for (Constraint c : constraintRepo.findBySpaceIdOrderByIdAsc(attempt.getSpaceId())) {
            constraintSummary.add(Map.of(
                    "id", c.getId(),
                    "kind", c.getConstraintKind(),
                    "operator", c.getOperator(),
                    "rhs", c.getRhs(),
                    "coeffs", readTree(c.getCoeffsJson())));
        }
        manifest.put("constraintGraph", constraintSummary);

        String payload = attempt.getOutputJson();
        String manifestJson = CanonicalJson.write(mapper, manifest);

        Deliverable d = new Deliverable();
        d.setCode("DLV-" + UUID.randomUUID().toString().substring(0, 12));
        d.setSpaceId(attempt.getSpaceId());
        d.setApprovalId(attempt.getApprovalId());
        d.setAnalysisVersionId(version.getId());
        d.setAttemptId(attemptId);
        d.setTitle(title);
        d.setKind(kind == null ? "TABLE" : kind);
        d.setFrozenPayloadJson(payload);
        d.setManifestJson(manifestJson);
        d.setPayloadSha256(CanonicalJson.sha256(CanonicalJson.write(mapper, readTree(payload))));
        d.setStatus("FROZEN");
        d = deliverableRepo.save(d);
        auditService.log(d.getSpaceId(), SecurityUtils.currentUser(), "DELIVER_FREEZE",
                "冻结交付物 " + d.getCode() + " sha256=" + d.getPayloadSha256());
        return d;
    }

    /**
     * 复现：按交付物记录的当时批准与水线重建。
     * 注意：这是审计动作，即使批准已撤回/到期仍可执行；新数据读取仍由查询闸门拒绝。
     * 非只读事务：复现过程会写审计事件，并可能惰性固化同规格队列。
     */
    @Transactional
    public Map<String, Object> reproduce(Long deliverableId) {
        Deliverable d = deliverableRepo.findById(deliverableId)
                .orElseThrow(() -> ApiException.notFound("交付物不存在: " + deliverableId));
        Map<String, Object> manifest = readTree(d.getManifestJson());
        Approval approval = approvalRepo.findById(d.getApprovalId())
                .orElseThrow(() -> ApiException.notFound("批准已不存在，无法复现"));
        AnalysisVersion version = versionRepo.findById(d.getAnalysisVersionId())
                .orElseThrow(() -> ApiException.notFound("版本已不存在，无法复现"));
        @SuppressWarnings("unchecked")
        Map<String, Object> wlInfo = (Map<String, Object>) manifest.get("waterline");
        Waterline waterline = waterlineRepo.findByLabel((String) wlInfo.get("label"))
                .orElseThrow(() -> ApiException.badRequest("水线不存在"));

        // 1. 队列复现：重新解析成员并比对 cohort_hash
        List<String> membersNow = cohortService.resolveMembers(approval, waterline);
        Cohort current = cohortService.compileCohort(approval, waterline);
        @SuppressWarnings("unchecked")
        Map<String, Object> cohortInfo = (Map<String, Object>) manifest.get("cohort");
        String frozenCohortHash = (String) cohortInfo.get("cohortHash");
        boolean cohortMatch = Objects.equals(frozenCohortHash, current.getCohortHash());

        // 2. 按冻结的规范化查询与算法版本重新执行
        CanonicalQuery cq = mapper.convertValue(manifest.get("canonicalQuery"), CanonicalQuery.class);
        QueryExecutor.RawTable reproducedRaw = executor.execute(cq, membersNow,
                version.getAlgorithmVersion());

        // 3. 用同一脱敏器按冻结时的最小单元/舍入规则重建输出，与冻结载荷逐单元比对
        @SuppressWarnings("unchecked")
        Map<String, Object> reviewProof = (Map<String, Object>) manifest.get("reviewProof");
        int minUnit = ((Number) reviewProof.get("minUnit")).intValue();
        Integer roundingStep = reviewProof.get("roundingStep") == null ? null
                : ((Number) reviewProof.get("roundingStep")).intValue();
        List<OutputCell> reproducedOutput = sanitizer.sanitize(
                reproducedRaw, cq.groups, minUnit, roundingStep);
        String reproducedJson = CanonicalJson.write(mapper, reproducedOutput);
        String frozenJson = CanonicalJson.write(mapper, readTree(d.getFrozenPayloadJson()));
        boolean outputMatch = Objects.equals(reproducedJson, frozenJson);
        boolean hashIntact = Objects.equals(d.getPayloadSha256(),
                CanonicalJson.sha256(CanonicalJson.write(mapper, readTree(d.getFrozenPayloadJson()))));

        Map<String, Object> result = new TreeMap<>();
        result.put("deliverableCode", d.getCode());
        result.put("approvalReadableNow", isReadable(approval));
        result.put("cohortHashMatch", cohortMatch);
        result.put("frozenCohortHash", frozenCohortHash);
        result.put("reproducedCohortHash", current.getCohortHash());
        result.put("memberCountNow", membersNow.size());
        result.put("payloadHashIntact", hashIntact);
        result.put("outputMatch", outputMatch);
        result.put("frozenOutput", readTree(d.getFrozenPayloadJson()));
        result.put("reproducedOutput", readTree(reproducedJson));
        result.put("conclusion", cohortMatch && outputMatch && hashIntact
                ? "REPRODUCED：按当时审批与水线逐单元重建成功"
                : "DIVERGED：复现结果与冻结载荷不一致，需审计调查");
        auditService.log(d.getSpaceId(), SecurityUtils.currentUser(), "REPRODUCTION",
                "复现 " + d.getCode() + " => " + result.get("conclusion"));
        return result;
    }

    private boolean isReadable(Approval a) {
        long now = System.currentTimeMillis();
        return "ACTIVE".equals(a.getStatus()) && now >= a.getValidFrom() && now <= a.getValidTo();
    }

    @Transactional(readOnly = true)
    public List<Deliverable> listBySpace(Long spaceId) {
        return deliverableRepo.findBySpaceIdOrderByIdDesc(spaceId);
    }

    @Transactional(readOnly = true)
    public Deliverable mustGet(Long id) {
        return deliverableRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("交付物不存在: " + id));
    }

    @SuppressWarnings("unchecked")
    private <T> T readTree(String json) {
        try {
            return (T) mapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
