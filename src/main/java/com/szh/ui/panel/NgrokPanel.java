package com.szh.ui.panel;

import com.szh.ui.MessageDialog;
import com.szh.utils.NetUtil;
import com.szh.utils.ThreadPoolUtil;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;


/**
 * 内网穿透 + 文件服务器面板（Bore 隧道 — 零认证、零注册）
 * <p>
 * 内置 HTTP 文件服务器，可将本地文件/目录映射为公网可访问的 URL，
 * 供 AI 视频生成等场景使用。
 * </p>
 *
 * <h3>使用流程</h3>
 * <ol>
 *   <li>启动文件服务器（提供本地 HTTP 端口）</li>
 *   <li>启动 Bore 隧道（将本地端口映射到公网 bore.pub）</li>
 *   <li>公网 URL 出现后即可使用</li>
 * </ol>
 */
public class NgrokPanel extends AbstractCommandPanel {

    private static final Logger logger = LogManager.getLogger(NgrokPanel.class);

    // ==================== bore 资源管理 ====================
    /** 提取目录：始终在程序自身目录下（user.dir/bore/） */
    private static final Path BORE_DIR = Paths.get(System.getProperty("user.dir"), "bore");
    /** 已提取文件缓存：资源名 → 绝对路径 */
    private static final Map<String, String> extractedCache = new ConcurrentHashMap<>();

    /** classpath resources/bore/ 下已知的 bore 二进制文件名列表 */
    private static final String[] KNOWN_BORE_RESOURCES = {
        "bore_apple_new", "bore_apple_old",
        "bore_linux_arm64", "bore_linux_x86_64",
        "bore_windows.exe", "bore.exe", "bore"
    };

    // ==================== 共享状态（AiChatPanel 通过此处获取公网 URL） ====================

    /** 当前暴露的公网 URL，未启动时为 null */
    private static volatile String publicUrl;

    /** 隧道进程是否正在运行 */
    private static volatile boolean tunnelRunning;

    public static String getPublicUrl() {
        return tunnelRunning ? publicUrl : null;
    }

    public static boolean isRunning() {
        return tunnelRunning && publicUrl != null && !publicUrl.isBlank();
    }

    // ==================== 文件服务器（供 AiChatPanel 注册本地文件） ====================

    // 字段类型用 Object 避免 jdk.httpserver 模块缺失时类加载失败
    private static Object fileServer;
    private static int fileServerPort = -1;
    private static boolean fileServerRunning;
    private static final Map<String, File> fileMap = new ConcurrentHashMap<>();

    public static boolean isFileServerRunning() {
        return fileServerRunning && fileServer != null;
    }

    public static int getFileServerPort() {
        return fileServerPort;
    }

    /**
     * 获取文件服务器的基础 URL。
     * 如果隧道已启动且有公网 URL，优先返回公网 URL；
     * 否则返回本地 127.0.0.1 地址。
     */
    public static String getFileServerBaseUrl() {
        if (tunnelRunning && publicUrl != null && !publicUrl.isBlank()) {
            return publicUrl;
        }
        return "http://127.0.0.1:" + fileServerPort;
    }

    /** 将本地文件注册到文件服务器，返回可访问的 HTTP URL（由 AiChatPanel 调用） */
    public static String registerFile(File file) {
        if (!isFileServerRunning()) return null;
        String fileId = file.getName() + "_" + Integer.toHexString(file.getAbsolutePath().hashCode());
        fileMap.put(fileId, file);
        return getFileServerBaseUrl() + "/temp/" + fileId;
    }

    /** 根据文件服务器 URL 反向解析回本地文件（用于图片预览），若不是文件服务器注册的 URL 则返回 null */
    public static File resolveFileUrl(String url) {
        if (url == null || !isFileServerRunning()) return null;
        try {
            if (!url.contains("/temp/")) return null;
            String fileId = url.substring(url.indexOf("/temp/") + 6);
            // 去掉可能的查询参数
            int qIdx = fileId.indexOf('?');
            if (qIdx >= 0) fileId = fileId.substring(0, qIdx);
            return fileMap.get(fileId);
        } catch (Exception e) {
            return null;
        }
    }

    // ==================== 目录模式内置 HTTP 文件服务器 ====================

    private static Object dirHttpServer;
    private static File dirHttpServerRoot;
    // ==================== UI 组件 ====================

    // --- 文件服务器 ---
    private JTextField fsPortField;
    private JButton fsStartBtn;
    private JButton fsStopBtn;
    private JLabel fsStatusLabel;

    // --- 二进制路径 ---
    private JComboBox<String> binaryCombo;
    private DefaultComboBoxModel<String> binaryComboModel;
    private JButton browseBinaryBtn;
    private JButton downloadBtn;
    // bore 文件夹中扫描到的架构名称 → 绝对路径
    private Map<String, String> boreArchToPath; // 在 initPanel() 中初始化（因为父类构造函数会调用 initPanel）

    // --- 模式切换 ---
    private JRadioButton portModeBtn;
    private JRadioButton dirModeBtn;
    private JPanel portModePanel;
    private JPanel dirModePanel;

    // --- 端口模式 ---
    private JTextField portField;
    private JButton syncFsPortBtn;

    // --- 目录模式 ---
    private JTextField dirPathField;
    private JButton browseDirBtn;
    private JTextField dirPortField;

    // --- 目标地址（端口和目录模式共用）---
    private JTextField hostField;

    // --- 隧道控制 ---
    private JButton tunnelStartBtn;
    private JButton tunnelStopBtn;

    // --- 状态 ---
    private JLabel tunnelStatusLabel;
    private JLabel publicUrlLabel;
    private JLabel dirBrowseUrlLabel;
    private JTextArea logArea;

    private Process tunnelProcess;
    private java.util.concurrent.Future<?> outputReaderFuture;
    private volatile boolean intentionalStop; // 是否由用户主动停止（与意外退出区分）
    private int restartCount; // 当前自动重试次数
    private static final int MAX_AUTO_RESTART = 5; // 最大自动重试次数

    // ==================== 构造 ====================

