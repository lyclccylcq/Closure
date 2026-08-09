package app.reminder;

import app.ai.AiChatRequest;
import app.ai.AiModelClient;
import app.ai.AiModelConfig;
import app.ai.AiStreamListener;
import app.ai.OpenAiCompatibleChatClient;

import java.io.IOException;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

public final class AiCourseScheduleImportConverter implements CourseScheduleImportConverter {
    private static final int MAX_SOURCE_LINES = 300;
    private static final int MAX_SOURCE_CHARACTERS = 20_000;
    private static final String CSV_HEADER = "title,start,end,type,remindBeforeMinutes,location,notes";
    private static final String SYSTEM_PROMPT = """
            你是课程表格式转换器，只负责把用户上传的课程表文本转换为标准 CSV。

            输出要求：
            - 只输出 CSV，不要解释，不要 Markdown，不要代码块，不要引号包住整段。
            - 第一行必须严格是：title,start,end,type,remindBeforeMinutes,location,notes
            - 每一行代表一个上课、考试、作业截止或 Deadline。
            - start 和 end 使用 yyyy-MM-dd HH:mm，end 不确定就留空。
            - type 只能是 class 或 deadline。上课、讲座、tutorial、lab 用 class；作业截止、考试截止、due、ddl 用 deadline。
            - remindBeforeMinutes 必须是整数。class 默认 15，deadline 默认 1440。
            - title 保留课程名或事项名，location 保留教室/地点，notes 放必要补充。
            - 如果某行没有明确日期或时间，不要输出那一行。
            - 不要编造课程、日期、地点或提醒事项。
            """;

    private final AiModelClient aiClient;

    public AiCourseScheduleImportConverter(AiModelClient aiClient) {
        this.aiClient = Objects.requireNonNull(aiClient, "aiClient");
    }

    public static CourseScheduleImportConverter fromEnvironment() {
        try {
            return new AiCourseScheduleImportConverter(new OpenAiCompatibleChatClient(AiModelConfig.fromEnvironment()));
        } catch (RuntimeException exception) {
            System.out.println("课程表 AI 转换配置不可用，仅使用本地解析：" + exception.getMessage());
            return CourseScheduleImportConverter.disabled();
        }
    }

    @Override
    public CompletableFuture<List<String>> convertToScheduleLines(Path sourceFile, String parserErrorMessage) {
        Objects.requireNonNull(sourceFile, "sourceFile");
        CompletableFuture<List<String>> resultFuture = new CompletableFuture<>();
        List<String> sourceLines;
        try {
            sourceLines = CourseScheduleSourceReader.readLines(sourceFile);
        } catch (IOException | IllegalArgumentException exception) {
            resultFuture.completeExceptionally(exception);
            return resultFuture;
        }

        StringBuilder streamedText = new StringBuilder();
        AtomicBoolean completed = new AtomicBoolean(false);
        AiChatRequest request = AiChatRequest
                .singleTurn(SYSTEM_PROMPT, userPrompt(sourceFile, parserErrorMessage, sourceLines))
                .withTemperature(0.0);

        try {
            aiClient.streamChat(request, new AiStreamListener() {
                @Override
                public void onToken(String token) {
                    streamedText.append(token);
                }

                @Override
                public void onComplete() {
                    if (completed.compareAndSet(false, true)) {
                        try {
                            resultFuture.complete(normalizeCsvLines(streamedText.toString()));
                        } catch (RuntimeException exception) {
                            resultFuture.completeExceptionally(exception);
                        }
                    }
                }

                @Override
                public void onError(Throwable error) {
                    if (completed.compareAndSet(false, true)) {
                        resultFuture.completeExceptionally(error);
                    }
                }
            });
        } catch (RuntimeException exception) {
            if (completed.compareAndSet(false, true)) {
                resultFuture.completeExceptionally(exception);
            }
        }

        return resultFuture;
    }

    @Override
    public void close() {
        aiClient.close();
    }

    static List<String> normalizeCsvLines(String rawResponse) {
        String cleanedResponse = stripCodeFence(rawResponse);
        List<String> lines = cleanedResponse.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .toList();
        if (lines.isEmpty()) {
            throw new IllegalArgumentException("AI 没有返回可用的课程表 CSV。");
        }

        List<String> csvLines = new ArrayList<>();
        int headerIndex = firstCsvHeaderIndex(lines);
        if (headerIndex >= 0) {
            csvLines.add(CSV_HEADER);
            csvLines.addAll(lines.subList(headerIndex + 1, lines.size()));
        } else {
            csvLines.add(CSV_HEADER);
            csvLines.addAll(lines);
        }

        if (csvLines.size() <= 1) {
            throw new IllegalArgumentException("AI 没有转换出任何课程或 Deadline。");
        }

        return csvLines;
    }

    private static String userPrompt(Path sourceFile, String parserErrorMessage, List<String> sourceLines) {
        return """
                当前日期：%s
                文件名：%s
                本地解析失败原因：%s

                原始课程表内容如下。请转换为标准 CSV：

                %s
                """.formatted(
                LocalDate.now(),
                sourceFile.getFileName(),
                parserErrorMessage == null || parserErrorMessage.isBlank() ? "未提供" : parserErrorMessage,
                limitedSourceText(sourceLines)
        );
    }

    private static String limitedSourceText(List<String> sourceLines) {
        StringBuilder builder = new StringBuilder();
        int lineCount = Math.min(sourceLines.size(), MAX_SOURCE_LINES);
        for (int index = 0; index < lineCount; index++) {
            if (builder.length() >= MAX_SOURCE_CHARACTERS) {
                break;
            }

            String line = sourceLines.get(index);
            int remainingCharacters = MAX_SOURCE_CHARACTERS - builder.length();
            if (line.length() > remainingCharacters) {
                builder.append(line, 0, remainingCharacters);
                break;
            }

            builder.append(line).append('\n');
        }

        if (sourceLines.size() > lineCount || builder.length() >= MAX_SOURCE_CHARACTERS) {
            builder.append("\n[内容已截断，只转换可见部分]");
        }

        return builder.toString().trim();
    }

    private static String stripCodeFence(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String trimmedText = text.trim();
        if (!trimmedText.startsWith("```")) {
            return trimmedText;
        }

        List<String> lines = trimmedText.lines().toList();
        if (lines.size() >= 2 && lines.getLast().trim().equals("```")) {
            return String.join("\n", lines.subList(1, lines.size() - 1)).trim();
        }

        return trimmedText.replace("```csv", "")
                .replace("```CSV", "")
                .replace("```", "")
                .trim();
    }

    private static int firstCsvHeaderIndex(List<String> lines) {
        String expectedHeader = normalizeHeaderLine(CSV_HEADER);
        for (int index = 0; index < lines.size(); index++) {
            if (normalizeHeaderLine(lines.get(index)).equals(expectedHeader)) {
                return index;
            }
        }

        return -1;
    }

    private static String normalizeHeaderLine(String line) {
        return line.toLowerCase(Locale.ROOT)
                .replace(" ", "")
                .replace("_", "")
                .replace("-", "")
                .replace("\"", "");
    }
}
