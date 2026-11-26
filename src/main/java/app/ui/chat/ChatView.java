package app.ui.chat;

import app.chat.model.AssistantReply;
import app.character.CharacterActivityListener;
import app.character.ChatActivity;
import app.chat.model.ChatMessage;
import app.chat.model.MessageRole;
import app.service.AssistantDisplayTextStreamExtractor;
import app.service.ChatService;
import app.service.ChatSpeechController;
import app.service.ChatStreamListener;
import javafx.animation.Animation;
import javafx.animation.KeyFrame;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.VBox;
import javafx.util.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;

final class ChatView extends BorderPane {
    private static final Duration REPLY_STREAM_INTERVAL=Duration.millis(95);
    private static final String OPENING="我在，想聊点什么？";

    private final ChatService chatService;
    private final SoundPlayer soundPlayer;
    private final ChatSpeechController chatSpeechController;
    private final VBox messageBox;
    private final ScrollPane scrollPane;
    private final ChatActivity characterActivity;
    private final Set<ChatService.Request> pendingRequests=new HashSet<>();
    private final Set<StreamingContentRenderer> renderers=new HashSet<>();
    private volatile boolean disposed;
    private volatile long viewEpoch;

    ChatView(ChatService chatService,SoundPlayer soundPlayer,ChatSpeechController chatSpeechController){
        this(chatService,soundPlayer,chatSpeechController,CharacterActivityListener.NONE);
    }

