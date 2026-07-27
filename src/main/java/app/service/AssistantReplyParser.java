package app.service;

import app.chat.model.AssistantReply;
import app.voice.model.SpeechEmotion;
import app.voice.model.SpeechSegment;

import java.util.ArrayList;
import java.util.List;

/**
 * 把模型原始响应解析成聊天显示文本和语音分段。
 */
public final class AssistantReplyParser {
    public AssistantReply parse(String rawModelResponse) {
        String normalizedResponse = stripCodeFence(rawModelResponse);
        try {
            return parseJson(normalizedResponse);
        } catch (RuntimeException exception) {
            System.out.println("AssistantReply JSON 解析失败，使用降级文本：" + exception.getMessage());
            String displayText = fallbackDisplayText(normalizedResponse);
            return new AssistantReply(displayText, fallbackSegments(displayText));
        }
    }

    private AssistantReply parseJson(String json) {
        String objectJson = extractObject(json);
        String displayText = extractStringField(objectJson, "displayText");
        if (displayText == null || displayText.isBlank()) {
            throw new IllegalArgumentException("displayText 缺失");
        }

        List<SpeechSegment> segments = new ArrayList<>();
        String segmentsJson = extractArrayField(objectJson, "speechSegments");
        if (segmentsJson != null) {
            for (String segmentJson : splitJsonObjects(segmentsJson)) {
                String text = extractStringField(segmentJson, "text");
                if (text == null || text.isBlank()) {
                    continue;
                }
                segments.add(new SpeechSegment(text, SpeechEmotion.fromString(extractStringField(segmentJson, "emotion"))));
            }
        }

        return new AssistantReply(displayText, segments);
    }

    private String fallbackDisplayText(String response) {
        if (response == null || response.isBlank()) {
            return "工程部暂时没有收到模型回复。";
        }

        return response.trim();
    }

    private List<SpeechSegment> fallbackSegments(String displayText) {
        List<SpeechSegment> segments = new ArrayList<>();
        for (String sentence : splitSentences(displayText)) {
            if (segments.size() >= AssistantReply.MAX_SEGMENTS) {
                break;
            }

            String text = SpeechTextSanitizer.sanitizeSegment(sentence);
            if (!text.isBlank()) {
                segments.add(new SpeechSegment(text, fallbackEmotion(text)));
            }
        }

        return segments;
    }

    private SpeechEmotion fallbackEmotion(String text) {
        String trimmedText = text.trim();
        if (containsAny(trimmedText, "维修费", "收费", "打折", "采购", "别戳", "偷懒", "摸鱼", "补觉", "逗", "催工期", "不是吧", "哼")) {
            return SpeechEmotion.TEASING;
        }
        if (containsAny(trimmedText, "立即停止", "立刻停止", "马上停止", "禁止继续", "不要继续", "会删除", "删除数据", "危险操作", "严重风险", "高风险")) {
            return SpeechEmotion.SERIOUS;
        }
        if (trimmedText.endsWith("？") || trimmedText.endsWith("?")) {
            return SpeechEmotion.QUESTION;
        }
        if (containsAny(trimmedText, "太好了", "好耶", "恭喜", "欢迎", "太棒了", "真棒")) {
            return SpeechEmotion.CHEERFUL;
        }

        return SpeechEmotion.NEUTRAL;
    }

    private boolean containsAny(String text, String... candidates) {
        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }

        return false;
    }

    private List<String> splitSentences(String text) {
        List<String> sentences = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return sentences;
        }

        StringBuilder currentSentence = new StringBuilder();
        text.codePoints().forEach(codePoint -> {
            currentSentence.appendCodePoint(codePoint);
            if (isSentenceBoundary(codePoint)) {
                sentences.add(currentSentence.toString().trim());
                currentSentence.setLength(0);
            }
        });

        if (!currentSentence.isEmpty()) {
            sentences.add(currentSentence.toString().trim());
        }

        return sentences;
    }

    private boolean isSentenceBoundary(int codePoint) {
        return codePoint == '。'
                || codePoint == '？'
                || codePoint == '?'
                || codePoint == '！'
                || codePoint == '!'
                || codePoint == '；'
                || codePoint == ';';
    }

    private String stripCodeFence(String text) {
        if (text == null) {
            return "";
        }

        String trimmedText = text.trim();
        if (!trimmedText.startsWith("```")) {
            return trimmedText;
        }

        String withoutOpeningFence = trimmedText.replaceFirst("(?s)^```(?:json|JSON)?\\s*", "");
        return withoutOpeningFence.replaceFirst("(?s)\\s*```$", "").trim();
    }

    private String extractObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("不是 JSON 对象");
        }

        return text.substring(start, end + 1);
    }

    private String extractStringField(String json, String fieldName) {
        int fieldIndex = findField(json, fieldName);
        if (fieldIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', fieldIndex);
        int valueStart = skipWhitespace(json, colonIndex + 1);
        if (valueStart >= json.length() || json.charAt(valueStart) != '"') {
            return null;
        }

        return readJsonString(json, valueStart);
    }

    private String extractArrayField(String json, String fieldName) {
        int fieldIndex = findField(json, fieldName);
        if (fieldIndex < 0) {
            return null;
        }

        int colonIndex = json.indexOf(':', fieldIndex);
        int valueStart = skipWhitespace(json, colonIndex + 1);
        if (valueStart >= json.length() || json.charAt(valueStart) != '[') {
            return null;
        }

        int valueEnd = findMatchingBracket(json, valueStart, '[', ']');
        return json.substring(valueStart + 1, valueEnd);
    }

    private List<String> splitJsonObjects(String arrayJson) {
        List<String> objects = new ArrayList<>();
        int index = 0;
        while (index < arrayJson.length()) {
            int objectStart = arrayJson.indexOf('{', index);
            if (objectStart < 0) {
                break;
            }

            int objectEnd = findMatchingBracket(arrayJson, objectStart, '{', '}');
            objects.add(arrayJson.substring(objectStart, objectEnd + 1));
            index = objectEnd + 1;
        }

        return objects;
    }

    private int findField(String json, String fieldName) {
        return json.indexOf("\"" + fieldName + "\"");
    }

    private int skipWhitespace(String text, int start) {
        int index = Math.max(0, start);
        while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
            index++;
        }

        return index;
    }

    private int findMatchingBracket(String text, int openIndex, char openBracket, char closeBracket) {
        int depth = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int index = openIndex; index < text.length(); index++) {
            char character = text.charAt(index);
            if (inString) {
                if (escaped) {
                    escaped = false;
                } else if (character == '\\') {
                    escaped = true;
                } else if (character == '"') {
                    inString = false;
                }
                continue;
            }

            if (character == '"') {
                inString = true;
                continue;
            }
            if (character == openBracket) {
                depth++;
            } else if (character == closeBracket) {
                depth--;
                if (depth == 0) {
                    return index;
                }
            }
        }

        throw new IllegalArgumentException("JSON 括号不完整");
    }

    private String readJsonString(String json, int quoteIndex) {
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
                throw new IllegalArgumentException("JSON 字符串转义不完整");
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
                        throw new IllegalArgumentException("JSON unicode 转义不完整");
                    }
                    String hex = json.substring(index + 1, index + 5);
                    value.append((char) Integer.parseInt(hex, 16));
                    index += 4;
                }
                default -> value.append(escaped);
            }
        }

        throw new IllegalArgumentException("JSON 字符串没有结束");
    }
}
