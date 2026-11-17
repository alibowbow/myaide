package dev.jun.gyeot.core;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * JavaScript number and text semantics the BotAvatar port depends on, so its output matches the
 * TypeScript engine exactly: Math.round, Number#toString, V8's Math.hypot, ToInt32, String#trim,
 * JSON.stringify of a string, and an order-preserving JSON reader (object keys in JS order).
 * Transcendental functions use StrictMath (fdlibm), which is what V8 uses as well.
 */
final class BotAvatarJs {
    private BotAvatarJs() {}

    /** JSON null, as read by {@link #parseJson}. */
    static final Object NULL = new Object() { @Override public String toString() { return "null"; } };

    /** Math.round: the closest integer, ties toward +Infinity, -0 kept. */
    static double round(double x) {
        if (Double.isNaN(x) || Double.isInfinite(x)) return x;
        double c = Math.ceil(x);
        return c - 0.5 > x ? c - 1 : c;
    }

    /** V8's Math.hypot: normalized to the largest magnitude, Kahan-summed squares. */
    static double hypot(double a, double b) {
        double x = Math.abs(a), y = Math.abs(b);
        if (Double.isInfinite(x) || Double.isInfinite(y)) return Double.POSITIVE_INFINITY;
        if (Double.isNaN(x) || Double.isNaN(y)) return Double.NaN;
        double max = Math.max(x, y);
        if (max == 0) return 0;
        double sum = 0, comp = 0;
        double n = x / max, summand = n * n - comp, pre = sum + summand;
        comp = (pre - sum) - summand; sum = pre;
        n = y / max; summand = n * n - comp; pre = sum + summand;
        sum = pre;
        return Math.sqrt(sum) * max;
    }

    static double hypot(double... values) {
        double max = 0;
        boolean nan = false;
        for (double v : values) {
            if (Double.isNaN(v)) nan = true;
            else if (Math.abs(v) > max) max = Math.abs(v);
        }
        if (max == Double.POSITIVE_INFINITY) return max;
        if (nan) return Double.NaN;
        if (max == 0) return 0;
        double sum = 0, comp = 0;
        for (double v : values) {
            double n = Math.abs(v) / max, summand = n * n - comp, pre = sum + summand;
            comp = (pre - sum) - summand;
            sum = pre;
        }
        return Math.sqrt(sum) * max;
    }

    /** ToInt32 (as in `n | 0`). */
    static int toInt32(double n) {
        if (Double.isNaN(n) || Double.isInfinite(n)) return 0;
        double t = n < 0 ? Math.ceil(n) : Math.floor(n);
        if (Math.abs(t) < 9.2e18) return (int) (long) t;
        double m = t % 4294967296.0;
        return (int) (long) m;
    }

    /** `a - b` as a sort comparator result (equal, NaN and ±0 compare as 0). */
    static int cmp(double difference) { return difference < 0 ? -1 : difference > 0 ? 1 : 0; }

    /** Number#toString(): the shortest decimal that reads back as the same double, laid out as ECMAScript does. */
    static String num(double v) {
        if (Double.isNaN(v)) return "NaN";
        if (v == 0) return "0";
        if (Double.isInfinite(v)) return v > 0 ? "Infinity" : "-Infinity";
        if (v < 0) return "-" + num(-v);
        if (v == Math.rint(v) && v < 1e15) return Long.toString((long) v);
        BigDecimal exact = new BigDecimal(v), best = null;
        for (int p = 1; p <= 17; p++) {
            BigDecimal r = exact.round(new MathContext(p, RoundingMode.HALF_EVEN));
            if (Double.parseDouble(r.toString()) == v) { best = r; break; }
        }
        if (best == null) best = exact.round(new MathContext(17, RoundingMode.HALF_EVEN));
        best = best.stripTrailingZeros();
        String s = best.unscaledValue().toString();
        int k = s.length(), n = k - best.scale();
        if (k <= n && n <= 21) return s + zeros(n - k);
        if (0 < n && n <= 21) return s.substring(0, n) + "." + s.substring(n);
        if (-6 < n && n <= 0) return "0." + zeros(-n) + s;
        int e = n - 1;
        String exp = "e" + (e >= 0 ? "+" : "-") + Math.abs(e);
        return k == 1 ? s + exp : s.charAt(0) + "." + s.substring(1) + exp;
    }

    private static String zeros(int n) {
        StringBuilder out = new StringBuilder(n);
        for (int i = 0; i < n; i++) out.append('0');
        return out.toString();
    }

