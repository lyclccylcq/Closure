package app.ui.chat;

import app.model.ChatMessage;
import app.model.MessageRole;

import javax.swing.*;
import java.awt.*;

public class MessageListPanel extends JScrollPane {
    private final JPanel messageContainer;

    public MessageListPanel() {
        messageContainer = new JPanel();


        //竖直方向依次排列
        messageContainer.setLayout(new BoxLayout(messageContainer,BoxLayout.Y_AXIS));

        //设置消息列表背景色
        messageContainer.setBackground(new Color(245,245,245));

        setViewportView(messageContainer);

        //去掉面板的滚动边框
        setBorder(null);

        getVerticalScrollBar().setUnitIncrement(16);
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
}
