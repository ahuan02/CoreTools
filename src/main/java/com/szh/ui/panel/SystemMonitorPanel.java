package com.szh.ui.panel;

import com.szh.utils.NetUtil;
import com.szh.utils.ThreadPoolUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.knowm.xchart.*;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;
import oshi.SystemInfo;
import oshi.hardware.*;
import oshi.software.os.OSFileStore;
import oshi.software.os.OSProcess;
import oshi.software.os.OperatingSystem;
import oshi.util.FormatUtil;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.table.TableCellRenderer;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.lang.management.*;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 系统监控面板：CPU、内存、磁盘、网卡、JVM 实时图表 + OSHI 系统详情
 */
public class SystemMonitorPanel extends AbstractCommandPanel {

    private static final Logger logger = LogManager.getLogger(SystemMonitorPanel.class);

    private static final int HISTORY_SIZE = 60;
    private static final int UPDATE_INTERVAL_MS = 1000;

    private static final Color BG_DARK = new Color(0x2B2B2B);
    private static final Color WHITE = new Color(0xCCCCCC);
    private static final Color GREEN = new Color(0x4EC9B0);
    private static final Color YELLOW = new Color(0xDCDCAA);
    private static final Color BLUE = new Color(0x569CD6);
    private static final Color ORANGE = new Color(0xCE9178);
    private static final Color PURPLE = new Color(0xC586C0);
    private static final Color GRID_COLOR = new Color(0x444444);
    private static final Color TICK_COLOR = new Color(0xAAAAAA);

    // 使用 NetUtil 全局字体
    private static final Font FONT = NetUtil.FONT_TEXT;
    private static final Font FONT_SMALL = new Font(FONT.getFamily(), Font.PLAIN, 10);
    private static final Font FONT_TITLE = new Font(FONT.getFamily(), Font.BOLD, 13);

    // 数据队列
    private final LinkedList<Date> timeAxis = new LinkedList<>();
    private final LinkedList<Double> cpuData = new LinkedList<>();
    private final LinkedList<Double> memUsedData = new LinkedList<>();
    private final LinkedList<Double> memMaxData = new LinkedList<>();
    private final LinkedList<Double> jvmHeapData = new LinkedList<>();
    private final LinkedList<Double> jvmNonHeapData = new LinkedList<>();

    // 图表
    private XYChart cpuChart;
    private XYChart memChart;
    private XYChart jvmChart;
    private PieChart diskChart;
    private XYChart netChart;

    // 图表面板
    private XChartPanel<XYChart> cpuChartPanel;
    private XChartPanel<XYChart> memChartPanel;
    private XChartPanel<XYChart> jvmChartPanel;
    private XChartPanel<PieChart> diskChartPanel;
    private XChartPanel<XYChart> netChartPanel;

    // 网速追踪
    private long prevNetBytesSent = -1;
    private long prevNetBytesRecv = -1;
    private long prevNetTime = -1;
    private final LinkedList<Double> netSentHistory = new LinkedList<>();
    private final LinkedList<Double> netRecvHistory = new LinkedList<>();

    // 顶部信息标签
    private JLabel netLabel;
    private JLabel cpuLabel;
    private JLabel memLabel;
    private JLabel threadLabel;
    private JLabel uptimeLabel;
    private JLabel osLabel;
    private JLabel tempLabel;

    // 控制标志
    private volatile boolean running = false;
    private java.util.concurrent.Future<?> monitorFuture;

    // JMX Beans（JVM 内部信息）
    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    // OSHI 跨平台硬件抽象层
    private SystemInfo systemInfo;
    private CentralProcessor processor;
    private HardwareAbstractionLayer hal;
    private OperatingSystem oshiOs;
    private long[] prevCpuTicks;

    // 系统详情标签页
    private JTabbedPane tabbedPane;
    private JScrollPane sysInfoScrollPane;
    private JTextArea sysSensorsLabel;
    private JTextArea sysLoadLabel;
    private JTextArea sysFsLabel;
    private JTextArea sysTcpUdpLabel;

    // 动态信息缓存（在工作线程预采集，EDT 只负责设文本）
    private String cachedSensorsText = "传感器: --";
    private String cachedLoadText = "负载: --";
    private String cachedFsText = "磁盘: --";
    private String cachedTcpUdpText = "TCP/UDP: --";

    // 慢操作节流：温度采集（wmic/PowerShell 很慢，降低频率）
    private double cachedCpuTemp = -1;
    private long lastTempRefreshTime;
    private long lastDynamicInfoRefreshTime;

    private JTable processTable;
    private DefaultTableModel processTableModel;
    private long lastProcessRefreshTime;
    private volatile java.util.List<OSProcess> cachedRawProcesses; // 缓存的原始进程列表，展开/折叠时直接复用
    private final Map<String, Icon> iconCache = new ConcurrentHashMap<>();
    private int selectedPid = -1; // 用户选中的进程 PID（刷新时按 PID 保持选中状态）
    private String selectedGroupName = null; // 选中的组头行纯名称（PID=-1 时用于区分各组）
    private final Set<String> expandedGroups = new HashSet<>(); // 展开的进程组名（纯名称）
    private volatile boolean suppressSelectionEvents = false; // 程序化操作表时抑制 ListSelectionListener
    private final Vector<String> processColumnNames = new Vector<>(
            Arrays.asList("", "PID", "名称", "CPU%", "内存MB", "用户", "线程", "命令行"));


    /** OSHI 是否已初始化 */
    private boolean oshiInitialized;

