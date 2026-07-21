package app.service;

/**
 * ChatService 对 UI 暴露的流式回复回调。
 * 回调可能来自后台线程，UI 层更新界面时需要切回自己的 UI 线程。
 */
public interface ChatStreamListener {
    void onToken(String token);

    default void onComplete(){
    }

    default void onError(Throwable error){
    }
}