    private static final long[] TEN = {1, 10, 100, 1000, 10000};

    /** `(Math.round(v * 10^decimals) / 10^decimals).toString()` with -0 printed as 0 (render.ts `f`, contour.ts `n1`). */
    static String fixed(double v, int decimals) {
        double scale = TEN[decimals];
        double k = round(v * scale);
        if (k == 0) return "0";
        if (Double.isNaN(k) || Math.abs(k) >= 1e15) return num(k / scale);
        long a = (long) Math.abs(k);
        long whole = a / TEN[decimals], frac = a % TEN[decimals];
        StringBuilder out = new StringBuilder(12);
        if (k < 0) out.append('-');
        out.append(whole);
        if (frac != 0) {
            String digits = Long.toString(frac);
            int end = digits.length();
            while (digits.charAt(end - 1) == '0') end--;
            out.append('.');
            for (int i = digits.length(); i < decimals; i++) out.append('0');
            out.append(digits, 0, end);
        }
        return out.toString();
    }

    /** WhiteSpace and LineTerminator as String#trim and the regex class \s see them. */
    static boolean isSpace(char c) {
        switch (c) {
            case '\t': case '\n': case '\u000b': case '\f': case '\r': case ' ': case ' ': case ' ':
            case ' ': case ' ': case ' ': case ' ': case '　': case '﻿':
                return true;
            default:
                return c >= ' ' && c <= ' ';
        }
    }

    static String trim(String s) {
        int a = 0, b = s.length();
        while (a < b && isSpace(s.charAt(a))) a++;
        while (b > a && isSpace(s.charAt(b - 1))) b--;
        return s.substring(a, b);
    }

