package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 남는 한도 (준: "여러 ai 한도 중 안쓰고 남아도는 것들 적절하게 잘 활용하자"): what each free or subscribed model the app can call
 * can still take now, which one a request goes to so one model is not used up while others of its kind sit idle, and what
 * background work may spend of a day that would otherwise go unused. Before, 퍼레's talk always went to Groq's Qwen and 잡일 to
 * GPT OSS 120B: thirteen long talks filled Qwen's 200,000 tokens a day ("한도 다 썼다") while GPT OSS 20B and Llama 4 Scout,
 * each with a day of their own, were not asked at all.
 *
 * The limits are the app's own tables (GeminiQuota.free: Groq's and Gemini's free tiers as their pages showed them, and the
 * limit a refusal named) and the counts GeminiQuota keeps; a subscription or NVIDIA is known only by what it reported itself
 * (core/Limits). A model the user chose stays chosen: only the automatic choices and the hand-over after a limit move. Paid
 * keys and Gemini's free tier are never chosen here. Pure Java; the caller keeps the state.
 */
public final class SpareQuota {
    private SpareQuota() {}
    /** What a request is: 퍼레's talk (TALK, its brain the user chose), 잡일 (LIGHT, automatic), or work done with what is left (SPARE). */
    public enum Kind { TALK, LIGHT, SPARE }
    /** How good a model is at the app's Korean talk and chores, best first; a request falls to a lower tier only when allowed. */
    public enum Tier { HIGH, MID, LOW }

    private static final Pattern SIZE = Pattern.compile("(\\d+(?:\\.\\d+)?)b(?![a-z0-9])");
    private static final Pattern NOT_CHAT = Pattern.compile(".*(guard|tts|orpheus|playai|embed|vision|-vl|coder|whisper|compound).*");
    /**
     * A Groq chat model's tier, or null for one this does not choose: speech, guards, search (compound), and any model whose free
     * tier the app's table does not know. GPT OSS 120B and a Qwen of 20B or more are HIGH; GPT OSS 20B, Llama 4 and Llama 3.3
     * 70B are MID; smaller ones LOW.
     */
    public static Tier tier(String model) {
        if (model == null || !GeminiQuota.groq(model) || GeminiQuota.free(model) == null) return null;
        String m = model.toLowerCase(Locale.ROOT);
        if (NOT_CHAT.matcher(m).matches()) return null;
        if (m.contains("gpt-oss-120b")) return Tier.HIGH;
        if (m.contains("gpt-oss-20b") || m.contains("llama-4") || m.contains("llama-3.3-70b")) return Tier.MID;
        if (m.startsWith("qwen/")) return size(m) >= 20 ? Tier.HIGH : Tier.MID;
        return size(m) >= 60 ? Tier.MID : Tier.LOW;
    }
    /** The largest "…b" size in an id ("qwen3.8-27b" 27, "llama-4-scout-17b-16e" 17), 0 when it names none. */
    static double size(String id) {
        Matcher m = SIZE.matcher(id); double most = 0;
        while (m.find()) try { most = Math.max(most, Double.parseDouble(m.group(1))); } catch (NumberFormatException ignored) {}
        return most;
    }

