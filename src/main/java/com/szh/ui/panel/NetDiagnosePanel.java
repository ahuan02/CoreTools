package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.szh.utils.NetUtil.*;

/**
 * 网络诊断工具：Ping + DNS解析
 */
public class NetDiagnosePanel extends AbstractCommandPanel {

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();

    // ===== Ping =====
    private JTextField pingHostField;
    private JTextField pingCountField;
    private JTextField pingTimeoutField;
    private JButton btnPing;
    private JButton btnPingStop;
    private JTable pingTable;
    private DefaultTableModel pingTableModel;
    private JLabel pingStatsLabel;
    private final AtomicBoolean pingRunning = new AtomicBoolean(false);

    // ===== DNS 解析 =====
    private JTextField dnsHostField;
    private JButton btnDnsResolve;
    private JButton btnReverseDns;
    private JTable dnsTable;
    private DefaultTableModel dnsTableModel;

    public NetDiagnosePanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));
        setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));

        // ---- 上半部分：Ping 控制 + 结果表格 ----
        JPanel topArea = new JPanel(new BorderLayout(4, 4));

        // 控制行：主机 + 次数 + 超时 + 扫描/停止
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.add(new JLabel("主机:"));
        pingHostField = new JTextField("www.baidu.com", 16);
        pingHostField.setFont(FONT_TEXT);
        pingHostField.setToolTipText("域名或 IP 地址");
        row1.add(pingHostField);
        row1.add(new JLabel("次数:"));
        pingCountField = new JTextField("4", 4);
        pingCountField.setFont(FONT_TEXT);
        pingCountField.setToolTipText("Ping 次数，0 表示持续直到手动停止");
        row1.add(pingCountField);
        row1.add(new JLabel("超时:"));
        pingTimeoutField = new JTextField("1000", 5);
        pingTimeoutField.setFont(FONT_TEXT);
        pingTimeoutField.setToolTipText("每次 Ping 超时（毫秒）");
        row1.add(pingTimeoutField);
        row1.add(new JLabel("ms"));

        btnPing = makeBtn("Ping", new Color(0x4CAF50));
        btnPingStop = makeBtn("停止", new Color(0xE57373));
        btnPingStop.setEnabled(false);
        row1.add(btnPing);
        row1.add(btnPingStop);

        topArea.add(row1, BorderLayout.NORTH);

        // Ping 结果表格：序号 | 目标 | IP | 字节 | 延迟 | TTL | 状态
        String[] pingCols = {"序号", "目标", "解析IP", "字节", "延迟", "TTL", "状态"};
        pingTableModel = new DefaultTableModel(pingCols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        pingTable = new JTable(pingTableModel);
        pingTable.setFont(FONT_TEXT);
        pingTable.setRowHeight(22);
        pingTable.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        pingTable.setSelectionBackground(new Color(0x333333));
        pingTable.setSelectionForeground(new Color(0x64B5F6));
        pingTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        pingTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        pingTable.getColumnModel().getColumn(2).setPreferredWidth(110);
        pingTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        pingTable.getColumnModel().getColumn(4).setPreferredWidth(65);
        pingTable.getColumnModel().getColumn(5).setPreferredWidth(45);
        pingTable.getColumnModel().getColumn(6).setPreferredWidth(100);

        // 居中渲染
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        for (int i = 0; i < 7; i++) {
            pingTable.getColumnModel().getColumn(i).setCellRenderer(centerRenderer);
        }

        // 延迟列绿色标注
        pingTable.getColumnModel().getColumn(4).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!isSelected && value != null) {
                    try {
                        String s = value.toString().replace("ms", "").trim();
                        if (s.equals("-") || s.isEmpty()) {
                            c.setBackground(table.getBackground());
                            c.setForeground(table.getForeground());
                        } else {
                            int ms = Integer.parseInt(s);
                            if (ms <= 5) {
                                c.setBackground(new Color(0x1B5E20)); c.setForeground(new Color(0x69F0AE));
                            } else if (ms <= 15) {
                                c.setBackground(new Color(0x33691E)); c.setForeground(new Color(0xAED581));
                            } else if (ms <= 40) {
                                c.setBackground(new Color(0x558B2F)); c.setForeground(new Color(0xDCEDC8));
                            } else {
                                c.setBackground(table.getBackground());
                                c.setForeground(table.getForeground());
                            }
                        }
                    } catch (NumberFormatException e) {
                        c.setBackground(table.getBackground());
                        c.setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });

        JScrollPane pingScroll = new JScrollPane(pingTable);
        pingScroll.setPreferredSize(new Dimension(0, 180));
        topArea.add(pingScroll, BorderLayout.CENTER);

        // 统计信息栏
        pingStatsLabel = new JLabel("就绪");
        pingStatsLabel.setFont(FONT_TEXT);
        pingStatsLabel.setBorder(BorderFactory.createEmptyBorder(4, 8, 4, 8));
        topArea.add(pingStatsLabel, BorderLayout.SOUTH);

        // ---- 下半部分：DNS 解析 ----
        JPanel bottomArea = new JPanel(new BorderLayout(4, 4));
        bottomArea.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "DNS 域名解析", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        JPanel dnsCtrl = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        dnsCtrl.add(new JLabel("域名/IP:"));
        dnsHostField = new JTextField("www.baidu.com", 18);
        dnsHostField.setFont(FONT_TEXT);
        dnsCtrl.add(dnsHostField);
        btnDnsResolve = makeBtn("解析", new Color(0x42A5F5));
        btnReverseDns = makeBtn("反查", new Color(0xAB47BC));
        dnsCtrl.add(btnDnsResolve);
        dnsCtrl.add(btnReverseDns);
        bottomArea.add(dnsCtrl, BorderLayout.NORTH);

        // DNS 结果表格
        String[] dnsCols = {"类型", "主机名", "IP 地址", "别名"};
        dnsTableModel = new DefaultTableModel(dnsCols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        dnsTable = new JTable(dnsTableModel);
        dnsTable.setFont(FONT_TEXT);
        dnsTable.setRowHeight(22);
        dnsTable.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        dnsTable.setSelectionBackground(new Color(0x333333));
        dnsTable.setSelectionForeground(new Color(0x64B5F6));
        dnsTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        dnsTable.getColumnModel().getColumn(1).setPreferredWidth(160);
        dnsTable.getColumnModel().getColumn(2).setPreferredWidth(150);
        dnsTable.getColumnModel().getColumn(3).setPreferredWidth(140);

        JScrollPane dnsScroll = new JScrollPane(dnsTable);
        bottomArea.add(dnsScroll, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topArea, bottomArea);
        splitPane.setResizeWeight(0.55);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        // 事件绑定
        btnPing.addActionListener(e -> doPing());
        btnPingStop.addActionListener(e -> stopPing());
        btnDnsResolve.addActionListener(e -> doDnsResolve());
        btnReverseDns.addActionListener(e -> doReverseDns());
    }

    // ==================== Ping 逻辑 ====================

    private void doPing() {
        String host = pingHostField.getText().trim();
        if (host.isEmpty()) {
            pingStatsLabel.setText("请输入目标主机");
            return;
        }

        int count;
        try {
            count = Integer.parseInt(pingCountField.getText().trim());
            if (count < 0) count = 0;
        } catch (NumberFormatException e) {
            pingStatsLabel.setText("次数格式错误");
            return;
        }

        int timeout;
        try {
            timeout = Integer.parseInt(pingTimeoutField.getText().trim());
            if (timeout < 100) timeout = 100;
        } catch (NumberFormatException e) {
            timeout = 1000;
        }

        final String targetHost = host;
        final int maxCount = count;
        final int pingTimeout = timeout;

        pingRunning.set(true);
        SwingUtilities.invokeLater(() -> {
            btnPing.setEnabled(false);
            btnPingStop.setEnabled(true);
            pingTableModel.setRowCount(0);
            pingStatsLabel.setText("Ping " + targetHost + " ...");
        });

        threadPool.submit(() -> {
            try {
                // 先解析 IP
                String resolvedIp;
                try {
                    resolvedIp = InetAddress.getByName(targetHost).getHostAddress();
                } catch (UnknownHostException e) {
                    SwingUtilities.invokeLater(() -> {
                        pingTableModel.addRow(new Object[]{1, targetHost, "解析失败", "-", "-", "-", "无法解析主机"});
                        pingStatsLabel.setText("错误: 无法解析主机 " + targetHost);
                    });
                    pingRunning.set(false);
                    SwingUtilities.invokeLater(() -> {
                        btnPing.setEnabled(true);
                        btnPingStop.setEnabled(false);
                    });
                    return;
                }

                int seq = 0;
                int sent = 0, received = 0, lost = 0;
                long minDelay = Long.MAX_VALUE, maxDelay = 0, totalDelay = 0;

                while (pingRunning.get()) {
                    if (maxCount > 0 && seq >= maxCount) break;

                    seq++;
                    sent++;
                    boolean success = false;
                    long delay = -1;
                    int ttl = -1;
                    int bytes = 32;

                    try {
                        long start = System.currentTimeMillis();
                        ProcessBuilder pb = new ProcessBuilder(
                                "ping", "-n", "1", "-w", String.valueOf(pingTimeout), "-l", "32", targetHost);
                        Process p = pb.start();
                        String line;
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(p.getInputStream(), "GBK"))) {
                            while ((line = reader.readLine()) != null) {
                                if (line.contains("来自") || line.contains("Reply from")) {
                                    success = true;
                                    int ttlIdx = line.toUpperCase().indexOf("TTL=");
                                    if (ttlIdx >= 0) {
                                        StringBuilder num = new StringBuilder();
                                        String sub = line.substring(ttlIdx + 4);
                                        for (char c : sub.toCharArray()) {
                                            if (Character.isDigit(c)) num.append(c);
                                            else break;
                                        }
                                        try { ttl = Integer.parseInt(num.toString()); } catch (NumberFormatException ignored) {}
                                    }
                                    int byteIdx = line.indexOf("字节=");
                                    if (byteIdx < 0) byteIdx = line.indexOf("bytes=");
                                    if (byteIdx >= 0) {
                                        StringBuilder bnum = new StringBuilder();
                                        String bsub = line.substring(byteIdx + (line.charAt(byteIdx) == '字' ? 3 : 6));
                                        for (char c : bsub.toCharArray()) {
                                            if (Character.isDigit(c)) bnum.append(c);
                                            else break;
                                        }
                                        try { bytes = Integer.parseInt(bnum.toString()); } catch (NumberFormatException ignored) {}
                                    }
                                }
                            }
                        }
                        p.waitFor();
                        delay = System.currentTimeMillis() - start;
                    } catch (Exception ignored) {}

                    final int finalSeq = seq;
                    final boolean finalSuccess = success;
                    final long finalDelay = delay;
                    final int finalTtl = ttl;
                    final int finalBytes = bytes;

                    if (finalSuccess) {
                        received++;
                        if (delay < minDelay) minDelay = delay;
                        if (delay > maxDelay) maxDelay = delay;
                        totalDelay += delay;
                        SwingUtilities.invokeLater(() ->
                                pingTableModel.addRow(new Object[]{
                                        finalSeq, targetHost, resolvedIp, finalBytes + "B",
                                        finalDelay + "ms", finalTtl > 0 ? String.valueOf(finalTtl) : "-",
                                        "成功"
                                }));
                    } else {
                        lost++;
                        SwingUtilities.invokeLater(() ->
                                pingTableModel.addRow(new Object[]{
                                        finalSeq, targetHost, resolvedIp, "-", "-", "-", "超时"
                                }));
                    }

                    // 更新统计
                    final int fSent = sent, fRecv = received, fLost = lost;
                    final long fMin = minDelay == Long.MAX_VALUE ? 0 : minDelay;
                    final long fMax = maxDelay;
                    final long fAvg = received > 0 ? totalDelay / received : 0;
                    SwingUtilities.invokeLater(() ->
                            pingStatsLabel.setText(String.format(
                                    "发送: %d | 接收: %d | 丢包: %d (%.0f%%) | 最短: %dms | 最长: %dms | 平均: %dms",
                                    fSent, fRecv, fLost, fSent > 0 ? fLost * 100.0 / fSent : 0,
                                    fMin, fMax, fAvg)));

                    if (!pingRunning.get()) break;
                    if (maxCount > 0 && seq >= maxCount) break;
                }
            } finally {
                pingRunning.set(false);
                SwingUtilities.invokeLater(() -> {
                    btnPing.setEnabled(true);
                    btnPingStop.setEnabled(false);
                });
            }
        });
    }

    private void stopPing() {
        pingRunning.set(false);
        btnPingStop.setEnabled(false);
    }

    // ==================== DNS 解析逻辑 ====================

    private void doDnsResolve() {
        String host = dnsHostField.getText().trim();
        if (host.isEmpty()) return;

        dnsTableModel.setRowCount(0);
        threadPool.submit(() -> {
            try {
                InetAddress[] addrs = InetAddress.getAllByName(host);
                for (InetAddress addr : addrs) {
                    String ip = addr.getHostAddress();
                    String hostname = addr.getHostName();
                    String alias = addr.getCanonicalHostName();
                    boolean isIpv6 = addr instanceof Inet6Address;
                    SwingUtilities.invokeLater(() ->
                            dnsTableModel.addRow(new Object[]{
                                    isIpv6 ? "AAAA" : "A",
                                    hostname,
                                    ip,
                                    alias.equals(hostname) || alias.equals(ip) ? "-" : alias
                            }));
                }
                if (addrs.length == 0) {
                    SwingUtilities.invokeLater(() ->
                            dnsTableModel.addRow(new Object[]{"-", host, "未找到记录", "-"}));
                }
            } catch (UnknownHostException e) {
                SwingUtilities.invokeLater(() ->
                        dnsTableModel.addRow(new Object[]{"错误", host, "无法解析: " + e.getMessage(), "-"}));
            }
        });
    }

    private void doReverseDns() {
        String ip = dnsHostField.getText().trim();
        if (ip.isEmpty()) return;

        dnsTableModel.setRowCount(0);
        threadPool.submit(() -> {
            try {
                InetAddress addr = InetAddress.getByName(ip);
                String hostname = addr.getHostName();
                String canonical = addr.getCanonicalHostName();
                if (hostname.equals(ip)) {
                    SwingUtilities.invokeLater(() ->
                            dnsTableModel.addRow(new Object[]{"PTR", ip, "无反向记录", "-"}));
                } else {
                    SwingUtilities.invokeLater(() ->
                            dnsTableModel.addRow(new Object[]{"PTR", hostname, ip,
                                    canonical.equals(hostname) ? "-" : canonical}));
                }
            } catch (UnknownHostException e) {
                SwingUtilities.invokeLater(() ->
                        dnsTableModel.addRow(new Object[]{"错误", ip, "无法反查: " + e.getMessage(), "-"}));
            }
        });
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(pingHostField, config, "diag.ping.host");
        loadField(pingCountField, config, "diag.ping.count");
        loadField(pingTimeoutField, config, "diag.ping.timeout");
        loadField(dnsHostField, config, "diag.dns.host");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(pingHostField, config, "diag.ping.host");
        saveField(pingCountField, config, "diag.ping.count");
        saveField(pingTimeoutField, config, "diag.ping.timeout");
        saveField(dnsHostField, config, "diag.dns.host");
    }
}
