package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.LinkBatchEntry;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.*;

/**
 * 链接版本封存服务。
 *
 * 对某次已批准的院外令牌链接分析，固化为一个版本封存包，保留复现闭包：
 * 提供方输入批次 + 令牌化规则版本 + 候选阻断规则 + 用途授权快照 + 受控匹配权限 + 成员摘要。
 *
 * 路线选择（二选一，均须在复现期限内恢复原成员）：
 * - ESCROW_SEAL：托管加密的短期匹配封条，重放快，但托管面较大；
 * - PROVIDER_REBUILD：只留旧输入 + 旧规则 + 期限内的提供方重建授权，每次重新匹配，
 *   留存映射更少，但要求提供方在期限内仍可用。
 * 选择依据记录在 strategyRationaleJson（提供方可用性 / 托管风险 / 重放频率）。
 */
@Service
public class LinkSealService {

    private final LinkVersionRepository linkRepo;
    private final PurposeAuthorizationRepository purposeAuthRepo;
    private final ProviderInputBatchRepository batchRepo;
    private final ProviderProfileRepository providerRepo;
    private final TokenRuleVersionRepository ruleRepo;
    private final LinkMatchSealRepository sealRepo;
    private final ProviderRebuildAuthorizationRepository rebuildAuthRepo;
    private final ApprovalRepository approvalRepo;
    private final WaterlineRepository waterlineRepo;
    private final PatientRepository patientRepo;
    private final AnalysisVersionRepository versionRepo;
    private final CohortService cohortService;
    private final LinkTokenService tokenService;
    private final LinkCryptoService crypto;
    private final LinkMatchingEngine matchingEngine;
    private final AuditService auditService;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public LinkSealService(LinkVersionRepository linkRepo, PurposeAuthorizationRepository purposeAuthRepo,
                           ProviderInputBatchRepository batchRepo, ProviderProfileRepository providerRepo,
                           TokenRuleVersionRepository ruleRepo, LinkMatchSealRepository sealRepo,
                           ProviderRebuildAuthorizationRepository rebuildAuthRepo,
                           ApprovalRepository approvalRepo, WaterlineRepository waterlineRepo,
                           PatientRepository patientRepo, AnalysisVersionRepository versionRepo,
                           CohortService cohortService, LinkTokenService tokenService,
                           LinkCryptoService crypto, LinkMatchingEngine matchingEngine,
                           AuditService auditService, ObjectMapper mapper) {
        this.linkRepo = linkRepo;
        this.purposeAuthRepo = purposeAuthRepo;
        this.batchRepo = batchRepo;
        this.providerRepo = providerRepo;
        this.ruleRepo = ruleRepo;
        this.sealRepo = sealRepo;
        this.rebuildAuthRepo = rebuildAuthRepo;
        this.approvalRepo = approvalRepo;
        this.waterlineRepo = waterlineRepo;
        this.patientRepo = patientRepo;
        this.versionRepo = versionRepo;
        this.cohortService = cohortService;
        this.tokenService = tokenService;
        this.crypto = crypto;
        this.matchingEngine = matchingEngine;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    /** 用途授权快照：每个批准 + specHash 一份，不可变；缺失时由管理员“授权恢复”补录 */
    @Transactional
    public PurposeAuthorization ensurePurposeAuthorization(Approval approval) {
        String code = "PAU-" + approval.getCode() + "-" + approval.getSpecHash().substring(0, 8);
        Optional<PurposeAuthorization> existing = purposeAuthRepo.findByCode(code);
        if (existing.isPresent()) return existing.get();

        TreeMap<String, Object> snapshot = new TreeMap<>();
        snapshot.put("approvalCode", approval.getCode());
        snapshot.put("approvalSpecHash", approval.getSpecHash());
        snapshot.put("purpose", approval.getPurpose());
        snapshot.put("center", approval.getCenter());
        snapshot.put("compiledSpec", cohortService.readCompiled(approval));
        snapshot.put("validFrom", approval.getValidFrom());
        snapshot.put("validTo", approval.getValidTo());
        Map<String, Object> compiled = cohortService.readCompiled(approval);
        snapshot.put("modelUsage", compiled.getOrDefault("modelUsage", ""));

        String snapshotJson = CanonicalJson.write(mapper, snapshot);
        PurposeAuthorization pa = new PurposeAuthorization();
        pa.setCode(code);
        pa.setApprovalId(approval.getId());
        pa.setApprovalCode(approval.getCode());
        pa.setPurpose(approval.getPurpose());
        pa.setModelUsage(String.valueOf(compiled.getOrDefault("modelUsage", "")));
        pa.setCenter(approval.getCenter());
        pa.setSpecHash(approval.getSpecHash());
        pa.setValidFrom(approval.getValidFrom());
        pa.setValidTo(approval.getValidTo());
        pa.setSnapshotJson(snapshotJson);
        pa.setSnapshotHash(CanonicalJson.sha256(snapshotJson));
        pa.setPurposeScope(purposeScopeOf(approval, pa));
        pa.setStatus("ACTIVE");
        pa = purposeAuthRepo.save(pa);
        auditService.log(approval.getSpace().getId(), SecurityUtils.currentUser(), "LINK_PURPOSE_AUTH_SEAL",
                "固化用途授权快照 " + code + " hash=" + pa.getSnapshotHash());
        return pa;
    }

    public String purposeScopeOf(Approval approval, PurposeAuthorization pa) {
        return "LS:" + CanonicalJson.sha256(
                approval.getSpecHash() + "|" + pa.getSnapshotHash() + "|" + approval.getPurpose())
                .substring(0, 24);
    }

    /**
     * 授权恢复：用途授权快照若被标记 MISSING，管理员可据批准现有规格重新固化，
     * 但只允许在新的封存/重建中使用；旧封存包引用的快照若已 MISSING，按证据不完整处理，
     * 不会用新快照替换旧快照来解释成员。
     */
    @Transactional
    public PurposeAuthorization restorePurposeAuthorization(Long approvalId) {
        Approval approval = approvalRepo.findById(approvalId)
                .orElseThrow(() -> ApiException.notFound("批准不存在: " + approvalId));
        PurposeAuthorization pa = ensurePurposeAuthorization(approval);
        pa.setStatus("ACTIVE");
        pa = purposeAuthRepo.save(pa);
        auditService.log(approval.getSpace().getId(), SecurityUtils.currentUser(),
                "LINK_PURPOSE_AUTH_RESTORE", "用途授权快照恢复为 ACTIVE: " + pa.getCode());
        return pa;
    }

    /**
     * 封存链接版本。
     *
     * @param approvalId            已批准的分析批准
     * @param analysisVersionId     可选：绑定的分析版本（决定水线）
     * @param providerCode          提供方
     * @param strategy              ESCROW_SEAL / PROVIDER_REBUILD
     * @param replayFrequency       预期重放频率（LOW/MEDIUM/HIGH）
     * @param escrowRisk            托管风险评估（LOW/MEDIUM/HIGH）
     * @param reproduceWindowMinutes 复现期限（分钟），不得超过批准有效期
     * @param ruleLabel             令牌化规则版本标签（默认 tk-rules-v1）
     * @param batchId               可选：指定已有提供方输入批次；为空则固化新批次
     * @param candidateRuleJson     可选：候选阻断规则；为空用默认规则
     */
    @Transactional
    public Map<String, Object> seal(Long approvalId, Long analysisVersionId, String providerCode,
                                    String strategy, String replayFrequency, String escrowRisk,
                                    Integer reproduceWindowMinutes, String ruleLabel, Long batchId,
                                    String candidateRuleJson) {
        Approval approval = approvalRepo.findById(approvalId)
                .orElseThrow(() -> ApiException.notFound("批准不存在: " + approvalId));
        // 新封存属于新读取，必须走批准访问闸门；已撤回/到期的批准不能再封存新链接版本
        requireActiveApproval(approval);

        ProviderProfile provider = providerRepo.findByProviderCode(providerCode)
                .orElseThrow(() -> ApiException.notFound("提供方不存在: " + providerCode));

        AnalysisVersion version = null;
        String waterlineLabel;
        if (analysisVersionId != null) {
            version = versionRepo.findById(analysisVersionId)
                    .orElseThrow(() -> ApiException.notFound("分析版本不存在: " + analysisVersionId));
            if (!Objects.equals(version.getApproval().getId(), approval.getId())) {
                throw ApiException.badRequest("分析版本不属于该批准");
            }
            waterlineLabel = version.getWaterlineLabel();
        } else {
            waterlineLabel = "WL-2026Q4";
        }
        Waterline waterline = waterlineRepo.findByLabel(waterlineLabel)
                .orElseThrow(() -> ApiException.notFound("水线不存在: " + waterlineLabel));

        PurposeAuthorization pa = ensurePurposeAuthorization(approval);
        if (!"ACTIVE".equals(pa.getStatus())) {
            throw ApiException.conflict("用途授权快照状态为 " + pa.getStatus() + "，不能封存新链接版本");
        }
        String purposeScope = pa.getPurposeScope() != null && !pa.getPurposeScope().isBlank()
                ? pa.getPurposeScope() : purposeScopeOf(approval, pa);

        TokenRuleVersion rule = tokenService.requireRule(
                ruleLabel == null || ruleLabel.isBlank() ? "tk-rules-v1" : ruleLabel);
        TokenKeyGeneration key = tokenService.activeKey();
        CandidateBlockingRule blocking = candidateRuleJson == null || candidateRuleJson.isBlank()
                ? CandidateBlockingRule.demoDefault()
                : CandidateBlockingRule.parse(mapper, candidateRuleJson);

        long now = System.currentTimeMillis();
        long windowEnd = now + Math.max(1, reproduceWindowMinutes == null ? 43200 : reproduceWindowMinutes) * 60_000L;
        windowEnd = Math.min(windowEnd, approval.getValidTo());
        if (windowEnd <= now) {
            throw ApiException.badRequest("复现期限超出批准有效期，无法封存");
        }

        // 提供方输入批次：优先使用指定批次，否则固化新批次
        ProviderInputBatch batch;
        if (batchId != null) {
            batch = batchRepo.findById(batchId)
                    .orElseThrow(() -> ApiException.notFound("提供方输入批次不存在: " + batchId));
            if (!Objects.equals(batch.getProviderCode(), providerCode)) {
                throw ApiException.badRequest("输入批次提供方与所选提供方不一致");
            }
            if (!"SEALED".equals(batch.getStatus())) {
                throw ApiException.conflict("输入批次状态为 " + batch.getStatus() + "，不能用于封存");
            }
        } else {
            List<LinkBatchEntry> entries = buildProviderEntries(provider, waterline, approval);
            batch = tokenService.createBatch(providerCode, purposeScope, entries, now, windowEnd, "BATCH");
        }

        // 中心侧成员：由批准编译规格 + 水线确定性解析，再取该提供方覆盖的院区
        List<LinkMatchingEngine.CenterMember> centerMembers =
                centerMembersFor(approval, waterline, provider.getCoversHospital());
        List<LinkBatchEntry> entries = tokenService.batchEntries(batch);
        String sealSalt = crypto.newSealSalt();
        LinkMatchingEngine.MatchResult result = matchingEngine.match(
                purposeScope, rule, key.getKeyMaterial(), sealSalt, centerMembers, entries, blocking);
        if (result.members().isEmpty()) {
            throw ApiException.conflict("按该提供方输入批次未匹配到任何成员，拒绝封存空链接版本");
        }

        String effectiveStrategy = normalizeStrategy(strategy, provider, replayFrequency, escrowRisk);
        Map<String, Object> rationale = buildRationale(provider, effectiveStrategy, replayFrequency, escrowRisk);

        TreeMap<String, Object> matchPermission = new TreeMap<>();
        matchPermission.put("allowedMatchFields", List.of("studyRef", "hospital", "sex", "ageBand"));
        matchPermission.put("caller", "link-rebuild-service");
        matchPermission.put("maxCalls", 200);
        matchPermission.put("requireSamePurposeScope", true);
        matchPermission.put("requireSameAuthorizationSnapshot", true);
        matchPermission.put("escrowLocation", "escrow://link-seals/" + key.getKid());

        // 成员摘要：汇总 + 逐成员摘要（token,digest，均不含身份，可逐项核对）
        TreeMap<String, Object> memberDigest = new TreeMap<>();
        memberDigest.put("memberCount", result.members().size());
        memberDigest.put("digestRoot", result.digestRoot());
        memberDigest.put("hospitalDistribution", result.summary().get("hospitalDistribution"));
        memberDigest.put("members", result.memberDigests());
        memberDigest.put("blockedTokens", result.blockedTokenRefs());

        TreeMap<String, Object> pkg = new TreeMap<>();
        pkg.put("approval", Map.of(
                "id", approval.getId(),
                "code", approval.getCode(),
                "title", approval.getTitle(),
                "purpose", approval.getPurpose(),
                "center", approval.getCenter(),
                "specHash", approval.getSpecHash(),
                "compiledSpec", cohortService.readCompiled(approval)));
        pkg.put("analysisVersion", version == null ? null : Map.of(
                "id", version.getId(),
                "versionNo", version.getVersionNo(),
                "algorithmVersion", version.getAlgorithmVersion(),
                "specHash", version.getSpecHash()));
        pkg.put("waterline", Map.of("label", waterline.getLabel(), "cutoffAt", waterline.getCutoffAt()));
        pkg.put("purposeAuthorization", Map.of(
                "code", pa.getCode(),
                "snapshotHash", pa.getSnapshotHash(),
                "purpose", pa.getPurpose(),
                "modelUsage", pa.getModelUsage() == null ? "" : pa.getModelUsage(),
                "validFrom", pa.getValidFrom(),
                "validTo", pa.getValidTo()));
        pkg.put("provider", Map.of(
                "code", provider.getProviderCode(),
                "name", provider.getName(),
                "coversHospital", provider.getCoversHospital() == null ? "" : provider.getCoversHospital()));
        pkg.put("providerInputBatch", Map.of(
                "id", batch.getId(),
                "code", batch.getCode(),
                "batchHash", batch.getBatchHash(),
                "entryCount", batch.getEntryCount(),
                "producedAt", batch.getProducedAt(),
                "expiresAt", batch.getExpiresAt(),
                "purposeScope", batch.getPurposeScope()));
        pkg.put("tokenRuleVersion", Map.of(
                "label", rule.getLabel(),
                "algorithm", rule.getAlgorithm(),
                "truncation", rule.getTruncation(),
                "prefix", rule.getPrefix(),
                "ruleJson", rule.getRuleJson()));
        pkg.put("keyGeneration", Map.of(
                "kid", key.getKid(),
                "fingerprint", key.getFingerprint(),
                "escrowRef", key.getEscrowRef()));
        pkg.put("purposeScope", purposeScope);
        pkg.put("candidateBlockingRule", blocking.toMap());
        pkg.put("matchPermission", matchPermission);
        pkg.put("sealingStrategy", effectiveStrategy);
        pkg.put("strategyRationale", rationale);
        pkg.put("memberDigest", memberDigest);
        pkg.put("reproduceWindow", Map.of("start", now, "end", windowEnd));
        // sealSalt 仅用于逐成员摘要重算，不对外投影
        pkg.put("sealSalt", sealSalt);

        LinkVersion lv = new LinkVersion();
        lv.setCode("LNK-" + randomSuffix());
        lv.setSpaceId(approval.getSpace().getId());
        lv.setApprovalId(approval.getId());
        lv.setApprovalCode(approval.getCode());
        lv.setAnalysisVersionId(version == null ? null : version.getId());
        lv.setAnalysisVersionNo(version == null ? null : version.getVersionNo());
        lv.setProviderCode(providerCode);
        lv.setPurposeAuthorizationId(pa.getId());
        lv.setProviderBatchId(batch.getId());
        lv.setTokenRuleVersionId(rule.getId());
        lv.setKeyGenerationKid(key.getKid());
        lv.setCandidateBlockingRuleJson(CanonicalJson.write(mapper, blocking.toMap()));
        lv.setPurposeScope(purposeScope);
        lv.setMatchPermissionJson(CanonicalJson.write(mapper, matchPermission));
        lv.setMemberDigestJson(CanonicalJson.write(mapper, memberDigest));
        lv.setMemberDigestRoot(result.digestRoot());
        lv.setMemberCount(result.members().size());
        lv.setSealingStrategy(effectiveStrategy);
        lv.setStrategyRationaleJson(CanonicalJson.write(mapper, rationale));
        lv.setSealPackageJson(CanonicalJson.write(mapper, pkg));
        lv.setSealPackageHash(CanonicalJson.sha256(lv.getSealPackageJson()));
        lv.setReproduceWindowStart(now);
        lv.setReproduceWindowEnd(windowEnd);
        lv.setStatus("SEALED");
        lv.setMatchCallsUsed(0);
        lv = linkRepo.save(lv);

        // 路线专属留存
        if ("ESCROW_SEAL".equals(effectiveStrategy)) {
            TreeMap<String, Object> mapping = new TreeMap<>();
            List<Map<String, Object>> rows = new ArrayList<>();
            for (LinkMatchingEngine.LinkedMember m : result.members()) {
                Map<String, Object> row = new TreeMap<>();
                row.put("token", m.token());
                row.put("patientKey", m.patientKey());
                row.put("providerLocalRef", m.providerLocalRef());
                rows.add(row);
            }
            mapping.put("members", rows);
            mapping.put("purposeScope", purposeScope);
            String plain = CanonicalJson.write(mapper, mapping);
            String sealed = crypto.escrowSeal(key.getKeyMaterial(), plain);
            String sealDigest = CanonicalJson.sha256(plain);
            sealRepo.save(new LinkMatchSeal(lv.getId(), key.getKid(), sealed, sealDigest));
        } else {
            ProviderRebuildAuthorization auth = new ProviderRebuildAuthorization();
            auth.setCode("PRA-" + randomSuffix());
            auth.setLinkVersionId(lv.getId());
            auth.setProviderCode(providerCode);
            auth.setPurposeScope(purposeScope);
            auth.setStatus("ACTIVE");
            auth.setMaxInvocations(200);
            auth.setInvocationCount(0);
            auth.setAuthorizedAt(now);
            auth.setExpiresAt(windowEnd);
            auth.setNote("提供方重建授权：仅限相同用途作用域与相同授权快照，期限至复现窗口结束");
            rebuildAuthRepo.save(auth);
        }

        TreeMap<String, Object> sealDetail = new TreeMap<>();
        sealDetail.put("strategy", effectiveStrategy);
        sealDetail.put("providerCode", providerCode);
        sealDetail.put("batchCode", batch.getCode());
        sealDetail.put("memberCount", result.members().size());
        sealDetail.put("digestRoot", result.digestRoot());
        sealDetail.put("keyKid", key.getKid());
        sealDetail.put("ruleLabel", rule.getLabel());
        tokenService.lineage(lv.getId(), lv.getSpaceId(), "SEAL", key.getKid(), rule.getLabel(),
                result.digestRoot(), sealDetail);
        auditService.log(lv.getSpaceId(), SecurityUtils.currentUser(), "LINK_VERSION_SEAL",
                "封存链接版本 " + lv.getCode() + " 策略=" + effectiveStrategy
                        + " 提供方=" + providerCode + " 成员=" + result.members().size()
                        + " digestRoot=" + result.digestRoot());

        return toView(lv, true);
    }

    private void requireActiveApproval(Approval approval) {
        long now = System.currentTimeMillis();
        if (!"ACTIVE".equals(approval.getStatus())) {
            throw ApiException.forbidden("批准 " + approval.getCode() + " 状态为 "
                    + approval.getStatus() + "，禁止封存新链接版本");
        }
        if (now < approval.getValidFrom() || now > approval.getValidTo()) {
            throw ApiException.forbidden("批准 " + approval.getCode() + " 已过有效期，禁止封存新链接版本");
        }
    }

    private String normalizeStrategy(String requested, ProviderProfile provider,
                                     String replayFrequency, String escrowRisk) {
        String strategy = requested == null ? "AUTO" : requested.toUpperCase(Locale.ROOT);
        if ("AUTO".equals(strategy)) {
            boolean highReplay = "HIGH".equalsIgnoreCase(replayFrequency);
            boolean highEscrow = "HIGH".equalsIgnoreCase(escrowRisk);
            boolean providerDown = !"AVAILABLE".equals(provider.getStatus());
            strategy = (highReplay || providerDown || highEscrow) ? "ESCROW_SEAL" : "PROVIDER_REBUILD";
        }
        if (!"ESCROW_SEAL".equals(strategy) && !"PROVIDER_REBUILD".equals(strategy)) {
            throw ApiException.badRequest("未知封存策略: " + requested);
        }
        return strategy;
    }

    private Map<String, Object> buildRationale(ProviderProfile provider, String strategy,
                                               String replayFrequency, String escrowRisk) {
        boolean providerAvailable = "AVAILABLE".equals(provider.getStatus());
        TreeMap<String, Object> r = new TreeMap<>();
        r.put("providerAvailability", providerAvailable ? "AVAILABLE" : "UNAVAILABLE");
        r.put("escrowRisk", escrowRisk == null ? "MEDIUM" : escrowRisk.toUpperCase(Locale.ROOT));
        r.put("replayFrequency", replayFrequency == null ? "MEDIUM" : replayFrequency.toUpperCase(Locale.ROOT));
        r.put("chosenStrategy", strategy);
        if ("ESCROW_SEAL".equals(strategy)) {
            r.put("tradeoff", "重放快、不依赖提供方在线；代价是密钥托管面较大（逐例密文封条）");
            r.put("decisionBasis", providerAvailable
                    ? "重放频率高或托管风险可接受，优先保证复现速度"
                    : "提供方当前不可用，必须依赖托管封条才能确保期限内恢复原成员");
        } else {
            r.put("tradeoff", "只留旧输入/旧规则/期限内的提供方重建授权，留存的映射最少；代价是提供方须在期限内可用");
            r.put("decisionBasis", "重放频率低且提供方在期限内可用，优先最小化留存的可识别映射");
        }
        return r;
    }

    private List<LinkBatchEntry> buildProviderEntries(ProviderProfile provider, Waterline waterline,
                                                      Approval approval) {
        List<Patient> patients = patientRepo.findAll();
        patients.sort(Comparator.comparing(Patient::getPatientKey));
        List<String> cohortKeys = cohortService.resolveMembers(approval, waterline);
        List<LinkBatchEntry> entries = new ArrayList<>();
        int seq = 0;
        for (Patient p : patients) {
            if (provider.getCoversHospital() != null
                    && !provider.getCoversHospital().equals(p.getHospital())) continue;
            // 提供方只有参加该研究的患者才在批次中（研究伪标识：ST-<脱敏键>）
            if (!cohortKeys.contains(p.getPatientKey())) continue;
            seq++;
            entries.add(new LinkBatchEntry(
                    provider.getProviderCode() + "-LR-" + String.format("%03d", seq),
                    crypto.studyRefOf(p.getPatientKey()),
                    p.getHospital(),
                    p.getSex(),
                    p.getAgeBand()));
        }
        if (entries.isEmpty()) {
            throw ApiException.conflict("提供方 " + provider.getProviderCode()
                    + " 在该水线下没有候选行，无法封存");
        }
        return entries;
    }

    public List<LinkMatchingEngine.CenterMember> centerMembersFor(Approval approval, Waterline waterline,
                                                                  String hospital) {
        List<String> keys = cohortService.resolveMembers(approval, waterline);
        Map<String, Patient> byKey = new HashMap<>();
        for (Patient p : patientRepo.findAll()) byKey.put(p.getPatientKey(), p);
        List<LinkMatchingEngine.CenterMember> members = new ArrayList<>();
        for (String k : keys) {
            Patient p = byKey.get(k);
            if (p == null) continue;
            if (hospital != null && !hospital.equals(p.getHospital())) continue;
            members.add(new LinkMatchingEngine.CenterMember(k, p.getHospital(), p.getSex(), p.getAgeBand()));
        }
        return members;
    }

    @Transactional(readOnly = true)
    public LinkVersion mustGet(Long id) {
        return linkRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("链接版本不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<LinkVersion> list(Long spaceId) {
        return spaceId == null ? linkRepo.findAllByOrderByIdDesc()
                : linkRepo.findBySpaceIdOrderByIdDesc(spaceId);
    }

    /** 对外视图：默认脱敏 sealSalt 与密钥材料，只暴露可审计字段 */
    public Map<String, Object> toView(LinkVersion lv, boolean includePackage) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("id", lv.getId());
        m.put("code", lv.getCode());
        m.put("spaceId", lv.getSpaceId());
        m.put("approvalId", lv.getApprovalId());
        m.put("approvalCode", lv.getApprovalCode());
        m.put("analysisVersionId", lv.getAnalysisVersionId());
        m.put("analysisVersionNo", lv.getAnalysisVersionNo());
        m.put("providerCode", lv.getProviderCode());
        m.put("purposeAuthorizationId", lv.getPurposeAuthorizationId());
        m.put("providerBatchId", lv.getProviderBatchId());
        m.put("tokenRuleVersionId", lv.getTokenRuleVersionId());
        m.put("keyGenerationKid", lv.getKeyGenerationKid());
        m.put("purposeScope", lv.getPurposeScope());
        m.put("candidateBlockingRule", readTree(lv.getCandidateBlockingRuleJson()));
        m.put("matchPermission", readTree(lv.getMatchPermissionJson()));
        m.put("matchCallsUsed", lv.getMatchCallsUsed());
        m.put("memberDigest", readTree(lv.getMemberDigestJson()));
        m.put("memberDigestRoot", lv.getMemberDigestRoot());
        m.put("memberCount", lv.getMemberCount());
        m.put("sealingStrategy", lv.getSealingStrategy());
        m.put("strategyRationale", readTree(lv.getStrategyRationaleJson()));
        m.put("sealPackageHash", lv.getSealPackageHash());
        m.put("reproduceWindowStart", lv.getReproduceWindowStart());
        m.put("reproduceWindowEnd", lv.getReproduceWindowEnd());
        m.put("windowOpen", System.currentTimeMillis() <= lv.getReproduceWindowEnd());
        m.put("status", lv.getStatus());
        m.put("lastVerifierNote", lv.getLastVerifierNote());
        m.put("createdAt", lv.getCreatedAt());
        m.put("expiredAt", lv.getExpiredAt());
        if (includePackage) {
            Map<String, Object> pkg = readTree(lv.getSealPackageJson());
            if (pkg != null) pkg.remove("sealSalt");
            m.put("sealPackage", pkg);
        }
        return m;
    }

    @SuppressWarnings("unchecked")
    public Map<String, Object> readPackage(LinkVersion lv) {
        return readTree(lv.getSealPackageJson());
    }

    public String sealSaltOf(LinkVersion lv) {
        Object salt = readPackage(lv).get("sealSalt");
        return salt == null ? null : String.valueOf(salt);
    }

    @SuppressWarnings("unchecked")
    private <T> T readTree(String json) {
        try {
            return (T) mapper.readValue(json, Object.class);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String randomSuffix() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
