package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.CanonicalQuery;
import com.sandbox.imaging.inference.FeasibilityResult;
import com.sandbox.imaging.inference.InferenceEngine;
import com.sandbox.imaging.inference.LinConstraint;
import com.sandbox.imaging.repo.AnalysisVersionRepository;
import com.sandbox.imaging.repo.ConstraintRepository;
import com.sandbox.imaging.repo.QueryAttemptRepository;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 披露审查器（平台核心）。
 *
 * 裁决流程：
 * 1. 把候选输出转成线性约束：精确披露=等式；舍入披露=区间；抑制单元=上界承诺；分组恒等=分区等式。
 * 2. 载入研究空间内全部历史约束（所有已披露输出 + 公开总数），与候选约束合并。
 * 3. 推断引擎计算受保护单元（真实计数 &lt; 最小单元）的可行整数计数集合。
 * 4. 风险点 = 各受保护单元可行集合较审查前减少的可能值个数之和；
 *    可行集合被约束为唯一值、系统无解或枚举触顶 => 无论预算余量一律硬阻断；
 *    风险点超过季度剩余预算 => 阻断；零窄化结果即使预算耗尽也放行。
 * 5. 放行后约束入库、预算扣减；阻断则不留约束、不扣点，仅留阻断台账与审计。
 */
@Service
public class DisclosureReviewService {

    private final ConstraintRepository constraintRepo;
    private final QueryAttemptRepository attemptRepo;
    private final AnalysisVersionRepository versionRepo;
    private final ConstraintBuilder builder;
    private final OutputSanitizer sanitizer;
    private final BudgetService budgetService;
    private final AuditService auditService;
    private final InferenceEngine engine;
    private final ObjectMapper mapper;

    public DisclosureReviewService(ConstraintRepository constraintRepo,
                                   QueryAttemptRepository attemptRepo,
                                   AnalysisVersionRepository versionRepo,
                                   ConstraintBuilder builder,
                                   OutputSanitizer sanitizer,
                                   BudgetService budgetService, AuditService auditService,
                                   ObjectMapper mapper) {
        this.constraintRepo = constraintRepo;
        this.attemptRepo = attemptRepo;
        this.versionRepo = versionRepo;
        this.builder = builder;
        this.sanitizer = sanitizer;
        this.budgetService = budgetService;
        this.auditService = auditService;
        this.engine = new InferenceEngine();
        this.mapper = mapper;
    }

    /** 变量作用域：同一批准 + 同一水线的版本共享约束图，不同队列同名变量互不串扰 */
    private String scopeOf(Approval approval, AnalysisVersion version) {
        return approval.getCode() + "@" + version.getWaterlineLabel();
    }

    private String scopedVar(String scope, String variable) {
        return scope + "::" + variable;
    }

    private String unscopedVar(String scoped) {
        int i = scoped.indexOf("::");
        return i < 0 ? scoped : scoped.substring(i + 2);
    }

    private LinConstraint scopeConstraint(String scope, LinConstraint c) {
        Map<String, Integer> coeff = new TreeMap<>();
        c.coeff().forEach((k, v) -> coeff.put(scopedVar(scope, k), v));
        return new LinConstraint(coeff, c.op(), c.rhs(), c.kind());
    }

    /** 同作用域的历史分析版本 ID（同批准、同水线） */
    private java.util.Set<Long> scopedVersionIds(Approval approval, String waterlineLabel) {
        java.util.Set<Long> ids = new java.util.HashSet<>();
        for (AnalysisVersion v : versionRepo.findByApprovalIdOrderByVersionNoDesc(approval.getId())) {
            if (v.getWaterlineLabel().equals(waterlineLabel)) ids.add(v.getId());
        }
        return ids;
    }

