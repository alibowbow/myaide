package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * What a provider says is left of an account's limits, read only from the headers of a response that came
 * anyway, never from a request made to read them: Codex's x-codex-primary-* and x-codex-secondary-* (used percent, window
 * minutes, reset-at in Unix seconds), Anthropic's anthropic-ratelimit-{requests,tokens,input-tokens,output-tokens}-
 * {limit,remaining,reset} (reset in RFC 3339) and OpenAI's x-ratelimit-{limit,remaining,reset}-{requests,tokens} (reset as a
 * duration such as "6m0s", or plain seconds). A window is the share left (0 to 1) and when it is full again (epoch milliseconds, 0 unknown); the
 * rate windows of an API key (per-minute requests and tokens) show as the tightest one, "속도 한도".
 */
public record Limits(List<Window> windows, long at) {
    public record Window(String name, double left, long reset, boolean rate) {}
    public static final Limits NONE = new Limits(List.of(), 0);
    /** A window without a reset time is shown this long after it was seen; one with a reset time until then. */
    static final long FRESH_MS = 60 * 60_000L;
    private static final String[][] RATES = {{"requests", "요청"}, {"tokens", "토큰"}, {"input-tokens", "입력 토큰"}, {"output-tokens", "출력 토큰"}};
    private static final Pattern DURATION = Pattern.compile("(\\d+(?:\\.\\d+)?)(ms|h|m|s)");

    public Limits { windows = windows == null ? List.of() : List.copyOf(windows); }
    public boolean empty() { return windows.isEmpty(); }

