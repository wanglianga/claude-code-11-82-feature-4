package com.sandbox.imaging.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.TreeMap;

/** 规范化后的查询（确定性结构，哈希即其身份；注释/版本号不入哈希） */
public class CanonicalQuery {
    public String approvalCode;
    public int analysisVersionNo;
    public String waterlineLabel;
    public String algorithmVersion;
    public String route;
    public List<TreeMap<String, Object>> filters = new ArrayList<>();
    public List<String> groups = new ArrayList<>();
    public TreeMap<String, Object> timeWindow = new TreeMap<>();
    public List<String> measures = new ArrayList<>();
    public TreeMap<String, Object> output = new TreeMap<>();
}
