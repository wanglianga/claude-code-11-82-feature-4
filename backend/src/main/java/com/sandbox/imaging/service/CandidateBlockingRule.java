package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * 候选阻断规则：把提供方批次行映射到受控分析队列的确定性规则。
 * 规则本身进入封存包；密钥轮换或时间推移都不能换用新规则重新解释旧成员。
 */
public record CandidateBlockingRule(
        Map<String, String> providerHospitalMap,
        int maxCandidatesPerToken,
        boolean requireHospitalMatch,
        boolean requireSexMatch,
        boolean requireAgeBandMatch,
        List<String> requiredStudyPrefixes,
        String notes) {

    public static CandidateBlockingRule parse(ObjectMapper mapper, String json) {
        try {
            JsonNode n = mapper.readTree(json);
            Map<String, String> map = new TreeMap<>();
            JsonNode hm = n.path("providerHospitalMap");
            if (hm.isObject()) hm.fields().forEachRemaining(e -> map.put(e.getKey(), e.getValue().asText()));
            List<String> prefixes = new ArrayList<>();
            JsonNode ps = n.path("requiredStudyPrefixes");
            if (ps.isArray()) ps.forEach(x -> prefixes.add(x.asText()));
            return new CandidateBlockingRule(
                    map,
                    n.path("maxCandidatesPerToken").asInt(1),
                    n.path("requireHospitalMatch").asBoolean(true),
                    n.path("requireSexMatch").asBoolean(true),
                    n.path("requireAgeBandMatch").asBoolean(true),
                    prefixes,
                    n.path("notes").asText(""));
        } catch (Exception e) {
            throw new IllegalStateException("候选阻断规则解析失败", e);
        }
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("providerHospitalMap", new TreeMap<>(providerHospitalMap));
        m.put("maxCandidatesPerToken", maxCandidatesPerToken);
        m.put("requireHospitalMatch", requireHospitalMatch);
        m.put("requireSexMatch", requireSexMatch);
        m.put("requireAgeBandMatch", requireAgeBandMatch);
        m.put("requiredStudyPrefixes", requiredStudyPrefixes);
        m.put("notes", notes);
        return m;
    }

    public String mappedHospital(String providerHospital) {
        return providerHospitalMap.getOrDefault(providerHospital, providerHospital);
    }

    public static CandidateBlockingRule demoDefault() {
        Map<String, String> map = new TreeMap<>();
        map.put("HOSP-A", "A");
        map.put("HOSP-B", "B");
        return new CandidateBlockingRule(map, 1, true, true, true,
                List.of("ST-"), "按研究伪标识令牌连接；院区/性别/年龄带须一致；同一令牌多患者即阻断");
    }
}
