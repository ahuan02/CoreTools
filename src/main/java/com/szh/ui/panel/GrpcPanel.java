package com.szh.ui.panel;

import com.google.protobuf.ByteString;
import com.google.protobuf.DescriptorProtos.FileDescriptorProto;
import com.google.protobuf.DescriptorProtos.FileDescriptorSet;
import com.google.protobuf.Descriptors;
import com.google.protobuf.DynamicMessage;
import com.google.protobuf.InvalidProtocolBufferException;
import com.google.protobuf.util.JsonFormat;
import com.szh.grpc.TestGrpcServer;
import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;
import io.grpc.CallOptions;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.StatusRuntimeException;
import io.grpc.reflection.v1.ServerReflectionGrpc;
import io.grpc.reflection.v1.ServerReflectionRequest;
import io.grpc.reflection.v1.ServerReflectionResponse;
import io.grpc.reflection.v1.ServiceResponse;
import io.grpc.stub.ClientCalls;
import io.grpc.stub.StreamObserver;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import static com.szh.utils.NetUtil.*;

/**
 * gRPC 客户端工具 — 通过 Server Reflection 自动发现服务/方法，
 * 支持动态构建请求（JSON）并调用，响应以 JSON 展示。
 */
public class GrpcPanel extends AbstractCommandPanel {

    // ===== 连接 =====
    private JTextField hostField;
    private JTextField portField;
    private JButton connectBtn;
    private JButton disconnectBtn;
    private JButton testServerBtn;

    // ===== 服务发现 =====
    private JComboBox<String> serviceCombo;
    private JComboBox<String> methodCombo;
    private JButton refreshBtn;

    // ===== 请求 / 响应 =====
    private RSyntaxTextArea requestArea;
    private RSyntaxTextArea responseArea;
    private JButton sendBtn;

    // ===== 日志 =====
    private JTextPane logPane;

    // ===== gRPC 状态 =====
    private ManagedChannel channel;
    private TestGrpcServer testServer;
    private volatile boolean connected;
    /** 服务名 → Descriptors.ServiceDescriptor */
    private final Map<String, Descriptors.ServiceDescriptor> serviceMap = new LinkedHashMap<>();

