package com.szh.ui.panel;

import com.szh.manager.ConfigManager;
import com.szh.ui.MainFrame;
import com.szh.utils.NetUtil;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import java.awt.*;
import java.util.function.Consumer;

/**
 * 所有指令面板的基类
 */
public abstract class AbstractCommandPanel extends JPanel {

    protected final Consumer<String> sendCmd;

    public AbstractCommandPanel(Consumer<String> sendCmd) {
        this.sendCmd = sendCmd;
        setLayout(new BorderLayout());
        setBorder(new EmptyBorder(10, 10, 10, 10));
        initPanel();
    }

    protected abstract void initPanel();

    /** 创建统一风格的表单面板 */
    protected JPanel createFormPanel() {
        JPanel panel = new JPanel(new GridBagLayout());
        panel.setBorder(new EmptyBorder(6, 6, 6, 6));
        return panel;
    }

    /** 表单约束 */
    protected GridBagConstraints gbc(int x, int y) {
        GridBagConstraints c = new GridBagConstraints();
        c.gridx = x;
        c.gridy = y;
        c.insets = new Insets(5, 8, 5, 8);
        c.fill = GridBagConstraints.HORIZONTAL;
        c.anchor = GridBagConstraints.WEST;
        return c;
    }

    protected GridBagConstraints gbc(int x, int y, int w) {
        GridBagConstraints c = gbc(x, y);
        c.gridwidth = w;
        return c;
    }

    /** 创建精致小巧的发送按钮 */
    protected JButton createSendButton(String text) {
        JButton btn = new JButton(text);
        btn.setFont(new Font("Microsoft YaHei", Font.BOLD, 12));
        btn.setFocusPainted(false);
        btn.setBorder(BorderFactory.createEmptyBorder(4, 14, 4, 14));
        btn.setCursor(new Cursor(Cursor.HAND_CURSOR));
        return btn;
    }

    /** 创建小巧输入框 */
    protected JTextField createTextField(String defaultVal, int cols) {
        JTextField f = new JTextField(defaultVal, cols);
        f.setPreferredSize(new Dimension(cols * 9, 24));
        NetUtil.fixPaste(f);
        return f;
    }

    /** 整型输入框 */
    protected JTextField createIntField(String defaultVal) {
        return createTextField(defaultVal, 8);
    }

    /** 浮点输入框 */
    protected JTextField createDoubleField(String defaultVal) {
        return createTextField(defaultVal, 10);
    }

    /** 字符串输入框 */
    protected JTextField createStrField(String defaultVal) {
        return createTextField(defaultVal, 14);
    }

    /** 创建带标签的滚动面板 */
    protected JScrollPane createScrollPane(JComponent content) {
        JScrollPane sp = new JScrollPane(content);
        sp.setBorder(BorderFactory.createEmptyBorder());
        sp.getVerticalScrollBar().setUnitIncrement(16);
        MainFrame.enableSmoothScrolling(sp);
        return sp;
    }

    /** 子类可覆盖：从配置加载字段值 */
    public void loadConfig(ConfigManager config) {}

    /** 子类可覆盖：将字段值保存到配置 */
    public void saveConfig(ConfigManager config) {}
}
