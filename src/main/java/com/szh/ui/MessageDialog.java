package com.szh.ui;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 全局单例消息弹出框 —— 仿 ElementPlus ElMessage 风格。
 * <p>
 * 用法示例：
 * <pre>
 *   // 默认顶部居中弹出
 *   MessageDialog.success("操作成功");
 *   MessageDialog.info("这是一条信息");
 *   MessageDialog.warning("请注意检查");
 *   MessageDialog.error("操作失败");
 *
 *   // 指定弹出位置
 *   MessageDialog.getInstance().setPlacement(MessageDialog.Placement.BOTTOM);
 *   MessageDialog.info("从底部弹出");
 *
 *   // 完全自定义
 *   MessageDialog.getInstance().show("内容", MessageDialog.Type.INFO,
 *       myIcon, 5000, MessageDialog.Placement.TOP_RIGHT);
 * </pre>
 * </p>
 *
 * <h3>特性</h3>
 * <ul>
 *   <li>全局单例 / 静态便捷调用</li>
 *   <li>自带防抖：相同内容 400ms 内不重复弹出</li>
 *   <li>多条消息垂直堆叠（上限 5 条 / 放置位）</li>
 *   <li>每条消息独立进出动画（滑入 + 淡入 / 反向滑出 + 淡出）</li>
 *   <li>鼠标移入暂停自动消失，移出恢复倒计时</li>
 *   <li>支持 7 个弹出位置（顶部/底部/左上/右上/左下/右下/居中）</li>
 *   <li>支持自定义图标（可选，缺省绘 SVG 风格图标）</li>
 *   <li>自适应暗色主题，与项目 FlatLaf 风格统一</li>
 *   <li>宿主窗口移动/缩放时自动重定位</li>
 * </ul>
 *
 * @author szh
 */
public final class MessageDialog {

    // ==================== 单例 ====================

    private static volatile MessageDialog instance;

    private MessageDialog() {}

    public static MessageDialog getInstance() {
        if (instance == null) {
            synchronized (MessageDialog.class) {
                if (instance == null) {
                    instance = new MessageDialog();
                }
            }
        }
        return instance;
    }

    // ==================== 静态便捷方法（使用当前默认位置） ====================

    public static void success(String content) {
        getInstance().showInternal(content, Type.SUCCESS, null, DURATION_NORMAL, getInstance().defaultPlacement);
    }

    public static void info(String content) {
        getInstance().showInternal(content, Type.INFO, null, DURATION_NORMAL, getInstance().defaultPlacement);
    }

    public static void warning(String content) {
        getInstance().showInternal(content, Type.WARNING, null, DURATION_NORMAL, getInstance().defaultPlacement);
    }

    public static void error(String content) {
        getInstance().showInternal(content, Type.ERROR, null, DURATION_LONG, getInstance().defaultPlacement);
    }

    // ==================== 公开 API ====================

    /** 注册宿主 Frame，用于定位。需在 MainFrame 初始化后调用一次。 */
    public void registerOwner(JFrame frame) {
        this.ownerFrame = frame;
        if (frame != null) {
            frame.addWindowListener(new java.awt.event.WindowAdapter() {
                @Override
                public void windowDeiconified(java.awt.event.WindowEvent e) {
                    repositionAllContainers();
                }
            });
            frame.addComponentListener(new java.awt.event.ComponentAdapter() {
                @Override
                public void componentMoved(java.awt.event.ComponentEvent e) {
                    repositionAllContainers();
                }
                @Override
                public void componentResized(java.awt.event.ComponentEvent e) {
                    repositionAllContainers();
                }
            });
        }
    }

    /** 设置默认弹出位置（影响所有静态便捷方法）。 */
    public void setPlacement(Placement p) {
        this.defaultPlacement = p;
    }

    /** 获取当前默认弹出位置。 */
    public Placement getPlacement() {
        return defaultPlacement;
    }

    /**
     * 完整自定义调用（使用默认位置）。
     *
     * @param content    消息文本
     * @param type       消息类型/颜色
     * @param icon       自定义图标，null 则自动绘制默认图标
     * @param durationMs 显示时长（毫秒），不含动画时间
     */
    public void show(String content, Type type, Icon icon, int durationMs) {
        showInternal(content, type, icon, durationMs, defaultPlacement);
    }

