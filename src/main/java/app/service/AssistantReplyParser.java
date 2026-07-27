package app.service;

import app.chat.model.AssistantReply;
import app.voice.model.SpeechEmotion;

/**
 * 把模型原始响应解析成聊天显示文本、朗读文本和整条回复的主情绪。
 */
public final class AssistantReplyParser {
    public AssistantReply parse(String rawModelResponse) {
        String normalizedResponse = stripCodeFence(rawModelResponse);
        try {
            return parseJson(normalizedResponse);
        } catch (RuntimeException exception) {
            System.out.println("AssistantReply JSON 解析失败，使用降级文本：" + exception.getMessage());
            String displayText = fallbackDisplayText(normalizedResponse);
            return new AssistantReply(displayText, displayText, SpeechEmotion.NEUTRAL);
        }
    }

    private AssistantReply parseJson(String json) {
        String objectJson = extractObject(json);
        String displayText = extractStringField(objectJson, "displayText");
        if (displayText == null || displayText.isBlank()) {
            throw new IllegalArgumentException("displayText 缺失");
        }

        String speechText = extractStringField(objectJson, "speechText");
        SpeechEmotion declaredEmotion = SpeechEmotion.fromString(extractStringField(objectJson, "dominantEmotion"));
        SpeechEmotion dominantEmotion = normalizeDominantEmotion(displayText + "\n" + speechText, declaredEmotion);

        return new AssistantReply(displayText, speechText, dominantEmotion);
    }

    private String fallbackDisplayText(String response) {
        if (response == null || response.isBlank()) {
            return "工程部暂时没有收到模型回复。";
        }

        return response.trim();
    }

    private SpeechEmotion normalizeDominantEmotion(String text, SpeechEmotion declaredEmotion) {
        if (declaredEmotion == SpeechEmotion.NEUTRAL) {
            return SpeechEmotion.NEUTRAL;
        }

        int obviousEmotionKinds = 0;
        if (looksTeasing(text)) {
            obviousEmotionKinds++;
        }
        if (looksSerious(text)) {
            obviousEmotionKinds++;
        }
        if (looksQuestion(text)) {
            obviousEmotionKinds++;
        }
        if (looksCheerful(text)) {
            obviousEmotionKinds++;
        }

        if (obviousEmotionKinds > 1) {
            return SpeechEmotion.NEUTRAL;
        }

        return declaredEmotion == null ? SpeechEmotion.NEUTRAL : declaredEmotion;
    }

    private boolean looksTeasing(String text) {
        return containsAny(text, "维修费", "收费", "打折", "采购", "别戳", "偷懒", "摸鱼", "补觉", "催工期", "不是吧", "哼");
    }

    private boolean looksSerious(String text) {
        return containsAny(text, "立即停止", "立刻停止", "马上停止", "禁止继续", "不要继续", "会删除", "删除数据", "危险操作", "严重风险", "高风险");
    }

    private boolean looksQuestion(String text) {
        String trimmedText = text == null ? "" : text.trim();
        return trimmedText.endsWith("？") || trimmedText.endsWith("?");
    }

    private boolean looksCheerful(String text) {
        return containsAny(text, "太好了", "好耶", "恭喜", "欢迎", "太棒了", "真棒");
    }

    private boolean containsAny(String text, String... candidates) {
        if (text == null || text.isBlank()) {
            return false;
        }

        for (String candidate : candidates) {
            if (text.contains(candidate)) {
                return true;
            }
        }

        return false;
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

        throw new IllegalArgumentException("JSON 字符串没有闭合");
    }
}
