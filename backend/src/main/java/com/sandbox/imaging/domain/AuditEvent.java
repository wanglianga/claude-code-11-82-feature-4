package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/** 审计事件：对批准、查询、阻断、交付、撤回、复现等全部敏感动作留痕 */
@Entity
@Table(name = "audit_event", indexes = {
        @Index(name = "idx_audit_space", columnList = "space_id"),
        @Index(name = "idx_audit_created", columnList = "created_at")
})
public class AuditEvent {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id")
    private Long spaceId;

    @Column(nullable = false)
    private String actor;

    /** APPROVAL_COMPILE / QUERY_REVIEW / RESULT_BLOCKED / DELIVER_FREEZE /
     *  APPROVAL_REVOKE / REPRODUCTION / ACCESS_DENIED ... */
    @Column(nullable = false)
    private String action;

    @Column(nullable = false, length = 4000)
    private String detail;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public AuditEvent() {}

    public AuditEvent(Long spaceId, String actor, String action, String detail) {
        this.spaceId = spaceId;
        this.actor = actor;
        this.action = action;
        this.detail = detail.length() > 3900 ? detail.substring(0, 3900) : detail;
    }

    public Long getId() { return id; }
    public Long getSpaceId() { return spaceId; }
    public String getActor() { return actor; }
    public String getAction() { return action; }
    public String getDetail() { return detail; }
    public long getCreatedAt() { return createdAt; }
}
