package app.ui.chat;

final class ChatStyles {
    static final String BACKGROUND_STYLE="-fx-background-color: #080c12;";
    static final String BUBBLE_STYLE="""
            -fx-background-color: #121b27;
            -fx-border-color: #3aaed2;
            -fx-border-width: 1;
            -fx-background-radius: 6;
            -fx-border-radius: 6;
            -fx-padding: 8 10 8 10;
            -fx-text-fill: #eef6ff;
            -fx-font-size: 14px;
            """;
    static final String NAME_STYLE="-fx-text-fill: #eef6ff; -fx-font-size: 12px; -fx-font-weight: bold;";
    static final String INPUT_STYLE="""
            -fx-background-color: #0e1620;
            -fx-border-color: #30586c;
            -fx-border-width: 1;
            -fx-text-fill: #eef6ff;
            -fx-prompt-text-fill: #6f8a9b;
            -fx-font-size: 14px;
            """;
    static final String BUTTON_STYLE="""
            -fx-background-color: #122a38;
            -fx-border-color: #3ad6ff;
            -fx-border-width: 1;
            -fx-text-fill: #eef6ff;
            -fx-font-size: 14px;
            -fx-padding: 7 18 7 18;
            """;
    static final int AVATAR_SIZE=40;
    // JavaFX Label 会在超过这个宽度时自然换行，短消息则保持按内容自适应。
    static final double BUBBLE_MAX_WIDTH=280;

    private ChatStyles(){}
}
