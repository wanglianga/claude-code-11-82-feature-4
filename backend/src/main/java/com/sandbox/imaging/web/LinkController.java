package com.sandbox.imaging.web;

import com.sandbox.imaging.service.*;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * 链接版本：封存、谱系、受控重建、一致性核对、到期证明（分析师可用）。
 */
@RestController
@RequestMapping("/api/link")
public class LinkController {

    private final LinkSealService sealService;
    private final LinkRebuildService rebuildService;
    private final LinkExpiryService expiryService;
    private final LinkGovernanceService governanceService;
    private final LinkTokenService tokenService;

    public LinkController(LinkSealService sealService, LinkRebuildService rebuildService,
                          LinkExpiryService expiryService, LinkGovernanceService governanceService,
                          LinkTokenService tokenService) {
        this.sealService = sealService;
        this.rebuildService = rebuildService;
        this.expiryService = expiryService;
        this.governanceService = governanceService;
        this.tokenService = tokenService;
    }

    // ---------- 封存 ----------

    @PostMapping("/versions")
    public Map<String, Object> seal(@RequestBody SealRequest req) {
        return sealService.seal(req.approvalId, req.analysisVersionId, req.providerCode,
                req.strategy, req.replayFrequency, req.escrowRisk, req.reproduceWindowMinutes,
                req.ruleLabel, req.batchId, req.candidateRuleJson);
    }

    @GetMapping("/versions")
    public List<Map<String, Object>> versions(@RequestParam(required = false) Long spaceId) {
        return sealService.list(spaceId).stream().map(lv -> sealService.toView(lv, false)).toList();
    }

    @GetMapping("/versions/{id}")
    public Map<String, Object> version(@PathVariable Long id) {
        return sealService.toView(sealService.mustGet(id), true);
    }

    @GetMapping("/versions/{id}/ledger")
    public Map<String, Object> ledger(@PathVariable Long id) {
        return governanceService.sealLedger(id);
    }

    // ---------- 受控重建 + 一致性核对 ----------

    @PostMapping("/versions/{id}/rebuild")
    public Map<String, Object> rebuild(@PathVariable Long id,
                                       @RequestBody(required = false) Map<String, String> body) {
        String ruleBasis = body == null ? null : body.get("ruleBasis");
        return rebuildService.rebuild(id, ruleBasis);
    }

    @GetMapping("/versions/{id}/runs")
    public List<Map<String, Object>> runs(@PathVariable Long id) {
        return rebuildService.runs(id).stream().map(r -> {
            Map<String, Object> m = new java.util.TreeMap<>();
            m.put("id", r.getId());
            m.put("strategy", r.getStrategy());
            m.put("verdict", r.getVerdict());
            m.put("ruleBasis", r.getRuleBasis());
            m.put("keyGenerationKid", r.getKeyGenerationKid());
            m.put("rebuiltMemberCount", r.getRebuiltMemberCount());
            m.put("sealedMemberCount", r.getSealedMemberCount());
            m.put("digestRootMatch", r.isDigestRootMatch());
            m.put("diff", r.getDiffJson());
            m.put("actor", r.getActor());
            m.put("createdAt", r.getCreatedAt());
            return m;
        }).toList();
    }

    // ---------- 到期证明 ----------

    @PostMapping("/versions/{id}/expire")
    public Map<String, Object> expire(@PathVariable Long id) {
        return expiryService.issueCertificate(id);
    }

    @GetMapping("/expiry-certificates")
    public List<Map<String, Object>> certificates() {
        return expiryService.list().stream().map(expiryService::toView).toList();
    }

    @GetMapping("/expiry-certificates/{id}")
    public Map<String, Object> certificate(@PathVariable Long id) {
        return expiryService.toView(expiryService.mustGet(id));
    }

    // ---------- 只读基础数据 ----------

    @GetMapping("/keys")
    public List<Map<String, Object>> keys() {
        return tokenService.listKeys().stream().map(k -> {
            Map<String, Object> m = new java.util.TreeMap<>();
            m.put("kid", k.getKid());
            m.put("status", k.getStatus());
            m.put("fingerprint", k.getFingerprint());
            m.put("escrowRef", k.getEscrowRef());
            m.put("createdAt", k.getCreatedAt());
            m.put("retiredAt", k.getRetiredAt());
            m.put("note", k.getNote());
            return m;
        }).toList();
    }

    @GetMapping("/providers")
    public List<Map<String, Object>> providers() {
        return governanceService.listProviders().stream().map(p -> {
            Map<String, Object> m = new java.util.TreeMap<>();
            m.put("providerCode", p.getProviderCode());
            m.put("name", p.getName());
            m.put("status", p.getStatus());
            m.put("coversHospital", p.getCoversHospital() == null ? "" : p.getCoversHospital());
            m.put("note", p.getNote());
            return m;
        }).toList();
    }

    @GetMapping("/purpose-authorizations")
    public List<Map<String, Object>> purposeAuthorizations() {
        return governanceService.listPurposeAuthorizations().stream().map(pa -> {
            Map<String, Object> m = new java.util.TreeMap<>();
            m.put("id", pa.getId());
            m.put("code", pa.getCode());
            m.put("approvalCode", pa.getApprovalCode());
            m.put("purpose", pa.getPurpose());
            m.put("status", pa.getStatus());
            m.put("snapshotHash", pa.getSnapshotHash());
            m.put("purposeScope", pa.getPurposeScope());
            return m;
        }).toList();
    }

    @GetMapping("/lineage")
    public List<Map<String, Object>> lineage(@RequestParam(required = false) Long linkVersionId) {
        if (linkVersionId != null) {
            List<Map<String, Object>> rows = new java.util.ArrayList<>();
            tokenService.lineageFor(linkVersionId).forEach(e -> rows.add(lineageRow(e)));
            return rows;
        }
        return tokenService.lineageAll().stream().map(this::lineageRow).toList();
    }

    private Map<String, Object> lineageRow(com.sandbox.imaging.domain.TokenLineageEvent e) {
        Map<String, Object> m = new java.util.TreeMap<>();
        m.put("id", e.getId());
        m.put("linkVersionId", e.getLinkVersionId());
        m.put("eventType", e.getEventType());
        m.put("keyGenerationKid", e.getKeyGenerationKid());
        m.put("tokenRuleLabel", e.getTokenRuleLabel());
        m.put("memberDigestRoot", e.getMemberDigestRoot());
        m.put("actor", e.getActor());
        m.put("createdAt", e.getCreatedAt());
        m.put("detail", e.getDetailJson());
        return m;
    }

    public static class SealRequest {
        public Long approvalId;
        public Long analysisVersionId;
        public String providerCode;
        public String strategy;
        public String replayFrequency;
        public String escrowRisk;
        public Integer reproduceWindowMinutes;
        public String ruleLabel;
        public Long batchId;
        public String candidateRuleJson;
    }
}
