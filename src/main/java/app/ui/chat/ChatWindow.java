package app.ui.chat;

import app.service.ChatService;
import javafx.application.Platform;
import javafx.embed.swing.JFXPanel;
import javafx.scene.Scene;

import javax.swing.*;
import java.awt.*;

public class ChatWindow extends JFrame {
    // JFXPanel 负责把 JavaFX Scene 嵌进现有 Swing JFrame，PetWindow 的调用方式就不用改。
    private final JFXPanel fxPanel;
    private final SoundPlayer soundPlayer;

    public ChatWindow() {
        fxPanel = new JFXPanel();
        soundPlayer = new SoundPlayer();

        initWindow();

        // 聊天窗口隐藏时不要关闭 JavaFX runtime，否则下次再打开窗口会失败。
        Platform.setImplicitExit(false);
        // JavaFX 节点必须在 JavaFX Application Thread 上创建。
        Platform.runLater(this::initFxContent);
    }

    @Override
    public void setVisible(boolean visible){
        boolean wasVisible=isVisible();
        super.setVisible(visible);

        // 每次聊天窗口从隐藏变成显示时播放打开提示音。
        if(visible && !wasVisible){
            soundPlayer.playOpen();
        }
    }

    private void initWindow(){
        setTitle("通讯频道：可露希尔");
        setSize(420,520);
        setLocationRelativeTo(null);
        setDefaultCloseOperation(JFrame.HIDE_ON_CLOSE);
        setLayout(new BorderLayout());
        add(fxPanel,BorderLayout.CENTER);
    }

    private void initFxContent(){
        ChatView chatView=new ChatView(new ChatService(),soundPlayer);
        fxPanel.setScene(new Scene(chatView));
    }
}
