package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The profile picture library's index: every picture the user made (GPT image, SVG, GIF) or chose, newest first, kept on
 * this device so any of them can become the profile again. An entry also remembers the expression GIFs made for it, one
 * per avatar mood. Pure data: the app keeps the files beside the index, under names derived only from validated ids.
 */
public final class ProfileShelf {
    public static final int VERSION = 1, LIMIT = 24, NAME_LIMIT = 40, PROMPT_LIMIT = 600;
    /** Where a picture came from: GPT image (png), GPT SVG, GPT GIF, the 들퍼레 folder, or the profile it already was. */
    public static final List<String> SOURCES = List.of("gpt", "svg", "gif", "upload", "saved");
    public static final List<String> FORMATS = List.of("png", "jpeg", "gif", "webp");
    private static final Pattern ID = Pattern.compile("[a-z0-9]{6,32}"), CHECKSUM = Pattern.compile("[0-9a-f]{16,64}");
    private final List<Entry> entries = new ArrayList<>();

    /** One kept picture. Immutable: the with… methods return a changed copy. */
    public static final class Entry {
        public final String id, name, source, prompt, checksum, format;
        public final long created;
        /** The SVG the picture was drawn from is kept beside it (svgFile). */
        public final boolean svg;
        /** The expression GIFs made for it, as BotAvatar mood names, in the order they were made. */
        public final Set<String> moods;
        /** Show the expression GIFs as the profile when this picture is the profile. */
        public final boolean useMoods;

        Entry(String id, String name, String source, String prompt, String checksum, String format, long created, boolean svg, Set<String> moods, boolean useMoods) {
            if (id == null || !ID.matcher(id).matches()) throw new IllegalArgumentException("보관함 항목 이름이 올바르지 않아요.");
            if (!SOURCES.contains(source)) throw new IllegalArgumentException("보관함 항목 출처가 올바르지 않아요.");
            if (!FORMATS.contains(format)) throw new IllegalArgumentException("보관함 그림 형식이 올바르지 않아요.");
            if (checksum == null || !CHECKSUM.matcher(checksum).matches()) throw new IllegalArgumentException("보관함 그림 확인값이 올바르지 않아요.");
            this.id = id; this.name = clip(name, NAME_LIMIT, "프로필 사진"); this.source = source; this.prompt = clip(prompt, PROMPT_LIMIT, "");
            this.checksum = checksum; this.format = format; this.created = Math.max(0, created); this.svg = svg;
            Set<String> kept = new LinkedHashSet<>();
            for (String m : moods) if (BotAvatar.MOODS.contains(m)) kept.add(m);
            this.moods = Collections.unmodifiableSet(kept); this.useMoods = useMoods;
        }
        public String imageFile() { return id + ".img"; }
        public String svgFile() { return id + ".svg"; }
        public String moodFile(String mood) {
            if (!BotAvatar.MOODS.contains(mood)) throw new IllegalArgumentException("표정 이름이 올바르지 않아요.");
            return id + "-" + mood + ".gif";
        }
        /** Moods still to make, in the editor's order. */
        public List<String> missingMoods() { List<String> out = new ArrayList<>(); for (String m : BotAvatar.MOODS) if (!moods.contains(m)) out.add(m); return out; }
        public Entry withMood(String mood) { Set<String> next = new LinkedHashSet<>(moods); next.add(mood); return new Entry(id, name, source, prompt, checksum, format, created, svg, next, true); }
        public Entry withoutMoods() { return new Entry(id, name, source, prompt, checksum, format, created, svg, Set.of(), false); }
        public Entry useMoods(boolean value) { return new Entry(id, name, source, prompt, checksum, format, created, svg, moods, value && !moods.isEmpty()); }
        public Entry named(String value) { return new Entry(id, value, source, prompt, checksum, format, created, svg, moods, useMoods); }
        public Entry described(String value) { return new Entry(id, name, source, value, checksum, format, created, svg, moods, useMoods); }
        /** GPT-made pictures already came from OpenAI, so sending them back as a reference reveals nothing new. */
        public boolean madeByGpt() { return source.equals("gpt") || source.equals("svg") || source.equals("gif"); }
        JSONObject json() {
            JSONArray m = arr(); for (String mood : moods) m.put(mood);
            return obj("id", id, "name", name, "source", source, "prompt", prompt, "checksum", checksum, "format", format, "created", created, "svg", svg, "moods", m, "useMoods", useMoods);
        }
        static Entry parse(JSONObject o) {
            if (o == null) throw new IllegalArgumentException("보관함 항목이 비어 있어요.");
            Set<String> moods = new LinkedHashSet<>(); JSONArray m = o.optJSONArray("moods");
            for (int i = 0; m != null && i < m.length(); i++) moods.add(m.optString(i));
            return new Entry(o.optString("id"), o.optString("name"), o.optString("source"), o.optString("prompt"), o.optString("checksum"), o.optString("format"),
                o.optLong("created"), o.optBoolean("svg"), moods, o.optBoolean("useMoods"));
        }
    }

