package com.szh.ui;

import com.formdev.flatlaf.FlatLaf;
import com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme;
import com.szh.manager.ConfigManager;
import com.szh.ui.panel.*;
import com.szh.utils.NetUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.text.JTextComponent;
import java.awt.*;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

public class MainFrame extends JFrame {

    private final Map<String, AbstractCommandPanel> panels = new LinkedHashMap<>();
    private VideoStreamPanel videoStreamPanel;
    private final ConfigManager config = new ConfigManager("app_config.properties");
    private String currentThemeClassName;
    private JTabbedPane tabbedPane;

    public MainFrame() {
        initTheme();
        initGlobalFont();
        initUI();
        setJMenuBar(createMenuBar());

        setTitle("CoreTools");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        Dimension screenSize = Toolkit.getDefaultToolkit().getScreenSize();
        int initW = (int) (screenSize.width * 0.5);
        int initH = (int) (screenSize.height * 0.5);
        setSize(initW, initH);
        setLocationRelativeTo(null);
        setPreferredSize(new Dimension(initW, initH));
        setMinimumSize(new Dimension(600, 400));
        // loadConfig 放在 setSize 之后，以便用保存的窗口尺寸覆盖默认值
        loadConfig();

        // 窗口 resize 时即时重新布局
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                getRootPane().revalidate();
                getRootPane().repaint();
            }
        });

        // 关闭时保存配置（含窗口尺寸和位置）
        addWindowListener(new WindowAdapter() {
            @Override
            public void windowClosing(WindowEvent e) {
                saveWindowBounds();
                saveConfig();
            }
        });
    }

    private void initGlobalFont() {
        currentFontFamily = config.get("font.family", "Microsoft YaHei");
        String sizeStr = config.get("font.size", "13");
        try {
            currentFontSize = Integer.parseInt(sizeStr);
        } catch (NumberFormatException e) {
            currentFontSize = 13;
        }

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
        JPanel root = new JPanel(new BorderLayout(0, 0));
        root.setBorder(new EmptyBorder(0, 0, 0, 0));

        // Tab 页
        tabbedPane = new JTabbedPane(JTabbedPane.TOP);
        tabbedPane.setTabLayoutPolicy(JTabbedPane.WRAP_TAB_LAYOUT);
        // FlatLaf: 均分 Tab 宽度填满整行
        UIManager.put("TabbedPane.tabWidthMode", "equal");
        // 窗口 resize 时即时重绘 tabbedPane，消除延迟感
        tabbedPane.addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                tabbedPane.revalidate();
                tabbedPane.repaint();
            }
        });
        enableSmoothTabScrolling(tabbedPane);

        // AI 对话 Tab
        AiChatPanel aiChatPanel = new AiChatPanel();
        addTab(tabbedPane, "AI 对话", aiChatPanel);

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

        // MQTT Tab
        MqttPanel mqttPanel = new MqttPanel();
        addTab(tabbedPane, "MQTT", mqttPanel);

        // gRPC Tab
        GrpcPanel grpcPanel = new GrpcPanel();
        addTab(tabbedPane, "gRPC", grpcPanel);

        // 二维码 Tab
        QrCodePanel qrCodePanel = new QrCodePanel();
        addTab(tabbedPane, "二维码", qrCodePanel);

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
        // 加载背景透明度
        String alphaStr = config.get("background.alpha", "0.45");
        try {
            backgroundAlpha = Float.parseFloat(alphaStr);
        } catch (NumberFormatException e) {
            backgroundAlpha = 0.45f;
        }
        // 加载背景图片
        String bgPath = config.get("background.image", "");
        if (bgPath != null && !bgPath.isEmpty()) {
            File bgFile = new File(bgPath);
            if (bgFile.exists()) {
                try {
                    backgroundImage = ImageIO.read(bgFile);
                    backgroundImagePath = bgPath;
                    applyBackgroundImage();
                } catch (IOException ignored) { /* 图片失效则忽略 */ }
            }
        }
        // 加载窗口尺寸和位置（在 setSize/setLocation 之后调用以覆盖默认值）
        loadWindowBounds();
        // 通知各面板加载配置
        for (AbstractCommandPanel panel : panels.values()) {
            panel.loadConfig(config);
        }
    }

    /**
     * 加载上次关闭时保存的窗口尺寸和位置
     */
    private void loadWindowBounds() {
        String xStr = config.get("window.x", "");
        String yStr = config.get("window.y", "");
        String wStr = config.get("window.width", "");
        String hStr = config.get("window.height", "");
        String maxStr = config.get("window.maximized", "false");

        if (!wStr.isEmpty() && !hStr.isEmpty()) {
            try {
                int w = Integer.parseInt(wStr);
                int h = Integer.parseInt(hStr);
                // 尺寸合理性检查：不能小于最小尺寸，不能大于屏幕
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                w = Math.max(600, Math.min(w, screen.width));
                h = Math.max(400, Math.min(h, screen.height));
                setSize(w, h);
                setPreferredSize(new Dimension(w, h));
                setMinimumSize(new Dimension(600, 400));
            } catch (NumberFormatException ignored) { /* 使用默认尺寸 */ }
        }

        if (!xStr.isEmpty() && !yStr.isEmpty()) {
            try {
                int x = Integer.parseInt(xStr);
                int y = Integer.parseInt(yStr);
                // 确保窗口至少部分可见（防止多显示器断开后窗口跑到屏幕外）
                Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
                if (x + 100 > 0 && x < screen.width - 50 && y + 50 > 0 && y < screen.height - 50) {
                    setLocation(x, y);
                } else {
                    setLocationRelativeTo(null); // 兜底居中
                }
            } catch (NumberFormatException ignored) { /* 使用默认位置 */ }
        }

        // 恢复最大化状态
        if ("true".equals(maxStr)) {
            setExtendedState(JFrame.MAXIMIZED_BOTH);
        }
    }

    /**
     * 保存当前窗口尺寸和位置到配置
     */
    private void saveWindowBounds() {
        // 最大化时不保存尺寸（下次打开还是最大化）
        if ((getExtendedState() & JFrame.MAXIMIZED_BOTH) != 0) {
            config.set("window.maximized", "true");
            return;
        }
        config.set("window.maximized", "false");
        config.set("window.x", String.valueOf(getX()));
        config.set("window.y", String.valueOf(getY()));
        config.set("window.width", String.valueOf(getWidth()));
        config.set("window.height", String.valueOf(getHeight()));
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

    /**
     * 启动时初始化主题：优先从 config 读取，否则用默认 Material Dark
     */
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

    /**
     * 即时切换主题
     */
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

    /**
     * 背景图片路径（null 表示无背景）
     */
    private String backgroundImagePath;
    /**
     * 缓存的背景图片
     */
    private BufferedImage backgroundImage;
    /**
     * 背景图片透明度 0.0~1.0，默认 0.45
     */
    private float backgroundAlpha = 0.45f;


    /**
     * 构建菜单栏，含主题选择
     */
    private JMenuBar createMenuBar() {
        JMenuBar bar = new JMenuBar();

        // ---- 主题菜单 ----
        JMenu themeMenu = new JMenu("主题");
        ButtonGroup themeGroup = new ButtonGroup();

        // {显示名, 完整类名, "Dark"/"Light"}
        String[][] themes = {
                // ---- 暗色 ----
                {"Material Dark ★", "com.formdev.flatlaf.intellijthemes.FlatMaterialDesignDarkIJTheme", "Dark"},
                {"Xcode Dark", "com.formdev.flatlaf.intellijthemes.FlatXcodeDarkIJTheme", "Dark"},
                {"One Dark", "com.formdev.flatlaf.intellijthemes.FlatOneDarkIJTheme", "Dark"},
                {"Dracula", "com.formdev.flatlaf.intellijthemes.FlatDraculaIJTheme", "Dark"},
                {"Nord", "com.formdev.flatlaf.intellijthemes.FlatNordIJTheme", "Dark"},
                {"Gruvbox Dark", "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkMediumIJTheme", "Dark"},
                {"Monokai Pro", "com.formdev.flatlaf.intellijthemes.FlatMonokaiProIJTheme", "Dark"},
                {"Arc Dark", "com.formdev.flatlaf.intellijthemes.FlatArcDarkIJTheme", "Dark"},
                {"Carbon", "com.formdev.flatlaf.intellijthemes.FlatCarbonIJTheme", "Dark"},
                {"Dark Flat", "com.formdev.flatlaf.intellijthemes.FlatDarkFlatIJTheme", "Dark"},
                {"Dark Purple", "com.formdev.flatlaf.intellijthemes.FlatDarkPurpleIJTheme", "Dark"},
                {"Hiberbee Dark", "com.formdev.flatlaf.intellijthemes.FlatHiberbeeDarkIJTheme", "Dark"},
                {"Spacegray", "com.formdev.flatlaf.intellijthemes.FlatSpacegrayIJTheme", "Dark"},
                {"Vuesion", "com.formdev.flatlaf.intellijthemes.FlatVuesionIJTheme", "Dark"},
                {"Monocai", "com.formdev.flatlaf.intellijthemes.FlatMonocaiIJTheme", "Dark"},
                {"Solarized Dark", "com.formdev.flatlaf.intellijthemes.FlatSolarizedDarkIJTheme", "Dark"},
                {"High Contrast", "com.formdev.flatlaf.intellijthemes.FlatHighContrastIJTheme", "Dark"},
                {"Gradianto Fuchsia", "com.formdev.flatlaf.intellijthemes.FlatGradiantoDarkFuchsiaIJTheme", "Dark"},
                {"Gradianto Ocean", "com.formdev.flatlaf.intellijthemes.FlatGradiantoDeepOceanIJTheme", "Dark"},
                {"Gradianto Blue", "com.formdev.flatlaf.intellijthemes.FlatGradiantoMidnightBlueIJTheme", "Dark"},
                {"Gradianto Green", "com.formdev.flatlaf.intellijthemes.FlatGradiantoNatureGreenIJTheme", "Dark"},
                {"Gruvbox Hard", "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkHardIJTheme", "Dark"},
                {"Gruvbox Soft", "com.formdev.flatlaf.intellijthemes.FlatGruvboxDarkSoftIJTheme", "Dark"},
                {"Flat Dark", "com.formdev.flatlaf.FlatDarkLaf", "Dark"},
                // ---- 亮色 ----
                {"Flat Light", "com.formdev.flatlaf.FlatLightLaf", "Light"},
                {"Flat IntelliJ", "com.formdev.flatlaf.FlatIntelliJLaf", "Light"},
                {"Arc", "com.formdev.flatlaf.intellijthemes.FlatArcIJTheme", "Light"},
                {"Cyan Light", "com.formdev.flatlaf.intellijthemes.FlatCyanLightIJTheme", "Light"},
                {"Gray", "com.formdev.flatlaf.intellijthemes.FlatGrayIJTheme", "Light"},
                {"Light Flat", "com.formdev.flatlaf.intellijthemes.FlatLightFlatIJTheme", "Light"},
                {"Solarized Light", "com.formdev.flatlaf.intellijthemes.FlatSolarizedLightIJTheme", "Light"},
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
        try {
            currentFontSize = Integer.parseInt(savedSize);
        } catch (NumberFormatException e) {
            currentFontSize = 13;
        }
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

        // ---- 背景菜单 ----
        JMenu bgMenu = new JMenu("背景");
        JMenuItem setBgItem = new JMenuItem("设置背景图片");
        setBgItem.addActionListener(e -> chooseBackgroundImage());
        bgMenu.add(setBgItem);

        JMenuItem clearBgItem = new JMenuItem("清除背景图片");
        clearBgItem.addActionListener(e -> clearBackgroundImage());
        bgMenu.add(clearBgItem);

        bgMenu.addSeparator();

        // 透明度滑块
        JMenu alphaMenu = new JMenu("背景透明度");
        JSlider alphaSlider = new JSlider(0, 100, (int) (backgroundAlpha * 100));
        alphaSlider.setMajorTickSpacing(25);
        alphaSlider.setMinorTickSpacing(5);
        alphaSlider.setPaintTicks(true);
        alphaSlider.setPaintLabels(true);
        alphaSlider.setSnapToTicks(true);
        // 刻度标签
        java.util.Hashtable<Integer, JLabel> labelTable = new java.util.Hashtable<>();
        labelTable.put(0, new JLabel("0%"));
        labelTable.put(25, new JLabel("25%"));
        labelTable.put(50, new JLabel("50%"));
        labelTable.put(75, new JLabel("75%"));
        labelTable.put(100, new JLabel("100%"));
        alphaSlider.setLabelTable(labelTable);
        alphaSlider.setPreferredSize(new Dimension(200, 50));
        alphaSlider.addChangeListener(e -> {
            if (!alphaSlider.getValueIsAdjusting()) {
                backgroundAlpha = alphaSlider.getValue() / 100f;
                config.set("background.alpha", String.valueOf(backgroundAlpha));
                // 刷新背景绘制
                if (backgroundImage != null) {
                    getContentPane().repaint();
                }
            }
        });
        alphaMenu.add(alphaSlider);
        bgMenu.add(alphaMenu);

        bar.add(bgMenu);

        // ---- 视图菜单 ----
        JMenu viewMenu = new JMenu("视图");

        // 面板子菜单（默认不可见，隐藏表头后出现）
        JMenu panelSubMenu = new JMenu("面板");
        panelSubMenu.setVisible(false);
        // 菜单打开时同步选中项到当前 Tab
        panelSubMenu.addMenuListener(new javax.swing.event.MenuListener() {
            @Override public void menuSelected(javax.swing.event.MenuEvent e) {
                int sel = tabbedPane.getSelectedIndex();
                for (int i = 0; i < panelSubMenu.getItemCount(); i++) {
                    JMenuItem mi = panelSubMenu.getItem(i);
                    if (mi instanceof JRadioButtonMenuItem rb) {
                        rb.setSelected(i == sel);
                    }
                }
            }
            @Override public void menuDeselected(javax.swing.event.MenuEvent e) {}
            @Override public void menuCanceled(javax.swing.event.MenuEvent e) {}
        });
        ButtonGroup panelGroup = new ButtonGroup();
        for (int i = 0; i < tabbedPane.getTabCount(); i++) {
            String title = tabbedPane.getTitleAt(i);
            JRadioButtonMenuItem panelItem = new JRadioButtonMenuItem(title);
            if (i == tabbedPane.getSelectedIndex()) {
                panelItem.setSelected(true);
            }
            final int idx = i;
            panelItem.addActionListener(e -> tabbedPane.setSelectedIndex(idx));
            panelGroup.add(panelItem);
            panelSubMenu.add(panelItem);
        }

        JCheckBoxMenuItem hideTabItem = new JCheckBoxMenuItem("隐藏Tab表头");
        hideTabItem.addActionListener(e -> {
            boolean hide = hideTabItem.isSelected();
            if (hide) {
                // 使用自定义 UI：tab area 高度为 0 + 不绘制背景/边框
                tabbedPane.setUI(new javax.swing.plaf.basic.BasicTabbedPaneUI() {
                    @Override
                    protected int calculateTabAreaHeight(int tabPlacement, int horizRunCount, int maxTabHeight) {
                        return 0;
                    }
                    @Override
                    protected void paintTabArea(Graphics g, int tabPlacement, int selectedIndex) {
                        // 不绘制 tab 区域
                    }
                    @Override
                    protected void paintContentBorder(Graphics g, int tabPlacement, int selectedIndex) {
                        // 不绘制内容边框，避免覆盖背景图
                    }
                    @Override
                    public void update(Graphics g, JComponent c) {
                        // 跳过 opaque 检查和背景填充，直接 paint，让背景图透出
                        paint(g, c);
                    }
                });
            } else {
                tabbedPane.updateUI();
            }
            // setUI/updateUI 的内部逻辑（installDefaults / FlatLaf 递归 updateUI）
            // 会重置 tabbedPane 和子组件的 opaque / background，所以需要：
            // 1) 立即设 tabbedPane 透明
            // 2) 延迟到 UI 变更完全落定后再恢复 AI 面板子组件透明
            if (backgroundImage != null) {
                tabbedPane.setOpaque(false);
                tabbedPane.setBackground(null);
                SwingUtilities.invokeLater(() -> {
                    makeAiPanelTransparent();
                    tabbedPane.revalidate();
                    tabbedPane.repaint();
                    getContentPane().revalidate();
                    getContentPane().repaint();
                });
            }
            // 面板子菜单可见性随隐藏状态切换
            panelSubMenu.setVisible(hide);
        });
        viewMenu.add(hideTabItem);
        viewMenu.add(panelSubMenu);

        bar.add(viewMenu);

        // ---- 窗口菜单 ----
        JMenu windowMenu = new JMenu("窗口");
        JCheckBoxMenuItem topItem = new JCheckBoxMenuItem("窗口置顶");
        topItem.addActionListener(e -> setAlwaysOnTop(topItem.isSelected()));
        windowMenu.add(topItem);
        bar.add(windowMenu);

        // ---- 捐助菜单 ----
        JMenu donateMenu = new JMenu("捐助");
        // 用 ax.svg 替换文字爱心图标
        try {
            java.io.InputStream svgStream = getClass().getResourceAsStream("/icons/ax.svg");
            if (svgStream != null) {
                com.formdev.flatlaf.extras.FlatSVGIcon donateIcon = new com.formdev.flatlaf.extras.FlatSVGIcon(svgStream);
                int iconSize = donateMenu.getFont().getSize() + 2;  // 比文字稍大一点点
                donateMenu.setIcon(donateIcon.derive(iconSize, iconSize));
                svgStream.close();
            }
        } catch (Exception ex) {
            // SVG 加载失败就退回到纯文字
            ex.printStackTrace();
        }
        JMenuItem donateItem = new JMenuItem("支持开发者");
        donateItem.addActionListener(e -> showDonateDialog());
        donateMenu.add(donateItem);
        bar.add(donateMenu);

        // ---- 关于菜单 ----
        JMenu aboutMenu = new JMenu("关于");
        JMenuItem aboutItem = new JMenuItem("关于 CoreTools");
        aboutItem.addActionListener(e -> showAboutDialog());
        aboutMenu.add(aboutItem);
        bar.add(aboutMenu);

        return bar;
    }

    /**
     * 切换字体族
     */
    private void switchFontFamily(String family) {
        currentFontFamily = family;
        applyFont();
        config.set("font.family", family);
    }

    /**
     * 切换字号
     */
    private void switchFontSize(int size) {
        currentFontSize = size;
        applyFont();
        config.set("font.size", String.valueOf(size));
    }

    /**
     * 打开颜色选择器切换全局文字颜色
     */
    private void switchTextColor() {
        Color chosen = JColorChooser.showDialog(this, "选择全局文字颜色", currentTextColor);
        if (chosen != null) {
            currentTextColor = chosen;
            NetUtil.TEXT_COLOR = chosen;
            config.set("font.color", String.valueOf(chosen.getRGB()));
            applyTextColor();
        }
    }

    /**
     * 将当前文字颜色应用到全局
     */
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

    /**
     * 递归遍历组件树，强制更新所有文字颜色
     */
    private void updateAllTextColors(Container root, Color color) {
        for (Component c : root.getComponents()) {
            if (c instanceof JLabel
                    || c instanceof JTextComponent
                    || c instanceof JButton
                    || c instanceof JComboBox
                    || c instanceof JTable
                    || c instanceof JTree
                    || c instanceof JList) {
                c.setForeground(color);
            } else if (c instanceof Container) {
                c.setForeground(color);
            }
            if (c instanceof Container) {
                updateAllTextColors((Container) c, color);
            }
        }
    }

    /**
     * 应用当前字体到全局
     */
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

    /**
     * 递归遍历组件树，更新所有手动设置过字体的组件
     */
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

    // ==================== 背景图片 ====================

    /**
     * 打开文件选择器选择背景图片
     */
    private void chooseBackgroundImage() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择背景图片");
        chooser.setFileFilter(new FileNameExtensionFilter(
                "图片文件 (*.jpg, *.jpeg, *.png, *.gif, *.bmp)",
                "jpg", "jpeg", "png", "gif", "bmp"));
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File file = chooser.getSelectedFile();
            try {
                backgroundImage = ImageIO.read(file);
                backgroundImagePath = file.getAbsolutePath();
                applyBackgroundImage();
                config.set("background.image", backgroundImagePath);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this,
                        "无法加载图片: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 清除背景图片
     */
    private void clearBackgroundImage() {
        backgroundImage = null;
        backgroundImagePath = null;
        config.set("background.image", "");
        applyBackgroundImage();
    }

    /**
     * 将背景图片应用到整个界面
     */
    private void applyBackgroundImage() {
        if (backgroundImage != null) {
            // 创建带背景图片的 contentPane
            JPanel bgPanel = new JPanel(new BorderLayout(0, 0)) {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g.create();
                    int w = getWidth(), h = getHeight();
                    if (w > 0 && h > 0) {
                        // 按比例缩放铺满
                        double imgRatio = (double) backgroundImage.getWidth() / backgroundImage.getHeight();
                        double panelRatio = (double) w / h;
                        int drawW, drawH, drawX, drawY;
                        if (imgRatio > panelRatio) {
                            drawH = h;
                            drawW = (int) (h * imgRatio);
                            drawX = (w - drawW) / 2;
                            drawY = 0;
                        } else {
                            drawW = w;
                            drawH = (int) (w / imgRatio);
                            drawX = 0;
                            drawY = (h - drawH) / 2;
                        }
                    // 先画背景图（透明度由用户控制）
                    g2.setComposite(AlphaComposite.SrcOver.derive(backgroundAlpha));
                    g2.drawImage(backgroundImage, drawX, drawY, drawW, drawH, this);
                    // 再画一层半透明暗色遮罩，确保文字可读（遮罩强度与图片透明度互补）
                    int maskAlpha = (int) (160 * (1 - backgroundAlpha * 0.7));
                    g2.setComposite(AlphaComposite.SrcOver.derive(1f));
                    g2.setColor(new Color(0, 0, 0, maskAlpha));
                    g2.fillRect(0, 0, w, h);
                    }
                    g2.dispose();
                }
            };
            bgPanel.setBorder(new EmptyBorder(0, 0, 0, 0));

            // 把原有的子组件迁移过来
            Container oldContent = getContentPane();
            if (oldContent.getComponentCount() > 0) {
                Component[] children = oldContent.getComponents();
                oldContent.removeAll();
                for (Component c : children) {
                    bgPanel.add(c);
                }
            }
            setContentPane(bgPanel);

            // 让 tabbedPane 变透明才能透出背景图
            tabbedPane.setOpaque(false);
            // 只让 AI 对话面板变透明
            makeAiPanelTransparent();
        } else {
            // 恢复 tabbedPane 不透明
            tabbedPane.setOpaque(true);
            // 恢复无背景状态
            Container oldContent = getContentPane();
            JPanel normalPanel = new JPanel(new BorderLayout(0, 0));
            normalPanel.setBorder(new EmptyBorder(0, 0, 0, 0));
            if (oldContent.getComponentCount() > 0) {
                Component[] children = oldContent.getComponents();
                oldContent.removeAll();
                for (Component c : children) {
                    normalPanel.add(c);
                }
            }
            setContentPane(normalPanel);
        }
        SwingUtilities.invokeLater(() -> {
            getRootPane().revalidate();
            getRootPane().repaint();
        });
    }

    /**
     * 递归查找 AiChatPanel 并将其背景设为透明
     */
    private void makeAiPanelTransparent() {
        Component aiPanel = findAiChatPanel(getContentPane());
        if (aiPanel != null) {
            setComponentTreeOpaque(aiPanel, false);
        }
    }

    /**
     * 在组件树中递归查找 AiChatPanel
     */
    private Component findAiChatPanel(Container root) {
        for (Component c : root.getComponents()) {
            if (c instanceof AiChatPanel) {
                return c;
            }
            if (c instanceof Container) {
                Component found = findAiChatPanel((Container) c);
                if (found != null) return found;
            }
        }
        return null;
    }

    /**
     * 弹出捐助对话框，展示微信收款码
     */
    private void showDonateDialog() {
        JDialog dialog = new JDialog(this, "支持 CoreTools", true);
        dialog.setResizable(false);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(20, 25, 20, 25));

        // 顶部文案
        JLabel titleLabel = new JLabel("<html><div style='text-align:center;'>"
                + "<h2 style='margin:0;'>感谢使用 CoreTools</h2>"
                + "<p style='font-size:13px;color:#999;'>软件持续免费，制作不易</p>"
                + "<p style='font-size:13px;'>如果您觉得好用，欢迎扫码支持一杯咖啡 ☕</p>"
                + "<p style='font-size:12px;color:#aaa;'><i>完全自愿，心意到了就好 ❤</i></p>"
                + "</div></html>");
        titleLabel.setHorizontalAlignment(SwingConstants.CENTER);
        panel.add(titleLabel, BorderLayout.NORTH);

        // 收款码图片
        try {
            java.io.InputStream is = getClass().getResourceAsStream("/wx.png");
            if (is != null) {
                BufferedImage qrImage = ImageIO.read(is);
                is.close();
                if (qrImage != null) {
                    // 控制显示大小，不超过 300x300
                    int maxSize = 300;
                    int w = qrImage.getWidth();
                    int h = qrImage.getHeight();
                    double scale = Math.min((double) maxSize / w, (double) maxSize / h);
                    if (scale < 1.0) {
                        w = (int) (w * scale);
                        h = (int) (h * scale);
                    }
                    // 高质量缩放：Graphics2D + 双三次插值，比 SCALE_SMOOTH 清晰很多
                    BufferedImage scaledImg = new BufferedImage(w, h, BufferedImage.TYPE_INT_ARGB);
                    Graphics2D g2d = scaledImg.createGraphics();
                    g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                    g2d.drawImage(qrImage, 0, 0, w, h, null);
                    g2d.dispose();
                    JLabel imageLabel = new JLabel(new ImageIcon(scaledImg));
                    imageLabel.setHorizontalAlignment(SwingConstants.CENTER);
                    imageLabel.setBorder(BorderFactory.createLineBorder(
                            UIManager.getColor("Component.borderColor"), 1));
                    panel.add(imageLabel, BorderLayout.CENTER);
                }
            } else {
                JLabel fallback = new JLabel("收款码图片未找到", SwingConstants.CENTER);
                fallback.setForeground(Color.GRAY);
                panel.add(fallback, BorderLayout.CENTER);
            }
        } catch (IOException e) {
            JLabel fallback = new JLabel("加载图片失败: " + e.getMessage(), SwingConstants.CENTER);
            fallback.setForeground(Color.GRAY);
            panel.add(fallback, BorderLayout.CENTER);
        }

        // 底部按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        btnPanel.add(closeBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * 弹出"关于"对话框，含版本信息和第三方开源库许可声明
     */
    private void showAboutDialog() {
        JDialog dialog = new JDialog(this, "关于 CoreTools", true);
        dialog.setResizable(false);

        JPanel panel = new JPanel(new BorderLayout(10, 10));
        panel.setBorder(new EmptyBorder(20, 25, 15, 25));

        // ---- 顶部：软件信息 ----
        JPanel infoPanel = new JPanel();
        infoPanel.setLayout(new BoxLayout(infoPanel, BoxLayout.Y_AXIS));
        infoPanel.setOpaque(false);

        JLabel titleLabel = new JLabel("CoreTools v1.0.0", SwingConstants.CENTER);
        titleLabel.setFont(titleLabel.getFont().deriveFont(Font.BOLD, 16f));
        titleLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel authorLabel = new JLabel("作者: sunzhenhuan", SwingConstants.CENTER);
        authorLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        JLabel licenseLabel = new JLabel("开源协议: MIT License", SwingConstants.CENTER);
        licenseLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

        infoPanel.add(titleLabel);
        infoPanel.add(Box.createVerticalStrut(8));
        infoPanel.add(authorLabel);
        infoPanel.add(Box.createVerticalStrut(4));
        infoPanel.add(licenseLabel);
        panel.add(infoPanel, BorderLayout.NORTH);

        // ---- 中间：第三方许可（滚动） ----
        String thirdPartyText = "<html><div style='text-align:center;'>"
                + "<h3 style='margin:5px 0;'>第三方开源库声明</h3>"
                + "<table style='font-size:12px;border-collapse:collapse;margin:0 auto;'>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>FlatLaf</td><td style='padding:2px 8px;color:#666;'>Apache 2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>JavaCV / FFmpeg / OpenCV</td><td style='padding:2px 8px;color:#666;'>LGPL / BSD</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>jSerialComm</td><td style='padding:2px 8px;color:#666;'>LGPL 3.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>RSyntaxTextArea</td><td style='padding:2px 8px;color:#666;'>BSD 3-Clause</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>XChart</td><td style='padding:2px 8px;color:#666;'>Apache 2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>MySQL Connector/J</td><td style='padding:2px 8px;color:#666;'>GPL 2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>PostgreSQL JDBC</td><td style='padding:2px 8px;color:#666;'>BSD 2-Clause</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>SQLite JDBC</td><td style='padding:2px 8px;color:#666;'>Apache 2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>Jedis (Redis)</td><td style='padding:2px 8px;color:#666;'>MIT</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>Radiance (Substance)</td><td style='padding:2px 8px;color:#666;'>BSD 3-Clause</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>OSHI</td><td style='padding:2px 8px;color:#666;'>MIT</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>Apache MINA SSHD</td><td style='padding:2px 8px;color:#666;'>Apache-2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>LangChain4j</td><td style='padding:2px 8px;color:#666;'>Apache 2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>Eclipse Paho MQTT</td><td style='padding:2px 8px;color:#666;'>EPL 2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>gRPC / Protobuf</td><td style='padding:2px 8px;color:#666;'>Apache 2.0</td></tr>"
                + "<tr><td style='padding:2px 8px;text-align:left;'>ZXing</td><td style='padding:2px 8px;color:#666;'>Apache 2.0</td></tr>"
                + "</table>"
                + "<p style='margin-top:10px;font-size:12px;color:#999;'>"
                + "感谢以上开源项目的贡献</p>"
                + "</div></html>";

        JLabel tplLabel = new JLabel(thirdPartyText);
        tplLabel.setHorizontalAlignment(SwingConstants.CENTER);

        JScrollPane scrollPane = new JScrollPane(tplLabel);
        scrollPane.setPreferredSize(new Dimension(400, 300));
        scrollPane.setBorder(BorderFactory.createEmptyBorder());
        panel.add(scrollPane, BorderLayout.CENTER);

        // ---- 底部按钮 ----
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 10, 5));
        JButton closeBtn = new JButton("关闭");
        closeBtn.addActionListener(e -> dialog.dispose());
        btnPanel.add(closeBtn);
        panel.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /**
     * 递归将组件树设为透明（opaque = false，清除背景色），以便背景透出
     */
    private void setComponentTreeOpaque(Component comp, boolean opaque) {
        if (comp instanceof JComponent jc) {
            jc.setOpaque(opaque);
            if (!opaque) {
                jc.setBackground(null);
            }
        }
        // JScrollPane 的 viewport 也需要透明
        if (comp instanceof JScrollPane sp) {
            sp.setOpaque(opaque);
            sp.getViewport().setOpaque(opaque);
            if (!opaque) {
                sp.setBackground(null);
                sp.getViewport().setBackground(null);
            }
        }
        if (comp instanceof Container container) {
            for (Component child : container.getComponents()) {
                setComponentTreeOpaque(child, opaque);
            }
        }
    }

}
