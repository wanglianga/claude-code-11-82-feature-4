package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.dto.LinkBatchEntry;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 受控重建服务 + 成员一致性验证器。
 *
 * 复现期限内、密钥已轮换后，仍能按封存包的“旧规则 + 旧密钥代际 + 旧用途授权快照”
 * 重新生成当时成员，再与封存成员摘要逐项核对：
 *
 * - ESCROW_SEAL 路线：解开托管封条得到逐例 token→patientKey/localRef 映射，快速重放；
 * - PROVIDER_REBUILD 路线：用旧输入批次 + 旧候选阻断规则重新匹配（要求提供方在期限内可用）。
 *
 * 结论三态：
 * - REPRODUCED：逐项摘要全部一致，可作为旧实验的复现；
 * - DIVERGED：重建成员与封存摘要不一致，必须阻止把结果称为该旧实验的复现，并输出差异调查；
 * - EVIDENCE_INCOMPLETE：用途授权或输入批次在期限内缺失（或提供方不可用），链接版本进入证据不完整，
 *   而不是允许成员漂移。
 *
 * 期限届满后逐例恢复能力已被销毁：重建返回 410，只能读取到期证明中的不可逆统计审计摘要。
 */
@Service
public class LinkRebuildService {

    private final LinkVersionRepository linkRepo;
    private final PurposeAuthorizationRepository purposeAuthRepo;
    private final ProviderInputBatchRepository batchRepo;
    private final ProviderProfileRepository providerRepo;
    private final TokenRuleVersionRepository ruleRepo;
    private final LinkMatchSealRepository sealRepo;
    private final ProviderRebuildAuthorizationRepository rebuildAuthRepo;
    private final LinkRebuildRunRepository runRepo;
    private final LinkExpiryCertificateRepository expiryRepo;
    private final TokenKeyGenerationRepository keyRepo;
    private final LinkTokenService tokenService;
    private final LinkSealService sealService;
    private final LinkMatchingEngine matchingEngine;
    private final LinkCryptoService crypto;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public LinkRebuildService(LinkVersionRepository linkRepo, PurposeAuthorizationRepository purposeAuthRepo,
                              ProviderInputBatchRepository batchRepo, ProviderProfileRepository providerRepo,
                              TokenRuleVersionRepository ruleRepo, LinkMatchSealRepository sealRepo,
                              ProviderRebuildAuthorizationRepository rebuildAuthRepo,
                              LinkRebuildRunRepository runRepo,
                              LinkExpiryCertificateRepository expiryRepo,
                              TokenKeyGenerationRepository keyRepo,
                              LinkTokenService tokenService, LinkSealService sealService,
                              LinkMatchingEngine matchingEngine, LinkCryptoService crypto,
                              AuditService auditService, ObjectMapper mapper) {
        this.linkRepo = linkRepo;
        this.purposeAuthRepo = purposeAuthRepo;
        this.batchRepo = batchRepo;
        this.providerRepo = providerRepo;
        this.ruleRepo = ruleRepo;
        this.sealRepo = sealRepo;
        this.rebuildAuthRepo = rebuildAuthRepo;
        this.runRepo = runRepo;
        this.expiryRepo = expiryRepo;
        this.keyRepo = keyRepo;
        this.tokenService = tokenService;
        this.sealService = sealService;
        this.matchingEngine = matchingEngine;
        this.crypto = crypto;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    /**
     * 受控重建 + 一致性核对。
     *
     * @param linkVersionId 链接版本
     * @param ruleBasis     REPLAYED_OLD（默认，按封存旧规则） / CURRENT_KEY_DRIFT（仅用于演示新密钥会产生漂移）
     */
    @Transactional
    public Map<String, Object> rebuild(Long linkVersionId, String ruleBasis) {
        LinkVersion lv = linkRepo.findById(linkVersionId)
                .orElseThrow(() -> ApiException.notFound("链接版本不存在: " + linkVersionId));
        long now = System.currentTimeMillis();
        if (now > lv.getReproduceWindowEnd()) {
            LinkExpiryCertificate cert = expiryRepo.findByLinkVersionId(lv.getId()).orElse(null);
            throw ApiException.gone("复现期限已届满（" + lv.getReproduceWindowEnd()
                    + "），逐例恢复能力已销毁，不能再逐例复现；仅可读取到期证明"
                    + (cert == null ? "" : " " + cert.getCode()) + " 中的不可逆统计审计摘要");
        }
        if ("EXPIRED".equals(lv.getStatus())) {
            throw ApiException.gone("链接版本 " + lv.getCode() + " 已签发到期证明，逐例恢复能力已销毁");
        }
        String basis = ruleBasis == null || ruleBasis.isBlank()
                ? "REPLAYED_OLD" : ruleBasis.toUpperCase(Locale.ROOT);
        if (!"REPLAYED_OLD".equals(basis) && !"CURRENT_KEY_DRIFT".equals(basis)) {
            throw ApiException.badRequest("未知重建规则基准: " + ruleBasis);
        }

        Map<String, Object> pkg = sealService.readPackage(lv);
        String sealSalt = sealService.sealSaltOf(lv);

        // 证据完整性前置：用途授权快照与输入批次必须在期限内可用
        PurposeAuthorization pa = purposeAuthRepo.findById(lv.getPurposeAuthorizationId()).orElse(null);
        ProviderInputBatch batch = batchRepo.findById(lv.getProviderBatchId()).orElse(null);
        List<String> evidenceGaps = new ArrayList<>();
        if (pa == null) {
            evidenceGaps.add("用途授权快照缺失（" + lv.getPurposeAuthorizationId() + "）");
        } else if ("MISSING".equals(pa.getStatus())) {
            evidenceGaps.add("用途授权快照 " + pa.getCode() + " 标记为 MISSING");
        }
        if (batch == null) {
            evidenceGaps.add("提供方输入批次缺失（" + lv.getProviderBatchId() + "）");
        } else if (!"SEALED".equals(batch.getStatus())) {
            evidenceGaps.add("提供方输入批次 " + batch.getCode() + " 状态为 "
                    + batch.getStatus() + "，逐例条目不可用");
        }
        // 只在“相同用途作用域 + 相同授权快照”下重建：封存包、链接版本、用途授权三者必须自洽
        @SuppressWarnings("unchecked")
        Map<String, Object> pkgAuth = (Map<String, Object>) pkg.get("purposeAuthorization");
        Object pkgScope = pkg.get("purposeScope");
        if (pkgScope == null || !Objects.equals(String.valueOf(pkgScope), lv.getPurposeScope())) {
            evidenceGaps.add("封存包用途作用域与链接版本不一致，拒绝在跨用途条件下重建");
        }
        if (pa != null && pkgAuth != null
                && !Objects.equals(String.valueOf(pkgAuth.get("snapshotHash")), pa.getSnapshotHash())) {
            evidenceGaps.add("用途授权快照哈希与封存包不一致，拒绝以不同授权快照重建");
        }
        if (!evidenceGaps.isEmpty()) {
            return recordEvidenceIncomplete(lv, basis, evidenceGaps, pkg);
        }

        // 取封存时的规则版本与密钥代际（不是当前默认规则/新密钥）
        TokenRuleVersion rule = ruleRepo.findById(lv.getTokenRuleVersionId()).orElse(null);
        if (rule == null) {
            return recordEvidenceIncomplete(lv, basis,
                    List.of("令牌化规则版本缺失（" + lv.getTokenRuleVersionId() + "）"), pkg);
        }
        TokenKeyGeneration sealKey = keyRepo.findByKid(lv.getKeyGenerationKid()).orElse(null);
        if (sealKey == null || sealKey.getKeyMaterial() == null) {
            return recordEvidenceIncomplete(lv, basis,
                    List.of("封存密钥代际 " + lv.getKeyGenerationKid() + " 材料缺失，逐例摘要无法重算"), pkg);
        }

        Map<String, Object> permission = readTree(lv.getMatchPermissionJson());
        int maxCalls = permission.get("maxCalls") == null ? 50 : ((Number) permission.get("maxCalls")).intValue();
        if (lv.getMatchCallsUsed() >= maxCalls) {
            throw ApiException.forbidden("受控匹配权限已用尽（" + lv.getMatchCallsUsed() + "/" + maxCalls
                    + "），须重新申请链接版本匹配授权");
        }

        List<LinkMatchingEngine.LinkedMember> rebuilt;
        String strategy = lv.getSealingStrategy();
        String providerState = null;
        if ("ESCROW_SEAL".equals(strategy)) {
            rebuilt = replayFromEscrowSeal(lv, sealKey);
        } else {
            ProviderRebuildAuthorization auth = rebuildAuthRepo.findByLinkVersionId(lv.getId()).orElse(null);
            ProviderProfile provider = providerRepo.findByProviderCode(lv.getProviderCode()).orElse(null);
            providerState = provider == null ? "MISSING" : provider.getStatus();
            if (auth == null) {
                return recordEvidenceIncomplete(lv, basis,
                        List.of("提供方重建授权缺失，无法在期限内重新匹配"), pkg);
            }
            if (!"ACTIVE".equals(auth.getStatus())) {
                return recordEvidenceIncomplete(lv, basis,
                        List.of("提供方重建授权状态为 " + auth.getStatus() + "，无法重新匹配"), pkg);
            }
            if (now > auth.getExpiresAt()) {
                return recordEvidenceIncomplete(lv, basis,
                        List.of("提供方重建授权已过期（" + auth.getExpiresAt() + "），无法重新匹配"), pkg);
            }
            if (auth.getInvocationCount() >= auth.getMaxInvocations()) {
                return recordEvidenceIncomplete(lv, basis,
                        List.of("提供方重建授权调用次数已用尽（" + auth.getInvocationCount()
                                + "/" + auth.getMaxInvocations() + "）"), pkg);
            }
            if (provider == null || !"AVAILABLE".equals(provider.getStatus())) {
                return recordEvidenceIncomplete(lv, basis,
                        List.of("提供方 " + lv.getProviderCode() + " 当前不可用，重建路线无法重新匹配；"
                                + "期限内的旧成员只能标记为证据不完整"), pkg);
            }
            auth.setInvocationCount(auth.getInvocationCount() + 1);
            rebuildAuthRepo.save(auth);

            rebuilt = rebuildViaProvider(lv, rule, sealKey, batch, pkg, basis);
        }

        // 若演示“当前密钥漂移”，用新 ACTIVE 密钥重算令牌，必然得到不同成员（仅用于对比证明）
        if ("CURRENT_KEY_DRIFT".equals(basis)) {
            TokenKeyGeneration currentKey = tokenService.activeKey();
            if (!Objects.equals(currentKey.getKid(), lv.getKeyGenerationKid())) {
                rebuilt = rebuildViaProviderWithKey(lv, rule, currentKey, batch, pkg);
            }
        }

        lv.setMatchCallsUsed(lv.getMatchCallsUsed() + 1);
        linkRepo.save(lv);

        Map<String, Object> verification = verify(lv, rebuilt, sealSalt, pkg, basis, providerState);

        LinkRebuildRun run = new LinkRebuildRun();
        run.setLinkVersionId(lv.getId());
        run.setStrategy(strategy);
        run.setVerdict((String) verification.get("verdict"));
        run.setKeyGenerationKid(lv.getKeyGenerationKid());
        run.setRuleBasis(basis);
        run.setRebuiltMemberCount(rebuilt.size());
        run.setSealedMemberCount(lv.getMemberCount());
        run.setDigestRootMatch(Boolean.TRUE.equals(verification.get("digestRootMatch")));
        run.setDiffJson(CanonicalJson.write(mapper, verification.get("diff")));
        run.setReportJson(CanonicalJson.write(mapper, verification));
        run.setActor(SecurityUtils.currentUser());
        run = runRepo.save(run);

        String verdict = (String) verification.get("verdict");
        lv.setStatus("REPRODUCED_OK".equals(verdict) ? "REPRODUCED_OK" : "DIVERGED");
        lv.setLastVerifierNote((String) verification.get("conclusion"));
        linkRepo.save(lv);

        TreeMap<String, Object> detail = new TreeMap<>();
        detail.put("verdict", verdict);
        detail.put("runId", run.getId());
        detail.put("strategy", strategy);
        detail.put("ruleBasis", basis);
        detail.put("digestRootMatch", verification.get("digestRootMatch"));
        detail.put("rebuiltMemberCount", rebuilt.size());
        detail.put("sealedMemberCount", lv.getMemberCount());
        tokenService.lineage(lv.getId(), lv.getSpaceId(), "VERIFY", lv.getKeyGenerationKid(),
                rule.getLabel(), lv.getMemberDigestRoot(), detail);
        auditService.log(lv.getSpaceId(), SecurityUtils.currentUser(),
                "DIVERGED".equals(verdict) ? "LINK_REBUILD_DIVERGED" : "LINK_REBUILD_VERIFY",
                "受控重建 " + lv.getCode() + " => " + verdict
                        + "（策略=" + strategy + "，基准=" + basis + "，重建=" + rebuilt.size()
                        + "，封存=" + lv.getMemberCount() + "）");

        Map<String, Object> out = new TreeMap<>(verification);
        out.put("linkVersionCode", lv.getCode());
        out.put("linkVersionId", lv.getId());
        out.put("runId", run.getId());
        out.put("strategy", strategy);
        out.put("ruleBasis", basis);
        out.put("keyGenerationKid", lv.getKeyGenerationKid());
        out.put("providerState", providerState);
        out.put("matchCallsUsed", lv.getMatchCallsUsed());
        out.put("matchCallsMax", maxCalls);
        out.put("reproduceWindowEnd", lv.getReproduceWindowEnd());
        return out;
    }

    // ---------------- 两条重建路线 ----------------

    /** 托管封条重放：解封逐例映射，再按旧规则重算令牌与摘要，确保与封存规则一致 */
    private List<LinkMatchingEngine.LinkedMember> replayFromEscrowSeal(LinkVersion lv, TokenKeyGeneration sealKey) {
        LinkMatchSeal seal = sealRepo.findByLinkVersionId(lv.getId())
                .orElseThrow(() -> ApiException.conflict("托管封条缺失，无法重放"));
        if (!"SEALED".equals(seal.getStatus()) || seal.getSealedPayload() == null) {
            throw ApiException.gone("托管封条已销毁（" + seal.getStatus() + "），逐例恢复能力不可用");
        }
        String plain = crypto.escrowOpen(sealKey.getKeyMaterial(), seal.getSealedPayload());
        Map<String, Object> mapping = readTree(plain);
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) mapping.get("members");
        List<LinkMatchingEngine.LinkedMember> out = new ArrayList<>();
        if (rows != null) {
            for (Map<String, Object> r : rows) {
                out.add(new LinkMatchingEngine.LinkedMember(
                        String.valueOf(r.get("patientKey")),
                        String.valueOf(r.get("token")),
                        String.valueOf(r.get("providerLocalRef"))));
            }
        }
        out.sort(Comparator.comparing(LinkMatchingEngine.LinkedMember::token));
        return out;
    }

