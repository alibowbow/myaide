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
 * 퍼레's own memory (준: "퍼레 캐릭터의 메모리는 따로 들퍼레 앱에 저장"): what it lived through on the phone, kept in the app apart
 * from the assistant's memory. The charger, music late at night, the user coming back after hours, a hot phone, an app opened,
 * being petted: a short line each with its time and kind, the newest last, at most MOST. The same kind within its quiet time
 * is kept once (a charger plugged in every few minutes is one memory). 퍼레's own words read the latest ones; the user sees
 * and deletes them in the app. Pure Java.
 */
public final class BuddyMemory {
    private BuddyMemory() {}
    public static final int MOST = 120;

    public record Entry(long at, String kind, String text) {}

    public static List<Entry> read(String saved) {
        List<Entry> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(saved == null || saved.isBlank() ? "[]" : saved);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.optJSONObject(i); if (o == null) continue;
                String text = o.optString("text").strip();
                if (!text.isEmpty() && o.optLong("at") > 0) out.add(new Entry(o.optLong("at"), o.optString("kind"), text));
            }
        } catch (Exception unreadable) { out.clear(); }
        return out;
    }
    public static String save(List<Entry> list) {
        JSONArray a = new JSONArray();
        for (Entry e : list) a.put(Jsons.obj("at", e.at(), "kind", e.kind(), "text", e.text()));
        return a.toString();
    }
    /** The list with this memory added, unless one of the same kind was kept within quiet ms (then the list as it was). */
    public static List<Entry> add(List<Entry> list, long now, String kind, String text, long quiet) {
        if (text == null || text.isBlank()) return list;
        for (int i = list.size() - 1; i >= 0; i--) {
            Entry e = list.get(i);
            if (e.kind().equals(kind) && now - e.at() < quiet) return list;
        }
        List<Entry> out = new ArrayList<>(list);
        out.add(new Entry(now, kind == null ? "" : kind, text.strip()));
        while (out.size() > MOST) out.remove(0);
        return out;
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
