package com.szh.ai;

import com.szh.entity.ModelConfig;
import com.szh.utils.ThreadPoolUtil;

import javax.swing.*;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.util.*;

/**
 * 通用 AI API 调用器 — 支持 sync 和 task 两种模式。
 * <p>
 * 特性：
 * <ul>
 *   <li>自定义请求头（{{apiKey}} 占位符自动替换）</li>
 *   <li>multipart/form-data 上传（bodyType: "multipart"）</li>
 *   <li>本地图片自动解析为 HTTP URL 或 base64 数据 URI</li>
 * </ul>
 */
public class GenericApiCaller {

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_2)
            .connectTimeout(Duration.ofSeconds(45))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    // ==================== 图片 URL 供应器（由上层模块注入） ====================

    /** 将本地文件注册为可访问的 HTTP URL。返回 null 表示不可用，触发 base64 退路。 */
    public interface ImageUrlSupplier {
        String supply(File localFile);
    }

    private static volatile ImageUrlSupplier imageUrlSupplier;

    /** 由 AiChatPanel 在初始化时注入（调用 NgrokPanel.registerFile） */
    public static void setImageUrlSupplier(ImageUrlSupplier supplier) {
        imageUrlSupplier = supplier;
    }

    /** 将图片路径解析为可发送的值（HTTP URL > base64 data URI > 原值） */
    public static String resolveImage(String imageSource) {
        if (imageSource == null || imageSource.isBlank()) return "";
        // 已经是 URL 或 base64 data URI → 直接返回
        if (imageSource.startsWith("http://") || imageSource.startsWith("https://")
                || imageSource.startsWith("data:")) {
            return imageSource;
        }

        File file = new File(imageSource);
        if (!file.isFile()) return imageSource; // 不是文件，原样返回

        // 尝试通过 NgrokPanel 文件服务器获取公网 URL
        if (imageUrlSupplier != null) {
            String url = imageUrlSupplier.supply(file);
            if (url != null && !url.isBlank()) return url;
        }

        // 退路：base64 data URI
        try {
            byte[] bytes = Files.readAllBytes(file.toPath());
            String mime = guessMimeType(file.getName());
            String b64 = Base64.getEncoder().encodeToString(bytes);
            return "data:" + mime + ";base64," + b64;
        } catch (IOException e) {
            System.err.println("[GenericApiCaller] 读取图片失败: " + imageSource + " → " + e.getMessage());
            return imageSource;
        }
    }

    private static String guessMimeType(String name) {
        String lower = name.toLowerCase();
        if (lower.endsWith(".png"))  return "image/png";
        if (lower.endsWith(".gif"))  return "image/gif";
        if (lower.endsWith(".bmp"))  return "image/bmp";
        if (lower.endsWith(".webp")) return "image/webp";
        if (lower.endsWith(".svg"))  return "image/svg+xml";
        return "image/jpeg";
    }

    // ==================== 返回结构 ====================

    /** sync 模式返回 */
    public record ApiResult(Object data, String rawJson) {}

    /** task 模式返回 */
    public record TaskResult(Object data, String rawJson, String taskId) {}

    // ==================== sync 模式（POST → 同步返回） ====================

    /**
     * 同步调用：POST 请求 → 解析响应 → 按 resultPath 提取结果
     */
    public static ApiResult callSync(ModelConfig mc, String jsonBody) throws Exception {
        HttpRequest req = buildRequest(mc, jsonBody);
        HttpResponse<String> resp = sendWithRetry(req);
        return parseSyncResponse(mc, resp);
    }

    /** 异步 sync 调用 */
    public static void callSyncAsync(ModelConfig mc, String jsonBody,
                                     java.util.function.Consumer<ApiResult> onSuccess,
                                     java.util.function.Consumer<Exception> onError) {
        ThreadPoolUtil.submitVirtual(() -> {
            try {
                ApiResult result = callSync(mc, jsonBody);
                if (onSuccess != null) SwingUtilities.invokeLater(() -> onSuccess.accept(result));
            } catch (Exception e) {
                if (onError != null) SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
    }

    // ==================== task 模式（POST 创建 → 轮询 GET → 取结果） ====================

    public static void callTaskAsync(ModelConfig mc, String jsonBody,
                                     java.util.function.Consumer<TaskResult> onSuccess,
                                     java.util.function.Consumer<Exception> onError,
                                     java.util.function.Consumer<String> onProgress) {
        ThreadPoolUtil.submitVirtual(() -> {
            try {
                // Step 1: 创建任务
                HttpRequest createReq = buildRequest(mc, jsonBody);
                HttpResponse<String> createResp = sendWithRetry(createReq);

                if (createResp.statusCode() < 200 || createResp.statusCode() >= 300) {
                    throw new ApiException("创建任务失败 HTTP " + createResp.statusCode() + ": "
                            + truncate(createResp.body()), createResp.statusCode());
                }

                Map<String, Object> respMap = JsonUtil.parseObject(createResp.body());

                // 取 taskId
                String taskIdPath = mc.getTaskIdPath();
                Object taskIdObj = taskIdPath != null ? JsonUtil.getByPath(respMap, taskIdPath) : null;
                if (taskIdObj == null) {
                    throw new ApiException("响应中找不到任务 ID（路径: " + taskIdPath + "）", 0);
                }
                String taskId = String.valueOf(taskIdObj);

                // Step 2: 轮询
                Map<String, Object> pollCfg = mc.getResponseMapping() != null
                        ? mc.getResponseMapping() : Map.of();
                if (pollCfg.isEmpty()) {
                    throw new ApiException("未配置轮询规则（response.poll）", 0);
                }

                String endpoint = (String) pollCfg.getOrDefault("endpoint", "/{{taskId}}");
                String method = (String) pollCfg.getOrDefault("method", "GET");
                String statusPath = (String) pollCfg.get("statusPath");
                String successStatus = (String) pollCfg.get("successStatus");
                String failStatus = (String) pollCfg.get("failStatus");
                String resultPath = (String) pollCfg.get("resultPath");
                long intervalMs = toLong(pollCfg.get("intervalMs"), 3000);
                long maxWaitMs = toLong(pollCfg.get("maxWaitMs"), 600_000);
                long timeoutSec = toLong(pollCfg.get("timeoutSec"), 30);

                long startTime = System.currentTimeMillis();
                int pollCount = 0;

                while (System.currentTimeMillis() - startTime < maxWaitMs) {
                    Thread.sleep(intervalMs);
                    pollCount++;

                    String pollUrl = mc.getApiUrl();
                    if (!pollUrl.endsWith("/")) pollUrl += "/";
                    String resolvedEndpoint = endpoint.replace("{{taskId}}", taskId)
                            .replace("{id}", taskId);
                    if (resolvedEndpoint.startsWith("/")) resolvedEndpoint = resolvedEndpoint.substring(1);
                    pollUrl += resolvedEndpoint;

                    // 轮询也使用自定义 headers
                    HttpRequest.Builder pollReqBuilder = HttpRequest.newBuilder()
                            .uri(URI.create(pollUrl))
                            .timeout(Duration.ofSeconds(timeoutSec))
                            .GET();

                    applyHeaders(pollReqBuilder, mc);

                    HttpRequest pollReq = pollReqBuilder.build();
                    HttpResponse<String> pollResp = sendWithRetry(pollReq);

                    if (pollResp.statusCode() != 200) {
                        if (onProgress != null) {
                            int finalPollCount = pollCount;
                            SwingUtilities.invokeLater(() ->
                                    onProgress.accept("轮询 #" + finalPollCount + ": HTTP " + pollResp.statusCode()));
                        }
                        continue;
                    }

                    Map<String, Object> pollMap = JsonUtil.parseObject(pollResp.body());

                    String status = null;
                    if (statusPath != null) {
                        Object s = JsonUtil.getByPath(pollMap, statusPath);
                        status = s != null ? String.valueOf(s) : null;
                    }

                    if (onProgress != null && status != null) {
                        final String fs = status;
                        int finalPollCount1 = pollCount;
                        SwingUtilities.invokeLater(() -> onProgress.accept("状态: " + fs + " (第 " + finalPollCount1 + " 次)"));
                    }

                    if (successStatus != null && successStatus.equals(status)) {
                        Object resultData = resultPath != null ? JsonUtil.getByPath(pollMap, resultPath) : pollMap;
                        final TaskResult tr = new TaskResult(resultData, pollResp.body(), taskId);
                        if (onSuccess != null) SwingUtilities.invokeLater(() -> onSuccess.accept(tr));
                        return;
                    }

                    if (failStatus != null && failStatus.equals(status)) {
                        throw new ApiException("任务失败: " + pollResp.body(), 0);
                    }
                }

                throw new ApiException("任务超时（已等待 " + (maxWaitMs / 1000) + " 秒，" + pollCount + " 次轮询）", 0);

            } catch (Exception e) {
                if (onError != null) SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
    }

    // ==================== HTTP 构建 ====================

    /** 构建 HTTP 请求，自动处理 headers / multipart */
    private static HttpRequest buildRequest(ModelConfig mc, String jsonBody) throws Exception {
        String url = buildUrl(mc);

        String bodyType = mc.getBodyType() != null ? mc.getBodyType() : "json";

        if ("multipart".equalsIgnoreCase(bodyType)) {
            // multipart 入参是 JSON 结构的 body，需解析出 key/value 对构建 multipart
            Map<String, Object> bodyMap = JsonUtil.parseObject(jsonBody);
            return buildMultipartRequest(mc, url, bodyMap);
        }

        // 默认 JSON
        return buildJsonRequest(mc, url, jsonBody);
    }

    /** 构建完整 URL */
    private static String buildUrl(ModelConfig mc) {
        String url = mc.getApiUrl();
        if (!url.endsWith("/")) url += "/";
        String ep = mc.getEndpoint() != null ? mc.getEndpoint() : "";
        if (ep.startsWith("/")) ep = ep.substring(1);
        url += ep;
        return url;
    }

    /** JSON 请求（原逻辑 + 自定义 headers） */
    private static HttpRequest buildJsonRequest(ModelConfig mc, String url, String jsonBody) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(toLong(mc.getExtra("timeoutSec"), 180)))
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));

        applyHeaders(builder, mc);
        // 确保 Content-Type
        if (!hasHeader(mc, "content-type")) {
            builder.header("Content-Type", "application/json");
        }

        return builder.build();
    }

    /** multipart/form-data 请求 */
    @SuppressWarnings("unchecked")
    private static HttpRequest buildMultipartRequest(ModelConfig mc, String url,
                                                      Map<String, Object> bodyMap) throws Exception {
        String boundary = "----CoreToolsBoundary" + UUID.randomUUID().toString().replace("-", "");
        byte[] body = buildMultipartBody(bodyMap, boundary);

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .timeout(Duration.ofSeconds(toLong(mc.getExtra("timeoutSec"), 300)))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));

        applyHeaders(builder, mc);
        return builder.build();
    }

    /** 构建 multipart body 字节 */
    private static byte[] buildMultipartBody(Map<String, Object> fields, String boundary) throws Exception {
        List<byte[]> parts = new ArrayList<>();
        byte[] boundaryBytes = ("--" + boundary + "\r\n").getBytes(StandardCharsets.UTF_8);
        byte[] crlf = "\r\n".getBytes(StandardCharsets.UTF_8);
        byte[] endBoundary = ("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8);

        for (Map.Entry<String, Object> entry : fields.entrySet()) {
            parts.add(boundaryBytes);
            String name = entry.getKey();
            Object value = entry.getValue();
            if (value == null) value = "";

            String strVal = String.valueOf(value);

            // 如果是本地文件路径 → 作为文件上传
            File maybeFile = new File(strVal);
            if (maybeFile.isFile() && !strVal.startsWith("data:") && !strVal.startsWith("http")) {
                // 文件上传
                String fileName = maybeFile.getName();
                String mime = guessMimeType(fileName);
                String header = "Content-Disposition: form-data; name=\"" + name
                        + "\"; filename=\"" + fileName + "\"\r\n"
                        + "Content-Type: " + mime + "\r\n\r\n";
                parts.add(header.getBytes(StandardCharsets.UTF_8));
                parts.add(Files.readAllBytes(maybeFile.toPath()));
                parts.add(crlf);
            } else {
                // 普通文本字段
                String header = "Content-Disposition: form-data; name=\"" + name + "\"\r\n\r\n";
                parts.add(header.getBytes(StandardCharsets.UTF_8));
                parts.add(strVal.getBytes(StandardCharsets.UTF_8));
                parts.add(crlf);
            }
        }
        parts.add(endBoundary);

        // 按顺序拼接
        int totalLen = 0;
        for (byte[] part : parts) totalLen += part.length;
        byte[] result = new byte[totalLen];
        int offset = 0;
        for (byte[] part : parts) {
            System.arraycopy(part, 0, result, offset, part.length);
            offset += part.length;
        }
        return result;
    }

    /** 应用自定义请求头，不覆盖已设置的 Content-Type */
    private static void applyHeaders(HttpRequest.Builder builder, ModelConfig mc) {
        Map<String, String> headers = mc.getRequestHeaders();
        if (headers != null && !headers.isEmpty()) {
            for (Map.Entry<String, String> e : headers.entrySet()) {
                // {{apiKey}} 替换
                String val = e.getValue();
                if (val != null && val.contains("{{apiKey}}")) {
                    val = val.replace("{{apiKey}}", mc.getApiKey() != null ? mc.getApiKey() : "");
                }
                builder.header(e.getKey(), val);
            }
            // 如果自定义 headers 中没有 Authorization，用默认 Bearer
            if (!hasHeader(mc, "authorization") && mc.getApiKey() != null && !mc.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + mc.getApiKey());
            }
        } else {
            // 无自定义 headers → 默认 Bearer
            if (mc.getApiKey() != null && !mc.getApiKey().isBlank()) {
                builder.header("Authorization", "Bearer " + mc.getApiKey());
            }
        }
    }

    private static boolean hasHeader(ModelConfig mc, String key) {
        if (mc.getRequestHeaders() == null) return false;
        String lowerKey = key.toLowerCase();
        for (String k : mc.getRequestHeaders().keySet()) {
            if (k.toLowerCase().equals(lowerKey)) return true;
        }
        return false;
    }

    // ==================== HTTP 辅助 ====================

    private static HttpResponse<String> sendWithRetry(HttpRequest req) throws Exception {
        Exception lastEx = null;
        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                return HTTP.send(req, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                lastEx = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (attempt < 2 && (msg.contains("reset") || msg.contains("header parser") || msg.contains("RST_STREAM"))) {
                    Thread.sleep((long) Math.pow(2, attempt + 1) * 1000);
                    continue;
                }
                throw e;
            }
        }
        throw lastEx;
    }

    private static ApiResult parseSyncResponse(ModelConfig mc, HttpResponse<String> resp) throws Exception {
        if (resp.statusCode() < 200 || resp.statusCode() >= 300) {
            throw new ApiException("API 调用失败 HTTP " + resp.statusCode() + ": "
                    + truncate(resp.body()), resp.statusCode());
        }
        String body = resp.body();
        Map<String, Object> respMap = JsonUtil.parseObject(body);
        Map<String, Object> rMapping = mc.getResponseMapping();
        String resultPath = rMapping != null ? (String) rMapping.get("resultPath") : null;
        Object data = resultPath != null ? JsonUtil.getByPath(respMap, resultPath) : respMap;
        return new ApiResult(data, body);
    }

    private static long toLong(Object v, long def) {
        if (v == null) return def;
        if (v instanceof Number n) return n.longValue();
        try { return Long.parseLong(String.valueOf(v)); } catch (NumberFormatException e) { return def; }
    }

    private static String truncate(String s) {
        return s != null && s.length() > 300 ? s.substring(0, 300) + "..." : s;
    }

    // ==================== 异常类 ====================

    public static class ApiException extends Exception {
        private final int statusCode;
        public ApiException(String msg, int code) { super(msg); this.statusCode = code; }
        public int getStatusCode() { return statusCode; }
    }
}
