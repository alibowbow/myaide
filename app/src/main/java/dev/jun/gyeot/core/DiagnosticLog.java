package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A small, bounded journal of error summaries for the 오류 진단 보고서. It keeps only what helps diagnosis: when, which
 * part of the app, the exception type, a masked one-line summary (DiagnosticRedactor) and the first app stack frame.
 * Stack traces, request bodies and conversations are never stored. The same error is counted instead of repeated.
 * Thread-safe; storage is the caller's (Android: DiagnosticsExport, in the app's private files).
 */
public final class DiagnosticLog {
    public static final int FORMAT = 1, MAX_ENTRIES = 50, SUMMARY_LIMIT = 160, MAX_COUNT = 9999;
    /** Where an error happened, as the report groups it; anything else is "other". */
    public static final List<String> AREAS = List.of("app", "crash", "voice", "work", "browser", "image", "video", "gallery",
        "github", "phone", "update", "network", "storage", "other");
    private static final String APP_PACKAGE = "dev.jun.gyeot.";

    /** One kind of error: the same area, type, summary and place, counted together. */
    public record Entry(String area, String type, String summary, String where, long firstAt, long lastAt, int count) {
        String key() { return area + "\n" + type + "\n" + summary + "\n" + where; }
    }

    /** Oldest first; a repeated error moves to the end. */
    private final ArrayList<Entry> entries = new ArrayList<>();

    public static String area(String area) {
        String value = area == null ? "" : area.strip().toLowerCase(Locale.ROOT);
        return AREAS.contains(value) ? value : "other";
    }
    /** An error known only by its message (for example the text shown in the error banner). */
    public synchronized Entry record(String area, String message, long now) {
        String summary = DiagnosticRedactor.line(message, SUMMARY_LIMIT);
        return add(new Entry(area(area), "", summary.isEmpty() ? "(내용 없음)" : summary, "", now, now, 1));
    }
    /** An exception: its cause chain's types, the innermost message, and the first frame inside this app. */
    public synchronized Entry record(String area, Throwable error, long now) {
        if (error == null) return record(area, "", now);
        List<Throwable> chain = chain(error);
        StringBuilder type = new StringBuilder();
        for (Throwable item : chain) { if (type.length() > 0) type.append(" > "); type.append(simpleName(item)); }
        String message = "", place = "";
        for (int i = chain.size() - 1; i >= 0 && message.isEmpty(); i--) {
            String text = chain.get(i).getMessage();
            if (text != null && !text.isBlank()) message = text;
        }
        for (int i = chain.size() - 1; i >= 0 && place.isEmpty(); i--) place = where(chain.get(i).getStackTrace());
        String summary = DiagnosticRedactor.line(message, SUMMARY_LIMIT);
        return add(new Entry(area(area), typeName(type.toString()), summary.isEmpty() ? "(메시지 없음)" : summary, whereName(place), now, now, 1));
    }
    /** Newest first, at most `limit`. */
    public synchronized List<Entry> recent(int limit) {
        List<Entry> out = new ArrayList<>();
        for (int i = entries.size() - 1; i >= 0 && out.size() < limit; i--) out.add(entries.get(i));
        return out;
    }
    public synchronized int size() { return entries.size(); }
    public synchronized void clear() { entries.clear(); }
    /** A pre-sanitized snapshot can be queued without retaining an exception or raw request text. */
    public synchronized Entry record(Entry entry) {
        return add(new Entry(area(entry.area()), typeName(entry.type()), DiagnosticRedactor.line(entry.summary(), SUMMARY_LIMIT),
            whereName(entry.where()), entry.firstAt(), entry.lastAt(), Math.max(1, Math.min(MAX_COUNT, entry.count()))));
    }

