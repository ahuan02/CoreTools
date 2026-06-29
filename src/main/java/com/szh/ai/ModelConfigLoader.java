package com.szh.ai;

import com.szh.entity.ModelConfig;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 从项目根目录（src 同级）的 models.json 加载 AI 模型配置。
 * <p>
 * 如果文件不存在，自动生成一份仅含常用聊天模型的基础配置（无 API Key，用户自行填写）。
 */
public class ModelConfigLoader {

    /** 模型配置文件名 */
    private static final String MODELS_JSON = "models.json";

    /** 用户目录下的 models.json（可读写，用户可修改 API Key 等） */
    private static File getRuntimeFile() {
        return new File(System.getProperty("user.dir"), MODELS_JSON);
    }

    /** 加载全部模型：优先用户目录，其次 classpath 内置资源，最后硬编码默认配置 */
    @SuppressWarnings("unchecked")
    public static List<ModelConfig> loadAll() {
        // 1) 优先加载用户目录下的 models.json（用户可能已修改 API Key 等）
        File runtimeFile = getRuntimeFile();
        if (runtimeFile.exists()) {
            return loadFromFile(runtimeFile);
        }

        // 2) 尝试从 classpath resources 加载内置 models.json
        try (InputStream in = ModelConfigLoader.class.getResourceAsStream("/models.json")) {
            if (in != null) {
                StringBuilder sb = new StringBuilder();
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) sb.append(new String(buf, 0, n, StandardCharsets.UTF_8));
                Map<String, Object> root = JsonUtil.parseObject(sb.toString());
                List<Object> arr = (List<Object>) root.get("models");
                if (arr != null) {
                    System.out.println("[ModelConfigLoader] 从内置资源加载 models.json");
                    List<ModelConfig> list = new ArrayList<>();
                    for (Object obj : arr) {
                        if (obj instanceof Map) list.add(fromMap((Map<String, Object>) obj));
                    }
                    // 将内置配置写出到用户目录（方便用户后续修改）
                    saveToRuntimeFile(sb.toString());
                    return list;
                }
            }
        } catch (Exception e) {
            System.err.println("[ModelConfigLoader] 读取内置 models.json 失败: " + e.getMessage());
        }

