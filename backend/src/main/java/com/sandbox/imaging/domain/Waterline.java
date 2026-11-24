package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/** 数据水线：某个时刻的数据快照标识，复现器据此重建当时结果 */
@Entity
@Table(name = "waterline")
public class Waterline {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String label;

    @Column(nullable = false)
    private String description;

    @Column(name = "cutoff_at", nullable = false)
    private long cutoffAt;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public Waterline() {}

    public Waterline(String label, String description, long cutoffAt) {
        this.label = label;
        this.description = description;
        this.cutoffAt = cutoffAt;
    }

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public void setLabel(String label) { this.label = label; }
    public String getDescription() { return description; }
    public long getCutoffAt() { return cutoffAt; }
    public void setCutoffAt(long cutoffAt) { this.cutoffAt = cutoffAt; }
}
