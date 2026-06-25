package com.szh.utils;

import com.szh.manager.ConfigManager;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.text.*;
import java.awt.*;
import java.awt.datatransfer.Clipboard;
import java.awt.datatransfer.DataFlavor;
import java.awt.datatransfer.Transferable;
import java.awt.event.ActionEvent;
import java.io.EOFException;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SocketChannel;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;

/**
 * 网络面板共享工具类
 */
public class NetUtil {

    private static final Logger logger = LogManager.getLogger(NetUtil.class);

    // ===== 日志配色 =====
    public static final Color C_TIME  = new Color(0x888888);
    public static final Color C_SEND  = new Color(0x64B5F6);
    public static final Color C_RECV  = new Color(0x81C784);
    public static final Color C_SYS   = new Color(0xCE93D8);
    public static final Color C_ERR   = new Color(0xE57373);
    public static final Color C_WARN  = new Color(0xFFB74D);
    public static final Color C_BG    = new Color(0x1E1E1E);
    public static Font FONT_TEXT = new Font("Microsoft YaHei", Font.PLAIN, 13);
    public static Color TEXT_COLOR = Color.WHITE;

    /** 由 MainFrame 调用，更新全局字体引用 */
    public static void updateFont(String family, int size) {
        FONT_TEXT = new Font(family, Font.PLAIN, size);
    }

    // ===== 格式模式 =====
    public static final String[] FORMAT_MODES = {"明文 Text", "十六进制 HEX", "二进制 BIN"};

    // ===== 格式模式枚举 =====
    public enum FormatMode { TEXT, HEX, BIN }

    private static final DateTimeFormatter TS_FMT = DateTimeFormatter.ofPattern("HH:mm:ss.SSS");

    public static String ts() {
        return "[" + TS_FMT.format(LocalTime.now()) + "] ";
    }

    public static FormatMode fromComboIndex(int idx) {
        switch (idx) {
            case 1: return FormatMode.HEX;
            case 2: return FormatMode.BIN;
            default: return FormatMode.TEXT;
        }
    }

    // ==================== 编解码 ====================

    public static byte[] hexToBytes(String hex) {
        String h = hex.replaceAll("\\s+", "");
        int len = h.length();
        if (len % 2 != 0) throw new IllegalArgumentException("HEX 字符串长度必须为偶数");
        byte[] data = new byte[len / 2];
        for (int i = 0; i < len; i += 2) {
            data[i / 2] = (byte) ((Character.digit(h.charAt(i), 16) << 4)
                    + Character.digit(h.charAt(i + 1), 16));
        }
        return data;
    }

