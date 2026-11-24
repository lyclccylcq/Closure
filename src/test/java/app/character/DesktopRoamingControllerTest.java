package app.character;

import org.junit.jupiter.api.Test;

import java.awt.Rectangle;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.*;

class DesktopRoamingControllerTest {
    private final AtomicLong now = new AtomicLong();
    private final Rectangle area = new Rectangle(0, 24, 1400, 850);
    private final Rectangle pet = new Rectangle(500, 120, 220, 220);
    private final DesktopRoamingController roaming = new DesktopRoamingController(now::get, () -> 0);

    private DesktopRoamingController.Step tick(boolean available) {
        var step = roaming.tick(pet, area, available);
        pet.setLocation(step.x(), step.y());
        return step;
    }

    @Test
    void automaticBoutsAlternateWithRestAndPreserveFractionalElapsedMotion() {
        assertFalse(tick(true).walking());
        now.set(Duration.ofSeconds(20).toNanos() - 1);
        assertFalse(tick(true).walking());
        now.incrementAndGet();
        assertTrue(tick(true).walking());
        for (int frame = 0; frame < 60; frame++) {
            now.addAndGet(16_666_667);
            assertTrue(tick(true).walking());
        }
        assertEquals(460, pet.x);
        assertEquals(120, pet.y);
        now.set(Duration.ofSeconds(24).toNanos());
        assertFalse(tick(true).walking());
        int stopped = pet.x;
        now.set(Duration.ofSeconds(43).toNanos());
        assertFalse(tick(true).walking());
        assertEquals(stopped, pet.x);
        now.set(Duration.ofSeconds(44).toNanos());
        assertTrue(tick(true).walking());
    }

    @Test
    void bothEdgesReverseDirectionWithinANegativeCoordinateMonitor() {
        area.setBounds(-1400, 24, 1400, 850);
        pet.x = area.x + 1;
        roaming.requestWalk();
        assertTrue(tick(true).facingLeft());
        now.addAndGet(100_000_000);
        assertFalse(tick(true).facingLeft());
        assertEquals(area.x, pet.x);
        now.addAndGet(100_000_000);
        tick(true);
        assertEquals(area.x + 4, pet.x);

        var right = new DesktopRoamingController(now::get, () -> .99);
        pet.x = area.x + area.width - pet.width - 1;
        right.requestWalk();
        var first = right.tick(pet, area, true);
        pet.setLocation(first.x(), first.y());
        now.addAndGet(100_000_000);
        var edge = right.tick(pet, area, true);
        assertTrue(edge.facingLeft());
        assertEquals(area.x + area.width - pet.width, edge.x());
    }

    @Test
    void blockersStopImmediatelyAndRequireAFreshRestAfterBecomingAvailable() {
        roaming.requestWalk();
        tick(true);
        now.addAndGet(50_000_000);
        tick(true);
        int stopped = pet.x;
        assertFalse(tick(false).walking());
        now.addAndGet(Duration.ofHours(1).toNanos());
        assertFalse(tick(false).walking());
        assertFalse(tick(true).walking());
        now.addAndGet(Duration.ofSeconds(19).toNanos());
        assertFalse(tick(true).walking());
        assertEquals(stopped, pet.x);
        now.addAndGet(Duration.ofSeconds(1).toNanos());
        assertTrue(tick(true).walking());
    }

    @Test
    void manualCommandWorksWithAutomaticModeOffButDoesNotEnableMoreBouts() {
        roaming.setEnabled(false);
        now.set(Duration.ofMinutes(1).toNanos());
        assertFalse(tick(true).walking());
        roaming.requestWalk();
        assertFalse(tick(false).walking()); // A visible menu can temporarily block the command.
        assertTrue(tick(true).walking());
        now.addAndGet(Duration.ofSeconds(4).toNanos());
        assertFalse(tick(true).walking());
        now.addAndGet(Duration.ofMinutes(1).toNanos());
        assertFalse(tick(true).walking());
        roaming.setEnabled(true);
        assertFalse(tick(true).walking());
        now.addAndGet(Duration.ofSeconds(20).toNanos());
        assertTrue(tick(true).walking());
    }

    @Test
    void timerStallsCannotCauseLargeJumpsOrCatchUpAfterABoutHasExpired() {
        roaming.requestWalk();
        tick(true);
        now.addAndGet(Duration.ofSeconds(2).toNanos());
        assertTrue(tick(true).walking());
        assertEquals(496, pet.x);
        now.addAndGet(Duration.ofHours(1).toNanos());
        assertFalse(tick(true).walking());
        assertEquals(496, pet.x);
    }

    @Test
    void externalRelocationWinsAndDoesNotSnapBackToTheOldTrajectory() {
        roaming.requestWalk();
        tick(true);
        now.addAndGet(100_000_000);
        tick(true);
        pet.setLocation(120, 240);
        now.addAndGet(50_000_000);
        assertFalse(tick(true).walking());
        assertEquals(120, pet.x);
        assertEquals(240, pet.y);
    }

    @Test
    void smallWorkAreasBlockWalkingAndOffscreenStartsAreClampedOnAValidDisplay() {
        roaming.requestWalk();
        area.width = pet.width;
        assertFalse(tick(true).walking());
        area.width = 1400;
        area.height = pet.height - 1;
        assertFalse(tick(true).walking());
        area.height = 850;
        pet.setLocation(-9000, 9000);
        assertTrue(tick(true).walking());
        assertTrue(area.contains(pet));
        assertEquals(area.y + area.height - pet.height, pet.y);
        area.height = 500;
        now.addAndGet(50_000_000);
        tick(true);
        assertTrue(area.contains(pet));
    }

    @Test
    void closingDisablesPendingCommandsAndFutureTimerTicks() {
        roaming.requestWalk();
        tick(true);
        roaming.close();
        roaming.requestWalk();
        roaming.setEnabled(true);
        now.addAndGet(Duration.ofMinutes(1).toNanos());
        assertFalse(tick(true).walking());
        assertEquals(500, pet.x);
    }
}
