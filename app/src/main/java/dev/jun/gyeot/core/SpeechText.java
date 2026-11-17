package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An answer as sentences to read aloud, Korean or English: Markdown marks, code, tables, visuals and bare web addresses
 * are not read out as written, and every piece is short enough for a voice to start quickly. The engine choice lives here
 * too, so the rule is host-tested.
 */
public final class SpeechText {
    /** The longest sentence handed to a voice; a longer one is cut at a comma or a space. */
    public static final int MAX = 220;
    /** A piece shorter than this ("1.", "네.", "Mr.") joins the next one on its line, so speech does not stutter. */
    static final int MIN = 4;
    /** What is said in place of a fenced code block, a visual (picture, chart, page), a table and a web address. */
    public static final String CODE = "코드는 화면에서 볼 수 있어요.", VISUAL = "그림은 화면에서 볼 수 있어요.", TABLE = "표는 화면에서 볼 수 있어요.", LINK = "링크";
    /** What is said in place of a main-chat calendar card (CalendarCards). */
    public static final String EVENT = "일정 카드는 화면에서 볼 수 있어요.";
    public static final String GEMINI = "gemini", DEVICE = "device";
    private static final Pattern LINKED = Pattern.compile("!?\\[([^\\]]*)\\]\\(\\s*([^)\\s]*)(?:\\s+\"[^\"]*\")?\\s*\\)");
    /** An inline citation as xAI writes it into Grok's answer, [[1]](https://…), with the space before it: not said at all. */
    private static final Pattern CITE = Pattern.compile("\\s*\\[\\[\\d{1,3}\\]\\]\\(\\s*https?://[^)\\s]*\\s*\\)", Pattern.CASE_INSENSITIVE);
    private static final Pattern AUTOLINK = Pattern.compile("<(?:https?://|www\\.)[^>\\s]*>", Pattern.CASE_INSENSITIVE);
    /** ASCII only, so Korean right after an address ("https://a.kr에서") stays as words. */
    private static final Pattern URL = Pattern.compile("(?:https?://|www\\.)[\\x21-\\x7E&&[^<>\"]]*[\\x21-\\x7E&&[^<>\".,;:!?)\\]]]", Pattern.CASE_INSENSITIVE);
    private static final Pattern TAG = Pattern.compile("</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?/?>");
    private static final Pattern MARKER = Pattern.compile("^\\s*(?:[•◦▪]|[-*+](?=\\s))\\s*");
    /** A sentence ends at . ? ! … before a space or the end, or at a full-width 。！？ anywhere; closing quotes stay with it. */
    private static final Pattern END = Pattern.compile("(?:[.?!…]+[\"'”’)\\]」』》〉]*(?=\\s|$)|[。！？]+[\"'”’)\\]」』》〉]*)");
    /** A line that already ends like a sentence or a clause; any other line gets a period, so the voice pauses after it. */
    private static final Pattern PAUSE = Pattern.compile("[.?!…。！？:;,，、][\"'”’)\\]」』》〉]*$");
    private SpeechText() {}

    /** Gemini speech needs an API key and a validated network; otherwise the device's own voice, which is free and local. */
    public static String engine(boolean key, boolean online) { return key && online ? GEMINI : DEVICE; }

