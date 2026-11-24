package com.sandbox.imaging.web;

import com.sandbox.imaging.domain.AnalysisVersion;
import com.sandbox.imaging.domain.Approval;
import com.sandbox.imaging.domain.Cohort;
import com.sandbox.imaging.service.CohortService;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.TreeMap;

/** 实体到对外视图的投影，避免懒加载关联在序列化阶段失败，同时只暴露必要字段 */
@Component
public class ViewMapper {

    private final CohortService cohortService;

    public ViewMapper(CohortService cohortService) {
        this.cohortService = cohortService;
    }

    public Map<String, Object> approval(Approval a) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", a.getId());
        m.put("spaceId", a.getSpace().getId());
        m.put("spaceCode", a.getSpace().getCode());
        m.put("code", a.getCode());
        m.put("title", a.getTitle());
        m.put("purpose", a.getPurpose());
        m.put("center", a.getCenter());
        m.put("status", a.getStatus());
        m.put("validFrom", a.getValidFrom());
        m.put("validTo", a.getValidTo());
        m.put("requestJson", a.getRequestJson());
        m.put("compiledJson", a.getCompiledJson());
        m.put("specHash", a.getSpecHash());
        m.put("createdAt", a.getCreatedAt());
        m.put("revokedAt", a.getRevokedAt());
        m.put("revokeReason", a.getRevokeReason());
        return m;
    }

    public Map<String, Object> version(AnalysisVersion v) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", v.getId());
        m.put("approvalId", v.getApproval().getId());
        m.put("approvalCode", v.getApproval().getCode());
        m.put("versionNo", v.getVersionNo());
        m.put("purpose", v.getPurpose());
        m.put("waterlineLabel", v.getWaterlineLabel());
        m.put("algorithmVersion", v.getAlgorithmVersion());
        m.put("versionSpecJson", v.getVersionSpecJson());
        m.put("specHash", v.getSpecHash());
        m.put("status", v.getStatus());
        m.put("createdAt", v.getCreatedAt());
        return m;
    }

    public Map<String, Object> cohort(Cohort c) {
        Map<String, Object> m = new TreeMap<>();
        m.put("id", c.getId());
        m.put("approvalId", c.getApproval().getId());
        m.put("approvalCode", c.getApproval().getCode());
        m.put("waterlineLabel", c.getWaterline().getLabel());
        m.put("cohortHash", c.getCohortHash());
        m.put("cohortSpecJson", c.getCohortSpecJson());
        m.put("memberCount", c.getMemberCount());
        m.put("memberKeys", cohortService.memberKeys(c));
        m.put("createdAt", c.getCreatedAt());
        return m;
    }
}
