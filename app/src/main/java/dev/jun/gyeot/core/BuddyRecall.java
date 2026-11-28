package dev.jun.gyeot.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레's memory found by meaning (기억 2.0): what the user told is kept as its kind (a fact, a liking, a plan, a promise, and
 * what the user set right as a lesson), and what comes to mind for a question is ranked by meaning (NVIDIA's free embedding
 * model, its vectors kept on the phone) together with words (topics in common, letter pairs) and what the question asks for
 * ("몇 시에 출발할까" asks for a plan: the wedding, not the sneakers). Without a vector (NVIDIA not connected, slow, failed),
 * the words and the kind decide alone. Pure Java: the kinds, the ranking, the embedding request and answer, the vector file.
 */
public final class BuddyRecall {
    private BuddyRecall() {}
    /** The kinds of what the user told: a fact about them, what they like or not, a plan with its time, 퍼레's promise, a lesson. */
    public static final String FACT = "fact", LIKE = "like", PLAN = "plan", PROMISE = "promise", LESSON = "lesson";
    /** The kinds a talk teaches and a correction sets right (the night's notes about the user too). */
    public static final Set<String> TOLD = Set.of(FACT, LIKE, PLAN, PROMISE, "profile");

    // ── the kind of a memory ──
    private static final Pattern LIKES = Pattern.compile("(좋아|싫어|즐겨|최애|취향|선호|입맛|못\\s*먹|안\\s*먹|싫대|좋대|팬이|빠졌|관심\\s*있|애정|맛있어\\s*한|별로래|질색|갖고\\s*싶|사고\\s*싶|먹고\\s*싶|가\\s*보고\\s*싶)");
    /** Words that look ahead: a plan, a booking, going somewhere. */
    private static final Pattern AHEAD = Pattern.compile("(오늘|내일|모레|글피|다음\\s*주|이번\\s*주|다음\\s*달|이번\\s*달|주말|예정|약속|일정|예약|가기로|하기로|만나기로|보기로|간대|간다|갈\\s*거|할\\s*거|올\\s*거|볼\\s*거|출발|떠난|떠날|만난대|온대|한대|있대)");
    /** Something that happens at a time: an event. */
    private static final Pattern EVENT = Pattern.compile("(결혼식|돌잔치|장례식|모임|회의|미팅|면접|시험|여행|출장|병원|진료|수업|공연|콘서트|경기|파티|약속|데이트|발표|마감|행사|소개팅|예약|비행기|기차|이사)");
    private static final Pattern WHEN = Pattern.compile("(\\d{1,2}\\s*월\\s*\\d{1,2}\\s*일|\\d{1,2}\\s*시|[월화수목금토일]요일|오늘|내일|모레|글피|다음\\s*주|이번\\s*주|주말|아침|저녁|밤|오전|오후)");
    /**
     * The kind of what the user told, from its words: a plan (an event with a time, or going to do something soon), a liking
     * (좋아·싫어·즐겨 …), else a fact. A birthday's date is a fact, not a plan.
     */
    public static String sort(String text) {
        String t = text == null ? "" : text;
        boolean when = WHEN.matcher(t).find(), event = EVENT.matcher(t).find(), ahead = AHEAD.matcher(t).find();
        if (when && (event || ahead) && !t.matches("(?s).*(생일|기념일|태어났)(?!.*(파티|모임|약속)).*")) return PLAN;
        if (LIKES.matcher(t).find()) return LIKE;
        return FACT;
    }
    /** A memory's kind as it is read now: a "fact" kept before kinds were told apart is sorted by its words. */
    public static String kind(BuddyMemory.Entry e) { return FACT.equals(e.kind()) ? sort(e.text()) : e.kind(); }

