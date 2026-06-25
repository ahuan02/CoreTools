package com.szh;

import com.szh.ui.MainFrame;
import com.szh.ui.MessageDialog;
import com.szh.utils.ThreadPoolUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Objects;
import java.util.stream.Stream;

import static org.bytedeco.opencv.global.opencv_imgproc.resize;

public class Start {

    private static final Logger logger;

    static {
        // ===== 最早初始化：清理旧日志，设置日志目录（和 app_config.properties 同级） =====
        Path logDir = Paths.get(System.getProperty("user.dir"), "logs");
        try {
            // 清理上次启动的所有日志文件（确保每次启动日志干净）
            if (Files.exists(logDir)) {
                try (Stream<Path> files = Files.list(logDir)) {
                    files.forEach(f -> {
                        try { Files.delete(f); } catch (IOException ignored) {}
                    });
                }
            }
            Files.createDirectories(logDir);
        } catch (IOException e) {
            System.err.println("初始化日志目录失败: " + e.getMessage());
        }

        // 注入目录给 log4j2.xml
        System.setProperty("app.log.dir", logDir.toAbsolutePath().toString());

        // 现在初始化 Logger（log4j2 会读取上面的 sys:app.log.dir）
        logger = LogManager.getLogger(Start.class);
        logger.info("========== CoreTools 启动 (日志目录: {}) ==========", logDir.toAbsolutePath());
    }

    public static void main(String[] args) {
        // 兜底全局未捕获异常
        Thread.setDefaultUncaughtExceptionHandler((t, e) -> {
            logger.fatal("未捕获异常 thread={}: {}", t.getName(), e.getMessage(), e);
            SwingUtilities.invokeLater(() ->
                    MessageDialog.error("程序发生严重错误: " + e.getMessage()));
        });

        try {
            // 初始化全局线程池（必须在任何模块创建之前，避免 JNI 原生调用 pin 住虚拟线程）
            ThreadPoolUtil.init();
            logger.debug("线程池初始化完成");

            Runtime.getRuntime().addShutdownHook(new Thread(() -> {
                logger.info("CoreTools 正在退出...");
                ThreadPoolUtil.shutdown();
            }));

            // 第一步：只构建框架外壳（无面板），窗口秒开
            MainFrame frame = new MainFrame();
            logger.debug("主窗口框架构建完成");

            // 设置程序图标
            try {
                BufferedImage icon = ImageIO.read(
                        Objects.requireNonNull(Start.class.getResourceAsStream("/icon.png")));
                frame.setIconImage(icon);
            } catch (IOException | IllegalArgumentException e) {
                logger.warn("加载程序图标失败: {}", e.getMessage());
            }

            final int totalTabs = frame.getTotalTabs();
            final int[] alpha = {255};
            final int[] loadedCount = {0};

            // ===== 玻璃幕布：背景 + 加载进度条（渐变色圆角） =====
            final Font fontTitle = new Font("Microsoft YaHei", Font.BOLD, 52);
            final Font fontText = new Font("Microsoft YaHei", Font.PLAIN, 16);
            final Color C_BG    = new Color(0x2B, 0x2B, 0x2B);
            final Color C_GRAD1 = new Color(0x66, 0x7E, 0xEA);
            final Color C_GRAD2 = new Color(0x76, 0x4B, 0xA2);
            final Color C_BAR_BG = new Color(0x44, 0x44, 0x44);
            final Color C_BAR_START = new Color(0x66, 0x7E, 0xEA);
            final Color C_BAR_END   = new Color(0x74, 0xEB, 0xD5);
            final Color C_TEXT = new Color(0xAA, 0xAA, 0xAA);

            JComponent curtain = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                    g2.setColor(new Color(C_BG.getRed(), C_BG.getGreen(), C_BG.getBlue(), alpha[0]));
                    g2.fillRect(0, 0, getWidth(), getHeight());

                    int cw = getWidth(), ch = getHeight();
                    if (cw <= 0 || ch <= 0) { g2.dispose(); return; }

                    g2.setFont(fontTitle);
                    FontMetrics fmTitle = g2.getFontMetrics();
                    String titleText = "CoreTools";
                    int titleX = (cw - fmTitle.stringWidth(titleText)) / 2;
                    int titleY = ch / 2 - 60;
                    GradientPaint titleGp = new GradientPaint(
                            titleX, titleY, new Color(C_GRAD1.getRed(), C_GRAD1.getGreen(), C_GRAD1.getBlue(), alpha[0]),
                            titleX + fmTitle.stringWidth(titleText), titleY,
                            new Color(C_GRAD2.getRed(), C_GRAD2.getGreen(), C_GRAD2.getBlue(), alpha[0]));
                    g2.setPaint(titleGp);
                    g2.drawString(titleText, titleX, titleY);

                    int n = loadedCount[0];
                    String loadingText = n > 0
                            ? "模块加载中  " + n + " / " + totalTabs
                            : "模块加载中...";
                    g2.setFont(fontText);
                    FontMetrics fmText = g2.getFontMetrics();
                    int textX = (cw - fmText.stringWidth(loadingText)) / 2;
                    int textY = titleY + 50;
                    g2.setColor(new Color(C_TEXT.getRed(), C_TEXT.getGreen(), C_TEXT.getBlue(), alpha[0]));
                    g2.drawString(loadingText, textX, textY);

                    int barW = Math.max(200, Math.min(500, cw / 3));
                    int barH = 8;
                    int barX = (cw - barW) / 2;
                    int barY = textY + 28;
                    float progress = totalTabs > 0 ? (float) n / totalTabs : 0f;

                    int corner = barH;
                    Shape bgTrack = new java.awt.geom.RoundRectangle2D.Float(barX, barY, barW, barH, corner, corner);
                    g2.setColor(new Color(C_BAR_BG.getRed(), C_BAR_BG.getGreen(), C_BAR_BG.getBlue(), alpha[0]));
                    g2.fill(bgTrack);

                    if (progress > 0) {
                        int filledW = (int) (barW * progress);
                        if (filledW > 0) {
                            Shape fillShape = new java.awt.geom.RoundRectangle2D.Float(barX, barY, filledW, barH, corner, corner);
                            GradientPaint barGp = new GradientPaint(
                                    barX, barY, new Color(C_BAR_START.getRed(), C_BAR_START.getGreen(), C_BAR_START.getBlue(), alpha[0]),
                                    barX + barW, barY, new Color(C_BAR_END.getRed(), C_BAR_END.getGreen(), C_BAR_END.getBlue(), alpha[0]));
                            g2.setPaint(barGp);
                            g2.fill(fillShape);
                        }
                    }

                    g2.dispose();
                }
            };
            curtain.setOpaque(false);
            frame.setGlassPane(curtain);
            curtain.setVisible(true);

