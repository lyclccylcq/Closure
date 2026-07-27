package app.service;

import app.chat.model.AssistantReply;
import app.voice.model.SpeechEmotion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantReplyParserTest {
    private final AssistantReplyParser parser = new AssistantReplyParser();

    @Test
    void parsesDisplayTextSpeechTextAndDominantEmotion() {
        AssistantReply reply = parser.parse("""
                {
                  "displayText": "博士，终端修好了。维修费先记账。",
                  "speechText": "终端修好了，维修费先记账。",
                  "dominantEmotion": "TEASING"
                }
                """);

        assertEquals("博士，终端修好了。维修费先记账。", reply.getDisplayText());
        assertEquals("终端修好了，维修费先记账。", reply.getSpeechText());
        assertEquals(SpeechEmotion.TEASING, reply.getDominantEmotion());
    }

    @Test
    void parsesJsonCodeFence() {
        AssistantReply reply = parser.parse("""
                ```json
                {"displayText":"别戳啦，我在呢。","speechText":"别戳啦，我在呢。","dominantEmotion":"TEASING"}
                ```
                """);

        assertEquals("别戳啦，我在呢。", reply.getDisplayText());
        assertEquals("别戳啦，我在呢。", reply.getSpeechText());
        assertEquals(SpeechEmotion.TEASING, reply.getDominantEmotion());
    }

    @Test
    void fallsBackWhenJsonIsInvalid() {
        AssistantReply reply = parser.parse("博士，别急。先看日志？");

        assertEquals("博士，别急。先看日志？", reply.getDisplayText());
        assertEquals("博士，别急。先看日志？", reply.getSpeechText());
        assertEquals(SpeechEmotion.NEUTRAL, reply.getDominantEmotion());
    }

    @Test
    void mixedEmotionReplyFallsBackToNeutral() {
        AssistantReply reply = parser.parse("""
                {
                  "displayText": "太好了，编译过了！不过立即停止，这会删除数据！",
                  "speechText": "编译过了，但这个删除操作要立即停止。",
                  "dominantEmotion": "CHEERFUL"
                }
                """);

        assertEquals(SpeechEmotion.NEUTRAL, reply.getDominantEmotion());
    }

    @Test
    void displayTextAndSpeechTextStaySeparated() {
        AssistantReply reply = parser.parse("""
                {
                  "displayText": "先执行 `mvn test`，日志在 https://example.com/report。",
                  "speechText": "先跑测试，再看日志结果。",
                  "dominantEmotion": "NEUTRAL"
                }
                """);

        assertEquals("先执行 `mvn test`，日志在 https://example.com/report。", reply.getDisplayText());
        assertEquals("先跑测试，再看日志结果。", reply.getSpeechText());
        assertFalse(reply.getSpeechText().contains("https://"));
        assertFalse(reply.getSpeechText().contains("`"));
    }

    @Test
    void longTechnicalSpeechTextIsSanitizedAndTruncated() {
        AssistantReply reply = parser.parse("""
                {
                  "displayText": "这次问题在配置和线程时序。",
                  "speechText": "### 技术总结：问题主要在配置加载顺序和线程时序。先确认本地配置是否存在，再确认初始化只执行一次，最后观察日志。```java System.out.println(\\"skip\\");```这一段很长很长很长很长很长很长很长很长很长很长很长很长。",
                  "dominantEmotion": "NEUTRAL"
                }
                """);

        assertTrue(reply.getSpeechText().codePointCount(0, reply.getSpeechText().length())
                <= AssistantReply.MAX_SPEECH_TEXT_CODE_POINTS);
        assertFalse(reply.getSpeechText().contains("```"));
        assertFalse(reply.getSpeechText().contains("System.out"));
    }

    @Test
    void unknownEmotionFallsBackToNeutral() {
        AssistantReply reply = parser.parse("""
                {"displayText":"工程部在线。","speechText":"工程部在线。","dominantEmotion":"SLEEPY"}
                """);

        assertEquals(SpeechEmotion.NEUTRAL, reply.getDominantEmotion());
    }

    @Test
    void displayTextDoesNotShowEmotionMetadata() {
        AssistantReply reply = parser.parse("""
                {"displayText":"博士，咖啡在桌上。","speechText":"咖啡在桌上。","dominantEmotion":"NEUTRAL"}
                """);

        assertFalse(reply.getDisplayText().contains("dominantEmotion"));
        assertFalse(reply.getDisplayText().contains("NEUTRAL"));
    }
}
