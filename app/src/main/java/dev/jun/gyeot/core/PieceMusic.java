package dev.jun.gyeot.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 내 음악 on the LP page (준: "내 폴더에 있는 음악들도 재생가능하게 해줘", "파일 선택해서 리스트 추가 할 수 있게"): the list of the
 * phone's music files the user picked, as kept in the app's private files, what the page may see of it (titles and lengths;
 * never a file's address), and the byte ranges the page's audio element asks for. Pure Java: the Android side opens the
 * files (PieceMusicStore).
 */
public final class PieceMusic {
    private PieceMusic() {}
    public static final int MOST = 500;

    /** One file: its id on the page, the document it is (uri, and the folder it came with), what it says about itself. */
    public record Track(String id, String uri, String tree, String title, String artist, long durMs, String mime, long size, long addedAt, boolean art) {
        JSONObject json() {
            return Jsons.obj("id", id, "uri", uri, "tree", tree, "title", title, "artist", artist, "durMs", durMs, "mime", mime, "size", size, "addedAt", addedAt, "art", art);
        }
        static Track of(JSONObject o) {
            if (o == null || !validId(o.optString("id")) || o.optString("uri").isEmpty()) return null;
            return new Track(o.optString("id"), o.optString("uri"), o.optString("tree"), o.optString("title"), o.optString("artist"),
                Math.max(0, o.optLong("durMs")), o.optString("mime"), o.optLong("size", -1), o.optLong("addedAt"), o.optBoolean("art"));
        }
        public Track withArt(boolean has) { return new Track(id, uri, tree, title, artist, durMs, mime, size, addedAt, has); }
    }
    /** The list and its revision (one more on every change, so the page knows to ask again). */
    public record Shelf(int rev, List<Track> items) {
        public Track find(String id) { for (Track t : items) if (t.id().equals(id)) return t; return null; }
        public boolean has(String uri) { for (Track t : items) if (t.uri().equals(uri)) return true; return false; }
    }
    public static final Shelf EMPTY = new Shelf(0, List.of());

    public static Shelf read(String json) {
        if (json == null || json.isBlank()) return EMPTY;
        try {
            JSONObject o = new JSONObject(json);
            JSONArray list = o.optJSONArray("items");
            List<Track> items = new ArrayList<>();
            Set<String> seen = new HashSet<>();
            for (int i = 0; list != null && i < list.length() && items.size() < MOST; i++) {
                Track t = Track.of(list.optJSONObject(i));
                if (t != null && seen.add(t.id())) items.add(t);
            }
            return new Shelf(Math.max(0, o.optInt("rev")), List.copyOf(items));
        } catch (Exception unreadable) { return EMPTY; }
    }
    public static String write(Shelf shelf) {
        JSONArray list = new JSONArray();
        for (Track t : shelf.items()) list.put(t.json());
        return Jsons.obj("rev", shelf.rev(), "items", list).toString();
    }
    /**
     * What the page gets: titles, artists, lengths, whether a track can be sought (its size is known) and has a picture. No
     * address of a file or folder; pick is how the last 파일 추가 is going ("open", "notification", "cancelled", "done" or "").
     */
    public static JSONObject page(Shelf shelf, String pick) {
        JSONArray list = new JSONArray();
        for (Track t : shelf.items())
            list.put(Jsons.obj("id", t.id(), "title", t.title(), "artist", t.artist(), "dur", Math.round(t.durMs() / 100.0) / 10.0,
                "mime", t.mime(), "size", Math.max(0, t.size()), "seek", t.size() > 0, "art", t.art()));
        return Jsons.obj("rev", shelf.rev(), "items", list, "pick", pick == null ? "" : pick);
    }

    /** The files added at the end (a file already there is not added again), at most MOST in all. */
    public static Shelf add(Shelf shelf, List<Track> more) {
        List<Track> items = new ArrayList<>(shelf.items());
        Set<String> uris = new HashSet<>(), ids = new HashSet<>();
        for (Track t : items) { uris.add(t.uri()); ids.add(t.id()); }
        int added = 0;
        for (Track t : more) {
            if (items.size() >= MOST) break;
            if (t == null || !uris.add(t.uri()) || !ids.add(t.id())) continue;
            items.add(t); added++;
        }
        return added == 0 ? shelf : new Shelf(shelf.rev() + 1, List.copyOf(items));
    }
    public static Shelf remove(Shelf shelf, String id) {
        List<Track> items = new ArrayList<>(shelf.items());
        if (!items.removeIf(t -> t.id().equals(id))) return shelf;
        return new Shelf(shelf.rev() + 1, List.copyOf(items));
    }
    public static Shelf replace(Shelf shelf, Track changed) {
        List<Track> items = new ArrayList<>();
        for (Track t : shelf.items()) items.add(t.id().equals(changed.id()) ? changed : t);
        return new Shelf(shelf.rev() + 1, List.copyOf(items));
    }

    /** A file's id: twelve hex digits from its document address (the same file always has the same one). */
    public static String id(String uri) {
        try {
            byte[] h = MessageDigest.getInstance("SHA-256").digest((uri == null ? "" : uri).getBytes(StandardCharsets.UTF_8));
            StringBuilder s = new StringBuilder();
            for (int i = 0; i < 6; i++) s.append(String.format(Locale.ROOT, "%02x", h[i] & 0xff));
            return s.toString();
        } catch (Exception impossible) { return "000000000000"; }
    }
    public static boolean validId(String id) { return id != null && id.matches("[0-9a-f]{12}"); }

    /** The title a file shows without its own: its name without the extension (and the path a few apps put in it). */
    public static String title(String displayName) {
        String name = displayName == null ? "" : displayName.strip();
        int slash = Math.max(name.lastIndexOf('/'), name.lastIndexOf('\\'));
        if (slash >= 0) name = name.substring(slash + 1);
        int dot = name.lastIndexOf('.');
        if (dot > 0 && name.length() - dot <= 6) name = name.substring(0, dot);
        name = name.replace('_', ' ').strip();
        return name.isEmpty() ? "제목 없는 곡" : name.length() > 120 ? name.substring(0, 120) : name;
    }
    /** What the web view's audio element plays (not AMR, 3GP or MIDI: they would only show '재생할 수 없는 형식' on the list). */
    private static final Set<String> EXTENSIONS = Set.of("mp3", "m4a", "aac", "mp4", "ogg", "oga", "opus", "flac", "wav", "wave", "weba", "webm", "mka");
    private static final Set<String> UNPLAYABLE = Set.of("audio/amr", "audio/amr-wb", "audio/3gpp", "audio/3gpp2", "audio/midi", "audio/mid", "audio/x-midi", "audio/sp-midi", "audio/x-mid");
    /** Whether a document is sound the page can play, by its type, or by its name when the provider says nothing useful. */
    public static boolean audio(String mime, String name) {
        String m = mime == null ? "" : mime.toLowerCase(Locale.ROOT);
        int semi = m.indexOf(';'); if (semi >= 0) m = m.substring(0, semi).strip();
        if (UNPLAYABLE.contains(m)) return false;
        if (m.startsWith("audio/") || m.equals("application/ogg") || m.equals("application/x-flac")) return true;
        if (!m.isEmpty() && !m.equals("application/octet-stream") && !m.equals("*/*")) return false;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        int dot = n.lastIndexOf('.');
        return dot > 0 && EXTENSIONS.contains(n.substring(dot + 1));
    }
    /** The type to serve a file as: what the provider said, or one from its name. */
    public static String mime(String mime, String name) {
        if (mime != null && (mime.startsWith("audio/") || mime.equals("application/ogg"))) return mime;
        String n = name == null ? "" : name.toLowerCase(Locale.ROOT);
        if (n.endsWith(".m4a") || n.endsWith(".mp4") || n.endsWith(".aac")) return "audio/mp4";
        if (n.endsWith(".ogg") || n.endsWith(".oga") || n.endsWith(".opus")) return "audio/ogg";
        if (n.endsWith(".flac")) return "audio/flac";
        if (n.endsWith(".wav") || n.endsWith(".wave")) return "audio/wav";
        if (n.endsWith(".weba") || n.endsWith(".webm")) return "audio/webm";
        return "audio/mpeg";
    }

    /** A byte range asked for: first and last byte, both inside the file. */
    public record Range(long first, long last) { public long length() { return last - first + 1; } }
    /** The whole file (no Range header). */
    public static final Range ALL = new Range(-1, -1);
    /**
     * A Range header ("bytes=a-", "bytes=a-b", "bytes=-n"; of several, the first) against a file of size bytes: ALL when
     * there is none or it is not about bytes, null when it cannot be satisfied (416).
     */
    public static Range range(String header, long size) {
        if (header == null || header.isBlank()) return ALL;
        String h = header.strip().toLowerCase(Locale.ROOT);
        if (!h.startsWith("bytes=")) return ALL;
        String spec = h.substring(6);
        int comma = spec.indexOf(',');
        if (comma >= 0) spec = spec.substring(0, comma);
        spec = spec.strip();
        int dash = spec.indexOf('-');
        if (dash < 0 || size <= 0) return null;
        try {
            String a = spec.substring(0, dash).strip(), b = spec.substring(dash + 1).strip();
            if (a.isEmpty()) {
                if (b.isEmpty()) return null;
                long n = Long.parseLong(b);
                if (n <= 0) return null;
                return new Range(Math.max(0, size - n), size - 1);
            }
            long first = Long.parseLong(a), last = b.isEmpty() ? size - 1 : Math.min(size - 1, Long.parseLong(b));
            if (first < 0 || first >= size || last < first) return null;
            return new Range(first, last);
        } catch (NumberFormatException bad) { return null; }
    }
}
