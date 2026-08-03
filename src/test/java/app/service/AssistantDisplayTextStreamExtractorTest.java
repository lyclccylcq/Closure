package app.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AssistantDisplayTextStreamExtractorTest {
    @Test
    void extractsPartialDisplayTextFromJsonStream() {
        assertEquals(
                "博士，终端",
                AssistantDisplayTextStreamExtractor
                        .visibleText("{\"displayText\":\"博士，终端")
                        .orElseThrow()
        );
    }

    @Test
    void hidesJsonEnvelopeBeforeDisplayTextStarts() {
        assertTrue(AssistantDisplayTextStreamExtractor.visibleText("{\"dis").isEmpty());
    }

    @Test
    void keepsPlainFallbackTextStreaming() {
        assertEquals(
                "无人机整备",
                AssistantDisplayTextStreamExtractor.visibleText("无人机整备").orElseThrow()
        );
    }

    @Test
    void decodesEscapedCharactersInPartialDisplayText() {
        assertEquals(
                "博士，\"日志\"到了\n看一下？",
                AssistantDisplayTextStreamExtractor
                        .visibleText("{\"displayText\":\"博士，\\\"日志\\\"到了\\n看一下？\",\"speechText\":\"")
                        .orElseThrow()
        );
    }

    @Test
    void handlesJsonCodeFencePrefix() {
        assertEquals(
                "工程部在线",
                AssistantDisplayTextStreamExtractor
                        .visibleText("```json\n{\"displayText\":\"工程部在线")
                        .orElseThrow()
        );
    }
}