    /** JSON.stringify of a string. */
    static String quote(String s) {
        StringBuilder out = new StringBuilder(s.length() + 2).append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"': out.append("\\\""); break;
                case '\\': out.append("\\\\"); break;
                case '\b': out.append("\\b"); break;
                case '\f': out.append("\\f"); break;
                case '\n': out.append("\\n"); break;
                case '\r': out.append("\\r"); break;
                case '\t': out.append("\\t"); break;
                default:
                    boolean lone = Character.isHighSurrogate(c) ? !(i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1)))
                        : Character.isLowSurrogate(c) && !(i > 0 && Character.isHighSurrogate(s.charAt(i - 1)));
                    if (c < 0x20 || lone) out.append(String.format("\\u%04x", (int) c));
                    else out.append(c);
            }
        }
        return out.append('"').toString();
    }

    /** Own enumerable keys in JS order: array indices ascending, then the rest in insertion order. */
    static List<String> keys(Map<String, Object> object) {
        List<String> indices = new ArrayList<>(), rest = new ArrayList<>();
        Map<String, Object> own = object; // java.util.Map.keySet is public on Android; org.json's JSONObject.keySet is hidden there
        for (String k : own.keySet()) (isIndex(k) ? indices : rest).add(k);
        indices.sort((a, b) -> Long.compare(Long.parseLong(a), Long.parseLong(b)));
        indices.addAll(rest);
        return indices;
    }

    private static boolean isIndex(String k) {
        if (k.isEmpty() || k.length() > 10) return false;
        for (int i = 0; i < k.length(); i++) if (k.charAt(i) < '0' || k.charAt(i) > '9') return false;
        if (k.length() > 1 && k.charAt(0) == '0') return false;
        return Long.parseLong(k) <= 4294967294L;
    }

    /** JSON.parse into LinkedHashMap / ArrayList / String / Double / Boolean / {@link #NULL}. */
    static Object parseJson(String text) {
        Reader r = new Reader(text);
        r.space();
        Object value = r.value();
        r.space();
        if (r.i != text.length()) throw r.error("unexpected text after the JSON value");
        return value;
    }

    /** org.json values (Android's or the host jar's) as the same plain structure; anything else unchanged. */
    @SuppressWarnings("unchecked")
    static Object plain(Object value) {
        if (value == null || value == org.json.JSONObject.NULL) return NULL;
        if (value instanceof org.json.JSONObject) {
            org.json.JSONObject o = (org.json.JSONObject) value;
            Map<String, Object> out = new LinkedHashMap<>();
            for (java.util.Iterator<String> it = o.keys(); it.hasNext(); ) { String k = it.next(); out.put(k, plain(o.opt(k))); }
            return out;
        }
        if (value instanceof org.json.JSONArray) {
            org.json.JSONArray a = (org.json.JSONArray) value;
            List<Object> out = new ArrayList<>();
            for (int i = 0; i < a.length(); i++) out.add(plain(a.opt(i)));
            return out;
        }
        if (value instanceof Map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : ((Map<?, ?>) value).entrySet()) out.put(String.valueOf(e.getKey()), plain(e.getValue()));
            return out;
        }
        if (value instanceof List) {
            List<Object> out = new ArrayList<>();
            for (Object item : (List<Object>) value) out.add(plain(item));
            return out;
        }
        if (value instanceof Number) return ((Number) value).doubleValue();
        return value;
    }

    private static final class Reader {
        final String s;
        int i;
        Reader(String s) { this.s = s; }

        IllegalArgumentException error(String why) { return new IllegalArgumentException(why + " at position " + i); }

        void space() { while (i < s.length() && (s.charAt(i) == ' ' || s.charAt(i) == '\t' || s.charAt(i) == '\n' || s.charAt(i) == '\r')) i++; }

        Object value() {
            if (i >= s.length()) throw error("unexpected end of JSON");
            char c = s.charAt(i);
            if (c == '{') {
                i++;
                Map<String, Object> out = new LinkedHashMap<>();
                space();
                if (i < s.length() && s.charAt(i) == '}') { i++; return out; }
                while (true) {
                    space();
                    if (i >= s.length() || s.charAt(i) != '"') throw error("expected a property name");
                    String key = string();
                    space();
                    expect(':');
                    space();
                    out.put(key, value());
                    space();
                    if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                    expect('}');
                    return out;
                }
            }
            if (c == '[') {
                i++;
                List<Object> out = new ArrayList<>();
                space();
                if (i < s.length() && s.charAt(i) == ']') { i++; return out; }
                while (true) {
                    space();
                    out.add(value());
                    space();
                    if (i < s.length() && s.charAt(i) == ',') { i++; continue; }
                    expect(']');
                    return out;
                }
            }
            if (c == '"') return string();
            if (s.startsWith("true", i)) { i += 4; return Boolean.TRUE; }
            if (s.startsWith("false", i)) { i += 5; return Boolean.FALSE; }
            if (s.startsWith("null", i)) { i += 4; return NULL; }
            return number();
        }

        void expect(char c) {
            if (i >= s.length() || s.charAt(i) != c) throw error("expected '" + c + "'");
            i++;
        }

        Double number() {
            int start = i;
            if (i < s.length() && s.charAt(i) == '-') i++;
            if (i < s.length() && s.charAt(i) == '0') i++;
            else if (i < s.length() && s.charAt(i) >= '1' && s.charAt(i) <= '9') digits();
            else throw error("unexpected character");
            if (i < s.length() && s.charAt(i) == '.') { i++; if (!digits()) throw error("expected a digit"); }
            if (i < s.length() && (s.charAt(i) == 'e' || s.charAt(i) == 'E')) {
                i++;
                if (i < s.length() && (s.charAt(i) == '+' || s.charAt(i) == '-')) i++;
                if (!digits()) throw error("expected a digit");
            }
            return Double.parseDouble(s.substring(start, i));
        }

        boolean digits() {
            int start = i;
            while (i < s.length() && s.charAt(i) >= '0' && s.charAt(i) <= '9') i++;
            return i > start;
        }

        String string() {
            i++;
            StringBuilder out = new StringBuilder();
            while (true) {
                if (i >= s.length()) throw error("unterminated string");
                char c = s.charAt(i++);
                if (c == '"') return out.toString();
                if (c < 0x20) throw error("control character in string");
                if (c != '\\') { out.append(c); continue; }
                if (i >= s.length()) throw error("unterminated string");
                char e = s.charAt(i++);
                switch (e) {
                    case '"': out.append('"'); break;
                    case '\\': out.append('\\'); break;
                    case '/': out.append('/'); break;
                    case 'b': out.append('\b'); break;
                    case 'f': out.append('\f'); break;
                    case 'n': out.append('\n'); break;
                    case 'r': out.append('\r'); break;
                    case 't': out.append('\t'); break;
                    case 'u':
                        if (i + 4 > s.length()) throw error("bad unicode escape");
                        try { out.append((char) Integer.parseInt(s.substring(i, i + 4), 16)); }
                        catch (NumberFormatException bad) { throw error("bad unicode escape"); }
                        i += 4;
                        break;
                    default: throw error("bad escape");
                }
            }
        }
    }
}