    @Transactional
    public ReviewOutcome review(AnalysisVersion version, Approval approval, ResearchSpace space,
                                QueryParser.ParsedQuery parsed, QueryExecutor.RawTable raw,
                                InteractiveSession session, boolean dryRun) {
        CanonicalQuery cq = parsed.query();
        int minUnit = ((Number) cq.output.get("minUnit")).intValue();
        Integer roundingStep = cq.output.containsKey("roundingStep")
                ? ((Number) cq.output.get("roundingStep")).intValue() : null;

        // 幂等：同一规范化查询此前已放行 => 直接复用，零扣点、不重复入库约束
        if (!dryRun) {
            for (QueryAttempt prev : attemptRepo.findByAnalysisVersionIdOrderByIdDesc(version.getId())) {
                if (parsed.hash().equals(prev.getQueryHash()) && "ALLOWED".equals(prev.getStatus())) {
                    return ReviewOutcome.allowedDuplicate(prev);
                }
            }
        }

        List<LinConstraint> publicTotalConstraints = ensurePublicTotals(version, approval, !dryRun);
        final String scope = scopeOf(approval, version);
        final java.util.Set<Long> scopedVersions =
                scopedVersionIds(approval, version.getWaterlineLabel());

        // ---- 1. 构造候选输出与候选约束（变量加作用域前缀） ----
        CandidateBundle bundle = buildBundle(cq, raw);
        List<OutputCell> outputCells = bundle.outputCells;
        List<LinConstraint> candidate = bundle.constraints.stream()
                .map(c -> scopeConstraint(scope, c)).toList();
        Set<String> protectedNow = bundle.protectedNow.stream()
                .map(v -> scopedVar(scope, v)).collect(java.util.stream.Collectors.toCollection(TreeSet::new));

        // ---- 2. 载入历史约束：仅同批准同水线作用域（跨版本、跨会话累计） ----
        List<LinConstraint> history = constraintRepo.findBySpaceIdOrderByIdAsc(space.getId())
                .stream().filter(c -> scopedVersions.contains(c.getAnalysisVersionId()))
                .map(builder::toLin).collect(java.util.stream.Collectors.toList());
        // dry-run 不写库，但公开总数仍须作为攻击者先验参与本次推断
        history.addAll(publicTotalConstraints);
        history = dedupe(history);
        final List<LinConstraint> historyFinal = history;

        // 候选约束分两层：
        //  基线层 = 历史 + 本次抑制承诺（抑制本身是平台给受保护单元的安全保证，不计点）
        //  增量层 = 披露等式/舍入区间/分区恒等（真正令可行集窄化的信息，按窄化计点）
        List<LinConstraint> suppressionLayer = candidate.stream()
                .filter(c -> "SUPPRESSION_DOMAIN".equals(c.kind())).toList();
        List<LinConstraint> incrementalLayer = dedupe(candidate.stream()
                .filter(c -> !"SUPPRESSION_DOMAIN".equals(c.kind())).toList()).stream()
                .filter(c -> historyFinal.stream().noneMatch(h -> sameConstraint(h, c))).toList();

        List<LinConstraint> baselineSystem = new ArrayList<>(history);
        baselineSystem.addAll(suppressionLayer);
        final List<LinConstraint> baselineFinal = dedupe(baselineSystem);
        List<LinConstraint> mergedSystem = new ArrayList<>(baselineFinal);
        mergedSystem.addAll(incrementalLayer);

        Set<String> protectedVars = new TreeSet<>(protectedNow);
        for (LinConstraint c : baselineFinal) {
            if ("SUPPRESSION_DOMAIN".equals(c.kind()) && "<=".equals(c.op())) {
                protectedVars.addAll(c.coeff().keySet());
            }
        }

        // ---- 3. 审查前/后可行域 ----
        int universe = Math.max(raw.cohortSize, raw.totalPatients);
        FeasibilityResult before = engine.analyze(baselineFinal, universe);
        FeasibilityResult after = engine.analyze(mergedSystem, universe);

        // ---- 4. 窄化计点与硬阻断 ----
        List<String> blockReasons = new ArrayList<>();
        List<ProtectedCellReport> cellReports = new ArrayList<>();
        int points = 0;
        if (!after.feasible()) {
            blockReasons.add("候选输出与既有披露构成矛盾约束系统：" + after.reason());
        }
        if (after.capped()) {
            blockReasons.add("可行整数解枚举触顶，fail-closed 阻断");
        }
        for (String pv : protectedVars) {
            List<Integer> base = before.domains().getOrDefault(pv, naturalDomain(universe));
            List<Integer> post = after.domains().getOrDefault(pv, naturalDomain(universe));
            int narrowed = Math.max(0, base.size() - post.size());
            points += narrowed;
            ProtectedCellReport r = new ProtectedCellReport();
            r.variable = unscopedVar(pv);
            r.scope = scope;
            r.baselineDomain = base;
            r.postDomain = post;
            r.narrowedValues = narrowed;
            cellReports.add(r);
            if (post.size() == 1) {
                blockReasons.add("受保护单元 " + unscopedVar(pv) + " 被联合约束唯一确定为 "
                        + post.get(0) + "，触发硬阻断（相减/组合推断）");
            }
        }

        String quarter = BudgetService.currentQuarter(System.currentTimeMillis());
        BudgetLedger ledger = budgetService.getOrCreate(space, quarter);
        int remaining = budgetService.remaining(ledger);
        if (blockReasons.isEmpty() && points > remaining) {
            blockReasons.add("本次披露消耗 " + points + " 风险点，季度剩余仅 " + remaining
                    + " 点（上限 " + ledger.getRiskCap() + "）");
        }

        ReviewReport report = new ReviewReport();
        report.feasible = after.feasible();
        report.capped = after.capped();
        report.historicalConstraints = history.size();
        report.candidateConstraints = candidate.size();
        report.protectedCells = cellReports;
        report.riskPoints = points;
        report.quarter = quarter;
        report.remainingBefore = remaining;
        report.blockReasons = blockReasons;
        report.minUnit = minUnit;
        report.roundingStep = roundingStep;

        if (dryRun) {
            report.decision = blockReasons.isEmpty() ? "WOULD_ALLOW" : "WOULD_BLOCK";
            return ReviewOutcome.dryRun(outputCells, report, candidate);
        }

        // ---- 5. 落账 ----
        QueryAttempt attempt = new QueryAttempt();
        attempt.setSpaceId(space.getId());
        attempt.setApprovalId(approval.getId());
        attempt.setAnalysisVersionId(version.getId());
        attempt.setSessionId(session == null ? null : session.getId());
        attempt.setRoute(cq.route);
        attempt.setRequestedBy(SecurityUtils.currentUser());
        attempt.setCanonicalQueryJson(parsed.canonicalJson());
        attempt.setQueryHash(parsed.hash());
        attempt.setRawResultJson(json(raw));
        attempt.setReviewJson(CanonicalJson.write(mapper, report));
        attempt.setRiskPointsUsed(0);

        if (!blockReasons.isEmpty()) {
            attempt.setStatus("BLOCKED");
            attempt.setOutputJson(null);
            attemptRepo.save(attempt);
            auditService.log(space.getId(), SecurityUtils.currentUser(), "RESULT_BLOCKED",
                    "查询 " + parsed.hash().substring(0, 12) + " 被阻断：" + String.join("；", blockReasons));
            return ReviewOutcome.blocked(outputCells, report, attempt);
        }

        attempt.setStatus("ALLOWED");
        attempt.setOutputJson(json(outputCells));
        attempt.setRiskPointsUsed(points);
        attempt = attemptRepo.save(attempt);

        for (LinConstraint lc : dedupe(candidate)) {
            final LinConstraint fc = lc;
            boolean dup = historyFinal.stream().anyMatch(h -> sameConstraint(h, fc));
            if (dup) continue;
            constraintRepo.save(builder.toEntity(space.getId(), version.getId(),
                    attempt.getId(), null, lc.kind(), lc));
        }
        if (points > 0) {
            budgetService.charge(ledger, points);
        }
        auditService.log(space.getId(), SecurityUtils.currentUser(), "QUERY_REVIEW",
                "查询放行 hash=" + parsed.hash().substring(0, 12) + " 风险点=" + points
                        + " 季度=" + quarter + " 剩余=" + (remaining - points));
        report.remainingAfter = remaining - points;
        attempt.setReviewJson(CanonicalJson.write(mapper, report));
        attemptRepo.save(attempt);
        return ReviewOutcome.allowed(outputCells, report, attempt);
    }

