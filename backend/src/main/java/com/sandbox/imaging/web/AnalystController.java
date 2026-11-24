package com.sandbox.imaging.web;

import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.QueryRequest;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class AnalystController {

    private final AnalysisVersionService versionService;
    private final QueryService queryService;
    private final SessionService sessionService;
    private final DeliverableService deliverableService;
    private final BudgetService budgetService;
    private final AuditService auditService;
    private final ApprovalRepository approvalRepo;
    private final AnalysisVersionRepository versionRepo;
    private final QueryAttemptRepository attemptRepo;
    private final ConstraintRepository constraintRepo;
    private final InteractiveSessionRepository sessionRepo;
    private final CohortService cohortService;
    private final WaterlineRepository waterlineRepo;
    private final ViewMapper viewMapper;

    public AnalystController(AnalysisVersionService versionService, QueryService queryService,
                             SessionService sessionService, DeliverableService deliverableService,
                             BudgetService budgetService, AuditService auditService,
                             ApprovalRepository approvalRepo, AnalysisVersionRepository versionRepo,
                             QueryAttemptRepository attemptRepo, ConstraintRepository constraintRepo,
                             InteractiveSessionRepository sessionRepo, CohortService cohortService,
                             WaterlineRepository waterlineRepo, ViewMapper viewMapper) {
        this.versionService = versionService;
        this.queryService = queryService;
        this.sessionService = sessionService;
        this.deliverableService = deliverableService;
        this.budgetService = budgetService;
        this.auditService = auditService;
        this.approvalRepo = approvalRepo;
        this.versionRepo = versionRepo;
        this.attemptRepo = attemptRepo;
        this.constraintRepo = constraintRepo;
        this.sessionRepo = sessionRepo;
        this.cohortService = cohortService;
        this.waterlineRepo = waterlineRepo;
        this.viewMapper = viewMapper;
    }

    // ---------- 批准与版本 ----------

    @GetMapping("/approvals")
    public List<Map<String, Object>> approvals() {
        return approvalRepo.findAll().stream().map(viewMapper::approval).toList();
    }

    @GetMapping("/approvals/{id}")
    public Map<String, Object> approval(@PathVariable Long id) {
        return viewMapper.approval(approvalRepo.findById(id).orElseThrow());
    }

    @PostMapping("/approvals/{id}/versions")
    public Map<String, Object> createVersion(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return viewMapper.version(versionService.create(id, body.get("purpose"),
                body.get("waterlineLabel"), body.getOrDefault("algorithmVersion", "feat-v1")));
    }

    @GetMapping("/approvals/{id}/versions")
    public List<Map<String, Object>> versions(@PathVariable Long id) {
        return versionService.listByApproval(id).stream().map(viewMapper::version).toList();
    }

    @GetMapping("/versions/{id}")
    public Map<String, Object> version(@PathVariable Long id) {
        return viewMapper.version(versionService.mustGet(id));
    }

    // ---------- 队列复现查看 ----------

    @GetMapping("/versions/{id}/cohort")
    public Map<String, Object> versionCohort(@PathVariable Long id) {
        AnalysisVersion v = versionService.mustGet(id);
        Waterline wl = waterlineRepo.findByLabel(v.getWaterlineLabel()).orElseThrow();
        Cohort c = cohortService.compileCohort(v.getApproval(), wl);
        return Map.of("cohortHash", c.getCohortHash(), "memberCount", c.getMemberCount(),
                "cohortSpec", c.getCohortSpecJson(),
                "memberKeys", cohortService.memberKeys(c));
    }

    // ---------- 交互会话 ----------

    @PostMapping("/sessions")
    public InteractiveSession openSession(@RequestBody Map<String, Object> body) {
        return sessionService.open(Long.valueOf(body.get("analysisVersionId").toString()),
                (String) body.get("purpose"));
    }

    @PostMapping("/sessions/{id}/close")
    public InteractiveSession closeSession(@PathVariable Long id) {
        return sessionService.close(id);
    }

    @GetMapping("/sessions")
    public List<InteractiveSession> sessions(@RequestParam(required = false) Long spaceId) {
        return spaceId == null ? sessionRepo.findAll() : sessionService.listBySpace(spaceId);
    }

    // ---------- 查询审查 ----------

    /** 干跑：只返回联合推断报告与预算影响，不落账、不写约束 */
    @PostMapping("/queries/dry-run")
    public DisclosureReviewService.ReviewOutcome dryRun(@RequestBody QueryRequest req) {
        return queryService.submit(req, true);
    }

    /** 正式提交：通过审查才落账并返回脱敏结果 */
    @PostMapping("/queries")
    public DisclosureReviewService.ReviewOutcome submit(@RequestBody QueryRequest req) {
        return queryService.submit(req, false);
    }

    /** 固定表组：整组联合核验，全有或全无 */
    @PostMapping("/table-sets/dry-run")
    public DisclosureReviewService.ReviewOutcome dryRunSet(@RequestBody TableSetRequest req) {
        return queryService.submitTableSet(req.analysisVersionId, req.sessionId, req.queries, true);
    }

    @PostMapping("/table-sets")
    public DisclosureReviewService.ReviewOutcome submitSet(@RequestBody TableSetRequest req) {
        return queryService.submitTableSet(req.analysisVersionId, req.sessionId, req.queries, false);
    }

    @GetMapping("/attempts")
    public List<QueryAttempt> attempts(@RequestParam(required = false) Long spaceId,
                                       @RequestParam(required = false) Long versionId) {
        if (versionId != null) return attemptRepo.findByAnalysisVersionIdOrderByIdDesc(versionId);
        if (spaceId != null) return attemptRepo.findBySpaceIdOrderByIdDesc(spaceId);
        return attemptRepo.findAll();
    }

    // ---------- 交付与复现 ----------

    @PostMapping("/deliverables")
    public Deliverable freeze(@RequestBody Map<String, Object> body) {
        return deliverableService.freeze(Long.valueOf(body.get("attemptId").toString()),
                (String) body.getOrDefault("title", "冻结结果表"),
                (String) body.getOrDefault("kind", "TABLE"));
    }

    @GetMapping("/deliverables")
    public List<Deliverable> deliverables(@RequestParam(required = false) Long spaceId) {
        return spaceId == null ? deliverableService.listBySpace(1L) : deliverableService.listBySpace(spaceId);
    }

    @GetMapping("/deliverables/{id}")
    public Deliverable deliverable(@PathVariable Long id) {
        return deliverableService.mustGet(id);
    }

    @PostMapping("/deliverables/{id}/reproduce")
    public Map<String, Object> reproduce(@PathVariable Long id) {
        return deliverableService.reproduce(id);
    }

    // ---------- 预算、约束图、审计 ----------

    @GetMapping("/spaces/{spaceId}/budget")
    public List<BudgetLedger> budget(@PathVariable Long spaceId) {
        return budgetService.listForSpace(spaceId);
    }

    @GetMapping("/spaces/{spaceId}/constraints")
    public List<Constraint> constraints(@PathVariable Long spaceId) {
        return constraintRepo.findBySpaceIdOrderByIdAsc(spaceId);
    }

    @GetMapping("/audit")
    public List<AuditEvent> audit(@RequestParam(required = false) Long spaceId) {
        return auditService.list(spaceId);
    }

    public static class TableSetRequest {
        public Long analysisVersionId;
        public Long sessionId;
        public List<QueryRequest> queries;
    }
}
