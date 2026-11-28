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
        if (lines.isEmpty()) throw new IllegalArgumentException("账户结果为空");
        note = note == null ? "" : note;
    }

    public enum Kind { BALANCE, COST, USAGE }
    public record Line(String scope, BigDecimal amount, String unit, boolean unlimited, Kind kind) {
        public Line(String scope, BigDecimal amount, String unit, boolean unlimited) {
            this(scope, amount, unit, unlimited, Kind.BALANCE);
        }
        public Line {
            Objects.requireNonNull(scope); Objects.requireNonNull(unit); Objects.requireNonNull(kind);
            if (!unlimited) Objects.requireNonNull(amount);
            if (unlimited && kind != Kind.BALANCE) throw new IllegalArgumentException("费用与用量不能表示为无限额度");
        }
        public String text() {
            if (unlimited) return scope + "未设额度上限";
            return scope + (kind == Kind.BALANCE ? "剩余 " : "：") + amount.stripTrailingZeros().toPlainString() + " " + unit;
        }
    }

    public boolean isUsageReport() { return lines.stream().anyMatch(line -> line.kind() != Kind.BALANCE); }

    public String speechText() {
        return (isUsageReport() ? "博士，费用和用量查到啦。" : "博士，查到啦。")
                + lines.stream().map(line -> line.text().replace("（UTC）", "")
                .replace("USD", "美元").replace("CNY", "元人民币").replace("tokens", "个 token"))
                .collect(Collectors.joining("。")) + "。" + note;
    }
}
