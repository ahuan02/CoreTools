package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.exceptions.JedisException;
import redis.clients.jedis.params.ScanParams;
import redis.clients.jedis.resps.ScanResult;
import redis.clients.jedis.resps.Tuple;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.TreeSelectionEvent;
import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.TreePath;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.Rectangle2D;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Redis 客户端面板（模仿 Redis Desktop Manager 风格）
 * - 左侧：连接管理树 + 服务器信息面板
 * - 右侧上方：Key 过滤 + 列表
 * - 右侧下方：选中 Key 的数据查看/编辑 + Redis 命令控制台
 */
public class RedisPanel extends AbstractCommandPanel {

    private static final Font FONT = NetUtil.FONT_TEXT;
    private static final Font FONT_BOLD = new Font(NetUtil.FONT_TEXT.getFamily(), Font.BOLD, 12);
    private static final Font FONT_MONO = new Font("Consolas", Font.PLAIN, 12);
    private static final Font FONT_MONO_CJK = new Font("Microsoft YaHei", Font.PLAIN, 12);

    /** Redis 命令列表（用于输入框自动补全） */
    private static final String[] REDIS_COMMANDS = {
        "get", "set", "del", "exists", "expire", "ttl", "persist",
        "type", "rename", "renamenx", "keys", "scan", "randomkey",
        "move", "dump", "restore", "object", "migrate",
        "incr", "incrby", "decr", "decrby", "incrbyfloat",
        "append", "strlen", "getrange", "setrange", "getset", "mget", "mset",
        "setnx", "msetnx", "setex", "psetex", "getex", "getdel",
        "hdel", "hexists", "hget", "hgetall", "hincrby", "hincrbyfloat",
        "hkeys", "hlen", "hmget", "hmset", "hset", "hsetnx", "hvals",
        "hscan", "hstrlen",
        "lindex", "linsert", "llen", "lpop", "lpos", "lpush", "lpushx",
        "lrange", "lrem", "lset", "ltrim", "rpop", "rpoplpush", "rpush", "rpushx",
        "sadd", "scard", "sdiff", "sdiffstore", "sinter", "sinterstore",
        "sismember", "smembers", "smismember", "smove", "spop", "srandmember",
        "srem", "sscan", "sunion", "sunionstore",
        "zadd", "zcard", "zcount", "zdiff", "zincrby", "zinter",
        "zlexcount", "zpopmax", "zpopmin", "zrange", "zrangebylex",
        "zrangebyscore", "zrank", "zrem", "zremrangebylex",
        "zremrangebyrank", "zremrangebyscore", "zrevrange",
        "zrevrangebylex", "zrevrangebyscore", "zrevrank", "zscan", "zscore",
        "zunion", "zdiffstore", "zinterstore", "zunionstore",
        "pfadd", "pfcount", "pfmerge",
        "xadd", "xlen", "xrange", "xrevrange", "xread", "xreadgroup",
        "xgroup", "xack", "xclaim", "xdel", "xpending", "xtrim",
        "geoadd", "geodist", "geohash", "geopos", "georadius",
        "georadiusbymember", "geosearch", "geosearchstore",
        "bitcount", "bitfield", "bitop", "bitpos", "getbit", "setbit",
        "publish", "subscribe", "unsubscribe", "psubscribe", "punsubscribe",
        "dbsize", "info", "client", "config", "flushdb", "flushall",
        "ping", "select", "auth", "echo", "time", "slowlog",
        "script", "eval", "evalsha", "function",
    };

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();
    private final ConfigManager localConfig = new ConfigManager("app_config.properties");

    // ===== 左侧：连接树 =====
    private JTree connTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;

    // ===== 左侧：服务器信息 =====
    private JTextPane serverInfoPane;

    // ===== 连接管理 =====
    private final Map<String, ConnectionInfo> connections = new LinkedHashMap<>();

    // ===== 右侧：标签页 =====
    private JTabbedPane detailTabs;

    // ===== DB 选项卡管理 =====
    private final Map<String, DbTabContext> dbTabs = new LinkedHashMap<>();

    /** 每个 DB 选项卡的独立上下文 */
    private static class DbTabContext {
        final String connName;
        final int db;
        DefaultListModel<String> keyModel;
        JList<String> keyList;
        JLabel keyCountLabel;
        JTextArea valueArea;
        JLabel keyTypeLabel;
        JLabel keySizeLabel;
        JTextField filterField;
        String currentKey;
        /** 每个 key 独立的 TTL 倒计时信息 */
        final Map<String, KeyTimerInfo> keyTimers = new ConcurrentHashMap<>();

        DbTabContext(String connName, int db) {
            this.connName = connName;
            this.db = db;
        }

        /** 为指定 key 启动独立 TTL 倒计时，过期后自动删除 */
        void startKeyTtlTimer(String keyName, int ttlSeconds) {
            stopKeyTtlTimer(keyName);
            if (ttlSeconds <= 0) return;
            KeyTimerInfo info = new KeyTimerInfo(ttlSeconds);
            info.timer = new javax.swing.Timer(1000, e -> {
                info.remaining--;
                if (info.remaining >= 0) {
                    // 刷新 JList 显示（触发 repaint 重新渲染）
                    keyList.repaint();
                } else {
                    stopKeyTtlTimer(keyName);
                    // 倒计时归零，自动删除
                    if (onKeyExpired != null) onKeyExpired.accept(keyName);
                }
            });
            info.timer.start();
            keyTimers.put(keyName, info);
        }

        void stopKeyTtlTimer(String keyName) {
            KeyTimerInfo info = keyTimers.remove(keyName);
            if (info != null && info.timer != null) {
                info.timer.stop();
            }
        }

        /** 停止所有 key 的 timer */
        void stopAllKeyTimers() {
            for (KeyTimerInfo info : keyTimers.values()) {
                if (info.timer != null) info.timer.stop();
            }
            keyTimers.clear();
        }

        /** 获取 key 的剩余 TTL 秒数，-1 表示永久，-2 表示无记录 */
        int getKeyTtl(String keyName) {
            KeyTimerInfo info = keyTimers.get(keyName);
            return info != null ? info.remaining : -2;
        }

        /** key 过期回调 */
        java.util.function.Consumer<String> onKeyExpired;
    }

    /** 每个 key 的独立倒计时信息 */
    private static class KeyTimerInfo {
        int remaining;
        javax.swing.Timer timer;
        KeyTimerInfo(int remaining) { this.remaining = remaining; }
    }

    // ===== 底部：Redis 命令控制台 =====
    private JTextArea consoleOutput;
    private JTextField consoleInput;
    private JPopupMenu autoCompletePopup;
    private JList<String> autoCompleteList;

    // ===== 当前选中状态 =====
    private String currentConnectionName;
    private int currentDb = 0; // 控制台当前选中的 DB
    private DbTabContext activeTabContext; // 当前活动的 DB 标签页上下文

    // ===== 连接信息 =====
    private static class ConnectionInfo {
        String name;
        String host;
        int port;
        String password;
        int timeout;

        ConnectionInfo(String name, String host, int port, String password) {
            this.name = name;
            this.host = host;
            this.port = port;
            this.password = password;
            this.timeout = 5000;
        }
    }

    // ===== 树节点数据（DB节点用） =====
    private static class DbNodeInfo {
        final int dbIndex;
        long keyCount = -1; // -1 表示未加载

        DbNodeInfo(int dbIndex) {
            this.dbIndex = dbIndex;
        }

        @Override
        public String toString() {
            if (keyCount >= 0) {
                return "DB" + dbIndex + " (" + keyCount + ")";
            }
            return "DB" + dbIndex;
        }
    }

