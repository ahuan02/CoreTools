package com.szh.ui.panel;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.jcraft.jsch.*;
import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;

import javax.swing.Timer;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.event.*;
import java.io.*;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.AttributedCharacterIterator;
import java.text.CharacterIterator;
import java.util.List;
import java.util.*;

import static com.szh.utils.NetUtil.*;

/**
 * SSH 客户端面板：左侧连接列表 + 右侧远程控制台
 */
public class SshPanel extends AbstractCommandPanel {

    private static final Color WHITE = new Color(0xCCCCCC);

    /** 进度条动画定时器间隔（毫秒），越小越流畅 */
    private static final int PROGRESS_ANIMATION_INTERVAL_MS = 40;
    /** 进度条视觉追赶速度：每次 tick 最多前进的百分点数，越小越慢 */
    private static final int MAX_VISUAL_ADVANCE_PCT = 2;

    /** 格式化文件大小（供整个类使用） */
    private static String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024L * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024L * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    // ===== 连接信息 =====
    private static class SshConn implements Serializable {
        String name;
        String host;
        int port = 22;
        String username = "root";
        String password;

        transient boolean connected;
        transient Session session;
        transient ChannelShell channel;
        transient OutputStream toChannel;
        transient JSch jsch;

        @Override
        public String toString() {
            return name + "  (" + host + ":" + port + ")";
        }
    }

    private final List<SshConn> connections = new ArrayList<>();
    private DefaultListModel<SshConn> connListModel;
    private JList<SshConn> connList;

    // 右侧控制台
    private JTextPane terminalArea;
    private JLabel statusLabel;
    private JLabel connInfoLabel;

    // ANSI 状态机
    private Color ansiFg = WHITE;
    private Color ansiBg = null;
    private boolean ansiBold = false;

    // 底部命令输入框
    private JTextField commandInput;

    // 会话命令历史（上下键翻历史）
    private final List<String> commandHistory = new ArrayList<>();
    private int historyIndex = -1;

    // 终端原始模式（用于 vim/nano 等交互程序）
    private boolean rawMode = false;
    private JToggleButton rawModeBtn;
    private KeyListener rawModeKeyListener;
    /** 是否正在执行程序化输出（终端回显/ANSI），用于绕过 DocumentFilter */
    private volatile boolean rawAppending = false;
    /** 是否处于 IME 组合输入过程中 */
    private volatile boolean imeComposing = false;

    // 与当前选中连接交互的锁
    private final Object connLock = new Object();
    private SshConn currentConn;
    private ConfigManager configManager;

    // ===== 远程目录浏览器 =====
    private JLabel dirPathLabel;
    private JTree dirTree;
    private DefaultTreeModel dirTreeModel;
    private DefaultMutableTreeNode dirRootNode;
    private javax.swing.Timer dirRefreshTimer;
    private static final int DIR_REFRESH_DELAY_MS = 600; // 命令执行后延迟刷新（等 shell 回显完成）
    private String cachedRemotePwd; // 本地推算的远程当前目录，避免每次刷新都发 pwd 命令到终端

    // 已知主机密钥
    private final Map<String, String> knownHosts = new LinkedHashMap<>();
    private static final String HOST_KEY_COUNT = "ssh.hostkey.count";

