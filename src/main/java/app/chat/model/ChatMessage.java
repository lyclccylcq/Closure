package app.chat.model;

public class ChatMessage {

    private final MessageRole role;
    private final String content;

    public ChatMessage(MessageRole role ,String content){
        this.role=role;
        this.content=content;
    }

    public  MessageRole getRole(){
        return role;
    }

    public String getContent(){
        return content;
    }
}
