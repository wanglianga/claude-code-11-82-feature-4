package com.sandbox.imaging.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sandbox.imaging.common.CanonicalJson;
import com.sandbox.imaging.domain.TokenRuleVersion;
import org.springframework.stereotype.Service;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Locale;

/**
 * 令牌化与托管封条密码学原语。
 *
 * 关键约束：
 * - 令牌只在相同用途作用域内可关联（scope 参与 HMAC 输入），跨用途不可关联。
 * - 令牌生成必须使用“封存时的规则版本 + 封存时的密钥代际”，与当前默认规则/新密钥无关。
 * - 托管封条使用 AES-GCM，密钥由封存代际的托管密钥材料派生；到期销毁密文、保留摘要。
 */
@Service
public class LinkCryptoService {

    private final ObjectMapper mapper;
    private final SecureRandom random = new SecureRandom();

    public LinkCryptoService(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    /** 生成 32 字节随机密钥材料（Base64） */
    public String newKeyMaterial() {
        byte[] key = new byte[32];
        random.nextBytes(key);
        return Base64.getEncoder().encodeToString(key);
    }

    public String keyFingerprint(String keyMaterial) {
        return sha256(keyMaterial).substring(0, 16);
    }

    /** 按规则版本生成令牌：prefix + HMAC(key, scope|studyRef) 截断 */
    public String token(String purposeScope, String studyRef, TokenRuleVersion rule, String keyMaterial) {
        if (rule == null) throw new IllegalStateException("令牌化规则版本缺失");
        if (keyMaterial == null) throw new IllegalStateException("密钥代际材料缺失");
        boolean caseFold = true;
        String separator = "|";
        try {
            JsonNode node = mapper.readTree(rule.getRuleJson());
            caseFold = node.path("caseFold").asBoolean(true);
            separator = node.path("separator").asText("|");
        } catch (Exception ignored) {
        }
        String ref = caseFold ? studyRef.toLowerCase(Locale.ROOT) : studyRef;
        String input = purposeScope + separator + ref;
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(Base64.getDecoder().decode(keyMaterial), "HmacSHA256"));
            String hex = hex(mac.doFinal(input.getBytes(StandardCharsets.UTF_8)));
            int len = Math.max(8, Math.min(rule.getTruncation(), hex.length()));
            return rule.getPrefix() + hex.substring(0, len);
        } catch (Exception e) {
            throw new IllegalStateException("令牌生成失败", e);
        }
    }

    /** 中心侧患者的研究伪标识：与提供方批次中的 studyRef 属于同一联调命名空间 */
    public String studyRefOf(String patientKey) {
        return "ST-" + patientKey;
    }

    /** 逐成员摘要：sha256(sealSalt | token | patientKey)，封存盐只存在于封存包与托管面 */
    public String memberDigest(String sealSalt, String token, String patientKey) {
        return sha256(sealSalt + "|" + token + "|" + patientKey);
    }

    /** 成员摘要根：对排序后的 token|digest 行求 sha256，逐项核对失败即根不一致 */
    public String digestRoot(List<String> tokenDigestLines) {
        List<String> sorted = tokenDigestLines.stream().sorted().toList();
        return sha256(String.join("\n", sorted));
    }

    public String newSealSalt() {
        byte[] salt = new byte[16];
        random.nextBytes(salt);
        return Base64.getEncoder().encodeToString(salt);
    }

    /** 托管封条加密：返回 Base64(IV || GCM(密文))；密钥 = sha256(keyMaterial) 截断 32B */
    public String escrowSeal(String keyMaterial, String plaintext) {
        try {
            byte[] key = sha256Bytes(keyMaterial + "|ESCROW");
            byte[] iv = new byte[12];
            random.nextBytes(iv);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"), new GCMParameterSpec(128, iv));
            byte[] ct = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = new byte[iv.length + ct.length];
            System.arraycopy(iv, 0, out, 0, iv.length);
            System.arraycopy(ct, 0, out, iv.length, ct.length);
            return Base64.getEncoder().encodeToString(out);
        } catch (Exception e) {
            throw new IllegalStateException("托管封条加密失败", e);
        }
    }

    /** 托管封条解密：仅重建服务可用，且仅在复现期限内存在密文 */
    public String escrowOpen(String keyMaterial, String sealedPayload) {
        try {
            byte[] all = Base64.getDecoder().decode(sealedPayload);
            byte[] iv = new byte[12];
            System.arraycopy(all, 0, iv, 0, 12);
            byte[] ct = new byte[all.length - 12];
            System.arraycopy(all, 12, ct, 0, ct.length);
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,
                    new SecretKeySpec(sha256Bytes(keyMaterial + "|ESCROW"), "AES"),
                    new GCMParameterSpec(128, iv));
            return new String(cipher.doFinal(ct), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new IllegalStateException("托管封条解密失败（密钥代际或密文不匹配）", e);
        }
    }

    public String sha256(String text) {
        return CanonicalJson.sha256(text);
    }

    private byte[] sha256Bytes(String text) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private String hex(byte[] bytes) {
        StringBuilder sb = new StringBuilder();
        for (byte b : bytes) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
