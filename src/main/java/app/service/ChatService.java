package app.service;

import app.ai.AiChatRequest;
import app.ai.AiModelClient;
import app.ai.AiModelConfig;
import app.ai.AiStreamListener;
import app.ai.OpenAiCompatibleChatClient;
import app.chat.model.ChatReply;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class ChatService {
    private static final int FALLBACK_TOKEN_DELAY_MILLIS=45;
    private static final String SYSTEM_PROMPT_PATH="/prompts/closure-system-prompt.txt";
    private static final String DEFAULT_SYSTEM_PROMPT="你是可露希尔，罗德岛工程部成员，也是博士桌面上的工程支援助手。";

    private final AiModelClient aiClient;
    private final ExecutorService fallbackExecutor;
    private final String systemPrompt;

    public ChatService(){
        aiClient=createAiClient();
        systemPrompt=loadSystemPrompt();
        fallbackExecutor=Executors.newSingleThreadExecutor(command -> {
            Thread thread=new Thread(command,"chat-service-fallback");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * 以流式方式回复用户。
     * 配置了 AI 时走真实模型；没有配置时退回本地假回复，方便继续离线开发 UI。
     */
    public void streamReply(String input,ChatStreamListener listener){
        Objects.requireNonNull(input,"input");
        Objects.requireNonNull(listener,"listener");

        if(aiClient==null){
            streamFallbackReply(input,listener);
            return;
        }

        AiChatRequest request=AiChatRequest
                .singleTurn(systemPrompt,input)
                .withTemperature(0.8);

        aiClient.streamChat(request,new AiStreamListener() {
            @Override
            public void onToken(String token){
                listener.onToken(token);
            }

            @Override
            public void onComplete(){
                listener.onComplete();
            }

            @Override
            public void onError(Throwable error){
                listener.onError(error);
            }
        });
    }

    public ChatReply getReply(String input){
        if(input.contains("你好")){
            // 每条假回复把文字和语音资源路径放在一起，后续新增语音只需要补这里。
            return new ChatReply("无人机整备完毕。离出发还有多长时间？我看看能不能补个觉......","/audio/hello.wav");
        }
        if (input.contains("累")){
            return new ChatReply("博士，你这次体检的指标怎么回事？什么，觉得最近很虚弱，怕自己时日无多？！我这就把这些年多收你的钱给医疗部当特别经费，放心，一块钱都没动过——报告是假的？逗我玩？华法琳！","/audio/tired.wav");
        }
        if (input.contains("饿")){
            return new ChatReply("烧烤味饼干，来一片？","/audio/hungry.wav");
        }
        if (input.contains("生日")){
            return new ChatReply("罗德岛干员过生日均有机会获得采购部免单券，你也不例外！来这里摇奖，免单券就在特等奖等着你！什么？谁中过特等奖？这个——别担心，第一位特等奖得主没准就是你！","/audio/birthday.wav");
        }
        if (input.contains("飞起来")){
            return new ChatReply("全体无人机，挂钩释放，功率解锁，钩住博士的兜帽——起飞!","/audio/fly.wav");
        }

        return new ChatReply("别急，我还在算呢。","/audio/touch.wav");
    }

    private AiModelClient createAiClient(){
        try{
            return new OpenAiCompatibleChatClient(AiModelConfig.fromEnvironment());
        }catch(RuntimeException exception){
            System.out.println("AI 配置不可用，使用本地假回复：" + exception.getMessage());
            return null;
        }
    }

    private void streamFallbackReply(String input,ChatStreamListener listener){
        fallbackExecutor.submit(() -> {
            try{
                String text=getReply(input).getText();
                int[] codePoints=text.codePoints().toArray();
                for(int codePoint:codePoints){
                    Thread.sleep(FALLBACK_TOKEN_DELAY_MILLIS);
                    listener.onToken(new String(Character.toChars(codePoint)));
                }
                listener.onComplete();
            }catch(InterruptedException exception){
                Thread.currentThread().interrupt();
                listener.onError(exception);
            }catch(RuntimeException exception){
                listener.onError(exception);
            }
        });
    }

    private String loadSystemPrompt(){
        try(InputStream inputStream=ChatService.class.getResourceAsStream(SYSTEM_PROMPT_PATH)){
            if(inputStream==null){
                System.out.println("角色 prompt 不存在，使用默认 prompt：" + SYSTEM_PROMPT_PATH);
                return DEFAULT_SYSTEM_PROMPT;
            }

            String prompt=new String(inputStream.readAllBytes(), StandardCharsets.UTF_8).trim();
            if(prompt.isBlank()){
                System.out.println("角色 prompt 为空，使用默认 prompt：" + SYSTEM_PROMPT_PATH);
                return DEFAULT_SYSTEM_PROMPT;
            }

            return prompt;
        }catch(IOException exception){
            System.out.println("读取角色 prompt 失败，使用默认 prompt：" + exception.getMessage());
            return DEFAULT_SYSTEM_PROMPT;
        }
    }
}
