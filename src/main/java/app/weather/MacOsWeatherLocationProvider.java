package app.weather;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** A one-shot Core Location helper, launched as an app so macOS owns permission. */
public final class MacOsWeatherLocationProvider implements WeatherLocationProvider {
    private static final int MAX_RESULT_BYTES = 4096;
    private final Path helper;
    private final Duration timeout;
    private final boolean macOs;
    private final ProcessStarter processStarter;

    @FunctionalInterface
    interface ProcessStarter {
        Process start(List<String> command) throws IOException;
    }

    public MacOsWeatherLocationProvider(Path helper, Duration timeout) {
        this(helper, timeout, System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("mac"),
                command -> new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start());
    }

    MacOsWeatherLocationProvider(Path helper, Duration timeout, boolean macOs, ProcessStarter processStarter) {
        this.helper = Objects.requireNonNull(helper, "helper");
        this.timeout = Objects.requireNonNull(timeout, "timeout");
        this.macOs = macOs;
        this.processStarter = Objects.requireNonNull(processStarter, "processStarter");
    }

    @Override
    public CompletableFuture<WeatherLocation> currentLocation() {
        return CompletableFuture.supplyAsync(this::resolve);
    }

    private WeatherLocation resolve() {
        if (!macOs) {
            throw new WeatherException("系统定位目前仅支持 macOS，可使用 fixed 天气位置");
        }
        Path directory = null;
        Process process = null;
        try {
            Path bundle = helper.toRealPath();
            if (!Files.isDirectory(bundle) || !Files.isExecutable(bundle.resolve("Contents/MacOS/closure-location"))) {
                throw new WeatherException("系统定位组件尚未安装");
            }
            // Private, short-lived IPC only; do not retain a location cache/history.
            directory = Files.createTempDirectory("closure-weather-location-");
            Path result = Files.createFile(directory.resolve("result"));
            process = processStarter.start(List.of("/usr/bin/open", "-W", "-n", bundle.toString(),
                    "--args", "--output", result.toString()));
            if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
                throw new WeatherException("系统定位暂时超时");
            }
            if (Files.size(result) > MAX_RESULT_BYTES || Files.size(result) == 0) {
                throw new WeatherException("系统定位组件未能返回位置");
            }
            WeatherLocation location = parseResult(Files.readString(result, StandardCharsets.UTF_8));
            if (process.exitValue() != 0) {
                throw new WeatherException("系统定位组件未能完成查询");
            }
            return location;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new WeatherException("系统定位已取消");
        } catch (IOException exception) {
            throw new WeatherException("系统定位组件不可用");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (directory != null) {
                try {
                    Files.deleteIfExists(directory.resolve("result"));
                    Files.deleteIfExists(directory);
                } catch (IOException ignored) {
                    // No coordinates are written to logs if cleanup is unavailable.
                }
            }
        }
    }

    static WeatherLocation parseResult(String result) {
        String[] fields = result.strip().split("\t", -1);
        if (fields.length == 2 && fields[0].equals("CLOSURE_LOCATION_ERROR")) {
            throw new WeatherException(switch (fields[1]) {
                case "DENIED" -> "未获系统定位授权";
                case "RESTRICTED" -> "系统限制了定位权限";
                case "SERVICES_DISABLED" -> "系统定位服务未开启";
                case "TIMEOUT" -> "系统定位暂时超时";
                default -> "暂时无法获取当前位置";
            });
        }
        if (fields.length != 4 || !fields[0].equals("CLOSURE_LOCATION_V1")) {
            throw new WeatherException("系统定位返回的数据不完整");
        }
        try {
            byte[] encodedName = Base64.getDecoder().decode(fields[3]);
            String name = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(encodedName)).toString();
            return new WeatherLocation(Double.parseDouble(fields[1]), Double.parseDouble(fields[2]), name);
        } catch (IllegalArgumentException | IOException exception) {
            throw new WeatherException("系统定位返回的数据无效");
        }
    }
}
