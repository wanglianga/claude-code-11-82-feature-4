package com.sandbox.imaging.service;

import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.QueryRequest;
import com.sandbox.imaging.repo.*;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;

/**
 * 查询编排：访问闸门 → 队列复现 → 规范化解析 → 执行 → 联合推断审查。
 * 固定表组与交互查询两条路线在此分流，但共用同一批准范围、最小单元与联合风险规则。
 */
@Service
public class QueryService {

    private final ApprovalService approvalService;
    private final AnalysisVersionService versionService;
    private final CohortService cohortService;
    private final WaterlineRepository waterlineRepo;
    private final QueryParser parser;
    private final QueryExecutor executor;
    private final DisclosureReviewService reviewService;
    private final InteractiveSessionRepository sessionRepo;

    public QueryService(ApprovalService approvalService, AnalysisVersionService versionService,
                        CohortService cohortService, WaterlineRepository waterlineRepo,
                        QueryParser parser, QueryExecutor executor,
                        DisclosureReviewService reviewService,
                        InteractiveSessionRepository sessionRepo) {
        this.approvalService = approvalService;
        this.versionService = versionService;
        this.cohortService = cohortService;
        this.waterlineRepo = waterlineRepo;
        this.parser = parser;
        this.executor = executor;
        this.reviewService = reviewService;
        this.sessionRepo = sessionRepo;
    }

    @Transactional
    public DisclosureReviewService.ReviewOutcome submit(QueryRequest req, boolean dryRun) {
        AnalysisVersion version = versionService.mustGet(req.analysisVersionId);
        Approval approval = approvalService.mustGet(version.getApproval().getId());
        // 访问闸门：撤回/到期/非激活一律拒绝新读取（旧交付物仍可审计）
        approvalService.requireReadable(approval);
        ResearchSpace space = approval.getSpace();

        InteractiveSession session = null;
        if (req.sessionId != null) {
            session = sessionRepo.findById(req.sessionId)
                    .orElseThrow(() -> ApiException.notFound("会话不存在: " + req.sessionId));
            if (!"OPEN".equals(session.getStatus())) {
                throw ApiException.badRequest("会话已关闭，请新建会话");
            }
        }

        Waterline waterline = waterlineRepo.findByLabel(version.getWaterlineLabel())
                .orElseThrow(() -> ApiException.badRequest("版本绑定的水线不存在: "
                        + version.getWaterlineLabel()));
        // 按当时批准与水线复现队列
        Cohort cohort = cohortService.compileCohort(approval, waterline);
        List<String> members = cohortService.memberKeys(cohort);

        QueryParser.ParsedQuery parsed = parser.parse(req, version, approval,
                space.getMinPublishUnit());
        QueryExecutor.RawTable raw = executor.execute(parsed.query(), members,
                version.getAlgorithmVersion());

        return reviewService.review(version, approval, space, parsed, raw, session, dryRun);
    }

    /** 固定表组：整组解析执行后统一联合核验 */
    @Transactional
    public DisclosureReviewService.ReviewOutcome submitTableSet(Long analysisVersionId,
                                                                Long sessionId,
                                                                List<QueryRequest> requests,
                                                                boolean dryRun) {
        AnalysisVersion version = versionService.mustGet(analysisVersionId);
        Approval approval = approvalService.mustGet(version.getApproval().getId());
        approvalService.requireReadable(approval);
        ResearchSpace space = approval.getSpace();
        InteractiveSession session = sessionId == null ? null
                : sessionRepo.findById(sessionId).orElse(null);

        Waterline waterline = waterlineRepo.findByLabel(version.getWaterlineLabel())
                .orElseThrow(() -> ApiException.badRequest("水线不存在"));
        Cohort cohort = cohortService.compileCohort(approval, waterline);
        List<String> members = cohortService.memberKeys(cohort);

        List<DisclosureReviewService.TableSetItem> items = new ArrayList<>();
        for (QueryRequest r : requests) {
            r.analysisVersionId = analysisVersionId;
            r.route = "FIXED_TABLE_SET";
            QueryParser.ParsedQuery parsed = parser.parse(r, version, approval,
                    space.getMinPublishUnit());
            QueryExecutor.RawTable raw = executor.execute(parsed.query(), members,
                    version.getAlgorithmVersion());
            DisclosureReviewService.TableSetItem item =
                    new DisclosureReviewService.TableSetItem();
            item.parsed = parsed;
            item.raw = raw;
            items.add(item);
        }
        return reviewService.reviewTableSet(version, approval, space, items, session, dryRun);
    }
}
