package com.sandbox.imaging.dto;

import java.util.List;
import java.util.Map;

/** 批准请求规格（管理员录入，编译器校验并规范化） */
public class ApprovalSpec {
    public String center;
    public String purpose;
    /** 授权字段：人口学维度 + 结构化特征字段，白名单 */
    public List<String> fields;
    /** 允许的队列分组维度 */
    public List<String> dimensions;
    public long enrollmentFrom;
    public long enrollmentTo;
    /** 允许的队列定义过滤条件：字段 -> 允许取值集合 */
    public Map<String, List<String>> allowedFilters;
    public List<String> modalities;
    public String modelUsage;
    /** 公开总数（攻击者先验知识），例如 [{"dimensions":[],"value":15}] */
    public List<PublicTotal> publicTotals;
    public long validFrom;
    public long validTo;

    public static class PublicTotal {
        public Map<String, String> dimensions;
        public int value;
    }
}
