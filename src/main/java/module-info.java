module com.example.closure {
    requires javafx.controls;
    requires javafx.fxml;
    // JFXPanel 用来把 JavaFX 聊天界面嵌进现有 Swing JFrame。
    requires javafx.swing;
    requires java.desktop;


    opens com.example.closure to javafx.fxml;
}