    public SystemMonitorPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(6, 6));
        setBackground(BG_DARK);

        // OSHI 初始化推迟到 startMonitoring() 后台线程，避免 new SystemInfo() 在构造期阻塞启动

        add(createOverviewPanel(), BorderLayout.NORTH);

        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setBackground(BG_DARK);
        tabbedPane.setForeground(WHITE);
        tabbedPane.setFont(FONT_TITLE);

        // ---- Tab 1: 监控图表 ----
        JPanel chartsPanel = new JPanel(new GridLayout(2, 3, 6, 6));
        chartsPanel.setOpaque(false);

        cpuChart = createCpuChart();
        cpuChartPanel = new XChartPanel<>(cpuChart);
        chartsPanel.add(wrapChartPanel(cpuChartPanel, "CPU"));

        memChart = createMemChart();
        memChartPanel = new XChartPanel<>(memChart);
        chartsPanel.add(wrapChartPanel(memChartPanel, "内存"));

        jvmChart = createJvmChart();
        jvmChartPanel = new XChartPanel<>(jvmChart);
        chartsPanel.add(wrapChartPanel(jvmChartPanel, "JVM 内存"));

        diskChart = createDiskPieChart();
        diskChartPanel = new XChartPanel<>(diskChart);
        chartsPanel.add(wrapChartPanel(diskChartPanel, "磁盘"));

        netChart = createNetXYChart();
        netChartPanel = new XChartPanel<>(netChart);
        chartsPanel.add(wrapChartPanel(netChartPanel, "网卡速率"));

        JPanel placeholder = new JPanel();
        placeholder.setOpaque(false);
        chartsPanel.add(placeholder);

        tabbedPane.addTab("监控图表", chartsPanel);

        // ---- Tab 2: 系统信息（紧凑平面面板，无滚动条）----
        tabbedPane.addTab("系统信息", createSysInfoPanel());

        // ---- Tab 3: 进程 ----
        processTableModel = new DefaultTableModel(
                new String[]{"", "PID", "名称", "CPU%", "内存MB", "用户", "线程", "命令行"}, 0) {
            public boolean isCellEditable(int row, int col) { return false; }
            public Class<?> getColumnClass(int col) { return col == 0 ? Icon.class : Object.class; }
        };
        processTable = new JTable(processTableModel);
        processTable.setBackground(BG_DARK);
        processTable.setForeground(WHITE);
        processTable.setGridColor(GRID_COLOR);
        processTable.setFont(FONT_SMALL);
        processTable.setRowHeight(22);
        processTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
        processTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        processTable.getTableHeader().setBackground(new Color(0x333333));
        processTable.getTableHeader().setForeground(WHITE);
        processTable.getTableHeader().setFont(FONT_SMALL);

        // 图标列
        processTable.getColumnModel().getColumn(0).setPreferredWidth(26);
        processTable.getColumnModel().getColumn(0).setMaxWidth(26);
        processTable.getColumnModel().getColumn(0).setResizable(false);
        processTable.getColumnModel().getColumn(0).setCellRenderer(new IconRenderer());

        processTable.getColumnModel().getColumn(1).setPreferredWidth(55);
        processTable.getColumnModel().getColumn(2).setPreferredWidth(130);
        processTable.getColumnModel().getColumn(3).setPreferredWidth(55);
        processTable.getColumnModel().getColumn(4).setPreferredWidth(70);
        processTable.getColumnModel().getColumn(5).setPreferredWidth(80);
        processTable.getColumnModel().getColumn(6).setPreferredWidth(50);
        processTable.getColumnModel().getColumn(7).setPreferredWidth(300);

        // 点击选中行时记录 PID + 组名（组头行 PID=-1，用组名区分）
        processTable.getSelectionModel().addListSelectionListener(e -> {
            if (e.getValueIsAdjusting() || suppressSelectionEvents) return;
            int row = processTable.getSelectedRow();
            if (row >= 0 && row < processTableModel.getRowCount()) {
                selectedPid = (int) processTableModel.getValueAt(row, 1);
                if (selectedPid < 0) {
                    String name = (String) processTableModel.getValueAt(row, 2);
                    selectedGroupName = name.replaceAll("^[▸▾]\\s*", "").replaceAll("\\s*\\(\\d+个进程\\)", "");
                } else {
                    selectedGroupName = null;
                }
            }
        });

        // 点击组头行立即切换展开/折叠（局部插入/删除，零重建）
        processTable.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                int row = processTable.rowAtPoint(e.getPoint());
                if (row < 0 || row >= processTableModel.getRowCount()) return;
                int pid = (int) processTableModel.getValueAt(row, 1);
                if (pid >= 0) return;
                String name = (String) processTableModel.getValueAt(row, 2);
                String pureName = name.replaceAll("^[▸▾]\\s*", "").replaceAll("\\s*\\(\\d+个进程\\)", "");
                toggleProcessGroup(row, pureName);
            }
        });

        // 自定义行渲染：组头行（PID=-1）用粗体灰色背景
        processTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
            @Override
            public Component getTableCellRendererComponent(JTable table, Object value,
                    boolean isSelected, boolean hasFocus, int row, int column) {
                Component c = super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                if (row < processTableModel.getRowCount()) {
                    int pid = (int) processTableModel.getValueAt(row, 1);
                    if (pid < 0) { // 组头行
                        c.setFont(FONT_SMALL.deriveFont(Font.BOLD));
                        if (!isSelected) c.setBackground(new Color(0x383838));
                    } else {
                        c.setFont(FONT_SMALL);
                        if (!isSelected) c.setBackground(BG_DARK);
                    }
                }
                if (isSelected) c.setBackground(new Color(0x444444));
                return c;
            }
        });

        JScrollPane procScroll = new JScrollPane(processTable);
        procScroll.getViewport().setBackground(BG_DARK);

        // 顶部栏：提示文字 + 结束进程按钮（右上角）
        JPanel procTopBar = new JPanel(new BorderLayout());
        procTopBar.setBackground(BG_DARK);
        procTopBar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));
        JLabel procHint = new JLabel("选中进程后点击右侧按钮结束");
        procHint.setFont(FONT_SMALL);
        procHint.setForeground(new Color(0x888888));
        procTopBar.add(procHint, BorderLayout.WEST);

        JButton killBtn = new JButton("结束进程");
        killBtn.setFont(FONT_SMALL);
        killBtn.setFocusPainted(false);
        killBtn.setBackground(new Color(0xD32F2F));
        killBtn.setForeground(Color.WHITE);
        killBtn.setBorder(BorderFactory.createEmptyBorder(2, 10, 2, 10));
        killBtn.addActionListener(e -> {
            // 优先用 selectedPid / selectedGroupName（用户最后一次手动选择），getSelectedRow 做兜底
            int pid = selectedPid;
            String name = null;
            if (pid > 0) {
                // 从模型反查进程名（避免从可能错位的选中行读取）
                for (int i = 0; i < processTableModel.getRowCount(); i++) {
                    if ((int) processTableModel.getValueAt(i, 1) == pid) {
                        name = (String) processTableModel.getValueAt(i, 2);
                        break;
                    }
                }
            }
            if (pid <= 0 && selectedGroupName != null) {
                // 选中的是组头
                int groupSize = getGroupPids(selectedGroupName).size();
                int ret = JOptionPane.showConfirmDialog(this,
                        "确定要结束 \"" + selectedGroupName + "\" 的全部 " + groupSize + " 个进程吗？\n（使用 /T 结束每个进程树）",
                        "确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ret == JOptionPane.YES_OPTION) {
                    killProcessGroup(selectedGroupName);
                }
                return;
            }
            if (pid > 0 && name != null) {
                int ret = JOptionPane.showConfirmDialog(this,
                        "确定要结束进程 " + name + " (PID=" + pid + ") 吗？\n（使用 /T 结束进程树，含所有子进程）",
                        "确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ret == JOptionPane.YES_OPTION) {
                    killProcess(pid);
                }
                return;
            }
            // 兜底：从当前选中行读取
            int row = processTable.getSelectedRow();
            if (row < 0 || row >= processTableModel.getRowCount()) {
                JOptionPane.showMessageDialog(this, "请先在列表中选中一个进程", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
            int fallbackPid = (int) processTableModel.getValueAt(row, 1);
            String fallbackName = (String) processTableModel.getValueAt(row, 2);
            if (fallbackPid < 0) {
                String pureName = fallbackName.replaceAll("^[▸▾]\\s*", "").replaceAll("\\s*\\(\\d+个进程\\)", "");
                int groupSize = getGroupPids(pureName).size();
                int ret = JOptionPane.showConfirmDialog(this,
                        "确定要结束 \"" + pureName + "\" 的全部 " + groupSize + " 个进程吗？\n（使用 /T 结束每个进程树）",
                        "确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ret == JOptionPane.YES_OPTION) {
                    killProcessGroup(pureName);
                }
            } else {
                int ret = JOptionPane.showConfirmDialog(this,
                        "确定要结束进程 " + fallbackName + " (PID=" + fallbackPid + ") 吗？\n（使用 /T 结束进程树，含所有子进程）",
                        "确认", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (ret == JOptionPane.YES_OPTION) {
                    killProcess(fallbackPid);
                }
            }
        });
        procTopBar.add(killBtn, BorderLayout.EAST);

        JPanel procPanel = new JPanel(new BorderLayout());
        procPanel.setBackground(BG_DARK);
        procPanel.add(procTopBar, BorderLayout.NORTH);
        procPanel.add(procScroll, BorderLayout.CENTER);
        tabbedPane.addTab("进程", procPanel);

        add(tabbedPane, BorderLayout.CENTER);
    }

    // ==================== 顶部概览面板 ====================

    private JPanel createOverviewPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 8));
        panel.setOpaque(false);

        netLabel = createInfoLabel("网络: --");
        cpuLabel = createInfoLabel("CPU: --%");
        memLabel = createInfoLabel("内存: --");
        tempLabel = createInfoLabel("温度: --");
        threadLabel = createInfoLabel("线程: --");
        uptimeLabel = createInfoLabel("运行: --");
        osLabel = createInfoLabel("OS: --");

        panel.add(netLabel);
        panel.add(cpuLabel);
        panel.add(memLabel);
        panel.add(tempLabel);
        panel.add(threadLabel);
        panel.add(uptimeLabel);
        panel.add(osLabel);

        return panel;
    }

    private JLabel createInfoLabel(String text) {
        JLabel label = new JLabel(text);
        label.setFont(FONT);
        label.setForeground(WHITE);
        return label;
    }

    // ==================== 图表创建 ====================

    private XYChart createCpuChart() {
        XYChart chart = new XYChartBuilder()
                .width(400).height(220)
                .title("CPU 使用率")
                .xAxisTitle(" ")
                .yAxisTitle("%")
                .theme(Styler.ChartTheme.GGPlot2)
                .build();
        applyXYStyle(chart);
        chart.getStyler().setYAxisDecimalPattern("#0.##'%'");
        chart.getStyler().setLegendVisible(false);
        addXYSeriesDate(chart, "CPU 使用率", GREEN);
        return chart;
    }

    private XYChart createMemChart() {
        XYChart chart = new XYChartBuilder()
                .width(400).height(220)
                .title("内存使用")
                .xAxisTitle(" ")
                .yAxisTitle("GB")
                .theme(Styler.ChartTheme.GGPlot2)
                .build();
        applyXYStyle(chart);
        chart.getStyler().setYAxisDecimalPattern("#0.## GB");
        chart.getStyler().setLegendVisible(true);
        chart.getStyler().setLegendFont(FONT_SMALL);
        chart.getStyler().setLegendBackgroundColor(BG_DARK);
        chart.getStyler().setLegendBorderColor(BG_DARK);
        addXYSeriesDate(chart, "已用", YELLOW);
        addXYSeriesDate(chart, "总量", BLUE);
        return chart;
    }

    private XYChart createJvmChart() {
        XYChart chart = new XYChartBuilder()
                .width(400).height(220)
                .title("JVM 堆内存")
                .xAxisTitle(" ")
                .yAxisTitle("MB")
                .theme(Styler.ChartTheme.GGPlot2)
                .build();
        applyXYStyle(chart);
        chart.getStyler().setYAxisDecimalPattern("#0.## MB");
        chart.getStyler().setLegendVisible(true);
        chart.getStyler().setLegendFont(FONT_SMALL);
        chart.getStyler().setLegendBackgroundColor(BG_DARK);
        chart.getStyler().setLegendBorderColor(BG_DARK);
        addXYSeriesDate(chart, "堆", ORANGE);
        addXYSeriesDate(chart, "非堆", PURPLE);
        return chart;
    }

    private XYChart createNetXYChart() {
        XYChart chart = new XYChartBuilder()
                .width(400).height(220)
                .title("网卡速率")
                .xAxisTitle(" ")
                .yAxisTitle("KB/s")
                .theme(Styler.ChartTheme.GGPlot2)
                .build();
        applyXYStyle(chart);
        chart.getStyler().setYAxisDecimalPattern("#0.# KB/s");
        chart.getStyler().setLegendVisible(true);
        chart.getStyler().setLegendFont(FONT_SMALL);
        chart.getStyler().setLegendBackgroundColor(BG_DARK);
        chart.getStyler().setLegendBorderColor(BG_DARK);
        addXYSeriesDate(chart, "发送", YELLOW);
        addXYSeriesDate(chart, "接收", BLUE);
        return chart;
    }

    private void applyXYStyle(XYChart chart) {
        chart.getStyler().setDefaultSeriesRenderStyle(XYSeries.XYSeriesRenderStyle.Line);
        chart.getStyler().setPlotBackgroundColor(BG_DARK);
        chart.getStyler().setChartBackgroundColor(BG_DARK);
        chart.getStyler().setChartTitleFont(FONT_TITLE);
        chart.getStyler().setChartTitleBoxBackgroundColor(new Color(0x333333));
        chart.getStyler().setChartTitleBoxBorderColor(BG_DARK);
        chart.getStyler().setChartTitleBoxVisible(true);
        chart.getStyler().setChartFontColor(WHITE);
        chart.getStyler().setAxisTickLabelsColor(TICK_COLOR);
        chart.getStyler().setAxisTickLabelsFont(FONT_SMALL);
        chart.getStyler().setAxisTitleFont(FONT_SMALL);
        chart.getStyler().setPlotGridLinesColor(GRID_COLOR);
        chart.getStyler().setPlotGridVerticalLinesVisible(false);
        chart.getStyler().setPlotBorderVisible(false);
        chart.getStyler().setAntiAlias(true);
        chart.getStyler().setDatePattern("HH:mm:ss");
        chart.getStyler().setLegendBorderColor(BG_DARK);
    }

    private void addXYSeriesDate(XYChart chart, String name, Color color) {
        XYSeries series = chart.addSeries(name, new ArrayList<>(List.of(new Date())), new ArrayList<>(List.of(0.0)));
        series.setMarker(SeriesMarkers.NONE);
        series.setLineColor(color);
        series.setSmooth(true);
    }

    private PieChart createDiskPieChart() {
        PieChart chart = new PieChartBuilder()
                .width(400).height(220)
                .title("磁盘使用")
                .theme(Styler.ChartTheme.GGPlot2)
                .build();

        chart.getStyler().setPlotBackgroundColor(BG_DARK);
        chart.getStyler().setChartBackgroundColor(BG_DARK);
        chart.getStyler().setChartTitleFont(FONT_TITLE);
        chart.getStyler().setChartTitleBoxBackgroundColor(new Color(0x333333));
        chart.getStyler().setChartTitleBoxBorderColor(BG_DARK);
        chart.getStyler().setChartTitleBoxVisible(true);
        chart.getStyler().setChartFontColor(WHITE);
        chart.getStyler().setLegendVisible(true);
        chart.getStyler().setLegendFont(FONT_SMALL);
        chart.getStyler().setLegendBackgroundColor(BG_DARK);
        chart.getStyler().setLegendBorderColor(BG_DARK);
        chart.getStyler().setLabelsFont(FONT_SMALL);
        chart.getStyler().setLabelsFontColor(WHITE);
        chart.getStyler().setSeriesColors(new Color[]{GREEN, BLUE});

        chart.addSeries("已用", 1);
        chart.addSeries("可用", 1);

        return chart;
    }

    private JPanel wrapChartPanel(JPanel chartPanel, String title) {
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setBackground(BG_DARK);
        wrapper.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(GRID_COLOR, 1),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                FONT_TITLE,
                WHITE));
        wrapper.add(chartPanel, BorderLayout.CENTER);
        return wrapper;
    }

    // ==================== 数据采集 ====================

    public void startMonitoring() {
        if (running) return;
        running = true;

        // 初始化历史数据（仅在首次或清空后）
        synchronized (timeAxis) {
            if (timeAxis.isEmpty()) {
                long now = System.currentTimeMillis();
                for (int i = HISTORY_SIZE - 1; i >= 0; i--) {
                    timeAxis.add(new Date(now - i * 1000L));
                    cpuData.add(0.0);
                    memUsedData.add(0.0);
                    memMaxData.add(0.0);
                    jvmHeapData.add(0.0);
                    jvmNonHeapData.add(0.0);
                    netSentHistory.add(0.0);
                    netRecvHistory.add(0.0);
                }
            }
        }
        // 重置网速追踪
        prevNetBytesSent = -1;
        prevNetBytesRecv = -1;
        prevNetTime = -1;

        // 立即渲染空图表（零数据），不等 OSHI 初始化，让用户马上看到图表框架
        renderChartsInitial();

        // 平台线程池轮询：OSHI 是 JNI/WMI 原生调用，虚拟线程会 pin 住 carrier
        monitorFuture = ThreadPoolUtil.submitPlatform(() -> {
            // 首次在后台初始化 OSHI
            if (!oshiInitialized) {
                oshiInitialized = true;
                initOshi();
                SwingUtilities.invokeLater(() -> {
                    if (tabbedPane != null && tabbedPane.getTabCount() > 1) {
                        tabbedPane.setComponentAt(1, createSysInfoPanel());
                    }
                });
            }
            // 初始化完成后立即采集一次，不等待
            if (running) collectAndUpdate();

            while (running) {
                try {
                    Thread.sleep(UPDATE_INTERVAL_MS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
                if (!running) break;
                collectAndUpdate();
            }
        });
    }

    private void collectAndUpdate() {
        try {
            // ---- 数据采集（非 EDT 线程） ----
            // CPU：基于两次采样的 tick 差值计算，比 JMX 的 getCpuLoad() 更可靠
            double cpuLoad = getCpuLoad();

            // 内存：OSHI GlobalMemory，不依赖 HotSpot 扩展 Bean
            GlobalMemory memory = hal.getMemory();
            long totalMem = memory.getTotal();
            long availMem = memory.getAvailable();
            long usedMem = totalMem - availMem;
            double usedMB = usedMem / (1024.0 * 1024.0);
            double totalMB = totalMem / (1024.0 * 1024.0);

            MemoryUsage heapUsage = memBean.getHeapMemoryUsage();
            MemoryUsage nonHeapUsage = memBean.getNonHeapMemoryUsage();
            double heapMB = heapUsage.getUsed() / (1024.0 * 1024.0);
            double nonHeapMB = nonHeapUsage.getUsed() / (1024.0 * 1024.0);

            int threadCount = threadBean.getThreadCount();
            long uptime = runtimeBean.getUptime();

            // 磁盘数据
            long totalDisk = 0, freeDisk = 0;
            for (java.io.File root : java.io.File.listRoots()) {
                totalDisk += root.getTotalSpace();
                freeDisk += root.getFreeSpace();
            }
            final double usedDiskGB = (totalDisk - freeDisk) / (1024.0 * 1024.0 * 1024.0);
            final double freeDiskGB = freeDisk / (1024.0 * 1024.0 * 1024.0);

            // 网卡数据：OSHI NetworkIF 统一跨平台，无需解析 netstat/proc
            long[] netBytes = getNetworkBytes();
            long nowNetBytesSent = netBytes[0];
            long nowNetBytesRecv = netBytes[1];
            long nowNetTime = System.currentTimeMillis();

            double netSentRate = 0;
            double netRecvRate = 0;
            if (prevNetTime > 0 && nowNetBytesSent >= prevNetBytesSent && nowNetBytesRecv >= prevNetBytesRecv) {
                double elapsedSec = (nowNetTime - prevNetTime) / 1000.0;
                if (elapsedSec > 0) {
                    netSentRate = (nowNetBytesSent - prevNetBytesSent) / elapsedSec / 1024.0;
                    netRecvRate = (nowNetBytesRecv - prevNetBytesRecv) / elapsedSec / 1024.0;
                }
            }
            prevNetBytesSent = nowNetBytesSent;
            prevNetBytesRecv = nowNetBytesRecv;
            prevNetTime = nowNetTime;

            // 锁内完成数据写入 + 快照
            final List<Date> xData;
            final List<Double> cpuSnap, memUsedSnap, memMaxSnap, jvmHeapSnap, jvmNonHeapSnap, netSentSnap, netRecvSnap;
            final double finalCpuLoad = cpuLoad;
            final double finalUsedMB = usedMB;
            final double finalTotalMB = totalMB;

            synchronized (timeAxis) {
                timeAxis.add(new Date());
                cpuData.add(cpuLoad * 100);
                memUsedData.add(usedMB);
                memMaxData.add(totalMB);
                jvmHeapData.add(heapMB);
                jvmNonHeapData.add(nonHeapMB);
                netSentHistory.add(netSentRate);
                netRecvHistory.add(netRecvRate);

                while (timeAxis.size() > HISTORY_SIZE) {
                    timeAxis.removeFirst();
                    cpuData.removeFirst();
                    memUsedData.removeFirst();
                    memMaxData.removeFirst();
                    jvmHeapData.removeFirst();
                    jvmNonHeapData.removeFirst();
                    netSentHistory.removeFirst();
                    netRecvHistory.removeFirst();
                }

                xData = new ArrayList<>(timeAxis);
                cpuSnap = new ArrayList<>(cpuData);
                memUsedSnap = new ArrayList<>(memUsedData);
                memMaxSnap = new ArrayList<>(memMaxData);
                jvmHeapSnap = new ArrayList<>(jvmHeapData);
                jvmNonHeapSnap = new ArrayList<>(jvmNonHeapData);
                netSentSnap = new ArrayList<>(netSentHistory);
                netRecvSnap = new ArrayList<>(netRecvHistory);
            }

            // 内存数据预处理（非 EDT）
            final boolean useGB = finalTotalMB > 1024;
            final List<Double> memUsedFinal;
            final List<Double> memMaxFinal;
            if (useGB) {
                memUsedFinal = new ArrayList<>(memUsedSnap.size());
                memMaxFinal = new ArrayList<>(memMaxSnap.size());
                for (int i = 0; i < memUsedSnap.size(); i++) {
                    memUsedFinal.add(memUsedSnap.get(i) / 1024.0);
                    memMaxFinal.add(memMaxSnap.get(i) / 1024.0);
                }
            } else {
                memUsedFinal = memUsedSnap;
                memMaxFinal = memMaxSnap;
            }

            final String diskTitle = String.format("磁盘使用 (已用 %.1f GB / 共 %.1f GB)",
                    usedDiskGB, usedDiskGB + freeDiskGB);
            final String cpuText = String.format("CPU: %.1f%%", finalCpuLoad * 100);
            final String memText = String.format("内存: %s/%s (%.1f%%)",
                    formatMem(finalUsedMB), formatMem(finalTotalMB),
                    (finalUsedMB / finalTotalMB * 100));
            final String threadText = "线程: " + threadCount;
            final String uptimeText = "运行: " + formatUptime(uptime);
            final String osText = String.format("OS: %s %s (%s)",
                    oshiOs != null ? oshiOs.getFamily() : System.getProperty("os.name"),
                    oshiOs != null ? oshiOs.getVersionInfo().getVersion() : System.getProperty("os.version"),
                    System.getProperty("os.arch"));

            // 温度采集（节流：每 5 秒一次，wmic/PowerShell 很慢）
            long now = System.currentTimeMillis();
            if (now - lastTempRefreshTime > 5000) {
                lastTempRefreshTime = now;
                cachedCpuTemp = getCpuTemperature();
            }

            // ---- 工作线程采集动态信息（节流：每 3 秒一次，避免每次 OSHI WMI 查询拖慢图表刷新）----
            if (now - lastDynamicInfoRefreshTime > 3000) {
                lastDynamicInfoRefreshTime = now;
                collectDynamicInfo();
            }

            // 进程表数据采集（防抖：每 3 秒一次，工作线程）
            java.util.List<Object[]> processRows;
            if (now - lastProcessRefreshTime > 3000) {
                lastProcessRefreshTime = now;
                processRows = collectProcessData();
            } else {
                processRows = null;
            }

            final double finalNetSentRate = netSentRate;
            final double finalNetRecvRate = netRecvRate;
            final double finalCpuTemp = cachedCpuTemp;

            // ---- EDT 更新 UI（仅轻量操作：标签文字、图表更新、重绘）----
            SwingUtilities.invokeLater(() -> {
                netLabel.setText(String.format("网络: ↓%s/s ↑%s/s",
                        formatNetRate(finalNetSentRate), formatNetRate(finalNetRecvRate)));
                cpuLabel.setText(cpuText);
                memLabel.setText(memText);
                tempLabel.setText(finalCpuTemp > 0
                        ? String.format("温度: %.0f°C", finalCpuTemp) : "温度: N/A");
                threadLabel.setText(threadText);
                uptimeLabel.setText(uptimeText);
                osLabel.setText(osText);

                updateXY(cpuChart, "CPU 使用率", xData, cpuSnap);
                updateXY(memChart, "已用", xData, memUsedFinal);
                updateXY(memChart, "总量", xData, memMaxFinal);
                updateXY(jvmChart, "堆", xData, jvmHeapSnap);
                updateXY(jvmChart, "非堆", xData, jvmNonHeapSnap);

                diskChart.updatePieSeries("已用", usedDiskGB);
                diskChart.updatePieSeries("可用", freeDiskGB);
                diskChart.setTitle(diskTitle);

                updateXY(netChart, "发送", xData, netSentSnap);
                updateXY(netChart, "接收", xData, netRecvSnap);

                // XChart 数据更新后需手动触发 XChartPanel 重绘
                repaintCharts();

                // 应用动态系统信息标签（数据已在工作线程预采集）
                applyDynamicLabels();

                // 应用进程表数据（数据已在工作线程预采集）
                if (processRows != null) {
                    applyProcessData(processRows);
                }
            });

        } catch (Exception e) {
            logger.warn("系统监视器刷新失败", e);
        }
    }

    private void updateXY(XYChart chart, String name, List<Date> xData, List<Double> yData) {
        chart.updateXYSeries(name, xData, yData, null);
    }

    // ==================== 系统信息获取（OSHI 跨平台统一） ====================

    private void initOshi() {
        try {
            systemInfo = new SystemInfo();
            hal = systemInfo.getHardware();
            processor = hal.getProcessor();
            oshiOs = systemInfo.getOperatingSystem();
            prevCpuTicks = processor.getSystemCpuLoadTicks();
            refreshDynamicInfo();
        } catch (Throwable t) {
            logger.error("OSHI 初始化失败", t);
        }
    }

    /** 获取 CPU 温度：OSHI 传感器 → Windows WMIC / PowerShell 兜底 */
    private double getCpuTemperature() {
        // 1) OSHI 传感器（跨平台首选）
        if (hal != null) {
            try {
                double t = hal.getSensors().getCpuTemperature();
                if (t > 0) return t;
            } catch (Exception ignored) {}
        }

        // 2) Windows 兜底：wmic 查询 ThermalZoneTemperature
        String os = System.getProperty("os.name", "").toLowerCase();
        if (os.contains("win")) {
            try {
                // 方式 A：wmic 查询（需要 Kelvin → °C 转换）
                Process p = new ProcessBuilder(
                        "wmic", "/namespace:\\\\root\\wmi", "PATH", "MSAcpi_ThermalZoneTemperature",
                        "get", "CurrentTemperature", "/format:value")
                        .redirectErrorStream(true).start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line;
                    while ((line = r.readLine()) != null) {
                        line = line.trim();
                        if (line.startsWith("CurrentTemperature=")) {
                            String val = line.substring(line.indexOf('=') + 1).trim();
                            if (!val.isEmpty()) {
                                // 单位是 0.1K，转换为 °C
                                double kelvin = Double.parseDouble(val) / 10.0;
                                double celsius = kelvin - 273.15;
                                if (celsius > 0 && celsius < 120) return celsius;
                            }
                        }
                    }
                }
            } catch (Exception ignored) {}

            try {
                // 方式 B：PowerShell / CIM（Win10+ 更通用）
                Process p = new ProcessBuilder(
                        "powershell", "-NoProfile", "-Command",
                        "(Get-CimInstance -Namespace root/wmi -ClassName MSAcpi_ThermalZoneTemperature -ErrorAction SilentlyContinue | Select-Object -First 1).CurrentTemperature")
                        .redirectErrorStream(true).start();
                try (java.io.BufferedReader r = new java.io.BufferedReader(
                        new java.io.InputStreamReader(p.getInputStream()))) {
                    String line = r.readLine();
                    if (line != null) {
                        line = line.trim();
                        if (!line.isEmpty()) {
                            try {
                                double kelvin = Double.parseDouble(line) / 10.0;
                                double celsius = kelvin - 273.15;
                                if (celsius > 0 && celsius < 120) return celsius;
                            } catch (NumberFormatException ignored2) {}
                        }
                    }
                }
            } catch (Exception ignored) {}
        }

        return 0;
    }

    /** 基于两次采样的 CPU tick 差值计算利用率，全平台一致 */
    private double getCpuLoad() {
        if (processor == null) return 0;
        try {
            double load = processor.getSystemCpuLoadBetweenTicks(prevCpuTicks);
            prevCpuTicks = processor.getSystemCpuLoadTicks();
            return Math.max(0, load);
        } catch (Exception e) {
            return 0;
        }
    }

    /** 汇总所有非回环网卡的收发字节数，返回 [sent, recv] */
    private long[] getNetworkBytes() {
        if (hal == null) return new long[]{0, 0};
        long sent = 0, recv = 0;
        try {
            // 强制重新枚举网卡列表（true 跳过缓存），否则可能拿到空列表
            for (NetworkIF net : hal.getNetworkIFs(true)) {
                if (isLoopback(net)) continue;
                String name = net.getName();
                if (name != null && name.startsWith("lo")) continue;
                net.updateAttributes();
                recv += net.getBytesRecv();
                sent += net.getBytesSent();
            }
        } catch (Exception e) {
            logger.warn("获取网络流量统计失败", e);
        }
        return new long[]{sent, recv};
    }

    /** 通过底层 Java NetworkInterface 判断是否为回环网卡 */
    private boolean isLoopback(NetworkIF net) {
        try {
            java.net.NetworkInterface ni = net.queryNetworkInterface();
            return ni != null && ni.isLoopback();
        } catch (Exception e) {
            return false;
        }
    }

    // ==================== 系统信息紧凑面板 ====================

    /** 构建单列垂直紧凑系统信息面板（带滚动条） */
    private JPanel createSysInfoPanel() {
        // 单列垂直：静态信息 → 网络/外设 → 动态信息
        JPanel col = new JPanel();
        col.setLayout(new BoxLayout(col, BoxLayout.Y_AXIS));
        col.setBackground(BG_DARK);

        JPanel staticBlock = buildStaticBlock();
        staticBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(staticBlock);

        JPanel netBlock = buildNetworkBlock();
        netBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(netBlock);

        JPanel dynBlock = buildDynamicBlock();
        dynBlock.setAlignmentX(Component.LEFT_ALIGNMENT);
        col.add(Box.createVerticalStrut(6));
        col.add(dynBlock);

        JScrollPane scroll = new JScrollPane(col);
        sysInfoScrollPane = scroll;
        scroll.setBorder(null);
        scroll.getViewport().setBackground(BG_DARK);
        scroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        scroll.getVerticalScrollBar().setUnitIncrement(16);

        JPanel outer = new JPanel(new BorderLayout());
        outer.setBackground(BG_DARK);
        outer.setBorder(BorderFactory.createEmptyBorder(6, 10, 6, 10));
        outer.add(scroll, BorderLayout.CENTER);
        return outer;
    }

    private JPanel buildStaticBlock() {
        JPanel p = sectionPanel("");
        if (hal == null) { p.add(label("OSHI 未初始化", WHITE)); return p; }

        try {
            // OS
            if (oshiOs != null) {
                oshi.software.os.OperatingSystem.OSVersionInfo vi = oshiOs.getVersionInfo();
                p.add(label(oshiOs.getFamily() + "  " + vi.getVersion() + "  " + oshiOs.getBitness() + "-bit", WHITE));
            }

            // 计算机 / 主板
            oshi.hardware.ComputerSystem cs = hal.getComputerSystem();
            if (cs != null) {
                String csInfo = nonEmpty(cs.getManufacturer()) + " " + nonEmpty(cs.getModel());
                p.add(label(csInfo.trim(), YELLOW));
                oshi.hardware.Baseboard bb = cs.getBaseboard();
                if (bb != null) {
                    p.add(label("主板: " + nonEmpty(bb.getManufacturer()) + " " + nonEmpty(bb.getModel()) +
                            "  " + nonEmpty(bb.getVersion()), new Color(0x888888)));
                }
                oshi.hardware.Firmware fw = cs.getFirmware();
                if (fw != null) {
                    p.add(label("固件: " + nonEmpty(fw.getName()) + "  " + nonEmpty(fw.getVersion()) +
                            "  " + naStr(fw.getReleaseDate()), new Color(0x888888)));
                }
            }

            // CPU
            if (processor != null) {
                p.add(Box.createVerticalStrut(4));
                p.add(label(processor.getProcessorIdentifier().getName(), GREEN));
                p.add(label("物理核心: " + processor.getPhysicalProcessorCount() +
                        "  |  逻辑核心: " + processor.getLogicalProcessorCount() +
                        "  |  频率: " + FormatUtil.formatHertz(processor.getMaxFreq()), new Color(0x888888)));
            }

            // 内存
            GlobalMemory mem = hal.getMemory();
            if (mem != null) {
                p.add(Box.createVerticalStrut(4));
                p.add(label("内存: " + FormatUtil.formatBytes(mem.getTotal()) +
                        "  |  页文件: " + FormatUtil.formatBytes(mem.getVirtualMemory().getSwapTotal()), BLUE));
            }

            // 显卡
            java.util.List<oshi.hardware.GraphicsCard> gpus = hal.getGraphicsCards();
            if (gpus != null && !gpus.isEmpty()) {
                p.add(Box.createVerticalStrut(4));
                for (oshi.hardware.GraphicsCard gc : gpus) {
                    p.add(label("显卡: " + gc.getName() + "  " + FormatUtil.formatBytes(gc.getVRam()), PURPLE));
                }
            }

            // 显示器
            java.util.List<oshi.hardware.Display> displays = hal.getDisplays();
            if (displays != null && !displays.isEmpty()) {
                for (oshi.hardware.Display d : displays) {
                    String mfr = "";
                    byte[] edid = d.getEdid();
                    if (edid != null && edid.length >= 10) {
                        try {
                            mfr = "" + (char)(((edid[8]>>2)&0x1F)+'A'-1) +
                                    (char)((((edid[8]<<3)&0x1F)|((edid[9]>>5)&0x07))+'A'-1) +
                                    (char)((edid[9]&0x1F)+'A'-1) + " ";
                        } catch (Exception ignored) {}
                    }
                    p.add(label("显示器: " + mfr + d.toString(), new Color(0x888888)));
                }
            }

        } catch (Exception ignored) {}
        return p;
    }

    private JPanel buildNetworkBlock() {
        JPanel p = sectionPanel("");
        if (hal == null) return p;

        try {
            java.util.List<NetworkIF> nets = hal.getNetworkIFs(false);
            if (nets != null && !nets.isEmpty()) {
                p.add(label("网络接口", BLUE));
                for (NetworkIF net : nets) {
                    if (isLoopback(net)) continue;
                    String name = net.getName();
                    String[] ips = net.getIPv4addr();
                    String ip = ips.length > 0 ? String.join(",", ips) : "无IP";
                    p.add(label(name + "  " + ip, WHITE));
                    p.add(label("  MAC: " + naStr(net.getMacaddr()) +
                            "  MTU: " + net.getMTU() +
                            "  速度: " + FormatUtil.formatBytes(net.getSpeed()) + "/s", new Color(0x888888)));
                }
            }

            // 网关/DNS
            if (oshiOs != null) {
                try {
                    oshi.software.os.NetworkParams np = oshiOs.getNetworkParams();
                    if (np != null) {
                        String[] dns = np.getDnsServers();
                        p.add(Box.createVerticalStrut(4));
                        p.add(label("网关: " + naStr(np.getIpv4DefaultGateway()) +
                                "  DNS: " + (dns.length > 0 ? String.join(", ", dns) : "--"),
                                new Color(0x888888)));
                    }
                } catch (Exception ignored) {}
            }

            // 声卡
            java.util.List<oshi.hardware.SoundCard> cards = hal.getSoundCards();
            if (cards != null && !cards.isEmpty()) {
                p.add(Box.createVerticalStrut(4));
                for (oshi.hardware.SoundCard sc : cards) {
                    p.add(label("声卡: " + sc.getName(), new Color(0x888888)));
                }
            }

        } catch (Exception ignored) {}
        return p;
    }

    private JPanel buildDynamicBlock() {
        JPanel p = sectionPanel("实时状态");

        sysSensorsLabel = label("传感器: --", ORANGE);
        sysLoadLabel = label("负载: --", YELLOW);
        sysFsLabel = label("磁盘: --", GREEN);
        sysTcpUdpLabel = label("TCP/UDP: --", WHITE);

        p.add(sysSensorsLabel);
        p.add(sysLoadLabel);
        p.add(sysFsLabel);
        p.add(sysTcpUdpLabel);

        return p;
    }

    /** 在工作线程采集动态信息（含 OSHI 查询，不进 EDT） */
    private void collectDynamicInfo() {
        if (hal == null) return;
        try {
            // --- 传感器 ---
            StringBuilder sb = new StringBuilder("传感器: ");
            // 使用缓存的温度（已在 collectAndUpdate 中节流采集），避免重复调用慢速 wmic/PowerShell
            sb.append(cachedCpuTemp > 0 ? String.format("%.1f°C", cachedCpuTemp) : "N/A");
            Sensors sensors = hal.getSensors();
            if (sensors != null) {
                int[] fans = sensors.getFanSpeeds();
                if (fans.length > 0) {
                    for (int i = 0; i < Math.min(2, fans.length); i++)
                        sb.append("  风扇").append(i + 1).append(": ").append(fans[i]).append("RPM");
                }
            }
            cachedSensorsText = sb.toString();

            // --- 负载 ---
            sb.setLength(0); sb.append("负载: ");
            if (processor != null) {
                double[] la = processor.getSystemLoadAverage(3);
                sb.append(String.format("1m:%.2f  5m:%.2f  15m:%.2f", la[0], la[1], la[2]));
            } else sb.append("--");
            cachedLoadText = sb.toString();

            // --- 文件系统 ---
            sb.setLength(0); sb.append("磁盘: ");
            if (oshiOs != null) {
                java.util.List<OSFileStore> fsList = oshiOs.getFileSystem().getFileStores();
                if (fsList != null && !fsList.isEmpty()) {
                    for (OSFileStore fs : fsList) {
                        sb.append(fs.getMount()).append(" ")
                          .append(FormatUtil.formatBytes(fs.getFreeSpace())).append("  ");
                    }
                } else sb.append("--");
            }
            cachedFsText = sb.toString();

            // --- TCP/UDP ---
            sb.setLength(0); sb.append("TCP/UDP: ");
            if (oshiOs != null) {
                try {
                    oshi.software.os.InternetProtocolStats ips = oshiOs.getInternetProtocolStats();
                    if (ips != null) {
                        oshi.software.os.InternetProtocolStats.TcpStats tcp = ips.getTCPv4Stats();
                        if (tcp != null) {
                            sb.append("Estab:").append(tcp.getConnectionsEstablished())
                              .append(" Fail:").append(tcp.getConnectionFailures());
                        }
                        oshi.software.os.InternetProtocolStats.UdpStats udp = ips.getUDPv4Stats();
                        if (udp != null) {
                            sb.append("  UDP:").append(udp.getDatagramsSent())
                              .append("/").append(udp.getDatagramsReceived());
                        }
                    }
                } catch (Exception e) { sb.append("--"); }
            }
            cachedTcpUdpText = sb.toString();

        } catch (Exception ignored) {}
    }

    /** 在 EDT 将缓存的动态信息设置到标签（轻量，仅 setText），保存并恢复滚动位置 */
    private void applyDynamicLabels() {
        if (sysInfoScrollPane == null) return;
        JScrollBar bar = sysInfoScrollPane.getVerticalScrollBar();
        int oldVal = bar.getValue();
        boolean atBottom = oldVal + bar.getModel().getExtent() >= bar.getMaximum();

        sysSensorsLabel.setText(cachedSensorsText);
        sysLoadLabel.setText(cachedLoadText);
        sysFsLabel.setText(cachedFsText);
        sysTcpUdpLabel.setText(cachedTcpUdpText);

        // setText 触发重布局，Swing 会在布局完成时调整滚动位置。
        // 用 invokeLater 等布局完成后再恢复；用户在底部时保持底部。
        if (atBottom) {
            SwingUtilities.invokeLater(() -> bar.setValue(bar.getMaximum()));
        } else {
            SwingUtilities.invokeLater(() -> bar.setValue(Math.min(oldVal, bar.getMaximum())));
        }
    }

    /** 初始化时一次性调用（EDT OK） */
    private void refreshDynamicInfo() {
        collectDynamicInfo();
        applyDynamicLabels();
    }

    // ===== 紧凑面板工具方法 =====

    /** 垂直堆叠面板，强制子组件横向撑满以实现换行 */
    private JPanel sectionPanel(String title) {
        JPanel p = new JPanel(null) {
            @Override
            public void doLayout() {
                Insets ins = getInsets();
                int w = getWidth() - ins.left - ins.right;
                int y = ins.top;
                for (Component c : getComponents()) {
                    Dimension pref = c.getPreferredSize();
                    int h = lineWrapComp(c, w) ? c.getPreferredSize().height : pref.height;
                    c.setBounds(ins.left, y, w, h);
                    y += h;
                }
            }
            @Override
            public Dimension getPreferredSize() {
                Insets ins = getInsets();
                int h = 0, maxW = 0;
                for (Component c : getComponents()) {
                    Dimension pref = c.getPreferredSize();
                    h += pref.height;
                    if (pref.width > maxW) maxW = pref.width;
                }
                return new Dimension(maxW + ins.left + ins.right + 20, h + ins.top + ins.bottom);
            }
        };
        p.setBackground(BG_DARK);
        if (title != null && !title.isEmpty()) {
            p.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(GRID_COLOR, 1),
                    title, TitledBorder.LEFT, TitledBorder.TOP, FONT_TITLE, BLUE));
        }
        return p;
    }

    /** 触发 JTextArea 按给定宽度换行并返回新高度 */
    private static boolean lineWrapComp(Component c, int width) {
        if (c instanceof JTextArea && width > 0) {
            JTextArea ta = (JTextArea) c;
            ta.setSize(width, Short.MAX_VALUE);
            return true;
        }
        return false;
    }

    /** 返回可自动换行的只读文本区域（仿标签外观） */
    private JTextArea label(String text, Color c) {
        JTextArea ta = new JTextArea(text);
        ta.setFont(FONT);
        ta.setForeground(c);
        ta.setBackground(BG_DARK);
        ta.setEditable(false);
        ta.setLineWrap(true);
        ta.setWrapStyleWord(true);
        ta.setOpaque(true);
        ta.setBorder(null);
        ta.setCursor(null);
        ta.setHighlighter(null);
        return ta;
    }


    private String nonEmpty(String s) {
        return s == null || s.trim().isEmpty() ? "--" : s.trim();
    }

    private String naStr(Object o) {
        if (o == null) return "--";
        String s = o.toString().trim();
        return s.isEmpty() ? "--" : s;
    }

    private String formatNetRate(double kbPerSec) {
        if (kbPerSec < 1.0) return String.format("%.0fB", kbPerSec * 1024);
        if (kbPerSec < 1024) return String.format("%.1fK", kbPerSec);
        return String.format("%.2fM", kbPerSec / 1024);
    }

    // ===== 进程表 =====

    /** 工作线程采集进程数据（不进 EDT），按内存占用降序，同名进程聚合在一起 */
    private java.util.List<Object[]> collectProcessData() {
        java.util.List<Object[]> rows = new ArrayList<>();
        if (processTableModel == null || oshiOs == null) return rows;
        try {
            java.util.List<OSProcess> procs = oshiOs.getProcesses(
                    oshi.software.os.OperatingSystem.ProcessFiltering.ALL_PROCESSES,
                    oshi.software.os.OperatingSystem.ProcessSorting.PID_ASC, 200);

            // 先按名称分组，组内按内存降序，组间按总内存降序
            procs.sort(Comparator.comparing((OSProcess p) -> p.getName().toLowerCase())
                    .thenComparing((OSProcess p) -> p.getResidentMemory(), Comparator.reverseOrder()));
            // 按组总内存重新排序：先收集组信息，再按组内存降序排列
            List<List<OSProcess>> grouped = groupByName(procs);
            grouped.sort(Comparator.<List<OSProcess>>comparingLong(
                    g -> g.stream().mapToLong(OSProcess::getResidentMemory).sum()).reversed());
            List<OSProcess> sorted = new ArrayList<>();
            for (List<OSProcess> g : grouped) {
                sorted.addAll(g);
            }
            cachedRawProcesses = sorted;

            // 后台预加载所有进程图标到缓存，避免 EDT 上首次展开时触发文件 I/O 卡顿
            warmProcessIcons(sorted);

            buildRowsFromCache(rows);

        } catch (Exception e) {
            // 静默降级
        }
        return rows;
    }

    /** 将已按名称排好序的进程列表按名称分组（保持组内顺序） */
    private static List<List<OSProcess>> groupByName(List<OSProcess> procs) {
        List<List<OSProcess>> groups = new ArrayList<>();
        List<OSProcess> current = new ArrayList<>();
        String lastName = "";
        for (OSProcess p : procs) {
            String name = p.getName().toLowerCase();
            if (!name.equals(lastName)) {
                if (!current.isEmpty()) groups.add(current);
                current = new ArrayList<>();
                lastName = name;
            }
            current.add(p);
        }
        if (!current.isEmpty()) groups.add(current);
        return groups;
    }

    /** 从缓存的原始进程列表构建表格行（含展开/折叠状态） */
    private void buildRowsFromCache(java.util.List<Object[]> rows) {
        if (cachedRawProcesses == null) return;
        String lastName = "";
        List<OSProcess> group = new ArrayList<>();
        for (OSProcess p : cachedRawProcesses) {
            String name = p.getName();
            if (!name.equalsIgnoreCase(lastName)) {
                flushGroup(group, rows);
                group.clear();
                lastName = name;
            }
            group.add(p);
        }
        flushGroup(group, rows);
    }

    /** 后台预加载所有进程图标到缓存，避免 EDT 上首次展开触发文件 I/O */
    private void warmProcessIcons(List<OSProcess> procs) {
        for (OSProcess p : procs) {
            getProcessIcon(p);
        }
    }

    /** 局部切换进程组展开/折叠：只插入/移除该组的子行，不重建整个表 */
    private void toggleProcessGroup(int headerRow, String pureName) {
        if (cachedRawProcesses == null) return;
        List<OSProcess> group = getGroupByName(pureName);
        if (group.size() <= 1) return; // 单进程无需展开

        suppressSelectionEvents = true;
        try {
            if (expandedGroups.contains(pureName)) {
                // ---- 折叠：删除 headerRow 下方的子行 ----
                expandedGroups.remove(pureName);
                int childCount = countChildRows(headerRow);
                for (int i = 0; i < childCount; i++) {
                    processTableModel.removeRow(headerRow + 1);
                }
                updateHeaderRow(headerRow, group, false);
            } else {
                // ---- 展开：在 headerRow 下方插入子行 ----
                expandedGroups.add(pureName);
                updateHeaderRow(headerRow, group, true);
                for (int i = 0; i < group.size(); i++) {
                    processTableModel.insertRow(headerRow + 1 + i, createDataRow(group.get(i)));
                }
            }
            // 保持选择状态
            if (selectedPid > 0 || selectedGroupName != null) {
                for (int i = 0; i < processTableModel.getRowCount(); i++) {
                    int pid = (int) processTableModel.getValueAt(i, 1);
                    if (pid > 0 && pid == selectedPid) {
                        processTable.setRowSelectionInterval(i, i);
                        return;
                    }
                    if (pid < 0 && selectedGroupName != null) {
                        String name = (String) processTableModel.getValueAt(i, 2);
                        String pure = name.replaceAll("^[▸▾]\\s*", "").replaceAll("\\s*\\(\\d+个进程\\)", "");
                        if (pure.equals(selectedGroupName)) {
                            processTable.setRowSelectionInterval(i, i);
                            return;
                        }
                    }
                }
            }
        } finally {
            suppressSelectionEvents = false;
        }
    }

    /** 统计 headerRow 下方连续的非组头行数量 */
    private int countChildRows(int headerRow) {
        int count = 0;
        for (int r = headerRow + 1; r < processTableModel.getRowCount(); r++) {
            if ((int) processTableModel.getValueAt(r, 1) < 0) break;
            count++;
        }
        return count;
    }

    /** 更新组头行的显示（箭头和汇总数据） */
    private void updateHeaderRow(int row, List<OSProcess> group, boolean expanded) {
        Object[] header = createGroupHeader(group, expanded);
        for (int col = 0; col < header.length; col++) {
            processTableModel.setValueAt(header[col], row, col);
        }
    }

    /** 从缓存中按名称获取同组进程列表 */
    private List<OSProcess> getGroupByName(String name) {
        List<OSProcess> result = new ArrayList<>();
        if (cachedRawProcesses == null) return result;
        for (OSProcess p : cachedRawProcesses) {
            if (p.getName().equalsIgnoreCase(name)) {
                result.add(p);
            }
        }
        return result;
    }

    /** 输出一组同名进程：默认只显示组头（折叠），已展开的才列出子进程；单个进程直接加入 */
    private void flushGroup(List<OSProcess> group, List<Object[]> rows) {
        if (group.isEmpty()) return;
        if (group.size() > 1) {
            String pureName = group.getFirst().getName();
            boolean expanded = expandedGroups.contains(pureName);
            rows.add(createGroupHeader(group, expanded));
            if (expanded) {
                for (OSProcess p : group) {
                    rows.add(createDataRow(p));
                }
            }
        } else {
            rows.add(createDataRow(group.get(0)));
        }
    }

    /** 创建同名进程组汇总行（粗体灰底，带展开/折叠标记 ▸/▾） */
    private Object[] createGroupHeader(List<OSProcess> group, boolean expanded) {
        OSProcess first = group.getFirst();
        double totalCpu = 0;
        double totalMem = 0;
        for (OSProcess p : group) {
            totalCpu += 100d * p.getProcessCpuLoadCumulative();
            totalMem += p.getResidentMemory() / (1024.0 * 1024.0);
        }
        String arrow = expanded ? "\u25BE " : "\u25B8 "; // ▾ or ▸
        return new Object[]{
                getProcessIcon(first),
                -1, // PID = -1 标记为组头
                arrow + first.getName() + " (" + group.size() + "个进程)",
                String.format("%.1f", totalCpu),
                String.format("%.1f", totalMem),
                first.getUser(),
                "",
                expanded ? "点击收起" : "点击展开",
        };
    }

    /** 创建单个进程数据行 */
    private Object[] createDataRow(OSProcess p) {
        int pid = p.getProcessID();
        return new Object[]{
                getProcessIcon(p),
                pid,
                p.getName(),
                String.format("%.1f", 100d * p.getProcessCpuLoadCumulative()),
                String.format("%.1f", p.getResidentMemory() / (1024.0 * 1024.0)),
                p.getUser(),
                p.getThreadCount(),
                p.getCommandLine(),
        };
    }

    // ===== 进程图标 =====

    /** EDT 将预采集的进程数据批量写入表格，按 PID + 组名保持选中状态 */
    private void applyProcessData(java.util.List<Object[]> rows) {
        if (processTableModel == null) return;
        suppressSelectionEvents = true;
        try {
            // 用 setDataVector 批量替换，只触发一次 tableChanged，避免逐行 addRow 的 N 次重绘
            Vector<Vector<Object>> dataVector = new Vector<>(rows.size());
            int selectRow = -1;
            int idx = 0;
            for (Object[] row : rows) {
                Vector<Object> vRow = new Vector<>(row.length);
                Collections.addAll(vRow, row);
                dataVector.add(vRow);
                int pid = (int) row[1];
                if (pid > 0 && pid == selectedPid) {
                    selectRow = idx;
                } else if (pid < 0 && selectedGroupName != null) {
                    String name = (String) row[2];
                    String pure = name.replaceAll("^[▸▾]\\s*", "").replaceAll("\\s*\\(\\d+个进程\\)", "");
                    if (pure.equals(selectedGroupName)) {
                        selectRow = idx;
                    }
                }
                idx++;
            }
            processTableModel.setDataVector(dataVector, processColumnNames);

            if (selectRow >= 0) {
                processTable.setRowSelectionInterval(selectRow, selectRow);
            } else {
                processTable.clearSelection();
                selectedPid = -1;
                selectedGroupName = null;
            }
        } catch (Exception e) {
            // 静默降级
        } finally {
            suppressSelectionEvents = false;
        }
    }

    // ===== 进程图标 & 操作 =====

    /** 获取进程图标（带缓存，工作线程调用） */
    private Icon getProcessIcon(OSProcess p) {
        try {
            String path = p.getPath();
            if (path != null && !path.isEmpty()) {
                Icon cached = iconCache.get(path);
                if (cached != null) return cached;
                java.io.File f = new java.io.File(path);
                if (f.exists()) {
                    Icon icon = javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon(f);
                    if (icon instanceof javax.swing.ImageIcon) {
                        ImageIcon scaled = new ImageIcon(
                                ((javax.swing.ImageIcon) icon).getImage().getScaledInstance(18, 18, Image.SCALE_SMOOTH));
                        iconCache.put(path, scaled);
                        return scaled;
                    } else if (icon != null) {
                        // 非 ImageIcon 类型（某些平台的 FileSystemView 可能返回其他类型图标）
                        iconCache.put(path, icon);
                        return icon;
                    }
                }
            }
            // 回退到名称缓存
            String name = p.getName();
            Icon cached = iconCache.get(name);
            if (cached != null) return cached;
            // 跨平台兜底图标
            Icon def = getPlatformFallbackIcon();
            if (def instanceof javax.swing.ImageIcon) {
                ImageIcon scaled = new ImageIcon(
                        ((javax.swing.ImageIcon) def).getImage().getScaledInstance(18, 18, Image.SCALE_SMOOTH));
                iconCache.put(name, scaled);
                return scaled;
            } else if (def != null) {
                iconCache.put(name, def);
                return def;
            }
        } catch (Exception e) {
            // ignore
        }
        return null;
    }

    /** 跨平台获取一个系统原生可执行文件图标作为兜底 */
    private Icon getPlatformFallbackIcon() {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            java.io.File fallback;
            if (os.contains("win")) {
                fallback = new java.io.File("C:\\Windows\\explorer.exe");
            } else if (os.contains("mac")) {
                fallback = new java.io.File("/bin/bash");
            } else {
                fallback = new java.io.File("/bin/bash");
            }
            if (fallback.exists()) {
                return javax.swing.filechooser.FileSystemView.getFileSystemView().getSystemIcon(fallback);
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 结束指定 PID 的进程（Windows 用 /T 结束整个进程树，Linux 用 kill -9） */
    private void killProcess(int pid) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (os.contains("win")) {
                // /T = 结束进程树（父进程 + 所有子进程）
                new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                        .redirectErrorStream(true).start();
            } else {
                new ProcessBuilder("kill", "-9", String.valueOf(pid))
                        .redirectErrorStream(true).start();
            }
            selectedPid = -1;
            selectedGroupName = null;
        } catch (Exception e) {
            JOptionPane.showMessageDialog(this, "结束进程失败: " + e.getMessage(),
                    "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    /** 获取同名进程组中所有 PID */
    private List<Integer> getGroupPids(String name) {
        List<Integer> pids = new ArrayList<>();
        for (int i = 0; i < processTableModel.getRowCount(); i++) {
            String rowName = (String) processTableModel.getValueAt(i, 2);
            if (rowName != null && rowName.equals(name)) {
                int pid = (int) processTableModel.getValueAt(i, 1);
                if (pid > 0) pids.add(pid);
            }
        }
        return pids;
    }

    /** 结束同名进程组中的所有进程 */
    private void killProcessGroup(String name) {
        List<Integer> pids = getGroupPids(name);
        if (pids.isEmpty()) return;
        for (int pid : pids) {
            try {
                String os = System.getProperty("os.name").toLowerCase();
                if (os.contains("win")) {
                    new ProcessBuilder("taskkill", "/PID", String.valueOf(pid), "/T", "/F")
                            .redirectErrorStream(true).start();
                } else {
                    new ProcessBuilder("kill", "-9", String.valueOf(pid))
                            .redirectErrorStream(true).start();
                }
            } catch (Exception ignored) {}
        }
        selectedPid = -1;
        selectedGroupName = null;
    }

    /** 图标列渲染器 */
    private static class IconRenderer extends JLabel implements TableCellRenderer {
        public IconRenderer() {
            setOpaque(true);
            setHorizontalAlignment(CENTER);
        }
        @Override
        public Component getTableCellRendererComponent(JTable table, Object value,
                boolean isSelected, boolean hasFocus, int row, int column) {
            if (value instanceof Icon) setIcon((Icon) value);
            else setIcon(null);
            if (isSelected) {
                setBackground(new Color(0x444444));
            } else {
                setBackground(BG_DARK);
            }
            return this;
        }
    }

    // ==================== 工具方法 ====================

    private String formatMem(double mb) {
        if (mb >= 1024) return String.format("%.1f GB", mb / 1024.0);
        return String.format("%.0f MB", mb);
    }

    private String formatUptime(long ms) {
        long seconds = ms / 1000;
        long minutes = seconds / 60;
        long hours = minutes / 60;
        long days = hours / 24;
        if (days > 0) return String.format("%dd %dh %dm", days, hours % 24, minutes % 60);
        if (hours > 0) return String.format("%dh %dm %ds", hours, minutes % 60, seconds % 60);
        if (minutes > 0) return String.format("%dm %ds", minutes, seconds % 60);
        return seconds + "s";
    }

    // ==================== 生命周期 ====================

    /** 立即用预填的零数据渲染图表框架，不等 OSHI 初始化 */
    private void renderChartsInitial() {
        List<Date> xData;
        List<Double> cpuSnap, memSnap, jvmHeapSnap, jvmNonHeapSnap, netSentSnap, netRecvSnap;
        synchronized (timeAxis) {
            xData = new ArrayList<>(timeAxis);
            cpuSnap = new ArrayList<>(cpuData);
            memSnap = new ArrayList<>(memUsedData);
            jvmHeapSnap = new ArrayList<>(jvmHeapData);
            jvmNonHeapSnap = new ArrayList<>(jvmNonHeapData);
            netSentSnap = new ArrayList<>(netSentHistory);
            netRecvSnap = new ArrayList<>(netRecvHistory);
        }
        if (cpuChart != null) updateXY(cpuChart, "CPU 使用率", xData, cpuSnap);
        if (memChart != null) {
            updateXY(memChart, "已用", xData, memSnap);
            updateXY(memChart, "总量", xData, new ArrayList<>(memMaxData));
        }
        if (jvmChart != null) {
            updateXY(jvmChart, "堆", xData, jvmHeapSnap);
            updateXY(jvmChart, "非堆", xData, jvmNonHeapSnap);
        }
        if (netChart != null) {
            updateXY(netChart, "发送", xData, netSentSnap);
            updateXY(netChart, "接收", xData, netRecvSnap);
        }
        repaintCharts();
    }

    private void repaintCharts() {
        if (cpuChartPanel != null) cpuChartPanel.repaint();
        if (memChartPanel != null) memChartPanel.repaint();
        if (jvmChartPanel != null) jvmChartPanel.repaint();
        if (diskChartPanel != null) diskChartPanel.repaint();
        if (netChartPanel != null) netChartPanel.repaint();
    }

    @Override
    public void addNotify() {
        super.addNotify();
        startMonitoring();
    }

    @Override
    public void removeNotify() {
        super.removeNotify();
        stopMonitoring();
    }

    /** 供外部调用：停止轮询 */
    public void stopMonitoring() {
        running = false;
        if (monitorFuture != null) {
            monitorFuture.cancel(true);
            monitorFuture = null;
        }
    }
}
