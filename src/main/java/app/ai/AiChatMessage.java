package app.ai;

import java.util.Objects;

/**
 * 表示一次模型对话里的单条消息。
 * 这里不绑定任何 UI 组件，只保存模型需要的 role 和 content。
 */
public final class AiChatMessage {
    private final AiChatRole role;
    private final String content;

    public AiChatMessage(AiChatRole role,String content){
        this.role=Objects.requireNonNull(role,"role");
        this.content=Objects.requireNonNull(content,"content");
    }

    public static AiChatMessage system(String content){
        return new AiChatMessage(AiChatRole.SYSTEM,content);
    }

    public static AiChatMessage user(String content){
        return new AiChatMessage(AiChatRole.USER,content);
    }

    public static AiChatMessage assistant(String content){
        return new AiChatMessage(AiChatRole.ASSISTANT,content);
    }

    public AiChatRole getRole(){
        return role;
    }

    public String getContent(){
        return content;
    }
}
