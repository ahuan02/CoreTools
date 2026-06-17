package com.szh.ui.panel;

import com.szh.entity.ModelConfig;
import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;

import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import java.awt.*;
import java.awt.event.*;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 对话面板 - 基于 langchain4j
 */
public class AiChatPanel extends AbstractCommandPanel {

    // ==================== 聊天组件 ====================
    private JEditorPane chatPane;
    private JTextArea inputArea;
    private JButton sendBtn;
    private JButton clearBtn;
    private JButton addModelBtn;
    private JScrollPane chatScrollPane;

    // ==================== 颜色常量 ====================
    private static final Color C_INPUT_BG   = new Color(0x252525);
    private static final Color C_FIELD_BG   = new Color(0x333333);
    private static final Color C_BTN_BG     = new Color(0x3A3A3A);
    private static final Color C_BTN_HOVER  = new Color(0x4A4A4A);
    private static final Color C_PRIMARY    = new Color(0x2E7D32);
    private static final Color C_PRIMARY_HV = new Color(0x388E3C);
    private static final Color C_DANGER     = new Color(0xC62828);
    private static final Color C_BORDER     = new Color(0x444444);
    private static final Color C_TIME       = new Color(0x888888);
    private static final Color C_TEXT       = new Color(0xD4D4D4);

    // ==================== 模型配置数据 ====================
    private List<ModelConfig> modelConfigs;
    private JComboBox<ModelConfig> modelSelector; // 输入栏右侧模型下拉

