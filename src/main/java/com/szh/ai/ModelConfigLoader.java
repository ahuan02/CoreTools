package com.szh.ai;

import com.szh.entity.ModelConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从 JSON 文件加载 AI 模型配置。
 * <p>
 * 加载顺序：
 * <ol>
 *   <li>classpath 中的 models.json（默认内置模型）</li>
 *   <li>运行目录下的 user-models.json（用户自定义，覆盖/追加）</li>
 * </ol>
 */
public class ModelConfigLoader {

    /** classpath 中的默认模型配置文件 */
    private static final String DEFAULT_MODELS_JSON = "models.json";
    /** 运行目录下的用户自定义模型配置文件 */
    private static final String USER_MODELS_JSON = "user-models.json";

    /** 加载全部模型（内置 + 用户自定义），用户定义的同别名模型覆盖内置 */
    @SuppressWarnings("unchecked")
    public static List<ModelConfig> loadAll() {
        Map<String, ModelConfig> map = new LinkedHashMap<>();

        // 1. 加载内置模型
        List<ModelConfig> builtin = loadFromClasspath();
        for (ModelConfig mc : builtin) {
            map.put(mc.getAlias(), mc);
        }

        // 2. 用户自定义覆盖
        List<ModelConfig> user = loadFromFile(new File(USER_MODELS_JSON));
        for (ModelConfig mc : user) {
            map.put(mc.getAlias(), mc); // 同别名覆盖
        }

        return new ArrayList<>(map.values());
    }

    /** 仅加载用户自定义模型 */
    @SuppressWarnings("unchecked")
    public static List<ModelConfig> loadUser() {
        return loadFromFile(new File(USER_MODELS_JSON));
    }

