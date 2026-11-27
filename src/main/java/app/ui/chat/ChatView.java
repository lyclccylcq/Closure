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
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.ScrollPane;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.Region;
import javafx.scene.layout.StackPane;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Pane;
import javafx.scene.layout.Priority;
import javafx.util.Duration;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

final class ChatView extends BorderPane {
    private static final Duration REPLY_STREAM_INTERVAL=Duration.millis(95);
    private static final String OPENING="我在，想聊点什么？";

    private final ChatService chatService;
    private final SoundPlayer soundPlayer;
    private final ChatSpeechController chatSpeechController;
    private final ChatSpeechController.SpeechSession speechSession;
    private final VBox messageBox;
    private final ScrollPane scrollPane;
    private final ChatInputBar composer;
    private final ChatActivity characterActivity;
    private final Consumer<String> onUserMessage;
    private final boolean workbench;
    private boolean speechActive;
    private final Set<ChatService.Request> pendingRequests=new HashSet<>();
    private final Set<StreamingContentRenderer> renderers=new HashSet<>();
    private volatile boolean disposed;
    private volatile long viewEpoch;
    private Node companion;

    ChatView(ChatService chatService,SoundPlayer soundPlayer,ChatSpeechController chatSpeechController){
        this(chatService,soundPlayer,chatSpeechController,CharacterActivityListener.NONE);
    }

    ChatView(ChatService chatService,SoundPlayer soundPlayer,ChatSpeechController chatSpeechController,
             CharacterActivityListener activityListener){
        this(chatService,soundPlayer,chatSpeechController,activityListener,null);
    }

    ChatView(ChatService chatService,Consumer<String> onUserMessage){
        this(chatService,onUserMessage,null);
    }

    ChatView(ChatService chatService,Consumer<String> onUserMessage,ChatSpeechController speech){
        this(chatService,null,speech,CharacterActivityListener.NONE,onUserMessage);
    }

    ChatView(ChatService chatService,Consumer<String> onUserMessage,ChatSpeechController speech,
             CharacterActivityListener activity){
        this(chatService,null,speech,activity,onUserMessage);
    }

    private ChatView(ChatService chatService,SoundPlayer soundPlayer,ChatSpeechController chatSpeechController,
                     CharacterActivityListener activityListener,Consumer<String> onUserMessage){
        this.chatService=chatService;
        this.soundPlayer=soundPlayer;
        this.chatSpeechController=chatSpeechController;
        this.onUserMessage=onUserMessage;
        workbench=onUserMessage!=null;
        speechSession=workbench && chatSpeechController!=null ? chatSpeechController.createSession() : null;
        characterActivity=new ChatActivity(activityListener);
        messageBox=new VBox(18);
        messageBox.setPadding(new Insets(14));
        messageBox.setFillWidth(true);
        scrollPane=new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        messageBox.heightProperty().addListener((observable,oldHeight,newHeight) -> scrollToLatestMessage());
        setCenter(scrollPane);
        composer=new ChatInputBar(this::handleUserMessage,this::cancelReplies,
                workbench ? null : this::startNewConversation);
        setBottom(composer);
        if(workbench){
            getStyleClass().add("workbench-chat");
            scrollPane.getStyleClass().add("workbench-messages");
            messageBox.setMinWidth(0);
            messageBox.getStyleClass().add("workbench-transcript");
            StackPane transcript=new StackPane(messageBox);
            transcript.setMinWidth(0);
            transcript.setAlignment(Pos.TOP_CENTER);
            scrollPane.setContent(transcript);
            BorderPane.setAlignment(composer,Pos.CENTER);
        }else{
            scrollPane.setContent(messageBox);
            setStyle(ChatStyles.BACKGROUND_STYLE);
            messageBox.setStyle(ChatStyles.BACKGROUND_STYLE);
            scrollPane.setStyle(ChatStyles.BACKGROUND_STYLE+" -fx-background: #080c12; -fx-border-color: transparent;");
        }

        List<app.service.ConversationHistory.Turn> turns=chatService.completedTurns();
        if(turns.isEmpty()){
            if(workbench) showEmptyWorkbench();
            else addMessage(new ChatMessage(MessageRole.CLOSURE,OPENING));
        }else{
            for(var turn:turns){
                addMessage(new ChatMessage(MessageRole.USER,turn.userText()));
                addMessage(new ChatMessage(MessageRole.CLOSURE,turn.reply().getDisplayText()));
            }
        }
        // The greeting is UI-only, never a user fact or an assistant history entry.
    }

    private void showEmptyWorkbench(){
        setBottom(null);
        detach(composer);
        VBox start=new VBox(12,WorkbenchTheme.welcome(companion == null),composer);
        start.setAlignment(Pos.CENTER);
        start.setMaxHeight(Region.USE_PREF_SIZE);
        start.setMaxWidth(900);
        StackPane welcome=new StackPane(start);
        welcome.getStyleClass().add("workbench-welcome");
        setCenter(welcome);
        if (companion != null) {
            detach(companion);
            Region spacer = new Region(); HBox.setHgrow(spacer, Priority.ALWAYS);
            HBox row = new HBox(spacer, companion); row.setAlignment(Pos.BOTTOM_RIGHT);
            row.getStyleClass().add("workbench-pet-footer"); setBottom(row);
        }
    }

    void setCompanion(Node node) {
        if (companion != null) detach(companion);
        companion = node;
        if (getCenter() != scrollPane) showEmptyWorkbench(); else showComposer();
    }

    private void showComposer() {
        setBottom(null); detach(composer);
        if (companion == null) { setBottom(composer); return; }
        detach(companion);
        HBox row = new HBox(8, composer, companion);
        row.setAlignment(Pos.BOTTOM_RIGHT); HBox.setHgrow(composer, Priority.ALWAYS);
        row.getStyleClass().add("workbench-pet-footer"); setBottom(row);
    }

    private static void detach(Node node) {
        if (node.getParent() instanceof Pane pane) pane.getChildren().remove(node);
    }

    private void handleUserMessage(String userText){
        if(disposed) return;
        if(workbench && getCenter()!=scrollPane){
            setCenter(scrollPane);
            showComposer();
            composer.focusInput();
        }
        if(onUserMessage!=null) onUserMessage.accept(userText);
        addMessage(new ChatMessage(MessageRole.USER,userText));
        MessageBubble bubble=addMessage(new ChatMessage(MessageRole.CLOSURE,"等待回复..."));
        streamReplyInto(bubble,userText);
    }

    private MessageBubble addMessage(ChatMessage message){
        MessageBubble bubble=new MessageBubble(message,workbench);
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
        if(speechSession!=null) speechSession.stop();
        else if(chatSpeechController!=null) chatSpeechController.stop();
    }

    void setSpeechActive(boolean active){
        speechActive=active;
        if(!active && speechSession!=null) speechSession.stop();
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
            if(soundPlayer!=null) soundPlayer.stopVoice();
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
            if(speechSession!=null){
                if(speechActive) speechSession.speak(reply);
            }else if(chatSpeechController!=null) chatSpeechController.speakAssistantReply(reply);
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
