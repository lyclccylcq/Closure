package app.service;

import app.chat.model.AssistantReply;
import app.voice.model.SpeechEmotion;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;

class AssistantReplyParserTest {
    private final AssistantReplyParser parser = new AssistantReplyParser();

    @Test
    void keepsARealQuestionWhenTheSpeechSummaryAccidentallyBecomesAnExclamation(){
        var reply=parser.parse("""
                {"displayText":"博士，现在要不要先休息一下？","speechText":"博士，现在先休息一下！","dominantEmotion":"NEUTRAL"}
                """);
        assertEquals("博士，现在要不要先休息一下？",reply.getSpeechText());
        assertEquals(SpeechEmotion.QUESTION,reply.getDominantEmotion());
    }

    @Test
    void genuineQuestionsUseQuestionProfileButMixedSpeechAndTeasingKeepTheirIntent(){
        var question=parser.parse("""
                {"displayText":"今天想喝什么？","speechText":"今天想喝什么？","dominantEmotion":"NEUTRAL"}
                """);
        assertEquals(SpeechEmotion.QUESTION,question.getDominantEmotion());
        assertEquals(SpeechEmotion.NEUTRAL,SpeechProsody.emotionFor("已经修好了。还需要帮忙吗？",SpeechEmotion.NEUTRAL));
        assertEquals(SpeechEmotion.TEASING,SpeechProsody.emotionFor("博士，你又想省维修费了吗？",SpeechEmotion.TEASING));
        assertEquals(SpeechEmotion.NEUTRAL,SpeechProsody.emotionFor("先看日志？",SpeechEmotion.NEUTRAL));
    }

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
    void lexicalMixturesDoNotOverrideTheDeclaredDominantIntent() {
        AssistantReply reply = parser.parse("""
                {
                  "displayText": "太好了，编译过了！不过立即停止，这会删除数据！",
                  "speechText": "编译过了，但这个删除操作要立即停止。",
                  "dominantEmotion": "CHEERFUL"
                }
                """);

        assertEquals(SpeechEmotion.CHEERFUL, reply.getDominantEmotion());
    }

    @Test
    void teasingQuestionKeepsItsDeclaredIntentInBothParsingPaths(){
        String json="""
                {"displayText":"不是吧，你要给每个最终版都发毕业证吗？","speechText":"不是吧，你要给每个最终版都发毕业证吗？","dominantEmotion":"TEASING"}
                """;
        assertEquals(SpeechEmotion.TEASING,parser.parse(json).getDominantEmotion());
        assertEquals(SpeechEmotion.TEASING,parser.parseStructured(json).reply().getDominantEmotion());
    }

    @Test
    void strictPathRejectsPartialDuplicateTrailingAndWronglyTypedFields(){
        String valid="{\"displayText\":\"完整显示\",\"speechText\":\"完整朗读\",\"dominantEmotion\":\"NEUTRAL\"}";
        for(String invalid:new String[]{
                "", valid.substring(0,valid.length()-1), valid+"尾巴", "前言"+valid,
                valid.replace("\"完整朗读\"","null"),
                valid.replace("\"完整朗读\"","[]"),
                valid.replace("\"完整朗读\"","\"  \""),
                valid.replace("NEUTRAL","UNKNOWN"),
                valid.replace("完整显示","错误\\x转义"),
                valid.replace("完整显示","未转义\n换行"),
                valid.replace("\"displayText\":", "\"displayText\":\"重复\",\"displayText\":"),
                valid.replace("}",",\"extra\":\"字段\"}"),
                valid.replace("}",",}"),
                "```json\n"+valid
        }){
            assertThrows(IllegalArgumentException.class,()->parser.parseStructured(invalid),invalid);
        }
    }

    @Test
    void strictPathAcceptsEscapedContentAndClosedLegacyJsonFences(){
        String json="""
                {"displayText":"路径 C:\\\\draft 和 \\"引号\\"\\n下一行","speechText":"看一下草稿。","dominantEmotion":"NEUTRAL"}
                """.strip();
        var parsed=parser.parseStructured("```json\n"+json+"\n```");
        assertEquals(json,parsed.json());
        assertEquals("路径 C:\\draft 和 \"引号\"\n下一行",parsed.reply().getDisplayText());
    }

    @Test
    void strictSpeechLimitCountsUnicodeCodePoints(){
        String prefix="{\"displayText\":\"完整\",\"speechText\":\"";
        String suffix="\",\"dominantEmotion\":\"NEUTRAL\"}";
        assertEquals(120,parser.parseStructured(prefix+"😀".repeat(120)+suffix).reply()
                .getSpeechText().codePointCount(0,240));
        assertThrows(IllegalArgumentException.class,()->parser.parseStructured(prefix+"字".repeat(121)+suffix));
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