    /**
     * How much a model can still take, from the app's counts and the limits it knows: requests and tokens left in the current
     * minute and in the day (-1: no such limit known), the day's limits themselves (0: not known), when the minute and the day
     * fill again (0: now), whether it can take a request of the size asked now (ready), and the share of its day left after that
     * request (0 to 1: 1 with no day limit known, 0 while its day's limit stops it).
     */
    public record Room(String model, int minuteRequests, long minuteTokens, int dayRequests, long dayTokens, int perDay, long tokensPerDay,
                       long minuteReset, long dayReset, boolean ready, double share) {
        /** Its day's limit stopped it (or the key cannot use it): it is out until dayReset. */
        public boolean out() { return dayRequests == 0 || dayTokens == 0; }
    }
    public static Room remaining(JSONObject state, String model, long now) { return remaining(state, model, now, 0); }
    /** As above, for a request of about tokens (the share left is after it, ready is whether it fits now). */
    public static Room remaining(JSONObject state, String model, long now, int tokens) {
        GeminiQuota.Free known = GeminiQuota.free(model);
        JSONObject entry = state == null ? null : state.optJSONObject(model);
        boolean missing = entry != null && entry.optBoolean("missing");
        boolean today = entry != null && GeminiQuota.day(now).equals(entry.optString("day"));
        long rest = entry == null ? 0 : entry.optLong("rest");
        boolean dayRest = rest > now && today && !entry.optString("dayKind").isEmpty(), stopped = missing || today && GeminiQuota.full(model, entry) || dayRest;
        int perDay = GeminiQuota.perDay(state, model), uses = GeminiQuota.uses(state, model, now);
        long tokenDay = entry == null ? 0 : entry.optLong("tokenLimit");
        if (tokenDay <= 0 && known != null) tokenDay = known.tokensPerDay();
        long used = GeminiQuota.tokens(state, model, now);
        int dayRequests = perDay > 0 ? Math.max(0, perDay - uses) : -1;
        long dayTokens = tokenDay > 0 ? Math.max(0, tokenDay - used) : -1;
        // Stopped by the day: the limit Groq named (requests, tokens, audio), else Google's day of requests; a key that cannot use it, both.
        if (stopped) {
            String limit = entry.optString("dayKind");
            if (missing || limit.equals("requests") || limit.equals("audio") || tokenDay <= 0) dayRequests = 0;
            if (missing || !limit.equals("requests") && !limit.equals("audio") && tokenDay > 0) dayTokens = 0;
        }
        boolean minute = entry != null && now - entry.optLong("minute") < GeminiQuota.MINUTE;
        int perMinute = known == null ? 0 : known.perMinute(), tokensMinute = known == null ? 0 : known.tokensPerMinute();
        int minuteRequests = perMinute > 0 ? Math.max(0, perMinute - (minute ? entry.optInt("minuteUses") : 0)) : -1;
        long minuteTokens = tokensMinute > 0 ? Math.max(0, tokensMinute - (minute ? entry.optInt("minuteTokens") : 0)) : -1;
        boolean minuteRest = rest > now && !dayRest;
        if (minuteRest) minuteRequests = 0;
        long minuteReset = minuteRest ? rest : minute ? entry.optLong("minute") + GeminiQuota.MINUTE : 0;
        long dayReset = dayRest ? rest : GeminiQuota.resetAt(now);
        double share = 1;
        if (stopped) share = 0;
        else {
            if (perDay > 0) share = Math.min(share, Math.max(0, perDay - uses - 1) / (double) perDay);
            if (tokenDay > 0) share = Math.min(share, Math.max(0, tokenDay - used - Math.max(0, tokens)) / (double) tokenDay);
        }
        return new Room(model, minuteRequests, minuteTokens, dayRequests, dayTokens, Math.max(0, perDay), Math.max(0, tokenDay), minuteReset, dayReset,
            GeminiQuota.ready(model, state, now, tokens), share);
    }
    /**
     * A provider known only by what it reported itself (a subscription's windows, NVIDIA's rate headers: core/Limits): its
     * tightest current window, as the share left and when it fills again. Null when it reported nothing current.
     */
    public static Limits.Window tightest(Limits limits, long now) {
        if (limits == null || limits.empty()) return null;
        Limits.Window least = null;
        for (Limits.Window w : limits.current(now)) if (least == null || w.left() < least.left()) least = w;
        return least;
    }

    // ── which model a request goes to ──
    /** A kind leaves a model another kind relies on (퍼레's Qwen, for 잡일 and spare work) once this share of its day is all that is left. */
    public static final double RESERVE = 0.3;
    /** 잡일 stays on its own model while its day is no more than this below the roomiest other of its tier (one model, one voice, its cache). */
    public static final double HOME_MARGIN = 0.2;
    /**
     * The model a request of about tokens goes to now, or null when none of candidates can take it. candidates are the key's
     * models in the caller's order, the kind's own (its home) first; reserved are other kinds' homes. TALK keeps its home while
     * it can take the request (the brain the user chose stays chosen); LIGHT keeps its home while its day is within HOME_MARGIN
     * of the roomiest other of its tier; SPARE takes only what spare() leaves for background work. Otherwise the model of the best
     * tier (down to floor) with the most of its day left after the request goes, a reserved one only while RESERVE of its day
     * would be left; on a tie the earlier candidate. Only models tier() knows are chosen.
     */
    public static String pick(Kind kind, int tokens, Tier floor, List<String> candidates, Collection<String> reserved, JSONObject state, long now) {
        if (candidates == null || candidates.isEmpty()) return null;
        String home = candidates.get(0);
        Tier lowest = floor == null ? Tier.LOW : floor;
        if (kind == Kind.TALK && tier(home) != null && GeminiQuota.ready(home, state, now, tokens)) return home;
        for (Tier t : Tier.values()) {
            if (t.ordinal() > lowest.ordinal()) break;
            String best = null; double most = -1, mine = -1;
            for (String model : candidates) {
                if (tier(model) != t || !GeminiQuota.ready(model, state, now, tokens)) continue;
                Room room = remaining(state, model, now, tokens);
                if (!model.equals(home) && reserved != null && reserved.contains(model) && room.share() < RESERVE) continue;
                double score;
                if (kind == Kind.SPARE) { long spare = spare(state, model, now); if (spare < tokens) continue; score = spare; }
                else score = room.share();
                if (model.equals(home)) mine = score;
                if (score > most) { most = score; best = model; }
            }
            if (best == null) continue;
            if (kind == Kind.LIGHT && mine >= 0 && mine >= most - HOME_MARGIN) return home;
            return best;
        }
        return null;
    }

