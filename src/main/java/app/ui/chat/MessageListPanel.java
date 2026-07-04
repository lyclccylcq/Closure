package app.ui.chat;

import app.model.ChatMessage;
import app.model.MessageRole;

import javax.swing.*;
import java.awt.*;

public class MessageListPanel extends JScrollPane {

    private static final Color BACKGROUND_COLOR=new Color(8,12,18);
    private static final Color SCROLLBAR_COLOR=new Color(36,52,68);
    private final JPanel messageContainer;

    public MessageListPanel() {
        messageContainer = new JPanel();


        //竖直方向依次排列
        messageContainer.setLayout(new BoxLayout(messageContainer,BoxLayout.Y_AXIS));

        //设置消息列表背景色
       messageContainer.setBackground(BACKGROUND_COLOR);

        setViewportView(messageContainer);
        getViewport().setBackground(BACKGROUND_COLOR);

        //去掉面板的滚动边框
        setBorder(null);
        setBackground(BACKGROUND_COLOR);

        getVerticalScrollBar().setUnitIncrement(16);
        getVerticalScrollBar().setBackground(BACKGROUND_COLOR);
        getVerticalScrollBar().setForeground(SCROLLBAR_COLOR);
    }

    public void addMessage(ChatMessage message){
        MessageItemPanel messageItemPanel=new MessageItemPanel(message);

        //把消息组件放进消息容器
        messageContainer.add(messageItemPanel);

        //消息之间的间距
        messageContainer.add(Box.createVerticalStrut(8));

        //刷新界面
        refresh();

        //默认滚到底部
        scrollToBottom();
    }

    //刷新消息界面
    private void refresh(){
        //重新计算组件
        messageContainer.revalidate();

        //更新页面
        messageContainer.repaint();
    }

    private void scrollToBottom() {
        /*
         * SwingUtilities.invokeLater 的意思是：
         * 因为刚 add 新消息时，滚动条的最大值可能还没来得及更新。
         * 如果立刻设置滚动条位置，可能滚不到真正的底部。
         */
        SwingUtilities.invokeLater(() -> {
            // 获取垂直滚动条
            JScrollBar verticalBar = getVerticalScrollBar();

            // 把滚动条位置设置到最大值，也就是最底部
            verticalBar.setValue(verticalBar.getMaximum());
        });
    }
}