    /** 保存用户自定义模型到 user-models.json */
    public static void saveUser(List<ModelConfig> models) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ModelConfig mc : models) {
            list.add(toMap(mc));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("models", list);

        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(USER_MODELS_JSON), StandardCharsets.UTF_8)) {
            w.write(JsonUtil.toPrettyJson(root));
        } catch (IOException ignored) {
            System.err.println("[ModelConfigLoader] 保存 user-models.json 失败");
        }
    }

    // ==================== 内部 ====================

    @SuppressWarnings("unchecked")
    private static List<ModelConfig> loadFromClasspath() {
        try (InputStream in = ModelConfigLoader.class.getClassLoader()
                .getResourceAsStream(DEFAULT_MODELS_JSON)) {
            if (in == null) return List.of();
            String json = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            Map<String, Object> root = JsonUtil.parseObject(json);
            List<Object> arr = (List<Object>) root.get("models");
            if (arr == null) return List.of();
            List<ModelConfig> list = new ArrayList<>();
            for (Object obj : arr) {
                if (obj instanceof Map) list.add(fromMap((Map<String, Object>) obj));
            }
            return list;
        } catch (IOException e) {
            System.err.println("[ModelConfigLoader] 加载内置模型失败: " + e.getMessage());
            return List.of();
        }
    }

    @SuppressWarnings("unchecked")
    private static List<ModelConfig> loadFromFile(File file) {
        if (!file.exists()) return List.of();
        try (Reader r = new InputStreamReader(new FileInputStream(file), StandardCharsets.UTF_8)) {
            StringBuilder sb = new StringBuilder();
            char[] buf = new char[4096];
            int n;
            while ((n = r.read(buf)) > 0) sb.append(buf, 0, n);
            Map<String, Object> root = JsonUtil.parseObject(sb.toString());
            List<Object> arr = (List<Object>) root.get("models");
            if (arr == null) return List.of();
            List<ModelConfig> list = new ArrayList<>();
            for (Object obj : arr) {
                if (obj instanceof Map) list.add(fromMap((Map<String, Object>) obj));
            }
            return list;
        } catch (IOException e) {
            System.err.println("[ModelConfigLoader] 加载用户模型失败: " + e.getMessage());
            return List.of();
        }
    }

    // ==================== Map ↔ ModelConfig 转换 ====================

    @SuppressWarnings("unchecked")
    public static ModelConfig fromMap(Map<String, Object> map) {
        ModelConfig mc = new ModelConfig();
        mc.setAlias(str(map, "alias", ""));
        mc.setApiKey(str(map, "apiKey", ""));
        // baseUrl 不包含 endpoint
        mc.setApiUrl(str(map, "baseUrl", ""));
        mc.setModelName(str(map, "modelName", ""));
        mc.setType(str(map, "type", "chat"));
        mc.setEndpoint(str(map, "endpoint", ""));

        // request → bodyTemplate + headers + bodyType
        Object reqObj = map.get("request");
        if (reqObj instanceof Map reqMap) {
            Object bodyTemplate = reqMap.get("bodyTemplate");
            if (bodyTemplate instanceof Map) {
                mc.setRequestTemplate((Map<String, Object>) bodyTemplate);
            }
            // 自定义请求头
            Object headersObj = reqMap.get("headers");
            if (headersObj instanceof Map headersMap) {
                Map<String, String> headers = new LinkedHashMap<>();
                for (Map.Entry<?, ?> e : ((Map<?, ?>) headersMap).entrySet()) {
                    headers.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
                }
                mc.setRequestHeaders(headers);
            }
            // bodyType: json / multipart
            String bt = str(reqMap, "bodyType", null);
            if (bt != null) mc.setBodyType(bt);
        }

        // response → resultPath / poll
        Object respObj = map.get("response");
        if (respObj instanceof Map respMap) {
            String resultPath = str(respMap, "resultPath", null);
            Object poll = respMap.get("poll");
            // 用 LinkedHashMap 保存 response 配置
            Map<String, Object> respMapping = new LinkedHashMap<>();
            if (resultPath != null) respMapping.put("resultPath", resultPath);
            if (poll instanceof Map) respMapping.putAll((Map<String, Object>) poll);
            if (!respMapping.isEmpty()) mc.setResponseMapping(respMapping);

            // taskId 提取路径
            String taskIdPath = str(respMap, "taskIdPath", null);
            if (taskIdPath != null) mc.setTaskIdPath(taskIdPath);
        }

        // extraConfig（兼容旧字段）
        Object extraObj = map.get("extraConfig");
        if (extraObj instanceof Map extraMap) {
            for (Map.Entry<?, ?> e : ((Map<?, ?>) extraMap).entrySet()) {
                mc.putExtra(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }

        // 顶级其他字段也放入 extraConfig
        for (Map.Entry<String, Object> e : map.entrySet()) {
            String k = e.getKey();
            if ("alias".equals(k) || "apiKey".equals(k) || "baseUrl".equals(k)
                    || "modelName".equals(k) || "type".equals(k) || "endpoint".equals(k)
                    || "request".equals(k) || "response".equals(k) || "extraConfig".equals(k)
                    || "taskIdPath".equals(k)) {
                continue;
            }
            Object v = e.getValue();
            if (v instanceof String) mc.putExtra(k, (String) v);
        }

        return mc;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> toMap(ModelConfig mc) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("alias", mc.getAlias());
        map.put("modelName", mc.getModelName());
        map.put("baseUrl", mc.getApiUrl());
        if (mc.getApiKey() != null && !mc.getApiKey().isEmpty())
            map.put("apiKey", mc.getApiKey());
        else
            map.put("apiKey", "");
        map.put("type", mc.getType() != null ? mc.getType() : "chat");
        if (mc.getEndpoint() != null && !mc.getEndpoint().isEmpty())
            map.put("endpoint", mc.getEndpoint());

        // request
        if (mc.getRequestTemplate() != null && !mc.getRequestTemplate().isEmpty()) {
            Map<String, Object> req = new LinkedHashMap<>();
            req.put("bodyTemplate", mc.getRequestTemplate());
            // 自定义请求头
            if (mc.getRequestHeaders() != null && !mc.getRequestHeaders().isEmpty()) {
                req.put("headers", new LinkedHashMap<>(mc.getRequestHeaders()));
            }
            // bodyType
            if (mc.getBodyType() != null && !mc.getBodyType().isEmpty() && !"json".equals(mc.getBodyType())) {
                req.put("bodyType", mc.getBodyType());
            }
            map.put("request", req);
        }

        // response
        Map<String, Object> respMapping = mc.getResponseMapping();
        boolean hasResp = (respMapping != null && !respMapping.isEmpty()) || (mc.getTaskIdPath() != null);
        if (hasResp) {
            Map<String, Object> resp = new LinkedHashMap<>(respMapping != null ? respMapping : Map.of());
            if (mc.getTaskIdPath() != null) resp.put("taskIdPath", mc.getTaskIdPath());
            map.put("response", resp);
        }

        // extraConfig
        if (mc.getExtraConfig() != null && !mc.getExtraConfig().isEmpty()) {
            map.put("extraConfig", new LinkedHashMap<>(mc.getExtraConfig()));
        }

        return map;
    }

    // ==================== KMS (Known Model Names) ====================

    /** 从模型列表中提取已知模型名称 */
    public static List<String> extractModelNames(List<ModelConfig> models) {
        List<String> names = new ArrayList<>();
        for (ModelConfig mc : models) {
            if (mc.getModelName() != null && !mc.getModelName().isEmpty()) {
                names.add(mc.getModelName());
            }
        }
        return names;
    }

    // ==================== 辅助 ====================

    private static String str(Map<String, Object> m, String key, String def) {
        Object v = m.get(key);
        return v != null ? String.valueOf(v) : def;
    }
}
