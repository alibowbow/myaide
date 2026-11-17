package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A chat answer cut into the pieces a chat screen draws differently: Markdown prose, fenced code, tables, visuals the
 * model wrote as HTML, SVG or Mermaid, and the next requests it suggests at its very end. Visual pages are built here
 * too, with a strict content policy: inline code plus scripts and styles from a few public CDNs, no other network, no
 * forms, frames or navigation, so a page can draw a chart but cannot send the conversation anywhere. The Android side
 * enforces the same allowlist on every request. FORMAT tells the models all of this, the same in the main chat and 작업창.
 */
public final class RichBlocks {
    public enum Kind { MARKDOWN, CODE, TABLE, HTML, SVG, MERMAID, PICTURE, IMAGE, EVENT, SUGGESTIONS }

    /**
     * One piece. lang is the fence's language tag (lowercase); rows are a table's cells, header first, as raw inline
     * Markdown, or for SUGGESTIONS one row holding the suggested requests as plain text.
     */
    public record Block(Kind kind, String lang, String text, List<List<String>> rows) {
        public boolean visual() { return kind == Kind.HTML || kind == Kind.SVG || kind == Kind.MERMAID; }
        /** The requests a SUGGESTIONS block offers, cleaned: at most SUGGESTION_LIMIT, each at most SUGGESTION_CHARS long. */
        public List<String> suggestions() { return kind == Kind.SUGGESTIONS && !rows.isEmpty() ? rows.get(0) : List.of(); }
        /** A short name for the block's header ("HTML", "SVG", "다이어그램", or the code language). */
        public String label() {
            return switch (kind) {
                case HTML -> "HTML"; case SVG -> "SVG"; case MERMAID -> "다이어그램"; case TABLE -> "표"; case PICTURE -> "그림"; case IMAGE -> "그림 요청"; case EVENT -> "일정"; case SUGGESTIONS -> "다음 요청";
                case CODE -> lang.isBlank() ? "코드" : lang; default -> "";
            };
        }
    }

    /** Script and style sources a visual may load; every other request is refused. */
    public static final List<String> SCRIPT_SOURCES = List.of("https://cdn.jsdelivr.net/npm/", "https://cdnjs.cloudflare.com/ajax/libs/", "https://unpkg.com/");
    public static final List<String> FONT_SOURCES = List.of("https://fonts.googleapis.com/", "https://fonts.gstatic.com/");
    /** Mermaid diagrams load this pinned build; offline, the diagram's source stays readable instead. */
    public static final String MERMAID = "https://cdn.jsdelivr.net/npm/mermaid@11.4.1/dist/mermaid.min.js";
    public static final String POLICY = "default-src 'none'; "
        + "script-src 'unsafe-inline' 'unsafe-eval' " + String.join(" ", SCRIPT_SOURCES) + "; "
        + "style-src 'unsafe-inline' " + String.join(" ", SCRIPT_SOURCES) + " https://fonts.googleapis.com/; "
        + "font-src data: https://fonts.gstatic.com/ " + String.join(" ", SCRIPT_SOURCES) + "; "
        + "img-src data: blob:; media-src data: blob:; connect-src 'none'; frame-src 'none'; worker-src blob:; "
        + "form-action 'none'; base-uri 'none'";