            // 先让窗口可见（此时 Tab 页空的，秒显示，幕布可见）
            frame.setVisible(true);

            // ===== EDT 链式加载：所有 Swing 组件必须在 EDT 创建（jpackage 打包后尤其严格） =====
            // 加 try-catch 保护：单个面板异常不影响后续面板加载
            final int[] nextIndex = {0};
            final int[] errorCount = {0};
            SwingUtilities.invokeLater(new Runnable() {
                @Override
                public void run() {
                    int i = nextIndex[0];
                    if (i >= totalTabs) {
                        // 全部加载完成
                        frame.onAllTabsLoaded();
                        // 幕布淡出动画
                        final int steps = 22;
                        new Timer(16, new java.awt.event.ActionListener() {
                            int tick = 0;
                            @Override
                            public void actionPerformed(java.awt.event.ActionEvent e) {
                                tick++;
                                double t = (double) tick / steps;
                                double eased = 1.0 - Math.pow(1.0 - t, 3);
                                alpha[0] = 255 - (int) (255 * eased);
                                if (tick >= steps) {
                                    ((Timer) e.getSource()).stop();
                                    curtain.setVisible(false);
                                } else {
                                    curtain.repaint();
                                }
                            }
                        }).start();
                        if (errorCount[0] > 0) {
                            logger.warn("面板加载完毕，共 {} 个面板加载失败", errorCount[0]);
                            MessageDialog.warning("部分面板加载失败（" + errorCount[0] + " 个），日志详情见: "
                                    + System.getProperty("app.log.dir"));
                        }
                        logger.info("全部面板加载完成，成功={}/{}", totalTabs - errorCount[0], totalTabs);
                        return;
                    }
                    // 在 EDT 创建面板（Swing 组件必须在 EDT）
                    String name = null;
                    try {
                        name = frame.createPanel(i);
                    } catch (Exception ex) {
                        errorCount[0]++;
                        logger.error("面板 index={} 创建失败", i, ex);
                        MessageDialog.error("面板 [" + i + "] 创建失败: " + ex.getMessage());
                    }
                    try {
                        if (name != null) {
                            frame.addPanelToUI(name);
                            loadedCount[0]++;
                        }
                    } catch (Exception ex) {
                        logger.error("addPanelToUI(\"{}\") 失败", name, ex);
                        MessageDialog.error("面板 [" + name + "] 添加到界面失败");
                    }
                    nextIndex[0]++;
                    curtain.repaint();
                    // 链式调度下一个，给 EDT 喘息空间让幕布能刷新
                    SwingUtilities.invokeLater(this);
                }
            });

        } catch (Exception e) {
            logger.fatal("CoreTools 启动失败", e);
            SwingUtilities.invokeLater(() ->
                    MessageDialog.error("程序启动失败: " + e.getMessage() + "\n请查看日志: " +
                            System.getProperty("app.log.dir")));
            System.exit(1);
        }
    }

    public static void resizeCv(Mat mat, Mat resized, Size size) {
        resize(mat, resized, size);
    }
}
