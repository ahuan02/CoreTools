package com.szh.agnes.entity;

import java.util.List;

/**
 * Agnes-Video-V2.0 视频生成请求实体
 * <p>
 * API: POST https://apihub.agnes-ai.com/v1/videos
 * <p>
 * 支持：文生视频、图生视频、多图视频生成、关键帧动画
 */
public class AgnesVideoRequest {

    /** 模型名称，固定为 agnes-video-v2.0 */
    private String model;
    /** 视频内容的文本描述（必填） */
    private String prompt;
    /** 图片 URL（图生视频时用）或图片 URL 数组 */
    private Object image; // String or List<String>
    /** 生成模式：ti2vid 或 keyframes */
    private String mode;
    /** 视频高度，默认 768 */
    private Integer height;
    /** 视频宽度，默认 1152 */
    private Integer width;
    /** 视频帧数，≤441，满足 8n+1 */
    private Integer numFrames;
    /** 视频 FPS，1–60 */
    private Double frameRate;
    /** 推理步数 */
    private Integer numInferenceSteps;
    /** 随机种子 */
    private Integer seed;
    /** 负向提示词 */
    private String negativePrompt;
    /** 扩展参数（多图/关键帧模式） */
    private ExtraBody extraBody;

    // ==================== 构造器 ====================

    public AgnesVideoRequest() {}

    /** 快速构建文生视频请求 */
    public static AgnesVideoRequest textToVideo(String prompt) {
        AgnesVideoRequest req = new AgnesVideoRequest();
        req.model = "agnes-video-v2.0";
        req.prompt = prompt;
        return req;
    }

    /** 快速构建图生视频请求 */
    public static AgnesVideoRequest imageToVideo(String prompt, String imageUrl) {
        AgnesVideoRequest req = textToVideo(prompt);
        req.image = imageUrl;
        return req;
    }

    // ==================== Fluent API ====================

    public AgnesVideoRequest model(String model) { this.model = model; return this; }
    public AgnesVideoRequest prompt(String prompt) { this.prompt = prompt; return this; }
    public AgnesVideoRequest image(String imageUrl) { this.image = imageUrl; return this; }
    public AgnesVideoRequest images(List<String> urls) { this.image = urls; return this; }
    public AgnesVideoRequest mode(String mode) { this.mode = mode; return this; }
    public AgnesVideoRequest height(Integer height) { this.height = height; return this; }
    public AgnesVideoRequest width(Integer width) { this.width = width; return this; }
    public AgnesVideoRequest size(int width, int height) { this.width = width; this.height = height; return this; }
    public AgnesVideoRequest numFrames(Integer numFrames) { this.numFrames = numFrames; return this; }
    public AgnesVideoRequest frameRate(Double frameRate) { this.frameRate = frameRate; return this; }
    public AgnesVideoRequest numInferenceSteps(Integer steps) { this.numInferenceSteps = steps; return this; }
    public AgnesVideoRequest seed(Integer seed) { this.seed = seed; return this; }
    public AgnesVideoRequest negativePrompt(String np) { this.negativePrompt = np; return this; }

    /**
     * 设置多图输入的图片 URL（放入 extra_body.image）
     */
    public AgnesVideoRequest extraImages(List<String> urls) {
        ensureExtraBody();
        this.extraBody.image = urls;
        return this;
    }

