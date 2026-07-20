package app.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * 一次发送给模型的请求。
 * 目前先支持基础字段：消息列表、可选模型名、可选 temperature。
 */
public final class AiChatRequest {
    private final List<AiChatMessage> messages;
    private final String model;
    private final Double temperature;

    public AiChatRequest(List<AiChatMessage> messages,String model,Double temperature){
        if(messages==null || messages.isEmpty()){
            throw new IllegalArgumentException("messages 不能为空");
        }

        this.messages=List.copyOf(messages);
        this.model=blankToNull(model);
        this.temperature=temperature;
    }

    /**
     * 最常用的单轮对话：一条 system prompt 加一条用户输入。
     */
    public static AiChatRequest singleTurn(String systemPrompt,String userText){
        List<AiChatMessage> messages=new ArrayList<>();
        if(systemPrompt!=null && !systemPrompt.isBlank()){
            messages.add(AiChatMessage.system(systemPrompt));
        }
        messages.add(AiChatMessage.user(Objects.requireNonNull(userText,"userText")));
        return new AiChatRequest(messages,null,null);
    }

    public AiChatRequest withModel(String model){
        return new AiChatRequest(messages,model,temperature);
    }

    public AiChatRequest withTemperature(double temperature){
        return new AiChatRequest(messages,model,temperature);
    }

    public List<AiChatMessage> getMessages(){
        return messages;
    }

    public String getModel(){
        return model;
    }

    public Double getTemperature(){
        return temperature;
    }

    private static String blankToNull(String value){
        if(value==null || value.isBlank()){
            return null;
        }
        return value;
    }
}
