package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;
import org.eclipse.paho.mqttv5.client.*;
import org.eclipse.paho.mqttv5.client.persist.MemoryPersistence;
import org.eclipse.paho.mqttv5.common.MqttException;
import org.eclipse.paho.mqttv5.common.MqttMessage;
import org.eclipse.paho.mqttv5.common.MqttSubscription;
import org.eclipse.paho.mqttv5.common.packet.MqttProperties;
import org.fife.ui.rsyntaxtextarea.*;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

import static com.szh.utils.NetUtil.*;

/**
 * MQTT 客户端面板：连接 / 多主题订阅 / 发布 / 消息日志
 */
public class MqttPanel extends AbstractCommandPanel {

    // ===== 连接配置 =====
    private JTextField brokerField;
    private JTextField clientIdField;
    private JTextField userField;
    private JPasswordField passField;
    private JTextField portField;
    private JButton connectBtn;
    private JButton disconnectBtn;

    // ===== 订阅 =====
    private JTextField subTopicField;
    private JComboBox<String> subQosCombo;
    private JButton subBtn;
    private JButton unsubBtn;
    /** 已订阅主题集合（有序） */
    private final Set<String> subscribedTopics = new LinkedHashSet<>();
    /** 订阅主题 → 日志面板 映射（有序） */
    private final Map<String, JTextPane> tabLogMap = new LinkedHashMap<>();

    // ===== 发布 =====
    private JTextField pubTopicField;
    private JComboBox<String> pubQosCombo;
    private JCheckBox retainedCheck;
    private RSyntaxTextArea pubPayloadArea;
    private JButton pubBtn;

    // ===== 日志 =====
    private JTabbedPane tabbedPane;
    /** "全部" tab 的日志面板 */
    private JTextPane allLogPane;

    // ===== MQTT 客户端 =====
    private MqttClient client;
    private volatile boolean connected = false;

    public MqttPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        split.setResizeWeight(0.38);
        split.setDividerSize(4);
        split.setLeftComponent(buildControlPanel());
        split.setRightComponent(buildLogPanel());
        add(split, BorderLayout.CENTER);

