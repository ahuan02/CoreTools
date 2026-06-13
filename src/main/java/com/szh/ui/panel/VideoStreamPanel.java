package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * 视频流面板：RTSP 流地址输入 + 视频预览画面
 */
public class VideoStreamPanel extends AbstractCommandPanel {

    private JTextField hostField;
    private JTextField portField;
    private JTextField rtspField;
    private JButton connectBtn, stopBtn;
    private VideoPreviewPanel videoPanel;

    public VideoStreamPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));

        // ---- 顶部控制栏：设备IP + 端口 + RTSP地址 + 连接/断开 ----
        JPanel controlBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        controlBar.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "视频流控制", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        controlBar.add(new JLabel("设备IP:"));
        hostField = new JTextField("127.0.0.1", 10);
        controlBar.add(hostField);
        controlBar.add(new JLabel("端口:"));
        portField = new JTextField("9000", 5);
        controlBar.add(portField);
        controlBar.add(Box.createHorizontalStrut(12));

        controlBar.add(new JLabel("RTSP 地址:"));
        rtspField = new JTextField("rtmp://liteavapp.qcloud.com/live/liteavdemoplayerstreamid", 36);
        rtspField.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        controlBar.add(rtspField);

        connectBtn = new JButton("连接");
        connectBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        connectBtn.setFocusPainted(false);
        connectBtn.setBackground(new Color(0x4CAF50));
        connectBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        stopBtn = new JButton("断开");
        stopBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        stopBtn.setFocusPainted(false);
        stopBtn.setBackground(new Color(0xF44336));
        stopBtn.setCursor(new Cursor(Cursor.HAND_CURSOR));

        controlBar.add(connectBtn);
        controlBar.add(stopBtn);

        add(controlBar, BorderLayout.NORTH);

        // ---- 视频预览画面 ----
        videoPanel = new VideoPreviewPanel("视频流画面");
        add(videoPanel, BorderLayout.CENTER);

        // ---- 事件绑定 ----
        connectBtn.addActionListener(e -> {
            String url = rtspField.getText().trim();
            if (!url.isEmpty()) {
                new Thread(() -> videoPanel.startPlayback(url), "connect-video").start();
            }
        });
        stopBtn.addActionListener(e -> new Thread(videoPanel::stopPlayback, "disconnect-video").start());
    }

    /** 设置日志回调，将视频状态输出到主日志 */
    public void setLogCallback(Consumer<String> callback) {
        videoPanel.setLogCallback(callback);
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        String ip = config.get("videoStream.host", null);
        if (ip != null && !ip.isEmpty()) hostField.setText(ip);
        String port = config.get("videoStream.port", null);
        if (port != null && !port.isEmpty()) portField.setText(port);
        String rtsp = config.get("videoStream.rtsp", null);
        if (rtsp != null && !rtsp.isEmpty()) rtspField.setText(rtsp);
    }

    @Override
    public void saveConfig(ConfigManager config) {
        config.set("videoStream.host", hostField.getText().trim());
        config.set("videoStream.port", portField.getText().trim());
        config.set("videoStream.rtsp", rtspField.getText().trim());
    }
}
