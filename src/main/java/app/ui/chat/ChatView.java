package app.ui.chat;

import app.chat.model.AssistantReply;
import app.chat.model.ChatMessage;
import app.chat.model.MessageRole;
import app.service.AssistantReplyParser;
import app.service.ChatService;
import app.service.ChatSpeechController;
import app.service.ChatStreamListener;
import app.voice.model.SpeechEmotion;
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
    private static final String CHAT_START_VOICE="/audio.Chinese/Hello.wav";

    private final ChatService chatService;
    private final SoundPlayer soundPlayer;
    private final ChatSpeechController chatSpeechController;
    private final AssistantReplyParser assistantReplyParser;
    private final VBox messageBox;
    private final ScrollPane scrollPane;

    ChatView(ChatService chatService,SoundPlayer soundPlayer,ChatSpeechController chatSpeechController){
        this.chatService=chatService;
        this.soundPlayer=soundPlayer;
        this.chatSpeechController=chatSpeechController;
        assistantReplyParser=new AssistantReplyParser();

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
        soundPlayer.playVoice(CHAT_START_VOICE);
    }

    private void handleUserMessage(String userText) {
        addUserMessage(userText);
        MessageBubble bubble=addMessage(new ChatMessage(MessageRole.CLOSURE,"接线中..."));
        streamReplyInto(bubble,userText);
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

    private void streamReplyInto(MessageBubble bubble,String userText){
        StringBuilder replyText=new StringBuilder();
        boolean[] receivedToken={false};

        chatService.streamReply(userText,new ChatStreamListener() {
            @Override
            public void onToken(String token){
                if(!receivedToken[0]){
                    receivedToken[0]=true;
                    replyText.setLength(0);
                }

                replyText.append(token);
            }

            @Override
            public void onComplete(){
                Platform.runLater(() -> {
                    AssistantReply assistantReply;
                    if(!receivedToken[0]){
                        assistantReply=new AssistantReply(
                                "工程部暂时没有收到模型回复。",
                                "工程部暂时没有收到模型回复。",
                                SpeechEmotion.NEUTRAL
                        );
                    }else{
                        assistantReply=assistantReplyParser.parse(replyText.toString());
                    }
                    bubble.setContent(assistantReply.getDisplayText());
                    scrollToLatestMessage();
                    chatSpeechController.speakAssistantReply(assistantReply);
                });
            }

            @Override
            public void onError(Throwable error){
                Platform.runLater(() -> {
                    bubble.setContent("模型连接失败：" + error.getMessage());
                    scrollToLatestMessage();
                });
            }
        });
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

    void dispose(){
        chatService.close();
        soundPlayer.stopVoice();
    }
}
