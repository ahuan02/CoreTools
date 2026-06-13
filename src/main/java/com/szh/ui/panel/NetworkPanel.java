package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.io.*;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * 网络调试面板：UDP/TCP 服务端 + 客户端（Java NIO 非阻塞实现），支持明文/HEX/二进制多格式收发
 */
public class NetworkPanel extends AbstractCommandPanel {

    private JTabbedPane tabbedPane;
    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();
    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");

    // 保存内部面板引用，用于配置持久化
    private UdpServerPanel udpServerPanel;
    private UdpClientPanel udpClientPanel;
    private TcpServerPanel tcpServerPanel;
    private TcpClientPanel tcpClientPanel;

    // ===== 日志配色 =====
    private static final Color C_TIME    = new Color(0x888888);
    private static final Color C_SEND    = new Color(0x64B5F6);
    private static final Color C_RECV    = new Color(0x81C784);
    private static final Color C_SYS     = new Color(0xCE93D8);
    private static final Color C_ERR     = new Color(0xE57373);
    private static final Color C_WARN    = new Color(0xFFB74D);
    private static final Color C_BG      = new Color(0x1E1E1E);
    /** 支持中文的等宽/UI字体，解决 Consolas 打汉字乱码 */
    private static final Font FONT_TEXT  = new Font("Microsoft YaHei", Font.PLAIN, 12);

    // ===== 格式模式 =====
    private static final String[] FORMAT_MODES = {"明文 Text", "十六进制 HEX", "二进制 BIN"};

