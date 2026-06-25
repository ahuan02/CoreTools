package com.szh.entity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型配置实体
 * <p>
 * 支持四种类型：
 * <ul>
 *   <li>chat — OpenAI 兼容的对话模型（走 LangChain4j）</li>
 *   <li>sync — POST 同步返回结果（如文生图）</li>
 *   <li>task — POST 创建异步任务 + 轮询取结果（如 3D 生成、视频生成）</li>
 *   <li>custom — 自定义 Handler 兜底</li>
 * </ul>
 */
public class ModelConfig {
    private String alias;     // 别名（显示用）
    private String apiKey;    // 自定义 Key
    private String apiUrl;    // API 地址（baseUrl，不包含 endpoint）
    private String modelName; // 模型全称

    /** 模型类型：chat / sync / task / custom */
    private String type;
    /** 请求端点路径（如 /v1/images/generations） */
    private String endpoint;
    /** 请求体模板（JSON Map，含 {{var}} 占位符） */
    private Map<String, Object> requestTemplate;
    /** 自定义请求头（如 x-api-key、Content-Type），空则用默认 Bearer */    private Map<String, String> requestHeaders;
    /** 请求体类型：json（默认） / multipart */
    private String bodyType;
    /** 响应解析配置（resultPath / poll 等） */
    private Map<String, Object> responseMapping;
    /** 异步任务创建响应中，taskId 的提取路径（如 "id"） */
    private String taskIdPath;

    /** 扩展配置（用于存储图片/视频生成参数等） */
    private Map<String, String> extraConfig;

    public ModelConfig() {
        this("", "", "", "");
    }

    public ModelConfig(String alias, String apiKey, String apiUrl, String modelName) {
        this.alias = alias != null ? alias : "";
        this.apiKey = apiKey != null ? apiKey : "";
        this.apiUrl = apiUrl != null ? apiUrl : "";
        this.modelName = modelName != null ? modelName : "";
        this.type = "chat";
        this.extraConfig = new LinkedHashMap<>();
    }

    /** 下拉列表显示的标签，非 chat 类型加标记 */
    public String comboLabel() {
        if (alias.isEmpty() && modelName.isEmpty()) return "(未命名)";
        StringBuilder sb = new StringBuilder();
        if (!alias.isEmpty()) sb.append(alias);
        else sb.append(modelName);
        // 非聊天模型加类型标记
        if (type != null && !"chat".equals(type)) {
            sb.append(" [").append(switch (type) {
                case "sync" -> "生成";
                case "task" -> "任务";
                default -> type;
            }).append("]");
        }
        return sb.toString();
    }

    /** 是否是聊天模型 */
    public boolean isChat() { return type == null || "chat".equals(type); }

    // ==================== getter / setter ====================

    public String getAlias()     { return alias; }
    public String getApiKey()    { return apiKey; }
    public String getApiUrl()    { return apiUrl; }
    public String getModelName() { return modelName; }
    public String getType()      { return type; }
    public String getEndpoint()  { return endpoint; }
    public Map<String, Object> getRequestTemplate()  { return requestTemplate; }
    public Map<String, String> getRequestHeaders()   { return requestHeaders; }
    public String getBodyType()                      { return bodyType; }
    public Map<String, Object> getResponseMapping()  { return responseMapping; }
    public String getTaskIdPath() { return taskIdPath; }

    public void setAlias(String alias)         { this.alias = alias != null ? alias : ""; }
    public void setApiKey(String apiKey)       { this.apiKey = apiKey != null ? apiKey : ""; }
    public void setApiUrl(String apiUrl)       { this.apiUrl = apiUrl != null ? apiUrl : ""; }
    public void setModelName(String modelName) { this.modelName = modelName != null ? modelName : ""; }
    public void setType(String type)           { this.type = type != null ? type : "chat"; }
    public void setEndpoint(String endpoint)   { this.endpoint = endpoint; }
    public void setRequestTemplate(Map<String, Object> t)  { this.requestTemplate = t; }
    public void setRequestHeaders(Map<String, String> hdrs)  { this.requestHeaders = hdrs; }
    public void setBodyType(String bodyType)                 { this.bodyType = bodyType; }
    public void setResponseMapping(Map<String, Object> m)  { this.responseMapping = m; }
    public void setTaskIdPath(String path)     { this.taskIdPath = path; }

    public Map<String, String> getExtraConfig() { return extraConfig; }
    public void setExtraConfig(Map<String, String> extraConfig) {
        this.extraConfig = extraConfig != null ? extraConfig : new LinkedHashMap<>();
    }
    public String getExtra(String key) { return extraConfig.get(key); }
    public void putExtra(String key, String value) { extraConfig.put(key, value); }

    @Override
    public String toString() {
        return comboLabel();
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof ModelConfig that)) return false;
        return java.util.Objects.equals(alias, that.alias)
            && java.util.Objects.equals(apiKey, that.apiKey)
            && java.util.Objects.equals(apiUrl, that.apiUrl)
            && java.util.Objects.equals(modelName, that.modelName)
            && java.util.Objects.equals(type, that.type)
            && java.util.Objects.equals(extraConfig, that.extraConfig);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(alias, apiKey, apiUrl, modelName, type, extraConfig);
    }
}
