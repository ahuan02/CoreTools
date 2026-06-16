package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import org.fife.ui.autocomplete.*;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rsyntaxtextarea.Theme;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;
import javax.swing.event.TreeExpansionEvent;
import javax.swing.event.TreeWillExpandListener;
import javax.swing.table.DefaultTableCellRenderer;
import javax.swing.table.DefaultTableModel;
import javax.swing.tree.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.sql.*;
import java.util.List;
import java.util.*;

/**
 * 数据库客户端（模仿 Navicat 风格）
 * - 左侧：JTree 展示连接 → 数据库 → 表
 * - 右侧：多标签页（SQL 查询编辑器 + 表数据视图）
 * - 双击表 → 打开新的表数据标签页
 * - 新建查询 → 打开新的 SQL 编辑器标签页
 */
public class DatabasePanel extends AbstractCommandPanel {

    private static final Font FONT = NetUtil.FONT_TEXT;
    private static final Font FONT_BOLD = new Font("Microsoft YaHei", Font.BOLD, 12);
    private static final Font FONT_TREE = new Font("Microsoft YaHei", Font.PLAIN, 12);

    // ===== 左侧树 =====
    private JTree dbTree;
    private DefaultTreeModel treeModel;
    private DefaultMutableTreeNode rootNode;

    // ===== 状态栏 =====
    private JLabel statusLabel;

    // ===== 右侧内容标签页 =====
    private JTabbedPane contentTabs;
    private int queryTabCounter = 1;      // 查询标签页计数器
    private static final int PAGE_SIZE = 500;

    // ===== 日志 =====
    private JTextPane logPane;

    // ===== 连接管理 =====
    // 当前选中/激活的连接信息
    private ConnectionInfo activeConnInfo;
    // 所有连接：key = 连接名
    private final Map<String, ConnectionInfo> connections = new LinkedHashMap<>();

    // 图标前缀（用于标识节点类型，渲染器中会被替换为实际图标）
    private static final String ICON_CONNECT_PREFIX = "@CONN ";
    private static final String ICON_DB_PREFIX = "@DB ";
    private static final String ICON_TABLE_PREFIX = "@TBL ";

    // 图标
    private static final Icon ICON_CONNECT = createCircleIcon(new Color(0x4CAF50));   // 绿色圆点（已连接）
    private static final Icon ICON_CONNECT_DISABLED = createCircleIcon(new Color(0x9E9E9E)); // 灰色圆点（未连接）
    private static final Icon ICON_DB = createCircleIcon(new Color(0x2196F3));        // 蓝色圆点
    private static final Icon ICON_TABLE = createCircleIcon(new Color(0xFF9800));     // 橙色圆点
    private static final Icon ICON_LOADING = createCircleIcon(new Color(0x9E9E9E));   // 灰色圆点

