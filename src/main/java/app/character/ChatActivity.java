package app.character;

import app.voice.model.SpeechEmotion;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

/** Pairs overlapping chat requests and releases them exactly once, including on window disposal. */
public final class ChatActivity implements AutoCloseable {
    private final CharacterActivityListener listener;
    private final Set<Request> pending = new HashSet<>();
    private boolean closed;

    public ChatActivity(CharacterActivityListener listener) {
        this.listener = listener == null ? CharacterActivityListener.NONE : listener;
    }

    public synchronized Request start() {
        Request request = new Request(closed ? () -> { } : listener.thinkingStarted());
        if (!closed) pending.add(request);
        return request;
    }

    public final class Request implements AutoCloseable {
        private final Runnable finish;
        private final AtomicBoolean finished = new AtomicBoolean();
        private Request(Runnable finish) { this.finish = finish; }
        public void complete(SpeechEmotion emotion) { end(emotion); }
        private void end(SpeechEmotion emotion) {
            synchronized (ChatActivity.this) {
                if (!finished.compareAndSet(false, true)) return;
                pending.remove(this);
                finish.run();
                if (!closed && emotion != null) listener.replyEmotion(emotion);
            }
        }
        @Override public void close() { end(null); }
    }

    @Override
    public synchronized void close() {
        closed = true;
        for (Request request : Set.copyOf(pending)) request.close();
        pending.clear();
    }
}
