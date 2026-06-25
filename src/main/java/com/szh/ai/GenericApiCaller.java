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
            .version(HttpClient.Version.HTTP_1_1)
            .connectTimeout(Duration.ofSeconds(45))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    /** 状态英文 → 中文翻译 */
    private static final java.util.Map<String, String> STATUS_CN = java.util.Map.of(
            "queued", "排队中",
            "running", "运行中",
            "succeeded", "已完成",
            "failed", "失败",
            "cancelled", "已取消"
    );

    /** 翻译状态文本，未知状态保持原文 */
    private static String translateStatus(String status) {
        if (status == null) return "未知";
        return STATUS_CN.getOrDefault(status, status);
    }

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

    // ==================== 任务取消句柄 ====================

    /** task 模式返回的句柄，调用方可通过它取消正在进行的任务 */
    public static class TaskHandle {
        private volatile boolean cancelled;
        private volatile String taskId;

        public boolean isCancelled() { return cancelled; }
        public String getTaskId() { return taskId; }

        /**
         * 取消任务：设置标志 + 调用服务端 DELETE 端点删除排队中的任务。
         * 如果已配置 cancelEndpoint，会发送 DELETE 请求到服务端。
         */
        public boolean cancel(ModelConfig mc) {
            cancelled = true;
            if (taskId == null || taskId.isBlank()) return false;
            // 尝试调用服务端取消端点
            Map<String, Object> pollCfg = mc.getResponseMapping() != null
                    ? mc.getResponseMapping() : Map.of();
            String cancelEndpoint = (String) pollCfg.get("cancelEndpoint");
            if (cancelEndpoint == null || cancelEndpoint.isBlank()) return false;
            try {
                String cancelUrl = mc.getApiUrl();
                if (!cancelUrl.endsWith("/")) cancelUrl += "/";
                String resolved = cancelEndpoint.replace("{{taskId}}", taskId)
                        .replace("{id}", taskId);
                if (resolved.startsWith("/")) resolved = resolved.substring(1);
                cancelUrl += resolved;

                HttpRequest.Builder cb = HttpRequest.newBuilder()
                        .uri(URI.create(cancelUrl))
                        .timeout(Duration.ofSeconds(10))
                        .DELETE();
                applyHeaders(cb, mc);
                HttpResponse<String> resp = sendWithRetry(cb.build());
                return resp.statusCode() >= 200 && resp.statusCode() < 300;
            } catch (Exception e) {
                System.err.println("[TaskHandle] 取消请求失败: " + e.getMessage());
                return false;
            }
        }
    }

    // ==================== task 模式（POST 创建 → 轮询 GET → 取结果） ====================

    /** @return TaskHandle，调用方可通过它取消任务 */
    public static TaskHandle callTaskAsync(ModelConfig mc, String jsonBody,
                                     java.util.function.Consumer<TaskResult> onSuccess,
                                     java.util.function.Consumer<Exception> onError,
                                     java.util.function.Consumer<String> onProgress) {
        TaskHandle handle = new TaskHandle();
        ThreadPoolUtil.submitVirtual(() -> {
            try {
                // Step 1: 创建任务
                System.out.println("[Task] ========== 创建任务 ==========");
                System.out.println("[Task] URL: " + mc.getApiUrl() + "/" + (mc.getEndpoint() != null ? mc.getEndpoint().replaceFirst("^/", "") : ""));
                System.out.println("[Task] 请求体: " + jsonBody);

                HttpRequest createReq = buildRequest(mc, jsonBody);
                HttpResponse<String> createResp = sendWithRetry(createReq);

                System.out.println("[Task] 响应 HTTP " + createResp.statusCode());
                System.out.println("[Task] 响应体: " + createResp.body());

                if (createResp.statusCode() < 200 || createResp.statusCode() >= 300) {
                    throw new ApiException("创建任务失败 HTTP " + createResp.statusCode() + ": "
                            + truncate(createResp.body()), createResp.statusCode());
                }

                if (handle.isCancelled()) return;

                Map<String, Object> respMap = JsonUtil.parseObject(createResp.body());

                // 取 taskId
                String taskIdPath = mc.getTaskIdPath();
                Object taskIdObj = taskIdPath != null ? JsonUtil.getByPath(respMap, taskIdPath) : null;
                if (taskIdObj == null) {
                    throw new ApiException("响应中找不到任务 ID（路径: " + taskIdPath + "）", 0);
                }
                handle.taskId = String.valueOf(taskIdObj);
                System.out.println("[Task] 提取到 taskId=" + handle.taskId + " (path=" + taskIdPath + ")");

                if (handle.isCancelled()) {
                    handle.cancel(mc); // 刚创建就被取消，立即调用 DELETE
                    return;
                }

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
                int consecutive404 = 0; // 连续 404 计数，达到阈值则认为任务不存在
                String lastReportedStatus = null; // 记录上次推送的状态，不变就不重复推送
                System.out.println("[Poll] ========== 开始轮询 taskId=" + handle.taskId + " ==========");

                while (System.currentTimeMillis() - startTime < maxWaitMs) {
                    // 检查用户取消
                    if (handle.isCancelled()) {
                        throw new ApiException("用户取消", 499);
                    }

                    Thread.sleep(intervalMs);
                    pollCount++;

                    // 再次检查取消
                    if (handle.isCancelled()) {
                        throw new ApiException("用户取消", 499);
                    }

                    String pollUrl = mc.getApiUrl();
                    if (!pollUrl.endsWith("/")) pollUrl += "/";
                    String resolvedEndpoint = endpoint.replace("{{taskId}}", handle.taskId)
                            .replace("{id}", handle.taskId);
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

                    // 每次轮询都打印请求 URL 和响应
                    System.out.println("[Poll #" + pollCount + "] " + method + " " + pollUrl
                            + " → HTTP " + pollResp.statusCode()
                            + (pollResp.body() != null && !pollResp.body().isEmpty()
                                ? " body=" + pollResp.body()
                                : " (空body)"));

                    // 404：任务尚未就绪（创建后有短暂延迟）或已被服务端清理
                    if (pollResp.statusCode() == 404) {
                        consecutive404++;
                        if (consecutive404 > 20) {
                            // 连续 21 次 404（约 60 秒），认为任务不存在
                            throw new ApiException("任务不存在（服务端已清理或 ID 无效）", 404);
                        }
                        if (onProgress != null) {
                            int finalPollCount = pollCount;
                            SwingUtilities.invokeLater(() ->
                                    onProgress.accept("等待任务就绪... (第 " + finalPollCount + " 次)"));
                        }
                        continue;
                    }
                    consecutive404 = 0; // 非 404 → 重置计数

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

                    // 只在状态变化时才推送进度（避免同状态反复刷新）
                    if (onProgress != null && status != null && !status.equals(lastReportedStatus)) {
                        lastReportedStatus = status;
                        final String cnStatus = translateStatus(status);
                        int finalPollCount1 = pollCount;
                        SwingUtilities.invokeLater(() -> onProgress.accept("状态: " + cnStatus + " (第 " + finalPollCount1 + " 次)"));
                    }

                    if (successStatus != null && successStatus.equals(status)) {
                        Object resultData = resultPath != null ? JsonUtil.getByPath(pollMap, resultPath) : pollMap;
                        final TaskResult tr = new TaskResult(resultData, pollResp.body(), handle.taskId);
                        System.out.println("[Poll] ✅ 任务完成! 耗时 " + (System.currentTimeMillis() - startTime) / 1000 + "s, 共 " + pollCount + " 次轮询");
                        if (onSuccess != null) SwingUtilities.invokeLater(() -> onSuccess.accept(tr));
                        return;
                    }

                    if (failStatus != null && failStatus.equals(status)) {
                        System.out.println("[Poll] ❌ 任务失败, status=" + status + ", body=" + pollResp.body());
                        throw new ApiException("任务失败: " + pollResp.body(), 0);
                    }
                }

                System.out.println("[Poll] ⏰ 任务超时, 已等待 " + (maxWaitMs / 1000) + "s, " + pollCount + " 次轮询");
                throw new ApiException("任务超时（已等待 " + (maxWaitMs / 1000) + " 秒，" + pollCount + " 次轮询）", 0);

            } catch (Exception e) {
                if (onError != null) SwingUtilities.invokeLater(() -> onError.accept(e));
            }
        });
        return handle;
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