    /** 计算公钥 SHA256 指纹（从原始字节） */
    private static String computeFingerprint(byte[] key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key);
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            return "SHA256:???";
        }
    }

    /** 从 SSH 公钥原始字节中提取密钥类型字符串 */
    private static String getKeyType(byte[] key) {
        if (key == null || key.length < 4) return "SSH";
        int len = ((key[0] & 0xFF) << 24) | ((key[1] & 0xFF) << 16) | ((key[2] & 0xFF) << 8) | (key[3] & 0xFF);
        if (len > 0 && 4 + len <= key.length) {
            return new String(key, 4, len, StandardCharsets.US_ASCII);
        }
        return "SSH";
    }

    public SshPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(6, 6));

        // ---- 顶部状态栏 ----
        JPanel topBar = new JPanel(new BorderLayout(10, 0));
        topBar.setOpaque(false);
        statusLabel = new JLabel("未连接");
        statusLabel.setFont(FONT_TEXT);
        statusLabel.setForeground(C_WARN);
        connInfoLabel = new JLabel();
        connInfoLabel.setFont(FONT_TEXT);
        connInfoLabel.setForeground(C_TIME);
        topBar.add(statusLabel, BorderLayout.WEST);
        topBar.add(connInfoLabel, BorderLayout.CENTER);
        add(topBar, BorderLayout.NORTH);

        // ---- 主分割面板 ----
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setResizeWeight(0.22);
        splitPane.setDividerSize(4);
        splitPane.setBorder(null);

        // 左侧连接列表面板
        splitPane.setLeftComponent(createConnListPanel());
        // 右侧控制台面板
        splitPane.setRightComponent(createConsolePanel());

        add(splitPane, BorderLayout.CENTER);
    }

    // ==================== 左侧连接列表 ====================

    private JPanel createConnListPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(0x666666), 1, true),
                "连接列表", TitledBorder.LEADING, TitledBorder.TOP,
                FONT_TEXT, C_WARN));
        panel.setMinimumSize(new Dimension(180, 100));

        connListModel = new DefaultListModel<>();
        connList = new JList<>(connListModel);
        connList.setCellRenderer(new ConnListRenderer());
        connList.setBackground(C_BG);
        connList.setForeground(WHITE);
        connList.setFont(FONT_TEXT);
        connList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);

        // 双击打开连接
        connList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    openSelectedConn();
                }
            }
        });

        // 右键菜单
        JPopupMenu popup = new JPopupMenu();
        JMenuItem openItem = new JMenuItem("打开");
        openItem.addActionListener(e -> openSelectedConn());
        JMenuItem closeItem = new JMenuItem("关闭");
        closeItem.addActionListener(e -> closeSelectedConn());
        JMenuItem editItem = new JMenuItem("编辑");
        editItem.addActionListener(e -> editSelectedConn());
        popup.add(openItem);
        popup.add(closeItem);
        popup.add(editItem);
        popup.addSeparator();
        JMenuItem deleteItem = new JMenuItem("删除");
        deleteItem.addActionListener(e -> deleteSelectedConn());
        popup.add(deleteItem);
        connList.setComponentPopupMenu(popup);

        JScrollPane scroll = new JScrollPane(connList);
        scroll.setBorder(null);
        panel.add(scroll, BorderLayout.CENTER);

        JButton newBtn = makeBtn("+ 新建连接", new Color(0x3A7D44));
        newBtn.addActionListener(e -> createNewConn());
        panel.add(newBtn, BorderLayout.SOUTH);

        return panel;
    }

    /** 连接列表渲染器 */
    private class ConnListRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                       boolean isSelected, boolean cellHasFocus) {
            JLabel label = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            if (value instanceof SshConn) {
                SshConn c = (SshConn) value;
                String indicator = c.connected ? "\u25CF " : "\u25CB ";
                label.setText(indicator + c.name + "  " + c.host + ":" + c.port);
                label.setForeground(c.connected ? C_RECV : WHITE);
            }
            if (!isSelected) label.setBackground(C_BG);
            return label;
        }
    }

    // ==================== 右侧控制台 ====================

    private JPanel createConsolePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 0));

        // 终端显示区域（纯只读输出）
        terminalArea = new JTextPane();
        terminalArea.setEditable(false);
        terminalArea.setBackground(C_BG);
        terminalArea.setForeground(WHITE);
        terminalArea.setCaretColor(WHITE);
        terminalArea.setFont(new Font("NSimSun", Font.PLAIN, 13));
        terminalArea.getCaret().setVisible(true);
        terminalArea.getCaret().setBlinkRate(500);

        // 右键菜单：复制 + 清空
        JPopupMenu terminalPopup = new JPopupMenu();
        JMenuItem copyItem = new JMenuItem("复制 (Ctrl+C)");
        copyItem.addActionListener(e -> {
            String selected = terminalArea.getSelectedText();
            if (selected != null && !selected.isEmpty()) {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(selected), null);
            } else {
                Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(terminalArea.getText()), null);
            }
        });
        JMenuItem clearItem = new JMenuItem("清空终端");
        clearItem.addActionListener(e -> terminalArea.setText(""));
        terminalPopup.add(copyItem);
        terminalPopup.add(clearItem);
        // 给 terminalArea 本身和它的 scroll 都加上右键菜单
        terminalArea.setComponentPopupMenu(terminalPopup);

        JScrollPane scroll = new JScrollPane(terminalArea);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "终端", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        scroll.setPreferredSize(new Dimension(400, 220));
        scroll.setMinimumSize(new Dimension(200, 120));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        // scroll 上也加右键菜单（防止 terminalArea 失焦时右键不触发）
        scroll.setComponentPopupMenu(terminalPopup);

        // 垂直分割：上方目录浏览器 + 下方终端
        JSplitPane verticalSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        verticalSplit.setResizeWeight(0.35);
        verticalSplit.setDividerSize(4);
        verticalSplit.setBorder(null);
        verticalSplit.setTopComponent(createDirBrowserPanel());
        verticalSplit.setBottomComponent(scroll);
        panel.add(verticalSplit, BorderLayout.CENTER);

        // 底部命令输入框（样式模仿终端，以假乱真）
        commandInput = new JTextField();
        commandInput.setBackground(C_BG);
        commandInput.setForeground(WHITE);
        commandInput.setCaretColor(WHITE);
        commandInput.setFont(FONT_TEXT);
        NetUtil.fixPaste(commandInput);
        commandInput.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createMatteBorder(1, 0, 0, 0, new Color(56, 56, 56)),
                BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        commandInput.putClientProperty("JTextField.placeholderText", "输入命令，按 Tab 发送到远程...");
        // 禁用 Tab/ShiftTab 焦点遍历，防止按 Tab 时焦点逃跑
        commandInput.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.emptySet());
        commandInput.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Collections.emptySet());

        // 键盘：Tab/Ctrl+C/Enter 发送，上下键翻历史
        commandInput.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                // Ctrl+C: 发送 \x03 中断远程命令 + 清空输入框
                if (e.isControlDown() && e.getKeyCode() == KeyEvent.VK_C) {
                    e.consume();
                    sendToChannel(new byte[]{0x03});
                    commandInput.setText("");
                    return;
                }

                switch (e.getKeyCode()) {
                    case KeyEvent.VK_TAB:
                        e.consume();
                        // Tab: 把输入框内容 + \t 发给远程做补全
                        String tabText = commandInput.getText();
                        if (!tabText.isEmpty()) {
                            sendToChannel((tabText + "\t").getBytes(StandardCharsets.UTF_8));
                            commandInput.setText("");
                            // 等 200ms 后从终端显示区提取补全结果回填输入框
                            scheduleTabCompletion(tabText);
                        }
                        break;

                    case KeyEvent.VK_ENTER:
                        e.consume();
                        String text = commandInput.getText();
                        // 回车：发当前内容 + \r 执行命令
                        sendToChannel((text + "\r").getBytes(StandardCharsets.UTF_8));
                        if (!text.isEmpty()) {
                            // 记录历史（去重相邻重复）
                            if (commandHistory.isEmpty() || !commandHistory.get(commandHistory.size() - 1).equals(text)) {
                                commandHistory.add(text);
                            }
                            historyIndex = commandHistory.size();
                        }
                        commandInput.setText("");
                        // 每次命令执行后都通过 SFTP 拉取真实目录，目录变化则自动同步树上
                        if (!text.isEmpty()) schedulePwdVerify(500);
                        break;

                    case KeyEvent.VK_UP:
                        e.consume();
                        // 发送 ↑ 给远程，让终端显示区也回显历史命令
                        sendToChannel(new byte[]{'\u001B', '[', 'A'});
                        if (!commandHistory.isEmpty() && historyIndex > 0) {
                            historyIndex--;
                            commandInput.setText(commandHistory.get(historyIndex));
                        }
                        break;

                    case KeyEvent.VK_DOWN:
                        e.consume();
                        // 发送 ↓ 给远程
                        sendToChannel(new byte[]{'\u001B', '[', 'B'});
                        if (historyIndex < commandHistory.size() - 1) {
                            historyIndex++;
                            commandInput.setText(commandHistory.get(historyIndex));
                        } else {
                            historyIndex = commandHistory.size();
                            commandInput.setText("");
                        }
                        break;
                }
            }
        });

        JPanel bottomBar = new JPanel(new BorderLayout(5, 0));
        bottomBar.setBackground(C_BG);
        bottomBar.add(commandInput, BorderLayout.CENTER);

        // 上传 / 下载 / 原始模式按钮
        JPanel fileBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        fileBtnPanel.setBackground(C_BG);

        rawModeBtn = new JToggleButton("RAW");
        rawModeBtn.setFont(new Font("Consolas", Font.BOLD, 11));
        rawModeBtn.setToolTipText("切换终端原始模式（用于 vim/nano 等交互程序，所有按键直通远程）");
        rawModeBtn.setMargin(new Insets(1, 6, 1, 6));
        rawModeBtn.addActionListener(e -> toggleRawMode());
        fileBtnPanel.add(rawModeBtn);

        JButton uploadBtn = makeBtn("上传", new Color(0x2E7D32));
        uploadBtn.setToolTipText("上传文件到远程当前目录");
        uploadBtn.addActionListener(e -> uploadFile());
        JButton downloadBtn = makeBtn("下载", new Color(0x1565C0));
        downloadBtn.setToolTipText("从远程下载文件");
        downloadBtn.addActionListener(e -> downloadFile());
        fileBtnPanel.add(uploadBtn);
        fileBtnPanel.add(downloadBtn);
        bottomBar.add(fileBtnPanel, BorderLayout.EAST);
        panel.add(bottomBar, BorderLayout.SOUTH);


        return panel;
    }

    // ==================== 远程目录浏览器 ====================

    /** SFTP 目录条目 */
    private static class RemoteFileNode {
        final String name;
        final String path;
        final boolean isDir;
        final boolean isLink;
        final long size;
        final String perms;

        RemoteFileNode(String name, String path, boolean isDir, boolean isLink, long size, String perms) {
            this.name = name;
            this.path = path;
            this.isDir = isDir;
            this.isLink = isLink;
            this.size = size;
            this.perms = perms;
        }

        @Override
        public String toString() { return name; }
    }

    /** 目录树单元格渲染器 */
    private static class DirTreeCellRenderer extends DefaultTreeCellRenderer {
        private static final Color C_DIR = new Color(0x64B5F6);
        private static final Color C_EXE = new Color(0x81C784);
        private static final Color C_LINK = new Color(0xCE93D8);
        private static Icon folderIcon;
        private static Icon fileIcon;
        static {
            URL folderUrl = DirTreeCellRenderer.class.getResource("/icons/fileholder.svg");
            if (folderUrl != null) folderIcon = new FlatSVGIcon(folderUrl).derive(16, 16);
            URL fileUrl = DirTreeCellRenderer.class.getResource("/icons/file.svg");
            if (fileUrl != null) fileIcon = new FlatSVGIcon(fileUrl).derive(16, 16);
        }

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object obj = node.getUserObject();
            if (obj instanceof RemoteFileNode fn) {
                String display = fn.name;
                if (!fn.isDir) {
                    display += "  (" + formatSize(fn.size) + ")";
                }
                setText(display);
                if (fn.isDir) {
                    setIcon(folderIcon);
                } else {
                    setIcon(fileIcon);
                }
                if (!sel) {
                    if (fn.isLink) setForeground(C_LINK);
                    else if (fn.isDir) setForeground(C_DIR);
                    else if (fn.perms != null && fn.perms.length() >= 4 && fn.perms.charAt(3) == 'x')
                        setForeground(C_EXE);
                    else setForeground(WHITE);
                }
            } else {
                setText(String.valueOf(obj));
                setIcon(folderIcon);
                if (!sel) setForeground(C_DIR);
            }
            setBackgroundNonSelectionColor(C_BG);
            setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            return this;
        }
    }

    /** 创建目录浏览器面板 */
    private JPanel createDirBrowserPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 2));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "远程目录", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12), new Color(0x64B5F6)));

        // ---- 顶部路径栏 ----
        JPanel pathBar = new JPanel(new BorderLayout(4, 0));
        pathBar.setBackground(C_BG);
        pathBar.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));

        dirPathLabel = new JLabel("未连接");
        dirPathLabel.setFont(FONT_TEXT.deriveFont(12f));
        dirPathLabel.setForeground(C_TIME);
        pathBar.add(dirPathLabel, BorderLayout.CENTER);

        JPanel navBtnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 2, 0));
        navBtnPanel.setBackground(C_BG);

        JButton parentBtn = new JButton("⬆");
        parentBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        parentBtn.setToolTipText("上级目录");
        parentBtn.setMargin(new Insets(1, 6, 1, 6));
        parentBtn.addActionListener(e -> navigateToParent());
        navBtnPanel.add(parentBtn);

        JButton refreshBtn = new JButton("🔄");
        refreshBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
        refreshBtn.setToolTipText("刷新目录");
        refreshBtn.setMargin(new Insets(1, 6, 1, 6));
        refreshBtn.addActionListener(e -> refreshDirectoryBrowser(true));
        navBtnPanel.add(refreshBtn);

        pathBar.add(navBtnPanel, BorderLayout.EAST);
        panel.add(pathBar, BorderLayout.NORTH);

        // ---- 目录树 ----
        dirRootNode = new DefaultMutableTreeNode("未连接");
        dirTreeModel = new DefaultTreeModel(dirRootNode);
        dirTree = new JTree(dirTreeModel);
        dirTree.setRootVisible(true);
        dirTree.setShowsRootHandles(true);
        dirTree.setBackground(C_BG);
        dirTree.setCellRenderer(new DirTreeCellRenderer());
        dirTree.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));

        JScrollPane treeScroll = new JScrollPane(dirTree);
        treeScroll.setBorder(null);
        treeScroll.setPreferredSize(new Dimension(400, 140));
        treeScroll.setMinimumSize(new Dimension(200, 80));
        panel.add(treeScroll, BorderLayout.CENTER);

        // 双击进入目录
        dirTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    TreePath tp = dirTree.getPathForLocation(e.getX(), e.getY());
                    if (tp != null) {
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
                        Object obj = node.getUserObject();
                        if (obj instanceof RemoteFileNode fn && fn.isDir) {
                            cdIntoRemote(fn.path);
                        }
                    }
                }
                // 改为右击下载（待扩展）
                if (SwingUtilities.isRightMouseButton(e)) {
                    TreePath tp = dirTree.getPathForLocation(e.getX(), e.getY());
                    if (tp != null) {
                        dirTree.setSelectionPath(tp);
                        DefaultMutableTreeNode node = (DefaultMutableTreeNode) tp.getLastPathComponent();
                        Object obj = node.getUserObject();
                        if (obj instanceof RemoteFileNode fn && !fn.isDir) {
                            // 预留：右键下载文件
                            JPopupMenu popup = new JPopupMenu();
                            JMenuItem downloadItem = new JMenuItem("下载 " + fn.name);
                            downloadItem.addActionListener(ev -> downloadSingleRemoteFile(fn.path, fn.name));
                            popup.add(downloadItem);
                            JMenuItem deleteItem = new JMenuItem("删除");
                            deleteItem.addActionListener(ev -> deleteRemoteFile(fn.path, fn.name));
                            popup.add(deleteItem);
                            popup.show(dirTree, e.getX(), e.getY());
                        }
                        if (obj instanceof RemoteFileNode fn && fn.isDir) {
                            JPopupMenu popup = new JPopupMenu();
                            JMenuItem enterItem = new JMenuItem("进入 " + fn.name);
                            enterItem.addActionListener(ev -> cdIntoRemote(fn.path));
                            popup.add(enterItem);
                            popup.show(dirTree, e.getX(), e.getY());
                        }
                    }
                }
            }
        });

        // 延迟刷新定时器（等 shell 回显完成后再查目录）
        dirRefreshTimer = new javax.swing.Timer(DIR_REFRESH_DELAY_MS, ev -> {
            SshConn c;
            synchronized (connLock) { c = currentConn; }
            if (c != null && c.connected) refreshDirectoryBrowser(false);
        });
        dirRefreshTimer.setRepeats(false);

        // 支持拖拽本地文件到目录树面板 → 上传到远程当前目录
        dirTree.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                if (!canImport(support)) return false;
                try {
                    List<File> files = (List<File>) support.getTransferable()
                            .getTransferData(DataFlavor.javaFileListFlavor);
                    if (!files.isEmpty()) uploadDraggedFiles(files);
                    return true;
                } catch (Exception e) {
                    appendTerminal("[拖拽上传失败: " + e.getMessage() + "]\n", C_ERR);
                    return false;
                }
            }
        });
        dirTree.setDragEnabled(false);

        return panel;
    }

    /** 刷新目录浏览器。forceQuery=true 时向 shell 发 pwd 获取真实目录，否则用本地缓存。 */
    private void refreshDirectoryBrowser(boolean forceQuery) {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) return;

        Thread.startVirtualThread(() -> {
            String cwd = cachedRemotePwd;
            if (cwd == null || forceQuery) {
                cwd = getRemotePwd(conn);
                if (cwd != null) cachedRemotePwd = cwd;
            }
            if (cwd == null) {
                SwingUtilities.invokeLater(() -> dirPathLabel.setText("获取目录失败"));
                return;
            }

            List<RemoteFileNode> children = new ArrayList<>();
            ChannelSftp sftp = null;
            try {
                sftp = (ChannelSftp) conn.session.openChannel("sftp");
                sftp.connect(3000);

                @SuppressWarnings("unchecked")
                Vector<ChannelSftp.LsEntry> entries = sftp.ls(cwd);
                for (ChannelSftp.LsEntry entry : entries) {
                    String name = entry.getFilename();
                    if (".".equals(name) || "..".equals(name)) continue;
                    SftpATTRS attrs = entry.getAttrs();
                    children.add(new RemoteFileNode(
                            name,
                            cwd + "/" + name,
                            attrs.isDir(),
                            attrs.isLink(),
                            attrs.getSize(),
                            attrs.getPermissionsString()
                    ));
                }
            } catch (Exception e) {
                final String errPath = cwd;
                SwingUtilities.invokeLater(() ->
                    dirPathLabel.setText(errPath + "  (无权限)"));
                return;
            } finally {
                if (sftp != null) try { sftp.disconnect(); } catch (Exception ignored) {}
            }

            // 排序：目录优先，然后按名称
            children.sort((a, b) -> {
                if (a.isDir != b.isDir) return a.isDir ? -1 : 1;
                return a.name.compareToIgnoreCase(b.name);
            });

            final String displayPath = cwd;
            SwingUtilities.invokeLater(() -> {
                dirPathLabel.setText(displayPath);
                dirRootNode.setUserObject(displayPath);
                dirRootNode.removeAllChildren();
                for (RemoteFileNode child : children) {
                    dirRootNode.add(new DefaultMutableTreeNode(child));
                }
                dirTreeModel.reload();
                dirTree.expandRow(0);
            });
        });
    }

    /** cd 到远程目录 */
    private void cdIntoRemote(String path) {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.toChannel == null) return;
        try {
            conn.toChannel.write(("cd \"" + path + "\"\r").getBytes(StandardCharsets.UTF_8));
            conn.toChannel.flush();
            cachedRemotePwd = path; // 本地缓存，不向 shell 发 pwd
            if (dirRefreshTimer != null) dirRefreshTimer.restart();
        } catch (Exception ignored) {}
    }

    /** 返回上级目录 */
    private void navigateToParent() {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.toChannel == null) return;
        String cwd = cachedRemotePwd;
        if (cwd != null && !cwd.equals("/")) {
            int idx = cwd.lastIndexOf('/');
            String parent = idx <= 0 ? "/" : cwd.substring(0, idx);
            cachedRemotePwd = parent;
            try {
                conn.toChannel.write(("cd \"" + parent + "\"\r").getBytes(StandardCharsets.UTF_8));
                conn.toChannel.flush();
                if (dirRefreshTimer != null) dirRefreshTimer.restart();
            } catch (Exception ignored) {}
        }
    }

    /** 拖拽本地文件到目录树 → 上传到远程当前目录 */
    private void uploadDraggedFiles(List<File> files) {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) {
            appendTerminal("[未连接到服务器，无法上传]\n", C_WARN);
            return;
        }
        Thread.startVirtualThread(() -> {
            String cwd = getRemotePwd(conn);
            if (cwd == null) {
                appendTerminal("[无法获取远程当前目录]\n", C_ERR);
                return;
            }
            ChannelSftp sftp = null;
            try {
                sftp = (ChannelSftp) conn.session.openChannel("sftp");
                sftp.connect(5000);
                for (File file : files) {
                    if (!file.isFile()) continue;
                    String remotePath = cwd + "/" + file.getName();
                    appendTerminal("[上传: " + file.getName() + " → " + remotePath + "]\n", C_SYS);
                    TransferProgressDialog dlg = new TransferProgressDialog(
                            SwingUtilities.getWindowAncestor(this), "上传 " + file.getName());
                    try {
                        sftp.put(file.getAbsolutePath(), remotePath,
                            new SftpProgressMonitor() {
                                private long total;
                                @Override public void init(int op, String src, String dest, long max) {
                                    total = max;
                                    SwingUtilities.invokeLater(() -> dlg.setVisible(true));
                                }
                                @Override public boolean count(long count) {
                                    dlg.setTarget(count, total);
                                    return !dlg.cancelled;
                                }
                                @Override public void end() {
                                    SwingUtilities.invokeLater(() -> {
                                        dlg.setTarget(total, total);
                                        dlg.markFinished();
                                    });
                                }
                            }, ChannelSftp.OVERWRITE);
                        if (dlg.cancelled) {
                            try { sftp.rm(remotePath); } catch (Exception ignored) {}
                            appendTerminal("[已取消上传: " + file.getName() + "]\n", C_WARN);
                        } else {
                            appendTerminal("[上传完成: " + file.getName() + "]\n", C_RECV);
                        }
                    } catch (Exception e) {
                        if (dlg.cancelled) {
                            try { sftp.rm(remotePath); } catch (Exception ignored) {}
                            dlg.markFinished();
                            appendTerminal("[已取消上传: " + file.getName() + "]\n", C_WARN);
                        } else {
                            dlg.markFinished();
                            appendTerminal("[上传失败: " + e.getMessage() + "]\n", C_ERR);
                        }
                    }
                }
            } catch (Exception e) {
                appendTerminal("[SFTP 连接失败: " + e.getMessage() + "]\n", C_ERR);
            } finally {
                if (sftp != null) try { sftp.disconnect(); } catch (Exception ignored) {}
            }
            refreshDirectoryBrowser(false);
        });
    }

    /** 下载右键选择的单个文件 */
    private void downloadSingleRemoteFile(String remotePath, String fileName) {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("保存 " + fileName);
        chooser.setSelectedFile(new File(fileName));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;
        File localFile = chooser.getSelectedFile();
        if (localFile == null) return;
        if (localFile.exists()) {
            int ret = JOptionPane.showConfirmDialog(this,
                    "文件已存在，是否覆盖？", "确认", JOptionPane.YES_NO_OPTION);
            if (ret != JOptionPane.YES_OPTION) return;
        }

        Thread.startVirtualThread(() -> {
            ChannelSftp sftp = null;
            TransferProgressDialog dlg = null;
            try {
                sftp = (ChannelSftp) conn.session.openChannel("sftp");
                sftp.connect(5000);

                dlg = new TransferProgressDialog(
                        SwingUtilities.getWindowAncestor(this), "下载 " + fileName);
                try {
                    TransferProgressDialog finalDlg = dlg;
                    sftp.get(remotePath, localFile.getAbsolutePath(),
                        new SftpProgressMonitor() {
                            private long total;
                            @Override public void init(int op, String src, String dest, long max) {
                                total = max;
                                SwingUtilities.invokeLater(() -> finalDlg.setVisible(true));
                            }
                            @Override public boolean count(long count) {
                                finalDlg.setTarget(count, total);
                                return !finalDlg.cancelled;
                            }
                            @Override public void end() {
                                SwingUtilities.invokeLater(() -> {
                                    finalDlg.setTarget(total, total);
                                    finalDlg.markFinished();
                                });
                            }
                        });
                    if (dlg.cancelled) {
                        try { java.nio.file.Files.deleteIfExists(localFile.toPath()); } catch (Exception ignored) {}
                        appendTerminal("[已取消下载: " + fileName + "]\n", C_WARN);
                    } else {
                        appendTerminal("[下载完成: " + fileName + "]\n", C_RECV);
                    }
                } catch (Exception e) {
                    if (dlg.cancelled) {
                        try { java.nio.file.Files.deleteIfExists(localFile.toPath()); } catch (Exception ignored) {}
                        dlg.markFinished();
                        appendTerminal("[已取消下载: " + fileName + "]\n", C_WARN);
                    } else {
                        dlg.markFinished();
                        appendTerminal("[下载失败: " + e.getMessage() + "]\n", C_ERR);
                    }
                }
            } catch (Exception e) {
                appendTerminal("[下载失败: " + e.getMessage() + "]\n", C_ERR);
            } finally {
                if (sftp != null) try { sftp.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    /** 删除远程文件 */
    private void deleteRemoteFile(String remotePath, String fileName) {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) return;
        int ret = JOptionPane.showConfirmDialog(this,
                "确定删除远程文件 \"" + fileName + "\" 吗？", "确认删除",
                JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (ret != JOptionPane.YES_OPTION) return;

        Thread.startVirtualThread(() -> {
            ChannelSftp sftp = null;
            try {
                sftp = (ChannelSftp) conn.session.openChannel("sftp");
                sftp.connect(3000);
                sftp.rm(remotePath);
                appendTerminal("[已删除: " + fileName + "]\n", C_SYS);
                refreshDirectoryBrowser(false);
            } catch (Exception e) {
                appendTerminal("[删除失败: " + e.getMessage() + "]\n", C_ERR);
            } finally {
                if (sftp != null) try { sftp.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    // ==================== 连接管理操作 ====================

    /** 新建连接 */
    private void createNewConn() {
        SshConnEditDialog dlg = new SshConnEditDialog(SwingUtilities.getWindowAncestor(this), null);
        dlg.setVisible(true);
        if (dlg.confirmed) {
            SshConn conn = dlg.getConnection();
            connections.add(conn);
            connListModel.addElement(conn);
            connList.setSelectedValue(conn, true);
            saveConnectionsToConfig();
            appendTerminal("[已添加连接: " + conn + "]\n", C_SYS);
        }
    }

    /** 编辑选中连接 */
    private void editSelectedConn() {
        SshConn conn = connList.getSelectedValue();
        if (conn == null) return;
        if (conn.connected) {
            JOptionPane.showMessageDialog(this, "请先关闭连接再编辑", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        SshConnEditDialog dlg = new SshConnEditDialog(SwingUtilities.getWindowAncestor(this), conn);
        dlg.setVisible(true);
        if (dlg.confirmed) {
            connList.repaint();
            saveConnectionsToConfig();
            appendTerminal("[已更新连接: " + conn + "]\n", C_SYS);
        }
    }

    /** 删除选中连接 */
    private void deleteSelectedConn() {
        SshConn conn = connList.getSelectedValue();
        if (conn == null) return;
        if (conn.connected) {
            JOptionPane.showMessageDialog(this, "请先关闭连接再删除", "提示", JOptionPane.INFORMATION_MESSAGE);
            return;
        }
        int ret = JOptionPane.showConfirmDialog(this,
                "确定删除连接 \"" + conn.name + "\" 吗？", "确认", JOptionPane.YES_NO_OPTION);
        if (ret == JOptionPane.YES_OPTION) {
            connections.remove(conn);
            connListModel.removeElement(conn);
            saveConnectionsToConfig();
            appendTerminal("[已删除连接: " + conn.name + "]\n", C_SYS);
        }
    }

    /** 打开选中连接 */
    private void openSelectedConn() {
        SshConn conn = connList.getSelectedValue();
        if (conn == null) return;
        if (conn.connected) {
            appendTerminal("[连接 " + conn.name + " 已处于打开状态]\n", C_WARN);
            return;
        }
        openConnection(conn);
    }

    /** 关闭选中连接 */
    private void closeSelectedConn() {
        SshConn conn = connList.getSelectedValue();
        if (conn == null || !conn.connected) return;
        closeConnection(conn);
    }

    /** 发送原始字节到 SSH channel */
    private void sendToChannel(byte[] data) {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.toChannel == null) return;
        try {
            conn.toChannel.write(data);
            conn.toChannel.flush();
        } catch (IOException e) {
            appendTerminal("[发送失败: " + e.getMessage() + "]\n", C_ERR);
        }
    }

    /** Tab 补全后从终端回显提取完成文本，延迟回填输入框 */
    private void scheduleTabCompletion(String prefix) {
        new javax.swing.Timer(180, ev -> {
            ((javax.swing.Timer) ev.getSource()).stop();
            try {
                StyledDocument doc = terminalArea.getStyledDocument();
                if (doc.getLength() == 0) return;
                String full = doc.getText(0, doc.getLength());
                // 取最后一个 "prompt 行" — 即最后一个以 prefix 开头或包含 prefix 的行
                int lastNL = full.lastIndexOf('\n');
                String lastLine = (lastNL >= 0) ? full.substring(lastNL + 1).trim() : full.trim();
                // 如果最后一行不是补全结果，往前找
                if (lastLine.isEmpty() || !lastLine.contains(prefix)) {
                    // 往前扫描最近 8 行
                    int scanEnd = lastNL >= 0 ? lastNL : full.length();
                    String before = full.substring(0, scanEnd);
                    String[] lines = before.split("\n");
                    for (int i = lines.length - 1; i >= Math.max(0, lines.length - 8); i--) {
                        String l = lines[i].trim();
                        if (l.startsWith(prefix) && l.length() > prefix.length()) {
                            lastLine = l;
                            break;
                        }
                    }
                }
                // 提取以 prefix 开头的完整词
                if (lastLine.startsWith(prefix) && lastLine.length() > prefix.length()) {
                    // 取 prefix 之后没有空格的那段作为补全结果
                    int end = prefix.length();
                    while (end < lastLine.length() && !Character.isWhitespace(lastLine.charAt(end))) {
                        end++;
                    }
                    String completed = lastLine.substring(0, end);
                    commandInput.setText(completed);
                }
            } catch (BadLocationException ignored) {}
        }).start();
    }

    /** 命令执行后延迟用 SFTP 拉取真实目录，变化则自动同步树上 */
    private void schedulePwdVerify(int delayMs) {
        new javax.swing.Timer(delayMs, ev -> {
            ((javax.swing.Timer) ev.getSource()).stop();
            SshConn conn;
            synchronized (connLock) { conn = currentConn; }
            if (conn != null && conn.connected && conn.session != null) {
                Thread.startVirtualThread(() -> {
                    String real = getSftpPwd(conn);
                    if (real != null && !real.equals(cachedRemotePwd)) {
                        cachedRemotePwd = real;
                        refreshDirectoryBrowser(false);
                    }
                });
            }
        }).start();
    }

    /** 切换终端原始模式（vim/nano 等交互程序用） */
    private void toggleRawMode() {
        rawMode = rawModeBtn.isSelected();
        if (rawMode) {
            enterRawMode();
        } else {
            exitRawMode();
        }
    }

    private void enterRawMode() {
        commandInput.setEnabled(false);
        commandInput.setForeground(Color.GRAY);
        commandInput.putClientProperty("JTextField.placeholderText", "RAW 模式 — 按键直通远程终端");
        // 临时设为可编辑以支持 IME 中文输入法
        terminalArea.setEditable(true);
        terminalArea.setFocusable(true);
        // 禁用 Tab/ShiftTab 焦点遍历，防止按 Tab 焦点逃跑
        terminalArea.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, Collections.emptySet());
        terminalArea.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, Collections.emptySet());
        terminalArea.requestFocusInWindow();

        // 文档过滤器：拦截用户输入发送到远程终端，阻止其写入 JTextPane
        // 程序化输出（appendTerminal/appendAnsi）通过 rawAppending 标志绕过
        DocumentFilter rawDocFilter = new DocumentFilter() {
            @Override
            public void insertString(FilterBypass fb, int offset, String string, AttributeSet attr) throws BadLocationException {
                if (rawAppending) {
                    super.insertString(fb, offset, string, attr);
                } else if (imeComposing) {
                    // IME 组合输入期间允许临时显示
                    super.insertString(fb, offset, string, attr);
                } else {
                    // 用户按键 → 发远程，不写入文档
                    sendToChannel(string.getBytes(StandardCharsets.UTF_8));
                }
            }
            @Override
            public void replace(FilterBypass fb, int offset, int length, String text, AttributeSet attrs) throws BadLocationException {
                if (rawAppending) {
                    super.replace(fb, offset, length, text, attrs);
                } else if (imeComposing) {
                    super.replace(fb, offset, length, text, attrs);
                } else {
                    if (text != null && !text.isEmpty()) {
                        sendToChannel(text.getBytes(StandardCharsets.UTF_8));
                    }
                }
            }
            @Override
            public void remove(FilterBypass fb, int offset, int length) throws BadLocationException {
                if (!rawAppending) return; // 阻止用户删除终端内容
                super.remove(fb, offset, length);
            }
        };
        ((AbstractDocument) terminalArea.getDocument()).setDocumentFilter(rawDocFilter);

        // 输入法监听：IME 组合期间允许文档变更显示；提交后统一发送到远程
        terminalArea.addInputMethodListener(new InputMethodListener() {
            @Override
            public void inputMethodTextChanged(InputMethodEvent event) {
                int committedCount = event.getCommittedCharacterCount();
                AttributedCharacterIterator text = event.getText();
                if (committedCount > 0 && text != null) {
                    // IME 已提交文字 → 提取并发送到远程
                    char[] chars = new char[committedCount];
                    int idx = 0;
                    for (char c = text.first(); c != CharacterIterator.DONE && idx < committedCount; c = text.next()) {
                        chars[idx++] = c;
                    }
                    sendToChannel(new String(chars).getBytes(StandardCharsets.UTF_8));
                }
                // 判断是否仍在组合输入中
                imeComposing = (text != null && (text.getEndIndex() - text.getBeginIndex() > committedCount));
            }
            @Override
            public void caretPositionChanged(InputMethodEvent event) {}
        });

        rawModeKeyListener = new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                byte[] data = keyEventToRemoteBytes(e);
                if (data != null) {
                    e.consume();
                    sendToChannel(data);
                }
                // 普通可打印键不在此处理，交由 DocumentFilter 统一拦截（含 IME 中文）
            }
        };
        terminalArea.addKeyListener(rawModeKeyListener);
    }

    private void exitRawMode() {
        if (rawModeKeyListener != null) {
            terminalArea.removeKeyListener(rawModeKeyListener);
            rawModeKeyListener = null;
        }
        // 移除文档过滤器
        Document d = terminalArea.getDocument();
        if (d instanceof AbstractDocument) {
            ((AbstractDocument) d).setDocumentFilter(null);
        }
        // 移除输入法监听器
        for (InputMethodListener l : terminalArea.getInputMethodListeners()) {
            terminalArea.removeInputMethodListener(l);
        }
        terminalArea.setEditable(false);
        terminalArea.setFocusable(false);
        // 恢复焦点遍历键为默认值
        terminalArea.setFocusTraversalKeys(KeyboardFocusManager.FORWARD_TRAVERSAL_KEYS, null);
        terminalArea.setFocusTraversalKeys(KeyboardFocusManager.BACKWARD_TRAVERSAL_KEYS, null);
        commandInput.setEnabled(true);
        commandInput.setForeground(WHITE);
        commandInput.putClientProperty("JTextField.placeholderText", "输入命令，按 Tab 发送到远程...");
        commandInput.requestFocusInWindow();
    }

    /** 将按键事件转换为发送到远程终端的字节序列 */
    private static byte[] keyEventToRemoteBytes(KeyEvent e) {
        int code = e.getKeyCode();
        boolean ctrl = e.isControlDown();

        if (ctrl) {
            // Ctrl+A..Z → 0x01..0x1A,  Ctrl+[ → 0x1B (ESC)
            if (code >= KeyEvent.VK_A && code <= KeyEvent.VK_Z) {
                return new byte[]{(byte) (code - KeyEvent.VK_A + 1)};
            }
            switch (code) {
                case KeyEvent.VK_OPEN_BRACKET: return new byte[]{0x1B}; // Ctrl+[ = ESC
                case KeyEvent.VK_SPACE:       return new byte[]{0x00};
                case KeyEvent.VK_BACK_SLASH:  return new byte[]{0x1C};
                case KeyEvent.VK_CLOSE_BRACKET: return new byte[]{0x1D};
                case KeyEvent.VK_SLASH:       return new byte[]{0x1F}; // Ctrl+/
                default: return null;
            }
        }

        // Alt+key: send ESC prefix (Meta)
        boolean alt = e.isAltDown();
        byte[] inner = null;

        switch (code) {
            case KeyEvent.VK_ENTER:      inner = new byte[]{'\r'}; break;
            case KeyEvent.VK_TAB:        inner = new byte[]{'\t'}; break;
            case KeyEvent.VK_BACK_SPACE: inner = new byte[]{0x7F}; break;
            case KeyEvent.VK_ESCAPE:     inner = new byte[]{0x1B}; break;
            case KeyEvent.VK_DELETE:     inner = "\u001B[3~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_UP:         inner = "\u001B[A".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_DOWN:       inner = "\u001B[B".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_RIGHT:      inner = "\u001B[C".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_LEFT:       inner = "\u001B[D".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_HOME:       inner = "\u001B[H".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_END:        inner = "\u001B[F".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_PAGE_UP:    inner = "\u001B[5~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_PAGE_DOWN:  inner = "\u001B[6~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_INSERT:     inner = "\u001B[2~".getBytes(StandardCharsets.UTF_8); break;
            // F1-F12
            case KeyEvent.VK_F1:  inner = "\u001BOP".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F2:  inner = "\u001BOQ".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F3:  inner = "\u001BOR".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F4:  inner = "\u001BOS".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F5:  inner = "\u001B[15~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F6:  inner = "\u001B[17~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F7:  inner = "\u001B[18~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F8:  inner = "\u001B[19~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F9:  inner = "\u001B[20~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F10: inner = "\u001B[21~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F11: inner = "\u001B[23~".getBytes(StandardCharsets.UTF_8); break;
            case KeyEvent.VK_F12: inner = "\u001B[24~".getBytes(StandardCharsets.UTF_8); break;
            default: return null;
        }

        if (inner == null) return null;

        // Alt 修饰：在序列前加 ESC
        if (alt) {
            byte[] prefixed = new byte[inner.length + 1];
            prefixed[0] = 0x1B;
            System.arraycopy(inner, 0, prefixed, 1, inner.length);
            return prefixed;
        }
        return inner;
    }

    // ==================== 文件上传 / 下载 ====================

    /**
     * 获取远程 Shell 当前所在目录。
     * 原理：通过 Shell 通道写 $PWD 到临时文件，再用 SFTP 下载回来读取，零歧义。
     */
    /** 通过 Shell 通道获取远程当前目录。
     *  原理：写到 /dev/shm（内存文件系统），SFTP 读回后删除，命令极短仅在终端一闪而过。 */
    private String getSftpPwd(SshConn conn) {
        if (conn.toChannel == null || conn.session == null) return null;
        String tmpName = "/dev/shm/.szh_cwd_" + UUID.randomUUID().toString().substring(0, 6);
        ChannelSftp sftp = null;
        try {
            // 1. 通过 Shell 通道写 $PWD 到内存文件（命令极短）
            conn.toChannel.write(("echo $PWD>" + tmpName + "\n").getBytes(StandardCharsets.UTF_8));
            conn.toChannel.flush();

            // 2. SFTP 读回
            sftp = (ChannelSftp) conn.session.openChannel("sftp");
            sftp.connect(3000);
            long deadline = System.currentTimeMillis() + 2000;
            boolean ready = false;
            while (System.currentTimeMillis() < deadline) {
                try { sftp.stat(tmpName); ready = true; break; }
                catch (Exception ignored) { Thread.sleep(80); }
            }
            if (!ready) return null;

            java.nio.file.Path localTmp = java.nio.file.Files.createTempFile("szh_cwd_", ".txt");
            sftp.get(tmpName, localTmp.toString());
            String cwd = new String(java.nio.file.Files.readAllBytes(localTmp), StandardCharsets.UTF_8).trim();
            try { java.nio.file.Files.deleteIfExists(localTmp); } catch (Exception ignored) {}
            if (!cwd.isEmpty() && cwd.startsWith("/")) return cwd;
        } catch (Exception ignored) {
        } finally {
            if (sftp != null) {
                try { sftp.rm(tmpName); } catch (Exception ignored) {}
                try { sftp.disconnect(); } catch (Exception ignored) {}
            }
        }
        return null;
    }

    /** 旧方法保留兼容 */
    private String getRemotePwd(SshConn conn) {
        return getSftpPwd(conn);
    }

    /** 上传文件到远程当前目录 */
    private void uploadFile() {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) {
            appendTerminal("[未连接到任何主机]\n", C_WARN);
            return;
        }

        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setDialogTitle("选择要上传的文件");
        if (chooser.showOpenDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File[] files = chooser.getSelectedFiles();
        if (files == null || files.length == 0) return;

        Thread.startVirtualThread(() -> {
            // 在后台线程获取远程当前目录，不阻塞 EDT
            String remoteDir = getRemotePwd(conn);
            if (remoteDir == null) {
                appendTerminal("[无法获取远程目录，默认使用 /root]\n", C_WARN);
                remoteDir = "/root";
            }
            ChannelSftp sftp = null;
            try {
                sftp = (ChannelSftp) conn.session.openChannel("sftp");
                sftp.connect(5000);

                for (File file : files) {
                    String remotePath = remoteDir + "/" + file.getName();
                    appendTerminal("[上传: " + file.getName() + " → " + remotePath + "]\n", C_SYS);

                    TransferProgressDialog dlg = new TransferProgressDialog(
                        SwingUtilities.getWindowAncestor(this), "上传 " + file.getName());

                    try {
                        sftp.put(file.getAbsolutePath(), remotePath,
                            new SftpProgressMonitor() {
                                private long total;
                                @Override public void init(int op, String src, String dest, long max) {
                                    total = max;
                                    SwingUtilities.invokeLater(() -> dlg.setVisible(true));
                                }
                                @Override public boolean count(long count) {
                                    dlg.setTarget(count, total);
                                    return !dlg.cancelled;
                                }
                                @Override public void end() {
                                    SwingUtilities.invokeLater(() -> {
                                        dlg.setTarget(total, total);
                                        dlg.markFinished();
                                    });
                                }
                            }, ChannelSftp.OVERWRITE);

                        if (dlg.cancelled) {
                            try { sftp.rm(remotePath); } catch (Exception ignored) {}
                            appendTerminal("[已取消上传: " + file.getName() + "]\n", C_WARN);
                        } else {
                            appendTerminal("[上传完成: " + file.getName() + "]\n", C_RECV);
                        }
                    } catch (Exception e) {
                        // count() 返回 false 会触发 JSch 异常，end() 不会被调用
                        if (dlg.cancelled) {
                            try { sftp.rm(remotePath); } catch (Exception ignored) {}
                            dlg.markFinished();
                            appendTerminal("[已取消上传: " + file.getName() + "]\n", C_WARN);
                        } else {
                            dlg.markFinished();
                            appendTerminal("[上传失败: " + e.getMessage() + "]\n", C_ERR);
                        }
                    }
                }
            } catch (Exception e) {
                appendTerminal("[SFTP 连接失败: " + e.getMessage() + "]\n", C_ERR);
            } finally {
                if (sftp != null) try { sftp.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    /** 下载远程文件 */
    private void downloadFile() {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) {
            appendTerminal("[未连接到任何主机]\n", C_WARN);
            return;
        }

        // 输入远程文件路径（不阻塞 EDT 查 pwd，后面在后台线程补全）
        String remotePath = (String) JOptionPane.showInputDialog(this,
            "输入远程文件路径（绝对路径 或 相对于当前目录的路径）:",
            "下载文件", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (remotePath == null || remotePath.trim().isEmpty()) return;
        remotePath = remotePath.trim();

        // 选择本地保存位置
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择保存位置");
        chooser.setSelectedFile(new File(new File(remotePath).getName()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File localFile = chooser.getSelectedFile();
        if (localFile == null) return;

        // 确认覆盖
        if (localFile.exists()) {
            int ret = JOptionPane.showConfirmDialog(this,
                "文件已存在，是否覆盖？\n" + localFile.getAbsolutePath(),
                "确认覆盖", JOptionPane.YES_NO_OPTION);
            if (ret != JOptionPane.YES_OPTION) return;
        }

        final String finalRemotePath = remotePath;
        Thread.startVirtualThread(() -> {
            // 在后台线程获取远程当前目录，不阻塞 EDT
            String remoteDir = getRemotePwd(conn);
            String resolvedPath = finalRemotePath;
            if (!finalRemotePath.startsWith("/") && remoteDir != null) {
                resolvedPath = remoteDir + "/" + finalRemotePath;
            }
            ChannelSftp sftp = null;
            try {
                sftp = (ChannelSftp) conn.session.openChannel("sftp");
                sftp.connect(5000);

                appendTerminal("[下载: " + resolvedPath + " → " + localFile.getAbsolutePath() + "]\n", C_SYS);

                TransferProgressDialog dlg = new TransferProgressDialog(
                    SwingUtilities.getWindowAncestor(this), "下载 " + new File(finalRemotePath).getName());

                try {
                    sftp.get(resolvedPath, localFile.getAbsolutePath(),
                        new SftpProgressMonitor() {
                            private long total;
                            @Override public void init(int op, String src, String dest, long max) {
                                total = max;
                                SwingUtilities.invokeLater(() -> dlg.setVisible(true));
                            }
                            @Override public boolean count(long count) {
                                dlg.setTarget(count, total);
                                return !dlg.cancelled;
                            }
                            @Override public void end() {
                                SwingUtilities.invokeLater(() -> {
                                    dlg.setTarget(total, total);
                                    dlg.markFinished();
                                });
                            }
                        });

                    if (dlg.cancelled) {
                        try { java.nio.file.Files.deleteIfExists(localFile.toPath()); } catch (Exception ignored) {}
                        appendTerminal("[已取消下载]\n", C_WARN);
                    } else {
                        appendTerminal("[下载完成: " + localFile.getName() + "]\n", C_RECV);
                    }
                } catch (Exception e) {
                    // count() 返回 false 触发 JSch 异常，end() 不会被调用
                    if (dlg.cancelled) {
                        try { java.nio.file.Files.deleteIfExists(localFile.toPath()); } catch (Exception ignored) {}
                        dlg.markFinished();
                        appendTerminal("[已取消下载]\n", C_WARN);
                    } else {
                        dlg.markFinished();
                        appendTerminal("[下载失败: " + e.getMessage() + "]\n", C_ERR);
                    }
                }
            } catch (Exception e) {
                appendTerminal("[SFTP 连接失败: " + e.getMessage() + "]\n", C_ERR);
            } finally {
                if (sftp != null) try { sftp.disconnect(); } catch (Exception ignored) {}
            }
        });
    }

    /** 传输进度对话框 — 带平滑追赶动画 */
    private static class TransferProgressDialog extends JDialog {
        final JProgressBar progressBar;
        final JLabel speedLabel;
        final JButton cancelBtn;
        volatile boolean cancelled;

        // 真实传输进度（由 JSch 回调线程写入，不经过 EDT）
        volatile long targetTransferred;
        volatile long targetTotal;
        volatile boolean transferFinished;

        // 视觉显示进度（仅 EDT 读写）
        private long displayTransferred;
        private final long startTime = System.currentTimeMillis();
        private long lastTargetCount;
        private long lastSpeedTime = startTime;
        private final Timer animateTimer;

        TransferProgressDialog(Window owner, String title) {
            super(owner, title, ModalityType.MODELESS);
            setDefaultCloseOperation(DO_NOTHING_ON_CLOSE);
            setResizable(false);

            JPanel panel = new JPanel(new BorderLayout(8, 8));
            panel.setBorder(BorderFactory.createEmptyBorder(12, 16, 12, 16));

            JLabel titleLabel = new JLabel(title);
            titleLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            panel.add(titleLabel, BorderLayout.NORTH);

            progressBar = new JProgressBar(0, 100);
            progressBar.setStringPainted(true);
            progressBar.setString("准备中...");
            progressBar.setPreferredSize(new Dimension(400, 26));
            panel.add(progressBar, BorderLayout.CENTER);

            speedLabel = new JLabel("等待传输...");
            speedLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));

            cancelBtn = new JButton("取消");
            cancelBtn.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            cancelBtn.addActionListener(e -> {
                cancelled = true;
                cancelBtn.setEnabled(false);
                cancelBtn.setText("取消中...");
            });

            JPanel bottom = new JPanel(new BorderLayout());
            bottom.add(speedLabel, BorderLayout.WEST);
            bottom.add(cancelBtn, BorderLayout.EAST);
            panel.add(bottom, BorderLayout.SOUTH);

            add(panel);
            pack();
            setLocationRelativeTo(owner);

            // 动画定时器：每 40ms 让视觉进度条追赶真实进度
            animateTimer = new Timer(PROGRESS_ANIMATION_INTERVAL_MS, ev -> animateTick());
            animateTimer.start();
        }

        /** JSch 回调线程调用：仅更新目标值，极轻量 */
        void setTarget(long transferred, long total) {
            this.targetTransferred = transferred;
            this.targetTotal = total;
        }

        /** JSch end() 回调：标记传输完成，让动画最终走到 100% */
        void markFinished() {
            this.transferFinished = true;
        }

        /** 动画 tick（EDT 线程） */
        private void animateTick() {
            if (targetTotal <= 0) return;

            // 计算视觉显示值应该追赶到的目标
            long visualTarget = transferFinished ? targetTotal : targetTransferred;

            int targetPct = (int) (visualTarget * 100 / targetTotal);
            int displayPct = (int) (displayTransferred * 100 / targetTotal);

            // 逐步追赶，每次最多前进 MAX_VISUAL_ADVANCE_PCT 百分点
            if (targetPct > displayPct) {
                int advance = Math.min(targetPct - displayPct, MAX_VISUAL_ADVANCE_PCT);
                displayPct = Math.min(displayPct + advance, 100);
                displayTransferred = displayPct * targetTotal / 100;
            }

            // 渲染进度条
            if (targetTotal > 0) {
                progressBar.setValue(displayPct);
                progressBar.setString(formatSize(displayTransferred) + " / " + formatSize(targetTotal)
                    + "  (" + displayPct + "%)");
                progressBar.setIndeterminate(false);
            } else {
                progressBar.setIndeterminate(true);
                progressBar.setString("等待中...");
            }

            // 速度统计（基于真实传输值）
            long now = System.currentTimeMillis();
            long dt = now - lastSpeedTime;
            if (dt >= 1000) {
                long dc = targetTransferred - lastTargetCount;
                double speedBps = dc * 1000.0 / dt;
                speedLabel.setText("速度: " + formatSize((long) speedBps) + "/s  |  已用时: "
                    + formatDuration(now - startTime));
                lastTargetCount = targetTransferred;
                lastSpeedTime = now;
            }

            // 传输已完成且视觉进度已追到 100%，关闭对话框
            if (transferFinished && displayPct >= 100) {
                animateTimer.stop();
                dispose();
            }
        }

        private static String formatDuration(long ms) {
            if (ms < 1000) return ms + "ms";
            long sec = ms / 1000;
            if (sec < 60) return sec + "s";
            return (sec / 60) + "m" + (sec % 60) + "s";
        }
    }

    // ==================== 终端输出 & ANSI ====================

    /** ANSI 标准色映射（暗色背景优化） */
    private static final Color[] ANSI_COLORS = {
        new Color(0x2E3436), // 0 Black
        new Color(0xEF5555), // 1 Red
        new Color(0x55DD55), // 2 Green
        new Color(0xDDDD55), // 3 Yellow
        new Color(0x5599EE), // 4 Blue
        new Color(0xE066E0), // 5 Magenta
        new Color(0x55D0D0), // 6 Cyan
        new Color(0xD3D7CF), // 7 White
    };
    private static final Color[] ANSI_BRIGHT = {
        new Color(0x555753), // 0 Bright Black
        new Color(0xFF6E6E), // 1 Bright Red
        new Color(0x8AFF8A), // 2 Bright Green
        new Color(0xFFFF70), // 3 Bright Yellow
        new Color(0x77AAFF), // 4 Bright Blue
        new Color(0xFF88FF), // 5 Bright Magenta
        new Color(0x88FFFF), // 6 Bright Cyan
        new Color(0xFFFFFF), // 7 Bright White
    };

    /** 追加纯文本到终端（系统消息，单色） */
    private void appendTerminal(String text, Color color) {
        if (terminalArea == null || text == null || text.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            rawAppending = true;
            try {
                StyledDocument doc = terminalArea.getStyledDocument();
                Style style = doc.addStyle("s" + System.nanoTime(), null);
                StyleConstants.setForeground(style, color != null ? color : WHITE);
                StyleConstants.setFontFamily(style, "NSimSun");
                StyleConstants.setFontSize(style, 13);
                doc.insertString(doc.getLength(), text, style);
                terminalArea.setCaretPosition(doc.getLength());
            } catch (BadLocationException ignored) {
            } finally {
                rawAppending = false;
            }
        });
    }

    /** 追加 ANSI 转义文本，解析着色 + 终端控制码，过滤 OSC 序列 */
    private void appendAnsi(String text) {
        if (terminalArea == null || text == null || text.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            rawAppending = true;
            try {
                StyledDocument doc = terminalArea.getStyledDocument();
                int len = text.length();
                int segStart = 0;
                for (int i = 0; i < len; i++) {
                    if (text.charAt(i) == '\033' && i + 1 < len) {
                        char next = text.charAt(i + 1);
                        if (next == '[') {
                            // CSI 序列 \033[...X
                            if (i > segStart) {
                                insertStyled(doc, text.substring(segStart, i));
                            }
                            int end = i + 2;
                            while (end < len && !isCsiTerminator(text.charAt(end))) end++;
                            if (end < len) {
                                String params = text.substring(i + 2, end);
                                applyCsiSequence(doc, params, text.charAt(end));
                                i = end;
                                segStart = end + 1;
                            } else {
                                i = len;
                            }
                        } else if (next == ']') {
                            // OSC 序列 \033]...\007 或 \033]...\033\\
                            // 常见：\033]0;title\007 — 设终端标题，直接丢弃
                            if (i > segStart) {
                                insertStyled(doc, text.substring(segStart, i));
                            }
                            int end = i + 2;
                            while (end < len) {
                                char ec = text.charAt(end);
                                if (ec == '\007' || (ec == '\033' && end + 1 < len && text.charAt(end + 1) == '\\')) {
                                    break;
                                }
                                end++;
                            }
                            if (end < len) {
                                if (text.charAt(end) == '\033') end++; // 跳过 \033\\ 中的 \
                                i = end;
                                segStart = end + 1;
                            } else {
                                i = len;
                            }
                        } else {
                            // 其他双字符 ESC 序列：\033= \033> \033( \033) 等，直接跳过
                            if (i > segStart) {
                                insertStyled(doc, text.substring(segStart, i));
                            }
                            i++; // 跳过 ESC 后的那个字符
                            segStart = i + 1;
                        }
                    }
                }
                if (segStart < len) {
                    insertStyled(doc, text.substring(segStart));
                }
                terminalArea.setCaretPosition(doc.getLength());
            } catch (BadLocationException ignored) {
            } finally {
                rawAppending = false;
            }
        });
    }

    private boolean isCsiTerminator(char c) {
        return (c >= '@' && c <= '~') || c == '`';
    }

    /** CSI 控制序列分发：SGR 颜色码 + 终端控制码 */
    private void applyCsiSequence(StyledDocument doc, String params, char terminator)
            throws BadLocationException {
        // 解析 private marker: ?25h → priv='?', code='h'
        char priv = 0;
        String numPart = params;
        if (!params.isEmpty() && (params.charAt(0) == '?' || params.charAt(0) == '>')) {
            priv = params.charAt(0);
            numPart = params.substring(1);
        }
        String[] parts = numPart.isEmpty() ? new String[]{"0"} : numPart.split(";");
        int n1 = parseIntSafe(parts, 0, 1);
        int n2 = parseIntSafe(parts, 1, 1);

        switch (terminator) {
            // ---- SGR 样式/颜色 ----
            case 'm':
                applySgr(params.isEmpty() ? "0" : params);
                break;

            // ---- 清屏 ----
            case 'J':
                if (n1 == 2 || n1 == 3) {
                    // \033[2J 清除整个屏幕, \033[3J 清除滚动缓冲
                    doc.remove(0, doc.getLength());
                    resetAnsiState();
                }
                break;

            // ---- 清行 ----
            case 'K':
                if (n1 == 0 || n1 == 2) {
                    // \033[K / \033[2K 清除当前行（简化：不处理，pager 用不到）
                }
                break;

            // ---- 光标定位 ----
            case 'H': case 'f':
                // \033[n;mH 光标移动到 (n,m)。pager 里用 \033[H 回到左上角
                // 简化：追加换行来区分不同"页面"
                if (n1 <= 1) {
                    // 光标归位 → 追加分割线
                    // 仅在文档非空且末尾不是换行时加分隔
                    if (doc.getLength() > 0) {
                        String last = doc.getText(doc.getLength() - 1, 1);
                        if (!"\n".equals(last)) {
                            doc.insertString(doc.getLength(), "\n", null);
                        }
                    }
                }
                break;

            // ---- 光标移动 ----
            case 'A': case 'B': case 'C': case 'D':
                // 光标上/下/右/左移动 → 忽略
                break;

            // ---- DEC 私有模式 ----
            case 'h': case 'l':
                if (priv == '?') {
                    if (n1 == 1049 || n1 == 47 || n1 == 1047) {
                        // 交替屏幕开关：进入时清屏，离开时也清屏（回到主屏）
                        if (terminator == 'h') {
                            doc.remove(0, doc.getLength());
                            resetAnsiState();
                        } else {
                            doc.remove(0, doc.getLength());
                            resetAnsiState();
                        }
                    }
                    // ?25: 光标显示/隐藏 → 忽略
                }
                break;
        }
    }

    private static int parseIntSafe(String[] parts, int idx, int def) {
        if (idx >= parts.length || parts[idx].isEmpty()) return def;
        try { return Integer.parseInt(parts[idx]); } catch (NumberFormatException e) { return def; }
    }

    /** 仅处理 SGR 颜色/样式 */
    private void applySgr(String params) {
        for (String p : params.split(";")) {
            int code;
            try { code = Integer.parseInt(p); } catch (NumberFormatException e) { continue; }
            switch (code) {
                case 0:  ansiFg = WHITE; ansiBg = null; ansiBold = false; break;
                case 1:  ansiBold = true;  break;
                case 22: ansiBold = false; break;
                case 30: case 31: case 32: case 33:
                case 34: case 35: case 36: case 37:
                    ansiFg = ANSI_COLORS[code - 30]; break;
                case 39: ansiFg = WHITE; break;
                case 40: case 41: case 42: case 43:
                case 44: case 45: case 46: case 47:
                    ansiBg = ANSI_COLORS[code - 40]; break;
                case 49: ansiBg = null; break;
                case 90: case 91: case 92: case 93:
                case 94: case 95: case 96: case 97:
                    ansiFg = ANSI_BRIGHT[code - 90]; break;
                case 100: case 101: case 102: case 103:
                case 104: case 105: case 106: case 107:
                    ansiBg = ANSI_BRIGHT[code - 100]; break;
            }
        }
    }

    private void insertStyled(StyledDocument doc, String seg) throws BadLocationException {
        if (seg.isEmpty()) return;
        StringBuilder clean = new StringBuilder(seg.length());
        for (int i = 0; i < seg.length(); i++) {
            char c = seg.charAt(i);
            if (c == '\b') {
                // 退格：删除文档最后一个字符
                if (doc.getLength() > 0) {
                    doc.remove(doc.getLength() - 1, 1);
                }
            } else if (c == '\r') {
                if (i + 1 < seg.length() && seg.charAt(i + 1) == '\n') {
                    // \r\n → 正常换行
                    clean.append('\n');
                    i++;
                } else {
                    // 单独的 \r：回到行首，清掉当前行已输出的内容
                    // 先把已积累的 clean 文本写入文档
                    if (clean.length() > 0) {
                        Style s = makeCurrentStyle(doc);
                        doc.insertString(doc.getLength(), clean.toString(), s);
                        clean.setLength(0);
                    }
                    // 找到最后一个 \n，删除它之后的所有内容（回到行首）
                    int docLen = doc.getLength();
                    String full = doc.getText(0, docLen);
                    int lastNL = full.lastIndexOf('\n');
                    int from = lastNL >= 0 ? lastNL + 1 : 0;
                    if (docLen > from) {
                        doc.remove(from, docLen - from);
                    }
                }
            } else if (c >= ' ' || c == '\n' || c == '\t') {
                // 只保留可打印字符 + 换行 + 制表符
                clean.append(c);
            }
            // 其他控制字符（0x00-0x1F、0x7F）直接丢弃
        }
        if (clean.length() == 0) return;
        Style style = makeCurrentStyle(doc);
        doc.insertString(doc.getLength(), clean.toString(), style);
    }

    /** 用当前 ANSI 状态创建一个 Style */
    private Style makeCurrentStyle(StyledDocument doc) {
        Style style = doc.addStyle("a" + System.nanoTime(), null);
        StyleConstants.setForeground(style, ansiFg);
        StyleConstants.setFontFamily(style, "NSimSun");
        StyleConstants.setFontSize(style, 13);
        StyleConstants.setBold(style, ansiBold);
        if (ansiBg != null) StyleConstants.setBackground(style, ansiBg);
        return style;
    }

    // ==================== SSH 连接核心 ====================

    private void openConnection(SshConn conn) {
        Thread.ofVirtual().name("ssh-" + conn.name).start(() -> {
            try {
                appendTerminal("[正在连接 " + conn.name + " (" + conn.host + ":" + conn.port + ")...]\n", C_SYS);

                JSch jsch = new JSch();
                conn.jsch = jsch;

                // ---- 主机密钥仓库：存待校验密钥供 UserInfo 对话框使用 ----
                final byte[][] pendingKey = new byte[1][];
                final String[] pendingHost = new String[1];

                jsch.setHostKeyRepository(new HostKeyRepository() {
                    @Override
                    public int check(String host, byte[] key) {
                        String fp = computeFingerprint(key);
                        String saved = knownHosts.get(host);
                        if (saved != null && saved.equals(fp)) {
                            return HostKeyRepository.OK;
                        }
                        if (saved != null) {
                            return HostKeyRepository.CHANGED;
                        }
                        pendingKey[0] = key;
                        pendingHost[0] = host;
                        return HostKeyRepository.NOT_INCLUDED;
                    }

                    @Override public void add(HostKey hk, UserInfo ui) {}
                    @Override public HostKey[] getHostKey() { return new HostKey[0]; }
                    @Override public HostKey[] getHostKey(String h, String t) { return new HostKey[0]; }
                    @Override public void remove(String h, String t) {}
                    @Override public void remove(String h, String t, byte[] k) {}
                    @Override public String getKnownHostsRepositoryID() { return "CoreTools"; }
                });

                // ---- 会话 ----
                Session session = jsch.getSession(conn.username, conn.host, conn.port);
                session.setPassword(conn.password);
                session.setConfig("StrictHostKeyChecking", "ask");

                session.setUserInfo(new UserInfo() {
                    @Override public String getPassphrase() { return null; }
                    @Override public String getPassword() { return null; }
                    @Override public boolean promptPassword(String msg) { return false; }
                    @Override public boolean promptPassphrase(String msg) { return false; }
                    @Override public void showMessage(String msg) {}

                    @Override
                    public boolean promptYesNo(String msg) {
                        byte[] key = pendingKey[0];
                        String host = pendingHost[0] != null ? pendingHost[0] : (conn.host + ":" + conn.port);
                        String fp = key != null ? computeFingerprint(key) : "SHA256:???";
                        String keyType = key != null ? getKeyType(key) : "SSH";
                        boolean[] box = {false, false};
                        try {
                            SwingUtilities.invokeAndWait(() -> {
                                HostKeyVerifyDialog dlg = new HostKeyVerifyDialog(
                                    SwingUtilities.getWindowAncestor(SshPanel.this),
                                    conn.host, conn.port, fp, keyType);
                                dlg.setVisible(true);
                                box[0] = dlg.accepted;
                                box[1] = dlg.savePermanently;
                            });
                        } catch (Exception ex) {
                            return false;
                        }
                        if (box[0] && box[1]) {
                            knownHosts.put(host, fp);
                            saveKnownHosts();
                        }
                        return box[0];
                    }
                });

                session.connect(8000);

                // ---- Shell Channel ----
                ChannelShell channel = (ChannelShell) session.openChannel("shell");
                channel.setPtyType("xterm-256color");
                channel.setPtySize(160, 30, 800, 600);
                channel.setEnv("LANG", "zh_CN.UTF-8");

                InputStream recvStream = channel.getInputStream();
                OutputStream toChannelOs = channel.getOutputStream();

                channel.connect(8000);

                conn.session = session;
                conn.channel = channel;
                conn.toChannel = toChannelOs;
                conn.connected = true;

                SwingUtilities.invokeLater(() -> {
                    synchronized (connLock) { currentConn = conn; }
                    updateUiConnected(conn);
                    connList.repaint();
                });
                appendTerminal("[连接成功: " + conn.name + "]\n\n", C_RECV);

                // 首次加载远程目录
                if (dirRefreshTimer != null) dirRefreshTimer.restart();

                // 读线程 — ANSI 着色输出
                byte[] buf = new byte[4096];
                try {
                    while (conn.connected) {
                        int len = recvStream.read(buf);
                        if (len == -1) break;
                        String text = new String(buf, 0, len, StandardCharsets.UTF_8);
                        appendAnsi(text);
                    }
                } catch (IOException e) {
                    if (conn.connected) {
                        appendTerminal("[连接中断: " + e.getMessage() + "]\n", C_ERR);
                    }
                }

            } catch (Exception e) {
                appendTerminal("[连接失败: " + e.getMessage() + "]\n", C_ERR);
                conn.connected = false;
            } finally {
                closeConnectionInternal(conn);
            }
        });
    }

    private void closeConnection(SshConn conn) {
        appendTerminal("[正在关闭连接: " + conn.name + "]\n", C_SYS);
        closeConnectionInternal(conn);
    }

    private void closeConnectionInternal(SshConn conn) {
        conn.connected = false;
        try {
            if (conn.toChannel != null) {
                conn.toChannel.close();
                conn.toChannel = null;
            }
        } catch (Exception ignored) {}
        try {
            if (conn.channel != null) {
                conn.channel.disconnect();
                conn.channel = null;
            }
        } catch (Exception ignored) {}
        try {
            if (conn.session != null) {
                conn.session.disconnect();
                conn.session = null;
            }
        } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            synchronized (connLock) {
                if (currentConn == conn) currentConn = null;
            }
            updateUiDisconnected();
            connList.repaint();
        });
    }

    // ==================== UI 状态 ====================

    private void updateUiConnected(SshConn conn) {
        statusLabel.setText("\u25CF 已连接");
        statusLabel.setForeground(C_RECV);
        connInfoLabel.setText(conn.name + "  " + conn.username + "@" + conn.host + ":" + conn.port);
        resetAnsiState();
        commandInput.setEnabled(true);
        commandInput.requestFocusInWindow();
    }

    private void updateUiDisconnected() {
        statusLabel.setText("未连接");
        statusLabel.setForeground(C_WARN);
        connInfoLabel.setText("");
        commandInput.setEnabled(false);
        // 清空控制台
        terminalArea.setText("");
        // 清空远程目录树
        dirPathLabel.setText("未连接");
        dirRootNode.removeAllChildren();
        dirTreeModel.reload();
    }

    private void resetAnsiState() {
        ansiFg = WHITE;
        ansiBg = null;
        ansiBold = false;
    }

    // ==================== 配置持久化 ====================

    private static final String CONN_COUNT_KEY = "ssh.conn.count";

    @Override
    public void loadConfig(ConfigManager cfg) {
        this.configManager = cfg;
        int count = Integer.parseInt(cfg.get(CONN_COUNT_KEY, "0"));
        for (int i = 0; i < count; i++) {
            String name = cfg.get("ssh.conn." + i + ".name", "");
            if (name.isEmpty()) continue;
            SshConn conn = new SshConn();
            conn.name = name;
            conn.host = cfg.get("ssh.conn." + i + ".host", "");
            conn.port = Integer.parseInt(cfg.get("ssh.conn." + i + ".port", "22"));
            conn.username = cfg.get("ssh.conn." + i + ".user", "root");
            conn.password = cfg.get("ssh.conn." + i + ".pass", "");
            connections.add(conn);
            connListModel.addElement(conn);
        }
        if (!connections.isEmpty() && connList.getSelectedIndex() < 0) {
            connList.setSelectedIndex(0);
        }
        // 加载已知主机密钥
        knownHosts.clear();
        int hkCount = Integer.parseInt(cfg.get(HOST_KEY_COUNT, "0"));
        for (int i = 0; i < hkCount; i++) {
            String addr = cfg.get("ssh.hostkey." + i + ".addr", "");
            String fp = cfg.get("ssh.hostkey." + i + ".fingerprint", "");
            if (!addr.isEmpty() && !fp.isEmpty()) {
                knownHosts.put(addr, fp);
            }
        }
    }

    @Override
    public void saveConfig(ConfigManager cfg) {
        this.configManager = cfg;
        cfg.set(CONN_COUNT_KEY, String.valueOf(connections.size()));
        for (int i = 0; i < connections.size(); i++) {
            SshConn c = connections.get(i);
            cfg.set("ssh.conn." + i + ".name", c.name);
            cfg.set("ssh.conn." + i + ".host", c.host);
            cfg.set("ssh.conn." + i + ".port", String.valueOf(c.port));
            cfg.set("ssh.conn." + i + ".user", c.username);
            cfg.set("ssh.conn." + i + ".pass", c.password);
        }
        // 保存已知主机密钥
        cfg.set(HOST_KEY_COUNT, String.valueOf(knownHosts.size()));
        int idx = 0;
        for (Map.Entry<String, String> e : knownHosts.entrySet()) {
            cfg.set("ssh.hostkey." + idx + ".addr", e.getKey());
            cfg.set("ssh.hostkey." + idx + ".fingerprint", e.getValue());
            idx++;
        }
    }

    private void saveConnectionsToConfig() {
        if (configManager != null) {
            saveConfig(configManager);
            configManager.save();
        }
    }

    private void saveKnownHosts() {
        saveConnectionsToConfig();
    }

    /** 释放所有连接 */
    public void shutdown() {
        for (SshConn conn : new ArrayList<>(connections)) {
            if (conn.connected) closeConnectionInternal(conn);
        }
    }

    // ==================== 主机密钥验证对话框 ====================

    private static class HostKeyVerifyDialog extends JDialog {
        boolean accepted;
        boolean savePermanently;
        Window owner;

        HostKeyVerifyDialog(Window owner, String host, int port, String fingerprint, String keyType) {
            super(owner, "安全警告 - 主机密钥验证", ModalityType.APPLICATION_MODAL);
            this.owner = owner;
            init(host, port, fingerprint, keyType);
        }

        private void init(String host, int port, String fingerprint, String keyType) {
            JPanel panel = new JPanel(new BorderLayout(12, 12));
            panel.setBackground(C_BG);
            panel.setBorder(BorderFactory.createEmptyBorder(16, 16, 16, 16));

            // 图标 + 警告文字
            JLabel warnIcon = new JLabel(UIManager.getIcon("OptionPane.warningIcon"));
            JLabel titleLabel = new JLabel("<html><b>主机密钥未注册</b></html>");
            titleLabel.setFont(FONT_TEXT.deriveFont(Font.BOLD, 15f));
            titleLabel.setForeground(C_WARN);

            JPanel topPanel = new JPanel(new BorderLayout(12, 0));
            topPanel.setOpaque(false);
            topPanel.add(warnIcon, BorderLayout.WEST);
            topPanel.add(titleLabel, BorderLayout.CENTER);

            // 详情
            JTextArea detailArea = new JTextArea(
                "主机 " + host + ":" + port + " 的密钥未被注册过。\n" +
                "无法验证该主机是否为你想连接的那台服务器。\n\n" +
                "密钥类型: " + keyType + "\n" +
                "密钥指纹:\n" +
                "    " + fingerprint
            );
            detailArea.setEditable(false);
            detailArea.setBackground(C_BG);
            detailArea.setFont(FONT_TEXT);
            detailArea.setForeground(Color.WHITE);

            // 按钮栏
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            btnPanel.setOpaque(false);
            JButton btnAcceptOnce = new JButton("只接受本次");
            JButton btnAcceptSave = new JButton("接收并保存");
            JButton btnCancel = new JButton("取消");

            btnAcceptOnce.addActionListener(e -> {
                accepted = true;
                savePermanently = false;
                dispose();
            });
            btnAcceptSave.addActionListener(e -> {
                accepted = true;
                savePermanently = true;
                dispose();
            });
            btnCancel.addActionListener(e -> {
                accepted = false;
                dispose();
            });

            // 允许 Enter 确认第一个按钮，Esc 取消
            getRootPane().setDefaultButton(btnAcceptOnce);
            KeyStroke escKey = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            getRootPane().registerKeyboardAction(
                e -> btnCancel.doClick(), escKey, JComponent.WHEN_IN_FOCUSED_WINDOW
            );

            btnPanel.add(btnAcceptOnce);
            btnPanel.add(btnAcceptSave);
            btnPanel.add(btnCancel);

            panel.add(topPanel, BorderLayout.NORTH);
            panel.add(new JScrollPane(detailArea), BorderLayout.CENTER);
            panel.add(btnPanel, BorderLayout.SOUTH);

            add(panel);
            setSize(520, 280);
            setResizable(false);
            setLocationRelativeTo(this.owner);
        }
    }

    // ==================== 连接编辑对话框 ====================

    private static class SshConnEditDialog extends JDialog {
        boolean confirmed;

        private final JTextField nameField = new JTextField(15);
        private final JTextField hostField = new JTextField(15);
        private final JTextField portField = new JTextField("22", 5);
        private final JTextField userField = new JTextField("root", 10);
        private final JPasswordField passField = new JPasswordField(15);

        SshConnEditDialog(Window owner, SshConn existing) {
            super(owner, existing == null ? "新建 SSH 连接" : "编辑 SSH 连接",
                    ModalityType.APPLICATION_MODAL);
            init(existing);
        }

        private void init(SshConn existing) {
            JPanel form = new JPanel(new GridBagLayout());
            form.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));
            Insets ins = new Insets(4, 4, 4, 4);

            GridBagConstraints lc = new GridBagConstraints();
            lc.gridx = 0; lc.anchor = GridBagConstraints.EAST; lc.insets = ins;
            GridBagConstraints fc = new GridBagConstraints();
            fc.gridx = 1; fc.fill = GridBagConstraints.HORIZONTAL; fc.weightx = 1; fc.insets = ins;

            addRow(form, lc, fc, "名称:", nameField, 0);
            addRow(form, lc, fc, "主机:", hostField, 1);
            addRow(form, lc, fc, "端口:", portField, 2);
            addRow(form, lc, fc, "用户:", userField, 3);
            addRow(form, lc, fc, "密码:", passField, 4);

            if (existing != null) {
                nameField.setText(existing.name);
                hostField.setText(existing.host);
                portField.setText(String.valueOf(existing.port));
                userField.setText(existing.username);
                passField.setText(existing.password);
            }

            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
            JButton okBtn = new JButton("确定");
            JButton cancelBtn = new JButton("取消");
            okBtn.addActionListener(e -> {
                if (nameField.getText().trim().isEmpty() || hostField.getText().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(this, "名称和主机不能为空", "提示", JOptionPane.WARNING_MESSAGE);
                    return;
                }
                confirmed = true;
                dispose();
            });
            cancelBtn.addActionListener(e -> dispose());
            btnPanel.add(okBtn);
            btnPanel.add(cancelBtn);

            JPanel wrapper = new JPanel(new BorderLayout());
            wrapper.add(form, BorderLayout.CENTER);
            wrapper.add(btnPanel, BorderLayout.SOUTH);
            add(wrapper);

            pack();
            setLocationRelativeTo(getOwner());
            setResizable(false);
        }

        private void addRow(JPanel p, GridBagConstraints lc, GridBagConstraints fc, String label, JComponent comp, int row) {
            lc.gridy = row; fc.gridy = row;
            p.add(new JLabel(label), lc);
            p.add(comp, fc);
        }

        SshConn getConnection() {
            SshConn c = new SshConn();
            c.name = nameField.getText().trim();
            c.host = hostField.getText().trim();
            c.port = Integer.parseInt(portField.getText().trim());
            c.username = userField.getText().trim();
            c.password = new String(passField.getPassword());
            return c;
        }
    }
}
