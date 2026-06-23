package com.szh;

import com.szh.ui.MainFrame;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;

import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.bytedeco.opencv.global.opencv_imgproc.resize;

public class Start {
    public static void main(String[] args) {
        // 第一步：只构建框架外壳（无面板），窗口秒开
        MainFrame frame = new MainFrame();

        // 设置程序图标
        try {
            BufferedImage icon = ImageIO.read(
                    Objects.requireNonNull(Start.class.getResourceAsStream("/icon.png")));
            frame.setIconImage(icon);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("加载图标失败: " + e.getMessage());
        }

        final int totalTabs = frame.getTotalTabs();
        final int maxDots = 6;              // 最多显示 6 个点
        final int[] alpha = {255};
        final int[] loadedCount = {0};

        // ===== 玻璃幕布：背景 + 加载进度提示 =====
        JComponent curtain = new JComponent() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

                // 背景
                g2.setColor(new Color(0x2B, 0x2B, 0x2B, alpha[0]));
                g2.fillRect(0, 0, getWidth(), getHeight());

                int cw = getWidth(), ch = getHeight();
                if (cw > 0 && ch > 0) {
                    // 主标题（蓝紫渐变）
                    g2.setFont(new Font("Microsoft YaHei", Font.BOLD, 52));
                    FontMetrics fmTitle = g2.getFontMetrics();
                    String title = "CoreTools";
                    int titleX = (cw - fmTitle.stringWidth(title)) / 2;
                    int titleY = ch / 2 - 30;
                    GradientPaint titleGp = new GradientPaint(
                            titleX, titleY, new Color(0x66, 0x7E, 0xEA, alpha[0]),
                            titleX + fmTitle.stringWidth(title), titleY, new Color(0x76, 0x4B, 0xA2, alpha[0]));
                    g2.setPaint(titleGp);
                    g2.drawString(title, titleX, titleY);

                    // 副标题：累加点（根据加载进度）+ 计数（青紫渐变）
                    g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 20));
                    FontMetrics fmSub = g2.getFontMetrics();

                    int n = loadedCount[0];
                    int dotCount = Math.max(1, Math.min(maxDots, n * maxDots / totalTabs));
                    StringBuilder dots = new StringBuilder();
                    for (int i = 0; i < dotCount; i++) dots.append(i > 0 ? " ·" : "·");

                    String subText = "工具模块加载中 " + dots + "  " + n + "/" + totalTabs;
                    int subX = (cw - fmSub.stringWidth(subText)) / 2;
                    int subY = ch / 2 + 20;
                    GradientPaint subGp = new GradientPaint(
                            subX, subY, new Color(0x74, 0xEB, 0xD5, alpha[0]),
                            subX + fmSub.stringWidth(subText), subY, new Color(0x9F, 0xAC, 0xE6, alpha[0]));
                    g2.setPaint(subGp);
                    g2.drawString(subText, subX, subY);
                }

                g2.dispose();
            }
        };
        curtain.setOpaque(false);
        frame.setGlassPane(curtain);
        curtain.setVisible(true);

        // 先让窗口可见（此时 Tab 页空的，秒显示，幕布可见）
        frame.setVisible(true);

        // ===== SwingWorker 后台加载面板，不卡主界面 =====
        SwingWorker<Void, String> loader = new SwingWorker<>() {
            @Override
            protected Void doInBackground() {
                for (int i = 0; i < totalTabs; i++) {
                    String name = frame.createPanel(i);   // 后台线程创建面板（重活）
                    if (name != null) publish(name);       // 通知 EDT 添加到 UI
                }
                return null;
            }

            @Override
            protected void process(List<String> chunks) {
                // EDT 线程：把面板加入 TabbedPane
                for (String name : chunks) {
                    frame.addPanelToUI(name);
                    loadedCount[0]++;
                }
                curtain.repaint();  // 更新点数和计数
            }

            @Override
            protected void done() {
                // 面板全部加载完成
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
            }
        };
        loader.execute();
    }

    public static void resizeCv(Mat mat, Mat resized, Size size) {
        resize(mat, resized, size);
    }
}
