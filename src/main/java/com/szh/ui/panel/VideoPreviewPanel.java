package com.szh.ui.panel;

import com.szh.Start;
import org.bytedeco.javacv.*;
import org.bytedeco.javacv.Frame;
import org.bytedeco.opencv.opencv_core.*;

import javax.sound.sampled.*;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.nio.Buffer;
import java.nio.ShortBuffer;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

/**
 * 视频预览面板，内嵌在组面板中，实时拉 RTSP 流显示，支持音频开关
 */
public class VideoPreviewPanel extends JPanel {

    private final JPanel canvas;
    private JCheckBox audioToggle;
    private Thread grabThread;
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicReference<FFmpegFrameGrabber> grabberRef = new AtomicReference<>(null);
    private final AtomicReference<BufferedImage> currentFrame = new AtomicReference<>(null);

    private Consumer<String> logCallback;
    private String placeholderText = "未连接视频流";

    // ==================== 音频播放相关 ====================
    private final boolean audioSupported;
    private volatile boolean audioEnabled = false;
    private SourceDataLine audioLine;
    private Thread audioPlayThread;
    private final ConcurrentLinkedDeque<ShortBuffer> audioQueue = new ConcurrentLinkedDeque<>();
    private static final int AUDIO_QUEUE_MAX = 100;

    // ==================== 节流 repaint ====================
    /** 上一次 invokeLater 触发 repaint 的时间，抓帧线程读写 */
    private long lastInvokeNanos;

    // ==================== 图像格式转换缓存 ====================
    /** 预分配的 TYPE_INT_RGB BufferedImage，避免每帧 new */
    private BufferedImage compatImage;
    private int compatW = -1, compatH = -1;

    private static boolean probeAudioSupport() {
        try {
            for (int rate : new int[]{44100, 48000, 22050}) {
                AudioFormat af = new AudioFormat(
                        rate, 16, 2, true, false);
                DataLine.Info info = new DataLine.Info(SourceDataLine.class, af);
                if (AudioSystem.isLineSupported(info)) return true;
            }
        } catch (Exception ignored) {}
        return false;
    }

