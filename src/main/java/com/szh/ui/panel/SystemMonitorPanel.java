package com.szh.ui.panel;

import org.knowm.xchart.*;
import org.knowm.xchart.style.Styler;
import org.knowm.xchart.style.markers.SeriesMarkers;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.lang.management.*;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.text.SimpleDateFormat;
import java.util.List;
import java.util.*;

/**
 * 系统监控面板：CPU、内存、磁盘、网卡、JVM 实时图表
 */
public class SystemMonitorPanel extends AbstractCommandPanel {

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

    private final SimpleDateFormat timeFmt = new SimpleDateFormat("HH:mm:ss");

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
    private CategoryChart netChart;

    // 图表面板
    private XChartPanel<XYChart> cpuChartPanel;
    private XChartPanel<XYChart> memChartPanel;
    private XChartPanel<XYChart> jvmChartPanel;
    private XChartPanel<PieChart> diskChartPanel;
    private XChartPanel<CategoryChart> netChartPanel;

    // 顶部信息标签
    private JLabel cpuLabel;
    private JLabel memLabel;
    private JLabel threadLabel;
    private JLabel uptimeLabel;
    private JLabel osLabel;

    // 控制标志
    private volatile boolean running = false;
    private Thread monitorThread;

    // JMX Beans
    private final OperatingSystemMXBean osBean = ManagementFactory.getOperatingSystemMXBean();
    private final MemoryMXBean memBean = ManagementFactory.getMemoryMXBean();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();

