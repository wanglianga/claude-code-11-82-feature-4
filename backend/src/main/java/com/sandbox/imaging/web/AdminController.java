package com.sandbox.imaging.web;

import com.sandbox.imaging.domain.Approval;
import com.sandbox.imaging.domain.Cohort;
import com.sandbox.imaging.domain.Waterline;
import com.sandbox.imaging.dto.ApprovalSpec;
import com.sandbox.imaging.repo.CohortRepository;
import com.sandbox.imaging.repo.ResearchSpaceRepository;
import com.sandbox.imaging.repo.WaterlineRepository;
import com.sandbox.imaging.service.ApprovalService;
import com.sandbox.imaging.service.BudgetService;
import com.sandbox.imaging.service.CohortService;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/** 管理员：批准编译/激活/撤回、水线、队列查看、预算治理 */
@RestController
@RequestMapping("/api/admin")
public class AdminController {

    private final ApprovalService approvalService;
    private final CohortService cohortService;
    private final CohortRepository cohortRepo;
    private final ResearchSpaceRepository spaceRepo;
    private final WaterlineRepository waterlineRepo;
    private final ViewMapper viewMapper;
    private final BudgetService budgetService;

    public AdminController(ApprovalService approvalService, CohortService cohortService,
                           CohortRepository cohortRepo, ResearchSpaceRepository spaceRepo,
                           WaterlineRepository waterlineRepo, ViewMapper viewMapper,
                           BudgetService budgetService) {
        this.approvalService = approvalService;
        this.cohortService = cohortService;
        this.cohortRepo = cohortRepo;
        this.spaceRepo = spaceRepo;
        this.waterlineRepo = waterlineRepo;
        this.viewMapper = viewMapper;
        this.budgetService = budgetService;
    }

    @GetMapping("/spaces")
    public Object spaces() {
        return spaceRepo.findAll().stream().map(s -> Map.of(
                "id", s.getId(), "code", s.getCode(), "name", s.getName(),
                "minPublishUnit", s.getMinPublishUnit(),
                "quarterlyBudget", s.getQuarterlyBudget())).toList();
    }

    @GetMapping("/waterlines")
    public List<Waterline> waterlines() {
        return waterlineRepo.findAllByOrderByCutoffAtDesc();
    }

    @PostMapping("/spaces/{spaceId}/approvals")
    public Map<String, Object> compile(@PathVariable Long spaceId, @RequestBody CompileRequest req) {
        return viewMapper.approval(approvalService.compile(spaceId, req.code, req.title, req.spec));
    }

    @PostMapping("/approvals/{id}/activate")
    public Map<String, Object> activate(@PathVariable Long id) {
        return viewMapper.approval(approvalService.activate(id));
    }

    @PostMapping("/approvals/{id}/revoke")
    public Map<String, Object> revoke(@PathVariable Long id, @RequestBody Map<String, String> body) {
        return viewMapper.approval(approvalService.revoke(id, body.getOrDefault("reason", "管理员撤回字段授权")));
    }

    @GetMapping("/spaces/{spaceId}/approvals")
    public List<Map<String, Object>> approvals(@PathVariable Long spaceId) {
        return approvalService.listBySpace(spaceId).stream().map(viewMapper::approval).toList();
    }

    @PostMapping("/approvals/{id}/cohorts")
    public Map<String, Object> compileCohort(@PathVariable Long id, @RequestBody Map<String, String> body) {
        Approval approval = approvalService.mustGet(id);
        Waterline wl = waterlineRepo.findByLabel(body.get("waterlineLabel"))
                .orElseThrow(() -> new com.sandbox.imaging.common.ApiException(404, "水线不存在"));
        return viewMapper.cohort(cohortService.compileCohort(approval, wl));
    }

    @GetMapping("/approvals/{id}/cohorts")
    public List<Map<String, Object>> cohorts(@PathVariable Long id) {
        return cohortRepo.findByApprovalIdOrderByIdDesc(id).stream()
                .map(viewMapper::cohort).toList();
    }

    /** 治理：调整研究空间季度风险点上限，并同步当前季度账本 */
    @PostMapping("/spaces/{spaceId}/budget-cap")
    public Map<String, Object> updateBudgetCap(@PathVariable Long spaceId,
                                               @RequestBody Map<String, Object> body) {
        var space = spaceRepo.findById(spaceId)
                .orElseThrow(() -> new com.sandbox.imaging.common.ApiException(404, "研究空间不存在"));
        int newCap = ((Number) body.get("quarterlyBudget")).intValue();
        String quarter = BudgetService.currentQuarter(System.currentTimeMillis());
        var ledger = budgetService.updateCap(space, newCap, quarter);
        return Map.of("spaceId", spaceId, "quarter", quarter,
                "riskCap", ledger.getRiskCap(), "pointsSpent", ledger.getPointsSpent(),
                "remaining", budgetService.remaining(ledger));
    }

    public static class CompileRequest {
        public String code;
        public String title;
        public ApprovalSpec spec;
    }
}
