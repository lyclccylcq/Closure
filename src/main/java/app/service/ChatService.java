package app.service;

import app.ai.AiChatRequest;
import app.ai.AiModelClient;
import app.ai.AiModelConfig;
import app.ai.AiStreamListener;
import app.ai.OpenAiCompatibleChatClient;

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
    private static final String AI_UNAVAILABLE_REPLY="通讯模块还没接上。检查一下本地 AI 配置，别让我对着空气发报。";

    private final AiModelClient aiClient;
    private final ExecutorService fallbackExecutor;
    private final String systemPrompt;
    private final PromptEnvironmentContext environmentContext;

    public ChatService(){
        aiClient=createAiClient();
        systemPrompt=loadSystemPrompt();
        environmentContext=new PromptEnvironmentContext();
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
                .singleTurn(systemPromptForCurrentRequest(),input)
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

    public void close(){
        if(aiClient!=null){
            aiClient.close();
        }
        fallbackExecutor.shutdownNow();
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
                int[] codePoints=AI_UNAVAILABLE_REPLY.codePoints().toArray();
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

    private String systemPromptForCurrentRequest() {
        return systemPrompt + environmentContext.currentContextPrompt();
    }
}
