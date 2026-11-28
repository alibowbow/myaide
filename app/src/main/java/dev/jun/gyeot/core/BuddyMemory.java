package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.IsoFields;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레's own memory (준: "퍼레 캐릭터의 메모리는 따로 들퍼레 앱에 저장"), kept in the app apart from the assistant's memory:
 * what the user told it in a talk (a plan, something they like …), the user coming back after a long while, being up in the
 * small hours, a snowy or very cold or hot day; not the phone's routine (준: "필요없는것들이 다 기록이돼"). A short line each with
 * its time and kind, the newest last, at most MOST. The same kind within its quiet time is kept once, and the routine moments
 * are counted a week to a line (tally). When it is full, the one that matters least goes (keep), never what the user asked
 * to be kept while a passing moment is there to go. 퍼레's own words read the latest ones; the user sees and deletes them in
 * the app. Pure Java.
 */
public final class BuddyMemory {
    private BuddyMemory() {}
    public static final int MOST = 120;

    /**
     * weight: how much it matters (1 a passing moment, 2 something told, 3 core); secret: told only after the fingerprint;
     * seen: how many times the user has told it (once when first kept; for a week's tally, how many times it happened); last:
     * when it was last told or corrected (at, first); done: a promise kept, or one no longer needed. at stays the memory's
     * own: it is how the app and 퍼레 name it.
     */
    public record Entry(long at, String kind, String text, int weight, boolean secret, int seen, long last, boolean done) {
        public Entry(long at, String kind, String text) { this(at, kind, text, 1, false); }
        public Entry(long at, String kind, String text, int weight, boolean secret) { this(at, kind, text, weight, secret, 1, at); }
        public Entry(long at, String kind, String text, int weight, boolean secret, int seen, long last) { this(at, kind, text, weight, secret, seen, last, false); }
        /** The same memory, its promise done. */
        public Entry finished() { return new Entry(at, kind, text, weight, secret, seen, last, true); }
    }
    /** The kinds the user tells (what they said, the night's notes about them, promises): their seen counts how often they told it. */
    public static boolean told(String kind) { return "fact".equals(kind) || "like".equals(kind) || "plan".equals(kind) || "profile".equals(kind) || "promise".equals(kind); }

