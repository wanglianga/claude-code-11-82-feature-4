package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 披露预算账本：按研究空间 + 季度累计风险点。
 * 预算只随受保护单元可行整数集合的窄化而消耗，不因查询文本或版本号变化而重置；
 * 新版本在同一空间同一季度继续使用同一账本。
 */
@Entity
@Table(name = "budget_ledger", uniqueConstraints =
        @UniqueConstraint(columnNames = {"space_id", "quarter"}))
public class BudgetLedger {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "space_id", nullable = false)
    private Long spaceId;

    /** 季度键，例如 2026Q4 */
    @Column(nullable = false, length = 8)
    private String quarter;

    @Column(name = "risk_cap", nullable = false)
    private int riskCap;

    @Column(name = "points_spent", nullable = false)
    private int pointsSpent;

    @Column(name = "updated_at", nullable = false)
    private long updatedAt = System.currentTimeMillis();

    public Long getId() { return id; }
    public Long getSpaceId() { return spaceId; }
    public void setSpaceId(Long v) { this.spaceId = v; }
    public String getQuarter() { return quarter; }
    public void setQuarter(String v) { this.quarter = v; }
    public int getRiskCap() { return riskCap; }
    public void setRiskCap(int v) { this.riskCap = v; }
    public int getPointsSpent() { return pointsSpent; }
    public void setPointsSpent(int v) { this.pointsSpent = v; }
    public long getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(long v) { this.updatedAt = v; }
}
