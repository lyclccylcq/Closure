package app.service;

import app.chat.model.AssistantReply;
import app.voice.model.SpeechEmotion;
import java.util.Set;

/**
 * 把模型原始响应解析成聊天显示文本、朗读文本和整条回复的主情绪。
 */
public final class AssistantReplyParser {
    public record StructuredReply(String json,AssistantReply reply) { }

    /** Only complete contract-valid replies may enter conversation history. */
    public StructuredReply parseStructured(String rawModelResponse){
        if(rawModelResponse==null || rawModelResponse.isBlank()){
            throw new IllegalArgumentException("模型没有返回完整回复");
        }
        String raw=rawModelResponse.trim();
        if(raw.startsWith("```") && !raw.endsWith("```")){
            throw new IllegalArgumentException("模型回复的代码围栏未结束");
        }
        String json=stripCodeFence(raw).trim();
        var fields=ReplyJsonObject.read(json);
        if(!fields.keySet().equals(Set.of("displayText","speechText","dominantEmotion"))){
            throw new IllegalArgumentException("模型回复字段不符合协议");
        }
        String display=fields.get("displayText");
        String speech=fields.get("speechText");
        if(display.isBlank() || speech.isBlank()){
            throw new IllegalArgumentException("模型回复文本为空");
        }
        if(speech.codePointCount(0,speech.length())>AssistantReply.MAX_SPEECH_TEXT_CODE_POINTS){
            throw new IllegalArgumentException("模型朗读文本超过 120 字符");
        }
        SpeechEmotion emotion;
        try{
            emotion=SpeechEmotion.valueOf(fields.get("dominantEmotion"));
        }catch(IllegalArgumentException invalid){
            throw new IllegalArgumentException("模型回复情绪标签不符合协议");
        }
        speech=SpeechProsody.preserveQuestion(display,speech);
        emotion=SpeechProsody.emotionFor(speech,emotion);
        return new StructuredReply(json,new AssistantReply(display,speech,emotion));
    }

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

        String speechText = SpeechProsody.preserveQuestion(displayText,extractStringField(objectJson, "speechText"));
        SpeechEmotion declaredEmotion = SpeechEmotion.fromString(extractStringField(objectJson, "dominantEmotion"));
        declaredEmotion = SpeechProsody.emotionFor(speechText,declaredEmotion);
        return new AssistantReply(displayText, speechText, declaredEmotion);
    }

    private String fallbackDisplayText(String response) {
        if (response == null || response.isBlank()) {
            return "工程部暂时没有收到模型回复。";
        }

        return response.trim();
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
