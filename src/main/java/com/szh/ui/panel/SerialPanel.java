package com.szh.ui.panel;

import com.fazecast.jSerialComm.SerialPort;
import com.fazecast.jSerialComm.SerialPortDataListener;
import com.fazecast.jSerialComm.SerialPortEvent;
import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.OutputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.szh.ui.panel.NetUtil.*;

/**
 * 串口调试面板：扫描串口、打开/关闭、收发数据
 */
public class SerialPanel extends AbstractCommandPanel {

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();
    private SerialPortPanel serialPanel;

    public SerialPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        serialPanel = new SerialPortPanel();
        add(serialPanel, BorderLayout.CENTER);
    }

    private class SerialPortPanel extends JPanel {
        // ---- UI 控件 ----
        private JComboBox<String> portCombo;
        private JComboBox<String> baudCombo;
        private JComboBox<String> dataBitsCombo;
        private JComboBox<String> stopBitsCombo;
        private JComboBox<String> parityCombo;
        private JComboBox<String> encCombo;
        private JComboBox<String> formatCombo;
        private JTextArea sendArea;
        private JTextPane logPane;
        private JButton btnOpen, btnClose, btnRefresh, btnSend;

        // ---- 串口对象 ----
        private SerialPort serialPort;
        private OutputStream outputStream;
        private final AtomicBoolean opened = new AtomicBoolean(false);
        private final AtomicBoolean monitorRunning = new AtomicBoolean(true);
        private StringBuilder readRemainder = new StringBuilder();

        SerialPortPanel() {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "串口调试", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            // ---- 控制栏 ----
            JPanel topPanel = new JPanel(new GridLayout(2, 1, 0, 3));

            // 第1行：串口 + 参数
            JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row1.add(new JLabel("串口:"));
            portCombo = new JComboBox<>();
            portCombo.setPreferredSize(new Dimension(130, 24));
            row1.add(portCombo);
            btnRefresh = makeBtn("刷新", null);
            btnRefresh.setMargin(new Insets(2, 8, 2, 8));
            row1.add(btnRefresh);

            row1.add(new JLabel("波特率:"));
            baudCombo = new JComboBox<>(new String[]{"9600", "19200", "38400", "57600", "115200", "230400", "460800", "921600"});
            baudCombo.setPreferredSize(new Dimension(80, 24));
            baudCombo.setEditable(true);
            baudCombo.setSelectedItem("115200");
            row1.add(baudCombo);

            row1.add(new JLabel("数据位:"));
            dataBitsCombo = new JComboBox<>(new String[]{"8", "7", "6", "5"});
            dataBitsCombo.setPreferredSize(new Dimension(55, 24));
            row1.add(dataBitsCombo);

            row1.add(new JLabel("停止位:"));
            stopBitsCombo = new JComboBox<>(new String[]{"1", "1.5", "2"});
            stopBitsCombo.setPreferredSize(new Dimension(55, 24));
            row1.add(stopBitsCombo);

            row1.add(new JLabel("校验:"));
            parityCombo = new JComboBox<>(new String[]{"无", "奇校验", "偶校验", "Mark", "Space"});
            parityCombo.setPreferredSize(new Dimension(70, 24));
            row1.add(parityCombo);

            // 第2行：编码 + 格式 + 开关按钮
            JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 0));
            row2.add(new JLabel("编码:"));
            encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1", "ASCII"});
            encCombo.setPreferredSize(new Dimension(80, 24));
            row2.add(encCombo);

            row2.add(new JLabel("格式:"));
            formatCombo = createFormatCombo();
            row2.add(formatCombo);

            btnOpen = makeBtn("打开串口", new Color(0x4CAF50));
            btnClose = makeBtn("关闭串口", new Color(0xF44336));
            btnClose.setEnabled(false);
            row2.add(Box.createHorizontalStrut(8));
            row2.add(btnOpen);
            row2.add(btnClose);

            topPanel.add(row1);
            topPanel.add(row2);

            // ---- 发送区 ----
            JPanel sendPanel = new JPanel(new BorderLayout());
            sendPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "发送数据（每行一条，支持换行符 \\n 和回车 \\r）", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));
            sendArea = new JTextArea(2, 20);
            sendArea.setFont(FONT_TEXT);
            sendPanel.add(new JScrollPane(sendArea), BorderLayout.CENTER);

            JPanel sendBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            btnSend = makeBtn("发送", new Color(0x2196F3));
            JButton btnClear = makeBtn("清除", null);
            sendBtnRow.add(btnSend);
            sendBtnRow.add(btnClear);
            sendPanel.add(sendBtnRow, BorderLayout.SOUTH);

            // ---- 日志区 ----
            logPane = createLogPane();

            // ---- 布局 ----
            JPanel topArea = new JPanel(new BorderLayout(4, 4));
            topArea.add(topPanel, BorderLayout.NORTH);
            topArea.add(sendPanel, BorderLayout.CENTER);

            add(topArea, BorderLayout.NORTH);
            add(createLogScroll(logPane), BorderLayout.CENTER);

            // ---- 事件绑定 ----
            btnRefresh.addActionListener(e -> refreshPorts());
            btnOpen.addActionListener(e -> openPort());
            btnClose.addActionListener(e -> closePort());
            btnSend.addActionListener(e -> doSend());
            btnClear.addActionListener(e -> sendArea.setText(""));

            // Ctrl+Enter 发送
            sendArea.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
            sendArea.getActionMap().put("send", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) { doSend(); }
            });

            // 初始化串口列表 + 启动热插拔监听
            refreshPorts();
            startPortMonitor();
        }

        /** 后台虚拟线程：每2秒检测串口增减，自动刷新下拉框 */
        private void startPortMonitor() {
            Thread.ofVirtual().name("serial-port-monitor").start(() -> {
                String lastPorts = "";
                while (monitorRunning.get()) {
                    try { Thread.sleep(2000); } catch (InterruptedException e) { break; }
                    if (opened.get()) continue; // 打开串口时不刷新，避免干扰
                    String current = Arrays.toString(SerialPort.getCommPorts());
                    if (!current.equals(lastPorts)) {
                        lastPorts = current;
                        SwingUtilities.invokeLater(this::refreshPortsSilent);
                    }
                }
            });
        }

        /** 静默刷新（不打印日志），用于热插拔自动更新 */
        private void refreshPortsSilent() {
            String selected = (String) portCombo.getSelectedItem();
            portCombo.removeAllItems();
            SerialPort[] ports = SerialPort.getCommPorts();
            for (SerialPort p : ports) {
                String label = p.getSystemPortName();
                if (p.getDescriptivePortName() != null && !p.getDescriptivePortName().isEmpty()) {
                    label += " - " + p.getDescriptivePortName();
                }
                portCombo.addItem(label);
            }
            if (selected != null) portCombo.setSelectedItem(selected);
        }

        // ==================== 串口操作 ====================

        private void refreshPorts() {
            String selected = (String) portCombo.getSelectedItem();
            portCombo.removeAllItems();
            SerialPort[] ports = SerialPort.getCommPorts();
            for (SerialPort p : ports) {
                String label = p.getSystemPortName();
                if (p.getDescriptivePortName() != null && !p.getDescriptivePortName().isEmpty()) {
                    label += " - " + p.getDescriptivePortName();
                }
                portCombo.addItem(label);
            }
            if (ports.length == 0) {
                logWarn(logPane, "未检测到可用串口");
            } else {
                logSys(logPane, "检测到 " + ports.length + " 个串口");
            }
            // 尝试恢复选中
            if (selected != null) portCombo.setSelectedItem(selected);
        }

        private void openPort() {
            String portName = getSelectedPortName();
            if (portName == null || portName.isEmpty()) {
                logWarn(logPane, "请先选择串口");
                return;
            }

            serialPort = SerialPort.getCommPort(portName);

            // 波特率
            String baudStr = (String) baudCombo.getSelectedItem();
            int baud;
            try { baud = Integer.parseInt(baudStr); }
            catch (NumberFormatException e) { logWarn(logPane, "波特率格式错误"); return; }

            // 数据位
            int dataBits = Integer.parseInt((String) dataBitsCombo.getSelectedItem());

            // 停止位
            int stopBits;
            switch ((String) stopBitsCombo.getSelectedItem()) {
                case "1.5": stopBits = SerialPort.ONE_POINT_FIVE_STOP_BITS; break;
                case "2":   stopBits = SerialPort.TWO_STOP_BITS; break;
                default:    stopBits = SerialPort.ONE_STOP_BIT; break;
            }

            // 校验位
            int parity;
            switch ((String) parityCombo.getSelectedItem()) {
                case "奇校验": parity = SerialPort.ODD_PARITY; break;
                case "偶校验": parity = SerialPort.EVEN_PARITY; break;
                case "Mark":   parity = SerialPort.MARK_PARITY; break;
                case "Space":  parity = SerialPort.SPACE_PARITY; break;
                default:       parity = SerialPort.NO_PARITY; break;
            }

            serialPort.setComPortParameters(baud, dataBits, stopBits, parity);
            serialPort.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 0, 0);

            if (!serialPort.openPort()) {
                logErr(logPane, "打开串口失败: " + portName);
                return;
            }

            outputStream = serialPort.getOutputStream();
            opened.set(true);
            updatePortState(true);
            logSys(logPane, "已打开 " + portName + " " + baud + "/" + dataBits + "/" + stopBitsCombo.getSelectedItem() + "/" + parityCombo.getSelectedItem());

            // 注册数据监听
            serialPort.addDataListener(new SerialPortDataListener() {
                @Override
                public int getListeningEvents() {
                    return SerialPort.LISTENING_EVENT_DATA_AVAILABLE;
                }

                @Override
                public void serialEvent(SerialPortEvent event) {
                    if (event.getEventType() != SerialPort.LISTENING_EVENT_DATA_AVAILABLE) return;
                    byte[] data = new byte[serialPort.bytesAvailable()];
                    int n = serialPort.readBytes(data, data.length);
                    if (n <= 0) return;
                    if (n < data.length) data = Arrays.copyOf(data, n);
                    handleReceived(data);
                }
            });
        }

        private void handleReceived(byte[] data) {
            String enc = (String) encCombo.getSelectedItem();
            Charset charset;
            try { charset = Charset.forName(enc); } catch (Exception e) { charset = StandardCharsets.UTF_8; }

            FormatMode mode = fromComboIndex(formatCombo.getSelectedIndex());
            if (mode == FormatMode.HEX) {
                logRecv(logPane, "← " + bytesToHex(data));
            } else if (mode == FormatMode.BIN) {
                logRecv(logPane, "← " + bytesToBin(data));
            } else {
                // TEXT 模式：按行显示
                String text = charset.decode(java.nio.ByteBuffer.wrap(data)).toString();
                readRemainder.append(text);
                // 提取完整行
                String remaining = readRemainder.toString();
                int idx;
                while ((idx = remaining.indexOf('\n')) >= 0) {
                    String line = remaining.substring(0, idx);
                    if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
                    final String displayLine = line;
                    SwingUtilities.invokeLater(() -> logRecv(logPane, "← " + displayLine));
                    remaining = remaining.substring(idx + 1);
                }
                readRemainder.setLength(0);
                if (!remaining.isEmpty()) readRemainder.append(remaining);
            }
        }

        private void closePort() {
            opened.set(false);
            if (serialPort != null) {
                serialPort.removeDataListener();
                serialPort.closePort();
            }
            outputStream = null;
            readRemainder.setLength(0);
            updatePortState(false);
            logSys(logPane, "串口已关闭");
        }

        private void updatePortState(boolean isOpen) {
            SwingUtilities.invokeLater(() -> {
                btnOpen.setEnabled(!isOpen);
                btnClose.setEnabled(isOpen);
                portCombo.setEnabled(!isOpen);
                baudCombo.setEnabled(!isOpen);
                dataBitsCombo.setEnabled(!isOpen);
                stopBitsCombo.setEnabled(!isOpen);
                parityCombo.setEnabled(!isOpen);
                btnRefresh.setEnabled(!isOpen);
            });
        }

        // ==================== 发送数据 ====================

        private void doSend() {
            if (!opened.get() || serialPort == null || outputStream == null) {
                logWarn(logPane, "串口未打开");
                return;
            }

            String content = sendArea.getText().trim();
            if (content.isEmpty()) return;

            String enc = (String) encCombo.getSelectedItem();
            Charset charset;
            try { charset = Charset.forName(enc); } catch (Exception e) { charset = StandardCharsets.UTF_8; }
            FormatMode mode = fromComboIndex(formatCombo.getSelectedIndex());

            Charset finalCharset = charset;
            threadPool.submit(() -> {
                try {
                    String[] lines = content.split("\\n");
                    for (String line : lines) {
                        if (line.trim().isEmpty()) continue;
                        // 处理转义：\n → 换行，\r → 回车
                        String processed = line.replace("\\n", "\n").replace("\\r", "\r");

                        byte[] data;
                        if (mode == FormatMode.HEX) {
                            data = hexToBytes(processed);
                            logSend(logPane, "→ HEX: " + bytesToHex(data));
                        } else if (mode == FormatMode.BIN) {
                            data = binToBytes(processed);
                            logSend(logPane, "→ BIN: " + bytesToBin(data));
                        } else {
                            data = processed.getBytes(finalCharset);
                            logSend(logPane, "→ " + processed);
                        }

                        outputStream.write(data);
                        outputStream.flush();
                    }
                } catch (Exception e) {
                    logErr(logPane, "发送失败: " + e.getMessage());
                }
            });
        }

        // ==================== 辅助 ====================

        /** 从下拉框中提取纯串口名（去掉描述部分） */
        private String getSelectedPortName() {
            String selected = (String) portCombo.getSelectedItem();
            if (selected == null) return null;
            int dash = selected.indexOf(" - ");
            return dash >= 0 ? selected.substring(0, dash) : selected;
        }
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadCombo(serialPanel.baudCombo, config, "serial.baud");
        loadCombo(serialPanel.dataBitsCombo, config, "serial.dataBits");
        loadCombo(serialPanel.stopBitsCombo, config, "serial.stopBits");
        loadCombo(serialPanel.parityCombo, config, "serial.parity");
        loadCombo(serialPanel.encCombo, config, "serial.enc");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveCombo(serialPanel.baudCombo, config, "serial.baud");
        saveCombo(serialPanel.dataBitsCombo, config, "serial.dataBits");
        saveCombo(serialPanel.stopBitsCombo, config, "serial.stopBits");
        saveCombo(serialPanel.parityCombo, config, "serial.parity");
        saveCombo(serialPanel.encCombo, config, "serial.enc");
    }
}
