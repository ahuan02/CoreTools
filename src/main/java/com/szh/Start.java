package com.szh;

import com.szh.ui.MainFrame;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import static org.bytedeco.opencv.global.opencv_imgproc.resize;
import javax.imageio.ImageIO;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.util.Objects;

public class Start {
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> {
            MainFrame frame = new MainFrame();
            // 设置程序图标
            try {
                BufferedImage icon = ImageIO.read(
                        Objects.requireNonNull(Start.class.getResourceAsStream("/donna.jpg")));
                frame.setIconImage(icon);
            } catch (IOException | IllegalArgumentException e) {
                System.err.println("加载图标失败: " + e.getMessage());
            }

            // ===== 入场过渡动画：玻璃幕布渐变淡化 =====
            // 在窗体上方铺一层实色幕布，逐帧降低不透明度，平滑露出界面
            final int[] alpha = {255};
            JComponent curtain = new JComponent() {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setColor(new Color(0x2B, 0x2B, 0x2B, alpha[0]));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.dispose();
                }
            };
            curtain.setOpaque(false);
            frame.setGlassPane(curtain);
            curtain.setVisible(true);
            frame.setVisible(true);

            final int steps = 22;
            new Timer(16, new java.awt.event.ActionListener() {
                int tick = 0;

                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    tick++;
                    double t = (double) tick / steps;
                    // ease-out cubic: 1 - (1-t)³
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
        });
    }

    public static void resizeCv(Mat mat, Mat resized, Size size){
        resize(mat,resized,size);
    }
}
