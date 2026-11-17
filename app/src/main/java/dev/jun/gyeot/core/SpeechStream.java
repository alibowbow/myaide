package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * An answer still streaming in, as the sentences SpeechText would say for it: each one once, in order, as soon as it is
 * complete. The sentence still growing waits, and so does anything the screen may yet draw as something else: a line that
 * may open a fence, a page or a table, lines that may be a table's header and rows, an SVG not yet closed, and a link still
 * arriving. finish() says the rest from the whole answer, so what is said is what SpeechText.sentences would say for it.
 * Only the part after the last settled prose line is parsed again on each piece, so a long answer costs about its length.
 */
public final class SpeechStream {
    /** A line starting like this may open a fence (``` ~~~), a page or an SVG (<): it waits until it is whole. */
    private static final Pattern OPENER = Pattern.compile("^\\s*[`~<]");
    private static final Pattern SVG_START = Pattern.compile("(?i)^\\s*<svg[\\s>]");
    /** RichBlocks' fence opener: the mark, then the language tag. */
    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private final StringBuilder text = new StringBuilder();
    private int said;
    /**
     * Where the part still parsed starts, and how many sentences come before it: everything before base is whole lines,
     * all said, ending in prose, that nothing later can read differently (no fence, page, SVG or table runs across it).
     */
    private int base, fixed;
    /** The settled part reads as Markdown, so the rest is read as Markdown too, as SpeechText reads the whole answer. */
    private boolean markdown;
    /** The end of the whole lines last found not ready to settle, so they are not checked again until a line is added. */
    private int tried;

    /** Adds a piece of the answer; returns the sentences that became complete with it. */
    public List<String> add(String delta) {
        if (delta != null) text.append(delta);
        String ready = settled(text.substring(base));
        List<String> all = SpeechText.sentences(ready, markdown);
        // Whole lines are final; on a line still arriving the last sentence may grow.
        List<String> out = take(all, ready.isEmpty() || ready.endsWith("\n") ? all.size() : all.size() - 1);
        settle(ready);
        return out;
    }
    /** The answer is complete: the sentences not said yet. */
    public List<String> finish() {
        List<String> all = SpeechText.sentences(text.substring(base), markdown);
        return take(all, all.size());
    }
    /** Everything added so far. */
    public String text() { return text.toString(); }
    /** How much of the answer is still parsed on each piece (host tests). */
    int unsettled() { return text.length() - base; }

    /** all is the sentences from base on; returns those before upTo not said yet. */
    private List<String> take(List<String> all, int upTo) {
        List<String> out = new ArrayList<>();
        for (int i = said - fixed; i < upTo && i < all.size(); i++) out.add(all.get(i));
        said = Math.max(said, fixed + Math.min(upTo, all.size()));
        return out;
    }
    /**
     * Moves base past the whole lines of ready (the settled text from base) once they are all said and end in prose: then
     * what follows is read the same alone as after them. settled() never ends on a whole line with a |, which a table's rule
     * may still follow, nor at or after an SVG not yet closed.
     */
    private void settle(String ready) {
        int cut = ready.lastIndexOf('\n') + 1;
        if (cut == 0 || base + cut == tried) return;
        tried = base + cut;
        String whole = ready.substring(0, cut);
        // A fence or page still open runs on; a closed code, table or picture run may get more of the same kind (one notice).
        // A suggestions fence still open is no piece at all, but its lines are not prose either: it runs on too.
        RichBlocks.Partial parts = RichBlocks.partial(whole); List<RichBlocks.Block> blocks = parts.blocks();
        if (parts.suggesting() || !blocks.isEmpty() && blocks.get(blocks.size() - 1).kind() != RichBlocks.Kind.MARKDOWN) return;
        // A prose line starting ``` switches Markdown.plain's code reading for the lines after it.
        for (String line : SpeechText.staged(whole).split("\n")) if (line.strip().startsWith("```")) return;
        // How SpeechText read ready just now: as Markdown when the part before did, or when ready itself looks like it.
        boolean reads = markdown || Markdown.looksLike(SpeechText.staged(ready));
        int count = SpeechText.sentences(whole, reads).size();
        if (said < fixed + count) return;
        base += cut; fixed += count; markdown = reads;
    }
    /** The part of the text whose sentences can be told now. */
    static String settled(String text) {
        String[] lines = text.split("\n", -1);
        int open = text.lastIndexOf('\n') + 1;
        // An SVG with no </svg> yet would read as prose (RichBlocks draws it only once closed): wait from its first line.
        // A fence with no language (or xml) is a picture when its first line is an SVG or a page: wait for that line.
        int from = 0, svg = -1, fence = -1; String mark = null, lang = ""; boolean body = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i]; boolean whole = i < lines.length - 1;
            if (mark == null) {
                if (svg < 0 && SVG_START.matcher(line).find()) svg = from;
                if (whole && svg >= 0 && line.toLowerCase(java.util.Locale.ROOT).contains("</svg>")) svg = -1;
                java.util.regex.Matcher opener = FENCE.matcher(line);
                if (whole && svg < 0 && opener.matches()) { mark = opener.group(1); lang = opener.group(2).toLowerCase(java.util.Locale.ROOT); fence = from; body = false; }
            } else if (whole) {
                if (closes(line, mark)) mark = null;
                else if (!line.isBlank()) body = true;
            }
            from += line.length() + 1;
        }
        // Whole lines with a | may be a table's header and rows (RichBlocks needs the rule under the header): wait for the next line.
        int cut = open;
        while (cut > 0) {
            int start = text.lastIndexOf('\n', cut - 2) + 1;
            if (!text.substring(start, cut - 1).contains("|")) break;
            cut = start;
        }
        if (svg >= 0) cut = Math.min(cut, svg);
        if (mark != null && !body && (lang.isEmpty() || lang.equals("xml"))) cut = Math.min(cut, fence);
        if (cut < open) return text.substring(0, cut);
        String line = text.substring(open);
        if (line.contains("|") || OPENER.matcher(line).find()) return text.substring(0, open);
        // A link still arriving: its words may hold a sentence end the finished link would not.
        int bracket = line.lastIndexOf('[');
        if (bracket >= 0 && line.indexOf(')', bracket) < 0) return text.substring(0, open + bracket);
        return text;
    }
    /** RichBlocks' fence end: a run of the opener's mark at least as long, indented at most three spaces. */
    private static boolean closes(String line, String mark) {
        String run = line.strip(); char kind = mark.charAt(0);
        return run.length() >= mark.length() && run.chars().allMatch(c -> c == kind) && line.length() - line.stripLeading().length() <= 3;
    }
}
