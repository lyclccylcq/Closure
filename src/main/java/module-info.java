module com.example.closure {
    requires javafx.controls;
    requires javafx.fxml;
    // JFXPanel 用来把 JavaFX 聊天界面嵌进现有 Swing JFrame。
    requires javafx.swing;
    // SoundPlayer 使用 JavaFX Media/AudioClip 播放提示音和配音。
    requires javafx.media;
    // AI 模型客户端使用 JDK 自带 HttpClient 调用 OpenAI-compatible 接口。
    requires java.net.http;
    // 课程表 .xlsx 导入使用 JDK XML 解析工作表内容。
    requires java.xml;
    requires java.desktop;
    // 单元测试使用 JDK HttpServer mock GPT-SoVITS HTTP API。
    requires static jdk.httpserver;


    opens com.example.closure to javafx.fxml;
}