    /** 提供方重建：用旧输入批次 + 旧候选阻断规则重新匹配（调用方已校验提供方可用与授权有效） */
    private List<LinkMatchingEngine.LinkedMember> rebuildViaProvider(LinkVersion lv, TokenRuleVersion rule,
                                                                     TokenKeyGeneration sealKey,
                                                                     ProviderInputBatch batch,
                                                                     Map<String, Object> pkg, String basis) {
        return rebuildViaProviderWithKey(lv, rule, sealKey, batch, pkg);
    }

    private List<LinkMatchingEngine.LinkedMember> rebuildViaProviderWithKey(LinkVersion lv, TokenRuleVersion rule,
                                                                            TokenKeyGeneration key,
                                                                            ProviderInputBatch batch,
                                                                            Map<String, Object> pkg) {
        Approval approval = resolveApproval(pkg, lv);
        String wlLabel = String.valueOf(((Map<?, ?>) pkg.get("waterline")).get("label"));
        Waterline waterline = new Waterline();
        waterline.setLabel(wlLabel);
        waterline.setCutoffAt(((Number) ((Map<?, ?>) pkg.get("waterline")).get("cutoffAt")).longValue());
        // 用封存规格重建中心成员，而不是当前批准现状
        List<LinkMatchingEngine.CenterMember> centerMembers =
                centerMembersFromPackage(approval, waterline, lv);
        List<LinkBatchEntry> entries = tokenService.batchEntries(batch);
        CandidateBlockingRule blocking = CandidateBlockingRule.parse(mapper, lv.getCandidateBlockingRuleJson());

        Map<String, Object> memberDigest = readTree(lv.getMemberDigestJson());
        Object saltObj = pkg.get("sealSalt");
        String sealSalt = saltObj == null ? null : String.valueOf(saltObj);
        LinkMatchingEngine.MatchResult result = matchingEngine.match(
                lv.getPurposeScope(), rule, key.getKeyMaterial(), sealSalt,
                centerMembers, entries, blocking);
        return result.members();
    }

