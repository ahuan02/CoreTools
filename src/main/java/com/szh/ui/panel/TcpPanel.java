package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.EOFException;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.szh.utils.NetUtil.*;

/**
 * TCP 调试面板：服务端 + 客户端（Java NIO 非阻塞实现）
 */
public class TcpPanel extends AbstractCommandPanel {

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();

    private TcpServerPanel tcpServerPanel;
    private TcpClientPanel tcpClientPanel;

    public TcpPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        tcpServerPanel = new TcpServerPanel();
        tcpClientPanel = new TcpClientPanel();

        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT, tcpServerPanel, tcpClientPanel);
        split.setResizeWeight(0.75);
        split.setDividerSize(5);
        add(split, BorderLayout.CENTER);
    }

    // ==================== TCP 服务端 ====================

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
        private final Map<String, String> clientNicknames = new ConcurrentHashMap<>();
        private volatile String cachedReplyText = "";
        private volatile Charset cachedCharset = StandardCharsets.UTF_8;
        private volatile FormatMode cachedFormatMode = FormatMode.TEXT;

        private class TcpClientCtx {
            ByteBuffer readBuf = ByteBuffer.allocate(8192);
            StringBuilder remainder = new StringBuilder();
            String clientId;
        }

        TcpServerPanel() {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "TCP 服务端", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            JPanel topPanel = new JPanel(new GridLayout(2, 1, 0, 2));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row1.add(new JLabel("端口:"));
            portField = new JTextField("9901", 5);
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
            row2.add(new JLabel("已连接:"));
            clientCountLabel = new JLabel("0");
            clientCountLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
            clientCountLabel.setForeground(C_RECV);
            row2.add(clientCountLabel);
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
            replyPanel.add(new JScrollPane(replyArea), BorderLayout.CENTER);

            clientListModel = new DefaultListModel<>();
            clientList = new JList<>(clientListModel);
            clientList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            clientList.setBackground(new Color(0x2D2D2D));
            clientList.setForeground(C_RECV);
            clientList.setSelectionBackground(new Color(0x3C3C3C));
            clientList.setSelectionForeground(Color.WHITE);
            clientList.setFixedCellHeight(22);
            clientList.setCellRenderer(new ClientListRenderer());
            clientList.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    int index = clientList.locationToIndex(e.getPoint());
                    if (index < 0) return;
                    java.awt.Rectangle bounds = clientList.getCellBounds(index, index);
                    if (bounds == null) return;
                    // 判断点击是否在右侧踢出按钮区域
                    int btnX = bounds.x + bounds.width - 55;
                    if (e.getX() >= btnX && e.getX() <= btnX + 50) {
                        String item = clientListModel.get(index);
                        kickClient(item);
                    }
                }
            });

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

        /** 踢出指定客户端 */
        private void kickClient(String item) {
            String display = stripKickBtn(item);
            int ret = JOptionPane.showConfirmDialog(TcpServerPanel.this,
                    "确定要踢出客户端 \"" + display + "\" 吗？",
                    "确认踢出", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (ret != JOptionPane.YES_OPTION) return;

            // 找出对应的 clientId（IP:port 格式）
            String targetClientId = null;
            for (Map.Entry<String, SocketChannel> entry : clients.entrySet()) {
                String ip = entry.getKey().contains(":") ? entry.getKey().substring(0, entry.getKey().lastIndexOf(':')) : entry.getKey();
                String nickname = clientNicknames.get(ip);
                String expected = (nickname != null) ? (nickname + "(" + ip + ")") : ip;
                if (display.equals(expected)) {
                    targetClientId = entry.getKey();
                    break;
                }
            }
            if (targetClientId == null) return;
            SocketChannel sc = clients.remove(targetClientId);
            if (sc == null) return;
            TcpClientCtx ctx = clientCtxMap.remove(sc);
            String ip;
            try { ip = sc.socket().getInetAddress().getHostAddress(); } catch (Exception e) { ip = "?"; }
            try { sc.close(); } catch (IOException ignored) {}
            final String finalIp = ip;
            SwingUtilities.invokeLater(() -> {
                clientCountLabel.setText(String.valueOf(clients.size()));
                String nickname = clientNicknames.remove(finalIp);
                if (nickname != null) {
                    clientListModel.removeElement(nickname + "(" + finalIp + ")");
                } else {
                    clientListModel.removeElement(finalIp);
                }
            });
            logSys(logPane, "已踢出客户端: " + targetClientId);
        }

        private static String stripKickBtn(String item) {
            int idx = item.lastIndexOf("  [");
            if (idx > 0) return item.substring(0, idx);
            return item;
        }

        /** 自定义列表渲染器：左侧显示名称，右侧红色踢出按钮 */
        private static class ClientListRenderer extends JPanel implements ListCellRenderer<String> {
            private final JLabel nameLabel;
            private final JLabel kickLabel;

            ClientListRenderer() {
                setLayout(new BorderLayout(0, 0));
                setOpaque(true);
                nameLabel = new JLabel();
                nameLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
                nameLabel.setForeground(new Color(0x81C784));
                kickLabel = new JLabel("[踢出]");
                kickLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 10));
                kickLabel.setForeground(new Color(0xE57373));
                kickLabel.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 4));
                add(nameLabel, BorderLayout.CENTER);
                add(kickLabel, BorderLayout.EAST);
            }

            @Override
            public Component getListCellRendererComponent(JList<? extends String> list,
                    String value, int index, boolean isSelected, boolean cellHasFocus) {
                String name = stripKickBtn(value);
                nameLabel.setText(name);
                if (isSelected) {
                    setBackground(new Color(0x3C3C3C));
                    nameLabel.setForeground(Color.WHITE);
                } else {
                    setBackground(new Color(0x2D2D2D));
                    nameLabel.setForeground(new Color(0x81C784));
                }
                return this;
            }
        }
    }

    // ==================== TCP 客户端 ====================

    private class TcpClientPanel extends JPanel {
        private JTextField hostField, portField, nicknameField;
        private JTextArea sendArea;
        private JTextPane logPane;
        private JComboBox<String> encCombo, formatCombo;
        private JButton btnConnect, btnDisconnect;
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
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "TCP 客户端", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            JPanel topPanel = new JPanel(new GridLayout(2, 1, 0, 2));

            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row1.add(new JLabel("目标IP:"));
            hostField = new JTextField("192.168.4.66", 9);
            row1.add(hostField);
            row1.add(new JLabel("端口:"));
            portField = new JTextField("9901", 5);
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
            btnConnect = makeBtn("连接", new Color(0x4CAF50));
            btnDisconnect = makeBtn("断开", new Color(0xF44336));
            btnDisconnect.setEnabled(false);
            row2.add(Box.createHorizontalStrut(6));
            row2.add(btnConnect);
            row2.add(btnDisconnect);

            topPanel.add(row1);
            topPanel.add(row2);

            JPanel sendPanel = new JPanel(new BorderLayout());
            sendPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "发送内容（每行一条消息）", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));
            sendArea = new JTextArea(2, 20);
            sendArea.setFont(FONT_TEXT);
            NetUtil.fixPaste(sendArea);
            sendPanel.add(new JScrollPane(sendArea), BorderLayout.CENTER);

            JPanel sendBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            JButton btnSend = makeBtn("发送", new Color(0x2196F3));
            JButton btnClear = makeBtn("清除", null);
            JButton btnHistory = makeBtn("历史", null);
            sendBtnRow.add(btnSend); sendBtnRow.add(btnClear); sendBtnRow.add(btnHistory);
            sendPanel.add(sendBtnRow, BorderLayout.SOUTH);

            logPane = createLogPane();

            JPanel topArea = new JPanel(new BorderLayout(4, 4));
            topArea.add(topPanel, BorderLayout.NORTH);
            topArea.add(sendPanel, BorderLayout.CENTER);

            add(topArea, BorderLayout.NORTH);
            add(createLogScroll(logPane), BorderLayout.CENTER);

            btnConnect.addActionListener(e -> connect());
            btnDisconnect.addActionListener(e -> {
                disconnect();
                updateButtonState(false);
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

        /** 统一更新按钮和输入框的启用/禁用状态 */
        private void updateButtonState(boolean isConnected) {
            SwingUtilities.invokeLater(() -> {
                btnConnect.setEnabled(!isConnected);
                btnDisconnect.setEnabled(isConnected);
                hostField.setEnabled(!isConnected);
                portField.setEnabled(!isConnected);
            });
        }

        private void connect() {
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
                    updateButtonState(true);
                    logSys(logPane, "正在连接 " + host + ":" + port + " ...");

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
                    updateButtonState(false);
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
        loadField(tcpServerPanel.portField, config, "tcpServer.port");
        loadArea(tcpServerPanel.replyArea, config, "tcpServer.reply");

        loadField(tcpClientPanel.hostField, config, "tcpClient.host");
        loadField(tcpClientPanel.portField, config, "tcpClient.port");
        loadField(tcpClientPanel.nicknameField, config, "tcpClient.nickname");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(tcpServerPanel.portField, config, "tcpServer.port");
        saveArea(tcpServerPanel.replyArea, config, "tcpServer.reply");

        saveField(tcpClientPanel.hostField, config, "tcpClient.host");
        saveField(tcpClientPanel.portField, config, "tcpClient.port");
        saveField(tcpClientPanel.nicknameField, config, "tcpClient.nickname");
    }
}
