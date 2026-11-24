package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 令牌谱系账：每次链接版本封存、密钥轮换、受控重建、一致性核对、证据缺失、到期签发都追加一条。
 * 只追加不改写；audit_event 负责通用留痕，本表专门记录令牌代际与成员摘要根的可追溯谱系。
 */
@Entity
@Table(name = "token_lineage_event", indexes = {
        @Index(name = "idx_lineage_version", columnList = "link_version_id")
})
public class TokenLineageEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "link_version_id")
    private Long linkVersionId;

    @Column(name = "space_id")
    private Long spaceId;

    /** SEAL / KEY_ROTATE / REBUILD / VERIFY / EVIDENCE_GAP / EXPIRE / PURGE */
    @Column(name = "event_type", nullable = false, length = 32)
    private String eventType;

    @Column(name = "key_generation_kid", length = 64)
    private String keyGenerationKid;

    @Column(name = "token_rule_label", length = 64)
    private String tokenRuleLabel;

    @Column(name = "member_digest_root", length = 64)
    private String memberDigestRoot;

    @Column(name = "detail_json", nullable = false, columnDefinition = "text")
    private String detailJson;

    @Column(name = "actor", nullable = false)
    private String actor;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Long getLinkVersionId() { return linkVersionId; }
    public void setLinkVersionId(Long v) { this.linkVersionId = v; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long v) { this.spaceId = v; }
    public String getEventType() { return eventType; }
    public void setEventType(String v) { this.eventType = v; }
    public String getKeyGenerationKid() { return keyGenerationKid; }
    public void setKeyGenerationKid(String v) { this.keyGenerationKid = v; }
    public String getTokenRuleLabel() { return tokenRuleLabel; }
    public void setTokenRuleLabel(String v) { this.tokenRuleLabel = v; }
    public String getMemberDigestRoot() { return memberDigestRoot; }
    public void setMemberDigestRoot(String v) { this.memberDigestRoot = v; }
    public String getDetailJson() { return detailJson; }
    public void setDetailJson(String v) { this.detailJson = v; }
    public String getActor() { return actor; }
    public void setActor(String v) { this.actor = v; }
    public long getCreatedAt() { return createdAt; }
}