    /** The limits in one response's headers (any letter case); NONE when it carries none. */
    public static Limits headers(Map<String, String> headers, long now) {
        if (headers == null || headers.isEmpty()) return NONE;
        Map<String, String> h = new HashMap<>();
        for (Map.Entry<String, String> header : headers.entrySet()) if (header.getKey() != null && header.getValue() != null) h.put(header.getKey().toLowerCase(Locale.ROOT), header.getValue().strip());
        List<Window> found = new ArrayList<>();
        codex(h, "primary", "짧은 한도", now, found); codex(h, "secondary", "긴 한도", now, found);
        for (String[] rate : RATES) {
            Window anthropic = rate(h.get("anthropic-ratelimit-" + rate[0] + "-limit"), h.get("anthropic-ratelimit-" + rate[0] + "-remaining"), instant(h.get("anthropic-ratelimit-" + rate[0] + "-reset")), rate[1]);
            if (anthropic != null) found.add(anthropic);
        }
        for (String[] rate : RATES) {
            String reset = h.get("x-ratelimit-reset-" + rate[0]);
            Long after = reset == null ? null : duration(reset);
            Window openai = rate(h.get("x-ratelimit-limit-" + rate[0]), h.get("x-ratelimit-remaining-" + rate[0]), after == null ? 0 : now + after, rate[1]);
            if (openai != null) found.add(openai);
        }
        return found.isEmpty() ? NONE : new Limits(found, now);
    }
    /** One Codex window: used percent, its length in minutes and the reset (Unix seconds, or seconds from now in older servers). */
    private static void codex(Map<String, String> h, String which, String fallback, long now, List<Window> found) {
        Double used = decimal(h.get("x-codex-" + which + "-used-percent"));
        if (used == null) return;
        Double minutes = decimal(h.get("x-codex-" + which + "-window-minutes")), at = decimal(h.get("x-codex-" + which + "-reset-at")), after = decimal(h.get("x-codex-" + which + "-reset-after-seconds"));
        long reset = at != null && at > 0 ? (long) (at > 1e12 ? at : at * 1000) : after != null && after >= 0 ? now + (long) (after * 1000) : 0;
        // As Codex itself reads them: a window with nothing in it is not one.
        if (used == 0 && (minutes == null || minutes == 0) && reset == 0) return;
        found.add(new Window(minutes == null || minutes <= 0 ? fallback : length(Math.round(minutes)), clamp(1 - used / 100), reset, false));
    }
    /** "5시간", "주간", "하루", "30분", "3일". */
    static String length(long minutes) {
        if (minutes == 10_080) return "주간";
        if (minutes == 1_440) return "하루";
        if (minutes % 1_440 == 0) return minutes / 1_440 + "일";
        if (minutes % 60 == 0) return minutes / 60 + "시간";
        return minutes + "분";
    }
    private static Window rate(String limit, String remaining, long reset, String name) {
        Double most = decimal(limit), left = decimal(remaining);
        if (most == null || left == null || most <= 0) return null;
        return new Window(name, clamp(left / most), reset, true);
    }
    private static double clamp(double value) { return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value)); }
    private static Double decimal(String value) {
        if (value == null || value.isBlank()) return null;
        try { double number = Double.parseDouble(value.strip()); return Double.isFinite(number) ? number : null; } catch (NumberFormatException notNumber) { return null; }
    }
    /** An RFC 3339 time in epoch milliseconds, 0 when it is missing or not one. */
    static long instant(String value) {
        if (value == null || value.isBlank()) return 0;
        try { return OffsetDateTime.parse(value.strip()).toInstant().toEpochMilli(); }
        catch (Exception notOffset) { try { return Instant.parse(value.strip()).toEpochMilli(); } catch (Exception notInstant) { return 0; } }
    }
    /** OpenAI's reset duration ("1s", "6m0s", "17ms", "1h2m3.5s", or plain seconds as other servers send it: "60") in milliseconds, null when it is not one. */
    static Long duration(String value) {
        String text = value == null ? "" : value.strip();
        if (text.isEmpty()) return null;
        if (text.matches("\\d+(\\.\\d+)?")) return Math.round(Double.parseDouble(text) * 1000);
        Matcher part = DURATION.matcher(text); double millis = 0; int end = 0;
        while (part.find()) {
            if (part.start() != end) return null;
            double amount = Double.parseDouble(part.group(1));
            millis += switch (part.group(2)) { case "h" -> amount * 3_600_000; case "m" -> amount * 60_000; case "s" -> amount * 1_000; default -> amount; };
            end = part.end();
        }
        return end == text.length() && end > 0 ? Math.round(millis) : null;
    }

    /**
     * One line per window still current: "5시간 · 남은 72% · 14:30 초기화", the tightest rate window as "속도 한도 · 남은 98% ·
     * 14:03 초기화". A window whose reset time has passed is no longer known (the next response tells again).
     */
    public List<String> lines(long now, ZoneId zone) {
        List<String> lines = new ArrayList<>();
        for (Window window : current(now)) lines.add(window.name + " · " + left(window, now, zone));
        return lines;
    }
    /** The windows lines() shows, in its order: each current window, then the tightest rate window named "속도 한도". */
    public List<Window> current(long now) {
        List<Window> shown = new ArrayList<>(); Window tightest = null;
        for (Window window : windows) {
            if (window.reset > 0 ? window.reset <= now : now - at > FRESH_MS) continue;
            if (!window.rate) { shown.add(window); continue; }
            if (tightest == null || window.left < tightest.left) tightest = window;
        }
        if (tightest != null) shown.add(new Window("속도 한도", tightest.left, tightest.reset, true));
        return shown;
    }
    /** A window's heading on the usage page: "5시간 단위 한도", "주간 사용 한도", "속도 한도". */
    public static String heading(Window window) {
        String name = window.name.strip();
        if (window.rate || name.endsWith("한도")) return name;
        if (name.endsWith("시간") || name.endsWith("분")) return name + " 단위 한도";
        if (name.endsWith("주간") || name.endsWith("하루") || name.endsWith("월간") || name.endsWith("일")) return name + " 사용 한도";
        return name + " 한도";
    }
    /** "4시간 29분 후 초기화됩니다", "4일 22시간 후 초기화됩니다", "곧 초기화됩니다"; "초기화 시간 모름" without a reset time. */
    public static String until(Window window, long now) {
        if (window.reset <= 0) return "초기화 시간 모름";
        long minutes = (window.reset - now) / 60_000;
        if (minutes < 1) return "곧 초기화됩니다";
        long days = minutes / 1_440, hours = minutes % 1_440 / 60, rest = minutes % 60;
        String span = days > 0 ? days + "일" + (hours > 0 ? " " + hours + "시간" : "") : hours > 0 ? hours + "시간" + (rest > 0 ? " " + rest + "분" : "") : rest + "분";
        return span + " 후 초기화됩니다";
    }
    /** "49% 남음". */
    public static String remaining(Window window) { return (int) Math.floor(window.left * 100 + 1e-9) + "% 남음"; }
    /**
     * When the provider said it: "방금 전 기준", "3분 전 기준", "2시간 전 기준", "5일 전 기준"; "" when that is not known. A
     * weekly window seen days ago is still shown until its reset, so its age is shown with it.
     */
    public String seen(long now) {
        if (at <= 0) return "";
        long minutes = Math.max(0, now - at) / 60_000;
        return (minutes < 1 ? "방금 전" : minutes < 60 ? minutes + "분 전" : minutes < 1_440 ? minutes / 60 + "시간 전" : minutes / 1_440 + "일 전") + " 기준";
    }
    /** "남은 72% · 14:30 초기화", "남은 40% · 11.12 09:00 초기화" (another day), or "남은 72%" without a reset time. */
    public static String left(Window window, long now, ZoneId zone) {
        String line = "남은 " + (int) Math.floor(window.left * 100 + 1e-9) + "%";
        if (window.reset <= 0) return line;
        ZonedDateTime reset = Instant.ofEpochMilli(window.reset).atZone(zone);
        boolean today = reset.toLocalDate().equals(Instant.ofEpochMilli(now).atZone(zone).toLocalDate());
        return line + " · " + reset.format(DateTimeFormatter.ofPattern(today ? "HH:mm" : "M.d HH:mm")) + " 초기화";
    }

    /** {at, windows: [{name, left, reset, rate}]}: names and numbers only, for the usage ledger. */
    public JSONObject json() {
        JSONArray list = arr();
        for (Window window : windows) list.put(obj("name", window.name, "left", window.left, "reset", window.reset, "rate", window.rate));
        return obj("at", at, "windows", list);
    }
    public static Limits of(JSONObject json) {
        JSONArray list = json == null ? null : json.optJSONArray("windows");
        List<Window> windows = new ArrayList<>();
        for (int i = 0; list != null && i < list.length(); i++) {
            JSONObject window = list.optJSONObject(i);
            if (window != null) windows.add(new Window(window.optString("name"), clamp(window.optDouble("left", 0)), window.optLong("reset"), window.optBoolean("rate")));
        }
        return windows.isEmpty() ? NONE : new Limits(windows, json.optLong("at"));
    }
}
