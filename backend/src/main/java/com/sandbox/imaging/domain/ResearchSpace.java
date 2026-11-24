package com.sandbox.imaging.domain;

import jakarta.persistence.*;

@Entity
@Table(name = "research_space")
public class ResearchSpace {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String code;

    @Column(nullable = false)
    private String name;

    /** 最小发布单元：任何输出单元计数低于该值即抑制 */
    @Column(name = "min_publish_unit", nullable = false)
    private int minPublishUnit = 5;

    /** 每季度风险点上限 */
    @Column(name = "quarterly_budget", nullable = false)
    private int quarterlyBudget = 10;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public ResearchSpace() {}

    public ResearchSpace(String code, String name) {
        this.code = code;
        this.name = name;
    }

    public Long getId() { return id; }
    public String getCode() { return code; }
    public String getName() { return name; }
    public int getMinPublishUnit() { return minPublishUnit; }
    public void setMinPublishUnit(int v) { this.minPublishUnit = v; }
    public int getQuarterlyBudget() { return quarterlyBudget; }
    public void setQuarterlyBudget(int v) { this.quarterlyBudget = v; }
    public long getCreatedAt() { return createdAt; }
}
