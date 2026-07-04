package app.ui.chat;

import javax.swing.*;
import java.awt.*;
import java.util.function.Consumer;

public class ChatInputPanel extends JPanel {

    private static final Color BACKGROUND_COLOR=new Color(8,12,18);
    private static final Color INPUT_COLOR=new Color(14,22,32);
    private static final Color ACCENT_COLOR=new Color(58,214,255);
    private static final Color TEXT_COLOR=new Color(238,246,255);
    private final JTextField inputField;
    private final JButton sendButton;

    private Consumer<String> onSendMessage;

    public ChatInputPanel(){
        setLayout(new BorderLayout(8,0));
        setBorder(BorderFactory.createEmptyBorder(8,8,8,8));
        setBackground(BACKGROUND_COLOR);

        inputField=new JTextField();
        sendButton =new JButton("发送");

        inputField.setBackground(INPUT_COLOR);
        inputField.setForeground(TEXT_COLOR);
        inputField.setCaretColor(ACCENT_COLOR);
        inputField.setSelectionColor(new Color(28,82,104));
        inputField.setSelectedTextColor(Color.WHITE);
        inputField.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(48,88,108),1),
                BorderFactory.createEmptyBorder(6,8,6,8)
        ));

        sendButton.setBackground(new Color(18,42,56));
        sendButton.setForeground(TEXT_COLOR);
        sendButton.setFocusPainted(false);
        sendButton.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(ACCENT_COLOR,1),
                BorderFactory.createEmptyBorder(6,14,6,14)
        ));

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
