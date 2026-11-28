package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 남는 한도 (core/SpareQuota): what each free model can still take, the model a request goes to so one is not used up while
 * others of its kind sit idle (and the day it fails with the routing before it: 퍼레's talk always on Qwen, 잡일 always on GPT
 * OSS 120B), what background work may take of a day about to start again, the browser's hand-over order, and the log of what
 * spare work did. Run by tools/build.py.
 */
final class SpareQuotaRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }

    static final String QWEN = "qwen/qwen3.8-27b", OSS = "openai/gpt-oss-120b", OSS20 = "openai/gpt-oss-20b", SCOUT = "meta-llama/llama-4-scout-17b-16e-instruct";
    /** What a Groq key lists: four chat models, and search, speech and a guard that are never chosen for talk or chores. */
    static final List<String> LISTED = List.of(QWEN, OSS, OSS20, SCOUT, "groq/compound", "whisper-large-v3", "openai/gpt-oss-safeguard-20b");
    /** 2026-11-28 06:00 Pacific (23:00 in Seoul): early in the free day, with hours of it behind for the counts set up here. */
    static final long MORNING = Instant.parse("2026-11-28T14:00:00Z").toEpochMilli();
    static final long MINUTE = 60_000;

    public static void main(String[] args) throws Exception {
        tiers();
        room();
        talk();
        light();
        exhaustion();
        spare();
        order();
        effort();
        log();
        System.out.println("Spare quota regression checks passed: " + checked);
    }
    /** candidates with home first, as SpareQuotas.groq puts them. */
    static List<String> with(String home) { List<String> out = new ArrayList<>(List.of(home)); for (String m : LISTED) if (!out.contains(m)) out.add(m); return out; }
    static void use(JSONObject state, String model, long at, int tokens, int times) { for (int i = 0; i < times; i++) GeminiQuota.used(state, model, at - (times - i) * 2 * MINUTE, tokens); }

    // ── which models are chosen at all, and how good each is ──
    static void tiers() {
        same(SpareQuota.Tier.HIGH, SpareQuota.tier(OSS), "GPT OSS 120B");
        same(SpareQuota.Tier.HIGH, SpareQuota.tier(QWEN), "Qwen 3.8 27B");
        same(SpareQuota.Tier.HIGH, SpareQuota.tier("qwen/qwen3-32b"), "Qwen3 32B");
        same(SpareQuota.Tier.MID, SpareQuota.tier(OSS20), "GPT OSS 20B");
        same(SpareQuota.Tier.MID, SpareQuota.tier(SCOUT), "Llama 4 Scout");
        same(SpareQuota.Tier.LOW, SpareQuota.tier("meta-llama/llama-3.1-8b-instant"), "an 8B Llama");
        for (String never : List.of("groq/compound", "groq/compound-mini", "whisper-large-v3", "openai/gpt-oss-safeguard-20b", "meta-llama/llama-guard-4-12b", "canopylabs/orpheus-v1",
                "gemini-3.5-flash-lite", "gemma-4-31b-it", "moonshotai/kimi-k2-instruct", "llama-3.3-70b-versatile", "", "nvidia/nemotron-3-ultra-550b-a55b"))
            same(null, SpareQuota.tier(never), "never chosen for talk or chores: " + never);
    }

    // ── what a model can still take ──
    static void room() {
        JSONObject state = new JSONObject();
        SpareQuota.Room fresh = SpareQuota.remaining(state, OSS, MORNING);
        same(1000, fresh.dayRequests(), "a fresh day of requests"); same(200_000L, fresh.dayTokens(), "a fresh day of tokens");
        same(1000, fresh.perDay(), "its day's requests"); same(200_000L, fresh.tokensPerDay(), "its day's tokens");
        same(30, fresh.minuteRequests(), "a fresh minute"); same(8_000L, fresh.minuteTokens(), "a fresh minute of tokens");
        check(fresh.ready() && !fresh.out(), "fresh: ready");
        same(GeminiQuota.resetAt(MORNING), fresh.dayReset(), "the day fills again at midnight Pacific");
        same(Instant.parse("2026-11-29T08:00:00Z").toEpochMilli(), fresh.dayReset(), "midnight Pacific in UTC");
        GeminiQuota.used(state, OSS, MORNING - 10_000, 3_000);
        SpareQuota.Room used = SpareQuota.remaining(state, OSS, MORNING, 2_000);
        same(999, used.dayRequests(), "one request"); same(197_000L, used.dayTokens(), "its tokens");
        same(29, used.minuteRequests(), "this minute"); same(5_000L, used.minuteTokens(), "this minute's tokens");
        same(MORNING - 10_000 + MINUTE, used.minuteReset(), "the minute fills again a minute after it began");
        check(Math.abs(used.share() - Math.min(998 / 1000.0, 195_000 / 200_000.0)) < 1e-9, "the share left after the request: " + used.share());
        // Groq's tokens a day reached, with its wait: out until then, not until midnight.
        GeminiQuota.groqDay(state, QWEN, MORNING, "Rate limit reached for model `qwen/qwen3.8-27b` on tokens per day (TPD): Limit 200000, Used 199500, Requested 9000. Please try again in 23m14.4s.");
        SpareQuota.Room tpd = SpareQuota.remaining(state, QWEN, MORNING + MINUTE);
        check(tpd.out() && !tpd.ready(), "its day of tokens stops it"); same(0L, tpd.dayTokens(), "no tokens left"); same(0.0, tpd.share(), "no share left");
        check(tpd.dayReset() < MORNING + 25 * MINUTE && tpd.dayReset() > MORNING + 23 * MINUTE, "back after Groq's wait: " + (tpd.dayReset() - MORNING));
        check(SpareQuota.remaining(state, QWEN, MORNING + 25 * MINUTE).ready(), "and ready after it");
        // A minute's rest: not out for the day.
        GeminiQuota.rest(state, OSS20, MORNING, 40_000);
        SpareQuota.Room rest = SpareQuota.remaining(state, OSS20, MORNING + 1_000);
        check(!rest.out() && !rest.ready(), "a minute's rest is not the day"); same(0, rest.minuteRequests(), "no request this minute"); same(MORNING + 40_000, rest.minuteReset(), "back when Groq said");
        // Requests a day, named with no time: out until the day turns.
        GeminiQuota.groqDay(state, SCOUT, MORNING, "Rate limit reached for model `x` on requests per day (RPD): Limit 1000, Used 1000, Requested 1.");
        SpareQuota.Room rpd = SpareQuota.remaining(state, SCOUT, MORNING);
        check(rpd.out(), "its day of requests"); same(0, rpd.dayRequests(), "no requests left"); same(GeminiQuota.resetAt(MORNING), rpd.dayReset(), "until the day turns");
        // A model this key cannot use.
        JSONObject missing = new JSONObject();
        GeminiQuota.refused(missing, "gemma-4-31b-it", 404, "{\"error\":{\"code\":404,\"message\":\"models/gemma-4-31b-it is not found\",\"status\":\"NOT_FOUND\"}}", MORNING);
        SpareQuota.Room gone = SpareQuota.remaining(missing, "gemma-4-31b-it", MORNING);
        check(gone.out() && !gone.ready() && gone.share() == 0, "a model the key cannot use is out");
        // Gemini's own day of requests (no tokens a day known).
        JSONObject gemini = new JSONObject();
        GeminiQuota.refused(gemini, "gemini-3.5-flash-lite", 429, "Quota exceeded for metric generate_content_free_tier_requests, limit: 500, model: gemini-3.5-flash-lite PerDay", MORNING);
        SpareQuota.Room full = SpareQuota.remaining(gemini, "gemini-3.5-flash-lite", MORNING);
        check(full.out(), "Google's day"); same(0, full.dayRequests(), "none left"); same(-1L, full.dayTokens(), "tokens a day not known");
    }

    // ── 퍼레's talk: the brain the user chose stays, until it cannot take the talk ──
    static void talk() {
        JSONObject state = new JSONObject();
        use(state, QWEN, MORNING, 7_000, 20);
        same(QWEN, SpareQuota.pick(SpareQuota.Kind.TALK, 7_500, SpareQuota.Tier.HIGH, with(QWEN), List.of(), state, MORNING), "Qwen while it can take it, though GPT OSS has more room");
        use(state, QWEN, MORNING, 7_000, 8);
        check(!GeminiQuota.ready(QWEN, state, MORNING, 7_500), "Qwen's day of tokens is nearly gone (196k)");
        same(OSS, SpareQuota.pick(SpareQuota.Kind.TALK, 7_500, SpareQuota.Tier.HIGH, with(QWEN), List.of(), state, MORNING), "then the HIGH model with the most room, not a MID one");
        same(QWEN, SpareQuota.pick(SpareQuota.Kind.TALK, 2_000, SpareQuota.Tier.HIGH, with(QWEN), List.of(), state, MORNING), "a short talk still fits Qwen");
        use(state, OSS, MORNING, 7_000, 28);
        same(null, SpareQuota.pick(SpareQuota.Kind.TALK, 7_500, SpareQuota.Tier.HIGH, with(QWEN), List.of(), state, MORNING), "no HIGH room: NVIDIA stands in (null)");
        same(OSS20, SpareQuota.pick(SpareQuota.Kind.TALK, 7_500, SpareQuota.Tier.MID, with(QWEN), List.of(), state, MORNING), "no NVIDIA: a MID one, the first of the roomiest");
        use(state, OSS20, MORNING, 7_000, 3);
        same(SCOUT, SpareQuota.pick(SpareQuota.Kind.TALK, 7_500, SpareQuota.Tier.MID, with(QWEN), List.of(), state, MORNING), "the roomier MID one");
        // The key's list never read: just Qwen, as before.
        same(null, SpareQuota.pick(SpareQuota.Kind.TALK, 7_500, SpareQuota.Tier.MID, List.of(QWEN), List.of(), state, MORNING), "alone and full: none");
        same(QWEN, SpareQuota.pick(SpareQuota.Kind.TALK, 7_500, SpareQuota.Tier.MID, List.of(QWEN), List.of(), new JSONObject(), MORNING), "alone and fresh: Qwen");
        // Never search, speech or a guard, however fresh.
        JSONObject spent = new JSONObject();
        for (String m : List.of(QWEN, OSS, OSS20, SCOUT)) GeminiQuota.groqDay(spent, m, MORNING, "on tokens per day (TPD): Limit 200000, Used 200000. Please try again in 3h.");
        same(null, SpareQuota.pick(SpareQuota.Kind.TALK, 1_000, SpareQuota.Tier.LOW, with(QWEN), List.of(), spent, MORNING), "every chat model out: none, never compound or Whisper");
    }

    // ── 잡일: GPT OSS 120B, spread once it is well behind another of its tier ──
    static void light() {
        List<String> chores = with(OSS); List<String> keep = List.of(QWEN);
        JSONObject state = new JSONObject();
        same(OSS, SpareQuota.pick(SpareQuota.Kind.LIGHT, 2_500, SpareQuota.Tier.MID, chores, keep, state, MORNING), "a fresh day: its own");
        use(state, OSS, MORNING, 2_500, 12);
        same(OSS, SpareQuota.pick(SpareQuota.Kind.LIGHT, 2_500, SpareQuota.Tier.MID, chores, keep, state, MORNING), "85% left is within reach of Qwen's 100%: stays");
        use(state, OSS, MORNING, 2_500, 30);
        same(QWEN, SpareQuota.pick(SpareQuota.Kind.LIGHT, 2_500, SpareQuota.Tier.MID, chores, keep, state, MORNING), "half its day behind: the roomier of its tier");
        use(state, QWEN, MORNING, 7_000, 22);
        same(OSS, SpareQuota.pick(SpareQuota.Kind.LIGHT, 2_500, SpareQuota.Tier.MID, chores, keep, state, MORNING), "퍼레's Qwen at 23%: kept for 퍼레");
        use(state, OSS, MORNING, 2_500, 38);
        same(OSS20, SpareQuota.pick(SpareQuota.Kind.LIGHT, 2_500, SpareQuota.Tier.MID, chores, keep, state, MORNING), "no HIGH room: the MID one, before Gemini's free tier");
        same(null, SpareQuota.pick(SpareQuota.Kind.LIGHT, 2_500, SpareQuota.Tier.HIGH, chores, keep, state, MORNING), "HIGH only: none");
    }

    /**
     * The day 준 saw "한도 다 썼다" after 13 Groq requests: 40 talks of 7,500 tokens and 60 chores of 2,500, two minutes apart.
     * The routing before (talk always on Qwen, chores always on GPT OSS 120B) leaves talks unanswered once Qwen's 200,000 tokens
     * are gone while GPT OSS 20B and Llama 4 Scout were never asked; the spread answers every one, no model past its day, most
     * talks on a HIGH model. The same check is run on the old routing first, which must fail it.
     */
    interface Router { String route(SpareQuota.Kind kind, int tokens, JSONObject state, long now); }
    record Day(int failed, int talksHigh, Map<String, Long> tokens) {}
    static Day day(Router router) {
        JSONObject state = new JSONObject(); int failed = 0, high = 0;
        for (int i = 0; i < 100; i++) {
            long at = MORNING + i * 2 * MINUTE;
            boolean talk = i % 5 < 2; int tokens = talk ? 7_500 : 2_500;
            String model = router.route(talk ? SpareQuota.Kind.TALK : SpareQuota.Kind.LIGHT, tokens, state, at);
            if (model == null) { failed++; continue; }
            check(GeminiQuota.ready(model, state, at, tokens), "never sent past what the app knows: " + model);
            GeminiQuota.used(state, model, at, tokens);
            if (talk && SpareQuota.tier(model) == SpareQuota.Tier.HIGH) high++;
        }
        Map<String, Long> spent = new java.util.TreeMap<>();
        long end = MORNING + 200 * MINUTE;
        for (String m : LISTED) spent.put(m, GeminiQuota.tokens(state, m, end));
        return new Day(failed, high, spent);
    }
    static boolean good(Day d) {
        if (d.failed() > 0) return false;
        for (long t : d.tokens().values()) if (t > 200_000) return false;
        return d.talksHigh() >= 30;
    }
    static void exhaustion() {
        Router before = (kind, tokens, state, now) -> {
            String home = kind == SpareQuota.Kind.TALK ? QWEN : OSS;
            return GeminiQuota.ready(home, state, now, tokens) ? home : null;
        };
        Router spread = (kind, tokens, state, now) -> kind == SpareQuota.Kind.TALK
            ? SpareQuota.pick(SpareQuota.Kind.TALK, tokens, SpareQuota.Tier.MID, with(QWEN), List.of(), state, now)
            : SpareQuota.pick(SpareQuota.Kind.LIGHT, tokens, SpareQuota.Tier.MID, with(OSS), List.of(QWEN), state, now);
        Day old = day(before), now = day(spread);
        System.out.println("  one model each: " + old.failed() + " of 100 unanswered, " + old.tokens());
        System.out.println("  spread: " + now.failed() + " unanswered, talks on HIGH " + now.talksHigh() + "/40, " + now.tokens());
        check(!good(old), "the check must catch the routing before (it left " + old.failed() + " unanswered)");
        check(old.failed() >= 10, "talks went unanswered with room elsewhere: " + old.failed());
        check(good(now), "the spread answers every one, none past its day, most talks on HIGH: " + now);
        check(now.tokens().get(OSS20) + now.tokens().get(SCOUT) > 0, "the idle models took part");
        for (String never : List.of("groq/compound", "whisper-large-v3", "openai/gpt-oss-safeguard-20b")) same(0L, now.tokens().get(never), "never " + never);
    }

    // ── what is left of a day about to start again ──
    static void spare() {
        long reset = GeminiQuota.resetAt(MORNING), in = reset - 2 * 3_600_000L;
        JSONObject state = new JSONObject();
        check(!SpareQuota.window(MORNING), "early in the day: not the window");
        same(0L, SpareQuota.spare(state, OSS, MORNING), "nothing to spare early in the day");
        same(0L, SpareQuota.budget(state, with(OSS), MORNING, 0), "no budget early in the day");
        check(SpareQuota.window(in), "two hours before midnight Pacific");
        check(SpareQuota.window(reset - SpareQuota.WINDOW), "the window opens three hours before");
        check(!SpareQuota.window(reset - 5 * MINUTE), "and closes ten minutes before");
        same(30_000L, SpareQuota.spare(state, OSS, in), "a fresh day: at most 15% of it");
        same(60_000L, SpareQuota.budget(state, with(OSS), in, 0), "four models, at most 60k a day in all");
        same(10_000L, SpareQuota.budget(state, with(OSS), in, 50_000), "less what was spent");
        same(0L, SpareQuota.budget(state, with(OSS), in, 70_000), "never below nothing");
        same(0L, SpareQuota.spare(state, "groq/compound", in), "never search");
        use(state, QWEN, in, 7_000, 21);
        same(0L, SpareQuota.spare(state, QWEN, in), "a model past 70% keeps its last 30% for the user");
        use(state, OSS, in, 7_000, 17);
        same(21_000L, SpareQuota.spare(state, OSS, in), "what is left beyond 30%");
        JSONObject busy = new JSONObject();
        for (int i = 0; i < 800; i++) GeminiQuota.used(busy, OSS20, in - (800 - i) * 5_000L, 10);
        same(0L, SpareQuota.spare(busy, OSS20, in), "requests too: 200 of 1,000 left is below 30%");
        same(OSS, SpareQuota.pick(SpareQuota.Kind.SPARE, 5_000, SpareQuota.Tier.HIGH, with(OSS), List.of(QWEN), state, in), "HIGH only: GPT OSS 120B's 21k (Qwen keeps its 30%)");
        same(OSS, SpareQuota.pick(SpareQuota.Kind.SPARE, 5_000, SpareQuota.Tier.MID, with(OSS20), List.of(QWEN), state, in), "a HIGH one before the fresher MID ones");
        JSONObject two = new JSONObject();
        same(OSS, SpareQuota.pick(SpareQuota.Kind.SPARE, 5_000, SpareQuota.Tier.HIGH, with(OSS), List.of(QWEN), two, in), "both fresh: the first");
        use(two, OSS, in, 7_000, 17);
        same(QWEN, SpareQuota.pick(SpareQuota.Kind.SPARE, 5_000, SpareQuota.Tier.HIGH, with(OSS), List.of(QWEN), two, in), "the one with more to spare (30k against 21k)");
        same(null, SpareQuota.pick(SpareQuota.Kind.SPARE, 25_000, SpareQuota.Tier.HIGH, with(OSS), List.of(QWEN), state, in), "a job bigger than any spare: none");
        same(null, SpareQuota.pick(SpareQuota.Kind.SPARE, 1_000, SpareQuota.Tier.HIGH, with(OSS), List.of(QWEN), new JSONObject(), MORNING), "outside the window: none");
        same(reset - SpareQuota.WINDOW, SpareQuota.windowStart(MORNING), "the window to wait for");
        same(in, SpareQuota.windowStart(in), "in it: now");
        same(GeminiQuota.resetAt(reset + 1) - SpareQuota.WINDOW, SpareQuota.windowStart(reset - 5 * MINUTE), "its last minutes: tomorrow's");
    }

    // ── the browser's next AI: one that reported itself nearly spent goes after the others; a paid key keeps its place ──
    static void order() {
        List<String> chain = List.of("gpt", "grok", "nim", "claude"); Set<String> paid = Set.of("claude");
        same(chain, SpareQuota.order(chain, Map.of(), paid), "nothing reported: as it was");
        same(List.of("grok", "nim", "gpt", "claude"), SpareQuota.order(chain, Map.of("gpt", 0.05), paid), "GPT's 5시간 nearly spent: after the others");
        same(List.of("grok", "nim", "gpt", "claude"), SpareQuota.order(chain, Map.of("gpt", 0.3, "grok", 0.9), paid), "some left after plenty");
        same(chain, SpareQuota.order(chain, Map.of("gpt", 0.6, "grok", 0.95), paid), "both plenty: the order kept");
        same(List.of("gpt", "nim", "grok", "claude"), SpareQuota.order(chain, Map.of("grok", 0.02), Set.of("gpt", "claude")), "ChatGPT on a paid key keeps its place");
        same(List.of("grok", "gpt", "nim", "claude"), SpareQuota.order(chain, Map.of("gpt", 0.2, "nim", 0.01), paid), "each group in the chain's order");
    }

    // ── a model chosen instead takes the reasoning word it knows ──
    static void effort() {
        same("low", SpareQuota.groqEffort(OSS, "none"), "GPT OSS has no none");
        same("low", SpareQuota.groqEffort(OSS, "low"), "kept");
        same("high", SpareQuota.groqEffort(OSS20, "high"), "kept");
        same("medium", SpareQuota.groqEffort(OSS, "default"), "Groq's default");
        same("none", SpareQuota.groqEffort(QWEN, "none"), "Qwen's own");
        same("none", SpareQuota.groqEffort(QWEN, "low"), "Qwen has no low");
        same("default", SpareQuota.groqEffort(QWEN, "medium"), "Qwen thinks");
        same(null, SpareQuota.groqEffort(SCOUT, "none"), "Llama takes none at all");
        same(null, SpareQuota.groqEffort(OSS, null), "none asked");
    }

    // ── 남는 한도로 한 일 ──
    static void log() {
        ZoneId seoul = ZoneId.of("Asia/Seoul");
        long reset = GeminiQuota.resetAt(MORNING), at = reset - 2 * 3_600_000L; // 15:00 in Seoul
        JSONArray log = new JSONArray();
        log = SpareQuota.add(log, new SpareQuota.Done(at - 86_400_000L, "titles", "대화 제목 2개", 4_000, OSS), at);
        log = SpareQuota.add(log, new SpareQuota.Done(at, "titles", "대화 제목 3개", 6_000, OSS), at);
        log = SpareQuota.add(log, new SpareQuota.Done(at + MINUTE, "memory", "기억 정리할 것 2가지", 5_500, OSS20), at);
        log = SpareQuota.add(log, new SpareQuota.Done(at + 2 * MINUTE, "titles", "대화 제목 4개", 0, OSS), at);
        same(11_500L, SpareQuota.spent(log, at + 2 * MINUTE), "this free day's tokens, not yesterday's");
        same("기억 정리할 것 2가지 · 대화 제목 4개", SpareQuota.line(log, at + 3 * MINUTE, seoul), "today in a line, each job by its latest words");
        same(11_500L, SpareQuota.tokens(log, at + 3 * MINUTE, seoul), "today's tokens on the phone's day");
        check(SpareQuota.doneToday(log, "memory", at + 2 * MINUTE, seoul), "done today"); check(!SpareQuota.doneToday(log, "profile", at, seoul), "not done");
        same("", SpareQuota.line(new JSONArray(), at, seoul), "nothing yet");
        JSONArray old = SpareQuota.add(log, null, at + SpareQuota.KEEP_MS + 3 * MINUTE);
        same(0, old.length(), "two weeks kept");
        JSONArray many = new JSONArray();
        for (int i = 0; i < 200; i++) many = SpareQuota.add(many, new SpareQuota.Done(at + i, "titles", "x", 1, OSS), at + i);
        same(SpareQuota.KEEP, many.length(), "at most KEEP lines");
        // What the models together can still do.
        JSONObject state = new JSONObject();
        GeminiQuota.used(state, OSS, MORNING, 10_000);
        List<SpareQuota.Room> rooms = List.of(SpareQuota.remaining(state, OSS, MORNING), SpareQuota.remaining(state, OSS20, MORNING));
        same("토큰 390k / 400k 남음", SpareQuota.summary(rooms), "two models together, by the tokens that fill first");
        GeminiQuota.groqDay(state, OSS20, MORNING, "on tokens per day (TPD): Limit 200000. Please try again in 1h.");
        same("토큰 190k / 400k 남음 · 1개 한도 참", SpareQuota.summary(List.of(SpareQuota.remaining(state, OSS, MORNING), SpareQuota.remaining(state, OSS20, MORNING))), "one out");
        same("요청 14,400 / 14,400회 남음", SpareQuota.summary(List.of(SpareQuota.remaining(new JSONObject(), "gemma-4-31b-it", MORNING))), "Gemma: requests only");
        same("", SpareQuota.summary(List.of(SpareQuota.remaining(new JSONObject(), "moonshotai/kimi-k2-instruct", MORNING))), "no day limit known: nothing to say");
        // The line fits one row on a phone (about 30 characters), so 설정 → 사용량 stays a list of one-line rows.
        check(SpareQuota.summary(List.of(SpareQuota.remaining(state, OSS, MORNING), SpareQuota.remaining(state, OSS20, MORNING))).length() <= 30, "one short line");
    }
}
