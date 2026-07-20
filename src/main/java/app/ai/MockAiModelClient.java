package app.ai;

import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 本地假模型客户端。
 * 用它可以先调通 UI 的流式显示，不需要网络，也不需要 API Key。
 */
public final class MockAiModelClient implements AiModelClient {
    private static final int TOKEN_DELAY_MILLIS=45;

    private final ExecutorService executor=Executors.newSingleThreadExecutor(command -> {
        Thread thread=new Thread(command,"mock-ai-model-client");
        thread.setDaemon(true);
        return thread;
    });

    @Override
    public void streamChat(AiChatRequest request,AiStreamListener listener){
        Objects.requireNonNull(request,"request");
        Objects.requireNonNull(listener,"listener");

        executor.submit(() -> {
            try{
                String answer=createMockAnswer(request);
                answer.codePoints().forEach(codePoint -> {
                    sleepQuietly();
                    listener.onToken(new String(Character.toChars(codePoint)));
                });
                listener.onComplete();
            }catch(Throwable error){
                listener.onError(error);
            }
        });
    }

    @Override
    public void close(){
        executor.shutdownNow();
    }

    private String createMockAnswer(AiChatRequest request){
        String userText=request.getMessages().getLast().getContent();
        return "工程部收到：" + userText + "。先别急，我正在检查参数和上下文。";
    }

    private void sleepQuietly(){
        try{
            Thread.sleep(TOKEN_DELAY_MILLIS);
        }catch(InterruptedException exception){
            Thread.currentThread().interrupt();
            throw new AiModelException("假模型流式输出被中断",exception);
        }
    }
}
