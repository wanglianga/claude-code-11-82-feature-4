package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.Approval;
import com.sandbox.imaging.domain.ResearchSpace;
import com.sandbox.imaging.dto.ApprovalSpec;
import com.sandbox.imaging.repo.ApprovalRepository;
import com.sandbox.imaging.repo.ResearchSpaceRepository;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.TreeMap;

/**
 * 批准编译器：把管理员录入的批准请求校验、规范化为可复现的编译规格。
 * 编译是确定性的——同一请求两次编译得到相同 spec_hash；队列由编译规格 + 水线确定性生成。
 */
@Service
public class ApprovalService {

    private final ApprovalRepository approvalRepo;
    private final ResearchSpaceRepository spaceRepo;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public ApprovalService(ApprovalRepository approvalRepo, ResearchSpaceRepository spaceRepo,
                           AuditService auditService, ObjectMapper mapper) {
        this.approvalRepo = approvalRepo;
        this.spaceRepo = spaceRepo;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    @Transactional
    public Approval compile(Long spaceId, String code, String title, ApprovalSpec spec) {
        ResearchSpace space = spaceRepo.findById(spaceId)
                .orElseThrow(() -> ApiException.notFound("研究空间不存在: " + spaceId));
        validate(spec);

        TreeMap<String, Object> compiled = new TreeMap<>();
        compiled.put("center", spec.center);
        compiled.put("purpose", spec.purpose);
        compiled.put("fields", new ArrayList<>(new java.util.TreeSet<>(spec.fields)));
        compiled.put("dimensions", new ArrayList<>(new java.util.TreeSet<>(spec.dimensions)));
        compiled.put("enrollmentFrom", spec.enrollmentFrom);
        compiled.put("enrollmentTo", spec.enrollmentTo);
        var filters = new TreeMap<String, Object>();
        spec.allowedFilters.forEach((k, v) -> {
            var values = new ArrayList<>(v);
            values.sort(Comparator.naturalOrder());
            filters.put(k, values);
        });
        compiled.put("allowedFilters", filters);
        compiled.put("modalities", new ArrayList<>(new java.util.TreeSet<>(spec.modalities)));
        compiled.put("modelUsage", spec.modelUsage);
        var totals = new ArrayList<Object>();
        if (spec.publicTotals != null) {
            for (var pt : spec.publicTotals) {
                var t = new TreeMap<String, Object>();
                t.put("dimensions", new TreeMap<>(pt.dimensions));
                t.put("value", pt.value);
                totals.add(t);
            }
            totals.sort(Comparator.comparing(Object::toString));
        }
        compiled.put("publicTotals", totals);
        compiled.put("validFrom", spec.validFrom);
        compiled.put("validTo", spec.validTo);

        String compiledJson = CanonicalJson.write(mapper, compiled);
        String hash = CanonicalJson.sha256(compiledJson);

        Approval approval = new Approval();
        approval.setSpace(space);
        approval.setCode(code);
        approval.setTitle(title);
        approval.setPurpose(spec.purpose);
        approval.setCenter(spec.center);
        approval.setStatus("DRAFT");
        approval.setValidFrom(spec.validFrom);
        approval.setValidTo(spec.validTo);
        approval.setRequestJson(CanonicalJson.write(mapper, spec));
        approval.setCompiledJson(compiledJson);
        approval.setSpecHash(hash);
        approval = approvalRepo.save(approval);
        auditService.log(spaceId, SecurityUtils.currentUser(), "APPROVAL_COMPILE",
                "批准 " + code + " 编译完成 specHash=" + hash);
        return approval;
    }

    private void validate(ApprovalSpec spec) {
        if (spec == null) {
            throw ApiException.badRequest("批准规格不能为空");
        }
        if (spec.enrollmentFrom >= spec.enrollmentTo) {
            throw ApiException.badRequest("入组期间无效：起必须早于止");
        }
        if (spec.validFrom >= spec.validTo) {
            throw ApiException.badRequest("批准有效期无效");
        }
        if (spec.fields == null || spec.fields.isEmpty()) {
            throw ApiException.badRequest("至少授权一个字段");
        }
        for (String f : spec.fields) {
            if (!FieldCatalog.isKnownField(f)) {
                throw ApiException.badRequest("字段不在平台目录中: " + f);
            }
        }
        if (spec.dimensions != null) {
            for (String d : spec.dimensions) {
                if (!spec.fields.contains(d)) {
                    throw ApiException.badRequest("分组维度 " + d + " 未获字段授权");
                }
            }
        }
        if (spec.allowedFilters != null) {
            for (String f : spec.allowedFilters.keySet()) {
                if (!spec.fields.contains(f)) {
                    throw ApiException.badRequest("队列过滤字段 " + f + " 未获字段授权");
                }
            }
        }
    }

    @Transactional
    public Approval activate(Long approvalId) {
        Approval a = mustGet(approvalId);
        if (!"DRAFT".equals(a.getStatus())) {
            throw ApiException.conflict("仅 DRAFT 状态可激活，当前 " + a.getStatus());
        }
        a.setStatus("ACTIVE");
        auditService.log(a.getSpace().getId(), SecurityUtils.currentUser(), "APPROVAL_ACTIVATE",
                "批准 " + a.getCode() + " 激活");
        return approvalRepo.save(a);
    }

    /** 撤回字段授权/整个批准：此后所有新读取必须拒绝，旧结果仅按留存许可审计 */
    @Transactional
    public Approval revoke(Long approvalId, String reason) {
        Approval a = mustGet(approvalId);
        a.setStatus("REVOKED");
        a.setRevokedAt(System.currentTimeMillis());
        a.setRevokeReason(reason);
        auditService.log(a.getSpace().getId(), SecurityUtils.currentUser(), "APPROVAL_REVOKE",
                "批准 " + a.getCode() + " 撤回，原因：" + reason);
        return approvalRepo.save(a);
    }

    /** 运行时访问闸门：状态 + 有效期 + 当前时间共同决定能否新读取 */
    public void requireReadable(Approval a) {
        long now = System.currentTimeMillis();
        if ("REVOKED".equals(a.getStatus())) {
            throw ApiException.forbidden("批准 " + a.getCode() + " 已撤回，禁止新读取（旧交付物仅供审计）");
        }
        if (now < a.getValidFrom() || now > a.getValidTo()) {
            throw ApiException.forbidden("批准 " + a.getCode() + " 已过有效期（"
                    + a.getValidFrom() + " ~ " + a.getValidTo() + "），禁止新读取");
        }
        if (!"ACTIVE".equals(a.getStatus())) {
            throw ApiException.forbidden("批准 " + a.getCode() + " 状态为 " + a.getStatus() + "，不可读取");
        }
    }

    @Transactional(readOnly = true)
    public Approval mustGet(Long id) {
        return approvalRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("批准不存在: " + id));
    }

    @Transactional(readOnly = true)
    public java.util.List<Approval> listBySpace(Long spaceId) {
        return approvalRepo.findBySpaceIdOrderByIdDesc(spaceId);
    }
}
