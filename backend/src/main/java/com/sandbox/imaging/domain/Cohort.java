package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 已编译队列：由批准范围与水线确定性生成，记录成员脱敏键集合（哈希）与队列规格。
 * 同一批准+同一水线重复编译必须得到同一 cohort_hash（可复现）。
 */
@Entity
@Table(name = "cohort")
public class Cohort {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "approval_id")
    private Approval approval;

    @ManyToOne(fetch = FetchType.EAGER, optional = false)
    @JoinColumn(name = "waterline_id")
    private Waterline waterline;

    @Column(name = "cohort_hash", nullable = false, length = 64)
    private String cohortHash;

    @Column(name = "cohort_spec_json", nullable = false, length = 10000)
    private String cohortSpecJson;

    @Column(name = "member_count", nullable = false)
    private int memberCount;

    /** 脱敏患者键的有序哈希列表 */
    @Column(name = "member_keys_json", nullable = false, length = 20000)
    private String memberKeysJson;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Approval getApproval() { return approval; }
    public void setApproval(Approval approval) { this.approval = approval; }
    public Waterline getWaterline() { return waterline; }
    public void setWaterline(Waterline waterline) { this.waterline = waterline; }
    public String getCohortHash() { return cohortHash; }
    public void setCohortHash(String cohortHash) { this.cohortHash = cohortHash; }
    public String getCohortSpecJson() { return cohortSpecJson; }
    public void setCohortSpecJson(String v) { this.cohortSpecJson = v; }
    public int getMemberCount() { return memberCount; }
    public void setMemberCount(int memberCount) { this.memberCount = memberCount; }
    public String getMemberKeysJson() { return memberKeysJson; }
    public void setMemberKeysJson(String v) { this.memberKeysJson = v; }
    public long getCreatedAt() { return createdAt; }
}
