package app.billing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

public record BalanceReport(String platform, List<Line> lines, String note, Instant checkedAt,
                            CostSnapshot costs, Funding funding) {
    public BalanceReport(String platform, List<Line> lines, String note, Instant checkedAt) {
        this(platform, lines, note, checkedAt, null, null);
    }
    public BalanceReport {
        Objects.requireNonNull(platform); Objects.requireNonNull(checkedAt);
        lines = List.copyOf(lines);
        if (lines.isEmpty()) throw new IllegalArgumentException("账户结果为空");
        note = note == null ? "" : note;
    }

    /** Exact observed costs for one complete, fixed window; never an official credit balance. */
    public record CostSnapshot(Instant from, Instant through, BigDecimal usdTotal, boolean usdOnly) {
        public CostSnapshot {
            Objects.requireNonNull(from); Objects.requireNonNull(through); Objects.requireNonNull(usdTotal);
            if (from.isAfter(through)) throw new IllegalArgumentException("费用区间无效");
        }
    }
    public record Funding(BigDecimal estimatedUsd, Instant syncedAt) {
        public Funding { Objects.requireNonNull(estimatedUsd); Objects.requireNonNull(syncedAt); }
    }
    public BalanceReport withFunding(Funding value) { return new BalanceReport(platform, lines, note, checkedAt, costs, value); }

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

    public boolean lowFunds() {
        BigDecimal amount = dollarFunds();
        return amount != null && amount.compareTo(new BigDecimal("5")) < 0;
    }
    public boolean emptyFunds() { return dollarFunds() != null && dollarFunds().signum() <= 0; }

    private BigDecimal dollarFunds() {
        if (funding != null) return funding.estimatedUsd();
        var accounts = lines.stream().filter(line -> line.kind() == Kind.BALANCE && line.scope().equals("账户")).toList();
        return accounts.size() == 1 && !accounts.getFirst().unlimited() && accounts.getFirst().unit().equals("USD")
                ? accounts.getFirst().amount() : null;
    }
    private List<Line> dailyCosts() {
        return lines.stream().filter(line -> line.kind() == Kind.COST && line.scope().startsWith("今日")).toList();
    }
    public static String oneDecimal(BigDecimal value) { return value.setScale(1, RoundingMode.HALF_UP).toPlainString(); }
    private static String spoken(Line line) {
        String unit = switch (line.unit()) { case "USD" -> "美元"; case "CNY" -> "元人民币"; default -> line.unit(); };
        return "约" + oneDecimal(line.amount()) + unit;
    }

    public String summaryText() {
        if (!isUsageReport()) return lines.stream().map(line -> line.unlimited() ? line.scope() + "未设额度上限"
                : line.scope() + "剩余：约" + oneDecimal(line.amount()) + " " + line.unit()).collect(Collectors.joining("\n"))
                + (note.isBlank() ? "" : "\n" + note);
        String daily = dailyCosts().isEmpty() ? "今日暂无费用记录"
                : "今日消耗：" + dailyCosts().stream().map(line -> "约" + oneDecimal(line.amount()) + " " + line.unit()).collect(Collectors.joining("、"));
        if (funding == null) return daily + "\n余额待同步，可用官方账单中的当前余额校准。";
        String amount = emptyFunds() ? "可能已用尽" : "约" + oneDecimal(funding.estimatedUsd()) + " USD";
        return daily + "\n估算余额：" + amount + (lowFunds() && !emptyFunds() ? " · 采购费不足" : "")
                + "\n同步于 " + DateTimeFormatter.ofPattern("MM-dd HH:mm").withZone(ZoneId.systemDefault()).format(funding.syncedAt());
    }

    /** One report keeps its wording on replay; new query timestamps select a short character line. */
    public String speechText() {
        int variant = (int) Math.floorMod(checkedAt.getEpochSecond(), 3);
        String text;
        if (isUsageReport()) {
            String daily = dailyCosts().stream().map(BalanceReport::spoken).collect(Collectors.joining("、"));
            text = daily.isEmpty() ? "博士，今天暂时还没有费用记录。" : switch (variant) {
                case 0 -> "博士，今天消耗了" + daily + "。";
                case 1 -> "查好啦，博士，今天用了" + daily + "。";
                default -> "博士，今天的开销是" + daily + "，账本我记好啦。";
            };
        } else {
            var accounts = lines.stream().filter(line -> line.scope().equals("账户") && !line.unlimited()).toList();
            if (accounts.isEmpty()) return "博士，令牌额度查到啦，账户余额还要到控制台确认哦。";
            text = accounts.stream().allMatch(line -> line.amount().signum() <= 0) ? "博士，账户已经没有可用余额了。"
                    : "博士，账户余额还有" + accounts.stream().map(BalanceReport::spoken).collect(Collectors.joining("、")) + "。";
        }
        if (!lowFunds()) return text;
        String estimate = funding == null ? "" : "按同步的账本估算，";
        if (emptyFunds()) return text + estimate + (variant == 0 ? "采购费见底啦，博士，补给得跟上哦。"
                : "采购费可能已经用完啦，记得给账户补点资金哦。");
        return text + estimate + switch (variant) {
            case 0 -> "采购费不足五美元啦，博士，记得补一点哦。";
            case 1 -> "采购费告急！已经不到五美元啦，博士该安排补给了。";
            default -> "欸，采购费还不到五美元啦，博士，可别忘了补给哦。";
        };
    }
}
