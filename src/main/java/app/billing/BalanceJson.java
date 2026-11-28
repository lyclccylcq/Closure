package app.billing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Small strict JSON reader; financial values never pass through floating point. */
final class BalanceJson {
    private final String source;
    private int at;
    private BalanceJson(String source) { this.source = source; }

    static Map<String,Object> read(String source) {
        if (source == null || source.length() > 131072) throw invalid();
        BalanceJson reader = new BalanceJson(source);
        Map<String,Object> result = object(reader.value(0));
        reader.space();
        if (reader.at != source.length()) throw invalid();
        return result;
    }

    @SuppressWarnings("unchecked")
    static Map<String,Object> object(Object value) {
        if (!(value instanceof Map<?,?>)) throw invalid();
        return (Map<String,Object>) value;
    }

    static BigDecimal number(Object value) {
        try {
            BigDecimal number = value instanceof BigDecimal b ? b : new BigDecimal((String) value);
            if (number.precision() > 50 || Math.abs((long) number.scale()) > 24) throw invalid();
            return number;
        } catch (RuntimeException invalid) { throw invalid(); }
    }

    static String string(Object value) {
        if (!(value instanceof String text)) throw invalid();
        return text;
    }

    static boolean bool(Object value) {
        if (!(value instanceof Boolean b)) throw invalid();
        return b;
    }

    private Object value(int depth) {
        if (depth > 24) throw invalid();
        space();
        if (at >= source.length()) throw invalid();
        char c = source.charAt(at);
        if (c == '"') return string();
        if (take('{')) {
            Map<String,Object> fields = new LinkedHashMap<>();
            if (!takeSpace('}')) {
                do {
                    String key = string(); expect(':');
                    if (fields.containsKey(key)) throw invalid();
                    fields.put(key, value(depth + 1));
                    if (takeSpace('}')) break;
                    expect(',');
                } while (true);
            }
            return fields;
        }
        if (take('[')) {
            List<Object> values = new ArrayList<>();
            if (!takeSpace(']')) {
                do {
                    values.add(value(depth + 1));
                    if (takeSpace(']')) break;
                    expect(',');
                } while (true);
            }
            return values;
        }
        for (String literal : List.of("true", "false", "null")) {
            if (source.startsWith(literal, at)) {
                at += literal.length();
                return literal.equals("null") ? null : Boolean.valueOf(literal);
            }
        }
        int start = at;
        while (at < source.length() && "-+0123456789.eE".indexOf(source.charAt(at)) >= 0) at++;
        String token = source.substring(start, at);
        if (!token.matches("-?(0|[1-9][0-9]*)(\\.[0-9]+)?([eE][+-]?[0-9]+)?")) throw invalid();
        return number(token);
    }

    private String string() {
        expect('"'); StringBuilder text = new StringBuilder();
        while (at < source.length()) {
            char c = source.charAt(at++);
            if (c == '"') return text.toString();
            if (c < 32) throw invalid();
            if (c != '\\') { text.append(c); continue; }
            if (at >= source.length()) throw invalid();
            char escaped = source.charAt(at++);
            switch (escaped) {
                case '"', '\\', '/' -> text.append(escaped);
                case 'b' -> text.append('\b');
                case 'f' -> text.append('\f');
                case 'n' -> text.append('\n');
                case 'r' -> text.append('\r');
                case 't' -> text.append('\t');
                case 'u' -> {
                    if (at + 4 > source.length()) throw invalid();
                    String hex = source.substring(at, at + 4);
                    if (!hex.matches("[0-9a-fA-F]{4}")) throw invalid();
                    text.append((char) Integer.parseInt(hex, 16)); at += 4;
                }
                default -> throw invalid();
            }
        }
        throw invalid();
    }
    private void space() { while (at < source.length() && " \t\r\n".indexOf(source.charAt(at)) >= 0) at++; }
    private void expect(char c) { if (!takeSpace(c)) throw invalid(); }
    private boolean takeSpace(char c) { space(); return take(c); }
    private boolean take(char c) { if (at < source.length() && source.charAt(at) == c) { at++; return true; } return false; }
    static BalanceException invalid() { return new BalanceException("平台返回的账户数据不完整，暂时无法确认。请稍后重试。"); }
}
