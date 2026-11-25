package app.character;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.Objects;
import java.util.function.DoubleSupplier;
import java.util.function.LongSupplier;

/** Idle walking decisions and geometry only; the Swing window applies each step on the EDT. */
public final class DesktopRoamingController implements AutoCloseable {
    private static final long MIN_REST_NANOS = Duration.ofSeconds(20).toNanos();
    private static final long REST_RANGE_NANOS = Duration.ofSeconds(15).toNanos();
    private static final long MIN_WALK_NANOS = Duration.ofSeconds(4).toNanos();
    private static final long WALK_RANGE_NANOS = Duration.ofSeconds(3).toNanos();
    private static final double PIXELS_PER_SECOND = 40;
    private static final double MAX_STEP_SECONDS = .1;

    public record Step(int x, int y, boolean walking, boolean facingLeft) { }

    private final LongSupplier clock;
    private final DoubleSupplier random;
    private boolean enabled = true;
    private boolean moving;
    private boolean requested;
    private boolean blocked;
    private boolean closed;
    private int direction = 1;
    private int lastX;
    private int lastY;
    private double x;
    private long lastTick;
    private long nextStart;
    private long walkUntil;

    public DesktopRoamingController() {
        this(System::nanoTime, Math::random);
    }

    public DesktopRoamingController(LongSupplier clock, DoubleSupplier random) {
        this.clock = Objects.requireNonNull(clock, "clock");
        this.random = Objects.requireNonNull(random, "random");
        pause();
    }

    public boolean isEnabled() { return enabled; }

    public void setEnabled(boolean enabled) {
        if (closed) return;
        this.enabled = enabled;
        pause();
    }

    /** An explicit user command may walk once even when automatic wandering is disabled. */
    public void requestWalk() {
        if (!closed) requested = true;
    }

    public void pause() {
        if (closed) return;
        moving = false;
        requested = false;
        blocked = false;
        lastTick = clock.getAsLong();
        nextStart = lastTick + restDelay();
    }

    public Step tick(Rectangle pet, Rectangle workArea, boolean available) {
        Objects.requireNonNull(pet, "pet");
        Objects.requireNonNull(workArea, "workArea");
        if (closed) return still(pet);
        long now = clock.getAsLong();
        if (!available || pet.width <= 0 || pet.height <= 0
                || workArea.width <= pet.width || workArea.height < pet.height) {
            moving = false;
            blocked = true;
            lastTick = now;
            return still(pet);
        }
        if (blocked) {
            blocked = false;
            nextStart = now + restDelay();
            lastTick = now;
        }
        if (moving && (pet.x != lastX || pet.y != lastY)) {
            // A drag, display change or external window relocation wins over our old trajectory.
            pause();
            return still(pet);
        }
        if (moving && now - walkUntil >= 0) {
            pause();
            return still(pet);
        }
        int minX = workArea.x;
        int maxX = workArea.x + workArea.width - pet.width;
        if (!moving) {
            if (!requested && (!enabled || now - nextStart < 0)) return still(pet);
            moving = true;
            requested = false;
            direction = random.getAsDouble() < .5 ? -1 : 1;
            x = Math.max(minX, Math.min(pet.x, maxX));
            lastY = Math.max(workArea.y, Math.min(pet.y,
                    workArea.y + Math.max(0, workArea.height - pet.height)));
            walkUntil = now + MIN_WALK_NANOS + (long) (random.getAsDouble() * WALK_RANGE_NANOS);
            lastTick = now;
        }
        double seconds = Math.max(0, Math.min(MAX_STEP_SECONDS, (now - lastTick) / 1_000_000_000.0));
        lastTick = now;
        x += direction * PIXELS_PER_SECOND * seconds;
        if (x <= minX) { x = minX; direction = 1; }
        if (x >= maxX) { x = maxX; direction = -1; }
        lastY = Math.max(workArea.y, Math.min(lastY,
                workArea.y + Math.max(0, workArea.height - pet.height)));
        lastX = (int) Math.round(x);
        return new Step(lastX, lastY, true, direction < 0);
    }

    private long restDelay() {
        return MIN_REST_NANOS + (long) (random.getAsDouble() * REST_RANGE_NANOS);
    }

    private Step still(Rectangle pet) { return new Step(pet.x, pet.y, false, direction < 0); }

    @Override
    public void close() {
        closed = true;
        moving = false;
        requested = false;
    }
}
