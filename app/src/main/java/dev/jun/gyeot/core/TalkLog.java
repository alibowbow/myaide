package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONObject;

/**
 * Every talk with 퍼레 kept on the phone (기억 2.0: "지난번에 내가 뭐라고 했지?"): 퍼레와 대화 typed on the home screen (by the
 * page's id for the talk, so a talk deleted there goes here too) and the voice talk, the user's turns and 퍼레's, a guest's
 * never. A question about what was said is answered from them: by the words said (the day's own word reading, DayLog.words)
 * and by meaning (the same embedding model as 퍼레's memory, for the user's turns), on the days said ("어제", "지난주"); with
 * no words to look for, the talk before this one. Pure Java: the lines, the search and the answer.
 */
public final class TalkLog {
    private TalkLog() {}
    /** At most this many turns, none older than a year; a turn at most this long. */
    public static final int MOST = 3_000, LONG = 1_500;
    public static final long KEEP_MS = 365L * 86_400_000L;
    /** The voice talk's id (퍼레와 대화's are the page's). */
    public static final String VOICE = "voice";

    /** One turn: when, which talk, the user's or 퍼레's, its words. */
    public record Turn(long at, String conv, boolean user, String text) {
        public String key() { return at + ":" + Integer.toHexString(text.hashCode()); }
        public String json() { return Jsons.obj("at", at, "conv", conv, "who", user ? "me" : "buddy", "text", text).toString(); }
    }
    /** The kept lines (one JSON a line), oldest first; a line that does not read is skipped. */
    public static List<Turn> read(String lines) {
        List<Turn> out = new ArrayList<>();
        if (lines == null) return out;
        for (String l : lines.split("\n")) {
            if (l.isBlank()) continue;
            try {
                JSONObject o = new JSONObject(l);
                String text = o.optString("text").strip(); long at = o.optLong("at");
                if (!text.isEmpty() && at > 0) out.add(new Turn(at, o.optString("conv"), "me".equals(o.optString("who")), text));
            } catch (Exception unreadable) { /* skipped */ }
        }
        return out;
    }
    public static String save(List<Turn> all) { StringBuilder b = new StringBuilder(); for (Turn t : all) b.append(t.json()).append('\n'); return b.toString(); }
    /**
     * The turns of a talk to keep, from turn from on ({role, text[, "g"]}: a guest's left out, so are empty ones), each its
     * own moment from now on (the times stay apart and in order), cut to LONG.
     */
    public static List<Turn> added(List<String[]> turns, int from, String conv, long now) {
        List<Turn> out = new ArrayList<>();
        if (turns == null) return out;
        int n = 0;
        for (int i = Math.max(0, from); i < turns.size(); i++) {
            String[] t = turns.get(i);
            if (t == null || t.length < 2 || t[1] == null || t[1].isBlank() || BuddyMind.guestTurn(t)) continue;
            String text = t[1].replaceAll("\\s+", " ").strip();
            if (text.codePointCount(0, text.length()) > LONG) text = text.substring(0, text.offsetByCodePoints(0, LONG));
            out.add(new Turn(now + n++, conv == null || conv.isBlank() ? VOICE : conv.strip(), "user".equals(t[0]), text));
        }
        return out;
    }
    /** All with the new turns after them: at most MOST, none older than KEEP_MS. */
    public static List<Turn> kept(List<Turn> all, List<Turn> added, long now) {
        List<Turn> out = new ArrayList<>();
        for (Turn t : all) if (now - t.at() < KEEP_MS) out.add(t);
        out.addAll(added);
        return out.size() <= MOST ? out : new ArrayList<>(out.subList(out.size() - MOST, out.size()));
    }
    /** Without a talk deleted on the page ("*": every typed talk; the voice talk's turns stay). */
    public static List<Turn> without(List<Turn> all, String conv) {
        String c = conv == null ? "" : conv.strip();
        List<Turn> out = new ArrayList<>();
        for (Turn t : all) if (!("*".equals(c) ? !VOICE.equals(t.conv()) : t.conv().equals(c))) out.add(t);
        return out;
    }