    private Approval resolveApproval(Map<String, Object> pkg, LinkVersion lv) {
        // 用封存包内的批准编译规格构造只读批准快照，避免依赖当前批准是否仍 ACTIVE
        Approval snap = new Approval();
        @SuppressWarnings("unchecked")
        Map<String, Object> a = (Map<String, Object>) pkg.get("approval");
        snap.setCode(String.valueOf(a.get("code")));
        snap.setTitle(String.valueOf(a.get("title")));
        snap.setPurpose(String.valueOf(a.get("purpose")));
        snap.setCenter(String.valueOf(a.get("center")));
        snap.setCompiledJson(CanonicalJson.write(mapper, a.get("compiledSpec")));
        snap.setSpecHash(String.valueOf(a.get("specHash")));
        return snap;
    }

    private List<LinkMatchingEngine.CenterMember> centerMembersFromPackage(Approval approval, Waterline waterline,
                                                                           LinkVersion lv) {
        // 中心成员由封存批准规格 + 水线确定性解析，再按提供方覆盖院区过滤
        String hospital = null;
        @SuppressWarnings("unchecked")
        Map<String, Object> pkg = sealService.readPackage(lv);
        @SuppressWarnings("unchecked")
        Map<String, Object> provider = (Map<String, Object>) pkg.get("provider");
        if (provider != null) {
            Object h = provider.get("coversHospital");
            hospital = h == null || String.valueOf(h).isBlank() ? null : String.valueOf(h);
        }
        return resolveCenterMembers(approval, waterline, hospital);
    }

