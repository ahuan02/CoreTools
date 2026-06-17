package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.ui.MainFrame;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.text.*;
import java.awt.*;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

import static com.szh.utils.NetUtil.*;

/**
 * 日志查看 / 分析工具：分段加载超大文件、关键词过滤高亮、搜索跳转、导出、级别筛选
 */
public class LogViewerPanel extends AbstractCommandPanel {

    // ===== 工具栏控件 =====
    private JTextField pathField;
    private JButton btnOpen, btnRefresh, btnExport, btnClear;
    private JComboBox<String> encCombo;
    private JLabel fileInfoLabel;

    // ===== 过滤控件 =====
    private JTextField filterField;
    private JButton btnFilter;
    private JCheckBox caseBox, regexBox, highlightBox;
    private JComboBox<String> levelCombo;

    // ===== 搜索跳转 =====
    private JTextField searchField;
    private JButton btnSearch, btnPrev, btnNext;
    private JLabel searchStatusLabel;
    private JTextField gotoLineField;
    private JButton btnGotoLine;

    // ===== 日志展示 =====
    private JTextPane logPane;
    private JScrollPane logScroll;
    private JLabel statusLabel;

    // ===== 分段加载 =====
    private static final int CHUNK_LINES = 5000;          // 每次加载 5000 行
    private static final int MAX_DISPLAY_LINES = 100000;  // 最多展示 10 万行
    private File currentFile;
    private long fileSize;
    private int totalLinesEstimate;
    private boolean loadingMore;
    private int loadedChunks;

    // ===== 原始行缓存（用于过滤） =====
    private final List<String> rawLines = new ArrayList<>();

    // ===== 搜索状态 =====
    private int lastSearchPos = -1;
    private final List<Integer> searchResults = new ArrayList<>();

