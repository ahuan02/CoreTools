package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.*;
import java.util.concurrent.*;

import static com.szh.utils.NetUtil.*;

/**
 * WebSocket 面板：客户端 + 服务端合并
 */
public class WebSocketPanel extends AbstractCommandPanel {

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();

    // ===== 客户端控件 =====
    private JTextField urlField;
    private JButton btnConnect;
    private JButton btnDisconnect;
    private JComboBox<String> clientFormatCombo;
    private volatile WebSocket webSocket;
    private volatile boolean clientConnected;
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .build();

    // ===== 服务端控件 =====
    private JTextField portField;
    private JTextField pathField;
    private JTextField heartbeatField;
    private JButton btnStart;
    private JButton btnStop;
    private JLabel clientCountLabel;
    private JComboBox<String> serverFormatCombo;
    private volatile ServerSocket serverSocket;
    private volatile boolean serverRunning;
    private final Map<String, WebSocketHandle> clients = new ConcurrentHashMap<>();
    private DefaultListModel<String> clientListModel;
    private JList<String> clientJList;

    // ===== 心跳 =====
    private volatile ScheduledFuture<?> heartbeatTask;
    private final ScheduledExecutorService heartbeatScheduler = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "ws-heartbeat");
        t.setDaemon(true);
        return t;
    });
    private JCheckBox showHeartbeatBox;

    // ===== 共用控件 =====
    private JTextPane logPane;

    // ===== 客户端发送区 =====
    private JTextArea clientSendArea;
    private JButton btnClientSend;

    // ===== 服务端发送区 =====
    private JTextArea serverSendArea;
    private JButton btnServerSend;
    private JButton btnBroadcast;

    public WebSocketPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));

        // ===== 上部：客户端 + 服务端并排 =====
        JPanel topArea = new JPanel(new GridLayout(1, 2, 6, 0));

        // --- 左侧：客户端 ---
        JPanel clientPanel = new JPanel(new BorderLayout(4, 4));
        clientPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "客户端", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        JPanel clientTop = new JPanel(new GridLayout(2, 1, 2, 4));
        JPanel clientRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        clientRow1.add(new JLabel("地址:"));
        urlField = new JTextField("ws://127.0.0.1:8080/ws", 20);
        urlField.setFont(FONT_TEXT);
        clientRow1.add(urlField);
        btnConnect = makeBtn("连接", new Color(0x4CAF50));
        btnDisconnect = makeBtn("断开", new Color(0xE57373));
        btnDisconnect.setEnabled(false);
        clientRow1.add(btnConnect);
        clientRow1.add(btnDisconnect);

        JPanel clientRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        clientRow2.add(new JLabel("格式:"));
        clientFormatCombo = createFormatCombo();
        clientRow2.add(clientFormatCombo);

        clientTop.add(clientRow1);
        clientTop.add(clientRow2);
        clientPanel.add(clientTop, BorderLayout.NORTH);

        // 客户端发送区
        JPanel clientSendPanel = new JPanel(new BorderLayout(4, 0));
        clientSendPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "客户端发送", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 10)));
        clientSendArea = createSendArea();
        JScrollPane csScroll = new JScrollPane(clientSendArea);
        csScroll.setPreferredSize(new Dimension(0, 60));
        clientSendPanel.add(csScroll, BorderLayout.CENTER);
        btnClientSend = makeBtn("发送", new Color(0x2196F3));
        btnClientSend.setEnabled(false);
        JPanel csBtnWrap = new JPanel(new BorderLayout());
        csBtnWrap.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        csBtnWrap.add(btnClientSend, BorderLayout.NORTH);
        clientSendPanel.add(csBtnWrap, BorderLayout.EAST);
        clientPanel.add(clientSendPanel, BorderLayout.CENTER);

        // Ctrl+Enter 客户端发送
        clientSendArea.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "sendC");
        clientSendArea.getActionMap().put("sendC", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (clientConnected) doClientSend();
            }
        });

        // --- 右侧：服务端 ---
        JPanel serverPanel = new JPanel(new BorderLayout(4, 4));
        serverPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "服务端", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        JPanel serverTop = new JPanel(new GridLayout(2, 1, 2, 4));
        JPanel serverRow1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        serverRow1.add(new JLabel("端口:"));
        portField = new JTextField("8080", 5);
        portField.setFont(FONT_TEXT);
        serverRow1.add(portField);
        serverRow1.add(new JLabel("路径:"));
        pathField = new JTextField("/ws", 6);
        pathField.setFont(FONT_TEXT);
        serverRow1.add(pathField);
        serverRow1.add(new JLabel("心跳(s):"));
        heartbeatField = new JTextField("30", 3);
        heartbeatField.setFont(FONT_TEXT);
        heartbeatField.setToolTipText("服务端定时Ping，客户端自动回Pong，连续3次无响应则踢出。设0禁用");
        serverRow1.add(heartbeatField);
        showHeartbeatBox = new JCheckBox("显示心跳");
        showHeartbeatBox.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        showHeartbeatBox.setForeground(new Color(0x888888));
        showHeartbeatBox.setBackground(C_BG);
        showHeartbeatBox.setFocusPainted(false);
        serverRow1.add(showHeartbeatBox);
        btnStart = makeBtn("启动", new Color(0x4CAF50));
        btnStop = makeBtn("停止", new Color(0xE57373));
        btnStop.setEnabled(false);
        serverRow1.add(btnStart);
        serverRow1.add(btnStop);

        JPanel serverRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        serverRow2.add(new JLabel("格式:"));
        serverFormatCombo = createFormatCombo();
        serverRow2.add(serverFormatCombo);
        clientCountLabel = new JLabel("客户端: 0");
        clientCountLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        clientCountLabel.setForeground(new Color(0x888888));
        serverRow2.add(clientCountLabel);

        serverTop.add(serverRow1);
        serverTop.add(serverRow2);
        serverPanel.add(serverTop, BorderLayout.NORTH);

        // 客户端列表（带踢出按钮的自定义渲染）
        clientListModel = new DefaultListModel<>();
        clientJList = new JList<>(clientListModel);
        clientJList.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        clientJList.setBackground(C_BG);
        clientJList.setForeground(new Color(0x81C784));
        clientJList.setSelectionBackground(new Color(0x333333));
        clientJList.setSelectionForeground(new Color(0x64B5F6));
        clientJList.setCellRenderer(new ClientListRenderer());
        clientJList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                int index = clientJList.locationToIndex(e.getPoint());
                if (index < 0) return;
                Rectangle bounds = clientJList.getCellBounds(index, index);
                if (bounds == null) return;
                // 判断点击是否在右侧踢出按钮区域
                int btnX = bounds.x + bounds.width - 55;
                if (e.getX() >= btnX && e.getX() <= btnX + 50) {
                    String item = clientListModel.get(index);
                    kickClient(item);
                }
            }
        });
        JScrollPane clientListScroll = new JScrollPane(clientJList);
        clientListScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "客户端列表", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 10)));
        clientListScroll.setPreferredSize(new Dimension(0, 80));
        serverPanel.add(clientListScroll, BorderLayout.CENTER);

        // 服务端发送区
        JPanel serverSendPanel = new JPanel(new BorderLayout(4, 0));
        serverSendPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "服务端发送", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 10)));
        serverSendArea = createSendArea();
        JScrollPane ssScroll = new JScrollPane(serverSendArea);
        ssScroll.setPreferredSize(new Dimension(0, 60));
        serverSendPanel.add(ssScroll, BorderLayout.CENTER);

        JPanel ssBtnWrap = new JPanel(new GridLayout(2, 1, 0, 4));
        ssBtnWrap.setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 0));
        btnServerSend = makeBtn("发送", new Color(0x2196F3));
        btnServerSend.setEnabled(false);
        btnBroadcast = makeBtn("广播", new Color(0xFF9800));
        btnBroadcast.setEnabled(false);
        ssBtnWrap.add(btnServerSend);
        ssBtnWrap.add(btnBroadcast);
        serverSendPanel.add(ssBtnWrap, BorderLayout.EAST);
        serverPanel.add(serverSendPanel, BorderLayout.SOUTH);

        // Ctrl+Enter 服务端发送
        serverSendArea.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "sendS");
        serverSendArea.getActionMap().put("sendS", new AbstractAction() {
            @Override
            public void actionPerformed(java.awt.event.ActionEvent e) {
                if (serverRunning) doServerSend();
            }
        });

        topArea.add(clientPanel);
        topArea.add(serverPanel);

        // ===== 下部：日志区 =====
        logPane = createLogPane();
        JScrollPane logScroll = createLogScroll(logPane);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "WebSocket 日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 11)));

        // ===== 整体组装 =====
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topArea, logScroll);
        splitPane.setResizeWeight(0.5);
        splitPane.setDividerLocation(280);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        // ===== 事件绑定 =====
        btnConnect.addActionListener(e -> doConnect());
        btnDisconnect.addActionListener(e -> doDisconnect());
        btnStart.addActionListener(e -> doStart());
        btnStop.addActionListener(e -> doStop());
        btnClientSend.addActionListener(e -> doClientSend());
        btnServerSend.addActionListener(e -> doServerSend());
        btnBroadcast.addActionListener(e -> doBroadcast());
    }

    private JTextArea createSendArea() {
        JTextArea area = new JTextArea(2, 20);
        area.setFont(FONT_TEXT);
        area.setBackground(C_BG);
        area.setForeground(new Color(0xD4D4D4));
        area.setCaretColor(new Color(0xD4D4D4));
        area.setLineWrap(true);
        return area;
    }

    // ==================== 客户端逻辑 ====================

    private void doConnect() {
        String url = urlField.getText().trim();
        if (url.isEmpty()) { logWarn(logPane, "请输入 WebSocket 地址"); return; }

        threadPool.submit(() -> {
            try {
                logSys(logPane, "[客户端] 正在连接 " + url + " ...");
                CompletableFuture<WebSocket> future = httpClient.newWebSocketBuilder()
                        .connectTimeout(Duration.ofSeconds(10))
                        .buildAsync(URI.create(url), new WebSocket.Listener() {
                            private final StringBuilder textBuffer = new StringBuilder();

                            @Override
                            public void onOpen(WebSocket webSocket) {
                                logSys(logPane, "[客户端] 已连接: " + url);
                                WebSocket.Listener.super.onOpen(webSocket);
                            }

                            @Override
                            public CompletionStage<?> onText(WebSocket webSocket, CharSequence data, boolean last) {
                                textBuffer.append(data);
                                if (last) {
                                    String msg = textBuffer.toString();
                                    textBuffer.setLength(0);
                                    logRecv(logPane, "[客户端←] " + truncate(msg, 500));
                                }
                                return WebSocket.Listener.super.onText(webSocket, data, last);
                            }

                            @Override
                            public CompletionStage<?> onBinary(WebSocket webSocket, ByteBuffer data, boolean last) {
                                byte[] bytes = new byte[data.remaining()];
                                data.get(bytes);
                                FormatMode mode = fromComboIndex(clientFormatCombo.getSelectedIndex());
                                logRecv(logPane, "[客户端←] [Binary " + bytes.length + "B] " + formatBytes(bytes, mode));
                                return WebSocket.Listener.super.onBinary(webSocket, data, last);
                            }

                            @Override
                            public CompletionStage<?> onPing(WebSocket webSocket, ByteBuffer message) {
                                if (showHeartbeatBox.isSelected()) {
                                    byte[] bytes = new byte[message.remaining()];
                                    message.get(bytes);
                                    logSys(logPane, "[客户端] Ping ← " + bytesToHex(bytes));
                                }
                                return WebSocket.Listener.super.onPing(webSocket, message);
                            }

                            @Override
                            public CompletionStage<?> onPong(WebSocket webSocket, ByteBuffer message) {
                                if (showHeartbeatBox.isSelected()) {
                                    byte[] bytes = new byte[message.remaining()];
                                    message.get(bytes);
                                    logSys(logPane, "[客户端] Pong ← " + bytesToHex(bytes));
                                }
                                return WebSocket.Listener.super.onPong(webSocket, message);
                            }

                            @Override
                            public CompletionStage<?> onClose(WebSocket webSocket, int statusCode, String reason) {
                                logSys(logPane, "[客户端] 连接关闭: " + statusCode + (reason.isEmpty() ? "" : " " + reason));
                                SwingUtilities.invokeLater(() -> setClientConnected(false));
                                return WebSocket.Listener.super.onClose(webSocket, statusCode, reason);
                            }

                            @Override
                            public void onError(WebSocket webSocket, Throwable error) {
                                logErr(logPane, "[客户端] 错误: " + error.getMessage());
                                SwingUtilities.invokeLater(() -> setClientConnected(false));
                                WebSocket.Listener.super.onError(webSocket, error);
                            }
                        });

                webSocket = future.join();
                SwingUtilities.invokeLater(() -> setClientConnected(true));

            } catch (Exception e) {
                logErr(logPane, "[客户端] 连接失败: " + e.getMessage());
                SwingUtilities.invokeLater(() -> setClientConnected(false));
            }
        });
    }

    private void setClientConnected(boolean conn) {
        clientConnected = conn;
        btnConnect.setEnabled(!conn);
        btnDisconnect.setEnabled(conn);
        btnClientSend.setEnabled(conn);
        urlField.setEditable(!conn);
    }

    private void doDisconnect() {
        if (webSocket != null) {
            try {
                webSocket.sendClose(WebSocket.NORMAL_CLOSURE, "用户断开");
            } catch (Exception ignored) {}
            webSocket = null;
        }
        setClientConnected(false);
        logSys(logPane, "[客户端] 已断开连接");
    }

    private void doClientSend() {
        if (!clientConnected || webSocket == null) {
            logWarn(logPane, "[客户端] 未连接");
            return;
        }
        String text = clientSendArea.getText().trim();
        if (text.isEmpty()) { logWarn(logPane, "请输入发送内容"); return; }

        FormatMode mode = fromComboIndex(clientFormatCombo.getSelectedIndex());
        try {
            switch (mode) {
                case HEX: {
                    byte[] data = hexToBytes(text);
                    webSocket.sendBinary(ByteBuffer.wrap(data), true);
                    logSend(logPane, "[客户端→] [Binary " + data.length + "B] " + bytesToHex(data));
                    break;
                }
                case BIN: {
                    byte[] data = binToBytes(text);
                    webSocket.sendBinary(ByteBuffer.wrap(data), true);
                    logSend(logPane, "[客户端→] [Binary " + data.length + "B] " + bytesToBin(data));
                    break;
                }
                default: {
                    webSocket.sendText(text, true);
                    logSend(logPane, "[客户端→] " + truncate(text, 200));
                    break;
                }
            }
        } catch (Exception e) {
            logErr(logPane, "[客户端] 发送失败: " + e.getMessage());
        }
    }

    // ==================== 服务端逻辑 ====================

    private void doStart() {
        String portStr = portField.getText().trim();
        String path = pathField.getText().trim();
        if (portStr.isEmpty()) { logWarn(logPane, "请输入端口"); return; }
        if (path.isEmpty()) path = "/ws";
        if (!path.startsWith("/")) path = "/" + path;

        final String finalPath = path;
        int port;
        try { port = Integer.parseInt(portStr); } catch (NumberFormatException e) {
            logWarn(logPane, "端口格式错误"); return;
        }

        final int finalPort = port;
        threadPool.submit(() -> {
            try {
                serverSocket = new ServerSocket(finalPort);
                serverRunning = true;
                SwingUtilities.invokeLater(() -> {
                    btnStart.setEnabled(false);
                    btnStop.setEnabled(true);
                    btnServerSend.setEnabled(true);
                    btnBroadcast.setEnabled(true);
                    portField.setEditable(false);
                    pathField.setEditable(false);
                    heartbeatField.setEditable(false);
                });
                logSys(logPane, "[服务端] 已启动: ws://0.0.0.0:" + finalPort + finalPath);

                // 启动心跳检测
                startHeartbeat();

                while (serverRunning) {
                    try {
                        Socket clientSocket = serverSocket.accept();
                        threadPool.submit(() -> handleHttpUpgrade(clientSocket, finalPath));
                    } catch (IOException e) {
                        if (serverRunning) logErr(logPane, "[服务端] accept 异常: " + e.getMessage());
                    }
                }
            } catch (Exception e) {
                logErr(logPane, "[服务端] 启动失败: " + e.getMessage());
                serverRunning = false;
            }
        });
    }

    private void doStop() {
        serverRunning = false;
        stopHeartbeat();
        if (serverSocket != null) {
            try { serverSocket.close(); } catch (Exception ignored) {}
            serverSocket = null;
        }
        clients.clear();
        SwingUtilities.invokeLater(() -> {
            clientListModel.clear();
            btnStart.setEnabled(true);
            btnStop.setEnabled(false);
            btnServerSend.setEnabled(false);
            btnBroadcast.setEnabled(false);
            portField.setEditable(true);
            pathField.setEditable(true);
            heartbeatField.setEditable(true);
            updateClientCount();
        });
        logSys(logPane, "[服务端] 已停止");
    }

    /**
     * 处理 HTTP 升级请求，进行 WebSocket 握手
     */
    private void handleHttpUpgrade(Socket clientSocket, String expectedPath) {
        String clientAddr = clientSocket.getRemoteSocketAddress().toString();
        // 从地址中提取 IP 和端口
        String ip = extractIp(clientAddr);
        try {
            InputStream rawIn = clientSocket.getInputStream();
            OutputStream rawOs = clientSocket.getOutputStream();
            BufferedReader reader = new BufferedReader(new InputStreamReader(rawIn, StandardCharsets.UTF_8));

            // 读取 HTTP 请求行
            String requestLine = reader.readLine();
            if (requestLine == null || requestLine.isEmpty()) {
                clientSocket.close();
                return;
            }

            String[] parts = requestLine.split(" ");
            if (parts.length < 2) {
                clientSocket.close();
                return;
            }
            String reqPath = parts[1];

            // 读取 HTTP 头部
            Map<String, String> headers = new HashMap<>();
            String line;
            while ((line = reader.readLine()) != null && !line.isEmpty()) {
                int colon = line.indexOf(':');
                if (colon > 0) {
                    headers.put(line.substring(0, colon).trim(),
                            line.substring(colon + 1).trim());
                }
            }

            // 只处理 WebSocket 升级
            if (!"websocket".equalsIgnoreCase(headers.get("Upgrade"))) {
                writeHttpResponse(rawOs, 200, "OK", "WebSocket endpoint");
                clientSocket.close();
                return;
            }

            // 检查路径
            if (!reqPath.equals(expectedPath)) {
                writeHttpResponse(rawOs, 404, "Not Found", "");
                clientSocket.close();
                return;
            }

            // WebSocket 握手
            String key = headers.get("Sec-WebSocket-Key");
            if (key == null) {
                writeHttpResponse(rawOs, 400, "Bad Request", "");
                clientSocket.close();
                return;
            }

            // 用 IP 去重：同一 IP 只能有一个连接，新连接挤掉旧连接
            String clientId = "IP:" + ip;
            WebSocketHandle oldHandle = clients.get(clientId);
            if (oldHandle != null) {
                try {
                    oldHandle.sendClose();
                    logSys(logPane, "[服务端] 踢掉旧连接: " + oldHandle.getDisplayName());
                } catch (Exception ignored) {}
                clients.remove(clientId);
                removeClientFromList(clientId);
            }

            String acceptKey = generateAcceptKey(key);
            String response = "HTTP/1.1 101 Switching Protocols\r\n" +
                    "Upgrade: websocket\r\n" +
                    "Connection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: " + acceptKey + "\r\n\r\n";
            rawOs.write(response.getBytes(StandardCharsets.UTF_8));
            rawOs.flush();

            WebSocketHandle handle = new WebSocketHandle(rawOs, clientId, ip);
            clients.put(clientId, handle);
            addClientToList(handle);
            logSys(logPane, "[服务端] 客户端已连接: " + handle.getDisplayName());
            updateClientCount();

            // 握手完成，进入 WebSocket 帧通信
            handleWebSocketFrames(rawIn, rawOs, handle);

        } catch (Exception e) {
            if (serverRunning) {
                logWarn(logPane, "[服务端] 客户端异常: " + ip + " - " + e.getMessage());
            }
        } finally {
            String clientId = "IP:" + ip;
            clients.remove(clientId);
            removeClientFromList(clientId);
            updateClientCount();
            try { clientSocket.close(); } catch (Exception ignored) {}
        }
    }

    private void writeHttpResponse(OutputStream os, int code, String status, String body) throws IOException {
        String resp = "HTTP/1.1 " + code + " " + status + "\r\n" +
                "Content-Length: " + body.getBytes(StandardCharsets.UTF_8).length + "\r\n\r\n" + body;
        os.write(resp.getBytes(StandardCharsets.UTF_8));
        os.flush();
    }

    // ==================== WebSocket 帧处理（服务端） ====================

    private void handleWebSocketFrames(InputStream rawIn, OutputStream rawOs, WebSocketHandle handle) {
        try {
            while (serverRunning) {
                int b1 = rawIn.read();
                if (b1 == -1) break;
                int b2 = rawIn.read();
                if (b2 == -1) break;

                int opcode = b1 & 0x0F;
                boolean masked = (b2 & 0x80) != 0;
                long payloadLen = b2 & 0x7F;

                if (payloadLen == 126) {
                    byte[] ext = new byte[2];
                    rawIn.read(ext);
                    payloadLen = ((ext[0] & 0xFF) << 8) | (ext[1] & 0xFF);
                } else if (payloadLen == 127) {
                    byte[] ext = new byte[8];
                    rawIn.read(ext);
                    payloadLen = 0;
                    for (int i = 0; i < 8; i++) {
                        payloadLen = (payloadLen << 8) | (ext[i] & 0xFF);
                    }
                }

                byte[] maskKey = null;
                if (masked) {
                    maskKey = new byte[4];
                    rawIn.read(maskKey);
                }

                byte[] payload = new byte[(int) payloadLen];
                int off = 0;
                while (off < payloadLen) {
                    int n = rawIn.read(payload, off, (int) (payloadLen - off));
                    if (n == -1) break;
                    off += n;
                }
                if (off < payloadLen) break;

                if (masked && maskKey != null) {
                    for (int i = 0; i < payload.length; i++) {
                        payload[i] ^= maskKey[i % 4];
                    }
                }

                String displayName = handle.getDisplayName();
                switch (opcode) {
                    case 0x01: {
                        String msg = new String(payload, StandardCharsets.UTF_8);
                        logRecv(logPane, "[服务端←] 收到客户端" + displayName + "的消息: " + truncate(msg, 500));
                        broadcastRaw(opcode, payload, handle);
                        break;
                    }
                    case 0x02: {
                        FormatMode mode = fromComboIndex(serverFormatCombo.getSelectedIndex());
                        logRecv(logPane, "[服务端←] 收到客户端" + displayName + "的消息: [Binary " + payload.length + "B] " + formatBytes(payload, mode));
                        broadcastRaw(opcode, payload, handle);
                        break;
                    }
                    case 0x08: {
                        logSys(logPane, "[服务端] 客户端断开: " + displayName);
                        sendCloseFrame(rawOs);
                        clients.remove(handle.clientId);
                        removeClientFromList(handle.clientId);
                        updateClientCount();
                        return;
                    }
                    case 0x09: {
                        if (showHeartbeatBox.isSelected())
                            logSys(logPane, "[服务端] Ping ← " + displayName);
                        sendPong(rawOs, payload);
                        break;
                    }
                    case 0x0A: {
                        handle.lastPongTime = System.currentTimeMillis();
                        if (showHeartbeatBox.isSelected())
                            logSys(logPane, "[服务端] Pong ← " + displayName);
                        break;
                    }
                }
            }

        } catch (Exception e) {
            if (serverRunning) {
                logWarn(logPane, "[服务端] 客户端异常断开: " + handle.getDisplayName() + " - " + e.getMessage());
            }
        } finally {
            clients.remove(handle.clientId);
            removeClientFromList(handle.clientId);
            updateClientCount();
        }
    }

    private void broadcastRaw(int opcode, byte[] payload, WebSocketHandle sender) {
        for (WebSocketHandle h : clients.values()) {
            if (h == sender) continue;
            try {
                h.sendFrame(opcode, payload);
            } catch (Exception ignored) {}
        }
    }

    private void sendCloseFrame(OutputStream os) throws IOException {
        byte[] frame = new byte[4];
        frame[0] = (byte) 0x88;
        frame[1] = 0x02;
        frame[2] = 0x03;
        frame[3] = (byte) 0xE8;
        os.write(frame);
        os.flush();
    }

    private void sendPong(OutputStream os, byte[] data) throws IOException {
        int len = data.length;
        byte[] frame = new byte[2 + len];
        frame[0] = (byte) 0x8A;
        frame[1] = (byte) len;
        System.arraycopy(data, 0, frame, 2, len);
        os.write(frame);
        os.flush();
    }

    private void doServerSend() {
        if (clients.isEmpty()) {
            logWarn(logPane, "[服务端] 没有连接的客户端");
            return;
        }
        String text = serverSendArea.getText().trim();
        if (text.isEmpty()) { logWarn(logPane, "请输入发送内容"); return; }

        // 如果选中了列表中的某个客户端，发给选中的；否则发给第一个
        String selected = clientJList.getSelectedValue();
        WebSocketHandle target = null;
        if (selected != null) {
            String selName = stripKickBtn(selected);
            for (WebSocketHandle h : clients.values()) {
                if (h.getDisplayName().equals(selName)) {
                    target = h;
                    break;
                }
            }
        }
        if (target == null) {
            target = clients.values().iterator().next();
        }
        sendToClient(target);
    }

    private void doBroadcast() {
        if (clients.isEmpty()) {
            logWarn(logPane, "[服务端] 没有连接的客户端");
            return;
        }
        String text = serverSendArea.getText().trim();
        if (text.isEmpty()) { logWarn(logPane, "请输入发送内容"); return; }

        FormatMode mode = fromComboIndex(serverFormatCombo.getSelectedIndex());
        int opcode;
        byte[] data;
        switch (mode) {
            case HEX:
                data = hexToBytes(text);
                opcode = 0x02;
                break;
            case BIN:
                data = binToBytes(text);
                opcode = 0x02;
                break;
            default:
                data = text.getBytes(StandardCharsets.UTF_8);
                opcode = 0x01;
                break;
        }

        int count = 0;
        for (WebSocketHandle ws : clients.values()) {
            try {
                ws.sendFrame(opcode, data);
                count++;
            } catch (Exception e) {
                logErr(logPane, "[服务端] 广播失败: " + e.getMessage());
            }
        }
        logSend(logPane, "[服务端→] 广播到 " + count + " 个客户端: " + truncate(text, 100));
    }

    private void sendToClient(WebSocketHandle ws) {
        String text = serverSendArea.getText().trim();
        if (text.isEmpty()) { logWarn(logPane, "请输入发送内容"); return; }

        FormatMode mode = fromComboIndex(serverFormatCombo.getSelectedIndex());
        try {
            int opcode;
            byte[] data;
            String logPrefix;
            switch (mode) {
                case HEX:
                    data = hexToBytes(text);
                    opcode = 0x02;
                    logPrefix = "[服务端→] [Binary " + data.length + "B] " + bytesToHex(data);
                    break;
                case BIN:
                    data = binToBytes(text);
                    opcode = 0x02;
                    logPrefix = "[服务端→] [Binary " + data.length + "B] " + bytesToBin(data);
                    break;
                default:
                    data = text.getBytes(StandardCharsets.UTF_8);
                    opcode = 0x01;
                    logPrefix = "[服务端→] " + truncate(text, 200);
                    break;
            }
            ws.sendFrame(opcode, data);
            logSend(logPane, logPrefix);
        } catch (Exception e) {
            logErr(logPane, "[服务端] 发送失败: " + e.getMessage());
        }
    }

    // ==================== 心跳检测 ====================

    private void startHeartbeat() {
        stopHeartbeat();
        int intervalSec;
        try {
            intervalSec = Integer.parseInt(heartbeatField.getText().trim());
        } catch (NumberFormatException e) {
            intervalSec = 30;
        }
        if (intervalSec <= 0) {
            logSys(logPane, "[服务端] 心跳间隔为 0，心跳检测已禁用");
            return;
        }
        final int interval = intervalSec;
        final int timeoutMs = interval * 3 * 1000; // 超时时间 = 3 倍心跳间隔

        heartbeatTask = heartbeatScheduler.scheduleWithFixedDelay(() -> {
            if (!serverRunning) return;
            long now = System.currentTimeMillis();
            List<WebSocketHandle> deadClients = new ArrayList<>();

            for (WebSocketHandle h : clients.values()) {
                // 检查是否超时
                if (now - h.lastPongTime > timeoutMs) {
                    deadClients.add(h);
                    continue;
                }
                // 发送 Ping
                try {
                    h.sendPing();
                } catch (Exception e) {
                    deadClients.add(h);
                }
            }

            // 踢掉死连接
            for (WebSocketHandle dead : deadClients) {
                try { dead.sendClose(); } catch (Exception ignored) {}
                clients.remove(dead.clientId);
                removeClientFromList(dead.clientId);
                logSys(logPane, "[服务端] 心跳超时，已踢出: " + dead.getDisplayName());
            }
            if (!deadClients.isEmpty()) {
                updateClientCount();
            }
        }, interval, interval, TimeUnit.SECONDS);

        logSys(logPane, "[服务端] 心跳检测已启动，间隔: " + interval + "s，超时: " + (interval * 3) + "s");
    }

    private void stopHeartbeat() {
        if (heartbeatTask != null) {
            heartbeatTask.cancel(false);
            heartbeatTask = null;
        }
    }

    private void updateClientCount() {
        SwingUtilities.invokeLater(() -> {
            clientCountLabel.setText("客户端: " + clients.size());
            clientCountLabel.setForeground(clients.isEmpty() ? new Color(0x888888) : new Color(0x81C784));
        });
    }

    // ==================== WebSocketHandle ====================

    private static class WebSocketHandle {
        final OutputStream os;
        final String clientId;
        final String ip;
        volatile long lastPongTime = System.currentTimeMillis();

        WebSocketHandle(OutputStream os, String clientId, String ip) {
            this.os = os;
            this.clientId = clientId;
            this.ip = ip;
        }

        String getDisplayName() {
            return clientId + "(" + ip + ")";
        }

        synchronized void sendFrame(int opcode, byte[] payload) throws IOException {
            os.write(buildFrame(opcode, payload));
            os.flush();
        }

        synchronized void sendClose() throws IOException {
            byte[] frame = new byte[4];
            frame[0] = (byte) 0x88;
            frame[1] = 0x02;
            frame[2] = 0x03;
            frame[3] = (byte) 0xE8;
            os.write(frame);
            os.flush();
        }

        synchronized void sendPing() throws IOException {
            byte[] frame = new byte[2];
            frame[0] = (byte) 0x89;
            frame[1] = 0x00;
            os.write(frame);
            os.flush();
        }

        private byte[] buildFrame(int opcode, byte[] payload) {
            int headerSize = 2;
            int len = payload.length;
            if (len > 65535) headerSize += 8;
            else if (len > 125) headerSize += 2;

            byte[] frame = new byte[headerSize + len];
            frame[0] = (byte) (0x80 | opcode);
            if (len <= 125) {
                frame[1] = (byte) len;
            } else if (len <= 65535) {
                frame[1] = 126;
                frame[2] = (byte) (len >> 8);
                frame[3] = (byte) len;
            } else {
                frame[1] = 127;
                for (int i = 0; i < 8; i++) {
                    frame[2 + i] = (byte) (len >> (56 - i * 8));
                }
            }
            System.arraycopy(payload, 0, frame, headerSize, len);
            return frame;
        }
    }

    // ==================== 客户端列表管理 ====================

    private void addClientToList(WebSocketHandle handle) {
        SwingUtilities.invokeLater(() -> {
            String displayName = handle.getDisplayName();
            // 去重：如果已存在同名的，先移除
            for (int i = clientListModel.size() - 1; i >= 0; i--) {
                if (stripKickBtn(clientListModel.get(i)).equals(displayName)) {
                    clientListModel.remove(i);
                    break;
                }
            }
            clientListModel.addElement(displayName);
        });
    }

    private void removeClientFromList(String clientId) {
        SwingUtilities.invokeLater(() -> {
            for (int i = clientListModel.size() - 1; i >= 0; i--) {
                String item = clientListModel.get(i);
                if (stripKickBtn(item).startsWith(clientId)) {
                    clientListModel.remove(i);
                    break;
                }
            }
        });
    }

    private void kickClient(String item) {
        String displayName = stripKickBtn(item);
        // 找到对应的 handle
        WebSocketHandle target = null;
        for (WebSocketHandle h : clients.values()) {
            if (h.getDisplayName().equals(displayName)) {
                target = h;
                break;
            }
        }
        if (target != null) {
            try { target.sendClose(); } catch (Exception ignored) {}
            clients.remove(target.clientId);
            removeClientFromList(target.clientId);
            updateClientCount();
            logSys(logPane, "[服务端] 已踢出客户端: " + displayName);
        }
    }

    private static String stripKickBtn(String item) {
        // item 格式: "IP:127.0.0.1(127.0.0.1)  [踢出]" 或纯 displayName
        int idx = item.lastIndexOf("  [");
        if (idx > 0) return item.substring(0, idx);
        return item;
    }

    /**
     * 自定义列表渲染器：左侧显示名称，右侧红色踢出按钮
     */
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
                setBackground(new Color(0x333333));
                nameLabel.setForeground(new Color(0x64B5F6));
            } else {
                setBackground(C_BG);
                nameLabel.setForeground(new Color(0x81C784));
            }
            return this;
        }
    }

    private static String extractIp(String addr) {
        // addr 格式: "/127.0.0.1:12345" 或 "/0:0:0:0:0:0:0:1:12345"
        if (addr.startsWith("/")) addr = addr.substring(1);
        int lastColon = addr.lastIndexOf(':');
        if (lastColon > 0) {
            return addr.substring(0, lastColon);
        }
        return addr;
    }

    // ==================== 工具方法 ====================

    private static String generateAcceptKey(String key) {
        try {
            String magic = key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11";
            MessageDigest sha1 = MessageDigest.getInstance("SHA-1");
            byte[] digest = sha1.digest(magic.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    private static String truncate(String s, int maxLen) {
        return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(urlField, config, "ws.url");
        loadField(portField, config, "ws.port");
        loadField(pathField, config, "ws.path");
        loadField(heartbeatField, config, "ws.heartbeat");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(urlField, config, "ws.url");
        saveField(portField, config, "ws.port");
        saveField(pathField, config, "ws.path");
        saveField(heartbeatField, config, "ws.heartbeat");
    }
}