    /** 公开总数约束（批准附带的攻击者先验）；persist=false 时只返回约束不写库（dry-run） */
    private List<LinConstraint> ensurePublicTotals(AnalysisVersion version, Approval approval,
                                                   boolean persist) {
        final String scope = scopeOf(approval, version);
        final java.util.Set<Long> scopedVersions =
                scopedVersionIds(approval, version.getWaterlineLabel());
        Map<String, Object> compiled;
        try {
            compiled = mapper.readValue(approval.getCompiledJson(), TreeMap.class);
        } catch (Exception e) {
            throw ApiException.badRequest("编译规格解析失败");
        }
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> totals =
                (List<Map<String, Object>>) compiled.getOrDefault("publicTotals", List.of());
        List<LinConstraint> existing = constraintRepo
                .findBySpaceIdOrderByIdAsc(approval.getSpace().getId()).stream()
                .filter(c -> scopedVersions.contains(c.getAnalysisVersionId()))
                .map(builder::toLin).toList();
        List<LinConstraint> result = new ArrayList<>();
        for (Map<String, Object> t : totals) {
            int value = ((Number) t.get("value")).intValue();
            @SuppressWarnings("unchecked")
            Map<String, String> dims = (Map<String, String>) t.getOrDefault("dimensions", Map.of());
            String var = dims.isEmpty() ? "TOTAL"
                    : dims.entrySet().stream().sorted(Map.Entry.comparingByKey())
                    .map(e -> e.getKey() + "=" + e.getValue())
                    .reduce((a, b) -> a + "|" + b).orElse("TOTAL");
            LinConstraint lc = scopeConstraint(scope,
                    new LinConstraint(Map.of(var, 1), "=", value, "PUBLIC_TOTAL"));
            boolean dup = existing.stream().anyMatch(x -> sameConstraint(x, lc));
            if (dup) continue;
            result.add(lc);
            if (persist) {
                constraintRepo.save(builder.toEntity(approval.getSpace().getId(),
                        version.getId(), null, null, "PUBLIC_TOTAL", lc));
            }
        }
        return result;
    }

