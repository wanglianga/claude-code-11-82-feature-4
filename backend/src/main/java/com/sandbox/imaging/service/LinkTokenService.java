package com.sandbox.imaging.service;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.ApiException;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.ProviderInputBatch;
import com.sandbox.imaging.domain.TokenKeyGeneration;
import com.sandbox.imaging.domain.TokenLineageEvent;
import com.sandbox.imaging.domain.TokenRuleVersion;
import com.sandbox.imaging.dto.LinkBatchEntry;
import com.sandbox.imaging.repo.ProviderInputBatchRepository;
import com.sandbox.imaging.repo.TokenKeyGenerationRepository;
import com.sandbox.imaging.repo.TokenLineageEventRepository;
import com.sandbox.imaging.repo.TokenRuleVersionRepository;
import com.sandbox.imaging.security.SecurityUtils;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 令牌基础设施：密钥代际轮换、令牌化规则版本、提供方输入批次、令牌谱系账。
 */
@Service
public class LinkTokenService {

    private final TokenKeyGenerationRepository keyRepo;
    private final TokenRuleVersionRepository ruleRepo;
    private final ProviderInputBatchRepository batchRepo;
    private final TokenLineageEventRepository lineageRepo;
    private final LinkCryptoService crypto;
    private final AuditService auditService;
    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public LinkTokenService(TokenKeyGenerationRepository keyRepo, TokenRuleVersionRepository ruleRepo,
                            ProviderInputBatchRepository batchRepo, TokenLineageEventRepository lineageRepo,
                            LinkCryptoService crypto, AuditService auditService, ObjectMapper mapper) {
        this.keyRepo = keyRepo;
        this.ruleRepo = ruleRepo;
        this.batchRepo = batchRepo;
        this.lineageRepo = lineageRepo;
        this.crypto = crypto;
        this.auditService = auditService;
        this.mapper = mapper;
    }

    // ---------------- 密钥代际 ----------------

    @Transactional(readOnly = true)
    public TokenKeyGeneration activeKey() {
        return keyRepo.findByStatus("ACTIVE").stream().findFirst()
                .orElseThrow(() -> ApiException.conflict("没有可用的 ACTIVE 令牌密钥代际"));
    }

    @Transactional(readOnly = true)
    public TokenKeyGeneration requireKey(String kid) {
        return keyRepo.findByKid(kid)
                .orElseThrow(() -> ApiException.notFound("密钥代际不存在: " + kid));
    }

    @Transactional
    public TokenKeyGeneration createKey(String note) {
        long seq = keyRepo.count() + 1;
        String kid = "KID-" + String.format("%04d", seq) + "-" + randomTokenSuffix();
        TokenKeyGeneration key = new TokenKeyGeneration(kid, crypto.newKeyMaterial(),
                "", "escrow://link-keys/" + kid, note);
        key.setFingerprint(crypto.keyFingerprint(key.getKeyMaterial()));
        return keyRepo.save(key);
    }

    /** 轮换：当前 ACTIVE 转 RETIRED（材料保留在托管面），新代际 ACTIVE；旧封存包仍绑定旧 kid。 */
    @Transactional
    public Map<String, Object> rotateKey(String note) {
        TokenKeyGeneration old = activeKey();
        old.setStatus("RETIRED");
        old.setRetiredAt(System.currentTimeMillis());
        old.setNote((old.getNote() == null ? "" : old.getNote() + " | ") + "轮换停用: " + note);
        keyRepo.save(old);

        TokenKeyGeneration fresh = createKey(note);
        Map<String, Object> detail = new TreeMap<>();
        detail.put("retiredKid", old.getKid());
        detail.put("retiredFingerprint", old.getFingerprint());
        detail.put("newKid", fresh.getKid());
        detail.put("newFingerprint", fresh.getFingerprint());
        detail.put("note", note == null ? "" : note);
        lineage(null, null, "KEY_ROTATE", null, null, null, detail);
        auditService.log(null, SecurityUtils.currentUser(), "LINK_KEY_ROTATE",
                "令牌密钥轮换 " + old.getKid() + " -> " + fresh.getKid());
        Map<String, Object> out = new TreeMap<>(detail);
        out.put("retiredEscrowRef", old.getEscrowRef());
        out.put("newEscrowRef", fresh.getEscrowRef());
        return out;
    }

    @Transactional(readOnly = true)
    public List<TokenKeyGeneration> listKeys() {
        return keyRepo.findAllByOrderByIdAsc();
    }

