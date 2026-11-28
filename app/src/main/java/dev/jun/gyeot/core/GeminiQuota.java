package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Gemini 무료 한도: what the app's own Gemini calls (search, titles, attachments, recordings, video summaries, the Gemini
 * brain) have used of each model's free tier today, kept in one place so they share it. Each model's free limits start from
 * what Google's rate-limit page showed (KNOWN) and give way to the limit Google names when it refuses ("limit: N" in a 429).
 * A model whose day is used up is left out until midnight Pacific time, when Google resets it; one that hit its per-minute
 * limit rests as long as Google said (a minute when it did not). The order keeps the caller's own: best first.
 *
 * State, per model: day (the Pacific date of uses), uses, full (today's quota used up), limit (per day, as Google named it),
 * missing (this key cannot use it), minute and minuteUses (the current minute's requests), rest (no request before then).
 * Pure Java; the caller keeps the state and serializes access.
 */
public final class GeminiQuota {
    private GeminiQuota() {}
    /** A model's free tier: requests a minute and a day, and tokens a minute (0: not known). */
    public record Free(int perMinute, int perDay, int tokensPerMinute, long tokensPerDay) {
        Free(int perMinute, int perDay) { this(perMinute, perDay, 0, 0); }
        Free(int perMinute, int perDay, int tokensPerMinute) { this(perMinute, perDay, tokensPerMinute, 0); }
    }
    /** As Google's rate-limit page shows them for a free key; Google may change them, and what it names in a refusal wins. */
    static final Map<String, Free> KNOWN = Map.ofEntries(
        // AI Studio's 비율 제한 for a free key, as 준 sent it 2026-11-28 (requests a minute, a day, tokens a minute; no tokens a day).
        Map.entry("gemini-3.5-flash-lite", new Free(15, 500, 250_000)), Map.entry("gemini-3.1-flash-lite", new Free(15, 500, 250_000)),
        Map.entry("gemini-3.5-transcribe", new Free(3, 25, 10_000)), Map.entry("gemini-flash-latest", new Free(5, 20, 250_000)),
        Map.entry("gemini-3.8-flash", new Free(5, 20, 250_000)), Map.entry("gemini-3.6-flash", new Free(5, 20, 250_000)),
        Map.entry("gemini-3-flash-preview", new Free(5, 20, 250_000)), Map.entry("gemini-4-hydrogen", new Free(5, 20, 250_000)),
        Map.entry("gemini-2.5-flash", new Free(5, 20, 250_000)), Map.entry("gemini-2.5-flash-lite", new Free(10, 20, 250_000)),
        Map.entry("gemini-decision-1", new Free(200, 10_000, 50_000)), Map.entry("gemini-2.5-flash-preview-tts", new Free(3, 10, 10_000)));
    /** Every Gemma 4 model (31B, 26B and any size the key lists): many requests a day, few tokens a minute. */
    static final Free GEMMA_4 = new Free(30, 14_400, 16_000);
    /** Gemini's embedding models (뜻으로 찾기): many requests a minute, a thousand a day each. */
    static final Free EMBEDDING = new Free(100, 1_000, 30_000);
    /**
     * Groq's free tier (console.groq.com → Limits, as 준 sent it 2026-11-28): GPT OSS 120B and 20B and Qwen 3.8 27B 30 requests a
     * minute, 1,000 a day, 8,000 tokens a minute and 200,000 a day; Llama 3.3 70B 12,000 and 100,000; the safeguard 3 a minute and
     * 2,000 tokens; compound (web search) 250 a day; Whisper 2,000 a day (and 8 hours of audio, not counted here).
     */
    static final Free GROQ_TEXT = new Free(30, 1_000, 8_000, 200_000), GROQ_LLAMA = new Free(30, 1_000, 12_000, 100_000),
        GROQ_SAFEGUARD = new Free(3, 1_000, 2_000, 200_000), GROQ_COMPOUND = new Free(30, 250, 0), GROQ_WHISPER = new Free(20, 2_000, 0);
    /** A model's free tier as known, or null. */
    public static Free free(String model) {
        Free known = KNOWN.get(model);
        if (known != null || model == null) return known;
        if (model.startsWith("openai/gpt-oss-safeguard")) return GROQ_SAFEGUARD;
        if (model.startsWith("meta-llama/llama-3.3-70b")) return GROQ_LLAMA;
        if (model.startsWith("openai/gpt-oss-") || model.startsWith("qwen/") || model.startsWith("meta-llama/")) return GROQ_TEXT;
        if (model.startsWith("groq/compound")) return GROQ_COMPOUND;
        if (model.startsWith("whisper-")) return GROQ_WHISPER;
        return model.startsWith("gemma-4-") ? GEMMA_4 : model.startsWith("gemini-embedding-") ? EMBEDDING : null;
    }
    /**
     * Today's quota used up. A Groq model's mark from before its limit's kind was kept (an earlier version left any Groq day limit
     * out until midnight Pacific, though Groq takes requests again minutes later) does not count: it is asked again, and a refusal
     * now keeps the kind and the wait Groq names.
     */
    private static boolean full(String model, JSONObject entry) { return entry.optBoolean("full") && !(groq(model) && entry.optString("dayKind").isEmpty()); }
    /** A Groq model (its ids carry an owner, "openai/gpt-oss-120b", or are Whisper's): Groq's own section and limits. */
    public static boolean groq(String model) { return model != null && (model.contains("/") || model.startsWith("whisper-")); }
    /**
     * Groq refused a model for a limit of the day (준: "1000회 중에 13회만 썼는데 왜 막힌거야"): Groq counts requests and tokens a
     * day apart ("tokens per day (TPD): Limit 200000, Used 199500"), and says when the model takes requests again ("try again in
     * 7m12.5s"; its day is not a calendar day). Kept: which limit (requests, tokens, audio seconds) and the number Groq named; the
     * model rests that long (a day at most), or, when Groq named no time, is left out until the day turns.
     */
    public static void groqDay(JSONObject state, String model, long now, String body) {
        String text = body == null ? "" : body.toLowerCase(Locale.ROOT);
        String kind = text.contains("(tpd)") || text.contains("tokens per day") ? "tokens" : text.contains("(asd)") || text.contains("audio seconds") ? "audio" : "requests";
        JSONObject entry = today(state, model, now);
        put(entry, "dayKind", kind);
        Matcher named = Pattern.compile("(?i)limit\\s+(\\d+)").matcher(body == null ? "" : body);
        if (named.find()) { long n = Long.parseLong(named.group(1)); if (kind.equals("tokens")) put(entry, "tokenLimit", n); else if (kind.equals("requests")) put(entry, "limit", (int) Math.min(Integer.MAX_VALUE, n)); }
        long wait = groqWait(body);
        if (wait > 0) put(entry, "rest", now + Math.min(DAY, wait)); else put(entry, "full", true);
    }
    static final long DAY = 24 * 60 * 60_000L;
    /** Groq's "Please try again in 1h2m3.5s", "7m12.5s", "7.66s" or "450ms" in a 429, as ms; -1 when it names no time. */
    static long groqWait(String body) {
        Matcher m = Pattern.compile("try again in ((?:\\d+h)?(?:\\d+m(?!s))?(?:[0-9.]+s)?(?:[0-9.]+ms)?)").matcher(body == null ? "" : body);
        if (!m.find() || m.group(1).isEmpty()) return -1;
        Matcher part = Pattern.compile("([0-9.]+)(ms|h|m|s)").matcher(m.group(1));
        double ms = 0;
        while (part.find()) { double v = Double.parseDouble(part.group(1)); ms += switch (part.group(2)) { case "h" -> v * 3_600_000; case "m" -> v * 60_000; case "s" -> v * 1000; default -> v; }; }
        return Math.round(ms) + 500;
    }
    /** A per-minute refusal: no request to it for ms (within a second and ten minutes). */
    public static void rest(JSONObject state, String model, long now, long ms) { put(entry(state, model), "rest", now + Math.max(1000, Math.min(LONGEST_REST, ms))); }
    /** Groq's "Please try again in 1m2.5s" (or "7.66s") in a 429, as ms; a minute when it says none. */
    public static long groqRetry(String body) { long wait = groqWait(body); return wait > 0 ? wait : MINUTE; }
    /** The first of models this key can use (not found missing), or null. */
    public static String usable(String[] models, JSONObject state) {
        for (String model : models) { JSONObject entry = state == null ? null : state.optJSONObject(model); if (entry == null || !entry.optBoolean("missing")) return model; }
        return null;
    }
    /** The model can take a request of about tokens now: not missing, its day not used up, not resting, under its minute's limits. */
    public static boolean ready(String model, JSONObject state, long now, int tokens) {
        JSONObject entry = state == null ? null : state.optJSONObject(model);
        if (entry != null && entry.optBoolean("missing")) return false;
        boolean current = entry != null && day(now).equals(entry.optString("day"));
        if (current && full(model, entry)) return false;
        if (entry != null && entry.optLong("rest") > now) return false;
        int perDay = perDay(state, model); if (perDay > 0 && uses(state, model, now) >= perDay) return false;
        Free known = free(model);
        // Tokens a day (Groq's TPD): today's here are never more than Groq's own count of its last day, so this stops no sooner.
        long tokenDay = entry == null ? 0 : entry.optLong("tokenLimit"); if (tokenDay <= 0 && known != null) tokenDay = known.tokensPerDay();
        if (tokenDay > 0 && tokens(state, model, now) + Math.max(0, tokens) > tokenDay) return false;
        boolean minute = entry != null && now - entry.optLong("minute") < MINUTE;
        if (known != null && known.perMinute() > 0 && minute && entry.optInt("minuteUses") >= known.perMinute()) return false;
        return known == null || known.tokensPerMinute() <= 0 || tokens <= 0 || (minute ? entry.optInt("minuteTokens") : 0) + tokens <= known.tokensPerMinute();
    }
    private static final ZoneId PACIFIC = ZoneId.of("America/Los_Angeles");
    static final long MINUTE = 60_000, LONGEST_REST = 10 * MINUTE;
    /** "Please retry in 23.4s" or "retryDelay": "23s" in a 429's body. */
    private static final Pattern RETRY = Pattern.compile("(?i)(?:retry\\s+in\\s+|\"retryDelay\"\\s*:\\s*\")([0-9]+(?:\\.[0-9]+)?)s");

    /** The quota day of a moment: the date in Pacific time. */
    public static String day(long now) { return Instant.ofEpochMilli(now).atZone(PACIFIC).toLocalDate().toString(); }
    /** When the free daily quotas reset next: midnight Pacific time. */
    public static long resetAt(long now) {
        ZonedDateTime pacific = Instant.ofEpochMilli(now).atZone(PACIFIC);
        return pacific.toLocalDate().plusDays(1).atStartOfDay(PACIFIC).toInstant().toEpochMilli();
    }
    /** The free requests a day: as Google named it in a refusal, else as its page showed, else 0 (not known). */
    public static int perDay(JSONObject state, String model) {
        JSONObject entry = state == null ? null : state.optJSONObject(model);
        int named = entry == null ? 0 : entry.optInt("limit");
        if (named > 0) return named;
        Free known = free(model);
        return known == null ? 0 : known.perDay();
    }
    /** Tokens the model's answers counted today (0 when the last were on another day). */
    public static long tokens(JSONObject state, String model, long now) {
        JSONObject entry = state == null ? null : state.optJSONObject(model);
        return entry == null || !day(now).equals(entry.optString("day")) ? 0 : entry.optLong("tokens");
    }
    /** Requests today (0 when the last were on another day). */
    public static int uses(JSONObject state, String model, long now) {
        JSONObject entry = state == null ? null : state.optJSONObject(model);
        return entry == null || !day(now).equals(entry.optString("day")) ? 0 : entry.optInt("uses");
    }

    /**
     * The models to try now, in the caller's order: not missing, today's quota not used up (as Google said, or as counted
     * against the known limit), not resting, and under the known per-minute limit. When none is left, the first one this key
     * can use is tried anyway: the counts may be off, or the quota may have reset.
     */
    public static List<String> order(String[] models, JSONObject state, long now) { return order(models, state, now, 0); }
    /**
     * As above, also leaving out a model whose tokens a minute this request (an estimate) would go past, counting what it
     * answered in the current minute; a request bigger than the whole minute's tokens leaves it out too.
     */
    public static List<String> order(String[] models, JSONObject state, long now, int tokens) {
        List<String> ready = new ArrayList<>();
        String fallback = null;
        for (String model : models) {
            JSONObject entry = state == null ? null : state.optJSONObject(model);
            if (entry != null && entry.optBoolean("missing")) continue;
            if (fallback == null) fallback = model;
            if (ready(model, state, now, tokens) && !ready.contains(model)) ready.add(model);
        }
        if (ready.isEmpty()) ready.add(fallback != null ? fallback : models[0]);
        return ready;
    }
    /** One request the model answered. */
    public static void used(JSONObject state, String model, long now) { used(state, model, now, 0); }
    /** One request the model answered, with the tokens it counted (usageMetadata.totalTokenCount; 0 when not given). */
    public static void used(JSONObject state, String model, long now, int tokens) {
        JSONObject entry = today(state, model, now);
        put(entry, "uses", entry.optInt("uses") + 1);
        put(entry, "tokens", entry.optLong("tokens") + Math.max(0, tokens));
        if (now - entry.optLong("minute") >= MINUTE) { put(entry, "minute", now); put(entry, "minuteUses", 0); put(entry, "minuteTokens", 0); }
        put(entry, "minuteUses", entry.optInt("minuteUses") + 1);
        put(entry, "minuteTokens", entry.optInt("minuteTokens") + Math.max(0, tokens));
    }
    /**
     * Google refused the model (status and the whole error body): today's quota used up (kept with the limit it named), its
     * per-minute limit (it rests as Google said), or a model this key cannot use (for good). True when it was one of those,
     * so the next model should be tried.
     */
    public static boolean refused(JSONObject state, String model, int status, String body, long now) {
        String quota = GeminiThink.quota(status, body);
        if (quota.equals("day")) {
            JSONObject entry = today(state, model, now); put(entry, "full", true);
            int limit = GeminiThink.limit(body); if (limit > 0) put(entry, "limit", limit);
            return true;
        }
        if (quota.equals("minute")) { put(entry(state, model), "rest", now + retry(body)); return true; }
        if (GeminiThink.missing(status, body)) { put(entry(state, model), "missing", true); return true; }
        return false;
    }
    /** How long Google asked to wait, within a minute's default and ten minutes at most. */
    static long retry(String body) {
        Matcher m = RETRY.matcher(body == null ? "" : body);
        if (!m.find()) return MINUTE;
        long ms = Math.round(Double.parseDouble(m.group(1)) * 1000) + 500;
        return Math.max(1000, Math.min(LONGEST_REST, ms));
    }

    /**
     * One model on 설정 → 사용량: its name, today's requests, the free requests a day (0 not known), how it stands now ("" fine),
     * whether it is stopped now (blocked), today's tokens and the tokens a day (0 not known).
     */
    public record Row(String model, String label, int uses, int perDay, String status, boolean blocked, long tokens, long tokensPerDay) {
        public Row(String model, String label, int uses, int perDay, String status) { this(model, label, uses, perDay, status, status.startsWith("오늘 한도"), 0, 0); }
    }
    /** The models the app uses (in the order given) and any other it used today. */
    public static List<Row> rows(JSONObject state, String[] models, long now) { return rows(state, models, now, null); }
    /** As above, the others used today only Groq's (groq true) or only Google's (false), or both (null). */
    public static List<Row> rows(JSONObject state, String[] models, long now, Boolean groq) {
        List<String> shown = new ArrayList<>(List.of(models));
        // keys(), not keySet(): Android's org.json has only the iterator.
        if (state != null) for (java.util.Iterator<String> names = state.keys(); names.hasNext(); ) {
            String model = names.next(); if (!shown.contains(model) && uses(state, model, now) > 0 && (groq == null || groq == groq(model))) shown.add(model);
        }
        List<Row> rows = new ArrayList<>();
        String today = day(now);
        for (String model : shown) {
            JSONObject entry = state == null ? null : state.optJSONObject(model);
            if (entry != null && entry.optBoolean("missing")) { rows.add(new Row(model, label(model), 0, 0, "이 키로는 쓸 수 없어요")); continue; }
            boolean current = entry != null && today.equals(entry.optString("day")), full = current && full(model, entry);
            long rest = entry == null ? 0 : entry.optLong("rest");
            // Who stopped it and for what, in plain words: Groq's own limit of the day by its kind, with when it takes requests again.
            String kind = current ? entry.optString("dayKind") : "", who = groq(model) ? "Groq " : "Google ";
            String limit = kind.equals("tokens") ? "하루 토큰 한도" : kind.equals("audio") ? "하루 음성 시간 한도" : "하루 요청 한도";
            String status = full ? (kind.isEmpty() ? "오늘 한도를 다 썼어요" : who + limit + "가 찼어요 · 내일 다시")
                : rest > now ? (kind.isEmpty() ? "분당 한도 · " + waitText(rest - now) + " 쉬는 중" : who + limit + "가 찼어요 · " + waitText(rest - now) + " 뒤 다시") : "";
            Free known = free(model);
            long tokenLimit = entry == null ? 0 : entry.optLong("tokenLimit");
            if (tokenLimit <= 0 && known != null) tokenLimit = known.tokensPerDay();
            rows.add(new Row(model, label(model), uses(state, model, now), perDay(state, model), status, full || rest > now && !kind.isEmpty(), tokens(state, model, now), tokenLimit));
        }
        return rows;
    }
    /** "12초", "7분", "2시간 5분": a wait as people say it. */
    static String waitText(long ms) {
        long s = Math.max(1, (ms + 999) / 1000);
        if (s < 60) return s + "초";
        long m = (s + 59) / 60;
        if (m < 60) return m + "분";
        return m / 60 + "시간" + (m % 60 > 0 ? " " + m % 60 + "분" : "");
    }
    /** One line: "Gemini 3.5 Flash-Lite 12/500회 · Gemini 3.1 Flash-Lite 한도 다 씀", or that none was used today. */
    public static String summary(JSONObject state, String[] models, long now) {
        List<String> parts = new ArrayList<>();
        for (Row row : rows(state, models, now)) {
            if (row.blocked()) parts.add(row.label() + " 한도 다 씀");
            else if (row.uses() > 0) parts.add(row.label() + " " + row.uses() + (row.perDay() > 0 ? "/" + row.perDay() : "") + "회");
        }
        return parts.isEmpty() ? "오늘은 아직 쓰지 않았어요." : String.join(" · ", parts);
    }
    /** A model's name as people say it. */
    public static String label(String model) {
        if ("gemini-3.5-transcribe".equals(model)) return "Gemini 3.5 Transcribe";
        if (model != null && (model.contains("/") || model.startsWith("whisper-"))) return "Groq " + model.replaceFirst("^[^/]+/", "");
        String named = GeminiThink.label(model);
        if (!named.equals(model)) return named;
        boolean gemma = model.startsWith("gemma-");
        String rest = model.startsWith("gemini-") ? model.substring(7) : gemma ? model.substring(6) : model;
        StringBuilder out = new StringBuilder(gemma ? "Gemma" : "Gemini");
        for (String part : rest.split("-")) {
            if (part.isEmpty() || gemma && part.equals("it")) continue;
            // Sizes in billions ("31b", "a4b" active, "e4b" effective) as written: 31B, A4B.
            out.append(' ').append(part.matches("[ae]?\\d+b") ? part.toUpperCase(Locale.ROOT) : part.substring(0, 1).toUpperCase(Locale.ROOT) + part.substring(1));
        }
        return out.toString();
    }

    private static JSONObject entry(JSONObject state, String model) {
        JSONObject entry = state.optJSONObject(model);
        if (entry == null) { entry = new JSONObject(); put(state, model, entry); }
        return entry;
    }
    /** The model's entry for today: a new day starts the counts again (the limit Google named stays). */
    private static JSONObject today(JSONObject state, String model, long now) {
        JSONObject entry = entry(state, model); String today = day(now);
        if (!today.equals(entry.optString("day"))) { put(entry, "day", today); put(entry, "uses", 0); put(entry, "tokens", 0); put(entry, "full", false); entry.remove("dayKind"); }
        return entry;
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
