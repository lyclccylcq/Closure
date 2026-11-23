package app.character.animation.spine;

import app.character.animation.AnimationPlayer;
import app.character.animation.AnimationType;
import javax.swing.*;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;

/** A single bounded render thread; Swing receives only completed, immutable frames. */
public final class SpineAnimationPlayer implements AnimationPlayer {
    private final JLabel label;
    private final int width;
    private final int height;
    private final SpineRenderer renderer;
    private final ScheduledExecutorService executor;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<BufferedImage> latest = new AtomicReference<>();
    private final AtomicBoolean publishQueued = new AtomicBoolean();
    private volatile boolean playing;
    private volatile boolean closed;
    private ScheduledFuture<?> timer;

    public SpineAnimationPlayer(JLabel label, int width, int height, SpineModel model) {
        this.label = Objects.requireNonNull(label);
        this.width = width;
        this.height = height;
        renderer = new SpineRenderer(Objects.requireNonNull(model));
        BufferedImage initial = renderer.render(AnimationType.IDLE, 0, width, height);
        label.setIcon(new ImageIcon(initial));
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "closure-spine-render");
            thread.setDaemon(true);
            return thread;
        });
    }

    @Override public void play(AnimationType animation) { start(animation, true, () -> { }); }
    @Override public void playOnce(AnimationType animation, Runnable completion) {
        start(animation, false, Objects.requireNonNull(completion));
    }

    private synchronized void start(AnimationType animation, boolean loop, Runnable completion) {
        Objects.requireNonNull(animation);
        if (closed) return;
        long token = generation.incrementAndGet();
        playing = true;
        executor.execute(() -> {
            cancelTimer();
            if (closed || token != generation.get()) return;
            long started = System.nanoTime();
            Runnable tick = () -> {
                if (closed || token != generation.get()) return;
                float elapsed = (System.nanoTime() - started) / 1_000_000_000f;
                float duration = renderer.duration(animation);
                float poseTime = loop ? elapsed % duration : Math.min(duration, elapsed);
                try {
                    latest.set(renderer.render(animation, poseTime, width, height));
                    publish(token);
                } catch (RuntimeException failure) {
                    cancelTimer();
                    playing = false;
                    System.err.println("Spine rendering stopped: " + failure.getMessage());
                    return;
                }
                if (!loop && elapsed >= duration) {
                    cancelTimer();
                    playing = false;
                    SwingUtilities.invokeLater(() -> {
                        if (!closed && token == generation.get()) completion.run();
                    });
                }
            };
            tick.run();
            if (playing && token == generation.get()) timer = executor.scheduleWithFixedDelay(tick, 33, 33, TimeUnit.MILLISECONDS);
        });
    }

    private void publish(long token) {
        if (!publishQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            publishQueued.set(false);
            if (!closed && token == generation.get()) label.setIcon(new ImageIcon(latest.get()));
        });
    }

    private void cancelTimer() {
        if (timer != null) { timer.cancel(false); timer = null; }
    }

    @Override
    public synchronized void stop() {
        if (closed) return;
        generation.incrementAndGet();
        playing = false;
        executor.execute(this::cancelTimer);
    }

    @Override public boolean isPlaying() { return playing; }

    @Override
    public synchronized void close() {
        if (closed) return;
        closed = true;
        generation.incrementAndGet();
        playing = false;
        executor.shutdownNow();
        latest.set(null);
    }

    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        return executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }
}
