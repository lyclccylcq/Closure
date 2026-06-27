package app.ui;

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
        String reply=fakeReply(text);

        //显示可露希尔的回复
        appendPetMessage(reply);
    }

    private String fakeReply(String input){
        if(input.contains("你好")){
            return "无人机整备完毕。离出发还有多长时间？我看看能不能补个觉......";
        }
        if (input.contains("累")){
            return "博士，你这次体检的指标怎么回事？什么，觉得最近很虚弱，怕自己时日无多？！我这就把这些年多收你的钱给医疗部当特别经费，放心，一块钱都没动过——报告是假的？逗我玩？华法琳！";
        }
        if (input.contains("饿")){
            return"烧烤味饼干，来一片？";
        }
        if (input.contains("生日")){
            return "罗德岛干员过生日均有机会获得采购部免单券，你也不例外！来这里摇奖，免单券就在特等奖等着你！什么？谁中过特等奖？这个——别担心，第一位特等奖得主没准就是你！";
        }
        if (input.contains("飞起来")){
            return "全体无人机，挂钩释放，功率解锁，钩住博士的兜帽——起飞!";
        }

        return "别急，我还在算呢。";
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
