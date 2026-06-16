package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import org.fife.ui.rsyntaxtextarea.RSyntaxTextArea;
import org.fife.ui.rsyntaxtextarea.SyntaxConstants;
import org.fife.ui.rtextarea.RTextScrollPane;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

import static com.szh.ui.panel.NetUtil.*;

/**
 * HTTP 调试面板：GET/POST/PUT/DELETE 请求，支持自定义 Header、请求体、参数
 */
public class HttpPanel extends AbstractCommandPanel {

    private final ExecutorService threadPool = Executors.newVirtualThreadPerTaskExecutor();
    private final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();
    private HttpRequestPanel httpPanel;

    public HttpPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        httpPanel = new HttpRequestPanel();
        add(httpPanel, BorderLayout.CENTER);
    }

    private class HttpRequestPanel extends JPanel {
        // ---- 请求行 ----
        private JComboBox<String> methodCombo;
        private JTextField urlField;
        private JTextField timeoutField;
        private JButton btnSend;

        // ---- 请求头 ----
        private final DefaultTableModel headerTableModel = new DefaultTableModel(
                new String[]{"启用", "Header 名称", "Header 值"}, 0) {
            @Override public Class<?> getColumnClass(int col) { return col == 0 ? Boolean.class : String.class; }
            @Override public boolean isCellEditable(int row, int col) { return true; }
        };
        private JTable headerTable;

        // ---- 请求参数 ----
        private final DefaultTableModel paramTableModel = new DefaultTableModel(
                new String[]{"参数名", "参数值"}, 0) {
            @Override public boolean isCellEditable(int row, int col) { return true; }
        };
        private JTable paramTable;

        // ---- 请求体 ----
        private RSyntaxTextArea bodyArea;
        private JComboBox<String> bodyTypeCombo;

        // ---- 响应区 ----
        private JTextPane logPane;
        private RSyntaxTextArea responseArea;

        // ---- 文件上传 ----
        private final List<File> uploadFiles = new ArrayList<>();
        private JPanel fileListPanel;       // 左侧文件列表
        private JLabel filePreviewLabel;    // 右侧图片预览
        private RSyntaxTextArea filePreviewArea; // 右侧文本预览
        private JPanel filePreviewCardPanel; // 右侧预览卡片面板
        private JPanel bodyContentPanel;     // CardLayout 容器（bodyScroll / filePanel 切换）
        private int previewFileIndex = -1;   // 当前预览的文件索引，用于动态 repaint

        // ---- 历史 ----
        private final List<String> history = new ArrayList<>();
        private static final int MAX_HISTORY = 50;

        HttpRequestPanel() {
            setLayout(new BorderLayout(4, 4));
            setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "HTTP 请求", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 12)));

            // ---- 左侧：请求区 ----
            JPanel leftPanel = new JPanel(new BorderLayout(4, 4));

            // 请求行
            JPanel urlRow = new JPanel(new BorderLayout(4, 0));
            JPanel urlLeft = new JPanel(new BorderLayout(2, 0));
            methodCombo = new JComboBox<>(new String[]{"GET", "POST", "PUT", "DELETE", "PATCH", "HEAD", "OPTIONS"});
            methodCombo.setPreferredSize(new Dimension(85, 26));
            urlLeft.add(methodCombo, BorderLayout.WEST);
            urlField = new JTextField("http://httpbin.org/get");
            urlField.setFont(FONT_TEXT);
            // 启用 Ctrl+Z/Y 撤销/恢复
            UndoManager undoManager = new UndoManager();
            urlField.getDocument().addUndoableEditListener(undoManager);
            urlField.getActionMap().put("Undo", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (undoManager.canUndo()) undoManager.undo();
                }
            });
            urlField.getInputMap().put(KeyStroke.getKeyStroke("control Z"), "Undo");
            urlField.getActionMap().put("Redo", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (undoManager.canRedo()) undoManager.redo();
                }
            });
            urlField.getInputMap().put(KeyStroke.getKeyStroke("control Y"), "Redo");
            urlLeft.add(urlField, BorderLayout.CENTER);
            urlRow.add(urlLeft, BorderLayout.CENTER);

            JPanel urlRight = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            urlRight.add(new JLabel("超时(s):"));
            timeoutField = new JTextField("30", 3);
            timeoutField.setFont(FONT_TEXT);
            timeoutField.setToolTipText("请求超时时间（秒），默认30秒");
            urlRight.add(timeoutField);
            btnSend = makeBtn("发送", new Color(0x4CAF50));
            urlRight.add(btnSend);
            urlRow.add(urlRight, BorderLayout.EAST);

            // Tab 折叠：请求头 / 参数 / 请求体
            JTabbedPane reqTabs = new JTabbedPane(JTabbedPane.TOP);
            reqTabs.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));

            // -- 请求头 --
            JPanel headerPanel = new JPanel(new BorderLayout(2, 2));

            // 常用 Header 快捷复选框（3列网格）
            JPanel headerQuickOuter = new JPanel(new BorderLayout());
            headerQuickOuter.add(new JLabel("快捷添加:"), BorderLayout.NORTH);
            JPanel headerQuickPanel = new JPanel(new GridLayout(0, 3, 4, 2));
            headerQuickPanel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
            String[] quickHeaders = {
                    "Content-Type: application/json",
                    "Accept: application/json",
                    "Authorization: Bearer ",
                    "User-Agent: Mozilla/5.0",
                    "Cookie: ",
                    "Referer: ",
                    "Cache-Control: no-cache",
                    "Connection: keep-alive"
            };
            final Map<String, JCheckBox> quickCheckBoxes = new LinkedHashMap<>();
            for (String h : quickHeaders) {
                JCheckBox cb = new JCheckBox(h);
                cb.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
                String[] kv = h.split(": ", 2);
                cb.addActionListener(e -> {
                    if (cb.isSelected()) {
                        addHeaderRow(kv[0], kv.length > 1 ? kv[1] : "");
                    } else {
                        removeHeaderRows(kv[0]);
                    }
                });
                headerQuickPanel.add(cb);
                quickCheckBoxes.put(kv[0].toLowerCase(), cb);
            }
            headerQuickOuter.add(headerQuickPanel, BorderLayout.CENTER);
            headerPanel.add(headerQuickOuter, BorderLayout.NORTH);

            // 预设默认行 + 同步勾选快捷复选框
            addHeaderRow("Accept", "*/*");
            addHeaderRow("User-Agent", "CorTools/1.0");
            JCheckBox cbAccept = quickCheckBoxes.get("accept");
            if (cbAccept != null) cbAccept.setSelected(true);
            JCheckBox cbUA = quickCheckBoxes.get("user-agent");
            if (cbUA != null) cbUA.setSelected(true);

            headerTable = new JTable(headerTableModel);
            headerTable.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            headerTable.setRowHeight(22);
            headerTable.getColumnModel().getColumn(0).setMaxWidth(40);
            headerTable.getColumnModel().getColumn(1).setPreferredWidth(150);
            headerTable.getColumnModel().getColumn(2).setPreferredWidth(300);
            headerTable.setShowGrid(true);
            headerTable.setGridColor(new Color(60, 60, 60));
            JScrollPane headerScroll = new JScrollPane(headerTable);

            // 增删行按钮
            JPanel headerBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JButton btnAddHeader = new JButton("+ 添加行");
            btnAddHeader.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
            btnAddHeader.addActionListener(e -> {
                headerTableModel.addRow(new Object[]{Boolean.TRUE, "", ""});
                headerTable.editCellAt(headerTableModel.getRowCount() - 1, 1);
            });
            JButton btnDelHeader = new JButton("- 删除选中行");
            btnDelHeader.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
            btnDelHeader.addActionListener(e -> {
                int[] rows = headerTable.getSelectedRows();
                if (rows.length == 0) {
                    int lastRow = headerTableModel.getRowCount() - 1;
                    if (lastRow >= 0) {
                        // 删除前同步取消对应复选框
                        String key = (String) headerTableModel.getValueAt(lastRow, 1);
                        JCheckBox cb = quickCheckBoxes.get(key != null ? key.toLowerCase() : "");
                        if (cb != null) cb.setSelected(false);
                        headerTableModel.removeRow(lastRow);
                    }
                } else {
                    for (int i = rows.length - 1; i >= 0; i--) {
                        // 删除前同步取消对应复选框
                        String key = (String) headerTableModel.getValueAt(rows[i], 1);
                        JCheckBox cb = quickCheckBoxes.get(key != null ? key.toLowerCase() : "");
                        if (cb != null) cb.setSelected(false);
                        headerTableModel.removeRow(rows[i]);
                    }
                }
            });
            headerBtnPanel.add(btnAddHeader);
            headerBtnPanel.add(btnDelHeader);
            headerPanel.add(headerScroll, BorderLayout.CENTER);
            headerPanel.add(headerBtnPanel, BorderLayout.SOUTH);

            reqTabs.addTab("Headers", headerPanel);

            // -- 请求参数 (Query Params) --
            JPanel paramPanel = new JPanel(new BorderLayout(2, 2));

            paramTable = new JTable(paramTableModel);
            paramTable.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            paramTable.setRowHeight(22);
            paramTable.getColumnModel().getColumn(0).setPreferredWidth(150);
            paramTable.getColumnModel().getColumn(1).setPreferredWidth(300);
            paramTable.setShowGrid(true);
            paramTable.setGridColor(new Color(60, 60, 60));
            JScrollPane paramScroll = new JScrollPane(paramTable);

            JPanel paramBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JButton btnAddParam = new JButton("+ 添加行");
            btnAddParam.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
            btnAddParam.addActionListener(e -> {
                paramTableModel.addRow(new Object[]{"", ""});
                paramTable.editCellAt(paramTableModel.getRowCount() - 1, 0);
            });
            JButton btnDelParam = new JButton("- 删除选中行");
            btnDelParam.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
            btnDelParam.addActionListener(e -> {
                int[] rows = paramTable.getSelectedRows();
                if (rows.length == 0) {
                    int lastRow = paramTableModel.getRowCount() - 1;
                    if (lastRow >= 0) paramTableModel.removeRow(lastRow);
                } else {
                    for (int i = rows.length - 1; i >= 0; i--) paramTableModel.removeRow(rows[i]);
                }
            });
            paramBtnPanel.add(btnAddParam);
            paramBtnPanel.add(btnDelParam);
            paramPanel.add(paramScroll, BorderLayout.CENTER);
            paramPanel.add(paramBtnPanel, BorderLayout.SOUTH);

            reqTabs.addTab("Params", paramPanel);

            // -- 请求体 --
            JPanel bodyPanel = new JPanel(new BorderLayout(2, 2));

            // 类型选择栏
            bodyTypeCombo = new JComboBox<>(new String[]{"JSON", "Form", "Text", "XML", "File"});
            bodyTypeCombo.setPreferredSize(new Dimension(80, 22));
            JPanel bodyTop = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            bodyTop.add(new JLabel("类型:"));
            bodyTop.add(bodyTypeCombo);
            JButton btnAddFiles = new JButton("+ 添加文件");
            btnAddFiles.setFont(new Font("Microsoft YaHei", Font.PLAIN, 10));
            btnAddFiles.addActionListener(e -> openFileChooser());
            btnAddFiles.setVisible(false);
            bodyTop.add(btnAddFiles);

            // CardLayout 容器：切换 bodyScroll / filePanel
            bodyContentPanel = new JPanel(new CardLayout());

            // 使用 RSyntaxTextArea，默认 JSON 语法高亮
            bodyArea = new RSyntaxTextArea(5, 20);
            bodyArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
            bodyArea.setCodeFoldingEnabled(false);
            bodyArea.setMarginLineEnabled(false);
            bodyArea.setCloseCurlyBraces(true);
            bodyArea.setCloseMarkupTags(true);
            bodyArea.setAutoIndentEnabled(true);
            bodyArea.setBracketMatchingEnabled(true);
            bodyArea.setTabsEmulated(true);
            bodyArea.setTabSize(4);
            bodyArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            bodyArea.setBackground(C_BG);
            bodyArea.setForeground(new Color(0xD4D4D4));
            bodyArea.setCaretColor(new Color(0xD4D4D4));
            bodyArea.setCurrentLineHighlightColor(new Color(0x2A2A2A));
            bodyArea.setToolTipText("请求体内容，支持 JSON/XML 语法高亮、自动补全、自动缩进；拖入文件可读取内容");

            // 确保撤销功能启用
            RTextScrollPane bodyScroll = new RTextScrollPane(bodyArea);
            bodyScroll.setLineNumbersEnabled(true);

            // 文件拖放：拖入文件自动读取内容到 body
            bodyScroll.setTransferHandler(new TransferHandler("text") {
                @Override
                public boolean canImport(TransferSupport support) {
                    return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                }
                @Override
                public boolean importData(TransferSupport support) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        if (!files.isEmpty()) {
                            String currentType = (String) bodyTypeCombo.getSelectedItem();
                            if ("File".equals(currentType)) {
                                // 文件模式：添加到上传列表
                                for (File f : files) {
                                    if (!uploadFiles.contains(f)) uploadFiles.add(f);
                                }
                                refreshFileList();
                                logSys(logPane, "已添加 " + files.size() + " 个文件");
                            } else {
                                // 文本模式：读取内容
                                File file = files.get(0);
                                String content = Files.readString(file.toPath());
                                bodyArea.setText(content);
                                String name = file.getName().toLowerCase();
                                if (name.endsWith(".json")) {
                                    bodyArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
                                    bodyTypeCombo.setSelectedItem("JSON");
                                } else if (name.endsWith(".xml")) {
                                    bodyArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
                                    bodyTypeCombo.setSelectedItem("XML");
                                } else {
                                    bodyArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                                    bodyTypeCombo.setSelectedItem("Text");
                                }
                                logSys(logPane, "已读取文件: " + file.getAbsolutePath());
                            }
                            return true;
                        }
                    } catch (Exception ex) {
                        logWarn(logPane, "读取文件失败: " + ex.getMessage());
                    }
                    return false;
                }
            });

            bodyContentPanel.add(bodyScroll, "text");

            // -- 文件上传面板（左右分栏）--
            JPanel filePanel = new JPanel(new BorderLayout(4, 0));
            // 左侧：文件列表
            fileListPanel = new JPanel();
            fileListPanel.setLayout(new BoxLayout(fileListPanel, BoxLayout.Y_AXIS));
            fileListPanel.setBackground(new Color(0x252526));
            JScrollPane fileListScroll = new JScrollPane(fileListPanel);
            fileListScroll.setPreferredSize(new Dimension(220, 0));
            fileListScroll.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
            fileListScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "文件列表", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));
            // 拖放支持
            fileListScroll.setTransferHandler(new TransferHandler("text") {
                @Override
                public boolean canImport(TransferSupport support) {
                    return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
                }
                @Override
                public boolean importData(TransferSupport support) {
                    try {
                        @SuppressWarnings("unchecked")
                        List<File> files = (List<File>) support.getTransferable()
                                .getTransferData(DataFlavor.javaFileListFlavor);
                        for (File f : files) {
                            if (!uploadFiles.contains(f)) uploadFiles.add(f);
                        }
                        refreshFileList();
                        logSys(logPane, "已添加 " + files.size() + " 个文件");
                        return true;
                    } catch (Exception ex) {
                        logWarn(logPane, "拖入失败: " + ex.getMessage());
                    }
                    return false;
                }
            });

            // 右侧：预览面板
            JPanel previewWrap = new JPanel(new BorderLayout(0, 0));
            previewWrap.setBackground(new Color(0x252526));

            filePreviewCardPanel = new JPanel(new CardLayout());
            filePreviewArea = new RSyntaxTextArea();
            filePreviewArea.setEditable(false);
            filePreviewArea.setFocusable(false);
            filePreviewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            filePreviewArea.setCodeFoldingEnabled(false);
            filePreviewArea.setMarginLineEnabled(false);
            filePreviewArea.setFont(new Font("Consolas", Font.PLAIN, 11));
            filePreviewArea.setBackground(C_BG);
            filePreviewArea.setForeground(new Color(0xD4D4D4));
            RTextScrollPane previewScroll = new RTextScrollPane(filePreviewArea);
            filePreviewCardPanel.add(previewScroll, "text");

            filePreviewLabel = new JLabel("点击文件列表中的 👁 预览", SwingConstants.CENTER);
            filePreviewLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            filePreviewLabel.setForeground(new Color(0x888888));
            filePreviewLabel.setBackground(C_BG);
            filePreviewLabel.setOpaque(true);
            filePreviewCardPanel.add(new JScrollPane(filePreviewLabel), "image");

            previewWrap.add(filePreviewCardPanel, BorderLayout.CENTER);

            JSplitPane fileSplit = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, fileListScroll, previewWrap);
            fileSplit.setResizeWeight(0.35);
            // 监听分割条拖动，动态重新缩放图片
            fileSplit.addPropertyChangeListener(JSplitPane.DIVIDER_LOCATION_PROPERTY, e -> {
                if (previewFileIndex >= 0 && previewFileIndex < uploadFiles.size()) {
                    previewFile(previewFileIndex);
                }
            });
            // 监听面板大小变化，动态重新缩放图片
            filePreviewCardPanel.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    if (previewFileIndex >= 0 && previewFileIndex < uploadFiles.size()) {
                        String name = uploadFiles.get(previewFileIndex).getName().toLowerCase();
                        if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                                || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")) {
                            previewFile(previewFileIndex);
                        }
                    }
                }
            });
            filePanel.add(fileSplit, BorderLayout.CENTER);
            bodyContentPanel.add(filePanel, "file");

            // bodyTop + bodyContentPanel 放在中间
            JPanel bodyCenter = new JPanel(new BorderLayout(0, 2));
            bodyCenter.add(bodyTop, BorderLayout.NORTH);
            bodyCenter.add(bodyContentPanel, BorderLayout.CENTER);
            bodyPanel.add(bodyCenter, BorderLayout.CENTER);

            // 切换类型时同步语法高亮 及 CardLayout 切换
            bodyTypeCombo.addActionListener(e -> {
                String type = (String) bodyTypeCombo.getSelectedItem();
                if (type == null) return;
                CardLayout cl = (CardLayout) bodyContentPanel.getLayout();
                switch (type) {
                    case "File":
                        cl.show(bodyContentPanel, "file");
                        btnAddFiles.setVisible(true);
                        break;
                    case "JSON":
                        bodyArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
                        cl.show(bodyContentPanel, "text");
                        btnAddFiles.setVisible(false);
                        break;
                    case "XML":
                        bodyArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
                        cl.show(bodyContentPanel, "text");
                        btnAddFiles.setVisible(false);
                        break;
                    default:
                        bodyArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                        cl.show(bodyContentPanel, "text");
                        btnAddFiles.setVisible(false);
                        break;
                }
            });

            reqTabs.addTab("Body", bodyPanel);

            leftPanel.add(urlRow, BorderLayout.NORTH);
            leftPanel.add(reqTabs, BorderLayout.CENTER);

            // ---- 右侧：响应区 ----
            JPanel rightPanel = new JPanel(new BorderLayout(4, 4));

            logPane = createLogPane();
            JScrollPane logScroll = new JScrollPane(logPane);
            logScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "HTTP日志", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));
            logScroll.setPreferredSize(new Dimension(400, 160));

            responseArea = new RSyntaxTextArea();
            responseArea.setEditable(false);
            responseArea.setFocusable(false);
            responseArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
            responseArea.setMarginLineEnabled(false);
            responseArea.setCodeFoldingEnabled(false);
            responseArea.setBracketMatchingEnabled(true);
            responseArea.setFont(new Font("Consolas", Font.PLAIN, 11));
            responseArea.setBackground(C_BG);
            responseArea.setForeground(new Color(0xD4D4D4));
            responseArea.setCaretColor(new Color(0xD4D4D4));
            responseArea.setCurrentLineHighlightColor(new Color(0x2A2A2A));
            RTextScrollPane respScroll = new RTextScrollPane(responseArea);
            respScroll.setLineNumbersEnabled(true);
            respScroll.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "响应体", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));

            rightPanel.add(logScroll, BorderLayout.NORTH);
            rightPanel.add(respScroll, BorderLayout.CENTER);

            // ---- 左右分栏 ----
            JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
            splitPane.setResizeWeight(0.5);
            splitPane.setDividerSize(4);
            add(splitPane, BorderLayout.CENTER);

            // ---- 事件 ----
            btnSend.addActionListener(e -> doSend());

            // Ctrl+Enter 发送
            urlField.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
            urlField.getActionMap().put("send", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) { doSend(); }
            });
            bodyArea.getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke("ctrl ENTER"), "send");
            bodyArea.getActionMap().put("send", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) { doSend(); }
            });

            // 初始化文件列表空状态提示 & 预览区默认提示
            SwingUtilities.invokeLater(() -> {
                refreshFileList();
                clearPreview();
            });
        }

        // ==================== 发送请求 ====================

        private void doSend() {
            String url = urlField.getText().trim();
            if (url.isEmpty()) { logWarn(logPane, "请输入 URL"); return; }

            // 自动补全协议
            if (!url.startsWith("http://") && !url.startsWith("https://")) {
                url = "http://" + url;
                urlField.setText(url);
            }

            String method = (String) methodCombo.getSelectedItem();
            addHistory(url);

            // 解析超时时间
            int timeoutSec = 30;
            try { timeoutSec = Integer.parseInt(timeoutField.getText().trim()); }
            catch (NumberFormatException e) { logWarn(logPane, "超时时间格式错误，使用默认30秒"); }
            if (timeoutSec <= 0) timeoutSec = 30;

            final String finalUrl = url;
            final int finalTimeout = timeoutSec;

            threadPool.submit(() -> {
                try {
                    String requestUrl = finalUrl;

                    // 从表格读取查询参数
                    Map<String, String> queryParams = readTableModel(paramTableModel);
                    if (!queryParams.isEmpty()) {
                        StringBuilder qs = new StringBuilder();
                        for (Map.Entry<String, String> e : queryParams.entrySet()) {
                            if (qs.length() > 0) qs.append('&');
                            qs.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                                    .append('=')
                                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                        }
                        requestUrl += (requestUrl.contains("?") ? "&" : "?") + qs;
                    }

                    HttpRequest.Builder builder = HttpRequest.newBuilder()
                            .uri(URI.create(requestUrl))
                            .timeout(Duration.ofSeconds(finalTimeout));

                    // 从表格读取请求头（仅勾选启用的行）
                    Map<String, String> headers = readHeaderTable();
                    for (Map.Entry<String, String> e : headers.entrySet()) {
                        builder.header(e.getKey(), e.getValue());
                    }

                    // 请求体
                    String bodyType = (String) bodyTypeCombo.getSelectedItem();
                    String bodyText = bodyArea.getText().trim();
                    HttpRequest.BodyPublisher bodyPublisher = HttpRequest.BodyPublishers.noBody();

                    if (!bodyText.isEmpty() && !"GET".equalsIgnoreCase(method) && !"HEAD".equalsIgnoreCase(method)) {
                        switch (bodyType) {
                            case "Form":
                                if (!headers.containsKey("Content-Type")) {
                                    builder.header("Content-Type", "application/x-www-form-urlencoded");
                                }
                                Map<String, String> formData = parseKeyValue(bodyText);
                                StringBuilder formBody = new StringBuilder();
                                for (Map.Entry<String, String> e : formData.entrySet()) {
                                    if (formBody.length() > 0) formBody.append('&');
                                    formBody.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8))
                                            .append('=')
                                            .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
                                }
                                bodyPublisher = HttpRequest.BodyPublishers.ofString(formBody.toString());
                                break;
                            case "JSON":
                                if (!headers.containsKey("Content-Type")) {
                                    builder.header("Content-Type", "application/json");
                                }
                                bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyText);
                                break;
                            case "XML":
                                if (!headers.containsKey("Content-Type")) {
                                    builder.header("Content-Type", "application/xml");
                                }
                                bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyText);
                                break;
                            case "File":
                                if (uploadFiles.isEmpty()) {
                                    logWarn(logPane, "请先添加文件");
                                    return;
                                }
                                // 构建 multipart/form-data
                                String boundary = "----CorTools" + System.currentTimeMillis();
                                builder.header("Content-Type", "multipart/form-data; boundary=" + boundary);
                                bodyPublisher = buildMultipartPublisher(uploadFiles, boundary);
                                break;
                            default: // Text
                                bodyPublisher = HttpRequest.BodyPublishers.ofString(bodyText);
                                break;
                        }
                    }

                    builder.method(method, bodyPublisher);
                    HttpRequest request = builder.build();

                    logSys(logPane, method + " " + requestUrl);
                    if (!bodyText.isEmpty() && !"File".equals(bodyType)) {
                        logSend(logPane, "Body → " + truncate(bodyText, 200));
                    }
                    if ("File".equals(bodyType)) {
                        logSend(logPane, "上传文件 " + uploadFiles.size() + " 个: " +
                                uploadFiles.stream().map(File::getName).reduce((a, b) -> a + ", " + b).orElse(""));
                    }

                    Instant start = Instant.now();
                    // 先用字节数组接收，再根据 Content-Type 解码，避免乱码
                    HttpResponse<byte[]> response = httpClient.send(request, HttpResponse.BodyHandlers.ofByteArray());
                    long elapsed = Duration.between(start, Instant.now()).toMillis();

                    // 从响应头解析 charset
                    String contentType = response.headers().firstValue("Content-Type").orElse("");
                    java.nio.charset.Charset respCharset = StandardCharsets.UTF_8;
                    String ctLower = contentType.toLowerCase();
                    int csIdx = ctLower.indexOf("charset=");
                    if (csIdx >= 0) {
                        try {
                            respCharset = java.nio.charset.Charset.forName(contentType.substring(csIdx + 8).trim());
                        } catch (Exception ignored) {}
                    }
                    String body = new String(response.body(), respCharset);

                    // 显示响应状态和头
                    logSys(logPane, "响应 " + response.statusCode() + " (" + elapsed + "ms)");
                    StringBuilder respHeaders = new StringBuilder();
                    response.headers().map().forEach((k, v) -> {
                        for (String val : v) respHeaders.append(k).append(": ").append(val).append("\n");
                    });

                    // 根据 Content-Type 自动选择语法高亮
                    final String finalCt = ctLower;
                    SwingUtilities.invokeLater(() -> {
                        responseArea.setText(
                                "=== Status: " + response.statusCode() + " (" + elapsed + "ms) ===\n\n"
                                        + "=== Response Headers ===\n" + respHeaders + "\n"
                                        + "=== Response Body ===\n" + body
                        );
                        responseArea.setCaretPosition(0);
                        // 自动语法高亮
                        if (finalCt.contains("json")) {
                            responseArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
                        } else if (finalCt.contains("xml") || finalCt.contains("html")) {
                            responseArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
                        } else {
                            responseArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                        }
                    });

                    logRecv(logPane, "← " + response.statusCode() + " " + truncate(body, 200));

                } catch (Exception e) {
                    String msg;
                    if (e instanceof HttpTimeoutException
                            || (e.getCause() instanceof HttpTimeoutException)) {
                        msg = "请求超时 (" + finalTimeout + "秒)";
                        String finalMsg1 = msg;
                        SwingUtilities.invokeLater(() -> {
                            logErr(logPane, finalMsg1);
                            responseArea.setText(finalMsg1);
                        });
                    } else {
                        msg = e.getMessage();
                        if (msg == null) msg = e.getClass().getSimpleName();
                        String finalMsg = msg;
                        SwingUtilities.invokeLater(() -> {
                            logErr(logPane, "请求失败: " + finalMsg);
                            responseArea.setText("请求失败:\n" + finalMsg);
                        });
                    }
                }
            });
        }

        // ==================== 表格辅助方法 ====================

        /** 从 Header 表格读取已启用的键值对 */
        private Map<String, String> readHeaderTable() {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < headerTableModel.getRowCount(); i++) {
                Boolean enabled = (Boolean) headerTableModel.getValueAt(i, 0);
                if (enabled != null && enabled) {
                    String key = (String) headerTableModel.getValueAt(i, 1);
                    String val = (String) headerTableModel.getValueAt(i, 2);
                    if (key != null && !key.trim().isEmpty()) {
                        map.put(key.trim(), val != null ? val.trim() : "");
                    }
                }
            }
            return map;
        }

        /** 从参数表格读取键值对 */
        private Map<String, String> readTableModel(DefaultTableModel model) {
            Map<String, String> map = new LinkedHashMap<>();
            for (int i = 0; i < model.getRowCount(); i++) {
                String key = (String) model.getValueAt(i, 0);
                String val = (String) model.getValueAt(i, 1);
                if (key != null && !key.trim().isEmpty()) {
                    map.put(key.trim(), val != null ? val.trim() : "");
                }
            }
            return map;
        }

        /** 添加 Header 行（避免重复） */
        private void addHeaderRow(String key, String value) {
            for (int i = 0; i < headerTableModel.getRowCount(); i++) {
                String existing = (String) headerTableModel.getValueAt(i, 1);
                if (key.equalsIgnoreCase(existing)) {
                    headerTableModel.setValueAt(Boolean.TRUE, i, 0);
                    return;
                }
            }
            headerTableModel.addRow(new Object[]{Boolean.TRUE, key, value});
        }

        /** 按名称取消/删除 Header 行 */
        private void removeHeaderRows(String key) {
            for (int i = headerTableModel.getRowCount() - 1; i >= 0; i--) {
                String existing = (String) headerTableModel.getValueAt(i, 1);
                if (key.equalsIgnoreCase(existing)) {
                    headerTableModel.removeRow(i);
                }
            }
        }

        // ==================== 文件上传相关 ====================

        /** 打开文件选择器添加文件 */
        private void openFileChooser() {
            SwingUtilities.invokeLater(() -> {
                JFileChooser chooser = new JFileChooser();
                chooser.setMultiSelectionEnabled(true);
                // 关闭文件图标/缩略图预览，加速目录切换
                chooser.setFileView(new javax.swing.filechooser.FileView() {
                    @Override public Icon getIcon(File f) { return null; }
                });
                // 加速大目录：不监听文件变化
                chooser.putClientProperty("FileChooser.useShellFolder", Boolean.FALSE);
                if (chooser.showOpenDialog(HttpRequestPanel.this) == JFileChooser.APPROVE_OPTION) {
                    for (File f : chooser.getSelectedFiles()) {
                        if (!uploadFiles.contains(f)) uploadFiles.add(f);
                    }
                    refreshFileList();
                    logSys(logPane, "已添加 " + chooser.getSelectedFiles().length + " 个文件");
                }
            });
        }

        /** 刷新左侧文件列表 */
        private void refreshFileList() {
            fileListPanel.removeAll();
            if (uploadFiles.isEmpty()) {
                // 空列表提示
                JLabel emptyLabel = new JLabel("拖拽文件到此处或点击\"+ 添加文件\"", SwingConstants.CENTER);
                emptyLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
                emptyLabel.setForeground(new Color(0x666666));
                emptyLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
                fileListPanel.add(Box.createVerticalGlue());
                fileListPanel.add(emptyLabel);
                fileListPanel.add(Box.createVerticalGlue());
            } else {
                for (int i = 0; i < uploadFiles.size(); i++) {
                    File f = uploadFiles.get(i);
                    fileListPanel.add(createFileItem(f, i));
                }
            }
            fileListPanel.revalidate();
            fileListPanel.repaint();
            // 清除右侧预览
            clearPreview();
        }

        /** 清除右侧预览面板 */
        private void clearPreview() {
            previewFileIndex = -1;
            filePreviewLabel.setIcon(null);
            filePreviewLabel.setText("点击文件列表中的 👁 预览");
            filePreviewArea.setText("");
            CardLayout cl = (CardLayout) filePreviewCardPanel.getLayout();
            cl.show(filePreviewCardPanel, "image");
        }

        private String truncate(String s, int maxLen) {
            return s.length() <= maxLen ? s : s.substring(0, maxLen) + "...";
        }

        /** 创建单个文件条目 */
        private JPanel createFileItem(File file, int index) {
            // 用 GridBagLayout 精确控制每行：文件名 | 大小 | 👁 | ×
            JPanel item = new JPanel(new GridBagLayout());
            item.setBackground(new Color(0x2D2D30));
            item.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createMatteBorder(0, 0, 1, 0, new Color(0x3E3E42)),
                    BorderFactory.createEmptyBorder(4, 8, 4, 4)));
            // 固定行高，让 BoxLayout 能正确排列
            item.setMaximumSize(new Dimension(Short.MAX_VALUE, 34));
            item.setPreferredSize(new Dimension(200, 34));

            // 文件名 + 大小
            long size = file.length();
            String sizeStr = size < 1024 ? size + " B" :
                    size < 1024 * 1024 ? String.format("%.1f KB", size / 1024.0) :
                            String.format("%.2f MB", size / (1024.0 * 1024.0));

            JLabel nameLabel = new JLabel(file.getName());
            nameLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            nameLabel.setForeground(new Color(0xCCCCCC));

            JLabel sizeLabel = new JLabel(sizeStr);
            sizeLabel.setFont(new Font("Consolas", Font.PLAIN, 10));
            sizeLabel.setForeground(new Color(0x888888));

            // 小眼睛预览按钮
            JButton eyeBtn = new JButton("\uD83D\uDC41"); // 👁
            eyeBtn.setFont(new Font("Segoe UI Emoji", Font.PLAIN, 14));
            eyeBtn.setToolTipText("预览文件内容");
            eyeBtn.setBorderPainted(false);
            eyeBtn.setContentAreaFilled(false);
            eyeBtn.setFocusPainted(false);
            eyeBtn.setMargin(new Insets(0, 0, 0, 0));
            eyeBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            final int idx = index;
            eyeBtn.addActionListener(e -> previewFile(idx));

            // 删除按钮
            JButton delBtn = new JButton("X");
            delBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
            delBtn.setToolTipText("移除文件");
            delBtn.setBorderPainted(false);
            delBtn.setContentAreaFilled(false);
            delBtn.setFocusPainted(false);
            delBtn.setForeground(new Color(0xCC6666));
            delBtn.setMargin(new Insets(0, 0, 0, 0));
            delBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            delBtn.addActionListener(e -> {
                String removedName = uploadFiles.get(idx).getName();
                uploadFiles.remove(idx);
                refreshFileList();
                logSys(logPane, "已移除文件: " + removedName);
            });

            // GridBagLayout 精确排列
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.fill = GridBagConstraints.HORIZONTAL;
            gbc.anchor = GridBagConstraints.WEST;
            gbc.insets = new Insets(0, 0, 0, 4);

            gbc.weightx = 1.0; gbc.gridx = 0; // 文件名 占据剩余空间
            item.add(nameLabel, gbc);

            gbc.weightx = 0.0; gbc.gridx = 1; // 文件大小
            gbc.insets = new Insets(0, 8, 0, 8);
            item.add(sizeLabel, gbc);

            gbc.gridx = 2; gbc.insets = new Insets(0, 2, 0, 0);
            item.add(eyeBtn, gbc);

            gbc.gridx = 3; gbc.insets = new Insets(0, 0, 0, 0);
            item.add(delBtn, gbc);

            // 点击整行也可预览
            item.addMouseListener(new java.awt.event.MouseAdapter() {
                @Override
                public void mouseClicked(java.awt.event.MouseEvent e) {
                    if (e.getClickCount() == 1) previewFile(idx);
                }
            });

            return item;
        }

        /** 预览文件内容 */
        private void previewFile(int index) {
            if (index < 0 || index >= uploadFiles.size()) return;
            previewFileIndex = index;
            File file = uploadFiles.get(index);
            String name = file.getName().toLowerCase();
            CardLayout cl = (CardLayout) filePreviewCardPanel.getLayout();

            // 判断是否为图片
            if (name.endsWith(".png") || name.endsWith(".jpg") || name.endsWith(".jpeg")
                    || name.endsWith(".gif") || name.endsWith(".bmp") || name.endsWith(".webp")) {
                try {
                    BufferedImage img = ImageIO.read(file);
                    if (img != null) {
                        // 缩放适应面板
                        int maxW = filePreviewCardPanel.getWidth() - 20;
                        int maxH = filePreviewCardPanel.getHeight() - 20;
                        if (maxW <= 0) maxW = 400;
                        if (maxH <= 0) maxH = 300;
                        double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
                        if (scale < 1.0) {
                            int newW = (int) (img.getWidth() * scale);
                            int newH = (int) (img.getHeight() * scale);
                            BufferedImage scaledImg = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
                            Graphics2D g2d = scaledImg.createGraphics();
                            g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                            g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                            g2d.drawImage(img, 0, 0, newW, newH, null);
                            g2d.dispose();
                            filePreviewLabel.setIcon(new ImageIcon(scaledImg));
                        } else {
                            filePreviewLabel.setIcon(new ImageIcon(img));
                        }
                        filePreviewLabel.setText(null);
                        cl.show(filePreviewCardPanel, "image");
                        return;
                    }
                } catch (Exception ex) {
                    // 图片读取失败，回退到文本模式
                }
            }

            // 文本预览
            try {
                String content = Files.readString(file.toPath());
                // 限制预览大小
                if (content.length() > 500_000) {
                    content = content.substring(0, 500_000) + "\n\n... (内容过长，已截断)";
                }
                filePreviewArea.setText(content);
                filePreviewArea.setCaretPosition(0);
                if (name.endsWith(".json")) {
                    filePreviewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_JSON);
                } else if (name.endsWith(".xml") || name.endsWith(".html")) {
                    filePreviewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_XML);
                } else {
                    filePreviewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                }
                cl.show(filePreviewCardPanel, "text");
            } catch (IOException ex) {
                filePreviewArea.setText("无法预览: " + ex.getMessage());
                filePreviewArea.setSyntaxEditingStyle(SyntaxConstants.SYNTAX_STYLE_NONE);
                cl.show(filePreviewCardPanel, "text");
            }
        }

        /** 构建 multipart/form-data BodyPublisher */
        private HttpRequest.BodyPublisher buildMultipartPublisher(List<File> files, String boundary) {
            return HttpRequest.BodyPublishers.ofByteArrays(() -> {
                List<byte[]> parts = new ArrayList<>();
                try {
                    for (File file : files) {
                        String header = "--" + boundary + "\r\n"
                                + "Content-Disposition: form-data; name=\"files\"; filename=\"" + file.getName() + "\"\r\n"
                                + "Content-Type: " + (Files.probeContentType(file.toPath()) != null
                                        ? Files.probeContentType(file.toPath()) : "application/octet-stream") + "\r\n"
                                + "\r\n";
                        parts.add(header.getBytes(StandardCharsets.UTF_8));
                        parts.add(Files.readAllBytes(file.toPath()));
                        parts.add("\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                    parts.add(("--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
                } catch (IOException ex) {
                    logWarn(logPane, "构建 multipart 失败: " + ex.getMessage());
                }
                return parts.stream().iterator();
            });
        }

        /** 解析 key=value 或 key: value 格式文本为 Map（用于 Form 模式的 Body） */
        private Map<String, String> parseKeyValue(String text) {
            Map<String, String> map = new LinkedHashMap<>();
            if (text == null || text.trim().isEmpty()) return map;
            for (String line : text.split("\\n")) {
                line = line.trim();
                if (line.isEmpty()) continue;
                int idx = line.indexOf(": ");
                if (idx < 0) idx = line.indexOf('=');
                if (idx > 0) {
                    String k = line.substring(0, idx).trim();
                    String v = line.substring(idx + (line.charAt(idx) == ':' ? 2 : 1)).trim();
                    if (!k.isEmpty()) map.put(k, v);
                }
            }
            return map;
        }

        private void addHistory(String url) {
            history.remove(url);
            history.addFirst(url);
            while (history.size() > MAX_HISTORY) history.removeLast();
        }
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadField(httpPanel.urlField, config, "http.url");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveField(httpPanel.urlField, config, "http.url");
    }
}
