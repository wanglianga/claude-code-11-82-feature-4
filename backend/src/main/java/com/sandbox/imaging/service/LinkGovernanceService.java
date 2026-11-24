package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.ApprovalSpec;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.*;

/**
 * 链接治理：提供方可用性、用途授权台账、封存台账聚合视图、演示场景编排。
 */
@Service
public class LinkGovernanceService {

    private final ProviderProfileRepository providerRepo;
    private final PurposeAuthorizationRepository purposeAuthRepo;
    private final LinkVersionRepository linkRepo;
    private final LinkMatchSealRepository sealRepo;
    private final ProviderRebuildAuthorizationRepository rebuildAuthRepo;
    private final LinkRebuildRunRepository runRepo;
    private final LinkExpiryCertificateRepository certRepo;
    private final TokenKeyGenerationRepository keyRepo;
    private final TokenRuleVersionRepository ruleRepo;
    private final TokenLineageEventRepository lineageRepo;
    private final ProviderInputBatchRepository batchRepo;
    private final ApprovalRepository approvalRepo;
    private final ResearchSpaceRepository spaceRepo;
    private final LinkSealService sealService;
    private final LinkRebuildService rebuildService;
    private final LinkExpiryService expiryService;
    private final LinkTokenService tokenService;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public LinkGovernanceService(ProviderProfileRepository providerRepo,
                                 PurposeAuthorizationRepository purposeAuthRepo,
                                 LinkVersionRepository linkRepo, LinkMatchSealRepository sealRepo,
                                 ProviderRebuildAuthorizationRepository rebuildAuthRepo,
                                 LinkRebuildRunRepository runRepo,
                                 LinkExpiryCertificateRepository certRepo,
                                 TokenKeyGenerationRepository keyRepo,
                                 TokenRuleVersionRepository ruleRepo,
                                 TokenLineageEventRepository lineageRepo,
                                 ProviderInputBatchRepository batchRepo,
                                 ApprovalRepository approvalRepo,
                                 ResearchSpaceRepository spaceRepo,
                                 LinkSealService sealService, LinkRebuildService rebuildService,
                                 LinkExpiryService expiryService, LinkTokenService tokenService,
                                 AuditService auditService, ObjectMapper mapper) {
        this.providerRepo = providerRepo;
        this.purposeAuthRepo = purposeAuthRepo;
        this.linkRepo = linkRepo;
        this.sealRepo = sealRepo;
        this.rebuildAuthRepo = rebuildAuthRepo;
        this.runRepo = runRepo;
        this.certRepo = certRepo;
        this.keyRepo = keyRepo;
        this.ruleRepo = ruleRepo;
        this.lineageRepo = lineageRepo;
        this.batchRepo = batchRepo;
        this.approvalRepo = approvalRepo;
        this.spaceRepo = spaceRepo;
        this.sealService = sealService;
        this.rebuildService = rebuildService;
        this.expiryService = expiryService;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    // ---------------- 提供方 ----------------

    @Transactional
    public ProviderProfile createProvider(String providerCode, String name, String coversHospital, String note) {
        if (providerRepo.findByProviderCode(providerCode).isPresent()) {
            throw ApiException.conflict("提供方已存在: " + providerCode);
        }
        ProviderProfile p = new ProviderProfile(providerCode, name, note);
        p.setCoversHospital(coversHospital);
        p = providerRepo.save(p);
        auditService.log(null, SecurityUtils.currentUser(), "LINK_PROVIDER_CREATE",
                "登记提供方 " + providerCode + "（覆盖院区 " + coversHospital + "）");
        return p;
    }

    @Transactional
    public ProviderProfile setProviderStatus(String providerCode, String status, String note) {
        ProviderProfile p = providerRepo.findByProviderCode(providerCode)
                .orElseThrow(() -> ApiException.notFound("提供方不存在: " + providerCode));
        if (!"AVAILABLE".equals(status) && !"UNAVAILABLE".equals(status)) {
            throw ApiException.badRequest("提供方状态仅支持 AVAILABLE / UNAVAILABLE");
        }
        p.setStatus(status);
        if (note != null) p.setNote(note);
        p = providerRepo.save(p);
        auditService.log(null, SecurityUtils.currentUser(), "LINK_PROVIDER_STATUS",
                "提供方 " + providerCode + " 状态=" + status);
        return p;
    }

    @Transactional(readOnly = true)
    public List<ProviderProfile> listProviders() {
        return providerRepo.findAll();
    }

    // ---------------- 用途授权台账 ----------------

    @Transactional(readOnly = true)
    public List<PurposeAuthorization> listPurposeAuthorizations() {
        return purposeAuthRepo.findAll();
    }

    @Transactional
    public PurposeAuthorization markPurposeAuthorizationMissing(Long authorizationId, boolean missing) {
        PurposeAuthorization pa = purposeAuthRepo.findById(authorizationId)
                .orElseThrow(() -> ApiException.notFound("用途授权快照不存在: " + authorizationId));
        pa.setStatus(missing ? "MISSING" : "ACTIVE");
        pa = purposeAuthRepo.save(pa);
        auditService.log(null, SecurityUtils.currentUser(),
                missing ? "LINK_PURPOSE_AUTH_MISSING" : "LINK_PURPOSE_AUTH_RESTORE",
                "用途授权快照 " + pa.getCode() + " 状态=" + pa.getStatus());
        return pa;
    }

    // ---------------- 封存台账聚合 ----------------

    @Transactional(readOnly = true)
    public Map<String, Object> sealLedger(Long linkVersionId) {
        LinkVersion lv = linkRepo.findById(linkVersionId)
                .orElseThrow(() -> ApiException.notFound("链接版本不存在: " + linkVersionId));
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("linkVersion", sealService.toView(lv, true));
        m.put("providerInputBatch", batchRepo.findById(lv.getProviderBatchId())
                .map(this::batchView).orElse(null));
        m.put("purposeAuthorization", purposeAuthRepo.findById(lv.getPurposeAuthorizationId())
                .map(this::purposeAuthView).orElse(null));
        m.put("tokenRuleVersion", ruleRepo.findById(lv.getTokenRuleVersionId())
                .map(r -> Map.of("label", r.getLabel(), "algorithm", r.getAlgorithm(),
                        "truncation", r.getTruncation(), "prefix", r.getPrefix(),
                        "ruleJson", r.getRuleJson())).orElse(null));
        m.put("keyGeneration", keyRepo.findByKid(lv.getKeyGenerationKid())
                .map(k -> Map.of("kid", k.getKid(), "status", k.getStatus(),
                        "fingerprint", k.getFingerprint(), "escrowRef", k.getEscrowRef(),
                        "retiredAt", k.getRetiredAt() == null ? "" : k.getRetiredAt())).orElse(null));
        m.put("matchSeal", sealRepo.findByLinkVersionId(lv.getId()).map(s -> {
            TreeMap<String, Object> sm = new TreeMap<>();
            sm.put("status", s.getStatus());
            sm.put("escrowKid", s.getEscrowKid());
            sm.put("sealDigest", s.getSealDigest());
            sm.put("sealedAt", s.getSealedAt());
            sm.put("destroyedAt", s.getDestroyedAt());
            sm.put("payloadPresent", s.getSealedPayload() != null);
            return sm;
        }).orElse(null));
        m.put("rebuildAuthorization", rebuildAuthRepo.findByLinkVersionId(lv.getId()).map(a -> {
            TreeMap<String, Object> am = new TreeMap<>();
            am.put("code", a.getCode());
            am.put("status", a.getStatus());
            am.put("providerCode", a.getProviderCode());
            am.put("invocationCount", a.getInvocationCount());
            am.put("maxInvocations", a.getMaxInvocations());
            am.put("expiresAt", a.getExpiresAt());
            return am;
        }).orElse(null));
        m.put("rebuildRuns", runRepo.findByLinkVersionIdOrderByIdDesc(lv.getId()).stream().map(this::runView).toList());
        m.put("expiryCertificate", certRepo.findByLinkVersionId(lv.getId())
                .map(expiryService::toView).orElse(null));
        m.put("lineage", tokenService.lineageFor(lv.getId()).stream().map(this::lineageView).toList());
        return m;
    }

    // ---------------- 演示场景 ----------------

    /**
     * 一次性铺设演示环境：提供方、令牌化规则、密钥代际与一批链接版本。
     * 若已存在则直接返回汇总。
     */
    @Transactional
    public Map<String, Object> bootstrapDemo() {
        List<ProviderProfile> providers = providerRepo.findAll();
        if (providers.isEmpty()) {
            createProvider("PRV-A", "甲院区影像库", "A", "覆盖医院 A 的院外影像提供方");
            createProvider("PRV-B", "乙院区影像库", "B", "覆盖医院 B 的院外影像提供方");
            createProvider("PRV-AB", "联合清算方", null, "可同时看到两院区候选行，用于跨院联合链接");
            providers = providerRepo.findAll();
        }
        TokenRuleVersion rule = ruleRepo.findByLabel("tk-rules-v1").orElse(null);
        if (rule == null) {
            TreeMap<String, Object> ruleMap = new TreeMap<>();
            ruleMap.put("caseFold", true);
            ruleMap.put("separator", "|");
            ruleMap.put("scopeBinding", "purposeScope");
            rule = new TokenRuleVersion("tk-rules-v1", 24, "tk_",
                    CanonicalJson.write(mapper, ruleMap));
            ruleRepo.save(rule);
        }
        if (keyRepo.findByStatus("ACTIVE").isEmpty()) {
            tokenService.createKey("初始令牌密钥代际");
        }
        TreeMap<String, Object> out = new TreeMap<>();
        out.put("providers", providerRepo.findAll().stream().map(p -> Map.of(
                "providerCode", p.getProviderCode(), "name", p.getName(),
                "coversHospital", p.getCoversHospital() == null ? "" : p.getCoversHospital(),
                "status", p.getStatus())).toList());
        out.put("ruleVersions", ruleRepo.findAll().stream().map(r -> r.getLabel()).toList());
        out.put("keys", keyRepo.findAllByOrderByIdAsc().stream().map(k -> Map.of(
                "kid", k.getKid(), "status", k.getStatus(), "fingerprint", k.getFingerprint())).toList());
        return out;
    }

    // ---------------- 视图投影 ----------------

    private Map<String, Object> batchView(ProviderInputBatch b) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("id", b.getId());
        m.put("code", b.getCode());
        m.put("providerCode", b.getProviderCode());
        m.put("status", b.getStatus());
        m.put("entryCount", b.getEntryCount());
        m.put("batchHash", b.getBatchHash());
        m.put("purposeScope", b.getPurposeScope());
        m.put("producedAt", b.getProducedAt());
        m.put("expiresAt", b.getExpiresAt());
        return m;
    }

