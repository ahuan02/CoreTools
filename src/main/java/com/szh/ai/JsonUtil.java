package com.szh.ai;

import com.alibaba.fastjson2.JSON;
import com.alibaba.fastjson2.JSONWriter;

import java.util.List;
import java.util.Map;

/**
 * JSON 工具类 — 基于 fastjson2 实现，高性能解析/序列化。
 * <p>
 * 公开 API 保持向前兼容，原有调用方零改动。
 * <ul>
 *   <li>解析 {@code JSONObject → LinkedHashMap}、{@code JSONArray → ArrayList}</li>
 *   <li>路径取值 {@code getByPath(root, "data[0].url")} 手工实现（避免 JSONPath 语法差异）</li>
 *   <li>缩进输出 {@code toPrettyJson(obj)} 统一走 fastjson2</li>
 * </ul>
 */
public class JsonUtil {

    // ==================== 解析 ====================

    /** 解析 JSON 字符串为 Map（顶层对象）。返回 LinkedHashMap 子类 {@code JSONObject}。 */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> parseObject(String json) {
        return (Map<String, Object>) JSON.parse(json);
    }

    /** 解析 JSON 数组为 List。返回 ArrayList 子类 {@code JSONArray}。 */
    @SuppressWarnings("unchecked")
    public static List<Object> parseArray(String json) {
        return (List<Object>) JSON.parse(json);
    }

    /** 按 "." / "[n]" 路径从 Map 中取值，如 "data[0].url" */
    @SuppressWarnings("unchecked")
    public static Object getByPath(Map<String, Object> root, String path) {
        if (path == null || path.isEmpty()) return null;
        Object current = root;
        for (String seg : path.split("\\.")) {
            if (current == null) return null;
            int bracket = seg.indexOf('[');
            String key = bracket >= 0 ? seg.substring(0, bracket) : seg;
            if (!key.isEmpty() && current instanceof Map) {
                current = ((Map<String, Object>) current).get(key);
            }
            // 处理 [n] 索引
            while (bracket >= 0 && current instanceof List) {
                int end = seg.indexOf(']', bracket);
                if (end < 0) break;
                int idx = Integer.parseInt(seg.substring(bracket + 1, end));
                List<?> list = (List<?>) current;
                current = idx < list.size() ? list.get(idx) : null;
                bracket = seg.indexOf('[', end + 1);
            }
        }
        return current;
    }

    // ==================== 格式化 ====================

    /** 将对象序列化为带缩进的 JSON 字符串 */
    public static String toPrettyJson(Object obj) {
        if (obj == null) return "null";
        try {
            return JSON.toJSONString(obj, JSONWriter.Feature.PrettyFormat);
        } catch (Exception e) {
            // 极端情况退路
            return String.valueOf(obj);
        }
    }
}
