package com.szh.ui.panel;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;
import com.szh.utils.ThreadPoolUtil;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelExec;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.channel.ClientChannelEvent;
import org.apache.sshd.client.future.ConnectFuture;
import org.apache.sshd.client.session.ClientSession;
import org.apache.sshd.sftp.client.SftpClient;
import org.apache.sshd.sftp.client.SftpClientFactory;

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
        transient ClientSession session;
        transient ChannelShell channel;
        transient OutputStream toChannel;
        transient SshClient client;
        /** 该连接的全部终端输出（含 ANSI 转义码），用于切换连接时恢复显示 */
        transient final StringBuffer terminalBuffer = new StringBuffer();
        /** 该连接的远程当前工作目录，切换时保存/恢复 */
        transient String pwdCache;

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
    private volatile String cachedRemotePwd; // 本地推算的远程当前目录，避免每次刷新都发 pwd 命令到终端

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

                // Backspace / Delete: 消费事件，交给 JTextField 默认 Action 处理
                // 不消费会导致 keyTyped 收到 \b / \u007F 作为可打印字符插入文本
                if (e.getKeyCode() == KeyEvent.VK_BACK_SPACE || e.getKeyCode() == KeyEvent.VK_DELETE) {
                    // 不 consume，让 JTextField 的 DeletePrevCharAction / DeleteNextCharAction 正常执行
                    return;
                }

                switch (e.getKeyCode()) {
                    case KeyEvent.VK_TAB:
                        e.consume();
                        // Tab: 先把输入框内容发给远程 shell，再发 \t 触发补全
                        String tabText = commandInput.getText();
                        if (!tabText.isEmpty()) {
                            sendToChannel((tabText + "\t").getBytes(StandardCharsets.UTF_8));
                            commandInput.setText("");
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
                        // 解析 cd 命令更新缓存目录，立刻刷新目录浏览器
                        if (!text.isEmpty()) {
                            updateCachedPwdFromCommand(text);
                            refreshDirectoryBrowser(false);
                        }
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

            @Override
            public void keyTyped(KeyEvent e) {
                char c = e.getKeyChar();
                // 阻止 Backspace(\b)、Delete(\u007f) 等控制字符被当作可打印字符插入文本框
                if (c == '\b' || c == '\u007f' || c < ' ') {
                    e.consume();
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

    /** 刷新目录浏览器。始终使用本地缓存的 cachedRemotePwd，不查询远程（exec 通道的 PWD 并非交互式 shell 的工作目录）。 */
    private void refreshDirectoryBrowser(boolean forceQuery) {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) return;

        ThreadPoolUtil.submitVirtual(() -> {
            String cwd = cachedRemotePwd;
            if (cwd == null) {
                // 首次连接：用 $HOME 作为初始目录
                cwd = getRemoteHome(conn.session);
                if (cwd != null) cachedRemotePwd = cwd;
            }
            if (cwd == null) {
                SwingUtilities.invokeLater(() -> dirPathLabel.setText("获取目录失败"));
                return;
            }

            List<RemoteFileNode> children = new ArrayList<>();
            SftpClient sftp = null;
            SftpClient.Handle dirHandle = null;
            try {
                sftp = SftpClientFactory.instance().createSftpClient(conn.session);
                dirHandle = sftp.openDir(cwd);
                for (SftpClient.DirEntry entry : sftp.listDir(dirHandle)) {
                    String name = entry.getFilename();
                    if (".".equals(name) || "..".equals(name)) continue;
                    SftpClient.Attributes attrs = entry.getAttributes();
                    children.add(new RemoteFileNode(
                            name,
                            cwd + "/" + name,
                            attrs.isDirectory(),
                            attrs.isSymbolicLink(),
                            attrs.getSize(),
                            formatUnixPerms(attrs.getPermissions())
                    ));
                }
            } catch (Exception e) {
                final String errPath = cwd;
                SwingUtilities.invokeLater(() ->
                    dirPathLabel.setText(errPath + "  (无权限)"));
                return;
            } finally {
                if (dirHandle != null) try { sftp.close(dirHandle); } catch (Exception ignored) {}
                if (sftp != null) try { sftp.close(); } catch (Exception ignored) {}
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

    /** 将 Unix 权限位转为字符串，如 -rwxr-xr-x */
    private static String formatUnixPerms(int perms) {
        char[] c = new char[10];
        c[0] = (perms & 0040000) != 0 ? 'd' : (perms & 0120000) != 0 ? 'l' : '-';
        c[1] = (perms & 0400) != 0 ? 'r' : '-';
        c[2] = (perms & 0200) != 0 ? 'w' : '-';
        c[3] = (perms & 0100) != 0 ? (perms & 04000) != 0 ? 's' : 'x' : '-';
        c[4] = (perms & 0040) != 0 ? 'r' : '-';
        c[5] = (perms & 0020) != 0 ? 'w' : '-';
        c[6] = (perms & 0010) != 0 ? (perms & 02000) != 0 ? 's' : 'x' : '-';
        c[7] = (perms & 0004) != 0 ? 'r' : '-';
        c[8] = (perms & 0002) != 0 ? 'w' : '-';
        c[9] = (perms & 0001) != 0 ? (perms & 01000) != 0 ? 't' : 'x' : '-';
        return new String(c);
    }

    /** 解析终端 cd 命令，更新 cachedRemotePwd */
    private void updateCachedPwdFromCommand(String cmd) {
        String trimmed = cmd.trim();
        // 处理命令中的 ; 分隔（如 cd /opt; ls），仅处理 cd 部分
        int semiIdx = trimmed.indexOf(';');
        String cdPart = semiIdx > 0 ? trimmed.substring(0, semiIdx).trim() : trimmed;

        if (cdPart.equals("cd") || cdPart.equals("cd ~")) {
            // cd 不带参数 / cd ~ → HOME
            SshConn conn;
            synchronized (connLock) { conn = currentConn; }
            if (conn != null && conn.session != null) {
                String home = getRemoteHome(conn.session);
                if (home != null) cachedRemotePwd = home;
            }
            return;
        }
        if (!cdPart.startsWith("cd ")) return;

        String target = cdPart.substring(3).trim();
        // 去外层引号
        if (target.length() >= 2) {
            char first = target.charAt(0), last = target.charAt(target.length() - 1);
            if ((first == '"' && last == '"') || (first == '\'' && last == '\'')) {
                target = target.substring(1, target.length() - 1);
            }
        }
        if (target.isEmpty()) return;

        // 处理 ~ 开头
        if (target.startsWith("~/") || target.equals("~")) {
            SshConn conn;
            synchronized (connLock) { conn = currentConn; }
            String home = getRemoteHome(conn != null ? conn.session : null);
            if (home == null) return;
            target = target.equals("~") ? home : home + target.substring(1);
        }

        if (target.startsWith("/")) {
            cachedRemotePwd = target;
        } else {
            // 相对路径
            String base = cachedRemotePwd != null ? cachedRemotePwd : "/";
            if (target.equals(".")) return;
            if (target.equals("-")) return; // cd - 不处理
            // 逐段解析 .. 和路径段
            String resolved = resolveRelativePath(base, target);
            if (resolved != null) cachedRemotePwd = resolved;
        }
    }

    /** 解析相对路径，返回规范化后的绝对路径 */
    private static String resolveRelativePath(String base, String relative) {
        String[] baseParts = base.equals("/") ? new String[0] : base.split("/");
        java.util.ArrayDeque<String> stack = new java.util.ArrayDeque<>();
        if (baseParts.length > 0) {
            for (String p : baseParts) {
                if (p.isEmpty()) continue;
                stack.add(p);
            }
        }
        for (String seg : relative.split("/")) {
            if (seg.isEmpty() || seg.equals(".")) continue;
            if (seg.equals("..")) {
                if (!stack.isEmpty()) stack.removeLast();
            } else {
                stack.add(seg);
            }
        }
        StringBuilder sb = new StringBuilder("/");
        for (String s : stack) sb.append(s).append("/");
        if (sb.length() > 1) sb.setLength(sb.length() - 1);
        return sb.toString();
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
            refreshDirectoryBrowser(false);
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
                refreshDirectoryBrowser(false);
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
        ThreadPoolUtil.submitVirtual(() -> {
            // 优先通过交互式 shell 执行 pwd 获取真实当前目录
            String cwd = getRemotePwdViaShell(conn.session, conn.toChannel);
            if (cwd == null) {
                cwd = cachedRemotePwd;
            } else {
                cachedRemotePwd = cwd;
            }
            if (cwd == null) {
                cwd = getRemoteHome(conn.session);
                if (cwd != null) cachedRemotePwd = cwd;
            }
            if (cwd == null) {
                appendTerminal("[无法获取远程目录]\n", C_ERR);
                return;
            }
            appendTerminal("[上传目标: " + cwd + "/]\n", C_SYS);

            // 预检写权限
            java.util.concurrent.atomic.AtomicReference<String> permErr = new java.util.concurrent.atomic.AtomicReference<>();
            if (!checkWritePermission(conn.session, cwd, permErr)) {
                appendTerminal("[无写权限: " + cwd + "/ — " + permErr.get() + "]\n", C_ERR);
                appendTerminal("[提示: 执行 sudo chown szh " + cwd + " 或 cd 到有权限的目录]\n", C_WARN);
                return;
            }

            for (File file : files) {
                if (!file.isFile()) continue;
                String remotePath = cwd + "/" + file.getName();
                appendTerminal("[上传: " + file.getName() + " → " + remotePath + "]\n", C_SYS);
                TransferProgressDialog dlg = new TransferProgressDialog(
                        SwingUtilities.getWindowAncestor(this), "上传 " + file.getName());
                java.util.concurrent.atomic.AtomicReference<String> errRef = new java.util.concurrent.atomic.AtomicReference<>();
                if (uploadViaSftp(conn.session, file, remotePath, dlg, errRef)) {
                    dlg.markFinished();
                    appendTerminal("[上传完成: " + file.getName() + "]\n", C_RECV);
                } else {
                    dlg.markFinished();
                    String detail = errRef.get();
                    appendTerminal("[上传失败: " + file.getName() + (detail != null ? " — " + detail : "") + "]\n", C_ERR);
                }
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

        ThreadPoolUtil.submitVirtual(() -> {
            SftpClient sftp = null;
            TransferProgressDialog dlg = null;
            try {
                sftp = SftpClientFactory.instance().createSftpClient(conn.session);
                long fileSize = sftp.stat(remotePath).getSize();

                dlg = new TransferProgressDialog(
                        SwingUtilities.getWindowAncestor(this), "下载 " + fileName);
                dlg.setTarget(0, fileSize > 0 ? fileSize : 1);
                TransferProgressDialog finalDlg = dlg;
                SwingUtilities.invokeLater(() -> finalDlg.setVisible(true));

                long total = fileSize;
                try (InputStream in = sftp.read(remotePath);
                     OutputStream out = new FileOutputStream(localFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long read = 0;
                    while ((n = in.read(buf)) != -1 && !dlg.cancelled) {
                        out.write(buf, 0, n);
                        read += n;
                        dlg.setTarget(read, total);
                    }
                    if (dlg.cancelled) {
                        try { java.nio.file.Files.deleteIfExists(localFile.toPath()); } catch (Exception ignored) {}
                        dlg.markFinished();
                        appendTerminal("[已取消下载: " + fileName + "]\n", C_WARN);
                    } else {
                        dlg.markFinished();
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
                if (sftp != null) try { sftp.close(); } catch (Exception ignored) {}
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

        ThreadPoolUtil.submitVirtual(() -> {
            SftpClient sftp = null;
            try {
                sftp = SftpClientFactory.instance().createSftpClient(conn.session);
                sftp.remove(remotePath);
                appendTerminal("[已删除: " + fileName + "]\n", C_SYS);
                refreshDirectoryBrowser(false);
            } catch (Exception e) {
                appendTerminal("[删除失败: " + e.getMessage() + "]\n", C_ERR);
            } finally {
                if (sftp != null) try { sftp.close(); } catch (Exception ignored) {}
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
            // 把对话框里的新值写回原对象
            SshConn updated = dlg.getConnection();
            conn.name = updated.name;
            conn.host = updated.host;
            conn.port = updated.port;
            conn.username = updated.username;
            conn.password = updated.password;
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
            // 已连接 → 切换过去
            if (conn == currentConn) {
                appendTerminal("[当前已是连接 " + conn.name + "]\n", C_WARN);
                return;
            }
            switchToConnection(conn);
            return;
        }
        openConnection(conn);
    }

    /** 切换到已打开的目标连接（保持各自终端内容独立） */
    private void switchToConnection(SshConn conn) {
        if (conn == null || !conn.connected || conn == currentConn) return;
        // 保存旧连接的当前目录
        SshConn old;
        synchronized (connLock) {
            old = currentConn;
            if (old != null) old.pwdCache = cachedRemotePwd;
            currentConn = conn;
        }
        // 恢复目标连接的缓存目录
        cachedRemotePwd = conn.pwdCache;
        // 兜底退出 RAW 模式
        exitRawMode();
        resetAnsiState();
        // 清空终端，恢复目标连接的 buffer 内容
        try {
            terminalArea.setText("");
        } catch (Exception ignored) {}
        if (conn.terminalBuffer.length() > 0) {
            appendAnsiRaw(conn.terminalBuffer.toString());
        }
        // 更新状态栏
        statusLabel.setText("\u25CF 已连接");
        statusLabel.setForeground(C_RECV);
        connInfoLabel.setText(conn.name + "  " + conn.username + "@" + conn.host + ":" + conn.port);
        commandInput.setEnabled(true);
        commandInput.setText("");
        commandHistory.clear();
        historyIndex = 0;
        commandInput.requestFocusInWindow();
        // 刷新目录浏览器（查询目标连接的工作目录）
        refreshDirectoryBrowser(false);
        connList.repaint();
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
        rawMode = false;
        if (rawModeKeyListener != null) {
            terminalArea.removeKeyListener(rawModeKeyListener);
            rawModeKeyListener = null;
        }
        // 移除文档过滤器（即使 rawMode 为 false 也确保清理）
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
        commandInput.putClientProperty("JTextField.placeholderText", "输入命令，按 Tab 补全 / Enter 执行...");
        commandInput.requestFocusInWindow();
        // 同步按钮状态，避免 toggle 事件触发递归（rawMode 已是 false，toggleRawMode 直接 return）
        if (rawModeBtn.isSelected()) {
            rawModeBtn.setSelected(false);
        }
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

    /** 通过 exec 获取远程 $HOME——纯 exec 通道，完全绕过 SFTP */
    private String getRemoteHome(ClientSession session) {
        ChannelExec exec = null;
        try {
            exec = session.createExecChannel("echo $HOME");
            exec.open().verify(5000);
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream in = exec.getInvertedOut();
            byte[] buf = new byte[256];
            int n;
            while ((n = in.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            exec.close(true);
            String home = baos.toString(StandardCharsets.UTF_8).trim();
            if (home.startsWith("/")) return home;
        } catch (Exception ignored) {
        } finally {
            if (exec != null && exec.isOpen()) try { exec.close(true); } catch (Exception ignored) {}
        }
        return null;
    }

    /** 通过交互式 shell 获取远程当前工作目录——写入临时文件，再用 SFTP 读回。
     *  避免了 exec 通道 CWD ≠ 交互式 Shell CWD 的问题。 */
    private String getRemotePwdViaShell(ClientSession session, OutputStream toChannel) {
        try {
            toChannel.write("pwd > /tmp/.ssh_panel_pwd\n".getBytes(StandardCharsets.UTF_8));
            toChannel.flush();
            // 等待 shell 执行 pwd 命令
            Thread.sleep(500);
            // 通过 SFTP 读取结果
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            try {
                try (InputStream in = sftp.read("/tmp/.ssh_panel_pwd")) {
                    ByteArrayOutputStream baos = new ByteArrayOutputStream();
                    byte[] buf = new byte[512];
                    int n;
                    while ((n = in.read(buf)) != -1) {
                        baos.write(buf, 0, n);
                    }
                    String pwd = baos.toString(StandardCharsets.UTF_8).trim();
                    if (pwd.startsWith("/")) return pwd;
                }
            } finally {
                try { sftp.close(); } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {
            // exec/SFTP 失败，回退到缓存值
        }
        return null;
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

        ThreadPoolUtil.submitVirtual(() -> {
            // 优先通过交互式 shell 执行 pwd 获取真实当前目录
            String remoteDir = getRemotePwdViaShell(conn.session, conn.toChannel);
            if (remoteDir == null) {
                // 回退到缓存值
                remoteDir = cachedRemotePwd;
            } else {
                // 更新缓存
                cachedRemotePwd = remoteDir;
            }
            if (remoteDir == null) {
                // 最后兜底：用 $HOME
                remoteDir = getRemoteHome(conn.session);
                if (remoteDir != null) cachedRemotePwd = remoteDir;
            }
            if (remoteDir == null) {
                appendTerminal("[无法获取远程目录，上传取消]\n", C_ERR);
                return;
            }
            appendTerminal("[上传目标: " + remoteDir + "/]\n", C_SYS);

            // 预检写权限
            java.util.concurrent.atomic.AtomicReference<String> permErr = new java.util.concurrent.atomic.AtomicReference<>();
            if (!checkWritePermission(conn.session, remoteDir, permErr)) {
                appendTerminal("[无写权限: " + remoteDir + "/ — " + permErr.get() + "]\n", C_ERR);
                appendTerminal("[提示: 执行 sudo chown szh " + remoteDir + " 或 cd 到有权限的目录]\n", C_WARN);
                return;
            }

            for (File file : files) {
                String remotePath = remoteDir + "/" + file.getName();
                appendTerminal("[上传: " + file.getName() + " → " + remotePath + "]\n", C_SYS);

                TransferProgressDialog dlg = new TransferProgressDialog(
                    SwingUtilities.getWindowAncestor(this), "上传 " + file.getName());
                java.util.concurrent.atomic.AtomicReference<String> errRef = new java.util.concurrent.atomic.AtomicReference<>();
                if (uploadViaSftp(conn.session, file, remotePath, dlg, errRef)) {
                    dlg.markFinished();
                    appendTerminal("[上传完成: " + file.getName() + "]\n", C_RECV);
                } else {
                    dlg.markFinished();
                    String detail = errRef.get();
                    appendTerminal("[上传失败: " + file.getName() + (detail != null ? " — " + detail : "") + "]\n", C_ERR);
                }
            }
            refreshDirectoryBrowser(false);
        });
    }

    /** 下载远程文件（SFTP Client） */
    private void downloadFile() {
        SshConn conn;
        synchronized (connLock) { conn = currentConn; }
        if (conn == null || !conn.connected || conn.session == null) {
            appendTerminal("[未连接到任何主机]\n", C_WARN);
            return;
        }

        String remotePath = (String) JOptionPane.showInputDialog(this,
            "输入远程文件路径（绝对路径 或 相对于当前目录的路径）:",
            "下载文件", JOptionPane.PLAIN_MESSAGE, null, null, "");
        if (remotePath == null || remotePath.trim().isEmpty()) return;
        remotePath = remotePath.trim();

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择保存位置");
        chooser.setSelectedFile(new File(new File(remotePath).getName()));
        if (chooser.showSaveDialog(this) != JFileChooser.APPROVE_OPTION) return;

        File localFile = chooser.getSelectedFile();
        if (localFile == null) return;
        if (localFile.exists()) {
            int ret = JOptionPane.showConfirmDialog(this,
                "文件已存在，是否覆盖？\n" + localFile.getAbsolutePath(),
                "确认覆盖", JOptionPane.YES_NO_OPTION);
            if (ret != JOptionPane.YES_OPTION) return;
        }

        final String finalRemotePath = remotePath;
        ThreadPoolUtil.submitVirtual(() -> {
            // 优先通过交互式 shell 获取真实当前目录
            String remoteDir = getRemotePwdViaShell(conn.session, conn.toChannel);
            if (remoteDir == null) {
                remoteDir = cachedRemotePwd;
            } else {
                cachedRemotePwd = remoteDir;
            }
            String resolvedPath = finalRemotePath;
            if (!finalRemotePath.startsWith("/") && remoteDir != null) {
                resolvedPath = remoteDir + "/" + finalRemotePath;
            }
            SftpClient sftp = null;
            try {
                sftp = SftpClientFactory.instance().createSftpClient(conn.session);
                long fileSize = sftp.stat(resolvedPath).getSize();

                appendTerminal("[下载: " + resolvedPath + " → " + localFile.getAbsolutePath() + "]\n", C_SYS);

                TransferProgressDialog dlg = new TransferProgressDialog(
                    SwingUtilities.getWindowAncestor(this), "下载 " + new File(finalRemotePath).getName());
                dlg.setTarget(0, fileSize > 0 ? fileSize : 1);
                SwingUtilities.invokeLater(() -> dlg.setVisible(true));

                long total = fileSize;
                try (InputStream in = sftp.read(resolvedPath);
                     OutputStream out = new FileOutputStream(localFile)) {
                    byte[] buf = new byte[8192];
                    int n;
                    long read = 0;
                    while ((n = in.read(buf)) != -1 && !dlg.cancelled) {
                        out.write(buf, 0, n);
                        read += n;
                        dlg.setTarget(read, total);
                    }
                    if (dlg.cancelled) {
                        try { java.nio.file.Files.deleteIfExists(localFile.toPath()); } catch (Exception ignored) {}
                        dlg.markFinished();
                        appendTerminal("[已取消下载]\n", C_WARN);
                    } else {
                        dlg.markFinished();
                        appendTerminal("[下载完成: " + localFile.getName() + "]\n", C_RECV);
                    }
                } catch (Exception e) {
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
                if (sftp != null) try { sftp.close(); } catch (Exception ignored) {}
            }
        });
    }

    // ==================== 上传（SFTP 优先，失败回退 exec + base64）====================

    /**
     * 上传文件：优先走 SFTP，失败回退 exec + base64。errRef 携带失败原因。
     */
    private boolean uploadViaSftp(ClientSession session, File file, String remotePath,
                                   TransferProgressDialog dlg,
                                   java.util.concurrent.atomic.AtomicReference<String> errRef) {
        long fileSize = file.length();
        final long displayTotal = fileSize > 0 ? fileSize : 1;
        dlg.setTarget(0, displayTotal);
        SwingUtilities.invokeLater(() -> {
            if (!dlg.isVisible()) dlg.setVisible(true);
        });

        // ① 先尝试 SFTP
        try {
            SftpClient sftp = SftpClientFactory.instance().createSftpClient(session);
            try (FileInputStream fis = new FileInputStream(file)) {
                sftp.put(new ProgressInputStream(fis, fileSize, dlg), remotePath);
            }
            if (dlg.cancelled) {
                try { sftp.remove(remotePath); } catch (Exception ignored) {}
                errRef.set("用户取消");
                return false;
            }
            sftp.close();
            dlg.setTarget(fileSize, displayTotal);
            return true;
        } catch (Exception e) {
            String sfpErr = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (sfpErr.toLowerCase().contains("permission denied")) {
                errRef.set("权限不足 (SFTP)");
            } else {
                errRef.set("SFTP: " + sfpErr);
            }
        }

        // ② 回退到 exec + base64（base64 编码避免二进制数据损坏 SSH 通道）
        return uploadViaExecBase64(session, file, remotePath, fileSize, displayTotal, dlg, errRef);
    }

    private boolean uploadViaExecBase64(ClientSession session, File file, String remotePath,
                                         long fileSize, long displayTotal, TransferProgressDialog dlg,
                                         java.util.concurrent.atomic.AtomicReference<String> errRef) {
        ChannelExec exec = null;
        OutputStream base64Encoder = null;
        java.util.concurrent.Future<?> stderrReader = null;
        java.io.ByteArrayOutputStream stderrBuf = new java.io.ByteArrayOutputStream();
        try {
            String escapedPath = remotePath.replace("'", "'\\''");

            // 远端 base64 -d 解码并写入文件
            exec = session.createExecChannel("base64 -d > '" + escapedPath + "'");
            exec.open().verify(10000);

            // 启动 stderr 读取线程，捕获权限错误等
            final ChannelExec execRef = exec;
            stderrReader = ThreadPoolUtil.submitVirtual(() -> {
                try {
                    java.io.InputStream errIn = execRef.getInvertedErr();
                    byte[] b = new byte[256];
                    int r;
                    while ((r = errIn.read(b)) != -1) {
                        stderrBuf.write(b, 0, r);
                    }
                } catch (Exception ignored) {}
            });

            // 流式 Base64 编码器 → exec stdin
            base64Encoder = Base64.getEncoder().wrap(exec.getInvertedIn());

            long sent = 0;
            try (FileInputStream fis = new FileInputStream(file)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = fis.read(buf)) != -1 && !dlg.cancelled) {
                    base64Encoder.write(buf, 0, n);
                    sent += n;
                    dlg.setTarget(sent, displayTotal);
                }
                base64Encoder.flush();
            } catch (java.io.IOException e) {
                // 写入失败通常是因为远端命令已退出（权限不足等）
                try { stderrReader.get(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
                String errText = stderrBuf.toString(java.nio.charset.StandardCharsets.UTF_8).trim();
                if (!errText.isEmpty()) {
                    errRef.set("Exec错误: " + errText);
                } else if (e.getMessage() != null && e.getMessage().contains("closed")) {
                    errRef.set("权限不足，远端命令立即退出");
                } else {
                    errRef.set("Exec写入失败: " + e.getMessage());
                }
                return false;
            }

            if (dlg.cancelled) {
                errRef.set("用户取消");
                return false;
            }

            // close 发送 final base64 padding + SSH_MSG_CHANNEL_EOF
            base64Encoder.close();
            base64Encoder = null;

            exec.waitFor(EnumSet.of(ClientChannelEvent.CLOSED), 60000);
            dlg.setTarget(fileSize, displayTotal);

            // 等待 stderr reader 完成
            try { stderrReader.get(2, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            String errText = stderrBuf.toString(java.nio.charset.StandardCharsets.UTF_8).trim();

            if (!verifyRemoteFile(session, escapedPath, fileSize)) {
                if (!errText.isEmpty()) {
                    errRef.set("Exec写入失败: " + errText);
                } else {
                    errRef.set("远程文件校验失败");
                }
                return false;
            }
            return true;

        } catch (Exception e) {
            String msg = e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName();
            if (msg.toLowerCase().contains("permission denied")) {
                errRef.set("权限不足");
            } else {
                errRef.set("异常: " + msg);
            }
            return false;
        } finally {
            if (stderrReader != null && !stderrReader.isDone()) {
                try { stderrReader.get(1, java.util.concurrent.TimeUnit.SECONDS); } catch (Exception ignored) {}
            }
            if (base64Encoder != null) try { base64Encoder.close(); } catch (Exception ignored) {}
            if (exec != null && exec.isOpen()) try { exec.close(true); } catch (Exception ignored) {}
        }
    }

    /** 通过 exec 校验远程文件是否写入成功 */
    private static boolean verifyRemoteFile(ClientSession session, String remotePath, long expectedSize) {
        try {
            String safe = remotePath.replace("'", "'\\''");
            ChannelExec verExec = session.createExecChannel(
                "test -f '" + safe + "' && wc -c < '" + safe + "' || echo MISSING");
            verExec.open().verify(5000);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream stdout = verExec.getInvertedOut();
            byte[] buf = new byte[256];
            int n;
            while ((n = stdout.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            verExec.close(true);

            String result = baos.toString(StandardCharsets.UTF_8).trim();
            if ("MISSING".equals(result)) return false;
            try {
                return Long.parseLong(result) >= expectedSize;
            } catch (NumberFormatException ex) {
                return !result.isEmpty();
            }
        } catch (Exception e) {
            return false;
        }
    }

    /** 预检远程目录是否有写权限，无权限时 errRef 填入失败原因 */
    private static boolean checkWritePermission(ClientSession session, String remoteDir,
                                                java.util.concurrent.atomic.AtomicReference<String> errRef) {
        ChannelExec exec = null;
        try {
            String safe = remoteDir.replace("'", "'\\''");
            exec = session.createExecChannel("test -d '" + safe + "' && test -w '" + safe + "' || echo NO_PERM:" + safe);
            exec.open().verify(5000);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            InputStream stdout = exec.getInvertedOut();
            byte[] buf = new byte[256];
            int n;
            while ((n = stdout.read(buf)) != -1) {
                baos.write(buf, 0, n);
            }
            exec.close(true);

            String result = baos.toString(StandardCharsets.UTF_8).trim();
            if (result.isEmpty()) return true; // 无输出 = test 全部通过
            if (result.startsWith("NO_PERM:")) {
                errRef.set("目录不可写（可能需 root 权限）");
                return false;
            }
            errRef.set("目录不存在或不可访问: " + result);
            return false;
        } catch (Exception e) {
            errRef.set("预检异常: " + (e.getMessage() != null ? e.getMessage() : e.getClass().getSimpleName()));
            return false;
        } finally {
            if (exec != null && exec.isOpen()) try { exec.close(true); } catch (Exception ignored) {}
        }
    }

    /** 带进度回调的输入流包装——拦截 read 并同步进度到 TransferProgressDialog */
    private static class ProgressInputStream extends FilterInputStream {
        private final long total;
        private long readBytes;
        private final TransferProgressDialog dlg;

        ProgressInputStream(InputStream in, long total, TransferProgressDialog dlg) {
            super(in);
            this.total = total > 0 ? total : 1;
            this.dlg = dlg;
        }

        @Override
        public int read() throws IOException {
            if (dlg.cancelled) return -1;
            int b = super.read();
            if (b != -1) { readBytes++; dlg.setTarget(readBytes, total); }
            return b;
        }

        @Override
        public int read(byte[] b, int off, int len) throws IOException {
            if (dlg.cancelled) return -1;
            int n = super.read(b, off, len);
            if (n > 0) { readBytes += n; dlg.setTarget(readBytes, total); }
            return n;
        }
    }

    /** 传输进度对话框 — 带平滑追赶动画 */
    private static class TransferProgressDialog extends JDialog {
        final JProgressBar progressBar;
        final JLabel speedLabel;
        final JButton cancelBtn;
        volatile boolean cancelled;

        // 真实传输进度（由上/下载线程写入，不经过 EDT）
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

        /** 上/下载线程调用：仅更新目标值，极轻量 */
        void setTarget(long transferred, long total) {
            this.targetTransferred = transferred;
            this.targetTotal = total;
        }

        /** 传输完成或异常终止时调用，让动画最终走到 100% */
        void markFinished() {
            this.transferFinished = true;
            // 防止 init() 未被调用导致 targetTotal=0 时 animateTick 提前 return 卡住
            if (this.targetTotal <= 0) {
                this.targetTotal = 1;
                this.targetTransferred = 1;
            }
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
            if (lastTargetCount > 0 && dt >= 500) {
                long dc = targetTransferred - lastTargetCount;
                double speedBps = dc * 1000.0 / Math.max(dt, 1);
                speedLabel.setText("速度: " + formatSize((long) speedBps) + "/s  |  已用时: "
                    + formatDuration(now - startTime));
                lastTargetCount = targetTransferred;
                lastSpeedTime = now;
            } else if (lastTargetCount == 0 && targetTransferred > 0) {
                // 首个真实数据点：初始化速度基线，跳过首次速度展示（避免瞬态虚高）
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
        if (text == null || text.isEmpty()) return;
        SwingUtilities.invokeLater(() -> appendAnsiRaw(text));
    }

    /** 连接感知版：text 累积到 conn.terminalBuffer；仅在当前正查看该连接时才渲染 UI */
    private void appendAnsiForConn(SshConn conn, String text) {
        if (conn == null || text == null || text.isEmpty()) return;
        conn.terminalBuffer.append(text);
        SwingUtilities.invokeLater(() -> {
            synchronized (connLock) {
                if (conn != currentConn) return;
            }
            appendAnsiRaw(text);
        });
    }

    /** 直接在终端渲染 ANSI 文本（必须在 EDT 调用） */
    private void appendAnsiRaw(String text) {
        if (terminalArea == null) return;
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
        ThreadPoolUtil.submitVirtual(() -> {
            try {
                appendTerminal("[正在连接 " + conn.name + " (" + conn.host + ":" + conn.port + ")...]\n", C_SYS);

                SshClient client = SshClient.setUpDefaultClient();
                conn.client = client;

                // 设置连接/认证超时（防止防火墙静默丢包导致无限等待），单位：毫秒
                // 不设空闲/读超时——交互式终端长时间无操作是正常的
                client.getProperties().put("io-connect-timeout", 10000L);
                client.getProperties().put("auth-timeout", 10000L);

                client.start();

                // ---- 主机密钥验证 ----
                client.setServerKeyVerifier((cliSession, address, key) -> {
                    String hostKey = conn.host + ":" + conn.port;
                    String fp = computeFingerprintFromPublicKey(key);
                    String saved = knownHosts.get(hostKey);
                    if (saved != null && saved.equals(fp)) {
                        return true; // 已信任
                    }
                    // 弹出验证对话框
                    String keyType = key.getAlgorithm();
                    boolean[] result = {false, false}; // [accepted, savePermanently]
                    try {
                        SwingUtilities.invokeAndWait(() -> {
                            HostKeyVerifyDialog dlg = new HostKeyVerifyDialog(
                                SwingUtilities.getWindowAncestor(SshPanel.this),
                                conn.host, conn.port, fp, keyType);
                            dlg.setVisible(true);
                            result[0] = dlg.accepted;
                            result[1] = dlg.savePermanently;
                        });
                    } catch (Exception ex) {
                        return false;
                    }
                    if (result[0] && result[1]) {
                        knownHosts.put(hostKey, fp);
                        saveKnownHosts();
                    }
                    return result[0];
                });

                // ---- 连接 + 认证 ----
                ConnectFuture cf = client.connect(conn.username, conn.host, conn.port);
                ClientSession session = cf.verify(8000).getSession();
                session.addPasswordIdentity(conn.password);
                session.auth().verify(8000);

                // ---- Shell Channel ----
                ChannelShell channel = session.createShellChannel();
                channel.setPtyType("xterm-256color");
                channel.setPtyColumns(160);
                channel.setPtyLines(30);
                channel.setPtyWidth(800);
                channel.setPtyHeight(600);
                channel.setEnv("LANG", "zh_CN.UTF-8");
                channel.open().verify(8000);

                InputStream recvStream = channel.getInvertedOut();
                OutputStream toChannelOs = channel.getInvertedIn();

                conn.session = session;
                conn.channel = channel;
                conn.toChannel = toChannelOs;
                conn.connected = true;

                SwingUtilities.invokeLater(() -> {
                    SshConn old;
                    synchronized (connLock) {
                        old = currentConn;
                        if (old != null) old.pwdCache = cachedRemotePwd;
                        currentConn = conn;
                    }
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
                        appendAnsiForConn(conn, text);
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

    /** 从 MINA SSHD PublicKey 计算 SHA256 指纹 */
    private static String computeFingerprintFromPublicKey(java.security.PublicKey key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            return "SHA256:???";
        }
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
                conn.channel.close(true);
                conn.channel = null;
            }
        } catch (Exception ignored) {}
            try {
                if (conn.session != null) {
                    conn.session.close();   // 先 close 不再等待数据包，减少 IOCP 回调
                    conn.session = null;
                }
            } catch (Exception ignored) {}
            // 留出时间让 Windows IOCP 完成剩余 I/O 事件，否则 client.stop() 关掉 NIO 线程池后
            // InnocuousThread 里 IOCP 回调会报 IllegalStateException: Executor has been shut down
            try { Thread.sleep(200); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            try {
                if (conn.client != null) {
                    conn.client.stop();
                    conn.client = null;
                }
            } catch (Exception ignored) {}

        SwingUtilities.invokeLater(() -> {
            synchronized (connLock) {
                if (currentConn == conn) {
                    // 查找其他已连接的连接，自动切换
                    SshConn other = null;
                    for (SshConn c : connections) {
                        if (c != conn && c.connected) { other = c; break; }
                    }
                    if (other != null) {
                        switchToConnection(other);
                    } else {
                        currentConn = null;
                        updateUiDisconnected();
                    }
                }
            }
            connList.repaint();
        });
    }

    // ==================== UI 状态 ====================

    private void updateUiConnected(SshConn conn) {
        // 兜底：确保退出 RAW 模式（防止上次断开前残留 DocumentFilter/KeyListener）
        exitRawMode();
        // 清空旧连接的所有 UI 残留
        terminalArea.setText("");
        commandInput.setText("");
        commandHistory.clear();
        historyIndex = 0;
        cachedRemotePwd = null;
        dirPathLabel.setText("加载中...");
        dirRootNode.removeAllChildren();
        dirTreeModel.reload();

        statusLabel.setText("\u25CF 已连接");
        statusLabel.setForeground(C_RECV);
        connInfoLabel.setText(conn.name + "  " + conn.username + "@" + conn.host + ":" + conn.port);
        resetAnsiState();
        commandInput.setEnabled(true);
        commandInput.requestFocusInWindow();
    }

    private void updateUiDisconnected() {
        // 断开连接时强制退出 RAW 模式，清理 DocumentFilter 和 KeyListener
        exitRawMode();
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

            // 回车键触发确定按钮
            getRootPane().setDefaultButton(okBtn);

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
