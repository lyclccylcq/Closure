package app.ui.chat;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

public class ChatInputPanel extends JPanel {

    private final JTextField inputField;
    private final JButton sendButton;

    private Consumer<String> onSendMessage;

    public ChatInputPanel(){
        setLayout(new BorderLayout(8,0));
        setBorder(BorderFactory.createEmptyBorder(8,8,8,8));

        inputField=new JTextField();
        sendButton =new JButton("发送");

        add(inputField,BorderLayout.CENTER);
        add(sendButton,BorderLayout.EAST);

        sendButton.addActionListener(e -> sendMessage());
        inputField.addActionListener(e -> sendMessage());
    }

    public void setOnSendMessage(Consumer<String> onSendMessage){
        this.onSendMessage=onSendMessage;
    }

    private void sendMessage(){
        String message=inputField.getText().trim();

        if (message.isEmpty()){
            return;
        }
        inputField.setText("");
        if(onSendMessage!=null){
            onSendMessage.accept(message);
        }
    }
}
