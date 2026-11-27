package app.character.animation.sprite;

import app.character.animation.AnimationPlayer;
import app.character.animation.AnimationType;
import javax.swing.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.util.Objects;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/** One background decoder, bounded page cache and at most one pending EDT publication. */
public final class SpriteAnimationPlayer implements AnimationPlayer {
    private final JLabel label;
    private final int width, height;
    private final SpriteModel model;
    private final ScheduledExecutorService executor;
    private final AtomicLong generation = new AtomicLong();
    private final AtomicReference<Publication> latest = new AtomicReference<>();
    private final AtomicBoolean publishQueued = new AtomicBoolean();
    private volatile boolean playing, closed;
    private volatile BufferedImage visibleFrame;
    private volatile float dragMotion;
    private volatile String currentClip = "idle";
    private AnimationType requested;
    private boolean oneShot;
    private ScheduledFuture<?> timer;
    private int interactionVariant;
    private record Publication(long token, Icon icon, BufferedImage raw, String clip, Runnable completion) { }

    public SpriteAnimationPlayer(JLabel label, int width, int height, SpriteModel model) {
        this.label = Objects.requireNonNull(label);
        this.width = width; this.height = height; this.model = Objects.requireNonNull(model);
        visibleFrame = model.frame("idle", 0);
        label.setIcon(new FrameIcon(visibleFrame, null, 1, 0, 0));
        executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread worker = new Thread(task, "closure-sprite-render"); worker.setDaemon(true); return worker;
        });
        System.out.println("CLOSURE_NEW_MODEL_READY renderer=sprite fps=" + model.fps()
                + " synthetic=" + model.synthetic() + " commit=" + model.commit());
    }

    @Override public void play(AnimationType animation) { start(animation, false, null); }
    @Override public void playOnce(AnimationType animation, Runnable completion) {
        start(animation, true, Objects.requireNonNull(completion));
    }

    private synchronized void start(AnimationType animation, boolean once, Runnable completion) {
        Objects.requireNonNull(animation);
        if (closed || (!once && !oneShot && playing && requested == animation)) return;
        long token = generation.incrementAndGet();
        int variant = animation == AnimationType.TAP ? interactionVariant++ : 0;
        SpriteSequence sequence = new SpriteSequence(model, requested, animation, once, variant);
        requested = animation; oneShot = once; playing = true;
        BufferedImage previous = visibleFrame;
        executor.execute(() -> {
            cancelTimer();
            if (closed || token != generation.get()) return;
            long started = System.nanoTime();
            Runnable tick = () -> {
                if (closed || token != generation.get()) return;
                long elapsed = System.nanoTime() - started;
                var sample = sequence.sample(elapsed);
                try {
                    BufferedImage frame = model.frame(sample.clip(), sample.frame());
                    float fade = Math.min(1, elapsed / 160_000_000f);
                    float angle = animation == AnimationType.DRAGGING ? dragMotion * .045f : 0;
                    float bounce = animation == AnimationType.LANDING
                            ? (float) (-4 * Math.sin(Math.min(1, elapsed / 200_000_000.0) * Math.PI)) : 0;
                    Runnable finished = sample.finished() ? completion : null;
                    synchronized (SpriteAnimationPlayer.this) {
                        if (closed || token != generation.get()) return;
                        latest.set(new Publication(token, new FrameIcon(frame, fade < 1 ? previous : null, fade, angle, bounce),
                                frame, sample.clip(), finished));
                        if (sample.finished()) { cancelTimer(); playing = false; }
                    }
                    publish();
                } catch (RuntimeException failure) {
                    synchronized (SpriteAnimationPlayer.this) {
                        if (closed || token != generation.get()) return;
                        cancelTimer(); playing = false;
                    }
                    System.err.println("New model playback stopped: " + failure.getMessage());
                    if (completion != null) SwingUtilities.invokeLater(() -> {
                        if (!closed && token == generation.get()) completion.run();
                    });
                }
            };
            tick.run();
            if (playing && token == generation.get()) timer = executor.scheduleWithFixedDelay(tick,
                    Math.max(1, 1000 / model.fps()), Math.max(1, 1000 / model.fps()), TimeUnit.MILLISECONDS);
        });
    }

    private void publish() {
        if (!publishQueued.compareAndSet(false, true)) return;
        SwingUtilities.invokeLater(() -> {
            publishQueued.set(false);
            Publication frame = latest.getAndSet(null);
            if (frame != null && !closed && frame.token() == generation.get()) {
                label.setIcon(frame.icon()); visibleFrame = frame.raw(); currentClip = frame.clip();
                if (frame.completion() != null) frame.completion().run();
            }
        });
    }

    private void cancelTimer() { if (timer != null) { timer.cancel(false); timer = null; } }
    @Override public synchronized void stop() {
        if (closed) return;
        generation.incrementAndGet(); playing = false; latest.set(null); executor.execute(this::cancelTimer);
    }
    @Override public boolean isPlaying() { return playing; }
    @Override public boolean supportsWalking() { return false; }
    @Override public void setDragMotion(float horizontal) {
        dragMotion = Float.isFinite(horizontal) ? Math.max(-1, Math.min(1, horizontal)) : 0;
    }
    public String currentClip() { return currentClip; }
    public SpriteModel model() { return model; }
    @Override public synchronized void close() {
        if (closed) return;
        closed = true; playing = false; generation.incrementAndGet(); latest.set(null); visibleFrame = null;
        executor.shutdownNow(); model.clearCache();
    }
    public boolean awaitClosed(long timeoutMillis) throws InterruptedException {
        return executor.awaitTermination(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    private final class FrameIcon implements Icon {
        private final BufferedImage frame, previous;
        private final float fade, angle, bounce;
        FrameIcon(BufferedImage frame, BufferedImage previous, float fade, float angle, float bounce) {
            this.frame = frame; this.previous = previous; this.fade = fade; this.angle = angle; this.bounce = bounce;
        }
        public int getIconWidth() { return width; }
        public int getIconHeight() { return height; }
        public void paintIcon(Component component, Graphics graphics, int x, int y) {
            Graphics2D g = (Graphics2D) graphics.create();
            try {
                g.translate(x, y + bounce); g.rotate(angle, width / 2.0, height / 2.0);
                g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
                if (previous != null) draw(g, previous, 1 - fade);
                draw(g, frame, previous == null ? 1 : fade);
            } finally { g.dispose(); }
        }
        private void draw(Graphics2D g, BufferedImage image, float opacity) {
            double scale = Math.min((double) width / image.getWidth(), (double) height / image.getHeight());
            int w = (int) Math.round(image.getWidth() * scale), h = (int) Math.round(image.getHeight() * scale);
            g.setComposite(AlphaComposite.SrcOver.derive(opacity));
            g.drawImage(image, (width - w) / 2, (height - h) / 2, w, h, null);
        }
    }
}