    /**
     * 完整自定义调用（指定位置）。
     *
     * @param content    消息文本
     * @param type       消息类型/颜色
     * @param icon       自定义图标，null 则自动绘制默认图标
     * @param durationMs 显示时长（毫秒），不含动画时间
     * @param placement  弹出位置
     */
    public void show(String content, Type type, Icon icon, int durationMs, Placement placement) {
        showInternal(content, type, icon, durationMs, placement);
    }

    // ==================== 枚举 ====================

    public enum Type {
        SUCCESS(new Color(0x67, 0xC2, 0x3A)),
        INFO(   new Color(0x40, 0x9E, 0xFF)),
        WARNING(new Color(0xE6, 0xA2, 0x3C)),
        ERROR(  new Color(0xF5, 0x6C, 0x6C));

        final Color primary;

        Type(Color primary) { this.primary = primary; }
    }

    /** 弹出位置 */
    public enum Placement {
        TOP, TOP_LEFT, TOP_RIGHT,
        BOTTOM, BOTTOM_LEFT, BOTTOM_RIGHT,
        CENTER
    }

    // ==================== 常量 ====================

    private static final int DURATION_NORMAL       = 3000;
    private static final int DURATION_LONG         = 5000;
    private static final long DEBOUNCE_MS          = 400;
    private static final int MAX_VISIBLE           = 5;
    private static final int ENTRY_DURATION        = 280;  // ms
    private static final int EXIT_DURATION         = 200;  // ms
    private static final int MOVE_DURATION         = 200;  // 堆叠移动
    private static final int GAP                   = 10;
    private static final int EDGE_OFFSET_TOP       = 88;   // 距宿主顶部偏移
    private static final int EDGE_OFFSET_BOTTOM    = 52;   // 距宿主底部偏移
    private static final int EDGE_OFFSET_SIDE      = 52;   // 距宿主左右偏移
    private static final int SCREEN_EDGE_OFFSET    = 30;   // 无宿主时距屏幕边缘
    private static final int SLIDE_DIST            = 30;   // 滑入/滑出距离

    // 暗色主题配色
    private static final Color PANEL_BG    = new Color(0x2B, 0x2B, 0x2B, 240);
    private static final Color TEXT_COLOR  = new Color(0xE0, 0xE0, 0xE0);
    private static final Color SHADOW_COLOR = new Color(0, 0, 0, 80);
    private static final Color BORDER_COLOR = new Color(0x50, 0x50, 0x50, 180);

    // ==================== 实例字段 ====================

    private JFrame ownerFrame;
    private Placement defaultPlacement = Placement.TOP;
    private final Map<Placement, ContainerState> containers = new EnumMap<>(Placement.class);
    private final Map<String, Long> debounceMap = new ConcurrentHashMap<>();

    // ==================== 内部实现 ====================

    private void showInternal(String content, Type type, Icon icon, int durationMs, Placement placement) {
        if (content == null || content.isEmpty()) return;

        // 防抖（content + type + placement 作为联合 key）
        String key = content.hashCode() + ":" + type.name() + ":" + placement.name();
        long now = System.currentTimeMillis();
        Long last = debounceMap.put(key, now);
        if (last != null && now - last < DEBOUNCE_MS) {
            debounceMap.put(key, last);
            return;
        }

        SwingUtilities.invokeLater(() -> {
            ContainerState cs = getOrCreateContainer(placement);
            if (cs.items.size() >= MAX_VISIBLE) {
                cs.items.get(0).dismiss(true);
            }
            MessageItem item = new MessageItem(content, type, icon, durationMs, placement, cs);
            cs.items.add(item);
            relayoutContainer(cs);
            item.showEntry();
        });
    }

    // --- 容器管理 ---

    private static class ContainerState {
        final Placement placement;
        JWindow window;
        final List<MessageItem> items = new ArrayList<>();
        JLayeredPane layered;

        ContainerState(Placement p) { this.placement = p; }

        void ensureWindow(JFrame owner) {
            if (window != null && window.isDisplayable()) return;
            window = new JWindow(owner);
            window.setBackground(new Color(0, 0, 0, 0));
            window.getRootPane().setOpaque(false);
            window.setAlwaysOnTop(true);
            window.setFocusableWindowState(false);
            layered = new JLayeredPane();
            layered.setOpaque(false);
            layered.setLayout(null);
            window.setContentPane(layered);
        }
    }

    private ContainerState getOrCreateContainer(Placement p) {
        ContainerState cs = containers.get(p);
        if (cs == null) {
            cs = new ContainerState(p);
            containers.put(p, cs);
        }
        cs.ensureWindow(ownerFrame);
        return cs;
    }

