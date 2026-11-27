package app;

import org.junit.jupiter.api.Test;
import javax.swing.SwingUtilities;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class MainTest {
    @Test
    void modelPreparationWaitsForWorkbenchAndRunsAwayFromTheUiThread() throws Exception {
        var ready=new CompletableFuture<Void>();
        var started=new CountDownLatch(1);
        var release=new CountDownLatch(1);
        var prepared=new AtomicBoolean();
        var shownOnEdt=new AtomicBoolean();
        CompletableFuture<Void> complete=Main.afterWorkbenchReady(ready,() -> {
            assertFalse(SwingUtilities.isEventDispatchThread());
            started.countDown();
            try { assertTrue(release.await(5,TimeUnit.SECONDS)); }
            catch(InterruptedException failure) { throw new AssertionError(failure); }
            prepared.set(true);
        },() -> {
            assertTrue(prepared.get());
            shownOnEdt.set(SwingUtilities.isEventDispatchThread());
        });
        assertEquals(1,started.getCount());
        assertFalse(complete.isDone());
        SwingUtilities.invokeAndWait(() -> ready.complete(null));
        assertTrue(started.await(5,TimeUnit.SECONDS));
        assertFalse(complete.isDone());
        release.countDown();
        complete.get(5,TimeUnit.SECONDS);
        assertTrue(shownOnEdt.get());
    }

    @Test
    void failedWarmupStillAllowsTheExistingPetFallback() throws Exception {
        var opened=new AtomicBoolean();
        Main.afterWorkbenchReady(CompletableFuture.completedFuture(null),() -> {
            throw new IllegalStateException("synthetic model failure");
        },() -> opened.set(true)).get(5,TimeUnit.SECONDS);
        assertTrue(opened.get());
    }

    @Test
    void failedWorkbenchReadinessDoesNotStartModelOrPet() {
        var touched=new AtomicBoolean();
        var ready=CompletableFuture.<Void>failedFuture(new IllegalStateException("synthetic UI failure"));
        var complete=Main.afterWorkbenchReady(ready,() -> touched.set(true),() -> touched.set(true));
        assertThrows(ExecutionException.class,() -> complete.get(5,TimeUnit.SECONDS));
        assertFalse(touched.get());
    }
}
