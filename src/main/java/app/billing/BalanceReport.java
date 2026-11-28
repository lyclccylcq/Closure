package app.billing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record BalanceReport(String platform, List<Line> lines, String note, Instant checkedAt) {
    public BalanceReport {
        Objects.requireNonNull(platform); Objects.requireNonNull(checkedAt);
        lines = List.copyOf(lines);
        if (lines.isEmpty()) throw new IllegalArgumentException("余额结果为空");
        note = note == null ? "" : note;
    }

    public record Line(String scope, BigDecimal remaining, String unit, boolean unlimited) {
        public Line {
            Objects.requireNonNull(scope); Objects.requireNonNull(unit);
            if (!unlimited) Objects.requireNonNull(remaining);
        }
        public String text() {
            if (unlimited) return scope + "未设额度上限";
            return scope + "剩余 " + remaining.stripTrailingZeros().toPlainString() + " " + unit;
        }
    }

    public String speechText() {
        return "博士，查到啦。" + lines.stream().map(line -> line.text()
                .replace("USD", "美元").replace("CNY", "元人民币"))
                .collect(Collectors.joining("。")) + "。" + note;
    }
}
