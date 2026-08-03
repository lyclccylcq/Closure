package app.service;

import java.util.Optional;

/**
 * 从模型流式 JSON 中提取已经生成的 displayText，用于聊天 UI 即时显示。
 */
public final class AssistantDisplayTextStreamExtractor {
    private AssistantDisplayTextStreamExtractor() {
    }

    public static Optional<String> visibleText(String streamedText) {
        if (streamedText == null || streamedText.isEmpty()) {
            return Optional.empty();
        }

        String trimmedText = streamedText.stripLeading();
        if (trimmedText.isEmpty()) {
            return Optional.empty();
        }

        if (!looksLikeJsonEnvelope(trimmedText)) {
            return Optional.of(streamedText);
        }

        int fieldIndex = streamedText.indexOf("\"displayText\"");
        if (fieldIndex < 0) {
            return Optional.empty();
        }

        int colonIndex = streamedText.indexOf(':', fieldIndex);
        if (colonIndex < 0) {
            return Optional.empty();
        }

        int valueStart = skipWhitespace(streamedText, colonIndex + 1);
        if (valueStart >= streamedText.length() || streamedText.charAt(valueStart) != '"') {
            return Optional.empty();
        }

        return Optional.of(readPartialJsonString(streamedText, valueStart));
    }

    private static boolean looksLikeJsonEnvelope(String text) {
        return text.startsWith("{") || text.startsWith("```");
    }

    private static int skipWhitespace(String text, int start) {
        int index = Math.max(0, start);
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }

        return index;
    }

    private static String readPartialJsonString(String json, int quoteIndex) {
        StringBuilder value = new StringBuilder();
        for (int index = quoteIndex + 1; index < json.length(); index++) {
            char character = json.charAt(index);
            if (character == '"') {
                return value.toString();
            }

            if (character != '\\') {
                value.append(character);
                continue;
            }

            if (index + 1 >= json.length()) {
                return value.toString();
            }

            char escaped = json.charAt(++index);
            switch (escaped) {
                case '"' -> value.append('"');
                case '\\' -> value.append('\\');
                case '/' -> value.append('/');
                case 'b' -> value.append('\b');
                case 'f' -> value.append('\f');
                case 'n' -> value.append('\n');
                case 'r' -> value.append('\r');
                case 't' -> value.append('\t');
                case 'u' -> {
                    if (index + 4 >= json.length()) {
                        return value.toString();
                    }
                    String hex = json.substring(index + 1, index + 5);
                    try {
                        value.append((char) Integer.parseInt(hex, 16));
                    } catch (NumberFormatException exception) {
                        return value.toString();
                    }
                    index += 4;
                }
                default -> value.append(escaped);
            }
        }

        return value.toString();
    }
}
