package com.szh.agnes.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Agnes-Image-2.0-Flash 图片生成请求实体
 * <p>
 * API: POST https://apihub.agnes-ai.com/v1/images/generations
 */
public class AgnesImageRequest {

    /** 模型名称，固定为 agnes-image-2.0-flash */
    private String model;
    /** 文本提示词 */
    private String prompt;
    /** 输出尺寸，如 1024x768、1024x1024、768x1024 */
    private String size;
    /** 输入图片数组（图生图时必填），支持公网 URL 或 Data URI Base64 */
    private List<String> image;
    /** 文生图返回 Base64 时置为 true */
    private Boolean returnBase64;
    /** 扩展参数（response_format 等） */
    private ExtraBody extraBody;

    // ==================== 构造器 ====================

    public AgnesImageRequest() {}

    /** 快速构建文生图请求 */
    public static AgnesImageRequest textToImage(String prompt, String size) {
        AgnesImageRequest req = new AgnesImageRequest();
        req.model = "agnes-image-2.0-flash";
        req.prompt = prompt;
        req.size = size;
        return req;
    }

    /** 快速构建图生图请求（输入为公网 URL） */
    public static AgnesImageRequest imageToImage(String prompt, String size, List<String> inputImageUrls) {
        AgnesImageRequest req = textToImage(prompt, size);
        req.image = inputImageUrls;
        return req;
    }

    // ==================== Fluent API ====================

    public AgnesImageRequest model(String model) { this.model = model; return this; }
    public AgnesImageRequest prompt(String prompt) { this.prompt = prompt; return this; }
    public AgnesImageRequest size(String size) { this.size = size; return this; }
    public AgnesImageRequest image(List<String> image) { this.image = image; return this; }
    public AgnesImageRequest addImage(String imageUrl) {
        if (this.image == null) this.image = new ArrayList<>();
        this.image.add(imageUrl);
        return this;
    }
    public AgnesImageRequest returnBase64(Boolean returnBase64) { this.returnBase64 = returnBase64; return this; }

    /**
     * 设置输出格式（url 或 b64_json），调用后自动创建 extra_body
     */
    public AgnesImageRequest responseFormat(String format) {
        ensureExtraBody();
        this.extraBody.responseFormat = format;
        return this;
    }

    /**
     * 将图片放入 extra_body.image（图生图推荐方式）
     */
    public AgnesImageRequest extraImage(List<String> urls) {
        ensureExtraBody();
        this.extraBody.image = urls;
        return this;
    }

    private void ensureExtraBody() {
        if (this.extraBody == null) {
            this.extraBody = new ExtraBody();
        }
    }

    // ==================== JSON 序列化 ====================

    /**
     * 构建请求 JSON 字符串
     */
    public String toJson() {
        StringBuilder sb = new StringBuilder(256);
        sb.append('{');

        appendJsonField(sb, "model", model, true);
        appendJsonField(sb, "prompt", prompt, false);
        appendJsonField(sb, "size", size, false);

        // 顶层 image 数组
        if (image != null && !image.isEmpty()) {
            sb.append(",\"image\":[");
            for (int i = 0; i < image.size(); i++) {
                if (i > 0) sb.append(',');
                sb.append('"').append(escapeJson(image.get(i))).append('"');
            }
            sb.append(']');
        }

        if (returnBase64 != null) {
            sb.append(",\"return_base64\":").append(returnBase64);
        }

        // extra_body
        if (extraBody != null) {
            sb.append(",\"extra_body\":{");
            boolean first = true;
            if (extraBody.responseFormat != null) {
                first = false;
                appendJsonField(sb, "response_format", extraBody.responseFormat, true);
            }
            if (extraBody.image != null && !extraBody.image.isEmpty()) {
                if (!first) sb.append(',');
                sb.append("\"image\":[");
                for (int i = 0; i < extraBody.image.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(escapeJson(extraBody.image.get(i))).append('"');
                }
                sb.append(']');
            }
            sb.append('}');
        }

        sb.append('}');
        return sb.toString();
    }

    // ==================== 内部类 / 辅助 ====================

    /** 扩展参数 */
    public static class ExtraBody {
        /** 输出格式：url 或 b64_json */
        String responseFormat;
        /** 输入图片（图生图推荐放 extra_body 内） */
        List<String> image;
    }

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean isFirst) {
        if (value == null) return;
        if (!isFirst) sb.append(',');
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"'  -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < ' ') {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }

    // ==================== getter / setter ====================

    public String getModel()      { return model; }
    public String getPrompt()     { return prompt; }
    public String getSize()       { return size; }
    public List<String> getImage(){ return image; }
    public Boolean getReturnBase64() { return returnBase64; }
    public ExtraBody getExtraBody()  { return extraBody; }

    public void setModel(String model)          { this.model = model; }
    public void setPrompt(String prompt)         { this.prompt = prompt; }
    public void setSize(String size)             { this.size = size; }
    public void setImage(List<String> image)     { this.image = image; }
    public void setReturnBase64(Boolean v)       { this.returnBase64 = v; }
    public void setExtraBody(ExtraBody extraBody){ this.extraBody = extraBody; }
}
