package app.ai;

/** Cancellation belongs to one transport attempt, not to the whole client. */
@FunctionalInterface
public interface AiStreamCall {
    AiStreamCall NONE = () -> { };

    void cancel();
}
