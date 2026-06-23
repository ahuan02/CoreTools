package com.szh.agnes.entity;

/**
 * Agnes-Video-V2.0 视频任务响应实体
 * <p>
 * 同时用于任务创建响应和轮询结果解析。
 * <p>
 * 任务状态：
 * <ul>
 *   <li>queued - 排队中</li>
 *   <li>in_progress - 生成中</li>
 *   <li>completed - 已完成</li>
 *   <li>failed - 失败</li>
 * </ul>
 */
public class AgnesVideoTaskResponse {

    /** 任务 ID */
    private String id;
    /** 任务 ID（与 id 相同） */
    private String taskId;
    /** 视频 ID，推荐用于查询结果 */
    private String videoId;
    /** 对象类型 */
    private String object;
    /** 模型名称 */
    private String model;
    /** 任务状态：queued / in_progress / completed / failed */
    private String status;
    /** 进度百分比 0-100 */
    private Integer progress;
    /** 创建时间戳 */
    private Long createdAt;
    /** 视频时长（秒），字符串形式 */
    private String seconds;
    /** 视频分辨率 */
    private String size;
    /** 最终视频 URL（status 为 completed 时可用） */
    private String remixedFromVideoId;
    /** 错误信息（status 为 failed 时可用） */
    private String error;
    /** 原始 JSON 响应（调试用） */
    private String rawJson;

    // ==================== 构造器 ====================

    public AgnesVideoTaskResponse() {}

    // ==================== JSON 解析 ====================

    /**
     * 从 JSON 字符串解析响应
     */
    public static AgnesVideoTaskResponse fromJson(String json) {
        AgnesVideoTaskResponse resp = new AgnesVideoTaskResponse();
        resp.rawJson = json;
        if (json == null || json.isBlank()) return resp;

        resp.id = extractJsonString(json, "\"id\"");
        resp.taskId = extractJsonString(json, "\"task_id\"");
        resp.videoId = extractJsonString(json, "\"video_id\"");
        resp.object = extractJsonString(json, "\"object\"");
        resp.model = extractJsonString(json, "\"model\"");
        resp.status = extractJsonString(json, "\"status\"");
        resp.seconds = extractJsonString(json, "\"seconds\"");
        resp.size = extractJsonString(json, "\"size\"");
        resp.remixedFromVideoId = extractJsonString(json, "\"remixed_from_video_id\"");
        resp.error = extractErrorField(json);

        // 数字字段
        resp.progress = extractJsonInt(json, "\"progress\"");
        resp.createdAt = extractJsonLong(json, "\"created_at\"");

        return resp;
    }

    // ==================== 便捷方法 ====================

    /** 任务是否排队中 */
    public boolean isQueued() {
        return "queued".equals(status);
    }

    /** 任务是否正在生成 */
    public boolean isInProgress() {
        return "in_progress".equals(status);
    }

    /** 任务是否已完成 */
    public boolean isCompleted() {
        return "completed".equals(status);
    }

    /** 任务是否失败 */
    public boolean isFailed() {
        return "failed".equals(status);
    }

    /** 是否仍在处理中（未完成且未失败） */
    public boolean isProcessing() {
        return isQueued() || isInProgress();
    }

    /** 获取视频 URL（completed 时才有效） */
    public String getVideoUrl() {
        return isCompleted() ? remixedFromVideoId : null;
    }

    /** 获取视频时长（秒，解析为 double） */
    public double getSecondsValue() {
        if (seconds == null || seconds.isBlank()) return 0;
        try {
            return Double.parseDouble(seconds);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    /** 获取状态描述文字 */
    public String getStatusText() {
        if (status == null) return "未知";
        return switch (status) {
            case "queued" -> "排队中";
            case "in_progress" -> "生成中";
            case "completed" -> "已完成";
            case "failed" -> "失败";
            default -> status;
        };
    }

    // ==================== JSON 辅助方法 ====================

    /**
     * 解析 error 字段，支持两种格式：
     * <ul>
     *   <li>字符串：{@code "error":"something went wrong"}</li>
     *   <li>对象：{@code "error":{"code":"500","message":"Internal generation failed"}}</li>
     * </ul>
     */
    private static String extractErrorField(String json) {
        int keyIdx = json.indexOf("\"error\"");
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx < 0) return null;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\n')) {
            valStart++;
        }
        if (valStart >= json.length()) return null;

        char firstChar = json.charAt(valStart);

        if (firstChar == '"') {
            // 格式1: "error":"string message"
            return extractQuotedString(json, valStart + 1);
        } else if (firstChar == '{') {
            // 格式2: "error":{...} 嵌套对象 — 找到对象边界后内部解析
            int objEnd = findMatchingBrace(json, valStart);
            if (objEnd < 0) return null;
            String errorObj = json.substring(valStart, objEnd + 1);
            String code = extractJsonString(errorObj, "\"code\"");
            String message = extractJsonString(errorObj, "\"message\"");
            if (code != null && message != null) {
                return "[code=" + code + "] " + message;
            } else if (message != null) {
                return message;
            } else if (code != null) {
                return "[code=" + code + "]";
            }
            // 都不存在，返回完整 error 对象字符串
            return errorObj;
        }
        return null;
    }