    /** A new entry for a picture the app has just checked. */
    public static Entry entry(String id, String name, String source, String prompt, String checksum, String format, long created, boolean svg) {
        return new Entry(id, name, source, prompt, checksum, format == null ? "" : format.toLowerCase(Locale.ROOT), created, svg, Set.of(), false);
    }
    /** A fresh id: the time in base 36 and some randomness, lower case letters and digits only. */
    public static String id(long now, long random) {
        String r = Long.toString(random & 0xffffffffffL, 36);
        return (Long.toString(Math.max(0, now), 36) + "0000".substring(Math.min(4, r.length())) + r).toLowerCase(Locale.ROOT);
    }

    public ProfileShelf() {}
    /** The stored index; anything unreadable is refused (the app then starts an empty library and never overwrites files it cannot read). */
    public static ProfileShelf parse(String json) {
        JSONObject o;
        try { o = new JSONObject(json); } catch (Exception e) { throw new IllegalArgumentException("보관함 목록을 읽지 못했어요."); }
        if (o.optInt("version") != VERSION) throw new IllegalArgumentException("보관함 목록 버전이 달라요.");
        ProfileShelf shelf = new ProfileShelf(); JSONArray items = o.optJSONArray("items");
        Set<String> ids = new LinkedHashSet<>();
        for (int i = 0; items != null && i < items.length() && shelf.entries.size() < LIMIT; i++) {
            Entry e = Entry.parse(items.optJSONObject(i));
            if (ids.add(e.id)) shelf.entries.add(e);
        }
        return shelf;
    }
    public String json() {
        JSONArray items = arr(); for (Entry e : entries) items.put(e.json());
        return obj("version", VERSION, "items", items).toString();
    }

    public List<Entry> entries() { return Collections.unmodifiableList(entries); }
    public Entry find(String id) { for (Entry e : entries) if (e.id.equals(id)) return e; return null; }
    public Entry byChecksum(String checksum) { for (Entry e : entries) if (e.checksum.equals(checksum)) return e; return null; }

    /** What add() did: the kept entry (an existing one when the same picture was already kept) and entries pushed out. */
    public record Added(Entry entry, List<Entry> dropped) {}
    /**
     * Puts a picture first. The same picture again (same checksum) moves its existing entry up instead, keeping its GIFs.
     * Past LIMIT the oldest go, but never the one whose checksum is keep (the profile shown now).
     */
    public Added add(Entry e, String keep) {
        Entry same = byChecksum(e.checksum), kept = e;
        if (same != null) {
            entries.remove(same);
            kept = same.prompt.isEmpty() && !e.prompt.isEmpty() ? same.described(e.prompt) : same;
            if (!same.svg && e.svg) kept = new Entry(kept.id, kept.name, kept.source, kept.prompt, kept.checksum, kept.format, kept.created, true, kept.moods, kept.useMoods);
        } else if (find(e.id) != null) throw new IllegalArgumentException("보관함에 같은 이름의 항목이 있어요.");
        entries.add(0, kept);
        List<Entry> dropped = new ArrayList<>();
        for (int i = entries.size() - 1; entries.size() > LIMIT && i > 0; i--) {
            Entry old = entries.get(i);
            if (old.checksum.equals(keep)) continue;
            dropped.add(entries.remove(i));
        }
        return new Added(kept, dropped);
    }
    public void replace(Entry e) {
        for (int i = 0; i < entries.size(); i++) if (entries.get(i).id.equals(e.id)) { entries.set(i, e); return; }
        throw new IllegalArgumentException("보관함에서 그림을 찾지 못했어요.");
    }
    public Entry remove(String id) { Entry e = find(id); if (e != null) entries.remove(e); return e; }

    private static String clip(String value, int limit, String fallback) {
        String text = value == null ? "" : value.replaceAll("\\s+", " ").strip();
        text = text.codePoints().filter(c -> !Character.isISOControl(c)).collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
        if (text.isEmpty()) return fallback;
        if (text.length() > limit) text = text.substring(0, Character.isHighSurrogate(text.charAt(limit - 1)) ? limit - 1 : limit);
        return text;
    }
}
