package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStreamReader;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;

/**
 * 文件批量处理工具：批量重命名、编码转换、文本内容批量替换
 */
public class BatchFilePanel extends AbstractCommandPanel {

    private static final Font FONT = NetUtil.FONT_TEXT;
    private static final String[] ENCODINGS = {"UTF-8", "GBK", "GB2312", "GB18030", "ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-16LE", "US-ASCII", "windows-1252"};
    private static final String[] RENAME_MODES = {"查找替换", "插入文本", "删除文本", "序号重命名", "正则替换", "大小写转换", "添加前后缀"};

    // ===== 文件列表 =====
    private DefaultListModel<File> fileListModel;
    private JList<File> fileList;
    private JLabel fileCountLabel;

    // ===== 重命名面板控件 =====
    private JPanel renameParamPanel;
    private JComboBox<String> renameModeCombo;
    private JTextField renameFindField, renameReplaceField;
    private JTextField renameInsertTextField, renameInsertPosField;
    private JTextField renameDeleteStartField, renameDeleteEndField;
    private JTextField renameSeqPrefixField, renameSeqStartField, renameSeqDigitsField;
    private JTextField renameRegexFindField, renameRegexReplaceField;
    private JComboBox<String> renameCaseCombo;
    private JTextField renamePrefixField, renameSuffixField;
    private JCheckBox renameIncludeExtBox;
    private JTextArea renamePreviewArea;
    private JButton btnRenamePreview, btnRenameExecute;

    // ===== 编码转换面板控件 =====
    private JComboBox<String> srcEncodingCombo, dstEncodingCombo;
    private JTextField encOutputDirField;
    private JCheckBox encBackupBox;
    private JTextArea encLogArea;
    private JButton btnEncConvert;

    // ===== 内容替换面板控件 =====
    private JTextField replaceFindField, replaceToField;
    private JCheckBox replaceRegexBox, replaceCaseBox, replaceBackupBox;
    private JComboBox<String> replaceEncodingCombo;
    private JTextArea replacePreviewArea, replaceLogArea;
    private JButton btnReplacePreview, btnReplaceExecute;

    // ===== 文件操作面板控件 =====
    private JTextField fileOpsDirField, fileOpsNameField, fileOpsListFileField;
    private JTextField fileOpsBatchPrefixField, fileOpsBatchStartField, fileOpsBatchCountField;
    private JTextField fileOpsBatchDigitsField, fileOpsBatchExtField;
    private JTextArea fileOpsLogArea;

    public BatchFilePanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        JTabbedPane tabbedPane = new JTabbedPane(JTabbedPane.TOP);

        tabbedPane.addTab("批量重命名", buildRenamePanel());
        tabbedPane.addTab("编码转换", buildEncodingPanel());
        tabbedPane.addTab("内容替换", buildReplacePanel());
        tabbedPane.addTab("文件操作", buildFileOpsPanel());

        // 左侧：文件选择区域 + 列表
        JPanel leftPanel = new JPanel(new BorderLayout(0, 6));
        leftPanel.setPreferredSize(new Dimension(340, 100));

        leftPanel.add(buildFileSelector(), BorderLayout.NORTH);
        leftPanel.add(buildFileList(), BorderLayout.CENTER);

        // 右侧：功能面板
        JPanel rightPanel = new JPanel(new BorderLayout());
        rightPanel.add(tabbedPane, BorderLayout.CENTER);

