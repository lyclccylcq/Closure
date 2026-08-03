package app.voice;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * 可选的 GPT-SoVITS 本地服务启动器。
 * 只有配置了启动命令时才会生效；如果 API 已经可连接，就不会重复启动。
 */
public final class GptSovitsServerProcess implements AutoCloseable {
    public static final String ENV_AUTO_START = "CLOSURE_GPT_SOVITS_SERVER_AUTO_START";
    public static final String ENV_COMMAND = "CLOSURE_GPT_SOVITS_SERVER_COMMAND";
    public static final String ENV_WORKING_DIR = "CLOSURE_GPT_SOVITS_SERVER_WORKING_DIR";

    public static final String PROPERTY_AUTO_START = "gptSovits.server.autoStart";
    public static final String PROPERTY_COMMAND = "gptSovits.server.command";
    public static final String PROPERTY_WORKING_DIR = "gptSovits.server.workingDir";

    private static final Duration SERVER_PROBE_TIMEOUT = Duration.ofMillis(900);
    private static final Duration PROCESS_STOP_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration EARLY_EXIT_WATCH_WINDOW = Duration.ofSeconds(45);
    private static final int RECENT_LOG_LINE_LIMIT = 8;
    private static final URI DEFAULT_BASE_URI = URI.create("http://127.0.0.1:9880");

    private final Process process;
    private final Consumer<String> warningSink;
    private final Deque<String> recentOutputLines = new ArrayDeque<>();
    private final Thread logPumpThread;
    private volatile boolean closed;

    private GptSovitsServerProcess(Process process, Consumer<String> warningSink) {
        this.process = Objects.requireNonNull(process, "process");
        this.warningSink = Objects.requireNonNull(warningSink, "warningSink");
        logPumpThread = new Thread(this::pumpProcessOutput, "gpt-sovits-output");
        logPumpThread.setDaemon(true);
        logPumpThread.start();
        startEarlyExitMonitor();
    }

    public static CompletableFuture<Optional<GptSovitsServerProcess>> startConfiguredAsync(
            Consumer<String> warningSink
    ) {
        Consumer<String> safeWarningSink = warningSink == null ? message -> {
        } : warningSink;

        return CompletableFuture.supplyAsync(
                () -> startConfigured(safeWarningSink),
                daemonExecutor("gpt-sovits-autostart")
        );
    }

    static Optional<GptSovitsServerProcess> startConfigured(Consumer<String> warningSink) {
        ServerStartupConfig config;
        try {
            config = ServerStartupConfig.fromEnvironment();
        } catch (RuntimeException exception) {
            warningSink.accept("读取 GPT-SoVITS 启动配置失败：" + exception.getMessage());
            return Optional.empty();
        }

        if (!config.autoStart()) {
            return Optional.empty();
        }
        if (config.command().isBlank()) {
            warningSink.accept("已开启 GPT-SoVITS 自动启动，但缺少 " + PROPERTY_COMMAND + "。");
            return Optional.empty();
        }
        if (isServerReachable(config.baseUri())) {
            warningSink.accept("GPT-SoVITS API 已在运行，跳过自动启动。");
            return Optional.empty();
        }

        try {
            List<String> command = parseCommandLine(config.command());
            ProcessBuilder processBuilder = new ProcessBuilder(command);
            config.workingDirectory().ifPresent(path -> processBuilder.directory(path.toFile()));
            processBuilder.redirectErrorStream(true);

            warningSink.accept("正在启动 GPT-SoVITS API：" + command.getFirst());
            GptSovitsServerProcess serverProcess = new GptSovitsServerProcess(processBuilder.start(), warningSink);
            Runtime.getRuntime().addShutdownHook(new Thread(serverProcess::close, "gpt-sovits-shutdown"));
            return Optional.of(serverProcess);
        } catch (IOException | RuntimeException exception) {
            warningSink.accept("启动 GPT-SoVITS API 失败：" + exception.getMessage());
            return Optional.empty();
        }
    }

    static List<String> parseCommandLine(String commandLine) {
        if (commandLine == null || commandLine.isBlank()) {
            return List.of();
        }

        List<String> arguments = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean inSingleQuote = false;
        boolean inDoubleQuote = false;
        boolean escaping = false;

        for (int index = 0; index < commandLine.length(); index++) {
            char character = commandLine.charAt(index);
            if (escaping) {
                current.append(character);
                escaping = false;
                continue;
            }
            if (character == '\\' && !inSingleQuote) {
                escaping = true;
                continue;
            }
            if (character == '\'' && !inDoubleQuote) {
                inSingleQuote = !inSingleQuote;
                continue;
            }
            if (character == '"' && !inSingleQuote) {
                inDoubleQuote = !inDoubleQuote;
                continue;
            }
            if (Character.isWhitespace(character) && !inSingleQuote && !inDoubleQuote) {
                addArgument(arguments, current);
                continue;
            }

            current.append(character);
        }

        if (escaping) {
            current.append('\\');
        }
        if (inSingleQuote || inDoubleQuote) {
            throw new VoiceSynthesisException("GPT-SoVITS 启动命令存在未闭合引号");
        }

        addArgument(arguments, current);
        if (arguments.isEmpty()) {
            throw new VoiceSynthesisException("GPT-SoVITS 启动命令不能为空");
        }

        return List.copyOf(arguments);
    }

    public boolean isAlive() {
        return process.isAlive();
    }

