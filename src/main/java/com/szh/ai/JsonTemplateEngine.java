package com.szh.ai;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JSON 模板占位符替换引擎。
 * <p>
 * 支持在 JSON 模板中使用 {{变量名}} 占位符，引擎会递归遍历模板，
 * 将所有匹配到的占位符替换为 context 中的实际值。
 * <p>
 * 常见内置变量：{{apiKey}}、{{modelName}}、{{prompt}}、{{image}}、{{params}}
 */
public class JsonTemplateEngine {

    /**
     * 应用模板：遍历模板 Map，将字符串值中的 {{var}} 替换为 context 中的值。
     * <p>
     * 特殊规则：
     * <ul>
     *   <li>如果整个字符串值恰好是 {{var}}，则替换为 context 中对应的原始类型值
     *       （如 number、boolean），而不是字符串</li>
     *   <li>否则做普通字符串占位符替换</li>
     * </ul>
     */
    public static Map<String, Object> apply(Map<String, Object> template, Map<String, Object> context) {
        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) process(template, context);
        return result;
    }

    @SuppressWarnings("unchecked")
    private static Object process(Object node, Map<String, Object> context) {
        if (node instanceof Map) {
            Map<String, Object> map = (Map<String, Object>) node;
            Map<String, Object> result = new LinkedHashMap<>();
            for (Map.Entry<String, Object> e : map.entrySet()) {
                String key = e.getKey();
                Object value = e.getValue();
                // key 也支持占位符
                String resolvedKey = resolveString(key, context);
                if (resolvedKey != null) result.put(resolvedKey, process(value, context));
            }
            return result;
        } else if (node instanceof List) {
            List<Object> list = (List<Object>) node;
            List<Object> result = new ArrayList<>();
            for (Object item : list) {
                result.add(process(item, context));
            }
            return result;
        } else if (node instanceof String) {
            String s = (String) node;
            // 整个值是 {{var}}
            if (s.startsWith("{{") && s.endsWith("}}") && s.indexOf("{{", 2) < 0) {
                String varName = s.substring(2, s.length() - 2).trim();
                Object replacement = context.get(varName);
                if (replacement instanceof String str) {
                    // 自动识别数字：context 值全是 String（来自 extraConfig），
                    // 但模板期望的是 JSON number，需转成 Integer 或 Double
                    Object number = tryParseNumber(str);
                    if (number != null) return number;
                }
                return replacement != null ? replacement : "";
            }
            // 部分占位符替换
            return resolveString(s, context);
        } else {
            return node; // number, boolean, null
        }
    }

    /** 替换字符串中的 {{var}} 占位符 */
    static String resolveString(String template, Map<String, Object> context) {
        if (template == null) return "";
        StringBuilder sb = new StringBuilder();
        int i = 0;
        while (i < template.length()) {
            int open = template.indexOf("{{", i);
            if (open < 0) {
                sb.append(template, i, template.length());
                break;
            }
            sb.append(template, i, open);
            int close = template.indexOf("}}", open + 2);
            if (close < 0) {
                sb.append(template, open, template.length());
                break;
            }
            String varName = template.substring(open + 2, close).trim();
            Object value = context.get(varName);
            sb.append(value != null ? value : "");
            i = close + 2;
        }
        return sb.toString();
    }

    /**
     * 尝试将字符串解析为数字（Integer → Long → Double），
     * 解析失败返回 null。
     * <p>
     * 用于解决 extraConfig 全为 String，但 bodyTemplate 中
     * num_frames / frame_rate / width / height 等字段应为 JSON number 的问题。
     */
    private static Number tryParseNumber(String s) {
        if (s == null || s.isEmpty()) return null;
        // 跳过明显不是数字的字符串（含非数字字符，允许负号和小数点）
        boolean hasDigit = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= '0' && c <= '9') { hasDigit = true; continue; }
            if (c == '-' || c == '+' || c == '.' || c == 'e' || c == 'E') continue;
            return null; // 非数字字符
        }
        if (!hasDigit) return null; // 空字符串或纯符号
        try {
            if (s.indexOf('.') >= 0 || s.indexOf('e') >= 0 || s.indexOf('E') >= 0) {
                return Double.parseDouble(s);
            }
            long longVal = Long.parseLong(s);
            if (longVal >= Integer.MIN_VALUE && longVal <= Integer.MAX_VALUE) {
                return (int) longVal;
            }
            return longVal;
        } catch (NumberFormatException ignored) {
            return null;
        }
    }
}