    public GrpcPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.42);
        split.setDividerSize(4);
        split.setLeftComponent(buildControlPanel());
        split.setRightComponent(buildLogPanel());
        add(split, BorderLayout.CENTER);

        updateButtonStates();
    }

    // ==================== 左侧面板 ====================

    private JScrollPane buildControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

        panel.add(buildConnectPanel());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildServicePanel());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildRequestPanel());
        panel.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(panel);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setPreferredSize(new Dimension(340, 100));
        return sp;
    }

    /** 连接区 */
    private JPanel buildConnectPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "gRPC 连接", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12), new Color(0x4FC3F7)));

        GridBagConstraints lc = gbc(0, 0);
        lc.insets = new Insets(3, 5, 3, 3);
        GridBagConstraints fc = gbc(1, 0, 2);
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0;
        fc.insets = new Insets(3, 3, 3, 5);

        p.add(new JLabel("Host:"), lc);
        hostField = new JTextField("localhost", 18);
        NetUtil.fixPaste(hostField);
        p.add(hostField, fc);
        lc.gridy++; fc.gridy++;

        p.add(new JLabel("端口:"), lc);
        portField = new JTextField("50051", 6);
        NetUtil.fixPaste(portField);
        fc.gridwidth = 1; fc.weightx = 0;
        p.add(portField, fc);
        fc.gridx = 2;
        p.add(new JPanel(), fc); // 占位
        fc.gridx = 1; fc.gridwidth = 2; fc.weightx = 1.0;
        lc.gridy++; fc.gridy++;

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        connectBtn = makeBtn("连接", new Color(0x4CAF50));
        disconnectBtn = makeBtn("断开", new Color(0xE57373));
        testServerBtn = makeBtn("启动测试服务", new Color(0x9C27B0));
        btnRow.add(connectBtn);
        btnRow.add(disconnectBtn);
        btnRow.add(testServerBtn);

        fc.gridx = 0; fc.gridwidth = 3; fc.gridy++;
        fc.insets = new Insets(8, 5, 3, 5);
        p.add(btnRow, fc);

        connectBtn.addActionListener(e -> doConnect());
        disconnectBtn.addActionListener(e -> doDisconnect());
        testServerBtn.addActionListener(e -> doToggleTestServer());

        return p;
    }

    /** 服务发现区 */
    private JPanel buildServicePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "服务发现 (Reflection)", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12), new Color(0x81C784)));

        GridBagConstraints lc = gbc(0, 0);
        lc.insets = new Insets(3, 5, 3, 3);
        GridBagConstraints fc = gbc(1, 0, 2);
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0;
        fc.insets = new Insets(3, 3, 3, 5);

        p.add(new JLabel("服务:"), lc);
        serviceCombo = new JComboBox<>();
        serviceCombo.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        p.add(serviceCombo, fc);
        lc.gridy++; fc.gridy++;

        p.add(new JLabel("方法:"), lc);
        methodCombo = new JComboBox<>();
        methodCombo.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        p.add(methodCombo, fc);
        lc.gridy++; fc.gridy++;

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        refreshBtn = makeBtn("刷新服务列表", new Color(0x42A5F5));
        btnRow.add(refreshBtn);

        fc.gridx = 0; fc.gridwidth = 3; fc.gridy++;
        fc.insets = new Insets(8, 5, 3, 5);
        p.add(btnRow, fc);

        refreshBtn.addActionListener(e -> doRefreshServices());
        serviceCombo.addActionListener(e -> onServiceSelected());

        return p;
    }

    /** 请求/响应区 */
    private JPanel buildRequestPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "调用方法", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12), new Color(0xFFB74D)));

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 0; fc.gridy = 0; fc.gridwidth = 1;
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0;
        fc.insets = new Insets(3, 5, 0, 5);

        // 请求
        fc.gridy = 0;
        p.add(new JLabel("请求 (JSON):"), fc);
        fc.gridy++;
        requestArea = createJsonEditor();
        fc.fill = GridBagConstraints.BOTH; fc.weighty = 0.35;
        RTextScrollPane reqSp = new RTextScrollPane(requestArea);
        reqSp.setPreferredSize(new Dimension(200, 100));
        p.add(reqSp, fc);

        // 发送按钮
        fc.gridy++;
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weighty = 0;
        fc.insets = new Insets(6, 5, 6, 5);
        JPanel sendRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        sendBtn = makeBtn("发送请求", new Color(0xFFA726));
        sendRow.add(sendBtn);
        p.add(sendRow, fc);

        // 响应
        fc.gridy++;
        fc.insets = new Insets(0, 5, 0, 5);
        p.add(new JLabel("响应:"), fc);
        fc.gridy++;
        responseArea = createJsonEditor();
        responseArea.setEditable(false);
        responseArea.setBackground(new Color(0x252525));
        fc.fill = GridBagConstraints.BOTH; fc.weighty = 0.35;
        RTextScrollPane respSp = new RTextScrollPane(responseArea);
        respSp.setPreferredSize(new Dimension(200, 100));
        p.add(respSp, fc);

        sendBtn.addActionListener(e -> doSendRequest());

        return p;
    }

    /** 右侧日志面板 */
    private JPanel buildLogPanel() {
        logPane = createLogPane();
        JPanel p = new JPanel(new BorderLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "输出日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        p.add(createLogScroll(logPane), BorderLayout.CENTER);
        return p;
    }

    // ==================== JSON 编辑器 ====================

    private RSyntaxTextArea createJsonEditor() {
        RSyntaxTextArea ta = new RSyntaxTextArea(5, 20);
        ta.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        ta.setCodeFoldingEnabled(true);
        ta.setAntiAliasingEnabled(true);
        ta.setAutoIndentEnabled(true);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setTabSize(2);
        ta.setFont(new Font("JetBrains Mono", Font.PLAIN, 12));
        // 暗色配色
        ta.setBackground(new Color(0x2B2B2B));
        ta.setCaretColor(new Color(0xA9B7C6));
        ta.setCurrentLineHighlightColor(new Color(0x323232));
        ta.setSelectionColor(new Color(0x214283));
        ta.setSelectedTextColor(Color.WHITE);
        applyJsonDarkColors(ta);
        return ta;
    }

    private static void applyJsonDarkColors(RSyntaxTextArea ta) {
        org.fife.ui.rsyntaxtextarea.SyntaxScheme scheme = ta.getSyntaxScheme();
        Color fg  = new Color(0xA9B7C6);
        Color key = new Color(0xCC7832);
        Color str = new Color(0x6A8759);
        Color num = new Color(0x6897BB);
        Color kw  = new Color(0xCC7832);
        Color sep = new Color(0xA9B7C6);
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.IDENTIFIER).foreground = key;
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground = str;
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground = num;
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.LITERAL_NUMBER_FLOAT).foreground = num;
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.RESERVED_WORD).foreground = kw;
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.SEPARATOR).foreground = sep;
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.OPERATOR).foreground = sep;
        scheme.getStyle(org.fife.ui.rsyntaxtextarea.TokenTypes.NULL).foreground = fg;
    }

    // ==================== 连接管理 ====================

    private void doConnect() {
        if (connected) {
            logWarn(logPane, "已经处于连接状态");
            return;
        }
        String host = hostField.getText().trim();
        if (host.isEmpty()) { logWarn(logPane, "Host 不能为空"); return; }
        int port;
        try { port = Integer.parseInt(portField.getText().trim()); }
        catch (NumberFormatException e) { logWarn(logPane, "端口格式错误"); return; }

        final String fHost = host;
        final int fPort = port;
        Thread.startVirtualThread(() -> {
            try {
                logSys(logPane, "正在连接 " + fHost + ":" + fPort + " ...");
                channel = ManagedChannelBuilder.forAddress(fHost, fPort)
                        .usePlaintext()
                        .build();
                connected = true;
                SwingUtilities.invokeLater(() -> {
                    updateButtonStates();
                    logSys(logPane, "已连接 " + fHost + ":" + fPort);
                });
                // 自动刷新服务列表
                doRefreshServices();
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    connected = false;
                    updateButtonStates();
                    logErr(logPane, "连接失败: " + ex.getMessage());
                });
            }
        });
    }

    private void doDisconnect() {
        if (channel == null || !connected) {
            logWarn(logPane, "未连接");
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                channel.shutdown().awaitTermination(3, TimeUnit.SECONDS);
                logSys(logPane, "已断开连接");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                channel = null;
                connected = false;
                serviceMap.clear();
                SwingUtilities.invokeLater(() -> {
                    serviceCombo.removeAllItems();
                    methodCombo.removeAllItems();
                    requestArea.setText("");
                    responseArea.setText("");
                    updateButtonStates();
                });
            }
        });
    }

    // ==================== 服务发现 (Reflection) ====================

    private void doRefreshServices() {
        if (!connected || channel == null) {
            logWarn(logPane, "请先连接");
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                serviceMap.clear();
                ServerReflectionGrpc.ServerReflectionStub stub =
                        ServerReflectionGrpc.newStub(channel);

                // 1) 列出所有服务
                ServerReflectionRequest listReq = ServerReflectionRequest.newBuilder()
                        .setListServices("")
                        .build();
                ServerReflectionResponse listResp = blockingReflectionCall(stub, listReq);
                List<ServiceResponse> services = listResp.getListServicesResponse().getServiceList();

                SwingUtilities.invokeLater(() -> {
                    serviceCombo.removeAllItems();
                    for (ServiceResponse sr : services) {
                        serviceCombo.addItem(sr.getName());
                    }
                    logSys(logPane, "发现 " + services.size() + " 个服务");
                });

                // 2) 逐个加载 FileDescriptor
                for (ServiceResponse sr : services) {
                    String fullName = sr.getName();
                    ServerReflectionRequest fileReq = ServerReflectionRequest.newBuilder()
                            .setFileContainingSymbol(fullName)
                            .build();
                    ServerReflectionResponse fileResp = blockingReflectionCall(stub, fileReq);
                    FileDescriptorSet.Builder fdsBuilder = FileDescriptorSet.newBuilder();
                    for (ByteString bs : fileResp.getFileDescriptorResponse().getFileDescriptorProtoList()) {
                        fdsBuilder.addFile(FileDescriptorProto.parseFrom(bs));
                    }
                    FileDescriptorSet fds = fdsBuilder.build();

                    // 解析依赖图 → 构建 FileDescriptor
                    Descriptors.FileDescriptor fd = buildFileDescriptor(fds);
                    if (fd != null) {
                        String shortName = fullName.contains(".")
                                ? fullName.substring(fullName.lastIndexOf('.') + 1) : fullName;
                        Descriptors.ServiceDescriptor sd = fd.findServiceByName(shortName);
                        if (sd != null) {
                            serviceMap.put(fullName, sd);
                        }
                    }
                }
                logSys(logPane, "服务描述符加载完成");

                // 默认选中第一个
                SwingUtilities.invokeLater(() -> {
                    if (serviceCombo.getItemCount() > 0) {
                        serviceCombo.setSelectedIndex(0);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    StringBuilder sb = new StringBuilder("服务发现失败: ").append(ex.toString());
                    for (StackTraceElement ste : ex.getStackTrace()) {
                        sb.append("\n    at ").append(ste.toString());
                    }
                    Throwable cause = ex.getCause();
                    while (cause != null) {
                        sb.append("\nCaused by: ").append(cause.toString());
                        for (StackTraceElement ste : cause.getStackTrace()) {
                            sb.append("\n    at ").append(ste.toString());
                        }
                        cause = cause.getCause();
                    }
                    logErr(logPane, sb.toString());
                });
            }
        });
    }

    /** 服务选中 → 填充方法列表 */
    private void onServiceSelected() {
        String svc = (String) serviceCombo.getSelectedItem();
        methodCombo.removeAllItems();
        if (svc == null) return;

        Descriptors.ServiceDescriptor sd = serviceMap.get(svc);
        if (sd == null) return;

        for (Descriptors.MethodDescriptor md : sd.getMethods()) {
            methodCombo.addItem(md.getName());
        }
        // 有方法时填充默认请求 JSON 模板
        if (methodCombo.getItemCount() > 0) {
            methodCombo.setSelectedIndex(0);
            fillRequestTemplate();
        }
    }

    /** 填充选中方法的默认 JSON 请求模板 */
    private void fillRequestTemplate() {
        String svc = (String) serviceCombo.getSelectedItem();
        String mtd = (String) methodCombo.getSelectedItem();
        if (svc == null || mtd == null) return;

        Descriptors.ServiceDescriptor sd = serviceMap.get(svc);
        if (sd == null) return;
        Descriptors.MethodDescriptor md = sd.findMethodByName(mtd);
        if (md == null) return;

        try {
            DynamicMessage defaultMsg = DynamicMessage.newBuilder(md.getInputType())
                    .buildPartial();
            String json = JsonFormat.printer()
                    .includingDefaultValueFields()
                    .print(defaultMsg);
            requestArea.setText(json);
        } catch (InvalidProtocolBufferException e) {
            requestArea.setText("{}");
        }
    }

    // ==================== 发送请求 ====================

    private void doSendRequest() {
        if (!connected || channel == null) {
            logWarn(logPane, "请先连接");
            return;
        }
        String svc = (String) serviceCombo.getSelectedItem();
        String mtd = (String) methodCombo.getSelectedItem();
        if (svc == null || mtd == null) {
            logWarn(logPane, "请选择服务和调用方法");
            return;
        }
        Descriptors.ServiceDescriptor sd = serviceMap.get(svc);
        if (sd == null) {
            logWarn(logPane, "服务未加载: " + svc);
            return;
        }
        Descriptors.MethodDescriptor md = sd.findMethodByName(mtd);
        if (md == null) {
            logWarn(logPane, "方法未找到: " + mtd);
            return;
        }
        final String jsonInput = requestArea.getText().trim();

        Thread.startVirtualThread(() -> {
            try {
                // 解析 JSON → DynamicMessage
                DynamicMessage.Builder reqBuilder = DynamicMessage.newBuilder(md.getInputType());
                JsonFormat.parser().merge(jsonInput, reqBuilder);
                DynamicMessage request = reqBuilder.build();

                logSend(logPane, "→ " + svc + "/" + mtd);

                // 构建 gRPC MethodDescriptor
                io.grpc.MethodDescriptor<DynamicMessage, DynamicMessage> grpcMethod =
                        io.grpc.MethodDescriptor.<DynamicMessage, DynamicMessage>newBuilder()
                                .setType(io.grpc.MethodDescriptor.MethodType.UNARY)
                                .setFullMethodName(svc + "/" + mtd)
                                .setRequestMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                                        DynamicMessage.getDefaultInstance(md.getInputType())))
                                .setResponseMarshaller(io.grpc.protobuf.ProtoUtils.marshaller(
                                        DynamicMessage.getDefaultInstance(md.getOutputType())))
                                .build();

                // 调用
                DynamicMessage response = ClientCalls.blockingUnaryCall(
                        channel, grpcMethod,
                        CallOptions.DEFAULT.withDeadlineAfter(30, TimeUnit.SECONDS),
                        request);

                // 格式化响应
                String respJson = JsonFormat.printer()
                        .includingDefaultValueFields()
                        .preservingProtoFieldNames()
                        .print(response);

                SwingUtilities.invokeLater(() -> {
                    responseArea.setText(respJson);
                    logRecv(logPane, "← 响应 (" + (respJson.length()) + " chars)");
                });
            } catch (StatusRuntimeException ex) {
                SwingUtilities.invokeLater(() -> {
                    responseArea.setText("gRPC Error [" + ex.getStatus().getCode()
                            + "]: " + ex.getStatus().getDescription());
                    logErr(logPane, "调用失败: " + ex.getStatus());
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    responseArea.setText("错误: " + ex.getMessage());
                    logErr(logPane, "调用失败: " + ex.getMessage());
                });
            }
        });
    }

    // ==================== UI 状态 ====================

    private void updateButtonStates() {
        boolean serverRunning = testServer != null && testServer.isRunning();
        connectBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);
        refreshBtn.setEnabled(connected);
        sendBtn.setEnabled(connected);
        testServerBtn.setEnabled(!connected || serverRunning);
        if (serverRunning) {
            testServerBtn.setText("停止测试服务");
            testServerBtn.setBackground(new Color(0xE57373));
        } else {
            testServerBtn.setText("启动测试服务");
            testServerBtn.setBackground(new Color(0x9C27B0));
        }
    }

    /** 启动/停止嵌入式测试服务 */
    private void doToggleTestServer() {
        boolean serverRunning = testServer != null && testServer.isRunning();
        if (serverRunning) {
            doStopTestServer();
        } else {
            doStartTestServer();
        }
    }

    private void doStartTestServer() {
        Thread.startVirtualThread(() -> {
            try {
                int port;
                try { port = Integer.parseInt(portField.getText().trim()); }
                catch (NumberFormatException e) { port = 50051; }
                testServer = new TestGrpcServer(port);
                testServer.start();
                int finalPort = port;
                SwingUtilities.invokeLater(() -> {
                    logSys(logPane, "测试服务已启动 → localhost:" + finalPort);
                    hostField.setText("localhost");
                    portField.setText(String.valueOf(finalPort));
                    updateButtonStates();
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    logErr(logPane, "启动测试服务失败: " + ex.getMessage());
                    updateButtonStates();
                });
            }
        });
    }

    private void doStopTestServer() {
        if (testServer == null || !testServer.isRunning()) return;
        Thread.startVirtualThread(() -> {
            try {
                testServer.stop();
                testServer = null;
                SwingUtilities.invokeLater(() -> {
                    logSys(logPane, "测试服务已停止");
                    updateButtonStates();
                });
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        });
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(hostField, config, "grpc.host");
        loadField(portField, config, "grpc.port");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(hostField, config, "grpc.host");
        saveField(portField, config, "grpc.port");
    }

    // ==================== gRPC Reflection 工具方法 ====================

    /**
     * 将双向流的 reflection 调用封装为同步阻塞调用。
     */
    private static ServerReflectionResponse blockingReflectionCall(
            ServerReflectionGrpc.ServerReflectionStub stub,
            ServerReflectionRequest request) throws InterruptedException {

        CountDownLatch latch = new CountDownLatch(1);
        ServerReflectionResponse[] result = new ServerReflectionResponse[1];
        Throwable[] error = new Throwable[1];

        StreamObserver<ServerReflectionRequest> reqObserver =
                stub.serverReflectionInfo(new StreamObserver<ServerReflectionResponse>() {
                    @Override
                    public void onNext(ServerReflectionResponse resp) {
                        result[0] = resp;
                        // 收到响应后主动完成（兼容不同服务端实现）
                        latch.countDown();
                    }

                    @Override
                    public void onError(Throwable t) {
                        error[0] = t;
                        latch.countDown();
                    }

                    @Override
                    public void onCompleted() {
                        latch.countDown();
                    }
                });

        reqObserver.onNext(request);
        reqObserver.onCompleted();
        boolean finished = latch.await(10, TimeUnit.SECONDS);

        if (error[0] != null) {
            throw new RuntimeException("Reflection error: " + error[0].getMessage(), error[0]);
        }
        if (!finished || result[0] == null) {
            throw new RuntimeException("反射调用超时或无响应，请确认服务端已开启 Reflection");
        }
        return result[0];
    }

    /**
     * 从 FileDescriptorSet 构建 FileDescriptor（处理依赖图）。
     */
    private static Descriptors.FileDescriptor buildFileDescriptor(FileDescriptorSet fds)
            throws Descriptors.DescriptorValidationException {
        List<FileDescriptorProto> protoList = fds.getFileList();
        if (protoList.isEmpty()) return null;

        // 按依赖顺序构建
        Map<String, Descriptors.FileDescriptor> built = new HashMap<>();
        Descriptors.FileDescriptor last = null;
        boolean added;
        do {
            added = false;
            for (FileDescriptorProto proto : protoList) {
                if (built.containsKey(proto.getName())) continue;
                if (!depsResolved(proto, built)) continue;
                Descriptors.FileDescriptor[] deps = proto.getDependencyList().stream()
                        .map(built::get)
                        .toArray(Descriptors.FileDescriptor[]::new);
                Descriptors.FileDescriptor fd = Descriptors.FileDescriptor.buildFrom(proto, deps);
                built.put(proto.getName(), fd);
                last = fd;
                added = true;
            }
        } while (added && built.size() < protoList.size());

        return last;
    }

    private static boolean depsResolved(FileDescriptorProto proto,
                                        Map<String, Descriptors.FileDescriptor> built) {
        for (String dep : proto.getDependencyList()) {
            if (!built.containsKey(dep)) return false;
        }
        return true;
    }
}
