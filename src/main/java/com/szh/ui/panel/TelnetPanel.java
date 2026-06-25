package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;
import com.szh.utils.ThreadPoolUtil;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.szh.utils.NetUtil.*;

/**
 * Telnet 终端
 */
public class TelnetPanel extends AbstractCommandPanel {

    private JTextField telnetHostField;
    private JTextField telnetPortField;
    private JButton btnTelnetConnect;
    private JButton btnTelnetDisconnect;
    private JTextField telnetInputField;
    private JButton btnTelnetSend;
    private JTextPane telnetLog;
    private final AtomicBoolean telnetConnected = new AtomicBoolean(false);
    private Socket telnetSocket;
    private BufferedReader telnetReader;
    private BufferedWriter telnetWriter;

    public TelnetPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // 控制行
        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ctrlRow.add(new JLabel("主机:"));
        telnetHostField = new JTextField("127.0.0.1", 14);
        telnetHostField.setFont(FONT_TEXT);
        ctrlRow.add(telnetHostField);
        ctrlRow.add(new JLabel("端口:"));
        telnetPortField = new JTextField("23", 5);
        telnetPortField.setFont(FONT_TEXT);
        ctrlRow.add(telnetPortField);

        btnTelnetConnect = makeBtn("连接", new Color(0x4CAF50));
        btnTelnetDisconnect = makeBtn("断开", new Color(0xE57373));
        btnTelnetDisconnect.setEnabled(false);
        ctrlRow.add(btnTelnetConnect);
        ctrlRow.add(btnTelnetDisconnect);
        add(ctrlRow, BorderLayout.NORTH);

        // 日志区
        telnetLog = createLogPane();
        JScrollPane logScroll = createLogScroll(telnetLog);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "Telnet 终端", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        add(logScroll, BorderLayout.CENTER);

        // 输入行
        JPanel inputRow = new JPanel(new BorderLayout(4, 0));
        telnetInputField = new JTextField();
        telnetInputField.setFont(new Font("Consolas", Font.PLAIN, 14));
        telnetInputField.setToolTipText("输入命令后按回车或点击发送，输入 exit 可断开");
        telnetInputField.setEnabled(false);
        NetUtil.fixPaste(telnetInputField);
        inputRow.add(telnetInputField, BorderLayout.CENTER);

        btnTelnetSend = makeBtn("发送", new Color(0x42A5F5));
        btnTelnetSend.setEnabled(false);
        inputRow.add(btnTelnetSend, BorderLayout.EAST);
        add(inputRow, BorderLayout.SOUTH);

        // 事件绑定
        btnTelnetConnect.addActionListener(e -> doTelnetConnect());
        btnTelnetDisconnect.addActionListener(e -> doTelnetDisconnect());
        btnTelnetSend.addActionListener(e -> doTelnetSend());
        telnetInputField.addActionListener(e -> doTelnetSend());
    }

    // ==================== Telnet 逻辑 ====================

    private void doTelnetConnect() {
        if (telnetConnected.get()) return;

        String host = telnetHostField.getText().trim();
        int port;
        try {
            port = Integer.parseInt(telnetPortField.getText().trim());
        } catch (NumberFormatException e) {
            logErr(telnetLog, "[Telnet] 端口格式错误");
            return;
        }

        final String targetHost = host;
        final int targetPort = port;

        ThreadPoolUtil.submitVirtual(() -> {
            try {
                telnetSocket = new Socket();
                telnetSocket.connect(new InetSocketAddress(targetHost, targetPort), 5000);
                telnetReader = new BufferedReader(new InputStreamReader(telnetSocket.getInputStream()));
                telnetWriter = new BufferedWriter(new OutputStreamWriter(telnetSocket.getOutputStream()));

                telnetConnected.set(true);
                SwingUtilities.invokeLater(() -> {
                    btnTelnetConnect.setEnabled(false);
                    btnTelnetDisconnect.setEnabled(true);
                    telnetInputField.setEnabled(true);
                    btnTelnetSend.setEnabled(true);
                    telnetHostField.setEnabled(false);
                    telnetPortField.setEnabled(false);
                    logSys(telnetLog, "[Telnet] 已连接到 " + targetHost + ":" + targetPort);
                });

                // 读取线程
                try {
                    String line;
                    while (telnetConnected.get() && (line = telnetReader.readLine()) != null) {
                        final String recvLine = line;
                        SwingUtilities.invokeLater(() -> logRecv(telnetLog, recvLine));
                    }
                } catch (IOException e) {
                    if (telnetConnected.get()) {
                        SwingUtilities.invokeLater(() -> logErr(telnetLog, "[Telnet] 连接中断: " + e.getMessage()));
                    }
                }
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> logErr(telnetLog, "[Telnet] 连接失败: " + e.getMessage()));
            } finally {
                doTelnetDisconnect();
            }
        });
    }

    private void doTelnetDisconnect() {
        telnetConnected.set(false);
        try { if (telnetReader != null) telnetReader.close(); } catch (Exception ignored) {}
        try { if (telnetWriter != null) telnetWriter.close(); } catch (Exception ignored) {}
        try { if (telnetSocket != null) telnetSocket.close(); } catch (Exception ignored) {}
        telnetSocket = null;
        telnetReader = null;
        telnetWriter = null;

        SwingUtilities.invokeLater(() -> {
            btnTelnetConnect.setEnabled(true);
            btnTelnetDisconnect.setEnabled(false);
            telnetInputField.setEnabled(false);
            btnTelnetSend.setEnabled(false);
            telnetHostField.setEnabled(true);
            telnetPortField.setEnabled(true);
            logSys(telnetLog, "[Telnet] 已断开连接");
        });
    }

    private void doTelnetSend() {
        if (!telnetConnected.get() || telnetWriter == null) return;

        String cmd = telnetInputField.getText().trim();
        if (cmd.isEmpty()) return;

        telnetInputField.setText("");

        ThreadPoolUtil.submitVirtual(() -> {
            try {
                telnetWriter.write(cmd);
                telnetWriter.newLine();
                telnetWriter.flush();
                SwingUtilities.invokeLater(() -> logSend(telnetLog, cmd));
            } catch (IOException e) {
                SwingUtilities.invokeLater(() -> logErr(telnetLog, "[Telnet] 发送失败: " + e.getMessage()));
            }
        });
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(telnetHostField, config, "telnet.host");
        loadField(telnetPortField, config, "telnet.port");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(telnetHostField, config, "telnet.host");
        saveField(telnetPortField, config, "telnet.port");
    }
}