    /**
     * 设置为关键帧模式（extra_body.mode = "keyframes"）
     */
    public AgnesVideoRequest keyframesMode() {
        ensureExtraBody();
        this.extraBody.mode = "keyframes";
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

        // 顶层 image
        if (image != null) {
            sb.append(',');
            sb.append("\"image\":");
            if (image instanceof String s) {
                sb.append('"').append(escapeJson(s)).append('"');
            } else if (image instanceof List<?> list) {
                sb.append('[');
                boolean first = true;
                for (Object item : list) {
                    if (!first) sb.append(',');
                    sb.append('"').append(escapeJson(String.valueOf(item))).append('"');
                    first = false;
                }
                sb.append(']');
            }
        }

        // mode
        if (mode != null) {
            sb.append(',');
            sb.append("\"mode\":\"").append(escapeJson(mode)).append('"');
        }

        // 数值字段
        appendJsonIntField(sb, "height", height);
        appendJsonIntField(sb, "width", width);
        appendJsonIntField(sb, "num_frames", numFrames);
        appendJsonDoubleField(sb, "frame_rate", frameRate);
        appendJsonIntField(sb, "num_inference_steps", numInferenceSteps);
        appendJsonIntField(sb, "seed", seed);

        // negative_prompt
        if (negativePrompt != null) {
            sb.append(',');
            sb.append("\"negative_prompt\":\"").append(escapeJson(negativePrompt)).append('"');
        }

        // extra_body
        if (extraBody != null) {
            sb.append(",\"extra_body\":{");
            boolean first = true;
            if (extraBody.image != null && !extraBody.image.isEmpty()) {
                first = false;
                sb.append("\"image\":[");
                for (int i = 0; i < extraBody.image.size(); i++) {
                    if (i > 0) sb.append(',');
                    sb.append('"').append(escapeJson(extraBody.image.get(i))).append('"');
                }
                sb.append(']');
            }
            if (extraBody.mode != null) {
                if (!first) sb.append(',');
                sb.append("\"mode\":\"").append(escapeJson(extraBody.mode)).append('"');
            }
            sb.append('}');
        }

        sb.append('}');
        return sb.toString();
    }

    // ==================== 内部类 ====================

    /** 扩展参数 */
    public static class ExtraBody {
        /** 多图输入的图片 URL 数组 */
        List<String> image;
        /** 模式：keyframes 等 */
        String mode;
    }

    // ==================== JSON 辅助 ====================

    private static void appendJsonField(StringBuilder sb, String key, String value, boolean isFirst) {
        if (value == null) return;
        if (!isFirst) sb.append(',');
        sb.append('"').append(key).append("\":\"").append(escapeJson(value)).append('"');
    }

    private static void appendJsonIntField(StringBuilder sb, String key, Integer value) {
        if (value == null) return;
        sb.append(',').append('"').append(key).append("\":").append(value);
    }

    private static void appendJsonDoubleField(StringBuilder sb, String key, Double value) {
        if (value == null) return;
        sb.append(',').append('"').append(key).append("\":").append(value);
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

    public String getModel()        { return model; }
    public String getPrompt()       { return prompt; }
    public Object getImage()        { return image; }
    public String getMode()         { return mode; }
    public Integer getHeight()      { return height; }
    public Integer getWidth()       { return width; }
    public Integer getNumFrames()   { return numFrames; }
    public Double getFrameRate()    { return frameRate; }
    public Integer getNumInferenceSteps() { return numInferenceSteps; }
    public Integer getSeed()        { return seed; }
    public String getNegativePrompt() { return negativePrompt; }
    public ExtraBody getExtraBody() { return extraBody; }

    public void setModel(String model)              { this.model = model; }
    public void setPrompt(String prompt)            { this.prompt = prompt; }
    public void setImage(Object image)              { this.image = image; }
    public void setMode(String mode)                { this.mode = mode; }
    public void setHeight(Integer height)           { this.height = height; }
    public void setWidth(Integer width)             { this.width = width; }
    public void setNumFrames(Integer numFrames)     { this.numFrames = numFrames; }
    public void setFrameRate(Double frameRate)      { this.frameRate = frameRate; }
    public void setNumInferenceSteps(Integer steps) { this.numInferenceSteps = steps; }
    public void setSeed(Integer seed)              { this.seed = seed; }
    public void setNegativePrompt(String np)        { this.negativePrompt = np; }
    public void setExtraBody(ExtraBody eb)          { this.extraBody = eb; }
}