    private List<LinConstraint> dedupe(List<LinConstraint> list) {
        List<LinConstraint> out = new ArrayList<>();
        for (LinConstraint c : list) {
            if (out.stream().noneMatch(x -> sameConstraint(x, c))) out.add(c);
        }
        return out;
    }

    private boolean sameConstraint(LinConstraint a, LinConstraint b) {
        return a.op().equals(b.op()) && a.rhs() == b.rhs()
                && a.kind().equals(b.kind()) && a.coeff().equals(b.coeff());
    }

    private String json(Object o) {
        try {
            return mapper.writeValueAsString(o);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** 把一张原始聚合表转换为对外输出单元 + 候选线性约束 */
    private CandidateBundle buildBundle(CanonicalQuery cq, QueryExecutor.RawTable raw) {
        int minUnit = ((Number) cq.output.get("minUnit")).intValue();
        Integer roundingStep = cq.output.containsKey("roundingStep")
                ? ((Number) cq.output.get("roundingStep")).intValue() : null;

        CandidateBundle bundle = new CandidateBundle();
        // 对外输出：统一脱敏器（复现时用同一逻辑，保证逐单元同构）
        bundle.outputCells.addAll(sanitizer.sanitize(raw, cq.groups, minUnit, roundingStep));

        // 候选约束：仅依据真实计数构造，真实值不进入输出
        List<String> tupleVars = new ArrayList<>();
        for (QueryExecutor.RawCell cell : raw.cells) {
            tupleVars.add(cell.dimKey);
            if (cell.patientCount < minUnit) {
                bundle.protectedNow.add(cell.dimKey);
                bundle.constraints.add(new LinConstraint(Map.of(cell.dimKey, 1), "<=",
                        minUnit - 1, "SUPPRESSION_DOMAIN"));
                bundle.constraints.add(new LinConstraint(Map.of(cell.dimKey, 1), ">=", 0,
                        "SUPPRESSION_DOMAIN"));
            } else if (roundingStep != null && roundingStep > 1) {
                int rounded = Math.floorDiv(cell.patientCount, roundingStep) * roundingStep;
                bundle.constraints.add(new LinConstraint(Map.of(cell.dimKey, 1), ">=", rounded,
                        "ROUNDED_INTERVAL"));
                bundle.constraints.add(new LinConstraint(Map.of(cell.dimKey, 1), "<=",
                        rounded + roundingStep - 1, "ROUNDED_INTERVAL"));
            } else {
                bundle.constraints.add(new LinConstraint(Map.of(cell.dimKey, 1), "=",
                        cell.patientCount, "DISCLOSED_EQUALITY"));
            }
        }
        if (!tupleVars.isEmpty() && !List.of("TOTAL").equals(tupleVars)) {
            bundle.constraints.add(builder.tuplePartition(tupleVars));
        }
        if (!cq.groups.isEmpty()) {
            if (raw.totalPatients >= minUnit) {
                bundle.constraints.add(new LinConstraint(Map.of("TOTAL", 1), "=",
                        raw.totalPatients, "DISCLOSED_EQUALITY"));
            } else if (raw.totalPatients > 0) {
                bundle.protectedNow.add("TOTAL");
                bundle.constraints.add(new LinConstraint(Map.of("TOTAL", 1), "<=",
                        minUnit - 1, "SUPPRESSION_DOMAIN"));
            }
        }
        return bundle;
    }

    /**
     * 固定表组路线：把一组互不细分的安全统计表作为整体联合核验。
     * 全组候选约束合并后一次性推断，任一组表导致唯一计数或预算超限即整组阻断（全有或全无）。
     */
    @Transactional
    public ReviewOutcome reviewTableSet(AnalysisVersion version, Approval approval,
                                        ResearchSpace space,
                                        List<TableSetItem> items, InteractiveSession session,
                                        boolean dryRun) {
        List<LinConstraint> publicTotals = ensurePublicTotals(version, approval, !dryRun);
        final String scope = scopeOf(approval, version);
        final java.util.Set<Long> scopedVersions =
                scopedVersionIds(approval, version.getWaterlineLabel());
        List<LinConstraint> history = new ArrayList<>(constraintRepo
                .findBySpaceIdOrderByIdAsc(space.getId()).stream()
                .filter(c -> scopedVersions.contains(c.getAnalysisVersionId()))
                .map(builder::toLin).toList());
        history.addAll(publicTotals);
        history = dedupe(history);
        final List<LinConstraint> historyFinal = history;

        List<LinConstraint> groupConstraints = new ArrayList<>();
        List<OutputCell> allOutputs = new ArrayList<>();
        Set<String> protectedVars = new TreeSet<>();
        int universe = 0;
        for (TableSetItem item : items) {
            CandidateBundle b = buildBundle(item.parsed.query(), item.raw);
            b.constraints.stream().map(c -> scopeConstraint(scope, c)).forEach(groupConstraints::add);
            allOutputs.addAll(b.outputCells);
            b.protectedNow.stream().map(v -> scopedVar(scope, v)).forEach(protectedVars::add);
            universe = Math.max(universe, item.raw.cohortSize);
        }
        // 与交互路线同一风险规则：抑制承诺入基线层，披露/区间/分区为增量层
        List<LinConstraint> suppressionLayer = groupConstraints.stream()
                .filter(c -> "SUPPRESSION_DOMAIN".equals(c.kind())).toList();
        List<LinConstraint> incrementalLayer = dedupe(groupConstraints.stream()
                .filter(c -> !"SUPPRESSION_DOMAIN".equals(c.kind())).toList()).stream()
                .filter(c -> historyFinal.stream().noneMatch(h -> sameConstraint(h, c))).toList();

        List<LinConstraint> baselineSystem = new ArrayList<>(historyFinal);
        baselineSystem.addAll(suppressionLayer);
        final List<LinConstraint> baselineFinal = dedupe(baselineSystem);
        for (LinConstraint c : baselineFinal) {
            if ("SUPPRESSION_DOMAIN".equals(c.kind()) && "<=".equals(c.op())) {
                protectedVars.addAll(c.coeff().keySet());
            }
        }

        FeasibilityResult before = engine.analyze(baselineFinal, universe);
        List<LinConstraint> merged = new ArrayList<>(baselineFinal);
        merged.addAll(incrementalLayer);
        FeasibilityResult after = engine.analyze(merged, universe);

        List<String> blockReasons = new ArrayList<>();
        List<ProtectedCellReport> reports = new ArrayList<>();
        int points = 0;
        if (!after.feasible()) blockReasons.add("表组联合约束系统无解：" + after.reason());
        if (after.capped()) blockReasons.add("枚举触顶，fail-closed 阻断整组");
        for (String pv : protectedVars) {
            List<Integer> base = before.domains().getOrDefault(pv, naturalDomain(universe));
            List<Integer> post = after.domains().getOrDefault(pv, naturalDomain(universe));
            int narrowed = Math.max(0, base.size() - post.size());
            points += narrowed;
            ProtectedCellReport r = new ProtectedCellReport();
            r.variable = unscopedVar(pv);
            r.scope = scope;
            r.baselineDomain = base;
            r.postDomain = post;
            r.narrowedValues = narrowed;
            reports.add(r);
            if (post.size() == 1) {
                blockReasons.add("表组联合推断使受保护单元 " + unscopedVar(pv)
                        + " 被唯一确定为 " + post.get(0) + "，整组阻断");
            }
        }
        String quarter = BudgetService.currentQuarter(System.currentTimeMillis());
        BudgetLedger ledger = budgetService.getOrCreate(space, quarter);
        int remaining = budgetService.remaining(ledger);
        if (blockReasons.isEmpty() && points > remaining) {
            blockReasons.add("表组需 " + points + " 风险点，季度剩余 " + remaining);
        }

        ReviewReport report = new ReviewReport();
        report.feasible = after.feasible();
        report.capped = after.capped();
        report.historicalConstraints = history.size();
        report.candidateConstraints = groupConstraints.size();
        report.protectedCells = reports;
        report.riskPoints = points;
        report.quarter = quarter;
        report.remainingBefore = remaining;
        report.blockReasons = blockReasons;
        report.minUnit = space.getMinPublishUnit();

        if (dryRun) {
            report.decision = blockReasons.isEmpty() ? "WOULD_ALLOW" : "WOULD_BLOCK";
            return ReviewOutcome.dryRun(allOutputs, report, groupConstraints);
        }
        // 整组落账：一个尝试记录承载整组结果
        QueryAttempt attempt = new QueryAttempt();
        attempt.setSpaceId(space.getId());
        attempt.setApprovalId(approval.getId());
        attempt.setAnalysisVersionId(version.getId());
        attempt.setSessionId(session == null ? null : session.getId());
        attempt.setRoute("FIXED_TABLE_SET");
        attempt.setRequestedBy(SecurityUtils.currentUser());
        attempt.setCanonicalQueryJson(json(
                items.stream().map(i -> i.parsed.canonicalJson()).toList()));
        String groupHash = CanonicalJson.sha256(
                items.stream().map(i -> i.parsed.hash()).sorted().reduce("", (a, b) -> a + b));
        attempt.setQueryHash(groupHash);
        attempt.setRawResultJson(json(items.stream().map(i -> i.raw).toList()));
        attempt.setReviewJson(CanonicalJson.write(mapper, report));
        if (!blockReasons.isEmpty()) {
            attempt.setStatus("BLOCKED");
            attempt.setRiskPointsUsed(0);
            attemptRepo.save(attempt);
            auditService.log(space.getId(), SecurityUtils.currentUser(), "RESULT_BLOCKED",
                    "固定表组整组阻断：" + String.join("；", blockReasons));
            return ReviewOutcome.blocked(allOutputs, report, attempt);
        }
        attempt.setStatus("ALLOWED");
        attempt.setOutputJson(json(allOutputs));
        attempt.setRiskPointsUsed(points);
        attempt = attemptRepo.save(attempt);
        for (LinConstraint lc : groupConstraints) {
            constraintRepo.save(builder.toEntity(space.getId(), version.getId(),
                    attempt.getId(), null, "FIXED_TABLE_SET", lc));
        }
        if (points > 0) budgetService.charge(ledger, points);
        report.remainingAfter = remaining - points;
        attempt.setReviewJson(CanonicalJson.write(mapper, report));
        attemptRepo.save(attempt);
        auditService.log(space.getId(), SecurityUtils.currentUser(), "FIXED_SET_REVIEW",
                "固定表组放行，风险点=" + points);
        return ReviewOutcome.allowed(allOutputs, report, attempt);
    }

    public static class TableSetItem {
        public QueryParser.ParsedQuery parsed;
        public QueryExecutor.RawTable raw;
    }

    private static class CandidateBundle {
        final List<OutputCell> outputCells = new ArrayList<>();
        final List<LinConstraint> constraints = new ArrayList<>();
        final Set<String> protectedNow = new TreeSet<>();
    }

    private List<Integer> naturalDomain(int universe) {
        List<Integer> d = new ArrayList<>();
        for (int i = 0; i <= universe; i++) d.add(i);
        return d;
    }

    public static class ProtectedCellReport {
        public String variable;
        public String scope;
        public List<Integer> baselineDomain;
        public List<Integer> postDomain;
        public int narrowedValues;
    }

    public static class ReviewReport {
        public String decision;
        public boolean feasible;
        public boolean capped;
        public int historicalConstraints;
        public int candidateConstraints;
        public List<ProtectedCellReport> protectedCells;
        public int riskPoints;
        public String quarter;
        public int remainingBefore;
        public Integer remainingAfter;
        public List<String> blockReasons;
        public int minUnit;
        public Integer roundingStep;
    }

    public static class ReviewOutcome {
        public String decision;
        public List<OutputCell> output;
        public ReviewReport report;
        public Long attemptId;
        public boolean duplicate;

        static ReviewOutcome allowed(List<OutputCell> out, ReviewReport r, QueryAttempt a) {
            ReviewOutcome o = new ReviewOutcome();
            o.decision = "ALLOWED"; o.output = out; o.report = r; o.attemptId = a.getId();
            return o;
        }

        static ReviewOutcome blocked(List<OutputCell> out, ReviewReport r, QueryAttempt a) {
            ReviewOutcome o = new ReviewOutcome();
            o.decision = "BLOCKED"; o.output = out; o.report = r; o.attemptId = a.getId();
            return o;
        }

        static ReviewOutcome dryRun(List<OutputCell> out, ReviewReport r, List<LinConstraint> cand) {
            ReviewOutcome o = new ReviewOutcome();
            o.decision = r.decision; o.output = out; o.report = r;
            return o;
        }

        static ReviewOutcome allowedDuplicate(QueryAttempt prev) {
            ReviewOutcome o = new ReviewOutcome();
            o.decision = "ALLOWED";
            o.duplicate = true;
            o.attemptId = prev.getId();
            try {
                var om = new ObjectMapper();
                o.output = om.readValue(prev.getOutputJson(),
                        om.getTypeFactory().constructCollectionType(List.class, OutputCell.class));
                o.report = om.readValue(prev.getReviewJson(), ReviewReport.class);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
            return o;
        }
    }
}
