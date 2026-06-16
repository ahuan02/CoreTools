package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static com.szh.ui.panel.NetUtil.*;

/**
 * IP & 端口扫描工具
 * - 网段扫描：Ping 在线主机 + 常用端口探测
 * - 端口扫描：指定 IP + 端口范围，TCP 连接检测
 */
public class ScanPanel extends AbstractCommandPanel {

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();

    // ===== 网段扫描 =====
    private JTextField subnetField;
    private JTextField subnetPortsField;
    private JTextField subnetIntervalField;  // 扫描间隔（秒）
    private JCheckBox subnetLoopBox;  // 循环扫描
    private JButton btnSubnetScan;
    private JButton btnSubnetStop;
    private JTable subnetTable;
    private DefaultTableModel subnetTableModel;
    private final AtomicBoolean subnetScanning = new AtomicBoolean(false);
    private final Map<String, Integer> subnetRowMap = new HashMap<>();  // IP -> 行号映射

    // ===== 端口扫描 =====
    private JTextField ipField;
    private JTextField portStartField;
    private JTextField portEndField;
    private JTextField portIntervalField;  // 扫描间隔（秒）
    private JCheckBox portLoopBox;  // 循环扫描
    private JButton btnPortScan;
    private JButton btnPortStop;
    private JTable portTable;
    private DefaultTableModel portTableModel;
    private final AtomicBoolean portScanning = new AtomicBoolean(false);
    private final Map<Integer, Integer> portRowMap = new HashMap<>();  // 端口 -> 行号映射

    // ===== 日志 =====
    private JTextPane logPane;

