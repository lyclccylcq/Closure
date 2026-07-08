package app.ui.chat;

import app.model.ChatMessage;
import app.model.MessageRole;
import app.service.ChatService;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import javax.swing.*;
import java.awt.*;
import java.net.URL;

public class ChatWindow extends JFrame {
    private static final String BACKGROUND_STYLE="-fx-background-color: #080c12;";
    private static final String BUBBLE_STYLE="""
            -fx-background-color: #121b27;
            -fx-border-color: #3aaed2;
            -fx-border-width: 1;
            -fx-background-radius: 6;
            -fx-border-radius: 6;
            -fx-padding: 8 10 8 10;
            -fx-text-fill: #eef6ff;
            -fx-font-size: 14px;
            """;
    private static final String NAME_STYLE="-fx-text-fill: #eef6ff; -fx-font-size: 12px; -fx-font-weight: bold;";
    private static final String INPUT_STYLE="""
            -fx-background-color: #0e1620;
            -fx-border-color: #30586c;
            -fx-border-width: 1;
            -fx-text-fill: #eef6ff;
            -fx-prompt-text-fill: #6f8a9b;
            -fx-font-size: 14px;
            """;
    private static final String BUTTON_STYLE="""
            -fx-background-color: #122a38;
            -fx-border-color: #3ad6ff;
            -fx-border-width: 1;
            -fx-text-fill: #eef6ff;
            -fx-font-size: 14px;
            -fx-padding: 7 18 7 18;
            """;
    private static final int AVATAR_SIZE=40;
    private static final double BUBBLE_MAX_WIDTH=280;

    private final ChatService chatService;
    private final JFXPanel fxPanel;

    private VBox messageBox;
    private ScrollPane scrollPane;
    private TextField inputField;

    public ChatWindow() {
        chatService = new ChatService();
        fxPanel = new JFXPanel();

        setTitle("通讯频道：可露希尔");
        setSize(420,520);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(fxPanel,BorderLayout.CENTER);

        Platform.setImplicitExit(false);
        Platform.runLater(this::initFxContent);
    }

    private void initFxContent(){
        BorderPane root=new BorderPane();
        root.setStyle(BACKGROUND_STYLE);

        messageBox=new VBox(18);
        messageBox.setPadding(new Insets(14));
        messageBox.setFillWidth(true);
        messageBox.setStyle(BACKGROUND_STYLE);

        scrollPane=new ScrollPane(messageBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle(BACKGROUND_STYLE+" -fx-background: #080c12; -fx-border-color: transparent;");

        inputField=new TextField();
        inputField.setStyle(INPUT_STYLE);
        inputField.setOnAction(event -> sendCurrentMessage());

        Button sendButton=new Button("发送");
        sendButton.setStyle(BUTTON_STYLE);
        sendButton.setOnAction(event -> sendCurrentMessage());

        HBox inputBar=new HBox(8,inputField,sendButton);
        inputBar.setPadding(new Insets(10,12,12,12));
        inputBar.setAlignment(Pos.CENTER);
        inputBar.setStyle(BACKGROUND_STYLE);
        HBox.setHgrow(inputField, Priority.ALWAYS);

        root.setCenter(scrollPane);
        root.setBottom(inputBar);

        fxPanel.setScene(new Scene(root));
        addPetMessage("给，咖啡。看你那黑眼圈，昨晚又熬夜了吧？");
    }

    private void sendCurrentMessage(){
        String text=inputField.getText().trim();
        if(text.isEmpty()){
            return;
        }

        inputField.clear();
        handleUserMessage(text);
    }

    private void handleUserMessage(String userText) {
        addUserMessage(userText);
        String reply = chatService.getReply(userText);
        addPetMessage(reply);
    }

    private void addUserMessage(String content) {
        addMessage(new ChatMessage(MessageRole.USER,content));
    }

    private void addPetMessage(String content) {
        addMessage(new ChatMessage(MessageRole.CLOSURE,content));
    }

    private void addMessage(ChatMessage message){
        if(Platform.isFxApplicationThread()){
            addMessageNode(message);
        }else{
            Platform.runLater(() -> addMessageNode(message));
        }
    }

    private void addMessageNode(ChatMessage message){
        if(messageBox==null){
            return;
        }

        boolean isUser=message.getRole().isUser();

        HBox row=new HBox(8);
        row.setMaxWidth(Double.MAX_VALUE);
        row.setAlignment(isUser ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        ImageView avatarView=createAvatarView(message.getRole());
        VBox contentColumn=new VBox(4);
        contentColumn.setAlignment(isUser ? Pos.TOP_RIGHT : Pos.TOP_LEFT);

        Label nameLabel=new Label(message.getRole().getDisplayName());
        nameLabel.setStyle(NAME_STYLE);

        Label bubbleLabel=new Label(message.getContent());
        bubbleLabel.setWrapText(true);
        bubbleLabel.setMaxWidth(BUBBLE_MAX_WIDTH);
        bubbleLabel.setStyle(BUBBLE_STYLE);

        contentColumn.getChildren().addAll(nameLabel,bubbleLabel);

        if(isUser){
            row.getChildren().addAll(contentColumn,avatarView);
        }else{
            row.getChildren().addAll(avatarView,contentColumn);
        }

        messageBox.getChildren().add(row);
        Platform.runLater(() -> scrollPane.setVvalue(1.0));
    }

    private ImageView createAvatarView(MessageRole role){
        ImageView avatarView=new ImageView();
        avatarView.setFitWidth(AVATAR_SIZE);
        avatarView.setFitHeight(AVATAR_SIZE);
        avatarView.setPreserveRatio(true);

        URL avatarUrl=ChatWindow.class.getResource(role.getAvatarPath());
        if(avatarUrl!=null){
            avatarView.setImage(new Image(avatarUrl.toExternalForm(),AVATAR_SIZE,AVATAR_SIZE,true,true));
        }

        return avatarView;
    }
}
