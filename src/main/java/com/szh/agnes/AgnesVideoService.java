package com.szh.agnes;

import com.szh.agnes.entity.AgnesVideoRequest;
import com.szh.agnes.entity.AgnesVideoTaskResponse;

import javax.swing.*;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.function.Consumer;

/**
 * Agnes-Video-V2.0 视频生成服务
 * <p>
 * 视频生成是异步任务式 API：
 * <ol>
 *   <li>POST /v1/videos 创建任务 → 获取 video_id</li>
 *   <li>轮询 GET /agnesapi?video_id=xxx 直到 completed/failed</li>
 *   <li>从 remixed_from_video_id 字段获取视频 URL</li>
 * </ol>
 * <p>
 * 支持能力：
 * <ul>
 *   <li>文生视频 (Text-to-Video)</li>
 *   <li>图生视频 (Image-to-Video)</li>
 *   <li>多图视频生成 (Multi-Image Video)</li>
 *   <li>关键帧动画 (Keyframe Animation)</li>
 * </ul>
 */
public class AgnesVideoService {

    public static final String BASE_URL = "https://apihub.agnes-ai.com";
    public static final String CREATE_ENDPOINT = "/v1/videos";
    public static final String POLL_ENDPOINT = "/agnesapi";
    public static final String DEFAULT_MODEL = "agnes-video-v2.0";

    /** 创建任务超时 */
    private static final Duration CREATE_TIMEOUT = Duration.ofSeconds(60);
    /** 轮询请求超时 */
    private static final Duration POLL_TIMEOUT = Duration.ofSeconds(30);
    /** 默认轮询间隔（毫秒） */
    private static final long DEFAULT_POLL_INTERVAL_MS = 5000;
    /** 默认最大等待时间 */
    private static final long DEFAULT_MAX_WAIT_MS = 30 * 60 * 1000; // 30 分钟
    /** 视频下载超时 */
    private static final Duration DOWNLOAD_TIMEOUT = Duration.ofSeconds(300);

    private final HttpClient httpClient;
    private final String apiKey;
    private final String baseUrl;

    /**
     * 创建服务实例
     * @param apiKey Agnes API 密钥
     */
    public AgnesVideoService(String apiKey) {
        this(apiKey, BASE_URL);
    }

