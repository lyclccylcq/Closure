package app.ui.chat;

import app.model.ChatMessage;
import app.model.MessageRole;

import javax.swing.*;
import java.awt.*;

public class MessageItemPanel extends JPanel {

    private static final int AVATAR_SIZE=40;
    private static final int MESSAGE_WIDTH=260;

    public MessageItemPanel(ChatMessage message){
        MessageRole role=message.getRole();
        boolean isUser= role.isUser();

        setLayout(new BorderLayout(8,0));

        //设置背景色和间距
        setBackground(new Color(245,245,245));
        setBorder(BorderFactory.createEmptyBorder(8,8,0,8));

        JLabel avatarLabel=createAvatarLable(role);
        JPanel contentPanel=createContentPanel(message);

        //设定头像和消息布局位置
        if(isUser){
            add(contentPanel,BorderLayout.CENTER);
            add(avatarLabel,BorderLayout.EAST);
        }else{
            add(contentPanel,BorderLayout.CENTER);
            add(avatarLabel,BorderLayout.WEST);
        }
    }

    private JLabel createAvatarLable(MessageRole role){
        ImageIcon avatar=ImageLoader.loadIcon(
                role.getAvatarPath(),
                AVATAR_SIZE,
                AVATAR_SIZE
        );

        JLabel avatarLabel=new JLabel(avatar);
        avatarLabel.setVerticalAlignment(SwingConstants.TOP);

        return avatarLabel;
    }

    private JPanel createContentPanel(ChatMessage message){
        //获取消息发送者身份
        MessageRole role=message.getRole();

        //判断是不是用户消息
        boolean isUser=role.isUser();

        JPanel contentPanel=new JPanel();

        contentPanel.setLayout(new BoxLayout(contentPanel,BoxLayout.Y_AXIS));
        // 内容区域背景和外层背景保持一致
        contentPanel.setBackground(new Color(245, 245, 245));

        // 创建名字标签
        JLabel nameLabel = new JLabel(role.getDisplayName());

        // 设置名字字体：无衬线、加粗、12号
        nameLabel.setFont(new Font("SansSerif", Font.BOLD, 12));

        // 创建消息文本区域
        JTextArea messageArea = new JTextArea(message.getContent());

        // 消息内容不允许用户编辑
        messageArea.setEditable(false);

        // 开启自动换行
        messageArea.setLineWrap(true);

        // 按单词边界换行，避免英文单词被硬拆开
        messageArea.setWrapStyleWord(true);

        // 设置消息文字字体
        messageArea.setFont(new Font("SansSerif", Font.PLAIN, 14));

        // 设置消息文本的内边距
        // 这样文字不会紧贴气泡边缘
        messageArea.setBorder(BorderFactory.createEmptyBorder(8, 10, 8, 10));

        /*
         * 根据发送者设置不同的消息背景色。
         *
         * 用户消息：浅蓝色
         * 可露希尔消息：白色
         */
        if (isUser) {
            messageArea.setBackground(new Color(220, 240, 255));
        } else {
            messageArea.setBackground(Color.WHITE);
        }

        // 限制消息区域最大宽度
        // 防止一行文字太长，把整个窗口撑开
        messageArea.setMaximumSize(new Dimension(MESSAGE_WIDTH, Integer.MAX_VALUE));

        // 把名字加入内容区域
        contentPanel.add(nameLabel);

        // 名字和消息内容之间留一点空隙
        contentPanel.add(Box.createVerticalStrut(4));

        // 把消息文本加入内容区域
        contentPanel.add(messageArea);

        return contentPanel;
    }
    }

