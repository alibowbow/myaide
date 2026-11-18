package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * One search over everything the app keeps as text: the main chat, work rooms, pages, memories, notes, voice records,
 * recordings and fetched texts. Every word of the query must appear (anywhere, in any letter case); a result carries a
 * short piece of the text around the first word found.
 */
public final class GlobalSearch {
    private GlobalSearch() {}
    public static final String ALL = "all", CHAT = "chat", WORK = "work", PAGE = "page", MEMORY = "memory", NOTE = "note", VOICE = "voice", RECORDING = "recording", KEPT = "kept";
    public static final String[] KINDS = {ALL, CHAT, WORK, PAGE, MEMORY, NOTE, VOICE, RECORDING, KEPT};
    public static final String[] LABELS = {"전체", "메인 대화", "작업창", "페이지", "기억", "메모", "음성 기록", "녹음", "가져온 글"};
    /** Words past this many are left out; a shorter query than LEAST letters searches nothing. */
    public static final int WORDS = 8, LEAST = 2, SNIPPET = 90;

    /** One thing found. id is what its own store knows it by; inTitle says the words were all in the title. */
    public record Hit(String kind, String id, String title, String snippet, long time, boolean inTitle) {}

    public static String label(String kind) { for (int i = 0; i < KINDS.length; i++) if (KINDS[i].equals(kind)) return LABELS[i]; return ""; }

    /** The query as lower-case words; empty when there is too little to search for. */
    public static List<String> words(String query) {
        List<String> out = new ArrayList<>(); String clean = query == null ? "" : query.strip().toLowerCase(Locale.ROOT);
        if (clean.codePointCount(0, clean.length()) < LEAST) return out;
        for (String word : clean.split("\\s+")) if (!word.isEmpty() && !out.contains(word) && out.size() < WORDS) out.add(word);
        return out;
    }

    /** True when every word is somewhere in the texts taken together. */
    public static boolean matches(List<String> words, String... texts) {
        if (words == null || words.isEmpty()) return false;
        StringBuilder all = new StringBuilder();
        for (String text : texts) if (text != null) all.append(text).append('\n');
        String haystack = all.toString().toLowerCase(Locale.ROOT);
        for (String word : words) if (!haystack.contains(word)) return false;
        return true;
    }

    /** About width characters of text on one line, around the first word found; the start of the text when none is. */
    public static String snippet(String text, List<String> words, int width) {
        String flat = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        if (flat.isEmpty()) return "";
        String lower = flat.toLowerCase(Locale.ROOT); int found = -1;
        // Lower-casing can change a string's length in a few scripts; then positions do not line up and the start is shown.
        if (lower.length() == flat.length() && words != null) for (String word : words) { int at = lower.indexOf(word); if (at >= 0 && (found < 0 || at < found)) found = at; }
        int size = Math.max(20, width), from = found < 0 ? 0 : Math.max(0, found - size / 3), to = Math.min(flat.length(), from + size);
        if (to == flat.length()) from = Math.max(0, to - size);
        if (from > 0 && Character.isLowSurrogate(flat.charAt(from))) from++;
        if (to < flat.length() && Character.isLowSurrogate(flat.charAt(to))) to--;
        return (from > 0 ? "…" : "") + flat.substring(from, to).strip() + (to < flat.length() ? "…" : "");
    }

    /** A result for one stored thing, or null when the words are not all in its title and body. */
    public static Hit hit(String kind, String id, String title, String body, long time, List<String> words) {
        String name = title == null ? "" : title.replaceAll("\\s+", " ").strip();
        if (!matches(words, name, body)) return null;
        boolean inTitle = matches(words, name);
        String piece = snippet(body, words, SNIPPET);
        return new Hit(kind, id == null ? "" : id, name.isEmpty() ? label(kind) : name, piece.equals(name) ? "" : piece, time, inTitle);
    }

    /** Title matches first, then the newest; at most limit of them. */
    public static List<Hit> ranked(List<Hit> hits, int limit) {
        List<Hit> out = new ArrayList<>(hits);
        out.sort((a, b) -> a.inTitle() != b.inTitle() ? (a.inTitle() ? -1 : 1) : Long.compare(b.time(), a.time()));
        return out.size() > limit ? new ArrayList<>(out.subList(0, Math.max(0, limit))) : out;
    }

    /** How many were found of each kind, in the order of KINDS: "작업창 3 · 페이지 1". Empty when nothing was. */
    public static String counts(List<Hit> hits) {
        Map<String, Integer> count = new LinkedHashMap<>();
        for (String kind : KINDS) count.put(kind, 0);
        for (Hit hit : hits) count.merge(hit.kind(), 1, Integer::sum);
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, Integer> entry : count.entrySet()) if (entry.getValue() > 0 && !label(entry.getKey()).isEmpty()) out.append(out.length() == 0 ? "" : " · ").append(label(entry.getKey())).append(' ').append(entry.getValue());
        return out.toString();
    }

    /** A fetched text's own title (its "제목: " line), or "" when it has none. */
    public static String keptTitle(String text) {
        String head = keptHead(text);
        for (String line : head.split("\n")) if (line.startsWith("제목: ")) return line.substring(4).strip();
        return "";
    }
    /** A fetched text without the lines the app wrote above it. */
    public static String keptBody(String text) { String head = keptHead(text); return text == null ? "" : text.substring(head.length()).strip(); }
    private static String keptHead(String text) {
        if (text == null || !text.startsWith("[")) return "";
        int end = text.indexOf("\n\n");
        return end < 0 ? "" : text.substring(0, end + 2);
    }

    /** The hits of one kind, in their order; all of them for ALL. */
    public static List<Hit> of(List<Hit> hits, String kind) {
        if (ALL.equals(kind)) return hits;
        List<Hit> out = new ArrayList<>();
        for (Hit hit : hits) if (hit.kind().equals(kind)) out.add(hit);
        return out;
    }
}
