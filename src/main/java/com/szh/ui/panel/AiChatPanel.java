package com.szh.ui.panel;

import com.formdev.flatlaf.extras.FlatSVGIcon;
import com.szh.Start;
import com.szh.agnes.AgnesImageService;
import com.szh.agnes.AgnesVideoService;
import com.szh.agnes.entity.AgnesImageRequest;
import com.szh.agnes.entity.AgnesVideoRequest;
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
import org.bytedeco.javacv.FFmpegFrameGrabber;
import org.bytedeco.javacv.Frame;
import org.bytedeco.javacv.Java2DFrameConverter;
import org.bytedeco.javacv.OpenCVFrameConverter;
import org.bytedeco.opencv.opencv_core.Mat;
import org.bytedeco.opencv.opencv_core.Size;
import org.pushingpixels.radiance.animation.api.Timeline;
import org.pushingpixels.radiance.animation.api.Timeline.TimelineState;
import org.pushingpixels.radiance.animation.api.callback.TimelineCallback;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import javax.swing.plaf.basic.BasicComboBoxEditor;
import javax.swing.undo.UndoManager;
import java.awt.*;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

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

    /** Agnes Image API Key（用于图片/视频生成） */
    private String agnesApiKey;
    /** 图片生成服务实例（延迟初始化） */
    private AgnesImageService imageService;
    /** 视频生成服务实例（延迟初始化，复用同一 API Key） */
    private AgnesVideoService videoService;

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
        // Agnes AI (图片/视频生成)
        "agnes-image-2.0-flash", "agnes-video-v2.0",
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

        String modelName = selectedModel.getModelName();

        // === Agnes 图片/视频生成路由 ===
        if ("agnes-image-2.0-flash".equals(modelName)) {
            handleAgnesImageSend(text, selectedModel);
            return;
        }
        if ("agnes-video-v2.0".equals(modelName)) {
            handleAgnesVideoSend(text, selectedModel);
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
            if (streamingBubble != null) {
                streamingBubble.onRevalidate = null;
                // 流式结束后加载媒体内容（图片/视频）
                streamingBubble.loadPendingMedia();
            }
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
        // 非流式消息：扫描内容中的媒体 URL
        bubble.trackPendingMedia(content);

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

        // 加载该消息中的图片/视频
        bubble.loadPendingMedia();

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

        // 选中模型时清空历史对话记忆，避免新模型延续旧模型的上下文（Agnes 模型不清理，因为不是对话模型）
        combo.addItemListener(e -> {
            if (e.getStateChange() == java.awt.event.ItemEvent.SELECTED) {
                Object item = e.getItem();
                if (item instanceof ModelConfig mc && !"请先添加模型".equals(mc.getAlias())
                        && !mc.getModelName().startsWith("agnes-")) {
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

        // Agnes 扩展配置面板（根据选中模型动态显示/隐藏）
        private JPanel extraPanel;
        private JPanel imageConfigPanel;
        private JPanel videoConfigPanel;
        private JComboBox<String> imgSizeCombo, imgModeCombo;
        private JTextArea imgUrlArea;
        private JComboBox<String> vidResCombo, vidDurCombo, vidFpsCombo, vidModeCombo;
        private JTextField vidSeedField;
        private JTextArea vidUrlArea;
        private JLabel imgUrlLabel;
        private JScrollPane imgUrlScroll;
        private JLabel vidUrlLabel;
        private JScrollPane vidUrlScroll;
        private JLabel vidUrlHint;

        ModelConfigDialog(Window owner, ModelConfig editing) {
            super(owner, editing == null ? "添加模型" : "编辑模型", ModalityType.APPLICATION_MODAL);
            this.editingRef = editing;
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

            // Agnes 扩展配置面板（放在模型全称下方，动态显示/隐藏）
            extraPanel = new JPanel(new BorderLayout());
            extraPanel.setOpaque(false);
            gbc.gridy = 4;
            gbc.gridx = 0;
            gbc.gridwidth = 2;
            gbc.weightx = 1;
            gbc.fill = GridBagConstraints.HORIZONTAL;
            form.add(extraPanel, gbc);
            gbc.gridwidth = 1; // 恢复
            buildAgnesPanels();

            // 模型全称变更时切换面板
            nameCombo.addActionListener(e -> toggleAgnesPanel());
            // Focus 离开时也检查（用户可能手动输入 Agnes 模型名）
            Component editorComp = nameCombo.getEditor().getEditorComponent();
            if (editorComp instanceof JTextField tf) {
                tf.addFocusListener(new FocusAdapter() {
                    @Override public void focusLost(FocusEvent e) { toggleAgnesPanel(); }
                });
            }

            // 回填编辑数据
            if (editing != null) {
                aliasField.setText(editing.getAlias());
                keyField.setText(editing.getApiKey());
                urlField.setText(editing.getApiUrl());
                nameCombo.setSelectedItem(editing.getModelName());
                // 回填 Agnes 扩展配置
                Map<String, String> ext = editing.getExtraConfig();
                if (ext != null && !ext.isEmpty()) {
                    SwingUtilities.invokeLater(this::toggleAgnesPanel); // 等面板构建完
                }
            }

            toggleAgnesPanel(); // 初始状态

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

        // ==================== Agnes 扩展配置面板 ====================

        /** 构建图片生成的配置面板 */
        private JPanel buildImageConfigPanel() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setOpaque(false);
            p.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(C_PRIMARY), "图片生成参数",
                    javax.swing.border.TitledBorder.LEFT,
                    javax.swing.border.TitledBorder.TOP, NetUtil.FONT_TEXT, C_PRIMARY));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(3, 4, 3, 4);
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;

            // 尺寸
            g.gridx = 0; g.gridy = 0; g.weightx = 0;
            JLabel szLbl = new JLabel("尺寸：");
            szLbl.setFont(NetUtil.FONT_TEXT); szLbl.setForeground(NetUtil.TEXT_COLOR);
            p.add(szLbl, g);
            g.gridx = 1; g.weightx = 1;
            imgSizeCombo = new JComboBox<>(new String[]{
                    "1024x1024 (1:1)", "1024x768 (4:3)", "768x1024 (3:4)",
                    "512x512 (1:1)", "1024x576 (16:9)", "576x1024 (9:16)"
            });
            imgSizeCombo.setFont(NetUtil.FONT_TEXT);
            imgSizeCombo.setForeground(NetUtil.TEXT_COLOR);
            imgSizeCombo.setBackground(C_FIELD_BG);
            p.add(imgSizeCombo, g);

            // 模式
            g.gridx = 0; g.gridy = 1; g.weightx = 0;
            JLabel mdLbl = new JLabel("模式：");
            mdLbl.setFont(NetUtil.FONT_TEXT); mdLbl.setForeground(NetUtil.TEXT_COLOR);
            p.add(mdLbl, g);
            g.gridx = 1; g.weightx = 1;
            imgModeCombo = new JComboBox<>(new String[]{"文生图", "图生图（输入URL）", "多图合成"});
            imgModeCombo.setFont(NetUtil.FONT_TEXT);
            imgModeCombo.setForeground(NetUtil.TEXT_COLOR);
            imgModeCombo.setBackground(C_FIELD_BG);
            p.add(imgModeCombo, g);

            // 输入图片URL（图生图时显示）
            g.gridx = 0; g.gridy = 2; g.weightx = 0;
            imgUrlLabel = new JLabel("图片URL：");
            imgUrlLabel.setFont(NetUtil.FONT_TEXT); imgUrlLabel.setForeground(NetUtil.TEXT_COLOR);
            p.add(imgUrlLabel, g);
            g.gridx = 1; g.weightx = 1;
            imgUrlArea = new JTextArea(2, 30);
            imgUrlArea.setFont(NetUtil.FONT_TEXT);
            imgUrlArea.setForeground(NetUtil.TEXT_COLOR);
            imgUrlArea.setBackground(C_FIELD_BG);
            imgUrlArea.setCaretColor(NetUtil.TEXT_COLOR);
            imgUrlArea.setLineWrap(true);
            imgUrlArea.setWrapStyleWord(true);
            imgUrlArea.setToolTipText("每行一个URL（图生图/多图合成时需要）");
            imgUrlScroll = new JScrollPane(imgUrlArea);
            imgUrlScroll.setPreferredSize(new Dimension(300, 45));
            p.add(imgUrlScroll, g);

            // 模式切换
            imgModeCombo.addActionListener(e -> {
                boolean show = !"文生图".equals(imgModeCombo.getSelectedItem());
                imgUrlLabel.setVisible(show);
                imgUrlScroll.setVisible(show);
                p.revalidate(); p.repaint();
                pack();
            });
            imgUrlLabel.setVisible(false);
            imgUrlScroll.setVisible(false);

            return p;
        }

        /** 构建视频生成的配置面板 */
        private JPanel buildVideoConfigPanel() {
            JPanel p = new JPanel(new GridBagLayout());
            p.setOpaque(false);
            p.setBorder(BorderFactory.createTitledBorder(
                    BorderFactory.createLineBorder(C_PRIMARY), "视频生成参数",
                    javax.swing.border.TitledBorder.LEFT,
                    javax.swing.border.TitledBorder.TOP, NetUtil.FONT_TEXT, C_PRIMARY));
            GridBagConstraints g = new GridBagConstraints();
            g.insets = new Insets(3, 4, 3, 4);
            g.anchor = GridBagConstraints.WEST;
            g.fill = GridBagConstraints.HORIZONTAL;

            // 分辨率
            g.gridx = 0; g.gridy = 0; g.weightx = 0;
            JLabel resLbl = new JLabel("分辨率：");
            resLbl.setFont(NetUtil.FONT_TEXT); resLbl.setForeground(NetUtil.TEXT_COLOR);
            p.add(resLbl, g);
            g.gridx = 1; g.weightx = 1;
            vidResCombo = new JComboBox<>(new String[]{
                    "1152x768 (16:9 720p)", "1280x720 (16:9 720p)", "1536x640 (约2.4:1)",
                    "768x1280 (9:16 竖屏)", "720x1280 (9:16 竖屏)", "1024x1024 (1:1 方形)"
            });
            vidResCombo.setFont(NetUtil.FONT_TEXT);
            vidResCombo.setForeground(NetUtil.TEXT_COLOR);
            vidResCombo.setBackground(C_FIELD_BG);
            p.add(vidResCombo, g);

            // 时长
            g.gridx = 0; g.gridy = 1; g.weightx = 0;
            JLabel durLbl = new JLabel("时长：");
            durLbl.setFont(NetUtil.FONT_TEXT); durLbl.setForeground(NetUtil.TEXT_COLOR);
            p.add(durLbl, g);
            g.gridx = 1; g.weightx = 1;
            vidDurCombo = new JComboBox<>(new String[]{
                    "约 3 秒 (81帧@24fps)", "约 5 秒 (121帧@24fps)",
                    "约 10 秒 (241帧@24fps)", "约 17 秒 (409帧@24fps)"
            });
            vidDurCombo.setFont(NetUtil.FONT_TEXT);
            vidDurCombo.setForeground(NetUtil.TEXT_COLOR);
            vidDurCombo.setBackground(C_FIELD_BG);
            vidDurCombo.setSelectedIndex(1);
            p.add(vidDurCombo, g);

            // 帧率
            g.gridx = 0; g.gridy = 2; g.weightx = 0;
            JLabel fpsLbl = new JLabel("帧率：");
            fpsLbl.setFont(NetUtil.FONT_TEXT); fpsLbl.setForeground(NetUtil.TEXT_COLOR);
            p.add(fpsLbl, g);
            g.gridx = 1; g.weightx = 1;
            int screenHz = getScreenRefreshRate();
            java.util.List<String> fpsOpts = new java.util.ArrayList<>();
            for (int f : new int[]{10, 15, 24, 30, 60, 120, 144, 240}) {
                if (f <= screenHz) fpsOpts.add(f + " fps");
            }
            String screenHzStr = screenHz + " fps";
            if (!fpsOpts.contains(screenHzStr)) fpsOpts.add(screenHzStr);
            vidFpsCombo = new JComboBox<>(fpsOpts.toArray(new String[0]));
            int defFpsIdx = fpsOpts.indexOf("24 fps");
            if (defFpsIdx >= 0) vidFpsCombo.setSelectedIndex(defFpsIdx);
            vidFpsCombo.setFont(NetUtil.FONT_TEXT);
            vidFpsCombo.setForeground(NetUtil.TEXT_COLOR);
            vidFpsCombo.setBackground(C_FIELD_BG);
            p.add(vidFpsCombo, g);

            // 模式
            g.gridx = 0; g.gridy = 3; g.weightx = 0;
            JLabel vmdLbl = new JLabel("模式：");
            vmdLbl.setFont(NetUtil.FONT_TEXT); vmdLbl.setForeground(NetUtil.TEXT_COLOR);
            p.add(vmdLbl, g);
            g.gridx = 1; g.weightx = 1;
            vidModeCombo = new JComboBox<>(new String[]{
                    "文生视频", "图生视频（输入URL）", "多图视频", "关键帧动画"
            });
            vidModeCombo.setFont(NetUtil.FONT_TEXT);
            vidModeCombo.setForeground(NetUtil.TEXT_COLOR);
            vidModeCombo.setBackground(C_FIELD_BG);
            p.add(vidModeCombo, g);

            // 输入图片URL
            g.gridx = 0; g.gridy = 4; g.weightx = 0;
            vidUrlLabel = new JLabel("图片URL：");
            vidUrlLabel.setFont(NetUtil.FONT_TEXT); vidUrlLabel.setForeground(NetUtil.TEXT_COLOR);
            p.add(vidUrlLabel, g);
            g.gridx = 1; g.weightx = 1;
            vidUrlArea = new JTextArea(2, 30);
            vidUrlArea.setFont(NetUtil.FONT_TEXT);
            vidUrlArea.setForeground(NetUtil.TEXT_COLOR);
            vidUrlArea.setBackground(C_FIELD_BG);
            vidUrlArea.setCaretColor(NetUtil.TEXT_COLOR);
            vidUrlArea.setLineWrap(true);
            vidUrlArea.setWrapStyleWord(true);
            vidUrlArea.setToolTipText("每行一个URL（图生视频/多图视频/关键帧动画时需要）");
            vidUrlScroll = new JScrollPane(vidUrlArea);
            vidUrlScroll.setPreferredSize(new Dimension(300, 45));
            p.add(vidUrlScroll, g);

            // 图片URL输入提示
            g.gridx = 1; g.gridy = 5; g.weightx = 1;
            vidUrlHint = new JLabel("提示：每行填写一个URL，关键帧动画/多图视频至少需要2张");
            vidUrlHint.setFont(new Font(NetUtil.FONT_TEXT.getFamily(), Font.PLAIN, 11));
            vidUrlHint.setForeground(new Color(180, 180, 180));
            p.add(vidUrlHint, g);

            vidModeCombo.addActionListener(e -> {
                boolean show = !"文生视频".equals(vidModeCombo.getSelectedItem());
                vidUrlLabel.setVisible(show);
                vidUrlScroll.setVisible(show);
                vidUrlHint.setVisible(show);
                p.revalidate(); p.repaint();
                pack();
            });
            vidUrlLabel.setVisible(false);
            vidUrlScroll.setVisible(false);
            vidUrlHint.setVisible(false);

            // Seed
            g.gridx = 0; g.gridy = 6; g.weightx = 0;
            JLabel seedLbl = new JLabel("Seed：");
            seedLbl.setFont(NetUtil.FONT_TEXT); seedLbl.setForeground(NetUtil.TEXT_COLOR);
            p.add(seedLbl, g);
            g.gridx = 1; g.weightx = 1;
            vidSeedField = new JTextField(10);
            vidSeedField.setFont(NetUtil.FONT_TEXT);
            vidSeedField.setForeground(NetUtil.TEXT_COLOR);
            vidSeedField.setBackground(C_FIELD_BG);
            vidSeedField.setCaretColor(NetUtil.TEXT_COLOR);
            vidSeedField.setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(C_BORDER),
                    BorderFactory.createEmptyBorder(3, 6, 3, 6)));
            vidSeedField.setToolTipText("留空为随机，填入数字可复现结果");
            p.add(vidSeedField, g);

            return p;
        }

        /** 预构建两个面板 */
        private void buildAgnesPanels() {
            imageConfigPanel = buildImageConfigPanel();
            videoConfigPanel = buildVideoConfigPanel();
        }

        /** 根据当前模型名切换显示对应的配置面板 */
        private void toggleAgnesPanel() {
            String name = getCurrentModelName();
            extraPanel.removeAll();

            if ("agnes-image-2.0-flash".equals(name)) {
                // API 链接自动填充（服务类内置），设为只读
                urlField.setText(com.szh.agnes.AgnesImageService.BASE_URL);
                urlField.setEditable(false);
                urlField.setBackground(C_INPUT_BG);
                // 回填已有配置
                ModelConfig editing = getEditingConfig();
                if (editing != null) {
                    Map<String, String> ext = editing.getExtraConfig();
                    if (ext != null) {
                        if (ext.containsKey("image.size")) setComboByPrefix(imgSizeCombo, ext.get("image.size"));
                        if (ext.containsKey("image.mode")) imgModeCombo.setSelectedItem(ext.get("image.mode"));
                        if (ext.containsKey("image.urls")) imgUrlArea.setText(ext.get("image.urls"));
                    }
                }
                extraPanel.add(imageConfigPanel, BorderLayout.CENTER);
                extraPanel.setVisible(true);
            } else if ("agnes-video-v2.0".equals(name)) {
                // API 链接自动填充（服务类内置），设为只读
                urlField.setText(com.szh.agnes.AgnesVideoService.BASE_URL);
                urlField.setEditable(false);
                urlField.setBackground(C_INPUT_BG);
                ModelConfig editing = getEditingConfig();
                if (editing != null) {
                    Map<String, String> ext = editing.getExtraConfig();
                    if (ext != null) {
                        if (ext.containsKey("video.size")) setComboByPrefix(vidResCombo, ext.get("video.size"));
                        if (ext.containsKey("video.duration")) vidDurCombo.setSelectedItem(ext.get("video.duration"));
                        if (ext.containsKey("video.fps")) setComboByPrefix(vidFpsCombo, ext.get("video.fps"));
                        if (ext.containsKey("video.mode")) vidModeCombo.setSelectedItem(ext.get("video.mode"));
                        if (ext.containsKey("video.urls")) vidUrlArea.setText(ext.get("video.urls"));
                        if (ext.containsKey("video.seed")) vidSeedField.setText(ext.get("video.seed"));
                    }
                }
                extraPanel.add(videoConfigPanel, BorderLayout.CENTER);
                extraPanel.setVisible(true);
            } else {
                // 非 Agnes 模型：恢复 URL 可编辑
                urlField.setEditable(true);
                urlField.setBackground(C_FIELD_BG);
                extraPanel.setVisible(false);
            }
            extraPanel.revalidate();
            extraPanel.repaint();
            pack();
        }

        /** 通过前缀匹配选中 ComboBox 项 */
        private void setComboByPrefix(JComboBox<String> combo, String prefix) {
            if (prefix == null) return;
            for (int i = 0; i < combo.getItemCount(); i++) {
                if (combo.getItemAt(i).startsWith(prefix)) {
                    combo.setSelectedIndex(i);
                    return;
                }
            }
        }

        /** 获取当前编辑框中的模型名 */
        private String getCurrentModelName() {
            Object sel = nameCombo.getSelectedItem();
            if (sel != null) {
                String s = sel.toString().trim();
                if (!s.isEmpty()) return s;
            }
            // 可能用户手动输入了但还没确认
            Component ec = nameCombo.getEditor().getEditorComponent();
            if (ec instanceof JTextField tf) {
                String t = tf.getText().trim();
                if (!t.isEmpty()) return t;
            }
            return "";
        }

        /** 获取正在编辑的 ModelConfig（仅编辑模式有值） */
        private ModelConfig getEditingConfig() {
            // 查找当前 ModelConfigDialog 构造时传入的 editing
            // 通过调用者获取 - 使用实例字段
            return editingRef;
        }
        private ModelConfig editingRef;

        private void onConfirm(ModelConfig editing) {
            String alias = aliasField.getText().trim();
            String key   = keyField.getText().trim();
            String url   = urlField.getText().trim();
            Object sel   = nameCombo.getSelectedItem();
            String name  = (sel != null) ? sel.toString().trim() : "";

            // 校验：Agnes 模型不需要 API 链接
            if (url.isEmpty() && !name.startsWith("agnes-")) {
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

            // 保存 Agnes 扩展配置
            saveAgnesExtraConfig(mc);

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

        /** 保存 Agnes 扩展配置到 ModelConfig */
        private void saveAgnesExtraConfig(ModelConfig mc) {
            String name = mc.getModelName();
            if ("agnes-image-2.0-flash".equals(name) && imgSizeCombo != null) {
                mc.putExtra("image.size", ((String) imgSizeCombo.getSelectedItem()).split(" ")[0]);
                mc.putExtra("image.mode", (String) imgModeCombo.getSelectedItem());
                if (!"文生图".equals(imgModeCombo.getSelectedItem())) {
                    mc.putExtra("image.urls", imgUrlArea.getText().trim());
                } else {
                    mc.getExtraConfig().remove("image.urls");
                }
            } else if ("agnes-video-v2.0".equals(name) && vidResCombo != null) {
                mc.putExtra("video.size", ((String) vidResCombo.getSelectedItem()).split(" ")[0]);
                mc.putExtra("video.duration", (String) vidDurCombo.getSelectedItem());
                mc.putExtra("video.fps", ((String) vidFpsCombo.getSelectedItem()).split(" ")[0]);
                mc.putExtra("video.mode", (String) vidModeCombo.getSelectedItem());
                if (!"文生视频".equals(vidModeCombo.getSelectedItem())) {
                    mc.putExtra("video.urls", vidUrlArea.getText().trim());
                } else {
                    mc.getExtraConfig().remove("video.urls");
                }
                if (!vidSeedField.getText().trim().isEmpty()) {
                    mc.putExtra("video.seed", vidSeedField.getText().trim());
                } else {
                    mc.getExtraConfig().remove("video.seed");
                }
            }
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
        private TextBubbleContent contentArea; // 自定义绘制文本区域
        private JPanel mediaPanel;           // 图片/视频媒体容器
        private JPanel headerPanel;          // 头部面板（发送者+时间）
        private boolean thinking;            // 是否正在等待 AI 首 token
        private float thinkingAlpha = 1.0f;  // "思考中..." 文字呼吸动画 alpha
        private boolean thinkingForward = true;
        private Timeline thinkingTimeline;   // 思考中呼吸动画时间线
        Runnable onRevalidate;               // 流式刷新时的回调
        /** 流式输出结束后待提取的媒体 URL 列表，finishStreaming 触发加载 */
        transient List<PendingMedia> pendingMedia;

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
                contentArea.clear();
                contentArea.setTextColor(isUser ? new Color(0xE8E8E8)
                        : isError ? new Color(0xFFCDD2)
                        : new Color(0xEAEAEA));
            }
            this.content += text;
            contentArea.append(text);
            // 跟踪待提取的媒体 URL（Markdown 图片/视频语法）
            trackPendingMedia(text);
            recalcContentSize();
            revalidateAndRepaint();
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
                    if (contentArea != null && contentArea.isShowing()) {
                        contentArea.setTextColor(new Color(C_PRIMARY.getRed(), C_PRIMARY.getGreen(),
                                C_PRIMARY.getBlue(), (int)(thinkingAlpha * 255)));
                        contentArea.repaint();
                    }
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

        // ==================== 媒体（图片/视频）展示 ====================

        /** 待加载的媒体项 */
        static class PendingMedia {
            enum Type { IMAGE, VIDEO }
            final Type type;
            final String url;
            PendingMedia(Type type, String url) { this.type = type; this.url = url; }
        }

        // Markdown 图片/视频语法正则
        private static final Pattern MD_IMAGE = Pattern.compile("!\\[.*?]\\((https?://[^\\s)]+)\\)");
        private static final Pattern MD_VIDEO = Pattern.compile("!\\[.*?]\\(((?:https?://[^\\s)]+\\.(?:mp4|webm|ogg|mov|mkv|avi)))\\)",
                Pattern.CASE_INSENSITIVE);
        private static final Pattern RAW_VIDEO = Pattern.compile(
                "https?://[^\\s]+\\.(?:mp4|webm|ogg|mov|mkv|avi)", Pattern.CASE_INSENSITIVE);

        /** 追踪待提取的媒体 URL */
        void trackPendingMedia(String text) {
            if (pendingMedia == null) pendingMedia = new ArrayList<>();
            Matcher m = MD_IMAGE.matcher(text);
            while (m.find()) {
                pendingMedia.add(new PendingMedia(PendingMedia.Type.IMAGE, m.group(1)));
            }
            m = MD_VIDEO.matcher(text);
            while (m.find()) {
                pendingMedia.add(new PendingMedia(PendingMedia.Type.VIDEO, m.group(1)));
            }
        }

        /** 流式结束后加载媒体内容（在 EDT 调用） */
        void loadPendingMedia() {
            if (pendingMedia == null || pendingMedia.isEmpty()) return;
            List<PendingMedia> copy = new ArrayList<>(pendingMedia);
            pendingMedia = null;
            for (PendingMedia pm : copy) {
                if (pm.type == PendingMedia.Type.IMAGE) {
                    loadImageAsync(pm.url);
                } else {
                    addVideoPanel(pm.url);
                }
            }
        }

        /** 异步加载图片 */
        private void loadImageAsync(String imageUrl) {
            Thread.ofVirtual().start(() -> {
                try {
                    LoadedImage loaded = downloadImage(imageUrl);
                    if (loaded != null && loaded.image != null) {
                        SwingUtilities.invokeLater(() -> {
                            // 显示保存路径
                            addSavedPathLabel(loaded.localPath);
                            addImageToPanel(loaded.image, imageUrl);
                        });
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> addMediaError("图片加载失败: " + imageUrl, e));
                }
            });
        }

        /** 下载图片到本地 agnes_img/ 目录，返回图片 + 本地路径 */
        private static LoadedImage downloadImage(String url) throws IOException, InterruptedException {
            java.io.File imgDir = getImgDir();

            // 推断扩展名
            String ext = ".png";
            String path = url;
            int qIdx = path.indexOf('?');
            if (qIdx > 0) path = path.substring(0, qIdx);
            int dotIdx = path.lastIndexOf('.');
            if (dotIdx > 0) {
                String e = path.substring(dotIdx).toLowerCase();
                if (e.matches("\\.[a-z]{3,4}")) ext = e;
            }

            java.io.File localFile = new java.io.File(imgDir, "agnes_img_" + System.currentTimeMillis() + ext);

            HttpClient client = HttpClient.newBuilder()
                    .connectTimeout(Duration.ofSeconds(10))
                    .followRedirects(HttpClient.Redirect.NORMAL)
                    .build();
            HttpRequest req = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
            HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
            if (resp.statusCode() != 200) throw new IOException("HTTP " + resp.statusCode());

            // 先写入本地文件，再从本地文件读取
            try (InputStream in = resp.body();
                 java.io.FileOutputStream fos = new java.io.FileOutputStream(localFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    fos.write(buf, 0, n);
                }
            }

            BufferedImage img = ImageIO.read(localFile);
            if (img == null) throw new IOException("无法解析图片: " + url);
            return new LoadedImage(img, localFile.getAbsolutePath());
        }

        /** 下载结果：图片 + 本地保存路径 */
        private static class LoadedImage {
            final BufferedImage image;
            final String localPath;
            LoadedImage(BufferedImage image, String localPath) {
                this.image = image;
                this.localPath = localPath;
            }
        }

        /** 在媒体面板中添加"已保存至 xxx"的提示标签 */
        private void addSavedPathLabel(String localPath) {
            if (mediaPanel == null) return;
            JLabel label = new JLabel("📁 文件已自动保存至: " + localPath);
            label.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            label.setForeground(new Color(0x888888));
            label.setToolTipText("单击打开文件夹");
            label.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            label.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    try {
                        Desktop.getDesktop().open(new java.io.File(localPath).getParentFile());
                    } catch (Exception ignored) {}
                }
            });
            mediaPanel.add(label);
            mediaPanel.add(Box.createVerticalStrut(4));
        }

        /** 把图片添加到媒体面板（独立绘制 + 右键菜单） */
        void addImageToPanel(BufferedImage img, String sourceUrl) {
            if (img == null || mediaPanel == null) return;

            // 计算可用宽度：ChatBubble 内部 contentPane 宽度
            int availW = getContentPaneWidth();
            if (availW < 200) availW = 400;
            int imgRatio = img.getWidth() > 0 ? img.getWidth() : 1;
            int displayW = availW - 4;
            if (displayW > imgRatio) displayW = imgRatio;
            int displayH = (int) ((long) img.getHeight() * displayW / imgRatio);
            if (displayH > 600) { displayW = (int) ((long) displayW * 600 / displayH); displayH = 600; }

            final BufferedImage source = img;
            final int fDisplayW = displayW, fDisplayH = displayH;

            JPanel imgPanel = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    int pw = getWidth(), ph = getHeight();
                    if (pw <= 0 || ph <= 0) return;
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                    // 填满面板宽度，保持比例
                    int dw = pw - 2;
                    int dh = (int) ((long) source.getHeight() * dw / source.getWidth());
                    if (dh > ph - 2) { dh = ph - 2; dw = (int) ((long) source.getWidth() * dh / source.getHeight()); }
                    int dx = (pw - dw) / 2, dy = (ph - dh) / 2;
                    g2.drawImage(source, dx, dy, dw, dh, null);
                    // 圆角边框
                    g2.setColor(new Color(0x3A3D55));
                    g2.drawRoundRect(0, 0, pw - 1, ph - 1, 12, 12);
                    g2.dispose();
                }
            };
            imgPanel.setOpaque(false);
            imgPanel.setPreferredSize(new Dimension(availW, displayH + 8));
            imgPanel.setMaximumSize(new Dimension(Integer.MAX_VALUE, displayH + 8));

            // 右键菜单
            boolean hasValidUrl = sourceUrl != null && (sourceUrl.startsWith("http://") || sourceUrl.startsWith("https://"));
            JPopupMenu popup = new JPopupMenu();

            if (hasValidUrl) {
                JMenuItem copyLinkItem = new JMenuItem("复制链接");
                copyLinkItem.addActionListener(e -> {
                    java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                            .setContents(new java.awt.datatransfer.StringSelection(sourceUrl), null);
                });
                popup.add(copyLinkItem);
            }

            JMenuItem saveItem = new JMenuItem("保存图片");
            saveItem.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                fc.setSelectedFile(new java.io.File("image.png"));
                if (fc.showSaveDialog(SwingUtilities.getWindowAncestor(imgPanel)) == JFileChooser.APPROVE_OPTION) {
                    try {
                        javax.imageio.ImageIO.write(img, "png", fc.getSelectedFile());
                    } catch (IOException ex) {
                        JOptionPane.showMessageDialog(imgPanel, "保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    }
                }
            });
            popup.add(saveItem);

            if (hasValidUrl) {
                JMenuItem openItem = new JMenuItem("浏览器打开");
                openItem.addActionListener(e -> {
                    try {
                        Desktop.getDesktop().browse(URI.create(sourceUrl));
                    } catch (Exception ex) {
                        JOptionPane.showMessageDialog(imgPanel, "无法打开浏览器: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                    }
                });
                popup.add(openItem);
            }

            imgPanel.setComponentPopupMenu(popup);
            if (hasValidUrl) {
                imgPanel.setToolTipText("右键菜单 | 单击浏览器打开");
                imgPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
                imgPanel.addMouseListener(new MouseAdapter() {
                    @Override
                    public void mouseClicked(MouseEvent e) {
                        if (SwingUtilities.isLeftMouseButton(e)) {
                            try { Desktop.getDesktop().browse(URI.create(sourceUrl)); } catch (Exception ignored) {}
                        }
                    }
                });
            }

            mediaPanel.add(imgPanel);
            mediaPanel.add(Box.createVerticalStrut(8));
            revalidateAndRepaint();
            if (onRevalidate != null) onRevalidate.run();
        }

        /** 添加视频面板（嵌入式 FFmpeg 播放器） */
        private void addVideoPanel(String videoUrl) {
            if (mediaPanel == null) return;
            int contentW = getContentPaneWidth();
            if (contentW < 240) contentW = 240;
            ChatVideoPlayer player = new ChatVideoPlayer(videoUrl, contentW);
            mediaPanel.add(player);
            mediaPanel.add(Box.createVerticalStrut(8));
            revalidateAndRepaint();
            if (onRevalidate != null) onRevalidate.run();
        }

        /** 媒体加载失败提示 */
        void addMediaError(String msg, Exception e) {
            if (mediaPanel == null) return;
            JLabel errLabel = new JLabel("⚠ " + msg);
            errLabel.setForeground(new Color(0xFFA726));
            errLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 12));
            mediaPanel.add(errLabel);
            mediaPanel.add(Box.createVerticalStrut(4));
            revalidateAndRepaint();
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
            this.headerPanel = new JPanel(new BorderLayout());
            this.headerPanel.setOpaque(false);

            if (!isUser && !isError) {
                // AI 消息：显示圆形头像图标
                JLabel avatarLabel = new JLabel(loadAiAvatar(24));
                avatarLabel.setBorder(BorderFactory.createEmptyBorder(0, 0, 0, 8));
                JPanel avatarPanel = new JPanel(new BorderLayout());
                avatarPanel.setOpaque(false);
                avatarPanel.add(avatarLabel, BorderLayout.CENTER);
                this.headerPanel.add(avatarPanel, BorderLayout.WEST);
            } else {
                // 用户/系统消息：显示发送者文字
                JLabel senderLabel = new JLabel(sender);
                senderLabel.setFont(new Font("Microsoft YaHei", Font.BOLD, 11));
                senderLabel.setForeground(isUser ? new Color(0xA5D6A7) : new Color(0xEF9A9A));
                this.headerPanel.add(senderLabel, BorderLayout.WEST);
            }

            JLabel timeLabel = new JLabel(time);
            timeLabel.setFont(new Font("Consolas", Font.PLAIN, 10));
            timeLabel.setForeground(Color.WHITE);

            this.headerPanel.add(timeLabel, BorderLayout.EAST);
            this.headerPanel.setBorder(BorderFactory.createEmptyBorder(0, 0, 4, 0));

            // 内容：使用自定义绘制文本面板（去掉 RSyntaxTextArea 嵌套感）
            String initialText = thinking ? "● AI 思考中..." : content;
            this.contentArea = new TextBubbleContent(initialText);
            Color textClr = isUser ? new Color(0xE8E8E8)
                    : isError ? new Color(0xFFCDD2)
                    : thinking ? C_PRIMARY
                    : new Color(0xEAEAEA);
            this.contentArea.setTextColor(textClr);
            this.contentArea.setCursor(Cursor.getPredefinedCursor(Cursor.TEXT_CURSOR));

            // 内容容器：BoxLayout 纵向排列 文本区 + 媒体面板
            JPanel contentPane = new JPanel();
            contentPane.setLayout(new BoxLayout(contentPane, BoxLayout.Y_AXIS));
            contentPane.setOpaque(false);
            contentPane.add(this.contentArea);

            // 媒体面板（图片/视频容器）
            this.mediaPanel = new JPanel();
            this.mediaPanel.setLayout(new BoxLayout(this.mediaPanel, BoxLayout.Y_AXIS));
            this.mediaPanel.setOpaque(false);
            this.mediaPanel.setBorder(BorderFactory.createEmptyBorder(8, 0, 0, 0));
            contentPane.add(this.mediaPanel);

            JPanel innerPanel = new JPanel(new BorderLayout());
            innerPanel.setOpaque(false);
            innerPanel.setBorder(BorderFactory.createEmptyBorder(6, 14, 6, 14));
            innerPanel.add(this.headerPanel, BorderLayout.NORTH);
            innerPanel.add(contentPane, BorderLayout.CENTER);

            add(innerPanel, BorderLayout.CENTER);
            setBorder(BorderFactory.createEmptyBorder(0, 4, 0, 4));
        }

        @Override
        public Dimension getPreferredSize() {
            // 回溯找到 chatMessagePanel 的实际宽度
            int chatPanelW = 500;
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
            int maxW = (int) (chatPanelW * 0.75);
            if (maxW < 240) maxW = 240;

            // 文本换行宽度
            if (contentArea != null) {
                int textWidth = maxW - 28;
                if (textWidth > 0) {
                    contentArea.setTargetWidth(textWidth);
                }
            }

            // 直接累加子组件高度，不用 super.getPreferredSize() 避免嵌套计算误差
            // ChatBubble border(0,4,0,4) + innerPanel border(6,14,6,14)
            Insets insets = getInsets();
            int chatBorderV = insets.top + insets.bottom;          // 0 + 0 = 0
            int innerBorderV = 6 + 6;                             // innerPanel top + bottom
            int contentH = contentArea != null ? contentArea.getPreferredSize().height : 0;
            int mediaH = (mediaPanel != null && mediaPanel.getComponentCount() > 0)
                    ? mediaPanel.getPreferredSize().height : 0;
            int h = chatBorderV + innerBorderV + contentH + mediaH + headerPanel.getPreferredSize().height;
            return new Dimension(maxW + 8, h);
        }

        /** 重新计算内容区尺寸 */
        void recalcContentSize() {
            if (contentArea == null) return;
            int chatPanelW = 500;
            Container pp = getParent();
            while (pp != null) {
                if (pp instanceof JViewport vp) { chatPanelW = vp.getWidth(); break; }
                if (pp.getParent() instanceof JViewport vp) { chatPanelW = vp.getWidth(); break; }
                pp = pp.getParent();
            }
            int maxW = (int) (chatPanelW * 0.75) - 40;
            if (maxW < 160) maxW = 160;
            contentArea.setTargetWidth(maxW);
        }

        /** 计算 contentPane 可用宽度：ChatBubble宽度 - 两边border/padding */
        private int getContentPaneWidth() {
            int chatPanelW = 500;
            Container pp = getParent();
            while (pp != null) {
                if (pp instanceof JViewport vp) { chatPanelW = vp.getWidth(); break; }
                if (pp.getParent() instanceof JViewport vp) { chatPanelW = vp.getWidth(); break; }
                pp = pp.getParent();
            }
            // maxW + 8 是 ChatBubble 宽度，减去 ChatBubble border(4+4) + innerPanel border(14+14)
            int maxW = (int) (chatPanelW * 0.75);
            if (maxW < 240) maxW = 240;
            return maxW - 28;  // (maxW + 8) - 8 - 28 = maxW - 28
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

    // ==================== 文本气泡内容面板（自定义绘制，替代 RSyntaxTextArea） ====================

    /** 聊天消息文本内容面板，纯 Graphics2D 绘制，支持鼠标选中文本并复制 */
    private static class TextBubbleContent extends JPanel {
        private static final int PADDING = 6;
        private static final Color SEL_BG = new Color(0x4A6FA5);       // 选中背景
        private static final Color SEL_TEXT = new Color(0xFFFFFF);      // 选中文字色

        private final StringBuilder buffer = new StringBuilder();
        private Color textColor = new Color(0xEAEAEA);
        private int lineHeight;
        private List<String> wrappedLines = new ArrayList<>();
        private List<Integer> lineOffsets = new ArrayList<>();  // 每行在 buffer 中的起始位置
        private int targetWidth = 400;

        // 文本选中
        private int selStart = -1, selEnd = -1;
        private final JPopupMenu popupMenu;

        TextBubbleContent(String initialText) {
            setOpaque(false);
            buffer.append(initialText);
            setFont(NetUtil.FONT_TEXT);
            updateMetrics();
            setFocusable(true);
            requestFocusInWindow();

            // 右键菜单
            popupMenu = new JPopupMenu();
            JMenuItem copyItem = new JMenuItem("复制");
            copyItem.addActionListener(e -> copySelection());
            popupMenu.add(copyItem);
            JMenuItem selectAllItem = new JMenuItem("全选");
            selectAllItem.addActionListener(e -> selectAll());
            popupMenu.add(selectAllItem);
            setComponentPopupMenu(popupMenu);

            // 鼠标选中
            MouseAdapter selMouse = new MouseAdapter() {
                @Override
                public void mousePressed(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        requestFocusInWindow();
                        selStart = selEnd = charAtPoint(e.getPoint());
                        repaint();
                    }
                }
                @Override
                public void mouseDragged(MouseEvent e) {
                    if (SwingUtilities.isLeftMouseButton(e)) {
                        selEnd = charAtPoint(e.getPoint());
                        repaint();
                    }
                }
            };
            addMouseListener(selMouse);
            addMouseMotionListener(selMouse);

            // Ctrl+C / Ctrl+A
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_C, InputEvent.CTRL_DOWN_MASK), "copy");
            getActionMap().put("copy", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) { copySelection(); }
            });
            getInputMap(JComponent.WHEN_FOCUSED).put(KeyStroke.getKeyStroke(KeyEvent.VK_A, InputEvent.CTRL_DOWN_MASK), "selectAll");
            getActionMap().put("selectAll", new AbstractAction() {
                @Override
                public void actionPerformed(ActionEvent e) { selectAll(); }
            });
        }

        private void selectAll() {
            selStart = 0;
            selEnd = buffer.length();
            repaint();
        }

        private void copySelection() {
            if (selStart < 0 || selEnd < 0 || selStart == selEnd) return;
            int s = Math.min(selStart, selEnd);
            int e = Math.max(selStart, selEnd);
            if (e > buffer.length()) e = buffer.length();
            String selected = buffer.substring(s, e);
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(selected), null);
        }

        /** 根据鼠标坐标计算缓冲区字符偏移 */
        private int charAtPoint(Point p) {
            int lineIdx = (p.y - PADDING) / lineHeight;
            if (lineIdx < 0) lineIdx = 0;
            if (lineIdx >= wrappedLines.size()) lineIdx = wrappedLines.size() - 1;
            if (lineIdx < 0) return 0;

            String line = wrappedLines.get(lineIdx);
            int baseOffset = lineOffsets.get(lineIdx);
            FontMetrics fm = getFontMetrics(getFont());
            int x = p.x - PADDING;

            // 二分查找字符位置
            int lo = 0, hi = line.length();
            while (lo < hi) {
                int mid = (lo + hi + 1) / 2;
                if (fm.stringWidth(line.substring(0, mid)) <= x) {
                    lo = mid;
                } else {
                    hi = mid - 1;
                }
            }
            return baseOffset + lo;
        }

        private void updateMetrics() {
            FontMetrics fm = getFontMetrics(getFont());
            lineHeight = fm.getHeight();
        }

        @Override
        public void setFont(Font font) {
            super.setFont(font);
            updateMetrics();
        }

        void setTextColor(Color c) { this.textColor = c; repaint(); }

        void setText(String text) {
            buffer.setLength(0);
            buffer.append(text);
            selStart = selEnd = -1;
            rewrap();
            repaint();
        }

        void append(String text) {
            buffer.append(text);
            rewrap();
        }

        void clear() {
            buffer.setLength(0);
            wrappedLines.clear();
            lineOffsets.clear();
            selStart = selEnd = -1;
            repaint();
        }

        private void rewrap() {
            wrappedLines.clear();
            lineOffsets.clear();
            if (buffer.length() == 0) return;

            FontMetrics fm = getFontMetrics(getFont());
            int maxW = targetWidth - PADDING * 2;
            if (maxW <= 0) maxW = 400;

            String text = buffer.toString();
            StringBuilder lineBuf = new StringBuilder();
            int lineStartOffset = 0;

            for (int i = 0; i < text.length(); i++) {
                char ch = text.charAt(i);
                if (ch == '\n') {
                    wrappedLines.add(lineBuf.toString());
                    lineOffsets.add(lineStartOffset);
                    lineBuf.setLength(0);
                    lineStartOffset = i + 1;
                } else {
                    String tentative = lineBuf.toString() + ch;
                    if (fm.stringWidth(tentative) <= maxW) {
                        lineBuf.append(ch);
                    } else {
                        wrappedLines.add(lineBuf.toString());
                        lineOffsets.add(lineStartOffset);
                        lineStartOffset = i;
                        lineBuf.setLength(0);
                        lineBuf.append(ch);
                    }
                }
            }
            if (lineBuf.length() > 0) {
                wrappedLines.add(lineBuf.toString());
                lineOffsets.add(lineStartOffset);
            }
            if (wrappedLines.isEmpty()) {
                wrappedLines.add("");
                lineOffsets.add(0);
            }
        }

        @Override
        public Dimension getPreferredSize() {
            if (wrappedLines.isEmpty()) {
                return new Dimension(100, lineHeight + PADDING * 2 + 4);
            }
            FontMetrics fm = getFontMetrics(getFont());
            int maxLineW = 0;
            for (String line : wrappedLines) {
                int w = fm.stringWidth(line);
                if (w > maxLineW) maxLineW = w;
            }
            int prefW = Math.min(maxLineW + PADDING * 2, targetWidth) + 4;
            if (prefW < 120) prefW = 120;
            int prefH = wrappedLines.size() * lineHeight + PADDING * 2 + 4;
            return new Dimension(prefW, prefH);
        }

        void setTargetWidth(int w) {
            if (w != targetWidth) {
                targetWidth = w;
                rewrap();
                repaint();
            }
        }

        int getLineCount() { return wrappedLines.size(); }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            int y = fm.getAscent() + PADDING;

            // 判断有效的选中范围
            int s = Math.min(selStart, selEnd);
            int e = Math.max(selStart, selEnd);
            boolean hasSelection = s >= 0 && e > s && e <= buffer.length();

            for (int li = 0; li < wrappedLines.size(); li++) {
                String line = wrappedLines.get(li);
                int baseOff = lineOffsets.get(li);
                int lineStartOff = baseOff;
                int lineEndOff = baseOff + line.length();

                if (hasSelection && s < lineEndOff && e > lineStartOff) {
                    // 该行有选中部分
                    int selInLineStart = Math.max(s, lineStartOff) - lineStartOff;
                    int selInLineEnd = Math.min(e, lineEndOff) - lineStartOff;

                    // 选中前部分
                    if (selInLineStart > 0) {
                        String before = line.substring(0, selInLineStart);
                        int beforeW = fm.stringWidth(before);
                        g2.setColor(textColor);
                        g2.drawString(before, PADDING, y);
                        // 选中部分
                        String selected = line.substring(selInLineStart, selInLineEnd);
                        int selW = fm.stringWidth(selected);
                        g2.setColor(SEL_BG);
                        g2.fillRect(PADDING + beforeW, y - fm.getAscent(), selW, lineHeight);
                        g2.setColor(SEL_TEXT);
                        g2.drawString(selected, PADDING + beforeW, y);
                        // 选中后部分
                        if (selInLineEnd < line.length()) {
                            g2.setColor(textColor);
                            g2.drawString(line.substring(selInLineEnd), PADDING + beforeW + selW, y);
                        }
                    } else {
                        // 从行首开始选中
                        String selected = line.substring(selInLineStart, selInLineEnd);
                        int selW = fm.stringWidth(selected);
                        g2.setColor(SEL_BG);
                        g2.fillRect(PADDING, y - fm.getAscent(), selW, lineHeight);
                        g2.setColor(SEL_TEXT);
                        g2.drawString(selected, PADDING, y);
                        if (selInLineEnd < line.length()) {
                            g2.setColor(textColor);
                            g2.drawString(line.substring(selInLineEnd), PADDING + selW, y);
                        }
                    }
                } else {
                    // 无选中，正常绘制
                    g2.setColor(textColor);
                    g2.drawString(line, PADDING, y);
                }
                y += lineHeight;
            }

            g2.dispose();
        }
    }

    // ==================== 媒体文件本地缓存目录 ====================
    private static java.io.File getImgDir() {
        java.io.File dir = new java.io.File(System.getProperty("user.dir"), "agnes_img");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    private static java.io.File getVideoDir() {
        java.io.File dir = new java.io.File(System.getProperty("user.dir"), "agnes_video");
        if (!dir.exists()) dir.mkdirs();
        return dir;
    }

    // ==================== 嵌入式视频播放器（FFmpegFrameGrabber） ====================

    /**
     * 嵌入在 ChatBubble 媒体面板中的视频播放器，使用 FFmpegFrameGrabber 解码播放。
     * 支持本地文件路径和 HTTP/HTTPS 视频 URL，自动循环播放。
     */
    private static class ChatVideoPlayer extends JPanel {

        private static final Color C_CONTROL_BG = new Color(0x2A2A2A);
        private static final Color C_BTN_BG = new Color(0x444444);
        private static final Color C_PRIMARY = new Color(0x6BCB77);

        private final String videoUrl;
        private final JPanel canvas;
        private final JButton playPauseBtn;
        private final JLabel statusLabel;
        private final JLabel savePathLabel;

        // 预下载相关
        private java.io.File localVideoFile;
        private volatile String placeholderText = "正在下载视频...";
        private volatile double downloadProgress = 0.0; // 0.0 ~ 1.0
        private volatile boolean ready = false;    // 下载完成、grabber 已初始化

        private FFmpegFrameGrabber grabber;
        private Thread grabThread;
        private volatile boolean running = false;
        private volatile boolean playing = false;
        private volatile boolean videoEnded = false;
        private BufferedImage currentFrame;    // 当前帧（播放时）/ 首帧缩略图
        private BufferedImage compatImage;
        private int compatW = -1, compatH = -1;
        private long lastRepaintNanos;
        private double videoFps = 30.0; // 兜底帧率

        // 视频尺寸
        private int videoW = 640, videoH = 360;

        ChatVideoPlayer(String videoUrl, int contentWidth) {
            this.videoUrl = videoUrl;
            int dispW = contentWidth > 0 ? contentWidth : 400;
            // 16:9 默认比例
            int dispH = dispW * 9 / 16;
            if (dispH > 400) { dispH = 400; dispW = dispH * 16 / 9; }

            setLayout(new BorderLayout());
            setOpaque(false);
            setBorder(BorderFactory.createCompoundBorder(
                    BorderFactory.createLineBorder(new Color(0x555555), 1),
                    BorderFactory.createEmptyBorder(4, 4, 4, 4)));

            // ---- 视频画面画布（透明背景，帧填满宽度） ----
            canvas = new JPanel() {
                @Override
                protected void paintComponent(Graphics g) {
                    super.paintComponent(g);
                    Graphics2D g2 = (Graphics2D) g;
                    int cw = getWidth(), ch = getHeight();

                    // 计算视频显示区域
                    int dw = cw;
                    int dh = (int) ((long) videoH * cw / videoW);
                    if (dh > ch) { dh = ch; dw = (int) ((long) videoW * ch / videoH); }
                    int dx = (cw - dw) / 2, dy = (ch - dh) / 2;

                    if (ready) {
                        // 已就绪：画缩略图或当前帧
                        BufferedImage img = currentFrame;
                        if (img != null) {
                            g2.drawImage(img, dx, dy, dw, dh, null);
                        }
                        // 未播放时绘制播放按钮叠加层
                        if (!running && !videoEnded) {
                            g2.setColor(new Color(0, 0, 0, 120));
                            g2.fillRect(dx, dy, dw, dh);
                            int r = Math.min(dw, dh) / 5;
                            int cx = dx + dw / 2, cy = dy + dh / 2;
                            g2.setColor(new Color(255, 255, 255, 200));
                            g2.setStroke(new java.awt.BasicStroke(3));
                            g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                            // 播放三角形
                            int[] xp = {cx - r / 3, cx + r * 2 / 3, cx - r / 3};
                            int[] yp = {cy - r / 2, cy, cy + r / 2};
                            g2.fillPolygon(xp, yp, 3);
                        } else if (videoEnded && img != null) {
                            // 播放完毕遮罩 + 重播按钮
                            g2.setColor(new Color(0, 0, 0, 100));
                            g2.fillRect(dx, dy, dw, dh);
                            int r = Math.min(dw, dh) / 5;
                            int cx = dx + dw / 2, cy = dy + dh / 2;
                            g2.setColor(new Color(255, 255, 255, 180));
                            g2.setStroke(new java.awt.BasicStroke(3));
                            g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                            // 重播三角形
                            int[] xp = {cx - r / 3, cx + r * 2 / 3, cx - r / 3};
                            int[] yp = {cy - r / 2, cy, cy + r / 2};
                            g2.fillPolygon(xp, yp, 3);
                        }
                    } else {
                        // 下载中：深色背景 + 进度条
                        g2.setColor(new Color(0x222222));
                        g2.fillRect(dx, dy, dw, dh);
                        g2.setColor(new Color(0xAAAAAA));
                        g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
                        FontMetrics fm = g2.getFontMetrics();
                        String text = placeholderText;
                        g2.drawString(text,
                                (cw - fm.stringWidth(text)) / 2,
                                ch / 2 - 16);

                        // 进度条
                        int barW = dw * 2 / 3;
                        int barX = dx + (dw - barW) / 2;
                        int barY = ch / 2 + 6;
                        int barH = 6;
                        g2.setColor(new Color(0x555555));
                        g2.fillRoundRect(barX, barY, barW, barH, 4, 4);
                        g2.setColor(C_PRIMARY);
                        int fillW = (int) (barW * downloadProgress);
                        if (fillW > 0) g2.fillRoundRect(barX, barY, fillW, barH, 4, 4);
                    }
                }
            };
            canvas.setOpaque(false);

            // 点击画布播放/暂停
            canvas.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (!ready || SwingUtilities.isRightMouseButton(e)) return;
                    if (running && !videoEnded) {
                        playing = !playing;
                        updatePlayPauseBtn();
                    } else if (videoEnded) {
                        startVideo();
                    } else {
                        startVideo();
                    }
                }
            });

            // 右键菜单
            JPopupMenu videoPopup = new JPopupMenu();
            JMenuItem copyLink = new JMenuItem("复制链接");
            copyLink.addActionListener(e -> java.awt.Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new java.awt.datatransfer.StringSelection(videoUrl), null));
            videoPopup.add(copyLink);

            JMenuItem saveVideo = new JMenuItem("保存视频");
            saveVideo.addActionListener(e -> {
                JFileChooser fc = new JFileChooser();
                String defaultName = videoUrl.substring(videoUrl.lastIndexOf('/') + 1);
                if (defaultName.contains("?")) defaultName = defaultName.substring(0, defaultName.indexOf('?'));
                if (defaultName.isEmpty()) defaultName = "video.mp4";
                fc.setSelectedFile(new java.io.File(defaultName));
                if (fc.showSaveDialog(SwingUtilities.getWindowAncestor(this)) == JFileChooser.APPROVE_OPTION) {
                    Thread.ofVirtual().start(() -> {
                        try {
                            java.net.http.HttpClient client = java.net.http.HttpClient.newBuilder()
                                    .followRedirects(java.net.http.HttpClient.Redirect.NORMAL).build();
                            java.net.http.HttpRequest req = java.net.http.HttpRequest.newBuilder(URI.create(videoUrl))
                                    .GET().build();
                            java.net.http.HttpResponse<InputStream> resp =
                                    client.send(req, java.net.http.HttpResponse.BodyHandlers.ofInputStream());
                            java.nio.file.Files.copy(resp.body(), fc.getSelectedFile().toPath(),
                                    java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                                    "视频已保存到: " + fc.getSelectedFile().getAbsolutePath(), "保存成功",
                                    JOptionPane.INFORMATION_MESSAGE));
                        } catch (Exception ex) {
                            SwingUtilities.invokeLater(() -> JOptionPane.showMessageDialog(this,
                                    "保存失败: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE));
                        }
                    });
                }
            });
            videoPopup.add(saveVideo);

            JMenuItem openBrowser = new JMenuItem("浏览器打开");
            openBrowser.addActionListener(e -> {
                try {
                    Desktop.getDesktop().browse(URI.create(videoUrl));
                } catch (Exception ex) {
                    JOptionPane.showMessageDialog(this, "无法打开浏览器: " + ex.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
                }
            });
            videoPopup.add(openBrowser);

            canvas.setComponentPopupMenu(videoPopup);
            canvas.setToolTipText("右键菜单 | 单击播放/暂停");

            // 保存路径提示标签（预下载完成后显示）
            savePathLabel = new JLabel();
            savePathLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            savePathLabel.setForeground(new Color(0x888888));
            savePathLabel.setOpaque(false);
            savePathLabel.setBorder(BorderFactory.createEmptyBorder(2, 0, 4, 0));
            savePathLabel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            savePathLabel.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseClicked(MouseEvent e) {
                    if (localVideoFile != null) {
                        try {
                            Desktop.getDesktop().open(localVideoFile.getParentFile());
                        } catch (Exception ignored) {}
                    }
                }
            });
            add(savePathLabel, BorderLayout.NORTH);

            add(canvas, BorderLayout.CENTER);

            // ---- 底部控制栏 ----
            JPanel controlBar = new JPanel(new BorderLayout(6, 0));
            controlBar.setBackground(C_CONTROL_BG);
            controlBar.setBorder(BorderFactory.createEmptyBorder(4, 6, 4, 6));

            JPanel leftCtrls = new JPanel(new FlowLayout(FlowLayout.LEFT, 4, 0));
            leftCtrls.setOpaque(false);

            playPauseBtn = new JButton(">");
            playPauseBtn.setFont(new Font("Microsoft YaHei", Font.BOLD, 14));
            playPauseBtn.setForeground(C_PRIMARY);
            playPauseBtn.setBackground(C_BTN_BG);
            playPauseBtn.setFocusable(false);
            playPauseBtn.setBorder(BorderFactory.createEmptyBorder(2, 8, 2, 8));
            playPauseBtn.addActionListener(e -> {
                if (!ready) return; // 还没下载完，不响应
                if (running && !videoEnded) {
                    playing = !playing;
                    updatePlayPauseBtn();
                } else if (videoEnded) {
                    // 重播
                    startVideo();
                } else {
                    // 首发加载
                    startVideo();
                }
            });
            leftCtrls.add(playPauseBtn);

            // 文件名标签
            String fileName = videoUrl.substring(videoUrl.lastIndexOf('/') + 1);
            if (fileName.contains("?")) fileName = fileName.substring(0, fileName.indexOf('?'));
            if (fileName.length() > 40) fileName = fileName.substring(0, 37) + "...";
            statusLabel = new JLabel(fileName);
            statusLabel.setFont(new Font("Microsoft YaHei", Font.PLAIN, 11));
            statusLabel.setForeground(new Color(0xAAAAAA));
            leftCtrls.add(statusLabel);

            controlBar.add(leftCtrls, BorderLayout.WEST);

            add(controlBar, BorderLayout.SOUTH);

            setPreferredSize(new Dimension(dispW, dispH + 36));
            setMaximumSize(new Dimension(Integer.MAX_VALUE, dispH + 36));
            setMinimumSize(new Dimension(240, 200));

            // 后台预下载视频，抓取首帧缩略图
            startPreload();
        }

        private void updatePlayPauseBtn() {
            playPauseBtn.setText(playing ? "||" : ">");
        }

        /** 启动视频抓帧线程（平台线程，避免 JNI pin 住虚拟线程 carrier） */
        private void startVideo() {
            if (running) return;
            running = true;
            playing = true;
            videoEnded = false;
            updatePlayPauseBtn();

            // 使用本地文件（已预下载），没有则回退到 URL
            String source = localVideoFile != null ? localVideoFile.getAbsolutePath() : videoUrl;
            boolean isReplay = grabber != null; // 之前解过码说明是重播

            grabThread = new Thread(() -> {
                try {
                    if (isReplay) {
                        // 重播：释放旧 grabber，重新创建
                        disposeGrabber();
                    }

                    grabber = new FFmpegFrameGrabber(source);
                    grabber.start();

                    // 获取视频信息
                    videoW = grabber.getImageWidth();
                    videoH = grabber.getImageHeight();
                    double fps = grabber.getFrameRate();
                    if (fps > 1 && fps < 200) videoFps = fps;

                    if (videoW <= 0) videoW = 640;
                    if (videoH <= 0) videoH = 360;

                    long frameIntervalNanos = (long) (1_000_000_000L / videoFps);
                    long nextFrameNanos = System.nanoTime();

                    Java2DFrameConverter converter = new Java2DFrameConverter();
                    OpenCVFrameConverter.ToMat matConverter = new OpenCVFrameConverter.ToMat();

                    try {
                        while (running) {
                            // 暂停等待
                            if (!playing) {
                                Thread.sleep(30);
                                nextFrameNanos = System.nanoTime();
                                continue;
                            }

                            Frame frame = grabber.grab();
                            if (frame == null) {
                                // 视频结束
                                videoEnded = true;
                                playing = false;
                                running = false;
                                SwingUtilities.invokeLater(() -> {
                                    updatePlayPauseBtn();
                                    canvas.repaint();
                                });
                                break;
                            }

                            if (frame.image == null) continue;

                            // 视频帧转换
                            BufferedImage img;
                            Mat mat = matConverter.convert(frame);
                            if (mat != null) {
                                int cw = canvas.getWidth();
                                int ch = canvas.getHeight();
                                if (cw > 0 && ch > 0) {
                                    Mat resizedMat = new Mat();
                                    Start.resizeCv(mat, resizedMat, new Size(cw, ch));
                                    Frame rf = matConverter.convert(resizedMat);
                                    img = converter.convert(rf);
                                    resizedMat.release();
                                } else {
                                    img = converter.convert(frame);
                                }
                                mat.release();
                            } else {
                                img = converter.convert(frame);
                            }

                            if (img != null) {
                                img = toScreenCompatible(img);
                                currentFrame = img;
                                throttleRepaint();
                            }

                            // 帧率控制
                            long now = System.nanoTime();
                            if (now < nextFrameNanos) {
                                long sleepMs = (nextFrameNanos - now) / 1_000_000;
                                if (sleepMs > 0) Thread.sleep(sleepMs);
                            }
                            nextFrameNanos = Math.max(now, nextFrameNanos) + frameIntervalNanos;
                        }
                    } finally {
                        try { converter.close(); } catch (Exception ignored) {}
                        try { matConverter.close(); } catch (Exception ignored) {}
                    }
                } catch (Exception e) {
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("播放失败: " + e.getMessage());
                        statusLabel.setForeground(new Color(0xFF6B6B));
                        running = false;
                        playing = false;
                        videoEnded = true;
                        updatePlayPauseBtn();
                        canvas.repaint();
                    });
                } finally {
                    disposeGrabber();
                }
            }, "chat-video-player");
            grabThread.setDaemon(true);
            grabThread.start();
        }

        private void disposeGrabber() {
            if (grabber != null) {
                try { grabber.stop(); } catch (Exception ignored) {}
                try { grabber.release(); } catch (Exception ignored) {}
                grabber = null;
            }
        }

        /** 后台预下载视频到本地临时文件，并抓取首帧作为缩略图 */
        private void startPreload() {
            Thread.ofVirtual().start(() -> {
                try {
                    // 1. 下载视频到临时文件
                    HttpClient client = HttpClient.newBuilder()
                            .followRedirects(HttpClient.Redirect.NORMAL)
                            .build();
                    HttpRequest req = HttpRequest.newBuilder(URI.create(videoUrl))
                            .timeout(java.time.Duration.ofSeconds(300))
                            .GET().build();

                    HttpResponse<InputStream> resp = client.send(req, HttpResponse.BodyHandlers.ofInputStream());
                    if (resp.statusCode() != 200) {
                        throw new IOException("HTTP " + resp.statusCode());
                    }

                    // 确定文件扩展名
                    String ext = ".mp4";
                    String path = videoUrl;
                    int qIdx = path.indexOf('?');
                    if (qIdx > 0) path = path.substring(0, qIdx);
                    int dotIdx = path.lastIndexOf('.');
                    if (dotIdx > 0) ext = path.substring(dotIdx);

                    java.io.File downloadDir = getVideoDir();
                    localVideoFile = new java.io.File(downloadDir, "agnes_video_" + System.currentTimeMillis() + ext);

                    long contentLen = resp.headers().firstValueAsLong("Content-Length").orElse(-1);
                    try (InputStream in = resp.body();
                         java.io.FileOutputStream fos = new java.io.FileOutputStream(localVideoFile)) {
                        byte[] buf = new byte[8192];
                        long total = 0;
                        int n;
                        while ((n = in.read(buf)) > 0) {
                            fos.write(buf, 0, n);
                            total += n;
                            if (contentLen > 0) {
                                downloadProgress = (double) total / contentLen;
                                placeholderText = String.format("正在下载视频... %.0f%%", downloadProgress * 100);
                            } else {
                                placeholderText = "正在下载视频... " + (total / 1024) + " KB";
                            }
                            SwingUtilities.invokeLater(canvas::repaint);
                        }
                    }

                    // 2. 初始化 grabber + 抓取首帧缩略图
                    placeholderText = "正在解析视频...";
                    downloadProgress = 1.0;
                    SwingUtilities.invokeLater(canvas::repaint);

                    grabber = new FFmpegFrameGrabber(localVideoFile);
                    grabber.start();
                    videoW = grabber.getImageWidth();
                    videoH = grabber.getImageHeight();
                    double fps = grabber.getFrameRate();
                    if (fps > 1 && fps < 200) videoFps = fps;
                    if (videoW <= 0) videoW = 640;
                    if (videoH <= 0) videoH = 360;

                    // 抓第一帧
                    Frame firstFrame = grabber.grab();
                    if (firstFrame != null && firstFrame.image != null) {
                        Java2DFrameConverter converter = new Java2DFrameConverter();
                        BufferedImage thumb = converter.convert(firstFrame);
                        converter.close();
                        if (thumb != null) {
                            currentFrame = toScreenCompatible(thumb);
                        }
                    }
                    grabber.stop();
                    grabber.release();
                    grabber = null;

                    // 3. 标记就绪
                    ready = true;
                    final String savedPath = localVideoFile.getAbsolutePath();
                    SwingUtilities.invokeLater(() -> {
                        savePathLabel.setText("📁 文件已自动保存至: " + savedPath);
                        savePathLabel.setToolTipText("单击打开文件夹");
                        statusLabel.setText("就绪 - 点击播放");
                        canvas.repaint();
                    });
                } catch (Exception e) {
                    // 下载失败，降级到在线播放
                    placeholderText = "下载失败，将在线播放";
                    ready = true; // 允许点击播放（降级到 URL 模式）
                    SwingUtilities.invokeLater(() -> {
                        statusLabel.setText("在线模式");
                        canvas.repaint();
                    });
                }
            });
        }

        /** 停止播放 */
        void stopVideo() {
            running = false;
            playing = false;
            if (grabThread != null) {
                grabThread.interrupt();
                grabThread = null;
            }
            disposeGrabber();
            currentFrame = null;
            // 清理临时文件
            if (localVideoFile != null) {
                try { localVideoFile.delete(); } catch (Exception ignored) {}
                localVideoFile = null;
            }
            SwingUtilities.invokeLater(() -> {
                updatePlayPauseBtn();
                canvas.repaint();
            });
        }

        /** 节流 repaint（~30fps 上限） */
        private void throttleRepaint() {
            long now = System.nanoTime();
            if (now - lastRepaintNanos >= 33_000_000L) {
                lastRepaintNanos = now;
                SwingUtilities.invokeLater(canvas::repaint);
            }
        }

        /** 转为 TYPE_INT_RGB，加速 EDT 渲染 */
        private BufferedImage toScreenCompatible(BufferedImage src) {
            if (src == null) return null;
            if (src.getType() == BufferedImage.TYPE_INT_RGB) return src;
            int w = src.getWidth(), h = src.getHeight();
            if (compatW != w || compatH != h) {
                compatImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
                compatW = w;
                compatH = h;
            }
            Graphics2D g2d = compatImage.createGraphics();
            try { g2d.drawImage(src, 0, 0, null); }
            finally { g2d.dispose(); }
            return compatImage;
        }

        @Override
        public void removeNotify() {
            super.removeNotify();
            stopVideo();
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

    // ==================== AI 图片生成 (Agnes-Image-2.0-Flash) ====================

    /** 从模型配置中解析图片生成参数（提取尺寸纯数字部分） */
    private String parseImageSize(ModelConfig mc) {
        String size = mc.getExtra("image.size");
        return (size != null && !size.isBlank()) ? size : "1024x1024";
    }

    /** 从模型配置中解析图片生成模式 */
    private String parseImageMode(ModelConfig mc) {
        String mode = mc.getExtra("image.mode");
        return (mode != null && !mode.isBlank()) ? mode : "文生图";
    }

    /** 从模型配置中解析图片输入 URL 列表 */
    private List<String> parseImageUrls(ModelConfig mc) {
        String urls = mc.getExtra("image.urls");
        if (urls != null && !urls.isBlank()) {
            List<String> list = new ArrayList<>(Arrays.asList(urls.split("\\n")));
            list.removeIf(String::isBlank);
            return list.isEmpty() ? null : list;
        }
        return null;
    }

    /** 从模型配置中解析视频参数 */
    private int[] parseVideoResolution(ModelConfig mc) {
        String size = mc.getExtra("video.size");
        if (size != null && !size.isBlank()) {
            String[] parts = size.split("x");
            if (parts.length == 2) {
                try { return new int[] { Integer.parseInt(parts[0]), Integer.parseInt(parts[1]) }; }
                catch (NumberFormatException ignored) {}
            }
        }
        return new int[]{1152, 768};
    }

    private int[] parseVideoDuration(ModelConfig mc) {
        String dur = mc.getExtra("video.duration");
        if (dur != null) {
            if (dur.contains("3 秒")) return new int[]{81, 24};
            if (dur.contains("5 秒")) return new int[]{121, 24};
            if (dur.contains("10 秒")) return new int[]{241, 24};
            if (dur.contains("17 秒")) return new int[]{409, 24};
        }
        return new int[]{121, 24};
    }

    private double parseVideoFps(ModelConfig mc) {
        String fps = mc.getExtra("video.fps");
        if (fps != null && !fps.isBlank()) {
            try { return Double.parseDouble(fps); } catch (NumberFormatException ignored) {}
        }
        return 24;
    }

    private String parseVideoMode(ModelConfig mc) {
        String mode = mc.getExtra("video.mode");
        return (mode != null && !mode.isBlank()) ? mode : "文生视频";
    }

    private Integer parseVideoSeed(ModelConfig mc) {
        String seed = mc.getExtra("video.seed");
        if (seed != null && !seed.isBlank()) {
            try { return Integer.parseInt(seed); } catch (NumberFormatException ignored) {}
        }
        return null;
    }

    private List<String> parseVideoUrls(ModelConfig mc) {
        String urls = mc.getExtra("video.urls");
        if (urls != null && !urls.isBlank()) {
            List<String> list = new ArrayList<>(Arrays.asList(urls.split("\\n")));
            list.removeIf(String::isBlank);
            return list.isEmpty() ? null : list;
        }
        return null;
    }

    /** 处理 Agnes 图片生成（从 sendMessage 路由过来） */
    private void handleAgnesImageSend(String prompt, ModelConfig mc) {
        // 优先使用模型配置中的 API Key
        String key = resolveAgnesApiKey(mc);
        if (key == null) return;

        String size = parseImageSize(mc);
        String mode = parseImageMode(mc);
        List<String> imageUrls = parseImageUrls(mc);

        // 对于非文生图模式但未配置 URL，在输入中检测 URL
        if (!"文生图".equals(mode) && (imageUrls == null || imageUrls.isEmpty())) {
            // 尝试从 prompt 中提取 URL
            imageUrls = extractUrlsFromText(prompt);
        }

        // 检查非文本模式是否有图片输入
        if (!"文生图".equals(mode) && (imageUrls == null || imageUrls.isEmpty())) {
            JOptionPane.showMessageDialog(this,
                    "图生图/多图合成需要提供图片 URL\n请在模型配置中填写或输入消息中包含图片 URL",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        inputArea.setText("");
        if (inputUndoManager != null) inputUndoManager.discardAllEdits();
        executeImageGeneration(mode, prompt, size, imageUrls);
    }

    /** 处理 Agnes 视频生成（从 sendMessage 路由过来） */
    private void handleAgnesVideoSend(String prompt, ModelConfig mc) {
        // 优先使用模型配置中的 API Key
        String key = resolveAgnesApiKey(mc);
        if (key == null) return;

        int[] wh = parseVideoResolution(mc);
        int[] fd = parseVideoDuration(mc);
        double fps = parseVideoFps(mc);
        String mode = parseVideoMode(mc);
        Integer seed = parseVideoSeed(mc);
        List<String> imageUrls = parseVideoUrls(mc);

        // 对于非文生视频模式但未配置 URL，尝试从 prompt 中提取
        if (!"文生视频".equals(mode) && (imageUrls == null || imageUrls.isEmpty())) {
            imageUrls = extractUrlsFromText(prompt);
        }

        if (!"文生视频".equals(mode) && (imageUrls == null || imageUrls.isEmpty())) {
            JOptionPane.showMessageDialog(this,
                    "图生视频/多图视频/关键帧动画需要提供图片 URL\n请在模型配置中填写或输入消息中包含图片 URL",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        // 关键帧动画和多图视频至少需要 2 张图片
        if (("关键帧动画".equals(mode) || "多图视频".equals(mode))
                && (imageUrls == null || imageUrls.size() < 2)) {
            JOptionPane.showMessageDialog(this,
                    "关键帧动画/多图视频至少需要 2 张图片\n当前只有 " + (imageUrls != null ? imageUrls.size() : 0) + " 张，请补充图片 URL",
                    "提示", JOptionPane.WARNING_MESSAGE);
            return;
        }

        inputArea.setText("");
        if (inputUndoManager != null) inputUndoManager.discardAllEdits();
        executeVideoGeneration(mode, prompt, wh[0], wh[1], fd[0], fps, seed, imageUrls);
    }

    /** 解析 Agnes API Key：优先用模型配置中的 Key，其次用缓存的 agnesApiKey，都没有则弹框 */
    private String resolveAgnesApiKey(ModelConfig mc) {
        // 优先使用模型配置中保存的 Key
        String key = mc.getApiKey();
        if (key != null && !key.isBlank()) {
            agnesApiKey = key;
            imageService = null;
            videoService = null;
            return key;
        }
        // 其次使用之前缓存的 Key
        if (agnesApiKey != null && !agnesApiKey.isBlank()) {
            return agnesApiKey;
        }
        // 都没有则弹框输入
        String inputKey = JOptionPane.showInputDialog(this,
                "请输入 Agnes API Key：\n（获取地址：https://apihub.agnes-ai.com）",
                "配置 Agnes API Key", JOptionPane.PLAIN_MESSAGE);
        if (inputKey != null && !inputKey.isBlank()) {
            agnesApiKey = inputKey.trim();
            imageService = null;
            videoService = null;
            return agnesApiKey;
        }
        return null;
    }

    /** 从文本中提取 URL */
    private List<String> extractUrlsFromText(String text) {
        List<String> urls = new ArrayList<>();
        java.util.regex.Matcher m = Pattern.compile("https?://[^\\s]+").matcher(text);
        while (m.find()) {
            urls.add(m.group());
        }
        return urls.isEmpty() ? null : urls;
    }

    /** 获取或创建图片生成服务 */
    private AgnesImageService getOrCreateImageService() {
        if (imageService == null && agnesApiKey != null && !agnesApiKey.isBlank()) {
            imageService = new AgnesImageService(agnesApiKey);
        }
        return imageService;
    }

    /** 执行图片生成并在对话中展示结果 */
    private void executeImageGeneration(String mode, String prompt, String size, List<String> imageUrls) {
        AgnesImageService service = getOrCreateImageService();
        if (service == null) {
            addErrorMessage("Agnes API Key 未配置", LocalTime.now().format(TF));
            return;
        }

        // 显示用户消息气泡（用户发起的图片生成请求）
        String userMsg = mode + "：" + prompt;
        String userTime = LocalTime.now().format(TF);
        clearPlaceholderIfNeeded();
        addMessageBubble("你", userMsg, userTime, true);

        // 显示生成中的 AI 气泡
        String aiTime = LocalTime.now().format(TF);
        ChatBubble genBubble = new ChatBubble("AI", "正在生成图片...", aiTime, false);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
        JPanel alignPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(genBubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        genBubble.setSize(genBubble.getPreferredSize());
        int prefH = genBubble.getPreferredSize().height + 4;
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, prefH));

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();
        chatMessagePanel.repaint();
        scrollToBottom();

        // 异步生成
        AgnesImageRequest req = buildImageRequest(mode, prompt, size, imageUrls);
        service.generateImageBytesAsync(req,
                imageBytes -> {
                    // 成功：将生成的图片显示在气泡中
                    SwingUtilities.invokeLater(() -> {
                        // 更新气泡内容
                        genBubble.contentArea.setText("[成功] 图片生成完成！\n\nPrompt：" + prompt
                                + "\n尺寸：" + size + "\n模式：" + mode);
                        genBubble.recalcContentSize();
                        genBubble.revalidateAndRepaint();

                        // 图片添加到气泡的媒体面板
                        try {
                            // 先保存到本地 agnes_img/ 目录
                            java.io.File imgDir = getImgDir();
                            java.io.File saveFile = new java.io.File(imgDir, "agnes_img_" + System.currentTimeMillis() + ".png");
                            try (java.io.FileOutputStream fos = new java.io.FileOutputStream(saveFile)) {
                                fos.write(imageBytes);
                            }

                            BufferedImage img = javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(imageBytes));
                            genBubble.addSavedPathLabel(saveFile.getAbsolutePath());
                            genBubble.addImageToPanel(img, mode + " 生成: " + prompt);
                            // 更新 wrapper 高度
                            int newH = genBubble.getPreferredSize().height + 4;
                            wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, newH));
                            wrapper.revalidate();
                            wrapper.repaint();
                        } catch (Exception ex) {
                            genBubble.addMediaError("图片解析失败", ex);
                        }

                        chatMessagePanel.revalidate();
                        chatMessagePanel.repaint();
                        scrollToBottom();
                    });
                },
                error -> {
                    // 失败：显示错误
                    SwingUtilities.invokeLater(() -> {
                        genBubble.contentArea.setText("[失败] 图片生成失败\n\n" + error.getMessage());
                        genBubble.recalcContentSize();
                        genBubble.revalidateAndRepaint();
                        chatMessagePanel.revalidate();
                        chatMessagePanel.repaint();
                        scrollToBottom();
                    });
                }
        );
    }

    /** 构建图片生成请求 */
    private AgnesImageRequest buildImageRequest(String mode, String prompt, String size, List<String> imageUrls) {
        AgnesImageRequest req = new AgnesImageRequest()
                .model("agnes-image-2.0-flash")
                .prompt(prompt)
                .size(size);

        switch (mode) {
            case "文生图" -> req.returnBase64(true); // Base64 输出，避免下载
            case "图生图（输入URL）" -> req.responseFormat("url")
                    .extraImage(imageUrls);
            case "多图合成" -> req.responseFormat("url")
                    .extraImage(imageUrls);
        }

        return req;
    }

    // ==================== AI 视频生成 (Agnes-Video-V2.0) ====================

    /** 获取或创建视频生成服务 */
    private AgnesVideoService getOrCreateVideoService() {
        if (videoService == null && agnesApiKey != null && !agnesApiKey.isBlank()) {
            videoService = new AgnesVideoService(agnesApiKey);
        }
        return videoService;
    }

    /** 解析分辨率字符串，返回 [width, height] */
    private int[] parseResolution(String sizeStr) {
        if (sizeStr == null) return new int[]{1152, 768};
        // 提取 "WxH" 格式
        String numPart = sizeStr.split(" ")[0].trim();
        String[] parts = numPart.split("x");
        if (parts.length == 2) {
            try {
                return new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
            } catch (NumberFormatException ignored) {}
        }
        return new int[]{1152, 768};
    }

    /** 解析时长预设，返回 [numFrames, frameRate] — 注意帧率单独解析所以这里只取帧数 */
    private int[] parseDuration(int idx) {
        return switch (idx) {
            case 0 -> new int[]{81, 24};   // 约 3 秒
            case 1 -> new int[]{121, 24};  // 约 5 秒
            case 2 -> new int[]{241, 24};  // 约 10 秒
            case 3 -> new int[]{409, 24};  // 约 17 秒 (720p上限)
            default -> new int[]{121, 24};
        };
    }

    /** 解析帧率字符串（格式如 "24 fps"），返回数值 */
    private double parseFps(String fpsStr) {
        if (fpsStr == null) return 24;
        try {
            return Double.parseDouble(fpsStr.replace(" fps", ""));
        } catch (NumberFormatException e) {
            return 24;
        }
    }

    /** 获取当前屏幕刷新率（Hz），获取失败返回 60 */
    private static int getScreenRefreshRate() {
        try {
            int rate = GraphicsEnvironment.getLocalGraphicsEnvironment()
                    .getDefaultScreenDevice()
                    .getDisplayMode()
                    .getRefreshRate();
            return rate > 0 ? rate : 60;
        } catch (Exception e) {
            return 60;
        }
    }

    /** 根据分辨率返回API允许的最大帧数（满足8n+1约束） */
    private static int getMaxVideoFrames(int width, int height) {
        int pixels = width * height;
        if (pixels >= 1280 * 720) return 169;  // 1080p级别
        if (pixels >= 640 * 480) return 409;   // 720p级别
        return 961;                              // 480p级别
    }

    /** 执行视频生成并在对话中展示结果 */
    private void executeVideoGeneration(String mode, String prompt,
                                         int width, int height, int numFrames, double fps,
                                         Integer seed, List<String> imageUrls) {
        // 根据分辨率裁剪帧数上限（API对不同分辨率有帧数限制）
        int maxFrames = getMaxVideoFrames(width, height);
        if (numFrames > maxFrames) {
            numFrames = maxFrames;
        }
        AgnesVideoService service = getOrCreateVideoService();
        if (service == null) {
            addErrorMessage("Agnes API Key 未配置", LocalTime.now().format(TF));
            return;
        }

        // 显示用户消息气泡
        String modeLabel = switch (mode) {
            case "文生视频" -> "文生视频";
            case "图生视频（输入URL）" -> "图生视频";
            case "多图视频" -> "多图视频";
            case "关键帧动画" -> "关键帧动画";
            default -> "视频生成";
        };
        String userMsg = modeLabel + "：" + prompt;
        String userTime = LocalTime.now().format(TF);
        clearPlaceholderIfNeeded();
        addMessageBubble("你", userMsg, userTime, true);

        // 显示生成中的 AI 气泡
        String aiTime = LocalTime.now().format(TF);
        ChatBubble genBubble = new ChatBubble("AI",
                "正在创建视频任务...\n\nPrompt：" + prompt
                        + "\n分辨率：" + width + "x" + height
                        + "\n帧数：" + numFrames + " @ " + fps + "fps",
                aiTime, false);

        JPanel wrapper = new JPanel(new BorderLayout());
        wrapper.setOpaque(false);
        wrapper.setBorder(BorderFactory.createEmptyBorder(2, 12, 2, 12));
        JPanel alignPanel = new JPanel(new FlowLayout(FlowLayout.LEFT, 0, 0));
        alignPanel.setOpaque(false);
        alignPanel.add(genBubble);
        wrapper.add(alignPanel, BorderLayout.CENTER);

        genBubble.setSize(genBubble.getPreferredSize());
        int prefH = genBubble.getPreferredSize().height + 4;
        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, prefH));

        chatMessagePanel.add(wrapper);
        chatMessagePanel.revalidate();
        chatMessagePanel.repaint();
        scrollToBottom();

        // 构建请求
        AgnesVideoRequest req = buildVideoRequest(mode, prompt, width, height, numFrames, fps, seed, imageUrls);

        // 异步创建并轮询
        String aiTimeFinal = aiTime;
        int finalNumFrames = numFrames;
        service.createAndWaitAsync(req,
                5000, // 5 秒轮询
                30 * 60 * 1000, // 最长 30 分钟
                progress -> {
                    // 进度更新
                    String progressText = progress.getStatusText()
                            + " (" + progress.progress + "%)"
                            + "\n\nPrompt：" + prompt
                            + "\n分辨率：" + width + "x" + height
                            + "\n帧数：" + finalNumFrames + " @ " + fps + "fps";
                    genBubble.contentArea.setText(progressText);
                    genBubble.recalcContentSize();
                    genBubble.revalidateAndRepaint();
                    int newH2 = genBubble.getPreferredSize().height + 4;
                    wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, newH2));
                    wrapper.revalidate();
                    wrapper.repaint();
                },
                completed -> {
                    // 成功
                    SwingUtilities.invokeLater(() -> {
                        String videoUrl = completed.getVideoUrl();
                        String sizeInfo = completed.getSize() != null ? completed.getSize() : (width + "x" + height);
                        String secondsInfo = completed.getSeconds() != null ? completed.getSeconds() : "—";

                        genBubble.contentArea.setText("[成功] 视频生成完成！"
                                + "\n\nPrompt：" + prompt
                                + "\n分辨率：" + sizeInfo
                                + "\n时长：" + secondsInfo + " 秒"
                                + "\n\n[>] 视频正在加载...");

                        genBubble.recalcContentSize();
                        genBubble.revalidateAndRepaint();

                        if (videoUrl != null && !videoUrl.isBlank()) {
                            try {
                                genBubble.addVideoPanel(videoUrl);
                            } catch (Exception ex) {
                                genBubble.contentArea.append("\n\n⚠ 视频加载失败：" + ex.getMessage());
                                genBubble.recalcContentSize();
                                genBubble.revalidateAndRepaint();
                            }
                        } else {
                            genBubble.contentArea.append("\n\n⚠ 未获取到视频 URL");
                            genBubble.recalcContentSize();
                            genBubble.revalidateAndRepaint();
                        }

                        int newH3 = genBubble.getPreferredSize().height + 4;
                        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, newH3));
                        wrapper.revalidate();
                        wrapper.repaint();
                        chatMessagePanel.revalidate();
                        chatMessagePanel.repaint();
                        scrollToBottom();
                    });
                },
                error -> {
                    // 失败
                    SwingUtilities.invokeLater(() -> {
                        genBubble.contentArea.setText("[失败] 视频生成失败"
                                + "\n\nPrompt：" + prompt
                                + "\n\n错误：" + error.getMessage());
                        genBubble.recalcContentSize();
                        genBubble.revalidateAndRepaint();
                        int newH4 = genBubble.getPreferredSize().height + 4;
                        wrapper.setMaximumSize(new Dimension(Integer.MAX_VALUE, newH4));
                        wrapper.revalidate();
                        wrapper.repaint();
                        chatMessagePanel.revalidate();
                        chatMessagePanel.repaint();
                        scrollToBottom();
                    });
                }
        );
    }

    /** 构建视频生成请求 */
    private AgnesVideoRequest buildVideoRequest(String mode, String prompt,
                                                 int width, int height, int numFrames, double fps,
                                                 Integer seed, List<String> imageUrls) {
        AgnesVideoRequest req = new AgnesVideoRequest()
                .model("agnes-video-v2.0")
                .prompt(prompt)
                .size(width, height)
                .numFrames(numFrames)
                .frameRate(fps);

        if (seed != null) {
            req.seed(seed);
        }

        switch (mode) {
            case "文生视频" -> { /* 无需额外参数 */ }
            case "图生视频（输入URL）" -> {
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    req.image(imageUrls.getFirst());
                }
            }
            case "多图视频" -> {
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    req.extraImages(imageUrls);
                }
            }
            case "关键帧动画" -> {
                if (imageUrls != null && !imageUrls.isEmpty()) {
                    req.extraImages(imageUrls).keyframesMode();
                }
            }
        }

        return req;
    }

    // ==================== 配置持久化 ====================
    @Override
    public void loadConfig(ConfigManager config) {
        // 加载 Agnes Image API Key
        agnesApiKey = config.get("ai.image.agnes.key", "");
        imageService = null; // 重置服务，下次从新 key 创建
        videoService = null; // 重置视频服务

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

        // 保存 Agnes Image API Key
        if (agnesApiKey != null && !agnesApiKey.isBlank()) {
            config.set("ai.image.agnes.key", agnesApiKey);
        }
    }
}
