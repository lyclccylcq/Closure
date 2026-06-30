package app.ui;

import app.service.ChatService;

import javax.swing.*;
import java.awt.*;

public class ChatWindow extends JFrame {
    private final JTextArea chatArea=new JTextArea();
    private final JTextField inputField=new JTextField();
    private final JButton sendButton=new JButton("发送");

    public ChatWindow(){
        setTitle("通讯频道：可露希尔");
        setSize(420,520);
        setLocationRelativeTo(null);

        //关闭聊天窗口仅仅隐藏聊天窗口，不会退出整个程序
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);

        initUI();
    }

    private void initUI(){
        //聊天区域不可编辑
        chatArea.setEditable(false);

        //自动换行
        chatArea.setLineWrap(true);
        chatArea.setWrapStyleWord(true);

        //设置边距
        chatArea.setMargin(new Insets(10,10,10,10));

        //创建滚动面板，聊天区域放进去
        JScrollPane scrollPane=new JScrollPane(chatArea);

        //输入框
        JPanel inputPanel=new JPanel(new BorderLayout(8,0));
        inputPanel.setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        inputPanel.add(inputField,BorderLayout.CENTER);
        inputPanel.add(sendButton,BorderLayout.EAST);

        //窗口布局
        setLayout(new BorderLayout());
        add(scrollPane,BorderLayout.CENTER);
        add(inputPanel,BorderLayout.SOUTH);

        //点击发送按钮发送消息
        sendButton.addActionListener(e ->
            sendMessage()
        );

        //按Enter也会发送消息
        inputField.addActionListener(e ->
                sendMessage()
        );

        //初始欢迎语句
        appendPetMessage("给，咖啡。看你那黑眼圈，昨晚又熬夜了吧？");
    }
    private void sendMessage(){
        String text=inputField.getText().trim();

        //如果输入为空，就不发送
        if(text.isEmpty()){
            return;
        }

        //先显示用户消息
        appendUserMessage(text);

        //清空输入框
        inputField.setText("");

        //生成一个假的回复
        final ChatService chatService=new ChatService();
        String reply= chatService.getReply(text);

        //显示可露希尔的回复
        appendPetMessage(reply);
    }


    private void appendUserMessage(String message){
        chatArea.append("你："+message+"\n\n");
        scrollToBottom();
    }

    private void appendPetMessage(String message){
        chatArea.append("可露希尔："+message+"\n\n");
    }
    private void scrollToBottom(){
        chatArea.setCaretPosition(chatArea.getDocument().getLength());
    }

    //测试用main
    public static void main(String[] args){
        SwingUtilities.invokeLater(()->{
            ChatWindow chatWindow=new ChatWindow();

            //关闭窗口就退出程序
            chatWindow.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            chatWindow.setVisible(true);
        });
    }
}
