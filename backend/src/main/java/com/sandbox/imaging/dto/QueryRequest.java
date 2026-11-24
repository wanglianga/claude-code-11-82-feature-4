package com.sandbox.imaging.dto;

import java.util.List;
import java.util.Map;

/**
 * 查询请求（分析师提交）。查询解析器将其规范化为 CanonicalQuery：
 * 排序过滤/分组/度量，时间窗与批准入组期间相交，输出抑制与舍入参数显式化。
 */
public class QueryRequest {
    public Long analysisVersionId;
    public Long sessionId;
    /** INTERACTIVE / FIXED_TABLE_SET */
    public String route;
    public List<Filter> filters;
    public List<String> groups;
    public TimeWindow timeWindow;
    public List<String> measures;
    public OutputSpec output;
    /** 仅用于界面展示的备注，不参与规范化哈希（查询文本不同不影响预算身份） */
    public String comment;

    public static class Filter {
        public String field;
        /** eq / in / gte / lte */
        public String op;
        public Object value;
    }

    public static class TimeWindow {
        public Long from;
        public Long to;
        public Integer followUpDays;
    }

    public static class OutputSpec {
        public Integer minUnit;
        public Integer roundingStep;
    }
}