    /** The fence tag of the next requests an answer suggests, how many are shown and how long one may be (code points). */
    public static final String SUGGEST = "suggestions";
    public static final int SUGGESTION_LIMIT = 3, SUGGESTION_CHARS = 80;
    /**
     * What every model that answers on a chat screen is told about it, the same bytes in the main chat (MainChatEngine)
     * and 작업창 (WorkBook.instructions), so each cached prefix stays stable: the screen draws Markdown, runs visual blocks
     * in the sandbox above and shows a closing suggestions block as buttons. The limits stated are the ones this class and
     * the WebView enforce.
     */
    public static final String FORMAT = "\n\nFormatting: the chat screen renders Markdown. Write the way a good chat answer reads: short paragraphs, **bold** for key terms, ## or ### headings only for longer answers, > for a quotation, and fenced code blocks with a language tag for code."
        + " Choose the form that fits the content, only where it helps the reader: a Markdown table for a comparison across the same attributes; a numbered list for steps or an order, bullets for parallel points; several things being introduced (options, places, products, files) as a list of **name**: one-line description. Never wrap a whole answer in a code block, never add structure for its own sake, and keep simple replies as plain sentences."
        + " Visuals: when a chart or a diagram explains better than words, add one fenced block and keep the explanation in Markdown around it: ```mermaid for a flow, a process, a sequence, a timeline, a mind map or a Gantt chart; a chart for numbers that change or compare (a trend, shares, a ranking), as ```html with a chart library or as ```svg; ```svg for any self-contained SVG; ```html for a self-contained HTML page with inline <style> and <script> (comparison cards, a small interactive demo). The app runs these inline in a sandbox: scripts and styles may load only from https://cdn.jsdelivr.net/npm/, https://cdnjs.cloudflare.com/ajax/libs/ or https://unpkg.com/ with a pinned version (for example Chart.js, D3, ECharts), fonts from Google Fonts; there is no other network, fetch/XHR, external images, forms, storage, popups or alerts, so embed data in the page and use only data: URI or inline SVG images."
        + " Design visuals for a phone: width 100% (about 360px), height under 560px, no fixed widths, readable in light and dark mode; the page provides CSS variables --bg, --text, --muted, --accent, --fill and --line in the app's colors, so prefer them (or a transparent background) over hard-coded colors. Use a visual only when it adds something, at most a few per answer, and never claim that a chart shows data you did not put in it."
        + " Next requests: only when the user is likely to go on from this answer, you may end it with one fenced ```" + SUGGEST + " block as its very last lines: up to " + SUGGESTION_LIMIT + " lines, each a request the user might send next, worded as the user would say it, in the user's language, plain text of at most " + SUGGESTION_CHARS + " characters. The app shows them as buttons that put the words in the message box and never sends them. Leave the block out of simple answers and do not repeat it in the prose.";