    /** The journal as JSON for private storage. */
    public synchronized String save() {
        try {
            JSONArray list = new JSONArray();
            for (Entry entry : entries) list.put(new JSONObject().put("area", entry.area()).put("type", entry.type()).put("summary", entry.summary())
                .put("where", entry.where()).put("firstAt", entry.firstAt()).put("lastAt", entry.lastAt()).put("count", entry.count()));
            return new JSONObject().put("format", FORMAT).put("entries", list).toString();
        } catch (Exception e) { return "{\"format\":" + FORMAT + ",\"entries\":[]}"; }
    }
    /** A saved journal; unreadable or unknown data gives an empty one, and every stored value is sanitized again. */
    public static DiagnosticLog restore(String stored) {
        DiagnosticLog log = new DiagnosticLog();
        if (stored == null || stored.isBlank()) return log;
        try {
            JSONObject root = new JSONObject(stored);
            if (root.optInt("format", 0) != FORMAT) return log;
            JSONArray list = root.optJSONArray("entries");
            if (list == null) return log;
            for (int i = 0; i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null) continue;
                long first = item.optLong("firstAt", 0), last = Math.max(first, item.optLong("lastAt", first));
                int count = Math.max(1, Math.min(MAX_COUNT, item.optInt("count", 1)));
                String type = typeName(item.optString("type", "")), summary = DiagnosticRedactor.line(item.optString("summary", ""), SUMMARY_LIMIT);
                if (first <= 0 || (type.isEmpty() && summary.isEmpty())) continue;
                log.add(new Entry(area(item.optString("area", "")), type, summary.isEmpty() ? "(내용 없음)" : summary, whereName(item.optString("where", "")), first, last, count));
            }
        } catch (Exception ignored) {
            // Keep what was readable; a damaged journal must never stop the report.
        }
        return log;
    }

    private Entry add(Entry entry) {
        String key = entry.key();
        for (int i = 0; i < entries.size(); i++) {
            Entry old = entries.get(i);
            if (!old.key().equals(key)) continue;
            Entry merged = new Entry(old.area(), old.type(), old.summary(), old.where(), Math.min(old.firstAt(), entry.firstAt()),
                Math.max(old.lastAt(), entry.lastAt()), (int) Math.min(MAX_COUNT, (long) old.count() + entry.count()));
            entries.remove(i); entries.add(merged);
            return merged;
        }
        entries.add(entry);
        while (entries.size() > MAX_ENTRIES) entries.remove(0);
        return entry;
    }
    /** The cause chain, outermost first, without repeating a throwable (causes can form a cycle). */
    private static List<Throwable> chain(Throwable error) {
        List<Throwable> chain = new ArrayList<>();
        for (Throwable item = error; item != null && chain.size() < 5; item = item.getCause()) {
            boolean seen = false;
            for (Throwable earlier : chain) if (earlier == item) { seen = true; break; }
            if (seen) break;
            chain.add(item);
        }
        return chain;
    }
    private static String simpleName(Throwable error) {
        String name = error.getClass().getName();
        return name.substring(name.lastIndexOf('.') + 1);
    }
    /** "core.WorkBook.recover:120": class (without the app package), method and line of the first frame in this app. */
    private static String where(StackTraceElement[] frames) {
        if (frames == null) return "";
        for (StackTraceElement frame : frames) {
            String owner = frame.getClassName();
            if (owner == null || !owner.startsWith(APP_PACKAGE)) continue;
            return owner.substring(APP_PACKAGE.length()) + "." + frame.getMethodName() + (frame.getLineNumber() > 0 ? ":" + frame.getLineNumber() : "");
        }
        return "";
    }
    static String typeName(String value) { return clip(DiagnosticRedactor.scrub(value).replaceAll("[^A-Za-z0-9_\\$. >]", ""), 120); }
    static String whereName(String value) { return clip(DiagnosticRedactor.scrub(value).replaceAll("[^A-Za-z0-9_\\$.:<>]", ""), 120); }
    private static String clip(String value, int limit) {
        String text = value.strip();
        return text.length() <= limit ? text : text.substring(0, limit);
    }
}
