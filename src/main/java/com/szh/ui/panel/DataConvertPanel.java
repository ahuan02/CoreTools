package com.szh.ui.panel;

import com.szh.manager.ConfigManager;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.io.UnsupportedEncodingException;
import java.net.URLDecoder;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.Base64;
import java.util.Date;
import java.util.TimeZone;

import static com.szh.utils.NetUtil.*;

/**
 * 数据编码转换工具集：进制转换 / 字符串编码 / Base64 / URL编解码 / 时间戳
 */
public class DataConvertPanel extends AbstractCommandPanel {

    // ===== 进制转换 =====
    private JTextField radixDecField, radixHexField, radixOctField, radixBinField;
    private JTextField radixSignedField;
    private JCheckBox radixMultiBox;
    private JButton btnSwapEndian;
    private JTextArea radixInfoArea;
    private boolean ignoreCaretEvent;

    // ===== 字符串编码 =====
    private JTextField strInputField;
    private JTextArea strHexArea, strTextArea;
    private JComboBox<String> strEncFromCombo, strEncToCombo;

    // ===== Base64 =====
    private JTextField b64TextField;
    private JTextArea b64ResultArea;
    private JButton btnB64Encode, btnB64Decode;

    // ===== URL =====
    private JTextField urlTextField;
    private JTextArea urlResultArea;
    private JButton btnUrlEncode, btnUrlDecode;

    // ===== 时间戳 =====
    private JTextField tsField;
    private JComboBox<String> tsUnitCombo;
    private JComboBox<String> tsZoneCombo;
    private JTextArea tsResultArea;
    private JButton btnTsToDate, btnTsToNow, btnDateToTs;
    private JSpinner tsDateSpinner;
    private JTextField tsBatchField;
    private JButton btnTsBatch;
    private JTable tsBatchTable;
    private DefaultTableModel tsBatchModel;

    // ===== 校验算法 =====
    private JTextField crcInputField;
    private JComboBox<String> crcAlgoCombo;
    private JComboBox<String> crcFormatCombo;
    private JTextArea crcResultArea;
    private JButton btnCrcCalc;

    // ===== 加解密 =====
    private JTextField cryptoInputField;
    private JTextField cryptoKeyField;
    private JTextField cryptoIvField;
    private JComboBox<String> cryptoAlgoCombo;
    private JComboBox<String> cryptoModeCombo;
    private JComboBox<String> cryptoPadCombo;
    private JTextArea cryptoResultArea;
    private JButton btnCryptoEncrypt, btnCryptoDecrypt;

    public DataConvertPanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        setLayout(new BorderLayout(4, 4));