    private static final Pattern FENCE = Pattern.compile("^ {0,3}(`{3,}|~{3,})\\s*([^`\\s]*)[^`]*$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern CELL_SPLIT = Pattern.compile("(?<!\\\\)\\|");
    private static final Pattern DOCUMENT_START = Pattern.compile("(?i)^\\s*(<!doctype\\s+html|<html[\\s>])");
    private static final Pattern SVG_START = Pattern.compile("(?i)^\\s*<svg[\\s>]");
    private static final Pattern DOCTYPE = Pattern.compile("(?i)^\\s*<!doctype[^>]*>");
    private static final Pattern FULL_DOCUMENT = Pattern.compile("(?i)<!doctype\\s+html|<html[\\s>]|<head[\\s>]|<body[\\s>]");
    private static final Pattern VIEWPORT = Pattern.compile("(?i)<meta[^<>]+name\\s*=\\s*[\"']?viewport");
    private RichBlocks() {}

    /**
     * The answer's pieces in order. Blank prose between blocks is dropped; an unclosed fence runs to the end. A suggestions
     * fence is SUGGESTIONS only as the answer's last piece, once it has closed (still open there, it is no piece at all);
     * anywhere else it stays code.
     */
    public static List<Block> split(String text) { return split(text, new boolean[1], new int[3]); }

    /**
     * An answer still being written: its pieces, whether the last one is a fence or a page whose end has not arrived, and
     * whether a suggestions fence is still arriving after them (no piece, so nothing of it is drawn or said meanwhile).
     */
    public record Partial(List<Block> blocks, boolean open, boolean suggesting) {}
    /**
     * As split, for an answer that is still arriving (or was cut off): open says the last piece is a fence or a bare HTML
     * document still waiting for its closing line, so the screen does not run a page or a diagram that is half written.
     */
    public static Partial partial(String text) { boolean[] open = new boolean[1]; int[] tail = new int[3]; List<Block> blocks = split(text, open, tail); return new Partial(blocks, open[0], tail[2] == 1); }

    /**
     * The answer without the suggestions block that closes it: what is copied, shared, kept as a note and shown as a
     * document. The buttons are an offer on the screen, not part of what the answer says. Any other text is returned as it is.
     */
    public static String withoutSuggestions(String text) {
        if (text == null) return "";
        if (!text.contains(SUGGEST)) return text;
        int[] fence = new int[3];
        split(text, new boolean[1], fence);
        return fence[1] == 0 ? text : join(lines(text), 0, fence[0] - 1).stripTrailing();
    }
    /**
     * The message box after a suggested request is tapped: the request alone in an empty box, after what is already typed
     * otherwise (the user's own words are never replaced), and unchanged when it already ends with it (a second tap).
     */
    public static String drafted(String draft, String ask) {
        String typed = draft == null ? "" : draft, words = ask == null ? "" : ask.strip();
        if (words.isEmpty() || typed.stripTrailing().endsWith(words)) return typed;
        return typed.isBlank() ? words : typed.stripTrailing() + " " + words;
    }
    /** An open fence whose tag is still arriving and may yet read suggestions ("```sugg"): not drawn as code meanwhile. */
    public static boolean suggesting(Block block) {
        return block.kind() == Kind.CODE && !block.lang().isEmpty() && block.text().isBlank() && SUGGEST.startsWith(block.lang());
    }
    private static final Pattern LIST_MARK = Pattern.compile("^\\s*(?:[-*+\u2022\u25E6\u25AA\u00B7]|\\d{1,2}[.)])\\s+");
    /** A suggestions block's lines as plain requests: Markdown marks, list marks, quotes and invisible characters gone. */
    static List<String> suggestionLines(String body) {
        List<String> out = new ArrayList<>();
        for (String raw : body.split("\n")) {
            StringBuilder seen = new StringBuilder();
            // Control and format characters (a bidi override, a zero-width space) never reach a button or the message box.
            raw.codePoints().forEach(c -> {
                int type = Character.getType(c);
                if (Character.isWhitespace(c) || type == Character.SPACE_SEPARATOR) seen.append(' ');
                else if (type != Character.CONTROL && type != Character.FORMAT && type != Character.PRIVATE_USE && type != Character.SURROGATE && type != Character.UNASSIGNED
                    && type != Character.LINE_SEPARATOR && type != Character.PARAGRAPH_SEPARATOR) seen.appendCodePoint(c);
            });
            String line = LIST_MARK.matcher(Markdown.plain(seen.toString(), true)).replaceFirst("").replaceAll(" {2,}", " ").strip();
            if (line.length() >= 2 && "\"'\u201C\u2018\u300C".indexOf(line.charAt(0)) >= 0 && "\"'\u201D\u2019\u300D".indexOf(line.charAt(line.length() - 1)) >= 0) line = line.substring(1, line.length() - 1).strip();
            if (line.isEmpty() || line.codePointCount(0, line.length()) > SUGGESTION_CHARS || out.contains(line)) continue;
            out.add(line);
            if (out.size() == SUGGESTION_LIMIT) break;
        }
        return out;
    }

    /**
     * Where an answer still being written can be cut so that nothing arriving later changes how the part before it is
     * drawn: the furthest line start at or after from that follows a closed fence, page, SVG or table, or a blank line
     * outside fences and code when the line after it starts afresh (no indent and no list mark, so no list carries on
     * over it). split of the part before and split of the rest then hold the same pieces as split of the whole, but for
     * prose the cut parts in two, and Markdown.blocks reads the two parts of that prose as it reads them joined. from is
     * 0 or what this returned for an earlier, shorter text of the same answer; it is returned when the text settled no
     * further. Text with a carriage return never settles.
     */
    public static int settled(String text, int from) {
        if (text == null || from < 0 || from > text.length()) return 0;
        if (text.indexOf('\r', from) >= 0) return from;
        int point = from, at = from; boolean gap = false, code = false;
        while (true) {
            int end = text.indexOf('\n', at);
            String line = end < 0 ? text.substring(at) : text.substring(at, end);
            if (at > point && gap && !code && afresh(line, end >= 0)) point = at;
            if (end < 0) return point;
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                // Settled only once its closing line has arrived whole; until then the rest of the text is its body.
                String mark = fence.group(1); int after = closingFence(text, end + 1, mark.charAt(0), mark.length());
                if (after < 0) return point;
                // Suggestions are buttons only as the answer's last piece: whether more follows is known only at the end.
                if (fence.group(2).toLowerCase(Locale.ROOT).equals(SUGGEST)) return point;
                point = at = after; gap = code = false; continue;
            }
            boolean page = DOCUMENT_START.matcher(line).find();
            if (page || SVG_START.matcher(line).find()) {
                // A page runs to its </html>, an SVG to its </svg>: without one, a later line may still close it.
                int after = closingLine(text, at, page ? "</html>" : "</svg>");
                if (after < 0) return point;
                point = at = after; gap = code = false; continue;
            }
            if (line.contains("|")) {
                int under = text.indexOf('\n', end + 1);
                if (under < 0) return point;
                if (TABLE_RULE.matcher(text.substring(end + 1, under)).matches()) {
                    // Rows run while lines hold a bar; the table is closed by a whole line that does not.
                    int row = under + 1;
                    while (true) { int r = text.indexOf('\n', row); if (r < 0) return point; String s = text.substring(row, r); if (!s.contains("|") || s.isBlank()) break; row = r + 1; }
                    point = at = row; gap = code = false; continue;
                }
            }
            // Markdown's own code (an indented or odd fence inside the prose) keeps its blank lines.
            if (line.strip().startsWith("```")) code = !code;
            gap = !code && line.isBlank();
            at = end + 1;
        }
    }
    /** After a blank line, a line that no list above it can carry on: neither indented nor a list item (or, still arriving, not yet able to become one). */
    private static boolean afresh(String line, boolean whole) {
        if (line.isEmpty() || Character.isWhitespace(line.charAt(0))) return false;
        char first = line.charAt(0);
        return whole ? !Markdown.item(line) : first != '-' && first != '*' && first != '+' && !Character.isDigit(first);
    }
    /** The start of the line after the whole line that closes a fence of this mark, searching from from; -1 before one arrives. */
    private static int closingFence(String text, int from, char kind, int length) {
        for (int at = from, end; (end = text.indexOf('\n', at)) >= 0; at = end + 1) {
            String line = text.substring(at, end), s = line.strip();
            if (s.length() >= length && s.chars().allMatch(c -> c == kind) && line.length() - line.stripLeading().length() <= 3) return end + 1;
        }
        return -1;
    }
    /** The start of the line after the first whole line from from on that holds tag (any case); -1 before one arrives. */
    private static int closingLine(String text, int from, String tag) {
        for (int at = from, end; (end = text.indexOf('\n', at)) >= 0; at = end + 1) if (text.substring(at, end).toLowerCase(Locale.ROOT).contains(tag)) return end + 1;
        return -1;
    }

