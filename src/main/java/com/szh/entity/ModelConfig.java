package com.szh.entity;

/**
 * AI 模型配置实体
 */
public class ModelConfig {
    private String alias;     // 别名（显示用）
    private String apiKey;    // 自定义 Key
    private String apiUrl;    // API 地址
    private String modelName; // 模型全称

    public ModelConfig() {
        this("", "", "", "");
    }

    public ModelConfig(String alias, String apiKey, String apiUrl, String modelName) {
        this.alias = alias != null ? alias : "";
        this.apiKey = apiKey != null ? apiKey : "";
        this.apiUrl = apiUrl != null ? apiUrl : "";
        this.modelName = modelName != null ? modelName : "";
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

    @Override
    public String toString() {
        return comboLabel();
    }
}
