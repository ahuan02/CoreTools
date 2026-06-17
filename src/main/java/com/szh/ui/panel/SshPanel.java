package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import org.apache.sshd.client.SshClient;
import org.apache.sshd.client.channel.ChannelShell;
import org.apache.sshd.client.session.ClientSession;

import javax.swing.*;
import javax.swing.Timer;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.event.*;
import java.io.*;
import java.net.SocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.util.*;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static com.szh.utils.NetUtil.*;

/**
 * SSH 客户端面板：左侧连接列表 + 右侧远程控制台
 */
public class SshPanel extends AbstractCommandPanel {

    private static final Color WHITE = new Color(0xCCCCCC);

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
        transient PipedOutputStream toChannel;
        transient Thread readerThread;
        transient SshClient client;

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

    // 指令补全
    private final StringBuilder currentInput = new StringBuilder();
    private Timer suggestionTimer;
    private JPopupMenu suggestionPopup;
    private int selectedSuggestionIndex = -1;
    private static final List<String> LINUX_COMMANDS = Collections.unmodifiableList(Arrays.asList(
        "alias", "apt", "apt-get", "awk", "basename", "bash", "bg", "bzip2", "cal",
        "cat", "cd", "chgrp", "chmod", "chown", "clear", "cmp", "comm", "cp",
        "cron", "crontab", "curl", "cut", "date", "dd", "df", "diff", "dig",
        "dirname", "dmesg", "dnf", "docker", "du", "echo", "env", "exit",
        "export", "fg", "file", "find", "firewall-cmd", "free", "fsck", "ftp",
        "gcc", "g++", "git", "grep", "groupadd", "groupdel", "groups", "gunzip",
        "gzip", "head", "history", "hostname", "htop", "id", "ifconfig",
        "ip", "iptables", "jobs", "journalctl", "kill", "killall", "kubectl",
        "less", "ln", "locate", "logout", "ls", "lsblk", "lsof", "make",
        "man", "md5sum", "mkdir", "mkfs", "more", "mount", "mv", "nano",
        "nc", "netstat", "nice", "nmap", "nohup", "nslookup", "pacman",
        "passwd", "patch", "ping", "pip", "pkill", "ps", "pwd", "readlink",
        "reboot", "renice", "rm", "rmdir", "route", "rsync", "scp", "screen",
        "sed", "seq", "service", "sftp", "sh", "sha256sum", "shutdown",
        "sleep", "sort", "source", "ss", "ssh", "stat", "su", "sudo",
        "systemctl", "tail", "tar", "tee", "time", "tmux", "top", "touch",
        "tr", "traceroute", "tree", "ufw", "umask", "umount", "unalias",
        "uniq", "unset", "unzip", "uptime", "useradd", "userdel", "usermod",
        "vi", "vim", "wc", "wget", "whereis", "which", "who", "whoami",
        "xargs", "yum", "zip"
    ));

    // 与当前选中连接交互的锁
    private final Object connLock = new Object();
    private SshConn currentConn;
    private ConfigManager configManager;

    // 已知主机密钥
    private final Map<String, String> knownHosts = new LinkedHashMap<>();
    private static final String HOST_KEY_COUNT = "ssh.hostkey.count";