    public NgrokPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        try {
            initPanelImpl();
        } catch (Throwable t) {
            logger.error("NgrokPanel 初始化失败", t);
            // 兜底：至少显示一个错误提示面板，避免完全空白
            setLayout(new BorderLayout());
            JLabel errLabel = new JLabel("<html><b>NgrokPanel 初始化失败:</b><br>" + t.toString().replace("\n", "<br>") + "</html>");
            errLabel.setForeground(Color.RED);
            errLabel.setBorder(BorderFactory.createEmptyBorder(20, 20, 20, 20));
            add(errLabel, BorderLayout.CENTER);
        }
    }

    private void initPanelImpl() {
        setLayout(new BorderLayout(8, 8));
        JPanel northPanel = new JPanel();
        northPanel.setLayout(new BoxLayout(northPanel, BoxLayout.Y_AXIS));

        // ======== 使用步骤（顶部水平居中） ========
        JLabel stepsLabel = new JLabel("使用步骤： ① 启动文件服务器 → ② 选择架构 → ③ 启动 Bore 隧道 → ④ 复制公网URL即可使用");
        stepsLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        stepsLabel.setForeground(new Color(255, 145, 0));
        stepsLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
        stepsLabel.setBorder(BorderFactory.createEmptyBorder(4, 0, 4, 0));
        northPanel.add(stepsLabel);
        northPanel.add(Box.createVerticalStrut(4));

        // ======== 文件服务器面板 ========
        JPanel fsPanel = new JPanel(new GridBagLayout());
        fsPanel.setBorder(new TitledBorder("文件服务器 — 提供本地图片/文件的 HTTP 访问"));
        fsPanel.setAlignmentX(CENTER_ALIGNMENT);

        fsPanel.add(new JLabel("端口："), gbc(0, 0));
        fsPortField = new JTextField("18080", 8); // 默认端口，避免 findFreePort() 在构造期阻塞 EDT
        fsPanel.add(fsPortField, gbc(1, 0));

        fsStartBtn = new JButton("启动");
        fsStartBtn.setBackground(new Color(76, 175, 80));
        fsStartBtn.setForeground(Color.WHITE);
        fsStartBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        fsStartBtn.addActionListener(e -> startFileServer());
        fsPanel.add(fsStartBtn, gbc(2, 0));

        fsStopBtn = new JButton("停止");
        fsStopBtn.setBackground(new Color(244, 67, 54));
        fsStopBtn.setForeground(Color.WHITE);
        fsStopBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        fsStopBtn.setEnabled(false);
        fsStopBtn.addActionListener(e -> stopFileServer());
        fsPanel.add(fsStopBtn, gbc(3, 0));

        fsStatusLabel = new JLabel("[未启动]");
        fsStatusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        fsStatusLabel.setForeground(Color.GRAY);
        fsPanel.add(fsStatusLabel, gbc(4, 0));

        northPanel.add(fsPanel);

        // ======== 隧道配置面板 ========
        JPanel configPanel = new JPanel(new GridBagLayout());
        configPanel.setBorder(new TitledBorder("隧道配置 — Bore（零认证、零注册）"));
        configPanel.setAlignmentX(CENTER_ALIGNMENT);

        int row = 0;

        // 下载按钮
        JPanel providerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        providerPanel.setOpaque(false);
        providerPanel.add(new JLabel("穿透工具：Bore"));

        downloadBtn = new JButton("下载 Bore");
        downloadBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        downloadBtn.setToolTipText("从 GitHub 下载对应系统的 bore 二进制（零认证）");
        downloadBtn.addActionListener(e -> downloadBore());
        providerPanel.add(downloadBtn);


        configPanel.add(providerPanel, gbc(0, row, 4));
        row++;

        // 二进制架构选择（扫描 bore/ 文件夹，默认选中当前系统）
        configPanel.add(new JLabel("架构："), gbc(0, row));
        boreArchToPath = new LinkedHashMap<>(); // 父类构造函数中 initPanel() 回调时字段初始化器尚未执行
        binaryComboModel = new DefaultComboBoxModel<>();
        binaryCombo = new JComboBox<>(binaryComboModel);
        scanBoreBinaries();
        binaryCombo.setPreferredSize(new Dimension(280, 25));
        configPanel.add(binaryCombo, gbc(1, row, 2));
        browseBinaryBtn = new JButton("浏览...");
        browseBinaryBtn.addActionListener(e -> browseBinary());
        configPanel.add(browseBinaryBtn, gbc(3, row));
        row++;

        // ---- 模式切换 ----
        configPanel.add(new JLabel("模式："), gbc(0, row));
        portModeBtn = new JRadioButton("转发端口", true);
        dirModeBtn = new JRadioButton("暴露目录");
        ButtonGroup modeGroup = new ButtonGroup();
        modeGroup.add(portModeBtn);
        modeGroup.add(dirModeBtn);

        JPanel modeBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 0));
        modeBtnPanel.setOpaque(false);
        modeBtnPanel.add(portModeBtn);
        modeBtnPanel.add(dirModeBtn);
        configPanel.add(modeBtnPanel, gbc(1, row, 3));
        row++;

        // ---- 端口模式面板 ----
        portModePanel = new JPanel(new GridBagLayout());
        portModePanel.setOpaque(false);
        portModePanel.add(new JLabel("转发端口："), gbc(0, 0));
        portField = new JTextField(8);
        portModePanel.add(portField, gbc(1, 0));
        syncFsPortBtn = new JButton("同步文件服务器端口");
        syncFsPortBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        syncFsPortBtn.addActionListener(e -> syncFileServerPort());
        portModePanel.add(syncFsPortBtn, gbc(2, 0));
        configPanel.add(portModePanel, gbc(0, row, 4));

        // ---- 目录模式面板 ----
        dirModePanel = new JPanel(new GridBagLayout());
        dirModePanel.setOpaque(false);
        dirModePanel.setVisible(false);

        dirModePanel.add(new JLabel("本地目录："), gbc(0, 0));
        dirPathField = new JTextField(25);
        dirModePanel.add(dirPathField, gbc(1, 0, 1));
        browseDirBtn = new JButton("浏览...");
        browseDirBtn.addActionListener(e -> browseDirectory());
        dirModePanel.add(browseDirBtn, gbc(2, 0));

        dirModePanel.add(new JLabel("服务器端口："), gbc(0, 1));
        dirPortField = new JTextField("8080", 8);
        dirModePanel.add(dirPortField, gbc(1, 1));

        configPanel.add(dirModePanel, gbc(0, row, 4));
        row++;

        // ---- 目标地址（供暴露局域网内其他服务）----
        configPanel.add(new JLabel("目标地址："), gbc(0, row));
        hostField = new JTextField("127.0.0.1", 15);
        hostField.setToolTipText("要暴露的服务所在 IP，127.0.0.1=本机，也可填局域网内其他机器 IP");
        configPanel.add(hostField, gbc(1, row, 2));
        row++;

        // 模式切换事件
        portModeBtn.addActionListener(e -> {
            portModePanel.setVisible(true);
            dirModePanel.setVisible(false);
            configPanel.revalidate();
            configPanel.repaint();
        });
        dirModeBtn.addActionListener(e -> {
            portModePanel.setVisible(false);
            dirModePanel.setVisible(true);
            configPanel.revalidate();
            configPanel.repaint();
        });

        // ---- 隧道启动/停止 ----
        tunnelStartBtn = new JButton("启动 Bore");
        tunnelStartBtn.setBackground(new Color(76, 175, 80));
        tunnelStartBtn.setForeground(Color.WHITE);
        tunnelStartBtn.addActionListener(e -> startTunnel());

        tunnelStopBtn = new JButton("停止 Bore");
        tunnelStopBtn.setBackground(new Color(244, 67, 54));
        tunnelStopBtn.setForeground(Color.WHITE);
        tunnelStopBtn.setEnabled(false);
        tunnelStopBtn.addActionListener(e -> stopTunnel());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 12, 0));
        btnPanel.setOpaque(false);
        btnPanel.add(tunnelStartBtn);
        btnPanel.add(tunnelStopBtn);

        GridBagConstraints btnGbc = gbc(0, row + 1, 4);
        btnGbc.insets = new Insets(10, 8, 5, 8);
        configPanel.add(btnPanel, btnGbc);

        northPanel.add(configPanel);

        // 安全软件误报提示
        JLabel defenderHint = new JLabel("⚠ Bore 启动时可能被 Windows Defender 误拦，如弹出提示请选择允许");
        defenderHint.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        defenderHint.setForeground(new Color(255, 145, 0));
        defenderHint.setAlignmentX(Component.CENTER_ALIGNMENT);
        defenderHint.setBorder(BorderFactory.createEmptyBorder(4, 0, 2, 0));
        northPanel.add(defenderHint);

        // 北部区域可滚动
        JScrollPane northScroll = new JScrollPane(northPanel);
        northScroll.setBorder(null);
        northScroll.setHorizontalScrollBarPolicy(JScrollPane.HORIZONTAL_SCROLLBAR_NEVER);
        northScroll.getVerticalScrollBar().setUnitIncrement(16);
        northScroll.setMinimumSize(new Dimension(200, 150));

        // ======== 中间：状态 ========
        JPanel statusPanel = new JPanel(new BorderLayout(8, 4));
        statusPanel.setBorder(new TitledBorder("隧道状态"));
        statusPanel.setPreferredSize(new Dimension(600, 60));
        statusPanel.setMinimumSize(new Dimension(200, 50));

        tunnelStatusLabel = new JLabel("未启动");
        tunnelStatusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
        statusPanel.add(tunnelStatusLabel, BorderLayout.NORTH);

        dirBrowseUrlLabel = new JLabel(" ");
        dirBrowseUrlLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        dirBrowseUrlLabel.setForeground(Color.GRAY);
        statusPanel.add(dirBrowseUrlLabel, BorderLayout.CENTER);

        publicUrlLabel = new JLabel(" ");
        publicUrlLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        publicUrlLabel.setForeground(new Color(33, 150, 243));
        publicUrlLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        publicUrlLabel.setToolTipText("点击复制公网 URL");
        publicUrlLabel.addMouseListener(new java.awt.event.MouseAdapter() {
            @Override
            public void mouseClicked(java.awt.event.MouseEvent e) {
                String url = publicUrlLabel.getText();
                if (url != null && !url.isBlank() && !url.equals(" ")) {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new java.awt.datatransfer.StringSelection(url), null);
                    appendLog("已复制到剪贴板: " + url);
                }
            }
        });
        statusPanel.add(publicUrlLabel, BorderLayout.SOUTH);

        // ======== 底部：日志 ========
        JPanel logPanel = new JPanel(new BorderLayout());
        logPanel.setBorder(new TitledBorder("运行日志"));

        logArea = new JTextArea(6, 50);
        logArea.setEditable(false);
        logArea.setFont(NetUtil.FONT_TEXT);
        logArea.setBackground(UIManager.getColor("TextArea.background"));
        JScrollPane logScroll = new JScrollPane(logArea);
        logScroll.setVerticalScrollBarPolicy(JScrollPane.VERTICAL_SCROLLBAR_ALWAYS);
        logPanel.add(logScroll, BorderLayout.CENTER);

        JButton clearLogBtn = new JButton("清空日志");
        clearLogBtn.addActionListener(e -> logArea.setText(""));
        JPanel logBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        logBtnPanel.setOpaque(false);
        logBtnPanel.add(clearLogBtn);
        logPanel.add(logBtnPanel, BorderLayout.SOUTH);

        // 状态+日志 合并到底部面板
        JPanel bottomPanel = new JPanel(new BorderLayout());
        bottomPanel.add(statusPanel, BorderLayout.NORTH);
        bottomPanel.add(logPanel, BorderLayout.CENTER);

        // 用 JSplitPane 分割配置区和状态/日志区，确保都可见
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, northScroll, bottomPanel);
        splitPane.setDividerLocation(260);
        splitPane.setResizeWeight(0.5);
        add(splitPane, BorderLayout.CENTER);

        // JVM 退出时自动停止服务器（ShutdownHook 必须用平台线程，虚拟线程在 JVM 关闭时无法调度）
        Runtime.getRuntime().addShutdownHook(new Thread(NgrokPanel::shutdownAll, "file-server-shutdown"));
    }

    // ==================== 文件服务器 ====================

    /** 启动文件服务器 */
    private void startFileServer() {
        if (fileServerRunning) {
            appendLog("警告: 文件服务器已在运行中");
            return;
        }
        try {
            int port = Integer.parseInt(fsPortField.getText().trim());
            if (port < 1 || port > 65535) throw new NumberFormatException();
            fileServerPort = port;
        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(this, "请输入有效的端口号 (1-65535)", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        try {
            com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                    new InetSocketAddress(fileServerPort), 0);
            // 用匿名内部类而非 lambda：避免 lambda 生成合成方法被 getDeclaredMethods 扫描触发 NoClassDefFoundError
            final com.sun.net.httpserver.HttpHandler fileHandler = new com.sun.net.httpserver.HttpHandler() {
                @Override
                public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                    String path = exchange.getRequestURI().getPath();
                    String fileId = path.substring(path.lastIndexOf('/') + 1);
                    File f = fileMap.get(fileId);
                    if (f == null || !f.isFile()) {
                        String resp = "{\"error\":\"file not found\"}";
                        byte[] data = resp.getBytes(StandardCharsets.UTF_8);
                        exchange.sendResponseHeaders(404, data.length);
                        try (OutputStream os = exchange.getResponseBody()) {
                            os.write(data);
                        }
                        return;
                    }
                    String name = f.getName().toLowerCase();
                    String contentType = name.endsWith(".png") ? "image/png"
                            : name.endsWith(".gif") ? "image/gif"
                            : name.endsWith(".bmp") ? "image/bmp"
                            : name.endsWith(".webp") ? "image/webp"
                            : "image/jpeg";
                    exchange.getResponseHeaders().set("Content-Type", contentType);
                    exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                    exchange.sendResponseHeaders(200, f.length());
                    try (OutputStream os = exchange.getResponseBody()) {
                        Files.copy(f.toPath(), os);
                    }
                }
            };
            server.createContext("/temp/", fileHandler);
            server.setExecutor(ThreadPoolUtil.getVirtualExecutor());
            server.start();
            fileServer = server;
            fileServerRunning = true;

            // 自动同步到隧道端口输入框
            syncFileServerPort();

            fsStartBtn.setEnabled(false);
            fsStopBtn.setEnabled(true);
            fsPortField.setEnabled(false);
            fsStatusLabel.setText("[运行中] http://127.0.0.1:" + fileServerPort);
            fsStatusLabel.setForeground(new Color(76, 175, 80));
            appendLog("文件服务器已启动: http://127.0.0.1:" + fileServerPort);
        } catch (Throwable e) {
            logger.error("启动文件服务器失败 port={}", fileServerPort, e);
            appendLog("失败: 启动文件服务器失败: " + e.getMessage());
            MessageDialog.error("启动文件服务器失败: " + e.getMessage());
        }
    }

    /** 停止文件服务器 */
    private void stopFileServer() {
        if (fileServer != null) {
            ((com.sun.net.httpserver.HttpServer) fileServer).stop(0);
            fileServer = null;
        }
        fileServerRunning = false;
        fileMap.clear();

        fsStartBtn.setEnabled(true);
        fsStopBtn.setEnabled(false);
        fsPortField.setEnabled(true);
        fsStatusLabel.setText("[已停止]");
        fsStatusLabel.setForeground(Color.GRAY);
        appendLog("文件服务器已停止");
    }

    /** 同步文件服务器端口到隧道端口输入框 */
    private void syncFileServerPort() {
        if (fileServerRunning && portField != null) {
            portField.setText(String.valueOf(fileServerPort));
        }
    }

    /** JVM 退出时关闭所有服务 */
    private static void shutdownAll() {
        if (fileServer != null) {
            ((com.sun.net.httpserver.HttpServer) fileServer).stop(0);
            fileServer = null;
        }
        if (dirHttpServer != null) {
            ((com.sun.net.httpserver.HttpServer) dirHttpServer).stop(0);
            dirHttpServer = null;
        }
    }

    // ==================== 路径查找 ====================

    /** 扫描 bore 二进制并填充下拉列表（用 getResourceAsStream 检测 classpath 中存在哪些） */
    private void scanBoreBinaries() {
        boreArchToPath.clear();
        binaryComboModel.removeAllElements();

        for (String name : KNOWN_BORE_RESOURCES) {
            try (InputStream in = NgrokPanel.class.getResourceAsStream("/bore/" + name)) {
                if (in != null) {
                    String label = mapArchLabel(name);
                    boreArchToPath.put(label, name);
                    binaryComboModel.addElement(label);
                }
            } catch (Exception ignored) {}
        }

        if (binaryComboModel.getSize() == 0) {
            binaryComboModel.addElement("未检测到 - 请点[浏览...]选择");
        }

        String savedArch = loadSavedArch();
        if (savedArch != null && boreArchToPath.containsKey(savedArch)) {
            binaryComboModel.setSelectedItem(savedArch);
        } else {
            String autoLabel = autoSelectArchForCurrentSystem();
            if (autoLabel != null) {
                binaryComboModel.setSelectedItem(autoLabel);
            } else if (binaryComboModel.getSize() > 0) {
                binaryComboModel.setSelectedItem(binaryComboModel.getElementAt(0));
            }
        }
    }

    /** 从 classpath 提取 bore 二进制到程序目录，返回绝对路径 */
    private static synchronized String extractBoreIfNeeded(String resourceName) {
        String cached = extractedCache.get(resourceName);
        if (cached != null && Files.exists(Paths.get(cached))) return cached;

        try {
            Files.createDirectories(BORE_DIR);
            Path target = BORE_DIR.resolve(resourceName);
            try (InputStream in = NgrokPanel.class.getResourceAsStream("/bore/" + resourceName)) {
                if (in == null) return "";
                Files.copy(in, target, StandardCopyOption.REPLACE_EXISTING);
                target.toFile().setExecutable(true);
                String abs = target.toString();
                extractedCache.put(resourceName, abs);
                // Windows Defender 可能误拦，尝试加入排除列表
                addDefenderExclusion(BORE_DIR.toFile());
                return abs;
            }
        } catch (IOException e) {
            logger.error("提取 bore 失败: {}", resourceName, e);
            return "";
        }
    }

    /** 以管理员权限将目录加入 Windows Defender 排除列表 */
    private static void addDefenderExclusion(File dir) {
        try {
            String os = System.getProperty("os.name").toLowerCase();
            if (!os.contains("win")) return;
            String cmd = "Start-Process powershell -Verb RunAs -WindowStyle Hidden -ArgumentList 'Add-MpPreference -ExclusionPath \"" + dir.getAbsolutePath() + "\"'";
            new ProcessBuilder("powershell", "-Command", cmd)
                    .redirectErrorStream(true)
                    .start();
        } catch (Exception ignored) {}
    }

    private static String mapArchLabel(String fileName) {
        String lower = fileName.toLowerCase();
        if (lower.contains("apple_new") || (lower.contains("apple") && lower.contains("new")))
            return "macOS (Apple Silicon) - " + fileName;
        if (lower.contains("apple_old") || (lower.contains("apple") && lower.contains("old")))
            return "macOS (Intel) - " + fileName;
        if (lower.contains("arm64") || lower.contains("aarch64"))
            return "Linux ARM64 - " + fileName;
        if (lower.contains("x64") || lower.contains("x86_64") || lower.contains("amd64"))
            return "Linux x86_64 - " + fileName;
        if (lower.contains("win") || lower.endsWith(".exe"))
            return "Windows - " + fileName;
        return fileName;
    }

    /** 用 System.getProperty 检测当前系统架构，从已有 bore 二进制中匹配最合适的项 */
    private String autoSelectArchForCurrentSystem() {
        try {
            String osName = System.getProperty("os.name").toLowerCase();
            String osArch = System.getProperty("os.arch").toLowerCase();

            java.util.function.Predicate<String> matcher;
            if (osName.contains("win")) {
                matcher = label -> label.startsWith("Windows - ");
            } else if (osName.contains("mac") || osName.contains("darwin")) {
                if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                    matcher = label -> label.startsWith("macOS (Apple Silicon) - ");
                } else {
                    matcher = label -> label.startsWith("macOS (Intel) - ");
                }
            } else {
                if (osArch.contains("aarch64") || osArch.contains("arm64")) {
                    matcher = label -> label.startsWith("Linux ARM64 - ");
                } else {
                    matcher = label -> label.startsWith("Linux x86_64 - ");
                }
            }

            for (String label : boreArchToPath.keySet()) {
                if (matcher.test(label)) return label;
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 获取当前选中架构的 bore 二进制绝对路径 */
    private String getSelectedBorePath() {
        Object selected = binaryComboModel.getSelectedItem();
        if (selected == null) return "";
        String label = selected.toString();
        String resourceName = boreArchToPath.get(label);
        if (resourceName != null) {
            // 从 classpath 提取到程序目录
            return extractBoreIfNeeded(resourceName);
        }
        // 浏览添加的自定义路径
        if (new File(label).exists()) return label;
        return "";
    }

    private void browseBinary() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择 bore 可执行文件");
        chooser.setFileHidingEnabled(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            String absPath = chooser.getSelectedFile().getAbsolutePath();
            String label = "自定义: " + absPath;
            boreArchToPath.put(label, absPath);
            binaryComboModel.addElement(label);
            binaryComboModel.setSelectedItem(label);
        }
    }

    private void browseDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择要暴露的目录");
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setFileHidingEnabled(false);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            dirPathField.setText(chooser.getSelectedFile().getAbsolutePath());
        }
    }

    private static int findFreePort() {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        } catch (Exception ignored) {
            return 18080;
        }
    }

    // ==================== 下载 Bore ====================

    private void downloadBore() {
        try {
            Desktop.getDesktop().browse(new URI("https://github.com/ekzhang/bore/releases"));
            appendLog("已打开 bore 下载页面，请下载 bore-vX.X.X-x86_64-pc-windows-msvc.zip 并解压");
        } catch (Exception e) {
            logger.warn("打开 bore 下载页面失败: {}", e.getMessage());
            appendLog("无法打开浏览器: " + e.getMessage());
            JOptionPane.showMessageDialog(this,
                    "请手动访问:\nhttps://github.com/ekzhang/bore/releases",
                    "下载 Bore", JOptionPane.INFORMATION_MESSAGE);
        }
    }

    // ==================== 目录模式 HTTP 文件服务器 ====================

    private void startDirHttpServer(File rootDir, int port) throws Exception {
        if (dirHttpServer != null) stopDirHttpServer();

        dirHttpServerRoot = rootDir;

        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(
                new InetSocketAddress(port), 0);
        // 用匿名内部类而非 lambda：避免 lambda 生成合成方法被 getDeclaredMethods 扫描触发 NoClassDefFoundError
        final File finalRootDir = rootDir;
        final com.sun.net.httpserver.HttpHandler dirHandler = new com.sun.net.httpserver.HttpHandler() {
            @Override
            public void handle(com.sun.net.httpserver.HttpExchange exchange) throws IOException {
                String path = exchange.getRequestURI().getPath();
                if (path == null || path.isEmpty() || "/".equals(path)) path = "/";

                File targetFile;
                if ("/".equals(path)) {
                    try {
                        sendDirectoryListing(exchange, finalRootDir, "");
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return;
                } else {
                    String relativePath = path.startsWith("/") ? path.substring(1) : path;
                    targetFile = new File(finalRootDir, relativePath);
                    if (!targetFile.getCanonicalPath().startsWith(finalRootDir.getCanonicalPath())) {
                        exchange.sendResponseHeaders(403, -1);
                        return;
                    }
                }

                if (!targetFile.exists()) {
                    String resp = "<html><body><h1>404 Not Found</h1><p>" + NetUtil.escapeHtml(path) + "</p></body></html>";
                    byte[] data = resp.getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
                    exchange.sendResponseHeaders(404, data.length);
                    try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
                    return;
                }

                if (targetFile.isDirectory()) {
                    String relPath = finalRootDir.toURI().relativize(targetFile.toURI()).getPath();
                    try {
                        sendDirectoryListing(exchange, finalRootDir, relPath);
                    } catch (Exception e) {
                        throw new RuntimeException(e);
                    }
                    return;
                }

                String contentType = NetUtil.getContentType(targetFile.getName());
                exchange.getResponseHeaders().set("Content-Type", contentType);
                exchange.getResponseHeaders().set("Access-Control-Allow-Origin", "*");
                exchange.sendResponseHeaders(200, targetFile.length());
                try (OutputStream os = exchange.getResponseBody()) {
                    Files.copy(targetFile.toPath(), os);
                }
            }
        };
        server.createContext("/", dirHandler);
        server.setExecutor(ThreadPoolUtil.getVirtualExecutor());
        server.start();
        dirHttpServer = server;
    }

    // 参数改用 Object 避免 getDeclaredMethods 扫描时触发 jdk.httpserver 模块缺失
    @SuppressWarnings("unchecked")
    private static void sendDirectoryListing(Object exchangeObj, File rootDir, String relativePath) throws Exception {
        com.sun.net.httpserver.HttpExchange exchange = (com.sun.net.httpserver.HttpExchange) exchangeObj;
        File dir = "/".equals(relativePath) || relativePath.isEmpty() ? rootDir : new File(rootDir, relativePath);
        StringBuilder html = new StringBuilder(1024);
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'>");
        html.append("<title>").append(NetUtil.escapeHtml(relativePath.isEmpty() ? "/" : relativePath)).append("</title>");
        html.append("<style>");
        html.append("body{font-family:'Segoe UI',sans-serif;max-width:900px;margin:20px auto;padding:0 20px;background:#f5f5f5;}");
        html.append("h1{color:#333;border-bottom:2px solid #4CAF50;padding-bottom:8px;}");
        html.append("table{width:100%;border-collapse:collapse;background:#fff;box-shadow:0 2px 8px rgba(0,0,0,.1);}");
        html.append("th{background:#4CAF50;color:#fff;padding:10px 12px;text-align:left;}");
        html.append("td{padding:8px 12px;border-bottom:1px solid #eee;}");
        html.append("tr:hover{background:#f9f9f9;}");
        html.append("a{color:#2196F3;text-decoration:none;}a:hover{text-decoration:underline;}");
        html.append(".dir{font-weight:bold;}");
        html.append("</style></head><body>");

        html.append("<h1>").append(NetUtil.escapeHtml(relativePath.isEmpty() ? "/" : relativePath)).append("</h1>");
        html.append("<p style='color:#888;margin-bottom:16px;'>目录: <code>").append(NetUtil.escapeHtml(rootDir.getAbsolutePath())).append("</code></p>");
        html.append("<table><tr><th>名称</th><th>大小</th><th>修改时间</th></tr>");

        if (!relativePath.isEmpty() && !"/".equals(relativePath)) {
            String parentPath = relativePath.contains("/")
                    ? relativePath.substring(0, relativePath.lastIndexOf('/'))
                    : "";
            String parentHref = parentPath.isEmpty() ? "/" : "/" + parentPath + "/";
            html.append("<tr><td class='dir'><a href='").append(parentHref).append("'>..</a></td><td>-</td><td>-</td></tr>");
        }

        File[] files = dir.listFiles();
        if (files != null) {
            java.util.Arrays.sort(files, (a, b) -> {
                if (a.isDirectory() && !b.isDirectory()) return -1;
                if (!a.isDirectory() && b.isDirectory()) return 1;
                return a.getName().compareToIgnoreCase(b.getName());
            });

            for (File f : files) {
                String name = f.getName();
                String href = "/".equals(relativePath) || relativePath.isEmpty()
                        ? "/" + name : "/" + relativePath + "/" + name;
                if (f.isDirectory()) href += "/";

                String icon = f.isDirectory() ? "[DIR]" : getFileIcon(name);
                String size = f.isDirectory() ? "-" : NetUtil.formatFileSize(f.length());
                String time = java.time.LocalDateTime.ofInstant(
                        java.time.Instant.ofEpochMilli(f.lastModified()),
                        java.time.ZoneId.systemDefault())
                        .format(java.time.format.DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm"));

                html.append("<tr><td class='dir'><a href='").append(href).append("'>")
                        .append(icon).append(" ").append(NetUtil.escapeHtml(name))
                        .append("</a></td><td>").append(size).append("</td><td>").append(time).append("</td></tr>");
            }
        }

        html.append("</table>");
        html.append("<p style='color:#aaa;margin-top:20px;font-size:12px;'>Powered by CoreTools Tunnel Panel (Bore)</p>");
        html.append("</body></html>");

        byte[] data = html.toString().getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "text/html; charset=utf-8");
        exchange.sendResponseHeaders(200, data.length);
        try (OutputStream os = exchange.getResponseBody()) { os.write(data); }
    }

    private static String getFileIcon(String name) {
        String n = name.toLowerCase();
        if (n.matches(".*\\.(jpg|jpeg|png|gif|bmp|webp)$")) return "[IMG]";
        if (n.matches(".*\\.(mp4|avi|mov|mkv|webm)$")) return "[VID]";
        if (n.matches(".*\\.(mp3|wav|flac|ogg)$")) return "[AUD]";
        if (n.endsWith(".pdf")) return "[PDF]";
        if (n.matches(".*\\.(zip|rar|7z|tar|gz)$")) return "[ZIP]";
        if (n.matches(".*\\.(txt|md|log)$")) return "[TXT]";
        if (n.matches(".*\\.(html|htm|css|js)$")) return "[WEB]";
        if (n.matches(".*\\.(java|py|c|cpp|go|rs)$")) return "[SRC]";
        if (n.matches(".*\\.(json|xml|yaml|yml)$")) return "[CFG]";
        return "[FILE]";
    }

    private void stopDirHttpServer() {
        if (dirHttpServer != null) {
            ((com.sun.net.httpserver.HttpServer) dirHttpServer).stop(1);
            dirHttpServer = null;
            dirHttpServerRoot = null;
        }
    }

    // ==================== 启动 / 停止隧道 ====================

    private void startTunnel() {
        String binaryPath = getSelectedBorePath();

        if (binaryPath.isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先选择 bore 架构或浏览指定 bore 执行文件", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        if (tunnelRunning) {
            appendLog("警告: 隧道已在运行中，请先停止");
            return;
        }

        boolean isDirMode = dirModeBtn.isSelected();
        int port;

        if (isDirMode) {
            String dirPath = dirPathField.getText().trim();
            if (dirPath.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请选择要暴露的本地目录", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            File dir = new File(dirPath);
            if (!dir.isDirectory()) {
                JOptionPane.showMessageDialog(this, "目录不存在: " + dirPath, "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                port = Integer.parseInt(dirPortField.getText().trim());
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "请输入有效的端口号 (1-65535)", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                startDirHttpServer(dir, port);
                appendLog("目录服务器已启动: http://127.0.0.1:" + port);
                appendLog("   根目录: " + dir.getAbsolutePath());
                SwingUtilities.invokeLater(() -> dirBrowseUrlLabel.setText("本地浏览: http://127.0.0.1:" + port));
            } catch (Throwable e) {
                logger.error("启动目录 HTTP 服务器失败 port={}", port, e);
                appendLog("启动目录服务器失败: " + e.getMessage());
                MessageDialog.error("启动目录服务器失败: " + e.getMessage());
                return;
            }
        } else {
            try {
                port = Integer.parseInt(portField.getText().trim());
                if (port < 1 || port > 65535) throw new NumberFormatException();
            } catch (NumberFormatException e) {
                JOptionPane.showMessageDialog(this, "请输入有效的端口号 (1-65535)", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            dirBrowseUrlLabel.setText(" ");
        }

        // 重置停止标识 & 重试计数
        intentionalStop = false;
        restartCount = 0;

        // 禁用 UI
        setTunnelUiEnabled(false);
        tunnelStatusLabel.setText("正在启动...");
        tunnelStatusLabel.setForeground(Color.ORANGE);

        final String targetHost = hostField.getText().trim().isEmpty() ? "127.0.0.1" : hostField.getText().trim();

        appendLogSeparator();
        appendLog("正在启动 bore local " + port + " --local-host " + targetHost + " --to bore.pub ...");

        final int finalPort = port;
        final boolean finalIsDirMode = isDirMode;

        ThreadPoolUtil.submitPlatform(() -> {
            try {
                ProcessBuilder pb = new ProcessBuilder(binaryPath, "local", String.valueOf(finalPort), "--local-host", targetHost, "--to", "bore.pub");
                pb.redirectErrorStream(true);
                tunnelProcess = pb.start();

                outputReaderFuture = ThreadPoolUtil.submitPlatform(() -> {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(tunnelProcess.getInputStream(), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            final String logLine = line;
                            SwingUtilities.invokeLater(() -> {
                                appendLog(logLine);
                                parseBoreUrl(logLine);
                            });
                        }
                    } catch (Exception e) {
                        if (!"Stream closed".equals(e.getMessage())) {
                            logger.error("Bore 隧道输出读取异常", e);
                            SwingUtilities.invokeLater(() -> appendLog("读取输出异常: " + e.getMessage()));
                        }
                    }
                });

                int exitCode = tunnelProcess.waitFor();

                // 进程退出处理：区分用户主动停止 vs 意外退出
                SwingUtilities.invokeLater(() -> {
                    if (intentionalStop) {
                        // 用户主动停止
                        if (finalIsDirMode) { stopDirHttpServer(); appendLog("目录服务器已停止"); }
                        resetTunnelAfterStop(exitCode);
                    } else {
                        // 意外退出 → 自动重连
                        appendLog("隧道意外断开 (exit=" + exitCode + ")，尝试自动重连...");
                        tunnelProcess = null;
                        outputReaderFuture = null;
                        autoRestartTunnel(finalPort, targetHost, finalIsDirMode, binaryPath);
                    }
                });
            } catch (Exception e) {
                logger.error("Bore 隧道启动异常", e);
                SwingUtilities.invokeLater(() -> {
                    if (!intentionalStop) {
                        // 启动过程中的异常也尝试重连
                        appendLog("隧道启动异常: " + e.getMessage() + "，尝试自动重连...");
                        tunnelProcess = null;
                        outputReaderFuture = null;
                        autoRestartTunnel(finalPort, targetHost, finalIsDirMode, binaryPath);
                        return;
                    }
                    if (finalIsDirMode) stopDirHttpServer();
                    resetTunnelAfterStop(-1);
                    tunnelStatusLabel.setText("启动失败");
                    tunnelStatusLabel.setForeground(Color.RED);
                    appendLog("启动失败: " + e.getMessage());

                    JOptionPane.showMessageDialog(this,
                            "启动失败:\n" + e.getMessage()
                                    + "\n\n请检查:\n1. 是否选择了正确的 bore 架构\n2. bore 二进制是否有执行权限\n3. 端口是否被占用",
                            "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        });
    }

    private void stopTunnel() {
        intentionalStop = true; // 标记为用户主动停止，不触发自动重连
        if (tunnelProcess != null && tunnelProcess.isAlive()) {
            appendLog("正在停止 tunnel...");
            tunnelProcess.destroy();
            try {
                tunnelProcess.waitFor(5, TimeUnit.SECONDS);
                if (tunnelProcess.isAlive()) {
                    tunnelProcess.destroyForcibly();
                    appendLog("隧道进程被强制终止");
                } else {
                    appendLog("隧道已正常停止");
                }
            } catch (InterruptedException e) {
                tunnelProcess.destroyForcibly();
                Thread.currentThread().interrupt();
            }
            tunnelProcess = null;
        }
        if (outputReaderFuture != null && !outputReaderFuture.isDone()) {
            outputReaderFuture.cancel(true);
            outputReaderFuture = null;
        }
        if (dirModeBtn.isSelected()) {
            stopDirHttpServer();
            appendLog("目录服务器已停止");
        }
        resetTunnelAfterStop(0);
    }

    private void setTunnelUiEnabled(boolean enabled) {
        tunnelStartBtn.setEnabled(enabled);
        tunnelStopBtn.setEnabled(!enabled);
        binaryCombo.setEnabled(enabled);
        browseBinaryBtn.setEnabled(enabled);
        portField.setEnabled(enabled);
        syncFsPortBtn.setEnabled(enabled);
        dirPathField.setEnabled(enabled);
        browseDirBtn.setEnabled(enabled);
        dirPortField.setEnabled(enabled);
        hostField.setEnabled(enabled);
        portModeBtn.setEnabled(enabled);
        dirModeBtn.setEnabled(enabled);
    }

    private void resetTunnelAfterStop(int exitCode) {
        tunnelRunning = false;
        publicUrl = null;
        setTunnelUiEnabled(true);
        tunnelStatusLabel.setText("未启动");
        tunnelStatusLabel.setForeground(Color.GRAY);
        publicUrlLabel.setText(" ");
        dirBrowseUrlLabel.setText(" ");
        if (exitCode >= 0) {
            appendLog("隧道进程已退出 (exit=" + exitCode + ")");
        }
    }

    /** 意外断开时自动重连隧道 */
    private void autoRestartTunnel(int port, String targetHost, boolean isDirMode, String binaryPath) {
        restartCount++;
        if (restartCount > MAX_AUTO_RESTART) {
            appendLog("已达到最大重试次数 (" + MAX_AUTO_RESTART + ") ，放弃自动重连，请手动重启");
            tunnelRunning = false;
            publicUrl = null;
            setTunnelUiEnabled(true);
            tunnelStatusLabel.setText("重连失败");
            tunnelStatusLabel.setForeground(Color.RED);
            return;
        }

        // 延迟重试（递增延迟：2s, 4s, 8s, 16s, 32s）
        int delaySec = (int) Math.pow(2, restartCount);
        appendLog("将在 " + delaySec + " 秒后进行第 " + restartCount + " 次自动重连...");
        tunnelStatusLabel.setText("自动重连中 (" + restartCount + "/" + MAX_AUTO_RESTART + ") ...");
        tunnelStatusLabel.setForeground(Color.ORANGE);

        ThreadPoolUtil.submitPlatformDelay(delaySec * 1000L, () -> {
            SwingUtilities.invokeLater(() -> {
                if (intentionalStop) return; // 等待期间用户点了停止

                appendLogSeparator();
                appendLog("自动重连 (第 " + restartCount + " 次)...");

                try {
                    ProcessBuilder pb = new ProcessBuilder(binaryPath, "local", String.valueOf(port),
                            "--local-host", targetHost, "--to", "bore.pub");
                    pb.redirectErrorStream(true);
                    tunnelProcess = pb.start();

                    outputReaderFuture = ThreadPoolUtil.submitPlatform(() -> {
                        try (BufferedReader reader = new BufferedReader(
                                new InputStreamReader(tunnelProcess.getInputStream(), StandardCharsets.UTF_8))) {
                            String line;
                            while ((line = reader.readLine()) != null) {
                                final String logLine = line;
                                SwingUtilities.invokeLater(() -> {
                                    appendLog(logLine);
                                    parseBoreUrl(logLine);
                                });
                            }
                        } catch (Exception e) {
                            if (!"Stream closed".equals(e.getMessage())) {
                                logger.error("Bore 隧道重连输出读取异常", e);
                                SwingUtilities.invokeLater(() -> appendLog("读取输出异常: " + e.getMessage()));
                            }
                        }
                    });

                    int exitCode = tunnelProcess.waitFor();

                    SwingUtilities.invokeLater(() -> {
                        if (intentionalStop) {
                            if (isDirMode) { stopDirHttpServer(); appendLog("目录服务器已停止"); }
                            resetTunnelAfterStop(exitCode);
                        } else {
                            appendLog("隧道再次断开 (exit=" + exitCode + ")，尝试自动重连...");
                            tunnelProcess = null;
                            outputReaderFuture = null;
                            autoRestartTunnel(port, targetHost, isDirMode, binaryPath);
                        }
                    });
                } catch (Exception e) {
                    logger.error("Bore 自动重连异常", e);
                    SwingUtilities.invokeLater(() -> {
                        if (!intentionalStop) {
                            appendLog("自动重连异常: " + e.getMessage());
                            tunnelProcess = null;
                            outputReaderFuture = null;
                            autoRestartTunnel(port, targetHost, isDirMode, binaryPath);
                        }
                    });
                }
            });
        });
    }

    // ==================== URL 解析 ====================

    private void parseBoreUrl(String line) {
        String lower = line.toLowerCase().trim();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("listening at (bore\\.pub:\\d+)").matcher(lower);
        if (m.find()) {
            setPublicUrl("http://" + m.group(1));
            return;
        }
        m = java.util.regex.Pattern.compile("(bore\\.pub:\\d+)").matcher(lower);
        if (m.find()) {
            setPublicUrl("http://" + m.group(1));
        }
    }

    private void setPublicUrl(String url) {
        if (url == null || url.isBlank()) return;
        publicUrl = url;
        tunnelRunning = true;
        tunnelStatusLabel.setText("运行中");
        tunnelStatusLabel.setForeground(new Color(76, 175, 80));
        publicUrlLabel.setText(url);
        appendLog("公网 URL: " + url);
        appendLogSeparator();
    }

    private static final DateTimeFormatter LOG_TIME_FMT = DateTimeFormatter.ofPattern("HH:mm:ss");

    private void appendLog(String msg) {
        if (logArea == null) return; // initPanel 阶段 logArea 尚未创建
        String timestamp = LOG_TIME_FMT.format(LocalTime.now());
        logArea.append(String.format("[%s] %s%n", timestamp, msg));
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    /** 添加一条分隔线到日志 */
    private void appendLogSeparator() {
        logArea.append("-----------------------------------------------\n");
        logArea.setCaretPosition(logArea.getDocument().getLength());
    }

    // ==================== 配置持久化 ====================

    private static final String ARCH_CACHE_FILE = "bore_selected_arch.txt";

    /** 架构缓存目录 */
    private static Path archCacheDir() {
        return BORE_DIR;
    }

    /** 读取上次选中的架构 */
    private String loadSavedArch() {
        try {
            Path cacheFile = archCacheDir().resolve(ARCH_CACHE_FILE);
            if (Files.exists(cacheFile)) {
                return Files.readString(cacheFile).trim();
            }
        } catch (Exception ignored) {}
        return null;
    }

    /** 保存当前选中的架构到缓存文件 */
    private void saveSelectedArch(String archLabel) {
        try {
            Path dir = archCacheDir();
            Files.createDirectories(dir);
            Files.writeString(dir.resolve(ARCH_CACHE_FILE), archLabel);
        } catch (Exception ignored) {}
    }




    @Override
    public void loadConfig(com.szh.manager.ConfigManager config) {
        // 文件服务器端口
        String fsPort = config.get("tunnel.fsPort");
        if (fsPort != null && !fsPort.isBlank()) {
            fsPortField.setText(fsPort);
        }

        // 公共配置
        String port = config.get("tunnel.port");
        if (port != null && !port.isBlank()) portField.setText(port);
        String dirPath = config.get("tunnel.dir");
        if (dirPath != null && !dirPath.isBlank()) dirPathField.setText(dirPath);
        String dirPort = config.get("tunnel.dirPort");
        if (dirPort != null && !dirPort.isBlank()) dirPortField.setText(dirPort);
        String targetHost = config.get("tunnel.targetHost");
        if (targetHost != null && !targetHost.isBlank()) hostField.setText(targetHost);
        String mode = config.get("tunnel.mode");
        if ("dir".equals(mode)) {
            dirModeBtn.setSelected(true);
            portModePanel.setVisible(false);
            dirModePanel.setVisible(true);
        }
    }

    @Override
    public void saveConfig(com.szh.manager.ConfigManager config) {
        // 保存选中的架构
        Object selected = binaryComboModel.getSelectedItem();
        if (selected != null) saveSelectedArch(selected.toString());

        config.set("tunnel.fsPort", fsPortField.getText().trim());
        config.set("tunnel.targetHost", hostField.getText().trim());
        config.set("tunnel.port", portField.getText().trim());
        config.set("tunnel.dir", dirPathField.getText().trim());
        config.set("tunnel.dirPort", dirPortField.getText().trim());
        config.set("tunnel.mode", dirModeBtn.isSelected() ? "dir" : "port");
    }
}