    // ---------------- 令牌化规则版本 ----------------

    @Transactional(readOnly = true)
    public TokenRuleVersion requireRule(String label) {
        return ruleRepo.findByLabel(label)
                .orElseThrow(() -> ApiException.notFound("令牌化规则版本不存在: " + label));
    }

    @Transactional(readOnly = true)
    public List<TokenRuleVersion> listRules() {
        return ruleRepo.findAll();
    }

    // ---------------- 提供方输入批次 ----------------

    /**
     * 为给定提供方固化为批次快照：批次条目只含伪引用与候选属性，
     * purposeScope 参与令牌生成，保证不同用途的令牌不可关联。
     */
    @Transactional
    public ProviderInputBatch createBatch(String providerCode, String purposeScope,
                                          List<LinkBatchEntry> entries, long producedAt, long expiresAt,
                                          String codePrefix) {
        List<LinkBatchEntry> sorted = entries.stream()
                .sorted(Comparator.comparing(LinkBatchEntry::localRef))
                .toList();
        String entriesJson;
        try {
            entriesJson = mapper.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        TreeMap<String, Object> canonical = new TreeMap<>();
        canonical.put("providerCode", providerCode);
        canonical.put("purposeScope", purposeScope);
        canonical.put("entries", sorted.stream().map(e -> {
            TreeMap<String, Object> m = new TreeMap<>();
            m.put("localRef", e.localRef());
            m.put("studyRef", e.studyRef());
            m.put("hospital", e.hospital());
            m.put("sex", e.sex());
            m.put("ageBand", e.ageBand());
            return m;
        }).toList());
        String batchHash = CanonicalJson.sha256(CanonicalJson.write(mapper, canonical));

        ProviderInputBatch batch = new ProviderInputBatch();
        batch.setCode(codePrefix + "-" + randomTokenSuffix());
        batch.setProviderCode(providerCode);
        batch.setPurposeScope(purposeScope);
        batch.setEntriesJson(entriesJson);
        batch.setBatchHash(batchHash);
        batch.setEntryCount(sorted.size());
        batch.setStatus("SEALED");
        batch.setProducedAt(producedAt);
        batch.setExpiresAt(expiresAt);
        return batchRepo.save(batch);
    }

    @Transactional(readOnly = true)
    public ProviderInputBatch requireBatch(Long id) {
        return batchRepo.findById(id)
                .orElseThrow(() -> ApiException.notFound("提供方输入批次不存在: " + id));
    }

    @Transactional(readOnly = true)
    public List<LinkBatchEntry> batchEntries(ProviderInputBatch batch) {
        if (!"SEALED".equals(batch.getStatus())) {
            throw ApiException.gone("提供方输入批次 " + batch.getCode()
                    + " 状态为 " + batch.getStatus() + "，逐例条目不可用（证据不完整）");
        }
        try {
            return mapper.readValue(batch.getEntriesJson(), new TypeReference<List<LinkBatchEntry>>() {});
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Transactional(readOnly = true)
    public List<ProviderInputBatch> listBatches() {
        return batchRepo.findAll();
    }

    // ---------------- 谱系账 ----------------

    @Transactional
    public void lineage(Long linkVersionId, Long spaceId, String eventType, String kid,
                        String ruleLabel, String digestRoot, Map<String, Object> detail) {
        TokenLineageEvent e = new TokenLineageEvent();
        e.setLinkVersionId(linkVersionId);
        e.setSpaceId(spaceId);
        e.setEventType(eventType);
        e.setKeyGenerationKid(kid);
        e.setTokenRuleLabel(ruleLabel);
        e.setMemberDigestRoot(digestRoot);
        e.setDetailJson(CanonicalJson.write(mapper, detail == null ? Map.of() : detail));
        e.setActor(SecurityUtils.currentUser());
        lineageRepo.save(e);
    }

    @Transactional(readOnly = true)
    public List<TokenLineageEvent> lineageFor(Long linkVersionId) {
        return lineageRepo.findByLinkVersionIdOrderByIdAsc(linkVersionId);
    }

    @Transactional(readOnly = true)
    public List<TokenLineageEvent> lineageAll() {
        return lineageRepo.findAllByOrderByIdDesc();
    }

    private String randomTokenSuffix() {
        byte[] b = new byte[6];
        random.nextBytes(b);
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02x", x));
        return sb.toString();
    }
}
