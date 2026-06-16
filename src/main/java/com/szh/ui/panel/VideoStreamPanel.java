package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.text.SimpleDateFormat;
import java.util.Date;

/**
 * 视频流面板：RTSP 流地址输入 + 视频预览画面 + 发送/响应日志
 */
public class VideoStreamPanel extends AbstractCommandPanel {

    private JTextField hostField;
    private JTextField portField;
    private JTextField rtspField;
    private JButton connectBtn, stopBtn;
    private VideoPreviewPanel videoPanel;

    // 日志相关
    private JTextPane logPane;
    private Style logStyleSend, logStyleOk, logStyleErr, logStyleTimeout, logStyleTime;
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    private static final int MAX_LOG_LINES = 2000;
    private int logLineCount = 0;

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

        // ---- 视频预览画面 + 日志（上下分栏）----
        videoPanel = new VideoPreviewPanel("视频流画面");
        JScrollPane logScroll = createLogPanel();

        JSplitPane centerSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, videoPanel, logScroll);
        centerSplit.setResizeWeight(0.65);
        centerSplit.setDividerSize(5);

        add(controlBar, BorderLayout.NORTH);
        add(centerSplit, BorderLayout.CENTER);

        // ---- 事件绑定 ----
        connectBtn.addActionListener(e -> {
            String url = rtspField.getText().trim();
            if (!url.isEmpty()) {
                new Thread(() -> videoPanel.startPlayback(url), "connect-video").start();
            }
        });
        stopBtn.addActionListener(e -> new Thread(videoPanel::stopPlayback, "disconnect-video").start());

        // 视频面板日志回调
        videoPanel.setLogCallback(msg -> appendLog(msg, logStyleOk));
    }

    // ==================== 日志面板 ====================

    private JScrollPane createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "发送 / 响应日志",
                TitledBorder.LEADING,
                TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)
        ));

        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setFocusable(false);
        logPane.setBackground(new Color(0x1E1E1E));
        logPane.setCaretColor(new Color(0xD4D4D4));

        StyleContext ctx = new StyleContext();
        Style defaultStyle = ctx.addStyle("default", null);
        StyleConstants.setFontFamily(defaultStyle, "Consolas");
        StyleConstants.setFontSize(defaultStyle, 13);
        StyleConstants.setForeground(defaultStyle, new Color(0xD4D4D4));

        logStyleSend = ctx.addStyle("send", defaultStyle);
        StyleConstants.setForeground(logStyleSend, new Color(0x64B5F6));

        logStyleOk = ctx.addStyle("ok", defaultStyle);
        StyleConstants.setForeground(logStyleOk, new Color(0x81C784));

        logStyleErr = ctx.addStyle("err", defaultStyle);
        StyleConstants.setForeground(logStyleErr, new Color(0xE57373));

        logStyleTimeout = ctx.addStyle("timeout", defaultStyle);
        StyleConstants.setForeground(logStyleTimeout, new Color(0xFFB74D));

        logStyleTime = ctx.addStyle("time", defaultStyle);
        StyleConstants.setForeground(logStyleTime, new Color(0x888888));
        StyleConstants.setFontSize(logStyleTime, 11);

        logPane.setDocument(new DefaultStyledDocument(ctx));

        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem clearItem = new JMenuItem("清除日志");
        clearItem.addActionListener(e -> logPane.setText(""));
        popupMenu.add(clearItem);
        logPane.setComponentPopupMenu(popupMenu);

        JScrollPane scrollPane = new JScrollPane(logPane);
        scrollPane.setPreferredSize(new Dimension(780, 160));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getVerticalScrollBar().setBlockIncrement(80);
        panel.add(scrollPane, BorderLayout.CENTER);

        return scrollPane;
    }

    public void appendLog(String msg, Style style) {
        try {
            Document doc = logPane.getDocument();
            if (logLineCount >= MAX_LOG_LINES) {
                Element root = doc.getDefaultRootElement();
                int endLine = Math.min(500, root.getElementCount() - 1);
                if (endLine > 0) {
                    Element endElem = root.getElement(endLine);
                    doc.remove(0, endElem.getEndOffset());
                    logLineCount -= endLine;
                }
            }
            String time = "[" + sdf.format(new Date()) + "] ";
            doc.insertString(doc.getLength(), time, logStyleTime);
            doc.insertString(doc.getLength(), msg + "\n", style);
            logLineCount++;
            logPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
    }

    /** 获取日志样式，供外部输出日志 */
    public Style getLogStyleOk() { return logStyleOk; }
    public Style getLogStyleErr() { return logStyleErr; }
    public Style getLogStyleSend() { return logStyleSend; }
    public Style getLogStyleTimeout() { return logStyleTimeout; }

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