    public ScanPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));

        // ===== 上部：网段扫描 + 端口扫描并排 =====
        JPanel topArea = new JPanel(new GridLayout(1, 2, 6, 0));

        topArea.add(buildSubnetPanel());
        topArea.add(buildPortPanel());

        // ===== 下部：日志 =====
        logPane = createLogPane();
        JScrollPane logScroll = createLogScroll(logPane);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "扫描日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 11)));

        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, topArea, logScroll);
        splitPane.setResizeWeight(0.55);
        splitPane.setDividerLocation(320);
        splitPane.setBorder(null);
        add(splitPane, BorderLayout.CENTER);

        // ===== 事件绑定 =====
        btnSubnetScan.addActionListener(e -> doSubnetScan());
        btnSubnetStop.addActionListener(e -> stopSubnetScan());
        btnPortScan.addActionListener(e -> doPortScan());
        btnPortStop.addActionListener(e -> stopPortScan());
    }

    // ==================== 网段扫描面板 ====================

    private JPanel buildSubnetPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));

        String localIP = detectLocalIP();
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "网段扫描   本机: " + localIP, TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        // 控制区：两行
        JPanel ctrlArea = new JPanel(new GridLayout(2, 1, 0, 2));

        // 第一行：网段输入 + 扫描/停止
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.add(new JLabel("网段:"));
        String autoSubnet = detectLocalSubnet();
        subnetField = new JTextField(autoSubnet, 15);
        subnetField.setFont(FONT_TEXT);
        subnetField.setToolTipText("自动检测的网段前缀（/24），可手动修改");
        row1.add(subnetField);

        btnSubnetScan = makeBtn("扫描", new Color(0x4CAF50));
        btnSubnetStop = makeBtn("停止", new Color(0xE57373));
        btnSubnetStop.setEnabled(false);
        row1.add(btnSubnetScan);
        row1.add(btnSubnetStop);

        // 第二行：端口输入 + 循环 + 间隔
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.add(new JLabel("端口:"));
        subnetPortsField = new JTextField("80,443,22,3389,8080,23,21,25,554,8554,8000,8888,37777,34567,9527,8899,554,7070", 25);
        subnetPortsField.setFont(FONT_TEXT);
        subnetPortsField.setToolTipText("逗号分隔，留空则仅 Ping。含摄像头/物联网/IoT常见端口");
        row2.add(subnetPortsField);

        subnetLoopBox = new JCheckBox("循环");
        subnetLoopBox.setFont(FONT_TEXT);
        subnetLoopBox.setToolTipText("勾选后持续循环扫描，直到按停止");
        row2.add(subnetLoopBox);

        row2.add(new JLabel("间隔:"));
        subnetIntervalField = new JTextField("5", 4);
        subnetIntervalField.setFont(FONT_TEXT);
        subnetIntervalField.setToolTipText("循环扫描间隔（秒），默认5秒");
        row2.add(subnetIntervalField);
        row2.add(new JLabel("秒"));

        ctrlArea.add(row1);
        ctrlArea.add(row2);
        panel.add(ctrlArea, BorderLayout.NORTH);

        // 结果表格: IP | 主机名 | MAC地址 | OS | 开放端口 | 延迟
        String[] cols = {"IP", "主机名", "MAC地址", "OS", "开放端口", "延迟"};
        subnetTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        subnetTable = new JTable(subnetTableModel);
        subnetTable.setFont(FONT_TEXT);
        subnetTable.setRowHeight(22);
        subnetTable.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        subnetTable.setSelectionBackground(new Color(0x333333));
        subnetTable.setSelectionForeground(new Color(0x64B5F6));
        subnetTable.getColumnModel().getColumn(0).setPreferredWidth(110);
        subnetTable.getColumnModel().getColumn(1).setPreferredWidth(130);
        subnetTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        subnetTable.getColumnModel().getColumn(3).setPreferredWidth(55);
        subnetTable.getColumnModel().getColumn(4).setPreferredWidth(160);
        subnetTable.getColumnModel().getColumn(5).setPreferredWidth(70);

        // 延迟列：绿色标注低延迟
        subnetTable.getColumnModel().getColumn(5).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!isSelected) {
                    try {
                        String s = value != null ? value.toString() : "";
                        int ms = Integer.parseInt(s.replace("ms", "").trim());
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
                    } catch (NumberFormatException e) {
                        c.setBackground(table.getBackground());
                        c.setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });

        JScrollPane tableScroll = new JScrollPane(subnetTable);
        tableScroll.setPreferredSize(new Dimension(0, 0));
        panel.add(tableScroll, BorderLayout.CENTER);

        return panel;
    }

    // ==================== 端口扫描面板 ====================

    private JPanel buildPortPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "端口扫描", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        // 控制区：两行
        JPanel ctrlArea = new JPanel(new GridLayout(2, 1, 0, 2));

        // 第一行：IP + 端口范围 + 扫描/停止
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.add(new JLabel("IP:"));
        ipField = new JTextField("127.0.0.1", 13);
        ipField.setFont(FONT_TEXT);
        row1.add(ipField);
        row1.add(new JLabel("端口:"));
        portStartField = new JTextField("1", 5);
        portStartField.setFont(FONT_TEXT);
        row1.add(portStartField);
        row1.add(new JLabel("-"));
        portEndField = new JTextField("10000", 5);
        portEndField.setFont(FONT_TEXT);
        row1.add(portEndField);

        btnPortScan = makeBtn("扫描", new Color(0x4CAF50));
        btnPortStop = makeBtn("停止", new Color(0xE57373));
        btnPortStop.setEnabled(false);
        row1.add(btnPortScan);
        row1.add(btnPortStop);

        // 第二行：循环 + 间隔
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        portLoopBox = new JCheckBox("循环");
        portLoopBox.setFont(FONT_TEXT);
        portLoopBox.setToolTipText("勾选后持续循环扫描，直到按停止");
        row2.add(portLoopBox);

        row2.add(new JLabel("间隔:"));
        portIntervalField = new JTextField("5", 4);
        portIntervalField.setFont(FONT_TEXT);
        portIntervalField.setToolTipText("循环扫描间隔（秒），默认5秒");
        row2.add(portIntervalField);
        row2.add(new JLabel("秒"));

        ctrlArea.add(row1);
        ctrlArea.add(row2);
        panel.add(ctrlArea, BorderLayout.NORTH);

        // 结果表格
        String[] cols = {"端口", "服务", "延迟"};
        portTableModel = new DefaultTableModel(cols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        portTable = new JTable(portTableModel);
        portTable.setFont(FONT_TEXT);
        portTable.setRowHeight(22);
        portTable.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        portTable.setSelectionBackground(new Color(0x333333));
        portTable.setSelectionForeground(new Color(0x64B5F6));
        portTable.getColumnModel().getColumn(0).setPreferredWidth(60);
        portTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        portTable.getColumnModel().getColumn(2).setPreferredWidth(70);

        // 端口列居中
        DefaultTableCellRenderer centerRenderer = new DefaultTableCellRenderer();
        centerRenderer.setHorizontalAlignment(SwingConstants.CENTER);
        portTable.getColumnModel().getColumn(0).setCellRenderer(centerRenderer);

        // 延迟列：绿色标注
        portTable.getColumnModel().getColumn(2).setCellRenderer(new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                setHorizontalAlignment(SwingConstants.CENTER);
                if (!isSelected) {
                    try {
                        String s = value != null ? value.toString() : "";
                        int ms = Integer.parseInt(s.replace("ms", "").trim());
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
                    } catch (NumberFormatException e) {
                        c.setBackground(table.getBackground());
                        c.setForeground(table.getForeground());
                    }
                }
                return c;
            }
        });

        JScrollPane tableScroll = new JScrollPane(portTable);
        tableScroll.setPreferredSize(new Dimension(0, 0));
        panel.add(tableScroll, BorderLayout.CENTER);

        return panel;
    }

    // ==================== 网段扫描逻辑 ====================

    private void doSubnetScan() {
        String subnet = subnetField.getText().trim();
        if (subnet.isEmpty()) { logWarn(logPane, "请输入网段前缀"); return; }
        // 确保网段以 . 结尾
        if (!subnet.endsWith(".")) subnet += ".";

        final String subnetPrefix = subnet;
        String portsText = subnetPortsField.getText().trim();
        final int[] ports;
        if (portsText.isEmpty()) {
            ports = new int[0];
        } else {
            String[] parts = portsText.split(",");
            ports = new int[parts.length];
            for (int i = 0; i < parts.length; i++) {
                try { ports[i] = Integer.parseInt(parts[i].trim()); }
                catch (NumberFormatException e) { logWarn(logPane, "端口格式错误: " + parts[i]); return; }
            }
        }

        final boolean loopMode = subnetLoopBox.isSelected();
        int intervalSec;
        try {
            intervalSec = Integer.parseInt(subnetIntervalField.getText().trim());
            if (intervalSec < 1) intervalSec = 1;
        } catch (NumberFormatException e) {
            intervalSec = 5;
        }
        final int intervalMs = intervalSec * 1000;

        subnetScanning.set(true);
        SwingUtilities.invokeLater(() -> {
            btnSubnetScan.setEnabled(false);
            btnSubnetStop.setEnabled(true);
            subnetLoopBox.setEnabled(false);
            subnetIntervalField.setEnabled(false);
            if (!loopMode) {
                subnetTableModel.setRowCount(0);
                subnetRowMap.clear();
            }
        });

        threadPool.submit(() -> {
            int round = 0;
            while (subnetScanning.get()) {
                round++;
                logSys(logPane, "[网段扫描] 第 " + round + " 轮扫描 " + subnetPrefix + "0/24" +
                        (ports.length > 0 ? " 探测端口: " + Arrays.toString(ports) : ""));
                if (loopMode) {
                    logSys(logPane, "[网段扫描] 循环模式，间隔 " + (intervalMs / 1000) + " 秒，按[停止]结束...");
                }

                // 本轮的存活 IP 集合，用于后续清理已下线的 IP
                Set<String> thisRoundAlive = ConcurrentHashMap.newKeySet();
                AtomicInteger aliveCount = new AtomicInteger(0);
                AtomicInteger completedCount = new AtomicInteger(0);
                final int total = 254;

                for (int i = 1; i <= total && subnetScanning.get(); i++) {
                    final int host = i;
                    threadPool.submit(() -> {
                        if (!subnetScanning.get()) {
                            completedCount.incrementAndGet();
                            return;
                        }
                        try {
                            String ip = subnetPrefix + host;
                            InetAddress addr = InetAddress.getByName(ip);
                            long start = System.currentTimeMillis();
                            boolean reachable = addr.isReachable(800);
                            long delay = System.currentTimeMillis() - start;

                            if (reachable && subnetScanning.get()) {
                                aliveCount.incrementAndGet();
                                thisRoundAlive.add(ip);
                                String hostname = resolveHostname(addr);
                                String mac = resolveMac(ip);
                                String osGuess = guessOS(ip);

                                StringBuilder infoParts = new StringBuilder();
                                if (!mac.isEmpty()) infoParts.append("MAC: ").append(mac);
                                if (!osGuess.isEmpty()) {
                                    if (infoParts.length() > 0) infoParts.append(", ");
                                    infoParts.append("OS: ").append(osGuess);
                                }

                                if (ports.length > 0) {
                                    StringBuilder openPorts = new StringBuilder();
                                    for (int port : ports) {
                                        if (!subnetScanning.get()) break;
                                        if (testPort(ip, port, 300)) {
                                            if (openPorts.length() > 0) openPorts.append(", ");
                                            String svc = getPortService(port);
                                            openPorts.append(port).append(svc.isEmpty() ? "" : "(" + svc + ")");
                                        }
                                    }
                                    String portsStr = openPorts.length() > 0 ? openPorts.toString() : "-";
                                    SwingUtilities.invokeLater(() ->
                                            upsertSubnetRow(ip, hostname, mac, osGuess, portsStr, delay + "ms"));
                                } else {
                                    SwingUtilities.invokeLater(() ->
                                            upsertSubnetRow(ip, hostname, mac, osGuess, "-", delay + "ms"));
                                }
                                logSys(logPane, "[网段扫描] " + ip + " 在线, 延迟 " + delay + "ms" +
                                        (hostname.isEmpty() ? "" : " (" + hostname + ")") +
                                        (infoParts.length() > 0 ? " [" + infoParts + "]" : ""));
                            }
                        } catch (Exception ignored) {
                        } finally {
                            completedCount.incrementAndGet();
                        }
                    });
                }

                // 等待本轮 254 个任务完成
                while (completedCount.get() < total && subnetScanning.get()) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }

                // 等待所有 EDT 上的 upsertSubnetRow 执行完，避免行号冲突
                if (loopMode && subnetScanning.get()) {
                    try {
                        SwingUtilities.invokeAndWait(() -> {});
                    } catch (Exception ignored) {}
                }

                // 循环模式下：清理已不在线的 IP 行（从表格中移除）
                if (loopMode && subnetScanning.get()) {
                    // 从后往前删除不在本轮存活集合中的 IP
                    for (int row = subnetTableModel.getRowCount() - 1; row >= 0; row--) {
                        if (!subnetScanning.get()) break;
                        String rowIp = (String) subnetTableModel.getValueAt(row, 0);
                        if (rowIp != null && !thisRoundAlive.contains(rowIp)) {
                            subnetTableModel.removeRow(row);
                        }
                    }
                    // 重建 rowMap
                    rebuildSubnetRowMap();
                }

                int count = aliveCount.get();
                logSys(logPane, "[网段扫描] 第 " + round + " 轮完成, 在线: " + count + " / " + total);

                // 每轮结束后按延迟从小到大排序（延迟越小越靠前）
                sortSubnetTableByDelay();

                if (!loopMode) break;  // 单次模式，一轮就结束

                // 等待间隔时间
                if (subnetScanning.get()) {
                    try { Thread.sleep(intervalMs); } catch (InterruptedException ignored) {}
                }
            }

            subnetScanning.set(false);
            logSys(logPane, "[网段扫描] 扫描结束, 共 " + round + " 轮");
            SwingUtilities.invokeLater(() -> {
                btnSubnetScan.setEnabled(true);
                btnSubnetStop.setEnabled(false);
                subnetLoopBox.setEnabled(true);
                subnetIntervalField.setEnabled(true);
            });
        });
    }

    /** 根据 IP 更新或插入网段扫描表格行 */
    private void upsertSubnetRow(String ip, String hostname, String mac, String osGuess, String ports, String delay) {
        synchronized (subnetRowMap) {
            Integer existingRow = subnetRowMap.get(ip);
            if (existingRow != null && existingRow < subnetTableModel.getRowCount()) {
                // 更新已有行
                subnetTableModel.setValueAt(hostname, existingRow, 1);
                subnetTableModel.setValueAt(mac, existingRow, 2);
                subnetTableModel.setValueAt(osGuess, existingRow, 3);
                subnetTableModel.setValueAt(ports, existingRow, 4);
                subnetTableModel.setValueAt(delay, existingRow, 5);
            } else {
                // 插入新行
                subnetTableModel.addRow(new Object[]{ip, hostname, mac, osGuess, ports, delay});
                subnetRowMap.put(ip, subnetTableModel.getRowCount() - 1);
            }
        }
    }

    /** 重建网段表格 IP->行号 映射（用于删除行后同步） */
    private void rebuildSubnetRowMap() {
        synchronized (subnetRowMap) {
            subnetRowMap.clear();
            for (int i = 0; i < subnetTableModel.getRowCount(); i++) {
                String ip = (String) subnetTableModel.getValueAt(i, 0);
                if (ip != null) {
                    subnetRowMap.put(ip, i);
                }
            }
        }
    }

    /** 按延迟从小到大排列表格行 */
    private void sortSubnetTableByDelay() {
        // 收集所有行数据
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < subnetTableModel.getRowCount(); i++) {
            Object[] row = new Object[6];
            for (int j = 0; j < 6; j++) row[j] = subnetTableModel.getValueAt(i, j);
            rows.add(row);
        }
        // 按延迟列排序（延迟越小越靠前）
        rows.sort((a, b) -> {
            try {
                int da = Integer.parseInt(a[5].toString().replace("ms", "").trim());
                int db = Integer.parseInt(b[5].toString().replace("ms", "").trim());
                return Integer.compare(da, db);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        // 重新填充表格
        subnetTableModel.setRowCount(0);
        synchronized (subnetRowMap) {
            subnetRowMap.clear();
            for (int i = 0; i < rows.size(); i++) {
                Object[] row = rows.get(i);
                subnetTableModel.addRow(row);
                subnetRowMap.put((String) row[0], i);
            }
        }
    }

    private void stopSubnetScan() {
        subnetScanning.set(false);
        btnSubnetStop.setEnabled(false);
        logSys(logPane, "[网段扫描] 正在停止...");
    }

    // ==================== 端口扫描逻辑 ====================

    private void doPortScan() {
        String ip = ipField.getText().trim();
        if (ip.isEmpty()) { logWarn(logPane, "请输入目标 IP"); return; }
        int startPort, endPort;
        try {
            startPort = Integer.parseInt(portStartField.getText().trim());
            endPort = Integer.parseInt(portEndField.getText().trim());
        } catch (NumberFormatException e) {
            logWarn(logPane, "端口格式错误");
            return;
        }
        if (startPort < 1 || endPort > 65535 || startPort > endPort) {
            logWarn(logPane, "端口范围无效 (1-65535)");
            return;
        }

        final String targetIp = ip;
        final int start = startPort;
        final int end = endPort;
        final boolean loopMode = portLoopBox.isSelected();
        int intervalSec;
        try {
            intervalSec = Integer.parseInt(portIntervalField.getText().trim());
            if (intervalSec < 1) intervalSec = 1;
        } catch (NumberFormatException e) {
            intervalSec = 5;
        }
        final int intervalMs = intervalSec * 1000;

        portScanning.set(true);
        SwingUtilities.invokeLater(() -> {
            btnPortScan.setEnabled(false);
            btnPortStop.setEnabled(true);
            portLoopBox.setEnabled(false);
            portIntervalField.setEnabled(false);
            if (!loopMode) {
                portTableModel.setRowCount(0);
                portRowMap.clear();
            }
        });

        threadPool.submit(() -> {
            int round = 0;
            while (portScanning.get()) {
                round++;
                logSys(logPane, "[端口扫描] 第 " + round + " 轮扫描 " + targetIp + " 端口 " + start + "-" + end);
                if (loopMode) {
                    logSys(logPane, "[端口扫描] 循环模式，间隔 " + (intervalMs / 1000) + " 秒，按[停止]结束...");
                }

                // 本轮开放的端口集合
                Set<Integer> thisRoundOpen = ConcurrentHashMap.newKeySet();
                AtomicInteger openCount = new AtomicInteger(0);
                AtomicInteger completedCount = new AtomicInteger(0);
                int total = end - start + 1;

                for (int port = start; port <= end && portScanning.get(); port++) {
                    final int p = port;
                    threadPool.submit(() -> {
                        if (!portScanning.get()) {
                            completedCount.incrementAndGet();
                            return;
                        }
                        try {
                            long t0 = System.currentTimeMillis();
                            boolean open = testPort(targetIp, p, 400);
                            long delay = System.currentTimeMillis() - t0;
                            if (open && portScanning.get()) {
                                openCount.incrementAndGet();
                                thisRoundOpen.add(p);
                                String service = getPortService(p);
                                SwingUtilities.invokeLater(() ->
                                        upsertPortRow(p, service, delay + "ms"));
                                logSys(logPane, "[端口扫描] " + targetIp + ":" + p + " 开放 (" + service + ")");
                            }
                        } catch (Exception ignored) {
                        } finally {
                            completedCount.incrementAndGet();
                        }
                    });
                }

                // 等待本轮所有任务完成
                while (completedCount.get() < total && portScanning.get()) {
                    try { Thread.sleep(200); } catch (InterruptedException ignored) {}
                }

                // 等待所有 EDT 上的 upsertPortRow 执行完，避免行号冲突
                if (loopMode && portScanning.get()) {
                    try {
                        SwingUtilities.invokeAndWait(() -> {});
                    } catch (Exception ignored) {}
                }

                // 循环模式下：清理已关闭的端口行
                if (loopMode && portScanning.get()) {
                    // 从后往前删除不在本轮开放集合中的端口
                    for (int row = portTableModel.getRowCount() - 1; row >= 0; row--) {
                        if (!portScanning.get()) break;
                        Object val = portTableModel.getValueAt(row, 0);
                        if (val instanceof Integer && !thisRoundOpen.contains((Integer) val)) {
                            portTableModel.removeRow(row);
                        }
                    }
                    rebuildPortRowMap();
                }

                int count = openCount.get();
                logSys(logPane, "[端口扫描] 第 " + round + " 轮完成, 开放: " + count + " / " + total);

                // 每轮结束后按延迟从小到大排序
                sortPortTableByDelay();

                if (!loopMode) break;

                // 等待间隔时间
                if (portScanning.get()) {
                    try { Thread.sleep(intervalMs); } catch (InterruptedException ignored) {}
                }
            }

            portScanning.set(false);
            logSys(logPane, "[端口扫描] 扫描结束, 共 " + round + " 轮");
            SwingUtilities.invokeLater(() -> {
                btnPortScan.setEnabled(true);
                btnPortStop.setEnabled(false);
                portLoopBox.setEnabled(true);
                portIntervalField.setEnabled(true);
            });
        });
    }

    /** 根据端口号更新或插入端口扫描表格行 */
    private void upsertPortRow(int port, String service, String delay) {
        synchronized (portRowMap) {
            Integer existingRow = portRowMap.get(port);
            if (existingRow != null && existingRow < portTableModel.getRowCount()) {
                portTableModel.setValueAt(service, existingRow, 1);
                portTableModel.setValueAt(delay, existingRow, 2);
            } else {
                portTableModel.addRow(new Object[]{port, service, delay});
                portRowMap.put(port, portTableModel.getRowCount() - 1);
            }
        }
    }

    /** 重建端口表格 端口->行号 映射 */
    private void rebuildPortRowMap() {
        synchronized (portRowMap) {
            portRowMap.clear();
            for (int i = 0; i < portTableModel.getRowCount(); i++) {
                Object val = portTableModel.getValueAt(i, 0);
                if (val instanceof Integer) {
                    portRowMap.put((Integer) val, i);
                }
            }
        }
    }

    /** 按延迟从小到大排序端口表格行 */
    private void sortPortTableByDelay() {
        List<Object[]> rows = new ArrayList<>();
        for (int i = 0; i < portTableModel.getRowCount(); i++) {
            Object[] row = new Object[3];
            for (int j = 0; j < 3; j++) row[j] = portTableModel.getValueAt(i, j);
            rows.add(row);
        }
        rows.sort((a, b) -> {
            try {
                int da = Integer.parseInt(a[2].toString().replace("ms", "").trim());
                int db = Integer.parseInt(b[2].toString().replace("ms", "").trim());
                return Integer.compare(da, db);
            } catch (NumberFormatException e) {
                return 0;
            }
        });
        portTableModel.setRowCount(0);
        synchronized (portRowMap) {
            portRowMap.clear();
            for (int i = 0; i < rows.size(); i++) {
                Object[] row = rows.get(i);
                portTableModel.addRow(row);
                portRowMap.put((Integer) row[0], i);
            }
        }
    }

    private void stopPortScan() {
        portScanning.set(false);
        btnPortStop.setEnabled(false);
        logSys(logPane, "[端口扫描] 正在停止...");
    }

    // ==================== 工具方法 ====================

    /**
     * 自动检测本机局域网网段前缀（/24），如 192.168.1.
     * 优先选择物理网卡（排除虚拟网卡），如果有多个则选能连外网的
     */
    private String detectLocalSubnet() {
        try {
            String ip = detectBestLocalIP();
            if (!ip.equals("未知")) {
                int lastDot = ip.lastIndexOf('.');
                if (lastDot > 0) {
                    return ip.substring(0, lastDot + 1);
                }
            }
        } catch (Exception ignored) {}
        return "192.168.1.";
    }

    /**
     * 获取本机最佳局域网 IPv4 地址
     * 策略：排除虚拟网卡 → 排除链路本地地址(169.254) → 选能连外网的 → 回退第一个物理网卡
     */
    private String detectLocalIP() {
        return detectBestLocalIP();
    }

    private String detectBestLocalIP() {
        try {
            // 先收集所有候选地址
            record Candidate(NetworkInterface ni, InetAddress addr) {}
            List<Candidate> candidates = new ArrayList<>();

            Enumeration<NetworkInterface> nis = NetworkInterface.getNetworkInterfaces();
            while (nis.hasMoreElements()) {
                NetworkInterface ni = nis.nextElement();
                if (ni.isLoopback() || !ni.isUp()) continue;
                String displayName = ni.getDisplayName() != null ? ni.getDisplayName().toLowerCase() : "";

                // 排除虚拟网卡：VMware, VirtualBox, Hyper-V, Virtual, Docker, WSL, Bluetooth, Loopback
                if (displayName.contains("vmware") || displayName.contains("virtualbox")
                        || displayName.contains("hyper-v") || displayName.contains("virtual")
                        || displayName.contains("docker") || displayName.contains("wsl")
                        || displayName.contains("bluetooth") || displayName.contains("loopback")) {
                    continue;
                }

                Enumeration<InetAddress> addrs = ni.getInetAddresses();
                while (addrs.hasMoreElements()) {
                    InetAddress addr = addrs.nextElement();
                    if (addr instanceof Inet4Address && !addr.isLoopbackAddress()) {
                        String ip = addr.getHostAddress();
                        // 排除链路本地地址 169.254.x.x
                        if (ip.startsWith("169.254.")) continue;
                        candidates.add(new Candidate(ni, addr));
                    }
                }
            }

            if (candidates.isEmpty()) return "未知";

            // 优先级：尝试用 8.8.8.8 UDP 连接来判断哪个网卡能出外网
            for (Candidate c : candidates) {
                try {
                    // 通过这个网卡地址尝试连接外网来判断是否为默认路由网卡
                    DatagramSocket testSocket = new DatagramSocket(new InetSocketAddress(c.addr, 0));
                    testSocket.connect(new InetSocketAddress("8.8.8.8", 53));
                    if (testSocket.isConnected()) {
                        testSocket.close();
                        return c.addr.getHostAddress();  // 返回能连外网的地址
                    }
                    testSocket.close();
                } catch (Exception ignored) {}
            }

            // 回退：返回第一个非虚拟网卡地址
            return candidates.get(0).addr.getHostAddress();
        } catch (SocketException ignored) {}
        return "未知";
    }

    /**
     * 通过系统 ARP 表获取 MAC 地址
     */
    private String resolveMac(String ip) {
        try {
            Process p = new ProcessBuilder("arp", "-a", ip).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (line.contains(ip)) {
                        // Windows arp -a 输出格式: 192.168.1.1         00-11-22-33-44-55     动态
                        // 提取 MAC: 6组十六进制，用 - 或 : 分隔
                        String[] parts = line.trim().split("\\s+");
                        for (String part : parts) {
                            if (part.matches("([0-9A-Fa-f]{2}[-:]){5}[0-9A-Fa-f]{2}")) {
                                return part.toUpperCase().replace('-', ':');
                            }
                        }
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return "";
    }

    /**
     * 通过 Ping TTL 推测操作系统
     * Windows: 128 (或 64, 但通常是128)
     * Linux/Unix: 64
     * 网络设备: 255
     */
    private String guessOS(String ip) {
        try {
            Process p = new ProcessBuilder("ping", "-n", "1", "-w", "500", ip).start();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(p.getInputStream(), "GBK"))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    // Windows ping 输出: 来自 192.168.1.1 的回复: 字节=32 时间<1ms TTL=64
                    if (line.contains("TTL=") || line.contains("ttl=")) {
                        int idx = line.toUpperCase().indexOf("TTL=");
                        if (idx >= 0) {
                            String ttlStr = line.substring(idx + 4).trim();
                            // 截取数字部分
                            StringBuilder num = new StringBuilder();
                            for (char c : ttlStr.toCharArray()) {
                                if (Character.isDigit(c)) num.append(c);
                                else break;
                            }
                            int ttl = Integer.parseInt(num.toString());
                            if (ttl <= 64) return "Linux/Unix";
                            if (ttl <= 128) return "Windows";
                            return "网络设备";
                        }
                    }
                }
            }
            p.waitFor();
        } catch (Exception ignored) {}
        return "";
    }

    private boolean testPort(String ip, int port, int timeout) {
        try (Socket s = new Socket()) {
            s.connect(new InetSocketAddress(ip, port), timeout);
            return true;
        } catch (Exception e) {
            return false;
        }
    }

    private String resolveHostname(InetAddress addr) {
        try {
            String hostname = addr.getHostName();
            // 如果返回的是 IP 地址说明没解析到
            if (hostname != null && !hostname.equals(addr.getHostAddress())) {
                return hostname;
            }
        } catch (Exception ignored) {}
        return "";
    }

    private static final Map<Integer, String> WELL_KNOWN_PORTS = new HashMap<>();
    static {
        WELL_KNOWN_PORTS.put(21, "FTP");
        WELL_KNOWN_PORTS.put(22, "SSH");
        WELL_KNOWN_PORTS.put(23, "Telnet");
        WELL_KNOWN_PORTS.put(25, "SMTP");
        WELL_KNOWN_PORTS.put(53, "DNS");
        WELL_KNOWN_PORTS.put(80, "HTTP");
        WELL_KNOWN_PORTS.put(110, "POP3");
        WELL_KNOWN_PORTS.put(143, "IMAP");
        WELL_KNOWN_PORTS.put(443, "HTTPS");
        WELL_KNOWN_PORTS.put(445, "SMB");
        WELL_KNOWN_PORTS.put(993, "IMAPS");
        WELL_KNOWN_PORTS.put(995, "POP3S");
        WELL_KNOWN_PORTS.put(1433, "MSSQL");
        WELL_KNOWN_PORTS.put(1521, "Oracle");
        WELL_KNOWN_PORTS.put(3306, "MySQL");
        WELL_KNOWN_PORTS.put(3389, "RDP");
        WELL_KNOWN_PORTS.put(5432, "PostgreSQL");
        WELL_KNOWN_PORTS.put(5672, "RabbitMQ");
        WELL_KNOWN_PORTS.put(6379, "Redis");
        WELL_KNOWN_PORTS.put(8080, "HTTP-Alt");
        WELL_KNOWN_PORTS.put(8443, "HTTPS-Alt");
        WELL_KNOWN_PORTS.put(9092, "Kafka");
        WELL_KNOWN_PORTS.put(9200, "ES");
        WELL_KNOWN_PORTS.put(27017, "MongoDB");
        // 物联网/摄像头/可疑设备常见端口
        WELL_KNOWN_PORTS.put(554, "RTSP摄像头");
        WELL_KNOWN_PORTS.put(8554, "RTSP-Alt摄像头");
        WELL_KNOWN_PORTS.put(8000, "摄像头/流媒体");
        WELL_KNOWN_PORTS.put(8888, "摄像头/代理");
        WELL_KNOWN_PORTS.put(8899, "摄像头/ONVIF");
        WELL_KNOWN_PORTS.put(34567, "摄像头(P2P)");
        WELL_KNOWN_PORTS.put(37777, "大华摄像头");
        WELL_KNOWN_PORTS.put(9527, "摄像头(无线)");
        WELL_KNOWN_PORTS.put(7070, "摄像头/RTSP");
        WELL_KNOWN_PORTS.put(9000, "摄像头/流媒体");
        WELL_KNOWN_PORTS.put(8091, "摄像头/CGI");
        WELL_KNOWN_PORTS.put(10080, "摄像头/海康");
        WELL_KNOWN_PORTS.put(65001, "海康SDK");
        WELL_KNOWN_PORTS.put(6666, "恶意软件");
        WELL_KNOWN_PORTS.put(4444, "Metasploit");
        WELL_KNOWN_PORTS.put(31337, "BackOrifice");
        WELL_KNOWN_PORTS.put(12345, "NetBus木马");
        WELL_KNOWN_PORTS.put(6667, "IRC僵尸网络");
        WELL_KNOWN_PORTS.put(1883, "MQTT(IoT)");
        WELL_KNOWN_PORTS.put(8883, "MQTT-SSL(IoT)");
        WELL_KNOWN_PORTS.put(5683, "CoAP(IoT)");
        WELL_KNOWN_PORTS.put(502, "Modbus(工控)");
        WELL_KNOWN_PORTS.put(47808, "BACnet(楼控)");
    }

    private String getPortService(int port) {
        return WELL_KNOWN_PORTS.getOrDefault(port, "");
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(subnetField, config, "scan.subnet");
        loadField(subnetPortsField, config, "scan.subnet.ports");
        loadField(subnetLoopBox, config, "scan.subnet.loop");
        loadField(subnetIntervalField, config, "scan.subnet.interval");
        loadField(ipField, config, "scan.ip");
        loadField(portStartField, config, "scan.port.start");
        loadField(portEndField, config, "scan.port.end");
        loadField(portLoopBox, config, "scan.port.loop");
        loadField(portIntervalField, config, "scan.port.interval");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(subnetField, config, "scan.subnet");
        saveField(subnetPortsField, config, "scan.subnet.ports");
        saveField(subnetLoopBox, config, "scan.subnet.loop");
        saveField(subnetIntervalField, config, "scan.subnet.interval");
        saveField(ipField, config, "scan.ip");
        saveField(portStartField, config, "scan.port.start");
        saveField(portEndField, config, "scan.port.end");
        saveField(portLoopBox, config, "scan.port.loop");
        saveField(portIntervalField, config, "scan.port.interval");
    }
}
