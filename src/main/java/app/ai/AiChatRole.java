package app.ai;

/**
 * 模型接口使用的消息角色。
 * wireValue 是发送到 OpenAI-compatible 接口时使用的字符串。
 */
public enum AiChatRole {
    SYSTEM("system"),
    USER("user"),
    ASSISTANT("assistant");

    private final String wireValue;

    AiChatRole(String wireValue){
        this.wireValue=wireValue;
    }

    public String wireValue(){
        return wireValue;
    }
}