    public LogViewerPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));

        // ---- 顶部工具栏 ----
        JPanel topPanel = new JPanel(new BorderLayout(4, 0));

        // 文件选择行
        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fileRow.add(new JLabel("文件:"));
        pathField = new JTextField(35);
        pathField.setFont(FONT_TEXT);
        fileRow.add(pathField);

        btnOpen = makeBtn("打开", new Color(0x42A5F5));
        btnOpen.addActionListener(e -> openFile());
        fileRow.add(btnOpen);

        btnRefresh = makeBtn("刷新", new Color(0x66BB6A));
        btnRefresh.setEnabled(false);
        btnRefresh.addActionListener(e -> refreshFile());
        fileRow.add(btnRefresh);

        btnExport = makeBtn("导出", new Color(0xAB47BC));
        btnExport.setEnabled(false);
        btnExport.addActionListener(e -> exportLog());
        fileRow.add(btnExport);

        btnClear = makeBtn("清屏", new Color(0xE57373));
        btnClear.addActionListener(e -> clearLog());
        fileRow.add(btnClear);

        encCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ISO-8859-1"});
        encCombo.setFont(FONT_TEXT);
        fileRow.add(new JLabel("编码:"));
        fileRow.add(encCombo);

        fileInfoLabel = new JLabel("  未打开文件");
        fileInfoLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        fileInfoLabel.setForeground(new Color(0x888888));
        fileRow.add(fileInfoLabel);

        topPanel.add(fileRow, BorderLayout.NORTH);

        // 过滤 + 搜索行
        JPanel toolRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));

        toolRow.add(new JLabel("过滤:"));
        filterField = new JTextField(16);
        filterField.setFont(FONT_TEXT);
        filterField.setToolTipText("输入关键词过滤，支持正则表达式");
        filterField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void removeUpdate(DocumentEvent e) { applyFilter(); }
            @Override public void changedUpdate(DocumentEvent e) { applyFilter(); }
        });
        toolRow.add(filterField);

        caseBox = new JCheckBox("区分大小写");
        caseBox.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        caseBox.addActionListener(e -> applyFilter());
        toolRow.add(caseBox);

        regexBox = new JCheckBox("正则");
        regexBox.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        regexBox.addActionListener(e -> applyFilter());
        toolRow.add(regexBox);

        highlightBox = new JCheckBox("高亮");
        highlightBox.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        highlightBox.setSelected(true);
        highlightBox.addActionListener(e -> applyFilter());
        toolRow.add(highlightBox);

        toolRow.add(new JLabel("级别:"));
        levelCombo = new JComboBox<>(new String[]{"全部", "DEBUG", "INFO", "WARN", "ERROR", "FATAL"});
        levelCombo.setFont(FONT_TEXT);
        levelCombo.addActionListener(e -> applyFilter());
        toolRow.add(levelCombo);

        toolRow.add(new JSeparator(SwingConstants.VERTICAL));

        toolRow.add(new JLabel("搜索:"));
        searchField = new JTextField(12);
        searchField.setFont(FONT_TEXT);
        searchField.setToolTipText("在已显示内容中搜索，实时高亮");
        searchField.getDocument().addDocumentListener(new DocumentListener() {
            @Override public void insertUpdate(DocumentEvent e) { onSearchTextChanged(); }
            @Override public void removeUpdate(DocumentEvent e) { onSearchTextChanged(); }
            @Override public void changedUpdate(DocumentEvent e) { onSearchTextChanged(); }
        });
        searchField.addActionListener(e -> searchNext());
        toolRow.add(searchField);

        btnSearch = makeBtn("搜索", new Color(0x42A5F5));
        btnSearch.addActionListener(e -> searchNext());
        toolRow.add(btnSearch);

        btnPrev = makeBtn("↑", new Color(0x78909C));
        btnPrev.setToolTipText("上一个匹配");
        btnPrev.addActionListener(e -> searchPrev());
        toolRow.add(btnPrev);

        btnNext = makeBtn("↓", new Color(0x78909C));
        btnNext.setToolTipText("下一个匹配");
        btnNext.addActionListener(e -> searchNext());
        toolRow.add(btnNext);

        searchStatusLabel = new JLabel("");
        searchStatusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        searchStatusLabel.setForeground(new Color(0x81C784));
        toolRow.add(searchStatusLabel);

        toolRow.add(new JSeparator(SwingConstants.VERTICAL));

        toolRow.add(new JLabel("跳转到行:"));
        gotoLineField = new JTextField(6);
        gotoLineField.setFont(FONT_TEXT);
        gotoLineField.addActionListener(e -> gotoLine());
        toolRow.add(gotoLineField);

        btnGotoLine = makeBtn("跳转", new Color(0x78909C));
        btnGotoLine.addActionListener(e -> gotoLine());
        toolRow.add(btnGotoLine);

        topPanel.add(toolRow, BorderLayout.SOUTH);
        add(topPanel, BorderLayout.NORTH);

        // ---- 日志展示区 ----
        logPane = new JTextPane();
        logPane.setEditable(false);
        logPane.setBackground(new Color(0x1E1E1E));
        logPane.setCaretColor(new Color(0xD4D4D4));
        logPane.setFont(FONT_TEXT);

        logScroll = new JScrollPane(logPane);
        logScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "日志内容", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        logScroll.getVerticalScrollBar().setUnitIncrement(16);
        MainFrame.enableSmoothScrolling(logScroll);

        // 右键菜单
        JPopupMenu popup = new JPopupMenu();
        JMenuItem clearItem = new JMenuItem("清空日志");
        clearItem.addActionListener(e -> clearLog());
        popup.add(clearItem);
        JMenuItem copyItem = new JMenuItem("复制选中");
        copyItem.addActionListener(e -> logPane.copy());
        popup.add(copyItem);
        JMenuItem copyAllItem = new JMenuItem("复制全部");
        copyAllItem.addActionListener(e -> {
            logPane.selectAll();
            logPane.copy();
        });
        popup.add(copyAllItem);
        logPane.setComponentPopupMenu(popup);

        // 滚动到底部自动加载更多
        logScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) return;
            JScrollBar bar = logScroll.getVerticalScrollBar();
            if (bar.getValue() + bar.getVisibleAmount() >= bar.getMaximum() - 50) {
                loadMoreChunk();
            }
        });

        add(logScroll, BorderLayout.CENTER);

        // ---- 底部状态栏 ----
        statusLabel = new JLabel(" 就绪");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(statusLabel, BorderLayout.SOUTH);
    }

    // ==================== 文件操作 ====================

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择日志文件");
        chooser.setFileFilter(new javax.swing.filechooser.FileFilter() {
            @Override
            public boolean accept(File f) {
                return f.isDirectory() || f.getName().endsWith(".log")
                        || f.getName().endsWith(".txt") || f.getName().endsWith(".out");
            }
            @Override
            public String getDescription() { return "日志文件 (*.log, *.txt, *.out)"; }
        });

        // 尝试从 pathField 获取目录
        String currentPath = pathField.getText().trim();
        if (!currentPath.isEmpty()) {
            File f = new File(currentPath);
            if (f.exists()) {
                chooser.setCurrentDirectory(f.isDirectory() ? f : f.getParentFile());
            }
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            pathField.setText(f.getAbsolutePath());
            loadFile(f);
        }
    }

    private void loadFile(File f) {
        currentFile = f;
        fileSize = f.length();
        loadedChunks = 0;
        loadingMore = false;
        rawLines.clear();
        logPane.setText("");

        // 估算总行数（采样前 64KB）
        totalLinesEstimate = estimateTotalLines(f);

        try {
            loadChunk(f, 0, CHUNK_LINES);
            loadedChunks = 1;
            updateFileInfo();
            applyFilter();
            btnRefresh.setEnabled(true);
            btnExport.setEnabled(true);
            setStatus("已加载: " + f.getName() + " (" + formatSize(fileSize) + ")");
        } catch (Exception e) {
            setStatus("打开失败: " + e.getMessage());
            logPane.setText("[错误] 无法打开文件: " + e.getMessage());
        }
    }

    private void loadMoreChunk() {
        if (currentFile == null || loadingMore) return;
        if (rawLines.size() >= MAX_DISPLAY_LINES) {
            setStatus("已达最大显示行数 (" + MAX_DISPLAY_LINES + ")，不再加载更多");
            return;
        }

        loadingMore = true;
        new SwingWorker<Void, Void>() {
            @Override
            protected Void doInBackground() {
                try {
                    int startLine = loadedChunks * CHUNK_LINES;
                    loadChunk(currentFile, startLine, CHUNK_LINES);
                    loadedChunks++;
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> setStatus("加载失败: " + e.getMessage()));
                }
                return null;
            }

            @Override
            protected void done() {
                loadingMore = false;
                SwingUtilities.invokeLater(() -> {
                    applyFilter();
                    updateFileInfo();
                });
            }
        }.execute();
    }

    private void loadChunk(File f, int skipLines, int maxLines) throws IOException {
        String enc = (String) encCombo.getSelectedItem();
        Charset charset;
        try {
            charset = Charset.forName(enc);
        } catch (Exception e) {
            charset = StandardCharsets.UTF_8;
        }

        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), charset))) {

            String line;
            int lineNum = 0;
            int added = 0;

            while ((line = reader.readLine()) != null) {
                if (lineNum >= skipLines) {
                    rawLines.add(line);
                    added++;
                    if (added >= maxLines) break;
                }
                lineNum++;
            }
        }
    }

    private void refreshFile() {
        if (currentFile != null && currentFile.exists()) {
            loadFile(currentFile);
        }
    }

    private void clearLog() {
        rawLines.clear();
        logPane.setText("");
        searchResults.clear();
        lastSearchPos = -1;
        searchStatusLabel.setText("");
        loadedChunks = 0;
        currentFile = null;
        fileSize = 0;
        totalLinesEstimate = 0;
        pathField.setText("");
        fileInfoLabel.setText("  未打开文件");
        btnRefresh.setEnabled(false);
        btnExport.setEnabled(false);
        setStatus("已清空");
    }

    private void exportLog() {
        if (rawLines.isEmpty()) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出日志");
        chooser.setSelectedFile(new File("exported_log.txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try (BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(new FileOutputStream(f), StandardCharsets.UTF_8))) {
                for (String line : rawLines) {
                    writer.write(line);
                    writer.newLine();
                }
                setStatus("已导出到: " + f.getAbsolutePath());
            } catch (IOException e) {
                setStatus("导出失败: " + e.getMessage());
            }
        }
    }

    // ==================== 过滤与展示 ====================

    private void applyFilter() {
        if (rawLines.isEmpty()) return;

        String filterText = filterField.getText();
        String levelFilter = (String) levelCombo.getSelectedItem();
        boolean caseSensitive = caseBox.isSelected();
        boolean isRegex = regexBox.isSelected();
        boolean doHighlight = highlightBox.isSelected();

        // 编译过滤条件
        Pattern pattern = null;
        if (filterText != null && !filterText.isEmpty()) {
            try {
                int flags = isRegex ? 0 : Pattern.LITERAL;
                if (!caseSensitive) flags |= Pattern.CASE_INSENSITIVE;
                pattern = Pattern.compile(filterText, flags);
            } catch (PatternSyntaxException e) {
                setStatus("正则表达式错误: " + e.getMessage());
                return;
            }
        }

        // 级别关键词列表
        boolean filterByLevel = !"全部".equals(levelFilter);
        String levelKeyword = levelFilter;

        // 过滤行
        List<Integer> matchedIndices = new ArrayList<>();
        for (int i = 0; i < rawLines.size(); i++) {
            String line = rawLines.get(i);

            // 级别筛选
            if (filterByLevel) {
                if (!line.contains(levelKeyword)) continue;
            }

            // 关键词筛选
            if (pattern != null) {
                if (!pattern.matcher(line).find()) continue;
            }

            matchedIndices.add(i);
        }

        // 渲染到 JTextPane
        renderLines(matchedIndices, pattern, doHighlight);

        // 重置搜索
        searchResults.clear();
        lastSearchPos = -1;
        searchStatusLabel.setText("");

        int shown = matchedIndices.size();
        int total = rawLines.size();
        if (pattern != null || filterByLevel) {
            setStatus("过滤后: " + shown + " / " + total + " 行");
        } else {
            setStatus("已显示: " + shown + " 行");
        }
    }

    private void renderLines(List<Integer> lineIndices, Pattern highlightPattern, boolean doHighlight) {
        logPane.setText("");
        if (lineIndices.isEmpty()) {
            try {
                StyledDocument doc = logPane.getStyledDocument();
                Style s = logPane.addStyle("empty", null);
                StyleConstants.setForeground(s, new Color(0x888888));
                doc.insertString(0, "（无匹配结果）", s);
            } catch (BadLocationException ignored) {}
            return;
        }

        StringBuilder sb = new StringBuilder();
        for (int idx : lineIndices) {
            sb.append(rawLines.get(idx)).append("\n");
        }

        logPane.setText(sb.toString());

        // 高亮关键词
        if (doHighlight && highlightPattern != null) {
            highlightKeywords(highlightPattern);
        }

        // 级别着色
        applyLevelColoring();
    }

    private void highlightKeywords(Pattern pattern) {
        String text = logPane.getText();
        java.util.regex.Matcher matcher = pattern.matcher(text);

        StyledDocument doc = logPane.getStyledDocument();
        Style highlightStyle = logPane.addStyle("highlight", null);
        StyleConstants.setBackground(highlightStyle, new Color(0xFFB74D));
        StyleConstants.setForeground(highlightStyle, new Color(0x1E1E1E));
        StyleConstants.setBold(highlightStyle, true);

        while (matcher.find()) {
            int start = matcher.start();
            int end = matcher.end();
            doc.setCharacterAttributes(start, end - start, highlightStyle, false);
        }
    }

    private void applyLevelColoring() {
        String text = logPane.getText();
        StyledDocument doc = logPane.getStyledDocument();

        // 按行着色级别标记
        String[] lines = text.split("\n", -1);
        int pos = 0;
        for (String line : lines) {
            int len = line.length();
            Color color = getLevelColor(line);
            if (color != null) {
                SimpleAttributeSet attrs = new SimpleAttributeSet();
                StyleConstants.setForeground(attrs, color);
                doc.setCharacterAttributes(pos, len, attrs, false);
            }
            pos += len + 1; // +1 for \n
        }
    }

    private Color getLevelColor(String line) {
        if (line.contains("ERROR") || line.contains("error") || line.contains("FATAL") || line.contains("fatal"))
            return new Color(0xE57373);
        if (line.contains("WARN") || line.contains("warn"))
            return new Color(0xFFB74D);
        if (line.contains("INFO") || line.contains("info"))
            return new Color(0x81C784);
        if (line.contains("DEBUG") || line.contains("debug"))
            return new Color(0x64B5F6);
        if (line.contains("TRACE") || line.contains("trace"))
            return new Color(0x888888);
        return null;
    }

    // ==================== 搜索 ====================

    private String lastSearchText = "";
    private Object lastSearchHighlightTag = null;

    /** 搜索文本变化时：实时高亮所有匹配 + 跳转到第一个 */
    private void onSearchTextChanged() {
        // 先清除上一次的搜索高亮
        clearSearchHighlights();

        String searchText = searchField.getText();
        if (searchText.isEmpty()) {
            searchResults.clear();
            lastSearchPos = -1;
            lastSearchText = "";
            searchStatusLabel.setText("");
            return;
        }

        String content = logPane.getText();
        if (content.isEmpty()) return;

        // 重建搜索结果列表
        searchResults.clear();
        lastSearchPos = -1;
        lastSearchText = searchText;

        boolean caseSensitive = caseBox.isSelected();
        String searchFor = caseSensitive ? searchText : searchText.toLowerCase();
        String searchIn = caseSensitive ? content : content.toLowerCase();

        int idx = 0;
        while ((idx = searchIn.indexOf(searchFor, idx)) >= 0) {
            searchResults.add(idx);
            idx++;
        }

        if (searchResults.isEmpty()) {
            searchStatusLabel.setText("0/0");
            searchStatusLabel.setForeground(new Color(0xE57373));
            return;
        }

        // 高亮所有匹配项
        highlightAllSearchMatches(searchText, searchResults);

        // 跳转到第一个匹配
        lastSearchPos = 0;
        int targetPos = searchResults.get(0);
        logPane.setCaretPosition(targetPos);
        logPane.moveCaretPosition(targetPos + searchText.length());
        logPane.requestFocusInWindow();

        searchStatusLabel.setText("1/" + searchResults.size());
        searchStatusLabel.setForeground(new Color(0x81C784));
    }

    /** 高亮所有搜索匹配项（用 JTextPane 的 Highlighter） */
    private void highlightAllSearchMatches(String searchText, List<Integer> positions) {
        Highlighter hl = logPane.getHighlighter();
        Highlighter.HighlightPainter painter = new DefaultHighlighter.DefaultHighlightPainter(
                new Color(0xFF7043));  // 橙色半透明高亮

        for (int pos : positions) {
            try {
                hl.addHighlight(pos, pos + searchText.length(), painter);
            } catch (BadLocationException ignored) {}
        }
    }

    /** 清除所有搜索高亮 */
    private void clearSearchHighlights() {
        Highlighter hl = logPane.getHighlighter();
        for (Highlighter.Highlight h : hl.getHighlights()) {
            hl.removeHighlight(h);
        }
    }

    private void doSearch(boolean forward) {
        String searchText = searchField.getText();
        if (searchText.isEmpty() || searchResults.isEmpty()) return;

        // 移到下一个/上一个
        if (forward) {
            lastSearchPos++;
            if (lastSearchPos >= searchResults.size()) lastSearchPos = 0;
        } else {
            lastSearchPos--;
            if (lastSearchPos < 0) lastSearchPos = searchResults.size() - 1;
        }

        int targetPos = searchResults.get(lastSearchPos);
        logPane.setCaretPosition(targetPos);
        logPane.moveCaretPosition(targetPos + searchText.length());
        logPane.requestFocusInWindow();

        searchStatusLabel.setText((lastSearchPos + 1) + "/" + searchResults.size());
        searchStatusLabel.setForeground(new Color(0x81C784));
    }

    private void searchNext() {
        doSearch(true);
    }

    private void searchPrev() {
        doSearch(false);
    }

    private void gotoLine() {
        try {
            int line = Integer.parseInt(gotoLineField.getText().trim());
            if (line < 1) return;

            String text = logPane.getText();
            String[] lines = text.split("\n", -1);
            if (line > lines.length) {
                setStatus("行号超出范围 (最大: " + lines.length + ")");
                return;
            }

            // 计算该行的偏移位置
            int pos = 0;
            for (int i = 0; i < line - 1; i++) {
                pos += lines[i].length() + 1;
            }

            logPane.setCaretPosition(pos);
            logPane.requestFocusInWindow();
            setStatus("跳转到第 " + line + " 行");
        } catch (NumberFormatException e) {
            setStatus("请输入有效的行号");
        }
    }

    // ==================== 辅助方法 ====================

    private int estimateTotalLines(File f) {
        try (BufferedReader reader = new BufferedReader(
                new InputStreamReader(new FileInputStream(f), StandardCharsets.UTF_8), 65536)) {
            long sampleBytes = 0;
            int sampleLines = 0;
            String line;
            while ((line = reader.readLine()) != null && sampleBytes < 65536) {
                sampleLines++;
                sampleBytes += line.length() + 1;
            }
            if (sampleLines > 0 && sampleBytes > 0) {
                return (int) (fileSize / (sampleBytes / sampleLines));
            }
        } catch (IOException ignored) {}
        return 0;
    }

    private void updateFileInfo() {
        if (currentFile == null) {
            fileInfoLabel.setText("  未打开文件");
            return;
        }
        String loaded = loadedChunks > 0 ? " 已加载约 " + (loadedChunks * CHUNK_LINES) + " 行" : "";
        String info = String.format("  %s (%s) | 估算 %d 行%s",
                currentFile.getName(), formatSize(fileSize), totalLinesEstimate, loaded);
        fileInfoLabel.setText(info);
    }

    private String formatSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.2f GB", bytes / (1024.0 * 1024 * 1024));
    }

    private void setStatus(String msg) {
        statusLabel.setText(" " + msg);
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadCombo(encCombo, config, "logviewer.enc");
        loadCombo(levelCombo, config, "logviewer.level");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveCombo(encCombo, config, "logviewer.enc");
        saveCombo(levelCombo, config, "logviewer.level");
    }
}