    private Map<String, Object> purposeAuthView(PurposeAuthorization pa) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("id", pa.getId());
        m.put("code", pa.getCode());
        m.put("approvalCode", pa.getApprovalCode());
        m.put("purpose", pa.getPurpose());
        m.put("modelUsage", pa.getModelUsage());
        m.put("center", pa.getCenter());
        m.put("status", pa.getStatus());
        m.put("snapshotHash", pa.getSnapshotHash());
        m.put("purposeScope", pa.getPurposeScope());
        m.put("validFrom", pa.getValidFrom());
        m.put("validTo", pa.getValidTo());
        return m;
    }

    private Map<String, Object> runView(LinkRebuildRun r) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("id", r.getId());
        m.put("strategy", r.getStrategy());
        m.put("verdict", r.getVerdict());
        m.put("ruleBasis", r.getRuleBasis());
        m.put("keyGenerationKid", r.getKeyGenerationKid());
        m.put("rebuiltMemberCount", r.getRebuiltMemberCount());
        m.put("sealedMemberCount", r.getSealedMemberCount());
        m.put("digestRootMatch", r.isDigestRootMatch());
        m.put("actor", r.getActor());
        m.put("createdAt", r.getCreatedAt());
        m.put("diff", readTree(r.getDiffJson()));
        return m;
    }

    private Map<String, Object> lineageView(TokenLineageEvent e) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("id", e.getId());
        m.put("eventType", e.getEventType());
        m.put("keyGenerationKid", e.getKeyGenerationKid());
        m.put("tokenRuleLabel", e.getTokenRuleLabel());
        m.put("memberDigestRoot", e.getMemberDigestRoot());
        m.put("actor", e.getActor());
        m.put("createdAt", e.getCreatedAt());
        m.put("detail", readTree(e.getDetailJson()));
        return m;
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
