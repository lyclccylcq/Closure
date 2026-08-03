package app.service;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;

import static org.junit.jupiter.api.Assertions.assertTrue;

class PromptEnvironmentContextTest {
    @Test
    void includesCurrentDateTimeSeasonAndDayPeriod() {
        Clock clock = Clock.fixed(
                Instant.parse("2026-07-29T03:45:00Z"),
                ZoneId.of("Australia/Sydney")
        );
        PromptEnvironmentContext context = new PromptEnvironmentContext(clock);

        String prompt = context.currentContextPrompt();

        assertTrue(prompt.contains("2026年7月29日"));
        assertTrue(prompt.contains("星期三"));
        assertTrue(prompt.contains("13:45"));
        assertTrue(prompt.contains("Australia/Sydney"));
        assertTrue(prompt.contains("冬季"));
        assertTrue(prompt.contains("下午"));
        assertTrue(prompt.contains("今天、明天、今晚"));
    }
}
