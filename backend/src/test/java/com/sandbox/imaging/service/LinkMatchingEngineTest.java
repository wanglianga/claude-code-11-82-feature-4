package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.domain.TokenRuleVersion;
import com.sandbox.imaging.dto.LinkBatchEntry;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * 链接封存的密码学与匹配不变量：
 * - 同规则/同密钥/同用途作用域生成确定性令牌；
 * - 不同用途作用域令牌不可关联；
 * - 密钥轮换后旧令牌与新令牌漂移（因此必须按旧密钥代际重建）；
 * - 候选阻断规则（院区映射/性别/年龄带/单令牌候选上限）决定成员；
 * - 托管封条可解密且摘要稳定。
 */
class LinkMatchingEngineTest {

    private final ObjectMapper mapper = new ObjectMapper();
    private final LinkCryptoService crypto = new LinkCryptoService(mapper);
    private final LinkMatchingEngine engine = new LinkMatchingEngine(crypto);

    private TokenRuleVersion rule() throws Exception {
        return new TokenRuleVersion("tk-rules-v1", 24, "tk_",
                mapper.writeValueAsString(Map.of("caseFold", true, "separator", "|")));
    }

    private final String key1 = crypto.newKeyMaterial();
    private final String key2 = crypto.newKeyMaterial();

    @Test
    void tokenIsDeterministicAndScopeBound() throws Exception {
        TokenRuleVersion r = rule();
        String a = crypto.token("LS:scopeA", "ST-P001", r, key1);
        String b = crypto.token("LS:scopeA", "ST-P001", r, key1);
        String otherScope = crypto.token("LS:scopeB", "ST-P001", r, key1);
        String rotated = crypto.token("LS:scopeA", "ST-P001", r, key2);
        assertEquals(a, b, "同规则/密钥/作用域令牌必须确定");
        assertNotEquals(a, otherScope, "跨用途作用域令牌不得关联");
        assertNotEquals(a, rotated, "密钥轮换后令牌必须漂移");
        assertTrue(a.startsWith("tk_"));
    }

    @Test
    void matchingLinksOnlyRuleCompliantCandidates() throws Exception {
        TokenRuleVersion r = rule();
        CandidateBlockingRule blocking = CandidateBlockingRule.demoDefault();
        List<LinkMatchingEngine.CenterMember> center = List.of(
                new LinkMatchingEngine.CenterMember("P001", "A", "F", "18-39"),
                new LinkMatchingEngine.CenterMember("P002", "B", "M", "75+"));
        List<LinkBatchEntry> provider = List.of(
                new LinkBatchEntry("PRV-A-LR-001", "ST-P001", "HOSP-A", "F", "18-39"),
                // 性别不一致：被阻断，不建立链接
                new LinkBatchEntry("PRV-B-LR-002", "ST-P002", "HOSP-B", "F", "75+"));
        LinkMatchingEngine.MatchResult res = engine.match("LS:scope", r, key1, "salt", center, provider, blocking);
        assertEquals(1, res.members().size(), "仅符合阻断规则的候选可链接");
        assertEquals("P001", res.members().get(0).patientKey());
    }

    @Test
    void digestRootIsOrderIndependentButSensitive() throws Exception {
        TokenRuleVersion r = rule();
        CandidateBlockingRule blocking = CandidateBlockingRule.demoDefault();
        List<LinkMatchingEngine.CenterMember> center = List.of(
                new LinkMatchingEngine.CenterMember("P001", "A", "F", "18-39"),
                new LinkMatchingEngine.CenterMember("P003", "A", "F", "18-39"));
        List<LinkBatchEntry> provider = List.of(
                new LinkBatchEntry("LR1", "ST-P001", "HOSP-A", "F", "18-39"),
                new LinkBatchEntry("LR2", "ST-P003", "HOSP-A", "F", "18-39"));
        LinkMatchingEngine.MatchResult m1 = engine.match("LS:s", r, key1, "salt", center, provider, blocking);
        LinkMatchingEngine.MatchResult m2 = engine.match("LS:s", r, key1, "salt",
                List.of(center.get(1), center.get(0)),
                List.of(provider.get(1), provider.get(0)), blocking);
        assertEquals(m1.digestRoot(), m2.digestRoot(), "摘要根对成员顺序不敏感");
        LinkMatchingEngine.MatchResult m3 = engine.match("LS:s", r, key1, "other-salt", center, provider, blocking);
        assertNotEquals(m1.digestRoot(), m3.digestRoot(), "不同封存盐得到不同摘要根");
    }

    @Test
    void escrowSealRoundTripsAndFailsClosedOnWrongKey() {
        String plain = "{\"members\":[{\"token\":\"tk_x\",\"patientKey\":\"P001\"}]}";
        String sealed = crypto.escrowSeal(key1, plain);
        assertEquals(plain, crypto.escrowOpen(key1, sealed));
        assertThrows(IllegalStateException.class, () -> crypto.escrowOpen(key2, sealed),
                "错误密钥解密必须失败（fail-closed）");
    }
}
