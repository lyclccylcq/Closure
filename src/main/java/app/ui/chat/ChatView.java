package app.ui.chat;

import app.chat.model.ChatMessage;
import app.chat.model.ChatReply;
import app.chat.model.MessageRole;
import app.service.ChatService;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;

final class ChatView extends BorderPane {
    private static final Duration STREAM_INTERVAL=Duration.millis(60);

    private final ChatService chatService;
    private final SoundPlayer soundPlayer;
    private final VBox messageBox;
    private final ScrollPane scrollPane;

    ChatView(ChatService chatService,SoundPlayer soundPlayer){
        this.chatService=chatService;
        this.soundPlayer=soundPlayer;

        setStyle(ChatStyles.BACKGROUND_STYLE);

        // VBox 竖向保存所有消息行，ScrollPane 负责滚动。
        messageBox=new VBox(18);
        messageBox.setPadding(new Insets(14));
        messageBox.setFillWidth(true);
        messageBox.setStyle(ChatStyles.BACKGROUND_STYLE);

        scrollPane=new ScrollPane(messageBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle(ChatStyles.BACKGROUND_STYLE+" -fx-background: #080c12; -fx-border-color: transparent;");
        
        // 新消息会改变 VBox 高度；等 JavaFX 完成这一轮布局后再滚到底部。
        messageBox.heightProperty().addListener((observable,oldHeight,newHeight) -> scrollToLatestMessage());

        setCenter(scrollPane);
        setBottom(new ChatInputBar(this::handleUserMessage));

        addStreamingPetMessage("给，咖啡。看你那黑眼圈，昨晚又熬夜了吧？");
        soundPlayer.playVoice("/audio/chatstart.wav");
    }

    private void handleUserMessage(String userText) {
        addUserMessage(userText);
        ChatReply reply=chatService.getReply(userText);
        addStreamingPetMessage(reply.getText());

        // 回复对象里带了语音路径；没有配置语音时，SoundPlayer 不会播放任何东西。
        if(reply.hasVoice()){
            soundPlayer.playVoice(reply.getVoicePath());
        }
    }

    private void addUserMessage(String content) {
        addMessage(new ChatMessage(MessageRole.USER,content));
    }

    private void addStreamingPetMessage(String content) {
        MessageBubble bubble=addMessage(new ChatMessage(MessageRole.CLOSURE,""));
        streamTextInto(bubble,content);
    }

    private MessageBubble addMessage(ChatMessage message){
        MessageBubble bubble=new MessageBubble(message);
        messageBox.getChildren().add(bubble);
        scrollToLatestMessage();
        return bubble;
    }

    private void streamTextInto(MessageBubble bubble,String content){
        int[] codePoints=content.codePoints().toArray();
        if(codePoints.length==0){
            bubble.setContent("");
            return;
        }

        StringBuilder visibleText=new StringBuilder();
        int[] nextIndex={0};

        Timeline timeline=new Timeline(new KeyFrame(STREAM_INTERVAL,event -> {
            visibleText.appendCodePoint(codePoints[nextIndex[0]]);
            nextIndex[0]++;
            bubble.setContent(visibleText.toString());
            scrollToLatestMessage();
        }));

        timeline.setCycleCount(codePoints.length);
        timeline.play();
    }

    void scrollToLatestMessage(){
        Platform.runLater(() -> {
            scrollPane.layout();
            scrollPane.setVvalue(scrollPane.getVmax());
        });
    }
}