    private static String[] lines(String text) { return (text == null ? "" : text.replace("\r\n", "\n").replace('\r', '\n')).split("\n", -1); }
    /**
     * As split; open says the last fence or page has not closed. tail[0] is the line the last fence opened on (-1 without
     * one), tail[1] is 1 when that fence is the suggestions block that ends the answer, offered or left out, and tail[2]
     * is 1 when that block has not closed yet.
     */
    private static List<Block> split(String text, boolean[] open, int[] tail) {
        List<Block> out = new ArrayList<>();
        String[] lines = lines(text);
        tail[0] = -1; tail[1] = tail[2] = 0;
        StringBuilder prose = new StringBuilder();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            Matcher fence = FENCE.matcher(line);
            if (fence.matches()) {
                String mark = fence.group(1), lang = fence.group(2).toLowerCase(Locale.ROOT);
                char kind = mark.charAt(0);
                StringBuilder body = new StringBuilder();
                int j = i + 1;
                for (; j < lines.length; j++) {
                    String s = lines[j].strip();
                    if (s.length() >= mark.length() && s.chars().allMatch(c -> c == kind) && lines[j].length() - lines[j].stripLeading().length() <= 3) break;
                    if (body.length() > 0 || j > i + 1) body.append('\n');
                    body.append(lines[j]);
                }
                open[0] = j >= lines.length;
                tail[0] = i;
                flush(out, prose);
                out.add(fenced(lang, body.toString()));
                i = j;
                continue;
            }
            if (DOCUMENT_START.matcher(line).find()) {
                int end = closing(lines, i, "</html>");
                open[0] = end < 0;
                flush(out, prose);
                out.add(new Block(Kind.HTML, "html", join(lines, i, end < 0 ? lines.length - 1 : end), List.of()));
                i = end < 0 ? lines.length : end;
                continue;
            }
            if (SVG_START.matcher(line).find()) {
                int end = closing(lines, i, "</svg>");
                if (end >= 0) {
                    flush(out, prose);
                    out.add(new Block(Kind.SVG, "svg", join(lines, i, end), List.of()));
                    i = end;
                    continue;
                }
            }
            if (line.contains("|") && i + 1 < lines.length && TABLE_RULE.matcher(lines[i + 1]).matches()) {
                List<List<String>> rows = new ArrayList<>();
                rows.add(cells(line));
                int j = i + 2;
                for (; j < lines.length && lines[j].contains("|") && !lines[j].isBlank(); j++) rows.add(cells(lines[j]));
                flush(out, prose);
                out.add(new Block(Kind.TABLE, "", join(lines, i, j - 1), rows));
                i = j - 1;
                continue;
            }
            prose.append(prose.length() > 0 ? "\n" : "").append(line);
        }
        flush(out, prose);
        // A suggestions fence that ends the answer (the last piece is the last fence): buttons once it has closed and any of
        // its lines can be offered, else nothing at all, so one still arriving or cut off never shows as code.
        Block last = out.isEmpty() ? null : out.get(out.size() - 1);
        if (last != null && last.kind() == Kind.CODE && last.lang().equals(SUGGEST) && tail[0] >= 0) {
            List<String> offered = open[0] ? List.of() : suggestionLines(last.text());
            out.remove(out.size() - 1); tail[1] = 1; tail[2] = open[0] ? 1 : 0; open[0] = false;
            if (!offered.isEmpty()) out.add(new Block(Kind.SUGGESTIONS, SUGGEST, last.text(), List.of(offered)));
        }
        return out;
    }

    /** How many visual blocks an answer holds, without keeping its pieces. */
    public static int visuals(String text) {
        int count = 0;
        for (Block block : split(text)) if (block.visual()) count++;
        return count;
    }

    /** Whether the answer needs more than one plain text view: anything but a single run of prose. */
    public static boolean structured(String text) {
        List<Block> blocks = split(text);
        return blocks.size() != 1 || blocks.get(0).kind() != Kind.MARKDOWN;
    }

    private static Block fenced(String lang, String body) {
        String head = body.stripLeading().toLowerCase(Locale.ROOT);
        if (lang.equals("html") || lang.equals("htm") || lang.equals("xhtml")) return new Block(Kind.HTML, "html", body, List.of());
        if (lang.equals("svg") || ((lang.equals("xml") || lang.isEmpty()) && head.startsWith("<svg"))) return new Block(Kind.SVG, "svg", body, List.of());
        if (lang.equals("mermaid") || lang.equals("mmd")) return new Block(Kind.MERMAID, "mermaid", body, List.of());
        // Main-chat pictures (ChatImages): the app's record of a drawn picture, and a request that was not drawn.
        if (lang.equals(ChatImages.MADE)) return new Block(Kind.PICTURE, lang, body, List.of());
        if (lang.equals(ChatImages.ASK)) return new Block(Kind.IMAGE, lang, body, List.of());
        // Main-chat calendar events (CalendarCards): the app's card, and a request the model wrote (lang tells them apart).
        // A ```calendar block is a request only with a JSON object body (one still arriving starts with "{"); any other is code.
        if (lang.equals(CalendarCards.CARD) || lang.equals(CalendarCards.ASK) && head.startsWith("{")) return new Block(Kind.EVENT, lang, body, List.of());
        if (lang.isEmpty() && DOCUMENT_START.matcher(head).find()) return new Block(Kind.HTML, "html", body, List.of());
        return new Block(Kind.CODE, lang, body, List.of());
    }
    private static void flush(List<Block> out, StringBuilder prose) {
        String text = prose.toString().strip();
        if (!text.isEmpty()) out.add(new Block(Kind.MARKDOWN, "", trimBlankEdges(prose.toString()), List.of()));
        prose.setLength(0);
    }
    /** Drops blank lines around prose but keeps the indentation of its first line (a nested list item). */
    private static String trimBlankEdges(String text) {
        String[] lines = text.split("\n", -1);
        int start = 0, end = lines.length - 1;
        while (start <= end && lines[start].isBlank()) start++;
        while (end >= start && lines[end].isBlank()) end--;
        return join(lines, start, end).stripTrailing();
    }
    private static int closing(String[] lines, int from, String tag) {
        for (int j = from; j < lines.length; j++) if (lines[j].toLowerCase(Locale.ROOT).contains(tag)) return j;
        return -1;
    }
    private static String join(String[] lines, int from, int to) {
        StringBuilder out = new StringBuilder();
        for (int j = from; j <= to && j < lines.length; j++) out.append(j > from ? "\n" : "").append(lines[j]);
        return out.toString();
    }
    /** A table row's cells; an escaped bar (\|) stays inside its cell. */
    static List<String> cells(String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|") && !trimmed.endsWith("\\|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        List<String> cells = new ArrayList<>();
        for (String cell : CELL_SPLIT.split(trimmed, -1)) cells.add(cell.strip().replace("\\|", "|"));
        return cells;
    }

    // ── Visual pages ─────────────────────────────────────────────────────

    /** The app's colors as CSS values, so visuals can match the chat in light and dark mode. */
    public record Theme(boolean dark, String bg, String text, String muted, String accent, String fill, String line) {
        public static Theme of(boolean dark, int bg, int text, int muted, int accent, int fill, int line) {
            return new Theme(dark, css(bg), css(text), css(muted), css(accent), css(fill), css(line));
        }
        private static String css(int color) { return String.format(Locale.ROOT, "#%06x", color & 0xffffff); }
    }

    /** Whether a visual page may load this address: inline data, or a script, style or font from the allowed sources. */
    public static boolean allowed(String url) {
        if (url == null) return false;
        String lower = url.toLowerCase(Locale.ROOT);
        if (lower.startsWith("data:") || lower.startsWith("blob:") || lower.equals("about:blank")) return true;
        for (String source : SCRIPT_SOURCES) if (lower.startsWith(source)) return true;
        for (String source : FONT_SOURCES) if (lower.startsWith(source)) return true;
        return false;
    }

    /** A complete page for one visual block: the content policy first, the app's colors as CSS variables, then the content. */
    public static String page(Block block, Theme theme) {
        String head = "<meta charset=\"utf-8\"><meta http-equiv=\"Content-Security-Policy\" content=\"" + POLICY + "\">" + GUARD
            + "<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\"><meta name=\"color-scheme\" content=\"" + (theme.dark() ? "dark" : "light") + "\">"
            + "<style>" + base(theme) + "</style>";
        return switch (block.kind()) {
            case SVG -> "<!doctype html><html><head>" + head + "<style>body{display:flex;justify-content:center}.svg{width:100%}.svg svg{display:block;margin:0 auto;max-width:100%;height:auto}</style></head><body><div class=\"svg\">" + block.text() + "</div></body></html>";
            case MERMAID -> "<!doctype html><html><head>" + head + "<style>.mermaid{margin:0;text-align:center;white-space:pre-wrap;font:13px/1.5 monospace;color:var(--muted)}.mermaid svg{max-width:100%;height:auto}</style></head><body>"
                + "<pre class=\"mermaid\">" + Markdown.escape(block.text()) + "</pre>"
                + "<script src=\"" + MERMAID + "\"></script>"
                + "<script>if(window.mermaid){mermaid.initialize({startOnLoad:true,securityLevel:'strict',theme:'" + (theme.dark() ? "dark" : "neutral") + "',fontFamily:'sans-serif'});}</script></body></html>";
            default -> html(block.text(), head);
        };
    }
    /** A whole HTML document gets the policy and colors at the very start of its head; a fragment is wrapped in a page. */
    private static String html(String source, String head) {
        String text = source == null ? "" : source;
        if (!FULL_DOCUMENT.matcher(text).find()) return "<!doctype html><html><head>" + head + "</head><body>" + text + "</body></html>";
        // Placed before the document's own markup, the policy applies to everything it loads; a second viewport is left out.
        if (VIEWPORT.matcher(text).find()) head = head.replace("<meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">", "");
        Matcher doctype = DOCTYPE.matcher(text);
        if (doctype.find()) return text.substring(0, doctype.end()) + head + text.substring(doctype.end());
        return "<!doctype html>" + head + text;
    }
    /**
     * Channels the content policy does not cover, closed before any page script runs: DNS prefetching and WebRTC (whose
     * STUN/TURN lookups ignore connect-src). Best effort; the Android side also refuses every request off the allowlist.
     */
    static final String GUARD = "<meta http-equiv=\"x-dns-prefetch-control\" content=\"off\">"
        + "<script>(function(){var n=['RTCPeerConnection','webkitRTCPeerConnection','RTCDataChannel','RTCIceCandidate','RTCSessionDescription'];"
        + "for(var i=0;i<n.length;i++){try{Object.defineProperty(window,n[i],{value:undefined,writable:false,configurable:false});}catch(e){}}})();</script>";
    private static String base(Theme t) {
        return ":root{--bg:" + t.bg() + ";--text:" + t.text() + ";--muted:" + t.muted() + ";--accent:" + t.accent() + ";--fill:" + t.fill() + ";--line:" + t.line() + "}"
            + "html{background:var(--bg);color:var(--text)}"
            + "body{margin:0;padding:12px;font:15px/1.6 sans-serif;word-break:keep-all;overflow-wrap:anywhere;-webkit-text-size-adjust:100%}"
            + "img,svg,canvas,video{max-width:100%}table{border-collapse:collapse}th,td{padding:6px 10px;border-bottom:1px solid var(--line);text-align:left}"
            + "a{color:var(--accent)}";
    }

    /** Script that returns the page's content height in CSS pixels (dp), also when the page fills its viewport. */
    public static final String MEASURE = "(function(){var b=document.body;if(!b)return 0;var h=0,y=window.scrollY||0;"
        + "for(var i=0;i<b.children.length;i++){var r=b.children[i].getBoundingClientRect();if(r.height>0)h=Math.max(h,r.bottom+y);}"
        + "var s=getComputedStyle(b);h+=parseFloat(s.paddingBottom||0)+parseFloat(s.marginBottom||0);"
        + "if(b.scrollHeight>window.innerHeight+1)h=Math.max(h,b.scrollHeight);return Math.ceil(h);})()";
}