        // 初始按钮状态
        updateButtonStates();
    }

    // ==================== 左侧控制面板 ====================

    private JScrollPane buildControlPanel() {
        JPanel panel = new JPanel();
        panel.setLayout(new BoxLayout(panel, BoxLayout.Y_AXIS));
        panel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));

        panel.add(buildConnectPanel());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildSubscribePanel());
        panel.add(Box.createVerticalStrut(8));
        panel.add(buildPublishPanel());
        panel.add(Box.createVerticalGlue());

        JScrollPane sp = new JScrollPane(panel);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        sp.setPreferredSize(new Dimension(300, 100));
        return sp;
    }

    /** 连接配置区 */
    private JPanel buildConnectPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "连接设置", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12), new Color(0x4FC3F7)));

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = 0; lc.anchor = GridBagConstraints.EAST;
        lc.insets = new Insets(3, 5, 3, 3);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = 0; fc.gridwidth = 2;
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0;
        fc.insets = new Insets(3, 3, 3, 5);

        // Broker
        p.add(new JLabel("Broker:"), lc);
        brokerField = new JTextField("tcp://broker.emqx.io", 18);
        NetUtil.fixPaste(brokerField);
        p.add(brokerField, fc);
        lc.gridy++; fc.gridy++;

        // 端口
        p.add(new JLabel("端口:"), lc);
        portField = new JTextField("1883", 6);
        NetUtil.fixPaste(portField);
        fc.gridwidth = 1; fc.weightx = 0;
        p.add(portField, fc);
        fc.gridx = 2; fc.gridwidth = 1;
        p.add(new JPanel(), fc); // 占位
        fc.gridx = 1; fc.gridwidth = 2; fc.weightx = 1.0;
        lc.gridy++; fc.gridy++;

        // Client ID
        p.add(new JLabel("Client ID:"), lc);
        clientIdField = new JTextField("CoreTools_" + System.currentTimeMillis() % 100000, 18);
        NetUtil.fixPaste(clientIdField);
        p.add(clientIdField, fc);
        lc.gridy++; fc.gridy++;

        // Username
        p.add(new JLabel("用户名:"), lc);
        userField = new JTextField(18);
        NetUtil.fixPaste(userField);
        p.add(userField, fc);
        lc.gridy++; fc.gridy++;

        // Password
        p.add(new JLabel("密码:"), lc);
        passField = new JPasswordField(18);
        p.add(passField, fc);
        lc.gridy++; fc.gridy++;

        // 按钮
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        connectBtn = makeBtn("连接", new Color(0x4CAF50));
        disconnectBtn = makeBtn("断开", new Color(0xE57373));
        btnRow.add(connectBtn);
        btnRow.add(disconnectBtn);

        fc.gridx = 0; fc.gridwidth = 3; fc.gridy++;
        fc.insets = new Insets(8, 5, 3, 5);
        p.add(btnRow, fc);

        connectBtn.addActionListener(e -> doConnect());
        disconnectBtn.addActionListener(e -> doDisconnect());

        return p;
    }

    /** 订阅区 */
    private JPanel buildSubscribePanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "订阅主题", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12), new Color(0x81C784)));

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = 0; lc.anchor = GridBagConstraints.EAST;
        lc.insets = new Insets(3, 5, 3, 3);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = 0; fc.gridwidth = 2;
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0;
        fc.insets = new Insets(3, 3, 3, 5);

        // Topic
        p.add(new JLabel("Topic:"), lc);
        subTopicField = new JTextField("#", 18);
        NetUtil.fixPaste(subTopicField);
        p.add(subTopicField, fc);
        lc.gridy++; fc.gridy++;

        // QoS
        p.add(new JLabel("QoS:"), lc);
        subQosCombo = new JComboBox<>(new String[]{"0 - 最多一次", "1 - 至少一次", "2 - 仅一次"});
        p.add(subQosCombo, fc);
        lc.gridy++; fc.gridy++;

        // 按钮
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        subBtn = makeBtn("订阅", new Color(0x42A5F5));
        unsubBtn = makeBtn("取消订阅", new Color(0xFF8A65));
        btnRow.add(subBtn);
        btnRow.add(unsubBtn);

        fc.gridx = 0; fc.gridwidth = 3; fc.gridy++;
        fc.insets = new Insets(8, 5, 3, 5);
        p.add(btnRow, fc);

        subBtn.addActionListener(e -> doSubscribe());
        unsubBtn.addActionListener(e -> doUnsubscribe());

        return p;
    }

    /** 发布区 */
    private JPanel buildPublishPanel() {
        JPanel p = new JPanel(new GridBagLayout());
        p.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "发布消息", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12), new Color(0xFFB74D)));

        GridBagConstraints lc = new GridBagConstraints();
        lc.gridx = 0; lc.gridy = 0; lc.anchor = GridBagConstraints.EAST;
        lc.insets = new Insets(3, 5, 3, 3);

        GridBagConstraints fc = new GridBagConstraints();
        fc.gridx = 1; fc.gridy = 0; fc.gridwidth = 2;
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1.0;
        fc.insets = new Insets(3, 3, 3, 5);

        // Topic
        p.add(new JLabel("Topic:"), lc);
        pubTopicField = new JTextField("test/topic", 18);
        NetUtil.fixPaste(pubTopicField);
        p.add(pubTopicField, fc);
        lc.gridy++; fc.gridy++;

        // QoS + Retained
        p.add(new JLabel("QoS:"), lc);
        JPanel qosRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        pubQosCombo = new JComboBox<>(new String[]{"0", "1", "2"});
        pubQosCombo.setPreferredSize(new Dimension(60, 24));
        qosRow.add(pubQosCombo);
        retainedCheck = new JCheckBox("保留");
        retainedCheck.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        qosRow.add(retainedCheck);
        p.add(qosRow, fc);
        lc.gridy++; fc.gridy++;

        // Payload
        fc.fill = GridBagConstraints.BOTH; fc.weighty = 0.3;
        fc.insets = new Insets(3, 5, 3, 5);
        p.add(new JLabel("消息:"), lc);
        pubPayloadArea = new RSyntaxTextArea(4, 18);
        pubPayloadArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
        pubPayloadArea.setCodeFoldingEnabled(true);
        pubPayloadArea.setAntiAliasingEnabled(true);
        pubPayloadArea.setAutoIndentEnabled(true);
        pubPayloadArea.setLineWrap(true);
        pubPayloadArea.setWrapStyleWord(true);
        pubPayloadArea.setTabSize(2);
        pubPayloadArea.setFont(new Font("JetBrains Mono", Font.PLAIN, 12));
        // 暗色背景 + 编辑器 chrome
        pubPayloadArea.setBackground(new Color(0x2B2B2B));
        pubPayloadArea.setCaretColor(new Color(0xA9B7C6));
        pubPayloadArea.setCurrentLineHighlightColor(new Color(0x323232));
        pubPayloadArea.setSelectionColor(new Color(0x214283));
        pubPayloadArea.setSelectedTextColor(Color.WHITE);
        pubPayloadArea.setMatchedBracketBGColor(new Color(0x3E4E6B));
        pubPayloadArea.setMatchedBracketBorderColor(new Color(0x3E4E6B));
        pubPayloadArea.setMargin(new java.awt.Insets(0, 4, 0, 0));
        // JSON 语法高亮配色（暗色系）
        applyJsonDarkScheme(pubPayloadArea);
        // 主题文件兜底
        try {
            Theme theme = Theme.load(getClass().getResourceAsStream(
                    "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
            if (theme != null) theme.apply(pubPayloadArea);
        } catch (Exception ignored) { }
        RTextScrollPane payloadSp = new RTextScrollPane(pubPayloadArea);
        payloadSp.setPreferredSize(new Dimension(200, 70));
        p.add(payloadSp, fc);
        fc.fill = GridBagConstraints.HORIZONTAL; fc.weighty = 0;
        lc.gridy++; fc.gridy++;

        // 按钮
        fc.gridx = 0; fc.gridwidth = 3; fc.gridy++;
        fc.insets = new Insets(8, 5, 3, 5);
        pubBtn = makeBtn("发布", new Color(0xFFA726));
        JPanel pubBtnRow = new JPanel(new FlowLayout(FlowLayout.CENTER, 6, 0));
        pubBtnRow.add(pubBtn);
        p.add(pubBtnRow, fc);

        pubBtn.addActionListener(e -> doPublish());

        return p;
    }

    /** 右侧消息日志 — 每订阅一个主题独立一个 tab */
    private JPanel buildLogPanel() {
        allLogPane = createLogPane();
        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        addLogTab("<全部>", allLogPane);

        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "消息日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        panel.add(tabbedPane, BorderLayout.CENTER);
        return panel;
    }

    private void addLogTab(String title, JTextPane pane) {
        JScrollPane sp = new JScrollPane(pane);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        tabbedPane.addTab(title, sp);
    }

    // ==================== MQTT 操作 ====================

    private void doConnect() {
        if (connected) {
            logWarn(allLogPane, "已经处于连接状态");
            return;
        }
        final String broker = brokerField.getText().trim();
        if (broker.isEmpty()) {
            logErr(allLogPane, "Broker 地址不能为空");
            return;
        }
        String cid = clientIdField.getText().trim();
        if (cid.isEmpty()) {
            cid = "CoreTools_" + System.currentTimeMillis();
            clientIdField.setText(cid);
        }
        final String clientId = cid;

        Thread.startVirtualThread(() -> {
            try {
                logSys(allLogPane, "正在连接 " + broker + " ...");
                MqttConnectionOptions opts = new MqttConnectionOptions();
                opts.setServerURIs(new String[]{broker});
                opts.setCleanStart(true);
                opts.setConnectionTimeout(10);
                opts.setKeepAliveInterval(60);
                opts.setAutomaticReconnect(true);
                opts.setMaxReconnectDelay(10000);

                String user = userField.getText().trim();
                if (!user.isEmpty()) {
                    opts.setUserName(user);
                    opts.setPassword(new String(passField.getPassword()).getBytes(StandardCharsets.UTF_8));
                }

                client = new MqttClient(broker, clientId, new MemoryPersistence());
                client.setCallback(new MqttCallback() {
                    @Override
                    public void disconnected(MqttDisconnectResponse disconnectResponse) {
                        SwingUtilities.invokeLater(() -> {
                            connected = false;
                            updateButtonStates();
                            updateConnectionInputs();
                            logWarn(allLogPane, "连接已断开: " +
                                    (disconnectResponse != null ? disconnectResponse.getReasonString() : ""));
                        });
                    }

                    @Override
                    public void mqttErrorOccurred(MqttException exception) {
                        SwingUtilities.invokeLater(() ->
                                logErr(allLogPane, "MQTT 错误: " + exception.getMessage()));
                    }

                    @Override
                    public void messageArrived(String topic, MqttMessage message) {
                        SwingUtilities.invokeLater(() -> {
                            String payload = new String(message.getPayload(), StandardCharsets.UTF_8);
                            // 写入"全部" tab
                            logRecv(allLogPane, "← [" + topic + "] " + payload);
                            // 写入匹配的订阅主题 tab（可能匹配多个 wildcard 订阅项）
                            synchronized (subscribedTopics) {
                                for (Map.Entry<String, JTextPane> e : tabLogMap.entrySet()) {
                                    if (topicMatches(e.getKey(), topic)) {
                                        logRecv(e.getValue(), "← " + payload);
                                    }
                                }
                            }
                        });
                    }

                    @Override
                    public void deliveryComplete(IMqttToken token) {
                    }

                    @Override
                    public void connectComplete(boolean reconnect, String serverURI) {
                        SwingUtilities.invokeLater(() -> {
                            connected = true;
                            updateButtonStates();
                            updateConnectionInputs();
                            if (reconnect) {
                                logSys(allLogPane, "重连成功: " + serverURI);
                            } else {
                                logSys(allLogPane, "连接成功: " + serverURI);
                            }
                        });
                    }

                    @Override
                    public void authPacketArrived(int reasonCode, MqttProperties properties) {
                    }
                });

                client.connect(opts);
            } catch (MqttException ex) {
                SwingUtilities.invokeLater(() -> {
                    connected = false;
                    updateButtonStates();
                    updateConnectionInputs();
                    logErr(allLogPane, "连接失败: " + ex.getMessage());
                });
            }
        });
    }

    private void doDisconnect() {
        if (client == null || !connected) {
            logWarn(allLogPane, "未连接");
            return;
        }
        Thread.startVirtualThread(() -> {
            try {
                // 自动取消所有订阅
                synchronized (subscribedTopics) {
                    for (String t : subscribedTopics) {
                        try { client.unsubscribe(t); } catch (MqttException ignored) {}
                    }
                }
                client.disconnect();
                client.close();
                logSys(allLogPane, "已断开连接");
            } catch (MqttException ex) {
                logErr(allLogPane, "断开异常: " + ex.getMessage());
            } finally {
                synchronized (subscribedTopics) {
                    subscribedTopics.clear();
                    tabLogMap.clear();
                }
                client = null;
                connected = false;
                SwingUtilities.invokeLater(() -> {
                    // 移除所有主题 tab，只保留 "全部"
                    while (tabbedPane.getTabCount() > 1) {
                        tabbedPane.removeTabAt(tabbedPane.getTabCount() - 1);
                    }
                    tabbedPane.setSelectedIndex(0);
                    updateButtonStates();
                    updateConnectionInputs();
                });
            }
        });
    }

    private void doSubscribe() {
        if (client == null || !connected) {
            logWarn(allLogPane, "请先连接");
            return;
        }
        String topic = subTopicField.getText().trim();
        if (topic.isEmpty()) {
            logWarn(allLogPane, "请输入订阅主题");
            return;
        }
        // 已订阅则跳过
        synchronized (subscribedTopics) {
            if (subscribedTopics.contains(topic)) {
                logWarn(allLogPane, "已订阅过: " + topic);
                return;
            }
        }
        int qos = subQosCombo.getSelectedIndex();

        Thread.startVirtualThread(() -> {
            try {
                MqttSubscription sub = new MqttSubscription(topic, qos);
                client.subscribe(new MqttSubscription[]{sub});
                synchronized (subscribedTopics) {
                    subscribedTopics.add(topic);
                    // 创建该主题的独立日志 tab
                    JTextPane tp = createLogPane();
                    tabLogMap.put(topic, tp);
                }
                SwingUtilities.invokeLater(() -> {
                    JTextPane tp;
                    synchronized (subscribedTopics) { tp = tabLogMap.get(topic); }
                    addLogTab(topic, tp);
                    // 自动切到刚订阅的 tab
                    tabbedPane.setSelectedIndex(tabbedPane.getTabCount() - 1);
                    updateButtonStates();
                    logSys(allLogPane, "已订阅: " + topic + " (QoS " + qos + ")");
                });
            } catch (MqttException ex) {
                SwingUtilities.invokeLater(() ->
                        logErr(allLogPane, "订阅失败: " + ex.getMessage()));
            }
        });
    }

    private void doUnsubscribe() {
        if (client == null || !connected) {
            logWarn(allLogPane, "请先连接");
            return;
        }
        // 优先取当前选中 tab 的标题作为主题，否则取输入框
        int idx = tabbedPane.getSelectedIndex();
        String topic;
        if (idx > 0) {
            topic = tabbedPane.getTitleAt(idx);
        } else {
            topic = subTopicField.getText().trim();
        }
        if (topic.isEmpty()) {
            logWarn(allLogPane, "请选择主题 tab 或输入要取消订阅的主题");
            return;
        }
        synchronized (subscribedTopics) {
            if (!subscribedTopics.contains(topic)) {
                logWarn(allLogPane, "未订阅该主题: " + topic);
                return;
            }
        }

        final String finalTopic = topic;
        Thread.startVirtualThread(() -> {
            try {
                client.unsubscribe(finalTopic);
                synchronized (subscribedTopics) {
                    subscribedTopics.remove(finalTopic);
                    tabLogMap.remove(finalTopic);
                }
                SwingUtilities.invokeLater(() -> {
                    // 移除对应的 tab
                    for (int i = tabbedPane.getTabCount() - 1; i > 0; i--) {
                        if (finalTopic.equals(tabbedPane.getTitleAt(i))) {
                            tabbedPane.removeTabAt(i);
                            break;
                        }
                    }
                    tabbedPane.setSelectedIndex(0);
                    updateButtonStates();
                    logSys(allLogPane, "已取消订阅: " + finalTopic);
                });
            } catch (MqttException ex) {
                SwingUtilities.invokeLater(() ->
                        logErr(allLogPane, "取消订阅失败: " + ex.getMessage()));
            }
        });
    }

    private void doPublish() {
        if (client == null || !connected) {
            logWarn(allLogPane, "请先连接");
            return;
        }
        String topic = pubTopicField.getText().trim();
        if (topic.isEmpty()) {
            logWarn(allLogPane, "请输入发布主题");
            return;
        }
        String payload = pubPayloadArea.getText();
        int qos = pubQosCombo.getSelectedIndex();
        boolean retained = retainedCheck.isSelected();

        Thread.startVirtualThread(() -> {
            try {
                MqttMessage msg = new MqttMessage(payload.getBytes(StandardCharsets.UTF_8));
                msg.setQos(qos);
                msg.setRetained(retained);
                client.publish(topic, msg);
                SwingUtilities.invokeLater(() -> {
                    String preview = payload.length() > 80 ? payload.substring(0, 80) + "…" : payload;
                    logSend(allLogPane, "→ [" + topic + "] " + preview);
                });
            } catch (MqttException ex) {
                SwingUtilities.invokeLater(() ->
                        logErr(allLogPane, "发布失败: " + ex.getMessage()));
            }
        });
    }

    // ==================== UI 状态更新 ====================

    private void updateButtonStates() {
        boolean hasSubscribed;
        if (subscribedTopics != null) {
            synchronized (subscribedTopics) {
                hasSubscribed = !subscribedTopics.isEmpty();
            }
        } else {
            hasSubscribed = false;
        }
        connectBtn.setEnabled(!connected);
        disconnectBtn.setEnabled(connected);
        subBtn.setEnabled(connected);
        unsubBtn.setEnabled(connected && hasSubscribed);
        pubBtn.setEnabled(connected);
    }

    /** 连接后禁用连接设置输入框，断开后恢复 */
    private void updateConnectionInputs() {
        boolean editable = !connected;
        brokerField.setEnabled(editable);
        portField.setEnabled(editable);
        clientIdField.setEnabled(editable);
        userField.setEnabled(editable);
        passField.setEnabled(editable);
    }

    /**
     * MQTT 主题通配符匹配。
     * <ul>
     *   <li>{@code +} 匹配单个 topic 层级</li>
     *   <li>{@code #} 匹配零个或多个层级（只能出现在末尾）</li>
     * </ul>
     */
    private static boolean topicMatches(String filter, String topic) {
        // 不需要匹配时直接 eq
        if (!filter.contains("+") && !filter.contains("#")) {
            return filter.equals(topic);
        }
        String[] filterLevels = filter.split("/", -1);
        String[] topicLevels = topic.split("/", -1);

        int fi = 0, ti = 0;
        while (fi < filterLevels.length && ti < topicLevels.length) {
            String fl = filterLevels[fi];
            if ("#".equals(fl)) {
                return true; // # 匹配剩余所有
            }
            if ("+".equals(fl)) {
                fi++;
                ti++;
                continue;
            }
            if (!fl.equals(topicLevels[ti])) {
                return false;
            }
            fi++;
            ti++;
        }
        // 双方都走完 → 精确匹配
        if (fi == filterLevels.length && ti == topicLevels.length) {
            return true;
        }
        // filter 末尾是 # 且 topic 还有剩余层级
        if (fi == filterLevels.length - 1 && "#".equals(filterLevels[fi])) {
            return true;
        }
        return false;
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(brokerField, config, "mqtt.broker");
        loadField(portField, config, "mqtt.port");
        loadField(clientIdField, config, "mqtt.clientId");
        loadField(userField, config, "mqtt.user");
        loadField(subTopicField, config, "mqtt.subTopic");
        loadField(pubTopicField, config, "mqtt.pubTopic");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(brokerField, config, "mqtt.broker");
        saveField(portField, config, "mqtt.port");
        saveField(clientIdField, config, "mqtt.clientId");
        saveField(userField, config, "mqtt.user");
        saveField(subTopicField, config, "mqtt.subTopic");
        saveField(pubTopicField, config, "mqtt.pubTopic");
    }

    /** 为 RSyntaxTextArea 设置 JSON 暗色语法高亮配色 */
    private static void applyJsonDarkScheme(RSyntaxTextArea textArea) {
        SyntaxScheme scheme = textArea.getSyntaxScheme();
        Color fg = new Color(0xA9B7C6);       // 默认前景
        Color key = new Color(0xCC7832);       // key 名 — 橙色
        Color str = new Color(0x6A8759);       // 字符串 — 绿色
        Color num = new Color(0x6897BB);       // 数值 — 蓝色
        Color kw  = new Color(0xCC7832);       // true/false/null — 橙色
        Color sep = new Color(0xA9B7C6);       // 分隔符 — 浅灰
        Color op  = new Color(0xA9B7C6);       // 冒号 — 浅灰

        scheme.getStyle(TokenTypes.IDENTIFIER).foreground = key;
        scheme.getStyle(TokenTypes.LITERAL_STRING_DOUBLE_QUOTE).foreground = str;
        scheme.getStyle(TokenTypes.LITERAL_NUMBER_DECIMAL_INT).foreground = num;
        scheme.getStyle(TokenTypes.LITERAL_NUMBER_FLOAT).foreground = num;
        scheme.getStyle(TokenTypes.RESERVED_WORD).foreground = kw;
        scheme.getStyle(TokenTypes.SEPARATOR).foreground = sep;
        scheme.getStyle(TokenTypes.OPERATOR).foreground = op;
        // 默认文字色（未被上述 token 命中的）
        scheme.getStyle(TokenTypes.NULL).foreground = fg;
    }
}
