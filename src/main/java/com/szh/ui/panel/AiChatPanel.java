package com.szh.ui.panel;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.szh.entity.ModelConfig;
import com.szh.manager.ConfigManager;
import com.szh.utils.NetUtil;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.pushingpixels.radiance.animation.api.Timeline;
import org.pushingpixels.radiance.animation.api.Timeline.TimelineState;
import org.pushingpixels.radiance.animation.api.callback.TimelineCallback;

import javax.swing.*;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import java.awt.*;
import java.awt.event.*;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/**
 * AI 对话面板 - 基于 langchain4j
 */
public class AiChatPanel extends AbstractCommandPanel {

    // ==================== 聊天组件 ====================
    private JPanel chatMessagePanel;     // 气泡消息列表容器
    private JLabel placeholderLabel;     // 空状态占位文字
    private AnimatedLabel typingLabel;    // "AI 思考中..." 打字指示器（已废弃，思考动画移入气泡内）
    private Timeline typingTimeline;     // 打字指示器呼吸动画（已废弃）
    private ChatBubble streamingBubble;   // 当前流式输出中的气泡（用于增量更新）
    private JPanel streamingWrapper;      // 流式气泡的父级包装容器
    private JTextArea inputArea;
    private JButton sendBtn;
    private JButton clearBtn;
    private JButton addModelBtn;
    private JLabel editModelBtn;
    private JScrollPane chatScrollPane;
    private JLayeredPane chatLayeredPane;     // 层级面板，放浮动按钮
    private JButton scrollToBottomBtn;         // 浮动「滚动到底部」按钮
    private Timeline scrollBtnTimeline;        // 按钮显隐动画时间线