    // ── what a question asks for ──
    private static final Pattern ASKS_PLAN = Pattern.compile("(몇\\s*시|언제|며칠|무슨\\s*요일|몇\\s*요일|몇\\s*월|출발|도착|일정|약속|예약|스케줄|날짜|시간\\s*(이|은|맞|돼)|늦겠|늦을|준비해야|챙겨야|가야\\s*(돼|해|하|되)|나가야)");
    private static final Pattern ASKS_LIKE = Pattern.compile("(좋아하|싫어하|좋아할|취향|뭐\\s*먹|먹을까|마실까|추천|선물|뭐가\\s*좋|어떤\\s*게\\s*좋|입맛|들을까|볼까)");
    private static final Pattern ASKS_PROMISE = Pattern.compile("(약속했|해\\s*주기로|하기로\\s*했|알려\\s*주기로|말해\\s*주기로)");
    /** The kinds a question asks about ("몇 시에 출발할까": a plan; "뭐 먹을까": a liking); none for small talk. */
    public static Set<String> wants(String question) {
        String q = question == null ? "" : question;
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        if (ASKS_PLAN.matcher(q).find()) out.add(PLAN);
        if (ASKS_LIKE.matcher(q).find()) out.add(LIKE);
        if (ASKS_PROMISE.matcher(q).find()) out.add(PROMISE);
        return out;
    }

    // ── how near a memory is to a question ──
    /** By words: topics in common (커피 and 커피를 are one), and letter pairs in common; 0 when nothing is shared. */
    public static double words(String memory, String query) {
        if (memory == null || query == null || query.isBlank()) return 0;
        // Quotes and brackets are not part of a word (a lesson quotes what it set right: ‘생일은 5월이래’).
        String m = memory.replaceAll("[‘’“”'\"()\\[\\]「」]", " "), q = query.replaceAll("[‘’“”'\"()\\[\\]「」]", " ");
        Set<String> heard = BuddyMind.topics(q);
        int shared = 0; for (String w : BuddyMind.topics(m)) if (BuddyMind.among(w, heard)) shared++;
        return Math.min(1.5, 0.5 * shared) + BuddyMind.similarity(m, q);
    }
    /** One memory found, and how near it was. */
    public record Hit(BuddyMemory.Entry entry, double score) {}
    /**
     * The memories nearest the question, at most n, best first: how much each matters (weight, how often told, how lately: two
     * weeks to fade by half), how near its words are (words), whether it is of the kind asked for (wants), and, when the
     * question's vector and the memory's are both here, how near their meanings are (cosine, spread over this pool so it does
     * not depend on the model's scale). vectors: key(entry) → unit vector of the same model as query (null or empty: words
     * alone). Private ones only when unlocked; profile lines, promises, lessons and kept promises are given apart.
     */
    public static List<Hit> find(List<BuddyMemory.Entry> all, String question, Map<String, float[]> vectors, float[] query, long now, Map<Long, Long> shown, boolean unlocked, int n) {
        String q = question == null ? "" : question;
        Set<String> wanted = wants(q);
        List<BuddyMemory.Entry> pool = new ArrayList<>();
        for (BuddyMemory.Entry e : all) {
            if (e.secret() && !unlocked) continue;
            if ("profile".equals(e.kind()) || PROMISE.equals(e.kind()) || LESSON.equals(e.kind())) continue; // given apart
            Long last = shown == null ? null : shown.get(e.at());
            if (last != null && now - last < 24 * 3_600_000L) continue;
            pool.add(e);
        }
        Map<BuddyMemory.Entry, Double> meaning = meanings(pool, vectors, query);
        List<Hit> out = new ArrayList<>();
        for (BuddyMemory.Entry e : pool) {
            double age = Math.max(0, now - e.last()) / (14 * 86_400_000.0);
            double s = 0.25 * e.weight() + 0.06 * Math.min(e.seen(), 5) + 0.4 * Math.pow(0.5, age) + 2.2 * words(e.text(), q)
                + (wanted.contains(kind(e)) ? 0.9 : 0) + 2.5 * meaning.getOrDefault(e, 0.0);
            out.add(new Hit(e, s));
        }
        out.sort((a, b) -> Double.compare(b.score(), a.score()));
        return new ArrayList<>(out.subList(0, Math.min(Math.max(0, n), out.size())));
    }
    /**
     * Each entry's meaning as near the query, 0 to 1 over this pool (the nearest 1, the farthest 0; a pool where they are all
     * alike, or nothing to compare, 0 for all). Only entries with a vector are compared.
     */
    static Map<BuddyMemory.Entry, Double> meanings(List<BuddyMemory.Entry> pool, Map<String, float[]> vectors, float[] query) {
        Map<BuddyMemory.Entry, Double> raw = new HashMap<>(), out = new HashMap<>();
        if (query == null || vectors == null || vectors.isEmpty()) return out;
        double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE;
        for (BuddyMemory.Entry e : pool) {
            float[] v = vectors.get(key(e)); if (v == null || v.length != query.length) continue;
            double c = SemanticSearch.cosine(query, v); raw.put(e, c); lo = Math.min(lo, c); hi = Math.max(hi, c);
        }
        if (raw.size() < 2 || hi - lo < 0.02) return out;
        // Below a floor it is no meaning at all (the farthest of a pool of strangers still says nothing).
        for (Map.Entry<BuddyMemory.Entry, Double> r : raw.entrySet()) out.put(r.getKey(), Math.max(0, (r.getValue() - lo) / (hi - lo)) * (r.getValue() >= 0.15 ? 1 : 0.3));
        return out;
    }
    /** The lessons (what the user set right) that this question is about, at most n, the newest first among the near ones. */
    public static List<BuddyMemory.Entry> lessons(List<BuddyMemory.Entry> all, String question, Map<String, float[]> vectors, float[] query, boolean unlocked, int n) {
        List<BuddyMemory.Entry> pool = new ArrayList<>();
        for (BuddyMemory.Entry e : all) if (LESSON.equals(e.kind()) && (!e.secret() || unlocked)) pool.add(e);
        Map<BuddyMemory.Entry, Double> meaning = meanings(pool, vectors, query);
        List<Hit> near = new ArrayList<>();
        for (BuddyMemory.Entry e : pool) {
            double s = words(e.text(), question) + (meaning.containsKey(e) ? meaning.get(e) : 0);
            if (words(e.text(), question) >= 0.5 || meaning.getOrDefault(e, 0.0) >= 0.85) near.add(new Hit(e, s + e.at() / 1e15));
        }
        near.sort((a, b) -> Double.compare(b.score(), a.score()));
        List<BuddyMemory.Entry> out = new ArrayList<>(); for (Hit h : near) { if (out.size() >= n) break; out.add(h.entry()); }
        return out;
    }

