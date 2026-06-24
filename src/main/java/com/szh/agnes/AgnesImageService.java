package com.szh.agnes;

import com.szh.agnes.entity.AgnesImageRequest;
import com.szh.agnes.entity.AgnesImageResponse;
import com.szh.utils.ThreadPoolUtil;

import javax.swing.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.Consumer;

/**
 * Agnes-Image-2.0-Flash 图片生成服务
 * <p>
 * 支持能力：
 * <ul>
 *   <li>文生图 (Text-to-Image)</li>
 *   <li>图生图 (Image-to-Image)</li>
 *   <li>多图合成 (Multi-Image Input)</li>
 *   <li>URL 输出 / Base64 输出</li>
 * </ul>
 */
public class AgnesImageService {

    public static final String BASE_URL = "https://apihub.agnes-ai.com";
    public static final String ENDPOINT = "/v1/images/generations";
    public static final String DEFAULT_MODEL = "agnes-image-2.0-flash";

    /** 默认超时：图片生成可能耗时数秒到数十秒，建议 180s */
    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(180);

    /** 网络瞬时错误最大重试次数 */
    private static final int MAX_RETRIES = 2;

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;

    /**
     * 创建服务实例
     * @param apiKey Agnes API 密钥
     */
    public AgnesImageService(String apiKey) {
        this(apiKey, BASE_URL);
    }

    public AgnesImageService(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : BASE_URL;
        this.httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_2)
                .connectTimeout(Duration.ofSeconds(45))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ==================== 同步调用 ====================

