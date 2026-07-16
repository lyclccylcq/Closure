package app.ui.chat;

import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;

import java.util.function.Consumer;

final class ChatInputBar extends HBox {
    private final TextField inputField;
    private final Consumer<String> onSendMessage;

    ChatInputBar(Consumer<String> onSendMessage){
        super(8);
        this.onSendMessage=onSendMessage;
        inputField=new TextField();

        setPadding(new Insets(10,12,12,12));
        setAlignment(Pos.CENTER);
        setStyle(ChatStyles.BACKGROUND_STYLE);

        inputField.setStyle(ChatStyles.INPUT_STYLE);
        inputField.setOnAction(event -> sendCurrentMessage());

        Button sendButton=new Button("发送");
        sendButton.setStyle(ChatStyles.BUTTON_STYLE);
        sendButton.setOnAction(event -> sendCurrentMessage());

        HBox.setHgrow(inputField, Priority.ALWAYS);
        getChildren().addAll(inputField,sendButton);
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