        JSplitPane splitPane = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
        splitPane.setResizeWeight(0.3);
        splitPane.setDividerLocation(340);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        add(splitPane, BorderLayout.CENTER);
    }

    // ==================== 文件选择器 ====================

    private JPanel buildFileSelector() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "文件选择", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 4));
        JButton btnAddFiles = NetUtil.makeBtn("添加文件", null);
        JButton btnAddDir = NetUtil.makeBtn("添加目录", null);
        JButton btnClearList = NetUtil.makeBtn("清空列表", null);
        btnRow.add(btnAddFiles);
        btnRow.add(btnAddDir);
        btnRow.add(btnClearList);

        fileCountLabel = new JLabel("共 0 个文件");
        fileCountLabel.setFont(FONT);

        JPanel bottomRow = new JPanel(new BorderLayout());
        bottomRow.add(btnRow, BorderLayout.CENTER);
        bottomRow.add(fileCountLabel, BorderLayout.EAST);

        panel.add(bottomRow, BorderLayout.CENTER);

        btnAddFiles.addActionListener(e -> addFiles());
        btnAddDir.addActionListener(e -> addDirectory());
        btnClearList.addActionListener(e -> clearFileList());

        return panel;
    }

    private void addFiles() {
        JFileChooser chooser = new JFileChooser();
        chooser.setMultiSelectionEnabled(true);
        chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            for (File f : chooser.getSelectedFiles()) {
                addFileIfAbsent(f);
            }
            updateFileCount();
        }
    }

    private void addDirectory() {
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File dir = chooser.getSelectedFile();
            JCheckBox recursiveBox = new JCheckBox("递归子目录", true);
            JCheckBox extFilterBox = new JCheckBox("按扩展名过滤:", false);
            JTextField extField = new JTextField(".txt,.log,.java,.xml", 15);
            extField.setEnabled(false);
            extFilterBox.addActionListener(e -> extField.setEnabled(extFilterBox.isSelected()));

            JPanel opts = new JPanel(new GridLayout(3, 1, 4, 4));
            opts.add(recursiveBox);
            JPanel extRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
            extRow.add(extFilterBox);
            extRow.add(extField);
            opts.add(extRow);

            int result = JOptionPane.showConfirmDialog(this, opts, "添加目录选项", JOptionPane.OK_CANCEL_OPTION);
            if (result == JOptionPane.OK_OPTION) {
                String[] exts = null;
                if (extFilterBox.isSelected() && !extField.getText().trim().isEmpty()) {
                    exts = extField.getText().trim().toLowerCase().split("\\s*,\\s*");
                }
                addFilesFromDir(dir, recursiveBox.isSelected(), exts);
                updateFileCount();
            }
        }
    }

    private void addFilesFromDir(File dir, boolean recursive, String[] exts) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory() && recursive) {
                addFilesFromDir(f, true, exts);
            } else if (f.isFile()) {
                if (exts == null || exts.length == 0) {
                    addFileIfAbsent(f);
                } else {
                    String name = f.getName().toLowerCase();
                    for (String ext : exts) {
                        if (name.endsWith(ext)) {
                            addFileIfAbsent(f);
                            break;
                        }
                    }
                }
            }
        }
    }

    private void addFileIfAbsent(File f) {
        for (int i = 0; i < fileListModel.size(); i++) {
            if (fileListModel.get(i).getAbsolutePath().equals(f.getAbsolutePath())) return;
        }
        fileListModel.addElement(f);
    }

    private void clearFileList() {
        fileListModel.clear();
        updateFileCount();
    }

    private void updateFileCount() {
        fileCountLabel.setText("共 " + fileListModel.size() + " 个文件");
    }

    // ==================== 文件列表 ====================

    private JPanel buildFileList() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "文件列表（右键移除）", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        fileListModel = new DefaultListModel<>();
        fileList = new JList<>(fileListModel);
        fileList.setFont(FONT);
        fileList.setCellRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                                                          int index, boolean isSelected, boolean cellHasFocus) {
                Component c = super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                if (value instanceof File) {
                    File f = (File) value;
                    setText(f.getName() + "  —  " + f.getParent());
                    setToolTipText(f.getAbsolutePath());
                }
                return c;
            }
        });

        // 右键移除
        JPopupMenu popup = new JPopupMenu();
        JMenuItem removeItem = new JMenuItem("移除选中");
        removeItem.addActionListener(e -> {
            int[] indices = fileList.getSelectedIndices();
            for (int i = indices.length - 1; i >= 0; i--) {
                fileListModel.remove(indices[i]);
            }
            updateFileCount();
        });
        popup.add(removeItem);
        fileList.setComponentPopupMenu(popup);

        // 拖拽添加文件
        fileList.setDropMode(DropMode.INSERT);
        fileList.setTransferHandler(new TransferHandler() {
            @Override
            public boolean canImport(TransferSupport support) {
                return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor);
            }
            @Override
            @SuppressWarnings("unchecked")
            public boolean importData(TransferSupport support) {
                try {
                    List<File> files = (List<File>) support.getTransferable().getTransferData(DataFlavor.javaFileListFlavor);
                    for (File f : files) {
                        if (f.isDirectory()) {
                            addFilesFromDir(f, true, null);
                        } else {
                            addFileIfAbsent(f);
                        }
                    }
                    updateFileCount();
                    return true;
                } catch (Exception ex) { return false; }
            }
        });

        JScrollPane sp = new JScrollPane(fileList);
        sp.setBorder(BorderFactory.createEmptyBorder());
        panel.add(sp, BorderLayout.CENTER);

        return panel;
    }

    // ==================== 批量重命名面板 ====================

    private JPanel buildRenamePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new JPanel().getBorder());

        // 模式选择
        JPanel topRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        topRow.add(new JLabel("模式:"));
        renameModeCombo = new JComboBox<>(RENAME_MODES);
        renameModeCombo.setFont(FONT);
        renameModeCombo.addActionListener(e -> switchRenameMode());
        topRow.add(renameModeCombo);
        renameIncludeExtBox = new JCheckBox("包含扩展名");
        renameIncludeExtBox.setFont(FONT);
        topRow.add(renameIncludeExtBox);

        // 参数区（卡片式切换）
        renameParamPanel = new JPanel(new CardLayout());
        renameParamPanel.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        renameParamPanel.add(buildRenameFindReplace(), "查找替换");
        renameParamPanel.add(buildRenameInsert(), "插入文本");
        renameParamPanel.add(buildRenameDelete(), "删除文本");
        renameParamPanel.add(buildRenameSeq(), "序号重命名");
        renameParamPanel.add(buildRenameRegex(), "正则替换");
        renameParamPanel.add(buildRenameCase(), "大小写转换");
        renameParamPanel.add(buildRenamePrefixSuffix(), "添加前后缀");

        // 预览区域
        renamePreviewArea = new JTextArea(8, 40);
        renamePreviewArea.setFont(FONT);
        renamePreviewArea.setEditable(false);
        JScrollPane previewSp = new JScrollPane(renamePreviewArea);
        previewSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "预览", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        // 按钮行
        JPanel btnRow = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 4));
        btnRenamePreview = NetUtil.makeBtn("预览", null);
        btnRenameExecute = NetUtil.makeBtn("执行重命名", new Color(0x388E3C));
        btnRow.add(btnRenamePreview);
        btnRow.add(btnRenameExecute);

        btnRenamePreview.addActionListener(e -> doRenamePreview());
        btnRenameExecute.addActionListener(e -> doRenameExecute());

        JPanel centerPanel = new JPanel(new BorderLayout(0, 6));
        centerPanel.add(renameParamPanel, BorderLayout.NORTH);
        centerPanel.add(previewSp, BorderLayout.CENTER);

        panel.add(topRow, BorderLayout.NORTH);
        panel.add(centerPanel, BorderLayout.CENTER);
        panel.add(btnRow, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel buildRenameFindReplace() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.add(new JLabel("查找:"));
        renameFindField = new JTextField(10);
        renameFindField.setFont(FONT);
        p.add(renameFindField);
        p.add(new JLabel("替换为:"));
        renameReplaceField = new JTextField(10);
        renameReplaceField.setFont(FONT);
        p.add(renameReplaceField);
        return p;
    }

    private JPanel buildRenameInsert() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.add(new JLabel("插入文本:"));
        renameInsertTextField = new JTextField(8);
        renameInsertTextField.setFont(FONT);
        p.add(renameInsertTextField);
        p.add(new JLabel("位置:"));
        renameInsertPosField = new JTextField("0", 4);
        renameInsertPosField.setFont(FONT);
        renameInsertPosField.setToolTipText("0=开头，-1=末尾（扩展名之前）");
        p.add(renameInsertPosField);
        return p;
    }

    private JPanel buildRenameDelete() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.add(new JLabel("起始位置:"));
        renameDeleteStartField = new JTextField("0", 4);
        renameDeleteStartField.setFont(FONT);
        p.add(renameDeleteStartField);
        p.add(new JLabel("结束位置:"));
        renameDeleteEndField = new JTextField("3", 4);
        renameDeleteEndField.setFont(FONT);
        renameDeleteEndField.setToolTipText("删除 [start, end) 范围的字符，-1=末尾");
        p.add(renameDeleteEndField);
        return p;
    }

    private JPanel buildRenameSeq() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.add(new JLabel("前缀:"));
        renameSeqPrefixField = new JTextField("file_", 8);
        renameSeqPrefixField.setFont(FONT);
        p.add(renameSeqPrefixField);
        p.add(new JLabel("起始序号:"));
        renameSeqStartField = new JTextField("1", 4);
        renameSeqStartField.setFont(FONT);
        p.add(renameSeqStartField);
        p.add(new JLabel("位数:"));
        renameSeqDigitsField = new JTextField("3", 3);
        renameSeqDigitsField.setFont(FONT);
        p.add(renameSeqDigitsField);
        return p;
    }

    private JPanel buildRenameRegex() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.add(new JLabel("正则:"));
        renameRegexFindField = new JTextField(10);
        renameRegexFindField.setFont(FONT);
        p.add(renameRegexFindField);
        p.add(new JLabel("替换为:"));
        renameRegexReplaceField = new JTextField(10);
        renameRegexReplaceField.setFont(FONT);
        renameRegexReplaceField.setToolTipText("支持 $1, $2 分组引用");
        p.add(renameRegexReplaceField);
        return p;
    }

    private JPanel buildRenameCase() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.add(new JLabel("转换为:"));
        renameCaseCombo = new JComboBox<>(new String[]{"全小写", "全大写", "首字母大写"});
        renameCaseCombo.setFont(FONT);
        p.add(renameCaseCombo);
        return p;
    }

    private JPanel buildRenamePrefixSuffix() {
        JPanel p = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        p.add(new JLabel("前缀:"));
        renamePrefixField = new JTextField(8);
        renamePrefixField.setFont(FONT);
        p.add(renamePrefixField);
        p.add(new JLabel("后缀:"));
        renameSuffixField = new JTextField(8);
        renameSuffixField.setFont(FONT);
        p.add(renameSuffixField);
        return p;
    }

    private void switchRenameMode() {
        CardLayout cl = (CardLayout) renameParamPanel.getLayout();
        cl.show(renameParamPanel, (String) renameModeCombo.getSelectedItem());
    }

    // ==================== 重命名逻辑 ====================

    private Map<File, String> generateRenameMap() {
        Map<File, String> map = new LinkedHashMap<>();
        String mode = (String) renameModeCombo.getSelectedItem();
        if (mode == null) return map;

        boolean includeExt = renameIncludeExtBox.isSelected();
        List<File> files = Collections.list(fileListModel.elements());

        switch (mode) {
            case "查找替换": {
                String find = renameFindField.getText();
                String replace = renameReplaceField.getText();
                for (File f : files) {
                    String name = includeExt ? f.getName() : fileNameWithoutExt(f);
                    String newName = name.replace(find, replace);
                    if (!includeExt) newName += fileExt(f);
                    map.put(f, newName);
                }
                break;
            }
            case "插入文本": {
                String insert = renameInsertTextField.getText();
                int pos;
                try { pos = Integer.parseInt(renameInsertPosField.getText().trim()); } catch (NumberFormatException e) { pos = 0; }
                for (File f : files) {
                    String name = includeExt ? f.getName() : fileNameWithoutExt(f);
                    String newName;
                    if (pos < 0) {
                        newName = name + insert;
                    } else if (pos >= name.length()) {
                        newName = name + insert;
                    } else {
                        newName = name.substring(0, pos) + insert + name.substring(pos);
                    }
                    if (!includeExt) newName += fileExt(f);
                    map.put(f, newName);
                }
                break;
            }
            case "删除文本": {
                int start, end;
                try { start = Integer.parseInt(renameDeleteStartField.getText().trim()); } catch (NumberFormatException e) { start = 0; }
                try { end = Integer.parseInt(renameDeleteEndField.getText().trim()); } catch (NumberFormatException e) { end = -1; }
                for (File f : files) {
                    String name = includeExt ? f.getName() : fileNameWithoutExt(f);
                    if (end < 0 || end > name.length()) end = name.length();
                    if (start < 0) start = 0;
                    if (start < end && start < name.length()) {
                        name = name.substring(0, start) + name.substring(Math.min(end, name.length()));
                    }
                    if (!includeExt) name += fileExt(f);
                    map.put(f, name);
                }
                break;
            }
            case "序号重命名": {
                String prefix = renameSeqPrefixField.getText();
                int startNum, digits;
                try { startNum = Integer.parseInt(renameSeqStartField.getText().trim()); } catch (NumberFormatException e) { startNum = 1; }
                try { digits = Integer.parseInt(renameSeqDigitsField.getText().trim()); } catch (NumberFormatException e) { digits = 3; }
                int num = startNum;
                String fmt = "%0" + digits + "d";
                for (File f : files) {
                    String ext = fileExt(f);
                    map.put(f, prefix + String.format(fmt, num++) + ext);
                }
                break;
            }
            case "正则替换": {
                String regex = renameRegexFindField.getText();
                String replacement = renameRegexReplaceField.getText();
                try {
                    Pattern p = Pattern.compile(regex);
                    for (File f : files) {
                        String name = includeExt ? f.getName() : fileNameWithoutExt(f);
                        String newName = p.matcher(name).replaceAll(replacement);
                        if (!includeExt) newName += fileExt(f);
                        map.put(f, newName);
                    }
                } catch (PatternSyntaxException ex) {
                    JOptionPane.showMessageDialog(this, "正则表达式错误: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    return map;
                }
                break;
            }
            case "大小写转换": {
                String caseType = (String) renameCaseCombo.getSelectedItem();
                for (File f : files) {
                    String name = includeExt ? f.getName() : fileNameWithoutExt(f);
                    String newName;
                    switch (caseType) {
                        case "全小写": newName = name.toLowerCase(); break;
                        case "全大写": newName = name.toUpperCase(); break;
                        case "首字母大写": newName = toTitleCase(name); break;
                        default: newName = name;
                    }
                    if (!includeExt) newName += fileExt(f);
                    map.put(f, newName);
                }
                break;
            }
            case "添加前后缀": {
                String prefix = renamePrefixField.getText();
                String suffix = renameSuffixField.getText();
                for (File f : files) {
                    String name = includeExt ? f.getName() : fileNameWithoutExt(f);
                    String newName = prefix + name + suffix;
                    if (!includeExt) newName += fileExt(f);
                    map.put(f, newName);
                }
                break;
            }
        }
        return map;
    }

    private void doRenamePreview() {
        Map<File, String> map = generateRenameMap();
        StringBuilder sb = new StringBuilder();
        int count = 0;
        for (Map.Entry<File, String> e : map.entrySet()) {
            String oldName = e.getKey().getName();
            String newName = e.getValue();
            if (!oldName.equals(newName)) {
                sb.append(oldName).append("  →  ").append(newName).append("\n");
                count++;
            }
        }
        if (sb.length() == 0) {
            renamePreviewArea.setText("（没有文件需要重命名）");
        } else {
            renamePreviewArea.setText("共 " + count + " 个文件将重命名:\n\n" + sb.toString());
        }
    }

    private void doRenameExecute() {
        Map<File, String> map = generateRenameMap();
        int count = 0, errors = 0;
        StringBuilder log = new StringBuilder();
        for (Map.Entry<File, String> e : map.entrySet()) {
            File src = e.getKey();
            String newName = e.getValue();
            if (src.getName().equals(newName)) continue;
            File dst = new File(src.getParent(), newName);
            if (dst.exists() && !dst.equals(src)) {
                log.append("冲突: ").append(src.getName()).append(" → ").append(newName).append(" (目标已存在)\n");
                errors++;
                continue;
            }
            if (src.renameTo(dst)) {
                log.append("OK: ").append(src.getName()).append(" → ").append(newName).append("\n");
                count++;
                // 更新列表中的文件引用
                int idx = fileListModel.indexOf(src);
                if (idx >= 0) fileListModel.set(idx, dst);
            } else {
                log.append("失败: ").append(src.getName()).append(" → ").append(newName).append("\n");
                errors++;
            }
        }
        renamePreviewArea.setText("完成: " + count + " 成功, " + errors + " 失败\n\n" + log.toString());
    }

    // ==================== 编码转换面板 ====================

    private JPanel buildEncodingPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new JPanel().getBorder());

        // 参数区
        JPanel paramPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        paramPanel.add(new JLabel("源编码:"));
        srcEncodingCombo = new JComboBox<>(ENCODINGS);
        srcEncodingCombo.setFont(FONT);
        srcEncodingCombo.setSelectedItem("GBK");
        paramPanel.add(srcEncodingCombo);
        paramPanel.add(new JLabel("目标编码:"));
        dstEncodingCombo = new JComboBox<>(ENCODINGS);
        dstEncodingCombo.setFont(FONT);
        dstEncodingCombo.setSelectedItem("UTF-8");
        paramPanel.add(dstEncodingCombo);

        JPanel paramRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        paramRow2.add(new JLabel("输出目录（留空=原地替换）:"));
        encOutputDirField = new JTextField(20);
        encOutputDirField.setFont(FONT);
        paramRow2.add(encOutputDirField);
        JButton btnBrowseOutDir = NetUtil.makeBtn("...", null);
        btnBrowseOutDir.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                encOutputDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        paramRow2.add(btnBrowseOutDir);

        JPanel paramRow3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        encBackupBox = new JCheckBox("转换前备份原文件 (.bak)");
        encBackupBox.setFont(FONT);
        paramRow3.add(encBackupBox);
        btnEncConvert = NetUtil.makeBtn("执行转换", new Color(0x1565C0));
        btnEncConvert.addActionListener(e -> doEncodingConvert());
        paramRow3.add(btnEncConvert);

        // 日志区
        encLogArea = new JTextArea(8, 40);
        encLogArea.setFont(FONT);
        encLogArea.setEditable(false);
        JScrollPane logSp = new JScrollPane(encLogArea);
        logSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "转换日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        JPanel topPanel = new JPanel(new GridLayout(3, 1, 0, 2));
        topPanel.add(paramPanel);
        topPanel.add(paramRow2);
        topPanel.add(paramRow3);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(logSp, BorderLayout.CENTER);

        return panel;
    }

    private void doEncodingConvert() {
        String srcEnc = (String) srcEncodingCombo.getSelectedItem();
        String dstEnc = (String) dstEncodingCombo.getSelectedItem();
        if (srcEnc == null || dstEnc == null) return;
        if (srcEnc.equals(dstEnc)) {
            encLogArea.setText("源编码和目标编码相同，无需转换。");
            return;
        }

        String outDir = encOutputDirField.getText().trim();
        boolean backup = encBackupBox.isSelected();
        List<File> files = Collections.list(fileListModel.elements());
        if (files.isEmpty()) {
            encLogArea.setText("请先添加文件。");
            return;
        }

        StringBuilder log = new StringBuilder();
        log.append("源编码: ").append(srcEnc).append(" → 目标编码: ").append(dstEnc).append("\n");
        log.append("文件数: ").append(files.size()).append("\n\n");

        int success = 0, errors = 0;
        for (File src : files) {
            try {
                // 读取源文件
                String content = new String(Files.readAllBytes(src.toPath()), Charset.forName(srcEnc));

                // 备份
                if (backup) {
                    File bak = new File(src.getAbsolutePath() + ".bak");
                    Files.copy(src.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
                }

                // 写入目标
                File dst;
                if (!outDir.isEmpty()) {
                    File outDirFile = new File(outDir);
                    if (!outDirFile.exists()) outDirFile.mkdirs();
                    dst = new File(outDirFile, src.getName());
                } else {
                    dst = src;
                }

                Files.write(dst.toPath(), content.getBytes(Charset.forName(dstEnc)));
                log.append("OK: ").append(src.getName()).append("\n");
                success++;
            } catch (Exception ex) {
                log.append("失败: ").append(src.getName()).append(" - ").append(ex.getMessage()).append("\n");
                errors++;
            }
        }
        log.append("\n完成: ").append(success).append(" 成功, ").append(errors).append(" 失败\n");
        encLogArea.setText(log.toString());
    }

    // ==================== 内容替换面板 ====================

    private JPanel buildReplacePanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new JPanel().getBorder());

        // 参数区
        JPanel paramPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        paramPanel.add(new JLabel("查找:"));
        replaceFindField = new JTextField(12);
        replaceFindField.setFont(FONT);
        paramPanel.add(replaceFindField);
        paramPanel.add(new JLabel("替换为:"));
        replaceToField = new JTextField(12);
        replaceToField.setFont(FONT);
        paramPanel.add(replaceToField);

        JPanel paramRow2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        replaceRegexBox = new JCheckBox("正则表达式");
        replaceRegexBox.setFont(FONT);
        paramRow2.add(replaceRegexBox);
        replaceCaseBox = new JCheckBox("区分大小写");
        replaceCaseBox.setFont(FONT);
        paramRow2.add(replaceCaseBox);
        replaceBackupBox = new JCheckBox("替换前备份 (.bak)");
        replaceBackupBox.setFont(FONT);
        paramRow2.add(replaceBackupBox);
        paramRow2.add(new JLabel("编码:"));
        replaceEncodingCombo = new JComboBox<>(ENCODINGS);
        replaceEncodingCombo.setFont(FONT);
        replaceEncodingCombo.setSelectedItem("UTF-8");
        paramRow2.add(replaceEncodingCombo);

        JPanel paramRow3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        btnReplacePreview = NetUtil.makeBtn("预览", null);
        btnReplacePreview.addActionListener(e -> doReplacePreview());
        btnReplaceExecute = NetUtil.makeBtn("执行替换", new Color(0xE65100));
        btnReplaceExecute.addActionListener(e -> doContentReplace());
        paramRow3.add(btnReplacePreview);
        paramRow3.add(btnReplaceExecute);

        // 预览区
        replacePreviewArea = new JTextArea(6, 40);
        replacePreviewArea.setFont(FONT);
        replacePreviewArea.setEditable(false);
        JScrollPane previewSp = new JScrollPane(replacePreviewArea);
        previewSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "预览（显示前5个匹配文件的差异）", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        // 日志区
        replaceLogArea = new JTextArea(4, 40);
        replaceLogArea.setFont(FONT);
        replaceLogArea.setEditable(false);
        JScrollPane logSp = new JScrollPane(replaceLogArea);
        logSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "替换日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        // 上下分屏：预览 + 日志
        JSplitPane splitPane = new JSplitPane(JSplitPane.VERTICAL_SPLIT, previewSp, logSp);
        splitPane.setResizeWeight(0.55);
        splitPane.setDividerLocation(200);
        splitPane.setBorder(BorderFactory.createEmptyBorder());

        JPanel topPanel = new JPanel(new GridLayout(3, 1, 0, 2));
        topPanel.add(paramPanel);
        topPanel.add(paramRow2);
        topPanel.add(paramRow3);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(splitPane, BorderLayout.CENTER);

        return panel;
    }

    private void doReplacePreview() {
        String find = replaceFindField.getText();
        if (find.isEmpty()) {
            replacePreviewArea.setText("请输入查找内容。");
            return;
        }
        String replace = replaceToField.getText();
        boolean regex = replaceRegexBox.isSelected();
        boolean caseSensitive = replaceCaseBox.isSelected();
        String encoding = (String) replaceEncodingCombo.getSelectedItem();

        List<File> files = Collections.list(fileListModel.elements());
        if (files.isEmpty()) {
            replacePreviewArea.setText("请先添加文件。");
            return;
        }

        Pattern pattern = null;
        if (regex) {
            try {
                pattern = Pattern.compile(find, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException ex) {
                replacePreviewArea.setText("正则表达式错误: " + ex.getMessage());
                return;
            }
        }

        StringBuilder sb = new StringBuilder();
        sb.append("查找: \"").append(find).append("\" → \"").append(replace).append("\"");
        sb.append("  正则:").append(regex ? "是" : "否");
        sb.append("  大小写:").append(caseSensitive ? "是" : "否");
        sb.append("  编码:").append(encoding).append("\n\n");

        int previewCount = 0;
        int totalMatchFiles = 0;
        int totalMatches = 0;

        for (File f : files) {
            try {
                String content = new String(Files.readAllBytes(f.toPath()), Charset.forName(encoding));
                int matchCount;
                String newContent;

                if (regex) {
                    Matcher m = pattern.matcher(content);
                    matchCount = 0;
                    while (m.find()) matchCount++;
                    newContent = pattern.matcher(content).replaceAll(replace);
                } else {
                    if (caseSensitive) {
                        matchCount = countOccurrences(content, find);
                        newContent = content.replace(find, replace);
                    } else {
                        matchCount = countOccurrencesIgnoreCase(content, find);
                        newContent = replaceIgnoreCase(content, find, replace);
                    }
                }

                if (matchCount > 0) {
                    totalMatchFiles++;
                    totalMatches += matchCount;

                    if (previewCount < 5) {
                        sb.append("━━ ").append(f.getName()).append(" (").append(matchCount).append(" 处匹配) ━━\n");
                        // 显示第一个差异的上下文
                        showFirstDiff(sb, content, newContent, find, regex, caseSensitive);
                        sb.append("\n");
                        previewCount++;
                    }
                }
            } catch (Exception ex) {
                // skip preview errors
            }
        }

        if (totalMatchFiles == 0) {
            sb.append("没有找到匹配的内容。\n");
        } else if (totalMatchFiles > 5) {
            sb.append("... 还有 ").append(totalMatchFiles - 5).append(" 个文件有匹配，共 ").append(totalMatches).append(" 处替换\n");
        }

        replacePreviewArea.setText(sb.toString());
        replacePreviewArea.setCaretPosition(0);
    }

    /** 在预览中展示第一处差异的上下文 */
    private void showFirstDiff(StringBuilder sb, String oldContent, String newContent,
                                String find, boolean regex, boolean caseSensitive) {
        // 找第一处匹配位置
        int matchStart, matchEnd;
        if (regex) {
            try {
                Pattern p = Pattern.compile(find, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
                Matcher m = p.matcher(oldContent);
                if (m.find()) {
                    matchStart = m.start();
                    matchEnd = m.end();
                } else return;
            } catch (Exception e) { return; }
        } else {
            String searchIn = caseSensitive ? oldContent : oldContent.toLowerCase();
            String searchFor = caseSensitive ? find : find.toLowerCase();
            matchStart = searchIn.indexOf(searchFor);
            if (matchStart < 0) return;
            matchEnd = matchStart + find.length();
        }

        // 截取上下文（前后各40字符）
        int ctxStart = Math.max(0, matchStart - 40);
        int ctxEnd = Math.min(oldContent.length(), matchEnd + 40);

        // 找到行边界
        while (ctxStart > 0 && oldContent.charAt(ctxStart) != '\n') ctxStart--;
        if (oldContent.charAt(ctxStart) == '\n') ctxStart++;
        while (ctxEnd < oldContent.length() && oldContent.charAt(ctxEnd) != '\n') ctxEnd++;

        String oldCtx = oldContent.substring(ctxStart, ctxEnd).replace("\r", "");
        String newCtx = newContent.substring(ctxStart, Math.min(ctxEnd, newContent.length())).replace("\r", "");

        sb.append("  - ").append(oldCtx).append("\n");
        sb.append("  + ").append(newCtx).append("\n");
    }

    private void doContentReplace() {
        String find = replaceFindField.getText();
        if (find.isEmpty()) {
            replaceLogArea.setText("请输入查找内容。");
            return;
        }
        String replace = replaceToField.getText();
        boolean regex = replaceRegexBox.isSelected();
        boolean caseSensitive = replaceCaseBox.isSelected();
        boolean backup = replaceBackupBox.isSelected();
        String encoding = (String) replaceEncodingCombo.getSelectedItem();

        List<File> files = Collections.list(fileListModel.elements());
        if (files.isEmpty()) {
            replaceLogArea.setText("请先添加文件。");
            return;
        }

        Pattern pattern = null;
        if (regex) {
            try {
                pattern = Pattern.compile(find, caseSensitive ? 0 : Pattern.CASE_INSENSITIVE);
            } catch (PatternSyntaxException ex) {
                replaceLogArea.setText("正则表达式错误: " + ex.getMessage());
                return;
            }
        }

        StringBuilder log = new StringBuilder();
        log.append("查找: ").append(find).append(" → 替换为: ").append(replace).append("\n");
        log.append("正则: ").append(regex ? "是" : "否").append("  区分大小写: ").append(caseSensitive ? "是" : "否").append("\n");
        log.append("文件数: ").append(files.size()).append("\n\n");

        int totalFiles = 0, totalMatches = 0, errors = 0;
        for (File f : files) {
            try {
                String content = new String(Files.readAllBytes(f.toPath()), Charset.forName(encoding));
                String newContent;
                int matchCount;

                if (regex) {
                    Matcher m = pattern.matcher(content);
                    StringBuffer sb = new StringBuffer();
                    matchCount = 0;
                    while (m.find()) {
                        m.appendReplacement(sb, replace);
                        matchCount++;
                    }
                    m.appendTail(sb);
                    newContent = sb.toString();
                } else {
                    if (caseSensitive) {
                        matchCount = countOccurrences(content, find);
                        newContent = content.replace(find, replace);
                    } else {
                        matchCount = countOccurrencesIgnoreCase(content, find);
                        newContent = replaceIgnoreCase(content, find, replace);
                    }
                }

                if (matchCount > 0) {
                    // 备份
                    if (backup) {
                        File bak = new File(f.getAbsolutePath() + ".bak");
                        Files.copy(f.toPath(), bak.toPath(), StandardCopyOption.REPLACE_EXISTING);
                    }
                    Files.write(f.toPath(), newContent.getBytes(Charset.forName(encoding)));
                    log.append(f.getName()).append(": ").append(matchCount).append(" 处替换\n");
                    totalFiles++;
                    totalMatches += matchCount;
                }
            } catch (Exception ex) {
                log.append("失败: ").append(f.getName()).append(" - ").append(ex.getMessage()).append("\n");
                errors++;
            }
        }
        log.append("\n完成: ").append(totalFiles).append(" 个文件, ").append(totalMatches).append(" 处替换, ").append(errors).append(" 失败\n");
        replaceLogArea.setText(log.toString());
    }

    // ==================== 文件操作面板 ====================

    private JPanel buildFileOpsPanel() {
        JPanel panel = new JPanel(new BorderLayout(0, 6));
        panel.setBorder(new JPanel().getBorder());

        // 工作目录行
        JPanel dirRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        dirRow.add(new JLabel("工作目录:"));
        fileOpsDirField = new JTextField(25);
        fileOpsDirField.setFont(FONT);
        fileOpsDirField.setToolTipText("所有操作默认在此目录下执行，留空则弹窗选择");
        dirRow.add(fileOpsDirField);
        JButton btnBrowseDir = NetUtil.makeBtn("浏览...", null);
        btnBrowseDir.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                fileOpsDirField.setText(chooser.getSelectedFile().getAbsolutePath());
            }
        });
        dirRow.add(btnBrowseDir);

        // ---- 创建文件/目录 ----
        JPanel createPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        createPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "创建", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 11)));
        createPanel.add(new JLabel("名称:"));
        fileOpsNameField = new JTextField(14);
        fileOpsNameField.setFont(FONT);
        fileOpsNameField.setToolTipText("支持多级路径，如 a/b/c.txt");
        createPanel.add(fileOpsNameField);
        JButton btnCreateFile = NetUtil.makeBtn("创建文件", null);
        btnCreateFile.addActionListener(e -> doCreateFile());
        JButton btnCreateDir = NetUtil.makeBtn("创建目录", null);
        btnCreateDir.addActionListener(e -> doCreateDir());
        JButton btnCreateDirs = NetUtil.makeBtn("创建多级目录", null);
        btnCreateDirs.addActionListener(e -> doCreateDirs());
        createPanel.add(btnCreateFile);
        createPanel.add(btnCreateDir);
        createPanel.add(btnCreateDirs);

        // ---- 读取文件创建目录 ----
        JPanel createFromFilePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        createFromFilePanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "读取文件批量创建目录（每行一个路径，支持多级）", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 11)));
        createFromFilePanel.add(new JLabel("列表文件:"));
        fileOpsListFileField = new JTextField(28);
        fileOpsListFileField.setFont(FONT);
        fileOpsListFileField.setToolTipText("选择一个文本文件，每行一个目录路径，支持多级如：张三/2026/05/工作情况");
        createFromFilePanel.add(fileOpsListFileField);
        JButton btnBrowseAndCreate = NetUtil.makeBtn("选择文件并创建", new Color(0x1565C0));
        btnBrowseAndCreate.addActionListener(e -> {
            JFileChooser chooser = new JFileChooser();
            chooser.setFileSelectionMode(JFileChooser.FILES_ONLY);
            chooser.setDialogTitle("选择目录列表文件");
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                fileOpsListFileField.setText(chooser.getSelectedFile().getAbsolutePath());
                doCreateFromFile();
            }
        });
        createFromFilePanel.add(btnBrowseAndCreate);

        // ---- 批量创建文件 ----
        JPanel batchCreatePanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        batchCreatePanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "批量创建", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 11)));
        batchCreatePanel.add(new JLabel("前缀:"));
        fileOpsBatchPrefixField = new JTextField("file_", 6);
        fileOpsBatchPrefixField.setFont(FONT);
        batchCreatePanel.add(fileOpsBatchPrefixField);
        batchCreatePanel.add(new JLabel("起始:"));
        fileOpsBatchStartField = new JTextField("1", 4);
        fileOpsBatchStartField.setFont(FONT);
        batchCreatePanel.add(fileOpsBatchStartField);
        batchCreatePanel.add(new JLabel("数量:"));
        fileOpsBatchCountField = new JTextField("10", 4);
        fileOpsBatchCountField.setFont(FONT);
        batchCreatePanel.add(fileOpsBatchCountField);
        batchCreatePanel.add(new JLabel("位数:"));
        fileOpsBatchDigitsField = new JTextField("3", 3);
        fileOpsBatchDigitsField.setFont(FONT);
        batchCreatePanel.add(fileOpsBatchDigitsField);
        batchCreatePanel.add(new JLabel("扩展名:"));
        fileOpsBatchExtField = new JTextField(".txt", 5);
        fileOpsBatchExtField.setFont(FONT);
        batchCreatePanel.add(fileOpsBatchExtField);
        JButton btnBatchFiles = NetUtil.makeBtn("批量创建文件", null);
        btnBatchFiles.addActionListener(e -> doBatchCreateFiles());
        JButton btnBatchDirs = NetUtil.makeBtn("批量创建目录", null);
        btnBatchDirs.addActionListener(e -> doBatchCreateDirs());
        batchCreatePanel.add(btnBatchFiles);
        batchCreatePanel.add(btnBatchDirs);

        // ---- 属性/删除操作 ----
        JPanel attrPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 4));
        attrPanel.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "属性 & 删除", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 11)));
        JButton btnSetReadOnly = NetUtil.makeBtn("设为只读", null);
        btnSetReadOnly.addActionListener(e -> doSetReadOnly(true));
        JButton btnSetWritable = NetUtil.makeBtn("取消只读", null);
        btnSetWritable.addActionListener(e -> doSetReadOnly(false));
        JButton btnSetExecutable = NetUtil.makeBtn("设为可执行", null);
        btnSetExecutable.addActionListener(e -> doSetExecutable());
        JButton btnDeleteFiles = NetUtil.makeBtn("删除选中文件", new Color(0xD32F2F));
        btnDeleteFiles.addActionListener(e -> doDeleteFiles());
        attrPanel.add(btnSetReadOnly);
        attrPanel.add(btnSetWritable);
        attrPanel.add(btnSetExecutable);
        attrPanel.add(btnDeleteFiles);

        // ---- 日志区 ----
        fileOpsLogArea = new JTextArea(6, 40);
        fileOpsLogArea.setFont(FONT);
        fileOpsLogArea.setEditable(false);
        JScrollPane logSp = new JScrollPane(fileOpsLogArea);
        logSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "操作日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));

        JPanel topPanel = new JPanel();
        topPanel.setLayout(new BoxLayout(topPanel, BoxLayout.Y_AXIS));
        topPanel.add(dirRow);
        topPanel.add(createPanel);
        topPanel.add(createFromFilePanel);
        topPanel.add(batchCreatePanel);
        topPanel.add(attrPanel);

        panel.add(topPanel, BorderLayout.NORTH);
        panel.add(logSp, BorderLayout.CENTER);

        return panel;
    }

    private File resolveWorkDir() {
        String dir = fileOpsDirField.getText().trim();
        if (!dir.isEmpty()) {
            File d = new File(dir);
            if (d.exists() && d.isDirectory()) return d;
        }
        // 弹窗选择
        JFileChooser chooser = new JFileChooser();
        chooser.setFileSelectionMode(JFileChooser.DIRECTORIES_ONLY);
        chooser.setDialogTitle("选择工作目录");
        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File d = chooser.getSelectedFile();
            fileOpsDirField.setText(d.getAbsolutePath());
            return d;
        }
        return new File(".");
    }

    private void doCreateFile() {
        File workDir = resolveWorkDir();
        String name = fileOpsNameField.getText().trim();
        if (name.isEmpty()) { fileOpsLogArea.setText("请输入文件名。"); return; }
        File target = new File(workDir, name);
        try {
            if (target.exists()) {
                fileOpsLogArea.setText("文件已存在: " + target.getAbsolutePath());
                return;
            }
            // 确保父目录存在（支持多级路径）
            File parent = target.getParentFile();
            if (parent != null && !parent.exists()) parent.mkdirs();
            if (target.createNewFile()) {
                fileOpsLogArea.setText("已创建文件: " + target.getAbsolutePath());
            } else {
                fileOpsLogArea.setText("创建失败: " + target.getAbsolutePath());
            }
        } catch (Exception ex) {
            fileOpsLogArea.setText("创建失败: " + ex.getMessage());
        }
    }

    private void doCreateDir() {
        File workDir = resolveWorkDir();
        String name = fileOpsNameField.getText().trim();
        if (name.isEmpty()) { fileOpsLogArea.setText("请输入目录名。"); return; }
        File target = new File(workDir, name);
        if (target.exists()) {
            fileOpsLogArea.setText("已存在: " + target.getAbsolutePath());
            return;
        }
        if (target.mkdir()) {
            fileOpsLogArea.setText("已创建目录: " + target.getAbsolutePath());
        } else {
            fileOpsLogArea.setText("创建失败（可能需要先创建父目录）: " + target.getAbsolutePath());
        }
    }

    private void doCreateDirs() {
        File workDir = resolveWorkDir();
        String name = fileOpsNameField.getText().trim();
        if (name.isEmpty()) { fileOpsLogArea.setText("请输入路径。"); return; }
        File target = new File(workDir, name);
        if (target.exists()) {
            fileOpsLogArea.setText("已存在: " + target.getAbsolutePath());
            return;
        }
        if (target.mkdirs()) {
            fileOpsLogArea.setText("已创建多级目录: " + target.getAbsolutePath());
        } else {
            fileOpsLogArea.setText("创建失败: " + target.getAbsolutePath());
        }
    }

    private void doCreateFromFile() {
        File workDir = resolveWorkDir();
        String listFilePath = fileOpsListFileField.getText().trim();
        if (listFilePath.isEmpty()) { fileOpsLogArea.setText("请先选择一个目录列表文件。"); return; }

        File listFile = new File(listFilePath);
        if (!listFile.exists() || !listFile.isFile()) {
            fileOpsLogArea.setText("列表文件不存在: " + listFilePath);
            return;
        }

        StringBuilder log = new StringBuilder();
        log.append("读取列表文件: ").append(listFile.getName()).append("\n");
        log.append("工作目录: ").append(workDir.getAbsolutePath()).append("\n\n");

        int dirCount = 0, existCount = 0, errors = 0;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                new FileInputStream(listFile), StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;
                // 每行作为一个目录路径，支持多级如：张三/2026/05/工作情况
                File target = new File(workDir, line);
                try {
                    if (target.exists()) {
                        log.append("[已存在] ").append(line).append("\n");
                        existCount++;
                    } else if (target.mkdirs()) {
                        log.append("[已创建] ").append(line).append("\n");
                        dirCount++;
                    } else {
                        log.append("[失败] ").append(line).append("\n");
                        errors++;
                    }
                } catch (Exception ex) {
                    log.append("[异常] ").append(line).append(": ").append(ex.getMessage()).append("\n");
                    errors++;
                }
            }
        } catch (Exception ex) {
            log.append("[读取文件异常] ").append(ex.getMessage()).append("\n");
            fileOpsLogArea.setText(log.toString());
            return;
        }

        log.append("\n完成: 新建 ").append(dirCount).append(" 个目录, ");
        log.append("已存在 ").append(existCount).append(" 个, ");
        log.append("失败 ").append(errors).append(" 个");
        fileOpsLogArea.setText(log.toString());
    }

    private void doBatchCreateFiles() {
        File workDir = resolveWorkDir();
        String prefix = fileOpsBatchPrefixField.getText();
        int start, count, digits;
        String ext = fileOpsBatchExtField.getText();
        try { start = Integer.parseInt(fileOpsBatchStartField.getText().trim()); } catch (NumberFormatException e) { start = 1; }
        try { count = Integer.parseInt(fileOpsBatchCountField.getText().trim()); } catch (NumberFormatException e) { count = 10; }
        try { digits = Integer.parseInt(fileOpsBatchDigitsField.getText().trim()); } catch (NumberFormatException e) { digits = 3; }
        if (count > 10000) { fileOpsLogArea.setText("数量不能超过 10000。"); return; }

        String fmt = "%0" + digits + "d";
        StringBuilder log = new StringBuilder();
        int created = 0, errors = 0;
        for (int i = 0; i < count; i++) {
            String name = prefix + String.format(fmt, start + i) + ext;
            File f = new File(workDir, name);
            try {
                if (f.createNewFile()) {
                    created++;
                } else {
                    log.append("[已存在] ").append(name).append("\n");
                }
            } catch (Exception ex) {
                log.append("[失败] ").append(name).append(": ").append(ex.getMessage()).append("\n");
                errors++;
            }
        }
        log.append("完成: 创建 ").append(created).append(" 个文件");
        if (errors > 0) log.append(", ").append(errors).append(" 失败");
        fileOpsLogArea.setText(log.toString());
    }

    private void doBatchCreateDirs() {
        File workDir = resolveWorkDir();
        String prefix = fileOpsBatchPrefixField.getText();
        int start, count, digits;
        try { start = Integer.parseInt(fileOpsBatchStartField.getText().trim()); } catch (NumberFormatException e) { start = 1; }
        try { count = Integer.parseInt(fileOpsBatchCountField.getText().trim()); } catch (NumberFormatException e) { count = 10; }
        try { digits = Integer.parseInt(fileOpsBatchDigitsField.getText().trim()); } catch (NumberFormatException e) { digits = 3; }
        if (count > 10000) { fileOpsLogArea.setText("数量不能超过 10000。"); return; }

        String fmt = "%0" + digits + "d";
        StringBuilder log = new StringBuilder();
        int created = 0, errors = 0;
        for (int i = 0; i < count; i++) {
            String name = prefix + String.format(fmt, start + i);
            File d = new File(workDir, name);
            if (d.mkdir()) {
                created++;
            } else {
                log.append("[已存在] ").append(name).append("\n");
            }
        }
        log.append("完成: 创建 ").append(created).append(" 个目录");
        fileOpsLogArea.setText(log.toString());
    }

    private void doSetReadOnly(boolean readOnly) {
        List<File> files = Collections.list(fileListModel.elements());
        if (files.isEmpty()) {
            fileOpsLogArea.setText("请先在左侧文件列表中添加文件。");
            return;
        }
        StringBuilder log = new StringBuilder();
        int ok = 0, fail = 0;
        for (File f : files) {
            if (f.setReadOnly()) {
                log.append(readOnly ? "[只读] " : "[可写] ").append(f.getName()).append("\n");
                ok++;
            } else {
                log.append("[失败] ").append(f.getName()).append("\n");
                fail++;
            }
        }
        log.append("\n完成: ").append(ok).append(" 成功, ").append(fail).append(" 失败");
        fileOpsLogArea.setText(log.toString());
    }

    private void doSetExecutable() {
        List<File> files = Collections.list(fileListModel.elements());
        if (files.isEmpty()) {
            fileOpsLogArea.setText("请先在左侧文件列表中添加文件。");
            return;
        }
        StringBuilder log = new StringBuilder();
        int ok = 0, fail = 0;
        for (File f : files) {
            if (f.setExecutable(true)) {
                log.append("[可执行] ").append(f.getName()).append("\n");
                ok++;
            } else {
                log.append("[失败] ").append(f.getName()).append("\n");
                fail++;
            }
        }
        log.append("\n完成: ").append(ok).append(" 成功, ").append(fail).append(" 失败");
        fileOpsLogArea.setText(log.toString());
    }

    private void doDeleteFiles() {
        List<File> files = Collections.list(fileListModel.elements());
        if (files.isEmpty()) {
            fileOpsLogArea.setText("请先在左侧文件列表中添加文件。");
            return;
        }
        int confirm = JOptionPane.showConfirmDialog(this,
                "确定要删除列表中 " + files.size() + " 个文件/目录吗？\n此操作不可撤销！",
                "确认删除", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
        if (confirm != JOptionPane.YES_OPTION) return;

        StringBuilder log = new StringBuilder();
        int ok = 0, fail = 0;
        for (File f : files) {
            if (f.delete()) {
                log.append("[已删除] ").append(f.getName()).append("\n");
                ok++;
            } else {
                log.append("[失败] ").append(f.getName()).append(" (可能目录非空或权限不足)\n");
                fail++;
            }
        }
        log.append("\n完成: ").append(ok).append(" 成功, ").append(fail).append(" 失败");
        fileOpsLogArea.setText(log.toString());
        // 从列表中移除已删除的文件
        for (int i = fileListModel.size() - 1; i >= 0; i--) {
            if (!fileListModel.get(i).exists()) {
                fileListModel.remove(i);
            }
        }
        updateFileCount();
    }

    // ==================== 工具方法 ====================

    private static String fileNameWithoutExt(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String fileExt(File f) {
        String name = f.getName();
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(dot) : "";
    }

    private static String toTitleCase(String s) {
        if (s.isEmpty()) return s;
        StringBuilder sb = new StringBuilder(s.length());
        boolean nextUpper = true;
        for (char c : s.toCharArray()) {
            if (Character.isWhitespace(c) || c == '_' || c == '-') {
                nextUpper = true;
                sb.append(c);
            } else if (nextUpper) {
                sb.append(Character.toUpperCase(c));
                nextUpper = false;
            } else {
                sb.append(Character.toLowerCase(c));
            }
        }
        return sb.toString();
    }

    private static int countOccurrences(String text, String sub) {
        int count = 0, idx = 0;
        while ((idx = text.indexOf(sub, idx)) >= 0) {
            count++;
            idx += sub.length();
        }
        return count;
    }

    private static int countOccurrencesIgnoreCase(String text, String sub) {
        return countOccurrences(text.toLowerCase(), sub.toLowerCase());
    }

    private static String replaceIgnoreCase(String text, String find, String replacement) {
        String lower = text.toLowerCase();
        String findLower = find.toLowerCase();
        StringBuilder sb = new StringBuilder();
        int idx = 0, last = 0;
        while ((idx = lower.indexOf(findLower, last)) >= 0) {
            sb.append(text, last, idx);
            sb.append(replacement);
            last = idx + find.length();
        }
        sb.append(text.substring(last));
        return sb.toString();
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        NetUtil.loadCombo(renameModeCombo, config, "batch.rename.mode");
        NetUtil.loadField(renameFindField, config, "batch.rename.find");
        NetUtil.loadField(renameReplaceField, config, "batch.rename.replace");
        NetUtil.loadField(renameInsertTextField, config, "batch.rename.insert");
        NetUtil.loadField(renameInsertPosField, config, "batch.rename.insertpos");
        NetUtil.loadField(renameDeleteStartField, config, "batch.rename.delstart");
        NetUtil.loadField(renameDeleteEndField, config, "batch.rename.delend");
        NetUtil.loadField(renameSeqPrefixField, config, "batch.rename.seqprefix");
        NetUtil.loadField(renameSeqStartField, config, "batch.rename.seqstart");
        NetUtil.loadField(renameSeqDigitsField, config, "batch.rename.seqdigits");
        NetUtil.loadField(renameRegexFindField, config, "batch.rename.regexfind");
        NetUtil.loadField(renameRegexReplaceField, config, "batch.rename.regexreplace");
        NetUtil.loadCombo(renameCaseCombo, config, "batch.rename.case");
        NetUtil.loadField(renamePrefixField, config, "batch.rename.prefix");
        NetUtil.loadField(renameSuffixField, config, "batch.rename.suffix");
        NetUtil.loadField(renameIncludeExtBox, config, "batch.rename.includeext");
        NetUtil.loadCombo(srcEncodingCombo, config, "batch.enc.src");
        NetUtil.loadCombo(dstEncodingCombo, config, "batch.enc.dst");
        NetUtil.loadField(encOutputDirField, config, "batch.enc.outdir");
        NetUtil.loadField(encBackupBox, config, "batch.enc.backup");
        NetUtil.loadField(replaceFindField, config, "batch.replace.find");
        NetUtil.loadField(replaceToField, config, "batch.replace.to");
        NetUtil.loadField(replaceRegexBox, config, "batch.replace.regex");
        NetUtil.loadField(replaceCaseBox, config, "batch.replace.case");
        NetUtil.loadField(replaceBackupBox, config, "batch.replace.backup");
        NetUtil.loadCombo(replaceEncodingCombo, config, "batch.replace.encoding");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        NetUtil.saveCombo(renameModeCombo, config, "batch.rename.mode");
        NetUtil.saveField(renameFindField, config, "batch.rename.find");
        NetUtil.saveField(renameReplaceField, config, "batch.rename.replace");
        NetUtil.saveField(renameInsertTextField, config, "batch.rename.insert");
        NetUtil.saveField(renameInsertPosField, config, "batch.rename.insertpos");
        NetUtil.saveField(renameDeleteStartField, config, "batch.rename.delstart");
        NetUtil.saveField(renameDeleteEndField, config, "batch.rename.delend");
        NetUtil.saveField(renameSeqPrefixField, config, "batch.rename.seqprefix");
        NetUtil.saveField(renameSeqStartField, config, "batch.rename.seqstart");
        NetUtil.saveField(renameSeqDigitsField, config, "batch.rename.seqdigits");
        NetUtil.saveField(renameRegexFindField, config, "batch.rename.regexfind");
        NetUtil.saveField(renameRegexReplaceField, config, "batch.rename.regexreplace");
        NetUtil.saveCombo(renameCaseCombo, config, "batch.rename.case");
        NetUtil.saveField(renamePrefixField, config, "batch.rename.prefix");
        NetUtil.saveField(renameSuffixField, config, "batch.rename.suffix");
        NetUtil.saveField(renameIncludeExtBox, config, "batch.rename.includeext");
        NetUtil.saveCombo(srcEncodingCombo, config, "batch.enc.src");
        NetUtil.saveCombo(dstEncodingCombo, config, "batch.enc.dst");
        NetUtil.saveField(encOutputDirField, config, "batch.enc.outdir");
        NetUtil.saveField(encBackupBox, config, "batch.enc.backup");
        NetUtil.saveField(replaceFindField, config, "batch.replace.find");
        NetUtil.saveField(replaceToField, config, "batch.replace.to");
        NetUtil.saveField(replaceRegexBox, config, "batch.replace.regex");
        NetUtil.saveField(replaceCaseBox, config, "batch.replace.case");
        NetUtil.saveField(replaceBackupBox, config, "batch.replace.backup");
        NetUtil.saveCombo(replaceEncodingCombo, config, "batch.replace.encoding");
    }
}