    public NetworkPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);

        udpServerPanel = new UdpServerPanel();
        udpClientPanel = new UdpClientPanel();
        tcpServerPanel = new TcpServerPanel();
        tcpClientPanel = new TcpClientPanel();

        tabbedPane.addTab("UDP 服务端", udpServerPanel);
        tabbedPane.addTab("UDP 客户端", udpClientPanel);
        tabbedPane.addTab("TCP 服务端", tcpServerPanel);
        tabbedPane.addTab("TCP 客户端", tcpClientPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    // ==================== 格式模式枚举 ====================
    private enum FormatMode { TEXT, HEX, BIN }

    private static FormatMode fromComboIndex(int idx) {
        switch (idx) {
            case 1: return FormatMode.HEX;
            case 2: return FormatMode.BIN;
            default: return FormatMode.TEXT;
        }
    }

    // ==================== 工具方法 ====================

    private String ts() {
        return "[" + sdf.format(new Date()) + "] ";
    }

    private static byte[] hexToBytes(String hex) {
        String h = hex.replaceAll("\\s+", "");
        int len = h.length();
        if (len % 2 != 0) throw new IllegalArgumentException("HEX 字符串长度必须为偶数");
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(h.charAt(i), 16) << 4)
                    + Character.digit(h.charAt(i + 1), 16));
        }
        return data;
    }

    private static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    private static String bytesToBin(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 9);
        for (byte b : bytes) {
            sb.append(String.format("%8s ", Integer.toBinaryString(b & 0xFF)).replace(' ', '0'));
        }
        return sb.toString().trim();
    }

    private static byte[] binToBytes(String bin) {
        String b = bin.replaceAll("\\s+", "");
        int len = b.length();
        if (len % 8 != 0) throw new IllegalArgumentException("二进制字符串长度必须为8的倍数");
        byte[] data = new byte[len / 8];
        for (int i = 0; i < len; i += 8) {
            data[i / 8] = (byte) Integer.parseInt(b.substring(i, i + 8), 2);
        }
        return data;
    }

    private String formatBytes(byte[] data, FormatMode mode) {
        switch (mode) {
            case HEX: return "HEX: " + bytesToHex(data);
            case BIN: return "BIN: " + bytesToBin(data);
            default:  return decode(data, "UTF-8");
        }
    }

    private String decode(byte[] data, String encoding) {
        try {
            return new String(data, Charset.forName(encoding));
        } catch (Exception e) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    private byte[] encodeByMode(String text, FormatMode mode, String encoding) {
        switch (mode) {
            case HEX: return hexToBytes(text);
            case BIN: return binToBytes(text);
            default:
                try {
                    return text.getBytes(Charset.forName(encoding));
                } catch (Exception e) {
                    return text.getBytes(StandardCharsets.UTF_8);
                }
        }
    }

    // ==================== 彩色日志 JTextPane ====================

    private static JTextPane createLogPane() {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setBackground(C_BG);
        pane.setCaretColor(new Color(0xD4D4D4));
        pane.setFont(FONT_TEXT);

        JPopupMenu popup = new JPopupMenu();
        JMenuItem clearItem = new JMenuItem("清空日志");
        clearItem.addActionListener(e -> pane.setText(""));
        popup.add(clearItem);
        pane.setComponentPopupMenu(popup);

        return pane;
    }

    private static JScrollPane createLogScroll(JTextPane pane) {
        JScrollPane sp = new JScrollPane(pane);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    private void appendLog(JTextPane log, String prefix, String content, Color cPrefix, Color cContent) {
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = log.getStyledDocument();
                Style sTime = log.addStyle("time", null);
                StyleConstants.setForeground(sTime, C_TIME);
                StyleConstants.setFontSize(sTime, 11);

                doc.insertString(doc.getLength(), prefix, sTime);

                if (cPrefix != null) {
                    Style sP = log.addStyle("p" + System.nanoTime(), null);
                    StyleConstants.setForeground(sP, cPrefix);
                    doc.insertString(doc.getLength(), content, sP);
                }
                if (cContent != null && cContent != cPrefix) {
                    Style sC = log.addStyle("c" + System.nanoTime(), null);
                    StyleConstants.setForeground(sC, cContent);
                    doc.insertString(doc.getLength(), content, sC);
                }

                doc.insertString(doc.getLength(), "\n", null);
                log.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                e.printStackTrace();
            }
        });
    }

    private void logSys(JTextPane log, String msg)  { appendLog(log, ts(), msg, C_SYS, null); }
    private void logRecv(JTextPane log, String msg)  { appendLog(log, ts(), msg, C_RECV, null); }
    private void logSend(JTextPane log, String msg)  { appendLog(log, ts(), msg, C_SEND, null); }
    private void logErr(JTextPane log, String msg)   { appendLog(log, ts(), msg, C_ERR, null); }
    private void logWarn(JTextPane log, String msg)  { appendLog(log, ts(), msg, C_WARN, null); }

    // ==================== 通用控件工厂 ====================

    private static JButton makeBtn(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(2, 10, 2, 10));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        if (bg != null) btn.setBackground(bg);
        return btn;
    }

    private static JComboBox<String> createFormatCombo() {
        JComboBox<String> combo = new JComboBox<>(FORMAT_MODES);
        combo.setPreferredSize(new Dimension(115, 24));
        return combo;
    }

    // ==================== 通用昵称解析 ====================
    private static String[] parseNickname(String msg) {
        if (msg != null && msg.startsWith("[") && msg.length() > 2) {
            int endIdx = msg.indexOf("]: ", 2);
            if (endIdx > 2) {
                return new String[]{msg.substring(1, endIdx), msg.substring(endIdx + 3)};
            }
        }
        return new String[]{null, msg};
    }

    private static String buildMsgWithNickname(String nickname, String content) {
        if (nickname == null || (nickname = nickname.trim()).isEmpty()) return content;
        return "[" + nickname + "]: " + content;
    }

    // ==================== NIO Selector 工具 ====================
    /** 将 ByteBuffer 中的数据按行解析，返回完整行列表和剩余未完成行 */
    private static List<String> extractLines(StringBuilder remainder, Charset charset) {
        List<String> lines = new ArrayList<>();
        String text = remainder.toString();
        int idx;
        while ((idx = text.indexOf('\n')) >= 0) {
            String line = text.substring(0, idx);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            lines.add(line);
            text = text.substring(idx + 1);
        }
        remainder.setLength(0);
        if (!text.isEmpty()) remainder.append(text);
        return lines;
    }

    /** 从 SocketChannel 读取数据到 ByteBuffer，解码后追加到 remainder */
    private static void readIntoRemainder(SocketChannel sc, ByteBuffer buf, StringBuilder remainder, Charset charset) throws IOException {
        buf.clear();
        int n = sc.read(buf);
        if (n == -1) throw new EOFException("连接关闭");
        if (n > 0) {
            buf.flip();
            remainder.append(charset.decode(buf));
        }
    }

    // ==================== UDP 服务端（NIO DatagramChannel + Selector） ====================

    private class UdpServerPanel extends JPanel {
        private JTextField portField;
        private JTextArea replyArea;
        private JTextPane logPane;
        private JComboBox<String> encCombo, formatCombo;
        private DatagramChannel serverChannel;
        private Selector selector;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private Thread nioThread;
        private final Map<String, InetSocketAddress> recentClients = new LinkedHashMap<>();

        /** NIO 线程可安全读取的缓存值，在 startServer 时于 EDT 上设置 */
        private volatile String cachedReplyText = "";
        private volatile Charset cachedCharset = StandardCharsets.UTF_8;
        private volatile FormatMode cachedFormatMode = FormatMode.TEXT;
        private DefaultListModel<String> clientListModel;
        private JList<String> clientList;
        /** IP -> 昵称 映射，用于在客户端列表中显示昵称 */
        private final Map<String, String> clientNicknames = new ConcurrentHashMap<>();

        UdpServerPanel() {
            setLayout(new BorderLayout(6, 6));
            setBorder(new EmptyBorder(6, 0, 0, 0));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            top.add(new JLabel("监听端口:"));
            portField = new JTextField("9900", 6);
            top.add(portField);

            encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1", "ASCII"});
            top.add(new JLabel("编码:"));
            top.add(encCombo);

            formatCombo = createFormatCombo();
            top.add(new JLabel("格式:"));
            top.add(formatCombo);

            JButton btnStart = makeBtn("启动", new Color(0x4CAF50));
            JButton btnStop = makeBtn("停止", new Color(0xF44336));
            btnStop.setEnabled(false);
            top.add(btnStart);
            top.add(btnStop);

            JPanel replyPanel = new JPanel(new BorderLayout());
            replyPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "自动回复内容（留空不回复）", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));
            replyArea = new JTextArea(3, 40);
            replyArea.setFont(FONT_TEXT);
            replyPanel.add(new JScrollPane(replyArea), BorderLayout.CENTER);

            JPanel broadcastPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            JTextField broadcastField = new JTextField(30);
            broadcastField.setFont(FONT_TEXT);
            JButton btnBroadcast = makeBtn("广播发送", new Color(0xFF9800));
            btnBroadcast.addActionListener(e -> {
                String msg = broadcastField.getText().trim();
                if (msg.isEmpty()) return;
                broadcastToAll(msg);
                broadcastField.setText("");
            });
            broadcastPanel.add(new JLabel("广播:"));
            broadcastPanel.add(broadcastField);
            broadcastPanel.add(btnBroadcast);
            replyPanel.add(broadcastPanel, BorderLayout.SOUTH);

            logPane = createLogPane();

            clientListModel = new DefaultListModel<>();
            clientList = new JList<>(clientListModel);
            clientList.setFont(FONT_TEXT);
            clientList.setBackground(new Color(0x2D2D2D));
            clientList.setForeground(C_RECV);
            clientList.setSelectionBackground(new Color(0x3C3C3C));
            clientList.setSelectionForeground(Color.WHITE);
            clientList.setFixedCellHeight(22);
            JScrollPane clientListScroll = new JScrollPane(clientList);
            clientListScroll.setPreferredSize(new Dimension(170, 0));
            clientListScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "已记录客户端（最近通信）", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            JPanel center = new JPanel(new BorderLayout(6, 6));
            center.add(replyPanel, BorderLayout.NORTH);
            center.add(createLogScroll(logPane), BorderLayout.CENTER);

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, clientListScroll);
            splitPane.setResizeWeight(0.78);
            splitPane.setDividerSize(4);

            add(top, BorderLayout.NORTH);
            add(splitPane, BorderLayout.CENTER);

            btnStart.addActionListener(e -> {
                int port;
                try { port = Integer.parseInt(portField.getText().trim()); }
                catch (NumberFormatException ex) { logWarn(logPane, "端口号格式错误"); return; }
                startServer(port, btnStart, btnStop);
            });
            btnStop.addActionListener(e -> {
                stopServer();
                btnStart.setEnabled(true);
                btnStop.setEnabled(false);
                portField.setEnabled(true);
            });
        }

        private void startServer(int port, JButton startBtn, JButton stopBtn) {
            try {
                serverChannel = DatagramChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.bind(new InetSocketAddress(port));
                selector = Selector.open();
                serverChannel.register(selector, SelectionKey.OP_READ);

                // 在 EDT 上捕获编码/格式/回复内容，NIO 线程安全读取
                cachedReplyText = replyArea.getText().trim();
                String enc = (String) encCombo.getSelectedItem();
                try { cachedCharset = Charset.forName(enc); } catch (Exception e) { cachedCharset = StandardCharsets.UTF_8; }
                cachedFormatMode = fromComboIndex(formatCombo.getSelectedIndex());

                running.set(true);
                startBtn.setEnabled(false);
                stopBtn.setEnabled(true);
                portField.setEnabled(false);
                logSys(logPane, "UDP 服务端已启动（NIO），监听端口 " + port);

                nioThread = Thread.ofVirtual().name("udp-server-nio").start(() -> {
                    ByteBuffer recvBuf = ByteBuffer.allocate(65535);
                    while (running.get()) {
                        try {
                            if (selector.select(1000) <= 0) continue;
                            for (SelectionKey key : selector.selectedKeys()) {
                                if (!key.isReadable()) continue;
                                DatagramChannel ch = (DatagramChannel) key.channel();
                                recvBuf.clear();
                                InetSocketAddress sender = (InetSocketAddress) ch.receive(recvBuf);
                                if (sender == null) continue;
                                recvBuf.flip();
                                byte[] recvData = new byte[recvBuf.remaining()];
                                recvBuf.get(recvData);

                                String clientIp = sender.getAddress().getHostAddress();
                                int clientPort = sender.getPort();
                                String clientKey = clientIp + ":" + clientPort;

                                boolean isNew = !recentClients.containsKey(clientKey);
                                recentClients.put(clientKey, new InetSocketAddress(sender.getAddress(), clientPort));
                                if (isNew) {
                                    SwingUtilities.invokeLater(() -> clientListModel.addElement(clientIp));
                                }

                                FormatMode mode = cachedFormatMode;
                                Charset charset = cachedCharset;
                                String formatted = formatBytes(recvData, mode);
                                if (mode == FormatMode.TEXT) {
                                    String[] parts = parseNickname(formatted);
                                    if (parts[0] != null) {
                                        String oldNickname = clientNicknames.put(clientIp, parts[0]);
                                        if (oldNickname == null || !oldNickname.equals(parts[0])) {
                                            final String display = parts[0] + "(" + clientIp + ")";
                                            SwingUtilities.invokeLater(() -> updateClientListEntry(clientIp, display));
                                        }
                                        logRecv(logPane, "收到 [" + clientKey + "] [" + parts[0] + "]: " + parts[1]);
                                    } else {
                                        logRecv(logPane, "收到 [" + clientKey + "] " + formatted);
                                    }
                                } else {
                                    logRecv(logPane, "收到 [" + clientKey + "] " + formatted);
                                }

                                String replyText = cachedReplyText;
                                if (!replyText.isEmpty()) {
                                    byte[] replyData;
                                    if (mode == FormatMode.HEX) {
                                        replyData = hexToBytes(replyText);
                                    } else if (mode == FormatMode.BIN) {
                                        replyData = binToBytes(replyText);
                                    } else {
                                        replyData = replyText.getBytes(charset);
                                    }
                                    ByteBuffer sendBuf = ByteBuffer.wrap(replyData);
                                    ch.send(sendBuf, sender);
                                    logSend(logPane, "回复 [" + clientKey + "] " + formatBytes(replyData, mode));
                                }
                            }
                            selector.selectedKeys().clear();
                        } catch (ClosedSelectorException | ClosedChannelException e) {
                            break;
                        } catch (IOException e) {
                            if (running.get()) logErr(logPane, "IO 错误: " + e.getMessage());
                        }
                    }
                });
            } catch (IOException e) {
                logErr(logPane, "启动失败: " + e.getMessage());
            }
        }

        private void broadcastToAll(String msg) {
            if (recentClients.isEmpty()) { logWarn(logPane, "没有已记录的客户端"); return; }
            FormatMode mode = fromComboIndex(formatCombo.getSelectedIndex());
            String enc = (String) encCombo.getSelectedItem();
            byte[] data = encodeByMode(msg, mode, enc);
            logSend(logPane, "广播 → " + recentClients.size() + " 个客户端: " + formatBytes(data, mode));
            ByteBuffer sendBuf = ByteBuffer.wrap(data);
            for (Map.Entry<String, InetSocketAddress> entry : recentClients.entrySet()) {
                threadPool.submit(() -> {
                    try {
                        sendBuf.rewind();
                        serverChannel.send(sendBuf, entry.getValue());
                    } catch (IOException e) {
                        logErr(logPane, "广播失败 [" + entry.getKey() + "]: " + e.getMessage());
                    }
                });
            }
        }

        /** 在 EDT 上更新客户端列表项：将 oldEntry 替换为 newEntry */
        private void updateClientListEntry(String oldEntry, String newEntry) {
            int idx = clientListModel.indexOf(oldEntry);
            if (idx >= 0) {
                clientListModel.set(idx, newEntry);
            }
        }

        private void stopServer() {
            running.set(false);
            try { if (selector != null) selector.close(); } catch (IOException ignored) {}
            try { if (serverChannel != null) serverChannel.close(); } catch (IOException ignored) {}
            recentClients.clear();
            clientNicknames.clear();
            SwingUtilities.invokeLater(() -> clientListModel.clear());
            logSys(logPane, "UDP 服务端已停止");
        }
    }

    // ==================== UDP 客户端（NIO DatagramChannel） ====================

    private class UdpClientPanel extends JPanel {
        private JTextField hostField, portField, nicknameField;
        private JTextArea sendArea;
        private JTextPane logPane;
        private JComboBox<String> encCombo, formatCombo;
        private final List<String> history = new ArrayList<>();
        private static final int MAX_HISTORY = 50;

        UdpClientPanel() {
            setLayout(new BorderLayout(6, 6));
            setBorder(new EmptyBorder(6, 0, 0, 0));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            top.add(new JLabel("目标IP:"));
            hostField = new JTextField("192.168.4.66", 10);
            top.add(hostField);
            top.add(new JLabel("端口:"));
            portField = new JTextField("9000", 6);
            top.add(portField);

            top.add(new JLabel("昵称:"));
            nicknameField = new JTextField(6);
            nicknameField.setToolTipText("设置昵称后，消息将自动带上 [昵称]: 前缀");
            top.add(nicknameField);

            encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1", "ASCII"});
            top.add(new JLabel("编码:"));
            top.add(encCombo);

            formatCombo = createFormatCombo();
            top.add(new JLabel("格式:"));
            top.add(formatCombo);

            JPanel sendPanel = new JPanel(new BorderLayout());
            sendPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "发送内容", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));
            sendArea = new JTextArea(4, 40);
            sendArea.setFont(FONT_TEXT);
            sendPanel.add(new JScrollPane(sendArea), BorderLayout.CENTER);

            JPanel sendBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            JButton btnSend = makeBtn("发送 (Ctrl+Enter)", new Color(0x2196F3));
            JButton btnClear = makeBtn("清除", null);
            JButton btnHistory = makeBtn("历史", null);
            JButton btnQuick1 = makeBtn("HELLO", null);
            JButton btnQuick2 = makeBtn("STATUS", null);
            JButton btnQuick3 = makeBtn("VERSION", null);
            sendBtnRow.add(btnSend);
            sendBtnRow.add(btnClear);
            sendBtnRow.add(btnHistory);
            sendBtnRow.add(btnQuick1);
            sendBtnRow.add(btnQuick2);
            sendBtnRow.add(btnQuick3);
            sendPanel.add(sendBtnRow, BorderLayout.SOUTH);

            logPane = createLogPane();

            add(top, BorderLayout.NORTH);
            JPanel center = new JPanel(new BorderLayout(6, 6));
            center.add(sendPanel, BorderLayout.NORTH);
            center.add(createLogScroll(logPane), BorderLayout.CENTER);
            add(center, BorderLayout.CENTER);

            btnSend.addActionListener(e -> doSend());
            btnClear.addActionListener(e -> sendArea.setText(""));
            btnHistory.addActionListener(e -> showHistory());
            btnQuick1.addActionListener(e -> sendArea.setText("HELLO"));
            btnQuick2.addActionListener(e -> sendArea.setText("STATUS"));
            btnQuick3.addActionListener(e -> sendArea.setText("VERSION"));

            sendArea.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
            sendArea.getActionMap().put("send", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) { doSend(); }
            });
        }

        private void doSend() {
            String host = hostField.getText().trim();
            String portText = portField.getText().trim();
            String content = sendArea.getText().trim();
            if (host.isEmpty() || portText.isEmpty() || content.isEmpty()) {
                logWarn(logPane, "请填写IP、端口和发送内容"); return;
            }
            int port;
            try { port = Integer.parseInt(portText); }
            catch (NumberFormatException e) { logWarn(logPane, "端口格式错误"); return; }
            addHistory(content);

            FormatMode mode = fromComboIndex(formatCombo.getSelectedIndex());
            String enc = (String) encCombo.getSelectedItem();

            String finalContent;
            String nickname = nicknameField.getText().trim();
            if (mode == FormatMode.TEXT && !nickname.isEmpty()) {
                finalContent = buildMsgWithNickname(nickname, content);
            } else {
                finalContent = content;
            }

            threadPool.submit(() -> {
                try (DatagramChannel ch = DatagramChannel.open()) {
                    ch.configureBlocking(true);
                    ch.socket().setSoTimeout(3000);
                    byte[] sendData = encodeByMode(finalContent, mode, enc);
                    InetSocketAddress target = new InetSocketAddress(InetAddress.getByName(host), port);
                    ByteBuffer sendBuf = ByteBuffer.wrap(sendData);
                    ch.send(sendBuf, target);
                    logSend(logPane, "发送 → " + host + ":" + port + " " + formatBytes(sendData, mode));

                    ByteBuffer recvBuf = ByteBuffer.allocate(65535);
                    SocketAddress from = ch.receive(recvBuf);
                    if (from != null) {
                        recvBuf.flip();
                        byte[] recvData = new byte[recvBuf.remaining()];
                        recvBuf.get(recvData);
                        String recvStr = formatBytes(recvData, mode);
                        if (mode == FormatMode.TEXT) {
                            String[] parts = parseNickname(recvStr);
                            if (parts[0] != null) {
                                logRecv(logPane, "响应 ← [" + parts[0] + "]: " + parts[1]);
                            } else {
                                logRecv(logPane, "响应 ← " + recvStr);
                            }
                        } else {
                            logRecv(logPane, "响应 ← " + recvStr);
                        }
                    } else {
                        logWarn(logPane, "超时: 无响应 (3秒)");
                    }
                } catch (SocketTimeoutException e) {
                    logWarn(logPane, "超时: 无响应 (3秒)");
                } catch (Exception e) {
                    logErr(logPane, "错误: " + e.getMessage());
                }
            });
        }

        private void addHistory(String c) { history.remove(c); history.add(0, c); while (history.size() > MAX_HISTORY) history.remove(history.size() - 1); }

        private void showHistory() {
            if (history.isEmpty()) { JOptionPane.showMessageDialog(this, "暂无发送历史"); return; }
            JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "发送历史", true);
            dlg.setSize(450, 350); dlg.setLocationRelativeTo(this);
            DefaultListModel<String> model = new DefaultListModel<>();
            for (String h : history) model.addElement(h);
            JList<String> list = new JList<>(model);
            list.setFont(FONT_TEXT);
            JButton btnUse = makeBtn("使用", null);
            btnUse.addActionListener(e -> { String s = list.getSelectedValue(); if (s != null) { sendArea.setText(s); dlg.dispose(); } });
            dlg.setLayout(new BorderLayout()); dlg.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel bp = new JPanel(); bp.add(btnUse); dlg.add(bp, BorderLayout.SOUTH);
            dlg.setVisible(true);
        }
    }

    // ==================== TCP 服务端（NIO ServerSocketChannel + Selector） ====================

    private class TcpServerPanel extends JPanel {
        private JTextField portField;
        private JTextArea replyArea;
        private JTextPane logPane;
        private JComboBox<String> encCombo, formatCombo;
        private JLabel clientCountLabel;
        private ServerSocketChannel serverChannel;
        private Selector selector;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private final Map<String, SocketChannel> clients = new ConcurrentHashMap<>();
        private final Map<SocketChannel, TcpClientCtx> clientCtxMap = new ConcurrentHashMap<>();
        private Thread nioThread;
        private DefaultListModel<String> clientListModel;
        private JList<String> clientList;
        /** IP -> 昵称 映射，用于在客户端列表中显示昵称 */
        private final Map<String, String> clientNicknames = new ConcurrentHashMap<>();

        /** NIO 线程可安全读取的缓存值，在 startServer 时于 EDT 上设置 */
        private volatile String cachedReplyText = "";
        private volatile Charset cachedCharset = StandardCharsets.UTF_8;
        private volatile FormatMode cachedFormatMode = FormatMode.TEXT;

        /** NIO TCP 客户端上下文：读缓冲 + 行剩余 */
        private class TcpClientCtx {
            ByteBuffer readBuf = ByteBuffer.allocate(8192);
            StringBuilder remainder = new StringBuilder();
            String clientId;
        }

        TcpServerPanel() {
            setLayout(new BorderLayout(6, 6));
            setBorder(new EmptyBorder(6, 0, 0, 0));

            JPanel top = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            top.add(new JLabel("监听端口:"));
            portField = new JTextField("9901", 6);
            top.add(portField);

            encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1", "ASCII"});
            top.add(new JLabel("编码:"));
            top.add(encCombo);

            formatCombo = createFormatCombo();
            top.add(new JLabel("格式:"));
            top.add(formatCombo);

            JButton btnStart = makeBtn("启动", new Color(0x4CAF50));
            JButton btnStop = makeBtn("停止", new Color(0xF44336));
            btnStop.setEnabled(false);
            top.add(btnStart); top.add(btnStop);

            top.add(Box.createHorizontalStrut(16));
            top.add(new JLabel("已连接:"));
            clientCountLabel = new JLabel("0");
            clientCountLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            clientCountLabel.setForeground(C_RECV);
            top.add(clientCountLabel);

            JPanel replyPanel = new JPanel(new BorderLayout());
            replyPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "自动回复内容（收到消息后回复，留空不回复）", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));
            replyArea = new JTextArea(3, 40);
            replyArea.setFont(FONT_TEXT);
            replyPanel.add(new JScrollPane(replyArea), BorderLayout.CENTER);

            JPanel broadcastPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            JTextField broadcastField = new JTextField(30);
            broadcastField.setFont(FONT_TEXT);
            JButton btnBroadcast = makeBtn("广播发送", new Color(0xFF9800));
            btnBroadcast.addActionListener(e -> {
                String msg = broadcastField.getText().trim();
                if (msg.isEmpty()) return;
                broadcastToAll(msg);
                broadcastField.setText("");
            });
            broadcastPanel.add(new JLabel("广播:"));
            broadcastPanel.add(broadcastField);
            broadcastPanel.add(btnBroadcast);
            replyPanel.add(broadcastPanel, BorderLayout.SOUTH);

            logPane = createLogPane();

            clientListModel = new DefaultListModel<>();
            clientList = new JList<>(clientListModel);
            clientList.setFont(FONT_TEXT);
            clientList.setBackground(new Color(0x2D2D2D));
            clientList.setForeground(C_RECV);
            clientList.setSelectionBackground(new Color(0x3C3C3C));
            clientList.setSelectionForeground(Color.WHITE);
            clientList.setFixedCellHeight(22);
            JScrollPane clientListScroll = new JScrollPane(clientList);
            clientListScroll.setPreferredSize(new Dimension(170, 0));
            clientListScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "已连接客户端", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            add(top, BorderLayout.NORTH);
            JPanel center = new JPanel(new BorderLayout(6, 6));
            center.add(replyPanel, BorderLayout.NORTH);
            center.add(createLogScroll(logPane), BorderLayout.CENTER);

            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, center, clientListScroll);
            splitPane.setResizeWeight(0.78);
            splitPane.setDividerSize(4);
            add(splitPane, BorderLayout.CENTER);

            btnStart.addActionListener(e -> {
                int port;
                try { port = Integer.parseInt(portField.getText().trim()); }
                catch (NumberFormatException ex) { logWarn(logPane, "端口号格式错误"); return; }
                startServer(port, btnStart, btnStop);
            });
            btnStop.addActionListener(e -> {
                stopServer();
                btnStart.setEnabled(true); btnStop.setEnabled(false); portField.setEnabled(true);
            });
        }

        private void startServer(int port, JButton startBtn, JButton stopBtn) {
            try {
                serverChannel = ServerSocketChannel.open();
                serverChannel.configureBlocking(false);
                serverChannel.bind(new InetSocketAddress(port));
                selector = Selector.open();
                serverChannel.register(selector, SelectionKey.OP_ACCEPT);

                // 在 EDT 上捕获编码/回复内容，NIO 线程安全读取
                cachedReplyText = replyArea.getText().trim();
                String enc = (String) encCombo.getSelectedItem();
                try { cachedCharset = Charset.forName(enc); } catch (Exception e) { cachedCharset = StandardCharsets.UTF_8; }
                cachedFormatMode = fromComboIndex(formatCombo.getSelectedIndex());

                running.set(true);
                startBtn.setEnabled(false); stopBtn.setEnabled(true); portField.setEnabled(false);
                logSys(logPane, "TCP 服务端已启动（NIO），监听端口 " + port);

                nioThread = Thread.ofVirtual().name("tcp-server-nio").start(() -> {
                    while (running.get()) {
                        try {
                            if (selector.select(500) <= 0) continue;
                            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                            while (it.hasNext()) {
                                SelectionKey key = it.next();
                                it.remove();
                                try {
                                    if (key.isAcceptable()) {
                                        handleAccept(key);
                                    } else if (key.isReadable()) {
                                        handleRead(key);
                                    }
                                } catch (Exception e) {
                                    closeKey(key);
                                }
                            }
                        } catch (ClosedSelectorException e) {
                            break;
                        } catch (IOException e) {
                            if (running.get()) logErr(logPane, "Selector 错误: " + e.getMessage());
                        }
                    }
                });
            } catch (IOException e) {
                logErr(logPane, "启动失败: " + e.getMessage());
            }
        }

        private void handleAccept(SelectionKey key) throws IOException {
            ServerSocketChannel ssc = (ServerSocketChannel) key.channel();
            SocketChannel sc = ssc.accept();
            if (sc == null) return;

            sc.configureBlocking(false);
            sc.register(selector, SelectionKey.OP_READ);

            String clientId = sc.getRemoteAddress().toString().replace("/", "");
            clients.put(clientId, sc);

            TcpClientCtx ctx = new TcpClientCtx();
            ctx.clientId = clientId;
            clientCtxMap.put(sc, ctx);

            final String ip = sc.socket().getInetAddress().getHostAddress();
            SwingUtilities.invokeLater(() -> {
                clientCountLabel.setText(String.valueOf(clients.size()));
                clientListModel.addElement(ip);
            });
            logSys(logPane, "客户端连接: " + clientId);

            // 连接时自动回复
            String replyText = cachedReplyText;
            if (!replyText.isEmpty()) {
                Charset charset = cachedCharset;
                ByteBuffer out = ByteBuffer.wrap((replyText + "\n").getBytes(charset));
                while (out.hasRemaining()) sc.write(out);
                logSend(logPane, "回复(连接) [" + clientId + "] " + replyText);
            }
        }

        private void handleRead(SelectionKey key) throws IOException {
            SocketChannel sc = (SocketChannel) key.channel();
            TcpClientCtx ctx = clientCtxMap.get(sc);
            if (ctx == null) { closeKey(key); return; }

            try {
                Charset charset = cachedCharset;
                readIntoRemainder(sc, ctx.readBuf, ctx.remainder, charset);
                List<String> lines = extractLines(ctx.remainder, charset);

                FormatMode mode = cachedFormatMode;
                for (String line : lines) {
                    if (mode == FormatMode.TEXT) {
                        String[] parts = parseNickname(line);
                        if (parts[0] != null) {
                            String ip = ctx.clientId.contains(":") ? ctx.clientId.substring(0, ctx.clientId.lastIndexOf(':')) : ctx.clientId;
                            String oldNickname = clientNicknames.put(ip, parts[0]);
                            if (oldNickname == null || !oldNickname.equals(parts[0])) {
                                final String display = parts[0] + "(" + ip + ")";
                                SwingUtilities.invokeLater(() -> updateClientListEntry(ip, display));
                            }
                            logRecv(logPane, "收到 [" + ctx.clientId + "] [" + parts[0] + "]: " + parts[1]);
                        } else {
                            logRecv(logPane, "收到 [" + ctx.clientId + "] " + line);
                        }
                    } else {
                        logRecv(logPane, "收到 [" + ctx.clientId + "] " + line);
                    }

                    String replyText = cachedReplyText;
                    if (!replyText.isEmpty()) {
                        ByteBuffer out = ByteBuffer.wrap((replyText + "\n").getBytes(charset));
                        while (out.hasRemaining()) sc.write(out);
                        logSend(logPane, "回复 [" + ctx.clientId + "] " + replyText);
                    }
                }
            } catch (EOFException e) {
                closeKey(key);
            }
        }

        private void closeKey(SelectionKey key) {
            SocketChannel sc = (SocketChannel) key.channel();
            TcpClientCtx ctx = clientCtxMap.remove(sc);
            String clientId = ctx != null ? ctx.clientId : "unknown";
            clients.remove(clientId);

            String ip;
            try { ip = sc.socket().getInetAddress().getHostAddress(); }
            catch (Exception e) { ip = "?"; }

            key.cancel();
            try { sc.close(); } catch (IOException ignored) {}

            final String finalIp = ip;
            SwingUtilities.invokeLater(() -> {
                clientCountLabel.setText(String.valueOf(clients.size()));
                String display = clientNicknames.remove(finalIp);
                if (display != null) {
                    clientListModel.removeElement(display + "(" + finalIp + ")");
                } else {
                    clientListModel.removeElement(finalIp);
                }
            });
            logSys(logPane, "客户端断开: " + clientId);
        }

        private void broadcastToAll(String msg) {
            if (clients.isEmpty()) { logWarn(logPane, "没有连接的客户端"); return; }
            logSend(logPane, "广播 → " + clients.size() + " 个客户端: " + msg);
            String enc = (String) encCombo.getSelectedItem();
            Charset charset;
            try { charset = Charset.forName(enc); } catch (Exception e) { charset = StandardCharsets.UTF_8; }
            Charset finalCharset = charset;
            for (Map.Entry<String, SocketChannel> entry : clients.entrySet()) {
                threadPool.submit(() -> {
                    try {
                        ByteBuffer out = ByteBuffer.wrap((msg + "\n").getBytes(finalCharset));
                        while (out.hasRemaining()) entry.getValue().write(out);
                    } catch (IOException e) {
                        logErr(logPane, "广播失败 [" + entry.getKey() + "]: " + e.getMessage());
                    }
                });
            }
        }

        /** 在 EDT 上更新客户端列表项：将 oldEntry 替换为 newEntry */
        private void updateClientListEntry(String oldEntry, String newEntry) {
            int idx = clientListModel.indexOf(oldEntry);
            if (idx >= 0) {
                clientListModel.set(idx, newEntry);
            }
        }

        private void stopServer() {
            running.set(false);
            try { if (selector != null) selector.close(); } catch (IOException ignored) {}
            try { if (serverChannel != null) serverChannel.close(); } catch (IOException ignored) {}
            for (SocketChannel sc : clients.values()) { try { sc.close(); } catch (IOException ignored) {} }
            clients.clear();
            clientCtxMap.clear();
            clientNicknames.clear();
            SwingUtilities.invokeLater(() -> {
                clientCountLabel.setText("0");
                clientListModel.clear();
            });
            logSys(logPane, "TCP 服务端已停止");
        }
    }

    // ==================== TCP 客户端（NIO SocketChannel + Selector） ====================

    private class TcpClientPanel extends JPanel {
        private JTextField hostField, portField, nicknameField;
        private JTextArea sendArea;
        private JTextPane logPane;
        private JComboBox<String> encCombo, formatCombo;
        private final List<String> history = new ArrayList<>();
        private static final int MAX_HISTORY = 50;
        private SocketChannel channel;
        private Selector selector;
        private final AtomicBoolean connected = new AtomicBoolean(false);
        private ByteBuffer readBuf = ByteBuffer.allocate(8192);
        private StringBuilder readRemainder = new StringBuilder();
        private Thread nioThread;
        private Charset currentCharset = StandardCharsets.UTF_8;

        TcpClientPanel() {
            setLayout(new BorderLayout(6, 6));
            setBorder(new EmptyBorder(6, 0, 0, 0));

            Box top = Box.createVerticalBox();

            // 第一行：目标地址 + 连接操作
            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            row1.add(new JLabel("目标IP:"));
            hostField = new JTextField("192.168.4.66", 10);
            row1.add(hostField);
            row1.add(new JLabel("端口:"));
            portField = new JTextField("9901", 6);
            row1.add(portField);
            JButton btnConnect = makeBtn("连接", new Color(0x4CAF50));
            JButton btnDisconnect = makeBtn("断开", new Color(0xF44336));
            btnDisconnect.setEnabled(false);
            row1.add(btnConnect); row1.add(btnDisconnect);

            // 第二行：昵称 + 编码 + 格式
            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            row2.add(new JLabel("昵称:"));
            nicknameField = new JTextField(6);
            nicknameField.setToolTipText("设置昵称后，消息将自动带上 [昵称]: 前缀");
            row2.add(nicknameField);

            encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1", "ASCII"});
            row2.add(new JLabel("编码:"));
            row2.add(encCombo);

            formatCombo = createFormatCombo();
            row2.add(new JLabel("格式:"));
            row2.add(formatCombo);

            top.add(row1);
            top.add(row2);

            JPanel sendPanel = new JPanel(new BorderLayout());
            sendPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "发送内容（每行一条消息）", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));
            sendArea = new JTextArea(4, 40);
            sendArea.setFont(FONT_TEXT);
            sendPanel.add(new JScrollPane(sendArea), BorderLayout.CENTER);

            JPanel sendBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 4));
            JButton btnSend = makeBtn("发送 (Ctrl+Enter)", new Color(0x2196F3));
            JButton btnClear = makeBtn("清除", null);
            JButton btnHistory = makeBtn("历史", null);
            sendBtnRow.add(btnSend); sendBtnRow.add(btnClear); sendBtnRow.add(btnHistory);
            sendPanel.add(sendBtnRow, BorderLayout.SOUTH);

            logPane = createLogPane();

            add(top, BorderLayout.NORTH);
            JPanel center = new JPanel(new BorderLayout(6, 6));
            center.add(sendPanel, BorderLayout.NORTH);
            center.add(createLogScroll(logPane), BorderLayout.CENTER);
            add(center, BorderLayout.CENTER);

            btnConnect.addActionListener(e -> connect(btnConnect, btnDisconnect));
            btnDisconnect.addActionListener(e -> {
                disconnect();
                btnConnect.setEnabled(true); btnDisconnect.setEnabled(false);
                hostField.setEnabled(true); portField.setEnabled(true);
            });
            btnSend.addActionListener(e -> doSend());
            btnClear.addActionListener(e -> sendArea.setText(""));
            btnHistory.addActionListener(e -> showHistory());

            sendArea.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
            sendArea.getActionMap().put("send", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) { doSend(); }
            });
        }

        private void connect(JButton connBtn, JButton disBtn) {
            String host = hostField.getText().trim();
            int port;
            try { port = Integer.parseInt(portField.getText().trim()); }
            catch (NumberFormatException e) { logWarn(logPane, "端口格式错误"); return; }

            String enc = (String) encCombo.getSelectedItem();
            Charset charset;
            try { charset = Charset.forName(enc); } catch (Exception e) { charset = StandardCharsets.UTF_8; }
            currentCharset = charset;
            String nickname = nicknameField.getText().trim();

            Charset finalCharset = charset;
            threadPool.submit(() -> {
                try {
                    channel = SocketChannel.open();
                    channel.configureBlocking(false);
                    channel.connect(new InetSocketAddress(host, port));
                    selector = Selector.open();
                    channel.register(selector, SelectionKey.OP_CONNECT);
                    connected.set(true);
                    SwingUtilities.invokeLater(() -> {
                        connBtn.setEnabled(false); disBtn.setEnabled(true);
                        hostField.setEnabled(false); portField.setEnabled(false);
                    });
                    logSys(logPane, "正在连接 " + host + ":" + port + " ...");

                    // 连接完成 → 注册 OP_READ，开始读循环
                    if (selector.select(5000) > 0) {
                        Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                        while (it.hasNext()) {
                            SelectionKey key = it.next();
                            it.remove();
                            if (key.isConnectable()) {
                                SocketChannel sc = (SocketChannel) key.channel();
                                if (sc.finishConnect()) {
                                    logSys(logPane, "已连接 " + host + ":" + port);
                                    key.interestOps(SelectionKey.OP_READ);

                                    // 连接成功时立即发送昵称，让服务端列表直接显示
                                    if (!nickname.isEmpty()) {
                                        String hello = "[" + nickname + "]: \n";
                                        ByteBuffer out = ByteBuffer.wrap(hello.getBytes(finalCharset));
                                        while (out.hasRemaining()) sc.write(out);
                                        logSend(logPane, "发送 → " + hello.trim());
                                    }
                                } else {
                                    logErr(logPane, "连接失败");
                                    disconnect();
                                    return;
                                }
                            }
                        }
                    } else {
                        logErr(logPane, "连接超时 (5秒)");
                        disconnect();
                        return;
                    }

                    readRemainder.setLength(0);
                    // 读循环
                    while (connected.get()) {
                        try {
                            if (selector.select(500) <= 0) continue;
                            Iterator<SelectionKey> it = selector.selectedKeys().iterator();
                            while (it.hasNext()) {
                                SelectionKey key = it.next();
                                it.remove();
                                if (!key.isReadable()) continue;
                                try {
                                    readIntoRemainder((SocketChannel) key.channel(), readBuf, readRemainder, finalCharset);
                                    List<String> lines = extractLines(readRemainder, finalCharset);
                                    FormatMode mode = fromComboIndex(formatCombo.getSelectedIndex());
                                    for (String line : lines) {
                                        if (mode == FormatMode.TEXT) {
                                            String[] parts = parseNickname(line);
                                            if (parts[0] != null) {
                                                logRecv(logPane, "响应 ← [" + parts[0] + "]: " + parts[1]);
                                            } else {
                                                logRecv(logPane, "响应 ← " + line);
                                            }
                                        } else {
                                            logRecv(logPane, "响应 ← " + line);
                                        }
                                    }
                                } catch (EOFException e) {
                                    logSys(logPane, "服务器断开连接");
                                    disconnect();
                                    return;
                                }
                            }
                        } catch (ClosedSelectorException e) {
                            break;
                        } catch (IOException e) {
                            if (connected.get()) logErr(logPane, "读取错误: " + e.getMessage());
                        }
                    }
                } catch (IOException e) {
                    if (connected.get()) logErr(logPane, "连接失败: " + e.getMessage());
                    disconnect();
                }
            });
        }

        private void disconnect() {
            boolean wasConnected = connected.getAndSet(false);
            try { if (selector != null) selector.close(); } catch (IOException ignored) {}
            try { if (channel != null) channel.close(); } catch (IOException ignored) {}
            if (wasConnected) {
                SwingUtilities.invokeLater(() -> {
                    logSys(logPane, "已断开");
                });
            }
        }

        private void doSend() {
            if (!connected.get() || channel == null) { logWarn(logPane, "未连接"); return; }
            String content = sendArea.getText().trim();
            if (content.isEmpty()) return;
            addHistory(content);

            FormatMode mode = fromComboIndex(formatCombo.getSelectedIndex());
            String enc = (String) encCombo.getSelectedItem();
            String nickname = nicknameField.getText().trim();
            Charset charset;
            try { charset = Charset.forName(enc); } catch (Exception e) { charset = StandardCharsets.UTF_8; }

            Charset finalCharset = charset;
            threadPool.submit(() -> {
                try {
                    String[] lines = content.split("\\n");
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;
                        if (mode == FormatMode.HEX) {
                            byte[] data = hexToBytes(line);
                            ByteBuffer out = ByteBuffer.wrap(data);
                            while (out.hasRemaining()) channel.write(out);
                            logSend(logPane, "发送 → HEX: " + bytesToHex(data));
                        } else if (mode == FormatMode.BIN) {
                            byte[] data = binToBytes(line);
                            ByteBuffer out = ByteBuffer.wrap(data);
                            while (out.hasRemaining()) channel.write(out);
                            logSend(logPane, "发送 → BIN: " + bytesToBin(data));
                        } else {
                            String finalLine = !nickname.isEmpty() ? buildMsgWithNickname(nickname, line) : line;
                            ByteBuffer out = ByteBuffer.wrap((finalLine + "\n").getBytes(finalCharset));
                            while (out.hasRemaining()) channel.write(out);
                            logSend(logPane, "发送 → " + finalLine);
                        }
                    }
                } catch (IOException e) {
                    logErr(logPane, "发送失败: " + e.getMessage());
                }
            });
        }

        private void addHistory(String c) { history.remove(c); history.add(0, c); while (history.size() > MAX_HISTORY) history.remove(history.size() - 1); }

        private void showHistory() {
            if (history.isEmpty()) { JOptionPane.showMessageDialog(this, "暂无发送历史"); return; }
            JDialog dlg = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "发送历史", true);
            dlg.setSize(450, 350); dlg.setLocationRelativeTo(this);
            DefaultListModel<String> model = new DefaultListModel<>();
            for (String h : history) model.addElement(h);
            JList<String> list = new JList<>(model);
            list.setFont(FONT_TEXT);
            JButton btnUse = makeBtn("使用", null);
            btnUse.addActionListener(e -> { String s = list.getSelectedValue(); if (s != null) { sendArea.setText(s); dlg.dispose(); } });
            dlg.setLayout(new BorderLayout()); dlg.add(new JScrollPane(list), BorderLayout.CENTER);
            JPanel bp = new JPanel(); bp.add(btnUse); dlg.add(bp, BorderLayout.SOUTH);
            dlg.setVisible(true);
        }
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(udpServerPanel.portField, config, "udpServer.port");
        loadArea(udpServerPanel.replyArea, config, "udpServer.reply");
        loadCombo(udpServerPanel.encCombo, config, "udpServer.enc");
        loadCombo(udpServerPanel.formatCombo, config, "udpServer.format");

        loadField(udpClientPanel.hostField, config, "udpClient.host");
        loadField(udpClientPanel.portField, config, "udpClient.port");
        loadField(udpClientPanel.nicknameField, config, "udpClient.nickname");

        loadField(tcpServerPanel.portField, config, "tcpServer.port");
        loadArea(tcpServerPanel.replyArea, config, "tcpServer.reply");

        loadField(tcpClientPanel.hostField, config, "tcpClient.host");
        loadField(tcpClientPanel.portField, config, "tcpClient.port");
        loadField(tcpClientPanel.nicknameField, config, "tcpClient.nickname");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(udpServerPanel.portField, config, "udpServer.port");
        saveArea(udpServerPanel.replyArea, config, "udpServer.reply");
        saveCombo(udpServerPanel.encCombo, config, "udpServer.enc");
        saveCombo(udpServerPanel.formatCombo, config, "udpServer.format");

        saveField(udpClientPanel.hostField, config, "udpClient.host");
        saveField(udpClientPanel.portField, config, "udpClient.port");
        saveField(udpClientPanel.nicknameField, config, "udpClient.nickname");

        saveField(tcpServerPanel.portField, config, "tcpServer.port");
        saveArea(tcpServerPanel.replyArea, config, "tcpServer.reply");

        saveField(tcpClientPanel.hostField, config, "tcpClient.host");
        saveField(tcpClientPanel.portField, config, "tcpClient.port");
        saveField(tcpClientPanel.nicknameField, config, "tcpClient.nickname");
    }

    private void loadField(JTextField f, ConfigManager cfg, String key) {
        String v = cfg.get(key, null);
        if (v != null && !v.isEmpty()) f.setText(v);
    }

    private void saveField(JTextField f, ConfigManager cfg, String key) {
        cfg.set(key, f.getText().trim());
    }

    private void loadArea(JTextArea a, ConfigManager cfg, String key) {
        String v = cfg.get(key, null);
        if (v != null && !v.isEmpty()) a.setText(v);
    }

    private void saveArea(JTextArea a, ConfigManager cfg, String key) {
        cfg.set(key, a.getText().trim());
    }

    private void loadCombo(JComboBox<String> c, ConfigManager cfg, String key) {
        String v = cfg.get(key, null);
        if (v != null && !v.isEmpty()) c.setSelectedItem(v);
    }

    private void saveCombo(JComboBox<String> c, ConfigManager cfg, String key) {
        Object sel = c.getSelectedItem();
        if (sel != null) cfg.set(key, sel.toString());
    }
}
