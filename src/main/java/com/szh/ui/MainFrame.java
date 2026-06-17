package com.szh.ui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme;
import com.szh.manager.ConfigManager;
import com.szh.ui.panel.*;
import com.szh.utils.NetUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainFrame extends JFrame {

    private final Map<String, AbstractCommandPanel> panels = new LinkedHashMap<>();
    private VideoStreamPanel videoStreamPanel;
    private final ConfigManager config = new ConfigManager("app_config.properties");
    private String currentThemeClassName;

    public MainFrame() {
        initTheme();
        initGlobalFont();
        initUI();
        setJMenuBar(createMenuBar());
        loadConfig();

        setTitle("CoreTools");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setSize(1280, 780);
        setLocationRelativeTo(null);
        setMinimumSize(new Dimension(1000, 650));

        // 关闭时保存配置
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveConfig();
            }
        });
    }

    private void initGlobalFont() {
        currentFontFamily = config.get("font.family", "Microsoft YaHei");
        String sizeStr = config.get("font.size", "13");
        try { currentFontSize = Integer.parseInt(sizeStr); } catch (NumberFormatException e) { currentFontSize = 13; }

        Font font = new Font(currentFontFamily, Font.PLAIN, currentFontSize);
        UIManager.put("defaultFont", font);
        UIManager.put("Label.font", font);
        UIManager.put("Button.font", font.deriveFont(currentFontSize - 0.5f));
        UIManager.put("TextField.font", font);
        UIManager.put("ComboBox.font", font);
        UIManager.put("TitledBorder.font", new Font(currentFontFamily, Font.BOLD, currentFontSize));
        UIManager.put("TabbedPane.font", new Font(currentFontFamily, Font.BOLD, currentFontSize));

        // FlatLaf 细粒度按钮样式：更小巧精致
        UIManager.put("Button.arc", 6);
        UIManager.put("Button.minimumWidth", 0);
        UIManager.put("Button.margin", new Insets(3, 10, 3, 10));
        UIManager.put("Component.focusWidth", 0.6f);

        // 全局文字颜色
        String colorStr = config.get("font.color", "");
        if (!colorStr.isEmpty()) {
            try {
                currentTextColor = new Color(Integer.parseInt(colorStr));
            } catch (NumberFormatException e) {
                currentTextColor = Color.WHITE;
            }
        } else {
            currentTextColor = Color.WHITE;
        }
        applyTextColor();
    }

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        // Tab 页
        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        enableSmoothTabScrolling(tabbedPane);

        // 系统监控 Tab
        SystemMonitorPanel sysMonitorPanel = new SystemMonitorPanel();
        addTab(tabbedPane, "系统监控", sysMonitorPanel);


        // 串口调试 Tab
        SerialPanel serialPanel = new SerialPanel();
        addTab(tabbedPane, "串口调试", serialPanel);

        // HTTP 请求 Tab
        HttpPanel httpPanel = new HttpPanel();
        addTab(tabbedPane, "HTTP", httpPanel);

        // UDP Tab
        UdpPanel udpPanel = new UdpPanel();
        addTab(tabbedPane, "UDP", udpPanel);

        // TCP Tab
        TcpPanel tcpPanel = new TcpPanel();
        addTab(tabbedPane, "TCP", tcpPanel);

        // WebSocket Tab
        WebSocketPanel wsPanel = new WebSocketPanel();
        addTab(tabbedPane, "WebSocket", wsPanel);

        // Telnet Tab
        TelnetPanel telnetPanel = new TelnetPanel();
        addTab(tabbedPane, "Telnet", telnetPanel);

        // 视频流 Tab（内置发送/响应日志）
        videoStreamPanel = new VideoStreamPanel();
        addTab(tabbedPane, "视频流", videoStreamPanel);

        // 数据编码转换 Tab
        DataConvertPanel convertPanel = new DataConvertPanel();
        addTab(tabbedPane, "数据编码转换", convertPanel);

        // 网络诊断 Tab
        NetDiagnosePanel diagPanel = new NetDiagnosePanel();
        addTab(tabbedPane, "Ping/DNS", diagPanel);

        // 扫描 Tab
        ScanPanel scanPanel = new ScanPanel();
        addTab(tabbedPane, "网络扫描", scanPanel);

        // 日志查看 Tab
        LogViewerPanel logViewerPanel = new LogViewerPanel();
        addTab(tabbedPane, "日志查看", logViewerPanel);

        // 十六进制查看 Tab
        HexViewerPanel hexViewerPanel = new HexViewerPanel();
        addTab(tabbedPane, "Hex查看", hexViewerPanel);

        // 文件批量处理 Tab
        BatchFilePanel batchFilePanel = new BatchFilePanel();
        addTab(tabbedPane, "文件处理", batchFilePanel);

        // 数据库客户端 Tab
        DatabasePanel dbPanel = new DatabasePanel();
        addTab(tabbedPane, "数据库", dbPanel);

        // Redis 客户端 Tab
        RedisPanel redisPanel = new RedisPanel();
        addTab(tabbedPane, "Redis", redisPanel);

        // SSH Tab
        SshPanel sshPanel = new SshPanel();
        addTab(tabbedPane, "SSH", sshPanel);

        // 监听 tab 切换：控制系统监控面板的启停
        tabbedPane.addChangeListener(e -> {
            Component selected = tabbedPane.getSelectedComponent();
            for (Map.Entry<String, AbstractCommandPanel> entry : panels.entrySet()) {
                if (entry.getValue() instanceof SystemMonitorPanel smp) {
                    if (entry.getValue() == selected) {
                        smp.startMonitoring();
                    } else {
                        smp.stopMonitoring();
                    }
                }
            }
        });

        root.add(tabbedPane, BorderLayout.CENTER);

        setContentPane(root);
    }

    private void addTab(JTabbedPane tabbedPane, String title, AbstractCommandPanel panel) {
        panels.put(title, panel);
        tabbedPane.addTab(title, panel);
    }

    // ==================== Tab 滚轮切换 ====================
    private static void enableSmoothTabScrolling(JTabbedPane tabbedPane) {
        tabbedPane.addMouseWheelListener(new java.awt.event.MouseWheelListener() {
            private long lastScrollTime = 0;

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                // 只在鼠标在 Tab 区域时处理
                if (tabbedPane.getTabCount() == 0) return;
                int tabAreaHeight = tabbedPane.getUI().getTabBounds(tabbedPane, 0).height + 4;
                if (e.getY() > tabAreaHeight) return;

                // 防抖：120ms 内只响应一次
                long now = System.currentTimeMillis();
                if (now - lastScrollTime < 120) return;
                lastScrollTime = now;

                int idx = tabbedPane.getSelectedIndex();
                int dir = e.getUnitsToScroll() > 0 ? 1 : -1;
                int newIdx = idx + dir;
                if (newIdx >= 0 && newIdx < tabbedPane.getTabCount()) {
                    tabbedPane.setSelectedIndex(newIdx);
                }
            }
        });
    }

    // ==================== 丝滑滚动（模拟惯性） ====================
    public static void enableSmoothScrolling(JScrollPane scrollPane) {
        scrollPane.setWheelScrollingEnabled(false);

        scrollPane.addMouseWheelListener(new java.awt.event.MouseWheelListener() {
            private Timer timer;
            private float velocity = 0;
            private long lastTime;

            @Override
            public void mouseWheelMoved(java.awt.event.MouseWheelEvent e) {
                if (timer != null && timer.isRunning()) timer.stop();

                long now = System.currentTimeMillis();
                if (lastTime > 0) {
                    float dt = Math.max(1, now - lastTime);
                    velocity = e.getUnitsToScroll() * 300f / dt;
                } else {
                    velocity = e.getUnitsToScroll() * 15f;
                }
                lastTime = now;

                JScrollBar bar = scrollPane.getVerticalScrollBar();
                timer = new Timer(12, null);
                final JScrollBar finalBar = bar;
                timer.addActionListener(evt -> {
                    velocity *= 0.88f;
                    if (Math.abs(velocity) < 0.5f) {
                        timer.stop();
                        return;
                    }
                    int val = finalBar.getValue() + Math.round(velocity);
                    val = Math.max(finalBar.getMinimum(), Math.min(finalBar.getMaximum() - finalBar.getVisibleAmount(), val));
                    finalBar.setValue(val);
                });
                timer.start();
            }
        });
    }

    // ==================== 配置持久化 ====================

    private void loadConfig() {
        // 通知各面板加载配置
        for (AbstractCommandPanel panel : panels.values()) {
            panel.loadConfig(config);
        }
    }

    private void saveConfig() {
        config.set("theme", currentThemeClassName);
        config.set("font.family", currentFontFamily);
        config.set("font.size", String.valueOf(currentFontSize));
        config.set("font.color", String.valueOf(currentTextColor.getRGB()));
        // 通知各面板保存配置
        for (AbstractCommandPanel panel : panels.values()) {
            panel.saveConfig(config);
        }
        config.save();
    }

    // ==================== 主题切换 ====================

    /** 启动时初始化主题：优先从 config 读取，否则用默认 Material Dark */
    private void initTheme() {
        String saved = config.get("theme", "");
        if (saved != null && !saved.isEmpty()) {
            try {
                // 通过反射调用对应主题类的 setup()，行为与默认分支完全一致
                Class.forName(saved).getMethod("setup").invoke(null);
                FlatLaf.updateUILater();
                currentThemeClassName = saved;
                return;
            } catch (Exception ignored) { /* fallback */ }
        }
        FlatMaterialDesignDarkIJTheme.setup();
        currentThemeClassName = FlatMaterialDesignDarkIJTheme.class.getName();
    }

    /** 即时切换主题 */
    private void switchTheme(String className) {
        try {
            // 亮色主题不需要自绘标题栏，使用系统原生标题栏即可
            boolean isDark = className.contains("Dark") || className.contains("dark");
            System.setProperty("flatlaf.useWindowDecorations", String.valueOf(isDark));

            UIManager.setLookAndFeel(className);
            FlatLaf.updateUILater();
            initGlobalFont();          // 重新注入字体 + 文字颜色
            SwingUtilities.updateComponentTreeUI(this);
            currentThemeClassName = className;

            // 重建窗口装饰以应用新标题栏样式
            SwingUtilities.invokeLater(() -> {
                getRootPane().revalidate();
                getRootPane().repaint();
            });
        } catch (Exception ex) {
            System.err.println("切换主题失败: " + ex.getMessage());
        }
    }

    private String currentFontFamily;
    private int currentFontSize;
    private Color currentTextColor;

    /** 构建菜单栏，含主题选择 */
    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        // ---- 主题菜单 ----
        JMenu themeMenu = new JMenu("主题");
        ButtonGroup themeGroup = new ButtonGroup();

        // {显示名, 完整类名, "Dark"/"Light"}
        String[][] themes = {
                // ---- 暗色 ----
                {"Material Dark ★", "com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme",    "Dark"},
                {"Xcode Dark",      "com.formdev.flatlaf.intellijthemes.FlatXcodeDarkIJTheme",              "Dark"},
                {"One Dark",        "com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme",               "Dark"},
                {"Dracula",         "com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme",               "Dark"},
                {"Nord",            "com.formdev.flatlaf.intellijthemes.FlatNordIJTheme",                  "Dark"},
                {"Gruvbox Dark",    "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkMediumIJTheme",     "Dark"},
                {"Monokai Pro",     "com.formdev.flatlaf.intellijthemes.FlatMonokaiProIJTheme",            "Dark"},
                {"Arc Dark",        "com.formdev.flatlaf.intellijthemes.FlatArcDarkIJTheme",               "Dark"},
                {"Carbon",          "com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme",                "Dark"},
                {"Dark Flat",       "com.formdev.flatlaf.intellijthemes.FlatDarkFlatIJTheme",              "Dark"},
                {"Dark Purple",     "com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme",            "Dark"},
                {"Hiberbee Dark",   "com.formdev.flatlaf.intellijthemes.FlatHiberbeeDarkIJTheme",          "Dark"},
                {"Spacegray",       "com.formdev.flatlaf.intellijthemes.FlatSpacegrayIJTheme",             "Dark"},
                {"Vuesion",         "com.formdev.flatlaf.intellijthemes.FlatVuesionIJTheme",               "Dark"},
                {"Monocai",         "com.formdev.flatlaf.intellijthemes.FlatMonocaiIJTheme",               "Dark"},
                {"Solarized Dark",  "com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme",         "Dark"},
                {"High Contrast",   "com.formdev.flatlaf.intellijthemes.FlatHighContrastIJTheme",          "Dark"},
                {"Gradianto Fuchsia","com.formdev.flatlaf.intellijthemes.FlatGradiantoDarkFuchsiaIJTheme", "Dark"},
                {"Gradianto Ocean", "com.formdev.flatlaf.intellijthemes.FlatGradiantoDeepOceanIJTheme",    "Dark"},
                {"Gradianto Blue",  "com.formdev.flatlaf.intellijthemes.FlatGradiantoMidnightBlueIJTheme", "Dark"},
                {"Gradianto Green", "com.formdev.flatlaf.intellijthemes.FlatGradiantoNatureGreenIJTheme",  "Dark"},
                {"Gruvbox Hard",    "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkHardIJTheme",       "Dark"},
                {"Gruvbox Soft",    "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkSoftIJTheme",       "Dark"},
                {"Flat Dark",       "com.formdev.flatlaf.FlatDarkLaf",                                     "Dark"},
                // ---- 亮色 ----
                {"Flat Light",      "com.formdev.flatlaf.FlatLightLaf",                                    "Light"},
                {"Flat IntelliJ",   "com.formdev.flatlaf.FlatIntelliJLaf",                                 "Light"},
                {"Arc",             "com.formdev.flatlaf.intellijthemes.FlatArcIJTheme",                   "Light"},
                {"Cyan Light",      "com.formdev.flatlaf.intellijthemes.FlatCyanLightIJTheme",             "Light"},
                {"Gray",            "com.formdev.flatlaf.intellijthemes.FlatGrayIJTheme",                  "Light"},
                {"Light Flat",      "com.formdev.flatlaf.intellijthemes.FlatLightFlatIJTheme",             "Light"},
                {"Solarized Light", "com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme",        "Light"},
        };

        JMenu darkMenu = new JMenu("暗色主题");
        JMenu lightMenu = new JMenu("亮色主题");

        for (String[] t : themes) {
            String name = t[0], cls = t[1];
            boolean isDark = "Dark".equals(t[2]);

            JRadioButtonMenuItem item = new JRadioButtonMenuItem(name);
            item.addActionListener(e -> switchTheme(cls));
            themeGroup.add(item);

            if (cls.equals(currentThemeClassName)) {
                item.setSelected(true);
            }

            (isDark ? darkMenu : lightMenu).add(item);
        }

        themeMenu.add(darkMenu);
        themeMenu.add(lightMenu);
        bar.add(themeMenu);

        // ---- 字体菜单 ----
        JMenu fontMenu = new JMenu("字体");

        // 字体族子菜单
        JMenu familyMenu = new JMenu("字体族");
        ButtonGroup familyGroup = new ButtonGroup();
        String[] families = {
                "Microsoft YaHei", "Consolas", "JetBrains Mono", "Fira Code",
                "Source Code Pro", "Cascadia Code", "Monaco", "Courier New", "SimSun", "FangSong"
        };

        String savedFamily = config.get("font.family", "Microsoft YaHei");
        currentFontFamily = savedFamily;
        for (String f : families) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(f);
            item.addActionListener(e -> switchFontFamily(f));
            familyGroup.add(item);
            if (f.equals(currentFontFamily)) item.setSelected(true);
            familyMenu.add(item);
        }
        fontMenu.add(familyMenu);

        // 字号子菜单
        JMenu sizeMenu = new JMenu("字号");
        ButtonGroup sizeGroup = new ButtonGroup();
        int[] sizes = {10, 11, 12, 13, 14, 15, 16, 18, 20};

        String savedSize = config.get("font.size", "13");
        try { currentFontSize = Integer.parseInt(savedSize); } catch (NumberFormatException e) { currentFontSize = 13; }
        for (int s : sizes) {
            JRadioButtonMenuItem item = new JRadioButtonMenuItem(String.valueOf(s));
            item.addActionListener(e -> switchFontSize(s));
            sizeGroup.add(item);
            if (s == currentFontSize) item.setSelected(true);
            sizeMenu.add(item);
        }
        fontMenu.add(sizeMenu);

        fontMenu.addSeparator();

        // 字体颜色
        JMenuItem colorItem = new JMenuItem("字体颜色");
        colorItem.addActionListener(e -> switchTextColor());
        fontMenu.add(colorItem);

        bar.add(fontMenu);

        return bar;
    }

    /** 切换字体族 */
    private void switchFontFamily(String family) {
        currentFontFamily = family;
        applyFont();
        config.set("font.family", family);
    }

    /** 切换字号 */
    private void switchFontSize(int size) {
        currentFontSize = size;
        applyFont();
        config.set("font.size", String.valueOf(size));
    }

    /** 打开颜色选择器切换全局文字颜色 */
    private void switchTextColor() {
        Color chosen = JColorChooser.showDialog(this, "选择全局文字颜色", currentTextColor);
        if (chosen != null) {
            currentTextColor = chosen;
            NetUtil.TEXT_COLOR = chosen;
            config.set("font.color", String.valueOf(chosen.getRGB()));
            applyTextColor();
        }
    }

    /** 将当前文字颜色应用到全局 */
    private void applyTextColor() {
        NetUtil.TEXT_COLOR = currentTextColor;

        // UIManager 全局前景色覆盖
        UIManager.put("Label.foreground", currentTextColor);
        UIManager.put("Button.foreground", currentTextColor);
        UIManager.put("TextField.foreground", currentTextColor);
        UIManager.put("TextArea.foreground", currentTextColor);
        UIManager.put("ComboBox.foreground", currentTextColor);
        UIManager.put("TitledBorder.titleColor", currentTextColor);
        UIManager.put("TabbedPane.foreground", currentTextColor);
        UIManager.put("Table.foreground", currentTextColor);
        UIManager.put("TableHeader.foreground", currentTextColor);
        UIManager.put("ToolTip.foreground", currentTextColor);
        UIManager.put("Tree.foreground", currentTextColor);
        UIManager.put("List.foreground", currentTextColor);

        SwingUtilities.updateComponentTreeUI(this);
        updateAllTextColors(getContentPane(), currentTextColor);

        SwingUtilities.invokeLater(() -> {
            getRootPane().revalidate();
            getRootPane().repaint();
        });
    }

    /** 递归遍历组件树，强制更新所有文字颜色 */
    private void updateAllTextColors(Container root, Color color) {
        for (Component c : root.getComponents()) {
            if (c instanceof javax.swing.JLabel
                    || c instanceof javax.swing.JTextComponent
                    || c instanceof javax.swing.JButton
                    || c instanceof javax.swing.JComboBox
                    || c instanceof javax.swing.JTable
                    || c instanceof javax.swing.JTree
                    || c instanceof javax.swing.JList) {
                c.setForeground(color);
            } else if (c instanceof Container) {
                c.setForeground(color);
            }
            if (c instanceof Container) {
                updateAllTextColors((Container) c, color);
            }
        }
    }

    /** 应用当前字体到全局 */
    private void applyFont() {
        Font font = new Font(currentFontFamily, Font.PLAIN, currentFontSize);
        UIManager.put("defaultFont", font);
        UIManager.put("Label.font", font);
        UIManager.put("Button.font", font.deriveFont(currentFontSize - 0.5f));
        UIManager.put("TextField.font", font);
        UIManager.put("ComboBox.font", font);
        UIManager.put("TitledBorder.font", new Font(currentFontFamily, Font.BOLD, currentFontSize));
        UIManager.put("TabbedPane.font", new Font(currentFontFamily, Font.BOLD, currentFontSize));

        // 更新 NetUtil 中的全局字体引用
        NetUtil.updateFont(currentFontFamily, currentFontSize);

        // 递归刷新所有组件字体
        SwingUtilities.updateComponentTreeUI(this);
        updateAllFonts(getContentPane(), font);

        SwingUtilities.invokeLater(() -> {
            getRootPane().revalidate();
            getRootPane().repaint();
        });
    }

    /** 递归遍历组件树，更新所有手动设置过字体的组件 */
    private void updateAllFonts(Container root, Font font) {
        for (Component c : root.getComponents()) {
            if (c instanceof javax.swing.JTable) {
                c.setFont(new Font(currentFontFamily, Font.PLAIN, currentFontSize));
                ((javax.swing.JTable) c).setRowHeight(currentFontSize + 10);
                ((javax.swing.JTable) c).getTableHeader().setFont(new Font(currentFontFamily, Font.PLAIN, currentFontSize));
            } else if (c instanceof org.fife.ui.rsyntaxtextarea.RSyntaxTextArea) {
                c.setFont(font);
            } else if (c instanceof javax.swing.JTextPane || c instanceof javax.swing.JTextArea) {
                c.setFont(font);
            } else if (c instanceof javax.swing.JLabel) {
                // 只更新非 emoji 字体
                String family = c.getFont().getFamily();
                if (!"Segoe UI Emoji".equals(family)) {
                    c.setFont(new Font(currentFontFamily, c.getFont().getStyle(), currentFontSize));
                }
            } else if (c instanceof javax.swing.JButton) {
                java.awt.Font old = c.getFont();
                if (!"Segoe UI Emoji".equals(old.getFamily())) {
                    c.setFont(new Font(currentFontFamily, old.getStyle(), currentFontSize));
                }
            } else if (c instanceof javax.swing.JComboBox) {
                c.setFont(font);
            }
            if (c instanceof Container) {
                updateAllFonts((Container) c, font);
            }
        }
    }
}
