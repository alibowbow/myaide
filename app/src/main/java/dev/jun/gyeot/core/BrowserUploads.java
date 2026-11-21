package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The files the browser's AI may hand to a page's file chooser (an upload to YouTube Studio, say): only those the app itself
 * fetched for the user in this run (a Drive file drive_download kept in the 들퍼레 folder), named by the address or the
 * name the tool returned. Nothing else on the phone can be attached. Pure Java; kept a day, at most 50.
 */
public final class BrowserUploads {
    private BrowserUploads() {}
    public record Entry(String uri, String name, String mime, long at) {}
    private static final List<Entry> ALLOWED = new ArrayList<>();
    private static final long DAY = 24L * 3600_000L;

    public static synchronized void allow(String uri, String name, String mime, long now) {
        if (uri == null || uri.isBlank() || !uri.matches("(?i)(content|file)://.+")) return;
        ALLOWED.removeIf(e -> e.uri().equals(uri) || now - e.at() > DAY);
        ALLOWED.add(new Entry(uri, name == null ? "" : name.strip(), mime == null ? "" : mime, now));
        while (ALLOWED.size() > 50) ALLOWED.remove(0);
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
    public static synchronized void clear() { ALLOWED.clear(); }
}