    // --- 布局 ---

    private void relayoutContainer(ContainerState cs) {
        if (cs.items.isEmpty()) {
            hideContainer(cs);
            return;
        }
        int containerW = calcContainerWidth(cs);
        int containerH = calcTotalHeight(cs);
        Point pos = calcPosition(cs.placement, containerW, containerH);
        cs.window.setBounds(pos.x, pos.y, containerW, containerH);

        int y = 0;
        for (MessageItem item : cs.items) {
            item.animateToY(y);
            y += item.getHeight() + GAP;
        }

        cs.window.setVisible(true);
        cs.window.repaint();
    }

    private void hideContainer(ContainerState cs) {
        Timer t = new Timer(100, e -> {
            if (cs.items.isEmpty() && cs.window != null) {
                cs.window.setVisible(false);
                cs.window.dispose();
                cs.window = null;
                containers.remove(cs.placement);
            }
        });
        t.setRepeats(false);
        t.start();
    }

    private void dismissItem(MessageItem item) {
        ContainerState cs = item.containerState;
        cs.items.remove(item);
        if (cs.items.isEmpty()) {
            hideContainer(cs);
        } else {
            relayoutContainer(cs);
        }
    }

    private int calcContainerWidth(ContainerState cs) {
        int maxW = 360;
        for (MessageItem item : cs.items) {
            maxW = Math.max(maxW, item.getPreferredWidth());
        }
        return Math.min(maxW + 40, 520);
    }

    private int calcTotalHeight(ContainerState cs) {
        int h = 0;
        for (MessageItem item : cs.items) {
            h += item.getHeight() + GAP;
        }
        if (h > 0) h -= GAP;
        return Math.max(h, 10);
    }

    /** 根据 placement 计算容器左上角屏幕坐标 */
    private Point calcPosition(Placement p, int w, int h) {
        Rectangle owner = getOwnerBounds();
        boolean hasOwner = ownerFrame != null;
        int topOffset    = hasOwner ? EDGE_OFFSET_TOP    : SCREEN_EDGE_OFFSET;
        int bottomOffset = hasOwner ? EDGE_OFFSET_BOTTOM : SCREEN_EDGE_OFFSET;
        int sideOffset   = hasOwner ? EDGE_OFFSET_SIDE   : SCREEN_EDGE_OFFSET;

        return switch (p) {
            case TOP         -> new Point(owner.x + (owner.width - w) / 2,            owner.y + topOffset);
            case TOP_LEFT    -> new Point(owner.x + sideOffset,                        owner.y + topOffset);
            case TOP_RIGHT   -> new Point(owner.x + owner.width - w - sideOffset,      owner.y + topOffset);
            case BOTTOM      -> new Point(owner.x + (owner.width - w) / 2,            owner.y + owner.height - h - bottomOffset);
            case BOTTOM_LEFT -> new Point(owner.x + sideOffset,                        owner.y + owner.height - h - bottomOffset);
            case BOTTOM_RIGHT-> new Point(owner.x + owner.width - w - sideOffset,      owner.y + owner.height - h - bottomOffset);
            case CENTER      -> new Point(owner.x + (owner.width - w) / 2,            owner.y + (owner.height - h) / 2);
        };
    }

    private Rectangle getOwnerBounds() {
        if (ownerFrame != null && ownerFrame.isShowing()) {
            Point loc = ownerFrame.getLocationOnScreen();
            return new Rectangle(loc.x, loc.y, ownerFrame.getWidth(), ownerFrame.getHeight());
        }
        Dimension screen = Toolkit.getDefaultToolkit().getScreenSize();
        return new Rectangle(0, 0, screen.width, screen.height);
    }

    private void repositionAllContainers() {
        SwingUtilities.invokeLater(() -> {
            for (ContainerState cs : containers.values()) {
                relayoutContainer(cs);
            }
        });
    }

    // ==================== 消息条目内部类 ====================

    private class MessageItem {

        final String content;
        final Type type;
        final Icon icon;
        final int durationMs;
        final Placement placement;
        final ContainerState containerState;
        final JPanel panel;
        final JLayeredPane parent;

        int targetY;
        Timer entryTimer, exitTimer, dismissTimer, moveTimer;
        float alpha = 0f;
        int currentY;

        // hover 暂停倒计时相关
        long dismissStartedAt;      // 启动 dismissTimer 时的 System.currentTimeMillis
        long totalPausedTime = 0;   // 累计暂停时长
        Long pauseBeganAt;          // 本次暂停开始时间
        boolean dismissRunning = false;

