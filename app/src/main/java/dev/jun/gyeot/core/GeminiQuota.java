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
    public record Free(int perMinute, int perDay, int tokensPerMinute) {
        Free(int perMinute, int perDay) { this(perMinute, perDay, 0); }
    }
    /** As Google's rate-limit page showed them for a free key; Google may change them, and what it names in a refusal wins. */
    static final Map<String, Free> KNOWN = Map.of(
        "gemini-3.5-flash-lite", new Free(15, 500), "gemini-3.1-flash-lite", new Free(15, 500),
        "gemini-3.5-transcribe", new Free(3, 25), "gemini-flash-latest", new Free(5, 20),
        "gemini-2.5-flash", new Free(5, 20), "gemini-2.5-flash-lite", new Free(10, 20));
    /** Every Gemma 4 model (31B, 26B and any size the key lists): many requests a day, few tokens a minute. */
    static final Free GEMMA_4 = new Free(30, 14_400, 16_000);
    /** Gemini's embedding models (뜻으로 찾기): many requests a minute, a thousand a day each. */
    static final Free EMBEDDING = new Free(100, 1_000, 30_000);
    /** A model's free tier as known, or null. */
    public static Free free(String model) {
        Free known = KNOWN.get(model);
        if (known != null || model == null) return known;
        return model.startsWith("gemma-4-") ? GEMMA_4 : model.startsWith("gemini-embedding-") ? EMBEDDING : null;
    }
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
        if (current && entry.optBoolean("full")) return false;
        if (entry != null && entry.optLong("rest") > now) return false;
        int perDay = perDay(state, model); if (perDay > 0 && uses(state, model, now) >= perDay) return false;
        Free known = free(model);
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

    /** One model on 설정 → 사용량: its name, today's requests, the free requests a day (0 not known) and how it stands now. */
    public record Row(String model, String label, int uses, int perDay, String status) {}
    /** The models the app uses (in the order given) and any other it used today. */
    public static List<Row> rows(JSONObject state, String[] models, long now) {
        List<String> shown = new ArrayList<>(List.of(models));
        // keys(), not keySet(): Android's org.json has only the iterator.
        if (state != null) for (java.util.Iterator<String> names = state.keys(); names.hasNext(); ) {
            String model = names.next(); if (!shown.contains(model) && uses(state, model, now) > 0) shown.add(model);
        }
        List<Row> rows = new ArrayList<>();
        String today = day(now);
        for (String model : shown) {
            JSONObject entry = state == null ? null : state.optJSONObject(model);
            if (entry != null && entry.optBoolean("missing")) { rows.add(new Row(model, label(model), 0, 0, "이 키로는 쓸 수 없어요")); continue; }
            boolean full = entry != null && today.equals(entry.optString("day")) && entry.optBoolean("full");
            long rest = entry == null ? 0 : entry.optLong("rest");
            String status = full ? "오늘 한도를 다 썼어요" : rest > now ? "분당 한도 · " + Math.max(1, (rest - now + 999) / 1000) + "초 쉬는 중" : "";
            rows.add(new Row(model, label(model), uses(state, model, now), perDay(state, model), status));
        }
        return rows;
    }
    /** One line: "Gemini 3.5 Flash-Lite 12/500회 · Gemini 3.1 Flash-Lite 한도 다 씀", or that none was used today. */
    public static String summary(JSONObject state, String[] models, long now) {
        List<String> parts = new ArrayList<>();
        for (Row row : rows(state, models, now)) {
            if (row.status().startsWith("오늘 한도")) parts.add(row.label() + " 한도 다 씀");
            else if (row.uses() > 0) parts.add(row.label() + " " + row.uses() + (row.perDay() > 0 ? "/" + row.perDay() : "") + "회");
        }
        return parts.isEmpty() ? "오늘은 아직 쓰지 않았어요." : String.join(" · ", parts);
    }
    /** A model's name as people say it. */
    public static String label(String model) {
        if ("gemini-3.5-transcribe".equals(model)) return "Gemini 3.5 Transcribe";
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
        if (!today.equals(entry.optString("day"))) { put(entry, "day", today); put(entry, "uses", 0); put(entry, "full", false); }
        return entry;
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