    public static List<Entry> read(String saved) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(saved == null || saved.isBlank() ? "[]" : saved);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                String text = o.optString("text").strip();
                long at = o.optLong("at");
                if (!text.isEmpty() && at > 0) out.add(new Entry(at, o.optString("kind"), text, Math.max(1, Math.min(3, o.optInt("weight", 1))), o.optBoolean("secret"), Math.max(1, o.optInt("seen", 1)), Math.max(at, o.optLong("last", at)), o.optBoolean("done")));
            }
        } catch (Exception unreadable) { out.clear(); }
        return out;
    }
    public static String save(List<Entry> list) {
        JSONArray a = new JSONArray();
        for (Entry e : list) { JSONObject o = Jsons.obj("at", e.at(), "kind", e.kind(), "text", e.text(), "weight", e.weight(), "secret", e.secret(), "seen", e.seen(), "last", e.last()); if (e.done()) Jsons.put(o, "done", true); a.put(o); }
        return a.toString();
    }
    /** The list with this memory added, unless one of the same kind was kept within quiet ms (then the list as it was). */
    public static List<Entry> add(List<Entry> list, long now, String kind, String text, long quiet) { return add(list, now, kind, text, quiet, 1, false); }
    public static List<Entry> add(List<Entry> list, long now, String kind, String text, long quiet, int weight, boolean secret) {
        if (text == null || text.isBlank()) return list;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            // No quiet time (0): never held back (three told in one millisecond dropped the third: its neighbour's time was ahead).
            if (quiet > 0 && e.kind().equals(kind) && now - e.at() < quiet) return list;
        }
        List<Entry> out = new ArrayList<>(list);
        out.add(new Entry(free(out, now), kind == null ? "" : kind, text.strip(), Math.max(1, Math.min(3, weight)), secret));
        return fit(out, now);
    }
    /**
     * at, or the first moment after it that no memory has: at is how a memory is named (the memory book deletes by it, a
     * lesson corrects by it), so two never share it. The night's digest kept its first note line and the day at the same
     * moment, and deleting one deleted both.
     */
    public static long free(List<Entry> list, long at) {
        long t = at;
        for (boolean taken = true; taken; ) { taken = false; for (Entry e : list) if (e.at() == t) { t++; taken = true; break; } }
        return t;
    }
    /**
     * How much a memory is worth keeping now: how much it matters, how often the user told it (up to five times; a routine
     * moment's count does not make it matter), and how lately it was told (a month to fade by half).
     */
    public static double keep(Entry e, long now) {
        double age = Math.max(0, now - e.last()) / (30 * 86_400_000.0);
        return e.weight() + (told(e.kind()) ? 0.3 * Math.min(e.seen(), 5) : 0) + Math.pow(0.5, age);
    }
    /** What the user asked to keep or told again and again (weight 3, told three times): not to go while a passing moment can. */
    private static boolean kept(Entry e) { return e.weight() >= 3 || told(e.kind()) && e.seen() >= 3; }
    /** The list at most MOST long: the one worth least goes first (keep), never a kept one while a weight-1 one is there. */
    public static List<Entry> fit(List<Entry> list, long now) {
        if (list.size() <= MOST) return list;
        List<Entry> out = new ArrayList<>(list);
        while (out.size() > MOST) {
            boolean passing = false; for (Entry e : out) if (e.weight() <= 1) { passing = true; break; }
            int worst = -1; double least = Double.MAX_VALUE;
            for (int i = 0; i < out.size(); i++) {
                Entry e = out.get(i); if (passing && kept(e)) continue;
                double k = keep(e, now); if (k < least) { least = k; worst = i; }
            }
            out.remove(worst < 0 ? 0 : worst);
        }
        return out;
    }
    /**
     * The list with a routine moment counted (준: "필요없는것들이 다 기록이돼"): one line a kind a week (ISO), its count in seen and
     * in its words (line: the words for that many times), last now; a new line the first time in a week. oncePerDay: a second
     * time the same day is not counted (a cold day, a late night). The list as it was when nothing changed.
     */
    public static List<Entry> tally(List<Entry> list, long now, ZoneId zone, String kind, boolean oncePerDay, java.util.function.IntFunction<String> line) {
        ZonedDateTime t = Instant.ofEpochMilli(now).atZone(zone);
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i); if (!e.kind().equals(kind)) continue;
            ZonedDateTime w = Instant.ofEpochMilli(e.at()).atZone(zone);
            if (w.get(IsoFields.WEEK_BASED_YEAR) != t.get(IsoFields.WEEK_BASED_YEAR) || w.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) != t.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)) break;
            if (oncePerDay && Instant.ofEpochMilli(e.last()).atZone(zone).toLocalDate().equals(t.toLocalDate())) return list;
            String text = line.apply(e.seen() + 1);
            if (text == null || text.isBlank()) return list;
            List<Entry> out = new ArrayList<>(list);
            out.set(i, new Entry(e.at(), kind, text.strip(), e.weight(), e.secret(), e.seen() + 1, Math.max(e.last(), now)));
            return out;
        }
        String text = line.apply(1);
        if (text == null || text.isBlank()) return list;
        List<Entry> out = new ArrayList<>(list);
        out.add(new Entry(free(out, now), kind, text.strip(), 1, false));
        return fit(out, now);
    }
    /** The list with the promise kept at at done (kept, as the memory book shows it, but no longer brought up). */
    public static List<Entry> done(List<Entry> list, long at) {
        List<Entry> out = new ArrayList<>(list);
        for (int i = 0; i < out.size(); i++) if (out.get(i).at() == at && "promise".equals(out.get(i).kind()) && !out.get(i).done()) { out.set(i, out.get(i).finished()); return out; }
        return list;
    }
    /**
     * The list with the memory kept at at told once more (준: "퍼레의 학습기능 … 강화해줘"): seen one more, last now, and from the
     * third time on it matters more (weight up to 3). The list as it was when there is none.
     */
    public static List<Entry> confirm(List<Entry> list, long at, long now) {
        List<Entry> out = new ArrayList<>(list);
        for (int i = 0; i < out.size(); i++) {
            Entry e = out.get(i); if (e.at() != at) continue;
            int seen = e.seen() + 1;
            out.set(i, new Entry(e.at(), e.kind(), e.text(), seen >= 3 ? Math.min(3, e.weight() + 1) : e.weight(), e.secret(), seen, Math.max(e.last(), now), e.done()));
            return out;
        }
        return list;
    }
    /**
     * The list with the memory kept at at said anew, as the user corrected it ("커피 끊었어" for "커피를 좋아한대"): its new
     * words, something told (weight 2), told once, last now; its kind and its at stay. The list as it was when there is none.
     */
    public static List<Entry> replace(List<Entry> list, long at, String text, long now, boolean secret) { return replace(list, at, text, now, secret, 2); }
    /** The same, as much as it matters now (3: asked to be kept; 1: the night's guess). */
    public static List<Entry> replace(List<Entry> list, long at, String text, long now, boolean secret, int weight) {
        if (text == null || text.isBlank()) return list;
        List<Entry> out = new ArrayList<>(list);
        for (int i = 0; i < out.size(); i++) {
            Entry e = out.get(i); if (e.at() != at) continue;
            out.set(i, new Entry(e.at(), e.kind(), text.strip(), Math.max(1, Math.min(3, weight)), secret, 1, Math.max(e.at(), now)));
            return out;
        }
        return list;
    }
    public static List<Entry> remove(List<Entry> list, long at) {
        List<Entry> out = new ArrayList<>();
        for (Entry e : list) if (e.at() != at) out.add(e);
        return out;
    }
    /** The latest n as lines for 퍼레's words, oldest first: "어제 밤 11시: …". */
    public static String recent(List<Entry> list, long now, int n, ZoneId zone) {
        StringBuilder out = new StringBuilder();
        for (int i = Math.max(0, list.size() - n); i < list.size(); i++) out.append("- ").append(when(list.get(i).at(), now, zone)).append(": ").append(list.get(i).text()).append('\n');
        return out.toString().strip();
    }
    /** When, as one says it: 방금, 12분 전, 3시간 전, 어제 밤 11시, 11월 24일 오후 3시. */
    public static String when(long at, long now, ZoneId zone) {
        long min = Math.max(0, (now - at) / 60_000);
        if (min < 1) return "방금";
        if (min < 60) return min + "분 전";
        ZonedDateTime t = Instant.ofEpochMilli(at).atZone(zone);
        LocalDate today = Instant.ofEpochMilli(now).atZone(zone).toLocalDate();
        if (min < 6 * 60 && t.toLocalDate().equals(today)) return min / 60 + "시간 전";
        int h = t.getHour(); String hour = (h < 5 ? "새벽 " : h < 12 ? "오전 " : h < 18 ? "오후 " : "밤 ") + (h % 12 == 0 ? 12 : h % 12) + "시";
        if (t.toLocalDate().equals(today)) return "오늘 " + hour;
        if (t.toLocalDate().equals(today.minusDays(1))) return "어제 " + hour;
        return t.getMonthValue() + "월 " + t.getDayOfMonth() + "일 " + hour;
    }
}
