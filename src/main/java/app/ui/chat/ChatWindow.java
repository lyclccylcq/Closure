package app.ui.chat;

import app.model.ChatMessage;
import app.model.MessageRole;
import app.service.ChatService;

import javax.swing.*;
import java.awt.*;

public class ChatWindow extends JFrame {

    /**
     * 消息列表区域。
     */
    private final MessageListPanel messageListPanel;

    /**
     * 底部输入区域。
     */
    private final ChatInputPanel chatInputPanel;

    /**
     * 聊天服务。
     */
    private final ChatService chatService;

    /**
     * 构造方法：创建聊天窗口。
     */
    public ChatWindow() {
        // 创建聊天服务对象
        chatService = new ChatService();

        // 设置窗口标题
        setTitle("通讯频道：可露希尔");
        getContentPane().setBackground(new Color(8,12,18));

        // 设置聊天窗口大小
        setSize(420, 520);

        // 如果外部没有重新设置位置，则默认居中
        setLocationRelativeTo(null);

        /*
         * 关闭聊天窗口时只隐藏窗口，不退出整个程序。
         */
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        // 初始化消息列表区域
        messageListPanel = new MessageListPanel();

        // 初始化底部输入区域
        chatInputPanel = new ChatInputPanel();

        /*
         * 设置聊天窗口整体布局。
         */
        setLayout(new BorderLayout());

        // 添加消息列表到窗口中间
        add(messageListPanel, BorderLayout.CENTER);

        // 添加输入区域到窗口底部
        add(chatInputPanel, BorderLayout.SOUTH);

        /*
         * 设置输入区域的发送回调。
         */
        chatInputPanel.setOnSendMessage(this::handleUserMessage);

        // 聊天窗口打开时，先显示一句可露希尔的欢迎语
        addPetMessage("给，咖啡。看你那黑眼圈，昨晚又熬夜了吧？");
    }

    /**
     * 处理用户发送的消息。
     */
    private void handleUserMessage(String userText) {
        // 先显示用户消息
        addUserMessage(userText);

        // 通过 ChatService 生成可露希尔回复
        String reply = chatService.getReply(userText);

        // 显示可露希尔回复
        addPetMessage(reply);
    }

    /**
     * 添加一条用户消息。
     */
    private void addUserMessage(String content) {
        /*
         * 创建一条 ChatMessage 数据。
         */
        ChatMessage message = new ChatMessage(MessageRole.USER, content);

        // 把消息交给消息列表显示
        messageListPanel.addMessage(message);
    }

    /**
     * 添加一条可露希尔消息.
     */
    private void addPetMessage(String content) {
        /*
         * 创建一条 ChatMessage 数据。
         *
         * MessageRole.CLOSURE 表示这条消息是可露希尔发的。
         */
        ChatMessage message = new ChatMessage(MessageRole.CLOSURE, content);

        // 把消息交给消息列表显示
        messageListPanel.addMessage(message);
    }
}