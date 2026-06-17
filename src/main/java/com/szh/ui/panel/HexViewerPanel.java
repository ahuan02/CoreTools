package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.ui.MainFrame;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.*;

import static com.szh.utils.NetUtil.*;

/**
 * 十六进制文件编辑器（简易版）：十六进制 + ASCII 双视图，适合查看二进制文件、固件、报文原始数据
 */
public class HexViewerPanel extends AbstractCommandPanel {

    // ===== 工具栏 =====
    private JTextField pathField;
    private JButton btnOpen, btnGoto, btnExport, btnClear;
    private JComboBox<String> bytesPerLineCombo;
    private JLabel fileInfoLabel;
    private JTextField gotoOffsetField;

    // ===== 双视图 =====
    private JTextArea hexArea;
    private JTextArea asciiArea;
    private JScrollPane hexScroll, asciiScroll;

    // ===== 状态 =====
    private byte[] fileData;
    private File currentFile;
    private int bytesPerLine = 16;
    private JLabel statusLabel;
    private JLabel selInfoLabel;

    // ===== 颜色 =====
    private static final Color HEX_BG = new Color(0x1E1E1E);
    private static final Color HEX_FG = new Color(0x64B5F6);
    private static final Color ASCII_FG = new Color(0x81C784);
    private static final Color OFFSET_FG = new Color(0x888888);
    private static final Color SEL_BG = new Color(0x264F78);

    // ===== 每行字节数选项 =====
    private static final int[] BYTES_OPTIONS = {8, 16, 32, 64, 128};

