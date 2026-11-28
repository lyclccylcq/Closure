package app.billing;

import org.junit.jupiter.api.Test;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class BalanceReportTest {
    private static final Instant NOW = Instant.parse("2026-11-28T10:30:00Z");
    private static BalanceReport daily(String value) {
        return new BalanceReport("OpenAI", List.of(
                new BalanceReport.Line("今日费用（UTC）", new BigDecimal(value), "USD", false, BalanceReport.Kind.COST),
                new BalanceReport.Line("本月费用（UTC）", new BigDecimal("123.456"), "USD", false, BalanceReport.Kind.COST),
                new BalanceReport.Line("本月输入", new BigDecimal("123456789"), "tokens", false, BalanceReport.Kind.USAGE)),
                "整段统计说明不应朗读", NOW);
    }
    @Test void dailyReportRoundsOnlyPresentationAndReplayKeepsItsShortWording() {
        BalanceReport report = daily("1.2345678901234567890123456789");
        assertTrue(report.speechText().contains("1.2美元")); assertTrue(report.summaryText().contains("1.2 USD"));
        assertEquals(new BigDecimal("1.2345678901234567890123456789"), report.lines().getFirst().amount());
        for (String hidden : List.of("123.456", "本月", "tokens", "123456789", "整段", "请求")) {
            assertFalse(report.speechText().contains(hidden)); assertFalse(report.summaryText().contains(hidden));
        }
        assertEquals(report.speechText(), report.speechText()); assertTrue(report.speechText().length() < 50);
        assertTrue(daily("1.25").speechText().contains("1.3美元"));
        assertTrue(daily("0.000000000001").speechText().contains("0.0美元"));
        assertTrue(daily("-0.15").speechText().contains("-0.2美元"));
    }
    @Test void fiveDollarThresholdUsesTheExactEstimateAndDoesNotInventUnsyncedFunds() {
        assertFalse(daily("100").lowFunds()); assertFalse(daily("100").speechText().contains("采购费"));
        for (String value : List.of("4.99", "4.9", "0.01", "0", "-1")) {
            BalanceReport report = daily("1.23").withFunding(new BalanceReport.Funding(new BigDecimal(value), NOW));
            assertTrue(report.lowFunds()); assertTrue(report.speechText().contains("采购费"));
            assertTrue(report.speechText().contains("估算")); assertTrue(report.summaryText().contains("估算余额"));
            assertEquals(new BigDecimal(value).signum() <= 0, report.emptyFunds());
        }
        assertFalse(daily("1").withFunding(new BalanceReport.Funding(new BigDecimal("5.0"), NOW)).lowFunds());
        assertFalse(daily("1").withFunding(new BalanceReport.Funding(new BigDecimal("20"), NOW)).speechText().contains("采购费"));
    }
    @Test void noRecordsAndOtherCurrenciesNeverTurnIntoAFabricatedDollarBalance() {
        var empty = new BalanceReport("OpenAI", List.of(new BalanceReport.Line("今日费用记录", BigDecimal.ZERO, "条", false, BalanceReport.Kind.USAGE)), "", NOW);
        assertFalse(empty.speechText().contains("美元")); assertFalse(empty.lowFunds());
        var yuan = new BalanceReport("本地替身", List.of(new BalanceReport.Line("账户", new BigDecimal("1"), "CNY", false)), "", NOW);
        assertFalse(yuan.lowFunds()); assertTrue(yuan.speechText().contains("1.0元人民币"));
        var token = new BalanceReport("本地替身", List.of(new BalanceReport.Line("令牌", new BigDecimal("1"), "USD", false)), "", NOW);
        assertFalse(token.lowFunds()); assertTrue(token.speechText().contains("控制台确认"));
    }
    @Test void newTimestampsVaryTheCharacterLineButEachResultRemainsStable() {
        var sample = daily("1.2");
        var texts = new java.util.HashSet<String>();
        for (int offset = 0; offset < 3; offset++) {
            var report = new BalanceReport(sample.platform(), sample.lines(), sample.note(), NOW.plusSeconds(offset), null,
                    new BalanceReport.Funding(new BigDecimal("4"), NOW));
            texts.add(report.speechText()); assertTrue(report.speechText().contains("博士"));
        }
        assertEquals(3, texts.size());
    }
}
