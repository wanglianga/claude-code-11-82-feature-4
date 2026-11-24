package com.sandbox.imaging.domain;

import jakarta.persistence.*;

/**
 * 令牌化规则版本：算法、截断长度、前缀与规则参数。封存包绑定规则版本，
 * 密钥轮换后受控重建必须以该旧版本重新生成令牌，而不是当前默认规则。
 */
@Entity
@Table(name = "token_rule_version")
public class TokenRuleVersion {
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true, length = 64)
    private String label;

    @Column(nullable = false, length = 64)
    private String algorithm = "HMAC-SHA256";

    @Column(nullable = false)
    private int truncation = 24;

    @Column(nullable = false, length = 16)
    private String prefix = "tk_";

    /** 规则说明 JSON（参与量：用途作用域、分隔符、大小写处理等） */
    @Column(name = "rule_json", nullable = false, columnDefinition = "text")
    private String ruleJson;

    @Column(name = "created_at", nullable = false)
    private long createdAt = System.currentTimeMillis();

    public TokenRuleVersion() {}

    public TokenRuleVersion(String label, int truncation, String prefix, String ruleJson) {
        this.label = label;
        this.truncation = truncation;
        this.prefix = prefix;
        this.ruleJson = ruleJson;
    }

    public Long getId() { return id; }
    public String getLabel() { return label; }
    public String getAlgorithm() { return algorithm; }
    public int getTruncation() { return truncation; }
    public String getPrefix() { return prefix; }
    public String getRuleJson() { return ruleJson; }
    public long getCreatedAt() { return createdAt; }
}
