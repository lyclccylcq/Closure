package app.service;

import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 只清洗交给 TTS 的文本，不改变聊天窗口里的原始 Markdown 回复。
 */
public final class SpeechTextSanitizer {
    public static final int MAX_TOTAL_CODE_POINTS = 200;
    public static final int MAX_SEGMENT_CODE_POINTS = 80;

    private static final Pattern CODE_BLOCK = Pattern.compile("(?s)```.*?```");
    private static final Pattern MARKDOWN_IMAGE = Pattern.compile("!\\[[^\\]]*]\\([^)]*\\)");
    private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[([^\\]]+)]\\([^)]*\\)");
    private static final Pattern URL = Pattern.compile("(?i)\\b(?:https?://|www\\.)\\S+");
    private static final Pattern MARKDOWN_HEADING = Pattern.compile("(?m)^\\s{0,3}#{1,6}\\s*");
    private static final Pattern EXTRA_BLANK_LINES = Pattern.compile("(?:\\R\\s*){2,}");
    private static final Pattern INLINE_SPACES = Pattern.compile("[ \\t]+");
    private static final Pattern DANGLING_COLON = Pattern.compile("[：:]\\s*(?=\\R|$)");

    private SpeechTextSanitizer() {
    }

    public static String sanitize(String text) {
        return sanitizeText(text, MAX_TOTAL_CODE_POINTS);
    }

    public static String sanitizeSegment(String text) {
        return sanitizeSegment(text, MAX_SEGMENT_CODE_POINTS);
    }

    public static String sanitizeSegment(String text, int maxCodePoints) {
        int limit = Math.max(1, Math.min(MAX_SEGMENT_CODE_POINTS, maxCodePoints));
        return sanitizeText(text, limit);
    }

    private static String sanitizeText(String text, int maxCodePoints) {
        if (text == null || text.isBlank()) {
            return "";
        }

        String cleaned = text.replace("\r\n", "\n").replace('\r', '\n');
        cleaned = CODE_BLOCK.matcher(cleaned).replaceAll(" ");
        cleaned = MARKDOWN_IMAGE.matcher(cleaned).replaceAll(" ");
        cleaned = MARKDOWN_LINK.matcher(cleaned).replaceAll("$1");
        cleaned = URL.matcher(cleaned).replaceAll(" ");
        cleaned = DANGLING_COLON.matcher(cleaned).replaceAll("。");
        cleaned = MARKDOWN_HEADING.matcher(cleaned).replaceAll("");
        cleaned = cleaned.replace("*", "").replace("`", "");
        cleaned = EXTRA_BLANK_LINES.matcher(cleaned).replaceAll("\n");
        cleaned = INLINE_SPACES.matcher(cleaned).replaceAll(" ");
        cleaned = cleaned.lines()
                .map(String::trim)
                .filter(line -> !line.isBlank())
                .collect(Collectors.joining("\n"))
                .trim();

        if (cleaned.isBlank()) {
            return "";
        }

        return truncateAtSentenceBoundary(cleaned, maxCodePoints);
    }

    private static String truncateAtSentenceBoundary(String text, int maxCodePoints) {
        int codePointCount = text.codePointCount(0, text.length());
        if (codePointCount <= maxCodePoints) {
            return text;
        }

        int limitCharIndex = text.offsetByCodePoints(0, maxCodePoints);
        String prefix = text.substring(0, limitCharIndex).trim();
        int boundaryIndex = lastSentenceBoundary(prefix);
        if (boundaryIndex >= 0) {
            return prefix.substring(0, boundaryIndex + 1).trim();
        }

        return prefix;
    }

    private static int lastSentenceBoundary(String text) {
        int lastBoundary = -1;
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '。'
                    || character == '？'
                    || character == '?'
                    || character == '！'
                    || character == '!'
                    || character == '；'
                    || character == ';') {
                lastBoundary = index;
            }
        }

        return lastBoundary;
    }
}
