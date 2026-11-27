package app.character.animation.sprite;

import app.character.animation.AnimationType;
import java.util.ArrayList;
import java.util.List;

/** Time-based sampling skips late frames without queuing a backlog on Swing. */
public final class SpriteSequence {
    public record Sample(String clip, int frame, boolean finished) { }
    private record Segment(String clip, int frames) { }
    private final List<Segment> segments;
    private final int fps;
    private final boolean once;

    public SpriteSequence(SpriteModel model, AnimationType previous, AnimationType target, boolean once, int variant) {
        fps = model.fps();
        this.once = once;
        List<String> names = new ArrayList<>();
        if (target == AnimationType.SAD) {
            names.addAll(List.of("sad_begin", "sad_idle", "sad_exit"));
        } else if (target == AnimationType.THINKING) {
            names.addAll(List.of("think_begin", "think_idle"));
        } else {
            // Speech begins immediately. Authored exit clips must not postpone TTS feedback.
            if (!once && previous == AnimationType.THINKING && target != AnimationType.TALK
                    && target != AnimationType.THINKING_TALK && target != AnimationType.PRESSED
                    && target != AnimationType.DRAGGING) names.add("think_exit");
            names.add(switch (target) {
                case TALK, THINKING_TALK -> "talk";
                case TAP -> variant % 2 == 0 ? "interact_1" : "interact_2";
                case GREETING -> "celebrate";
                case HAPPY -> "happy";
                default -> "idle";
            });
        }
        segments = names.stream().map(name -> new Segment(name,
                target == AnimationType.LANDING ? Math.min(model.frames(name), Math.max(1, fps / 5)) : model.frames(name))).toList();
    }

    public Sample sample(long elapsedNanos) {
        long elapsed = Math.max(0, elapsedNanos);
        long frame = elapsed / 1_000_000_000L * fps + elapsed % 1_000_000_000L * fps / 1_000_000_000L;
        for (int i = 0; i < segments.size(); i++) {
            Segment segment = segments.get(i);
            boolean last = i == segments.size() - 1;
            if (frame < segment.frames()) return new Sample(segment.clip(), (int) frame, false);
            if (last) return once ? new Sample(segment.clip(), segment.frames() - 1, true)
                    : new Sample(segment.clip(), (int) (frame % segment.frames()), false);
            frame -= segment.frames();
        }
        throw new IllegalStateException("Empty sequence");
    }
}
