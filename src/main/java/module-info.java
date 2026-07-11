module com.example.closure {
    requires javafx.controls;
    requires javafx.fxml;
    // JFXPanel 用来把 JavaFX 聊天界面嵌进现有 Swing JFrame。
    requires javafx.swing;
    // SoundPlayer 使用 JavaFX Media/AudioClip 播放提示音和配音。
    requires javafx.media;
    requires java.desktop;


    opens com.example.closure to javafx.fxml;
}