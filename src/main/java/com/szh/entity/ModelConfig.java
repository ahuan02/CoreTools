package com.szh.entity;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * AI 模型配置实体
 */
public class ModelConfig {
    private String alias;     // 别名（显示用）
    private String apiKey;    // 自定义 Key
    private String apiUrl;    // API 地址
    private String modelName; // 模型全称
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
        this.extraConfig = new LinkedHashMap<>();
    }

    /** 下拉列表显示的标签 */
    public String comboLabel() {
        if (alias.isEmpty() && modelName.isEmpty()) return "(未命名)";
        if (!alias.isEmpty()) return alias;
        return modelName;
    }

    // ==================== getter / setter ====================

    public String getAlias()     { return alias; }
    public String getApiKey()    { return apiKey; }
    public String getApiUrl()    { return apiUrl; }
    public String getModelName() { return modelName; }

    public void setAlias(String alias)         { this.alias = alias != null ? alias : ""; }
    public void setApiKey(String apiKey)       { this.apiKey = apiKey != null ? apiKey : ""; }
    public void setApiUrl(String apiUrl)       { this.apiUrl = apiUrl != null ? apiUrl : ""; }
    public void setModelName(String modelName) { this.modelName = modelName != null ? modelName : ""; }

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
        return alias.equals(that.alias)
            && apiKey.equals(that.apiKey)
            && apiUrl.equals(that.apiUrl)
            && modelName.equals(that.modelName)
            && extraConfig.equals(that.extraConfig);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(alias, apiKey, apiUrl, modelName, extraConfig);
    }
}
