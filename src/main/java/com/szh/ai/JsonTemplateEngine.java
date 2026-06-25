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
}
