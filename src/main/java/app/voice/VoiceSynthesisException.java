package app.voice;

/**
 * 语音合成链路统一异常。
 */
public class VoiceSynthesisException extends RuntimeException {
    public VoiceSynthesisException(String message) {
        super(message);
    }

    public VoiceSynthesisException(String message, Throwable cause) {
        super(message, cause);
    }
}
