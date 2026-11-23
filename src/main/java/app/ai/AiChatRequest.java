package app.ai;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.Locale;

/**
 * 一次发送给模型的请求。
 * 消息列表、可选模型名、temperature 和逐请求推理强度。
 */
public final class AiChatRequest {
    private final List<AiChatMessage> messages;
    private final String model;
    private final Double temperature;
    private final String reasoningEffort;

    public AiChatRequest(List<AiChatMessage> messages,String model,Double temperature){
        this(messages,model,temperature,null);
    }

    public AiChatRequest(List<AiChatMessage> messages,String model,Double temperature,String reasoningEffort){
        if(messages==null || messages.isEmpty()){
            throw new IllegalArgumentException("messages 不能为空");
        }

        this.messages=List.copyOf(messages);
        this.model=blankToNull(model);
        this.temperature=temperature;
        this.reasoningEffort=normalizeReasoningEffort(reasoningEffort);
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
        return new AiChatRequest(messages,model,temperature,reasoningEffort);
    }

    public AiChatRequest withTemperature(double temperature){
        return new AiChatRequest(messages,model,temperature,reasoningEffort);
    }

    public AiChatRequest withReasoningEffort(String reasoningEffort){
        return new AiChatRequest(messages,model,temperature,reasoningEffort);
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

    public String getReasoningEffort(){
        return reasoningEffort;
    }

    static String normalizeReasoningEffort(String value){
        if(value==null || value.isBlank() || value.trim().equalsIgnoreCase("default")){
            return null;
        }
        String effort=value.trim().toLowerCase(Locale.ROOT);
        if(!Set.of("none","minimal","low","medium","high","xhigh","max").contains(effort)){
            throw new IllegalArgumentException("不支持的 API 推理强度：" + value);
        }
        return effort;
    }

    private static String blankToNull(String value){
        if(value==null || value.isBlank()){
            return null;
        }
        return value;
    }
}