    /** 中心成员直接从库中按封存规格解析：复用 CohortService.resolveMembers 的语义 */
    private List<LinkMatchingEngine.CenterMember> resolveCenterMembers(Approval approval, Waterline waterline,
                                                                       String hospital) {
        return sealService.centerMembersFor(approval, waterline, hospital);
    }

    // ---------------- 成员一致性验证器 ----------------

    private Map<String, Object> verify(LinkVersion lv, List<LinkMatchingEngine.LinkedMember> rebuilt,
                                       String sealSalt, Map<String, Object> pkg,
                                       String basis, String providerState) {
        Map<String, Object> sealedDigest = readTree(lv.getMemberDigestJson());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> sealedRows = (List<Map<String, Object>>) sealedDigest.get("members");
        Map<String, String> sealedByToken = new TreeMap<>();
        if (sealedRows != null) {
            for (Map<String, Object> r : sealedRows) {
                sealedByToken.put(String.valueOf(r.get("token")), String.valueOf(r.get("digest")));
            }
        }
        Map<String, String> rebuiltByToken = new TreeMap<>();
        List<String> rebuiltLines = new ArrayList<>();
        for (LinkMatchingEngine.LinkedMember m : rebuilt) {
            String digest = crypto.memberDigest(sealSalt, m.token(), m.patientKey());
            rebuiltByToken.put(m.token(), digest);
            rebuiltLines.add(m.token() + "|" + digest);
        }

        List<String> missing = new ArrayList<>();      // 封存有、重建无
        List<String> added = new ArrayList<>();        // 重建有、封存无
        List<String> mismatched = new ArrayList<>();   // 令牌相同但摘要不同
        for (Map.Entry<String, String> e : sealedByToken.entrySet()) {
            String rebuiltDigest = rebuiltByToken.get(e.getKey());
            if (rebuiltDigest == null) missing.add(e.getKey());
            else if (!rebuiltDigest.equals(e.getValue())) mismatched.add(e.getKey());
        }
        for (String t : rebuiltByToken.keySet()) {
            if (!sealedByToken.containsKey(t)) added.add(t);
        }

        String rebuiltRoot = crypto.digestRoot(rebuiltLines);
        boolean digestRootMatch = Objects.equals(rebuiltRoot, lv.getMemberDigestRoot());
        boolean countsMatch = rebuilt.size() == lv.getMemberCount();
        boolean clean = digestRootMatch && countsMatch && missing.isEmpty()
                && added.isEmpty() && mismatched.isEmpty();

        String verdict = clean ? "REPRODUCED_OK" : "DIVERGED";
        String conclusion;
        if (clean) {
            conclusion = "REPRODUCED：按封存旧规则/旧密钥代际/旧授权快照重建，逐项成员摘要与封存摘要完全一致";
        } else {
            conclusion = "DIVERGED：重建成员与封存摘要不一致，禁止将该结果称为旧实验的复现；已生成差异调查";
        }

        TreeMap<String, Object> diff = new TreeMap<>();
        diff.put("sealedCount", lv.getMemberCount());
        diff.put("rebuiltCount", rebuilt.size());
        diff.put("countMatch", countsMatch);
        diff.put("missingInRebuild", missing);
        diff.put("addedInRebuild", added);
        diff.put("digestMismatch", mismatched);
        diff.put("sealedDigestRoot", lv.getMemberDigestRoot());
        diff.put("rebuiltDigestRoot", rebuiltRoot);
        diff.put("digestRootMatch", digestRootMatch);
        diff.put("investigationChecklist", buildChecklist(lv, missing, added, mismatched, digestRootMatch));
        diff.put("claimsReproduction", clean);

        TreeMap<String, Object> out = new TreeMap<>();
        out.put("verdict", verdict);
        out.put("conclusion", conclusion);
        out.put("digestRootMatch", digestRootMatch);
        out.put("memberCountMatch", countsMatch);
        out.put("sealedMemberCount", lv.getMemberCount());
        out.put("rebuiltMemberCount", rebuilt.size());
        out.put("sealedDigestRoot", lv.getMemberDigestRoot());
        out.put("rebuiltDigestRoot", rebuiltRoot);
        out.put("diff", diff);
        out.put("claimsOldExperimentReproduction", clean);
        out.put("providerState", providerState);
        out.put("ruleBasis", basis);
        out.put("sealRuleLabel", ruleLabelOf(pkg));
        out.put("sealKeyKid", lv.getKeyGenerationKid());
        // 逐项摘要（token 与摘要均为不可逆伪标识，不含身份）
        out.put("sealedDigests", sealedRows);
        out.put("rebuiltDigests", rebuiltByToken.entrySet().stream().map(e -> {
            Map<String, Object> row = new TreeMap<>();
            row.put("token", e.getKey());
            row.put("digest", e.getValue());
            return row;
        }).toList());
        return out;
    }