    // ── work done with what would otherwise go unused ──
    /** Spare work runs only in the last WINDOW before the free day's counts start again (midnight Pacific, GeminiQuota.resetAt), and stops CLOSE before it. */
    public static final long WINDOW = 3 * 3_600_000L, CLOSE = 10 * 60_000L;
    /** Of a model's day, spare work leaves FLOOR for the user and takes at most MOST; DAY_BUDGET tokens a day in all. */
    public static final double FLOOR = 0.3, MOST = 0.15;
    public static final long DAY_BUDGET = 60_000;
    /** Whether now is in the window: what is left of the day is about to start again from nothing. */
    public static boolean window(long now) { long left = GeminiQuota.resetAt(now) - now; return left <= WINDOW && left > CLOSE; }
    /** When the window opens next: now while it is open, else today's (or, past it, tomorrow's) start. */
    public static long windowStart(long now) {
        if (window(now)) return now;
        long reset = GeminiQuota.resetAt(now), start = reset - WINDOW;
        return start > now ? start : GeminiQuota.resetAt(reset + 1) - WINDOW;
    }
    /**
     * The tokens spare work may spend on the model now: in the window, a chat model whose day of tokens is known and that can
     * take a request, what is left beyond FLOOR of its day (requests too), at most MOST of its day. 0 otherwise.
     */
    public static long spare(JSONObject state, String model, long now) {
        if (!window(now) || tier(model) == null) return 0;
        Room room = remaining(state, model, now);
        if (!room.ready() || room.dayTokens() < 0 || room.tokensPerDay() <= 0) return 0;
        if (room.perDay() > 0 && room.dayRequests() < room.perDay() * FLOOR) return 0;
        long keep = Math.round(room.tokensPerDay() * FLOOR), most = Math.round(room.tokensPerDay() * MOST);
        return Math.max(0, Math.min(room.dayTokens() - keep, most));
    }
    /** What spare work may still spend today in all: DAY_BUDGET less what it spent this day (spent), and no more than the models' spare() together. */
    public static long budget(JSONObject state, List<String> models, long now, long spent) {
        long free = 0;
        for (String model : models == null ? List.<String>of() : models) free += spare(state, model, now);
        return Math.max(0, Math.min(DAY_BUDGET - Math.max(0, spent), free));
    }

    // ── the hand-over after a limit (the browser's next AI) ──
    /** A report under LOW left is nearly spent; one of PLENTY or more (or none) counts as room. */
    public static final double LOW = 0.1, PLENTY = 0.5;
    /**
     * The hand-over order (BrowserAgent.FALLBACK) with what each one reported left (shares: its tightest window's share, absent
     * when it reported nothing): among those not paid, one with plenty or no report keeps its place first, one with some left
     * next, one nearly spent last; each group in the chain's own order. A paid one keeps its place, so nothing here makes a paid
     * key answer sooner.
     */
    public static List<String> order(List<String> chain, Map<String, Double> shares, Collection<String> paid) {
        List<String> free = new ArrayList<>();
        for (String c : chain) if (paid == null || !paid.contains(c)) free.add(c);
        // List.sort is stable: each group keeps the chain's order.
        free.sort(Comparator.comparingInt(c -> group(shares == null ? null : shares.get(c))));
        List<String> out = new ArrayList<>(); int next = 0;
        for (String c : chain) out.add(paid != null && paid.contains(c) ? c : free.get(next++));
        return out;
    }
    private static int group(Double share) { return share == null || share >= PLENTY ? 0 : share >= LOW ? 1 : 2; }

    /**
     * The reasoning word a Groq model takes for what was asked (a model chosen instead of the caller's may take other words):
     * GPT OSS low · medium · high ("none" asks for the least: low; "default" medium), Qwen none · default (low is none, medium
     * and high default), the others none (null).
     */
    public static String groqEffort(String model, String asked) {
        if (model == null || asked == null || asked.isBlank()) return null;
        String m = model.toLowerCase(Locale.ROOT), a = asked.strip().toLowerCase(Locale.ROOT);
        if (m.startsWith("openai/gpt-oss")) return switch (a) { case "none", "minimal" -> "low"; case "default" -> "medium"; case "low", "medium", "high" -> a; default -> null; };
        if (m.contains("qwen")) return switch (a) { case "none", "low", "minimal" -> "none"; case "default", "medium", "high" -> "default"; default -> null; };
        return null;
    }

