package com.szh.ui.panel;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.szh.entity.ModelConfig;
import com.szh.manager.ConfigManager;
import com.szh.utils.AiUtils;
import com.szh.utils.NetUtil;
import dev.langchain4j.agent.tool.ToolExecutionRequest;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ToolExecutionResultMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.memory.ChatMemory;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.request.ChatRequest;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import dev.langchain4j.model.openai.OpenAiStreamingChatModel;
import org.pushingpixels.radiance.animation.api.Timeline;
import org.pushingpixels.radiance.animation.api.Timeline.TimelineState;
import org.pushingpixels.radiance.animation.api.callback.TimelineCallback;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;
import java.net.URL;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

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
    private UndoManager inputUndoManager;   // inputArea 的撤销管理器
    private JButton sendBtn;
    private JButton addModelBtn;
    private JButton editModelBtn;
    private JScrollPane chatScrollPane;
    private JLayeredPane chatLayeredPane;     // 层级面板，放浮动按钮
    private JButton scrollToBottomBtn;         // 浮动「滚动到底部」按钮
    private Timeline scrollBtnTimeline;        // 按钮显隐动画时间线

    /** 流式响应进行中标记，防止过程中打开模态弹窗导致 UI 卡死 */
    private volatile boolean streamingActive = false;

    /** 停止流式输出标记，用户点击停止时设为 true，后台线程检测到后中断流式输出 */
    private volatile boolean stopRequested = false;

    /** 用户在流式输出期间手动滚上去了，不再自动滚动到底部 */
    private volatile boolean userScrolledUp = false;

    /** 对话记忆，保留最近 20 轮对话（用户+AI 各算一轮） */
    private final ChatMemory chatMemory = MessageWindowChatMemory.builder()
            .maxMessages(40)
            .build();

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

        // 注册预置动态工具（getCurrentTime 等），无需创建类即可扩展 AI 能力
        AiUtils.registerPresetDynamicTools();

        // 在父类构造器中被子类覆盖调用，此时字段初始化器尚未执行，需手动初始化
        if (modelConfigs == null) {
            modelConfigs = new java.util.ArrayList<>();
            // 不从预置模型加载，用户自行添加
        }
        // 模型下拉选择器
        modelSelector = createModelSelector();

        add(createChatArea(), BorderLayout.CENTER);

        // 底部输入栏：自适应宽度，留少量左右边距
        JPanel southWrapper = new JPanel(new BorderLayout());
        southWrapper.setBackground(C_INPUT_BG);
        southWrapper.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createMatteBorder(1, 0, 0, 0, C_BORDER),
            BorderFactory.createEmptyBorder(6, 12, 6, 12)));
        southWrapper.add(createInputBar(), BorderLayout.CENTER);
        add(southWrapper, BorderLayout.SOUTH);

        // 初始占位
        showPlaceholder();

        // 监听面板可见性变化：切换 Tab 时暂停呼吸动画，避免后台闪烁
        addHierarchyListener(e -> {
            if ((e.getChangeFlags() & HierarchyEvent.SHOWING_CHANGED) != 0) {
                if (!isShowing()) {
                    // 面板隐藏时，暂停当前流式气泡的思考动画
                    if (streamingBubble != null && streamingBubble.thinking) {
                        streamingBubble.pauseThinkingAnimation();
                    }
                } else {
                    // 面板重新显示时，恢复思考动画
                    if (streamingBubble != null && streamingBubble.thinking) {
                        streamingBubble.resumeThinkingAnimation();
                    }
                }
            }
        });
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
        // 圆角滚动条（自定义 UI）
        chatScrollPane.getVerticalScrollBar().setUI(new javax.swing.plaf.basic.BasicScrollBarUI() {
            @Override
            protected void configureScrollBarColors() {
                thumbColor = new Color(0x666666);
                trackColor = new Color(0x2A2A2A);
            }
            @Override
            protected JButton createDecreaseButton(int orientation) {
                return createZeroSizeButton();
            }
            @Override
            protected JButton createIncreaseButton(int orientation) {
                return createZeroSizeButton();
            }
            private JButton createZeroSizeButton() {
                JButton b = new JButton();
                Dimension zero = new Dimension(0, 0);
                b.setPreferredSize(zero);
                b.setMinimumSize(zero);
                b.setMaximumSize(zero);
                return b;
            }
            @Override
            protected void paintThumb(Graphics g, JComponent c, Rectangle thumbBounds) {
                if (thumbBounds.isEmpty() || !c.isEnabled()) return;
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = thumbBounds.width;
                int h = thumbBounds.height;
                // 圆角半径取宽度的一半，形成胶囊/药丸形状
                int arc = Math.min(w, h) / 2;
                int margin = 3;
                int x = thumbBounds.x + margin;
                int y = thumbBounds.y + margin;
                int rw = w - margin * 2;
                int rh = h - margin * 2;
                if (rw <= 0) rw = w;
                if (rh <= 0) rh = h;
                // 圆角滑块填充
                g2.setColor(thumbColor);
                g2.fillRoundRect(x, y, rw, rh, arc, arc);
                g2.dispose();
            }
            @Override
            protected void paintTrack(Graphics g, JComponent c, Rectangle trackBounds) {
                // 轨道完全透明，不绘制任何内容
            }
        });
        // 确保滚动条宽度合适
        chatScrollPane.getVerticalScrollBar().setPreferredSize(new Dimension(10, 0));
        // 丝滑滚动优化：鼠标滚轮增量
        chatScrollPane.getVerticalScrollBar().setUnitIncrement(32);
        chatScrollPane.getVerticalScrollBar().setBlockIncrement(120);

        // 监听滚动：用户滚上去时停止自动滚动 / 显示浮动按钮
        chatScrollPane.getVerticalScrollBar().addAdjustmentListener(e -> {
            if (e.getValueIsAdjusting()) return;
            if (streamingActive) {
                if (!isScrolledToBottom()) {
                    userScrolledUp = true;
                }
            }
            updateScrollToBottomButton();
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

    /** 创建浮动「滚动到底部」圆形按钮（圆底 + caret-bottom.svg 图标） */
    private JButton createScrollToBottomFloatingButton() {
        int btnSize = 42;
        int iconSize = 20;
        Icon caretIcon = loadSvgIcon("/icons/caret-bottom.svg", iconSize);

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
                g2.dispose();
                // 绘制 SVG 图标居中
                if (caretIcon != null) {
                    int ix = (w - iconSize) / 2;
                    int iy = (h - iconSize) / 2;
                    caretIcon.paintIcon(this, g, ix, iy);
                }
            }
        };
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setVisible(false);
        btn.setSize(btnSize, btnSize);
        btn.setPreferredSize(new Dimension(btnSize, btnSize));
        btn.addActionListener(e -> {
            userScrolledUp = false;
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

        // 左侧：模型下拉选择器 + 编辑按钮 + 添加模型按钮（图标按钮，垂直居中对齐）
        JPanel leftBtnPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
        leftBtnPanel.setOpaque(false);
        // 用垂直居中的包装
        JPanel selectorWrap = new JPanel(new GridBagLayout());
        selectorWrap.setOpaque(false);
        selectorWrap.add(modelSelector);
        leftBtnPanel.add(selectorWrap);

        // 编辑按钮（图标按钮，和发送按钮对齐）
        editModelBtn = createIconButton("/icons/edit.svg", 20, "编辑模型", e -> editSelectedModel());
        leftBtnPanel.add(editModelBtn);

        // 添加模型按钮（图标按钮，和发送按钮对齐）
        addModelBtn = createIconButton("/icons/add.svg", 20, "添加模型", e -> showAddModelDialog());
        leftBtnPanel.add(addModelBtn);

        bar.add(leftBtnPanel, BorderLayout.WEST);

        // 中间：输入框（带 placeholder），包在 JScrollPane 中支持多行滚动
        JPanel centerPanel = new JPanel(new BorderLayout());
        centerPanel.setOpaque(false);
        inputArea = new PromptTextArea(1, 0, "输入消息，Enter 发送");
        inputArea.setFont(NetUtil.FONT_TEXT);
        inputArea.setForeground(NetUtil.TEXT_COLOR);
        inputArea.setBackground(C_FIELD_BG);
        inputArea.setCaretColor(NetUtil.TEXT_COLOR);
        inputArea.setLineWrap(true);
        inputArea.setWrapStyleWord(true);
        NetUtil.fixPaste(inputArea);
        inputArea.setBorder(BorderFactory.createCompoundBorder(
            BorderFactory.createLineBorder(C_BORDER, 1, true),
            BorderFactory.createEmptyBorder(5, 10, 5, 10)));

        // 文本域内容变化时切换发送按钮启用状态
        inputArea.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(DocumentEvent e) { updateSendBtnState(); }
            @Override
            public void removeUpdate(DocumentEvent e) { updateSendBtnState(); }
            @Override
            public void changedUpdate(DocumentEvent e) { updateSendBtnState(); }
        });

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

        // Ctrl+Z 撤销 / Ctrl+Y 恢复
        inputUndoManager = new UndoManager();
        inputUndoManager.setLimit(200);  // 最多保留 200 步操作历史
        inputArea.getDocument().addUndoableEditListener(inputUndoManager);
        inputArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Z, InputEvent.CTRL_DOWN_MASK), "undo");
        inputArea.getActionMap().put("undo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (inputUndoManager.canUndo()) {
                    inputUndoManager.undo();
                }
            }
        });
        inputArea.getInputMap().put(KeyStroke.getKeyStroke(KeyEvent.VK_Y, InputEvent.CTRL_DOWN_MASK), "redo");
        inputArea.getActionMap().put("redo", new AbstractAction() {
            @Override
            public void actionPerformed(ActionEvent e) {
                if (inputUndoManager.canRedo()) {
                    inputUndoManager.redo();
                }
            }
        });

        // 用 JScrollPane 包住 inputArea，支持多行时滚动
        JScrollPane inputScrollPane = new JScrollPane(inputArea);
        inputScrollPane.setBorder(BorderFactory.createEmptyBorder());
        inputScrollPane.setOpaque(false);
        inputScrollPane.getViewport().setOpaque(false);
        inputScrollPane.setHorizontalScrollBarPolicy(ScrollPaneConstants.HORIZONTAL_SCROLLBAR_NEVER);
        inputScrollPane.setVerticalScrollBarPolicy(ScrollPaneConstants.VERTICAL_SCROLLBAR_AS_NEEDED);
        centerPanel.add(inputScrollPane, BorderLayout.CENTER);
        bar.add(centerPanel, BorderLayout.CENTER);

        // 右侧按钮
        JPanel btnPanel = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        btnPanel.setOpaque(false);

        // 新建会话按钮
        JButton newSessionBtn = createIconButton("/icons/create_say.svg", 20, "新建会话", e -> newSession());
        btnPanel.add(newSessionBtn);

        sendBtn = createIconSendButton();
        btnPanel.add(sendBtn);

        bar.add(btnPanel, BorderLayout.EAST);

        // 输入栏高度：最小 40，最大 120（约 6 行），宽度由父容器决定
        bar.setMinimumSize(new Dimension(0, 40));
        bar.setMaximumSize(new Dimension(Integer.MAX_VALUE, 120));
        return bar;
    }

    // ---- 按钮工厂 ----

    // ---- SVG 图标按钮（发送/停止，带动画） ----

    /** 发送模式 SVG 图标 */
    private Icon sendIcon;
    /** 停止模式 SVG 图标 */
    private Icon stopIcon;
    /** 当前是否处于发送模式（true=发送, false=停止） */
    private boolean sendMode = true;

    /** 按钮缩放动画系数（1.0 = 原始大小） */
    private float btnScale = 1.0f;
    /** 缩放动画时间线 */
    private Timeline btnScaleTimeline;

    /** 创建发送/停止图标按钮 */
    private JButton createIconSendButton() {
        sendIcon = loadSvgIcon("/icons/send.svg", 20);
        stopIcon = loadSvgIcon("/icons/stop.svg", 20);

        JButton btn = new JButton() {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                int w = getWidth(), h = getHeight();
                // 缩放变换
                float scale = btnScale;
                int sw = (int)(w * scale);
                int sh = (int)(h * scale);
                int sx = (w - sw) / 2;
                int sy = (h - sh) / 2;
                // 图标居中缩放
                Icon icon = sendMode ? sendIcon : stopIcon;
                if (icon != null) {
                    int iconW = (int)(icon.getIconWidth() * scale);
                    int iconH = (int)(icon.getIconHeight() * scale);
                    int ix = (w - iconW) / 2;
                    int iy = (h - iconH) / 2;
                    // 绘制缩放后的图标
                    g2.translate(ix, iy);
                    g2.scale(scale, scale);
                    icon.paintIcon(this, g2, 0, 0);
                }
                g2.dispose();
            }
        };
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setEnabled(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        btn.setToolTipText("发送消息 (Enter)");
        int size = 36;
        btn.setPreferredSize(new Dimension(size, size));
        btn.setMinimumSize(new Dimension(size, size));
        btn.setMaximumSize(new Dimension(size, size));

        // 按下/松开动画
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                animateButtonScale(0.85f, 100);
            }
            @Override
            public void mouseReleased(MouseEvent e) {
                animateButtonScale(1.0f, 150);
            }
            @Override
            public void mouseExited(MouseEvent e) {
                // 鼠标移出时还原（防止卡在缩小状态）
                if (btnScale < 1.0f) {
                    animateButtonScale(1.0f, 100);
                }
            }
        });

        btn.addActionListener(e -> {
            if (sendMode) {
                sendMessage();
            } else {
                stopStreaming();
            }
        });

        return btn;
    }

    /** 按钮缩放动画 */
    private void animateButtonScale(float target, int durationMs) {
        if (btnScaleTimeline != null) {
            btnScaleTimeline.abort();
        }
        float from = btnScale;
        btnScaleTimeline = Timeline.builder(this)
            .setDuration(durationMs)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    // no-op
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    btnScale = from + (target - from) * timelinePosition;
                    if (sendBtn != null) sendBtn.repaint();
                }
            })
            .build();
        btnScaleTimeline.play();
    }

    /** 根据输入内容切换发送按钮启用/禁用 */
    private void updateSendBtnState() {
        if (sendBtn != null) {
            boolean hasText = !inputArea.getText().trim().isEmpty();
            sendBtn.setEnabled(hasText && sendMode);
        }
    }

    /** 切换到发送模式 */
    private void switchToSendMode() {
        sendMode = true;
        if (sendBtn != null) {
            sendBtn.setToolTipText("发送消息 (Enter)");
            updateSendBtnState();
        }
    }

    /** 切换到停止模式 */
    private void switchToStopMode() {
        sendMode = false;
        if (sendBtn != null) {
            sendBtn.setToolTipText("停止生成");
            sendBtn.setEnabled(true);
            sendBtn.repaint();
        }
    }

    /** 停止流式输出 */
    private void stopStreaming() {
        stopRequested = true;
        // 先禁用按钮防止重复点击，等后台线程确认停止后再恢复
        if (sendBtn != null) {
            sendBtn.setEnabled(false);
            sendBtn.repaint();
        }
    }

    /** 从 resources 加载 SVG 图标 */
    private static Icon loadSvgIcon(String path, int size) {
        URL url = AiChatPanel.class.getResource(path);
        if (url != null) {
            return new FlatSVGIcon(url).derive(size, size);
        }
        return null;
    }

    /** 创建通用图标按钮（无背景圆，纯图标），用于编辑/添加等操作 */
    private JButton createIconButton(String iconPath, int iconSize, String tooltip, ActionListener action) {
        Icon icon = loadSvgIcon(iconPath, iconSize);
        JButton btn = new JButton(icon) {
            @Override
            protected void paintComponent(Graphics g) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                super.paintComponent(g2);
                g2.dispose();
            }
        };
        btn.setToolTipText(tooltip);
        btn.setContentAreaFilled(false);
        btn.setBorderPainted(false);
        btn.setFocusPainted(false);
        btn.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        int size = 36;
        btn.setPreferredSize(new Dimension(size, size));
        btn.setMinimumSize(new Dimension(size, size));
        btn.setMaximumSize(new Dimension(size, size));
        btn.addActionListener(action);
        return btn;
    }

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
        if (inputUndoManager != null) {
            inputUndoManager.discardAllEdits();  // 清空撤销历史，防止 Ctrl+Z 恢复已发送消息
        }

        // 先创建空的 AI 气泡，准备流式填充（thinking=true 表示等待首 token）
        String aiTime = LocalTime.now().format(TF);
        ChatBubble aiBubble = new ChatBubble("AI", "", aiTime, false, true);
        aiBubble.setAlpha(0.0f);
        aiBubble.setSlideOffset(1.0f);  // 初始在左侧屏幕外

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
        JPanel alignPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(aiBubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        // 限制 wrapper 高度防止 BoxLayout 间隙过大
        aiBubble.setSize(aiBubble.getPreferredSize());
        int prefH = aiBubble.getPreferredSize().height + 4;
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, prefH));

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();

        // 记录流式气泡引用，供 handler 增量更新
        this.streamingBubble = aiBubble;
        this.streamingWrapper = wrapper;

        // 设置 revalidate 回调：用户手动滚上去后不再自动滚动；同时更新 wrapper 高度防止间隙
        aiBubble.onRevalidate = () -> {
            if (!isShowing()) return;  // 面板不可见时跳过，避免切 Tab 闪烁
            // 流式输出时 bubble 高度不断增长，同步更新 wrapper 的 maxHeight
            if (streamingWrapper != null) {
                int newH = aiBubble.getPreferredSize().height + 4;
                streamingWrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, newH));
                streamingWrapper.revalidate();
            }
            if (!userScrolledUp) {
                // 双重 invokeLater：等布局完全稳定后再滚动
                SwingUtilities.invokeLater(() -> {
                    SwingUtilities.invokeLater(() -> scrollToBottom());
                });
            } else {
                updateScrollToBottomButton();
            }
        };

        // 滑入 + 淡入动画（AI从左→右）
        Timeline.builder(aiBubble)
            .setDuration(300)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    aiBubble.repaint();
                    if (newState == TimelineState.DONE) {
                        SwingUtilities.invokeLater(() -> {
                            SwingUtilities.invokeLater(() -> {
                                chatScrollPane.getVerticalScrollBar().setValue(
                                    chatScrollPane.getVerticalScrollBar().getMaximum());
                            });
                        });
                    }
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    // ease-out-quad: 1 - (1-t)^2（比 cubic 更轻量）
                    float eased = timelinePosition * (2f - timelinePosition);
                    aiBubble.setAlpha(eased);
                    aiBubble.setSlideOffset(1f - eased);
                    aiBubble.repaint();
                }
            })
            .build()
            .play();

        scrollToBottom();

        // 标记流式开始，切换到停止按钮，禁用编辑按钮防止模态弹窗卡死 UI
        streamingActive = true;
        stopRequested = false;
        userScrolledUp = false;
        switchToStopMode();
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
                    .strictTools(true)
                    .build();

                // 将用户消息加入对话记忆
                chatMemory.add(UserMessage.from(text));

                // 构建 ChatRequest，附带工具定义（静态 + 动态）
                ChatRequest chatRequest = ChatRequest.builder()
                        .messages(chatMemory.messages())
                        .toolSpecifications(AiUtils.getAllToolSpecifications())
                        .build();

                StringBuilder fullResponse = new StringBuilder();
                model.chat(chatRequest, new StreamingChatResponseHandler() {
                    @Override
                    public void onPartialResponse(String partialResponse) {
                        if (stopRequested) {
                            throw new RuntimeException("User stopped");
                        }
                        fullResponse.append(partialResponse);
                        SwingUtilities.invokeLater(() -> {
                            if (streamingBubble != null) {
                                streamingBubble.appendContent(partialResponse);
                            }
                        });
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse completeResponse) {
                        // AI 返回了 Tool Call 请求，执行工具并继续对话
                        if (completeResponse.aiMessage() != null
                                && completeResponse.aiMessage().hasToolExecutionRequests()) {
                            AiMessage aiMsg = completeResponse.aiMessage();
                            chatMemory.add(aiMsg);

                            // 执行工具调用（优先动态，再走 @Tool 静态反射）
                            for (ToolExecutionRequest toolRequest : aiMsg.toolExecutionRequests()) {
                                String result = AiUtils.executeTool(toolRequest);
                                chatMemory.add(ToolExecutionResultMessage.from(toolRequest, result));
                                SwingUtilities.invokeLater(() -> {
                                    if (streamingBubble != null) {
                                        streamingBubble.appendContent(
                                            "\n\n🔧 执行工具: " + toolRequest.name()
                                            + " → " + (result.length() > 200
                                                ? result.substring(0, 200) + "..." : result));
                                    }
                                });
                            }

                            // 工具执行完后，让 AI 基于结果生成最终回复
                            ChatRequest followUp = ChatRequest.builder()
                                    .messages(chatMemory.messages())
                                    .toolSpecifications(AiUtils.getAllToolSpecifications())
                                    .build();
                            model.chat(followUp, new StreamingChatResponseHandler() {
                                StringBuilder followUpText = new StringBuilder();
                                @Override
                                public void onPartialResponse(String partialResponse) {
                                    followUpText.append(partialResponse);
                                    SwingUtilities.invokeLater(() -> {
                                        if (streamingBubble != null) {
                                            streamingBubble.appendContent(partialResponse);
                                        }
                                    });
                                }
                                @Override
                                public void onCompleteResponse(ChatResponse followUpResponse) {
                                    String text = followUpText.toString();
                                    if (!text.isEmpty()) {
                                        chatMemory.add(AiMessage.from(text));
                                    }
                                    finishStreaming();
                                }
                                @Override
                                public void onError(Throwable error) {
                                    handleStreamError(error, false);
                                }
                            });
                        } else {
                            // 普通文本回复
                            String aiContent = fullResponse.toString();
                            if (!aiContent.isEmpty()) {
                                chatMemory.add(AiMessage.from(aiContent));
                            }
                            finishStreaming();
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        handleStreamError(error, true);
                    }
                });
            } catch (Exception ex) {
                boolean isUserStop = "User stopped".equals(ex.getMessage());
                if (!isUserStop) {
                    ex.printStackTrace();
                }
                SwingUtilities.invokeLater(() -> {
                    if (!isUserStop && streamingWrapper != null) {
                        chatMessagePanel.remove(streamingWrapper);
                        streamingWrapper = null;
                        if (streamingBubble != null) streamingBubble.onRevalidate = null;
                        streamingBubble = null;
                        chatMessagePanel.revalidate();
                        chatMessagePanel.repaint();
                    } else {
                        if (streamingBubble != null) streamingBubble.onRevalidate = null;
                        streamingBubble = null;
                        streamingWrapper = null;
                    }
                    streamingActive = false;
                    updateScrollToBottomButton();   // 结束后根据滚动位置判断显隐
                    setModelButtonsEnabled(true);
                    switchToSendMode();
                    if (!isUserStop) {
                        String errTime = LocalTime.now().format(TF);
                        String errDetail = ex.getMessage();
                        Throwable cause = ex.getCause();
                        if (cause != null && cause.getMessage() != null) {
                            errDetail = cause.getMessage();
                        }
                        addErrorMessage("API call failed: " + errDetail, errTime);
                    }
                });
            }
        });
    }

    /** 流式对话结束后的清理工作 */
    private void finishStreaming() {
        SwingUtilities.invokeLater(() -> {
            if (streamingBubble != null) streamingBubble.onRevalidate = null;
            streamingBubble = null;
            streamingWrapper = null;
            streamingActive = false;
            updateScrollToBottomButton();   // 结束后根据滚动位置判断显隐
            setModelButtonsEnabled(true);
            switchToSendMode();
        });
    }

    /** 流式对话出错后的清理工作 */
    private void handleStreamError(Throwable error, boolean isOuter) {
        boolean isUserStop = "User stopped".equals(error.getMessage());
        if (!isUserStop) {
            error.printStackTrace();
        }
        SwingUtilities.invokeLater(() -> {
            if (!isUserStop && streamingWrapper != null) {
                chatMessagePanel.remove(streamingWrapper);
                streamingWrapper = null;
                if (streamingBubble != null) streamingBubble.onRevalidate = null;
                streamingBubble = null;
                chatMessagePanel.revalidate();
                chatMessagePanel.repaint();
            } else {
                if (streamingBubble != null) streamingBubble.onRevalidate = null;
                streamingBubble = null;
                streamingWrapper = null;
            }
            streamingActive = false;
            updateScrollToBottomButton();   // 结束后根据滚动位置判断显隐
            setModelButtonsEnabled(true);
            switchToSendMode();
            if (!isUserStop) {
                String errTime = LocalTime.now().format(TF);
                String errDetail = error.getMessage() != null ? error.getMessage() : "";
                Throwable cause = error.getCause();
                if (cause != null && cause.getMessage() != null) {
                    errDetail = cause.getMessage();
                }
                addErrorMessage("API call failed: " + errDetail, errTime);
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
        bubble.setSlideOffset(1.0f);  // 初始在屏幕外

        // 外层包装：控制左/右对齐
        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));

        // 限制 wrapper 最大高度为其 preferredSize，防止 BoxLayout 在气泡间产生巨大间隙
        // 先让 bubble 计算一次 preferredSize，再约束 wrapper 高度
        bubble.setSize(bubble.getPreferredSize());
        int prefH = bubble.getPreferredSize().height + 4; // +4 = wrapper 上下 border 各 2px
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, prefH));

        JPanel alignPanel = new JPanel(new FlowLayout(
            isUser ? FlowLayout.RIGHT : FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(bubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();

        // 滑入 + 淡入动画（用户右→左，AI左→右）
        Timeline timeline = Timeline.builder(bubble)
            .setDuration(350)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    bubble.repaint();
                    // 动画结束时确保滚到底
                    if (newState == TimelineState.DONE) {
                        SwingUtilities.invokeLater(() -> {
                            SwingUtilities.invokeLater(() -> {
                                chatScrollPane.getVerticalScrollBar().setValue(
                                    chatScrollPane.getVerticalScrollBar().getMaximum());
                            });
                        });
                    }
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    // ease-out-quad: t*(2-t)
                    float eased = timelinePosition * (2f - timelinePosition);
                    bubble.setAlpha(eased);
                    bubble.setSlideOffset(1f - eased);
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
        bubble.setAlpha(0.0f);
        bubble.setSlideOffset(1.0f);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));

        // 限制 wrapper 高度防止 BoxLayout 间隙过大
        bubble.setSize(bubble.getPreferredSize());
        int prefH = bubble.getPreferredSize().height + 4;
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, prefH));

        JPanel alignPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(bubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();

        Timeline.builder(bubble)
            .setDuration(300)
            .addCallback(new TimelineCallback() {
                @Override
                public void onTimelineStateChanged(TimelineState oldState, TimelineState newState,
                                                   float durationFraction, float timelinePosition) {
                    bubble.repaint();
                }
                @Override
                public void onTimelinePulse(float durationFraction, float timelinePosition) {
                    float eased = timelinePosition * (2f - timelinePosition);
                    bubble.setAlpha(eased);
                    bubble.setSlideOffset(1f - eased);
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
    /** 滚动动画用的单线程调度器（高精度，不受 EDT 排队影响） */
    private static final ScheduledExecutorService SCROLL_SCHEDULER =
            Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "scroll-animator");
                t.setDaemon(true);
                t.setPriority(Thread.MAX_PRIORITY);
                return t;
            });

    /** 当前正在运行的滚动动画 Future，新动画会取消旧的 */
    private ScheduledFuture<?> activeScrollFuture = null;

    /**
     * 平滑滚动到底部，120fps + easeOutQuad 缓动曲线。
     * 定时由独立后台线程驱动，仅在 setValue 时切到 EDT。
     * 动画结束后使用双重 invokeLater 确保布局完全稳定后再滚动到底。
     */
    private void scrollToBottom() {
        if (!isShowing()) return;  // 面板不可见时跳过，避免切 Tab 闪烁
        JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
        int from = vBar.getValue();
        int to = vBar.getMaximum();
        if (to <= from + vBar.getVisibleAmount()) return;

        // 取消上一个未完成的滚动动画
        if (activeScrollFuture != null && !activeScrollFuture.isDone()) {
            activeScrollFuture.cancel(false);
        }

        long durationMs = 180;
        int fps = 120;
        long intervalUs = 1_000_000 / fps;
        long startTime = System.nanoTime();

        activeScrollFuture = SCROLL_SCHEDULER.scheduleAtFixedRate(() -> {
            float elapsed = (System.nanoTime() - startTime) / 1_000_000f / durationMs;
            if (elapsed >= 1.0f) {
                elapsed = 1.0f;
                ScheduledFuture<?> self = activeScrollFuture;
                if (self != null && !self.isDone()) {
                    self.cancel(false);
                }
                // 双重 invokeLater：等待布局完全稳定后再滚到底
                SwingUtilities.invokeLater(() -> {
                    SwingUtilities.invokeLater(() -> {
                        vBar.setValue(vBar.getMaximum());
                    });
                });
            }
            // easeOutQuad
            float eased = elapsed * (2.0f - elapsed);
            final int val = from + Math.round((to - from) * eased);
            SwingUtilities.invokeLater(() -> vBar.setValue(val));
        }, 0, intervalUs, TimeUnit.MICROSECONDS);
    }

    /** 是否已滚动到底部附近（容差 20px） */
    private boolean isScrolledToBottom() {
        JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
        int val = vBar.getValue();
        int max = vBar.getMaximum();
        int extent = vBar.getVisibleAmount();
        return val + extent >= max - 20;
    }

    /** 距离底部是否超过视口的 1/3（用于决定是否显示"滚到底部"按钮） */
    private boolean isFarFromBottom() {
        JScrollBar vBar = chatScrollPane.getVerticalScrollBar();
        int val = vBar.getValue();
        int max = vBar.getMaximum();
        int extent = vBar.getVisibleAmount();
        if (extent <= 0) return false;
        int bottom = max - extent;
        if (bottom <= 0) return false;
        // 当前值距离底部 > 视口高度的 1/3
        return (bottom - val) > extent / 3;
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
        // 流式进行中且不在底部 → 显示
        // 流式结束后用户滚远了（距底部超过视口 1/3） → 显示
        boolean shouldShow = (streamingActive && !isScrolledToBottom())
                          || (!streamingActive && isFarFromBottom());
        if (shouldShow) {
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
        chatMemory.clear();  // 重置对话记忆
        showPlaceholder();
        inputArea.setText("");
        if (inputUndoManager != null) {
            inputUndoManager.discardAllEdits();
        }
    }

    /** 新建会话：停止流式输出 + 清空面板 + 清除记忆 */
    private void newSession() {
        // 如果正在流式输出，取消它
        if (streamingActive) {
            stopRequested = true;
        }
        clearChat();
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

        // 选中模型时清空历史对话记忆，避免新模型延续旧模型的上下文
        combo.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                Object item = e.getItem();
                if (item instanceof ModelConfig mc && !"请先添加模型".equals(mc.getAlias())) {
                    chatMemory.clear();
                    System.out.println("[AI Chat] 切换到模型: " + mc.comboLabel() + "，已清空对话记忆");
                }
            }
        });

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
        ModelConfigDialog dlg = new ModelConfigDialog(owner, mc);
        dlg.setVisible(true);
        if (dlg.confirmed) {
            refreshModelSelector();
        }
    }

    private void showAddModelDialog() {
        if (streamingActive) return;
        Window owner = SwingUtilities.getWindowAncestor(this);
        ModelConfigDialog dlg = new ModelConfigDialog(owner, null);
        dlg.setVisible(true);
        if (dlg.confirmed) {
            refreshModelSelector();
        }
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
        private float slideOffset = 0f;      // 滑入偏移 (0=滑入完成, 1=完全在屏幕外)
        private Color bubbleBg;
        private Color bubbleBgTop;           // 渐变顶部色
        private Color borderColor;
        private JTextArea contentArea;      // 内容文本区域引用，支持流式增量更新
        private boolean thinking;            // 是否正在等待 AI 首 token
        private float thinkingAlpha = 1.0f;  // "思考中..." 文字呼吸动画 alpha
        private boolean thinkingForward = true;
        private Timeline thinkingTimeline;   // 思考中呼吸动画时间线
        Runnable onRevalidate;               // 流式刷新时的回调

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
            // 渐变配色：更精致的双色渐变
            if (isUser) {
                this.bubbleBg = new Color(0x1B6B3A);       // 深绿底
                this.bubbleBgTop = new Color(0x218F4E);     // 浅绿顶
                this.borderColor = new Color(0x3BA064);     // 翠绿边框
            } else if (isError) {
                this.bubbleBg = new Color(0x5C2020);        // 深红底
                this.bubbleBgTop = new Color(0x7A2828);     // 浅红顶
                this.borderColor = new Color(0xC0392B);     // 红边框
            } else {
                this.bubbleBg = new Color(0x1C1E2E);        // 深蓝紫底
                this.bubbleBgTop = new Color(0x252740);     // 浅蓝紫顶
                this.borderColor = new Color(0x3A3D55);     // 灰蓝边框
            }
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

        // ---- slideOffset for slide-in animation ----
        public float getSlideOffset() { return slideOffset; }
        public void setSlideOffset(float offset) {
            this.slideOffset = Math.max(0.0f, Math.min(1.0f, offset));
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
                if (p != null && p.getWidth() > 0) {
                    curW = p.getWidth() - 64;
                } else {
                    // 兜底：回溯找 chatMessagePanel 宽度
                    Container pp = getParent();
                    int fallbackW = 500;
                    while (pp != null) {
                        if (pp instanceof JViewport vp) { fallbackW = vp.getWidth(); break; }
                        if (pp.getParent() instanceof JViewport vp) { fallbackW = vp.getWidth(); break; }
                        pp = pp.getParent();
                    }
                    curW = (int) (fallbackW * 0.65) - 40;
                    if (curW < 160) curW = 160;
                }
            }
            if (curW > 0) {
                contentArea.setSize(curW, Short.MAX_VALUE);
            }
            revalidateAndRepaint();
            // 通知外部（用于判断是否需要滚动）
            if (onRevalidate != null) {
                onRevalidate.run();
            }
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
                    if (contentArea != null && contentArea.isShowing()) contentArea.repaint();
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

        /** 暂停"思考中..."呼吸动画（切换 Tab 隐藏面板时调用） */
        void pauseThinkingAnimation() {
            if (thinkingTimeline != null) {
                thinkingTimeline.abort();
                thinkingTimeline = null;
            }
            // 保留 thinking = true，恢复时重新创建
        }

        /** 恢复"思考中..."呼吸动画（切换 Tab 重新显示面板时调用） */
        void resumeThinkingAnimation() {
            if (!thinking) return;
            if (thinkingTimeline != null) return; // 已在运行
            startThinkingAnimation();
        }

        /** 重新计算布局（内容尺寸变化后调用）。仅在可见时触发重绘，避免隐藏 Tab 时闪烁。 */
        void revalidateAndRepaint() {
            if (!isShowing()) return;
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
            timeLabel.setForeground(Color.WHITE);

            header.add(timeLabel, BorderLayout.EAST);
            header.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

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
            // 允许文本选中（用于拖选复制），但不可编辑
            this.contentArea.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

            // 内容内边距面板
            JPanel contentPane = new JPanel(new BorderLayout());
            contentPane.setOpaque(false);
            contentPane.add(this.contentArea, BorderLayout.CENTER);

            JPanel innerPanel = new JPanel(new BorderLayout());
            innerPanel.setOpaque(false);
            innerPanel.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            innerPanel.add(header, BorderLayout.NORTH);
            innerPanel.add(contentPane, BorderLayout.CENTER);

            add(innerPanel, BorderLayout.CENTER);
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        }

        @Override
        public Dimension getPreferredSize() {
            // 回溯找到 chatMessagePanel 的实际宽度
            int chatPanelW = 500; // 默认兜底
            Container p = getParent();
            while (p != null) {
                if (p instanceof JViewport vp) {
                    chatPanelW = vp.getWidth();
                    break;
                }
                if (p.getParent() instanceof JViewport vp) {
                    chatPanelW = vp.getWidth();
                    break;
                }
                p = p.getParent();
            }
            // 气泡宽度取 chatMessagePanel 宽度的 65%，适配窗口缩放
            int maxW = (int) (chatPanelW * 0.65);
            if (maxW < 200) maxW = 200;

            // 让 JTextArea 按其宽度重新计算换行后的真实高度
            if (contentArea != null) {
                int textWidth = maxW - 28; // 减去内外面板边距（含tail空间）
                if (textWidth > 0) {
                    contentArea.setSize(textWidth, Short.MAX_VALUE);
                }
            }
            return new Dimension(maxW + 8, super.getPreferredSize().height);
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            int w = getWidth() - 1;
            int h = getHeight() - 1;
            int slideX = (int) (w * slideOffset * (isUser ? 1 : -1));

            // 整体透明度
            float compositeAlpha = alpha * (1f - slideOffset * 0.3f);
            g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, compositeAlpha));

            // ---- 单层柔和阴影 ----
            g2.setColor(new Color(0, 0, 0, (int)(30 * alpha)));
            g2.fillRoundRect(2 + slideX, 3, w - 2, h - 2, 16, 16);

            // ---- 渐变背景（缓存 GradientPaint 避免每帧创建） ----
            if (cachedGradient == null || cachedGradientH != h) {
                cachedGradient = new GradientPaint(0, 0, bubbleBgTop, 0, h, bubbleBg);
                cachedGradientH = h;
            }
            g2.setPaint(cachedGradient);
            g2.fillRoundRect(slideX, 0, w, h, 16, 16);

            // ---- 小三角尾巴 ----
            int tailSize = 8;
            int tailY = Math.min(28, h / 2);
            if (isUser) {
                g2.fillPolygon(
                    new int[]{w + slideX, w + tailSize + slideX, w + slideX},
                    new int[]{tailY, tailY + tailSize / 2, tailY + tailSize}, 3);
            } else {
                g2.fillPolygon(
                    new int[]{slideX, slideX - tailSize, slideX},
                    new int[]{tailY, tailY + tailSize / 2, tailY + tailSize}, 3);
            }

            // ---- 边框（缓存颜色对象） ----
            if (cachedBorderColor == null || cachedBorderAlpha != (int)(borderColor.getAlpha() * alpha)) {
                cachedBorderAlpha = (int)(borderColor.getAlpha() * alpha);
                cachedBorderColor = new Color(
                    borderColor.getRed(), borderColor.getGreen(), borderColor.getBlue(), cachedBorderAlpha);
            }
            g2.setStroke(STROKE_1PX);
            g2.setColor(cachedBorderColor);
            g2.drawRoundRect(slideX, 0, w, h, 16, 16);

            // 小三角边框
            if (isUser) {
                g2.drawLine(w + slideX, tailY, w + tailSize + slideX, tailY + tailSize / 2);
                g2.drawLine(w + tailSize + slideX, tailY + tailSize / 2, w + slideX, tailY + tailSize);
            } else {
                g2.drawLine(slideX, tailY, slideX - tailSize, tailY + tailSize / 2);
                g2.drawLine(slideX - tailSize, tailY + tailSize / 2, slideX, tailY + tailSize);
            }

            g2.dispose();
            super.paintComponent(g);
        }

        // 缓存对象减少 GC 压力
        private GradientPaint cachedGradient;
        private int cachedGradientH = -1;
        private Color cachedBorderColor;
        private int cachedBorderAlpha = -1;
        private static final BasicStroke STROKE_1PX = new BasicStroke(1.0f);
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
