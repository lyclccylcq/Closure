package app.ai;

/**
 * 流式输出回调。
 * onToken 会被调用多次；onComplete 表示本次回复完整结束；onError 表示请求失败。
 */
public interface AiStreamListener {
    void onToken(String token);

    default void onComplete(){
    }

    default void onError(Throwable error){
    }
}
