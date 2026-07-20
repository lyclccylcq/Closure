package app.ui.quick;

import app.chat.model.ChatReply;
import app.service.ChatService;
import app.ui.chat.SoundPlayer;
import javafx.animation.KeyFrame;
import javafx.animation.PauseTransition;
import javafx.animation.Timeline;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Point2D;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.paint.Color;
import javafx.stage.Screen;
import javafx.stage.Stage;
import javafx.stage.StageStyle;
import javafx.util.Duration;

import java.awt.Rectangle;
import java.util.List;

public class QuickChatWindow {
    private static final Object FX_START_LOCK=new Object();
    private static final int WINDOW_WIDTH=340;
    private static final int WINDOW_HEIGHT=132;
    private static final int DIALOG_BOTTOM_OVERLAP=48;
    private static final int DIALOG_RIGHT_SHIFT=56;
    private static final int MAX_SINGLE_SEGMENT_CODE_POINTS=52;
    private static final Duration STREAM_INTERVAL=Duration.millis(60);
    private static final Duration SEGMENT_PAUSE=Duration.millis(520);

    private static boolean fxStarted;

    private final ChatService chatService;
    private final SoundPlayer soundPlayer;
    private Stage stage;
    private Label answerLabel;
    private TextField inputField;
    private Button actionButton;
    private Timeline streamTimeline;
    private PauseTransition segmentPause;
    private List<String> answerSegments=List.of();
    private int nextSegmentIndex;
    private boolean streamingAnswer;
    private boolean waitingForNextSegment;

    public QuickChatWindow(){
        chatService=new ChatService();
        soundPlayer=new SoundPlayer();
        runOnFxThread(this::initStage);
    }

    public void showNextTo(Rectangle petBounds){
        Rectangle bounds=new Rectangle(petBounds);
        runOnFxThread(() -> {
            ensureStage();
            moveStageNextTo(bounds);
            stage.show();
            stage.toFront();
            inputField.requestFocus();
        });
    }

    public void moveNextTo(Rectangle petBounds){
        Rectangle bounds=new Rectangle(petBounds);
        runOnFxThread(() -> {
            if(stage!=null && stage.isShowing()){
                moveStageNextTo(bounds);
            }
        });
    }

    public void dispose(){
        runOnFxThread(() -> {
            if(streamTimeline!=null){
                streamTimeline.stop();
            }
            if(segmentPause!=null){
                segmentPause.stop();
            }
            soundPlayer.stopVoice();
            if(stage!=null){
                stage.close();
            }
        });
    }

    private static void runOnFxThread(Runnable task){
        ensureFxStarted();
        if(Platform.isFxApplicationThread()){
            task.run();
        }else{
            Platform.runLater(task);
        }
    }

    private static void ensureFxStarted(){
        synchronized(FX_START_LOCK){
            if(fxStarted){
                return;
            }

            try{
                Platform.startup(() -> Platform.setImplicitExit(false));
            }catch(IllegalStateException exception){
                Platform.setImplicitExit(false);
            }

            fxStarted=true;
        }
    }

    private void ensureStage(){
        if(stage==null){
            initStage();
        }
    }

    private void initStage(){
        if(stage!=null){
            return;
        }

        stage=new Stage(StageStyle.TRANSPARENT);
        stage.setAlwaysOnTop(true);
        stage.setResizable(false);
        stage.setWidth(WINDOW_WIDTH);
        stage.setHeight(WINDOW_HEIGHT);

        Scene scene=new Scene(createContent(),WINDOW_WIDTH,WINDOW_HEIGHT);
        scene.setFill(Color.TRANSPARENT);
        stage.setScene(scene);
    }

    private BorderPane createContent(){
        answerLabel=new Label("工程部可露希尔在线");
        answerLabel.setWrapText(true);
        answerLabel.setMaxWidth(Double.MAX_VALUE);
        answerLabel.setMinHeight(58);
        answerLabel.setStyle("""
                -fx-text-fill: #eef6ff;
                -fx-font-size: 14px;
                """);

        BorderPane root=new BorderPane();
        root.setPadding(new Insets(14,16,12,16));
        root.setStyle("""
                -fx-background-color: rgba(8, 12, 18, 0.88);
                -fx-border-color: rgba(58, 174, 210, 0.90);
                -fx-border-width: 1;
                -fx-background-radius: 12;
                -fx-border-radius: 12;
                """);
        root.setCenter(answerLabel);
        root.setBottom(createInputBar());
        BorderPane.setMargin(answerLabel,new Insets(0,0,8,0));
        return root;
    }

    private HBox createInputBar(){
        inputField=new TextField();
        inputField.setStyle("""
                -fx-background-color: rgba(14, 22, 32, 0.92);
                -fx-border-color: #30586c;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                -fx-prompt-text-fill: #6f8a9b;
                -fx-font-size: 14px;
                -fx-padding: 4 7 4 7;
                """);
        inputField.setOnAction(event -> handleInputAction());

        actionButton=new Button("问");
        actionButton.setPrefWidth(44);
        actionButton.setStyle("""
                -fx-background-color: rgba(18, 42, 56, 0.96);
                -fx-border-color: #3ad6ff;
                -fx-border-width: 1;
                -fx-text-fill: #eef6ff;
                -fx-font-size: 14px;
                -fx-padding: 4 12 4 12;
                """);
        actionButton.setOnAction(event -> handleActionButton());

        HBox inputBar=new HBox(8,inputField,actionButton);
        inputBar.setFillHeight(true);
        HBox.setHgrow(inputField, Priority.ALWAYS);
        return inputBar;
    }