    public VideoPreviewPanel(String title) {
        audioSupported = probeAudioSupport();

        setLayout(new BorderLayout());
        setBackground(new Color(0x2B2B2B));
        setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                title, TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 13)));

        canvas = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                BufferedImage img = currentFrame.get();
                if (img != null) {
                    // compatImage 已是 TYPE_INT_RGB，与屏幕格式一致，blit 极快
                    int w = getWidth(), h = getHeight();
                    if (img.getWidth() == w && img.getHeight() == h) {
                        g.drawImage(img, 0, 0, null);
                    } else {
                        g.drawImage(img, 0, 0, w, h, null);
                    }
                } else if (placeholderText != null && !placeholderText.isEmpty()) {
                    g.setColor(new Color(0x888888));
                    g.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
                    FontMetrics fm = g.getFontMetrics();
                    String[] lines = placeholderText.split("\n");
                    int lh = fm.getHeight();
                    int totalH = lh * lines.length;
                    int startY = (getHeight() - totalH) / 2 + fm.getAscent();
                    for (int i = 0; i < lines.length; i++) {
                        g.drawString(lines[i],
                                (getWidth() - fm.stringWidth(lines[i])) / 2,
                                startY + i * lh);
                    }
                }
            }
        };
        canvas.setBackground(new Color(0x2B2B2B));
        canvas.setOpaque(true);
        add(canvas, BorderLayout.CENTER);

        JPanel controlBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        controlBar.setBackground(new Color(0x2B2B2B));
        if (audioSupported) {
            audioToggle = new JCheckBox("播放声音");
            audioToggle.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            audioToggle.setForeground(new Color(0xCCCCCC));
            audioToggle.setBackground(new Color(0x2B2B2B));
            audioToggle.setToolTipText("启用后实时播放拉流中的音频");
            audioToggle.addActionListener(unused -> audioEnabled = audioToggle.isSelected());
            controlBar.add(audioToggle);
        } else {
            JLabel label = new JLabel("当前环境不支持音频");
            label.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            label.setForeground(new Color(0xAA6666));
            controlBar.add(label);
        }
        add(controlBar, BorderLayout.SOUTH);

        setPreferredSize(new Dimension(480, 360));
        setMinimumSize(new Dimension(320, 240));
    }

    public void setLogCallback(Consumer<String> callback) {
        this.logCallback = callback;
    }

    private void log(String msg) {
        if (logCallback != null) {
            SwingUtilities.invokeLater(() -> logCallback.accept(msg));
        }
    }

    /**
     * 将任意 BufferedImage 转为 TYPE_INT_RGB，与屏幕格式一致，EDT 上 drawImage 极快。
     * 在抓帧线程调用，使用 Graphics2D 硬件加速管线而非逐像素 getRGB/setRGB。
     */
    private BufferedImage toScreenCompatible(BufferedImage src) {
        if (src == null) return null;
        int w = src.getWidth(), h = src.getHeight();

        // 已是 TYPE_INT_RGB 直接返回
        if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;

        // 尺寸变了才重新分配
        if (compatW != w || compatH != h) {
            compatImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            compatW = w;
            compatH = h;
        }

        Graphics2D g2d = compatImage.createGraphics();
        try {
            g2d.drawImage(src, 0, 0, null);
        } finally {
            g2d.dispose();
        }
        return compatImage;
    }

    // ==================== 拉流 ====================

    public void startPlayback(String rtspUrl) {
        stopPlayback();
        running.set(true);

        SwingUtilities.invokeLater(() -> {
            currentFrame.set(null);
            placeholderText = "";
            canvas.repaint();
        });

        // 用传统 Thread 而非虚拟线程，避免 JNI/原生调用时 carrier 线程 pin 住
        grabThread = new Thread(() -> {
            FFmpegFrameGrabber localGrabber = null;
            boolean audioInited = false;
            try {
                Thread.sleep(300);

                localGrabber = new FFmpegFrameGrabber(rtspUrl);
                localGrabber.setOption("rtsp_transport", "tcp");
                localGrabber.setOption("stimeout", "5000000");
                grabberRef.set(localGrabber);
                localGrabber.start();
                log("RTSP 流拉取成功: " + rtspUrl);

                audioInited = initAudio(localGrabber);

                Java2DFrameConverter converter = new Java2DFrameConverter();
                OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

                try {
                    while (running.get()) {
                        Frame frame = localGrabber.grab();
                        if (frame == null) {
                            if (!running.get()) break;
                            Thread.sleep(5);
                            continue;
                        }

                        // 音频帧
                        if (frame.samples != null) {
                            processAudioFrame(frame, audioInited);
                            continue;
                        }

                        // 视频帧
                        BufferedImage img = null;
                        Mat mat = matConverter.convert(frame);
                        if (mat != null) {
                            int cw = canvas.getWidth();
                            int ch = canvas.getHeight();
                            if (cw > 0 && ch > 0) {
                                Mat resizedMat = new Mat();
                                Start.resizeCv(mat, resizedMat, new Size(cw, ch));
                                Frame rf = matConverter.convert(resizedMat);
                                img = converter.convert(rf);
                                resizedMat.release();
                            } else {
                                img = converter.convert(frame);
                            }
                            mat.release();
                        } else {
                            img = converter.convert(frame);
                        }

                        if (img != null) {
                            // 转为 TYPE_INT_RGB，EDT 渲染零开销
                            img = toScreenCompatible(img);
                            currentFrame.set(img);
                            // 节流：距上次 invokeLater 超过 33ms 才触发一次 repaint
                            scheduleRepaint();
                        }
                    }
                } finally {
                    try { converter.close(); } catch (Exception ignored) {}
                    try { matConverter.close(); } catch (Exception ignored) {}
                }
            } catch (Exception e) {
                SwingUtilities.invokeLater(() -> {
                    currentFrame.set(null);
                    placeholderText = "连接失败\n" + e.getMessage();
                    canvas.repaint();
                });
            } finally {
                disposeAudio();
                if (localGrabber != null) {
                    try { localGrabber.stop(); } catch (Exception ignored) {}
                    try { localGrabber.release(); } catch (Exception ignored) {}
                }
                grabberRef.compareAndSet(localGrabber, null);
            }
        }, "video-grabber");
        grabThread.setDaemon(true);
        grabThread.start();
    }

    /** 节流：距上次 invokeLater 超过 33ms 才触发一次 repaint，~30fps 上限 */
    private void scheduleRepaint() {
        long now = System.nanoTime();
        long last = lastInvokeNanos;
        if (now - last >= 33_000_000L) {
            lastInvokeNanos = now;
            SwingUtilities.invokeLater(canvas::repaint);
        }
    }

    private void processAudioFrame(Frame frame, boolean audioInited) {
        if (!audioInited || !audioEnabled) return;
        Buffer[] bufs = frame.samples;
        if (bufs == null || bufs.length == 0) return;

        int channels = bufs.length;
        ShortBuffer first = (ShortBuffer) bufs[0];
        int spc = first.remaining();
        if (spc <= 0) return;

        ShortBuffer combined;
        if (channels == 1) {
            combined = ShortBuffer.allocate(spc);
            combined.put(first);
        } else {
            combined = ShortBuffer.allocate(spc * channels);
            short[][] chData = new short[channels][];
            for (int ch = 0; ch < channels; ch++) {
                ShortBuffer sb = (ShortBuffer) bufs[ch];
                chData[ch] = new short[spc];
                sb.duplicate().get(chData[ch]);
            }
            for (int i = 0; i < spc; i++) {
                for (int ch = 0; ch < channels; ch++) {
                    combined.put(chData[ch][i]);
                }
            }
        }
        combined.flip();

        while (audioQueue.size() >= AUDIO_QUEUE_MAX) {
            audioQueue.pollFirst();
        }
        audioQueue.addLast(combined);
    }

    // ==================== 音频 ====================

    private boolean initAudio(FFmpegFrameGrabber grabber) {
        if (!audioSupported) return false;
        try {
            int sampleRate = grabber.getSampleRate();
            int channels = grabber.getAudioChannels();
            if (sampleRate <= 0 || channels <= 0) return false;

            AudioFormat af = new AudioFormat(
                    sampleRate, 16, channels, true, false);
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, af);
            if (!AudioSystem.isLineSupported(info)) {
                log("音频: 不支持该格式 (sr=" + sampleRate + " ch=" + channels + ")");
                return false;
            }
            audioLine = (SourceDataLine) AudioSystem.getLine(info);
            audioLine.open(af, sampleRate * channels * 2 / 2);
            audioLine.start();

            audioPlayThread = new Thread(() -> {
                byte[] buf = new byte[sampleRate * channels * 2 / 50];
                while (running.get()) {
                    ShortBuffer sb = audioQueue.pollFirst();
                    if (sb == null) {
                        try { Thread.sleep(2); } catch (InterruptedException e) { break; }
                        continue;
                    }
                    short[] samples = new short[sb.remaining()];
                    sb.get(samples);
                    int needed = samples.length * 2;
                    if (buf.length < needed) buf = new byte[needed];
                    for (int i = 0, j = 0; i < samples.length; i++) {
                        short v = samples[i];
                        buf[j++] = (byte) (v & 0xFF);
                        buf[j++] = (byte) ((v >> 8) & 0xFF);
                    }
                    if (audioEnabled && audioLine != null && audioLine.isOpen()) {
                        audioLine.write(buf, 0, needed);
                    }
                }
            }, "audio-playback");
            audioPlayThread.setDaemon(true);
            audioPlayThread.start();

            log("音频: 已初始化 " + sampleRate + "Hz " + channels + "ch");
            return true;
        } catch (Exception e) {
            log("音频: 初始化失败 - " + e.getMessage());
            return false;
        }
    }

    private void disposeAudio() {
        if (audioPlayThread != null) {
            audioPlayThread.interrupt();
            audioPlayThread = null;
        }
        if (audioLine != null) {
            try { audioLine.stop(); } catch (Exception ignored) {}
            try { audioLine.flush(); } catch (Exception ignored) {}
            try { audioLine.close(); } catch (Exception ignored) {}
            audioLine = null;
        }
        audioQueue.clear();
    }

    // ==================== 停止 ====================

    public void stopPlayback() {
        running.set(false);

        Thread oldThread = grabThread;
        FFmpegFrameGrabber oldGrabber = grabberRef.getAndSet(null);
        grabThread = null;

        if (oldThread != null) {
            oldThread.interrupt();
        } else if (oldGrabber != null) {
            try { oldGrabber.stop(); } catch (Exception ignored) {}
            try { oldGrabber.release(); } catch (Exception ignored) {}
        }

        disposeAudio();
        audioEnabled = false;
        if (audioToggle != null) {
            SwingUtilities.invokeLater(() -> audioToggle.setSelected(false));
        }

        SwingUtilities.invokeLater(() -> {
            currentFrame.set(null);
            placeholderText = "未连接视频流";
            canvas.repaint();
        });
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        stopPlayback();
    }
}