        MessageItem(String content, Type type, Icon icon, int durationMs,
                    Placement placement, ContainerState cs) {
            this.content = content;
            this.type = type;
            this.icon = icon;
            this.durationMs = durationMs;
            this.placement = placement;
            this.containerState = cs;
            this.panel = createPanel();
            this.parent = cs.layered;
            this.panel.setSize(panel.getPreferredSize());
            parent.add(panel, JLayeredPane.PALETTE_LAYER);
        }

        // ---- 面板绘制（支持 alpha 淡入淡出） ----

        private JPanel createPanel() {
            JPanel p = new JPanel(null) {
                @Override
                protected void paintComponent(Graphics g) {
                    Graphics2D g2 = (Graphics2D) g.create();
                    g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                    int w = getWidth(), h = getHeight();
                    int arc = 14;

                    // 阴影
                    Shape shadowShape = new RoundRectangle2D.Float(2, 3, w - 4, h - 4, arc, arc);
                    g2.setColor(SHADOW_COLOR);
                    g2.fill(shadowShape);

                    // 背景
                    Shape bgShape = new RoundRectangle2D.Float(0, 0, w, h - 2, arc, arc);
                    float a = Math.min(1f, Math.max(0f, alpha));
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                    g2.setColor(PANEL_BG);
                    g2.fill(bgShape);

                    // 边框
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a * 0.6f));
                    g2.setColor(BORDER_COLOR);
                    g2.setStroke(new BasicStroke(1f));
                    g2.draw(bgShape);

                    // 左侧色条
                    int barW = 4;
                    Shape bar = new RoundRectangle2D.Float(barW, 6, barW, h - 14, barW, barW);
                    g2.setColor(type.primary);
                    g2.fill(bar);

                    // 图标
                    Icon displayIcon = getDisplayIcon();
                    if (displayIcon != null) {
                        int iconX = 22;
                        int iconY = (h - 2 - displayIcon.getIconHeight()) / 2;
                        g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                        displayIcon.paintIcon(this, g2, iconX, iconY);
                    }

                    // 文字
                    g2.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER, a));
                    g2.setFont(new Font("Microsoft YaHei", Font.PLAIN, 13));
                    FontMetrics fm = g2.getFontMetrics();
                    int textX = displayIcon != null ? 50 : 28;
                    int textY = (h - 2 - fm.getHeight()) / 2 + fm.getAscent();
                    g2.setColor(TEXT_COLOR);
                    drawMultilineText(g2, content, textX, textY, w - textX - 16, fm);