    // ── 남는 한도로 한 일: what spare work did ──
    /** One thing spare work did: when, the job's key, it in words ("대화 제목 3개"), the tokens it took, and the model. */
    public record Done(long at, String job, String words, long tokens, String model) {
        public JSONObject json() { return Jsons.obj("at", at, "job", job, "words", words, "tokens", tokens, "model", model == null ? "" : model); }
        public static Done of(JSONObject o) { return o == null ? null : new Done(o.optLong("at"), o.optString("job"), o.optString("words"), o.optLong("tokens"), o.optString("model")); }
    }
    /** The log keeps two weeks, at most this many lines. */
    public static final int KEEP = 120;
    public static final long KEEP_MS = 14L * 24 * 3_600_000L;
    /** The log with done added at the end, older than KEEP_MS gone, at most KEEP lines. */
    public static JSONArray add(JSONArray log, Done done, long now) {
        List<JSONObject> kept = new ArrayList<>();
        for (int i = 0; log != null && i < log.length(); i++) { JSONObject o = log.optJSONObject(i); if (o != null && now - o.optLong("at") < KEEP_MS) kept.add(o); }
        if (done != null) kept.add(done.json());
        JSONArray out = new JSONArray();
        for (JSONObject o : kept.subList(Math.max(0, kept.size() - KEEP), kept.size())) out.put(o);
        return out;
    }
    /** The tokens spare work took this free day (GeminiQuota.day: the counts' own day). */
    public static long spent(JSONArray log, long now) {
        String day = GeminiQuota.day(now); long sum = 0;
        for (int i = 0; log != null && i < log.length(); i++) { JSONObject o = log.optJSONObject(i); if (o != null && day.equals(GeminiQuota.day(o.optLong("at")))) sum += Math.max(0, o.optLong("tokens")); }
        return sum;
    }
    /** Whether job was done on the phone's own day of now. */
    public static boolean doneToday(JSONArray log, String job, long now, ZoneId zone) {
        for (Done d : today(log, now, zone)) if (d.job().equals(job)) return true;
        return false;
    }
    /** What spare work did on the phone's own day of now, oldest first. */
    public static List<Done> today(JSONArray log, long now, ZoneId zone) {
        java.time.LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        List<Done> out = new ArrayList<>();
        for (int i = 0; log != null && i < log.length(); i++) {
            Done d = Done.of(log.optJSONObject(i));
            if (d != null && Instant.ofEpochMilli(d.at()).atZone(zone).toLocalDate().equals(day)) out.add(d);
        }
        return out;
    }
    /** "대화 제목 3개 · 기억 정리할 것 2가지": today's work in a line, each job by its latest words; "" when it did none. */
    public static String line(JSONArray log, long now, ZoneId zone) {
        Map<String, String> words = new LinkedHashMap<>();
        for (Done d : today(log, now, zone)) { words.remove(d.job()); words.put(d.job(), d.words()); }
        return String.join(" · ", words.values());
    }
    /** The tokens today's spare work took, on the phone's own day of now. */
    public static long tokens(JSONArray log, long now, ZoneId zone) {
        long sum = 0;
        for (Done d : today(log, now, zone)) sum += Math.max(0, d.tokens());
        return sum;
    }

    /**
     * "토큰 612k / 800k 남음 · 1개 한도 참": what the models can still do today together, in one short line: the tokens a day when
     * they are known (Groq's: they fill before the requests do), else the requests a day ("요청 14,900 / 15,500회 남음"), and how
     * many are stopped by their day's limit. "" when no day limit is known.
     */
    public static String summary(List<Room> rooms) {
        if (rooms == null || rooms.isEmpty()) return "";
        long tokensLeft = 0, tokensDay = 0, requestsLeft = 0, requestsDay = 0; int out = 0;
        for (Room r : rooms) {
            if (r.tokensPerDay() > 0) { tokensDay += r.tokensPerDay(); tokensLeft += Math.max(0, r.dayTokens()); }
            if (r.perDay() > 0) { requestsDay += r.perDay(); requestsLeft += Math.max(0, r.dayRequests()); }
            if (r.out()) out++;
        }
        String line = tokensDay > 0 ? "토큰 " + Usage.tokens(tokensLeft) + " / " + Usage.tokens(tokensDay) + " 남음"
            : requestsDay > 0 ? "요청 " + String.format(Locale.ROOT, "%,d", requestsLeft) + " / " + String.format(Locale.ROOT, "%,d", requestsDay) + "회 남음" : "";
        return out == 0 ? line : (line.isEmpty() ? "" : line + " · ") + out + "개 한도 참";
    }
}
