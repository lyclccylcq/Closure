package app.ui.chat;

import app.ui.UiFonts;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.TextInputControl;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.control.Tooltip;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.Region;
import javafx.scene.layout.VBox;

import java.util.function.Consumer;

final class ChatInputBar extends HBox {
    private final TextInputControl inputField;
    private final Consumer<String> onSendMessage;

    ChatInputBar(Consumer<String> onSendMessage,Runnable onStop,Runnable onNewConversation){
        super(8);
        this.onSendMessage=onSendMessage;
        boolean workbench=onNewConversation==null;
        if(workbench){
            TextArea editor=new TextArea();
            editor.setWrapText(true);
            editor.setPrefRowCount(2);
            editor.setId("workbench-editor");
            editor.setPromptText("发消息给可露希尔");
            editor.setTooltip(new Tooltip("Enter 发送，Shift+Enter 换行"));
            editor.addEventFilter(KeyEvent.KEY_PRESSED,event -> {
                if(event.getCode()!=KeyCode.ENTER) return;
                event.consume();
                if(event.isShiftDown()) editor.replaceSelection("\n");
                else sendCurrentMessage();
            });
            inputField=editor;
            getStyleClass().add("workbench-composer");
        }else{
            TextField editor=new TextField();
            editor.setOnAction(event -> sendCurrentMessage());
            inputField=editor;
        }

        setPadding(new Insets(10,12,12,12));
        setAlignment(Pos.CENTER);
        UiFonts.applyJavaFxFont(inputField,14);

        Button sendButton=new Button("发送");
        UiFonts.applyJavaFxFont(sendButton,14);
        sendButton.setOnAction(event -> sendCurrentMessage());

        Button stopButton=new Button("停止");
        stopButton.setTooltip(new Tooltip("停止当前回复和排队消息"));
        UiFonts.applyJavaFxFont(stopButton,14);
        stopButton.setOnAction(event -> onStop.run());

        if(workbench){
            inputField.getStyleClass().add("workbench-composer-input");
            inputField.setMinWidth(0);
            sendButton.setId("workbench-send");
            sendButton.getStyleClass().add("workbench-send");
            sendButton.setMinWidth(Region.USE_PREF_SIZE);
            stopButton.setId("workbench-stop");
            stopButton.getStyleClass().add("workbench-stop");
            stopButton.setMinWidth(Region.USE_PREF_SIZE);
            Label shortcut=new Label("Enter 发送 · Shift+Enter 换行");
            shortcut.getStyleClass().add("workbench-shortcut");
            shortcut.setMinWidth(0);
            shortcut.setMaxWidth(Double.MAX_VALUE);
            HBox.setHgrow(shortcut,Priority.ALWAYS);
            HBox actions=new HBox(8,shortcut,stopButton,sendButton);
            actions.setAlignment(Pos.CENTER_LEFT);
            actions.getStyleClass().add("workbench-composer-actions");
            VBox surface=new VBox(inputField,actions);
            surface.getStyleClass().add("workbench-composer-surface");
            HBox.setHgrow(surface,Priority.ALWAYS);
            getChildren().add(surface);
        }else{
            setStyle(ChatStyles.BACKGROUND_STYLE);
            inputField.setStyle(ChatStyles.INPUT_STYLE);
            sendButton.setStyle(ChatStyles.BUTTON_STYLE);
            stopButton.setStyle(ChatStyles.BUTTON_STYLE);
            Button newButton=new Button("新对话");
            newButton.setStyle(ChatStyles.BUTTON_STYLE);
            newButton.setTooltip(new Tooltip("清空本次会话，重新开始"));
            UiFonts.applyJavaFxFont(newButton,14);
            newButton.setOnAction(event -> onNewConversation.run());
            HBox.setHgrow(inputField,Priority.ALWAYS);
            getChildren().addAll(inputField,sendButton,stopButton,newButton);
        }
    }

    void focusInput(){
        inputField.requestFocus();
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