    public RedisPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(3, 3));

        // ===== 左侧面板 =====
        JSplitPane leftPane = createLeftPanel();

        // ===== 右侧面板（标签页） =====
        JPanel rightPane = createRightPanel();

        // ===== 主分割面板（左右） =====
        JSplitPane mainSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPane, rightPane);
        mainSplit.setResizeWeight(0.25);
        mainSplit.setDividerLocation(280);
        mainSplit.setBorder(null);

        add(mainSplit, BorderLayout.CENTER);

        // ===== 底部状态栏 =====
        JPanel statusBar = new JPanel(new BorderLayout());
        statusBar.setBorder(new EmptyBorder(2, 8, 2, 8));
        JLabel statusLabel = new JLabel("就绪 - 请右键左侧树新建连接");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        statusBar.add(statusLabel, BorderLayout.WEST);
        add(statusBar, BorderLayout.SOUTH);
    }

    // ==================== 左侧面板 ====================

    private JSplitPane createLeftPanel() {
        // ---- 连接树 ----
        rootNode = new DefaultMutableTreeNode("Redis 连接");
        treeModel = new DefaultTreeModel(rootNode);
        connTree = new JTree(treeModel);
        connTree.setFont(FONT);
        connTree.setRootVisible(true);
        connTree.setShowsRootHandles(true);
        connTree.setCellRenderer(new RedisTreeRenderer());
        connTree.addTreeSelectionListener(this::onTreeSelection);
        connTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { handleTreeRightClick(e); }
            @Override
            public void mouseReleased(MouseEvent e) { handleTreeRightClick(e); }
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    handleTreeDoubleClick(e);
                }
            }
        });

        JScrollPane treeScroll = new JScrollPane(connTree);
        treeScroll.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70), 1, true),
                "连接", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));

        // ---- 服务器信息 ----
        serverInfoPane = new JTextPane();
        serverInfoPane.setContentType("text/html");
        serverInfoPane.setEditable(false);
        serverInfoPane.setBackground(new Color(0x2B2B2B));
        serverInfoPane.setFont(FONT);
        serverInfoPane.setText("<html><body style='color:#A9B7C6;font-family:Microsoft YaHei;font-size:12px;margin:4px'>未连接</body></html>");

        JScrollPane infoScroll = new JScrollPane(serverInfoPane);
        infoScroll.setBorder(new TitledBorder(
                BorderFactory.createLineBorder(new Color(70, 70, 70), 1, true),
                "服务器信息", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));
        infoScroll.setPreferredSize(new Dimension(260, 180));

        JSplitPane leftSplit = new JSplitPane(JSplitPane.VERTICAL_SPLIT, treeScroll, infoScroll);
        leftSplit.setResizeWeight(0.6);
        leftSplit.setDividerLocation(300);
        leftSplit.setBorder(null);
        return leftSplit;
    }

    // ==================== 右侧面板 ====================

    private JPanel createRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(3, 3));

        // ---- 标签页（DB选项卡） ----
        detailTabs = new JTabbedPane(JTabbedPane.TOP);
        detailTabs.setFont(FONT);
        detailTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        detailTabs.addChangeListener(e -> {
            // 跟踪当前活动的 DB 标签页
            Component comp = detailTabs.getSelectedComponent();
            if (comp != null) {
                for (Map.Entry<String, DbTabContext> entry : dbTabs.entrySet()) {
                    DbTabContext ctx = entry.getValue();
                    // 通过 keyList 等组件定位到对应的 ctx
                    if (ctx.keyList != null) {
                        java.awt.Component p = ctx.keyList.getParent();
                        while (p != null && p != comp) p = p.getParent();
                        if (p == comp) {
                            activeTabContext = ctx;
                            currentConnectionName = ctx.connName;
                            currentDb = ctx.db;
                            return;
                        }
                    }
                }
            }
            activeTabContext = null;
        });

        panel.add(detailTabs, BorderLayout.CENTER);

        // ---- 底部：Redis 命令控制台（可折叠） ----
        JPanel consolePanel = createConsolePanel();
        consolePanel.setPreferredSize(new Dimension(0, 150));
        consolePanel.setMinimumSize(new Dimension(0, 100));
        panel.add(consolePanel, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createConsolePanel() {
        JPanel panel = new JPanel(new BorderLayout(3, 3));

        // 输出区
        consoleOutput = new JTextArea();
        consoleOutput.setFont(FONT_MONO_CJK);
        consoleOutput.setEditable(false);
        consoleOutput.setBackground(new Color(0x1E1E1E));
        consoleOutput.setForeground(new Color(0xA9B7C6));
        consoleOutput.setCaretColor(new Color(0xBBBBBB));
        JScrollPane consoleScroll = new JScrollPane(consoleOutput);
        consoleScroll.setBorder(null);
        panel.add(consoleScroll, BorderLayout.CENTER);

        // 输入行 — JTextField + JPopupMenu 自动补全
        JPanel inputBar = new JPanel(new BorderLayout(4, 0));
        inputBar.setBorder(new EmptyBorder(4, 0, 0, 0));

        consoleInput = new JTextField();
        consoleInput.setFont(FONT_MONO_CJK);
        consoleInput.setBackground(new Color(0x2D2D2D));
        consoleInput.setForeground(new Color(0x9CDCFE));
        consoleInput.setCaretColor(new Color(0xBBBBBB));
        consoleInput.addActionListener(e -> executeConsoleCommand());

        // 自动补全弹出菜单
        autoCompleteList = new JList<>();
        autoCompleteList.setFont(FONT_MONO);
        autoCompleteList.setBackground(new Color(0x333333));
        autoCompleteList.setForeground(new Color(0x9CDCFE));
        autoCompleteList.setSelectionBackground(new Color(0x3A6EA5));
        autoCompleteList.setSelectionForeground(Color.WHITE);
        autoCompleteList.setVisibleRowCount(8);
        autoCompleteList.setFixedCellHeight(22);
        autoCompletePopup = new JPopupMenu();
        autoCompletePopup.setFocusable(false);
        autoCompletePopup.add(new JScrollPane(autoCompleteList));

        // 防抖 + DocumentListener：输入时自动弹出补全
        final javax.swing.Timer[] debounceTimer = {null};
        consoleInput.getDocument().addDocumentListener(new javax.swing.event.DocumentListener() {
            private void filter() {
                if (debounceTimer[0] != null) debounceTimer[0].stop();
                debounceTimer[0] = new javax.swing.Timer(150, evt -> {
                    SwingUtilities.invokeLater(() -> {
                        String text = consoleInput.getText();
                        if (text.isEmpty() || text.contains(" ")) {
                            autoCompletePopup.setVisible(false);
                            return;
                        }
                        String lower = text.toLowerCase();
                        DefaultListModel<String> model = new DefaultListModel<>();
                        for (String cmd : REDIS_COMMANDS) {
                            if (cmd.startsWith(lower)) model.addElement(cmd);
                        }
                        if (model.isEmpty()) {
                            autoCompletePopup.setVisible(false);
                            return;
                        }
                        autoCompleteList.setModel(model);
                        autoCompleteList.setSelectedIndex(0);
                        autoCompletePopup.pack();
                        // 定位在光标下方
                        try {
                            Rectangle2D caretRect = consoleInput.modelToView2D(consoleInput.getCaretPosition());
                            int x = caretRect != null ? (int) caretRect.getX() : 4;
                            int y = caretRect != null ? (int) caretRect.getMaxY() + 2 : consoleInput.getHeight();
                            autoCompletePopup.show(consoleInput, x, y);
                        } catch (Exception ex) {
                            autoCompletePopup.show(consoleInput, 4, consoleInput.getHeight());
                        }
                        consoleInput.requestFocusInWindow();
                    });
                });
                debounceTimer[0].setRepeats(false);
                debounceTimer[0].start();
            }
            @Override public void insertUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void removeUpdate(javax.swing.event.DocumentEvent e) { filter(); }
            @Override public void changedUpdate(javax.swing.event.DocumentEvent e) { filter(); }
        });

        // 鼠标点击/双击弹出面板中的指令
        autoCompleteList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    String selected = autoCompleteList.getSelectedValue();
                    if (selected != null) {
                        consoleInput.setText(selected + " ");
                        autoCompletePopup.setVisible(false);
                    }
                }
            }
        });

        // 键盘导航：上下箭头选择，Tab/Enter 补全
        consoleInput.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (!autoCompletePopup.isVisible()) return;
                switch (e.getKeyCode()) {
                    case KeyEvent.VK_DOWN -> {
                        e.consume();
                        int idx = autoCompleteList.getSelectedIndex();
                        if (idx < autoCompleteList.getModel().getSize() - 1) {
                            autoCompleteList.setSelectedIndex(idx + 1);
                            autoCompleteList.ensureIndexIsVisible(idx + 1);
                        }
                    }
                    case KeyEvent.VK_UP -> {
                        e.consume();
                        int idx = autoCompleteList.getSelectedIndex();
                        if (idx > 0) {
                            autoCompleteList.setSelectedIndex(idx - 1);
                            autoCompleteList.ensureIndexIsVisible(idx - 1);
                        }
                    }
                    case KeyEvent.VK_TAB, KeyEvent.VK_ENTER -> {
                        String selected = autoCompleteList.getSelectedValue();
                        if (selected != null) {
                            e.consume();
                            consoleInput.setText(selected + " ");
                            autoCompletePopup.setVisible(false);
                        }
                    }
                    case KeyEvent.VK_ESCAPE -> autoCompletePopup.setVisible(false);
                }
            }
        });

        inputBar.add(consoleInput, BorderLayout.CENTER);

        JButton btnExec = new JButton("执行");
        btnExec.setFont(FONT_BOLD);
        btnExec.setFocusPainted(false);
        btnExec.setMargin(new Insets(2, 10, 2, 10));
        btnExec.addActionListener(e -> executeConsoleCommand());
        inputBar.add(btnExec, BorderLayout.EAST);

        panel.add(inputBar, BorderLayout.SOUTH);

        return panel;
    }

    // ==================== 树事件 ====================

    private void onTreeSelection(TreeSelectionEvent e) {
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) connTree.getLastSelectedPathComponent();
        if (node == null) return;

        Object userObj = node.getUserObject();
        // DB 节点（DbNodeInfo）选中时不自动打开，只有双击时才打开
        if (userObj instanceof DbNodeInfo) {
            return;
        }
        if (userObj instanceof String) {
            String text = (String) userObj;
            // 判断是否选择了某个连接节点
            for (ConnectionInfo info : connections.values()) {
                if (text.equals(info.name)) {
                    connectToRedis(info);
                    return;
                }
            }
        }
    }

    private void handleTreeRightClick(MouseEvent e) {
        // 只在鼠标右键时处理（跨平台兼容）
        if (!SwingUtilities.isRightMouseButton(e)) return;

        // 用最近行号定位，让整行空白区域也能弹出菜单
        int row = connTree.getClosestRowForLocation(e.getX(), e.getY());
        if (row < 0) return;
        // 检查鼠标是否真的在该行的纵向范围内
        Rectangle rowBounds = connTree.getRowBounds(row);
        if (rowBounds == null || e.getY() < rowBounds.y || e.getY() > rowBounds.y + rowBounds.height) return;

        TreePath path = connTree.getPathForRow(row);
        if (path == null) return;

        // 先选中被右键的节点
        connTree.setSelectionRow(row);

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        JPopupMenu menu = createPopupMenu();

        if (node == rootNode) {
            // 根节点：新建连接
            menu.add(createMenuItem("新建连接", () -> showNewConnectionDialog()));
        } else if (node.getParent() == rootNode) {
            // 连接节点：编辑、刷新、删除
            menu.add(createMenuItem("编辑连接", () -> showEditConnectionDialog(node)));
            menu.add(createMenuItem("刷新连接", () -> refreshConnection(node)));
            menu.addSeparator();
            JMenuItem delItem = createMenuItem("删除连接", () -> deleteConnection(node));
            delItem.setForeground(new Color(0xFF6B6B));
            menu.add(delItem);
        } else if (node.getParent() != null && node.getParent().getParent() == rootNode) {
            // DB 节点（孙子节点）：新增Key、筛选Key、刷新Key数量、删除数据库
            Object userObj = node.getUserObject();
            if (userObj instanceof DbNodeInfo dbInfo) {
                DefaultMutableTreeNode connNode = (DefaultMutableTreeNode) node.getParent();
                String connName = (String) connNode.getUserObject();
                ConnectionInfo info = connections.get(connName);
                if (info != null) {
                    int db = dbInfo.dbIndex;
                    menu.add(createMenuItem("新增 Key", () -> {
                        openDbTab(info, db);
                        String tabKey = info.name + "|" + db;
                        DbTabContext ctx = dbTabs.get(tabKey);
                        if (ctx != null) showDbTabAddKeyDialog(ctx, info);
                    }));
                    menu.add(createMenuItem("筛选 Key", () -> {
                        openDbTab(info, db);
                    }));
                    menu.add(createMenuItem("刷新 Key 数量", () -> refreshDbNodeCount(info, db)));
                    menu.addSeparator();
                    JMenuItem delDbItem = createMenuItem("删除数据库（FLUSHDB）", () -> deleteDatabase(info, db, dbInfo.toString()));
                    delDbItem.setForeground(new Color(0xFF6B6B));
                    menu.add(delDbItem);
                }
            }
        }

        showMenuWithFade(menu, connTree, e.getX(), e.getY());
    }

    /** 处理树节点双击 */
    private void handleTreeDoubleClick(MouseEvent e) {
        int row = connTree.getClosestRowForLocation(e.getX(), e.getY());
        if (row < 0) return;
        Rectangle rowBounds = connTree.getRowBounds(row);
        if (rowBounds == null || e.getY() < rowBounds.y || e.getY() > rowBounds.y + rowBounds.height) return;

        TreePath path = connTree.getPathForRow(row);
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = node.getUserObject();

        // 只处理 DB 节点的双击
        if (userObj instanceof DbNodeInfo dbInfo && node.getParent() != null && node.getParent().getParent() == rootNode) {
            DefaultMutableTreeNode connNode = (DefaultMutableTreeNode) node.getParent();
            String connName = (String) connNode.getUserObject();
            ConnectionInfo info = connections.get(connName);
            if (info != null) {
                openDbTab(info, dbInfo.dbIndex);
            }
        }
    }

    /** 打开 DB 选项卡（如果已存在则选中，否则新建） */
    private void openDbTab(ConnectionInfo info, int db) {
        String tabKey = info.name + "|" + db;
        String tabTitle = info.name + "-DB" + db;

        // 如果选项卡已存在，直接选中
        if (dbTabs.containsKey(tabKey)) {
            for (int i = 0; i < detailTabs.getTabCount(); i++) {
                if (tabTitle.equals(detailTabs.getTitleAt(i))) {
                    detailTabs.setSelectedIndex(i);
                    return;
                }
            }
            return;
        }

        // 创建新的 DB 选项卡
        DbTabContext ctx = new DbTabContext(info.name, db);
        dbTabs.put(tabKey, ctx);

        JPanel tabPanel = createDbTabPanel(ctx, info, db);
        detailTabs.addTab(tabTitle, tabPanel);
        int idx = detailTabs.indexOfComponent(tabPanel);

        // 为选项卡添加关闭按钮
        JPanel tabTitlePanel = createTabTitlePanel(tabTitle, ctx, tabKey);
        detailTabs.setTabComponentAt(idx, tabTitlePanel);

        detailTabs.setSelectedComponent(tabPanel);

        // 异步加载 Key 列表
        refreshDbTabKeys(ctx, info);
    }

    /** 创建带关闭按钮的选项卡标题 */
    private JPanel createTabTitlePanel(String title, DbTabContext ctx, String tabKey) {
        JPanel panel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        panel.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FONT);
        titleLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 6));
        panel.add(titleLabel);

        JButton closeBtn = new JButton("×");
        closeBtn.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 14));
        closeBtn.setFocusPainted(false);
        closeBtn.setBorder(BorderFactory.createEmptyBorder());
        closeBtn.setContentAreaFilled(false);
        closeBtn.setForeground(new Color(0x999999));
        closeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        closeBtn.setPreferredSize(new Dimension(18, 18));
        closeBtn.setMargin(new Insets(0, 0, 0, 0));
        closeBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                closeBtn.setForeground(new Color(0xFF6B6B));
            }
            @Override
            public void mouseExited(MouseEvent e) {
                closeBtn.setForeground(new Color(0x999999));
            }
        });
        closeBtn.addActionListener(e -> closeDbTab(tabKey, title));

        panel.add(closeBtn);
        return panel;
    }

    /** 关闭 DB 选项卡 */
    private void closeDbTab(String tabKey, String tabTitle) {
        for (int i = 0; i < detailTabs.getTabCount(); i++) {
            if (tabTitle.equals(detailTabs.getTitleAt(i))) {
                DbTabContext ctx = dbTabs.get(tabKey);
                if (ctx != null) ctx.stopAllKeyTimers();
                detailTabs.removeTabAt(i);
                dbTabs.remove(tabKey);
                return;
            }
        }
    }

    /** 创建 DB 选项卡面板 */
    private JPanel createDbTabPanel(DbTabContext ctx, ConnectionInfo info, int db) {
        JSplitPane split = new JSplitPane(JSplitPane.VERTICAL_SPLIT);
        split.setResizeWeight(0.35);
        split.setDividerLocation(180);
        split.setBorder(null);

        // ---- 上部：Key 列表 ----
        JPanel keyPanel = new JPanel(new BorderLayout(3, 3));

        // 工具栏
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
        toolbar.add(new JLabel("过滤:"));
        ctx.filterField = new JTextField(12);
        ctx.filterField.setFont(FONT);
        ctx.filterField.addActionListener(ev -> refreshDbTabKeys(ctx, info));
        ctx.filterField.setToolTipText("支持通配符 * ?，如 user:*，回车刷新");
        toolbar.add(ctx.filterField);

        JButton btnRefresh = new JButton("刷新");
        btnRefresh.setFont(FONT_BOLD);
        btnRefresh.setFocusPainted(false);
        btnRefresh.setMargin(new Insets(2, 8, 2, 8));
        btnRefresh.addActionListener(ev -> refreshDbTabKeys(ctx, info));
        toolbar.add(btnRefresh);

        JButton btnAdd = new JButton("+Key");
        btnAdd.setFont(FONT_BOLD);
        btnAdd.setFocusPainted(false);
        btnAdd.setMargin(new Insets(2, 8, 2, 8));
        btnAdd.addActionListener(ev -> showDbTabAddKeyDialog(ctx, info));
        toolbar.add(btnAdd);

        JButton btnDel = new JButton("删除");
        btnDel.setFont(FONT_BOLD);
        btnDel.setFocusPainted(false);
        btnDel.setMargin(new Insets(2, 8, 2, 8));
        btnDel.addActionListener(ev -> deleteDbTabKey(ctx, info));
        toolbar.add(btnDel);

        ctx.keyCountLabel = new JLabel("共 0 个 key");
        ctx.keyCountLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        ctx.keyCountLabel.setForeground(new Color(0x9E9E9E));
        toolbar.add(ctx.keyCountLabel);

        keyPanel.add(toolbar, BorderLayout.NORTH);

        // Key 列表（自定义渲染器显示 TTL 倒计时）
        ctx.keyModel = new DefaultListModel<>();
        ctx.keyList = new JList<>(ctx.keyModel);
        ctx.keyList.setFont(FONT_MONO);
        ctx.keyList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        ctx.keyList.setCellRenderer(new KeyTtlListCellRenderer(ctx));
        ctx.keyList.addListSelectionListener(ev -> {
            if (!ev.getValueIsAdjusting()) {
                onDbTabKeySelected(ctx, info);
            }
        });
        // 右键菜单 & 双击刷新
        ctx.keyList.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) { handleKeyRightClick(e, ctx, info); }
            @Override
            public void mouseReleased(MouseEvent e) { handleKeyRightClick(e, ctx, info); }
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && SwingUtilities.isLeftMouseButton(e)) {
                    int idx = ctx.keyList.locationToIndex(e.getPoint());
                    if (idx >= 0) {
                        ctx.keyList.setSelectedIndex(idx);
                        loadDbTabValue(ctx, info);
                    }
                }
            }
        });
        // 焦点离开 key 列表时清空 value 面板
        ctx.keyList.addFocusListener(new FocusAdapter() {
            @Override
            public void focusLost(FocusEvent e) {
                clearValuePanel(ctx);
            }
        });
        JScrollPane keyScroll = new JScrollPane(ctx.keyList);
        keyScroll.setBorder(null);
        keyPanel.add(keyScroll, BorderLayout.CENTER);

        split.setTopComponent(keyPanel);

        // ---- 下部：值查看 ----
        JPanel valuePanel = new JPanel(new BorderLayout(3, 3));

        // 信息栏
        JPanel infoBar = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 2));
        ctx.keyTypeLabel = new JLabel("类型: -");
        ctx.keyTypeLabel.setFont(FONT);
        infoBar.add(ctx.keyTypeLabel);
        ctx.keySizeLabel = new JLabel("大小: -");
        ctx.keySizeLabel.setFont(FONT);
        infoBar.add(ctx.keySizeLabel);

        JButton btnReload = new JButton("重新加载");
        btnReload.setFont(FONT_BOLD);
        btnReload.setFocusPainted(false);
        btnReload.setMargin(new Insets(2, 8, 2, 8));
        btnReload.addActionListener(ev -> loadDbTabValue(ctx, info));
        infoBar.add(btnReload);

        JButton btnSave = new JButton("保存");
        btnSave.setFont(FONT_BOLD);
        btnSave.setFocusPainted(false);
        btnSave.setMargin(new Insets(2, 8, 2, 8));
        btnSave.addActionListener(ev -> saveDbTabValue(ctx, info));
        infoBar.add(btnSave);

        valuePanel.add(infoBar, BorderLayout.NORTH);

        // 值文本域
        ctx.valueArea = new JTextArea();
        ctx.valueArea.setFont(FONT_MONO);
        ctx.valueArea.setBackground(new Color(0x2B2B2B));
        ctx.valueArea.setForeground(new Color(0xA9B7C6));
        ctx.valueArea.setCaretColor(new Color(0xBBBBBB));
        NetUtil.fixPaste(ctx.valueArea);
        JScrollPane valueScroll = new JScrollPane(ctx.valueArea);
        valueScroll.setBorder(null);
        valuePanel.add(valueScroll, BorderLayout.CENTER);

        split.setBottomComponent(valuePanel);

        // 过期回调：倒计时归零后自动删除 key
        ctx.onKeyExpired = (keyName) -> onKeyExpired(ctx, info, keyName);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.add(split, BorderLayout.CENTER);
        return wrapper;
    }

    /** 清空 value 面板 */
    private void clearValuePanel(DbTabContext ctx) {
        ctx.valueArea.setText("");
        ctx.keyTypeLabel.setText("类型: -");
        ctx.keySizeLabel.setText("大小: -");
    }

    /** 刷新 DB 选项卡的 Key 列表 */
    private void refreshDbTabKeys(DbTabContext ctx, ConnectionInfo info) {
        refreshDbTabKeys(ctx, info, null);
    }

    /** 刷新 DB 选项卡的 Key 列表，刷新完成后选中指定 key */
    private void refreshDbTabKeys(DbTabContext ctx, ConnectionInfo info, String afterRefreshKey) {
        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(ctx.db);

                String filter = ctx.filterField.getText().trim();
                String pattern = filter.isEmpty() ? "*" : filter;
                if (!pattern.contains("*") && !pattern.contains("?")) {
                    pattern = "*" + pattern + "*";
                }

                Set<String> keys = new TreeSet<>();
                String cursor = ScanParams.SCAN_POINTER_START;
                ScanParams params = filter.isEmpty()
                        ? new ScanParams().count(200)
                        : new ScanParams().match(pattern).count(200);
                do {
                    ScanResult<String> result = jedis.scan(cursor, params);
                    keys.addAll(result.getResult());
                    cursor = result.getCursor();
                } while (!cursor.equals(ScanParams.SCAN_POINTER_START));

                SwingUtilities.invokeLater(() -> {
                    ctx.keyModel.clear();
                    for (String key : keys) {
                        ctx.keyModel.addElement(key);
                    }
                    ctx.keyCountLabel.setText("共 " + keys.size() + " 个 key");
                    // 刷新完成后，如果有需要选中的 key，选中它（触发 loadDbTabValue 启动倒计时）
                    if (afterRefreshKey != null) {
                        ctx.keyList.setSelectedValue(afterRefreshKey, true);
                    }
                });
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() ->
                        ctx.keyCountLabel.setText("错误: " + ex.getMessage()));
            }
        });
    }

    /** DB 选项卡中选中 Key */
    private void onDbTabKeySelected(DbTabContext ctx, ConnectionInfo info) {
        String key = ctx.keyList.getSelectedValue();
        if (key == null) {
            // 取消选中时清空 value
            ctx.currentKey = null;
            clearValuePanel(ctx);
            return;
        }
        ctx.currentKey = key;
        loadDbTabValue(ctx, info);
    }

    /** 加载 DB 选项卡中 Key 的值 */
    private void loadDbTabValue(DbTabContext ctx, ConnectionInfo info) {
        if (ctx.currentKey == null) return;
        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(ctx.db);
                String type = jedis.type(ctx.currentKey);
                long ttl = jedis.ttl(ctx.currentKey);

                StringBuilder value = new StringBuilder();
                switch (type) {
                    case "string" -> value.append(jedis.get(ctx.currentKey));
                    case "hash" -> {
                        Map<String, String> hash = jedis.hgetAll(ctx.currentKey);
                        hash.forEach((k, v) -> value.append(k).append(" = ").append(v).append("\n"));
                    }
                    case "list" -> {
                        List<String> list = jedis.lrange(ctx.currentKey, 0, -1);
                        for (int i = 0; i < list.size(); i++) {
                            value.append(i).append(") ").append(list.get(i)).append("\n");
                        }
                    }
                    case "set" -> {
                        Set<String> set = jedis.smembers(ctx.currentKey);
                        int i = 0;
                        for (String member : set) {
                            value.append(i++).append(") ").append(member).append("\n");
                        }
                    }
                    case "zset" -> {
                        List<Tuple> zset = jedis.zrangeWithScores(ctx.currentKey, 0, -1);
                        for (int i = 0; i < zset.size(); i++) {
                            Tuple t = zset.get(i);
                            value.append(i).append(") ").append(t.getElement())
                                    .append(" (score: ").append(t.getScore()).append(")\n");
                        }
                    }
                    case "none" -> value.append("(key 不存在或已过期)");
                    default -> value.append("(不支持的类型: ").append(type).append(")");
                }

                final long finalTtl = ttl;
                SwingUtilities.invokeLater(() -> {
                    ctx.keyTypeLabel.setText("类型: " + type);
                    ctx.keySizeLabel.setText("大小: " + value.length() + " 字节");
                    ctx.valueArea.setText(value.toString());
                    ctx.valueArea.setCaretPosition(0);

                    // 为当前 key 启动独立的 TTL 倒计时（在列表渲染中显示）
                    final String keyName = ctx.currentKey;
                    if (finalTtl >= 0) {
                        ctx.startKeyTtlTimer(keyName, (int) finalTtl);
                    }
                    // -1 永久，-2 已过期：不需要 timer，停止旧的即可
                    if (finalTtl == -1 || finalTtl == -2) {
                        ctx.stopKeyTtlTimer(keyName);
                    }
                });
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() ->
                        ctx.valueArea.setText("加载失败: " + ex.getMessage()));
            }
        });
    }

    /** 保存 DB 选项卡中 Key 的值 */
    private void saveDbTabValue(DbTabContext ctx, ConnectionInfo info) {
        if (ctx.currentKey == null) return;
        String newValue = ctx.valueArea.getText();
        String type = ctx.keyTypeLabel.getText().replace("类型: ", "");

        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(ctx.db);
                switch (type) {
                    case "string" -> jedis.set(ctx.currentKey, newValue);
                    case "list" -> {
                        jedis.del(ctx.currentKey);
                        for (String line : newValue.split("\n")) {
                            String val = line.replaceFirst("^\\d+\\)\\s*", "").trim();
                            if (!val.isEmpty()) jedis.rpush(ctx.currentKey, val);
                        }
                    }
                    case "set" -> {
                        jedis.del(ctx.currentKey);
                        for (String line : newValue.split("\n")) {
                            String val = line.replaceFirst("^\\d+\\)\\s*", "").trim();
                            if (!val.isEmpty()) jedis.sadd(ctx.currentKey, val);
                        }
                    }
                    case "hash" -> {
                        for (String line : newValue.split("\n")) {
                            String[] kv = line.split("=", 2);
                            if (kv.length == 2) jedis.hset(ctx.currentKey, kv[0].trim(), kv[1].trim());
                        }
                    }
                    default -> {
                        SwingUtilities.invokeLater(() ->
                                JOptionPane.showMessageDialog(this,
                                        "不支持编辑类型: " + type, "提示", JOptionPane.WARNING_MESSAGE));
                        return;
                    }
                }
                SwingUtilities.invokeLater(() -> {
                    loadDbTabValue(ctx, info);
                    refreshDbTabKeys(ctx, info);
                });
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                                "保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    /** TTL 倒计时归零，自动删除 key（无需确认） */
    private void onKeyExpired(DbTabContext ctx, ConnectionInfo info, String key) {
        if (key == null) return;
        // 如果过期的是当前选中的 key，清空 value 面板
        if (key.equals(ctx.currentKey)) {
            ctx.currentKey = null;
            clearValuePanel(ctx);
        }
        // 从列表移除
        ctx.keyModel.removeElement(key);
        ctx.keyCountLabel.setText("共 " + ctx.keyModel.size() + " 个 key");
        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(ctx.db);
                jedis.del(key);
                long remaining = jedis.dbSize();
                SwingUtilities.invokeLater(() ->
                        updateDbNodeCount(info.name, ctx.db, remaining));
            } catch (JedisException ignored) {

                // 静默处理，key 可能已被其他客户端删除
            }
        });
    }

    /** DB 选项卡中删除 Key */
    private void deleteDbTabKey(DbTabContext ctx, ConnectionInfo info) {
        String key = ctx.keyList.getSelectedValue();
        if (key == null) {
            JOptionPane.showMessageDialog(this, "请先选择一个 Key", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }
        int result = JOptionPane.showConfirmDialog(this,
                "确定要删除 Key \"" + key + "\" 吗？此操作不可恢复！",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;

        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(ctx.db);
                jedis.del(key);
                // 获取删除后剩余数量
                long remaining = jedis.dbSize();
                SwingUtilities.invokeLater(() -> {
                    ctx.currentKey = null;
                    ctx.stopKeyTtlTimer(key);
                    clearValuePanel(ctx);
                    refreshDbTabKeys(ctx, info);
                    // 更新树节点 key 数量
                    updateDbNodeCount(info.name, ctx.db, remaining);
                });
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                                "删除失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    /** Key 列表右键菜单 */
    private void handleKeyRightClick(MouseEvent e, DbTabContext ctx, ConnectionInfo info) {
        if (!SwingUtilities.isRightMouseButton(e)) return;
        int index = ctx.keyList.locationToIndex(e.getPoint());
        if (index < 0) return;
        // 选中被右键的 key
        ctx.keyList.setSelectedIndex(index);
        String key = ctx.keyList.getSelectedValue();
        if (key == null) return;

        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        menu.setBackground(new Color(0x3C3F41));

        JMenuItem delItem = new JMenuItem("删除 Key \"" + key + "\"");
        delItem.setFont(FONT);
        delItem.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        delItem.setBackground(new Color(0x3C3F41));
        delItem.setForeground(new Color(0xFF6B6B));
        delItem.addActionListener(ev -> deleteDbTabKey(ctx, info));
        menu.add(delItem);

        menu.show(ctx.keyList, e.getX(), e.getY());
    }

    /** DB 选项卡中新增 Key 对话框 */
    private void showDbTabAddKeyDialog(DbTabContext ctx, ConnectionInfo info) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "新建 Key - " + info.name + "-DB" + ctx.db, true);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        int y = 0;

        JTextField nameField = createDialogTextField("");
        panel.add(new JLabel("Key 名:"), gbc(0, y));
        panel.add(nameField, gbc(1, y++));

        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"string", "hash", "list", "set", "zset"});
        typeCombo.setFont(FONT);
        typeCombo.setPreferredSize(new Dimension(280, 28));
        panel.add(new JLabel("类型:"), gbc(0, y));
        panel.add(typeCombo, gbc(1, y++));

        JTextArea valueArea = new JTextArea(6, 25);
        valueArea.setFont(FONT);
        valueArea.setLineWrap(true);
        valueArea.setWrapStyleWord(true);
        NetUtil.fixPaste(valueArea);
        JScrollPane valueScroll = new JScrollPane(valueArea);
        valueScroll.setPreferredSize(new Dimension(280, 120));
        panel.add(new JLabel("值:"), gbc(0, y));
        panel.add(valueScroll, gbc(1, y++));

        JSpinner ttlSpin = new JSpinner(new SpinnerNumberModel(-1, -1, Integer.MAX_VALUE, 1));
        ttlSpin.setFont(FONT);
        panel.add(new JLabel("TTL(秒):"), gbc(0, y));
        panel.add(ttlSpin, gbc(1, y++));

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton okBtn = new JButton("保存");
        okBtn.setFont(FONT_BOLD);
        okBtn.setFocusPainted(false);
        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(FONT);
        cancelBtn.setFocusPainted(false);

        okBtn.addActionListener(ev -> {
            String name = nameField.getText().trim();
            String type = (String) typeCombo.getSelectedItem();
            String value = valueArea.getText().trim();
            int ttl = (Integer) ttlSpin.getValue();

            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "请输入 Key 名", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }

            dialog.dispose();
            threadPool.submit(() -> {
                try (Jedis jedis = createJedis(info)) {
                    jedis.select(ctx.db);
                    switch (type) {
                        case "string" -> jedis.set(name, value);
                        case "list" -> {
                            for (String line : value.split("\n")) {
                                String v = line.trim();
                                if (!v.isEmpty()) jedis.rpush(name, v);
                            }
                        }
                        case "set" -> {
                            for (String line : value.split("\n")) {
                                String v = line.trim();
                                if (!v.isEmpty()) jedis.sadd(name, v);
                            }
                        }
                        case "hash" -> {
                            for (String line : value.split("\n")) {
                                String[] kv = line.split("=", 2);
                                if (kv.length >= 1 && !kv[0].trim().isEmpty()) {
                                    jedis.hset(name, kv[0].trim(), kv.length > 1 ? kv[1].trim() : "");
                                }
                            }
                        }
                        case "zset" -> {
                            for (String line : value.split("\n")) {
                                String[] parts = line.trim().split("\\s+", 2);
                                if (parts.length >= 1) {
                                    double score;
                                    try { score = Double.parseDouble(parts[0]); }
                                    catch (NumberFormatException ex) { score = 1.0; }
                                    jedis.zadd(name, score, parts.length > 1 ? parts[1] : parts[0]);
                                }
                            }
                        }
                    }
                    if (ttl > 0) jedis.expire(name, ttl);
                    final int finalTtl = ttl;
                    SwingUtilities.invokeLater(() -> {
                        refreshDbTabKeys(ctx, info, name);
                        // 新建的 key 如果有 TTL，启动独立倒计时
                        if (finalTtl > 0) {
                            ctx.startKeyTtlTimer(name, finalTtl);
                        }
                    });
                } catch (JedisException ex) {
                    SwingUtilities.invokeLater(() ->
                            JOptionPane.showMessageDialog(this,
                                    "创建失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
                }
            });
        });
        cancelBtn.addActionListener(ev -> dialog.dispose());

        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);
        panel.add(btnPanel, gbc(0, y, 2));

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(450, 350));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** 带渐显动画的菜单弹出 */
    private void showMenuWithFade(JPopupMenu menu, Component invoker, int x, int y) {
        menu.show(invoker, x, y);
        // 找到 popup 的顶层窗口设置透明度渐显
        Window w = SwingUtilities.getWindowAncestor(menu);
        if (w != null && w.getGraphicsConfiguration().getDevice().isWindowTranslucencySupported(
                java.awt.GraphicsDevice.WindowTranslucency.TRANSLUCENT)) {
            w.setOpacity(0.0f);
            javax.swing.Timer timer = new javax.swing.Timer(20, null);
            final float[] alpha = {0.0f};
            timer.addActionListener(ev -> {
                alpha[0] += 0.12f;
                if (alpha[0] >= 1.0f) {
                    w.setOpacity(1.0f);
                    timer.stop();
                } else {
                    w.setOpacity(alpha[0]);
                }
            });
            timer.start();
        }
    }

    private JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        menu.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1),
                BorderFactory.createEmptyBorder(4, 4, 4, 4)));
        menu.setBackground(new Color(0x3C3F41));
        return menu;
    }

    private JMenuItem createMenuItem(String text, Runnable action) {
        JMenuItem item = new JMenuItem(text);
        item.setFont(FONT);
        item.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        item.setBackground(new Color(0x3C3F41));
        item.setForeground(new Color(0xBBBBBB));
        item.addActionListener(ev -> action.run());
        return item;
    }

    /** 编辑连接对话框 */
    private void showEditConnectionDialog(DefaultMutableTreeNode node) {
        String connName = (String) node.getUserObject();
        ConnectionInfo info = connections.get(connName);
        if (info == null) return;

        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "编辑 Redis 连接", true);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        int y = 0;

        JTextField nameField = createDialogTextField(connName);
        panel.add(new JLabel("连接名:"), gbc(0, y));
        panel.add(nameField, gbc(1, y++));

        JTextField hostField = createDialogTextField(info.host);
        panel.add(new JLabel("主机:"), gbc(0, y));
        panel.add(hostField, gbc(1, y++));

        JTextField portField = createDialogTextField(String.valueOf(info.port));
        panel.add(new JLabel("端口:"), gbc(0, y));
        panel.add(portField, gbc(1, y++));

        JPasswordField passField = createDialogPassField();
        passField.setText(info.password);
        panel.add(new JLabel("密码:"), gbc(0, y));
        panel.add(passField, gbc(1, y++));

        JButton btnSave = new JButton("保存");
        btnSave.setFont(FONT);
        btnSave.addActionListener(ev -> {
            String newName = nameField.getText().trim();
            String host = hostField.getText().trim();
            String portStr = portField.getText().trim();
            String password = new String(passField.getPassword());

            if (newName.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "请填写所有必填项", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            int port;
            try {
                port = Integer.parseInt(portStr);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dialog, "端口号无效", "错误", JOptionPane.ERROR_MESSAGE);
                return;
            }

            connections.remove(connName);
            ConnectionInfo newInfo = new ConnectionInfo(newName, host, port, password);
            connections.put(newName, newInfo);

            node.setUserObject(newName);
            treeModel.nodeChanged(node);

            if (connName.equals(currentConnectionName)) {
                currentConnectionName = newName;
            }

            saveLocalConfig();
            dialog.dispose();
        });

        JButton btnCancel = new JButton("取消");
        btnCancel.setFont(FONT);
        btnCancel.addActionListener(ev -> dialog.dispose());

        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        btnPanel.add(btnSave);
        btnPanel.add(btnCancel);
        panel.add(btnPanel, gbc(1, y));

        dialog.add(panel);
        dialog.pack();
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** 删除连接 */
    private void deleteConnection(DefaultMutableTreeNode node) {
        String connName = (String) node.getUserObject();
        int result = JOptionPane.showConfirmDialog(this,
                "确定要删除连接 \"" + connName + "\" 吗？",
                "确认删除", JOptionPane.YES_NO_OPTION);
        if (result != JOptionPane.YES_OPTION) return;

        // 断开连接
        ConnectionInfo info = connections.get(connName);
        if (info != null) {
            // 关闭该连接的所有 DB 选项卡
            List<String> toRemove = new ArrayList<>();
            for (Map.Entry<String, DbTabContext> entry : dbTabs.entrySet()) {
                if (entry.getValue().connName.equals(connName)) {
                    toRemove.add(entry.getKey());
                }
            }
            for (String key : toRemove) {
                for (int i = 0; i < detailTabs.getTabCount(); i++) {
                    if (detailTabs.getTitleAt(i).equals(connName + "-DB" + dbTabs.get(key).db)) {
                        detailTabs.removeTabAt(i);
                        break;
                    }
                }
                dbTabs.remove(key);
            }

            connections.remove(connName);
            if (connName.equals(currentConnectionName)) {
                currentConnectionName = null;
                serverInfoPane.setText("<html><body style='color:#A9B7C6;font-family:Microsoft YaHei;font-size:12px;margin:4px'>未连接</body></html>");
            }
            saveLocalConfig();
        }
        treeModel.removeNodeFromParent(node);
    }

    /** 刷新连接 */
    private void refreshConnection(DefaultMutableTreeNode node) {
        String connName = (String) node.getUserObject();
        ConnectionInfo info = connections.get(connName);
        if (info == null) return;

        try (Jedis jedis = new Jedis(info.host, info.port, info.timeout)) {
            if (!info.password.isEmpty()) {
                jedis.auth(info.password);
            }
            String pong = jedis.ping();
            if ("PONG".equalsIgnoreCase(pong)) {
                JOptionPane.showMessageDialog(this, "连接正常: " + pong, "刷新成功", JOptionPane.INFORMATION_MESSAGE);
            }
        } catch (JedisException ex) {
            JOptionPane.showMessageDialog(this, "连接失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
        }
    }

    // ==================== 连接管理对话框 ====================

    private JTextField createDialogTextField(String text) {
        JTextField f = new JTextField(text, 25);
        f.setFont(FONT);
        f.setPreferredSize(new Dimension(280, 28));
        return f;
    }

    private JPasswordField createDialogPassField() {
        JPasswordField f = new JPasswordField(25);
        f.setFont(FONT);
        f.setPreferredSize(new Dimension(280, 28));
        return f;
    }

    private void showNewConnectionDialog() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "新建 Redis 连接", true);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        int y = 0;

        JTextField nameField = createDialogTextField("Redis-本地");
        panel.add(new JLabel("连接名:"), gbc(0, y));
        panel.add(nameField, gbc(1, y++));

        JTextField hostField = createDialogTextField("127.0.0.1");
        panel.add(new JLabel("主机:"), gbc(0, y));
        panel.add(hostField, gbc(1, y++));

        JTextField portField = createDialogTextField("6379");
        panel.add(new JLabel("端口:"), gbc(0, y));
        panel.add(portField, gbc(1, y++));

        JPasswordField passField = createDialogPassField();
        panel.add(new JLabel("密码:"), gbc(0, y));
        panel.add(passField, gbc(1, y++));

        // 按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
        JButton okBtn = new JButton("连接");
        okBtn.setFont(FONT_BOLD);
        okBtn.setFocusPainted(false);
        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(FONT);
        cancelBtn.setFocusPainted(false);

        okBtn.addActionListener(ev -> {
            String name = nameField.getText().trim();
            String host = hostField.getText().trim();
            String portStr = portField.getText().trim();
            String pass = new String(passField.getPassword());

            if (name.isEmpty() || host.isEmpty() || portStr.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "请填写所有必填项", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            try {
                int port = Integer.parseInt(portStr);
                ConnectionInfo info = new ConnectionInfo(name, host, port, pass);
                connections.put(name, info);
                addConnectionNode(info);
                dialog.dispose();
                connectToRedis(info);
            } catch (NumberFormatException ex) {
                JOptionPane.showMessageDialog(dialog, "端口号格式错误", "错误", JOptionPane.ERROR_MESSAGE);
            }
        });
        cancelBtn.addActionListener(ev -> dialog.dispose());

        btnPanel.add(okBtn);
        btnPanel.add(cancelBtn);
        panel.add(btnPanel, gbc(0, y, 2));

        dialog.setContentPane(panel);
        dialog.pack();
        dialog.setMinimumSize(new Dimension(380, 220));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ==================== 树节点操作 ====================

    private void addConnectionNode(ConnectionInfo info) {
        DefaultMutableTreeNode connNode = new DefaultMutableTreeNode(info.name);
        // 默认添加 DB0-DB15
        for (int i = 0; i < 16; i++) {
            connNode.add(new DefaultMutableTreeNode(new DbNodeInfo(i)));
        }
        rootNode.add(connNode);
        treeModel.reload();
        // 不自动展开连接节点，也不自动打开 DB，用户手动双击操作
    }

    // ==================== 连接管理 ====================

    private void connectToRedis(ConnectionInfo info) {
        currentConnectionName = info.name;
        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                String pong = jedis.ping();
                String serverInfo = jedis.info("server");
                String clientsInfo = jedis.info("clients");
                String memoryInfo = jedis.info("memory");
                String statsInfo = jedis.info("stats");
                String keyspaceInfo = jedis.info("keyspace");

                // 加载每个 DB 的 key 数量
                long[] dbSizes = new long[16];
                for (int i = 0; i < 16; i++) {
                    try {
                        jedis.select(i);
                        dbSizes[i] = jedis.dbSize();
                    } catch (Exception ignored) {
                        dbSizes[i] = -1;
                    }
                }

                SwingUtilities.invokeLater(() -> {
                    StringBuilder html = new StringBuilder();
                    html.append("<html><body style='color:#A9B7C6;font-family:Microsoft YaHei;font-size:12px;margin:6px'>");
                    html.append("<b style='color:#6A9955'>连接:</b> ").append(escapeHtml(info.name)).append("<br>");
                    html.append("<b style='color:#6A9955'>状态:</b> <span style='color:#4EC9B0'>").append(pong).append("</span><br><br>");

                    // 解析 server info，输出带样式的 HTML
                    parseInfoSectionHtml(html, serverInfo, "Server");
                    parseInfoSectionHtml(html, clientsInfo, "Clients");
                    parseInfoSectionHtml(html, memoryInfo, "Memory");
                    parseInfoSectionHtml(html, statsInfo, "Stats");
                    parseInfoSectionHtml(html, keyspaceInfo, "Keyspace");

                    html.append("</body></html>");
                    serverInfoPane.setText(html.toString());

                    // 更新树中 DB 节点的 key 数量
                    for (int i = 0; i < 16; i++) {
                        updateDbNodeCount(info.name, i, dbSizes[i]);
                    }

                    // 不自动打开 DB0，用户双击 DB 节点时手动打开
                });
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() -> {
                    serverInfoPane.setText("<html><body style='color:#E57373;font-family:Microsoft YaHei;font-size:12px;margin:4px'>连接失败: " + escapeHtml(ex.getMessage()) + "</body></html>");
                });
            }
        });
    }

    /** 查找连接名对应的树节点 */
    private DefaultMutableTreeNode findConnectionNode(String connName) {
        for (int i = 0; i < rootNode.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) rootNode.getChildAt(i);
            if (connName.equals(child.getUserObject())) {
                return child;
            }
        }
        return null;
    }

    /** 更新树中 DB 节点的 key 数量 */
    private void updateDbNodeCount(String connName, int db, long count) {
        DefaultMutableTreeNode connNode = findConnectionNode(connName);
        if (connNode != null) {
            for (int i = 0; i < connNode.getChildCount(); i++) {
                DefaultMutableTreeNode dbNode = (DefaultMutableTreeNode) connNode.getChildAt(i);
                if (dbNode.getUserObject() instanceof DbNodeInfo dbInfo && dbInfo.dbIndex == db) {
                    dbInfo.keyCount = count;
                    treeModel.nodeChanged(dbNode);
                    break;
                }
            }
        }
    }

    /** 刷新 DB 节点的 key 数量（从 Redis 实时获取） */
    private void refreshDbNodeCount(ConnectionInfo info, int db) {
        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(db);
                long count = jedis.dbSize();
                SwingUtilities.invokeLater(() -> updateDbNodeCount(info.name, db, count));
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                                "刷新失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private void deleteDatabase(ConnectionInfo info, int db, String dbText) {
        int result = JOptionPane.showConfirmDialog(this,
                "确定要清空 " + dbText + " 的所有数据吗？\n此操作不可撤销！（执行 FLUSHDB）",
                "危险操作", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;

        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(db);
                jedis.flushDB();
                SwingUtilities.invokeLater(() -> {
                    // 刷新对应的 DB 选项卡（如果打开的话）
                    String tabKey = info.name + "|" + db;
                    DbTabContext ctx = dbTabs.get(tabKey);
                    if (ctx != null) {
                        ctx.keyModel.clear();
                        ctx.valueArea.setText("");
                        ctx.keyTypeLabel.setText("类型: -");
                        ctx.keySizeLabel.setText("大小: -");
                    }
                    // 更新树节点 key 数量为 0
                    updateDbNodeCount(info.name, db, 0);
                    JOptionPane.showMessageDialog(this,
                            dbText + " 已清空", "完成", JOptionPane.INFORMATION_MESSAGE);
                });
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() ->
                        JOptionPane.showMessageDialog(this,
                                "清空失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
            }
        });
    }

    private DefaultMutableTreeNode findNode(DefaultMutableTreeNode parent, String target) {
        for (int i = 0; i < parent.getChildCount(); i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            if (target.equals(child.getUserObject())) {
                return child;
            }
            DefaultMutableTreeNode found = findNode(child, target);
            if (found != null) return found;
        }
        return null;
    }

    private void parseInfoSectionHtml(StringBuilder html, String info, String section) {
        String[] lines = info.split("\n");
        boolean inSection = false;
        boolean hasContent = false;
        for (String line : lines) {
            if (line.startsWith("# " + section)) {
                inSection = true;
                hasContent = true;
                html.append("<br><b style='color:#CE9178'>── ").append(section).append(" ──</b><br>");
                continue;
            }
            if (inSection) {
                if (line.startsWith("# ") || line.trim().isEmpty()) break;
                String[] kv = line.split(":", 2);
                if (kv.length == 2) {
                    html.append("&nbsp;&nbsp;<span style='color:#9CDCFE'>").append(kv[0])
                        .append(":</span> <span style='color:#B5CEA8'>").append(escapeHtml(kv[1]))
                        .append("</span><br>");
                }
            }
        }
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    // ==================== Redis 命令控制台 ====================

    private void executeConsoleCommand() {
        String command = consoleInput.getText().trim();
        if (command.isEmpty()) return;

        // 优先使用当前活动标签页的上下文
        if (activeTabContext == null) {
            consoleOutput.append("错误: 请先双击打开一个 DB 选项卡\n");
            return;
        }

        String connName = activeTabContext.connName;
        int db = activeTabContext.db;
        ConnectionInfo info = connections.get(connName);
        if (info == null) {
            consoleOutput.append("错误: 连接不存在\n");
            return;
        }

        consoleOutput.append("[" + connName + "-DB" + db + "] > " + command + "\n");
        consoleInput.setText("");
        autoCompletePopup.setVisible(false);

        threadPool.submit(() -> {
            try (Jedis jedis = createJedis(info)) {
                jedis.select(db);

                // 解析命令和参数
                String[] parts = command.split("\\s+");
                String cmd = parts[0].toUpperCase();
                String[] args = Arrays.copyOfRange(parts, 1, parts.length);

                Object result = executeRedisCommand(jedis, cmd, args);

                // 写命令执行成功后刷新当前 DB 的 key 列表
                boolean isWriteCmd = isWriteCommand(cmd);

                SwingUtilities.invokeLater(() -> {
                    if (result instanceof byte[]) {
                        consoleOutput.append(new String((byte[]) result, StandardCharsets.UTF_8) + "\n");
                    } else if (result instanceof List) {
                        @SuppressWarnings("unchecked")
                        List<Object> list = (List<Object>) result;
                        if (list.isEmpty()) {
                            consoleOutput.append("(empty)\n");
                        } else {
                            for (int i = 0; i < list.size(); i++) {
                                Object item = list.get(i);
                                consoleOutput.append(i + 1 + ") " + (item instanceof byte[] ? new String((byte[]) item, StandardCharsets.UTF_8) : item) + "\n");
                            }
                        }
                    } else if (result instanceof Set) {
                        @SuppressWarnings("unchecked")
                        Set<Object> set = (Set<Object>) result;
                        int i = 1;
                        for (Object o : set) {
                            consoleOutput.append(i++ + ") " + (o instanceof byte[] ? new String((byte[]) o, StandardCharsets.UTF_8) : o) + "\n");
                        }
                    } else if (result instanceof Map) {
                        @SuppressWarnings("unchecked")
                        Map<Object, Object> map = (Map<Object, Object>) result;
                        map.forEach((k, v) -> consoleOutput.append(
                                (k instanceof byte[] ? new String((byte[]) k, StandardCharsets.UTF_8) : k)
                                + " = " +
                                (v instanceof byte[] ? new String((byte[]) v, StandardCharsets.UTF_8) : v) + "\n"));
                    } else {
                        consoleOutput.append(result + "\n");
                    }
                    consoleOutput.setCaretPosition(consoleOutput.getDocument().getLength());

                    // 写命令执行后刷新 key 列表
                    if (isWriteCmd && activeTabContext != null) {
                        refreshDbTabKeys(activeTabContext, info, null);
                    }
                });
            } catch (JedisException ex) {
                SwingUtilities.invokeLater(() -> {
                    consoleOutput.append("错误: " + ex.getMessage() + "\n");
                    consoleOutput.setCaretPosition(consoleOutput.getDocument().getLength());
                });
            }
        });
    }

    /** 判断是否为写命令（需要刷新 key 列表） */
    private static boolean isWriteCommand(String cmd) {
        return switch (cmd) {
            case "SET", "DEL", "MSET", "HSET", "HDEL", "LPUSH", "RPUSH", "LPOP", "RPOP",
                 "SADD", "SREM", "ZADD", "ZREM", "INCR", "INCRBY", "DECR", "DECRBY",
                 "SETEX", "SETNX", "RENAME", "RENAMENX", "FLUSHDB", "FLUSHALL",
                 "APPEND", "SETRANGE", "GETSET", "GETDEL", "MSETNX", "PSETEX",
                 "LREM", "LSET", "LTRIM", "RPOPLPUSH", "LMOVE",
                 "SINTERSTORE", "SUNIONSTORE", "SDIFFSTORE", "SMOVE",
                 "ZINCRBY", "ZREMRANGEBYRANK", "ZREMRANGEBYSCORE",
                 "ZUNIONSTORE", "ZINTERSTORE",
                 "HINCRBY", "HINCRBYFLOAT", "HSETNX",
                 "INCRBYFLOAT", "PERSIST", "EXPIRE", "EXPIREAT", "PEXPIRE", "PEXPIREAT",
                 "MOVE", "RESTORE", "UNLINK", "COPY" -> true;
            default -> false;
        };
    }

    @SuppressWarnings("unchecked")
    private Object executeRedisCommand(Jedis jedis, String cmd, String... args) throws JedisException {
        return switch (cmd) {
            case "PING" -> jedis.ping();
            case "GET" -> jedis.get(args[0]);
            case "SET" -> jedis.set(args[0], args.length > 1 ? args[1] : "");
            case "DEL" -> jedis.del(args);
            case "EXISTS" -> jedis.exists(args[0]);
            case "EXPIRE" -> jedis.expire(args[0], Long.parseLong(args[1]));
            case "TTL" -> jedis.ttl(args[0]);
            case "TYPE" -> jedis.type(args[0]);
            case "KEYS" -> {
                Set<String> keys = jedis.keys(args.length > 0 ? args[0] : "*");
                yield new ArrayList<>(keys);
            }
            case "SCAN" -> {
                String cursor = args.length > 0 ? args[0] : "0";
                ScanParams params = new ScanParams().count(20);
                if (args.length > 1) params.match(args[1]);
                ScanResult<String> sr = jedis.scan(cursor, params);
                yield sr.getCursor() + " => " + sr.getResult();
            }
            case "RENAME" -> jedis.rename(args[0], args[1]);
            case "RENAMENX" -> jedis.renamenx(args[0], args[1]);
            case "INCR" -> jedis.incr(args[0]);
            case "DECR" -> jedis.decr(args[0]);
            case "INCRBY" -> jedis.incrBy(args[0], Long.parseLong(args[1]));
            case "APPEND" -> jedis.append(args[0], args[1]);
            case "STRLEN" -> jedis.strlen(args[0]);
            case "GETRANGE" -> jedis.getrange(args[0], Long.parseLong(args[1]), Long.parseLong(args[2]));
            case "SETEX" -> jedis.setex(args[0], Long.parseLong(args[1]), args[2]);
            case "MSET" -> jedis.mset(args);
            case "MGET" -> jedis.mget(args);
            case "HSET" -> jedis.hset(args[0], java.util.Map.of(args[1], args[2]));
            case "HGET" -> jedis.hget(args[0], args[1]);
            case "HGETALL" -> jedis.hgetAll(args[0]);
            case "HDEL" -> jedis.hdel(args[0], args[1]);
            case "HEXISTS" -> jedis.hexists(args[0], args[1]);
            case "HLEN" -> jedis.hlen(args[0]);
            case "HKEYS" -> jedis.hkeys(args[0]);
            case "HVALS" -> jedis.hvals(args[0]);
            case "LPUSH" -> jedis.lpush(args[0], args[1]);
            case "RPUSH" -> jedis.rpush(args[0], args[1]);
            case "LPOP" -> jedis.lpop(args[0]);
            case "RPOP" -> jedis.rpop(args[0]);
            case "LRANGE" -> jedis.lrange(args[0], Long.parseLong(args[1]), Long.parseLong(args[2]));
            case "LLEN" -> jedis.llen(args[0]);
            case "SADD" -> jedis.sadd(args[0], args[1]);
            case "SMEMBERS" -> jedis.smembers(args[0]);
            case "SREM" -> jedis.srem(args[0], args[1]);
            case "SCARD" -> jedis.scard(args[0]);
            case "SISMEMBER" -> jedis.sismember(args[0], args[1]);
            case "ZADD" -> jedis.zadd(args[0], Double.parseDouble(args[1]), args[2]);
            case "ZRANGE" -> jedis.zrange(args[0], Long.parseLong(args[1]), Long.parseLong(args[2]));
            case "ZRANGEBYSCORE" -> jedis.zrangeByScore(args[0], Double.parseDouble(args[1]), Double.parseDouble(args[2]));
            case "ZREM" -> jedis.zrem(args[0], args[1]);
            case "ZCARD" -> jedis.zcard(args[0]);
            case "ZSCORE" -> jedis.zscore(args[0], args[1]);
            case "ZRANK" -> jedis.zrank(args[0], args[1]);
            case "DBSIZE" -> jedis.dbSize();
            case "FLUSHDB" -> {
                jedis.flushDB();
                yield "OK";
            }
            case "FLUSHALL" -> {
                jedis.flushAll();
                yield "OK";
            }
            case "INFO" -> jedis.info(args.length > 0 ? args[0] : "server");
            case "SELECT" -> {
                int db = Integer.parseInt(args[0]);
                jedis.select(db);
                currentDb = db;
                yield "OK";
            }
            case "RANDOMKEY" -> jedis.randomKey();
            case "SAVE" -> {
                jedis.save();
                yield "OK";
            }
            case "BGSAVE" -> {
                jedis.bgsave();
                yield "Background saving started";
            }
            default -> jedis.eval("return redis.call('" + cmd + "', unpack(ARGV))",
                    java.util.List.of(), java.util.List.of(args));
        };
    }

    // ==================== 工具方法 ====================

    private Jedis createJedis(ConnectionInfo info) {
        Jedis jedis = new Jedis(info.host, info.port, info.timeout);
        if (info.password != null && !info.password.isEmpty()) {
            jedis.auth(info.password);
        }
        jedis.ping(); // 测试连接
        return jedis;
    }

    // ==================== 树渲染器 ====================

    private static class RedisTreeRenderer extends DefaultTreeCellRenderer {
        private final Icon computerIcon = UIManager.getIcon("FileView.computerIcon");
        private final Icon folderIcon = UIManager.getIcon("FileView.directoryIcon");

        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                                                      boolean sel, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setFont(FONT);
            if (value instanceof DefaultMutableTreeNode node) {
                Object obj = node.getUserObject();
                if (obj instanceof DbNodeInfo dbInfo) {
                    setText(dbInfo.toString());
                    setIcon(folderIcon);
                } else if (obj instanceof String text && !text.equals("Redis 连接")) {
                    setIcon(computerIcon);
                }
            }
            return this;
        }
    }

    // ==================== Key 列表渲染器（带 TTL 倒计时） ====================

    private static class KeyTtlListCellRenderer extends DefaultListCellRenderer {
        private final DbTabContext ctx;
        private final Font plainFont = new Font("Consolas", Font.PLAIN, 13);
        private final Color ttlColor = new Color(0xCC7832);  // 橙色
        private final Color expireColor = new Color(0xCC4444); // 红色

        KeyTtlListCellRenderer(DbTabContext ctx) { this.ctx = ctx; }

        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value,
                int index, boolean isSelected, boolean cellHasFocus) {
            super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
            setFont(plainFont);
            String key = (String) value;
            if (key != null) {
                int remaining = ctx.getKeyTtl(key);
                if (remaining > 0) {
                    setText(key + "  [TTL: " + remaining + "s]");
                    setForeground(isSelected ? Color.WHITE : ttlColor);
                } else if (remaining == 0) {
                    setText(key + "  [Expired]");
                    setForeground(isSelected ? Color.WHITE : expireColor);
                } else {
                    // -1 永久，-2 无记录：只显示 key 名
                    setText(key);
                }
            }
            return this;
        }
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        // 加载保存的连接信息
        String countStr = config.get("redis.connection.count");
        int count = countStr.isEmpty() ? 0 : Integer.parseInt(countStr);
        for (int i = 0; i < count; i++) {
            String prefix = "redis.connection." + i + ".";
            String name = config.get(prefix + "name");
            String host = config.get(prefix + "host");
            String portStr = config.get(prefix + "port");
            String pass = config.get(prefix + "password");
            if (name.isEmpty()) continue;
            int port = portStr.isEmpty() ? 6379 : Integer.parseInt(portStr);
            ConnectionInfo info = new ConnectionInfo(name, host.isEmpty() ? "127.0.0.1" : host, port, pass);
            connections.put(name, info);
            addConnectionNode(info);
        }
    }

    @Override
    public void saveConfig(ConfigManager config) {
        config.set("redis.connection.count", String.valueOf(connections.size()));
        int i = 0;
        for (ConnectionInfo info : connections.values()) {
            String prefix = "redis.connection." + i + ".";
            config.set(prefix + "name", info.name);
            config.set(prefix + "host", info.host);
            config.set(prefix + "port", String.valueOf(info.port));
            config.set(prefix + "password", info.password != null ? info.password : "");
            i++;
        }
    }

    /** 保存配置到本地文件（用于编辑/删除连接后即时持久化） */
    private void saveLocalConfig() {
        saveConfig(localConfig);
        localConfig.save();
    }
}