    private List<String> buildChecklist(LinkVersion lv, List<String> missing, List<String> added,
                                        List<String> mismatched, boolean rootMatch) {
        List<String> items = new ArrayList<>();
        if (!added.isEmpty()) {
            items.add("重建多出 " + added.size() + " 个封存中不存在的成员：核查提供方批次是否混入新行或阻断规则是否被替换");
        }
        if (!missing.isEmpty()) {
            items.add("重建缺少 " + missing.size() + " 个封存成员：核查提供方重建授权是否被提前撤销、输入批次是否被截断");
        }
        if (!mismatched.isEmpty()) {
            items.add("有 " + mismatched.size() + " 个令牌摘要不一致：核查是否误用轮换后的新密钥或新令牌规则版本");
        }
        if (!rootMatch && missing.isEmpty() && added.isEmpty() && mismatched.isEmpty()) {
            items.add("逐项集合一致但摘要根不一致：核查 sealSalt 或规范化序列化是否被改动");
        }
        items.add("核对封存包哈希 " + lv.getSealPackageHash() + " 是否与台账记录一致");
        items.add("比对令牌谱系账中 SEAL 与本次 VERIFY 事件引用的 kid/规则版本是否完全相同");
        return items;
    }

    private String ruleLabelOf(Map<String, Object> pkg) {
        Object trv = pkg.get("tokenRuleVersion");
        if (trv instanceof Map<?, ?> m) return String.valueOf(m.get("label"));
        return "";
    }