    /** 流式响应进行中标记，防止过程中打开模态弹窗导致 UI 卡死 */
    private volatile boolean streamingActive = false;

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
        showPlaceholder();
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
            if (prompt != null && getText().isEmpty()) {
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

    /** 带 placeholder 的 JTextArea */
    private static class PromptTextArea extends JTextArea {
        private final String prompt;

        PromptTextArea(int rows, int cols, String prompt) {
            super(rows, cols);
            this.prompt = prompt;
        }

        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            if (getText().isEmpty()) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
                g2.setColor(C_TIME);
                FontMetrics fm = g2.getFontMetrics();
                Insets ins = getInsets();
                int y = fm.getAscent() + ins.top;
                g2.drawString(prompt, ins.left, y);
                g2.dispose();
            }
        }
    }

    // ==================== 聊天区域 ====================
    private JComponent createChatArea() {
        // 消息气泡列表（垂直排列）
        chatMessagePanel = new JPanel();
        chatMessagePanel.setLayout(new BoxLayout(chatMessagePanel, BoxLayout.Y_AXIS));
        chatMessagePanel.setBackground(NetUtil.C_BG);

        chatScrollPane = new JScrollPane(chatMessagePanel);
        chatScrollPane.setBorder(null);
        chatScrollPane.getViewport().setBackground(NetUtil.C_BG);
        chatScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(16);

        // 监听滚动：流式期间用户滚上去时显示浮动按钮
        chatScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (streamingActive) updateScrollToBottomButton();
        });

        // 层级面板：DEFAULT 层放滚动面板，PALETTE 层放浮动按钮
        chatLayeredPane = new JLayeredPane();

        chatLayeredPane.add(chatScrollPane, JLayeredPane.DEFAULT_LAYER);

        scrollToBottomBtn = createScrollToBottomFloatingButton();
        chatLayeredPane.add(scrollToBottomBtn, JLayeredPane.PALETTE_LAYER);

        // 窗口缩放时重设 scrollPane 尺寸并定位按钮
        chatLayeredPane.addComponentListener(new ComponentAdapter() {
            @Override
            public void componentResized(ComponentEvent e) {
                chatScrollPane.setBounds(0, 0, chatLayeredPane.getWidth(), chatLayeredPane.getHeight());
                positionScrollToBottomButton();
            }
        });

        return chatLayeredPane;
    }

    /** 创建浮动「滚动到底部」圆形按钮（绘制圆底+下箭头） */
    private JButton createScrollToBottomFloatingButton() {
        JButton btn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                // 半透明圆形背景
                g2.setColor(new Color(58, 58, 58, 200));
                g2.fillOval(2, 2, w - 4, h - 4);
                // 描边
                g2.setStroke(new BasicStroke(1.5f));
                g2.setColor(new Color(0x666666));
                g2.drawOval(2, 2, w - 4, h - 4);
                // 向下箭头
                g2.setColor(new Color(0xCCCCCC));
                g2.setFont(new Font("Segoe UI Symbol", Font.BOLD, 18));
                FontMetrics fm = g2.getFontMetrics();
                String arrow = "\u2304";
                int ax = (w - fm.stringWidth(arrow)) / 2;
                int ay = (h + fm.getAscent() - fm.getDescent()) / 2;
                g2.drawString(arrow, ax, ay);
                g2.dispose();
            }
        };
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setVisible(false);
        int btnSize = 42;
        btn.setSize(btnSize, btnSize);
        btn.setPreferredSize(new Dimension(btnSize, btnSize));
        btn.addActionListener(e -> {
            hideScrollToBottomButton();
            scrollToBottom();
        });
        return btn;
    }

    private void showPlaceholder() {
        streamingBubble = null;
        streamingWrapper = null;
        chatMessagePanel.removeAll();

        boolean hasModels = modelConfigs != null && !modelConfigs.isEmpty();

        if (hasModels) {
            // 已配置模型：显示品牌大字
            placeholderLabel = new JLabel("CoreTools AI", SwingConstants.CENTER);
            placeholderLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 42));
            placeholderLabel.setForeground(new Color(0x3A3A3A)); // 浅白色大字
            placeholderLabel.setAlignmentX(Component.CENTER_ALIGNMENT);
            chatMessagePanel.add(Box.createVerticalGlue());
            chatMessagePanel.add(placeholderLabel);
            chatMessagePanel.add(Box.createVerticalGlue());
        } else {
            // 未配置模型：显示引导提示
            placeholderLabel = new JLabel("AI 对话助手", SwingConstants.CENTER);
            placeholderLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 14));
            placeholderLabel.setForeground(C_TIME);
            placeholderLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            JLabel subLabel = new JLabel("点击「＋ 添加模型」配置后即可使用", SwingConstants.CENTER);
            subLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            subLabel.setForeground(C_TIME);
            subLabel.setAlignmentX(Component.CENTER_ALIGNMENT);

            chatMessagePanel.add(Box.createVerticalGlue());
            chatMessagePanel.add(placeholderLabel);
            chatMessagePanel.add(Box.createVerticalStrut(8));
            chatMessagePanel.add(subLabel);
            chatMessagePanel.add(Box.createVerticalGlue());
        }
        chatMessagePanel.revalidate();
        chatMessagePanel.repaint();
    }

    // ==================== 输入栏 ====================
    private JPanel createInputBar() {
        JPanel bar = new JPanel(new BorderLayout(8, 0));
        bar.setBackground(C_INPUT_BG);
        bar.setBorder(BorderFactory.createEmptyBorder(0, 8, 0, 8));

        // 左侧：模型下拉选择器 + 编辑按钮 + 添加模型按钮
        JPanel leftBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftBtnPanel.setOpaque(false);
        leftBtnPanel.add(modelSelector);
        // 编辑当前选中模型的图标标签
        editModelBtn = new JLabel(loadEditIcon(24));
        editModelBtn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        editModelBtn.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent e) { editSelectedModel(); }
        });
        leftBtnPanel.add(editModelBtn);
        addModelBtn = createFilledButton("＋ 添加模型", e -> showAddModelDialog());
        leftBtnPanel.add(addModelBtn);
        bar.add(leftBtnPanel, BorderLayout.WEST);

        // 中间：输入框（带 placeholder）
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setOpaque(false);
        inputArea = new PromptTextArea(1, 0, "输入消息，Enter 发送");
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

        // 输入栏宽度
        bar.setPreferredSize(new Dimension(900, 40));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 40));
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

        // 获取当前选中的模型
        ModelConfig selectedModel = getSelectedModel();
        if (selectedModel == null || selectedModel.getModelName().isEmpty()) {
            JOptionPane.showMessageDialog(this, "请先添加并选择一个模型", "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 清除占位文字，显示用户消息（右侧绿色气泡）
        clearPlaceholderIfNeeded();
        String userTime = LocalTime.now().format(TF);
        addMessageBubble("你", text, userTime, true);
        inputArea.setText("");

        // 先创建空的 AI 气泡，准备流式填充（thinking=true 表示等待首 token）
        String aiTime = LocalTime.now().format(TF);
        ChatBubble aiBubble = new ChatBubble("AI", "", aiTime, false, true);
        aiBubble.setAlpha(0.0f);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));
        JPanel alignPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(aiBubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();

        // 记录流式气泡引用，供 handler 增量更新
        this.streamingBubble = aiBubble;
        this.streamingWrapper = wrapper;

        // 淡入动画
        Timeline.builder(aiBubble)
            .setDuration(300)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    aiBubble.repaint();
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    aiBubble.setAlpha(timelinePosition);
                    aiBubble.repaint();
                }
            })
            .build()
            .play();

        scrollToBottom();

        // 标记流式开始，禁用编辑按钮防止模态弹窗卡死 UI
        streamingActive = true;
        setModelButtonsEnabled(false);
        // 思考中状态已在 AI 气泡内部显示（● AI 思考中...），不再单独添加指示器

        // 异步流式调用 AI API（虚拟线程）
        Thread.ofVirtual().start(() -> {
            try {
                String apiKey = selectedModel.getApiKey();
                String apiUrl = selectedModel.getApiUrl();
                String modelName = selectedModel.getModelName();

                // 去除 baseUrl 末尾的 /chat/completions，LangChain4j 会自动追加
                String baseUrl = apiUrl;
                if (baseUrl != null && baseUrl.endsWith("/chat/completions")) {
                    baseUrl = baseUrl.substring(0, baseUrl.length() - "/chat/completions".length());
                }

                System.out.println("[AI Chat] Calling API: " + baseUrl + " | Model: " + modelName);

                OpenAiStreamingChatModel model = OpenAiStreamingChatModel.builder()
                    .apiKey(apiKey.isEmpty() ? "sk-placeholder" : apiKey)
                    .baseUrl(baseUrl)
                    .modelName(modelName)
                    .logRequests(true)
                    .logResponses(true)
                    .build();

                StringBuilder fullResponse = new StringBuilder();
                model.chat(text, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        fullResponse.append(partialResponse);
                        SwingUtilities.invokeLater(() -> {
                            if (streamingBubble != null) {
                                streamingBubble.appendContent(partialResponse);
                                // 不再强制滚动到底部，让用户自由浏览
                            }
                        });
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        SwingUtilities.invokeLater(() -> {
                            streamingBubble = null;
                            streamingWrapper = null;
                            streamingActive = false;
                            hideScrollToBottomButton();
                            setModelButtonsEnabled(true);
                        });
                    }

                    @Override
                    public void onError(Throwable error) {
                        error.printStackTrace();
                        SwingUtilities.invokeLater(() -> {
                            // 移除流式气泡
                            if (streamingWrapper != null) {
                                chatMessagePanel.remove(streamingWrapper);
                                streamingWrapper = null;
                                streamingBubble = null;
                                chatMessagePanel.revalidate();
                                chatMessagePanel.repaint();
                            }
                            streamingActive = false;
                            hideScrollToBottomButton();
                            setModelButtonsEnabled(true);
                            String errTime = LocalTime.now().format(TF);
                            String errDetail = error.getMessage() != null ? error.getMessage() : "";
                            Throwable cause = error.getCause();
                            if (cause != null && cause.getMessage() != null) {
                                errDetail = cause.getMessage();
                            }
                            addErrorMessage("API call failed: " + errDetail, errTime);
                        });
                    }
                });
            } catch (Exception ex) {
                ex.printStackTrace();
                SwingUtilities.invokeLater(() -> {
                    if (streamingWrapper != null) {
                        chatMessagePanel.remove(streamingWrapper);
                        streamingWrapper = null;
                        streamingBubble = null;
                        chatMessagePanel.revalidate();
                        chatMessagePanel.repaint();
                    }
                    streamingActive = false;
                    hideScrollToBottomButton();
                    setModelButtonsEnabled(true);
                    String errTime = LocalTime.now().format(TF);
                    String errDetail = ex.getMessage();
                    Throwable cause = ex.getCause();
                    if (cause != null && cause.getMessage() != null) {
                        errDetail = cause.getMessage();
                    }
                    addErrorMessage("API call failed: " + errDetail, errTime);
                });
            }
        });
    }

    /** 获取当前选中的模型配置 */
    private ModelConfig getSelectedModel() {
        if (modelSelector == null || modelSelector.getItemCount() == 0) return null;
        Object item = modelSelector.getSelectedItem();
        if (item instanceof ModelConfig mc) {
            // 过滤占位项
            if ("请先添加模型".equals(mc.getAlias())) return null;
            return mc;
        }
        return null;
    }

    /** 清除占位内容 */
    private void clearPlaceholderIfNeeded() {
        if (placeholderLabel != null && placeholderLabel.getParent() != null) {
            placeholderLabel = null;
            chatMessagePanel.removeAll();
            chatMessagePanel.revalidate();
            chatMessagePanel.repaint();
        }
    }

    /** 添加消息气泡到聊天面板（带动画） */
    private void addMessageBubble(String sender, String content, String time, boolean isUser) {
        ChatBubble bubble = new ChatBubble(sender, content, time, isUser);
        bubble.setAlpha(0.0f);

        // 外层包装：控制左/右对齐和最大宽度
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, bubble.getPreferredSize().height + 10));
        wrapper.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        JPanel alignPanel = new JPanel(new FlowLayout(
            isUser ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(bubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();

        // 淡入动画
        Timeline timeline = Timeline.builder(bubble)
            .setDuration(450)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    bubble.repaint();
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    bubble.setAlpha(timelinePosition);
                    bubble.repaint();
                }
            })
            .build();
        timeline.play();

        // 自动滚到底部（带平滑动画）
        scrollToBottom();
    }

    /** 添加错误消息气泡 */
    private void addErrorMessage(String content, String time) {
        ChatBubble bubble = new ChatBubble("系统", content, time, false);
        bubble.setBubbleBg(new Color(0x4A2020)); // 红色调背景
        bubble.setBorderColor(new Color(0xC62828));
        bubble.setAlpha(0.0f);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, bubble.getPreferredSize().height + 10));
        wrapper.setBorder(BorderFactory.createEmptyBorder(4, 12, 4, 12));

        JPanel alignPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(bubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();

        Timeline.builder(bubble)
            .setDuration(350)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    bubble.repaint();
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    bubble.setAlpha(timelinePosition);
                    bubble.repaint();
                }
            })
            .build()
            .play();

        scrollToBottom();
    }

    // ==================== 打字指示器 ====================
    private void showTypingIndicator() {
        if (typingLabel != null && typingLabel.getParent() != null) return; // 已显示

        clearPlaceholderIfNeeded();

        typingLabel = new AnimatedLabel("● AI 思考中", SwingConstants.LEFT);
        typingLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
        typingLabel.setForeground(C_PRIMARY);
        typingLabel.setBorder(BorderFactory.createEmptyBorder(6, 20, 6, 20));

        JPanel wrapper = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        wrapper.setOpaque(false);
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, 32));
        wrapper.add(typingLabel);

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();
        scrollToBottom();

        // 呼吸动画：循环改变透明度
        typingTimeline = Timeline.builder(typingLabel)
            .setDuration(800)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    if (newState == TimelineState.DONE && typingLabel != null) {
                        // 反转方向继续呼吸
                        typingLabel.forward = !typingLabel.forward;
                        Timeline reverse = Timeline.builder(typingLabel)
                            .setDuration(800)
                            .addCallback(this)
                            .build();
                        typingTimeline = reverse;
                        reverse.play();
                    }
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    float alpha = typingLabel.forward
                        ? 0.35f + timelinePosition * 0.65f   // 0.35 → 1.0
                        : 1.0f - timelinePosition * 0.65f;   // 1.0 → 0.35
                    typingLabel.setAlpha(alpha);
                    typingLabel.repaint();
                }
            })
            .build();
        typingTimeline.play();
    }

    private void hideTypingIndicator() {
        if (typingTimeline != null) {
            typingTimeline.abort();
            typingTimeline = null;
        }
        if (typingLabel != null && typingLabel.getParent() != null) {
            typingLabel.getParent().remove(typingLabel.getParent());
            typingLabel = null;
            chatMessagePanel.revalidate();
            chatMessagePanel.repaint();
        }
    }

    // ==================== 滚动 ====================
    private void scrollToBottom() {
        SwingUtilities.invokeLater(() -> {
            JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
            int from = vBar.getValue();
            int to = vBar.getMaximum();
            if (to <= from + vBar.getVisibleAmount()) return; // 已经在底部

            Timeline scrollTimeline = Timeline.builder(new Object())
                .setDuration(300)
                .addCallback(new TimelineCallback() {
                    @Override
                    public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                       float durationFraction, float timelinePosition) {
                        // no-op
                    }
                    @Override
                    public void onTimelinePulse(float durationFraction, float timelinePosition) {
                        int val = from + (int)((to - from) * timelinePosition);
                        vBar.setValue(val);
                    }
                })
                .build();
            scrollTimeline.play();
        });
    }

    /** 是否已滚动到底部附近（容差 20px） */
    private boolean isScrolledToBottom() {
        JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
        int val = vBar.getValue();
        int max = vBar.getMaximum();
        int extent = vBar.getVisibleAmount();
        return val + extent >= max - 20;
    }

    /** 定位浮动按钮：水平居中，距视口底部 16px */
    private void positionScrollToBottomButton() {
        if (scrollToBottomBtn == null || chatLayeredPane == null) return;
        int w = chatLayeredPane.getWidth();
        int h = chatLayeredPane.getHeight();
        int bw = scrollToBottomBtn.getWidth();
        int bh = scrollToBottomBtn.getHeight();
        scrollToBottomBtn.setBounds((w - bw) / 2, h - bh - 16, bw, bh);
    }

    /** 根据滚动位置和流式状态决定浮动按钮显隐 */
    private void updateScrollToBottomButton() {
        if (scrollToBottomBtn == null) return;
        if (streamingActive && !isScrolledToBottom()) {
            showScrollToBottomButton();
        } else {
            hideScrollToBottomButton();
        }
    }

    private void showScrollToBottomButton() {
        if (scrollToBottomBtn == null || scrollToBottomBtn.isVisible()) return;
        scrollToBottomBtn.setVisible(true);
        positionScrollToBottomButton();
        scrollToBottomBtn.repaint();
    }

    private void hideScrollToBottomButton() {
        if (scrollToBottomBtn == null || !scrollToBottomBtn.isVisible()) return;
        scrollToBottomBtn.setVisible(false);
        scrollToBottomBtn.repaint();
    }

    private void clearChat() {
        showPlaceholder();
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
        refreshModelSelector();
        showPlaceholder();
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

        // 选中模型只切换，不弹窗

        refreshModelSelector();
        return combo;
    }

    /** 弹出模型实体详情 */
    private void showModelDetail(ModelConfig mc) {
        Window owner = SwingUtilities.getWindowAncestor(this);
        JDialog detail = new JDialog(owner, "当前模型: " + mc.comboLabel(), Dialog.ModalityType.APPLICATION_MODAL);
        detail.setResizable(false);

        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBackground(C_INPUT_BG);
        panel.setBorder(BorderFactory.createEmptyBorder(16, 20, 16, 20));
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(4, 0, 4, 10);
        gbc.anchor = GridBagConstraints.WEST;

        addDetailRow(panel, gbc, 0, "别　名：", mc.getAlias());
        addDetailRow(panel, gbc, 1, "Key　：", mc.getApiKey());
        addDetailRow(panel, gbc, 2, "接　口：", mc.getApiUrl());
        addDetailRow(panel, gbc, 3, "全　称：", mc.getModelName());

        JButton btnClose = createOutlinedButton("关闭", C_TIME, e -> detail.dispose());
        JPanel btnBar = new JPanel(new FlowLayout(FlowLayout.CENTER, 0, 10));
        btnBar.setOpaque(false);
        btnBar.add(btnClose);

        JPanel content = new JPanel(new BorderLayout());
        content.setBackground(C_INPUT_BG);
        content.add(panel, BorderLayout.CENTER);
        content.add(btnBar, BorderLayout.SOUTH);

        detail.add(content);
        detail.pack();
        detail.setLocationRelativeTo(owner);
        detail.setVisible(true);
    }

    private void addDetailRow(JPanel p, GridBagConstraints gbc, int row, String label, String value) {
        gbc.gridy = row;

        gbc.gridx = 0;
        gbc.weightx = 0;
        JLabel lbl = new JLabel(label);
        lbl.setFont(NetUtil.FONT_TEXT);
        lbl.setForeground(C_TIME);
        p.add(lbl, gbc);

        gbc.gridx = 1;
        gbc.weightx = 1;
        JLabel val = new JLabel(value.isEmpty() ? "（未设置）" : value);
        val.setFont(NetUtil.FONT_TEXT);
        val.setForeground(value.isEmpty() ? C_TIME : NetUtil.TEXT_COLOR);
        p.add(val, gbc);
    }

    /** 刷新模型下拉选择器内容 */
    private void refreshModelSelector() {
        if (modelSelector == null) return;
        modelSelector.removeAllItems();
        if (modelConfigs.isEmpty()) {
            // 没有模型时：显示提示文字并禁用下拉选择器 + 编辑按钮
            ModelConfig placeholder = new ModelConfig("请先添加模型", "", "", "");
            modelSelector.addItem(placeholder);
            modelSelector.setEnabled(false);
            if (editModelBtn != null) editModelBtn.setEnabled(false);
        } else {
            modelSelector.setEnabled(true);
            if (editModelBtn != null) editModelBtn.setEnabled(true);
            for (ModelConfig mc : modelConfigs) {
                modelSelector.addItem(mc);
            }
            // 默认选中第一个
            modelSelector.setSelectedIndex(0);
        }
        modelSelector.repaint();
    }

    private void editSelectedModel() {
        if (streamingActive) return;
        if (modelSelector == null) return;
        Object sel = modelSelector.getSelectedItem();
        if (!(sel instanceof ModelConfig mc)) return;
        if (mc.getModelName() == null || mc.getModelName().isEmpty()) return;
        Window owner = SwingUtilities.getWindowAncestor(this);
        new ModelConfigDialog(owner, mc).setVisible(true);
        refreshModelSelector();
        showPlaceholder();
    }

    private void showAddModelDialog() {
        if (streamingActive) return;
        Window owner = SwingUtilities.getWindowAncestor(this);
        new ModelConfigDialog(owner, null).setVisible(true);
        refreshModelSelector();
        showPlaceholder();
    }

    /** 流式进行中禁用/启用模型编辑相关按钮 */
    private void setModelButtonsEnabled(boolean enabled) {
        if (addModelBtn != null) addModelBtn.setEnabled(enabled);
        // 编辑按钮恢复时还要检查是否确实有模型可编辑
        if (editModelBtn != null) {
            editModelBtn.setEnabled(enabled && !modelConfigs.isEmpty());
        }
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

    /** 编辑图标 — 从 resources/icons/edit.svg 加载 */
    private static Icon loadEditIcon(int sz) {
        URL url = AiChatPanel.class.getResource("/icons/edit.svg");
        if (url != null) {
            return new FlatSVGIcon(url).derive(sz, sz);
        }
        return new ColorIcon(14);
    }

    /** AI 头像 — 圆形裁剪 + 从 resources/icons/ai.svg 加载 */
    private static Icon loadAiAvatar(int sz) {
        URL url = AiChatPanel.class.getResource("/icons/ai.svg");
        if (url != null) {
            Icon svgIcon = new FlatSVGIcon(url).derive(sz - 4, sz - 4);
            return new Icon() {
                @Override public void paintIcon(Component c, Graphics g, int x, int y) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                    // 圆形背景
                    g2.setColor(new Color(0x444444));
                    g2.fillOval(x, y, sz, sz);
                    // 圆形裁剪
                    g2.setClip(new java.awt.geom.Ellipse2D.Float(x + 2, y + 2, sz - 4, sz - 4));
                    svgIcon.paintIcon(c, g2, x + 2, y + 2);
                    g2.setClip(null);
                    // 外圈
                    g2.setColor(new Color(0x666666));
                    g2.setStroke(new BasicStroke(1.2f));
                    g2.drawOval(x, y, sz, sz);
                    g2.dispose();
                }
                @Override public int getIconWidth()  { return sz; }
                @Override public int getIconHeight() { return sz; }
            };
        }
        return new ColorIcon(sz);
    }

    /** 占位图标（资源缺失时显示） */
    private static class ColorIcon implements Icon {
        private final int size;
        ColorIcon(int size) { this.size = size; }
        @Override public void paintIcon(Component c, Graphics g, int x, int y) {
            g.setColor(Color.GRAY);
            g.fillRect(x, y, size, size);
        }
        @Override public int getIconWidth() { return size; }
        @Override public int getIconHeight() { return size; }
    }

    private static String toHex(Color c) {
        return String.format("#%02x%02x%02x", c.getRed(), c.getGreen(), c.getBlue());
    }

    // ==================== 聊天气泡组件 ====================

    /** 聊天气泡面板（支持 radiance 透明度动画） */
    private static class ChatBubble extends JPanel {
        private String sender;
        private String content;
        private String time;
        private boolean isUser;
        private boolean isError;
        private float alpha = 1.0f;
        private Color bubbleBg;
        private Color borderColor;
        private JTextArea contentArea;      // 内容文本区域引用，支持流式增量更新
        private boolean thinking;            // 是否正在等待 AI 首 token
        private float thinkingAlpha = 1.0f;  // "思考中..." 文字呼吸动画 alpha
        private boolean thinkingForward = true;
        private Timeline thinkingTimeline;   // 思考中呼吸动画时间线

        ChatBubble(String sender, String content, String time, boolean isUser) {
            this(sender, content, time, isUser, false);
        }

        ChatBubble(String sender, String content, String time, boolean isUser, boolean thinking) {
            this.sender = sender;
            this.content = content;
            this.time = time;
            this.isUser = isUser;
            this.isError = "系统".equals(sender);
            this.thinking = thinking;
            this.bubbleBg = isUser ? new Color(0x1B5E20)
                    : isError ? new Color(0x4A2020)
                    : new Color(0x21212E);      // AI 消息：深蓝灰底色
            this.borderColor = isUser ? new Color(0x2E7D32)
                    : isError ? new Color(0xC62828)
                    : new Color(0x2F2F40);       // AI 消息：柔和边框
            setOpaque(false);
            buildLayout();
            if (thinking) {
                startThinkingAnimation();
            }
        }

        void setBubbleBg(Color bg) {
            this.bubbleBg = bg;
            for (Component c : getComponents()) {
                if (c instanceof JPanel inner) {
                    inner.setBackground(bg);
                }
            }
        }

        void setBorderColor(Color bc) { this.borderColor = bc; }

        // ---- alpha property for radiance Timeline ----
        public float getAlpha() { return alpha; }
        public void setAlpha(float alpha) {
            this.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        }

        /** 流式追加文本内容（在 EDT 上调用） */
        void appendContent(String text) {
            if (contentArea == null) return;
            if (thinking) {
                // 首 token 到达，退出思考模式，清空"思考中"占位文字
                thinking = false;
                stopThinkingAnimation();
                contentArea.setText("");
                contentArea.setForeground(isUser ? new Color(0xE8E8E8)
                        : isError ? new Color(0xFFCDD2)
                        : new Color(0xEAEAEA));
            }
            this.content += text;
            contentArea.append(text);
            // 强制 JTextArea 按当前宽度重新计算所需高度
            int curW = contentArea.getWidth();
            if (curW <= 0) {
                Container p = getParent();
                curW = (p != null && p.getWidth() > 0) ? p.getWidth() - 56 : 500 - 56;
            }
            if (curW > 0) {
                contentArea.setSize(curW, Short.MAX_VALUE);
            }
            revalidateAndRepaint();
        }

        /** 启动"思考中..."呼吸动画 */
        void startThinkingAnimation() {
            if (thinkingTimeline != null) return;
            TimelineCallback cb = new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    if (newState == TimelineState.DONE && thinking) {
                        thinkingForward = !thinkingForward;
                        Timeline reverse = Timeline.builder(ChatBubble.this)
                            .setDuration(800)
                            .addCallback(this)
                            .build();
                        thinkingTimeline = reverse;
                        reverse.play();
                    }
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    thinkingAlpha = thinkingForward
                        ? 0.35f + timelinePosition * 0.65f   // 0.35 → 1.0
                        : 1.0f - timelinePosition * 0.65f;   // 1.0 → 0.35
                    if (contentArea != null) contentArea.repaint();
                }
            };
            thinkingTimeline = Timeline.builder(this)
                .setDuration(800)
                .addCallback(cb)
                .build();
            thinkingTimeline.play();
        }

        /** 停止"思考中..."呼吸动画 */
        void stopThinkingAnimation() {
            if (thinkingTimeline != null) {
                thinkingTimeline.abort();
                thinkingTimeline = null;
            }
            thinkingAlpha = 1.0f;
        }

        /** 重新计算布局（内容尺寸变化后调用） */
        void revalidateAndRepaint() {
            revalidate();
            repaint();
            Container p = getParent();
            while (p != null) {
                if (p instanceof JComponent jc) {
                    jc.revalidate();
                    jc.repaint();
                }
                p = p.getParent();
            }
        }

        private void buildLayout() {
            setLayout(new BorderLayout());

            // 头部：头像/发送者 + 时间
            JPanel header = new JPanel(new BorderLayout());
            header.setOpaque(false);

            if (!isUser && !isError) {
                // AI 消息：显示圆形头像图标
                JLabel avatarLabel = new JLabel(loadAiAvatar(24));
                avatarLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
                JPanel avatarPanel = new JPanel(new BorderLayout());
                avatarPanel.setOpaque(false);
                avatarPanel.add(avatarLabel, BorderLayout.CENTER);
                header.add(avatarPanel, BorderLayout.WEST);
            } else {
                // 用户/系统消息：显示发送者文字
                JLabel senderLabel = new JLabel(sender);
                senderLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
                senderLabel.setForeground(isUser ? new Color(0xA5D6A7) : new Color(0xEF9A9A));
                header.add(senderLabel, BorderLayout.WEST);
            }

            JLabel timeLabel = new JLabel(time);
            timeLabel.setFont(new Font("Consolas", Font.PLAIN, 10));
            timeLabel.setForeground(new Color(0x777777));

            header.add(timeLabel, BorderLayout.EAST);
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 6, 0));

            // 内容：使用 JTextArea 自动换行
            String initialText = thinking ? "● AI 思考中..." : content;
            this.contentArea = new JTextArea(initialText, 0, 45) {
                @Override
                protected void paintComponent(Graphics g) {
                    if (thinking && thinkingAlpha < 1.0f) {
                        Graphics2D g2 = (Graphics2D) g.create();
                        try {
                            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, thinkingAlpha));
                            super.paintComponent(g2);
                        } finally {
                            g2.dispose();
                        }
                    } else {
                        super.paintComponent(g);
                    }
                }
            };
            this.contentArea.setFont(new Font(Font.SANS_SERIF, Font.PLAIN, 14));
            this.contentArea.setForeground(isUser ? new Color(0xE8E8E8)
                    : isError ? new Color(0xFFCDD2)
                    : thinking ? C_PRIMARY
                    : new Color(0xEAEAEA));     // AI 消息文字更亮
            this.contentArea.setOpaque(false);
            this.contentArea.setEditable(false);
            this.contentArea.setLineWrap(true);
            this.contentArea.setWrapStyleWord(true);
            this.contentArea.setCursor(null);
            this.contentArea.setFocusable(false);

            // 内容内边距面板
            JPanel contentPane = new JPanel(new BorderLayout());
            contentPane.setOpaque(false);
            contentPane.add(this.contentArea, BorderLayout.CENTER);

            JPanel innerPanel = new JPanel(new BorderLayout());
            innerPanel.setBackground(bubbleBg);
            innerPanel.setBorder(BorderFactory.createEmptyBorder(10, 14, 10, 14));
            innerPanel.add(header, BorderLayout.NORTH);
            innerPanel.add(contentPane, BorderLayout.CENTER);

            add(innerPanel, BorderLayout.CENTER);
            setBorder(BorderFactory.createEmptyBorder(2, 0, 2, 0));
        }

        @Override
        public Dimension getPreferredSize() {
            Container parent = getParent();
            int parentW = (parent != null) ? parent.getWidth() : 0;
            int maxW = (parentW > 0) ? Math.min(500, parentW) : 500;

            // 让 JTextArea 按其宽度重新计算换行后的真实高度
            if (contentArea != null) {
                int textWidth = maxW - 32; // 减去内外面板边距
                if (textWidth > 0) {
                    contentArea.setSize(textWidth, Short.MAX_VALUE);
                }
            }
            return new Dimension(maxW, super.getPreferredSize().height + 4);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 使用 alpha 做整体透明度
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));

            int w = getWidth() - 1;
            int h = getHeight() - 1;

            // 绘制圆角矩形背景
            g2.setColor(bubbleBg);
            g2.fillRoundRect(0, 0, w, h, 14, 14);

            // 绘制边框
            g2.setStroke(new BasicStroke(1.0f));
            g2.setColor(new Color(
                borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(),
                (int)(borderColor.getAlpha() * alpha)));
            g2.drawRoundRect(0, 0, w, h, 14, 14);

            g2.dispose();
            super.paintComponent(g);
        }
    }

    /** 支持透明度动画的 JLabel（用于打字指示器和滚动等） */
    private static class AnimatedLabel extends JLabel {
        private float alpha = 1.0f;
        boolean forward = true;

        AnimatedLabel(String text, int horizontalAlignment) {
            super(text, horizontalAlignment);
        }

        public float getAlpha() { return alpha; }
        public void setAlpha(float alpha) {
            this.alpha = Math.max(0.0f, Math.min(1.0f, alpha));
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, alpha));
            g2.setColor(getBackground());
            g2.fillRect(0, 0, getWidth(), getHeight());
            g2.setColor(getForeground());
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int y = (getHeight() + fm.getAscent() - fm.getDescent()) / 2;
            int x;
            switch (getHorizontalAlignment()) {
                case SwingConstants.CENTER -> x = (getWidth() - fm.stringWidth(getText())) / 2;
                case SwingConstants.RIGHT  -> x = getWidth() - fm.stringWidth(getText()) - getInsets().right;
                default                    -> x = getInsets().left;
            }
            g2.drawString(getText(), x, y);
            g2.dispose();
        }
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

        // 如果主界面还是占位状态（无聊天记录），刷新占位文字
        if (placeholderLabel != null && placeholderLabel.getParent() != null) {
            showPlaceholder();
        }
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
