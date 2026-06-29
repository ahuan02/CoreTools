package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;
import com.szh.utils.ThreadPoolUtil;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.szh.utils.NetUtil.*;

/**
 * UDP 调试面板：服务端 + 客户端（Java NIO 非阻塞实现）
 */
public class UdpPanel extends AbstractCommandPanel {

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();

    private UdpServerPanel udpServerPanel;
    private UdpClientPanel udpClientPanel;

    public UdpPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        udpServerPanel = new UdpServerPanel();
        udpClientPanel = new UdpClientPanel();

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, udpServerPanel, udpClientPanel);
        split.setResizeWeight(0.75);
        split.setDividerSize(5);
        add(split, BorderLayout.CENTER);
    }

    // ==================== UDP 服务端 ====================

    private class UdpServerPanel extends JPanel {
        private JTextField portField;
        private JTextArea replyArea;
        private JTextPane logPane;
        private JComboBox<String> encCombo, formatCombo;
        private DatagramChannel serverChannel;
        private Selector selector;
        private final AtomicBoolean running = new AtomicBoolean(false);
        private java.util.concurrent.Future<?> nioFuture;
        private final Map<String, InetSocketAddress> recentClients = new LinkedHashMap<>();
        private volatile String cachedReplyText = "";
        private volatile Charset cachedCharset = StandardCharsets.UTF_8;
        private volatile FormatMode cachedFormatMode = FormatMode.TEXT;
        private DefaultListModel<String> clientListModel;
        private JList<String> clientList;
        private final Map<String, String> clientNicknames = new ConcurrentHashMap<>();
        private final java.util.Set<String> shownIps = new java.util.HashSet<>();

        UdpServerPanel() {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "UDP 服务端", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            JPanel topPanel = new JPanel(new GridLayout(2, 1, 0, 2));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row1.add(new JLabel("端口:"));
            portField = new JTextField("9900", 5);
            row1.add(portField);
            encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1", "ASCII"});
            encCombo.setPreferredSize(new Dimension(80, 24));
            row1.add(new JLabel("编码:"));
            row1.add(encCombo);
            formatCombo = createFormatCombo();
            row1.add(new JLabel("格式:"));
            row1.add(formatCombo);

            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            JButton btnStart = makeBtn("启动", new Color(0x4CAF50));
            JButton btnStop = makeBtn("停止", new Color(0xF44336));
            btnStop.setEnabled(false);
            JButton btnBroadcast = makeBtn("广播", new Color(0xFF9800));
            JTextField broadcastField = new JTextField(12);
            broadcastField.setFont(FONT_TEXT);
            btnBroadcast.addActionListener(e -> {
                String msg = broadcastField.getText().trim();
                if (msg.isEmpty()) return;
                broadcastToAll(msg);
                broadcastField.setText("");
            });
            row2.add(btnStart);
            row2.add(btnStop);
            row2.add(Box.createHorizontalStrut(8));
            row2.add(new JLabel("广播:"));
            row2.add(broadcastField);
            row2.add(btnBroadcast);

            topPanel.add(row1);
            topPanel.add(row2);

            JPanel replyPanel = new JPanel(new BorderLayout(2, 2));
            replyPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "自动回复（留空不回复）", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));
            replyArea = new JTextArea(1, 20);
            replyArea.setFont(FONT_TEXT);
            NetUtil.fixPaste(replyArea);
            replyArea.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
                @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { syncReply(); }
                @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { syncReply(); }
                @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { syncReply(); }
                private void syncReply() { cachedReplyText = replyArea.getText().trim(); }
            });
            replyPanel.add(new JScrollPane(replyArea), BorderLayout.CENTER);

            clientListModel = new DefaultListModel<>();
            clientList = new JList<>(clientListModel);
            clientList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            clientList.setBackground(new Color(0x2D2D2D));
            clientList.setForeground(C_RECV);
            clientList.setSelectionBackground(new Color(0x3C3C3C));
            clientList.setSelectionForeground(Color.WHITE);
            clientList.setFixedCellHeight(20);

            logPane = createLogPane();

            JPanel topArea = new JPanel(new BorderLayout(4, 0));
            topArea.add(topPanel, BorderLayout.NORTH);
            topArea.add(replyPanel, BorderLayout.SOUTH);

            // 左侧：控件区 + 日志
            JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
            leftPanel.add(topArea, BorderLayout.NORTH);
            leftPanel.add(createLogScroll(logPane), BorderLayout.CENTER);

            // 右侧：客户端列表
            JScrollPane clientScroll = new JScrollPane(clientList);
            clientScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "客户端列表", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));
            clientScroll.setPreferredSize(new Dimension(150, 100));

            // 服务端整体：左右分栏
            JSplitPane serverSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, clientScroll);
            serverSplit.setResizeWeight(0.82);
            serverSplit.setDividerSize(4);
            add(serverSplit, BorderLayout.CENTER);

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

                cachedReplyText = replyArea.getText().trim();
                String enc = (String) encCombo.getSelectedItem();
                try { cachedCharset = Charset.forName(enc); } catch (Exception e) { cachedCharset = StandardCharsets.UTF_8; }
                cachedFormatMode = fromComboIndex(formatCombo.getSelectedIndex());

                running.set(true);
                startBtn.setEnabled(false);
                stopBtn.setEnabled(true);
                portField.setEnabled(false);
                logSys(logPane, "UDP 服务端已启动（NIO），监听端口 " + port);

                nioFuture = ThreadPoolUtil.submitVirtual(() -> {
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
                                if (isNew && shownIps.add(clientIp)) {
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
            shownIps.clear();
            SwingUtilities.invokeLater(() -> clientListModel.clear());
            logSys(logPane, "UDP 服务端已停止");
        }
    }

    // ==================== UDP 客户端 ====================

    private class UdpClientPanel extends JPanel {
        private JTextField hostField, portField, nicknameField;
        private JTextArea sendArea;
        private JTextPane logPane;
        private JComboBox<String> encCombo, formatCombo;
        private final List<String> history = new ArrayList<>();
        private static final int MAX_HISTORY = 50;
        // 持久化 UDP 通道 + 持续接收
        private DatagramChannel clientChannel;
        private final AtomicBoolean clientReceiving = new AtomicBoolean(false);
        private java.util.concurrent.Future<?> clientReceiveFuture;
        private volatile FormatMode clientMode;
        private volatile Charset clientCharset;

        UdpClientPanel() {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "UDP 客户端", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            JPanel topPanel = new JPanel(new GridLayout(2, 1, 0, 2));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row1.add(new JLabel("目标IP:"));
            hostField = new JTextField("192.168.4.66", 9);
            row1.add(hostField);
            row1.add(new JLabel("端口:"));
            portField = new JTextField("9000", 5);
            row1.add(portField);
            row1.add(new JLabel("昵称:"));
            nicknameField = new JTextField(5);
            nicknameField.setToolTipText("设置昵称后，消息将自动带上 [昵称]: 前缀");
            row1.add(nicknameField);

            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1", "ASCII"});
            encCombo.setPreferredSize(new Dimension(80, 24));
            row2.add(new JLabel("编码:"));
            row2.add(encCombo);
            formatCombo = createFormatCombo();
            row2.add(new JLabel("格式:"));
            row2.add(formatCombo);

            topPanel.add(row1);
            topPanel.add(row2);

            JPanel sendPanel = new JPanel(new BorderLayout());
            sendPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "发送内容", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));
            sendArea = new JTextArea(2, 20);
            sendArea.setFont(FONT_TEXT);
            NetUtil.fixPaste(sendArea);
            sendPanel.add(new JScrollPane(sendArea), BorderLayout.CENTER);

            JPanel sendBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JButton btnSend = makeBtn("发送", new Color(0x2196F3));
            JButton btnClear = makeBtn("清除", null);
            JButton btnHistory = makeBtn("历史", null);
            JButton btnQuick1 = makeBtn("HELLO", null);
            JButton btnQuick2 = makeBtn("STATUS", null);
            JButton btnQuick3 = makeBtn("VERSION", null);
            sendBtnRow.add(btnSend);
            sendBtnRow.add(btnClear);
            sendBtnRow.add(btnHistory);
            sendBtnRow.add(Box.createHorizontalStrut(6));
            sendBtnRow.add(btnQuick1);
            sendBtnRow.add(btnQuick2);
            sendBtnRow.add(btnQuick3);
            sendPanel.add(sendBtnRow, BorderLayout.SOUTH);

            logPane = createLogPane();

            JPanel topArea = new JPanel(new BorderLayout(4, 4));
            topArea.add(topPanel, BorderLayout.NORTH);
            topArea.add(sendPanel, BorderLayout.CENTER);

            add(topArea, BorderLayout.NORTH);
            add(createLogScroll(logPane), BorderLayout.CENTER);

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
            clientMode = mode;
            try { clientCharset = Charset.forName(enc); } catch (Exception e) { clientCharset = StandardCharsets.UTF_8; }

            String finalContent;
            String nickname = nicknameField.getText().trim();
            if (mode == FormatMode.TEXT && !nickname.isEmpty()) {
                finalContent = buildMsgWithNickname(nickname, content);
            } else {
                finalContent = content;
            }

            // 确保持久通道存在，保持同一本地端口以便硬件持续回推数据
            if (clientChannel == null || !clientChannel.isOpen()) {
                try {
                    clientChannel = DatagramChannel.open();
                    clientChannel.configureBlocking(false);
                    clientChannel.bind(null);
                } catch (IOException e) {
                    logErr(logPane, "创建通道失败: " + e.getMessage());
                    return;
                }
            }

            final DatagramChannel ch = clientChannel;
            threadPool.submit(() -> {
                try {
                    byte[] sendData = encodeByMode(finalContent, mode, enc);
                    InetSocketAddress target = new InetSocketAddress(InetAddress.getByName(host), port);
                    ByteBuffer sendBuf = ByteBuffer.wrap(sendData);
                    ch.send(sendBuf, target);
                    logSend(logPane, "发送 → " + host + ":" + port + " " + formatBytes(sendData, mode));

                    // 如果未启动持续接收，则启动
                    if (!clientReceiving.get()) {
                        startContinuousReceive(ch, mode);
                    }
                } catch (Exception e) {
                    logErr(logPane, "发送失败: " + e.getMessage());
                }
            });
        }

        private void startContinuousReceive(DatagramChannel ch, FormatMode mode) {
            if (!clientReceiving.compareAndSet(false, true)) return;

            clientReceiveFuture = ThreadPoolUtil.submitVirtual(() -> {
                ByteBuffer recvBuf = ByteBuffer.allocate(65535);
                while (clientReceiving.get() && ch.isOpen()) {
                    try {
                        recvBuf.clear();
                        SocketAddress from = ch.receive(recvBuf);
                        if (from != null) {
                            recvBuf.flip();
                            byte[] recvData = new byte[recvBuf.remaining()];
                            recvBuf.get(recvData);
                            FormatMode m = clientMode != null ? clientMode : mode;
                            String recvStr = formatBytes(recvData, m);
                            if (m == FormatMode.TEXT) {
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
                            // 非阻塞模式下无数据，短暂休眠避免空转
                            Thread.sleep(50);
                        }
                    } catch (ClosedChannelException | InterruptedException e) {
                        break;
                    } catch (IOException e) {
                        if (clientReceiving.get()) logErr(logPane, "接收错误: " + e.getMessage());
                    }
                }
                // 循环退出后置标志
                clientReceiving.set(false);
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
    }
}
