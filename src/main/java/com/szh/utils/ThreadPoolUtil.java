package com.szh.utils;

import java.util.concurrent.*;

/**
 * 统一线程池工具类，防止 JNI/WMI/Process 等原生调用 pin 住虚拟线程 carrier。
 *
 * <h3>两条池</h3>
 * <ul>
 *   <li><b>虚拟线程池</b> — 用于纯 I/O 操作（HTTP、SSH、NIO 等），不涉及 JNI/原生调用</li>
 *   <li><b>平台线程池</b> — 用于 OSHI、SerialPort、ProcessBuilder、FFmpeg 等涉及 JNI/原生调用的场景</li>
 * </ul>
 *
 * <h3>用法</h3>
 * <pre>{@code
 * // 纯 I/O（不会 pin 住 carrier）
 * ThreadPoolUtil.submitVirtual(() -> httpClient.send(request));
 *
 * // JNI/原生调用
 * ThreadPoolUtil.submitPlatform(() -> someNativeCall());
 *
 * // 需要可取消
 * Future<?> future = ThreadPoolUtil.submitPlatform(() -> longRunningTask());
 * future.cancel(true);
 *
 * // 程序退出时
 * ThreadPoolUtil.shutdown();
 * }</pre>
 */
public final class ThreadPoolUtil {

    /** 虚拟线程池 — 每个任务一个虚拟线程，适合大量 I/O 短任务 */
    private static volatile ExecutorService virtualPool;

    /** 平台线程池 — 固定大小，避免 JNI 调用耗尽系统线程 */
    private static volatile ExecutorService platformPool;

    private static volatile boolean shutdown = false;

    private ThreadPoolUtil() {}

    // ==================== 初始化 ====================

    /** 初始化两个池（应在应用启动时调用一次） */
    public static synchronized void init() {
        if (virtualPool == null || virtualPool.isShutdown()) {
            virtualPool = Executors.newVirtualThreadPerTaskExecutor();
        }
        if (platformPool == null || platformPool.isShutdown()) {
            int cores = Runtime.getRuntime().availableProcessors();
            int poolSize = Math.max(cores * 2, 4); // 至少 4，最多 cpu*2
            platformPool = new ThreadPoolExecutor(
                    poolSize, poolSize,
                    60L, TimeUnit.SECONDS,
                    new LinkedBlockingQueue<>(256),
                    r -> {
                        Thread t = new Thread(r);
                        t.setName("platform-pool-" + t.threadId());
                        t.setDaemon(true);
                        return t;
                    });
        }
        shutdown = false;
    }

    // ==================== 提交任务 ====================

    /** 提交到虚拟线程池（纯 I/O） */
    public static Future<?> submitVirtual(Runnable task) {
        return pool(virtualPool).submit(task);
    }

    /** 提交到虚拟线程池（纯 I/O，带回调） */
    public static <T> Future<T> submitVirtual(Callable<T> task) {
        return pool(virtualPool).submit(task);
    }

    /** 提交到平台线程池（JNI/原生调用） */
    public static Future<?> submitPlatform(Runnable task) {
        return pool(platformPool).submit(task);
    }

    /** 提交到平台线程池（JNI/原生调用，带返回值） */
    public static <T> Future<T> submitPlatform(Callable<T> task) {
        return pool(platformPool).submit(task);
    }

    /**
     * 延迟执行（用于替代 {@code Thread.ofVirtual().start(() -> { Thread.sleep(...); ... })}）.
     * 如果涉及原生调用，用 submitPlatformDelay；纯 I/O 用 submitVirtualDelay。
     */
    public static Future<?> submitVirtualDelay(long delayMs, Runnable task) {
        return pool(virtualPool).submit(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            task.run();
        });
    }

    /** 延迟执行 - 平台线程池版本 */
    public static Future<?> submitPlatformDelay(long delayMs, Runnable task) {
        return pool(platformPool).submit(() -> {
            try { Thread.sleep(delayMs); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            task.run();
        });
    }

    // ==================== 关闭 ====================

    /** 优雅关闭所有线程池 */
    public static synchronized void shutdown() {
        shutdown = true;
        shutdownPool(virtualPool);
        shutdownPool(platformPool);
    }

    private static void shutdownPool(ExecutorService pool) {
        if (pool == null) return;
        pool.shutdown();
        try {
            if (!pool.awaitTermination(3, TimeUnit.SECONDS)) {
                pool.shutdownNow();
            }
        } catch (InterruptedException e) {
            pool.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

    private static ExecutorService pool(ExecutorService p) {
        if (shutdown) throw new RejectedExecutionException("ThreadPoolUtil 已关闭");
        if (p != null && !p.isShutdown()) return p;
        synchronized (ThreadPoolUtil.class) {
            init();
        }
        // 重新从 volatile 字段取对应池
        if (p == virtualPool || p == null) return virtualPool;
        return platformPool;
    }
}