    public AgnesVideoService(String apiKey, String baseUrl) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl != null && !baseUrl.isBlank() ? baseUrl : BASE_URL;
        this.httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(30))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    // ==================== 创建任务 ====================

    /**
     * 创建视频生成任务（同步）
     * @param request 请求参数
     * @return 任务创建响应（含 video_id / task_id）
     * @throws Exception API 调用异常
     */
    public AgnesVideoTaskResponse createTask(AgnesVideoRequest request) throws Exception {
        String jsonBody = request.toJson();
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(baseUrl + CREATE_ENDPOINT))
                .header("Authorization", "Bearer " + apiKey)
                .header("Content-Type", "application/json")
                .timeout(CREATE_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(jsonBody))
                .build();

        HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

        int status = httpResp.statusCode();
        String body = httpResp.body();

        if (status != 200) {
            throw new AgnesImageService.AgnesApiException("创建视频任务失败，HTTP " + status + ": " + body, status);
        }

        AgnesVideoTaskResponse resp = AgnesVideoTaskResponse.fromJson(body);
        if (resp.getVideoId() == null || resp.getVideoId().isBlank()) {
            throw new AgnesImageService.AgnesApiException("创建任务响应缺少 video_id", status);
        }

        return resp;
    }

    // ==================== 查询任务（推荐方式：video_id） ====================

    /**
     * 通过 video_id 查询任务状态
     * @param videoId 视频 ID
     * @return 当前任务状态
     */
    public AgnesVideoTaskResponse queryByVideoId(String videoId) throws Exception {
        String url = baseUrl + POLL_ENDPOINT + "?video_id=" + videoId;
        return doQuery(url);
    }

    /**
     * 通过 task_id 查询任务状态（兼容旧版）
     * @param taskId 任务 ID
     * @return 当前任务状态
     */
    public AgnesVideoTaskResponse queryByTaskId(String taskId) throws Exception {
        String url = baseUrl + "/v1/videos/" + taskId;
        return doQuery(url);
    }

    private AgnesVideoTaskResponse doQuery(String url) throws Exception {
        HttpRequest httpReq = HttpRequest.newBuilder()
                .uri(URI.create(url))
                .header("Authorization", "Bearer " + apiKey)
                .timeout(POLL_TIMEOUT)
                .GET()
                .build();

        HttpResponse<String> httpResp = httpClient.send(httpReq, HttpResponse.BodyHandlers.ofString());

        int status = httpResp.statusCode();
        String body = httpResp.body();

        if (status == 404) {
            throw new AgnesImageService.AgnesApiException("任务或视频不存在", 404);
        }
        if (status != 200) {
            throw new AgnesImageService.AgnesApiException("查询视频任务失败，HTTP " + status + ": " + body, status);
        }

        return AgnesVideoTaskResponse.fromJson(body);
    }

    // ==================== 轮询等待完成 ====================

    /**
     * 创建任务并轮询等待完成（同步阻塞）
     * @param request        视频生成请求
     * @param pollIntervalMs 轮询间隔（毫秒）
     * @param maxWaitMs      最大等待时间（毫秒）
     * @return 完成后的任务响应（含视频 URL）
     */
    public AgnesVideoTaskResponse createAndWait(AgnesVideoRequest request,
                                                 long pollIntervalMs,
                                                 long maxWaitMs) throws Exception {
        AgnesVideoTaskResponse createResp = createTask(request);
        String videoId = createResp.getVideoId();
        String taskId = createResp.getTaskId();

        if (pollIntervalMs <= 0) pollIntervalMs = DEFAULT_POLL_INTERVAL_MS;
        if (maxWaitMs <= 0) maxWaitMs = DEFAULT_MAX_WAIT_MS;

        long startTime = System.currentTimeMillis();
        int pollCount = 0;

        while (System.currentTimeMillis() - startTime < maxWaitMs) {
            Thread.sleep(pollIntervalMs);
            pollCount++;

            AgnesVideoTaskResponse result;
            try {
                result = queryByVideoId(videoId);
            } catch (AgnesImageService.AgnesApiException e) {
                // video_id 查询失败，尝试 task_id 兜底
                if (taskId != null) {
                    result = queryByTaskId(taskId);
                } else {
                    throw e;
                }
            }

            if (result.isCompleted()) {
                return result;
            }

            if (result.isFailed()) {
                throw new AgnesImageService.AgnesApiException(
                        "视频生成失败: " + (result.getError() != null ? result.getError() : "未知错误"),
                        0);
            }
        }

        throw new AgnesImageService.AgnesApiException(
                "视频生成超时（已等待 " + (maxWaitMs / 1000) + " 秒，" + pollCount + " 次轮询）", 0);
    }

    /**
     * 创建任务并轮询等待完成（使用默认轮询间隔和超时）
     */
    public AgnesVideoTaskResponse createAndWait(AgnesVideoRequest request) throws Exception {
        return createAndWait(request, DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_WAIT_MS);
    }

    // ==================== 异步版本 ====================

    /**
     * 异步创建视频并轮询等待完成（虚拟线程）
     * @param request   视频生成请求
     * @param onSuccess 成功回调（在 EDT 执行，参数为完成响应）
     * @param onError   失败回调（在 EDT 执行）
     */
    public void createAndWaitAsync(AgnesVideoRequest request,
                                   Consumer<AgnesVideoTaskResponse> onSuccess,
                                   Consumer<Exception> onError) {
        createAndWaitAsync(request, DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_WAIT_MS,
                null, onSuccess, onError);
    }

    /**
     * 异步创建视频并轮询（带进度回调）
     * @param request        视频生成请求
     * @param pollIntervalMs 轮询间隔
     * @param maxWaitMs      最大等待时间
     * @param onProgress     进度回调（EDT，参数：进度 0-100 / 状态描述）
     * @param onSuccess      成功回调（EDT）
     * @param onError        失败回调（EDT）
     */
    public void createAndWaitAsync(AgnesVideoRequest request,
                                   long pollIntervalMs,
                                   long maxWaitMs,
                                   Consumer<ProgressInfo> onProgress,
                                   Consumer<AgnesVideoTaskResponse> onSuccess,
                                   Consumer<Exception> onError) {
        final long actualPollInterval = pollIntervalMs > 0 ? pollIntervalMs : DEFAULT_POLL_INTERVAL_MS;
        final long actualMaxWait = maxWaitMs > 0 ? maxWaitMs : DEFAULT_MAX_WAIT_MS;

        Thread.ofVirtual().start(() -> {
            try {
                // Step 1: 创建任务
                AgnesVideoTaskResponse createResp = createTask(request);
                SwingUtilities.invokeLater(() -> {
                    if (onProgress != null) {
                        onProgress.accept(new ProgressInfo(0, "queued", createResp.getSize(),
                                createResp.getSeconds(), createResp.getVideoId()));
                    }
                });

                String videoId = createResp.getVideoId();
                String taskId = createResp.getTaskId();

                // Step 2: 轮询
                long startTime = System.currentTimeMillis();

                while (System.currentTimeMillis() - startTime < actualMaxWait) {
                    Thread.sleep(actualPollInterval);

                    AgnesVideoTaskResponse result;
                    try {
                        result = queryByVideoId(videoId);
                    } catch (AgnesImageService.AgnesApiException e) {
                        if (taskId != null) {
                            result = queryByTaskId(taskId);
                        } else {
                            throw e;
                        }
                    }

                    // 进度回调
                    int progress = result.getProgress() != null ? result.getProgress() : 0;
                    final AgnesVideoTaskResponse finalResult = result;
                    SwingUtilities.invokeLater(() -> {
                        if (onProgress != null) {
                            onProgress.accept(new ProgressInfo(progress, finalResult.getStatus(),
                                    finalResult.getSize(), finalResult.getSeconds(),
                                    finalResult.getVideoId()));
                        }
                    });

                    if (result.isCompleted()) {
                        final AgnesVideoTaskResponse finalCompleted = result;
                        SwingUtilities.invokeLater(() -> {
                            if (onSuccess != null) onSuccess.accept(finalCompleted);
                        });
                        return;
                    }

                    if (result.isFailed()) {
                        throw new AgnesImageService.AgnesApiException(
                                "视频生成失败: " + (result.getError() != null ? result.getError() : "未知错误"),
                                0);
                    }
                }

                throw new AgnesImageService.AgnesApiException(
                        "视频生成超时（已等待 " + (actualMaxWait / 1000) + " 秒）", 0);

            } catch (Exception e) {
                if (onError != null) {
                    SwingUtilities.invokeLater(() -> onError.accept(e));
                }
            }
        });
    }

    // ==================== 视频下载 ====================

    /**
     * 下载视频文件为字节数组
     * @param videoUrl 视频 URL
     * @return 视频二进制数据
     */
    public byte[] downloadVideo(String videoUrl) throws Exception {
        HttpRequest req = HttpRequest.newBuilder(URI.create(videoUrl))
                .timeout(DOWNLOAD_TIMEOUT)
                .GET()
                .build();
        HttpResponse<byte[]> resp = httpClient.send(req, HttpResponse.BodyHandlers.ofByteArray());
        if (resp.statusCode() != 200) {
            throw new AgnesImageService.AgnesApiException(
                    "下载视频失败，HTTP " + resp.statusCode(), resp.statusCode());
        }
        return resp.body();
    }

    /**
     * 异步下载视频
     */
    public void downloadVideoAsync(String videoUrl,
                                   Consumer<byte[]> onSuccess,
                                   Consumer<Exception> onError) {
        Thread.ofVirtual().start(() -> {
            try {
                byte[] data = downloadVideo(videoUrl);
                if (onSuccess != null) {
                    SwingUtilities.invokeLater(() -> onSuccess.accept(data));
                }
            } catch (Exception e) {
                if (onError != null) {
                    SwingUtilities.invokeLater(() -> onError.accept(e));
                }
            }
        });
    }

    // ==================== 便捷方法：文生视频 ====================

    /**
     * 文生视频 - 异步生成
     * @param prompt   文本提示词
     * @param onSuccess 成功回调（返回含视频 URL 的响应）
     * @param onError   失败回调
     */
    public void textToVideo(String prompt,
                            Consumer<AgnesVideoTaskResponse> onSuccess,
                            Consumer<Exception> onError) {
        AgnesVideoRequest req = AgnesVideoRequest.textToVideo(prompt);
        createAndWaitAsync(req, onSuccess, onError);
    }

    /**
     * 文生视频 - 异步生成（带进度）
     */
    public void textToVideo(String prompt,
                            int width, int height,
                            int numFrames, double frameRate,
                            Consumer<ProgressInfo> onProgress,
                            Consumer<AgnesVideoTaskResponse> onSuccess,
                            Consumer<Exception> onError) {
        AgnesVideoRequest req = AgnesVideoRequest.textToVideo(prompt)
                .size(width, height)
                .numFrames(numFrames)
                .frameRate(frameRate);
        createAndWaitAsync(req, DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_WAIT_MS,
                onProgress, onSuccess, onError);
    }

    // ==================== 便捷方法：图生视频 ====================

    /**
     * 图生视频 - 将单张图片动画化
     */
    public void imageToVideo(String prompt, String imageUrl,
                             Consumer<AgnesVideoTaskResponse> onSuccess,
                             Consumer<Exception> onError) {
        AgnesVideoRequest req = AgnesVideoRequest.imageToVideo(prompt, imageUrl);
        createAndWaitAsync(req, onSuccess, onError);
    }

    /**
     * 图生视频（带参数 + 进度）
     */
    public void imageToVideo(String prompt, String imageUrl,
                             int numFrames, double frameRate,
                             Consumer<ProgressInfo> onProgress,
                             Consumer<AgnesVideoTaskResponse> onSuccess,
                             Consumer<Exception> onError) {
        AgnesVideoRequest req = AgnesVideoRequest.imageToVideo(prompt, imageUrl)
                .numFrames(numFrames)
                .frameRate(frameRate);
        createAndWaitAsync(req, DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_WAIT_MS,
                onProgress, onSuccess, onError);
    }

    // ==================== 便捷方法：多图视频 ====================

    /**
     * 多图视频生成
     */
    public void multiImageToVideo(String prompt, java.util.List<String> imageUrls,
                                  Consumer<AgnesVideoTaskResponse> onSuccess,
                                  Consumer<Exception> onError) {
        AgnesVideoRequest req = AgnesVideoRequest.textToVideo(prompt)
                .extraImages(imageUrls);
        createAndWaitAsync(req, onSuccess, onError);
    }

    // ==================== 便捷方法：关键帧动画 ====================

    /**
     * 关键帧动画
     */
    public void keyframesAnimation(String prompt, java.util.List<String> keyframeUrls,
                                   Consumer<AgnesVideoTaskResponse> onSuccess,
                                   Consumer<Exception> onError) {
        AgnesVideoRequest req = AgnesVideoRequest.textToVideo(prompt)
                .extraImages(keyframeUrls)
                .keyframesMode();
        createAndWaitAsync(req, onSuccess, onError);
    }

    /**
     * 关键帧动画（带参数 + 进度）
     */
    public void keyframesAnimation(String prompt, java.util.List<String> keyframeUrls,
                                   int numFrames, double frameRate,
                                   Consumer<ProgressInfo> onProgress,
                                   Consumer<AgnesVideoTaskResponse> onSuccess,
                                   Consumer<Exception> onError) {
        AgnesVideoRequest req = AgnesVideoRequest.textToVideo(prompt)
                .extraImages(keyframeUrls)
                .keyframesMode()
                .numFrames(numFrames)
                .frameRate(frameRate);
        createAndWaitAsync(req, DEFAULT_POLL_INTERVAL_MS, DEFAULT_MAX_WAIT_MS,
                onProgress, onSuccess, onError);
    }

    // ==================== 进度信息内部类 ====================

    /**
     * 视频生成进度信息
     */
    public static class ProgressInfo {
        /** 进度 0-100 */
        public final int progress;
        /** 状态：queued / in_progress / completed / failed */
        public final String status;
        /** 视频分辨率 */
        public final String size;
        /** 视频时长（秒） */
        public final String seconds;
        /** 视频 ID */
        public final String videoId;

        public ProgressInfo(int progress, String status, String size, String seconds, String videoId) {
            this.progress = progress;
            this.status = status;
            this.size = size;
            this.seconds = seconds;
            this.videoId = videoId;
        }

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

        @Override
        public String toString() {
            return "ProgressInfo{progress=" + progress + ", status='" + getStatusText() + '\'' +
                    ", size='" + size + "', seconds='" + seconds + "'}";
        }
    }
}
