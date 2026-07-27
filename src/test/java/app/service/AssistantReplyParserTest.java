package app.service;

import app.chat.model.AssistantReply;
import app.voice.model.SpeechEmotion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class AssistantReplyParserTest {
    private final AssistantReplyParser parser = new AssistantReplyParser();

    @Test
    void parsesValidJson() {
        AssistantReply reply = parser.parse("""
                {
                  "displayText": "博士，终端修好了。维修费先记账。",
                  "speechSegments": [
                    {"text": "博士，终端修好了。", "emotion": "CHEERFUL"},
                    {"text": "维修费先记账。", "emotion": "TEASING"}
                  ]
                }
                """);

        assertEquals("博士，终端修好了。维修费先记账。", reply.getDisplayText());
        assertEquals(2, reply.getSpeechSegments().size());
        assertEquals(SpeechEmotion.CHEERFUL, reply.getSpeechSegments().getFirst().getEmotion());
        assertEquals(SpeechEmotion.TEASING, reply.getSpeechSegments().get(1).getEmotion());
    }

    @Test
    void parsesJsonCodeFence() {
        AssistantReply reply = parser.parse("""
                ```json
                {"displayText":"别戳啦，我在呢。","speechSegments":[{"text":"别戳啦，我在呢。","emotion":"TEASING"}]}
                ```
                """);

        assertEquals("别戳啦，我在呢。", reply.getDisplayText());
        assertEquals(SpeechEmotion.TEASING, reply.getSpeechSegments().getFirst().getEmotion());
    }

    @Test
    void fallsBackWhenJsonIsInvalid() {
        AssistantReply reply = parser.parse("博士，别急。先看日志？");

        assertEquals("博士，别急。先看日志？", reply.getDisplayText());
        assertEquals(2, reply.getSpeechSegments().size());
        assertEquals(SpeechEmotion.NEUTRAL, reply.getSpeechSegments().getFirst().getEmotion());
        assertEquals(SpeechEmotion.QUESTION, reply.getSpeechSegments().get(1).getEmotion());
    }

    @Test
    void ordinarySuccessNoticeFallsBackToNeutral() {
        AssistantReply reply = parser.parse("程序已经运行成功了！");

        assertEquals(SpeechEmotion.NEUTRAL, reply.getSpeechSegments().getFirst().getEmotion());
    }

    @Test
    void explicitCheerfulFallbackCanUseCheerful() {
        AssistantReply reply = parser.parse("太好了，终于成功了！");

        assertEquals(SpeechEmotion.CHEERFUL, reply.getSpeechSegments().getFirst().getEmotion());
    }

    @Test
    void ordinaryReminderFallsBackToNeutral() {
        AssistantReply reply = parser.parse("记得先保存文件。");

        assertEquals(SpeechEmotion.NEUTRAL, reply.getSpeechSegments().getFirst().getEmotion());
    }

    @Test
    void explicitStopWarningFallsBackToSerious() {
        AssistantReply reply = parser.parse("立即停止，这会删除数据！");

        assertEquals(SpeechEmotion.SERIOUS, reply.getSpeechSegments().getFirst().getEmotion());
    }

    @Test
    void ambiguousFallbackUsesNeutral() {
        AssistantReply reply = parser.parse("嗯……差不多吧。");

        assertEquals(SpeechEmotion.NEUTRAL, reply.getSpeechSegments().getFirst().getEmotion());
    }

    @Test
    void acceptsEmptySpeechSegments() {
        AssistantReply reply = parser.parse("""
                {"displayText":"工程部在线。","speechSegments":[]}
                """);

        assertEquals("工程部在线。", reply.getDisplayText());
        assertEquals(0, reply.getSpeechSegments().size());
    }

    @Test
    void unknownEmotionFallsBackToNeutral() {
        AssistantReply reply = parser.parse("""
                {"displayText":"工程部在线。","speechSegments":[{"text":"工程部在线。","emotion":"SLEEPY"}]}
                """);

        assertEquals(SpeechEmotion.NEUTRAL, reply.getSpeechSegments().getFirst().getEmotion());
    }

    @Test
    void keepsAtMostFiveSegments() {
        AssistantReply reply = parser.parse("""
                {"displayText":"一。二。三。四。五。六。","speechSegments":[
                {"text":"一。","emotion":"NEUTRAL"},
                {"text":"二。","emotion":"NEUTRAL"},
                {"text":"三。","emotion":"NEUTRAL"},
                {"text":"四。","emotion":"NEUTRAL"},
                {"text":"五。","emotion":"NEUTRAL"},
                {"text":"六。","emotion":"NEUTRAL"}]}
                """);

        assertEquals(5, reply.getSpeechSegments().size());
    }

    @Test
    void displayTextDoesNotShowEmotionMetadata() {
        AssistantReply reply = parser.parse("""
                {"displayText":"博士，咖啡在桌上。","speechSegments":[{"text":"博士，咖啡在桌上。","emotion":"NEUTRAL"}]}
                """);

        assertFalse(reply.getDisplayText().contains("emotion"));
        assertFalse(reply.getDisplayText().contains("NEUTRAL"));
    }
}
