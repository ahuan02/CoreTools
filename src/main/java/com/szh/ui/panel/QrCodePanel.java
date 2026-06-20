package com.szh.ui.panel;

import com.google.zxing.*;
import com.google.zxing.client.j2se.BufferedImageLuminanceSource;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.common.HybridBinarizer;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import com.szh.utils.NetUtil;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.border.TitledBorder;
import javax.swing.event.UndoableEditEvent;
import javax.swing.filechooser.FileNameExtensionFilter;
import javax.swing.undo.UndoManager;
import java.awt.Dimension;
import java.awt.*;
import java.awt.datatransfer.DataFlavor;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

import static com.szh.utils.NetUtil.*;

/**
 * 二维码生成/识别面板
 */
public class QrCodePanel extends AbstractCommandPanel {

    private static final int DEFAULT_SIZE = 300;
    private static final Color DARK_BG = new Color(0x1E1E1E);

    public QrCodePanel() {
        super(null);
    }

    @Override
    protected void initPanel() {
        JTabbedPane tabs = new JTabbedPane(JTabbedPane.TOP);
        tabs.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));

        tabs.addTab("生成二维码", new GeneratePanel());
        tabs.addTab("识别二维码", new RecognisePanel());

        add(tabs, BorderLayout.CENTER);
    }

    // ==================== 生成面板 ====================
    private static class GeneratePanel extends JPanel {

        private final JTextArea inputArea;
        private final JComboBox<String> sizeCombo;
        private final JComboBox<String> levelCombo;
        private final JLabel qrLabel;
        private final JButton btnGenerate;
        private final JButton btnSave;
        private BufferedImage currentQr;

        GeneratePanel() {
            setLayout(new BorderLayout(8, 8));
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            // 左侧：输入区
            JPanel leftPanel = new JPanel(new BorderLayout(4, 4));

            JPanel topPanel = new JPanel(new BorderLayout(4, 4));
            topPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "内容", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));

            inputArea = new JTextArea(5, 20);
            inputArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            inputArea.setBackground(C_BG);
            inputArea.setForeground(TEXT_COLOR);
            inputArea.setCaretColor(TEXT_COLOR);
            inputArea.setLineWrap(true);
            inputArea.setWrapStyleWord(true);
            NetUtil.fixPaste(inputArea);
            inputArea.setText("https://www.example.com");
            JScrollPane inputScroll = new JScrollPane(inputArea);
            // UndoManager — 支持 Ctrl+Z / Ctrl+Y
            UndoManager inputUndo = new UndoManager();
            inputArea.getDocument().addUndoableEditListener((UndoableEditEvent e) -> inputUndo.addEdit(e.getEdit()));
            inputArea.getInputMap().put(KeyStroke.getKeyStroke("ctrl Z"), "undo");
            inputArea.getActionMap().put("undo", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (inputUndo.canUndo()) inputUndo.undo();
                }
            });
            inputArea.getInputMap().put(KeyStroke.getKeyStroke("ctrl Y"), "redo");
            inputArea.getActionMap().put("redo", new AbstractAction() {
                @Override public void actionPerformed(java.awt.event.ActionEvent e) {
                    if (inputUndo.canRedo()) inputUndo.redo();
                }
            });
            // 右键菜单
            JPopupMenu inputPopup = new JPopupMenu();
            JMenuItem miUndo = new JMenuItem("撤销\tCtrl+Z");
            miUndo.addActionListener(e -> { if (inputUndo.canUndo()) inputUndo.undo(); });
            JMenuItem miRedo = new JMenuItem("重做\tCtrl+Y");
            miRedo.addActionListener(e -> { if (inputUndo.canRedo()) inputUndo.redo(); });
            JMenuItem miPaste = new JMenuItem("粘贴\tCtrl+V");
            miPaste.addActionListener(e -> inputArea.paste());
            JMenuItem miCopy = new JMenuItem("复制\tCtrl+C");
            miCopy.addActionListener(e -> inputArea.copy());
            JMenuItem miCut = new JMenuItem("剪切\tCtrl+X");
            miCut.addActionListener(e -> inputArea.cut());
            JMenuItem miSelectAll = new JMenuItem("全选\tCtrl+A");
            miSelectAll.addActionListener(e -> inputArea.selectAll());
            inputPopup.add(miUndo);
            inputPopup.add(miRedo);
            inputPopup.addSeparator();
            inputPopup.add(miCut);
            inputPopup.add(miCopy);
            inputPopup.add(miPaste);
            inputPopup.addSeparator();
            inputPopup.add(miSelectAll);
            inputArea.setComponentPopupMenu(inputPopup);

            topPanel.add(inputScroll, BorderLayout.CENTER);

            // 设置区
            JPanel settingPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 12, 4));
            settingPanel.add(new JLabel("尺寸:"));
            sizeCombo = new JComboBox<>(new String[]{"200×200", "300×300", "400×400", "500×500", "600×600"});
            sizeCombo.setSelectedIndex(1);
            settingPanel.add(sizeCombo);

            settingPanel.add(new JLabel("纠错:"));
            levelCombo = new JComboBox<>(new String[]{"L (7%)", "M (15%)", "Q (25%)", "H (30%)"});
            levelCombo.setSelectedIndex(1);
            settingPanel.add(levelCombo);

            btnGenerate = makeBtn("生成二维码", new Color(0x4CAF50));
            settingPanel.add(btnGenerate);

            leftPanel.add(topPanel, BorderLayout.CENTER);
            leftPanel.add(settingPanel, BorderLayout.SOUTH);

            // 右侧：预览区
            JPanel rightPanel = new JPanel(new BorderLayout());
            rightPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "预览", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));

            qrLabel = new JLabel("输入内容后点击生成", SwingConstants.CENTER);
            qrLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            qrLabel.setForeground(new Color(0x888888));
            qrLabel.setBackground(C_BG);
            qrLabel.setOpaque(true);
            qrLabel.setPreferredSize(new Dimension(DEFAULT_SIZE, DEFAULT_SIZE));

            JPanel qrWrap = new JPanel(new GridBagLayout());
            qrWrap.setBackground(C_BG);
            qrWrap.add(qrLabel);

            // 操作按钮
            JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
            btnSave = makeBtn("保存图片", new Color(0x2196F3));
            btnSave.setEnabled(false);
            JButton btnCopy = makeBtn("复制到剪贴板", new Color(0xFF9800));
            btnCopy.setEnabled(false);
            btnPanel.add(btnSave);
            btnPanel.add(btnCopy);

            rightPanel.add(qrWrap, BorderLayout.CENTER);
            rightPanel.add(btnPanel, BorderLayout.SOUTH);

            // 左右分栏
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
            split.setResizeWeight(0.4);
            add(split, BorderLayout.CENTER);

            // 事件
            btnGenerate.addActionListener(e -> generateQr());
            btnSave.addActionListener(e -> saveQr());
            btnCopy.addActionListener(e -> copyToClipboard());

            // Ctrl+Enter 生成
            inputArea.getInputMap().put(KeyStroke.getKeyStroke("ctrl ENTER"), "generate");
            inputArea.getActionMap().put("generate", new AbstractAction() {
                @Override
                public void actionPerformed(java.awt.event.ActionEvent e) { generateQr(); }
            });

            // 拖拽文件到输入区（保留默认剪贴板复制/粘贴行为）
            inputArea.setTransferHandler(new TransferHandler("text") {
                @Override
                public boolean canImport(TransferSupport support) {
                    // 拖拽文件 → 自定义处理
                    if (support.isDrop()
                            && (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                                || support.isDataFlavorSupported(DataFlavor.stringFlavor))) {
                        return true;
                    }
                    // 剪贴板粘贴 → 走默认行为（Ctrl+C/V/X）
                    return super.canImport(support);
                }
                @Override
                public boolean importData(TransferSupport support) {
                    if (support.isDrop()) {
                        try {
                            if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                                @SuppressWarnings("unchecked")
                                var files = (java.util.List<File>) support.getTransferable()
                                        .getTransferData(DataFlavor.javaFileListFlavor);
                                if (!files.isEmpty()) {
                                    inputArea.setText(Files.readString(files.get(0).toPath()));
                                }
                                return true;
                            } else if (support.isDataFlavorSupported(DataFlavor.stringFlavor)) {
                                String text = (String) support.getTransferable()
                                        .getTransferData(DataFlavor.stringFlavor);
                                inputArea.setText(text);
                                return true;
                            }
                        } catch (Exception ex) { /* ignore */ }
                        return false;
                    }
                    // 非拖拽（如 Ctrl+V 粘贴）→ 走默认行为
                    return super.importData(support);
                }
            });
        }

        private void generateQr() {
            String text = inputArea.getText().trim();
            if (text.isEmpty()) {
                JOptionPane.showMessageDialog(this, "请输入内容", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }

            try {
                int size = parseSize();
                ErrorCorrectionLevel level = parseLevel();

                Map<EncodeHintType, Object> hints = new HashMap<>();
                hints.put(EncodeHintType.CHARACTER_SET, "UTF-8");
                hints.put(EncodeHintType.ERROR_CORRECTION, level);
                hints.put(EncodeHintType.MARGIN, 1);

                QRCodeWriter writer = new QRCodeWriter();
                BitMatrix matrix = writer.encode(text, BarcodeFormat.QR_CODE, size, size, hints);
                currentQr = MatrixToImageWriter.toBufferedImage(matrix);

                // 缩放显示（保持面板适应）
                ImageIcon icon = new ImageIcon(currentQr.getScaledInstance(
                        Math.min(size, 500), Math.min(size, 500), Image.SCALE_SMOOTH));
                qrLabel.setIcon(icon);
                qrLabel.setText(null);
                btnSave.setEnabled(true);

                // 更新复制按钮
                Container c = btnSave.getParent();
                for (Component comp : c.getComponents()) {
                    if (comp instanceof JButton jb && "复制到剪贴板".equals(jb.getText())) {
                        jb.setEnabled(true);
                        break;
                    }
                }
            } catch (Exception ex) {
                JOptionPane.showMessageDialog(this, "生成失败: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }

        private int parseSize() {
            String sel = (String) sizeCombo.getSelectedItem();
            if (sel == null) return DEFAULT_SIZE;
            return Integer.parseInt(sel.split("×")[0]);
        }

        private ErrorCorrectionLevel parseLevel() {
            String sel = (String) levelCombo.getSelectedItem();
            if (sel == null) return ErrorCorrectionLevel.M;
            return switch (sel.charAt(0)) {
                case 'L' -> ErrorCorrectionLevel.L;
                case 'Q' -> ErrorCorrectionLevel.Q;
                case 'H' -> ErrorCorrectionLevel.H;
                default -> ErrorCorrectionLevel.M;
            };
        }

        private void saveQr() {
            if (currentQr == null) return;
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("保存二维码");
            chooser.setFileFilter(new FileNameExtensionFilter("PNG 图片 (*.png)", "png"));
            File defaultFile = new File(System.getProperty("user.home"), "qrcode.png");
            chooser.setSelectedFile(defaultFile);
            if (chooser.showSaveDialog(this) == JFileChooser.APPROVE_OPTION) {
                File file = chooser.getSelectedFile();
                if (!file.getName().contains(".")) file = new File(file.getAbsolutePath() + ".png");
                try {
                    ImageIO.write(currentQr, "png", file);
                    JOptionPane.showMessageDialog(this, "已保存至: " + file.getAbsolutePath());
                } catch (IOException ex) {
                    JOptionPane.showMessageDialog(this, "保存失败: " + ex.getMessage(),
                            "错误", JOptionPane.ERROR_MESSAGE);
                }
            }
        }

        private void copyToClipboard() {
            if (currentQr == null) return;
            TransferableImage trans = new TransferableImage(currentQr);
            Toolkit.getDefaultToolkit().getSystemClipboard().setContents(trans, null);
            JOptionPane.showMessageDialog(this, "已复制到剪贴板");
        }
    }

    // ==================== 识别面板 ====================
    private static class RecognisePanel extends JPanel {

        private final JLabel imageLabel;
        private final JTextArea resultArea;
        private final JButton btnSelectImage;
        private final JButton btnRecognise;
        private BufferedImage loadedImage;
        private File loadedFile;

        RecognisePanel() {
            setLayout(new BorderLayout(8, 8));
            setBorder(BorderFactory.createEmptyBorder(8, 8, 8, 8));

            // 左侧：图片预览 + 识别按钮
            JPanel leftPanel = new JPanel(new BorderLayout(4, 4));
            leftPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "图片", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));

            imageLabel = new JLabel("拖拽图片到此处\n或点击下方按钮选择", SwingConstants.CENTER);
            imageLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            imageLabel.setForeground(new Color(0x888888));
            imageLabel.setBackground(C_BG);
            imageLabel.setOpaque(true);
            imageLabel.setPreferredSize(new Dimension(350, 350));

            JPanel imgWrap = new JPanel(new GridBagLayout());
            imgWrap.setBackground(C_BG);
            imgWrap.add(imageLabel);
            JScrollPane imgScroll = new JScrollPane(imgWrap);
            imgScroll.setPreferredSize(new Dimension(370, 370));

            JPanel imgBtnPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 8, 4));
            btnSelectImage = makeBtn("选择图片", new Color(0x2196F3));
            btnRecognise = makeBtn("识别二维码", new Color(0x4CAF50));
            btnRecognise.setEnabled(false);
            imgBtnPanel.add(btnSelectImage);
            imgBtnPanel.add(btnRecognise);

            leftPanel.add(imgScroll, BorderLayout.CENTER);
            leftPanel.add(imgBtnPanel, BorderLayout.SOUTH);

            // 拖放支持
            imgWrap.setTransferHandler(new TransferHandler("text") {
                @Override
                public boolean canImport(TransferSupport support) {
                    return support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)
                            || support.isDataFlavorSupported(DataFlavor.imageFlavor);
                }
                @Override
                public boolean importData(TransferSupport support) {
                    try {
                        if (support.isDataFlavorSupported(DataFlavor.javaFileListFlavor)) {
                            @SuppressWarnings("unchecked")
                            var files = (java.util.List<File>) support.getTransferable()
                                    .getTransferData(DataFlavor.javaFileListFlavor);
                            if (!files.isEmpty()) {
                                loadImage(files.get(0));
                            }
                        } else if (support.isDataFlavorSupported(DataFlavor.imageFlavor)) {
                            Image img = (Image) support.getTransferable()
                                    .getTransferData(DataFlavor.imageFlavor);
                            loadedImage = toBufferedImage(img);
                            loadedFile = null;
                            showImage(loadedImage);
                            btnRecognise.setEnabled(true);
                        }
                        return true;
                    } catch (Exception ex) {
                        // ignore
                    }
                    return false;
                }
            });

            // 右侧：识别结果
            JPanel rightPanel = new JPanel(new BorderLayout(4, 4));
            rightPanel.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(new Color(80, 80, 80), 1, true),
                    "识别结果", TitledBorder.LEADING, TitledBorder.TOP,
                    new Font("Microsoft YaHei", Font.BOLD, 11)));

            resultArea = new JTextArea();
            resultArea.setEditable(false);
            resultArea.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
            resultArea.setBackground(C_BG);
            resultArea.setForeground(TEXT_COLOR);
            resultArea.setLineWrap(true);
            resultArea.setWrapStyleWord(true);
            JScrollPane resultScroll = new JScrollPane(resultArea);
            // 右键菜单
            JPopupMenu resultPopup = new JPopupMenu();
            JMenuItem rsCopy = new JMenuItem("复制\tCtrl+C");
            rsCopy.addActionListener(e -> resultArea.copy());
            JMenuItem rsSelectAll = new JMenuItem("全选\tCtrl+A");
            rsSelectAll.addActionListener(e -> resultArea.selectAll());
            resultPopup.add(rsCopy);
            resultPopup.add(rsSelectAll);
            resultArea.setComponentPopupMenu(resultPopup);

            JPanel resultBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 2));
            JButton btnCopy = makeBtn("复制结果", new Color(0xFF9800));
            btnCopy.addActionListener(e -> {
                String text = resultArea.getText();
                if (!text.isEmpty()) {
                    Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new java.awt.datatransfer.StringSelection(text), null);
                    JOptionPane.showMessageDialog(this, "已复制");
                }
            });
            resultBtnPanel.add(btnCopy);

            rightPanel.add(resultScroll, BorderLayout.CENTER);
            rightPanel.add(resultBtnPanel, BorderLayout.SOUTH);

            // 左右分栏
            JSplitPane split = new JSplitPane(JSplitPane.HORIZONTAL_SPLIT, leftPanel, rightPanel);
            split.setResizeWeight(0.55);
            add(split, BorderLayout.CENTER);

            // 事件
            btnSelectImage.addActionListener(e -> selectImage());
            btnRecognise.addActionListener(e -> recognise());
        }

        private void selectImage() {
            JFileChooser chooser = new JFileChooser();
            chooser.setDialogTitle("选择二维码图片");
            chooser.setFileFilter(new FileNameExtensionFilter(
                    "图片文件 (*.png, *.jpg, *.jpeg, *.gif, *.bmp)", "png", "jpg", "jpeg", "gif", "bmp"));
            if (chooser.showOpenDialog(this) == JFileChooser.APPROVE_OPTION) {
                loadImage(chooser.getSelectedFile());
            }
        }

        private void loadImage(File file) {
            try {
                loadedFile = file;
                loadedImage = ImageIO.read(file);
                if (loadedImage == null) {
                    JOptionPane.showMessageDialog(this, "无法读取图片文件",
                            "错误", JOptionPane.ERROR_MESSAGE);
                    return;
                }
                showImage(loadedImage);
                btnRecognise.setEnabled(true);
            } catch (IOException ex) {
                JOptionPane.showMessageDialog(this, "读取图片失败: " + ex.getMessage(),
                        "错误", JOptionPane.ERROR_MESSAGE);
            }
        }

        private void showImage(BufferedImage img) {
            int maxW = imageLabel.getParent() != null ? imageLabel.getParent().getWidth() - 20 : 350;
            int maxH = imageLabel.getParent() != null ? imageLabel.getParent().getHeight() - 20 : 350;
            if (maxW <= 0) maxW = 350;
            if (maxH <= 0) maxH = 350;

            double scale = Math.min((double) maxW / img.getWidth(), (double) maxH / img.getHeight());
            if (scale < 1.0) {
                int newW = (int) (img.getWidth() * scale);
                int newH = (int) (img.getHeight() * scale);
                BufferedImage scaled = new BufferedImage(newW, newH, BufferedImage.TYPE_INT_ARGB);
                Graphics2D g2d = scaled.createGraphics();
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                g2d.drawImage(img, 0, 0, newW, newH, null);
                g2d.dispose();
                imageLabel.setIcon(new ImageIcon(scaled));
            } else {
                imageLabel.setIcon(new ImageIcon(img));
            }
            imageLabel.setText(null);
        }

        private void recognise() {
            if (loadedImage == null) {
                JOptionPane.showMessageDialog(this, "请先选择图片", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }

            try {
                LuminanceSource source = new BufferedImageLuminanceSource(loadedImage);
                BinaryBitmap bitmap = new BinaryBitmap(new HybridBinarizer(source));

                Map<DecodeHintType, Object> hints = new HashMap<>();
                hints.put(DecodeHintType.CHARACTER_SET, "UTF-8");
                hints.put(DecodeHintType.TRY_HARDER, Boolean.TRUE);

                // 尝试多种条形码格式
                MultiFormatReader reader = new MultiFormatReader();
                Result result = reader.decode(bitmap, hints);

                StringBuilder sb = new StringBuilder();
                sb.append("格式: ").append(result.getBarcodeFormat()).append("\n");
                sb.append("内容: ").append(result.getText()).append("\n");

                resultArea.setText(sb.toString());

                // 如果识别结果以 http 开头，提供一键打开
                if (result.getText().startsWith("http://") || result.getText().startsWith("https://")) {
                    int choice = JOptionPane.showConfirmDialog(this,
                            "识别到 URL，是否在浏览器打开？\n" + result.getText(),
                            "URL 识别", JOptionPane.YES_NO_OPTION);
                    if (choice == JOptionPane.YES_OPTION) {
                        try {
                            Desktop.getDesktop().browse(java.net.URI.create(result.getText()));
                        } catch (Exception ignored) {}
                    }
                }
            } catch (NotFoundException ex) {
                resultArea.setText("未识别到二维码或条形码\n\n请确认图片中包含有效的二维码");
            } catch (Exception ex) {
                resultArea.setText("识别失败: " + ex.getMessage());
            }
        }

        private static BufferedImage toBufferedImage(Image img) {
            if (img instanceof BufferedImage bi) return bi;
            BufferedImage bi = new BufferedImage(img.getWidth(null), img.getHeight(null),
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g2d = bi.createGraphics();
            g2d.drawImage(img, 0, 0, null);
            g2d.dispose();
            return bi;
        }
    }

    /**
     * 可转移的图片（支持复制到剪贴板）
     */
    private static class TransferableImage implements java.awt.datatransfer.Transferable {
        private final Image image;
        TransferableImage(Image image) { this.image = image; }
        @Override public DataFlavor[] getTransferDataFlavors() {
            return new DataFlavor[]{DataFlavor.imageFlavor};
        }
        @Override public boolean isDataFlavorSupported(DataFlavor flavor) {
            return DataFlavor.imageFlavor.equals(flavor);
        }
        @Override public Object getTransferData(DataFlavor flavor) {
            return image;
        }
    }
}
