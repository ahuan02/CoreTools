package com.szh.agnes.entity;

import java.util.ArrayList;
import java.util.List;

/**
 * Agnes-Image-2.0-Flash 图片生成响应实体
 */
public class AgnesImageResponse {

    /** 请求创建时间戳 */
    private long created;
    /** 生成图片结果列表 */
    private List<ImageData> data;

    // ==================== 构造器 ====================

    public AgnesImageResponse() {}

    // ==================== 简易 JSON 解析 ====================

    /**
     * 从 JSON 字符串解析响应
     */
    public static AgnesImageResponse fromJson(String json) {
        AgnesImageResponse resp = new AgnesImageResponse();
        if (json == null || json.isBlank()) return resp;

        // 解析 created
        int createdIdx = json.indexOf("\"created\"");
        if (createdIdx >= 0) {
            int colonIdx = json.indexOf(':', createdIdx);
            if (colonIdx >= 0) {
                int endIdx = json.indexOf(',', colonIdx);
                if (endIdx < 0) endIdx = json.indexOf('}', colonIdx);
                if (endIdx > colonIdx) {
                    String numStr = json.substring(colonIdx + 1, endIdx).trim();
                    try { resp.created = Long.parseLong(numStr); } catch (NumberFormatException ignored) {}
                }
            }
        }

        // 解析 data 数组
        resp.data = new ArrayList<>();
        int dataIdx = json.indexOf("\"data\"");
        if (dataIdx >= 0) {
            int bracketIdx = json.indexOf('[', dataIdx);
            int endBracketIdx = findMatchingBracket(json, bracketIdx);
            if (bracketIdx >= 0 && endBracketIdx > bracketIdx) {
                String arrayJson = json.substring(bracketIdx + 1, endBracketIdx).trim();
                // 按 "{\n...}" 分割每个对象
                parseDataObjects(arrayJson, resp.data);
            }
        }

        return resp;
    }

    // ==================== 辅助解析方法 ====================

    private static void parseDataObjects(String arrayJson, List<ImageData> dataList) {
        int depth = 0;
        int start = -1;

        for (int i = 0; i < arrayJson.length(); i++) {
            char c = arrayJson.charAt(i);
            if (c == '{') {
                if (depth == 0) start = i;
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0 && start >= 0) {
                    String objJson = arrayJson.substring(start, i + 1);
                    ImageData img = parseImageData(objJson);
                    if (img != null) dataList.add(img);
                    start = -1;
                }
            } else if (c == '"') {
                // 跳过字符串
                i = skipString(arrayJson, i);
            }
        }
    }

    private static ImageData parseImageData(String objJson) {
        ImageData data = new ImageData();

        data.url = extractJsonString(objJson, "\"url\"");
        data.b64Json = extractJsonString(objJson, "\"b64_json\"");
        data.revisedPrompt = extractJsonString(objJson, "\"revised_prompt\"");

        // 只有至少一个字段不为空才算有效
        if (data.url != null || data.b64Json != null) {
            return data;
        }
        return null;
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
            // 提取引号内的字符串
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
            // null
            return null;
        }
        return null;
    }

    private static int findMatchingBracket(String json, int openIdx) {
        if (openIdx < 0) return -1;
        int depth = 0;
        for (int i = openIdx; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '[') {
                depth++;
            } else if (c == ']') {
                depth--;
                if (depth == 0) return i;
            } else if (c == '"') {
                i = skipString(json, i);
            }
        }
        return -1;
    }

    private static int skipString(String json, int startIdx) {
        for (int i = startIdx + 1; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '\\') {
                i++; // skip escaped char
            } else if (c == '"') {
                return i;
            }
        }
        return json.length();
    }

    // ==================== 便捷方法 ====================

    /** 获取第一张图片的 URL（URL 输出模式下） */
    public String firstImageUrl() {
        if (data != null && !data.isEmpty()) {
            for (ImageData d : data) {
                if (d.url != null && !d.url.isEmpty()) return d.url;
            }
        }
        return null;
    }

    /** 获取第一张图片的 Base64 数据（Base64 输出模式下） */
    public String firstImageBase64() {
        if (data != null && !data.isEmpty()) {
            for (ImageData d : data) {
                if (d.b64Json != null && !d.b64Json.isEmpty()) return d.b64Json;
            }
        }
        return null;
    }

    /** 是否生成成功 */
    public boolean isSuccess() {
        return data != null && !data.isEmpty();
    }

    // ==================== 内部类 ====================

    /** 单张图片数据 */
    public static class ImageData {
        /** 生成图片 URL（Base64 输出时通常为 null） */
        public String url;
        /** Base64 图片数据（URL 输出时通常为 null） */
        public String b64Json;
        /** 修订后的 Prompt（如无则为 null） */
        public String revisedPrompt;

        @Override
        public String toString() {
            return "ImageData{url='" + url + "', b64Json=" + (b64Json != null ? "(len=" + b64Json.length() + ")" : "null") + "}";
        }
    }

    // ==================== getter / setter ====================

    public long getCreated()           { return created; }
    public List<ImageData> getData()   { return data; }

    public void setCreated(long created)      { this.created = created; }
    public void setData(List<ImageData> data) { this.data = data; }

    @Override
    public String toString() {
        return "AgnesImageResponse{created=" + created + ", data=" + data + '}';
    }
}
