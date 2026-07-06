package app.ui.chat;

import app.model.ChatMessage;
import app.model.MessageRole;
import app.util.AvatarLoader;

import javax.swing.*;
import java.awt.*;

public class MessageItemPanel extends JPanel {

    private static final Color BACKGROUND_COLOR=new Color(8,12,18);
    private static final Color BUBBLE_COLOR=new Color(18,27,39);
    private static final Color BORDER_COLOR=new Color(58,174,210);
    private static final Color TEXT_COLOR=new Color(238,246,255);


    private static final int AVATAR_SIZE=40;

    //这是限制文本的最大宽度
    private static final int MESSAGE_MAX_WIDTH=260;
    //限制短消息防止气泡被压成竖排
    private static final int MESSAGE_MIN_WIDTH=96;
    // 短消息按字符数估算宽度，比 JTextArea 的自动换行判断更适合中文。
    private static final int SHORT_MESSAGE_LENGTH=12;
    //内边距
    private static final Insets MESSAGE_INSETS=new Insets(8,10,8,10);


    public MessageItemPanel(ChatMessage message){
        MessageRole role=message.getRole();
        boolean isUser= role.isUser();

        setLayout(new BorderLayout(8,0));

        //设置背景色和间距
        //setBackground(new Color(245,245,245));
        setBackground(BACKGROUND_COLOR);
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
        ImageIcon avatar= AvatarLoader.loadAvatar(
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
        //contentPanel.setBackground(new Color(245, 245, 245));
        contentPanel.setBackground(BACKGROUND_COLOR);

        // 创建名字标签
        JLabel nameLabel = new JLabel(role.getDisplayName());


        // 设置名字字体：无衬线、加粗、12号
        nameLabel.setFont(new Font("SansSerif", Font.BOLD, 12));
        nameLabel.setForeground(TEXT_COLOR);
        nameLabel.setOpaque(false);

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
        messageArea.setForeground(TEXT_COLOR);
        messageArea.setDisabledTextColor(TEXT_COLOR);
        messageArea.setCaretColor(TEXT_COLOR);
        messageArea.setOpaque(true);

        // 设置消息文本的内边距
        // 这样文字不会紧贴气泡边缘
        messageArea.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(BORDER_COLOR,1),
                BorderFactory.createEmptyBorder(
                        MESSAGE_INSETS.top,
                        MESSAGE_INSETS.left,
                        MESSAGE_INSETS.bottom,
                        MESSAGE_INSETS.right
                )
        ));

        /*
         * 根据发送者设置不同的消息背景色。
         *
         * 用户消息：浅蓝色
         * 可露希尔消息：白色
         */
        if (isUser) {
            messageArea.setBackground(BUBBLE_COLOR);
        } else {
           // messageArea.setBackground(Color.WHITE);
            messageArea.setBackground(BUBBLE_COLOR);
        }

        //固定首选尺寸
        Dimension messageSize=calculateMessageSize(messageArea,message.getContent());
        messageArea.setPreferredSize(messageSize);
        messageArea.setMinimumSize(messageSize);
        messageArea.setMaximumSize(messageSize);

        // 把名字加入内容区域
        contentPanel.add(nameLabel);

        // 名字和消息内容之间留一点空隙
        contentPanel.add(Box.createVerticalStrut(4));

        //左右对齐，保持宽度
        JPanel messageRow=new JPanel(new FlowLayout(
                isUser?FlowLayout.RIGHT:FlowLayout.LEFT,
                0,
                0
        ));

        //messageRow.setBackground(new Color(245,245,245));
        messageRow.setBackground(BACKGROUND_COLOR);
        messageRow.add(messageArea);
        contentPanel.add(messageRow);

        nameLabel.setAlignmentX(isUser?Component.RIGHT_ALIGNMENT:Component.LEFT_ALIGNMENT);
        messageRow.setAlignmentX(isUser?Component.RIGHT_ALIGNMENT:Component.LEFT_ALIGNMENT);

        return contentPanel;
    }

    //计算消息的长宽
    private Dimension calculateMessageSize(JTextArea messageArea,String content) {
        FontMetrics fontMetrics = messageArea.getFontMetrics(messageArea.getFont());
        int maxLineWidth = 0;
        int maxLineLength=0;

        //先算没有换行时候的宽度
        for(String line:content.split("\\R",-1)){
            maxLineWidth=Math.max(maxLineWidth,fontMetrics.stringWidth(line));
        }

        //文字宽度不能超过气泡最大宽度扣掉内边距的可用区域
        int maxTextWidth=MESSAGE_MAX_WIDTH-MESSAGE_INSETS.left-MESSAGE_INSETS.right;
        //int textWidth=Math.max(1,Math.min(maxLineWidth+1,maxLineWidth));
        int minTextWidth=MESSAGE_MIN_WIDTH-MESSAGE_INSETS.left-MESSAGE_INSETS.right;
        int contentWidth=maxLineWidth+1;
        if(maxLineLength>0 && maxLineLength<=SHORT_MESSAGE_LENGTH){
            contentWidth=Math.max(contentWidth,fontMetrics.charWidth('测')*maxLineLength);
        }
        int textWidth=Math.max(minTextWidth,Math.min(contentWidth,maxTextWidth));
        int bubbleWidth=textWidth+MESSAGE_INSETS.left+MESSAGE_INSETS.right;

        // 先给定宽度再询问首选高度，让 JTextArea 按换行后的高度返回尺寸。
        messageArea.setSize(new Dimension(bubbleWidth,Short.MAX_VALUE));
        Dimension preferredSize=messageArea.getPreferredSize();
        preferredSize.width=bubbleWidth;

        return preferredSize;
    }
    }