    /** 找到与 start 位置的 { 匹配的 } 的下标 */
    private static int findMatchingBrace(String s, int start) {
        if (start >= s.length() || s.charAt(start) != '{') return -1;
        int depth = 0;
        for (int i = start; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '{') depth++;
            else if (c == '}') {
                depth--;
                if (depth == 0) return i;
            }
        }
        return -1; // 不匹配
    }

    /** 提取双引号包裹的字符串（从引号后的第一个字符开始） */
    private static String extractQuotedString(String json, int start) {
        if (start >= json.length()) return null;
        StringBuilder sb = new StringBuilder();
        for (int i = start; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++;
                if (i < json.length()) {
                    sb.append(json.charAt(i));
                }
            } else if (c == '"') {
                return sb.toString();
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private static String extractJsonString(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx < 0) return null;

        // 跳过冒号后的空白
        int valStart = colonIdx + 1;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\n')) {
            valStart++;
        }

        if (valStart >= json.length()) return null;
        char firstChar = json.charAt(valStart);

        if (firstChar == '"') {
            // 提取引号内字符串
            StringBuilder sb = new StringBuilder();
            for (int i = valStart + 1; i < json.length(); i++) {
                char c = json.charAt(i);
                if (c == '\\') {
                    i++;
                    if (i < json.length()) {
                        char next = json.charAt(i);
                        switch (next) {
                            case '"' -> sb.append('"');
                            case '\\' -> sb.append('\\');
                            case '/' -> sb.append('/');
                            case 'b' -> sb.append('\b');
                            case 'f' -> sb.append('\f');
                            case 'n' -> sb.append('\n');
                            case 'r' -> sb.append('\r');
                            case 't' -> sb.append('\t');
                            case 'u' -> {
                                if (i + 4 < json.length()) {
                                    try { sb.append((char) Integer.parseInt(json.substring(i + 1, i + 5), 16)); }
                                    catch (NumberFormatException e) { sb.append(json.substring(i, i + 5)); }
                                    i += 4;
                                }
                            }
                            default -> sb.append(next);
                        }
                    }
                } else if (c == '"') {
                    break;
                } else {
                    sb.append(c);
                }
            }
            return sb.toString();
        } else if (firstChar == 'n') {
            return null; // null
        }
        return null;
    }

    private static Integer extractJsonInt(String json, String key) {
        String str = extractRawValue(json, key);
        if (str == null) return null;
        try { return Integer.parseInt(str); } catch (NumberFormatException e) { return null; }
    }

    private static Long extractJsonLong(String json, String key) {
        String str = extractRawValue(json, key);
        if (str == null) return null;
        try { return Long.parseLong(str); } catch (NumberFormatException e) { return null; }
    }

    private static String extractRawValue(String json, String key) {
        int keyIdx = json.indexOf(key);
        if (keyIdx < 0) return null;

        int colonIdx = json.indexOf(':', keyIdx);
        if (colonIdx < 0) return null;

        int valStart = colonIdx + 1;
        while (valStart < json.length() && (json.charAt(valStart) == ' ' || json.charAt(valStart) == '\n')) {
            valStart++;
        }
        if (valStart >= json.length()) return null;

        char firstChar = json.charAt(valStart);
        if (firstChar == 'n') return null;

        int valEnd = valStart;
        while (valEnd < json.length()) {
            char c = json.charAt(valEnd);
            if (c == ',' || c == '}' || c == '\n' || c == ' ') break;
            valEnd++;
        }
        return json.substring(valStart, valEnd).trim();
    }

    // ==================== getter / setter ====================

    public String getId()              { return id; }
    public String getTaskId()          { return taskId; }
    public String getVideoId()         { return videoId; }
    public String getObject()          { return object; }
    public String getModel()           { return model; }
    public String getStatus()          { return status; }
    public Integer getProgress()       { return progress; }
    public Long getCreatedAt()         { return createdAt; }
    public String getSeconds()         { return seconds; }
    public String getSize()            { return size; }
    public String getRemixedFromVideoId() { return remixedFromVideoId; }
    public String getError()           { return error; }
    public String getRawJson()         { return rawJson; }

    public void setId(String id)                    { this.id = id; }
    public void setTaskId(String taskId)            { this.taskId = taskId; }
    public void setVideoId(String videoId)          { this.videoId = videoId; }
    public void setObject(String object)            { this.object = object; }
    public void setModel(String model)              { this.model = model; }
    public void setStatus(String status)            { this.status = status; }
    public void setProgress(Integer progress)       { this.progress = progress; }
    public void setCreatedAt(Long createdAt)        { this.createdAt = createdAt; }
    public void setSeconds(String seconds)          { this.seconds = seconds; }
    public void setSize(String size)                { this.size = size; }
    public void setRemixedFromVideoId(String url)   { this.remixedFromVideoId = url; }
    public void setError(String error)              { this.error = error; }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("AgnesVideoTaskResponse{");
        sb.append("videoId='").append(videoId).append('\'');
        sb.append(", status='").append(status).append('\'');
        sb.append(", progress=").append(progress);
        sb.append(", videoUrl='").append(remixedFromVideoId != null ? remixedFromVideoId : "null").append('\'');
        sb.append(", error='").append(error).append('\'');
        if (rawJson != null && rawJson.length() < 500) {
            sb.append(", rawJson=").append(rawJson);
        } else if (rawJson != null) {
            sb.append(", rawJson=").append(rawJson.substring(0, 500)).append("...(truncated)");
        }
        sb.append('}');
        return sb.toString();
    }
}