    @Override
    public void close() {
        if (closed) {
            return;
        }

        closed = true;
        if (!process.isAlive()) {
            return;
        }

        process.destroy();
        try {
            if (!process.waitFor(PROCESS_STOP_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    private void pumpProcessOutput() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                process.getInputStream(),
                StandardCharsets.UTF_8
        ))) {
            String line;
            while ((line = reader.readLine()) != null) {
                rememberOutputLine(line);
                System.out.println("[GPT-SoVITS] " + line);
            }
        } catch (IOException exception) {
            if (!closed) {
                System.out.println("读取 GPT-SoVITS 输出失败：" + exception.getMessage());
            }
        }
    }

    private void startEarlyExitMonitor() {
        Thread monitorThread = new Thread(() -> {
            try {
                if (process.waitFor(EARLY_EXIT_WATCH_WINDOW.toMillis(), TimeUnit.MILLISECONDS) && !closed) {
                    warningSink.accept("GPT-SoVITS API 启动后过早退出，退出码 "
                            + process.exitValue()
                            + recentOutputSummary());
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        }, "gpt-sovits-early-exit-monitor");
        monitorThread.setDaemon(true);
        monitorThread.start();
    }

    private synchronized void rememberOutputLine(String line) {
        if (line == null || line.isBlank()) {
            return;
        }

        recentOutputLines.addLast(line.trim());
        while (recentOutputLines.size() > RECENT_LOG_LINE_LIMIT) {
            recentOutputLines.removeFirst();
        }
    }

    private synchronized String recentOutputSummary() {
        if (recentOutputLines.isEmpty()) {
            return "。没有捕获到输出。";
        }

        return "。最近输出：" + String.join(" / ", recentOutputLines);
    }

    private static void addArgument(List<String> arguments, StringBuilder current) {
        if (current.isEmpty()) {
            return;
        }

        arguments.add(current.toString());
        current.setLength(0);
    }

    private static boolean isServerReachable(URI baseUri) {
        try {
            HttpRequest request = HttpRequest.newBuilder(baseUri)
                    .timeout(SERVER_PROBE_TIMEOUT)
                    .GET()
                    .build();
            HttpResponse<Void> response = HttpClient
                    .newBuilder()
                    .connectTimeout(SERVER_PROBE_TIMEOUT)
                    .build()
                    .send(request, HttpResponse.BodyHandlers.discarding());
            return response.statusCode() < 500;
        } catch (IOException | IllegalArgumentException exception) {
            return false;
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    private static Executor daemonExecutor(String threadName) {
        return command -> {
            Thread thread = new Thread(command, threadName);
            thread.setDaemon(true);
            thread.start();
        };
    }

    record ServerStartupConfig(
            boolean autoStart,
            String command,
            Optional<Path> workingDirectory,
            URI baseUri
    ) {
        private static ServerStartupConfig fromEnvironment() {
            Properties properties = loadLocalProperties();
            String command = configValue(ENV_COMMAND, PROPERTY_COMMAND, properties);
            boolean autoStart = configBoolean(
                    ENV_AUTO_START,
                    PROPERTY_AUTO_START,
                    properties,
                    command != null && !command.isBlank()
            );
            String workingDirectoryText = configValue(ENV_WORKING_DIR, PROPERTY_WORKING_DIR, properties);
            Optional<Path> workingDirectory = workingDirectory(workingDirectoryText);
            URI baseUri = URI.create(configValueOrDefault(
                    GptSovitsConfig.ENV_BASE_URL,
                    GptSovitsConfig.PROPERTY_BASE_URL,
                    properties,
                    DEFAULT_BASE_URI.toString()
            ));

            return new ServerStartupConfig(autoStart, command == null ? "" : command, workingDirectory, baseUri);
        }

        private static Optional<Path> workingDirectory(String text) {
            if (text == null || text.isBlank()) {
                return Optional.empty();
            }

            Path path = Path.of(text.trim());
            if (!Files.isDirectory(path)) {
                throw new VoiceSynthesisException(PROPERTY_WORKING_DIR + " 不存在或不是目录：" + path);
            }

            return Optional.of(path);
        }
    }

    private static Properties loadLocalProperties() {
        Properties properties = new Properties();
        Path localConfigPath = GptSovitsConfig.localConfigPath();
        if (!Files.exists(localConfigPath)) {
            return properties;
        }

        try (var reader = Files.newBufferedReader(localConfigPath, StandardCharsets.UTF_8)) {
            properties.load(reader);
            return properties;
        } catch (IOException exception) {
            throw new VoiceSynthesisException("读取本地语音配置失败：" + localConfigPath, exception);
        }
    }

    private static String configValueOrDefault(
            String environmentName,
            String propertyName,
            Properties localProperties,
            String defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }

        return value;
    }

    private static String configValue(String environmentName, String propertyName, Properties localProperties) {
        String environmentValue = System.getenv(environmentName);
        if (environmentValue != null && !environmentValue.isBlank()) {
            return environmentValue.trim();
        }

        String localValue = localProperties.getProperty(propertyName);
        if (localValue != null && !localValue.isBlank()) {
            return localValue.trim();
        }

        return null;
    }

    private static boolean configBoolean(
            String environmentName,
            String propertyName,
            Properties localProperties,
            boolean defaultValue
    ) {
        String value = configValue(environmentName, propertyName, localProperties);
        if (value == null || value.isBlank()) {
            return defaultValue;
        }
        if ("true".equalsIgnoreCase(value)
                || "1".equals(value)
                || "yes".equalsIgnoreCase(value)
                || "on".equalsIgnoreCase(value)) {
            return true;
        }
        if ("false".equalsIgnoreCase(value)
                || "0".equals(value)
                || "no".equalsIgnoreCase(value)
                || "off".equalsIgnoreCase(value)) {
            return false;
        }

        throw new VoiceSynthesisException(propertyName + " 必须是 true 或 false");
    }
}
