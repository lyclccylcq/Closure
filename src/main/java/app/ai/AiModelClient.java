package app.ai;

/**
 * 所有 AI 模型客户端的统一入口。
 * UI 层只依赖这个接口，后面可以自由切换真实模型、Ollama、本地 mock 等实现。
 */
public interface AiModelClient extends AutoCloseable {
    /**
     * 以流式方式请求模型。
     * 注意：回调通常发生在后台线程里，JavaFX UI 更新要使用 Platform.runLater。
     */
    void streamChat(AiChatRequest request,AiStreamListener listener);

    @Override
    default void close(){
    }
}