    private void moveStageNextTo(Rectangle petBounds){
        Point2D location=calculateLocation(petBounds);
        stage.setX(location.getX());
        stage.setY(location.getY());
    }

    private Point2D calculateLocation(Rectangle petBounds){
        double x=petBounds.x+Math.max(0,petBounds.width-WINDOW_WIDTH)+DIALOG_RIGHT_SHIFT;
        double y=petBounds.y+petBounds.height-DIALOG_BOTTOM_OVERLAP;

        Screen screen=screenFor(petBounds);
        if(screen!=null){
            javafx.geometry.Rectangle2D visualBounds=screen.getVisualBounds();
            double minX=visualBounds.getMinX();
            double maxX=visualBounds.getMaxX()-WINDOW_WIDTH;

            if(x>maxX){
                x=petBounds.x+petBounds.width-WINDOW_WIDTH;
            }

            x=Math.max(minX,Math.min(x,maxX));
        }

        return new Point2D(x,y);
    }

    private Screen screenFor(Rectangle petBounds){
        List<Screen> screens=Screen.getScreensForRectangle(
                petBounds.x,
                petBounds.y,
                petBounds.width,
                petBounds.height
        );
        if(screens.isEmpty()){
            return Screen.getPrimary();
        }
        return screens.getFirst();
    }

    private void handleInputAction(){
        if(isAnswerBusy()){
            return;
        }

        sendCurrentQuestion();
    }

    private void handleActionButton(){
        if(isAnswerBusy()){
            return;
        }

        sendCurrentQuestion();
    }

    private void sendCurrentQuestion(){
        String question=inputField.getText().trim();
        if(question.isEmpty()){
            return;
        }
        if(isAnswerBusy()){
            return;
        }

        inputField.clear();

        ChatReply reply=chatService.getReply(question);
        answerSegments=splitAnswer(reply.getText());
        nextSegmentIndex=0;
        streamNextAnswerSegment();
        if(reply.hasVoice()){
            soundPlayer.playVoice(reply.getVoicePath());
        }
    }

    private boolean hasPendingSegment(){
        return nextSegmentIndex<answerSegments.size();
    }

    private boolean isAnswerBusy(){
        return streamingAnswer || waitingForNextSegment || hasPendingSegment();
    }

    private void streamNextAnswerSegment(){
        if(nextSegmentIndex>=answerSegments.size()){
            updateInputState();
            return;
        }

        waitingForNextSegment=false;
        String answerSegment=answerSegments.get(nextSegmentIndex);
        nextSegmentIndex++;
        streamAnswer(answerSegment);
    }

    private void streamAnswer(String answer){
        if(streamTimeline!=null){
            streamTimeline.stop();
        }
        if(segmentPause!=null){
            segmentPause.stop();
        }

        streamingAnswer=true;
        waitingForNextSegment=false;
        updateInputState();

        int[] codePoints=answer.codePoints().toArray();
        if(codePoints.length==0){
            answerLabel.setText("");
            streamingAnswer=false;
            continueOrFinishAnswer();
            return;
        }

        StringBuilder visibleText=new StringBuilder();
        int[] nextIndex={0};
        answerLabel.setText("");

        streamTimeline=new Timeline(new KeyFrame(STREAM_INTERVAL,event -> {
            visibleText.appendCodePoint(codePoints[nextIndex[0]]);
            nextIndex[0]++;
            answerLabel.setText(visibleText.toString());
        }));

        streamTimeline.setCycleCount(codePoints.length);
        streamTimeline.setOnFinished(event -> {
            streamingAnswer=false;
            continueOrFinishAnswer();
        });
        streamTimeline.play();
    }

    private void continueOrFinishAnswer(){
        if(hasPendingSegment()){
            scheduleNextAnswerSegment();
            return;
        }

        updateInputState();
        inputField.requestFocus();
    }

    private void scheduleNextAnswerSegment(){
        waitingForNextSegment=true;
        updateInputState();

        segmentPause=new PauseTransition(SEGMENT_PAUSE);
        segmentPause.setOnFinished(event -> streamNextAnswerSegment());
        segmentPause.play();
    }

    private void updateInputState(){
        if(actionButton==null){
            return;
        }

        boolean busy=streamingAnswer || waitingForNextSegment || hasPendingSegment();
        inputField.setDisable(busy);
        actionButton.setDisable(busy);
        actionButton.setText("问");
    }

    private List<String> splitAnswer(String answer){
        int[] codePoints=answer.codePoints().toArray();
        if(codePoints.length<=MAX_SINGLE_SEGMENT_CODE_POINTS){
            return List.of(answer);
        }

        int splitIndex=findSplitIndex(codePoints);
        String firstSegment=new String(codePoints,0,splitIndex).trim();
        String secondSegment=new String(codePoints,splitIndex,codePoints.length-splitIndex).trim();
        return List.of(firstSegment,secondSegment);
    }

    private int findSplitIndex(int[] codePoints){
        int middle=codePoints.length/2;
        int searchRadius=Math.min(18,middle);

        for(int offset=0;offset<=searchRadius;offset++){
            int forwardIndex=middle+offset;
            if(forwardIndex<codePoints.length-1 && isSegmentBreak(codePoints[forwardIndex])){
                return forwardIndex+1;
            }

            int backwardIndex=middle-offset;
            if(backwardIndex>0 && isSegmentBreak(codePoints[backwardIndex])){
                return backwardIndex+1;
            }
        }

        return middle;
    }

    private boolean isSegmentBreak(int codePoint){
        return "，。！？；、,.!?;".indexOf(codePoint)>=0;
    }
}