    // ---------------- 证据不完整 / 到期 ----------------

    private Map<String, Object> recordEvidenceIncomplete(LinkVersion lv, String basis,
                                                         List<String> gaps, Map<String, Object> pkg) {
        lv.setStatus("EVIDENCE_INCOMPLETE");
        lv.setLastVerifierNote("证据不完整：" + String.join("；", gaps)
                + "。在复现期限内不得据此改变成员或声称复现，须先补齐授权/输入批次。");
        linkRepo.save(lv);

        TreeMap<String, Object> diff = new TreeMap<>();
        diff.put("evidenceGaps", gaps);
        diff.put("sealedMemberCount", lv.getMemberCount());
        diff.put("claimsReproduction", false);
        diff.put("policy", "证据不完整 = 拒绝成员漂移：不得以任何替代输入重建成员");

        LinkRebuildRun run = new LinkRebuildRun();
        run.setLinkVersionId(lv.getId());
        run.setStrategy(lv.getSealingStrategy());
        run.setVerdict("EVIDENCE_INCOMPLETE");
        run.setKeyGenerationKid(lv.getKeyGenerationKid());
        run.setRuleBasis(basis);
        run.setRebuiltMemberCount(0);
        run.setSealedMemberCount(lv.getMemberCount());
        run.setDigestRootMatch(false);
        run.setDiffJson(CanonicalJson.write(mapper, diff));
        run.setReportJson(CanonicalJson.write(mapper, diff));
        run.setActor(SecurityUtils.currentUser());
        run = runRepo.save(run);

        tokenService.lineage(lv.getId(), lv.getSpaceId(), "EVIDENCE_GAP", lv.getKeyGenerationKid(),
                null, lv.getMemberDigestRoot(), diff);
        auditService.log(lv.getSpaceId(), SecurityUtils.currentUser(), "LINK_EVIDENCE_INCOMPLETE",
                "链接版本 " + lv.getCode() + " 证据不完整：" + String.join("；", gaps));

        TreeMap<String, Object> out = new TreeMap<>();
        out.put("linkVersionId", lv.getId());
        out.put("linkVersionCode", lv.getCode());
        out.put("runId", run.getId());
        out.put("verdict", "EVIDENCE_INCOMPLETE");
        out.put("conclusion", lv.getLastVerifierNote());
        out.put("evidenceGaps", gaps);
        out.put("claimsOldExperimentReproduction", false);
        out.put("diff", diff);
        out.put("ruleBasis", basis);
        out.put("reproduceWindowEnd", lv.getReproduceWindowEnd());
        return out;
    }

    // ---------------- 查询 ----------------

    @Transactional(readOnly = true)
    public List<LinkRebuildRun> runs(Long linkVersionId) {
        return runRepo.findByLinkVersionIdOrderByIdDesc(linkVersionId);
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
