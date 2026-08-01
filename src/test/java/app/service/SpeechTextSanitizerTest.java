package app.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpeechTextSanitizerTest {
    @Test
    void keepsPlainChineseText() {
        assertEquals(
                "博士，今天写代码进度不错。",
                SpeechTextSanitizer.sanitize("博士，今天写代码进度不错。")
        );
    }

    @Test
    void cleansMarkdownMarkers() {
        String text = """
                # 今日汇报
                **博士**，`终端`已经修好了。
                """;

        assertEquals("今日汇报\n博士，终端已经修好了。", SpeechTextSanitizer.sanitize(text));
    }

    @Test
    void removesUrls() {
        assertEquals(
                "博士，资料我看过了。",
                SpeechTextSanitizer.sanitize("博士，资料我看过了：https://example.com/report?id=1")
        );
    }

    @Test
    void removesCodeBlocks() {
        String text = """
                先看结论。
                ```java
                System.out.println("not for speech");
                ```
                然后再修。
                """;

        assertEquals("先看结论。\n然后再修。", SpeechTextSanitizer.sanitize(text));
    }

    @Test
    void emptyTextAfterCleaningStaysEmpty() {
        String text = """
                ```java
                int value = 1;
                ```
                https://example.com
                """;

        assertEquals("", SpeechTextSanitizer.sanitize(text));
    }

    @Test
    void truncatesLongTextAtSentenceBoundary() {
        String longText = "第一句。第二句。第三句。第四句。".repeat(20) + "尾巴不能出现。";

        String sanitized = SpeechTextSanitizer.sanitize(longText);

        assertTrue(sanitized.codePointCount(0, sanitized.length()) <= 200);
        assertTrue(sanitized.endsWith("。"));
        assertFalse(sanitized.contains("尾巴不能出现"));
    }

    @Test
    void sanitizeFullCleansTextWithoutTruncating() {
        String longText = "第一句。第二句。第三句。第四句。".repeat(20) + "尾巴也要朗读。";

        String sanitized = SpeechTextSanitizer.sanitizeFull(longText);

        assertEquals(longText, sanitized);
        assertTrue(sanitized.contains("尾巴也要朗读。"));
    }
}
