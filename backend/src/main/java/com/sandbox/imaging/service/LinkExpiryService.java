package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.*;
import com.sandbox.imaging.repo.*;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;

/**
 * 到期证明服务。
 *
 * 复现期限届满后，销毁逐例恢复能力：
 * - 托管封条：清空 sealedPayload，仅保留 sealDigest；
 * - 提供方输入批次：若没有其他仍处复现期限内的链接版本引用，清空逐例条目（PURGED）；
 * - 提供方重建授权：置 EXPIRED，不再允许重新匹配；
 * - 链接版本：置 EXPIRED，逐例重建返回 410。
 * 只保留不可逆统计审计摘要（成员数、院别分布、令牌计数、摘要根），并明确声明“不能再逐例复现”。
 */
@Service
public class LinkExpiryService {

    private final LinkVersionRepository linkRepo;
    private final LinkExpiryCertificateRepository certRepo;
    private final LinkMatchSealRepository sealRepo;
    private final ProviderInputBatchRepository batchRepo;
    private final ProviderRebuildAuthorizationRepository rebuildAuthRepo;
    private final LinkTokenService tokenService;
    private final AuditService auditService;
    private final ObjectMapper mapper;

    public LinkExpiryService(LinkVersionRepository linkRepo, LinkExpiryCertificateRepository certRepo,
                             LinkMatchSealRepository sealRepo, ProviderInputBatchRepository batchRepo,
                             ProviderRebuildAuthorizationRepository rebuildAuthRepo,
                             LinkTokenService tokenService, AuditService auditService,
                             ObjectMapper mapper) {
        this.linkRepo = linkRepo;
        this.certRepo = certRepo;
        this.sealRepo = sealRepo;
        this.batchRepo = batchRepo;
        this.rebuildAuthRepo = rebuildAuthRepo;
        this.tokenService = tokenService;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    @Transactional
    public Map<String, Object> issueCertificate(Long linkVersionId) {
        LinkVersion lv = linkRepo.findById(linkVersionId)
                .orElseThrow(() -> ApiException.notFound("链接版本不存在: " + linkVersionId));
        Optional<LinkExpiryCertificate> existing = certRepo.findByLinkVersionId(linkVersionId);
        if (existing.isPresent()) {
            return toView(existing.get());
        }
        long now = System.currentTimeMillis();
        if (now <= lv.getReproduceWindowEnd()) {
            throw ApiException.conflict("复现期限尚未届满（截止 " + lv.getReproduceWindowEnd()
                    + "），不得提前签发到期证明；如需立即到期请使用“强制到期”演示接口");
        }
        return issue(lv, now, false);
    }

    /**
     * 演示/治理用强制到期：把复现期限截止时间提前到当前，随即签发到期证明。
     * 仅管理员可用；用于验证到期后逐例恢复能力被删除。
     */
    @Transactional
    public Map<String, Object> forceExpire(Long linkVersionId, String reason) {
        LinkVersion lv = linkRepo.findById(linkVersionId)
                .orElseThrow(() -> ApiException.notFound("链接版本不存在: " + linkVersionId));
        Optional<LinkExpiryCertificate> existing = certRepo.findByLinkVersionId(linkVersionId);
        if (existing.isPresent()) {
            return toView(existing.get());
        }
        long now = System.currentTimeMillis();
        lv.setReproduceWindowEnd(now);
        linkRepo.save(lv);
        auditService.log(lv.getSpaceId(), SecurityUtils.currentUser(), "LINK_FORCE_EXPIRE",
                "管理员强制到期链接版本 " + lv.getCode() + "，原因：" + (reason == null ? "" : reason));
        return issue(lv, now, true);
    }

    private Map<String, Object> issue(LinkVersion lv, long now, boolean forced) {
        Map<String, Object> digest = readTree(lv.getMemberDigestJson());
        @SuppressWarnings("unchecked")
        List<Map<String, Object>> rows = (List<Map<String, Object>>) digest.get("members");
        int memberCount = rows == null ? 0 : rows.size();
        @SuppressWarnings("unchecked")
        Map<String, Object> dist = (Map<String, Object>) digest.get("hospitalDistribution");

        // 不可逆统计审计摘要：只有数量/类别分布/哈希根，无可逐例还原的信息
        TreeMap<String, Object> irrev = new TreeMap<>();
        irrev.put("memberCount", memberCount);
        irrev.put("sealedMemberDigestRoot", lv.getMemberDigestRoot());
        irrev.put("hospitalDistribution", dist == null ? Map.of() : dist);
        irrev.put("tokenRuleLabel", ruleLabel(lv));
        irrev.put("sealingStrategy", lv.getSealingStrategy());
        irrev.put("sealPackageHash", lv.getSealPackageHash());
        irrev.put("purposeScope", lv.getPurposeScope());
        irrev.put("reproduceWindowEnd", lv.getReproduceWindowEnd());
        irrev.put("perCaseTokenCount", rows == null ? 0 : rows.size());
        irrev.put("aggregateNote", "仅保留不可逆统计摘要：无法由本摘要反推任何逐例成员映射");
        String irrevJson = CanonicalJson.write(mapper, irrev);
        String digestRoot = CanonicalJson.sha256(irrevJson);

        // 销毁逐例恢复能力
        TreeMap<String, Object> purged = new TreeMap<>();
        LinkMatchSeal seal = sealRepo.findByLinkVersionId(lv.getId()).orElse(null);
        if (seal != null) {
            if ("SEALED".equals(seal.getStatus()) && seal.getSealedPayload() != null) {
                seal.setSealedPayload(null);
                seal.setStatus("DESTROYED");
                seal.setDestroyedAt(now);
                sealRepo.save(seal);
                purged.put("escrowSealCiphertext", "DESTROYED");
                purged.put("escrowSealDigestRetained", seal.getSealDigest());
            } else {
                purged.put("escrowSealCiphertext", "ALREADY_" + seal.getStatus());
            }
        } else {
            purged.put("escrowSealCiphertext", "NOT_APPLICABLE（未采用托管路线）");
        }

        ProviderInputBatch batch = batchRepo.findById(lv.getProviderBatchId()).orElse(null);
        if (batch != null) {
            long otherActive = linkRepo.findByProviderBatchId(batch.getId()).stream()
                    .filter(x -> !Objects.equals(x.getId(), lv.getId()))
                    .filter(x -> System.currentTimeMillis() <= x.getReproduceWindowEnd())
                    .count();
            if (otherActive == 0) {
                int entries = batch.getEntryCount();
                batch.setEntriesJson("[]");
                batch.setEntryCount(0);
                batch.setStatus("PURGED");
                batchRepo.save(batch);
                purged.put("providerInputBatchPerCaseEntries", "PURGED");
                purged.put("providerInputBatchEntriesPurged", entries);
                purged.put("providerInputBatchHashRetained", batch.getBatchHash());
            } else {
                purged.put("providerInputBatchPerCaseEntries", "RETAINED（另有 " + otherActive
                        + " 个仍处复现期限内的链接版本共用该批次）");
            }
        }

        ProviderRebuildAuthorization auth = rebuildAuthRepo.findByLinkVersionId(lv.getId()).orElse(null);
        if (auth != null) {
            auth.setStatus("EXPIRED");
            rebuildAuthRepo.save(auth);
            purged.put("providerRebuildAuthorization", "EXPIRED");
        }

        lv.setStatus("EXPIRED");
        lv.setExpiredAt(now);
        lv.setLastVerifierNote("复现期限届满，逐例恢复能力已删除；仅保留不可逆统计审计摘要，不能再逐例复现");
        linkRepo.save(lv);

        String statement = "复现期限已于 " + new java.util.Date(lv.getReproduceWindowEnd())
                + " 届满。已删除该链接版本的逐例恢复能力（托管封条密文、提供方逐例输入批次、重建授权）；"
                + "仅保留不可逆统计审计摘要（成员数、院别分布、令牌计数与摘要根），"
                + "不能再以任何方式逐例复现或重建原成员映射。";

        TreeMap<String, Object> sealAttestation = new TreeMap<>();
        sealAttestation.put("sealRetained", seal != null && seal.getSealDigest() != null);
        sealAttestation.put("sealStatus", seal == null ? "NONE" : seal.getStatus());
        sealAttestation.put("batchStatus", batch == null ? "MISSING" : batch.getStatus());
        sealAttestation.put("rebuildAuthStatus", auth == null ? "NONE" : auth.getStatus());
        sealAttestation.put("perCaseRecoverable", false);
        sealAttestation.put("forced", forced);

        LinkExpiryCertificate cert = new LinkExpiryCertificate();
        cert.setCode("EXP-" + lv.getCode() + "-" + Long.toString(now, 36));
        cert.setLinkVersionId(lv.getId());
        cert.setLinkVersionCode(lv.getCode());
        cert.setWindowEnd(lv.getReproduceWindowEnd());
        cert.setIssuedAt(now);
        cert.setIrreversibleDigestJson(irrevJson);
        cert.setDigestRoot(digestRoot);
        cert.setPurgedManifestJson(CanonicalJson.write(mapper, purged));
        cert.setPerCaseRecoverable(false);
        cert.setStatement(statement);
        cert.setSealAttestationJson(CanonicalJson.write(mapper, sealAttestation));
        cert.setIssuedBy(SecurityUtils.currentUser());
        cert = certRepo.save(cert);

        TreeMap<String, Object> lineageDetail = new TreeMap<>();
        lineageDetail.put("certificateCode", cert.getCode());
        lineageDetail.put("digestRoot", digestRoot);
        lineageDetail.put("purged", purged);
        lineageDetail.put("forced", forced);
        tokenService.lineage(lv.getId(), lv.getSpaceId(), "EXPIRE", lv.getKeyGenerationKid(),
                ruleLabel(lv), lv.getMemberDigestRoot(), lineageDetail);
        auditService.log(lv.getSpaceId(), SecurityUtils.currentUser(), "LINK_EXPIRY_CERTIFICATE",
                "签发到期证明 " + cert.getCode() + "，逐例恢复能力已销毁，仅留不可逆统计摘要");

        return toView(cert);
    }

    private String ruleLabel(LinkVersion lv) {
        if (lv.getSealPackageJson() == null) return "";
        Map<String, Object> pkg = readTree(lv.getSealPackageJson());
        Object trv = pkg.get("tokenRuleVersion");
        if (trv instanceof Map<?, ?> m) return String.valueOf(m.get("label"));
        return "";
    }

    @Transactional(readOnly = true)
    public List<LinkExpiryCertificate> list() {
        return certRepo.findAllByOrderByIdDesc();
    }

    @Transactional(readOnly = true)
    public LinkExpiryCertificate mustGet(Long id) {
        return certRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("到期证明不存在: " + id));
    }

    public Map<String, Object> toView(LinkExpiryCertificate cert) {
        TreeMap<String, Object> m = new TreeMap<>();
        m.put("id", cert.getId());
        m.put("code", cert.getCode());
        m.put("linkVersionId", cert.getLinkVersionId());
        m.put("linkVersionCode", cert.getLinkVersionCode());
        m.put("windowEnd", cert.getWindowEnd());
        m.put("issuedAt", cert.getIssuedAt());
        m.put("issuedBy", cert.getIssuedBy());
        m.put("digestRoot", cert.getDigestRoot());
        m.put("irreversibleDigest", readTree(cert.getIrreversibleDigestJson()));
        m.put("purgedManifest", readTree(cert.getPurgedManifestJson()));
        m.put("sealAttestation", readTree(cert.getSealAttestationJson()));
        m.put("perCaseRecoverable", cert.isPerCaseRecoverable());
        m.put("statement", cert.getStatement());
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
