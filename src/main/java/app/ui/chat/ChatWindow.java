package app.ui.chat;

import app.service.ChatService;
import app.service.ChatSpeechController;
import app.ui.UiFonts;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;

import javax.swing.*;
import java.awt.*;

public class ChatWindow extends JFrame {
    private static final int DEFAULT_WIDTH = 560;
    private static final int DEFAULT_HEIGHT = 680;
    private static final int MIN_WIDTH = 480;
    private static final int MIN_HEIGHT = 560;
    private static final int ANCHOR_GAP = 18;

    // JFXPanel 负责把 JavaFX Scene 嵌进现有 Swing JFrame，PetWindow 的调用方式就不用改。
    private final JFXPanel fxPanel;
    private final SoundPlayer soundPlayer;
    private final ChatSpeechController chatSpeechController;
    private final boolean ownsSpeechController;
    private ChatView chatView;
    private Rectangle anchorBounds;
    private boolean disposed;

    public ChatWindow() {
        this(ChatSpeechController.fromEnvironment(), true);
    }

    public ChatWindow(ChatSpeechController chatSpeechController) {
        this(chatSpeechController, false);
    }

    private ChatWindow(ChatSpeechController chatSpeechController, boolean ownsSpeechController) {
        UiFonts.applySwingDefaults();
        fxPanel = new JFXPanel();
        soundPlayer = new SoundPlayer();
        this.chatSpeechController = chatSpeechController;
        this.ownsSpeechController = ownsSpeechController;

        initWindow();

        // 聊天窗口隐藏时不要关闭 JavaFX runtime，否则下次再打开窗口会失败。
        Platform.setImplicitExit(false);
        // JavaFX 节点必须在 JavaFX Application Thread 上创建。
        Platform.runLater(this::initFxContent);
    }

    @Override
    public void setVisible(boolean visible){
        boolean wasVisible=isVisible();

        if(visible && !wasVisible){
            positionForDisplay();
        }

        super.setVisible(visible);

        // 每次聊天窗口从隐藏变成显示时，把消息列表滚到最新位置。
        if(visible && !wasVisible){
            Platform.runLater(() -> {
                if(chatView!=null){
                    chatView.scrollToLatestMessage();
                }
            });
        }
    }

    public void showNear(Rectangle anchorBounds){
        this.anchorBounds=anchorBounds==null ? null : new Rectangle(anchorBounds);
        if(isVisible()){
            positionForDisplay();
        }
        setVisible(true);
    }

    private void initWindow(){
        setTitle("通讯频道：可露希尔");
        setFont(UiFonts.preferredSwingFont(Font.PLAIN,13));
        setSize(DEFAULT_WIDTH,DEFAULT_HEIGHT);
        setMinimumSize(new Dimension(MIN_WIDTH,MIN_HEIGHT));
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(fxPanel,BorderLayout.CENTER);
        setLocationRelativeTo(null);
    }

    private void initFxContent(){
        chatView=new ChatView(new ChatService(),soundPlayer,chatSpeechController);
        fxPanel.setScene(new Scene(chatView));
    }

    private void positionForDisplay(){
        if(anchorBounds==null){
            setLocationRelativeTo(null);
            return;
        }

        Rectangle usableBounds=usableScreenBounds(anchorBounds);
        int windowWidth=Math.max(getWidth(),MIN_WIDTH);
        int windowHeight=Math.max(getHeight(),MIN_HEIGHT);
        int leftSpace=anchorBounds.x - usableBounds.x - ANCHOR_GAP;
        int rightSpace=usableBounds.x + usableBounds.width - (anchorBounds.x + anchorBounds.width) - ANCHOR_GAP;

        int x;
        if(leftSpace>=windowWidth){
            x=anchorBounds.x - windowWidth - ANCHOR_GAP;
        }else if(rightSpace>=windowWidth){
            x=anchorBounds.x + anchorBounds.width + ANCHOR_GAP;
        }else if(leftSpace>=rightSpace){
            x=usableBounds.x;
        }else{
            x=usableBounds.x + usableBounds.width - windowWidth;
        }

        int anchorCenterY=anchorBounds.y + anchorBounds.height / 2;
        int y=anchorCenterY - windowHeight / 2;
        setLocation(
                clamp(x,usableBounds.x,usableBounds.x + usableBounds.width - windowWidth),
                clamp(y,usableBounds.y,usableBounds.y + usableBounds.height - windowHeight)
        );
    }

    private Rectangle usableScreenBounds(Rectangle anchorBounds){
        GraphicsConfiguration graphicsConfiguration=graphicsConfigurationFor(anchorBounds);
        if(graphicsConfiguration==null){
            Dimension screenSize=Toolkit.getDefaultToolkit().getScreenSize();
            return new Rectangle(0,0,screenSize.width,screenSize.height);
        }

        Rectangle bounds=graphicsConfiguration.getBounds();
        Insets insets=Toolkit.getDefaultToolkit().getScreenInsets(graphicsConfiguration);
        return new Rectangle(
                bounds.x + insets.left,
                bounds.y + insets.top,
                bounds.width - insets.left - insets.right,
                bounds.height - insets.top - insets.bottom
        );
    }

    private GraphicsConfiguration graphicsConfigurationFor(Rectangle anchorBounds){
        Point anchorCenter=new Point(
                anchorBounds.x + anchorBounds.width / 2,
                anchorBounds.y + anchorBounds.height / 2
        );
        for(GraphicsDevice screenDevice:GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices()){
            GraphicsConfiguration configuration=screenDevice.getDefaultConfiguration();
            if(configuration.getBounds().contains(anchorCenter)){
                return configuration;
            }
        }

        return getGraphicsConfiguration();
    }

    private int clamp(int value,int min,int max){
        if(max<min){
            return min;
        }

        return Math.max(min,Math.min(value,max));
    }

    @Override
    public void dispose(){
        disposeResources();
        super.dispose();
    }

    private void disposeResources(){
        if(disposed){
            return;
        }
        disposed=true;

        soundPlayer.stopVoice();
        if(chatView!=null){
            chatView.dispose();
        }
        if(ownsSpeechController){
            chatSpeechController.close();
        }
    }
}
