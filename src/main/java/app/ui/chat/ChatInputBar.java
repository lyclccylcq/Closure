package app.ui.chat;

import app.ui.UiFonts;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.Consumer;

final class ChatInputBar extends HBox {
    private final TextField inputField;
    private final Consumer<String> onSendMessage;

    ChatInputBar(Consumer<String> onSendMessage,Runnable onStop,Runnable onNewConversation){
        super(8);
        this.onSendMessage=onSendMessage;
        inputField=new TextField();

        setPadding(new Insets(10,12,12,12));
        setAlignment(Pos.CENTER);
        setStyle(ChatStyles.BACKGROUND_STYLE);

        inputField.setStyle(ChatStyles.INPUT_STYLE);
        UiFonts.applyJavaFxFont(inputField,14);
        inputField.setOnAction(event -> sendCurrentMessage());

        Button sendButton=new Button("发送");
        sendButton.setStyle(ChatStyles.BUTTON_STYLE);
        UiFonts.applyJavaFxFont(sendButton,14);
        sendButton.setOnAction(event -> sendCurrentMessage());

        Button stopButton=new Button("停止");
        stopButton.setStyle(ChatStyles.BUTTON_STYLE);
        stopButton.setTooltip(new Tooltip("停止当前回复和排队消息"));
        UiFonts.applyJavaFxFont(stopButton,14);
        stopButton.setOnAction(event -> onStop.run());

        Button newButton=new Button("新对话");
        newButton.setStyle(ChatStyles.BUTTON_STYLE);
        newButton.setTooltip(new Tooltip("清空本次会话，重新开始"));
        UiFonts.applyJavaFxFont(newButton,14);
        newButton.setOnAction(event -> onNewConversation.run());

        HBox.setHgrow(inputField, Priority.ALWAYS);
        getChildren().addAll(inputField,sendButton,stopButton,newButton);
    }

    private void sendCurrentMessage(){
        String text=inputField.getText().trim();
        if(text.isEmpty()){
            return;
        }

        inputField.clear();
        onSendMessage.accept(text);
    }
}