    /**
     * 同步生成图片
     * @param request 请求参数
     * @return 响应结果
     */
    public AgnesImageResponse generate(AgnesImageRequest request) throws Exception {
        String jsonBody = request.toJson();
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(DEFAULT_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> httpResp = sendWithRetry(httpReq);
        return parseResponse(httpResp);
    }

    /** 带重试的 send：处理 Connection reset / header parser 等瞬时网络错误 */
    private HttpResponse<String> sendWithRetry(HttpRequest request) throws Exception {
        Exception lastEx = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            } catch (java.io.IOException e) {
                lastEx = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (attempt < MAX_RETRIES && (msg.contains("reset") || msg.contains("header parser") || msg.contains("RST_STREAM"))) {
                    long delay = (long) Math.pow(2, attempt + 1) * 1000; // 2s, 4s
                    System.out.println("[AgnesImage] 网络瞬时错误，将在 " + (delay / 1000) + " 秒后重试 ("
                            + (attempt + 1) + "/" + MAX_RETRIES + "): " + msg);
                    Thread.sleep(delay);
                    continue;
                }
                throw e;
            }
        }
        throw lastEx;
    }

    /** 带重试的 send（byte[] 响应，用于下载） */
    private HttpResponse<byte[]> sendWithRetryBytes(HttpRequest request) throws Exception {
        Exception lastEx = null;
        for (int attempt = 0; attempt <= MAX_RETRIES; attempt++) {
            try {
                return httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
            } catch (java.io.IOException e) {
                lastEx = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (attempt < MAX_RETRIES && (msg.contains("reset") || msg.contains("header parser") || msg.contains("RST_STREAM"))) {
                    long delay = (long) Math.pow(2, attempt + 1) * 1000;
                    System.out.println("[AgnesImage] 下载网络瞬时错误，将在 " + (delay / 1000) + " 秒后重试 ("
                            + (attempt + 1) + "/" + MAX_RETRIES + "): " + msg);
                    Thread.sleep(delay);
                    continue;
                }
                throw e;
            }
        }
        throw lastEx;
    }

    private AgnesImageResponse parseResponse(HttpResponse<String> httpResp) throws Exception {
        int status = httpResp.statusCode();
        String body = httpResp.body();

        if (status != 200) {
            throw new AgnesApiException("API 调用失败：" +
                    AgnesVideoService.parseApiErrorBody(body, status), status);
        }

        AgnesImageResponse resp = AgnesImageResponse.fromJson(body);
        if (!resp.isSuccess()) {
            throw new AgnesApiException("图片生成失败，响应为空或格式异常", 0);
        }
        return resp;
    }

    /**
     * 同步生成图片并下载第一张图片为字节数组
     * @return 图片二进制数据，或 null（生成失败）
     */
    public byte[] generateAndDownload(AgnesImageRequest request) throws Exception {
        AgnesImageResponse resp = generate(request);
        return downloadFromResponse(resp);
    }

    // ==================== 异步调用 ====================

    /**
     * 异步生成图片（虚拟线程）
     * @param request  请求参数
     * @param onSuccess 成功回调（在 EDT 执行）
     * @param onError   失败回调（在 EDT 执行）
     */
    public void generateAsync(AgnesImageRequest request,
                              Consumer<AgnesImageResponse> onSuccess,
                              Consumer<Exception> onError) {
        ThreadPoolUtil.submitVirtual(() -> {
            try {
                AgnesImageResponse resp = generate(request);
                if (onSuccess != null) {
                    SwingUtilities.invokeLater(() -> onSuccess.accept(resp));
                }
            } catch (Exception e) {
                if (onError != null) {
                    SwingUtilities.invokeLater(() -> onError.accept(e));
                }
            }
        });
    }

    /**
     * 异步生成图片并下载为字节数组后回调（虚拟线程）
     * @param request  请求参数
     * @param onImageReady 图片就绪回调（在 EDT 执行，参数为原始图片字节数组或 Base64 字符串）
     * @param onError      失败回调（在 EDT 执行）
     */
    public void generateImageBytesAsync(AgnesImageRequest request,
                                        Consumer<byte[]> onImageReady,
                                        Consumer<Exception> onError) {
        ThreadPoolUtil.submitVirtual(() -> {
            try {
                byte[] imageBytes = generateAndDownload(request);
                if (imageBytes != null && onImageReady != null) {
                    SwingUtilities.invokeLater(() -> onImageReady.accept(imageBytes));
                } else if (onError != null) {
                    SwingUtilities.invokeLater(() -> onError.accept(
                            new AgnesApiException("生成图片为空", 0)));
                }
            } catch (Exception e) {
                if (onError != null) {
                    SwingUtilities.invokeLater(() -> onError.accept(e));
                }
            }
        });
    }

    // ==================== 便捷方法：文生图 ====================

    /**
     * 文生图 - 快速生成
     * @param prompt 文本提示词
     * @param size   输出尺寸，如 "1024x768"
     * @param onSuccess 成功回调（在 EDT 执行）
     * @param onError   失败回调（在 EDT 执行）
     */
    public void textToImage(String prompt, String size,
                            Consumer<AgnesImageResponse> onSuccess,
                            Consumer<Exception> onError) {
        AgnesImageRequest req = AgnesImageRequest.textToImage(prompt, size)
                .responseFormat("url");
        generateAsync(req, onSuccess, onError);
    }

    /**
     * 文生图 - 返回图片字节
     */
    public void textToImageBytes(String prompt, String size,
                                 Consumer<byte[]> onImageReady,
                                 Consumer<Exception> onError) {
        AgnesImageRequest req = AgnesImageRequest.textToImage(prompt, size)
                .returnBase64(true);
        generateImageBytesAsync(req, onImageReady, onError);
    }

    // ==================== 便捷方法：图生图 ====================

    /**
     * 图生图 - 使用公网 URL 输入
     * @param prompt       编辑提示词
     * @param size         输出尺寸
     * @param inputImageUrls 输入图片 URL 列表
     */
    public void imageToImage(String prompt, String size, List<String> inputImageUrls,
                             Consumer<AgnesImageResponse> onSuccess,
                             Consumer<Exception> onError) {
        AgnesImageRequest req = AgnesImageRequest.imageToImage(prompt, size, inputImageUrls)
                .responseFormat("url");
        generateAsync(req, onSuccess, onError);
    }

    /**
     * 多图合成
     */
    public void multiImageSynthesis(String prompt, String size, List<String> inputImageUrls,
                                    Consumer<AgnesImageResponse> onSuccess,
                                    Consumer<Exception> onError) {
        imageToImage(prompt, size, inputImageUrls, onSuccess, onError);
    }

    // ==================== 辅助方法 ====================

    /**
     * 从响应中下载图片（URL 模式）或解码（Base64 模式）
     */
    private byte[] downloadFromResponse(AgnesImageResponse resp) throws Exception {
        // 优先 URL
        String url = resp.firstImageUrl();
        if (url != null && !url.isBlank()) {
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(60))
                    .GET()
                    .build();
            HttpResponse<byte[]> imgResp = sendWithRetryBytes(req);
            if (imgResp.statusCode() == 200) {
                return imgResp.body();
            }
            throw new AgnesApiException("下载图片失败，HTTP " + imgResp.statusCode(), imgResp.statusCode());
        }

        // 回退 Base64
        String b64 = resp.firstImageBase64();
        if (b64 != null && !b64.isBlank()) {
            return Base64.getDecoder().decode(b64);
        }

        return null;
    }

    // ==================== 静态工具方法 ====================

    /**
     * 从 Base64 字符串解码为字节数组
     */
    public static byte[] base64ToBytes(String b64) {
        return Base64.getDecoder().decode(b64);
    }

    /**
     * 从 URL 下载图片（同步）
     */
    public static byte[] downloadImage(String imageUrl) throws Exception {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
        HttpRequest req = HttpRequest.newBuilder(URI.create(imageUrl))
                .timeout(Duration.ofSeconds(120))
                .GET()
                .build();

        Exception lastEx = null;
        for (int attempt = 0; attempt <= 2; attempt++) {
            try {
                HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());
                if (resp.statusCode() != 200) {
                    throw new AgnesApiException("下载图片失败，HTTP " + resp.statusCode(), resp.statusCode());
                }
                return resp.body();
            } catch (java.io.IOException e) {
                lastEx = e;
                String msg = e.getMessage() != null ? e.getMessage() : "";
                if (attempt < 2 && (msg.contains("reset") || msg.contains("header parser") || msg.contains("RST_STREAM"))) {
                    long delay = (long) Math.pow(2, attempt + 1) * 1000;
                    Thread.sleep(delay);
                    continue;
                }
                throw e;
            }
        }
        throw lastEx;
    }

    // ==================== 异常类 ====================

    /**
     * Agnes API 异常
     */
    public static class AgnesApiException extends Exception {
        private final int statusCode;

        public AgnesApiException(String message, int statusCode) {
            super(message);
            this.statusCode = statusCode;
        }

        public int getStatusCode() {
            return statusCode;
        }

        @Override
        public String toString() {
            return "AgnesApiException{status=" + statusCode + ", message=" + getMessage() + "}";
        }
    }
}
