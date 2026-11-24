package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 受控重建运行记录：每次一致性核对都留痕（策略、授权快照、令牌代际、逐项差异摘要、结论）。
 * 结论必须是 REPRODUCED / DIVERGED / EVIDENCE_INCOMPLETE 之一；DIVERGED 必须阻止把结果称为旧实验复现。
 */
@Entity
@Table(name = "link_rebuild_run", indexes = {
        @Index(name = "idx_rebuild_run_version", columnList = "link_version_id")
})
public class LinkRebuildRun {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_version_id", nullable = false)
    private Long linkVersionId;

    /** ESCROW_SEAL / PROVIDER_REBUILD */
    @Column(nullable = false, length = 32)
    private String strategy;

    /** REPRODUCED / DIVERGED / EVIDENCE_INCOMPLETE */
    @Column(nullable = false, length = 32)
    private String verdict;

    @Column(name = "key_generation_kid", length = 64)
    private String keyGenerationKid;

    /**
     * REPLAYED_OLD（按旧规则与旧授权快照重建） / CURRENT_KEY_DRIFT（用轮换后新密钥尝试，仅用于对比证明）
     */
    @Column(name = "rule_basis", nullable = false, length = 32)
    private String ruleBasis = "REPLAYED_OLD";

    @Column(name = "rebuilt_member_count", nullable = false)
    private int rebuiltMemberCount;

    @Column(name = "sealed_member_count", nullable = false)
    private int sealedMemberCount;

    @Column(name = "digest_root_match", nullable = false)
    private boolean digestRootMatch;

    /** 逐项核对差异明细 JSON：缺失/新增/摘要不一致的成员数及其调查线索 */
    @Column(name = "diff_json", nullable = false, columnDefinition = "text")
    private String diffJson;

    @Column(name = "report_json", nullable = false, columnDefinition = "text")
    private String reportJson;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Long getLinkVersionId() { return linkVersionId; }
    public void setLinkVersionId(Long v) { this.linkVersionId = v; }
    public String getStrategy() { return strategy; }
    public void setStrategy(String v) { this.strategy = v; }
    public String getVerdict() { return verdict; }
    public void setVerdict(String v) { this.verdict = v; }
    public String getKeyGenerationKid() { return keyGenerationKid; }
    public void setKeyGenerationKid(String v) { this.keyGenerationKid = v; }
    public String getRuleBasis() { return ruleBasis; }
    public void setRuleBasis(String v) { this.ruleBasis = v; }
    public int getRebuiltMemberCount() { return rebuiltMemberCount; }
    public void setRebuiltMemberCount(int v) { this.rebuiltMemberCount = v; }
    public int getSealedMemberCount() { return sealedMemberCount; }
    public void setSealedMemberCount(int v) { this.sealedMemberCount = v; }
    public boolean isDigestRootMatch() { return digestRootMatch; }
    public void setDigestRootMatch(boolean v) { this.digestRootMatch = v; }
    public String getDiffJson() { return diffJson; }
    public void setDiffJson(String v) { this.diffJson = v; }
    public String getReportJson() { return reportJson; }
    public void setReportJson(String v) { this.reportJson = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public long getCreatedAt() { return createdAt; }
}