    public static String bytesToHex(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 3);
        for (byte b : bytes) {
            sb.append(String.format("%02X ", b & 0xFF));
        }
        return sb.toString().trim();
    }

    public static String bytesToBin(byte[] bytes) {
        StringBuilder sb = new StringBuilder(bytes.length * 9);
        for (byte b : bytes) {
            sb.append(String.format("%8s ", Integer.toBinaryString(b & 0xFF)).replace(' ', '0'));
        }
        return sb.toString().trim();
    }

    public static byte[] binToBytes(String bin) {
        String b = bin.replaceAll("\\s+", "");
        int len = b.length();
        if (len % 8 != 0) throw new IllegalArgumentException("二进制字符串长度必须为8的倍数");
        byte[] data = new byte[len / 8];
        for (int i = 0; i < len; i += 8) {
            data[i / 8] = (byte) Integer.parseInt(b.substring(i, i + 8), 2);
        }
        return data;
    }

    public static String formatBytes(byte[] data, FormatMode mode) {
        switch (mode) {
            case HEX: return "HEX: " + bytesToHex(data);
            case BIN: return "BIN: " + bytesToBin(data);
            default:  return decode(data, "UTF-8");
        }
    }

    public static String decode(byte[] data, String encoding) {
        try {
            return new String(data, Charset.forName(encoding));
        } catch (Exception e) {
            return new String(data, StandardCharsets.UTF_8);
        }
    }

    public static byte[] encodeByMode(String text, FormatMode mode, String encoding) {
        switch (mode) {
            case HEX: return hexToBytes(text);
            case BIN: return binToBytes(text);
            default:
                try {
                    return text.getBytes(Charset.forName(encoding));
                } catch (Exception e) {
                    return text.getBytes(StandardCharsets.UTF_8);
                }
        }
    }

    // ==================== 昵称解析 ====================

    public static String[] parseNickname(String msg) {
        if (msg != null && msg.startsWith("[") && msg.length() > 2) {
            int endIdx = msg.indexOf("]: ", 2);
            if (endIdx > 2) {
                return new String[]{msg.substring(1, endIdx), msg.substring(endIdx + 3)};
            }
        }
        return new String[]{null, msg};
    }

    public static String buildMsgWithNickname(String nickname, String content) {
        if (nickname == null || (nickname = nickname.trim()).isEmpty()) return content;
        return "[" + nickname + "]: " + content;
    }

    // ==================== NIO 工具 ====================

    public static List<String> extractLines(StringBuilder remainder, Charset charset) {
        List<String> lines = new ArrayList<>();
        String text = remainder.toString();
        int idx;
        while ((idx = text.indexOf('\n')) >= 0) {
            String line = text.substring(0, idx);
            if (line.endsWith("\r")) line = line.substring(0, line.length() - 1);
            lines.add(line);
            text = text.substring(idx + 1);
        }
        remainder.setLength(0);
        if (!text.isEmpty()) remainder.append(text);
        return lines;
    }

    public static void readIntoRemainder(SocketChannel sc, ByteBuffer buf, StringBuilder remainder, Charset charset) throws IOException {
        buf.clear();
        int n = sc.read(buf);
        if (n == -1) throw new EOFException("连接关闭");
        if (n > 0) {
            buf.flip();
            remainder.append(charset.decode(buf));
        }
    }

    // ==================== UI 组件工厂 ====================

    public static JTextPane createLogPane() {
        JTextPane pane = new JTextPane();
        pane.setEditable(false);
        pane.setFocusable(false);
        pane.setBackground(C_BG);
        pane.setCaretColor(new Color(0xD4D4D4));
        pane.setFont(FONT_TEXT);

        JPopupMenu popup = new JPopupMenu();
        JMenuItem clearItem = new JMenuItem("清空日志");
        clearItem.addActionListener(e -> pane.setText(""));
        popup.add(clearItem);
        pane.setComponentPopupMenu(popup);

        return pane;
    }

    public static JScrollPane createLogScroll(JTextPane pane) {
        JScrollPane sp = new JScrollPane(pane);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "日志", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        sp.setPreferredSize(new Dimension(400, 220));
        sp.setMinimumSize(new Dimension(200, 120));
        sp.getVerticalScrollBar().setUnitIncrement(16);
        return sp;
    }

    public static JButton makeBtn(String text, Color bg) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
        btn.setFocusPainted(false);
        btn.setMargin(new Insets(2, 10, 2, 10));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        if (bg != null) btn.setBackground(bg);
        return btn;
    }

    public static JComboBox<String> createFormatCombo() {
        JComboBox<String> combo = new JComboBox<>(FORMAT_MODES);
        combo.setPreferredSize(new Dimension(115, 24));
        return combo;
    }

    /**
     * 修复从 IntelliJ IDEA 复制后粘贴时 ClassNotFoundException 报错。
     * IntelliJ 会在剪贴板中放入自定义 DataFlavor（如 FoldingData），
     * JTextComponent 默认 paste() 会枚举所有 Flavor 并尝试加载其类，
     * 导致打印 ClassNotFoundException 到 stderr。
     * 此方法替换默认粘贴动作为安全版本，仅请求 stringFlavor。
     */
    public static void fixPaste(JTextComponent comp) {
        comp.getActionMap().put("paste-from-clipboard", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                try {
                    Clipboard cb = Toolkit.getDefaultToolkit().getSystemClipboard();
                    Transferable contents = cb.getContents(null);
                    if (contents != null && contents.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                        String text = (String) contents.getTransferData(DataFlavor.stringFlavor);
                        if (text != null) {
                            comp.replaceSelection(text);
                            return;
                        }
                    }
                } catch (Exception ignored) {
                    // 安全粘贴失败时回退到默认 paste
                }
                // 回退：调用原始 paste（可能打印错误但至少功能正常）
                comp.paste();
            }
        });
    }

    // ==================== 日志方法 ====================

    /** 单个日志面板最大保留行数，超出后从头部裁剪 */
    private static final int MAX_LOG_LINES = 5000;

    public static void appendLog(JTextPane log, String prefix, String content, Color cPrefix, Color cContent) {
        if (log == null) return;
        SwingUtilities.invokeLater(() -> {
            try {
                StyledDocument doc = log.getStyledDocument();
                Style sTime = log.addStyle("time", null);
                StyleConstants.setForeground(sTime, C_TIME);
                StyleConstants.setFontSize(sTime, 11);

                doc.insertString(doc.getLength(), prefix, sTime);

                if (cPrefix != null) {
                    Style sP = log.addStyle("p" + System.nanoTime(), null);
                    StyleConstants.setForeground(sP, cPrefix);
                    doc.insertString(doc.getLength(), content, sP);
                }
                if (cContent != null && cContent != cPrefix) {
                    Style sC = log.addStyle("c" + System.nanoTime(), null);
                    StyleConstants.setForeground(sC, cContent);
                    doc.insertString(doc.getLength(), content, sC);
                }

                doc.insertString(doc.getLength(), "\n", null);

                // 超出最大行数时从头部裁剪
                String text = doc.getText(0, doc.getLength());
                int lines = countLines(text);
                if (lines > MAX_LOG_LINES) {
                    int excess = lines - MAX_LOG_LINES;
                    int pos = 0;
                    for (int i = 0; i < excess && pos >= 0; i++) {
                        pos = text.indexOf('\n', pos);
                        if (pos >= 0) pos++;
                    }
                    if (pos > 0) {
                        doc.remove(0, pos);
                    }
                }

                log.setCaretPosition(doc.getLength());
            } catch (BadLocationException e) {
                logger.warn("文本区域位置异常", e);
            }
        });
    }

    private static int countLines(String text) {
        int count = 0;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '\n') count++;
        }
        return count;
    }

    public static void logSys(JTextPane log, String msg)  { appendLog( log, ts(), msg, C_SYS, null); }
    public static void logRecv(JTextPane log, String msg)  { appendLog(log, ts(), msg, C_RECV, null); }
    public static void logSend(JTextPane log, String msg)  { appendLog(log, ts(), msg, C_SEND, null); }
    public static void logErr(JTextPane log, String msg)   { appendLog(log, ts(), msg, C_ERR, null); }
    public static void logWarn(JTextPane log, String msg)  { appendLog(log, ts(), msg, C_WARN, null); }

    // ==================== 配置辅助 ====================

    public static void loadField(JTextField f, ConfigManager cfg, String key) {
        String v = cfg.get(key, null);
        if (v != null && !v.isEmpty()) f.setText(v);
    }

    public static void saveField(JTextField f, ConfigManager cfg, String key) {
        cfg.set(key, f.getText().trim());
    }

    public static void loadField(JCheckBox cb, ConfigManager cfg, String key) {
        String v = cfg.get(key, null);
        if (v != null && !v.isEmpty()) cb.setSelected(Boolean.parseBoolean(v));
    }

    public static void saveField(JCheckBox cb, ConfigManager cfg, String key) {
        cfg.set(key, String.valueOf(cb.isSelected()));
    }

    public static void loadArea(JTextArea a, ConfigManager cfg, String key) {
        String v = cfg.get(key, null);
        if (v != null && !v.isEmpty()) a.setText(v);
    }

    public static void saveArea(JTextArea a, ConfigManager cfg, String key) {
        cfg.set(key, a.getText().trim());
    }

    public static void loadCombo(JComboBox<String> c, ConfigManager cfg, String key) {
        String v = cfg.get(key, null);
        if (v != null && !v.isEmpty()) c.setSelectedItem(v);
    }

    public static void saveCombo(JComboBox<String> c, ConfigManager cfg, String key) {
        Object sel = c.getSelectedItem();
        if (sel != null) cfg.set(key, sel.toString());
    }

    // ==================== 通用工具方法 ====================

    /** 格式化文件大小为易读字符串 */
    public static String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024.0));
        return String.format("%.2f GB", bytes / (1024.0 * 1024.0 * 1024.0));
    }

    /** HTML 转义，防止 XSS */
    public static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }

    /** 根据文件名后缀推断 Content-Type（MIME 类型） */
    public static String getContentType(String fileName) {
        String name = fileName.toLowerCase();
        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".svg")) return "image/svg+xml";
        if (name.endsWith(".ico")) return "image/x-icon";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) return "image/jpeg";
        if (name.endsWith(".mp4")) return "video/mp4";
        if (name.endsWith(".webm")) return "video/webm";
        if (name.endsWith(".ogg")) return "video/ogg";
        if (name.endsWith(".mp3")) return "audio/mpeg";
        if (name.endsWith(".wav")) return "audio/wav";
        if (name.endsWith(".pdf")) return "application/pdf";
        if (name.endsWith(".json")) return "application/json";
        if (name.endsWith(".xml")) return "application/xml";
        if (name.endsWith(".html") || name.endsWith(".htm")) return "text/html; charset=utf-8";
        if (name.endsWith(".css")) return "text/css; charset=utf-8";
        if (name.endsWith(".js")) return "application/javascript; charset=utf-8";
        if (name.endsWith(".txt") || name.endsWith(".md") || name.endsWith(".log")) return "text/plain; charset=utf-8";
        if (name.endsWith(".zip")) return "application/zip";
        return "application/octet-stream";
    }
}
