package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.AnalysisVersion;
import com.sandbox.imaging.domain.Approval;
import com.sandbox.imaging.dto.ApprovalSpec;
import com.sandbox.imaging.dto.CanonicalQuery;
import com.sandbox.imaging.dto.QueryRequest;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 查询解析器：把分析师请求规范化为 CanonicalQuery。
 * - 过滤字段/分组维度/度量必须与批准范围相交（批准白名单之外一律拒绝）
 * - 时间窗与批准入组期间相交
 * - 输出抑制阈值与舍入步长显式化
 * - comment 等展示性文本不入哈希：查询文本不同不改变预算身份
 */
@Service
public class QueryParser {

    private final ObjectMapper mapper;
    private final CohortService cohortService;

    public QueryParser(ObjectMapper mapper, CohortService cohortService) {
        this.mapper = mapper;
        this.cohortService = cohortService;
    }

    public ParsedQuery parse(QueryRequest req, AnalysisVersion version, Approval approval,
                             int defaultMinUnit) {
        Map<String, Object> compiled = cohortService.readCompiled(approval);
        List<String> allowedFields = asStringList(compiled.get("fields"));
        List<String> allowedDimensions = asStringList(compiled.get("dimensions"));
        long enrollFrom = ((Number) compiled.get("enrollmentFrom")).longValue();
        long enrollTo = ((Number) compiled.get("enrollmentTo")).longValue();

        CanonicalQuery cq = new CanonicalQuery();
        cq.approvalCode = approval.getCode();
        cq.analysisVersionNo = version.getVersionNo();
        cq.waterlineLabel = version.getWaterlineLabel();
        cq.algorithmVersion = version.getAlgorithmVersion();
        cq.route = req.route == null ? "INTERACTIVE" : req.route;

        // 1. 过滤条件：校验白名单后规范化排序
        List<QueryRequest.Filter> filters = req.filters == null ? List.of() : req.filters;
        List<TreeMap<String, Object>> normFilters = new ArrayList<>();
        for (QueryRequest.Filter f : filters) {
            if (!allowedFields.contains(f.field)) {
                throw ApiException.forbidden("过滤字段 " + f.field + " 不在批准字段授权范围内");
            }
            String op = f.op == null ? "eq" : f.op;
            if (!List.of("eq", "in", "gte", "lte").contains(op)) {
                throw ApiException.badRequest("不支持的过滤运算符: " + op);
            }
            TreeMap<String, Object> nf = new TreeMap<>();
            nf.put("field", f.field);
            nf.put("op", op);
            nf.put("value", normalizeValue(f.value));
            normFilters.add(nf);
        }
        normFilters.sort(Comparator.comparing(Object::toString));
        cq.filters = normFilters;

        // 2. 分组维度
        List<String> groups = new ArrayList<>(req.groups == null ? List.of() : req.groups);
        for (String g : groups) {
            if (!allowedDimensions.contains(g)) {
                throw ApiException.forbidden("分组维度 " + g + " 不在批准队列维度范围内");
            }
        }
        Collections.sort(groups);
        cq.groups = groups;

        // 3. 时间窗：与批准入组期间相交
        TreeMap<String, Object> tw = new TreeMap<>();
        long wFrom = enrollFrom;
        long wTo = enrollTo;
        if (req.timeWindow != null) {
            if (req.timeWindow.from != null) wFrom = Math.max(wFrom, req.timeWindow.from);
            if (req.timeWindow.to != null) wTo = Math.min(wTo, req.timeWindow.to);
            if (req.timeWindow.followUpDays != null) {
                tw.put("followUpDays", req.timeWindow.followUpDays);
            }
        }
        if (wFrom > wTo) {
            throw ApiException.badRequest("查询时间窗与批准入组期间相交为空");
        }
        tw.put("from", wFrom);
        tw.put("to", wTo);
        cq.timeWindow = tw;

        // 4. 度量
        List<String> measures = new ArrayList<>(
                req.measures == null || req.measures.isEmpty()
                        ? List.of("PATIENT_COUNT") : req.measures);
        for (String m : measures) {
            if (!FieldCatalog.MEASURES.contains(m)) {
                throw ApiException.badRequest("不支持的度量: " + m);
            }
        }
        Collections.sort(measures);
        cq.measures = measures;

        // 5. 输出规格
        TreeMap<String, Object> out = new TreeMap<>();
        int minUnit = req.output != null && req.output.minUnit != null
                ? req.output.minUnit : defaultMinUnit;
        if (minUnit < defaultMinUnit) {
            throw ApiException.badRequest("最小发布单元不能低于空间阈值 " + defaultMinUnit);
        }
        out.put("minUnit", minUnit);
        if (req.output != null && req.output.roundingStep != null) {
            if (req.output.roundingStep < 1) {
                throw ApiException.badRequest("舍入步长必须 >=1");
            }
            out.put("roundingStep", req.output.roundingStep);
        }
        cq.output = out;

        String canonicalJson = CanonicalJson.write(mapper, cq);
        return new ParsedQuery(cq, canonicalJson, CanonicalJson.sha256(canonicalJson), wFrom, wTo);
    }

    private Object normalizeValue(Object value) {
        if (value instanceof List<?> list) {
            List<Object> copy = new ArrayList<>(list);
            Collections.sort(copy, Comparator.comparing(Object::toString));
            return copy;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    private List<String> asStringList(Object o) {
        return o == null ? List.of() : (List<String>) o;
    }

    /** 解析结果：规范化查询 + 哈希 + 相交后的时间窗 */
    public record ParsedQuery(CanonicalQuery query, String canonicalJson, String hash,
                              long windowFrom, long windowTo) {}
}
