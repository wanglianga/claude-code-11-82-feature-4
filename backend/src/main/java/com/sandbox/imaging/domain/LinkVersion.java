package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 链接版本封存包：某次院外令牌链接分析在发布时冻结的完整复现闭包。
 *
 * 每个已批准链接版本在自己的复现期限内保留：提供方输入批次、令牌化规则版本、候选阻断规则、
 * 用途授权快照、可调用的受控匹配权限与成员摘要（含逐成员摘要）。密钥轮换不影响该封存包：
 * 重建服务只按该版本记录的旧规则与旧授权快照重新生成成员，再与成员摘要逐项核对。
 */
@Entity
@Table(name = "link_version", indexes = {
        @Index(name = "idx_link_version_approval", columnList = "approval_id"),
        @Index(name = "idx_link_version_space", columnList = "space_id")
})
public class LinkVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "approval_id", nullable = false)
    private Long approvalId;

    @Column(name = "approval_code", nullable = false, length = 64)
    private String approvalCode;

    @Column(name = "analysis_version_id")
    private Long analysisVersionId;

    @Column(name = "analysis_version_no")
    private Integer analysisVersionNo;

    @Column(name = "provider_code", nullable = false, length = 64)
    private String providerCode;

    @Column(name = "purpose_authorization_id", nullable = false)
    private Long purposeAuthorizationId;

    @Column(name = "provider_batch_id", nullable = false)
    private Long providerBatchId;

    @Column(name = "token_rule_version_id", nullable = false)
    private Long tokenRuleVersionId;

    /** 封存时的密钥代际 kid（轮换后此代际 RETIRED，但仍可用于受控重建） */
    @Column(name = "key_generation_kid", nullable = false, length = 64)
    private String keyGenerationKid;

    /** 候选阻断规则：把提供方候选行映射到受控分析队列的确定性规则 */
    @Column(name = "candidate_blocking_rule_json", nullable = false, columnDefinition = "text")
    private String candidateBlockingRuleJson;

    /** 用途作用域：令牌只在相同用途作用域内可关联 */
    @Column(name = "purpose_scope", nullable = false, length = 200)
    private String purposeScope;

    /** 受控匹配权限快照：允许的匹配字段、调用方、单次/累计调用上限、托管位置 */
    @Column(name = "match_permission_json", nullable = false, columnDefinition = "text")
    private String matchPermissionJson;

    /** 受控匹配权限已使用次数（每次受控重建 +1，受 matchPermissionJson.maxCalls 约束） */
    @Column(name = "match_calls_used", nullable = false)
    private int matchCallsUsed = 0;

    /** 成员摘要（汇总层：数量、作用域、规则指纹等，不含逐例可识别映射） */
    @Column(name = "member_digest_json", nullable = false, columnDefinition = "text")
    private String memberDigestJson;

    /** 逐成员摘要根哈希：用于逐项核对 */
    @Column(name = "member_digest_root", nullable = false, length = 64)
    private String memberDigestRoot;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    /** ESCROW_SEAL（托管封条重放） / PROVIDER_REBUILD（旧输入+提供方授权重建） */
    @Column(name = "sealing_strategy", nullable = false, length = 32)
    private String sealingStrategy;

    /** 选择路线时记录的取舍依据：提供方可用性、托管风险、重放频率 */
    @Column(name = "strategy_rationale_json", nullable = false, columnDefinition = "text")
    private String strategyRationaleJson;

    /** 封存包完整载荷（确定性 JSON，含所有复现闭包引用与快照副本） */
    @Column(name = "seal_package_json", nullable = false, columnDefinition = "text")
    private String sealPackageJson;

    @Column(name = "seal_package_hash", nullable = false, length = 64)
    private String sealPackageHash;

    @Column(name = "reproduce_window_start", nullable = false)
    private long reproduceWindowStart;

    @Column(name = "reproduce_window_end", nullable = false)
    private long reproduceWindowEnd;

    /** SEALED / REPRODUCED_OK / DIVERGED / EVIDENCE_INCOMPLETE / EXPIRED */
    @Column(nullable = false, length = 32)
    private String status = "SEALED";

    /** 最近一次一致性核对结论说明 */
    @Column(name = "last_verifier_note", length = 2000)
    private String lastVerifierNote;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "expired_at")
    private Long expiredAt;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long v) { this.spaceId = v; }
    public Long getApprovalId() { return approvalId; }
    public void setApprovalId(Long v) { this.approvalId = v; }
    public String getApprovalCode() { return approvalCode; }
    public void setApprovalCode(String v) { this.approvalCode = v; }
    public Long getAnalysisVersionId() { return analysisVersionId; }
    public void setAnalysisVersionId(Long v) { this.analysisVersionId = v; }
    public Integer getAnalysisVersionNo() { return analysisVersionNo; }
    public void setAnalysisVersionNo(Integer v) { this.analysisVersionNo = v; }
    public String getProviderCode() { return providerCode; }
    public void setProviderCode(String v) { this.providerCode = v; }
    public Long getPurposeAuthorizationId() { return purposeAuthorizationId; }
    public void setPurposeAuthorizationId(Long v) { this.purposeAuthorizationId = v; }
    public Long getProviderBatchId() { return providerBatchId; }
    public void setProviderBatchId(Long v) { this.providerBatchId = v; }
    public Long getTokenRuleVersionId() { return tokenRuleVersionId; }
    public void setTokenRuleVersionId(Long v) { this.tokenRuleVersionId = v; }
    public String getKeyGenerationKid() { return keyGenerationKid; }
    public void setKeyGenerationKid(String v) { this.keyGenerationKid = v; }
    public String getCandidateBlockingRuleJson() { return candidateBlockingRuleJson; }
    public void setCandidateBlockingRuleJson(String v) { this.candidateBlockingRuleJson = v; }
    public String getPurposeScope() { return purposeScope; }
    public void setPurposeScope(String v) { this.purposeScope = v; }
    public String getMatchPermissionJson() { return matchPermissionJson; }
    public void setMatchPermissionJson(String v) { this.matchPermissionJson = v; }
    public int getMatchCallsUsed() { return matchCallsUsed; }
    public void setMatchCallsUsed(int v) { this.matchCallsUsed = v; }
    public String getMemberDigestJson() { return memberDigestJson; }
    public void setMemberDigestJson(String v) { this.memberDigestJson = v; }
    public String getMemberDigestRoot() { return memberDigestRoot; }
    public void setMemberDigestRoot(String v) { this.memberDigestRoot = v; }
    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int v) { this.memberCount = v; }
    public String getSealingStrategy() { return sealingStrategy; }
    public void setSealingStrategy(String v) { this.sealingStrategy = v; }
    public String getStrategyRationaleJson() { return strategyRationaleJson; }
    public void setStrategyRationaleJson(String v) { this.strategyRationaleJson = v; }
    public String getSealPackageJson() { return sealPackageJson; }
    public void setSealPackageJson(String v) { this.sealPackageJson = v; }
    public String getSealPackageHash() { return sealPackageHash; }
    public void setSealPackageHash(String v) { this.sealPackageHash = v; }
    public long getReproduceWindowStart() { return reproduceWindowStart; }
    public void setReproduceWindowStart(long v) { this.reproduceWindowStart = v; }
    public long getReproduceWindowEnd() { return reproduceWindowEnd; }
    public void setReproduceWindowEnd(long v) { this.reproduceWindowEnd = v; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public String getLastVerifierNote() { return lastVerifierNote; }
    public void setLastVerifierNote(String v) { this.lastVerifierNote = v; }
    public long getCreatedAt() { return createdAt; }
    public Long getExpiredAt() { return expiredAt; }
    public void setExpiredAt(Long v) { this.expiredAt = v; }
}