    public HexViewerPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));

        // ---- 顶部工具栏 ----
        JPanel topPanel = new JPanel(new BorderLayout(4, 0));

        JPanel fileRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        fileRow.add(new JLabel("文件:"));
        pathField = new JTextField(30);
        pathField.setFont(FONT_TEXT);
        fileRow.add(pathField);

        btnOpen = makeBtn("打开", new Color(0x42A5F5));
        btnOpen.addActionListener(e -> openFile());
        fileRow.add(btnOpen);

        bytesPerLineCombo = new JComboBox<>();
        for (int n : BYTES_OPTIONS) bytesPerLineCombo.addItem(n + " 字节/行");
        bytesPerLineCombo.setFont(FONT_TEXT);
        bytesPerLineCombo.setSelectedIndex(1); // 默认 16
        bytesPerLineCombo.addActionListener(e -> {
            bytesPerLine = BYTES_OPTIONS[bytesPerLineCombo.getSelectedIndex()];
            refreshView();
        });
        fileRow.add(bytesPerLineCombo);

        btnExport = makeBtn("导出", new Color(0xAB47BC));
        btnExport.setEnabled(false);
        btnExport.addActionListener(e -> exportHex());
        fileRow.add(btnExport);

        btnClear = makeBtn("清屏", new Color(0xE57373));
        btnClear.addActionListener(e -> clearAll());
        fileRow.add(btnClear);

        fileRow.add(new JSeparator(SwingConstants.VERTICAL));

        fileRow.add(new JLabel("跳转偏移:"));
        gotoOffsetField = new JTextField(10);
        gotoOffsetField.setFont(FONT_TEXT);
        gotoOffsetField.setToolTipText("十六进制偏移地址，如 0x100 或 100");
        gotoOffsetField.addActionListener(e -> gotoOffset());
        fileRow.add(gotoOffsetField);

        btnGoto = makeBtn("跳转", new Color(0x78909C));
        btnGoto.addActionListener(e -> gotoOffset());
        fileRow.add(btnGoto);

        fileInfoLabel = new JLabel("  未打开文件");
        fileInfoLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        fileInfoLabel.setForeground(new Color(0x888888));
        fileRow.add(fileInfoLabel);

        topPanel.add(fileRow, BorderLayout.NORTH);

        // 选择信息行
        selInfoLabel = new JLabel("");
        selInfoLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        selInfoLabel.setForeground(new Color(0xFFB74D));
        fileRow.add(selInfoLabel);

        add(topPanel, BorderLayout.NORTH);

        // ---- 双视图（同步滚动） ----
        JPanel viewPanel = new JPanel(new GridLayout(1, 2, 2, 0));

        // 十六进制视图
        hexArea = new JTextArea();
        hexArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        hexArea.setEditable(false);
        hexArea.setBackground(HEX_BG);
        hexArea.setForeground(HEX_FG);
        hexArea.setCaretColor(new Color(0xD4D4D4));
        hexArea.setLineWrap(false);

        hexScroll = new JScrollPane(hexArea);
        hexScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "十六进制", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        hexScroll.getVerticalScrollBar().setUnitIncrement(16);
        MainFrame.enableSmoothScrolling(hexScroll);

        // ASCII 视图
        asciiArea = new JTextArea();
        asciiArea.setFont(new Font("Consolas", Font.PLAIN, 13));
        asciiArea.setEditable(false);
        asciiArea.setBackground(HEX_BG);
        asciiArea.setForeground(ASCII_FG);
        asciiArea.setCaretColor(new Color(0xD4D4D4));
        asciiArea.setLineWrap(false);

        asciiScroll = new JScrollPane(asciiArea);
        asciiScroll.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "ASCII", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        asciiScroll.getVerticalScrollBar().setUnitIncrement(16);
        MainFrame.enableSmoothScrolling(asciiScroll);

        // 同步滚动
        hexScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) return;
            asciiScroll.getVerticalScrollBar().setValue(e.getValue());
        });
        asciiScroll.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (!e.getValueIsAdjusting()) return;
            hexScroll.getVerticalScrollBar().setValue(e.getValue());
        });

        viewPanel.add(hexScroll);
        viewPanel.add(asciiScroll);
        add(viewPanel, BorderLayout.CENTER);

        // 选择监听：鼠标释放时同步两个视图的选中
        hexArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) { syncSelection(hexArea, asciiArea); }
        });
        asciiArea.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseReleased(MouseEvent e) { syncSelection(asciiArea, hexArea); }
        });

        // 右键菜单
        JPopupMenu popup = new JPopupMenu();
        JMenuItem copyHexItem = new JMenuItem("复制 Hex 选中");
        copyHexItem.addActionListener(e -> {
            String sel = hexArea.getSelectedText();
            if (sel != null) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(sel), null);
        });
        popup.add(copyHexItem);
        JMenuItem copyAllHexItem = new JMenuItem("复制全部 Hex");
        copyAllHexItem.addActionListener(e -> {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(hexArea.getText()), null);
        });
        popup.add(copyAllHexItem);
        hexArea.setComponentPopupMenu(popup);

        JPopupMenu asciiPopup = new JPopupMenu();
        JMenuItem copyAsciiItem = new JMenuItem("复制 ASCII 选中");
        copyAsciiItem.addActionListener(e -> {
            String sel = asciiArea.getSelectedText();
            if (sel != null) Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(sel), null);
        });
        asciiPopup.add(copyAsciiItem);
        asciiArea.setComponentPopupMenu(asciiPopup);

        // ---- 底部状态栏 ----
        statusLabel = new JLabel(" 就绪");
        statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        statusLabel.setBorder(BorderFactory.createEmptyBorder(2, 4, 2, 4));
        add(statusLabel, BorderLayout.SOUTH);
    }

    // ==================== 文件操作 ====================

    private void openFile() {
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("选择文件（支持任意格式）");
        String currentPath = pathField.getText().trim();
        if (!currentPath.isEmpty()) {
            File f = new File(currentPath);
            if (f.exists()) chooser.setCurrentDirectory(f.isDirectory() ? f : f.getParentFile());
        }

        if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            pathField.setText(f.getAbsolutePath());
            loadFile(f);
        }
    }

    private void loadFile(File f) {
        // 分段加载大文件：最大 50MB
        long size = f.length();
        long loadLimit = 50 * 1024 * 1024;

        try (FileInputStream fis = new FileInputStream(f)) {
            if (size > loadLimit) {
                // 超大文件只加载前 50MB
                fileData = new byte[(int) loadLimit];
                int totalRead = 0;
                while (totalRead < loadLimit) {
                    int n = fis.read(fileData, totalRead, (int) (loadLimit - totalRead));
                    if (n == -1) break;
                    totalRead += n;
                }
                if (totalRead < loadLimit) {
                    byte[] trimmed = new byte[totalRead];
                    System.arraycopy(fileData, 0, trimmed, 0, totalRead);
                    fileData = trimmed;
                }
                setStatus("已加载前 " + formatSize(loadLimit) + "（文件共 " + formatSize(size) + "）");
            } else {
                fileData = new byte[(int) size];
                int totalRead = 0;
                while (totalRead < size) {
                    int n = fis.read(fileData, totalRead, (int) (size - totalRead));
                    if (n == -1) break;
                    totalRead += n;
                }
                setStatus("已加载: " + f.getName() + " (" + formatSize(size) + ")");
            }

            currentFile = f;
            btnExport.setEnabled(true);
            updateFileInfo();
            refreshView();
        } catch (IOException e) {
            setStatus("读取失败: " + e.getMessage());
        }
    }

    private void clearAll() {
        fileData = null;
        currentFile = null;
        pathField.setText("");
        hexArea.setText("");
        asciiArea.setText("");
        selInfoLabel.setText("");
        fileInfoLabel.setText("  未打开文件");
        btnExport.setEnabled(false);
        setStatus("已清空");
    }

    // ==================== 渲染 ====================

    private void refreshView() {
        if (fileData == null || fileData.length == 0) return;

        StringBuilder hexBuf = new StringBuilder();
        StringBuilder asciiBuf = new StringBuilder();

        int totalLines = (fileData.length + bytesPerLine - 1) / bytesPerLine;

        for (int line = 0; line < totalLines; line++) {
            int offset = line * bytesPerLine;
            int lineEnd = Math.min(offset + bytesPerLine, fileData.length);

            // 偏移地址
            hexBuf.append(String.format("%08X  ", offset));

            // 十六进制
            for (int i = offset; i < offset + bytesPerLine; i++) {
                if (i < lineEnd) {
                    hexBuf.append(String.format("%02X ", fileData[i] & 0xFF));
                } else {
                    hexBuf.append("   ");
                }
            }

            // ASCII
            for (int i = offset; i < offset + bytesPerLine; i++) {
                if (i < lineEnd) {
                    int b = fileData[i] & 0xFF;
                    if (b >= 0x20 && b < 0x7F) {
                        asciiBuf.append((char) b);
                    } else {
                        asciiBuf.append('.');
                    }
                }
            }

            if (line < totalLines - 1) {
                hexBuf.append('\n');
                asciiBuf.append('\n');
            }
        }

        hexArea.setText(hexBuf.toString());
        asciiArea.setText(asciiBuf.toString());
        hexArea.setCaretPosition(0);
        asciiArea.setCaretPosition(0);

        selInfoLabel.setText("");
        updateFileInfo();
    }

    private void updateFileInfo() {
        if (currentFile == null) {
            fileInfoLabel.setText("  未打开文件");
            return;
        }
        long size = fileData != null ? fileData.length : currentFile.length();
        int totalLines = (int) ((size + bytesPerLine - 1) / bytesPerLine);
        fileInfoLabel.setText(String.format("  %s | %s | %d 字节 | %d 行 | 每行 %d 字节",
                currentFile.getName(), formatSize(size), size, totalLines, bytesPerLine));
    }

    // ==================== 选择同步 ====================

    private void syncSelection(JTextArea source, JTextArea target) {
        SwingUtilities.invokeLater(() -> {
            String sel = source.getSelectedText();
            if (sel == null || sel.isEmpty()) {
                selInfoLabel.setText("");
                return;
            }

            // 计算选中内容的字节偏移范围
            int[] byteRange = getByteRange(source, source.getSelectionStart(), source.getSelectionEnd());
            if (byteRange == null) return;

            int startByte = byteRange[0];
            int endByte = byteRange[1];

            // 显示选择信息
            StringBuilder info = new StringBuilder();
            info.append(String.format("选中: 0x%X ~ 0x%X (%d ~ %d), %d 字节",
                    startByte, endByte, startByte, endByte, endByte - startByte + 1));

            // 如果是 Hex 视图，显示常用数值
            if (source == hexArea && endByte - startByte + 1 <= 8) {
                info.append(" | ");
                long val = 0;
                for (int i = startByte; i <= endByte; i++) {
                    val = (val << 8) | (fileData[i] & 0xFF);
                }
                info.append(String.format("无符号: %d", val));
                if (endByte - startByte + 1 <= 4) {
                    info.append(String.format(", 有符号: %d", signExtend(val, endByte - startByte + 1)));
                }
            }
            selInfoLabel.setText(info.toString());
        });
    }

    /** 根据 JTextArea 的光标位置反推对应的文件字节偏移 */
    private int[] getByteRange(JTextArea area, int selStart, int selEnd) {
        if (fileData == null) return null;

        if (area == hexArea) {
            // Hex 行格式: "00000000  FF AA BB CC ..."
            String text = area.getText();
            int lineStart = text.lastIndexOf('\n', selStart - 1) + 1;
            String linePrefix = text.substring(lineStart, Math.min(lineStart + 10, text.length()));
            int lineOffset;
            try {
                lineOffset = Integer.parseInt(linePrefix.trim(), 16);
            } catch (NumberFormatException e) {
                return null;
            }

            int colOffset = Math.max(0, selStart - lineStart - 10);
            int byteIdx = colOffset / 3; // 每个字节占3字符 "FF "
            int colEndOffset = Math.max(0, selEnd - lineStart - 10);
            int byteEndIdx = (colEndOffset + 2) / 3;

            int startByte = Math.min(lineOffset + byteIdx, fileData.length - 1);
            int endByte = Math.min(lineOffset + byteEndIdx - 1, fileData.length - 1);
            if (startByte > endByte) { int t = startByte; startByte = endByte; endByte = t; }
            return new int[]{startByte, endByte};
        } else {
            // ASCII 视图：每个字符对应一个字节
            String text = area.getText();
            int lineStart = text.lastIndexOf('\n', selStart - 1) + 1;
            int lineNum = 0;
            int pos = 0;
            for (int i = 0; i < lineStart; i++) {
                if (text.charAt(i) == '\n') lineNum++;
            }
            int startCol = selStart - lineStart;
            int endCol = selEnd - lineStart;
            int startByte = lineNum * bytesPerLine + startCol;
            int endByte = lineNum * bytesPerLine + endCol - 1;
            if (startByte < 0) startByte = 0;
            if (endByte >= fileData.length) endByte = fileData.length - 1;
            if (startByte > endByte) { int t = startByte; startByte = endByte; endByte = t; }
            return new int[]{startByte, endByte};
        }
    }

    private long signExtend(long val, int bytes) {
        int bits = bytes * 8;
        long mask = 1L << (bits - 1);
        if ((val & mask) != 0) {
            val |= (~0L) << bits;
        }
        return val;
    }

    // ==================== 跳转 ====================

    private void gotoOffset() {
        if (fileData == null) {
            setStatus("请先打开文件");
            return;
        }

        String input = gotoOffsetField.getText().trim();
        if (input.isEmpty()) return;

        try {
            long offset;
            if (input.startsWith("0x") || input.startsWith("0X")) {
                offset = Long.parseLong(input.substring(2), 16);
            } else {
                offset = Long.parseLong(input);
            }

            if (offset < 0 || offset >= fileData.length) {
                setStatus("偏移超出范围 (0 ~ " + (fileData.length - 1) + ")");
                return;
            }

            // 计算目标行
            int targetLine = (int) (offset / bytesPerLine);

            // 滚动 Hex 视图到目标行
            scrollToLine(hexArea, hexScroll, targetLine);

            // 高亮目标字节（短暂选中）
            SwingUtilities.invokeLater(() -> {
                try {
                    hexArea.requestFocusInWindow();
                    int lineStart = 0;
                    String text = hexArea.getText();
                    for (int i = 0, l = 0; i < text.length(); i++) {
                        if (l == targetLine) { lineStart = i; break; }
                        if (text.charAt(i) == '\n') l++;
                    }
                    int byteInLine = (int) (offset % bytesPerLine);
                    int charPos = lineStart + 10 + byteInLine * 3; // 偏移地址10字符 + 每字节3字符
                    int endPos = Math.min(charPos + 2, text.length());
                    hexArea.select(charPos, endPos);
                } catch (Exception ignored) {}
            });

            setStatus("跳转到偏移 0x" + Long.toHexString(offset).toUpperCase() + " (" + offset + ")");
        } catch (NumberFormatException e) {
            setStatus("无效的偏移地址");
        }
    }

    private void scrollToLine(JTextArea area, JScrollPane scroll, int line) {
        try {
            int pos = 0;
            String text = area.getText();
            for (int i = 0, l = 0; i < text.length(); i++) {
                if (l == line) { pos = i; break; }
                if (text.charAt(i) == '\n') l++;
            }
            Rectangle rect = area.modelToView2D(pos).getBounds();
            if (rect != null) {
                rect.y = Math.max(0, rect.y - scroll.getViewport().getHeight() / 3);
                rect.height = scroll.getViewport().getHeight();
                area.scrollRectToVisible(rect);
            }
        } catch (Exception ignored) {}
    }

    // ==================== 导出 ====================

    private void exportHex() {
        if (fileData == null) return;

        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("导出 Hex 文本");
        chooser.setSelectedFile(new File("hex_dump.txt"));
        if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
            File f = chooser.getSelectedFile();
            try (PrintWriter pw = new PrintWriter(
                    new OutputStreamWriter(new FileOutputStream(f), java.nio.charset.StandardCharsets.UTF_8))) {
                pw.println("Offset    " + buildHexHeader() + "  ASCII");
                pw.println("-".repeat(10 + bytesPerLine * 3 + 2 + bytesPerLine));

                int totalLines = (fileData.length + bytesPerLine - 1) / bytesPerLine;
                for (int line = 0; line < totalLines; line++) {
                    int offset = line * bytesPerLine;
                    int lineEnd = Math.min(offset + bytesPerLine, fileData.length);

                    StringBuilder hexPart = new StringBuilder();
                    StringBuilder asciiPart = new StringBuilder();
                    for (int i = offset; i < offset + bytesPerLine; i++) {
                        if (i < lineEnd) {
                            int b = fileData[i] & 0xFF;
                            hexPart.append(String.format("%02X ", b));
                            asciiPart.append((b >= 0x20 && b < 0x7F) ? (char) b : '.');
                        } else {
                            hexPart.append("   ");
                        }
                    }
                    pw.printf("%08X  %-" + (bytesPerLine * 3) + "s %s%n",
                            offset, hexPart.toString(), asciiPart.toString());
                }
                setStatus("已导出到: " + f.getAbsolutePath());
            } catch (IOException e) {
                setStatus("导出失败: " + e.getMessage());
            }
        }
    }

    private String buildHexHeader() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < bytesPerLine; i++) {
            sb.append(String.format("%02X ", i));
        }
        return sb.toString();
    }

    // ==================== 辅助 ====================

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
        loadCombo(bytesPerLineCombo, config, "hexviewer.bytesPerLine");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveCombo(bytesPerLineCombo, config, "hexviewer.bytesPerLine");
    }
}