        // 3) 兜底：硬编码默认配置
        String defaultJson = buildDefaultModelsJson();
        saveToRuntimeFile(defaultJson);
        try {
            Map<String, Object> root = JsonUtil.parseObject(defaultJson);
            List<Object> arr = (List<Object>) root.get("models");
            if (arr == null) return List.of();
            List<ModelConfig> list = new ArrayList<>();
            for (Object obj : arr) {
                if (obj instanceof Map) list.add(fromMap((Map<String, Object>) obj));
            }
            return list;
        } catch (Exception e) {
            System.err.println("[ModelConfigLoader] 解析默认配置失败: " + e.getMessage());
            return List.of();
        }
    }

    /** 将 JSON 字符串写出到用户目录下的 models.json */
    private static void saveToRuntimeFile(String json) {
        File runtimeFile = getRuntimeFile();
        try {
            try (Writer w = new OutputStreamWriter(
                    new FileOutputStream(runtimeFile), StandardCharsets.UTF_8)) {
                w.write(json);
            }
            System.out.println("[ModelConfigLoader] 已写出默认配置: " + runtimeFile.getAbsolutePath());
        } catch (IOException e) {
            System.err.println("[ModelConfigLoader] 创建 " + runtimeFile.getAbsolutePath() + " 失败: " + e.getMessage());
        }
    }

    /** 生成仅含常用聊天模型的基础配置（API Key 为空，用户自行填写） */
    private static String buildDefaultModelsJson() {
        // 注意：bodyTemplate 中 width/height/num_frames/frame_rate 等字段虽然写成字符串格式，
        // 但 JsonTemplateEngine 的 tryParseNumber() 在替换 context 值时会自动转为数字。
        return """
                {
                  "models": [
                    {"alias":"GPT-4o","modelName":"gpt-4o","baseUrl":"https://api.openai.com/v1","apiKey":"","type":"chat"},
                    {"alias":"GPT-4o Mini","modelName":"gpt-4o-mini","baseUrl":"https://api.openai.com/v1","apiKey":"","type":"chat"},
                    {"alias":"DeepSeek Chat","modelName":"deepseek-chat","baseUrl":"https://api.deepseek.com/v1","apiKey":"","type":"chat"},
                    {"alias":"DeepSeek Reasoner","modelName":"deepseek-reasoner","baseUrl":"https://api.deepseek.com/v1","apiKey":"","type":"chat"},
                    {"alias":"DeepSeek V4 Pro","modelName":"deepseek-v4-pro","baseUrl":"https://api.deepseek.com/v1","apiKey":"","type":"chat"},
                    {"alias":"DeepSeek V4 Flash","modelName":"deepseek-v4-flash","baseUrl":"https://api.deepseek.com/v1","apiKey":"","type":"chat"},
                    {"alias":"豆包","modelName":"doubao-lite-128k","baseUrl":"https://ark.cn-beijing.volces.com/api/v3","apiKey":"","type":"chat"},
                    {"alias":"通义千问 Plus","modelName":"qwen-plus","baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","apiKey":"","type":"chat"},
                    {"alias":"通义千问 Max","modelName":"qwen-max","baseUrl":"https://dashscope.aliyuncs.com/compatible-mode/v1","apiKey":"","type":"chat"},
                    {
                      "alias":"Agnes 图片生成",
                      "modelName":"agnes-image-2.0-flash",
                      "baseUrl":"https://apihub.agnes-ai.com",
                      "apiKey":"",
                      "type":"sync",
                      "endpoint":"/v1/images/generations",
                      "request":{"bodyTemplate":{"model":"agnes-image-2.0-flash","prompt":"{{prompt}}","size":"{{size}}","return_base64":true,"extra_body":{"response_format":"{{format}}","image":"{{image}}"}}},
                      "response":{"poll":{"resultPath":"data"}}
                    },
                    {
                      "alias":"Agnes 视频生成",
                      "modelName":"agnes-video-v2.0",
                      "baseUrl":"https://apihub.agnes-ai.com",
                      "apiKey":"",
                      "type":"task",
                      "endpoint":"/v1/videos",
                      "request":{"bodyTemplate":{"model":"agnes-video-v2.0","prompt":"{{prompt}}","width":"{{width}}","height":"{{height}}","num_frames":"{{numFrames}}","frame_rate":"{{frameRate}}","seed":"{{seed}}","extra_body":{"image":"{{images}}","mode":"{{mode}}"}}},
                      "response":{"poll":{"endpoint":"/agnesapi?video_id={{taskId}}","method":"GET","statusPath":"status","successStatus":"completed","failStatus":"failed","resultPath":"video_url","intervalMs":5000,"maxWaitMs":1800000},"taskIdPath":"video_id"}
                    },
                    {
                      "alias":"豆包 Seed3D",
                      "modelName":"doubao-seed3d-2-0-260328",
                      "baseUrl":"https://ark.cn-beijing.volces.com/api/v3",
                      "apiKey":"",
                      "type":"task",
                      "endpoint":"/contents/generations/tasks",
                      "request":{"bodyTemplate":{"model":"doubao-seed3d-2-0-260328","content":[{"type":"text","text":"{{params}}"},{"type":"image_url","image_url":{"url":"{{image}}"}}]}},
                      "response":{"poll":{"endpoint":"/contents/generations/tasks/{{taskId}}","method":"GET","statusPath":"status","successStatus":"succeeded","failStatus":"failed","resultPath":"content","intervalMs":3000,"maxWaitMs":600000,"cancelEndpoint":"contents/generations/tasks/{{taskId}}"},"taskIdPath":"id"},
                      "extraConfig":{"params":"--subdivisionlevel medium --fileformat glb","sl":"medium","ff":"glb"}
                    }
                  ]
                }""";
    }

    /** 保存全部模型到运行目录下的 models.json */
    public static void saveUser(List<ModelConfig> models) {
        List<Map<String, Object>> list = new ArrayList<>();
        for (ModelConfig mc : models) {
            list.add(toMap(mc));
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("models", list);

        try (Writer w = new OutputStreamWriter(
                new FileOutputStream(getRuntimeFile()), StandardCharsets.UTF_8)) {
            w.write(JsonUtil.toPrettyJson(root));
        } catch (IOException ignored) {
            System.err.println("[ModelConfigLoader] 保存 models.json 失败");
        }
    }

    // ==================== 内部 ====================

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
            Map<String, Object> respMapping = new LinkedHashMap<>();

            // 兼容格式1: 嵌套 poll 子对象 { response: { poll: { endpoint: ... } } }
            Object poll = respMap.get("poll");
            if (poll instanceof Map) respMapping.putAll((Map<String, Object>) poll);

            // 兼容格式2: 扁平化字段 { response: { endpoint: ..., statusPath: ... } }
            // （toMap 保存时会将 poll 字段展开到 response 下）
            String[] pollKeys = {"endpoint", "cancelEndpoint", "method",
                    "statusPath", "successStatus", "failStatus",
                    "intervalMs", "maxWaitMs", "timeoutSec"};
            for (String key : pollKeys) {
                Object val = respMap.get(key);
                if (val != null && !respMapping.containsKey(key)) {
                    respMapping.put(key, val);
                }
            }

            // resultPath 可能在顶层也可能在 poll 内
            String resultPath = str(respMap, "resultPath", null);
            if (resultPath != null && !respMapping.containsKey("resultPath")) {
                respMapping.put("resultPath", resultPath);
            }

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

        // response — 保留 poll 子对象结构
        Map<String, Object> respMapping = mc.getResponseMapping();
        if ((respMapping != null && !respMapping.isEmpty()) || mc.getTaskIdPath() != null) {
            Map<String, Object> resp = new LinkedHashMap<>();
            // poll 相关的 key 集合
            java.util.Set<String> pollKeys = java.util.Set.of(
                    "endpoint", "cancelEndpoint", "method",
                    "statusPath", "successStatus", "failStatus",
                    "resultPath", "intervalMs", "maxWaitMs", "timeoutSec");
            if (respMapping != null) {
                Map<String, Object> poll = new LinkedHashMap<>();
                for (Map.Entry<String, Object> e : respMapping.entrySet()) {
                    if (pollKeys.contains(e.getKey())) {
                        poll.put(e.getKey(), e.getValue());
                    } else {
                        resp.put(e.getKey(), e.getValue());
                    }
                }
                if (!poll.isEmpty()) resp.put("poll", poll);
            }
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