    /** 预置模型（别名默认用 modelName，key 和 url 留空让用户填） */
    private static final ModelConfig[] PRESET_MODELS = {
        new ModelConfig("GPT-4o",            "", "https://api.openai.com/v1",         "gpt-4o"),
        new ModelConfig("GPT-4o Mini",       "", "https://api.openai.com/v1",         "gpt-4o-mini"),
        new ModelConfig("DeepSeek Chat",     "", "https://api.deepseek.com/v1",       "deepseek-chat"),
        new ModelConfig("DeepSeek Reasoner", "", "https://api.deepseek.com/v1",       "deepseek-reasoner"),
        new ModelConfig("DeepSeek V4 Pro",   "", "https://api.deepseek.com/v1",       "deepseek-v4-pro"),
        new ModelConfig("DeepSeek V4 Flash", "", "https://api.deepseek.com/v1",       "deepseek-v4-flash"),
        new ModelConfig("豆包",               "", "https://ark.cn-beijing.volces.com/api/v3", "doubao-lite-128k"),
        new ModelConfig("通义千问 Plus",      "", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-plus"),
        new ModelConfig("通义千问 Max",       "", "https://dashscope.aliyuncs.com/compatible-mode/v1", "qwen-max"),
    };

    /** 预定义模型全称，用于下拉选项 */
    private static final String[] KNOWN_MODEL_NAMES = {
        // OpenAI
        "gpt-4o", "gpt-4o-mini", "gpt-4-turbo", "gpt-4", "gpt-3.5-turbo", "gpt-3.5-turbo-16k",
        "o1", "o1-mini", "o3-mini",
        // DeepSeek
        "deepseek-chat", "deepseek-reasoner", "deepseek-v4-pro", "deepseek-v4-flash",
        // 通义千问
        "qwen-plus", "qwen-max", "qwen-turbo", "qwen-plus-latest", "qwen-max-latest",
        // 豆包 (ByteDance)
        "doubao-lite-128k", "doubao-lite-32k", "doubao-pro-128k", "doubao-pro-32k",
        // Claude
        "claude-3-5-sonnet-20241022", "claude-3-opus-20240229", "claude-3-haiku-20240307",
        // Gemini
        "gemini-2.5-pro-exp-03-25", "gemini-2.5-flash",
        // Moonshot
        "moonshot-v1-8k", "moonshot-v1-32k", "moonshot-v1-128k",
        // 智谱 GLM
        "glm-4-plus", "glm-4", "glm-4-flash",
        // 混元
        "hunyuan-turbos-latest", "hunyuan-lite",
        // 文心一言
        "ernie-4.0-turbo-8k", "ernie-3.5-8k",
    };

    private static final DateTimeFormatter TF = DateTimeFormatter.ofPattern("HH:mm:ss");

    // ==================== 布局常量 ====================
    private static final String CHAT_STYLE =
        "font-family:Microsoft YaHei;font-size:13px;color:#D4D4D4;padding:12px;";

    // ==================== 构造 ====================
    public AiChatPanel() {
        super(null);
    }

    // ==================== 初始化 ====================
    @Override
    protected void initPanel() {
        setLayout(new BorderLayout());
        setBackground(NetUtil.C_BG);

        // 在父类构造器中被子类覆盖调用，此时字段初始化器尚未执行，需手动初始化
        if (modelConfigs == null) {
            modelConfigs = new java.util.ArrayList<>();
            // 不从预置模型加载，用户自行添加
        }
        // 模型下拉选择器
        modelSelector = createModelSelector();

        add(createChatArea(), BorderLayout.CENTER);

        // 底部输入栏：水平居中
        JPanel southWrapper = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 0));
        southWrapper.setBackground(C_INPUT_BG);
        southWrapper.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
            BorderFactory.createEmptyBorder(6, 0, 6, 0)));
        southWrapper.add(createInputBar());
        add(southWrapper, BorderLayout.SOUTH);

        // 初始占位
        resetChatHtml();
    }

    private JTextField createField(int cols) {
        return createField(cols, null);
    }

    private JTextField createField(int cols, String placeholder) {
        JTextField f = new PromptTextField(cols, placeholder);
        f.setFont(NetUtil.FONT_TEXT);
        f.setForeground(NetUtil.TEXT_COLOR);
        f.setBackground(C_FIELD_BG);
        f.setCaretColor(NetUtil.TEXT_COLOR);
        f.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER),
            BorderFactory.createEmptyBorder(3, 6, 3, 6)));
        return f;
    }

    /** 带 placeholder 的 JTextField */
    private static class PromptTextField extends JTextField {
        private final String prompt;

        PromptTextField(int cols, String prompt) {
            super(cols);
            this.prompt = prompt;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (prompt != null && getText().isEmpty() && !isFocusOwner()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(C_TIME);
                FontMetrics fm = g2.getFontMetrics();
                Insets ins = getInsets();
                int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(prompt, ins.left, y);
                g2.dispose();
            }
        }
    }

    // ==================== 聊天区域 ====================
    private JScrollPane createChatArea() {
        chatPane = new JEditorPane();
        chatPane.setContentType("text/html");
        chatPane.setEditable(false);
        chatPane.setBackground(NetUtil.C_BG);
        chatPane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, true);

        chatScrollPane = new JScrollPane(chatPane);
        chatScrollPane.setBorder(null);
        chatScrollPane.getViewport().setBackground(NetUtil.C_BG);
        return chatScrollPane;
    }

    private void resetChatHtml() {
        chatPane.setText(
            "<html><body style='" + CHAT_STYLE + "background-color:"
            + toHex(NetUtil.C_BG) + ";'><div style='text-align:center;padding-top:80px;color:"
            + toHex(C_TIME) + ";'>AI 对话助手<br/><br/>"
            + "点击「＋ 添加模型」配置后即可使用</div></body></html>");
    }

    // ==================== 输入栏 ====================
    private JPanel createInputBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(C_INPUT_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        // 左侧：模型下拉选择器 + 添加模型按钮
        JPanel leftBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftBtnPanel.setOpaque(false);
        leftBtnPanel.add(modelSelector);
        addModelBtn = createFilledButton("＋ 添加模型", e -> showAddModelDialog());
        leftBtnPanel.add(addModelBtn);
        bar.add(leftBtnPanel, BorderLayout.WEST);

        // 中间：输入框（小巧单行）
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setOpaque(false);
        inputArea = new JTextArea(1, 0);
        inputArea.setFont(NetUtil.FONT_TEXT);
        inputArea.setForeground(NetUtil.TEXT_COLOR);
        inputArea.setBackground(C_FIELD_BG);
        inputArea.setCaretColor(NetUtil.TEXT_COLOR);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        inputArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1, true),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)));
        // Enter 发送，Shift+Enter 换行
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("ENTER"), "send");
        inputArea.getActionMap().put("send", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { sendMessage(); }
        });
        inputArea.getInputMap().put(KeyStroke.getKeyStroke("shift ENTER"), "newline");
        inputArea.getActionMap().put("newline", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) { inputArea.append("\n"); }
        });

        // 用 Box 做垂直居中包装，避免默认 FlowLayout 导致的拉伸
        centerPanel.add(inputArea, BorderLayout.CENTER);
        bar.add(centerPanel, BorderLayout.CENTER);

        // 右侧按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.setOpaque(false);
        clearBtn = createOutlinedButton("清除", C_TIME, e -> clearChat());
        sendBtn  = createFilledButton("发送", e -> sendMessage());
        btnPanel.add(clearBtn);
        btnPanel.add(sendBtn);

        bar.add(btnPanel, BorderLayout.EAST);

        // 限制输入栏最大宽度，配合 wrapper 的 FlowLayout.CENTER 实现居中
        bar.setPreferredSize(new Dimension(640, 40));
        bar.setMaximumSize(new Dimension(640, 40));
        return bar;
    }

    // ---- 按钮工厂 ----

    /** 填充按钮（主操作，如"发送"），宽度自适应文字 */
    private JButton createFilledButton(String text, ActionListener action) {
        RefinedButton btn = new RefinedButton(text);
        btn.setFont(NetUtil.FONT_TEXT);
        btn.setForeground(Color.WHITE);
        btn.normalBg = C_PRIMARY;
        btn.hoverBg  = C_PRIMARY_HV;
        btn.addActionListener(action);
        return btn;
    }

    /** 描边按钮（次要操作，如"清除"），宽度自适应文字 */
    private JButton createOutlinedButton(String text, Color fg, ActionListener action) {
        RefinedButton btn = new RefinedButton(text);
        btn.setFont(NetUtil.FONT_TEXT);
        btn.setForeground(fg);
        btn.normalBg = C_BTN_BG;
        btn.hoverBg  = C_BTN_HOVER;
        btn.addActionListener(action);
        return btn;
    }


    /** 圆角按钮（支持 hover 背景色切换，宽度自适应文字） */
    private static class RefinedButton extends JButton {
        Color hoverBg;
        Color normalBg;
        private boolean hovering;

        RefinedButton(String text) {
            super(text);
            setOpaque(false);
            setContentAreaFilled(false);
            setBorderPainted(false);
            setFocusPainted(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
            addMouseListener(new MouseAdapter() {
                @Override public void mouseEntered(MouseEvent e) { hovering = true; repaint(); }
                @Override public void mouseExited(MouseEvent e)  { hovering = false; repaint(); }
            });
        }

        @Override
        public Dimension getPreferredSize() {
            FontMetrics fm = getFontMetrics(getFont());
            int w = fm.stringWidth(getText()) + 28; // 左右各 12 padding + 4 余量
            int h = fm.getHeight() + 10;
            return new Dimension(w, Math.max(h, 28));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setColor(hovering && hoverBg != null ? hoverBg : normalBg);
            g2.fillRoundRect(0, 0, getWidth() - 1, getHeight() - 1, 8, 8);

            // 文字居中
            FontMetrics fm = g2.getFontMetrics();
            int textX = (getWidth() - fm.stringWidth(getText())) / 2;
            int textY = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            g2.setColor(getForeground());
            g2.drawString(getText(), textX, textY);
            g2.dispose();
        }
    }

    // ==================== 消息逻辑 ====================
    private void sendMessage() {
        String text = inputArea.getText().trim();
        if (text.isEmpty()) return;

        // 显示用户消息
        appendMessage("你", text, NetUtil.C_SEND);
        inputArea.setText("");

        // 异步调用 AI（占位，后续接入 langchain4j）
        SwingUtilities.invokeLater(() -> {
            appendMessage("AI", "（langchain4j 接口待接入，这是占位回复。）",
                          NetUtil.C_RECV);
        });
    }

    private void appendMessage(String sender, String body, Color senderColor) {
        String time = LocalTime.now().format(TF);
        String html = chatPane.getText();
        html = html.replace("</body></html>", "");
        html += String.format(
            "<div style='margin:6px 0;line-height:1.6;'>"
            + "<span style='color:%s;'>[%s]</span> "
            + "<b style='color:%s;'>%s:</b>"
            + "<span style='color:%s;'> %s</span>"
            + "</div>",
            toHex(C_TIME), time,
            toHex(senderColor), sender,
            toHex(C_TEXT), escapeHtml(body));
        html += "</body></html>";
        chatPane.setText(html);

        // 自动滚到底部
        SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
            vBar.setValue(vBar.getMaximum());
        });
    }

    private void clearChat() {
        resetChatHtml();
        inputArea.setText("");
    }

    // ==================== 设置对话框 ====================
    private void showSettingsDialog() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog dialog = new JDialog(owner, "模型设置", Dialog.ModalityType.APPLICATION_MODAL);
        dialog.setSize(520, 360);
        dialog.setLocationRelativeTo(owner);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(C_INPUT_BG);
        content.setBorder(BorderFactory.createEmptyBorder(10, 10, 10, 10));

        // 模型列表
        DefaultListModel<String> listModel = new DefaultListModel<>();
        for (ModelConfig mc : modelConfigs) {
            listModel.addElement(mc.comboLabel());
        }
        JList<String> modelList = new JList<>(listModel);
        modelList.setFont(NetUtil.FONT_TEXT);
        modelList.setForeground(NetUtil.TEXT_COLOR);
        modelList.setBackground(C_FIELD_BG);
        modelList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        JScrollPane listScroll = new JScrollPane(modelList);
        listScroll.setBorder(BorderFactory.createLineBorder(C_BORDER));
        listScroll.setPreferredSize(new Dimension(200, 0));
        content.add(listScroll, BorderLayout.CENTER);

        // 右侧按钮
        JPanel btnBar = new JPanel(new GridLayout(4, 1, 0, 8));
        btnBar.setOpaque(false);
        btnBar.setBorder(BorderFactory.createEmptyBorder(0, 10, 0, 0));

        JButton btnAdd    = createOutlinedButton("添加",    NetUtil.C_RECV, null);
        JButton btnEdit   = createOutlinedButton("编辑",    NetUtil.C_WARN, null);
        JButton btnDelete = createOutlinedButton("删除",    C_DANGER,       null);
        JButton btnClose  = createOutlinedButton("关闭",    C_TIME,         e -> dialog.dispose());

        btnAdd.addActionListener(e -> {
            new ModelConfigDialog(owner, null).setVisible(true);
            refreshList(listModel);
        });
        btnEdit.addActionListener(e -> {
            int idx = modelList.getSelectedIndex();
            if (idx < 0) {
                JOptionPane.showMessageDialog(dialog, "请先选择要编辑的模型");
                return;
            }
            new ModelConfigDialog(owner, modelConfigs.get(idx)).setVisible(true);
            refreshList(listModel);
        });
        btnDelete.addActionListener(e -> {
            int idx = modelList.getSelectedIndex();
            if (idx < 0) {
                JOptionPane.showMessageDialog(dialog, "请先选择要删除的模型");
                return;
            }
            modelConfigs.remove(idx);
            refreshList(listModel);
        });

        btnBar.add(btnAdd);
        btnBar.add(btnEdit);
        btnBar.add(btnDelete);
        btnBar.add(btnClose);

        content.add(btnBar, BorderLayout.EAST);
        dialog.add(content);

        KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
        dialog.getRootPane().registerKeyboardAction(
            e -> dialog.dispose(), esc, JComponent.WHEN_IN_FOCUSED_WINDOW);

        dialog.setVisible(true);
    }

    private void refreshList(DefaultListModel<String> listModel) {
        listModel.clear();
        for (ModelConfig mc : modelConfigs) {
            listModel.addElement(mc.comboLabel());
        }
    }

    // ==================== 添加 / 编辑模型对话框 ====================

    /** 创建输入栏左侧的模型下拉选择器 */
    private JComboBox<ModelConfig> createModelSelector() {
        JComboBox<ModelConfig> combo = new JComboBox<>();
        combo.setFont(NetUtil.FONT_TEXT);
        combo.setForeground(NetUtil.TEXT_COLOR);
        combo.setBackground(C_FIELD_BG);
        combo.setPreferredSize(new Dimension(160, 28));

        // 暗色下拉列表渲染
        combo.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value,
                    int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(
                        list, value, index, isSelected, cellHasFocus);
                lbl.setOpaque(true);
                lbl.setBackground(isSelected ? C_PRIMARY : C_FIELD_BG);
                lbl.setForeground(NetUtil.TEXT_COLOR);
                lbl.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                if (value instanceof ModelConfig mc) {
                    lbl.setText(mc.comboLabel());
                }
                return lbl;
            }
        });

        refreshModelSelector();
        return combo;
    }

    /** 刷新模型下拉选择器内容 */
    private void refreshModelSelector() {
        if (modelSelector == null) return;
        modelSelector.removeAllItems();
        if (modelConfigs.isEmpty()) {
            // 没有模型时：显示提示文字并禁用下拉选择器
            ModelConfig placeholder = new ModelConfig("请先添加模型", "", "", "");
            modelSelector.addItem(placeholder);
            modelSelector.setEnabled(false);
        } else {
            modelSelector.setEnabled(true);
            for (ModelConfig mc : modelConfigs) {
                modelSelector.addItem(mc);
            }
            // 默认选中第一个
            modelSelector.setSelectedIndex(0);
        }
        modelSelector.repaint();
    }

    private void showAddModelDialog() {
        Window owner = SwingUtilities.getWindowAncestor(this);
        new ModelConfigDialog(owner, null).setVisible(true);
    }

    /** 模型配置编辑弹窗（新增 / 编辑） */
    private class ModelConfigDialog extends JDialog {
        private final JTextField aliasField  = createField(24, "如 GPT-4o、DeepSeek");
        private final JTextField keyField    = createField(24, "sk-xxxxxxxxxxxxxxxxxxxxxxxx");
        private final JTextField urlField    = createField(24, "https://api.openai.com/v1");
        private final JComboBox<String> nameCombo = createModelCombo();
        private boolean confirmed;

        ModelConfigDialog(Window owner, ModelConfig editing) {
            super(owner, editing == null ? "添加模型" : "编辑模型", ModalityType.APPLICATION_MODAL);
            init(editing);
        }

        /** 创建可编辑下拉框（既可输入也可下拉选择） */
        private JComboBox<String> createModelCombo() {
            JComboBox<String> combo = new JComboBox<>(KNOWN_MODEL_NAMES);
            combo.setEditable(true);
            combo.setFont(NetUtil.FONT_TEXT);
            combo.setForeground(NetUtil.TEXT_COLOR);
            combo.setBackground(C_FIELD_BG);

            // 暗色下拉列表渲染
            combo.setRenderer(new DefaultListCellRenderer() {
                @Override
                public Component getListCellRendererComponent(JList<?> list, Object value,
                        int index, boolean isSelected, boolean cellHasFocus) {
                    JLabel lbl = (JLabel) super.getListCellRendererComponent(
                            list, value, index, isSelected, cellHasFocus);
                    lbl.setOpaque(true);
                    lbl.setBackground(isSelected ? C_PRIMARY : C_FIELD_BG);
                    lbl.setForeground(NetUtil.TEXT_COLOR);
                    lbl.setBorder(BorderFactory.createEmptyBorder(3, 8, 3, 8));
                    return lbl;
                }
            });

            // 用 PromptTextField 替换默认编辑器，实现 placeholder
            PromptTextField promptTf = new PromptTextField(20, "如 gpt-4o、deepseek-chat");
            promptTf.setFont(NetUtil.FONT_TEXT);
            promptTf.setForeground(NetUtil.TEXT_COLOR);
            promptTf.setBackground(C_FIELD_BG);
            promptTf.setCaretColor(NetUtil.TEXT_COLOR);
            promptTf.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(C_BORDER),
                BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            combo.setEditor(new BasicComboBoxEditor() {
                @Override
                protected JTextField createEditorComponent() {
                    return promptTf;
                }
            });

            combo.setPreferredSize(new Dimension(280, 28));
            return combo;
        }

        private void init(ModelConfig editing) {
            JPanel form = new JPanel(new GridBagLayout());
            form.setBackground(C_INPUT_BG);
            form.setBorder(BorderFactory.createEmptyBorder(14, 14, 14, 14));
            GridBagConstraints gbc = new GridBagConstraints();
            gbc.insets = new Insets(5, 4, 5, 4);
            gbc.anchor = GridBagConstraints.WEST;
            gbc.fill   = GridBagConstraints.HORIZONTAL;

            // 模型别名
            addFormRow(form, gbc, 0, "模型别名：", aliasField, "用于下拉列表显示，如 GPT-4o、DeepSeek");
            // API Key
            addFormRow(form, gbc, 1, "自定义 Key：", keyField, "该模型的 API Key");
            // API 链接
            addFormRow(form, gbc, 2, "API 链接：", urlField, "如 https://api.openai.com/v1");
            // 模型全称（可编辑下拉框）
            addFormRow(form, gbc, 3, "模型全称：", nameCombo, "可输入或下拉选择预定义模型名");

            // 回填编辑数据
            if (editing != null) {
                aliasField.setText(editing.getAlias());
                keyField.setText(editing.getApiKey());
                urlField.setText(editing.getApiUrl());
                nameCombo.setSelectedItem(editing.getModelName());
            }

            // 按钮栏
            JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 0));
            btnBar.setOpaque(false);

            JButton btnOk     = createFilledButton("确定",   e -> onConfirm(editing));
            JButton btnCancel = createOutlinedButton("取消", C_TIME, e -> dispose());
            btnBar.add(btnOk);
            btnBar.add(btnCancel);

            getRootPane().setDefaultButton(btnOk);
            registerEscClose(btnCancel);

            JPanel content = new JPanel(new BorderLayout());
            content.setBackground(C_INPUT_BG);
            content.add(form,   BorderLayout.CENTER);
            content.add(btnBar, BorderLayout.SOUTH);

            add(content);
            pack();
            setResizable(false);
            setLocationRelativeTo(SwingUtilities.getWindowAncestor(this));
        }

        private void addFormRow(JPanel form, GridBagConstraints gbc, int row,
                                String label, JComponent comp, String tooltip) {
            gbc.gridy = row;

            gbc.gridx = 0;
            gbc.weightx = 0;
            JLabel lbl = new JLabel(label);
            lbl.setFont(NetUtil.FONT_TEXT);
            lbl.setForeground(NetUtil.TEXT_COLOR);
            form.add(lbl, gbc);

            gbc.gridx = 1;
            gbc.weightx = 1;
            if (tooltip != null) {
                comp.setToolTipText(tooltip);
            }
            form.add(comp, gbc);
        }

        private void onConfirm(ModelConfig editing) {
            String alias = aliasField.getText().trim();
            String key   = keyField.getText().trim();
            String url   = urlField.getText().trim();
            Object sel   = nameCombo.getSelectedItem();
            String name  = (sel != null) ? sel.toString().trim() : "";

            // 校验
            if (url.isEmpty()) {
                JOptionPane.showMessageDialog(this, "API 链接不能为空", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            if (name.isEmpty()) {
                JOptionPane.showMessageDialog(this, "模型全称不能为空", "提示", JOptionPane.WARNING_MESSAGE);
                return;
            }
            // 别名未填时默认用模型全称
            if (alias.isEmpty()) {
                alias = name;
            }

            ModelConfig mc = new ModelConfig(alias, key, url, name);
            if (editing != null) {
                // 编辑模式：替换旧配置
                int idx = modelConfigs.indexOf(editing);
                if (idx >= 0) {
                    modelConfigs.set(idx, mc);
                }
            } else {
                // 新增模式：别名已存在则更新，否则追加
                int existIdx = -1;
                for (int i = 0; i < modelConfigs.size(); i++) {
                    if (modelConfigs.get(i).getAlias().equals(alias)) {
                        existIdx = i;
                        break;
                    }
                }
                if (existIdx >= 0) {
                    modelConfigs.set(existIdx, mc);
                } else {
                    modelConfigs.add(mc);
                }
            }
            confirmed = true;

            // 刷新外部下拉选择器
            refreshModelSelector();

            dispose();
        }

        private void registerEscClose(JButton cancelBtn) {
            KeyStroke esc = KeyStroke.getKeyStroke(KeyEvent.VK_ESCAPE, 0);
            getRootPane().registerKeyboardAction(
                e -> cancelBtn.doClick(), esc, JComponent.WHEN_IN_FOCUSED_WINDOW);
        }
    }

    // ==================== 工具方法 ====================
    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    private static String escapeHtml(String s) {
        return s.replace("&", "&amp;")
                .replace("<", "&lt;")
                .replace(">", "&gt;")
                .replace("\n", "<br>")
                .replace("\"", "&quot;");
    }

    // ==================== 配置持久化 ====================
    @Override
    public void loadConfig(ConfigManager config) {
        int count = Integer.parseInt(config.get("ai.model.count", "0"));
        if (count > 0) {
            // 清空预置数据，从配置加载
            modelConfigs.clear();
            for (int i = 0; i < count; i++) {
                String al   = config.get("ai.model." + i + ".alias", "");
                String key  = config.get("ai.model." + i + ".key", "");
                String url  = config.get("ai.model." + i + ".url", "");
                String name = config.get("ai.model." + i + ".name", "");
                ModelConfig mc = new ModelConfig(al, key, url, name);
                modelConfigs.add(mc);
            }
        }
        // 加载完配置后刷新下拉选择器
        refreshModelSelector();
    }

    @Override
    public void saveConfig(ConfigManager config) {
        int size = modelConfigs.size();
        config.set("ai.model.count", String.valueOf(size));
        for (int i = 0; i < size; i++) {
            ModelConfig mc = modelConfigs.get(i);
            config.set("ai.model." + i + ".alias", mc.getAlias());
            config.set("ai.model." + i + ".key",   mc.getApiKey());
            config.set("ai.model." + i + ".url",   mc.getApiUrl());
            config.set("ai.model." + i + ".name",  mc.getModelName());
        }
    }
}
