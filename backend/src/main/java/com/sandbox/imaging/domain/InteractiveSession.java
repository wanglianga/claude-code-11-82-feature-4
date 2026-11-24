package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 交互会话：维护会话级语义与约束图（交互路线）。
 * 同一会话及同一研究空间内的全部已披露输出共同参与联合推断，从共同预算扣减。
 */
@Entity
@Table(name = "interactive_session")
public class InteractiveSession {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String code;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    @Column(name = "analysis_version_id", nullable = false)
    private Long analysisVersionId;

    @Column(nullable = false)
    private String owner;

    @Column(nullable = false)
    private String purpose;

    /** OPEN / CLOSED */
    @Column(nullable = false)
    private String status = "OPEN";

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    @Column(name = "closed_at")
    private Long closedAt;

    public Long getId() { return id; }
    public String getCode() { return code; }
    public void setCode(String code) { this.code = code; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long v) { this.spaceId = v; }
    public Long getAnalysisVersionId() { return analysisVersionId; }
    public void setAnalysisVersionId(Long v) { this.analysisVersionId = v; }
    public String getOwner() { return owner; }
    public void setOwner(String owner) { this.owner = owner; }
    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }
    public String getStatus() { return status; }
    public void setStatus(String status) { this.status = status; }
    public long getCreatedAt() { return createdAt; }
    public Long getClosedAt() { return closedAt; }
    public void setClosedAt(Long v) { this.closedAt = v; }
}