                    g2.dispose();
                }

                @Override
                public Dimension getPreferredSize() {
                    Icon displayIcon = getDisplayIcon();
                    int iconW = displayIcon != null ? 28 : 0;
                    Font font = new Font("Microsoft YaHei", Font.PLAIN, 13);
                    FontMetrics fm = getFontMetrics(font);
                    int maxWidth = Math.min(520, getToolkit().getScreenSize().width - 100);
                    int textW = measureTextWidth(fm, content, maxWidth - iconW - 60);
                    int w = Math.max(160, textW + iconW + 60);
                    int textH = calcTextHeight(fm, content, w - iconW - 60);
                    int h = Math.max(textH, 20) + 28;
                    return new Dimension(w, h + 2);
                }

                // 折行绘制
                private void drawMultilineText(Graphics2D g2, String text, int x, int y, int maxW, FontMetrics fm) {
                    if (fm.stringWidth(text) <= maxW) {
                        g2.drawString(text, x, y);
                        return;
                    }
                    StringBuilder line = new StringBuilder();
                    int lineY = y;
                    for (int i = 0; i < text.length(); i++) {
                        char ch = text.charAt(i);
                        if (fm.stringWidth(line.toString() + ch) > maxW && line.length() > 0) {
                            g2.drawString(line.toString(), x, lineY);
                            lineY += fm.getHeight();
                            line = new StringBuilder();
                        }
                        line.append(ch);
                    }
                    if (line.length() > 0) g2.drawString(line.toString(), x, lineY);
                }

                private int measureTextWidth(FontMetrics fm, String text, int maxW) {
                    int longest = 0, curW = 0;
                    for (int i = 0; i < text.length(); i++) {
                        int cw = fm.charWidth(text.charAt(i));
                        if (curW + cw > maxW) { longest = Math.max(longest, curW); curW = 0; }
                        curW += cw;
                    }
                    longest = Math.max(longest, curW);
                    return Math.min(maxW, longest + 20);
                }

                private int calcTextHeight(FontMetrics fm, String text, int maxW) {
                    int lines = 1, curW = 0;
                    for (int i = 0; i < text.length(); i++) {
                        int cw = fm.charWidth(text.charAt(i));
                        if (curW + cw > maxW) { lines++; curW = 0; }
                        curW += cw;
                    }
                    return lines * fm.getHeight();
                }
            };
            p.setOpaque(false);

            // 鼠标移入暂停，移出恢复
            p.addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) { onMouseEnter(); }
                @Override
                public void mouseExited(MouseEvent e) { onMouseLeave(); }
            });

            return p;
        }

        private Icon getDisplayIcon() {
            if (icon != null) return icon;
            return createDefaultIcon(type);
        }

        int getHeight() { return panel.getPreferredSize().height; }
        int getPreferredWidth() { return panel.getPreferredSize().width; }

        // ==================== Hover 暂停/恢复 ====================

        void onMouseEnter() {
            if (!dismissRunning) return;
            pauseBeganAt = System.currentTimeMillis();
            if (dismissTimer != null) dismissTimer.stop();
        }

        void onMouseLeave() {
            if (!dismissRunning) return;
            if (pauseBeganAt != null) {
                totalPausedTime += System.currentTimeMillis() - pauseBeganAt;
                pauseBeganAt = null;
            }
            // 计算剩余时间
            long elapsed = System.currentTimeMillis() - dismissStartedAt - totalPausedTime;
            long remaining = durationMs - elapsed;
            if (remaining <= 0) {
                dismiss(false);
                return;
            }
            if (dismissTimer != null) {
                dismissTimer.setInitialDelay((int) remaining);
                dismissTimer.restart();
            }
        }

        // ==================== 动画 ====================

        /** 入场动画：根据 placement 从对应方向滑入 */
        void showEntry() {
            int endY = targetY;
            alpha = 0f;

            // 根据位置决定滑入方向
            boolean fromTop = isTopPlacement(placement);
            int slideOffset = (placement == Placement.CENTER) ? 0 : SLIDE_DIST;
            currentY = fromTop ? (endY - slideOffset) : (endY + slideOffset);
            if (placement == Placement.CENTER) currentY = endY;

            panel.setLocation(0, currentY);
            panel.repaint();

            entryTimer = new Timer(16, null);
            final long startTime = System.currentTimeMillis();
            final int startY = currentY;
            entryTimer.addActionListener(e -> {
                long elapsed = System.currentTimeMillis() - startTime;
                float progress = Math.min(1f, (float) elapsed / ENTRY_DURATION);
                // ease-out cubic
                float eased = 1f - (1f - progress) * (1f - progress) * (1f - progress);
                alpha = eased;
                currentY = (int) (startY + (endY - startY) * eased);
                panel.setLocation(0, currentY);
                panel.repaint();
                if (progress >= 1f) {
                    entryTimer.stop();
                    alpha = 1f;
                    currentY = endY;
                    panel.setLocation(0, currentY);
                    panel.repaint();
                    startAutoDismiss();
                }
            });
            entryTimer.start();
        }

        private void startAutoDismiss() {
            dismissRunning = true;
            dismissStartedAt = System.currentTimeMillis();
            totalPausedTime = 0;
            dismissTimer = new Timer(durationMs, e -> dismiss(false));
            dismissTimer.setRepeats(false);
            dismissTimer.start();
        }

        /** 出场动画：根据 placement 向对应方向滑出 */
        void dismiss(boolean immediate) {
            dismissRunning = false;
            if (entryTimer != null) entryTimer.stop();
            if (dismissTimer != null) dismissTimer.stop();
            pauseBeganAt = null;

            if (immediate) {
                removePanel();
                return;
            }

            boolean up = isTopPlacement(placement);
            int slideOffset = (placement == Placement.CENTER) ? 0 : SLIDE_DIST;
            final int endY = up ? (targetY - slideOffset) : (targetY + slideOffset);
            if (placement == Placement.CENTER) {
                // 居中只淡出
                exitFadeOnly();
                return;
            }

            exitTimer = new Timer(16, null);
            final long startTime = System.currentTimeMillis();
            final float startAlpha = alpha;
            final int startY = currentY;
            exitTimer.addActionListener(e -> {
                long elapsed = System.currentTimeMillis() - startTime;
                float progress = Math.min(1f, (float) elapsed / EXIT_DURATION);
                float eased = progress * progress; // ease-in quadratic
                alpha = startAlpha * (1f - eased);
                currentY = (int) (startY + (endY - startY) * eased);
                panel.setLocation(0, currentY);
                panel.repaint();
                if (progress >= 1f) {
                    exitTimer.stop();
                    removePanel();
                }
            });
            exitTimer.start();
        }

        private void exitFadeOnly() {
            exitTimer = new Timer(16, null);
            final long startTime = System.currentTimeMillis();
            final float startAlpha = alpha;
            exitTimer.addActionListener(e -> {
                long elapsed = System.currentTimeMillis() - startTime;
                float progress = Math.min(1f, (float) elapsed / EXIT_DURATION);
                float eased = progress * progress;
                alpha = startAlpha * (1f - eased);
                panel.repaint();
                if (progress >= 1f) {
                    exitTimer.stop();
                    removePanel();
                }
            });
            exitTimer.start();
        }

        private void removePanel() {
            parent.remove(panel);
            parent.repaint();
            SwingUtilities.invokeLater(() -> dismissItem(this));
        }

        /** 堆叠移动（同伴消失时调整位置） */
        void animateToY(int newY) {
            this.targetY = newY;
            if (entryTimer != null && entryTimer.isRunning()) return;

            if (moveTimer != null && moveTimer.isRunning()) {
                moveTimer.stop();
            }

            final int startY = currentY;
            moveTimer = new Timer(16, null);
            final long startTime = System.currentTimeMillis();
            moveTimer.addActionListener(e -> {
                long elapsed = System.currentTimeMillis() - startTime;
                float progress = Math.min(1f, (float) elapsed / MOVE_DURATION);
                float eased = 1f - (1f - progress) * (1f - progress);
                currentY = (int) (startY + (newY - startY) * eased);
                panel.setLocation(0, currentY);
                if (progress >= 1f) {
                    moveTimer.stop();
                    currentY = newY;
                    panel.setLocation(0, currentY);
                }
            });
            moveTimer.start();
        }

        /** 是否为顶部/上方弹出位置 */
        private static boolean isTopPlacement(Placement p) {
            return p == Placement.TOP || p == Placement.TOP_LEFT || p == Placement.TOP_RIGHT;
        }
    }

    // ==================== 默认图标绘制 ====================

    private static Icon createDefaultIcon(Type type) {
        int size = 20;
        return new Icon() {
            @Override
            public void paintIcon(Component c, Graphics g, int x, int y) {
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
                g2.setColor(type.primary);

                int cx = x + size / 2;
                int cy = y + size / 2;
                int r = size / 2 - 1;
                g2.setStroke(new BasicStroke(2f, BasicStroke.CAP_ROUND, BasicStroke.JOIN_ROUND));

                switch (type) {
                    case SUCCESS -> {
                        g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                        Path2D check = new Path2D.Float();
                        check.moveTo(cx - 5, cy);
                        check.lineTo(cx - 2, cy + 4);
                        check.lineTo(cx + 5, cy - 4);
                        g2.draw(check);
                    }
                    case INFO -> {
                        g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                        g2.setFont(new Font("Dialog", Font.BOLD, 13));
                        FontMetrics fm = g2.getFontMetrics();
                        g2.drawString("i", cx - fm.stringWidth("i") / 2, cy + fm.getAscent() / 2 - 1);
                    }
                    case WARNING -> {
                        Polygon tri = new Polygon();
                        tri.addPoint(cx, cy - r);
                        tri.addPoint(cx - r, cy + r);
                        tri.addPoint(cx + r, cy + r);
                        g2.drawPolygon(tri);
                        g2.setFont(new Font("Dialog", Font.BOLD, 14));
                        FontMetrics fm = g2.getFontMetrics();
                        g2.drawString("!", cx - fm.stringWidth("!") / 2, cy + fm.getAscent() / 2 - 1);
                    }
                    case ERROR -> {
                        g2.drawOval(cx - r, cy - r, r * 2, r * 2);
                        g2.drawLine(cx - 5, cy - 5, cx + 5, cy + 5);
                        g2.drawLine(cx + 5, cy - 5, cx - 5, cy + 5);
                    }
                }
                g2.dispose();
            }
            @Override public int getIconWidth() { return size; }
            @Override public int getIconHeight() { return size; }
        };
    }
}
