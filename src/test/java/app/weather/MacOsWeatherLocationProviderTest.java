package app.weather;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class MacOsWeatherLocationProviderTest {
    @TempDir Path temporary;

    @Test
    void launchesAnAppWithBoundedPrivateResultAndRemovesIt() throws Exception {
        Path bundle = bundle();
        AtomicReference<List<String>> command = new AtomicReference<>();
        MacOsWeatherLocationProvider provider = new MacOsWeatherLocationProvider(bundle, Duration.ofSeconds(1), true, args -> {
            command.set(args);
            Files.writeString(Path.of(args.getLast()), response("堪培拉"));
            return new FakeProcess(true);
        });
        WeatherLocation location = provider.currentLocation().get(2, TimeUnit.SECONDS);
        assertEquals(-35.28, location.latitude());
        assertEquals(149.13, location.longitude());
        assertEquals("堪培拉", location.name());
        assertEquals(List.of("/usr/bin/open", "-W", "-n", bundle.toRealPath().toString(), "--args", "--output"),
                command.get().subList(0, 6));
        assertFalse(Files.exists(Path.of(command.get().getLast()).getParent()));
    }

    @Test
    void denialAndTimeoutFailWithoutReturningAnyGuessedCoordinates() throws Exception {
        Path bundle = bundle();
        AtomicReference<Path> result = new AtomicReference<>();
        MacOsWeatherLocationProvider denied = new MacOsWeatherLocationProvider(bundle, Duration.ofSeconds(1), true, args -> {
            result.set(Path.of(args.getLast()));
            Files.writeString(result.get(), "CLOSURE_LOCATION_ERROR\tDENIED\n");
            return new FakeProcess(true, 1);
        });
        CompletionException error = assertThrows(CompletionException.class, () -> denied.currentLocation().join());
        assertTrue(error.getCause().getMessage().contains("授权"));
        assertFalse(Files.exists(result.get().getParent()));
        FakeProcess hanging = new FakeProcess(false);
        MacOsWeatherLocationProvider timeout = new MacOsWeatherLocationProvider(bundle, Duration.ofMillis(10), true, args -> {
            result.set(Path.of(args.getLast()));
            return hanging;
        });
        assertThrows(CompletionException.class, () -> timeout.currentLocation().join());
        assertTrue(hanging.destroyed);
        assertFalse(Files.exists(result.get().getParent()));
    }

    @Test
    void unsupportedPlatformAndOversizeResultAreUnavailable() throws Exception {
        MacOsWeatherLocationProvider unsupported = new MacOsWeatherLocationProvider(temporary, Duration.ofSeconds(1), false,
                args -> { throw new AssertionError("No native process on another platform"); });
        assertThrows(CompletionException.class, () -> unsupported.currentLocation().join());
        MacOsWeatherLocationProvider oversize = new MacOsWeatherLocationProvider(bundle(), Duration.ofSeconds(1), true, args -> {
            Files.writeString(Path.of(args.getLast()), "x".repeat(4097));
            return new FakeProcess(true);
        });
        assertThrows(CompletionException.class, () -> oversize.currentLocation().join());
    }

    @Test
    void rejectsInvalidCoordinatesVersionEncodingAndDenialInsteadOfLeakingPayload() {
        for (String raw : List.of("{\"latitude\":0}", "CLOSURE_LOCATION_V2\t0\t0\tQQ==",
                "CLOSURE_LOCATION_V1\tNaN\t0\tQQ==", "CLOSURE_LOCATION_V1\t0\t181\tQQ==",
                "CLOSURE_LOCATION_V1\t0\t0\t%%%", "CLOSURE_LOCATION_V1\t0\t0\t/w==")) {
            assertThrows(WeatherException.class, () -> MacOsWeatherLocationProvider.parseResult(raw));
        }
        assertEquals("当前位置", new WeatherLocation(0, 0, "").name());
        assertEquals("当前位置", new WeatherLocation(0, 0, "bad\nname").name());
    }

    private Path bundle() throws Exception {
        Path app = temporary.resolve("ClosureLocation.app");
        Path executable = app.resolve("Contents/MacOS/closure-location");
        Files.createDirectories(executable.getParent());
        Files.writeString(executable, "fake; never executed");
        assertTrue(executable.toFile().setExecutable(true));
        return app;
    }

    private static String response(String name) {
        return "CLOSURE_LOCATION_V1\t-35.28\t149.13\t"
                + Base64.getEncoder().encodeToString(name.getBytes(StandardCharsets.UTF_8)) + "\n";
    }

    private static final class FakeProcess extends Process {
        private final boolean completes;
        private final int code;
        private boolean destroyed;
        FakeProcess(boolean completes) { this(completes, 0); }
        FakeProcess(boolean completes, int code) { this.completes = completes; this.code = code; }
        @Override public OutputStream getOutputStream() { return OutputStream.nullOutputStream(); }
        @Override public InputStream getInputStream() { return InputStream.nullInputStream(); }
        @Override public InputStream getErrorStream() { return InputStream.nullInputStream(); }
        @Override public int waitFor() { return code; }
        @Override public boolean waitFor(long timeout, TimeUnit unit) { return completes; }
        @Override public int exitValue() { return code; }
        @Override public void destroy() { destroyed = true; }
        @Override public Process destroyForcibly() { destroyed = true; return this; }
        @Override public boolean isAlive() { return !completes && !destroyed; }
    }
}
