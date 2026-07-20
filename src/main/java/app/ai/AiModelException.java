package app.ai;

/**
 * AI 接入层统一异常。
 * 用 RuntimeException 是为了让 UI 层可以选择统一在 onError 回调里展示错误。
 */
public class AiModelException extends RuntimeException {
    public AiModelException(String message){
        super(message);
    }

    public AiModelException(String message,Throwable cause){
        super(message,cause);
    }
}