    ChatView(ChatService chatService,SoundPlayer soundPlayer,ChatSpeechController chatSpeechController,
             CharacterActivityListener activityListener){
        this.chatService=chatService;
        this.soundPlayer=soundPlayer;
        this.chatSpeechController=chatSpeechController;
        characterActivity=new ChatActivity(activityListener);
        setStyle(ChatStyles.BACKGROUND_STYLE);

        messageBox=new VBox(18);
        messageBox.setPadding(new Insets(14));
        messageBox.setFillWidth(true);
        messageBox.setStyle(ChatStyles.BACKGROUND_STYLE);
        scrollPane=new ScrollPane(messageBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle(ChatStyles.BACKGROUND_STYLE+" -fx-background: #080c12; -fx-border-color: transparent;");
        messageBox.heightProperty().addListener((observable,oldHeight,newHeight) -> scrollToLatestMessage());
        setCenter(scrollPane);
        setBottom(new ChatInputBar(this::handleUserMessage,this::cancelReplies,this::startNewConversation));

        List<app.service.ConversationHistory.Turn> turns=chatService.completedTurns();
        if(turns.isEmpty()){
            addMessage(new ChatMessage(MessageRole.CLOSURE,OPENING));
        }else{
            for(var turn:turns){
                addMessage(new ChatMessage(MessageRole.USER,turn.userText()));
                addMessage(new ChatMessage(MessageRole.CLOSURE,turn.reply().getDisplayText()));
            }
        }
        // The greeting is UI-only, never a user fact or an assistant history entry.
    }

    private void handleUserMessage(String userText){
        if(disposed) return;
        addMessage(new ChatMessage(MessageRole.USER,userText));
        MessageBubble bubble=addMessage(new ChatMessage(MessageRole.CLOSURE,"等待回复..."));
        streamReplyInto(bubble,userText);
    }

    private MessageBubble addMessage(ChatMessage message){
        MessageBubble bubble=new MessageBubble(message);
        messageBox.getChildren().add(bubble);
        scrollToLatestMessage();
        return bubble;
    }

    private void streamReplyInto(MessageBubble bubble,String userText){
        long epoch=viewEpoch;
        AtomicReference<ChatActivity.Request> activity=new AtomicReference<>();
        StringBuilder replyText=new StringBuilder();
        StreamingContentRenderer renderer=new StreamingContentRenderer(bubble,epoch);
        ChatService.Request[] handle={null};
        try{
            handle[0]=chatService.streamReply(userText,new ChatStreamListener(){
                @Override public void onStarted(){
                    if(isCurrent(epoch)) activity.set(characterActivity.start());
                    Platform.runLater(() -> {
                        if(isCurrent(epoch)) bubble.setContent("接线中...");
                    });
                }

                @Override public void onToken(String token){
                    if(!isCurrent(epoch)) return;
                    replyText.append(token);
                    AssistantDisplayTextStreamExtractor.visibleText(replyText.toString())
                            .ifPresent(renderer::setTargetText);
                }

                @Override public void onComplete(AssistantReply reply){
                    ChatActivity.Request request=activity.get();
                    if(request!=null){
                        if(isCurrent(epoch)) request.complete(reply.getDominantEmotion());
                        else request.close();
                    }
                    Platform.runLater(() -> {
                        pendingRequests.remove(handle[0]);
                        if(isCurrent(epoch)) renderer.finishWith(reply);
                    });
                }

                @Override public void onError(Throwable error){
                    ChatActivity.Request request=activity.get();
                    if(request!=null) request.close();
                    Platform.runLater(() -> {
                        pendingRequests.remove(handle[0]);
                        if(!isCurrent(epoch)) return;
                        String message=error instanceof CancellationException ? "已停止回复"
                                : "模型回复未完成：" + (error.getMessage()==null ? "连接失败" : error.getMessage());
                        renderer.showError(message);
                    });
                }
            });
            pendingRequests.add(handle[0]);
        }catch(RuntimeException failure){
            ChatActivity.Request request=activity.get();
            if(request!=null) request.close();
            renderer.showError("模型连接失败");
        }
    }

    private boolean isCurrent(long epoch){
        return !disposed && epoch==viewEpoch;
    }

    private void cancelReplies(){
        chatService.cancelReplies(pendingRequests);
        pendingRequests.clear();
        for(var renderer:List.copyOf(renderers)) renderer.stopTyping();
        chatSpeechController.stop();
    }

    private void startNewConversation(){
        viewEpoch++;
        cancelReplies();
        for(var renderer:List.copyOf(renderers)) renderer.dispose();
        chatService.newConversation();
        messageBox.getChildren().clear();
        addMessage(new ChatMessage(MessageRole.CLOSURE,OPENING));
    }

    void scrollToLatestMessage(){
        Platform.runLater(() -> {
            if(disposed) return;
            scrollPane.layout();
            scrollPane.setVvalue(scrollPane.getVmax());
        });
    }

    void dispose(){
        if(disposed) return;
        disposed=true;
        characterActivity.close();
        Platform.runLater(() -> {
            cancelReplies();
            for(var renderer:List.copyOf(renderers)) renderer.dispose();
            soundPlayer.stopVoice();
        });
    }

    private final class StreamingContentRenderer {
        private final MessageBubble bubble;
        private final long epoch;
        private final Timeline timeline;
        private String targetText="";
        private String renderedText="";
        private int renderedCodePoints;
        private AssistantReply pendingFinalReply;
        private boolean stopped;

        private StreamingContentRenderer(MessageBubble bubble,long epoch){
            this.bubble=bubble;
            this.epoch=epoch;
            timeline=new Timeline(new KeyFrame(REPLY_STREAM_INTERVAL,event -> renderNextCodePoint()));
            timeline.setCycleCount(Animation.INDEFINITE);
            renderers.add(this);
        }

        private void setTargetText(String text){
            if(!isCurrent(epoch) || text==null || text.isBlank()) return;
            Platform.runLater(() -> {
                if(isCurrent(epoch) && !stopped) setTargetTextOnFx(text);
            });
        }

        private void finishWith(AssistantReply reply){
            if(!isCurrent(epoch)) return;
            if(stopped){
                bubble.setContent(reply.getDisplayText());
                dispose();
                return;
            }
            pendingFinalReply=reply;
            setTargetTextOnFx(reply.getDisplayText());
            chatSpeechController.speakAssistantReply(reply);
            completeIfFinalTextRendered();
        }

        private void showError(String message){
            dispose();
            pendingFinalReply=null;
            bubble.setContent(message);
            scrollToLatestMessage();
        }

        private void setTargetTextOnFx(String text){
            if(text.equals(targetText)) return;
            if(!text.startsWith(renderedText)){
                renderedText="";
                renderedCodePoints=0;
                bubble.setContent("");
            }
            targetText=text;
            if(timeline.getStatus()!=Animation.Status.RUNNING) timeline.play();
        }

        private void renderNextCodePoint(){
            if(!isCurrent(epoch)){
                dispose();
                return;
            }
            int targetCodePoints=targetText.codePointCount(0,targetText.length());
            if(renderedCodePoints>=targetCodePoints){
                timeline.stop();
                completeIfFinalTextRendered();
                return;
            }
            renderedCodePoints++;
            renderedText=targetText.substring(0,targetText.offsetByCodePoints(0,renderedCodePoints));
            bubble.setContent(renderedText);
            scrollToLatestMessage();
            completeIfFinalTextRendered();
        }

        private void completeIfFinalTextRendered(){
            if(pendingFinalReply!=null && renderedText.equals(pendingFinalReply.getDisplayText())){
                pendingFinalReply=null;
                dispose();
            }
        }

        private void stopTyping(){
            stopped=true;
            timeline.stop();
            if(pendingFinalReply!=null){
                bubble.setContent(pendingFinalReply.getDisplayText());
                pendingFinalReply=null;
                dispose();
            }
        }

        private void dispose(){
            timeline.stop();
            renderers.remove(this);
        }
    }
}