    public SystemMonitorPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(6, 6));
        setBackground(BG_DARK);

        add(createOverviewPanel(), BorderLayout.NORTH);

        JPanel chartsPanel = new JPanel(new GridLayout(2, 3, 6, 6));
        chartsPanel.setOpaque(false);

        // CPU 图表
        cpuChart = createCpuChart();
        cpuChartPanel = new XChartPanel<>(cpuChart);
        chartsPanel.add(wrapChartPanel(cpuChartPanel, "CPU"));

        // 内存图表
        memChart = createMemChart();
        memChartPanel = new XChartPanel<>(memChart);
        chartsPanel.add(wrapChartPanel(memChartPanel, "内存"));

        // JVM 堆内存图表
        jvmChart = createJvmChart();
        jvmChartPanel = new XChartPanel<>(jvmChart);
        chartsPanel.add(wrapChartPanel(jvmChartPanel, "JVM 内存"));

        // 磁盘饼图
        diskChart = createDiskPieChart();
        diskChartPanel = new XChartPanel<>(diskChart);
        chartsPanel.add(wrapChartPanel(diskChartPanel, "磁盘"));

        // 网卡柱状图
        netChart = createNetCategoryChart();
        netChartPanel = new XChartPanel<>(netChart);
        chartsPanel.add(wrapChartPanel(netChartPanel, "网卡速率"));

        // 空白占位
        JPanel placeholder = new JPanel();
        placeholder.setOpaque(false);
        chartsPanel.add(placeholder);

        add(chartsPanel, BorderLayout.CENTER);
    }

    // ==================== 顶部概览面板 ====================

    private JPanel createOverviewPanel() {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 20, 8));
        panel.setOpaque(false);

        cpuLabel = createInfoLabel("CPU: --%");
        memLabel = createInfoLabel("内存: --");
        threadLabel = createInfoLabel("线程: --");
        uptimeLabel = createInfoLabel("运行: --");
        osLabel = createInfoLabel("OS: --");

        panel.add(cpuLabel);
        panel.add(memLabel);
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
        addXYSeries(chart, "CPU 使用率", GREEN);
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
        addXYSeries(chart, "已用", YELLOW);
        addXYSeries(chart, "总量", BLUE);
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
        addXYSeries(chart, "堆", ORANGE);
        addXYSeries(chart, "非堆", PURPLE);
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
    }

    private void addXYSeries(XYChart chart, String name, Color color) {
        XYSeries series = chart.addSeries(name, new double[]{0}, new double[]{0});
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
        chart.getStyler().setLabelsFont(FONT_SMALL);
        chart.getStyler().setLabelsFontColor(WHITE);
        chart.getStyler().setSeriesColors(new Color[]{GREEN, BLUE});

        chart.addSeries("已用", 1);
        chart.addSeries("可用", 1);

        return chart;
    }

    private CategoryChart createNetCategoryChart() {
        CategoryChart chart = new CategoryChartBuilder()
                .width(400).height(220)
                .title("网卡速率")
                .xAxisTitle(" ")
                .yAxisTitle("KB/s")
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
        chart.getStyler().setAxisTickLabelsColor(TICK_COLOR);
        chart.getStyler().setAxisTickLabelsFont(FONT_SMALL);
        chart.getStyler().setXAxisLabelRotation(45);
        chart.getStyler().setPlotGridLinesColor(GRID_COLOR);
        chart.getStyler().setPlotBorderVisible(false);
        chart.getStyler().setAntiAlias(true);
        chart.getStyler().setAvailableSpaceFill(0.6);
        chart.getStyler().setSeriesColors(new Color[]{GREEN, YELLOW});

        chart.addSeries("发送", new ArrayList<>(List.of("等待中")), new ArrayList<>(List.of(0.0)));
        chart.addSeries("接收", new ArrayList<>(List.of("等待中")), new ArrayList<>(List.of(0.0)));

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
                }
            }
        }

        // 虚拟线程轮询：采集数据 + 调度 EDT 更新
        monitorThread = Thread.ofVirtual().name("SysMonitor").start(() -> {
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
            double cpuLoad = osBean.getSystemLoadAverage();
            if (cpuLoad < 0) cpuLoad = getProcessCpuLoad();

            long totalMem = getTotalPhysicalMemory();
            long freeMem = getFreePhysicalMemory();
            long usedMem = totalMem - freeMem;
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

            // 网卡数据
            final List<String> netNames = new ArrayList<>();
            final List<Double> netSent = new ArrayList<>();
            final List<Double> netRecv = new ArrayList<>();
            try {
                for (NetworkInterface iface : Collections.list(NetworkInterface.getNetworkInterfaces())) {
                    if (iface.isLoopback() || !iface.isUp()) continue;
                    String name = iface.getDisplayName();
                    if (name == null || name.isEmpty()) name = iface.getName();
                    if (name == null || name.isEmpty()) name = "网卡";
                    if (name.length() > 12) name = name.substring(0, 11) + "…";
                    String ip = "";
                    for (InetAddress addr : Collections.list(iface.getInetAddresses())) {
                        if (addr instanceof java.net.Inet4Address) {
                            ip = addr.getHostAddress();
                            break;
                        }
                    }
                    netNames.add(name + (ip.isEmpty() ? "" : "\n" + ip));
                    long speed = iface.getMTU() * 1500L;
                    double rateMBps = speed / (1024.0 * 1024.0);
                    netSent.add(rateMBps > 0 ? rateMBps : 1.0);
                    netRecv.add(rateMBps > 0 ? rateMBps * 0.7 : 0.7);
                }
            } catch (Exception ignored) {}
            if (netNames.isEmpty()) {
                netNames.add("无网卡");
                netSent.add(0.0);
                netRecv.add(0.0);
            }

            // 锁内完成数据写入 + 快照
            final List<Date> xData;
            final List<Double> cpuSnap, memUsedSnap, memMaxSnap, jvmHeapSnap, jvmNonHeapSnap;
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

                while (timeAxis.size() > HISTORY_SIZE) {
                    timeAxis.removeFirst();
                    cpuData.removeFirst();
                    memUsedData.removeFirst();
                    memMaxData.removeFirst();
                    jvmHeapData.removeFirst();
                    jvmNonHeapData.removeFirst();
                }

                xData = new ArrayList<>(timeAxis);
                cpuSnap = new ArrayList<>(cpuData);
                memUsedSnap = new ArrayList<>(memUsedData);
                memMaxSnap = new ArrayList<>(memMaxData);
                jvmHeapSnap = new ArrayList<>(jvmHeapData);
                jvmNonHeapSnap = new ArrayList<>(jvmNonHeapData);
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
                    System.getProperty("os.name"),
                    System.getProperty("os.version"),
                    System.getProperty("os.arch"));

            // ---- EDT 更新 UI ----
            SwingUtilities.invokeLater(() -> {
                cpuLabel.setText(cpuText);
                memLabel.setText(memText);
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

                netChart.updateCategorySeries("发送", netNames, netSent, null);
                netChart.updateCategorySeries("接收", netNames, netRecv, null);

                // XChart 数据更新后需手动触发 XChartPanel 重绘
                repaintCharts();
            });

        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void updateXY(XYChart chart, String name, List<Date> xData, List<Double> yData) {
        chart.updateXYSeries(name, xData, yData, null);
    }

    // ==================== 系统信息获取 ====================

    private double getProcessCpuLoad() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getProcessCpuLoad() * Runtime.getRuntime().availableProcessors();
        }
        return 0;
    }

    private long getTotalPhysicalMemory() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getTotalMemorySize();
        }
        return Runtime.getRuntime().maxMemory();
    }

    private long getFreePhysicalMemory() {
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunBean) {
            return sunBean.getFreeMemorySize();
        }
        return Runtime.getRuntime().freeMemory();
    }

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
        if (monitorThread != null) {
            monitorThread.interrupt();
            monitorThread = null;
        }
    }
}
