package com.sandbox.imaging.common;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Iterator;
import java.util.Map;
import java.util.TreeMap;

/** 确定性 JSON 工具：按键排序、统一序列化，保证哈希可复现 */
public final class CanonicalJson {

    private CanonicalJson() {}

    /** 深度排序对象键后紧凑序列化 */
    public static String write(ObjectMapper mapper, Object value) {
        try {
            JsonNode node = mapper.valueToTree(value);
            JsonNode sorted = sort(node);
            return mapper.writeValueAsString(sorted);
        } catch (Exception e) {
            throw new IllegalStateException("canonical json failed", e);
        }
    }

    public static JsonNode sort(JsonNode node) {
        if (node == null || node.isNull()) return JsonNodeFactory.instance.nullNode();
        if (node.isObject()) {
            TreeMap<String, JsonNode> map = new TreeMap<>();
            Iterator<Map.Entry<String, JsonNode>> it = node.fields();
            while (it.hasNext()) {
                Map.Entry<String, JsonNode> e = it.next();
                map.put(e.getKey(), sort(e.getValue()));
            }
            return JsonNodeFactory.instance.objectNode().setAll(map);
        }
        if (node.isArray()) {
            var arr = JsonNodeFactory.instance.arrayNode();
            for (JsonNode child : node) arr.add(sort(child));
            return arr;
        }
        return node;
    }

    public static String sha256(String text) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : digest) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("sha256 failed", e);
        }
    }
}
