package com.szh.ui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme;
import com.szh.manager.ConfigManager;
import com.szh.ui.panel.AbstractCommandPanel;
import com.szh.ui.panel.TcpPanel;
import com.szh.ui.panel.UdpPanel;
import com.szh.ui.panel.VideoStreamPanel;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainFrame extends JFrame {

    private JTextPane logPane;
    private StyleContext logStyleContext;
    private Style logStyleSend, logStyleOk, logStyleErr, logStyleTimeout, logStyleTime;
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
        Font font = new Font("Microsoft YaHei", Font.PLAIN, 12);
        UIManager.put("defaultFont", font);
        UIManager.put("Label.font", font);
        UIManager.put("Button.font", font.deriveFont(11.5f));
        UIManager.put("TextField.font", font);
        UIManager.put("ComboBox.font", font);
        UIManager.put("TitledBorder.font", new Font("Microsoft YaHei", Font.BOLD, 12));
        UIManager.put("TabbedPane.font", new Font("Microsoft YaHei", Font.BOLD, 12));

        // FlatLaf 细粒度按钮样式：更小巧精致
        UIManager.put("Button.arc", 6);
        UIManager.put("Button.minimumWidth", 0);
        UIManager.put("Button.margin", new Insets(3, 10, 3, 10));
        UIManager.put("Component.focusWidth", 0.6f);
    }

    private UdpPanel udpPanel;
    private TcpPanel tcpPanel;

    private void initUI() {
        JPanel root = new JPanel(new BorderLayout(8, 8));
        root.setBorder(new EmptyBorder(10, 10, 10, 10));

        // 中间：左右分栏（左侧Tab + 右侧客户端列表）
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setDividerSize(6);
        splitPane.setResizeWeight(0.72);

        // 左侧 Tab 页
        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        enableSmoothTabScrolling(tabbedPane);

        // UDP Tab
        udpPanel = new UdpPanel();
        addTab(tabbedPane, "UDP", udpPanel);

        // TCP Tab
        tcpPanel = new TcpPanel();
        addTab(tabbedPane, "TCP", tcpPanel);

        // 视频流 Tab
        videoStreamPanel = new VideoStreamPanel();
        videoStreamPanel.setLogCallback(msg -> appendLog(msg, logStyleOk));
        addTab(tabbedPane, "视频流", videoStreamPanel);

        splitPane.setLeftComponent(tabbedPane);

        // 右侧客户端列表
        splitPane.setRightComponent(createClientListPanel());

        root.add(splitPane, BorderLayout.CENTER);

        // 底部日志
        root.add(createLogPanel(), BorderLayout.SOUTH);

        setContentPane(root);
    }

    /** 创建右侧客户端列表面板 */
    private JPanel createClientListPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setMinimumSize(new Dimension(180, 100));

        // 上下分栏：UDP 客户端列表在上，TCP 客户端列表在下，各占一半
        JSplitPane clientSplitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        clientSplitPane.setDividerSize(6);
        clientSplitPane.setResizeWeight(0.5);

        // UDP 服务端客户端列表
        JScrollPane udpScroll = udpPanel.getUdpClientListScroll();
        if (udpScroll != null) {
            clientSplitPane.setTopComponent(udpScroll);
        }

        // TCP 服务端客户端列表
        JScrollPane tcpScroll = tcpPanel.getTcpClientListScroll();
        if (tcpScroll != null) {
            clientSplitPane.setBottomComponent(tcpScroll);
        }

        panel.add(clientSplitPane, BorderLayout.CENTER);
        return panel;
    }

    private void addTab(JTabbedPane tabbedPane, String title, AbstractCommandPanel panel) {
        panels.put(title, panel);
        tabbedPane.addTab(title, panel);
    }

    private JPanel createLogPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "发送 / 响应日志",
                TitledBorder.LEADING,
                TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 13)
        ));

        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(0x1E1E1E));
        logPane.setCaretColor(new Color(0xD4D4D4));

        // 创建样式
        logStyleContext = new StyleContext();
        Style defaultStyle = logStyleContext.addStyle("default", null);
        StyleConstants.setFontFamily(defaultStyle, "Consolas");
        StyleConstants.setFontSize(defaultStyle, 13);
        StyleConstants.setForeground(defaultStyle, new Color(0xD4D4D4));

        logStyleSend = logStyleContext.addStyle("send", defaultStyle);
        StyleConstants.setForeground(logStyleSend, new Color(0x64B5F6));

        logStyleOk = logStyleContext.addStyle("ok", defaultStyle);
        StyleConstants.setForeground(logStyleOk, new Color(0x81C784));

        logStyleErr = logStyleContext.addStyle("err", defaultStyle);
        StyleConstants.setForeground(logStyleErr, new Color(0xE57373));

        logStyleTimeout = logStyleContext.addStyle("timeout", defaultStyle);
        StyleConstants.setForeground(logStyleTimeout, new Color(0xFFB74D));

        logStyleTime = logStyleContext.addStyle("time", defaultStyle);
        StyleConstants.setForeground(logStyleTime, new Color(0x888888));
        StyleConstants.setFontSize(logStyleTime, 11);

        logPane.setDocument(new DefaultStyledDocument(logStyleContext));

        // 右键菜单：清除日志
        JPopupMenu popupMenu = new JPopupMenu();
        JMenuItem clearItem = new JMenuItem("清除日志");
        clearItem.addActionListener(e -> logPane.setText(""));
        popupMenu.add(clearItem);
        logPane.setComponentPopupMenu(popupMenu);

        JScrollPane scrollPane = new JScrollPane(logPane);
        scrollPane.setPreferredSize(new Dimension(780, 260));
        scrollPane.getVerticalScrollBar().setUnitIncrement(16);
        scrollPane.getVerticalScrollBar().setBlockIncrement(80);
        enableSmoothScrolling(scrollPane);
        panel.add(scrollPane, BorderLayout.CENTER);

        return panel;
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

    private final SimpleDateFormat sdf = new SimpleDateFormat("HH:mm:ss.SSS");
    /** 日志最大行数，超过后自动裁掉旧行 */
    private static final int MAX_LOG_LINES = 2000;
    private int logLineCount = 0;

    private void appendLog(String msg, Style style) {
        try {
            Document doc = logPane.getDocument();
            // 超过最大行数时删除前 500 行，保持插入性能稳定
            if (logLineCount >= MAX_LOG_LINES) {
                Element root = doc.getDefaultRootElement();
                int endLine = Math.min(500, root.getElementCount() - 1);
                if (endLine > 0) {
                    Element endElem = root.getElement(endLine);
                    int endOffset = endElem.getEndOffset();
                    doc.remove(0, endOffset);
                    logLineCount -= endLine;
                }
            }
            String time = "[" + sdf.format(new Date()) + "] ";
            doc.insertString(doc.getLength(), time, logStyleTime);
            doc.insertString(doc.getLength(), msg + "\n", style);
            logLineCount++;
            logPane.setCaretPosition(doc.getLength());
        } catch (BadLocationException e) {
            e.printStackTrace();
        }
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
            initGlobalFont();          // 重新注入字体
            SwingUtilities.updateComponentTreeUI(this);
            currentThemeClassName = className;

            // 重建窗口装饰以应用新标题栏样式
            SwingUtilities.invokeLater(() -> {
                getRootPane().revalidate();
                getRootPane().repaint();
            });
        } catch (Exception ex) {
            appendLog("切换主题失败: " + ex.getMessage(), logStyleErr);
        }
    }

    /** 构建菜单栏，含主题选择 */
    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        // ---- 主题菜单 ----
        JMenu themeMenu = new JMenu("主题");
        ButtonGroup group = new ButtonGroup();

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
            group.add(item);

            if (cls.equals(currentThemeClassName)) {
                item.setSelected(true);
            }

            (isDark ? darkMenu : lightMenu).add(item);
        }

        themeMenu.add(darkMenu);
        themeMenu.add(lightMenu);
        bar.add(themeMenu);

        return bar;
    }
}