    /** 计算公钥 SHA256 指纹 */
    private static String computeFingerprint(PublicKey key) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] digest = md.digest(key.getEncoded());
            return "SHA256:" + Base64.getEncoder().withoutPadding().encodeToString(digest);
        } catch (Exception e) {
            return "SHA256:???";
        }
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

        // 终端区域（JTextPane 支持多彩色 StyledDocument）
        terminalArea = new JTextPane();
        terminalArea.setEditable(false);  // 禁止 Swing 自动插入文本
        terminalArea.setFocusable(true);
        terminalArea.setFocusTraversalKeysEnabled(false); // 禁用焦点切换：Tab 直达 SSH
        terminalArea.setBackground(C_BG);
        terminalArea.setForeground(WHITE);
        terminalArea.setCaretColor(WHITE);
        terminalArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        terminalArea.getCaret().setVisible(true);
        terminalArea.getCaret().setBlinkRate(500);

        // 键盘监听：keyTyped 发普通字符，keyPressed 发特殊键
        TerminalKeyListener keyListener = new TerminalKeyListener();
        terminalArea.addKeyListener(keyListener);

        // 防抖定时器：停止输入 300ms 后触发补全提示
        suggestionTimer = new Timer(300, e -> showSuggestions());
        suggestionTimer.setRepeats(false);

        // 补全弹窗
        suggestionPopup = new JPopupMenu();
        suggestionPopup.setFocusable(false);

        // 右键清空
        JPopupMenu popup = new JPopupMenu();
        JMenuItem clearItem = new JMenuItem("清空终端");
        clearItem.addActionListener(e -> terminalArea.setText(""));
        popup.add(clearItem);
        terminalArea.setComponentPopupMenu(popup);

        JScrollPane scroll = new JScrollPane(terminalArea);
        scroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "终端", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        scroll.setPreferredSize(new Dimension(400, 220));
        scroll.setMinimumSize(new Dimension(200, 120));
        scroll.getVerticalScrollBar().setUnitIncrement(16);
        panel.add(scroll, BorderLayout.CENTER);

        return panel;
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

    // ==================== 终端输出 & ANSI ====================

    /** ANSI 标准色映射（暗色背景） */
    private static final Color[] ANSI_COLORS = {
        new Color(0x000000), // 0 Black
        new Color(0xCD0000), // 1 Red
        new Color(0x00CD00), // 2 Green
        new Color(0xCDCD00), // 3 Yellow
        new Color(0x3465A4), // 4 Blue
        new Color(0xCD00CD), // 5 Magenta
        new Color(0x00CDCD), // 6 Cyan
        new Color(0xCCCCCC), // 7 White
    };
    private static final Color[] ANSI_BRIGHT = {
        new Color(0x555555), // 0 Bright Black
        new Color(0xFF5555), // 1 Bright Red
        new Color(0x55FF55), // 2 Bright Green
        new Color(0xFFFF55), // 3 Bright Yellow
        new Color(0x729FCF), // 4 Bright Blue
        new Color(0xFF55FF), // 5 Bright Magenta
        new Color(0x55FFFF), // 6 Bright Cyan
        new Color(0xFFFFFF), // 7 Bright White
    };

    /** 追加纯文本到终端（系统消息，单色） */
    private void appendTerminal(String text, Color color) {
        if (terminalArea == null || text == null || text.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = terminalArea.getStyledDocument();
                Style style = doc.addStyle("s" + System.nanoTime(), null);
                StyleConstants.setForeground(style, color != null ? color : WHITE);
                StyleConstants.setFontFamily(style, "Consolas");
                StyleConstants.setFontSize(style, 13);
                doc.insertString(doc.getLength(), text, style);
                terminalArea.setCaretPosition(doc.getLength());
            } catch (BadLocationException ignored) {}
        });
    }

    /** 追加 ANSI 转义文本，解析着色 + 终端控制码 */
    private void appendAnsi(String text) {
        if (terminalArea == null || text == null || text.isEmpty()) return;
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = terminalArea.getStyledDocument();
                int len = text.length();
                int segStart = 0;
                for (int i = 0; i < len; i++) {
                    if (text.charAt(i) == '\033' && i + 1 < len && text.charAt(i + 1) == '[') {
                        // 先把前面的纯文本段输出
                        if (i > segStart) {
                            insertStyled(doc, text.substring(segStart, i));
                        }
                        // 找 CSI 结束
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
                    }
                }
                if (segStart < len) {
                    insertStyled(doc, text.substring(segStart));
                }
                terminalArea.setCaretPosition(doc.getLength());
            } catch (BadLocationException ignored) {}
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
                // \r 回到行首（简化：仅当后跟 \n 时由 \n 统一处理，单独的 \r 做换行）
                if (i + 1 < seg.length() && seg.charAt(i + 1) == '\n') {
                    clean.append(c); // \r\n 一并追加
                } else {
                    clean.append('\n');
                }
            } else if (c >= ' ' || c == '\n' || c == '\t') {
                // 只保留可打印字符 + 换行 + 制表符
                clean.append(c);
            }
            // 其他控制字符（0x00-0x1F、0x7F）直接丢弃
        }
        if (clean.length() == 0) return;
        Style style = doc.addStyle("a" + System.nanoTime(), null);
        StyleConstants.setForeground(style, ansiFg);
        StyleConstants.setFontFamily(style, "Consolas");
        StyleConstants.setFontSize(style, 13);
        StyleConstants.setBold(style, ansiBold);
        if (ansiBg != null) StyleConstants.setBackground(style, ansiBg);
        doc.insertString(doc.getLength(), clean.toString(), style);
    }

    // ==================== 终端键盘监听器 ====================

    /** 终端键盘监听器：keyTyped 发普通字符，keyPressed 发特殊键 */
    private class TerminalKeyListener extends KeyAdapter {
        private SshConn activeConn() {
            synchronized (connLock) { return currentConn; }
        }

        @Override
        public void keyTyped(KeyEvent e) {
            SshConn conn = activeConn();
            if (conn == null || !conn.connected || conn.toChannel == null) {
                e.consume();
                return;
            }
            char c = e.getKeyChar();
            // 过滤控制字符（Enter/Backspace/Tab/Esc 等由 keyPressed 处理）
            if (c == '\n' || c == '\t' || c == '\b' || c == '\r' || c == 0x7F || c == 0x1B || c == KeyEvent.CHAR_UNDEFINED)
                return;
            // 避免重复处理 Ctrl 组合键产生的控制字符
            if (Character.isISOControl(c)) return;
            e.consume();
            sendToChannel(String.valueOf(c).getBytes(StandardCharsets.UTF_8));
            // 跟踪输入并触发防抖补全
            currentInput.append(c);
            suggestionTimer.restart();
        }

        @Override
        public void keyPressed(KeyEvent e) {
            SshConn conn = activeConn();
            if (conn == null || !conn.connected || conn.toChannel == null) {
                e.consume();
                return;
            }
            int code = e.getKeyCode();
            int mod = e.getModifiersEx();
            boolean ctrl = (mod & KeyEvent.CTRL_DOWN_MASK) != 0;

            switch (code) {
                case KeyEvent.VK_ENTER:
                    e.consume();
                    if (suggestionPopup.isVisible() && selectedSuggestionIndex >= 0) {
                        executeSelectedSuggestion();
                    } else {
                        sendToChannel(new byte[]{'\r'});
                        currentInput.setLength(0);
                        dismissSuggestion();
                    }
                    break;
                case KeyEvent.VK_TAB:
                    e.consume();
                    sendToChannel(new byte[]{'\t'});
                    // Tab 后远程会补全，不重启计时器避免弹出过期提示
                    break;
                case KeyEvent.VK_BACK_SPACE:
                    e.consume();
                    sendToChannel(new byte[]{0x7F});
                    if (currentInput.length() > 0) currentInput.setLength(currentInput.length() - 1);
                    suggestionTimer.restart();
                    break;
                case KeyEvent.VK_DELETE:
                    e.consume();
                    sendToChannel(new byte[]{'\u001B', '[', '3', '~'});
                    break;
                case KeyEvent.VK_UP:
                case KeyEvent.VK_DOWN:
                    e.consume();
                    if (suggestionPopup.isVisible()) {
                        navigateSuggestion(code == KeyEvent.VK_UP ? -1 : 1);
                    } else {
                        sendToChannel(new byte[]{'\u001B', '[', (byte) ('A' + (code == KeyEvent.VK_DOWN ? 1 : 0))});
                    }
                    break;
                case KeyEvent.VK_RIGHT:
                    e.consume();
                    sendToChannel(new byte[]{'\u001B', '[', 'C'});
                    break;
                case KeyEvent.VK_LEFT:
                    e.consume();
                    sendToChannel(new byte[]{'\u001B', '[', 'D'});
                    break;
                case KeyEvent.VK_HOME:
                    e.consume();
                    sendToChannel(new byte[]{'\u001B', '[', 'H'});
                    break;
                case KeyEvent.VK_END:
                    e.consume();
                    sendToChannel(new byte[]{'\u001B', '[', 'F'});
                    break;
                case KeyEvent.VK_PAGE_UP:
                    e.consume();
                    sendToChannel(new byte[]{'\u001B', '[', '5', '~'});
                    break;
                case KeyEvent.VK_PAGE_DOWN:
                    e.consume();
                    sendToChannel(new byte[]{'\u001B', '[', '6', '~'});
                    break;
                case KeyEvent.VK_ESCAPE:
                    if (suggestionPopup.isVisible()) {
                        dismissSuggestion();
                    } else {
                        sendToChannel(new byte[]{'\u001B'});
                    }
                    e.consume();
                    break;
                case KeyEvent.VK_C:
                    if (ctrl) {
                        e.consume();
                        sendToChannel(new byte[]{0x03});
                        currentInput.setLength(0);
                        dismissSuggestion();
                    }
                    break;
                case KeyEvent.VK_D:
                    if (ctrl) { e.consume(); sendToChannel(new byte[]{0x04}); }
                    break;
                case KeyEvent.VK_Z:
                    if (ctrl) { e.consume(); sendToChannel(new byte[]{0x1A}); }
                    break;
                case KeyEvent.VK_L:
                    if (ctrl) {
                        e.consume();
                        sendToChannel(new byte[]{0x0C});
                        currentInput.setLength(0);
                        dismissSuggestion();
                    }
                    break;
            }
        }
    }

    // ==================== 指令补全 ====================

    private void dismissSuggestion() {
        suggestionPopup.setVisible(false);
        suggestionTimer.stop();
        selectedSuggestionIndex = -1;
    }

    /** 上下导航补全列表 */
    private void navigateSuggestion(int delta) {
        int count = suggestionPopup.getComponentCount();
        if (count == 0) return;
        selectedSuggestionIndex = (selectedSuggestionIndex + delta + count) % count;
        updateSuggestionHighlight();
    }

    /** 选中当前高亮项的指令并发送 */
    private void executeSelectedSuggestion() {
        if (selectedSuggestionIndex < 0 || selectedSuggestionIndex >= suggestionPopup.getComponentCount()) return;
        Component comp = suggestionPopup.getComponent(selectedSuggestionIndex);
        if (comp instanceof JMenuItem) {
            ((JMenuItem) comp).doClick();
        }
    }

    /** 刷新弹窗里所有项的高亮状态 */
    private void updateSuggestionHighlight() {
        int count = suggestionPopup.getComponentCount();
        for (int i = 0; i < count; i++) {
            Component comp = suggestionPopup.getComponent(i);
            if (comp instanceof JMenuItem) {
                JMenuItem item = (JMenuItem) comp;
                if (i == selectedSuggestionIndex) {
                    item.setBackground(new Color(0x3A6EA5));
                    item.setOpaque(true);
                } else {
                    item.setBackground(null);
                    item.setOpaque(false);
                }
            }
        }
    }

    private void showSuggestions() {
        if (terminalArea == null) return;
        String input = currentInput.toString();
        // 提取空格分隔后的最后一个词
        String[] parts = input.split("\\s+");
        String lastWord = parts.length > 0 ? parts[parts.length - 1] : "";
        if (lastWord.isEmpty()) {
            dismissSuggestion();
            return;
        }

        String prefix = lastWord.toLowerCase();
        java.util.List<String> matches = new java.util.ArrayList<>();
        for (String cmd : LINUX_COMMANDS) {
            if (cmd.startsWith(prefix) && matches.size() < 10) {
                matches.add(cmd);
            }
        }
        if (matches.isEmpty()) {
            dismissSuggestion();
            return;
        }

        suggestionPopup.removeAll();
        for (String cmd : matches) {
            JMenuItem item = new JMenuItem(cmd);
            item.setFont(new Font("Consolas", Font.PLAIN, 12));
            item.addActionListener(ae -> {
                String remaining = cmd.substring(prefix.length());
                sendToChannel(remaining.getBytes(StandardCharsets.UTF_8));
                currentInput.append(remaining);
                dismissSuggestion();
                terminalArea.requestFocusInWindow();
            });
            suggestionPopup.add(item);
        }

        // 默认高亮第一项
        selectedSuggestionIndex = 0;
        updateSuggestionHighlight();

        // 定位在终端底部、光标附近
        try {
            Point pos = terminalArea.getCaret().getMagicCaretPosition();
            if (pos == null) {
                pos = new Point(10, terminalArea.getHeight() - 60);
            }
            suggestionPopup.show(terminalArea, pos.x, pos.y + 18);
        } catch (Exception ex) {
            suggestionPopup.show(terminalArea, 10, terminalArea.getHeight() - 60);
        }
    }

    // ==================== SSH 连接核心 ====================

    private void openConnection(SshConn conn) {
        Thread.ofVirtual().name("ssh-" + conn.name).start(() -> {
            try {
                appendTerminal("[正在连接 " + conn.name + " (" + conn.host + ":" + conn.port + ")...]\n", C_SYS);

                SshClient client = SshClient.setUpDefaultClient();
                conn.client = client;
                client.start();

                // 自定义主机密钥校验
                client.setServerKeyVerifier((clientSession, remoteAddress, serverKey) -> {
                    String addr = conn.host + ":" + conn.port;
                    String fingerprint = computeFingerprint(serverKey);

                    // 已保存且指纹一致 → 直接通过
                    String saved = knownHosts.get(addr);
                    if (saved != null && saved.equals(fingerprint)) {
                        return true;
                    }

                    // 弹窗让用户确认
                    boolean[] box = {false, false}; // [accepted, save]
                    try {
                        SwingUtilities.invokeAndWait(() -> {
                            HostKeyVerifyDialog dlg = new HostKeyVerifyDialog(
                                    SwingUtilities.getWindowAncestor(SshPanel.this),
                                    conn.host, conn.port, fingerprint, serverKey.getAlgorithm());
                            dlg.setVisible(true);
                            box[0] = dlg.accepted;
                            box[1] = dlg.savePermanently;
                        });
                    } catch (Exception ex) {
                        return false;
                    }

                    if (box[0]) {
                        if (box[1]) {
                            knownHosts.put(addr, fingerprint);
                            saveKnownHosts();
                        }
                        return true;
                    }
                    return false;
                });

                ClientSession session = client.connect(conn.username, conn.host, conn.port)
                        .verify(8000, TimeUnit.MILLISECONDS).getSession();
                session.addPasswordIdentity(conn.password);
                session.auth().verify(8000, TimeUnit.MILLISECONDS);

                ChannelShell channel = session.createShellChannel();
                channel.setPtyType("xterm-256color");
                channel.setPtyColumns(160);
                channel.setPtyLines(30);
                channel.setEnv("LANG", "en_US.UTF-8");

                PipedInputStream toChannelPipe = new PipedInputStream(65536);
                PipedOutputStream toChannelOs = new PipedOutputStream(toChannelPipe);
                channel.setIn(toChannelPipe);

                // stdout + stderr 合并到一个流
                PipedInputStream recvStream = new PipedInputStream(65536);
                PipedOutputStream recvOut = new PipedOutputStream(recvStream);
                channel.setOut(recvOut);
                channel.setErr(recvOut);

                channel.open().verify(8000, TimeUnit.MILLISECONDS);

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

                // 读线程 - ANSI 着色输出
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
                conn.channel.close(false);
                conn.channel = null;
            }
        } catch (Exception ignored) {}
        try {
            if (conn.session != null) {
                conn.session.close();
                conn.session = null;
            }
        } catch (Exception ignored) {}
        try {
            if (conn.client != null) {
                conn.client.stop();
                conn.client = null;
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
        terminalArea.requestFocusInWindow();
    }

    private void updateUiDisconnected() {
        statusLabel.setText("未连接");
        statusLabel.setForeground(C_WARN);
        connInfoLabel.setText("");
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