    private static Icon createCircleIcon(Color color) {
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(color);
                g2.fillOval(x + 2, y + 3, 10, 10);
                g2.dispose();
            }
            @Override
            public int getIconWidth() { return 14; }
            @Override
            public int getIconHeight() { return 16; }
        };
    }

    public DatabasePanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT);
        splitPane.setLeftComponent(buildLeftPanel());
        splitPane.setRightComponent(buildRightPanel());
        splitPane.setDividerLocation(240);
        splitPane.setDividerSize(4);
        add(splitPane, BorderLayout.CENTER);
    }

    // ==================== 左侧面板：连接树 ====================

    private JPanel buildLeftPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));
        panel.setMinimumSize(new Dimension(200, 0));

        // 标题行：标题 + 新建连接按钮（放在右侧，避免占用树空间）
        JPanel titleBar = new JPanel(new BorderLayout(0, 0));
        titleBar.setBorder(new EmptyBorder(4, 6, 4, 4));
        JLabel title = new JLabel("数据库连接");
        title.setFont(new Font("Microsoft YaHei", Font.BOLD, 13));
        titleBar.add(title, BorderLayout.WEST);
        JButton btnNewConn = new JButton("+ 新建连接");
        btnNewConn.setFont(FONT_BOLD);
        btnNewConn.setFocusPainted(false);
        btnNewConn.setMargin(new java.awt.Insets(2, 8, 2, 8));
        btnNewConn.addActionListener(e -> showNewConnectionDialog());
        titleBar.add(btnNewConn, BorderLayout.EAST);
        panel.add(titleBar, BorderLayout.NORTH);

        // 树
        rootNode = new DefaultMutableTreeNode("连接");
        treeModel = new DefaultTreeModel(rootNode);
        dbTree = new JTree(treeModel);
        dbTree.setFont(FONT_TREE);
        dbTree.setRowHeight(22);
        dbTree.setRootVisible(false);
        dbTree.setShowsRootHandles(true);
        dbTree.setCellRenderer(new DbTreeRenderer());
        // 禁止 JTree 默认的单击展开/折叠，统一由 handleTreeDoubleClick 处理
        dbTree.setToggleClickCount(0);

        // 双击展开/连接
        dbTree.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2) {
                    handleTreeDoubleClick(e);
                }
            }

            @Override
            public void mousePressed(MouseEvent e) {
                if (e.isPopupTrigger()) showTreePopup(e);
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                if (e.isPopupTrigger()) showTreePopup(e);
            }
        });

        // 展开时加载子节点
        dbTree.addTreeWillExpandListener(new TreeWillExpandListener() {
            @Override
            public void treeWillExpand(TreeExpansionEvent event) throws ExpandVetoException {
                TreePath path = event.getPath();
                DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (node.getChildCount() == 1) {
                    DefaultMutableTreeNode firstChild = (DefaultMutableTreeNode) node.getChildAt(0);
                    Object obj = firstChild.getUserObject();
                    if (obj instanceof String && "加载中...".equals(obj)) {
                        Object parentObj = node.getUserObject();
                        if (parentObj instanceof String) {
                            String parentText = (String) parentObj;
                            if (parentText.startsWith(ICON_CONNECT_PREFIX)) {
                                // 连接节点 → 加载数据库列表
                                String connName = extractConnName(parentText);
                                ConnectionInfo info = connections.get(connName);
                                if (info != null && info.conn != null) {
                                    loadDatabasesForConn(info, node);
                                }
                            } else if (parentText.startsWith(ICON_DB_PREFIX)) {
                                // 数据库节点 → 加载表
                                ConnectionInfo info = findConnectionForNode(node);
                                if (info != null) {
                                    loadTablesForDbNode(info, node);
                                }
                            }
                        }
                    }
                }
            }

            @Override
            public void treeWillCollapse(TreeExpansionEvent event) throws ExpandVetoException {}
        });

        JScrollPane treeScroll = new JScrollPane(dbTree);
        treeScroll.setBorder(new EmptyBorder(0, 0, 0, 0));
        panel.add(treeScroll, BorderLayout.CENTER);

        return panel;
    }

    private void showTreePopup(MouseEvent e) {
        // 用 getClosestPathForLocation 而不是 getPathForLocation，
        // 这样即使鼠标不在文本上，只要在同一行就能弹出菜单
        int row = dbTree.getClosestRowForLocation(e.getX(), e.getY());
        if (row < 0) return;
        TreePath path = dbTree.getPathForRow(row);
        if (path == null) return;
        dbTree.setSelectionPath(path);

        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = node.getUserObject();
        if (!(userObj instanceof String)) return;
        String text = (String) userObj;

        // 根据节点类型动态构建菜单
        JPopupMenu popup = new JPopupMenu();

        if (text.startsWith(ICON_CONNECT_PREFIX)) {
            String connName = extractConnName(text);
            ConnectionInfo info = connections.get(connName);

            if (info != null && info.connected) {
                // 已连接状态：打开（默认状态）+ 关闭连接
                JMenuItem closeItem = new JMenuItem("关闭连接");
                closeItem.addActionListener(ev -> {
                    closeConnection(info);
                    saveConnectionsConfig();
                });
                popup.add(closeItem);
            } else if (info != null && !info.connected) {
                // 未连接状态：打开连接
                JMenuItem openItem = new JMenuItem("打开连接");
                openItem.addActionListener(ev -> {
                    openExistingConnection(info);
                });
                popup.add(openItem);
            }
            popup.addSeparator();
            JMenuItem editItem = new JMenuItem("编辑连接");
            editItem.addActionListener(ev -> showEditConnectionDialog(info));
            popup.add(editItem);
            JMenuItem deleteItem = new JMenuItem("删除连接");
            deleteItem.addActionListener(ev -> {
                int result = JOptionPane.showConfirmDialog(DatabasePanel.this,
                        "确定要删除连接 \"" + connName + "\" 吗？", "确认删除",
                        JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
                if (result == JOptionPane.YES_OPTION) {
                    removeConnection(info);
                }
            });
            popup.add(deleteItem);
            popup.addSeparator();
            JMenuItem refreshItem = new JMenuItem("刷新");
            refreshItem.addActionListener(ev -> refreshTree());
            popup.add(refreshItem);
        } else if (text.startsWith(ICON_DB_PREFIX)) {
            // 数据库节点：新建表 + 编辑数据库 + 删除数据库 + 刷新
            JMenuItem newTableItem = new JMenuItem("新建表");
            newTableItem.addActionListener(ev -> showCreateTableDialog(node));
            popup.add(newTableItem);
            popup.addSeparator();
            JMenuItem editDbItem = new JMenuItem("编辑数据库");
            editDbItem.addActionListener(ev -> {
                ConnectionInfo info = findConnectionForNode(node);
                if (info != null) {
                    editDatabase(info, node);
                }
            });
            popup.add(editDbItem);
            JMenuItem dropDbItem = new JMenuItem("删除数据库");
            dropDbItem.addActionListener(ev -> dropDatabase(node));
            popup.add(dropDbItem);
            popup.addSeparator();
            JMenuItem refreshItem = new JMenuItem("刷新");
            refreshItem.addActionListener(ev -> refreshTree());
            popup.add(refreshItem);
        } else if (text.startsWith(ICON_TABLE_PREFIX)) {
            // 表节点：打开表 + 编辑表 + 删除表 + 刷新
            JMenuItem openItem = new JMenuItem("打开表");
            openItem.addActionListener(ev -> {
                ConnectionInfo info = findConnectionForNode(node);
                if (info != null && info.conn != null) {
                    switchToConnection(info);
                    String tableName = text.substring(ICON_TABLE_PREFIX.length());
                    DefaultMutableTreeNode dbNode = (DefaultMutableTreeNode) node.getParent();
                    String dbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());
                    switchDatabase(info, dbName);
                    openTableDataTab(info, dbName, tableName);
                }
            });
            popup.add(openItem);
            popup.addSeparator();
            JMenuItem editTableItem = new JMenuItem("编辑表");
            editTableItem.addActionListener(ev -> {
                ConnectionInfo info = findConnectionForNode(node);
                if (info != null && info.conn != null) {
                    String tableName = text.substring(ICON_TABLE_PREFIX.length());
                    DefaultMutableTreeNode dbNode = (DefaultMutableTreeNode) node.getParent();
                    String dbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());
                    editTable(info, dbName, tableName, node);
                }
            });
            popup.add(editTableItem);
            JMenuItem dropItem = new JMenuItem("删除表");
            dropItem.addActionListener(ev -> dropTable(node));
            popup.add(dropItem);
            popup.addSeparator();
            JMenuItem refreshItem = new JMenuItem("刷新");
            refreshItem.addActionListener(ev -> refreshTree());
            popup.add(refreshItem);
        }

        popup.show(dbTree, e.getX(), e.getY());
    }

    private void handleTreeDoubleClick(MouseEvent e) {
        int row = dbTree.getClosestRowForLocation(e.getX(), e.getY());
        if (row < 0) return;
        TreePath path = dbTree.getPathForRow(row);
        if (path == null) return;
        DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
        Object userObj = node.getUserObject();
        if (userObj instanceof String) {
            String text = (String) userObj;
            if (text.startsWith(ICON_CONNECT_PREFIX)) {
                // 双击连接名 → 如果未连接则先连接再展开，已连接则切换展开/折叠
                String connName = extractConnName(text);
                ConnectionInfo info = connections.get(connName);
                if (info != null && !info.connected) {
                    // 未连接：先连接（内部会自动展开）
                    openExistingConnection(info);
                } else if (info != null && info.connected) {
                    // 已连接：切换展开/折叠
                    if (dbTree.isExpanded(path)) {
                        dbTree.collapsePath(path);
                    } else {
                        dbTree.expandPath(path);
                    }
                }
            } else if (text.startsWith(ICON_DB_PREFIX)) {
                // 双击数据库节点 → 切换展开/折叠（展开时自动加载表）
                if (dbTree.isExpanded(path)) {
                    dbTree.collapsePath(path);
                } else {
                    dbTree.expandPath(path);
                }
            } else if (text.startsWith(ICON_TABLE_PREFIX)) {
                // 双击表名 → 打开表数据标签页
                ConnectionInfo info = findConnectionForNode(node);
                if (info != null && info.conn != null) {
                    switchToConnection(info);
                    String tableName = text.substring(ICON_TABLE_PREFIX.length());
                    DefaultMutableTreeNode dbNode = (DefaultMutableTreeNode) node.getParent();
                    String dbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());
                    switchDatabase(info, dbName);
                    openTableDataTab(info, dbName, tableName);
                }
            }
        }
    }

    // ==================== 右侧面板 ====================

    private JPanel buildRightPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 4));

        // 顶部：工具栏 + 状态栏
        JPanel topBar = new JPanel(new BorderLayout(0, 0));
        topBar.setBorder(new EmptyBorder(2, 6, 2, 6));

        // 工具栏按钮
        JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton btnNewQuery = new JButton("+ 新建查询");
        btnNewQuery.setFont(FONT_BOLD);
        btnNewQuery.setFocusPainted(false);
        btnNewQuery.addActionListener(e -> openNewQueryTab());
        toolbar.add(btnNewQuery);
        topBar.add(toolbar, BorderLayout.WEST);

        statusLabel = new JLabel("未连接");
        statusLabel.setFont(FONT);
        topBar.add(statusLabel, BorderLayout.EAST);
        panel.add(topBar, BorderLayout.NORTH);

        // 中间：内容标签页（默认无标签页）
        contentTabs = new JTabbedPane(JTabbedPane.TOP);
        contentTabs.setTabLayoutPolicy(JTabbedPane.SCROLL_TAB_LAYOUT);
        panel.add(contentTabs, BorderLayout.CENTER);

        // 底部：日志
        logPane = NetUtil.createLogPane();
        JScrollPane logScroll = new JScrollPane(logPane);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "日志", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));
        logScroll.setPreferredSize(new Dimension(400, 100));
        logScroll.setMinimumSize(new Dimension(200, 60));
        panel.add(logScroll, BorderLayout.SOUTH);

        return panel;
    }

    /**
     * 打开一个新的 SQL 查询标签页
     */
    private void openNewQueryTab() {
        String title = "查询 " + (queryTabCounter++);
        QueryTabPanel tab = new QueryTabPanel(title);
        addClosableTab(title, tab);
    }

    /**
     * 打开或切换到指定表的数据库视图标签页
     */
    private void openTableDataTab(ConnectionInfo info, String dbName, String tableName) {
        // 检查是否已经打开了该表的标签页（用连接名+数据库+表名作为唯一标识）
        String tabKey = info.name + "|" + dbName + "|" + tableName;
        for (int i = 0; i < contentTabs.getTabCount(); i++) {
            Component comp = contentTabs.getComponentAt(i);
            if (comp instanceof TableDataTabPanel) {
                TableDataTabPanel tp = (TableDataTabPanel) comp;
                String existingKey = tp.connectionInfo.name + "|" + tp.dbName + "|" + tp.tableName;
                if (tabKey.equals(existingKey)) {
                    contentTabs.setSelectedIndex(i);
                    return;
                }
            }
        }
        // 创建新的表数据标签页
        // 标题格式：表名 @数据库名 [连接名]
        String tabTitle = tableName + " @" + dbName + " [" + info.name + "]";
        TableDataTabPanel tab = new TableDataTabPanel(info, dbName, tableName);
        addClosableTab(tabTitle, tab);
        tab.loadPage(1);
    }

    /**
     * 添加一个带关闭按钮的标签页
     */
    private void addClosableTab(String title, JComponent component) {
        contentTabs.addTab(title, component);
        int index = contentTabs.getTabCount() - 1;
        contentTabs.setTabComponentAt(index, createTabHeader(title, component));
        contentTabs.setSelectedIndex(index);
    }

    /**
     * 创建带关闭按钮的标签头部
     */
    private JPanel createTabHeader(String title, JComponent tabComponent) {
        JPanel header = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        header.setOpaque(false);

        JLabel titleLabel = new JLabel(title);
        titleLabel.setFont(FONT);
        header.add(titleLabel);

        JButton closeBtn = new JButton("×");
        closeBtn.setFont(new Font("Arial", Font.BOLD, 14));
        closeBtn.setPreferredSize(new Dimension(18, 18));
        closeBtn.setMargin(new java.awt.Insets(0, 0, 0, 0));
        closeBtn.setContentAreaFilled(false);
        closeBtn.setBorderPainted(false);
        closeBtn.setFocusPainted(false);
        closeBtn.setToolTipText("关闭");
        closeBtn.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                closeBtn.setForeground(Color.RED);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                closeBtn.setForeground(Color.GRAY);
            }
        });
        closeBtn.setForeground(Color.GRAY);
        closeBtn.addActionListener(e -> {
            int idx = -1;
            for (int i = 0; i < contentTabs.getTabCount(); i++) {
                if (contentTabs.getComponentAt(i) == tabComponent) {
                    idx = i;
                    break;
                }
            }
            if (idx >= 0) {
                contentTabs.remove(idx);
            }
        });
        header.add(closeBtn);

        return header;
    }

    // ==================== 连接 / 断开 ====================

    private void doConnect(String connName, String type, String host, String port,
                           String user, String pass, String dbName) {
        if ("SQLite".equals(type)) {
            if (dbName.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入 SQLite 数据库文件路径", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        } else {
            if (host.isEmpty() || port.isEmpty() || user.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请填写主机、端口、用户名", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }
        }

        // 检查连接名是否重复
        if (connections.containsKey(connName)) {
            JOptionPane.showMessageDialog(this, "连接名 \"" + connName + "\" 已存在", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        statusLabel.setText("连接中...");
        NetUtil.logSys(logPane, "正在连接 " + connName + " (" + type + " " + host + ":" + port + ") ...");

        final ConnectionInfo info = new ConnectionInfo(connName, type, host, port, user, pass, dbName);

        new Thread(() -> {
            try {
                String url;
                switch (type) {
                    case "MySQL":
                        url = "jdbc:mysql://" + host + ":" + port + "/" + dbName
                                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
                        info.conn = DriverManager.getConnection(url, user, pass);
                        break;
                    case "PostgreSQL":
                        url = "jdbc:postgresql://" + host + ":" + port + "/" + dbName;
                        info.conn = DriverManager.getConnection(url, user, pass);
                        break;
                    case "SQLite":
                        url = "jdbc:sqlite:" + dbName;
                        info.conn = DriverManager.getConnection(url);
                        break;
                }

                SwingUtilities.invokeLater(() -> {
                    // 保存连接
                    connections.put(connName, info);
                    info.connected = true;

                    // 创建树节点，格式：@CONN connName|host:port（渲染时显示为 connName (host:port)）
                    String addr = "SQLite".equals(type) ? dbName : (host + ":" + port);
                    DefaultMutableTreeNode connNode = new DefaultMutableTreeNode(ICON_CONNECT_PREFIX + connName + "|" + addr);
                    connNode.add(new DefaultMutableTreeNode("加载中..."));
                    info.treeNode = connNode;
                    rootNode.add(connNode);
                    treeModel.reload();

                    // 展开并选中新连接
                    TreePath connPath = new TreePath(connNode.getPath());
                    dbTree.expandPath(new TreePath(rootNode));
                    dbTree.expandPath(connPath);
                    dbTree.setSelectionPath(connPath);

                    // 切换到这个连接
                    switchToConnection(info);
                    // 加载数据库列表
                    loadDatabasesForConn(info, connNode);

                    // 持久化连接配置
                    saveConnectionsConfig();
                });
            } catch (Exception ex) {
                final String errMsg = ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("连接失败");
                    NetUtil.logErr(logPane, "连接失败: " + errMsg);
                    JOptionPane.showMessageDialog(this, "连接失败:\n" + errMsg, "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "DBConnect").start();
    }

    /** 切换到指定连接 */
    private void switchToConnection(ConnectionInfo info) {
        activeConnInfo = info;
        String addr = "SQLite".equals(info.type) ? info.dbName : (info.host + ":" + info.port);
        statusLabel.setText("已连接: " + info.name + "  [" + info.type + " " + addr + "]");
    }

    /** 关闭连接（保留配置，不删除） */
    private void closeConnection(ConnectionInfo info) {
        try {
            if (info.conn != null && !info.conn.isClosed()) {
                info.conn.close();
            }
        } catch (SQLException ignored) {}
        info.conn = null;
        info.connected = false;
        // 清空子节点，不添加占位节点（避免展开角标）
        if (info.treeNode != null) {
            info.treeNode.removeAllChildren();
            treeModel.reload(info.treeNode);
        }
        if (activeConnInfo == info) {
            activeConnInfo = null;
            statusLabel.setText("未连接");
            closeTabsForConnection(info);
        }
        NetUtil.logSys(logPane, "已关闭连接: " + info.name);
    }

    /** 删除连接（从配置和树中彻底移除） */
    private void removeConnection(ConnectionInfo info) {
        try {
            if (info.conn != null && !info.conn.isClosed()) {
                info.conn.close();
            }
        } catch (SQLException ignored) {}
        info.conn = null;
        // 从树中移除
        if (info.treeNode != null) {
            rootNode.remove(info.treeNode);
            treeModel.reload();
        }
        connections.remove(info.name);
        if (activeConnInfo == info) {
            activeConnInfo = null;
            statusLabel.setText("未连接");
            closeTabsForConnection(info);
        }
        NetUtil.logSys(logPane, "已删除连接: " + info.name);
        saveConnectionsConfig();
    }

    /** 打开已有配置的连接 */
    private void openExistingConnection(ConnectionInfo info) {
        statusLabel.setText("连接中...");
        NetUtil.logSys(logPane, "正在打开连接 " + info.name + " (" + info.type + " " + info.host + ":" + info.port + ") ...");

        new Thread(() -> {
            try {
                String url;
                switch (info.type) {
                    case "MySQL":
                        url = "jdbc:mysql://" + info.host + ":" + info.port + "/" + info.dbName
                                + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia/Shanghai";
                        info.conn = DriverManager.getConnection(url, info.user, info.pass);
                        break;
                    case "PostgreSQL":
                        url = "jdbc:postgresql://" + info.host + ":" + info.port + "/" + info.dbName;
                        info.conn = DriverManager.getConnection(url, info.user, info.pass);
                        break;
                    case "SQLite":
                        url = "jdbc:sqlite:" + info.dbName;
                        info.conn = DriverManager.getConnection(url);
                        break;
                }

                SwingUtilities.invokeLater(() -> {
                    info.connected = true;
                    // 重新加载子节点
                    if (info.treeNode != null) {
                        info.treeNode.removeAllChildren();
                        info.treeNode.add(new DefaultMutableTreeNode("加载中..."));
                        treeModel.reload(info.treeNode);
                        dbTree.expandPath(new TreePath(info.treeNode.getPath()));
                        loadDatabasesForConn(info, info.treeNode);
                    }
                    switchToConnection(info);
                    NetUtil.logSys(logPane, "已连接: " + info.name);
                });
            } catch (Exception ex) {
                final String errMsg = ex.getMessage();
                SwingUtilities.invokeLater(() -> {
                    statusLabel.setText("连接失败");
                    NetUtil.logErr(logPane, "打开连接失败: " + errMsg);
                    JOptionPane.showMessageDialog(DatabasePanel.this, "打开连接失败:\n" + errMsg, "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "DBOpen").start();
    }

    /** 断开指定连接（兼容旧代码，实际调用 closeConnection） */
    private void disconnectConnection(ConnectionInfo info) {
        closeConnection(info);
    }

    /** 关闭属于指定连接的所有标签页 */
    private void closeTabsForConnection(ConnectionInfo info) {
        for (int i = contentTabs.getTabCount() - 1; i >= 0; i--) {
            Component comp = contentTabs.getComponentAt(i);
            if (comp instanceof TableDataTabPanel) {
                TableDataTabPanel tp = (TableDataTabPanel) comp;
                if (tp.connectionInfo == info) {
                    contentTabs.remove(i);
                }
            }
        }
    }

    private void doDisconnect() {
        // 断开右键选中的连接，或当前激活的连接
        TreePath path = dbTree.getSelectionPath();
        ConnectionInfo target = activeConnInfo;
        if (path != null) {
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) path.getLastPathComponent();
            // 向上找到连接节点
            while (node != null && node != rootNode) {
                Object obj = node.getUserObject();
                if (obj instanceof String && ((String) obj).startsWith(ICON_CONNECT_PREFIX)) {
                    String name = extractConnName((String) obj);
                    target = connections.get(name);
                    break;
                }
                node = (DefaultMutableTreeNode) node.getParent();
            }
        }
        if (target != null) {
            disconnectConnection(target);
        }
    }

    // ==================== 加载数据库/表到树 ====================

    private void loadDatabasesForConn(ConnectionInfo info, DefaultMutableTreeNode connNode) {
        new Thread(() -> {
            try {
                List<String> databases = new ArrayList<>();
                DatabaseMetaData meta = info.conn.getMetaData();

                if ("MySQL".equals(info.type) || "PostgreSQL".equals(info.type)) {
                    ResultSet rs = meta.getCatalogs();
                    while (rs.next()) {
                        databases.add(rs.getString(1));
                    }
                    rs.close();
                } else if ("SQLite".equals(info.type)) {
                    databases.add("main");
                }

                SwingUtilities.invokeLater(() -> {
                    connNode.removeAllChildren();
                    for (String db : databases) {
                        DefaultMutableTreeNode dbNode = new DefaultMutableTreeNode(ICON_DB_PREFIX + db);
                        dbNode.add(new DefaultMutableTreeNode("加载中..."));
                        connNode.add(dbNode);
                    }
                    treeModel.reload(connNode);
                    // 展开第一个数据库
                    if (connNode.getChildCount() > 0) {
                        DefaultMutableTreeNode firstDb = (DefaultMutableTreeNode) connNode.getChildAt(0);
                        dbTree.expandPath(new TreePath(firstDb.getPath()));
                        loadTablesForDbNode(info, firstDb);
                    }
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    connNode.removeAllChildren();
                    connNode.add(new DefaultMutableTreeNode("加载失败"));
                    treeModel.reload(connNode);
                    NetUtil.logErr(logPane, "加载数据库列表失败: " + ex.getMessage());
                });
            }
        }, "LoadDB").start();
    }

    /** 从连接节点文本中提取连接名（格式：@CONN name|addr） */
    private static String extractConnName(String text) {
        String content = text.substring(ICON_CONNECT_PREFIX.length());
        int pipe = content.indexOf('|');
        return pipe > 0 ? content.substring(0, pipe) : content;
    }

    /** 从树节点向上查找所属的 ConnectionInfo */
    private ConnectionInfo findConnectionForNode(DefaultMutableTreeNode node) {
        DefaultMutableTreeNode current = node;
        while (current != null && current != rootNode) {
            Object obj = current.getUserObject();
            if (obj instanceof String && ((String) obj).startsWith(ICON_CONNECT_PREFIX)) {
                String name = extractConnName((String) obj);
                return connections.get(name);
            }
            current = (DefaultMutableTreeNode) current.getParent();
        }
        return null;
    }

    private void loadTablesForDbNode(ConnectionInfo info, DefaultMutableTreeNode dbNode) {
        String dbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());
        new Thread(() -> {
            try {
                // 切换到目标数据库
                if ("MySQL".equals(info.type)) {
                    info.conn.setCatalog(dbName);
                } else if ("PostgreSQL".equals(info.type)) {
                    try (Statement stmt = info.conn.createStatement()) {
                        stmt.execute("SET search_path TO " + dbName);
                    }
                }

                DatabaseMetaData meta = info.conn.getMetaData();
                List<String> tables = new ArrayList<>();
                ResultSet rs = meta.getTables(dbName, null, "%", new String[]{"TABLE", "VIEW"});
                while (rs.next()) {
                    String tableName = rs.getString("TABLE_NAME");
                    String tableType = rs.getString("TABLE_TYPE");
                    tables.add((tableType.equals("VIEW") ? "(视图) " : "") + tableName);
                }
                rs.close();

                SwingUtilities.invokeLater(() -> {
                    dbNode.removeAllChildren();
                    for (String t : tables) {
                        boolean isView = t.startsWith("(视图) ");
                        String display = isView ? (ICON_TABLE_PREFIX + t.substring(5)) : (ICON_TABLE_PREFIX + t);
                        dbNode.add(new DefaultMutableTreeNode(display));
                    }
                    treeModel.reload(dbNode);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    dbNode.removeAllChildren();
                    dbNode.add(new DefaultMutableTreeNode("加载失败"));
                    treeModel.reload(dbNode);
                    NetUtil.logErr(logPane, "加载表失败: " + ex.getMessage());
                });
            }
        }, "LoadTables").start();
    }

    private void refreshTree() {
        if (activeConnInfo != null) {
            if (activeConnInfo.treeNode != null) {
                activeConnInfo.treeNode.removeAllChildren();
                activeConnInfo.treeNode.add(new DefaultMutableTreeNode("加载中..."));
                treeModel.reload(activeConnInfo.treeNode);
                dbTree.expandPath(new TreePath(activeConnInfo.treeNode.getPath()));
                loadDatabasesForConn(activeConnInfo, activeConnInfo.treeNode);
            }
            NetUtil.logSys(logPane, "已刷新");
        }
    }

    // ==================== 切换数据库 ====================

    private void switchDatabase(ConnectionInfo info, String dbName) {
        try {
            if ("MySQL".equals(info.type)) {
                info.conn.setCatalog(dbName);
            } else if ("PostgreSQL".equals(info.type)) {
                try (Statement stmt = info.conn.createStatement()) {
                    stmt.execute("SET search_path TO " + dbName);
                }
            }
        } catch (Exception ex) {
            NetUtil.logErr(logPane, "切换数据库失败: " + ex.getMessage());
        }
    }

    // ==================== 新建表 / 删除表 ====================

    // 常用字段类型（按数据库类型区分）
    private static final String[] MYSQL_TYPES = {"INT", "BIGINT", "VARCHAR(255)", "TEXT", "LONGTEXT",
            "DECIMAL(10,2)", "FLOAT", "DOUBLE", "DATE", "DATETIME", "TIMESTAMP", "TINYINT(1)", "BOOLEAN", "BLOB"};
    private static final String[] PG_TYPES = {"INTEGER", "BIGINT", "VARCHAR(255)", "TEXT",
            "DECIMAL(10,2)", "FLOAT8", "DATE", "TIMESTAMP", "BOOLEAN", "BYTEA", "UUID", "JSON", "JSONB"};
    private static final String[] SQLITE_TYPES = {"INTEGER", "TEXT", "REAL", "BLOB", "NUMERIC"};

    private static class ColumnDef {
        String name = "";
        String type = "INT";
        boolean primaryKey;
        boolean notNull = true;
        boolean autoIncrement;
        String defaultValue = "";
    }

    private void showCreateTableDialog(DefaultMutableTreeNode dbNode) {
        ConnectionInfo info = findConnectionForNode(dbNode);
        if (info == null || info.conn == null) return;
        String dbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());

        // 根据数据库类型选择字段类型列表
        String[] typeOptions;
        switch (info.type) {
            case "PostgreSQL": typeOptions = PG_TYPES; break;
            case "SQLite": typeOptions = SQLITE_TYPES; break;
            default: typeOptions = MYSQL_TYPES; break;
        }

        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "新建表 - " + dbName, true);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // 表名输入
        JPanel namePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        namePanel.add(new JLabel("表名:"));
        JTextField tableNameField = new JTextField(20);
        tableNameField.setFont(FONT);
        namePanel.add(tableNameField);
        JLabel nameHint = new JLabel("（必填）");
        nameHint.setForeground(new Color(0x9E9E9E));
        namePanel.add(nameHint);
        panel.add(namePanel, BorderLayout.NORTH);

        // 字段表格模型
        String[] colNames = {"字段名", "类型", "主键", "非空", "自增", "默认值"};
        DefaultTableModel columnModel = new DefaultTableModel(colNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return true; }
            @Override
            public Class<?> getColumnClass(int column) {
                return column == 2 || column == 3 || column == 4 ? Boolean.class : String.class;
            }
        };

        JTable columnTable = new JTable(columnModel);
        columnTable.setFont(FONT);
        columnTable.getTableHeader().setFont(FONT_BOLD);
        columnTable.setRowHeight(24);
        columnTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);

        // 类型列用下拉框
        JComboBox<String> typeCombo = new JComboBox<>(typeOptions);
        typeCombo.setFont(FONT);
        typeCombo.setEditable(true);
        columnTable.getColumnModel().getColumn(1).setCellEditor(new DefaultCellEditor(typeCombo));
        columnTable.getColumnModel().getColumn(1).setPreferredWidth(140);
        columnTable.getColumnModel().getColumn(0).setPreferredWidth(120);
        columnTable.getColumnModel().getColumn(2).setPreferredWidth(50);
        columnTable.getColumnModel().getColumn(3).setPreferredWidth(50);
        columnTable.getColumnModel().getColumn(4).setPreferredWidth(50);
        columnTable.getColumnModel().getColumn(5).setPreferredWidth(100);

        JScrollPane tableScroll = new JScrollPane(columnTable);
        tableScroll.setPreferredSize(new Dimension(620, 200));
        tableScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "字段定义", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));
        panel.add(tableScroll, BorderLayout.CENTER);

        // 默认添加一行
        addColumnRow(columnModel, "id", "INT", true, true, true, "");

        // 底部按钮栏
        JPanel bottomPanel = new JPanel(new BorderLayout(0, 4));

        JPanel btnLeft = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        JButton addBtn = new JButton("+ 添加字段");
        addBtn.setFont(FONT);
        addBtn.addActionListener(e -> addColumnRow(columnModel, "", "INT", false, true, false, ""));
        btnLeft.add(addBtn);
        JButton delBtn = new JButton("- 删除选中");
        delBtn.setFont(FONT);
        delBtn.addActionListener(e -> {
            int row = columnTable.getSelectedRow();
            if (row >= 0 && columnModel.getRowCount() > 1) {
                columnModel.removeRow(row);
            }
        });
        btnLeft.add(delBtn);
        bottomPanel.add(btnLeft, BorderLayout.WEST);

        JPanel btnRight = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton execBtn = new JButton("创建表");
        execBtn.setFont(FONT_BOLD);
        execBtn.setBackground(new Color(0x2E7D32));
        execBtn.setForeground(Color.WHITE);
        execBtn.setFocusPainted(false);
        execBtn.addActionListener(e -> {
            String tableName = tableNameField.getText().trim();
            if (tableName.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "请输入表名", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (columnModel.getRowCount() == 0) {
                JOptionPane.showMessageDialog(dialog, "至少需要一个字段", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // 检查字段名
            for (int i = 0; i < columnModel.getRowCount(); i++) {
                if (columnModel.getValueAt(i, 0).toString().trim().isEmpty()) {
                    JOptionPane.showMessageDialog(dialog, "第 " + (i + 1) + " 行字段名不能为空", "提示", JOptionPane.WARNING_MESSAGE);
                    return;
                }
            }

            // 构建 SQL
            String sql = buildCreateTableSQL(info.type, tableName, columnModel);
            dialog.dispose();
            ConnectionInfo ci = info;
            new Thread(() -> {
                try {
                    switchDatabase(ci, dbName);
                    try (Statement stmt = ci.conn.createStatement()) {
                        stmt.executeUpdate(sql);
                    }
                    SwingUtilities.invokeLater(() -> {
                        NetUtil.logSys(logPane, "表 " + tableName + " 创建成功");
                        loadTablesForDbNode(ci, dbNode);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        NetUtil.logErr(logPane, "创建表失败: " + ex.getMessage());
                        JOptionPane.showMessageDialog(this, "创建表失败:\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        });
        btnRight.add(execBtn);
        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(FONT);
        cancelBtn.addActionListener(e -> dialog.dispose());
        btnRight.add(cancelBtn);
        bottomPanel.add(btnRight, BorderLayout.EAST);

        panel.add(bottomPanel, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.setSize(680, 380);
        dialog.setMinimumSize(new Dimension(600, 320));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    private void addColumnRow(DefaultTableModel model, String name, String type,
                               boolean pk, boolean notNull, boolean autoInc, String defaultVal) {
        model.addRow(new Object[]{name, type, pk, notNull, autoInc, defaultVal});
    }

    private String buildCreateTableSQL(String dbType, String tableName, DefaultTableModel model) {
        StringBuilder sb = new StringBuilder();
        sb.append("CREATE TABLE ").append(tableName).append(" (\n");
        List<String> pkCols = new ArrayList<>();
        for (int i = 0; i < model.getRowCount(); i++) {
            String colName = model.getValueAt(i, 0).toString().trim();
            String colType = model.getValueAt(i, 1).toString().trim();
            boolean pk = Boolean.TRUE.equals(model.getValueAt(i, 2));
            boolean notNull = Boolean.TRUE.equals(model.getValueAt(i, 3));
            boolean autoInc = Boolean.TRUE.equals(model.getValueAt(i, 4));
            String defaultVal = model.getValueAt(i, 5) == null ? "" : model.getValueAt(i, 5).toString().trim();

            sb.append("    ").append(colName).append(" ").append(colType);

            if (notNull) sb.append(" NOT NULL");
            if (!defaultVal.isEmpty()) sb.append(" DEFAULT '").append(defaultVal).append("'");

            if (autoInc) {
                if ("MySQL".equals(dbType)) {
                    sb.append(" AUTO_INCREMENT");
                } else if ("PostgreSQL".equals(dbType)) {
                    // PostgreSQL 用 SERIAL 类型代替，这里简单处理
                    sb.append(" AUTO_INCREMENT");
                } else if ("SQLite".equals(dbType)) {
                    sb.append(" AUTOINCREMENT");
                }
            }

            if (pk) pkCols.add(colName);
            if (i < model.getRowCount() - 1) sb.append(",");
            sb.append("\n");
        }
        if (!pkCols.isEmpty()) {
            sb.append("    ,PRIMARY KEY (").append(String.join(", ", pkCols)).append(")\n");
        }
        sb.append(")");
        return sb.toString();
    }

    private void dropTable(DefaultMutableTreeNode tableNode) {
        ConnectionInfo info = findConnectionForNode(tableNode);
        if (info == null || info.conn == null) return;
        String tableName = ((String) tableNode.getUserObject()).substring(ICON_TABLE_PREFIX.length());
        DefaultMutableTreeNode dbNode = (DefaultMutableTreeNode) tableNode.getParent();
        String dbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());

        int result = JOptionPane.showConfirmDialog(this,
                "确定要删除表 \"" + tableName + "\" 吗？\n此操作不可撤销！",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;

        new Thread(() -> {
            try {
                switchDatabase(info, dbName);
                try (Statement stmt = info.conn.createStatement()) {
                    stmt.executeUpdate("DROP TABLE " + tableName);
                }
                SwingUtilities.invokeLater(() -> {
                    NetUtil.logSys(logPane, "已删除表: " + tableName);
                    loadTablesForDbNode(info, dbNode);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    NetUtil.logErr(logPane, "删除表失败: " + ex.getMessage());
                    JOptionPane.showMessageDialog(this, "删除表失败:\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    // ==================== 新建连接对话框 ====================

    private void showNewConnectionDialog() {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "新建连接", true);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        int y = 0;
        // 第一行：连接名 + 类型
        JTextField nameField = new JTextField();
        nameField.setFont(FONT);
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"MySQL", "PostgreSQL", "SQLite"});
        typeCombo.setFont(FONT);
        typeCombo.setPreferredSize(new Dimension(110, 26));

        GridBagConstraints gcNameLabel = gbc(0, y);
        gcNameLabel.weightx = 0;
        panel.add(new JLabel("连接名:"), gcNameLabel);
        GridBagConstraints gcName = gbc(1, y);
        gcName.weightx = 0.5;
        panel.add(nameField, gcName);

        GridBagConstraints gcTypeLabel = gbc(2, y);
        gcTypeLabel.weightx = 0;
        panel.add(new JLabel("类型:"), gcTypeLabel);
        GridBagConstraints gcType = gbc(3, y);
        gcType.weightx = 0.2;
        panel.add(typeCombo, gcType);
        y++;

        // 第二行：主机 + 端口
        JTextField hField = new JTextField("127.0.0.1");
        hField.setFont(FONT);
        JTextField pField = new JTextField("3306");
        pField.setFont(FONT);
        GridBagConstraints gcHostLabel = gbc(0, y);
        gcHostLabel.weightx = 0;
        panel.add(new JLabel("主机:"), gcHostLabel);
        GridBagConstraints gcHost = gbc(1, y);
        gcHost.weightx = 0.5;
        panel.add(hField, gcHost);
        GridBagConstraints gcPortLabel = gbc(2, y);
        gcPortLabel.weightx = 0;
        panel.add(new JLabel("端口:"), gcPortLabel);
        GridBagConstraints gcPort = gbc(3, y);
        gcPort.weightx = 0.2;
        panel.add(pField, gcPort);
        y++;

        // 第三行：用户 + 密码
        JTextField uField = new JTextField("root");
        uField.setFont(FONT);
        JPasswordField pwField = new JPasswordField();
        pwField.setFont(FONT);
        GridBagConstraints gcUserLabel = gbc(0, y);
        gcUserLabel.weightx = 0;
        panel.add(new JLabel("用户:"), gcUserLabel);
        GridBagConstraints gcUser = gbc(1, y);
        gcUser.weightx = 0.5;
        panel.add(uField, gcUser);
        GridBagConstraints gcPwdLabel = gbc(2, y);
        gcPwdLabel.weightx = 0;
        panel.add(new JLabel("密码:"), gcPwdLabel);
        GridBagConstraints gcPwd = gbc(3, y);
        gcPwd.weightx = 0.2;
        panel.add(pwField, gcPwd);
        y++;

        // 第四行：数据库
        JTextField dField = new JTextField();
        dField.setFont(FONT);
        GridBagConstraints gcDbLabel = gbc(0, y);
        gcDbLabel.weightx = 0;
        panel.add(new JLabel("数据库:"), gcDbLabel);
        GridBagConstraints gcDb = gbc(1, y, 3);
        gcDb.weightx = 1.0;
        panel.add(dField, gcDb);
        y++;

        typeCombo.addActionListener(e -> {
            String t = (String) typeCombo.getSelectedItem();
            if ("SQLite".equals(t)) {
                hField.setEnabled(false); pField.setEnabled(false);
                uField.setEnabled(false); pwField.setEnabled(false);
                hField.setText(""); pField.setText("");
                uField.setText(""); pwField.setText("");
            } else {
                hField.setEnabled(true); pField.setEnabled(true);
                uField.setEnabled(true); pwField.setEnabled(true);
                if ("MySQL".equals(t)) {
                    hField.setText("127.0.0.1");
                    pField.setText("3306"); uField.setText("root");
                } else if ("PostgreSQL".equals(t)) {
                    hField.setText("127.0.0.1");
                    pField.setText("5432"); uField.setText("postgres");
                }
            }
            // 自动生成连接名
            if (nameField.getText().isEmpty() && !"SQLite".equals(t)) {
                nameField.setText(t + "@" + hField.getText() + ":" + pField.getText());
            }
        });

        // 初始化连接名
        nameField.setText("MySQL@127.0.0.1:3306");

        // 按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton okBtn = new JButton("创建并连接");
        okBtn.setFont(FONT_BOLD);
        okBtn.addActionListener(e -> {
            String connName = nameField.getText().trim();
            String type = (String) typeCombo.getSelectedItem();
            String host = hField.getText().trim();
            String port = pField.getText().trim();
            String user = uField.getText().trim();
            String pass = new String(pwField.getPassword());
            String db = dField.getText().trim();

            if (connName.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "请输入连接名", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            dialog.dispose();
            doConnect(connName, type, host, port, user, pass, db);
        });
        btnPanel.add(okBtn);

        JButton cancelBtn = new JButton("取消");
        cancelBtn.addActionListener(e -> dialog.dispose());
        btnPanel.add(cancelBtn);

        GridBagConstraints gcBtn = gbc(0, y, 4);
        gcBtn.fill = GridBagConstraints.HORIZONTAL;
        gcBtn.weightx = 1.0;
        panel.add(btnPanel, gcBtn);

        dialog.setContentPane(panel);
        dialog.setSize(520, 320);
        dialog.setMinimumSize(new Dimension(480, 280));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    /** 编辑已有连接 */
    private void showEditConnectionDialog(ConnectionInfo info) {
        if (info == null) return;

        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this), "编辑连接 - " + info.name, true);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(16, 16, 16, 16));

        int y = 0;
        // 第一行：连接名（不可修改）+ 类型
        JLabel nameLabel = new JLabel(info.name);
        nameLabel.setFont(FONT_BOLD);
        JComboBox<String> typeCombo = new JComboBox<>(new String[]{"MySQL", "PostgreSQL", "SQLite"});
        typeCombo.setFont(FONT);
        typeCombo.setPreferredSize(new Dimension(110, 26));
        typeCombo.setSelectedItem(info.type);

        GridBagConstraints gcNameLabel = gbc(0, y);
        gcNameLabel.weightx = 0;
        panel.add(new JLabel("连接名:"), gcNameLabel);
        GridBagConstraints gcName = gbc(1, y);
        gcName.weightx = 0.5;
        panel.add(nameLabel, gcName);

        GridBagConstraints gcTypeLabel = gbc(2, y);
        gcTypeLabel.weightx = 0;
        panel.add(new JLabel("类型:"), gcTypeLabel);
        GridBagConstraints gcType = gbc(3, y);
        gcType.weightx = 0.2;
        panel.add(typeCombo, gcType);
        y++;

        // 第二行：主机 + 端口
        JTextField hField = new JTextField(info.host);
        hField.setFont(FONT);
        JTextField pField = new JTextField(info.port);
        pField.setFont(FONT);

        GridBagConstraints gcHostLabel = gbc(0, y);
        gcHostLabel.weightx = 0;
        panel.add(new JLabel("主机:"), gcHostLabel);
        GridBagConstraints gcHost = gbc(1, y);
        gcHost.weightx = 0.5;
        panel.add(hField, gcHost);
        GridBagConstraints gcPortLabel = gbc(2, y);
        gcPortLabel.weightx = 0;
        panel.add(new JLabel("端口:"), gcPortLabel);
        GridBagConstraints gcPort = gbc(3, y);
        gcPort.weightx = 0.2;
        panel.add(pField, gcPort);
        y++;

        // 第三行：用户 + 密码
        JTextField uField = new JTextField(info.user);
        uField.setFont(FONT);
        JPasswordField pwField = new JPasswordField(info.pass);
        pwField.setFont(FONT);
        GridBagConstraints gcUserLabel = gbc(0, y);
        gcUserLabel.weightx = 0;
        panel.add(new JLabel("用户:"), gcUserLabel);
        GridBagConstraints gcUser = gbc(1, y);
        gcUser.weightx = 0.5;
        panel.add(uField, gcUser);
        GridBagConstraints gcPwdLabel = gbc(2, y);
        gcPwdLabel.weightx = 0;
        panel.add(new JLabel("密码:"), gcPwdLabel);
        GridBagConstraints gcPwd = gbc(3, y);
        gcPwd.weightx = 0.2;
        panel.add(pwField, gcPwd);
        y++;

        // 第四行：数据库
        JTextField dField = new JTextField(info.dbName);
        dField.setFont(FONT);
        GridBagConstraints gcDbLabel = gbc(0, y);
        gcDbLabel.weightx = 0;
        panel.add(new JLabel("数据库:"), gcDbLabel);
        GridBagConstraints gcDb = gbc(1, y, 3);
        gcDb.weightx = 1.0;
        panel.add(dField, gcDb);
        y++;

        // 如果是已连接的，禁用编辑
        boolean isConnected = info.connected;
        if (isConnected) {
            typeCombo.setEnabled(false);
            hField.setEnabled(false);
            pField.setEnabled(false);
            uField.setEnabled(false);
            pwField.setEnabled(false);
            dField.setEnabled(false);
        } else {
            typeCombo.addActionListener(e -> {
                String t = (String) typeCombo.getSelectedItem();
                if ("SQLite".equals(t)) {
                    hField.setEnabled(false); pField.setEnabled(false);
                    uField.setEnabled(false); pwField.setEnabled(false);
                } else {
                    hField.setEnabled(true); pField.setEnabled(true);
                    uField.setEnabled(true); pwField.setEnabled(true);
                }
            });
            // 初始化禁用状态
            if ("SQLite".equals(info.type)) {
                hField.setEnabled(false); pField.setEnabled(false);
                uField.setEnabled(false); pwField.setEnabled(false);
            }
        }

        // 按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT));
        JButton saveBtn = new JButton(isConnected ? "重新连接" : "保存并连接");
        saveBtn.setFont(FONT_BOLD);
        saveBtn.addActionListener(e -> {
            String type = (String) typeCombo.getSelectedItem();
            String host = hField.getText().trim();
            String port = pField.getText().trim();
            String user = uField.getText().trim();
            String pass = new String(pwField.getPassword());
            String db = dField.getText().trim();

            if (info.connected) {
                // 已连接状态：先关闭再重新打开
                closeConnection(info);
            }
            dialog.dispose();

            // 更新连接信息
            info.type = type;
            info.host = host;
            info.port = port;
            info.user = user;
            info.pass = pass;
            info.dbName = db;

            // 更新树节点文本
            String addr = "SQLite".equals(type) ? db : (host + ":" + port);
            info.treeNode.setUserObject(ICON_CONNECT_PREFIX + info.name + "|" + addr);
            info.treeNode.removeAllChildren();
            info.treeNode.add(new DefaultMutableTreeNode("加载中..."));
            treeModel.reload(info.treeNode);

            saveConnectionsConfig();
            // 重新连接
            openExistingConnection(info);
        });
        btnPanel.add(saveBtn);

        JButton cancelBtn = new JButton("取消");
        cancelBtn.addActionListener(e -> dialog.dispose());
        btnPanel.add(cancelBtn);

        GridBagConstraints gcBtn = gbc(0, y, 4);
        gcBtn.fill = GridBagConstraints.HORIZONTAL;
        gcBtn.weightx = 1.0;
        panel.add(btnPanel, gcBtn);

        dialog.setContentPane(panel);
        dialog.setSize(520, 320);
        dialog.setMinimumSize(new Dimension(480, 280));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ==================== 编辑数据库 ====================

    /** 编辑数据库（重命名） */
    private void editDatabase(ConnectionInfo info, DefaultMutableTreeNode dbNode) {
        if (info == null || info.conn == null) return;
        String oldDbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());

        String newDbName = JOptionPane.showInputDialog(DatabasePanel.this,
                "修改数据库名:", oldDbName);
        if (newDbName == null || newDbName.trim().isEmpty() || newDbName.trim().equals(oldDbName)) return;
        newDbName = newDbName.trim();

        // 对于 MySQL/PostgreSQL，执行 RENAME DATABASE（或 ALTER DATABASE）
        String finalNewDbName = newDbName;
        new Thread(() -> {
            try {
                if ("MySQL".equals(info.type)) {
                    // MySQL 不支持直接 RENAME DATABASE，需要用 mysqldump + restore
                    // 简化方案：创建新库，复制表结构
                    try (Statement stmt = info.conn.createStatement()) {
                        stmt.executeUpdate("CREATE DATABASE " + finalNewDbName);
                    }
                    // 列出旧库中的所有表
                    info.conn.setCatalog(oldDbName);
                    List<String> tables = new ArrayList<>();
                    DatabaseMetaData meta = info.conn.getMetaData();
                    ResultSet rs = meta.getTables(oldDbName, null, "%", new String[]{"TABLE", "VIEW"});
                    while (rs.next()) tables.add(rs.getString("TABLE_NAME"));
                    rs.close();

                    // 逐个重命名表到新库
                    for (String table : tables) {
                        try (Statement stmt = info.conn.createStatement()) {
                            stmt.executeUpdate("RENAME TABLE " + oldDbName + "." + table + " TO " + finalNewDbName + "." + table);
                        }
                    }
                    // 删除旧库
                    try (Statement stmt = info.conn.createStatement()) {
                        stmt.executeUpdate("DROP DATABASE " + oldDbName);
                    }
                } else if ("PostgreSQL".equals(info.type)) {
                    try (Statement stmt = info.conn.createStatement()) {
                        stmt.executeUpdate("ALTER DATABASE " + oldDbName + " RENAME TO " + finalNewDbName);
                    }
                } else if ("SQLite".equals(info.type)) {
                    JOptionPane.showMessageDialog(DatabasePanel.this,
                            "SQLite 不支持重命名数据库", "提示", JOptionPane.INFORMATION_MESSAGE);
                    return;
                }

                SwingUtilities.invokeLater(() -> {
                    // 更新树节点
                    dbNode.setUserObject(ICON_DB_PREFIX + finalNewDbName);
                    treeModel.reload(dbNode);
                    // 重新加载数据库列表
                    DefaultMutableTreeNode connNode = (DefaultMutableTreeNode) dbNode.getParent();
                    loadDatabasesForConn(info, connNode);
                    NetUtil.logSys(logPane, "数据库已重命名: " + oldDbName + " → " + finalNewDbName);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    NetUtil.logErr(logPane, "重命名数据库失败: " + ex.getMessage());
                    JOptionPane.showMessageDialog(DatabasePanel.this,
                            "重命名数据库失败:\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "DBRename").start();
    }

    /** 删除数据库 */
    private void dropDatabase(DefaultMutableTreeNode dbNode) {
        ConnectionInfo info = findConnectionForNode(dbNode);
        if (info == null || info.conn == null) return;
        String dbName = ((String) dbNode.getUserObject()).substring(ICON_DB_PREFIX.length());

        int result = JOptionPane.showConfirmDialog(DatabasePanel.this,
                "确定要删除数据库 \"" + dbName + "\" 吗？\n此操作不可撤销！所有表和数据将被永久删除。",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (result != JOptionPane.YES_OPTION) return;

        new Thread(() -> {
            try {
                try (Statement stmt = info.conn.createStatement()) {
                    stmt.executeUpdate("DROP DATABASE " + dbName);
                }
                SwingUtilities.invokeLater(() -> {
                    NetUtil.logSys(logPane, "已删除数据库: " + dbName);
                    // 重新加载数据库列表
                    DefaultMutableTreeNode connNode = (DefaultMutableTreeNode) dbNode.getParent();
                    loadDatabasesForConn(info, connNode);
                });
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    NetUtil.logErr(logPane, "删除数据库失败: " + ex.getMessage());
                    JOptionPane.showMessageDialog(DatabasePanel.this,
                            "删除数据库失败:\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }).start();
    }

    // ==================== 编辑表 ====================

    /** 编辑表结构（查看/修改列信息） */
    private void editTable(ConnectionInfo info, String dbName, String tableName, DefaultMutableTreeNode tableNode) {
        if (info == null || info.conn == null) return;

        switchDatabase(info, dbName);

        new Thread(() -> {
            try {
                DatabaseMetaData meta = info.conn.getMetaData();
                // 获取列信息
                List<String[]> columns = new ArrayList<>();
                ResultSet rs = meta.getColumns(dbName, null, tableName, "%");
                while (rs.next()) {
                    columns.add(new String[]{
                            rs.getString("COLUMN_NAME"),
                            rs.getString("TYPE_NAME"),
                            String.valueOf(rs.getInt("COLUMN_SIZE")),
                            rs.getString("IS_NULLABLE"),
                            rs.getString("COLUMN_DEF") != null ? rs.getString("COLUMN_DEF") : ""
                    });
                }
                rs.close();

                // 获取主键
                Set<String> pkCols = new HashSet<>();
                ResultSet pkRs = meta.getPrimaryKeys(dbName, null, tableName);
                while (pkRs.next()) {
                    pkCols.add(pkRs.getString("COLUMN_NAME"));
                }
                pkRs.close();

                final List<String[]> finalColumns = columns;
                SwingUtilities.invokeLater(() -> showEditTableDialog(info, dbName, tableName, finalColumns, pkCols, tableNode));
            } catch (Exception ex) {
                SwingUtilities.invokeLater(() -> {
                    NetUtil.logErr(logPane, "获取表结构失败: " + ex.getMessage());
                    JOptionPane.showMessageDialog(DatabasePanel.this,
                            "获取表结构失败:\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                });
            }
        }, "TableInfo").start();
    }

    private void showEditTableDialog(ConnectionInfo info, String dbName, String tableName,
                                      List<String[]> columns, Set<String> pkCols,
                                      DefaultMutableTreeNode tableNode) {
        JDialog dialog = new JDialog((Frame) SwingUtilities.getWindowAncestor(this),
                "编辑表结构 - " + tableName, true);

        JPanel panel = new JPanel(new BorderLayout(0, 8));
        panel.setBorder(new EmptyBorder(12, 12, 12, 12));

        // 表信息
        JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 0));
        headerPanel.add(new JLabel("表名:"));
        JTextField tableNameField = new JTextField(tableName, 20);
        tableNameField.setFont(FONT);
        headerPanel.add(tableNameField);
        headerPanel.add(new JLabel("数据库: " + dbName + "  连接: " + info.name));
        panel.add(headerPanel, BorderLayout.NORTH);

        // 列信息表格
        String[] colNames = {"列名", "类型", "长度", "可为空", "默认值", "主键"};
        DefaultTableModel columnModel = new DefaultTableModel(colNames, 0) {
            @Override
            public boolean isCellEditable(int row, int column) { return column != 5; }
            @Override
            public Class<?> getColumnClass(int column) { return column == 3 || column == 5 ? Boolean.class : String.class; }
        };

        for (String[] col : columns) {
            columnModel.addRow(new Object[]{
                    col[0], col[1], col[2],
                    "YES".equalsIgnoreCase(col[3]),
                    col[4],
                    pkCols.contains(col[0])
            });
        }

        JTable columnTable = new JTable(columnModel);
        columnTable.setFont(FONT);
        columnTable.getTableHeader().setFont(FONT_BOLD);
        columnTable.setRowHeight(24);
        columnTable.putClientProperty("terminateEditOnFocusLost", Boolean.TRUE);
        columnTable.getColumnModel().getColumn(0).setPreferredWidth(130);
        columnTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        columnTable.getColumnModel().getColumn(2).setPreferredWidth(60);
        columnTable.getColumnModel().getColumn(3).setPreferredWidth(60);
        columnTable.getColumnModel().getColumn(4).setPreferredWidth(100);
        columnTable.getColumnModel().getColumn(5).setPreferredWidth(50);

        JScrollPane tableScroll = new JScrollPane(columnTable);
        tableScroll.setPreferredSize(new Dimension(650, 250));
        tableScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "列定义 (" + columns.size() + " 列)", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));
        panel.add(tableScroll, BorderLayout.CENTER);

        // 底部按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
        JButton saveBtn = new JButton("保存修改");
        saveBtn.setFont(FONT_BOLD);
        saveBtn.setBackground(new Color(0x1565C0));
        saveBtn.setForeground(Color.WHITE);
        saveBtn.setFocusPainted(false);
        saveBtn.addActionListener(e -> {
            String newTableName = tableNameField.getText().trim();
            if (newTableName.isEmpty()) {
                JOptionPane.showMessageDialog(dialog, "表名不能为空", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            dialog.dispose();
            // 构建 ALTER TABLE SQL
            new Thread(() -> {
                try {
                    switchDatabase(info, dbName);
                    try (Statement stmt = info.conn.createStatement()) {
                        // 重命名表
                        if (!newTableName.equals(tableName)) {
                            stmt.executeUpdate("ALTER TABLE " + tableName + " RENAME TO " + newTableName);
                        }

                        // 修改列（简化处理：删除后重建）
                        // 实际项目中应该逐列比较生成 ALTER COLUMN 语句
                        // 这里只做基本的列修改
                        for (int i = 0; i < columnModel.getRowCount(); i++) {
                            String colName = columnModel.getValueAt(i, 0).toString().trim();
                            String colType = columnModel.getValueAt(i, 1).toString().trim();
                            String colSize = columnModel.getValueAt(i, 2).toString().trim();
                            if (!colSize.isEmpty()) {
                                colType = colType + "(" + colSize + ")";
                            }
                            boolean nullable = Boolean.TRUE.equals(columnModel.getValueAt(i, 3));

                            // 尝试修改列
                            if (i < columns.size() && !colName.equals(columns.get(i)[0])) {
                                // 列名变了，重命名
                                stmt.executeUpdate("ALTER TABLE " + newTableName + " RENAME COLUMN " +
                                        columns.get(i)[0] + " TO " + colName);
                            }
                        }
                    }
                    SwingUtilities.invokeLater(() -> {
                        NetUtil.logSys(logPane, "表 " + tableName + " 结构已更新");
                        // 重新加载表列表
                        DefaultMutableTreeNode dbNode = (DefaultMutableTreeNode) tableNode.getParent();
                        loadTablesForDbNode(info, dbNode);
                    });
                } catch (Exception ex) {
                    SwingUtilities.invokeLater(() -> {
                        NetUtil.logErr(logPane, "修改表失败: " + ex.getMessage());
                        JOptionPane.showMessageDialog(DatabasePanel.this,
                                "修改表失败:\n" + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    });
                }
            }).start();
        });
        btnPanel.add(saveBtn);

        JButton cancelBtn = new JButton("取消");
        cancelBtn.setFont(FONT);
        cancelBtn.addActionListener(e -> dialog.dispose());
        btnPanel.add(cancelBtn);

        panel.add(btnPanel, BorderLayout.SOUTH);

        dialog.setContentPane(panel);
        dialog.setSize(700, 420);
        dialog.setMinimumSize(new Dimension(600, 350));
        dialog.setLocationRelativeTo(this);
        dialog.setVisible(true);
    }

    // ==================== 树渲染器 ====================

    private class DbTreeRenderer extends DefaultTreeCellRenderer {
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value,
                boolean sel, boolean expanded, boolean leaf, int row, boolean hasFocus) {
            super.getTreeCellRendererComponent(tree, value, sel, expanded, leaf, row, hasFocus);
            setFont(FONT_TREE);
            setBorder(new EmptyBorder(2, 2, 2, 2));
            DefaultMutableTreeNode node = (DefaultMutableTreeNode) value;
            Object userObj = node.getUserObject();
            if (userObj instanceof String) {
                String text = (String) userObj;
                // 去掉旧的前缀（如果有的话），提取纯文本
                String pureText = text;
                if (text.startsWith(ICON_CONNECT_PREFIX)) {
                    // 根据连接状态选择图标
                    String connName = extractConnName(text);
                    ConnectionInfo info = connections.get(connName);
                    setIcon((info != null && info.connected) ? ICON_CONNECT : ICON_CONNECT_DISABLED);
                    String content = text.substring(ICON_CONNECT_PREFIX.length());
                    int pipe = content.indexOf('|');
                    if (pipe > 0) {
                        pureText = content.substring(0, pipe) + " (" + content.substring(pipe + 1) + ")";
                    } else {
                        pureText = content;
                    }
                } else if (text.startsWith(ICON_DB_PREFIX)) {
                    setIcon(ICON_DB);
                    pureText = text.substring(ICON_DB_PREFIX.length());
                } else if (text.startsWith(ICON_TABLE_PREFIX)) {
                    setIcon(ICON_TABLE);
                    pureText = text.substring(ICON_TABLE_PREFIX.length());
                } else if ("加载中...".equals(text)) {
                    setIcon(ICON_LOADING);
                } else {
                    setIcon(null);
                }
                setText(pureText);
            }
            return this;
        }
    }

    // ==================== 内部类：SQL 查询标签页 ====================

    // ==================== SQL 关键字/函数 补全数据 ====================

    private static final String[] SQL_KEYWORDS = {
            // ===== DML 基础关键字 =====
            "select", "from", "where", "and", "or", "not", "in", "exists", "between", "like", "is", "null",
            "insert", "into", "values", "update", "set", "delete",
            // ===== DDL 关键字 =====
            "create", "table", "alter", "drop", "index", "view", "trigger", "procedure", "function",
            "database", "schema", "if", "temporary", "temp",
            "rename", "truncate", "replace", "column", "columns", "add", "modify", "change",
            "after", "before", "first", "last", "comment", "engine", "charset", "collate",
            "tablespace", "partition", "partitions",
            // ===== JOIN =====
            "join", "left", "right", "inner", "outer", "full", "cross", "on", "using",
            "natural", "straight_join",
            // ===== 查询子句 =====
            "group", "by", "order", "asc", "desc", "having", "limit", "offset",
            "union", "all", "intersect", "except", "minus",
            "distinct", "as", "case", "when", "then", "else", "end",
            "into", "ignore", "delayed", "low_priority", "high_priority",
            "force", "index", "key", "use", "straight_join",
            // ===== 约束 =====
            "primary", "key", "foreign", "references", "constraint", "unique", "check", "default",
            "cascade", "restrict", "no", "action", "deferrable", "initially",
            "auto_increment", "autoincrement", "serial", "identity", "generated", "always",
            // ===== 数据类型 - 整数 =====
            "int", "integer", "bigint", "smallint", "tinyint", "mediumint",
            "int2", "int4", "int8", "serial2", "serial4", "serial8",
            // ===== 数据类型 - 小数 =====
            "decimal", "numeric", "float", "double", "real", "money",
            "float4", "float8", "dec", "fixed",
            // ===== 数据类型 - 字符串 =====
            "varchar", "char", "text", "longtext", "mediumtext", "tinytext",
            "nvarchar", "nchar", "ntext", "clob", "character", "varying",
            // ===== 数据类型 - 日期时间 =====
            "date", "datetime", "timestamp", "time", "year",
            "interval", "timestamptz", "timetz", "smalldatetime", "datetime2",
            // ===== 数据类型 - 布尔与位 =====
            "boolean", "bool", "bit",
            // ===== 数据类型 - 二进制 =====
            "blob", "longblob", "mediumblob", "tinyblob",
            "binary", "varbinary", "image", "bytea", "raw", "long",
            // ===== 数据类型 - JSON/XML/特殊 =====
            "json", "jsonb", "uuid", "enum", "set", "xml",
            "array", "vector", "point", "line", "polygon", "box", "circle",
            "path", "geometry", "geography", "inet", "cidr", "macaddr",
            // ===== 事务 =====
            "begin", "commit", "rollback", "savepoint", "transaction",
            "start", "release", "work", "read", "write", "only",
            "isolation", "level", "repeatable", "uncommitted", "serializable",
            "snapshot", "lock", "unlock", "tables", "share", "exclusive",
            // ===== 权限 =====
            "grant", "revoke", "to", "with", "option", "admin",
            "privileges", "on", "role", "roles", "user", "users",
            // ===== 查询分析 =====
            "explain", "analyze", "describe", "desc", "show", "use",
            "explain", "verbose", "buffers", "costs", "timing", "format",
            // ===== 聚合函数 =====
            "count", "sum", "avg", "min", "max",
            "stddev", "variance", "stddev_pop", "stddev_samp",
            "var_pop", "var_samp", "bit_and", "bit_or", "bit_xor",
            "group_concat", "string_agg", "array_agg", "listagg",
            // ===== 字符串函数 =====
            "concat", "concat_ws", "substring", "substr", "trim", "ltrim", "rtrim",
            "upper", "lower", "length", "char_length", "character_length",
            "replace", "reverse", "repeat", "lpad", "rpad", "instr", "locate",
            "position", "left", "right", "mid", "elt", "field",
            "ascii", "char", "hex", "unhex", "bin", "oct",
            "space", "strcmp", "format", "initcap", "translate",
            "regexp_replace", "regexp_substr", "regexp_instr", "regexp_like",
            "split_part", "left", "right", "stuff",
            // ===== 数值函数 =====
            "abs", "ceil", "ceiling", "floor", "round", "truncate", "mod",
            "power", "pow", "sqrt", "exp", "log", "log10", "log2", "ln",
            "sign", "rand", "random", "pi", "greatest", "least",
            "div", "percent_rank", "cume_dist", "ntile",
            // ===== 日期时间函数 =====
            "now", "curdate", "curtime", "sysdate", "current_date", "current_time", "current_timestamp",
            "date_format", "datediff", "date_add", "date_sub",
            "timestampdiff", "timestampadd", "day", "month", "year",
            "hour", "minute", "second", "microsecond",
            "dayofweek", "dayofmonth", "dayofyear", "week", "quarter",
            "last_day", "monthname", "dayname",
            "extract", "date_trunc", "date_part", "age",
            "makedate", "maketime", "str_to_date", "to_date", "to_char", "to_timestamp",
            "from_days", "to_days", "from_unixtime", "unix_timestamp",
            "utc_date", "utc_time", "utc_timestamp", "getdate", "getutcdate",
            "timezone", "at", "time", "zone",
            // ===== 类型转换 =====
            "cast", "convert", "convert_to", "try_cast", "try_convert",
            // ===== 条件与 NULL 处理 =====
            "coalesce", "nullif", "ifnull", "isnull", "nvl", "nvl2",
            "if", "iff", "iif", "decode", "greatest", "least",
            // ===== 窗口函数 =====
            "row_number", "rank", "dense_rank", "over", "partition",
            "lead", "lag", "first_value", "last_value", "nth_value",
            "range", "rows", "preceding", "following", "unbounded", "current", "row",
            // ===== 布尔/位操作 =====
            "true", "false", "unknown",
            // ===== 高级查询 =====
            "top", "fetch", "next", "rows", "only", "percent", "ties",
            "returning", "conflict", "do", "nothing", "update",
            "recursive", "lateral", "window", "materialized",
            "with", "as", "cte", "connect", "prior", "start",
            "pivot", "unpivot", "tablesample",
            // ===== 全文搜索 =====
            "match", "against", "contains", "freetext", "freetexttable",
            "containstable", "fulltext", "spatial",
            // ===== MERGE / UPSERT =====
            "merge", "matched", "using", "source", "target",
            "on", "duplicate", "upsert",
            // ===== 序列 =====
            "sequence", "nextval", "currval", "setval", "increment",
            "minvalue", "maxvalue", "start", "cache", "cycle", "no",
            // ===== 事件/定时任务 =====
            "event", "schedule", "every", "starts", "ends", "preserve",
            "on", "completion", "enable", "disable",
            // ===== 游标 =====
            "cursor", "declare", "open", "close", "fetch", "deallocate",
            "refcursor", "return", "returns", "language",
            // ===== 存储过程/函数 =====
            "in", "out", "inout", "execute", "exec", "call",
            "deterministic", "modifies", "reads", "sql", "data",
            "security", "definer", "invoker",
            "declare", "handler", "continue", "exit", "condition",
            "signal", "resignal", "sqlexception", "sqlwarning",
            "get", "diagnostics", "stacked",
            // ===== 系统函数/变量 =====
            "version", "database", "user", "connection_id", "last_insert_id",
            "row_count", "found_rows", "schema",
            "@@global", "@@session", "@@local",
            // ===== 表维护 =====
            "optimize", "analyze", "check", "checksum", "repair",
            "backup", "restore", "dump", "load", "infile", "outfile",
            "flush", "reset", "kill", "shutdown",
            // ===== 复制 =====
            "master", "slave", "replication", "log", "position",
            "global", "session", "local", "variables", "status",
            "binlog", "relaylog", "server",
            // ===== 杂项 =====
            "ilike", "similar", "any", "some", "all",
            "escape", "regexp", "rlike", "sounds", "like",
            "distinctrow", "straight_join", "sql_small_result", "sql_big_result",
            "sql_buffer_result", "sql_cache", "sql_no_cache", "sql_calc_found_rows",
            // ===== PostgreSQL 特有 =====
            "ilike", "similar", "vacuum", "reindex", "cluster",
            "copy", "stdin", "stdout", "csv", "delimiter", "header",
            "owner", "tablespace", "extension", "collation",
            "domain", "type", "cast", "operator", "class", "family",
            "concurrently", "recursive", "unlogged",
            "gin", "gist", "spgist", "brin", "hash", "btree",
            "exclusion", "using", "with", "oids",
            "plpgsql", "plpython", "plperl", "pltcl",
            "returns", "setof", "strict", "immutable", "stable", "volatile",
            "leakproof", "cost", "parallel", "safe",
            "listen", "notify", "payload",
            // ===== MySQL/MariaDB 特有 =====
            "engine", "innodb", "myisam", "memory", "merge", "archive", "csv", "federated",
            "algorithm", "inplace", "copy", "instant",
            "persistent", "virtual", "stored", "generated",
            "visible", "invisible", "signed", "unsigned", "zerofill",
            "on", "delete", "update", "cascade", "set", "null", "no", "action",
            "delimiter", "routine", "definer", "sql", "security",
            // ===== SQL Server 特有 =====
            "top", "percent", "cross", "apply", "outer", "pivot", "unpivot",
            "row_number", "rank", "dense_rank", "ntile",
            "raiserror", "throw", "try", "catch", "print",
            "goto", "waitfor", "delay",
            // ===== Oracle 特有 =====
            "rownum", "rowid", "connect", "by", "prior", "start", "with",
            "level", "sysdate", "systimestamp", "dual",
            "decode", "nvl", "nvl2", "to_date", "to_char", "to_number",
            "substr", "instr", "vsize",
            "merge", "flashback", "purge", "materialized",
            // ===== SQLite 特有 =====
            "integer", "primary", "key", "autoincrement",
            "if", "not", "exists", "attach", "detach",
            "explain", "query", "plan",
            "conflict", "abort", "fail", "ignore", "rollback", "replace",
            // ===== 条件运算符补充 =====
            "between", "in", "like", "glob", "match", "regexp",
            "isnull", "notnull", "is", "not", "null",
            "asc", "desc", "nulls", "first", "last",
    };

    /**
     * SQL 查询编辑器标签页：编辑器（RSyntaxTextArea 语法高亮）+ 结果表格 + 格式化 + 自动补全
     */
    private class QueryTabPanel extends JPanel {
        final String tabName;
        final RSyntaxTextArea sqlEditor;
        final RTextScrollPane sqlScrollPane;
        final JTable resultTable;
        final DefaultTableModel resultTableModel;
        final JLabel resultCountLabel;
        final JButton btnExecute;
        final AutoCompletion autoCompletion;

        QueryTabPanel(String name) {
            super(new BorderLayout(0, 2));
            this.tabName = name;

            // ===== RSyntaxTextArea SQL 编辑器 =====
            sqlEditor = new RSyntaxTextArea();
            sqlEditor.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_SQL);
            sqlEditor.setCodeFoldingEnabled(true);
            sqlEditor.setAntiAliasingEnabled(true);
            sqlEditor.setTabSize(4);
            sqlEditor.setTabsEmulated(false);
            sqlEditor.setFont(new Font("Consolas", Font.PLAIN, 13));
            sqlEditor.setBackground(new Color(0x2B2B2B));
            sqlEditor.setForeground(new Color(0xA9B7C6));
            sqlEditor.setCaretColor(new Color(0xBBBBBB));
            sqlEditor.setCurrentLineHighlightColor(new Color(0x323232));
            sqlEditor.setLineWrap(false);
            sqlEditor.setBorder(new EmptyBorder(4, 4, 4, 4));
            // 行号区域
            sqlEditor.setMargin(new java.awt.Insets(0, 4, 0, 0));

            // 尝试加载暗色主题
            try {
                Theme theme = Theme.load(QueryTabPanel.class.getResourceAsStream(
                        "/org/fife/ui/rsyntaxtextarea/themes/dark.xml"));
                if (theme != null) {
                    theme.apply(sqlEditor);
                }
            } catch (Exception ignored) {
                // 如果主题文件不存在，使用默认配色
            }

            // ===== 自动补全 =====
            CompletionProvider provider = createSqlCompletionProvider();
            autoCompletion = new AutoCompletion(provider);
            autoCompletion.setAutoCompleteEnabled(true);
            autoCompletion.setAutoActivationEnabled(true);
            autoCompletion.setAutoActivationDelay(200);
            autoCompletion.setParameterAssistanceEnabled(true);
            autoCompletion.install(sqlEditor);

            // 设置补全弹窗丝滑滚动：监听弹窗 JWindow 显示时注入平滑滚动
            java.awt.Toolkit.getDefaultToolkit().addAWTEventListener(event -> {
                if (event.getID() == java.awt.event.WindowEvent.WINDOW_OPENED
                        || event.getID() == java.awt.event.HierarchyEvent.SHOWING_CHANGED) {
                    Component src = (Component) event.getSource();
                    // 找到 AutoCompletion 的弹出 JWindow
                    Window window = (src instanceof Window) ? (Window) src
                            : SwingUtilities.getWindowAncestor(src);
                    if (window instanceof JWindow && window.isVisible()) {
                        smoothScrollInWindow((JWindow) window);
                    }
                }
            }, java.awt.AWTEvent.WINDOW_EVENT_MASK | java.awt.AWTEvent.HIERARCHY_EVENT_MASK);

            // ===== 快捷键绑定 =====
            // F5 执行
            sqlEditor.getInputMap().put(KeyStroke.getKeyStroke("F5"), "execute");
            sqlEditor.getActionMap().put("execute", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    executeSql();
                }
            });
            // Ctrl+Enter 执行
            sqlEditor.getInputMap().put(KeyStroke.getKeyStroke("control ENTER"), "executeCtrl");
            sqlEditor.getActionMap().put("executeCtrl", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    executeSql();
                }
            });
            // Ctrl+Shift+F 格式化
            sqlEditor.getInputMap().put(KeyStroke.getKeyStroke("control shift F"), "formatSql");
            sqlEditor.getActionMap().put("formatSql", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) {
                    formatSql();
                }
            });

            // ===== 工具栏 =====
            JPanel toolbar = new JPanel(new FlowLayout(FlowLayout.LEFT, 6, 2));
            btnExecute = NetUtil.makeBtn("执行 (F5)", new Color(0x1565C0));
            btnExecute.addActionListener(e -> executeSql());
            toolbar.add(btnExecute);

            JButton btnFormat = NetUtil.makeBtn("格式化 (Ctrl+Shift+F)", new Color(0x6A1B9A));
            btnFormat.addActionListener(e -> formatSql());
            toolbar.add(btnFormat);

            JButton btnClearSql = NetUtil.makeBtn("清空", null);
            btnClearSql.addActionListener(e -> sqlEditor.setText(""));
            toolbar.add(btnClearSql);

            JLabel hintLabel = new JLabel("  选中文本执行所选 SQL；输入时自动提示关键字");
            hintLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            hintLabel.setForeground(new Color(0x9E9E9E));
            toolbar.add(hintLabel);

            add(toolbar, BorderLayout.NORTH);

            // ===== SQL 编辑器滚动面板 =====
            sqlScrollPane = new RTextScrollPane(sqlEditor);
            sqlScrollPane.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "SQL 编辑器", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));
            sqlScrollPane.setMinimumSize(new Dimension(200, 150));
            // 开启行号显示
            sqlScrollPane.setLineNumbersEnabled(true);

            // ===== 结果面板 =====
            JPanel resultPanel = new JPanel(new BorderLayout(0, 2));

            resultTableModel = new DefaultTableModel() {
                @Override
                public boolean isCellEditable(int row, int column) { return false; }
            };
            resultTable = new JTable(resultTableModel);
            resultTable.setFont(FONT);
            resultTable.getTableHeader().setFont(FONT_BOLD);
            resultTable.setRowHeight(22);
            resultTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            resultTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            resultTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value,
                        boolean isSelected, boolean hasFocus, int row, int column) {
                    setHorizontalAlignment(SwingConstants.CENTER);
                    return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                }
            });

            JScrollPane tableScroll = new JScrollPane(resultTable);
            tableScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "查询结果", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));
            resultPanel.add(tableScroll, BorderLayout.CENTER);

            resultCountLabel = new JLabel("就绪");
            resultCountLabel.setFont(FONT);
            resultCountLabel.setBorder(new EmptyBorder(4, 6, 4, 6));
            resultPanel.add(resultCountLabel, BorderLayout.SOUTH);

            JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, sqlScrollPane, resultPanel);
            splitPane.setDividerLocation(260);
            splitPane.setDividerSize(4);
            splitPane.setResizeWeight(0.5);
            add(splitPane, BorderLayout.CENTER);
        }

        // ===== SQL 自动补全提供器 =====
        private CompletionProvider createSqlCompletionProvider() {
            DefaultCompletionProvider provider = new DefaultCompletionProvider();

            // 添加 SQL 关键字
            for (String kw : SQL_KEYWORDS) {
                provider.addCompletion(new BasicCompletion(provider, kw));
            }

            // 添加常用 SQL 模板片段（小写）
            provider.addCompletion(new ShorthandCompletion(provider, "sel",
                    "select * from ", "select * from ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "selc",
                    "select count(*) from ", "select count(*) from ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "ins",
                    "insert into  () values ()", "insert into ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "upd",
                    "update  set  where ", "update ... set ... where ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "del",
                    "delete from  where ", "delete from ... where ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "createt",
                    "create table  (\n    \n)", "create table ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "altert",
                    "alter table  add column ", "alter table ... add column"));
            provider.addCompletion(new ShorthandCompletion(provider, "dropt",
                    "drop table if exists ", "drop table if exists ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "join",
                    "inner join  on ", "inner join ... on ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "ljoin",
                    "left join  on ", "left join ... on ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "rjoin",
                    "right join  on ", "right join ... on ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "grp",
                    "group by ", "group by ..."));
            provider.addCompletion(new ShorthandCompletion(provider, "ord",
                    "order by  asc", "order by ... asc"));

            // 设置大小写不敏感
            provider.setAutoActivationRules(true, ".");
            provider.setParameterizedCompletionParams('(', ", ", ')');

            return provider;
        }

        /**
         * 设置补全弹窗中 JList 的丝滑滚动效果
         */
        private void smoothScrollInWindow(JWindow window) {
            if (window == null) return;
            // 遍历弹窗中的组件，找到 JList 并设置滚动增量
            for (Component comp : window.getComponents()) {
                findAndSmoothList(comp);
            }
        }

        private void findAndSmoothList(Component comp) {
            if (comp instanceof JList) {
                JList<?> list = (JList<?>) comp;
                Container parent = list.getParent();
                if (parent instanceof JViewport) {
                    Container gp = parent.getParent();
                    if (gp instanceof JScrollPane) {
                        JScrollPane sp = (JScrollPane) gp;
                        sp.getVerticalScrollBar().setUnitIncrement(20);
                        sp.getVerticalScrollBar().setBlockIncrement(80);
                        sp.getHorizontalScrollBar().setUnitIncrement(16);
                    }
                }
            } else if (comp instanceof Container) {
                for (Component child : ((Container) comp).getComponents()) {
                    findAndSmoothList(child);
                }
            }
        }

        // ===== SQL 格式化 =====
        private void formatSql() {
            String sql = sqlEditor.getSelectedText();
            boolean hasSelection = (sql != null && !sql.trim().isEmpty());
            if (!hasSelection) {
                sql = sqlEditor.getText();
            }

            if (sql == null || sql.trim().isEmpty()) return;

            String formatted = doFormatSql(sql);
            if (hasSelection) {
                int start = sqlEditor.getSelectionStart();
                int end = sqlEditor.getSelectionEnd();
                sqlEditor.replaceRange(formatted, start, end);
            } else {
                int caretPos = sqlEditor.getCaretPosition();
                sqlEditor.setText(formatted);
                try {
                    sqlEditor.setCaretPosition(Math.min(caretPos, formatted.length()));
                } catch (Exception ignored) {}
            }
            NetUtil.logSys(logPane, "SQL 已格式化");
        }

        /**
         * 格式化 SQL：基于关键字换行 + 缩进
         */
        private String doFormatSql(String sql) {
            if (sql == null || sql.trim().isEmpty()) return sql;

            // 先处理已有的空白，把多余空白规范化
            sql = sql.replaceAll("\\s+", " ").trim();

            // 需要在这些关键字前换行
            String[] breakBefore = {
                    "SELECT", "FROM", "WHERE", "AND", "OR",
                    "ORDER BY", "GROUP BY", "HAVING",
                    "LIMIT", "OFFSET",
                    "INNER JOIN", "LEFT JOIN", "RIGHT JOIN", "FULL JOIN", "CROSS JOIN",
                    "JOIN", "ON",
                    "INSERT INTO", "VALUES",
                    "UPDATE", "SET", "DELETE FROM",
                    "CREATE TABLE", "ALTER TABLE", "DROP TABLE", "DROP TABLE IF EXISTS",
                    "CREATE INDEX", "DROP INDEX",
                    "UNION", "UNION ALL", "INTERSECT", "EXCEPT",
                    "RETURNING", "CONFLICT",
            };

            // 需要在关键字后换行的（如 SELECT 后面的列）
            String[] breakAfter = {
                    "SELECT", "SET",
            };

            StringBuilder result = new StringBuilder();
            // 用空格分割，但保留引号内的内容
            List<String> tokens = tokenize(sql);

            int indentLevel = 0;
            boolean newLine = true;
            boolean afterSelect = false;

            for (int i = 0; i < tokens.size(); i++) {
                String token = tokens.get(i);
                String upperToken = token.toUpperCase().replaceAll("\\s+", " ");

                // 检查是否需要在此 token 前换行（向前看合并关键字）
                String combined = upperToken;
                if (i + 1 < tokens.size()) {
                    String nextUpper = tokens.get(i + 1).toUpperCase();
                    String twoWord = upperToken + " " + nextUpper;
                    for (String kw : breakBefore) {
                        if (twoWord.equals(kw)) {
                            combined = twoWord;
                            break;
                        }
                    }
                }

                boolean isBreakBefore = false;
                for (String kw : breakBefore) {
                    if (combined.equals(kw) || upperToken.equals(kw)) {
                        isBreakBefore = true;
                        break;
                    }
                }

                if (isBreakBefore) {
                    if (result.length() > 0) result.append("\n");
                    newLine = true;
                }

                // 处理逗号（SELECT 后的列）
                if (upperToken.equals(",") && afterSelect) {
                    result.append(",");
                    result.append("\n");
                    appendIndent(result, indentLevel + 1);
                    newLine = true;
                    continue;
                }

                // 处理左括号
                if (token.equals("(")) {
                    result.append("(");
                    indentLevel++;
                    if (afterSelect) {
                        result.append("\n");
                        appendIndent(result, indentLevel);
                        newLine = true;
                    }
                    continue;
                }

                // 处理右括号
                if (token.equals(")")) {
                    indentLevel = Math.max(0, indentLevel - 1);
                    result.append(")");
                    continue;
                }

                // 输出 token
                if (newLine) {
                    appendIndent(result, indentLevel);
                    newLine = false;
                } else if (result.length() > 0 && !result.substring(result.length() - 1).equals("\n")
                        && !result.substring(result.length() - 1).equals(" ")) {
                    result.append(" ");
                }

                result.append(token);

                // SELECT 后设置标记（列之间用逗号换行）
                if (upperToken.equals("SELECT")) {
                    afterSelect = true;
                    indentLevel = 1;
                    result.append("\n");
                    appendIndent(result, indentLevel);
                    newLine = true;
                }

                // FROM 后重置 afterSelect
                if (upperToken.equals("FROM")) {
                    afterSelect = false;
                }

                // SET 后增加缩进
                if (upperToken.equals("SET")) {
                    indentLevel = 1;
                    result.append("\n");
                    appendIndent(result, indentLevel);
                    newLine = true;
                }

                // WHERE 后设置缩进
                if (upperToken.equals("WHERE") || upperToken.equals("ON")) {
                    indentLevel = 1;
                }

                // 分号结尾
                if (token.endsWith(";") && token.length() > 1) {
                    // 分离分号
                    result.setLength(result.length() - 1); // 去掉已添加的 token（含分号）
                    result.append(token.substring(0, token.length() - 1));
                    result.append(";");
                }

                // 合并关键字已处理两个 token
                if (combined.contains(" ") && combined.equals(upperToken + " " + (i + 1 < tokens.size() ? tokens.get(i + 1).toUpperCase() : ""))) {
                    i++; // 跳过下一个 token（已合并处理）
                }
            }

            return result.toString().trim();
        }

        private void appendIndent(StringBuilder sb, int level) {
            for (int i = 0; i < level; i++) {
                sb.append("    ");
            }
        }

        /**
         * 简单分词：保留引号内字符串完整，其他按空格分割
         */
        private List<String> tokenize(String sql) {
            List<String> tokens = new ArrayList<>();
            StringBuilder current = new StringBuilder();
            char quoteChar = 0;
            boolean inQuote = false;

            for (int i = 0; i < sql.length(); i++) {
                char c = sql.charAt(i);
                if (inQuote) {
                    current.append(c);
                    if (c == quoteChar) {
                        inQuote = false;
                        quoteChar = 0;
                    }
                } else if (c == '\'' || c == '"') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    inQuote = true;
                    quoteChar = c;
                    current.append(c);
                } else if (c == '(' || c == ')' || c == ',' || c == ';') {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                    tokens.add(String.valueOf(c));
                } else if (Character.isWhitespace(c)) {
                    if (current.length() > 0) {
                        tokens.add(current.toString());
                        current.setLength(0);
                    }
                } else {
                    current.append(c);
                }
            }
            if (current.length() > 0) {
                tokens.add(current.toString());
            }
            return tokens;
        }

        // ===== SQL 执行 =====
        private void executeSql() {
            if (activeConnInfo == null || activeConnInfo.conn == null) {
                JOptionPane.showMessageDialog(DatabasePanel.this, "请先连接数据库", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            String sql = sqlEditor.getSelectedText();
            boolean isSelection = (sql != null && !sql.trim().isEmpty());
            if (!isSelection) {
                sql = sqlEditor.getText().trim();
            }
            if (sql == null || sql.trim().isEmpty()) {
                JOptionPane.showMessageDialog(DatabasePanel.this, "请输入 SQL 语句", "提示", JOptionPane.INFORMATION_MESSAGE);
                return;
            }

            sql = sql.trim();
            if (sql.endsWith(";")) {
                sql = sql.substring(0, sql.length() - 1).trim();
            }

            final String finalSql = sql;
            btnExecute.setEnabled(false);
            NetUtil.logSys(logPane, (isSelection ? "[选中执行] " : "[全部执行] ") + finalSql);

            new Thread(() -> {
                try (Statement stmt = activeConnInfo.conn.createStatement()) {
                    boolean isResultSet = stmt.execute(finalSql);

                    if (isResultSet) {
                        try (ResultSet rs = stmt.getResultSet()) {
                            ResultSetMetaData rsmd = rs.getMetaData();
                            int colCount = rsmd.getColumnCount();

                            String[] columns = new String[colCount];
                            for (int i = 0; i < colCount; i++) {
                                columns[i] = rsmd.getColumnName(i + 1);
                            }

                            List<Object[]> rows = new ArrayList<>();
                            int rowLimit = 1000;
                            while (rs.next() && rows.size() < rowLimit) {
                                Object[] row = new Object[colCount];
                                for (int i = 0; i < colCount; i++) {
                                    row[i] = rs.getObject(i + 1);
                                }
                                rows.add(row);
                            }

                            final int totalRows = rows.size();
                            final boolean truncated = rs.next();

                            SwingUtilities.invokeLater(() -> {
                                resultTableModel.setColumnIdentifiers(columns);
                                resultTableModel.setRowCount(0);
                                for (Object[] row : rows) {
                                    resultTableModel.addRow(row);
                                }
                                for (int i = 0; i < colCount; i++) {
                                    resultTable.getColumnModel().getColumn(i).setPreferredWidth(120);
                                }
                                resultCountLabel.setText("返回 " + totalRows + " 行"
                                        + (truncated ? " (最多显示1000行)" : ""));
                                btnExecute.setEnabled(true);
                                NetUtil.logSys(logPane, "查询成功，返回 " + totalRows + " 行");
                            });
                        }
                    } else {
                        int affected = stmt.getUpdateCount();
                        SwingUtilities.invokeLater(() -> {
                            resultTableModel.setColumnIdentifiers(new Object[]{});
                            resultTableModel.setRowCount(0);
                            resultCountLabel.setText("影响 " + affected + " 行");
                            btnExecute.setEnabled(true);
                            NetUtil.logSys(logPane, "执行成功，影响 " + affected + " 行");
                            refreshTree();
                        });
                    }
                } catch (Exception ex) {
                    final String err = ex.getMessage();
                    SwingUtilities.invokeLater(() -> {
                        btnExecute.setEnabled(true);
                        resultCountLabel.setText("执行失败");
                        NetUtil.logErr(logPane, "SQL 错误: " + err);
                    });
                }
            }, "SQLExec").start();
        }
    }

    // ==================== 内部类：表数据查看标签页 ====================

    /**
     * 表数据查看标签页：展示表数据，支持分页
     */
    private class TableDataTabPanel extends JPanel {
        final ConnectionInfo connectionInfo;
        final String dbName;
        final String tableName;

        final JTable dataTable;
        final DefaultTableModel tableModel;
        final JLabel countLabel;
        final JLabel pageLabel;
        final JButton btnPrevPage;
        final JButton btnNextPage;

        int currentPage = 1;
        int totalRowCount = 0;

        TableDataTabPanel(ConnectionInfo info, String dbName, String tableName) {
            super(new BorderLayout(0, 2));
            this.connectionInfo = info;
            this.dbName = dbName;
            this.tableName = tableName;

            // 表信息头部
            JPanel headerPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
            JLabel titleLabel = new JLabel("表: " + tableName + "  数据库: " + dbName + "  连接: " + connectionInfo.name);
            titleLabel.setFont(FONT_BOLD);
            headerPanel.add(titleLabel);
            add(headerPanel, BorderLayout.NORTH);

            // 数据表格
            tableModel = new DefaultTableModel() {
                @Override
                public boolean isCellEditable(int row, int column) { return false; }
            };
            dataTable = new JTable(tableModel);
            dataTable.setFont(FONT);
            dataTable.getTableHeader().setFont(FONT_BOLD);
            dataTable.setRowHeight(22);
            dataTable.setAutoResizeMode(JTable.AUTO_RESIZE_OFF);
            dataTable.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
            dataTable.setDefaultRenderer(Object.class, new DefaultTableCellRenderer() {
                @Override
                public Component getTableCellRendererComponent(JTable table, Object value,
                        boolean isSelected, boolean hasFocus, int row, int column) {
                    setHorizontalAlignment(SwingConstants.CENTER);
                    return super.getTableCellRendererComponent(table, value, isSelected, hasFocus, row, column);
                }
            });

            JScrollPane tableScroll = new JScrollPane(dataTable);
            tableScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "数据", TitledBorder.LEADING, TitledBorder.TOP, FONT_BOLD));
            add(tableScroll, BorderLayout.CENTER);

            // 底部分页栏
            JPanel bottomPanel = new JPanel(new BorderLayout(0, 2));
            bottomPanel.setBorder(new EmptyBorder(4, 6, 4, 6));

            countLabel = new JLabel("加载中...");
            countLabel.setFont(FONT);
            bottomPanel.add(countLabel, BorderLayout.WEST);

            JPanel pagePanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 4, 0));
            btnPrevPage = new JButton("< 上一页");
            btnPrevPage.setFont(FONT);
            btnPrevPage.setFocusPainted(false);
            btnPrevPage.setEnabled(false);
            btnPrevPage.addActionListener(e -> {
                if (currentPage > 1) {
                    loadPage(currentPage - 1);
                }
            });
            pagePanel.add(btnPrevPage);

            pageLabel = new JLabel("-");
            pageLabel.setFont(FONT);
            pagePanel.add(pageLabel);

            btnNextPage = new JButton("下一页 >");
            btnNextPage.setFont(FONT);
            btnNextPage.setFocusPainted(false);
            btnNextPage.setEnabled(false);
            btnNextPage.addActionListener(e -> {
                int totalPages = Math.max(1, (int) Math.ceil((double) totalRowCount / PAGE_SIZE));
                if (currentPage < totalPages) {
                    loadPage(currentPage + 1);
                }
            });
            pagePanel.add(btnNextPage);

            // 刷新按钮
            JButton btnRefresh = new JButton("刷新");
            btnRefresh.setFont(FONT);
            btnRefresh.setFocusPainted(false);
            btnRefresh.addActionListener(e -> loadPage(currentPage));
            pagePanel.add(btnRefresh);

            bottomPanel.add(pagePanel, BorderLayout.EAST);
            add(bottomPanel, BorderLayout.SOUTH);
        }

        void loadPage(int page) {
            if (connectionInfo == null || connectionInfo.conn == null) return;
            currentPage = page;
            btnPrevPage.setEnabled(false);
            btnNextPage.setEnabled(false);
            pageLabel.setText("加载中...");
            countLabel.setText("加载中...");
            NetUtil.logSys(logPane, "查询 " + tableName + " 第 " + page + " 页...");

            new Thread(() -> {
                try {
                    // 先查总数
                    int total = 0;
                    try (Statement countStmt = connectionInfo.conn.createStatement();
                         ResultSet countRs = countStmt.executeQuery("SELECT COUNT(*) FROM " + tableName)) {
                        if (countRs.next()) total = countRs.getInt(1);
                    }

                    // 查当前页数据
                    int offset = (page - 1) * PAGE_SIZE;
                    String sql = "SELECT * FROM " + tableName + " LIMIT " + PAGE_SIZE + " OFFSET " + offset;

                    final int finalTotal = total;
                    try (Statement stmt = connectionInfo.conn.createStatement();
                         ResultSet rs = stmt.executeQuery(sql)) {
                        ResultSetMetaData rsmd = rs.getMetaData();
                        int colCount = rsmd.getColumnCount();
                        String[] columns = new String[colCount];
                        for (int i = 0; i < colCount; i++) {
                            columns[i] = rsmd.getColumnName(i + 1);
                        }

                        List<Object[]> rows = new ArrayList<>();
                        while (rs.next()) {
                            Object[] row = new Object[colCount];
                            for (int i = 0; i < colCount; i++) {
                                row[i] = rs.getObject(i + 1);
                            }
                            rows.add(row);
                        }

                        final int rowCount = rows.size();
                        SwingUtilities.invokeLater(() -> {
                            tableModel.setColumnIdentifiers(columns);
                            tableModel.setRowCount(0);
                            for (Object[] row : rows) {
                                tableModel.addRow(row);
                            }
                            for (int i = 0; i < colCount; i++) {
                                dataTable.getColumnModel().getColumn(i).setPreferredWidth(120);
                            }
                            totalRowCount = finalTotal;
                            int totalPages = Math.max(1, (int) Math.ceil((double) finalTotal / PAGE_SIZE));
                            countLabel.setText("共 " + finalTotal + " 条，当前 " + rowCount + " 条");
                            pageLabel.setText("第 " + page + " / " + totalPages + " 页");
                            btnPrevPage.setEnabled(page > 1);
                            btnNextPage.setEnabled(page < totalPages);
                            NetUtil.logSys(logPane, "查询成功，返回 " + rowCount + " 行（共 " + finalTotal + " 条）");
                        });
                    }
                } catch (Exception ex) {
                    final String err = ex.getMessage();
                    SwingUtilities.invokeLater(() -> {
                        countLabel.setText("查询失败");
                        pageLabel.setText("第 " + page + " 页");
                        btnPrevPage.setEnabled(page > 1);
                        btnNextPage.setEnabled(true);
                        NetUtil.logErr(logPane, "查询 " + tableName + " 失败: " + err);
                    });
                }
            }, "TablePage").start();
        }
    }

    // ==================== 内部类：连接信息 ====================

    private static class ConnectionInfo {
        String name;       // 用户自定义连接名
        String type, host, port, user, pass, dbName;
        boolean connected; // 是否已连接
        Connection conn;   // 实际的 JDBC 连接
        DefaultMutableTreeNode treeNode; // 树中对应的节点

        ConnectionInfo(String name, String type, String host, String port, String user, String pass, String dbName) {
            this.name = name;
            this.type = type; this.host = host; this.port = port;
            this.user = user; this.pass = pass; this.dbName = dbName;
            this.connected = false;
        }

        /** 生成配置 key 前缀 */
        String configKey(String suffix) {
            return "dbconn." + name + "." + suffix;
        }
    }

    // ==================== 配置持久化 ====================

    private ConfigManager configManager;

    @Override
    public void loadConfig(ConfigManager config) {
        this.configManager = config;
        // 加载持久化的连接配置
        String connList = config.get("dbconn.list", "");
        if (connList.isEmpty()) return;

        String[] connNames = connList.split(",");
        for (String connName : connNames) {
            connName = connName.trim();
            if (connName.isEmpty()) continue;

            String type = config.get("dbconn." + connName + ".type", "");
            if (type.isEmpty()) continue;

            String host = config.get("dbconn." + connName + ".host", "");
            String port = config.get("dbconn." + connName + ".port", "");
            String user = config.get("dbconn." + connName + ".user", "");
            String pass = config.get("dbconn." + connName + ".pass", "");
            String dbName = config.get("dbconn." + connName + ".dbName", "");

            ConnectionInfo info = new ConnectionInfo(connName, type, host, port, user, pass, dbName);
            connections.put(connName, info);

            // 创建树节点（未连接状态）
            String addr = "SQLite".equals(type) ? dbName : (host + ":" + port);
            DefaultMutableTreeNode connNode = new DefaultMutableTreeNode(ICON_CONNECT_PREFIX + connName + "|" + addr);
            connNode.add(new DefaultMutableTreeNode("(未连接)"));
            info.treeNode = connNode;
            rootNode.add(connNode);
        }
        treeModel.reload();
    }

    @Override
    public void saveConfig(ConfigManager config) {
        // 先保存连接配置
        saveConnectionsConfig();
        // 断开所有连接
        for (ConnectionInfo info : new ArrayList<>(connections.values())) {
            if (info.connected) {
                closeConnection(info);
            }
        }
    }

    /** 保存连接配置到 ConfigManager */
    private void saveConnectionsConfig() {
        if (configManager == null) return;

        // 先清除旧的连接配置
        String oldList = configManager.get("dbconn.list", "");
        if (!oldList.isEmpty()) {
            for (String oldName : oldList.split(",")) {
                oldName = oldName.trim();
                if (oldName.isEmpty()) continue;
                configManager.set("dbconn." + oldName + ".type", null);
                configManager.set("dbconn." + oldName + ".host", null);
                configManager.set("dbconn." + oldName + ".port", null);
                configManager.set("dbconn." + oldName + ".user", null);
                configManager.set("dbconn." + oldName + ".pass", null);
                configManager.set("dbconn." + oldName + ".dbName", null);
            }
        }

        // 保存当前所有连接
        StringBuilder sb = new StringBuilder();
        for (ConnectionInfo info : connections.values()) {
            if (!sb.isEmpty()) sb.append(",");
            sb.append(info.name);

            configManager.set(info.configKey("type"), info.type);
            configManager.set(info.configKey("host"), info.host);
            configManager.set(info.configKey("port"), info.port);
            configManager.set(info.configKey("user"), info.user);
            configManager.set(info.configKey("pass"), info.pass);
            configManager.set(info.configKey("dbName"), info.dbName);
        }
        configManager.set("dbconn.list", sb.toString());
        configManager.save();
    }
}