    /**
     * The text as it should sound: code, visuals and tables become one short line each, links keep their words. The pieces
     * are RichBlocks.split's, so what is read as prose is exactly what the screen draws as prose.
     */
    public static String speakable(String markdown) { return speakable(markdown, false); }
    /** As speakable; asMarkdown reads the prose as Markdown even when this text alone does not look like it (SpeechStream). */
    static String speakable(String markdown, boolean asMarkdown) {
        if (markdown == null || markdown.isBlank()) return "";
        String plain = Markdown.plain(staged(markdown), asMarkdown);
        StringBuilder spoken = new StringBuilder();
        for (String line : plain.split("\n", -1)) {
            String kept = MARKER.matcher(line).replaceFirst("").replace("**", "").replace("__", "").replace("~~", "").replace("`", "")
                .replaceAll("^\\s*#{1,6}\\s+", "").replaceAll("^\\s*>\\s?", "").replaceAll("\\s{2,}", " ").strip();
            if (kept.matches("[-*_=·\\s]*")) kept = "";
            spoken.append(kept).append('\n');
        }
        return spoken.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
    /** The text before Markdown.plain: prose lines with links said as words, and one notice line per code, visual or table run. */
    static String staged(String markdown) {
        StringBuilder out = new StringBuilder(); String last = "";
        for (RichBlocks.Block block : RichBlocks.split(markdown)) {
            switch (block.kind()) {
                case MARKDOWN -> {
                    for (String line : block.text().replace('\t', ' ').split("\n", -1)) out.append(line.isBlank() ? "" : inline(line)).append('\n');
                    last = "";
                }
                case TABLE -> last = note(out, last, TABLE);
                // The next requests an answer suggests are buttons on the screen, never said: closed, cut off or still arriving.
                case SUGGESTIONS -> { }
                case CODE -> { if (!block.lang().equals(RichBlocks.SUGGEST)) last = note(out, last, CODE); }
                case EVENT -> last = note(out, last, EVENT);
                default -> last = note(out, last, VISUAL);
            }
        }
        return out.toString();
    }

    /** The sentences to say, in order: each line splits at its sentence ends, short pieces join the next, long ones are cut. */
    public static List<String> sentences(String markdown) { return sentences(markdown, false); }
    /** As sentences; asMarkdown as in speakable. */
    static List<String> sentences(String markdown, boolean asMarkdown) {
        List<String> out = new ArrayList<>();
        for (String line : speakable(markdown, asMarkdown).split("\n")) {
            if (line.isBlank()) continue;
            if (!PAUSE.matcher(line).find()) line += ".";
            List<String> pieces = new ArrayList<>();
            Matcher end = END.matcher(line); int from = 0;
            while (end.find()) { pieces.add(line.substring(from, end.end())); from = end.end(); }
            if (from < line.length()) pieces.add(line.substring(from));
            StringBuilder held = new StringBuilder();
            for (String piece : pieces) {
                String part = piece.strip(); if (part.isEmpty()) continue;
                held.append(held.length() == 0 ? "" : " ").append(part);
                if (held.length() >= MIN) { cap(out, held.toString()); held.setLength(0); }
            }
            if (held.length() > 0) cap(out, held.toString());
        }
        return out;
    }

    /** Links keep their words and lose their address; a bare address is said as 링크; citations and inline HTML tags go. */
    private static String inline(String line) {
        StringBuilder staged = new StringBuilder();
        Matcher link = LINKED.matcher(CITE.matcher(line).replaceAll(""));
        while (link.find()) {
            String words = link.group(1).strip();
            boolean image = link.group().startsWith("!");
            String said = words.isEmpty() || URL.matcher(words).matches() ? (image ? "" : LINK) : words;
            link.appendReplacement(staged, Matcher.quoteReplacement(said));
        }
        link.appendTail(staged);
        String text = AUTOLINK.matcher(staged.toString()).replaceAll(LINK);
        text = URL.matcher(text).replaceAll(LINK);
        return TAG.matcher(text).replaceAll(" ");
    }
    /** A placeholder line, once for a run of the same kind (three code blocks in a row are one notice). */
    private static String note(StringBuilder out, String last, String note) {
        if (!note.equals(last)) out.append('\n').append(note).append('\n');
        return note;
    }
    /** Cuts a sentence longer than MAX at its last comma (or else space) in the second half of the window. */
    private static void cap(List<String> out, String sentence) {
        String rest = sentence.strip();
        while (rest.length() > MAX) {
            int cut = -1;
            for (String mark : new String[]{", ", "，", "、", "; ", ": "}) cut = Math.max(cut, rest.lastIndexOf(mark, MAX - mark.length()) + mark.length() - 1);
            if (cut < MAX / 2) cut = rest.lastIndexOf(' ', MAX);
            if (cut < MAX / 2) cut = MAX - 1;
            out.add(rest.substring(0, cut + 1).strip());
            rest = rest.substring(cut + 1).strip();
        }
        if (!rest.isEmpty()) out.add(rest);
    }
}