        JTabbedPane innerTabs = new JTabbedPane(JTabbedPane.TOP);
        innerTabs.addTab("进制转换", buildRadixPanel());
        innerTabs.addTab("字符串编码", buildStringPanel());
        innerTabs.addTab("Base64", buildBase64Panel());
        innerTabs.addTab("URL编解码", buildUrlPanel());
        innerTabs.addTab("时间戳", buildTimestampPanel());
        innerTabs.addTab("校验算法", buildCrcPanel());
        innerTabs.addTab("加解密", buildCryptoPanel());
        add(innerTabs, BorderLayout.CENTER);
    }

    // ==================== 1. 进制转换 ====================

    private JPanel buildRadixPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // 顶部控制区
        JPanel top = new JPanel(new GridLayout(2, 1, 0, 4));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.add(new JLabel("十进制:"));
        radixDecField = new JTextField("255", 10);
        radixDecField.setFont(FONT_TEXT);
        radixDecField.addCaretListener(e -> onDecChanged());
        row1.add(radixDecField);

        row1.add(new JLabel("十六进制:"));
        radixHexField = new JTextField("0xFF", 10);
        radixHexField.setFont(FONT_TEXT);
        radixHexField.addCaretListener(e -> onHexChanged());
        row1.add(radixHexField);

        row1.add(new JLabel("八进制:"));
        radixOctField = new JTextField("377", 8);
        radixOctField.setFont(FONT_TEXT);
        radixOctField.addCaretListener(e -> onOctChanged());
        row1.add(radixOctField);

        row1.add(new JLabel("二进制:"));
        radixBinField = new JTextField("11111111", 16);
        radixBinField.setFont(FONT_TEXT);
        radixBinField.addCaretListener(e -> onBinChanged());
        row1.add(radixBinField);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.add(new JLabel("有符号值:"));
        radixSignedField = new JTextField(8);
        radixSignedField.setFont(FONT_TEXT);
        radixSignedField.setEditable(false);
        radixSignedField.setBackground(new Color(0x2A2A2A));
        radixSignedField.setForeground(new Color(0xFFB74D));
        row2.add(radixSignedField);

        radixMultiBox = new JCheckBox("多字节模式");
        radixMultiBox.setFont(FONT_TEXT);
        radixMultiBox.setToolTipText("勾选后输入输出均按字节拆分，便于查看嵌入式大小端数据");
        radixMultiBox.addActionListener(e -> refreshRadix());
        row2.add(radixMultiBox);

        btnSwapEndian = makeBtn("高低位互换", new Color(0xAB47BC));
        btnSwapEndian.addActionListener(e -> swapEndian());
        row2.add(btnSwapEndian);

        top.add(row1);
        top.add(row2);
        panel.add(top, BorderLayout.NORTH);

        // 信息展示区
        radixInfoArea = new JTextArea();
        radixInfoArea.setFont(FONT_TEXT);
        radixInfoArea.setEditable(false);
        radixInfoArea.setBackground(new Color(0x1E1E1E));
        radixInfoArea.setForeground(new Color(0xAAAAAA));
        radixInfoArea.setBorder(BorderFactory.createEmptyBorder(4, 4, 4, 4));
        JScrollPane sp = new JScrollPane(radixInfoArea);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "按字节展示", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        panel.add(sp, BorderLayout.CENTER);

        refreshRadix();
        return panel;
    }

    private void onDecChanged() { if (!ignoreCaretEvent) syncFrom("DEC"); }
    private void onHexChanged() { if (!ignoreCaretEvent) syncFrom("HEX"); }
    private void onOctChanged() { if (!ignoreCaretEvent) syncFrom("OCT"); }
    private void onBinChanged() { if (!ignoreCaretEvent) syncFrom("BIN"); }

    private void syncFrom(String source) {
        ignoreCaretEvent = true;
        try {
            long val;
            switch (source) {
                case "DEC": val = Long.parseLong(radixDecField.getText().trim()); break;
                case "HEX": val = Long.parseLong(radixHexField.getText().trim().replace("0x", "").replace("0X", "").replace(" ", ""), 16); break;
                case "OCT": val = Long.parseLong(radixOctField.getText().trim(), 8); break;
                case "BIN": val = Long.parseLong(radixBinField.getText().trim().replace(" ", ""), 2); break;
                default: return;
            }
            updateRadixFields(val, source);
        } catch (NumberFormatException ignored) {
        } finally {
            ignoreCaretEvent = false;
        }
    }

    private void updateRadixFields(long val, String source) {
        ignoreCaretEvent = true;
        try {
            if (!"DEC".equals(source)) radixDecField.setText(String.valueOf(val));
            if (!"HEX".equals(source)) {
                if (radixMultiBox.isSelected()) {
                    radixHexField.setText("0x" + toMultiByteHex(val));
                } else {
                    radixHexField.setText("0x" + Long.toHexString(val).toUpperCase());
                }
            }
            if (!"OCT".equals(source)) radixOctField.setText(Long.toOctalString(val));
            if (!"BIN".equals(source)) {
                if (radixMultiBox.isSelected()) {
                    radixBinField.setText(toMultiByteBin(val));
                } else {
                    String bin = Long.toBinaryString(val);
                    // 补齐到 8 的倍数位
                    int pad = ((bin.length() + 7) / 8) * 8;
                    radixBinField.setText(String.format("%" + pad + "s", bin).replace(' ', '0'));
                }
            }
            // 有符号值
            radixSignedField.setText(String.valueOf(val));
            // 字节展示
            refreshRadixInfo(val);
        } finally {
            ignoreCaretEvent = false;
        }
    }

    private String toMultiByteHex(long val) {
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (int i = 7; i >= 0; i--) {
            int b = (int) ((val >> (i * 8)) & 0xFF);
            if (b != 0 || started || i == 0) {
                if (started) sb.append(" ");
                sb.append(String.format("%02X", b));
                started = true;
            }
        }
        return sb.toString();
    }

    private String toMultiByteBin(long val) {
        StringBuilder sb = new StringBuilder();
        boolean started = false;
        for (int i = 7; i >= 0; i--) {
            int b = (int) ((val >> (i * 8)) & 0xFF);
            if (b != 0 || started || i == 0) {
                if (started) sb.append(" ");
                String bin = Integer.toBinaryString(b);
                while (bin.length() < 8) bin = "0" + bin;
                sb.append(bin);
                started = true;
            }
        }
        return sb.toString();
    }

    private void refreshRadix() {
        try {
            long val = Long.parseLong(radixDecField.getText().trim());
            updateRadixFields(val, "FORCE");
        } catch (NumberFormatException ignored) {}
    }

    private void refreshRadixInfo(long val) {
        StringBuilder sb = new StringBuilder();
        sb.append(String.format("十进制 (有符号): %d\n", val));
        sb.append(String.format("十进制 (无符号): %s\n", Long.toUnsignedString(val)));
        sb.append(String.format("十六进制 (LE): %016X\n", val));
        sb.append(String.format("十六进制 (BE): %s\n", toBigEndianHex(val)));
        sb.append(String.format("二进制 (32bit): %s\n", formatBin32(val)));
        sb.append(String.format("int32 有符号: %d\n", (int) val));
        sb.append(String.format("int32 无符号: %s\n", Integer.toUnsignedString((int) val)));
        sb.append(String.format("int16 有符号: %d\n", (short) val));
        sb.append(String.format("int16 无符号: %d\n", (int) (val & 0xFFFF)));
        sb.append(String.format("int8 有符号: %d\n", (byte) val));
        sb.append(String.format("int8 无符号: %d\n", (int) (val & 0xFF)));
        radixInfoArea.setText(sb.toString());
    }

    private String toBigEndianHex(long val) {
        StringBuilder sb = new StringBuilder();
        for (int i = 7; i >= 0; i--) {
            sb.append(String.format("%02X", (val >> (i * 8)) & 0xFF));
        }
        return sb.toString();
    }

    private String formatBin32(long val) {
        StringBuilder sb = new StringBuilder();
        for (int i = 31; i >= 0; i--) {
            sb.append((val >> i) & 1);
            if (i % 8 == 0 && i > 0) sb.append(" ");
        }
        return sb.toString();
    }

    private void swapEndian() {
        try {
            long val = Long.parseLong(radixDecField.getText().trim());
            long swapped = 0;
            for (int i = 0; i < 8; i++) {
                swapped |= ((val >> (i * 8)) & 0xFF) << ((7 - i) * 8);
            }
            updateRadixFields(swapped, "FORCE");
        } catch (NumberFormatException ignored) {}
    }

    // ==================== 2. 字符串编码转换 ====================

    private JPanel buildStringPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // 输入区
        JPanel topArea = new JPanel(new BorderLayout(4, 4));
        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ctrlRow.add(new JLabel("原文:"));
        strInputField = new JTextField(30);
        strInputField.setFont(FONT_TEXT);
        strInputField.setToolTipText("输入要转换的字符串");
        ctrlRow.add(strInputField);

        strEncFromCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ASCII", "ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-16LE"});
        strEncFromCombo.setFont(FONT_TEXT);
        ctrlRow.add(new JLabel(" 源编码:"));
        ctrlRow.add(strEncFromCombo);

        strEncToCombo = new JComboBox<>(new String[]{"UTF-8", "GBK", "GB2312", "ASCII", "ISO-8859-1", "UTF-16", "UTF-16BE", "UTF-16LE"});
        strEncToCombo.setFont(FONT_TEXT);
        strEncToCombo.setSelectedIndex(1); // GBK
        ctrlRow.add(new JLabel("目标编码:"));
        ctrlRow.add(strEncToCombo);

        JButton btnConvert = makeBtn("转换", new Color(0x42A5F5));
        btnConvert.addActionListener(e -> doStringConvert());
        ctrlRow.add(btnConvert);
        topArea.add(ctrlRow, BorderLayout.NORTH);

        // 十六进制 + 字符串输出
        JPanel outputArea = new JPanel(new GridLayout(1, 2, 4, 0));

        JPanel hexPanel = new JPanel(new BorderLayout());
        strHexArea = new JTextArea();
        strHexArea.setFont(FONT_TEXT);
        strHexArea.setEditable(false);
        strHexArea.setBackground(new Color(0x1E1E1E));
        strHexArea.setForeground(new Color(0x64B5F6));
        strHexArea.setLineWrap(true);
        JScrollPane hexSp = new JScrollPane(strHexArea);
        hexSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "十六进制", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        hexPanel.add(hexSp, BorderLayout.CENTER);

        JPanel hexBtnRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 2, 0));
        JButton btnHexUpper = makeBtn("大写", new Color(0x78909C));
        btnHexUpper.addActionListener(e -> { strHexArea.setText(strHexArea.getText().toUpperCase()); });
        hexBtnRow.add(btnHexUpper);
        JButton btnHexLower = makeBtn("小写", new Color(0x78909C));
        btnHexLower.addActionListener(e -> { strHexArea.setText(strHexArea.getText().toLowerCase()); });
        hexBtnRow.add(btnHexLower);
        JButton btnHexSpace = makeBtn("补空格", new Color(0x78909C));
        btnHexSpace.addActionListener(e -> {
            String s = strHexArea.getText().replace(" ", "");
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < s.length(); i += 2) {
                if (i > 0) sb.append(" ");
                sb.append(s.substring(i, Math.min(i + 2, s.length())));
            }
            strHexArea.setText(sb.toString());
        });
        hexBtnRow.add(btnHexSpace);
        JButton btnHexFromStr = makeBtn("原文→Hex", new Color(0xFFB74D));
        btnHexFromStr.addActionListener(e -> doStringToHex());
        hexBtnRow.add(btnHexFromStr);
        JButton btnHexToStr = makeBtn("Hex→原文", new Color(0xFFB74D));
        btnHexToStr.addActionListener(e -> doHexToString());
        hexBtnRow.add(btnHexToStr);
        hexPanel.add(hexBtnRow, BorderLayout.SOUTH);
        outputArea.add(hexPanel);

        JPanel textPanel = new JPanel(new BorderLayout());
        strTextArea = new JTextArea();
        strTextArea.setFont(FONT_TEXT);
        strTextArea.setEditable(false);
        strTextArea.setBackground(new Color(0x1E1E1E));
        strTextArea.setForeground(new Color(0x81C784));
        strTextArea.setLineWrap(true);
        JScrollPane textSp = new JScrollPane(strTextArea);
        textSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "文本", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        textPanel.add(textSp, BorderLayout.CENTER);
        outputArea.add(textPanel);

        topArea.add(outputArea, BorderLayout.CENTER);
        panel.add(topArea, BorderLayout.CENTER);
        return panel;
    }

    private void doStringConvert() {
        String input = strInputField.getText();
        String fromEnc = (String) strEncFromCombo.getSelectedItem();
        String toEnc = (String) strEncToCombo.getSelectedItem();
        if (input.isEmpty()) return;

        try {
            byte[] bytes = input.getBytes(fromEnc);
            String hex = bytesToHex(bytes);
            String result = new String(bytes, toEnc);
            strHexArea.setText(hex);
            strTextArea.setText(result);
        } catch (UnsupportedEncodingException e) {
            strTextArea.setText("编码错误: " + e.getMessage());
        }
    }

    private void doStringToHex() {
        String input = strInputField.getText();
        String fromEnc = (String) strEncFromCombo.getSelectedItem();
        if (input.isEmpty()) return;
        try {
            byte[] bytes = input.getBytes(fromEnc);
            strHexArea.setText(bytesToHex(bytes));
        } catch (UnsupportedEncodingException e) {
            strHexArea.setText("错误: " + e.getMessage());
        }
    }

    private void doHexToString() {
        String hex = strHexArea.getText().replace(" ", "").replace("\n", "").trim();
        if (hex.isEmpty()) return;
        try {
            byte[] bytes = hexToBytes(hex);
            String toEnc = (String) strEncToCombo.getSelectedItem();
            strTextArea.setText(new String(bytes, toEnc));
        } catch (Exception e) {
            strTextArea.setText("解析错误: " + e.getMessage());
        }
    }

    // ==================== 3. Base64 ====================

    private JPanel buildBase64Panel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ctrlRow.add(new JLabel("文本:"));
        b64TextField = new JTextField(30);
        b64TextField.setFont(FONT_TEXT);
        ctrlRow.add(b64TextField);

        btnB64Encode = makeBtn("编码", new Color(0x4CAF50));
        btnB64Encode.addActionListener(e -> doBase64Encode());
        ctrlRow.add(btnB64Encode);

        btnB64Decode = makeBtn("解码", new Color(0xE57373));
        btnB64Decode.addActionListener(e -> doBase64Decode());
        ctrlRow.add(btnB64Decode);

        panel.add(ctrlRow, BorderLayout.NORTH);

        b64ResultArea = new JTextArea();
        b64ResultArea.setFont(FONT_TEXT);
        b64ResultArea.setEditable(false);
        b64ResultArea.setBackground(new Color(0x1E1E1E));
        b64ResultArea.setForeground(new Color(0x81C784));
        b64ResultArea.setLineWrap(true);
        JScrollPane sp = new JScrollPane(b64ResultArea);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "结果", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private void doBase64Encode() {
        String input = b64TextField.getText();
        if (input.isEmpty()) return;
        b64ResultArea.setText(Base64.getEncoder().encodeToString(input.getBytes(StandardCharsets.UTF_8)));
    }

    private void doBase64Decode() {
        String input = b64TextField.getText().trim();
        if (input.isEmpty()) return;
        try {
            byte[] decoded = Base64.getDecoder().decode(input);
            b64ResultArea.setText(new String(decoded, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            b64ResultArea.setText("解码失败：输入不是有效的 Base64 字符串\n\n" +
                    "提示：Base64 仅包含 A-Z a-z 0-9 + / = 字符\n" +
                    "如需对中文编码，请点击 [编码] 按钮");
        }
    }

    // ==================== 4. URL 编解码 ====================

    private JPanel buildUrlPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        JPanel ctrlRow = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        ctrlRow.add(new JLabel("文本:"));
        urlTextField = new JTextField(30);
        urlTextField.setFont(FONT_TEXT);
        ctrlRow.add(urlTextField);

        btnUrlEncode = makeBtn("编码", new Color(0x4CAF50));
        btnUrlEncode.addActionListener(e -> doUrlEncode());
        ctrlRow.add(btnUrlEncode);

        btnUrlDecode = makeBtn("解码", new Color(0xE57373));
        btnUrlDecode.addActionListener(e -> doUrlDecode());
        ctrlRow.add(btnUrlDecode);

        panel.add(ctrlRow, BorderLayout.NORTH);

        urlResultArea = new JTextArea();
        urlResultArea.setFont(FONT_TEXT);
        urlResultArea.setEditable(false);
        urlResultArea.setBackground(new Color(0x1E1E1E));
        urlResultArea.setForeground(new Color(0x81C784));
        urlResultArea.setLineWrap(true);
        JScrollPane sp = new JScrollPane(urlResultArea);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "结果", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private void doUrlEncode() {
        String input = urlTextField.getText();
        if (input.isEmpty()) return;
        try {
            urlResultArea.setText(URLEncoder.encode(input, StandardCharsets.UTF_8));
        } catch (Exception e) {
            urlResultArea.setText("编码失败: " + e.getMessage());
        }
    }

    private void doUrlDecode() {
        String input = urlTextField.getText().trim();
        if (input.isEmpty()) return;
        try {
            urlResultArea.setText(URLDecoder.decode(input, StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            urlResultArea.setText("解码失败：URL 编码格式不正确\n\n" +
                    "提示：URL 编码格式为 %XX（如 %E4%B8%AD 代表'中'）\n" +
                    "如需对中文编码，请点击 [编码] 按钮");
        } catch (Exception e) {
            urlResultArea.setText("解码失败: " + e.getMessage());
        }
    }

    // ==================== 5. 时间戳转换 ====================

    private JPanel buildTimestampPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // 控制区
        JPanel ctrlArea = new JPanel(new GridLayout(3, 1, 0, 2));

        // 行1：时间戳输入
        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.add(new JLabel("时间戳:"));
        tsField = new JTextField(15);
        tsField.setFont(FONT_TEXT);
        row1.add(tsField);

        tsUnitCombo = new JComboBox<>(new String[]{"秒", "毫秒"});
        tsUnitCombo.setFont(FONT_TEXT);
        row1.add(tsUnitCombo);

        tsZoneCombo = new JComboBox<>(new String[]{"北京时间 (UTC+8)", "UTC"});
        tsZoneCombo.setFont(FONT_TEXT);
        row1.add(tsZoneCombo);

        btnTsToDate = makeBtn("→日期", new Color(0x42A5F5));
        btnTsToDate.addActionListener(e -> doTsToDate());
        row1.add(btnTsToDate);

        btnTsToNow = makeBtn("当前时间戳", new Color(0x66BB6A));
        btnTsToNow.addActionListener(e -> doTsNow());
        row1.add(btnTsToNow);

        // 行2：日期选择 → 时间戳
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.add(new JLabel("日期:"));
        tsDateSpinner = new JSpinner(new SpinnerDateModel());
        JSpinner.DateEditor dateEditor = new JSpinner.DateEditor(tsDateSpinner, "yyyy-MM-dd HH:mm:ss");
        tsDateSpinner.setEditor(dateEditor);
        tsDateSpinner.setFont(FONT_TEXT);
        tsDateSpinner.setValue(new Date());
        row2.add(tsDateSpinner);

        btnDateToTs = makeBtn("→时间戳", new Color(0xAB47BC));
        btnDateToTs.addActionListener(e -> doDateToTs());
        row2.add(btnDateToTs);

        // 行3：批量转换
        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row3.add(new JLabel("批量:"));
        tsBatchField = new JTextField(30);
        tsBatchField.setFont(FONT_TEXT);
        tsBatchField.setToolTipText("多个时间戳用逗号或空格分隔");
        row3.add(tsBatchField);

        btnTsBatch = makeBtn("批量转换", new Color(0xFFB74D));
        btnTsBatch.addActionListener(e -> doTsBatch());
        row3.add(btnTsBatch);

        ctrlArea.add(row1);
        ctrlArea.add(row2);
        ctrlArea.add(row3);
        panel.add(ctrlArea, BorderLayout.NORTH);

        // 结果展示
        JPanel resultArea = new JPanel(new GridLayout(1, 2, 4, 0));

        tsResultArea = new JTextArea();
        tsResultArea.setFont(FONT_TEXT);
        tsResultArea.setEditable(false);
        tsResultArea.setBackground(new Color(0x1E1E1E));
        tsResultArea.setForeground(new Color(0x81C784));
        JScrollPane sp = new JScrollPane(tsResultArea);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "单次结果", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        resultArea.add(sp);

        // 批量转换表格
        String[] batchCols = {"序号", "时间戳", "北京时间", "UTC"};
        tsBatchModel = new DefaultTableModel(batchCols, 0) {
            @Override
            public boolean isCellEditable(int row, int col) { return false; }
        };
        tsBatchTable = new JTable(tsBatchModel);
        tsBatchTable.setFont(FONT_TEXT);
        tsBatchTable.setRowHeight(22);
        tsBatchTable.getTableHeader().setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        tsBatchTable.setSelectionBackground(new Color(0x333333));
        tsBatchTable.setSelectionForeground(new Color(0x64B5F6));
        tsBatchTable.getColumnModel().getColumn(0).setPreferredWidth(40);
        tsBatchTable.getColumnModel().getColumn(1).setPreferredWidth(120);
        tsBatchTable.getColumnModel().getColumn(2).setPreferredWidth(160);
        tsBatchTable.getColumnModel().getColumn(3).setPreferredWidth(160);

        JScrollPane batchSp = new JScrollPane(tsBatchTable);
        batchSp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "批量转换结果", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        resultArea.add(batchSp);

        panel.add(resultArea, BorderLayout.CENTER);
        return panel;
    }

    private void doTsToDate() {
        String tsStr = tsField.getText().trim();
        if (tsStr.isEmpty()) return;
        try {
            long ts = Long.parseLong(tsStr);
            boolean isMillis = tsUnitCombo.getSelectedIndex() == 1;
            if (!isMillis) ts *= 1000;

            Date date = new Date(ts);
            SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
            if (tsZoneCombo.getSelectedIndex() == 0) {
                sdf.setTimeZone(TimeZone.getTimeZone("Asia/Shanghai"));
            } else {
                sdf.setTimeZone(TimeZone.getTimeZone("UTC"));
            }

            StringBuilder sb = new StringBuilder();
            sb.append("时间戳: ").append(tsStr).append(isMillis ? " 毫秒" : " 秒").append("\n");
            sb.append("北京时间: ").append(formatDate(ts, "Asia/Shanghai")).append("\n");
            sb.append("UTC: ").append(formatDate(ts, "UTC")).append("\n");
            sb.append("ISO8601: ").append(formatISO8601(ts, "Asia/Shanghai")).append("\n");
            sb.append("星期: ").append(formatDayOfWeek(ts, "Asia/Shanghai"));
            tsResultArea.setText(sb.toString());
        } catch (NumberFormatException e) {
            tsResultArea.setText("时间戳格式错误");
        }
    }

    private void doTsNow() {
        long ms = System.currentTimeMillis();
        boolean isMillis = tsUnitCombo.getSelectedIndex() == 1;
        tsField.setText(isMillis ? String.valueOf(ms) : String.valueOf(ms / 1000));
        doTsToDate();
    }

    private void doDateToTs() {
        Date date = (Date) tsDateSpinner.getValue();
        long ms = date.getTime();
        boolean isMillis = tsUnitCombo.getSelectedIndex() == 1;
        tsField.setText(isMillis ? String.valueOf(ms) : String.valueOf(ms / 1000));
        doTsToDate();
    }

    private void doTsBatch() {
        String input = tsBatchField.getText().trim();
        if (input.isEmpty()) return;
        tsBatchModel.setRowCount(0);

        boolean isMillis = tsUnitCombo.getSelectedIndex() == 1;
        String[] parts = input.split("[,\\s]+");
        int seq = 1;
        for (String part : parts) {
            try {
                long ts = Long.parseLong(part.trim());
                if (!isMillis) ts *= 1000;
                tsBatchModel.addRow(new Object[]{
                        seq++, part.trim(),
                        formatDate(ts, "Asia/Shanghai"),
                        formatDate(ts, "UTC")
                });
            } catch (NumberFormatException ignored) {}
        }
    }

    private String formatDate(long ms, String tz) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
        sdf.setTimeZone(TimeZone.getTimeZone(tz));
        return sdf.format(new Date(ms));
    }

    private String formatISO8601(long ms, String tz) {
        SimpleDateFormat sdf = new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSXXX");
        sdf.setTimeZone(TimeZone.getTimeZone(tz));
        return sdf.format(new Date(ms));
    }

    private String formatDayOfWeek(long ms, String tz) {
        SimpleDateFormat sdf = new SimpleDateFormat("EEEE");
        sdf.setTimeZone(TimeZone.getTimeZone(tz));
        return sdf.format(new Date(ms));
    }

    // ==================== 6. 校验算法 ====================

    private JPanel buildCrcPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // 控制区
        JPanel ctrlArea = new JPanel(new GridLayout(2, 1, 0, 2));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.add(new JLabel("数据:"));
        crcInputField = new JTextField(28);
        crcInputField.setFont(FONT_TEXT);
        crcInputField.setToolTipText("输入文本或十六进制（如 AABBCC 或 0xAA 0xBB 0xCC）");
        row1.add(crcInputField);

        crcAlgoCombo = new JComboBox<>(new String[]{
                "CRC8", "CRC8-MAXIM", "CRC16", "CRC16-Modbus", "CRC32",
                "累加和 (Sum8)", "异或 (XOR8)", "累加和 (Sum16)", "异或 (XOR16)"
        });
        crcAlgoCombo.setFont(FONT_TEXT);
        row1.add(crcAlgoCombo);

        crcFormatCombo = new JComboBox<>(new String[]{"文本 (UTF-8)", "十六进制 (HEX)"});
        crcFormatCombo.setFont(FONT_TEXT);
        row1.add(crcFormatCombo);

        btnCrcCalc = makeBtn("计算", new Color(0x42A5F5));
        btnCrcCalc.addActionListener(e -> doCrcCalc());
        row1.add(btnCrcCalc);

        ctrlArea.add(row1);

        // 提示行
        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.add(new JLabel("提示：十六进制模式输入如 AABBCC 或 0xAA 0xBB 0xCC，不区分大小写"));
        row2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
        ctrlArea.add(row2);

        panel.add(ctrlArea, BorderLayout.NORTH);

        crcResultArea = new JTextArea();
        crcResultArea.setFont(FONT_TEXT);
        crcResultArea.setEditable(false);
        crcResultArea.setBackground(new Color(0x1E1E1E));
        crcResultArea.setForeground(new Color(0x81C784));
        JScrollPane sp = new JScrollPane(crcResultArea);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "计算结果", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        panel.add(sp, BorderLayout.CENTER);
        return panel;
    }

    private void doCrcCalc() {
        String input = crcInputField.getText().trim();
        if (input.isEmpty()) return;

        byte[] data;
        if (crcFormatCombo.getSelectedIndex() == 1) {
            // 十六进制模式
            try {
                data = hexToBytes(input.replace("0x", "").replace("0X", ""));
            } catch (Exception e) {
                crcResultArea.setText("十六进制解析错误: " + e.getMessage());
                return;
            }
        } else {
            data = input.getBytes(StandardCharsets.UTF_8);
        }

        String algo = (String) crcAlgoCombo.getSelectedItem();
        StringBuilder sb = new StringBuilder();
        sb.append("算法: ").append(algo).append("\n");
        sb.append("数据长度: ").append(data.length).append(" 字节\n");
        sb.append("数据HEX: ").append(bytesToHex(data)).append("\n");
        sb.append("──────────────────────\n");

        switch (algo) {
            case "CRC8":           sb.append("CRC8 = 0x").append(String.format("%02X", crc8(data, 0x07, 0x00))).append("\n"); break;
            case "CRC8-MAXIM":     sb.append("CRC8-MAXIM = 0x").append(String.format("%02X", crc8(data, 0x31, 0x00))).append("\n"); break;
            case "CRC16":          sb.append("CRC16 = 0x").append(String.format("%04X", crc16(data, 0x8005, 0x0000))).append("\n"); break;
            case "CRC16-Modbus":   sb.append("CRC16-Modbus = 0x").append(String.format("%04X", crc16(data, 0x8005, 0xFFFF))).append("\n"); break;
            case "CRC32":          sb.append("CRC32 = 0x").append(String.format("%08X", crc32(data))).append("\n"); break;
            case "累加和 (Sum8)":   sb.append("Sum8 = 0x").append(String.format("%02X", sumCheck(data, 8))).append("\n"); break;
            case "异或 (XOR8)":    sb.append("XOR8 = 0x").append(String.format("%02X", xorCheck(data))).append("\n"); break;
            case "累加和 (Sum16)":  sb.append("Sum16 = 0x").append(String.format("%04X", sumCheck(data, 16))).append("\n"); break;
            case "异或 (XOR16)":   sb.append("XOR16 = 0x").append(String.format("%04X", xorCheck16(data))).append("\n"); break;
        }

        // 追加不同展示格式
        sb.append("\n格式展示:\n");
        long result = 0;
        try {
            String line = sb.toString();
            String[] lines = line.split("\n");
            for (String l : lines) {
                if (l.contains("= 0x")) {
                    String hexPart = l.substring(l.indexOf("0x") + 2).trim();
                    result = Long.parseLong(hexPart, 16);
                    break;
                }
            }
            sb.append("  十进制: ").append(result).append("\n");
            sb.append("  二进制: ").append(Long.toBinaryString(result)).append("\n");
        } catch (Exception ignored) {}

        crcResultArea.setText(sb.toString());
    }

    // CRC8 通用实现
    private int crc8(byte[] data, int polynomial, int init) {
        int crc = init & 0xFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x80) != 0) crc = ((crc << 1) ^ polynomial) & 0xFF;
                else crc = (crc << 1) & 0xFF;
            }
        }
        return crc;
    }

    // CRC16 通用实现
    private int crc16(byte[] data, int polynomial, int init) {
        int crc = init & 0xFFFF;
        for (byte b : data) {
            crc ^= (b & 0xFF);
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x0001) != 0) crc = ((crc >> 1) ^ polynomial) & 0xFFFF;
                else crc = (crc >> 1) & 0xFFFF;
            }
        }
        return crc;
    }

    // CRC32 (标准 IEEE 802.3)
    private int crc32(byte[] data) {
        java.util.zip.CRC32 crc = new java.util.zip.CRC32();
        crc.update(data);
        return (int) crc.getValue();
    }

    // 累加和
    private int sumCheck(byte[] data, int bits) {
        long sum = 0;
        for (byte b : data) sum += (b & 0xFF);
        return (int) (sum & ((1L << bits) - 1));
    }

    // XOR8
    private int xorCheck(byte[] data) {
        int xor = 0;
        for (byte b : data) xor ^= (b & 0xFF);
        return xor;
    }

    // XOR16
    private int xorCheck16(byte[] data) {
        int xor = 0;
        for (int i = 0; i < data.length - 1; i += 2) {
            xor ^= ((data[i] & 0xFF) << 8) | (data[i + 1] & 0xFF);
        }
        if (data.length % 2 != 0) xor ^= ((data[data.length - 1] & 0xFF) << 8);
        return xor & 0xFFFF;
    }

    // ==================== 7. 加解密 ====================

    private JPanel buildCryptoPanel() {
        JPanel panel = new JPanel(new BorderLayout(4, 4));
        panel.setBorder(BorderFactory.createEmptyBorder(6, 6, 6, 6));

        // 控制区
        JPanel ctrlArea = new JPanel(new GridLayout(3, 1, 0, 2));

        JPanel row1 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row1.add(new JLabel("数据:"));
        cryptoInputField = new JTextField(28);
        cryptoInputField.setFont(FONT_TEXT);
        cryptoInputField.setToolTipText("输入明文或密文");
        row1.add(cryptoInputField);

        cryptoAlgoCombo = new JComboBox<>(new String[]{"AES", "DES", "MD5", "SHA-1", "SHA-256"});
        cryptoAlgoCombo.setFont(FONT_TEXT);
        cryptoAlgoCombo.addActionListener(e -> onCryptoAlgoChanged());
        row1.add(new JLabel("算法:"));
        row1.add(cryptoAlgoCombo);

        cryptoModeCombo = new JComboBox<>(new String[]{"ECB", "CBC", "CFB", "OFB", "CTR"});
        cryptoModeCombo.setFont(FONT_TEXT);
        row1.add(new JLabel("模式:"));
        row1.add(cryptoModeCombo);

        cryptoPadCombo = new JComboBox<>(new String[]{"PKCS5Padding", "NoPadding"});
        cryptoPadCombo.setFont(FONT_TEXT);
        row1.add(new JLabel("填充:"));
        row1.add(cryptoPadCombo);

        JPanel row2 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        row2.add(new JLabel("密钥:"));
        cryptoKeyField = new JTextField(20);
        cryptoKeyField.setFont(FONT_TEXT);
        cryptoKeyField.setToolTipText("AES 支持 16/24/32 字节密钥，DES 需要 8 字节密钥");
        row2.add(cryptoKeyField);

        row2.add(new JLabel("IV:"));
        cryptoIvField = new JTextField(16);
        cryptoIvField.setFont(FONT_TEXT);
        cryptoIvField.setToolTipText("CBC/CFB/OFB/CTR 模式需要 IV，长度与块大小一致");
        row2.add(cryptoIvField);

        JPanel row3 = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        btnCryptoEncrypt = makeBtn("加密", new Color(0x4CAF50));
        btnCryptoEncrypt.addActionListener(e -> doCrypto(true));
        row3.add(btnCryptoEncrypt);

        btnCryptoDecrypt = makeBtn("解密", new Color(0xE57373));
        btnCryptoDecrypt.addActionListener(e -> doCrypto(false));
        row3.add(btnCryptoDecrypt);

        ctrlArea.add(row1);
        ctrlArea.add(row2);
        ctrlArea.add(row3);
        panel.add(ctrlArea, BorderLayout.NORTH);

        cryptoResultArea = new JTextArea();
        cryptoResultArea.setFont(FONT_TEXT);
        cryptoResultArea.setEditable(false);
        cryptoResultArea.setBackground(new Color(0x1E1E1E));
        cryptoResultArea.setForeground(new Color(0x81C784));
        cryptoResultArea.setLineWrap(true);
        JScrollPane sp = new JScrollPane(cryptoResultArea);
        sp.setBorder(BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                "结果", TitledBorder.LEADING, TitledBorder.TOP,
                new Font("Microsoft YaHei", Font.BOLD, 12)));
        panel.add(sp, BorderLayout.CENTER);

        onCryptoAlgoChanged();
        return panel;
    }

    private void onCryptoAlgoChanged() {
        String algo = (String) cryptoAlgoCombo.getSelectedItem();
        boolean isHash = algo != null && (algo.startsWith("MD") || algo.startsWith("SHA"));
        cryptoModeCombo.setEnabled(!isHash);
        cryptoPadCombo.setEnabled(!isHash);
        cryptoKeyField.setEnabled(!isHash);
        cryptoIvField.setEnabled(!isHash);
        btnCryptoEncrypt.setEnabled(true);
        btnCryptoEncrypt.setText(isHash ? "计算哈希" : "加密");
        btnCryptoDecrypt.setEnabled(!isHash);
        if (isHash) {
            cryptoResultArea.setText("");
        }
    }

    private void doCrypto(boolean encrypt) {
        String input = cryptoInputField.getText();
        if (input.isEmpty()) return;

        String algo = (String) cryptoAlgoCombo.getSelectedItem();
        if (algo == null) return;

        // 哈希算法
        if (algo.startsWith("MD") || algo.startsWith("SHA")) {
            try {
                MessageDigest md = MessageDigest.getInstance(algo.replace("-", ""));
                byte[] hash = md.digest(input.getBytes(StandardCharsets.UTF_8));
                cryptoResultArea.setText("算法: " + algo + "\n"
                        + "HEX: " + bytesToHex(hash).replace(" ", "") + "\n"
                        + "长度: " + (hash.length * 8) + " bit (" + hash.length + " bytes)");
            } catch (Exception e) {
                cryptoResultArea.setText("哈希计算失败: " + e.getMessage());
            }
            return;
        }

        // 对称加解密
        String keyStr = cryptoKeyField.getText();
        if (keyStr.isEmpty()) {
            cryptoResultArea.setText("请输入密钥");
            return;
        }

        String mode = (String) cryptoModeCombo.getSelectedItem();
        String padding = (String) cryptoPadCombo.getSelectedItem();
        String ivStr = cryptoIvField.getText();

        try {
            byte[] keyBytes = keyStr.getBytes(StandardCharsets.UTF_8);
            String algorithm = algo + "/" + mode + "/" + padding;

            // 调整密钥长度
            if ("AES".equals(algo)) {
                if (keyBytes.length <= 16) keyBytes = padKey(keyBytes, 16);
                else if (keyBytes.length <= 24) keyBytes = padKey(keyBytes, 24);
                else keyBytes = padKey(keyBytes, 32);
            } else if ("DES".equals(algo)) {
                keyBytes = padKey(keyBytes, 8);
            }

            SecretKeySpec keySpec = new SecretKeySpec(keyBytes, algo);
            Cipher cipher;

            if ("ECB".equals(mode)) {
                cipher = Cipher.getInstance(algorithm);
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec);
            } else {
                if (ivStr.isEmpty()) {
                    cryptoResultArea.setText(mode + " 模式需要填写 IV");
                    return;
                }
                byte[] ivBytes = ivStr.getBytes(StandardCharsets.UTF_8);
                if ("AES".equals(algo)) ivBytes = padKey(ivBytes, 16);
                else ivBytes = padKey(ivBytes, 8);
                IvParameterSpec ivSpec = new IvParameterSpec(ivBytes);
                cipher = Cipher.getInstance(algorithm);
                cipher.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, keySpec, ivSpec);
            }

            byte[] result;
            if (encrypt) {
                byte[] inputBytes = input.getBytes(StandardCharsets.UTF_8);
                result = cipher.doFinal(inputBytes);
                cryptoResultArea.setText("算法: " + algorithm + "\n"
                        + "模式: " + mode + "  填充: " + padding + "\n"
                        + "密钥: " + keyStr + "\n"
                        + (!"ECB".equals(mode) ? "IV: " + ivStr + "\n" : "")
                        + "──────────────────────\n"
                        + "密文 (Base64): " + Base64.getEncoder().encodeToString(result) + "\n"
                        + "密文 (HEX): " + bytesToHex(result).replace(" ", ""));
            } else {
                byte[] inputBytes;
                // 尝试 Base64 解码，失败则尝试 HEX
                try {
                    inputBytes = Base64.getDecoder().decode(input);
                } catch (IllegalArgumentException e1) {
                    try {
                        inputBytes = hexToBytes(input);
                    } catch (Exception e2) {
                        cryptoResultArea.setText("解密输入格式错误：请输入 Base64 或十六进制密文");
                        return;
                    }
                }
                result = cipher.doFinal(inputBytes);
                cryptoResultArea.setText("算法: " + algorithm + "\n"
                        + "模式: " + mode + "  填充: " + padding + "\n"
                        + "密钥: " + keyStr + "\n"
                        + (!"ECB".equals(mode) ? "IV: " + ivStr + "\n" : "")
                        + "──────────────────────\n"
                        + "明文: " + new String(result, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            cryptoResultArea.setText((encrypt ? "加密" : "解密") + "失败: " + e.getMessage());
        }
    }

    private byte[] padKey(byte[] src, int targetLen) {
        if (src.length == targetLen) return src;
        byte[] dst = new byte[targetLen];
        System.arraycopy(src, 0, dst, 0, Math.min(src.length, targetLen));
        return dst;
    }

    // ==================== 配置持久化 ====================

    @Override
    public void loadConfig(ConfigManager config) {
        loadCombo(strEncFromCombo, config, "convert.str.encFrom");
        loadCombo(strEncToCombo, config, "convert.str.encTo");
        loadCombo(tsUnitCombo, config, "convert.ts.unit");
        loadCombo(tsZoneCombo, config, "convert.ts.zone");
        loadCombo(crcAlgoCombo, config, "convert.crc.algo");
        loadCombo(crcFormatCombo, config, "convert.crc.format");
        loadCombo(cryptoAlgoCombo, config, "convert.crypto.algo");
        loadCombo(cryptoModeCombo, config, "convert.crypto.mode");
        loadCombo(cryptoPadCombo, config, "convert.crypto.pad");
    }

    @Override
    public void saveConfig(ConfigManager config) {
        saveCombo(strEncFromCombo, config, "convert.str.encFrom");
        saveCombo(strEncToCombo, config, "convert.str.encTo");
        saveCombo(tsUnitCombo, config, "convert.ts.unit");
        saveCombo(tsZoneCombo, config, "convert.ts.zone");
        saveCombo(crcAlgoCombo, config, "convert.crc.algo");
        saveCombo(crcFormatCombo, config, "convert.crc.format");
        saveCombo(cryptoAlgoCombo, config, "convert.crypto.algo");
        saveCombo(cryptoModeCombo, config, "convert.crypto.mode");
        saveCombo(cryptoPadCombo, config, "convert.crypto.pad");
    }
}