    // ── the vectors: one per memory (its time and its words: a memory said anew is embedded anew) ──
    public static String key(BuddyMemory.Entry e) { return e.at() + ":" + Integer.toHexString(e.text().hashCode()); }
    /** NVIDIA's embedding endpoint (OpenAI style) and its free multilingual model (Korean among its languages). */
    public static final String EMBED_URL = "https://integrate.api.nvidia.com/v1/embeddings", EMBED_MODEL = "nvidia/llama-3.2-nv-embedqa-1b-v2";
    /** The first DIMS of each vector are kept (the model is trained so a shorter prefix still means the same: Matryoshka). */
    public static final int DIMS = 512, BATCH = 32, CHARS = 600;
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}"),
        PHONE = Pattern.compile("(?<![0-9])(?:\\+?[0-9]{1,3}[\\- .]?)?0?1[016789][\\- .]?[0-9]{3,4}[\\- .]?[0-9]{4}(?![0-9])|(?<![0-9])0(?:2|[3-6][1-5]|70)[\\- .)]?[0-9]{3,4}[\\- .][0-9]{4}(?![0-9])"),
        DIGITS = Pattern.compile("[0-9][0-9\\- ]{5,}[0-9]"), LINK = Pattern.compile("(?i)\\b(?:https?|wss?)://\\S+");
    /**
     * What of a text may go to the embedding model: e-mail addresses, phone numbers, long numbers and links taken out, cut
     * to CHARS; "" when it must not go at all (a private memory, a password or account, a key).
     */
    public static String outgoing(String text, boolean secret) {
        if (text == null || secret || BuddyMind.secret(text) || DiagnosticRedactor.leaks(text)) return "";
        String t = LINK.matcher(text).replaceAll(" ");
        t = EMAIL.matcher(t).replaceAll(" "); t = PHONE.matcher(t).replaceAll(" "); t = DIGITS.matcher(t).replaceAll(" ");
        t = t.replaceAll("\\s+", " ").strip();
        if (t.codePointCount(0, t.length()) < 2) return "";
        return t.codePointCount(0, t.length()) <= CHARS ? t : t.substring(0, t.offsetByCodePoints(0, CHARS));
    }
    /** An embedding request: the texts as passages (kept things) or one query (what was asked). */
    public static JSONObject request(List<String> texts, boolean query) {
        JSONArray input = new JSONArray(); for (String t : texts) input.put(t);
        return Jsons.obj("input", input, "model", EMBED_MODEL, "input_type", query ? "query" : "passage", "encoding_format", "float", "truncate", "END");
    }
    /** The vectors of an embedding answer in the order asked (by their index), each its first DIMS made unit length; null when one is missing. */
    public static List<float[]> vectors(JSONObject answer, int count) {
        JSONArray data = answer == null ? null : answer.optJSONArray("data");
        if (data == null || data.length() != count) return null;
        float[][] out = new float[count][];
        for (int i = 0; i < data.length(); i++) {
            JSONObject one = data.optJSONObject(i); if (one == null) return null;
            int at = one.optInt("index", i); if (at < 0 || at >= count || out[at] != null) return null;
            JSONArray values = one.optJSONArray("embedding"); if (values == null || values.length() == 0) return null;
            float[] head = new float[Math.min(DIMS, values.length())]; double sum = 0;
            for (int k = 0; k < head.length; k++) { head[k] = (float) values.optDouble(k, 0); sum += head[k] * (double) head[k]; }
            if (!(sum > 0)) return null;
            float scale = (float) (1 / Math.sqrt(sum)); for (int k = 0; k < head.length; k++) head[k] *= scale;
            out[at] = head;
        }
        return List.of(out);
    }
    private static final int FILE_VERSION = 1;
    /** A vector file: its version, the model, the count, then each key and its floats. */
    public static void write(DataOutput out, String model, Map<String, float[]> vectors) throws IOException {
        out.writeInt(FILE_VERSION); out.writeUTF(model == null ? "" : model); out.writeInt(vectors.size());
        for (Map.Entry<String, float[]> v : vectors.entrySet()) { out.writeUTF(v.getKey()); out.writeInt(v.getValue().length); for (float f : v.getValue()) out.writeFloat(f); }
    }
    /** A vector file read for model; an older, broken or other model's one reads as empty (it is made again). */
    public static Map<String, float[]> read(DataInput in, String model) {
        Map<String, float[]> out = new LinkedHashMap<>();
        try {
            if (in.readInt() != FILE_VERSION || !in.readUTF().equals(model)) return out;
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String key = in.readUTF(); int size = in.readInt(); if (size <= 0 || size > 4096) return new LinkedHashMap<>();
                float[] v = new float[size]; for (int k = 0; k < size; k++) v[k] = in.readFloat();
                out.put(key, v);
            }
        } catch (IOException | RuntimeException broken) { return new LinkedHashMap<>(); }
        return out;
    }
    /** The vectors still wanted: those of keys kept (a memory deleted or said anew lets its old vector go). */
    public static Map<String, float[]> kept(Map<String, float[]> vectors, Collection<String> keys) {
        Map<String, float[]> out = new LinkedHashMap<>();
        for (String k : keys) { float[] v = vectors.get(k); if (v != null) out.put(k, v); }
        return out;
    }
}
