package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레's own memory (준: "퍼레 캐릭터의 메모리는 따로 들퍼레 앱에 저장"), kept in the app apart from the assistant's memory:
 * what the user told it in a talk (a plan, something they like …), the user coming back after a long while, being up in the
 * small hours, a snowy or very cold or hot day; not the phone's routine (준: "필요없는것들이 다 기록이돼"). A short line each with
 * its time and kind, the newest last, at most MOST. The same kind within its quiet time is kept once. 퍼레's own words read the
 * latest ones; the user sees and deletes them in the app. Pure Java.
 */
public final class BuddyMemory {
    private BuddyMemory() {}
    public static final int MOST = 120;

    /**
     * weight: how much it matters (1 a passing moment, 2 something told, 3 core); secret: told only after the fingerprint;
     * seen: how many times the user has told it (once when first kept); last: when it was last told or corrected (at, first).
     * at stays the memory's own: it is how the app and 퍼레 name it.
     */
    public record Entry(long at, String kind, String text, int weight, boolean secret, int seen, long last) {
        public Entry(long at, String kind, String text) { this(at, kind, text, 1, false); }
        public Entry(long at, String kind, String text, int weight, boolean secret) { this(at, kind, text, weight, secret, 1, at); }
    }

    public static List<Entry> read(String saved) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(saved == null || saved.isBlank() ? "[]" : saved);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                String text = o.optString("text").strip();
                long at = o.optLong("at");
                if (!text.isEmpty() && at > 0) out.add(new Entry(at, o.optString("kind"), text, Math.max(1, Math.min(3, o.optInt("weight", 1))), o.optBoolean("secret"), Math.max(1, o.optInt("seen", 1)), Math.max(at, o.optLong("last", at))));
            }
        } catch (Exception unreadable) { out.clear(); }
        return out;
    }
    public static String save(List<Entry> list) {
        JSONArray a = new JSONArray();
        for (Entry e : list) a.put(Jsons.obj("at", e.at(), "kind", e.kind(), "text", e.text(), "weight", e.weight(), "secret", e.secret(), "seen", e.seen(), "last", e.last()));
        return a.toString();
    }
    /** The list with this memory added, unless one of the same kind was kept within quiet ms (then the list as it was). */
    public static List<Entry> add(List<Entry> list, long now, String kind, String text, long quiet) { return add(list, now, kind, text, quiet, 1, false); }
    public static List<Entry> add(List<Entry> list, long now, String kind, String text, long quiet, int weight, boolean secret) {
        if (text == null || text.isBlank()) return list;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            if (e.kind().equals(kind) && now - e.at() < quiet) return list;
        }
        List<Entry> out = new ArrayList<>(list);
        out.add(new Entry(now, kind == null ? "" : kind, text.strip(), Math.max(1, Math.min(3, weight)), secret));
        while (out.size() > MOST) out.remove(0);
        return out;
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
            out.set(i, new Entry(e.at(), e.kind(), e.text(), seen >= 3 ? Math.min(3, e.weight() + 1) : e.weight(), e.secret(), seen, Math.max(e.last(), now)));
            return out;
        }
        return list;
    }
    /**
     * The list with the memory kept at at said anew, as the user corrected it ("커피 끊었어" for "커피를 좋아한대"): its new
     * words, something told (weight 2), told once, last now; its kind and its at stay. The list as it was when there is none.
     */
    public static List<Entry> replace(List<Entry> list, long at, String text, long now, boolean secret) {
        if (text == null || text.isBlank()) return list;
        List<Entry> out = new ArrayList<>(list);
        for (int i = 0; i < out.size(); i++) {
            Entry e = out.get(i); if (e.at() != at) continue;
            out.set(i, new Entry(e.at(), e.kind(), text.strip(), 2, secret, 1, Math.max(e.at(), now)));
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
