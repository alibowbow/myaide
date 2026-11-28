package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The files the browser's AI may hand to a page's file chooser (an upload to YouTube Studio, say): only those the app itself
 * fetched for the user in this run (a Drive file drive_download kept in the 들퍼레 folder), named by the address or the
 * name the tool returned. Nothing else on the phone can be attached. Pure Java; kept a week, at most 50, and on the phone
 * (persistWith) so an app update or restart does not forget what was fetched.
 */
public final class BrowserUploads {
    private BrowserUploads() {}
    public record Entry(String uri, String name, String mime, long at) {}
    private static final List<Entry> ALLOWED = new ArrayList<>();
    private static final long DAY = 7 * 24L * 3600_000L;
    private static java.util.function.Consumer<String> sink;
    /** The list as saved before (the app's settings), and where to save it after each change. */
    public static synchronized void persistWith(String saved, java.util.function.Consumer<String> save, long now) {
        try {
            org.json.JSONArray list = new org.json.JSONArray(saved == null || saved.isBlank() ? "[]" : saved);
            for (int i = 0; i < list.length(); i++) {
                org.json.JSONObject o = list.optJSONObject(i); if (o == null) continue;
                Entry e = new Entry(o.optString("uri"), o.optString("name"), o.optString("mime"), o.optLong("at"));
                if (e.uri().matches("(?i)(content|file)://.+") && now - e.at() <= DAY && ALLOWED.stream().noneMatch(x -> x.uri().equals(e.uri()))) ALLOWED.add(e);
            }
        } catch (Exception unreadable) { /* starts empty */ }
        sink = save;
    }
    private static void save() {
        if (sink == null) return;
        org.json.JSONArray out = new org.json.JSONArray();
        for (Entry e : ALLOWED) { try { out.put(new org.json.JSONObject().put("uri", e.uri()).put("name", e.name()).put("mime", e.mime()).put("at", e.at())); } catch (Exception ignored) {} }
        try { sink.accept(out.toString()); } catch (RuntimeException ignored) {}
    }

    public static synchronized void allow(String uri, String name, String mime, long now) {
        if (uri == null || uri.isBlank() || !uri.matches("(?i)(content|file)://.+")) return;
        ALLOWED.removeIf(e -> e.uri().equals(uri) || now - e.at() > DAY);
        ALLOWED.add(new Entry(uri, name == null ? "" : name.strip(), mime == null ? "" : mime, now));
        while (ALLOWED.size() > 50) ALLOWED.remove(0);
        save();
    }
    /** The file a tool result named (its uri, or exactly its name; the newest of that name), or null. */
    public static synchronized Entry find(String reference, long now) {
        String ref = reference == null ? "" : reference.strip(); if (ref.isEmpty()) return null;
        for (int i = ALLOWED.size() - 1; i >= 0; i--) {
            Entry e = ALLOWED.get(i); if (now - e.at() > DAY) continue;
            if (e.uri().equals(ref) || e.name().equals(ref)) return e;
        }
        return null;
    }
    public static synchronized List<Entry> all(long now) { List<Entry> out = new ArrayList<>(); for (Entry e : ALLOWED) if (now - e.at() <= DAY) out.add(e); return out; }
    public static synchronized void clear() { ALLOWED.clear(); save(); }
}
