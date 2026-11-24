package com.sandbox.imaging.service;

import com.sandbox.imaging.domain.TokenRuleVersion;
import com.sandbox.imaging.dto.LinkBatchEntry;
import org.springframework.stereotype.Service;

import java.util.*;

/**
 * 受控匹配引擎（纯计算，不落库）。
 *
 * 输入两侧都用“封存时的令牌化规则 + 封存时的密钥代际 + 同一用途作用域”生成令牌：
 * - 中心侧：研究伪标识 studyRef 由脱敏患者键派生；
 * - 提供方侧：批次行的 studyRef。
 * 仅当令牌相等且候选阻断规则（院区映射、性别、年龄带、单令牌候选上限）全部通过时才建立成员链接。
 *
 * 该引擎不依赖当前默认规则或新密钥；调用方负责传入封存包记录的旧规则/旧密钥，
 * 从而保证密钥轮换后仍能重建同一批成员。
 */
@Service
public class LinkMatchingEngine {

    private final LinkCryptoService crypto;

    public LinkMatchingEngine(LinkCryptoService crypto) {
        this.crypto = crypto;
    }

    public record CenterMember(String patientKey, String hospital, String sex, String ageBand) {}

    public record LinkedMember(String patientKey, String token, String providerLocalRef) {}

    public record MatchResult(List<LinkedMember> members,
                              List<Map<String, Object>> memberDigests,
                              String digestRoot,
                              Map<String, Object> summary,
                              int blockedTokens,
                              List<String> blockedTokenRefs) {}

    public MatchResult match(String purposeScope, TokenRuleVersion rule, String keyMaterial,
                             String sealSalt, List<CenterMember> centerMembers,
                             List<LinkBatchEntry> providerEntries, CandidateBlockingRule blocking) {
        Map<String, List<LinkBatchEntry>> providerByToken = new HashMap<>();
        for (LinkBatchEntry e : providerEntries) {
            if (!passesPrefix(blocking, e.studyRef())) continue;
            String token = crypto.token(purposeScope, e.studyRef(), rule, keyMaterial);
            providerByToken.computeIfAbsent(token, k -> new ArrayList<>()).add(e);
        }

        List<LinkedMember> matched = new ArrayList<>();
        List<String> blockedRefs = new ArrayList<>();
        Set<String> seenCenterTokens = new HashSet<>();

        for (CenterMember cm : centerMembers) {
            String studyRef = crypto.studyRefOf(cm.patientKey());
            String token = crypto.token(purposeScope, studyRef, rule, keyMaterial);
            if (!seenCenterTokens.add(token)) {
                // 同一令牌映射多个中心患者：无法唯一链接，作为阻断令牌
                blockedRefs.add(token);
                continue;
            }
            List<LinkBatchEntry> candidates = providerByToken.getOrDefault(token, List.of());
            if (candidates.isEmpty()) continue;

            List<LinkBatchEntry> viable = new ArrayList<>();
            for (LinkBatchEntry c : candidates) {
                if (blocking.requireHospitalMatch()
                        && !Objects.equals(blocking.mappedHospital(c.hospital()), cm.hospital())) continue;
                if (blocking.requireSexMatch()
                        && c.sex() != null && cm.sex() != null && !c.sex().equals(cm.sex())) continue;
                if (blocking.requireAgeBandMatch()
                        && c.ageBand() != null && cm.ageBand() != null
                        && !c.ageBand().equals(cm.ageBand())) continue;
                viable.add(c);
            }
            if (viable.size() > blocking.maxCandidatesPerToken()) {
                blockedRefs.add(token);
                continue;
            }
            if (viable.size() == 1) {
                matched.add(new LinkedMember(cm.patientKey(), token, viable.get(0).localRef()));
            }
        }

        matched.sort(Comparator.comparing(LinkedMember::token));
        List<Map<String, Object>> digestRows = new ArrayList<>();
        List<String> digestLines = new ArrayList<>();
        TreeMap<String, Integer> hospitalDist = new TreeMap<>();
        Map<String, String> patientHospital = new HashMap<>();
        for (CenterMember cm : centerMembers) patientHospital.put(cm.patientKey(), cm.hospital());
        for (LinkedMember m : matched) {
            String digest = crypto.memberDigest(sealSalt, m.token(), m.patientKey());
            Map<String, Object> row = new TreeMap<>();
            row.put("token", m.token());
            row.put("digest", digest);
            digestRows.add(row);
            digestLines.add(m.token() + "|" + digest);
            String h = patientHospital.getOrDefault(m.patientKey(), "未知");
            hospitalDist.merge(h, 1, Integer::sum);
        }
        String root = crypto.digestRoot(digestLines);

        Map<String, Object> summary = new TreeMap<>();
        summary.put("memberCount", matched.size());
        summary.put("digestRoot", root);
        summary.put("purposeScope", purposeScope);
        summary.put("tokenRuleLabel", rule.getLabel());
        summary.put("hospitalDistribution", hospitalDist);
        summary.put("blockedTokens", blockedRefs.size());

        return new MatchResult(matched, digestRows, root, summary, blockedRefs.size(), blockedRefs);
    }

    private boolean passesPrefix(CandidateBlockingRule blocking, String studyRef) {
        if (blocking.requiredStudyPrefixes() == null || blocking.requiredStudyPrefixes().isEmpty()) {
            return true;
        }
        for (String p : blocking.requiredStudyPrefixes()) {
            if (studyRef != null && studyRef.startsWith(p)) return true;
        }
        return false;
    }
}