    // ── a question about what was said ──
    private static final Pattern ASKS = Pattern.compile("(?:뭐라고|뭐라)\\s*(?:했|그랬|말했|얘기했|이야기했|물어봤)|(?:했던|한)\\s*(?:말|얘기|이야기)|(?:말했던|얘기했던|이야기했던|물어봤던|부탁했던|말해\\s*줬던|알려\\s*줬던)"
        + "|(?:말한|얘기한|이야기한|물어본|부탁한)\\s*(?:거|게|것|적)|대화\\s*(?:에서|했던|한\\s*거|나눴)|무슨\\s*(?:얘기|이야기|말)\\s*(?:를|을)?\\s*(?:했|나눴)|(?:얘기|이야기)\\s*(?:했었|했지|했더라|나눴)|말했었|말했지|말했더라");
    /** Whether the words ask what was said in a talk ("지난번에 내가 뭐라고 했지?", "어제 결혼식 얘기했던 거", "아까 말한 거"). */
    public static boolean asks(String said) { return said != null && ASKS.matcher(said).find(); }
    /** Whether they ask what 퍼레 itself said ("너가 뭐라고 했지?", "퍼레가 알려 줬던"). */
    public static boolean asksBuddy(String said) { return said != null && Pattern.compile("(?:너가|네가|니가|너는|퍼레가|퍼레는)").matcher(said).find(); }
    private static final Set<String> ASKING = Set.of("뭐라고", "뭐라", "말", "말한", "말했던", "말했었", "얘기한", "얘기했던", "이야기한", "이야기했던", "물어본", "물어봤던", "부탁한", "부탁했던", "대화", "얘기", "이야기",
        "거", "것", "게", "적", "너가", "네가", "니가", "너", "퍼레가", "퍼레", "그랬", "그랬지", "그랬더라", "했어", "했지", "했더라", "했었지", "했던", "나눴", "나눴던", "무슨", "알려줬던", "말해줬던", "지난번", "저번", "아까", "전에");
    /** What to look for in the talks: the words said, without the asking ones (DayLog.words without 뭐라고, 말, 얘기 …). */
    public static List<String> words(String said) {
        List<String> out = new ArrayList<>();
        for (String w : DayLog.words(said)) if (!ASKING.contains(w) && !w.startsWith("뭐라") && !w.matches("(말|얘기|이야기|대화)(한|했던|했|하던)?")) out.add(w);
        return out;
    }
    private static boolean holds(Turn t, String word) {
        String all = t.text().toLowerCase(Locale.ROOT).replaceAll("[\\s‘’'\"·()]", ""), w = word.replace(" ", "");
        if (all.contains(w)) return true;
        return w.length() >= 3 && "이가도과와로랑야은는을를에의".indexOf(w.charAt(w.length() - 1)) >= 0 && all.contains(w.substring(0, w.length() - 1));
    }
    /**
     * The turns that answer it, at most n, best first: those on the days asked (from..to, null for any), the user's (and 퍼레's
     * when asked about its own words); near by the words said (each one held) and by meaning (vectors: key → unit vector, q
     * the question's, of one model; null: words alone, spread over the pool as for memories), the newer a little first.
     * With nothing to look for (no words, no vector), the talk before this one: its last user turns (at most n), in order.
     */
    public static List<Turn> find(List<Turn> all, String said, Map<String, float[]> vectors, float[] q, LocalDate from, LocalDate to, ZoneId zone, long now, int n) {
        boolean buddy = asksBuddy(said);
        List<Turn> pool = new ArrayList<>();
        for (Turn t : all) {
            if (!(t.user() || buddy)) continue;
            if (from != null) { LocalDate d = Instant.ofEpochMilli(t.at()).atZone(zone).toLocalDate(); if (d.isBefore(from) || to != null && d.isAfter(to)) continue; }
            // The question itself is not an answer.
            if (t.user() && t.text().equals(said == null ? "" : said.strip())) continue;
            pool.add(t);
        }
        List<String> words = words(said);
        Map<Turn, Double> meaning = new HashMap<>();
        if (q != null && vectors != null && !vectors.isEmpty()) {
            double lo = Double.MAX_VALUE, hi = -Double.MAX_VALUE; Map<Turn, Double> raw = new HashMap<>();
            for (Turn t : pool) { float[] v = vectors.get(t.key()); if (v == null || v.length != q.length) continue; double c = SemanticSearch.cosine(q, v); raw.put(t, c); lo = Math.min(lo, c); hi = Math.max(hi, c); }
            if (raw.size() >= 2 && hi - lo >= 0.02) for (Map.Entry<Turn, Double> r : raw.entrySet()) meaning.put(r.getKey(), (r.getValue() - lo) / (hi - lo) * (r.getValue() >= 0.15 ? 1 : 0.3));
        }
        if (words.isEmpty() && meaning.isEmpty()) return lastTalk(pool, n);
        Map<Turn, Double> score = new LinkedHashMap<>();
        for (Turn t : pool) {
            int held = 0; for (String w : words) if (holds(t, w)) held++;
            double m = meaning.getOrDefault(t, 0.0);
            if (held == 0 && m < 0.75) continue;
            double age = Math.max(0, now - t.at()) / (30 * 86_400_000.0);
            score.put(t, 2.0 * held + 2.5 * m + 0.3 * Math.pow(0.5, age));
        }
        List<Turn> out = new ArrayList<>(score.keySet());
        out.sort((a, b) -> Double.compare(score.get(b), score.get(a)));
        return new ArrayList<>(out.subList(0, Math.min(n, out.size())));
    }
    /** The talk before the newest (the newest is this one, still going), its last user turns, in order. */
    private static List<Turn> lastTalk(List<Turn> pool, int n) {
        List<Turn> mine = new ArrayList<>(); for (Turn t : pool) if (t.user()) mine.add(t);
        if (mine.isEmpty()) return mine;
        mine.sort(Comparator.comparingLong(Turn::at));
        String newest = mine.get(mine.size() - 1).conv();
        String before = null;
        for (int i = mine.size() - 1; i >= 0; i--) if (!mine.get(i).conv().equals(newest)) { before = mine.get(i).conv(); break; }
        // One talk only (or the voice talk, one long one): its turns before the last half hour.
        List<Turn> out = new ArrayList<>();
        for (Turn t : mine) if (before != null ? t.conv().equals(before) : t.at() < mine.get(mine.size() - 1).at() - 30 * 60_000L) out.add(t);
        if (out.isEmpty()) out = mine;
        return new ArrayList<>(out.subList(Math.max(0, out.size() - n), out.size()));
    }
    /** The answer: "11월 20일(금) 오후 9:05에 ‘토요일 결혼식 2시에 가’라고 했어 / …"; "" for none. */
    public static String answer(List<Turn> hits, ZonedDateTime now) {
        if (hits == null || hits.isEmpty()) return "";
        LocalDate today = now.toLocalDate(); ZoneId zone = now.getZone();
        List<Turn> sorted = new ArrayList<>(hits); sorted.sort(Comparator.comparingLong(Turn::at));
        StringBuilder b = new StringBuilder();
        for (Turn t : sorted) {
            String one = DayLog.when(t.at(), today, zone) + "에 " + (t.user() ? "" : "내가 ") + "‘" + cut(t.text(), 60) + "’라고 했어";
            if (b.length() + one.length() > DayLog.SPOKEN - 4 && b.length() > 0) break;
            b.append(b.length() == 0 ? "" : " / ").append(one);
        }
        return b.append('.').toString();
    }
    /** Nothing found: what was looked through, in 퍼레's words. */
    public static String notFound(List<String> words, int looked, String days) {
        String what = words == null || words.isEmpty() ? "그런 얘기" : "‘" + String.join(" ", words) + "’ 얘기";
        return (days == null || days.isBlank() ? "" : days + " ") + "우리 대화 " + looked + "개를 찾아봤는데 " + what + "는 없었어.";
    }
    private static String cut(String s, int most) { String t = s == null ? "" : s.strip(); return t.codePointCount(0, t.length()) <= most ? t : t.substring(0, t.offsetByCodePoints(0, most)) + "…"; }
}
