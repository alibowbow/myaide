package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A safe subset of Markdown to HTML for task reports and notes: headings, paragraphs, bold, italic, strike, inline and fenced
 * code, lists, quotes, rules, links and tables. Every character of the input is escaped first, so raw HTML in a report can
 * never become markup, and only http(s) links are kept.
 */
public final class Markdown {
    /** A heading line: its level and the rest of the line. The closing #s Markdown allows are trimmed by hand (title): a lazy group before \\s*#*\\s*$ goes cubic on a run of spaces. */
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(\\S.*|)$");
    /** A matched heading's text, without closing #s and the spaces around them. */
    private static String title(Matcher heading) { return PageBook.heading(heading.group(2)); }
    private static final Pattern RULE = Pattern.compile("^\\s*([-*_])(\\s*\\1){2,}\\s*$");
    private static final Pattern BULLET = Pattern.compile("^(\\s*)[-*+]\\s+(.*)$");
    private static final Pattern NUMBERED = Pattern.compile("^(\\s*)\\d{1,3}[.)]\\s+(.*)$");
    private static final Pattern NUMBER = Pattern.compile("^(\\s*)(\\d{1,3})[.)]\\s+(.*)$");
    private static final Pattern RAW_QUOTE = Pattern.compile("^\\s*>\\s?(.*)$");
    /** Bullets by depth, as list items nest; depth stops at MAX_DEPTH. */
    private static final String[] BULLETS = {"•", "◦", "▪"};
    public static final int MAX_DEPTH = 3;
    private static final Pattern QUOTE = Pattern.compile("^\\s*&gt;\\s?(.*)$");
    private static final Pattern TABLE_RULE = Pattern.compile("^\\s*\\|?\\s*:?-{3,}:?\\s*(\\|\\s*:?-{3,}:?\\s*)*\\|?\\s*$");
    private static final Pattern CODE = Pattern.compile("`([^`]+)`");
    private static final Pattern LINK = Pattern.compile("\\[([^\\]]+)\\]\\((https?://[^\\s)]+)\\)");
    /** An inline citation as xAI writes it into Grok's answer: [[1]](https://…). Drawn as a small [1] link, copied as [1]. */
    private static final Pattern CITE = Pattern.compile("\\[\\[(\\d{1,3})\\]\\]\\((https?://[^)\\s]+)\\)");
    private static final Pattern BARE_URL = Pattern.compile("https?://[^\\s<>\"]+[^\\s<>\".,;:!?)]");
    private static final Pattern BOLD = Pattern.compile("\\*\\*(.+?)\\*\\*|__(.+?)__");
    private static final Pattern ITALIC = Pattern.compile("(?<![*\\w])\\*(?!\\s)(.+?)(?<!\\s)\\*(?![*\\w])|(?<![_\\w])_(?!\\s)(.+?)(?<!\\s)_(?![_\\w])");
    private static final Pattern STRIKE = Pattern.compile("~~(.+?)~~");
    private Markdown() {}

    /** Whether a line is a list item (a bullet or a number), the lines blocks() carries a list on with across a blank line. */
    static boolean item(String line) { String expanded = line.replace("\t", "    "); return BULLET.matcher(expanded).matches() || NUMBERED.matcher(expanded).matches(); }

    /** Whether text uses Markdown structure worth rendering (headings, lists, emphasis, tables, code or links). */
    public static boolean looksLike(String text) {
        if (text == null) return false;
        return Pattern.compile("(?m)^(#{1,6}\\s|\\s*[-*+]\\s|\\s*\\d{1,3}[.)]\\s|>\\s?|```|\\|.*\\|)").matcher(text).find()
            || text.contains("**") || text.contains("](http") || text.contains("`");
    }

    /** HTML for the text. tables=false writes tables as plain rows, for renderers without table support (Android TextView). */
    public static String html(String markdown, boolean tables) {
        String[] lines = escape(markdown == null ? "" : markdown.replace("\r\n", "\n")).split("\n", -1);
        StringBuilder out = new StringBuilder();
        List<String> paragraph = new ArrayList<>();
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("```")) {
                flush(out, paragraph);
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].strip().startsWith("```"); i++) code.append(code.length() > 0 ? "\n" : "").append(lines[i]);
                out.append(tables ? "<pre><code>" + code + "</code></pre>" : "<p><tt>" + code.toString().replace("\n", "<br>") + "</tt></p>");
                continue;
            }
            if (line.isBlank()) { flush(out, paragraph); continue; }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) { flush(out, paragraph); int level = heading.group(1).length(); out.append("<h").append(level).append('>').append(inline(title(heading))).append("</h").append(level).append('>'); continue; }
            if (RULE.matcher(line).matches()) { flush(out, paragraph); out.append("<hr>"); continue; }
            if (line.contains("|") && i + 1 < lines.length && TABLE_RULE.matcher(lines[i + 1]).matches()) {
                flush(out, paragraph);
                List<String[]> rows = new ArrayList<>(); rows.add(cells(line));
                for (i += 2; i < lines.length && lines[i].contains("|") && !lines[i].isBlank(); i++) rows.add(cells(lines[i]));
                i--;
                table(out, rows, tables);
                continue;
            }
            if (QUOTE.matcher(line).matches()) {
                flush(out, paragraph);
                List<String> quoted = new ArrayList<>();
                for (; i < lines.length; i++) { Matcher quote = QUOTE.matcher(lines[i]); if (!quote.matches()) break; quoted.add(inline(quote.group(1))); }
                i--;
                out.append("<blockquote>").append(String.join("<br>", quoted)).append("</blockquote>");
                continue;
            }
            boolean bullet = BULLET.matcher(line).matches();
            if (bullet || NUMBERED.matcher(line).matches()) {
                flush(out, paragraph);
                List<String> items = new ArrayList<>();
                for (; i < lines.length; i++) {
                    Matcher item = (bullet ? BULLET : NUMBERED).matcher(lines[i]);
                    if (item.matches()) items.add(inline(item.group(2)));
                    else if (!lines[i].isBlank() && lines[i].startsWith("  ")) items.add(inline(lines[i].strip()));
                    else break;
                }
                i--;
                if (!bullet && !tables) {
                    // TextView draws <ol> items as bullets, so numbered lists keep their numbers as text there.
                    StringBuilder numbered = new StringBuilder();
                    for (int n = 0; n < items.size(); n++) numbered.append(n == 0 ? "" : "<br>").append(n + 1).append(". ").append(items.get(n));
                    out.append("<p>").append(numbered).append("</p>");
                    continue;
                }
                String tag = bullet ? "ul" : "ol";
                out.append('<').append(tag).append('>');
                for (String item : items) out.append("<li>").append(item).append("</li>");
                out.append("</").append(tag).append('>');
                continue;
            }
            paragraph.add(inline(line.strip()));
        }
        flush(out, paragraph);
        // TextView knows <tt> but not <code>.
        return tables ? out.toString() : out.toString().replace("<code>", "<tt>").replace("</code>", "</tt>");
    }

    /** One block for a native renderer: its kind, level (heading level or list depth), list marker and raw inline Markdown. */
    public static final class Block {
        public static final String HEADING = "heading", PARAGRAPH = "paragraph", ITEM = "item", QUOTE = "quote", CODE = "code", RULE = "rule", ROW = "row";
        public final String kind, marker, text; public final int level; public final boolean header;
        Block(String kind, int level, String marker, String text, boolean header) { this.kind = kind; this.level = level; this.marker = marker; this.text = text; this.header = header; }
        @Override public String toString() { return kind + (level > 0 ? level : "") + (marker.isEmpty() ? "" : " " + marker) + (header ? " header" : "") + ": " + text; }
    }
    /**
     * The text as blocks for a native renderer (Android TextView spans): headings, paragraphs, list items with their depth and
     * marker, quotes, code, rules and table rows. Nesting follows the indentation; numbers are kept as written and counted on
     * when a list repeats "1."; an indented or directly following line continues the item above it. Text stays raw Markdown
     * for inlineHtml.
     */
    public static List<Block> blocks(String markdown) {
        String[] lines = (markdown == null ? "" : markdown.replace("\r\n", "\n").replace("\t", "    ")).split("\n", -1);
        List<Block> out = new ArrayList<>();
        List<String> paragraph = new ArrayList<>();
        List<Integer> indents = new ArrayList<>();
        int[] counters = new int[MAX_DEPTH + 1];
        boolean inList = false, gap = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i];
            if (line.strip().startsWith("```")) {
                paragraph(out, paragraph); inList = false;
                StringBuilder code = new StringBuilder();
                for (i++; i < lines.length && !lines[i].strip().startsWith("```"); i++) code.append(code.length() > 0 ? "\n" : "").append(lines[i]);
                out.add(new Block(Block.CODE, 0, "", code.toString(), false));
                continue;
            }
            if (line.isBlank()) { paragraph(out, paragraph); gap = true; continue; }
            Matcher heading = HEADING.matcher(line);
            if (heading.matches()) { paragraph(out, paragraph); inList = false; out.add(new Block(Block.HEADING, heading.group(1).length(), "", title(heading), false)); continue; }
            if (RULE.matcher(line).matches()) { paragraph(out, paragraph); inList = false; out.add(new Block(Block.RULE, 0, "", "", false)); continue; }
            if (line.contains("|") && i + 1 < lines.length && TABLE_RULE.matcher(lines[i + 1]).matches()) {
                paragraph(out, paragraph); inList = false;
                out.add(new Block(Block.ROW, 0, "", String.join("  ·  ", rawCells(line)), true));
                for (i += 2; i < lines.length && lines[i].contains("|") && !lines[i].isBlank(); i++) out.add(new Block(Block.ROW, 0, "", String.join("  ·  ", rawCells(lines[i])), false));
                i--;
                continue;
            }
            if (RAW_QUOTE.matcher(line).matches()) {
                paragraph(out, paragraph); inList = false;
                List<String> quoted = new ArrayList<>();
                for (; i < lines.length; i++) { Matcher quote = RAW_QUOTE.matcher(lines[i]); if (!quote.matches()) break; quoted.add(quote.group(1).strip()); }
                i--;
                out.add(new Block(Block.QUOTE, 0, "", String.join("\n", quoted), false));
                continue;
            }
            Matcher bullet = BULLET.matcher(line), number = NUMBER.matcher(line);
            boolean isBullet = bullet.matches(), isNumber = !isBullet && number.matches();
            int indent = line.length() - line.stripLeading().length();
            if (isBullet || isNumber) {
                paragraph(out, paragraph);
                if (!inList) { indents.clear(); java.util.Arrays.fill(counters, 0); }
                while (!indents.isEmpty() && indents.get(indents.size() - 1) > indent) indents.remove(indents.size() - 1);
                if (indents.isEmpty() || indents.get(indents.size() - 1) < indent) indents.add(indent);
                int depth = Math.min(MAX_DEPTH, indents.size() - 1);
                for (int d = depth + 1; d < counters.length; d++) counters[d] = 0;
                String marker;
                if (isNumber) {
                    int written = Integer.parseInt(number.group(2));
                    int shown = counters[depth] == 0 || written == counters[depth] + 1 ? written : counters[depth] + 1;
                    counters[depth] = shown; marker = shown + ".";
                } else marker = BULLETS[depth % BULLETS.length];
                out.add(new Block(Block.ITEM, depth, marker, isNumber ? number.group(3) : bullet.group(2), false));
                inList = true; gap = false;
                continue;
            }
            // Under a list item: a line right below it, or an indented one after a blank line, belongs to the item.
            if (inList && (!gap || indent >= 2) && !out.isEmpty() && out.get(out.size() - 1).kind.equals(Block.ITEM)) {
                Block item = out.remove(out.size() - 1);
                out.add(new Block(Block.ITEM, item.level, item.marker, item.text + "\n" + line.strip(), false));
                gap = false;
                continue;
            }
            inList = false; gap = false;
            paragraph.add(line.strip());
        }
        paragraph(out, paragraph);
        return out;
    }
    /**
     * The text as plain text for sharing: Markdown's marks go (#, **, _, `, fences, quote marks, table bars, link brackets) and
     * the words, spaces and line breaks stay. Bullets become • (◦ when nested); numbers stay; a link keeps its address in
     * brackets, an inline citation only its number ([1]); table rows become cells joined by " · ". Text that does not look
     * like Markdown is returned as it is.
     */
    public static String plain(String markdown) { return plain(markdown, false); }
    /** As plain; asMarkdown reads the text as Markdown even when it alone does not look like it (part of a longer answer that did). */
    static String plain(String markdown, boolean asMarkdown) {
        if (markdown == null) return "";
        if (!asMarkdown && !looksLike(markdown)) return markdown;
        String[] lines = markdown.replace("\r\n", "\n").split("\n", -1);
        StringBuilder out = new StringBuilder();
        boolean code = false;
        for (int i = 0; i < lines.length; i++) {
            String line = lines[i], kept;
            if (line.strip().startsWith("```")) { code = !code; continue; }
            if (code) kept = line;
            else if (RULE.matcher(line).matches()) kept = "";
            else if (line.contains("|") && TABLE_RULE.matcher(line).matches()) continue;
            else {
                Matcher heading = HEADING.matcher(line), bullet = BULLET.matcher(line), quote = RAW_QUOTE.matcher(line);
                if (heading.matches()) kept = plainInline(title(heading));
                else if (bullet.matches()) kept = bullet.group(1) + (bullet.group(1).length() >= 2 ? "◦ " : "• ") + plainInline(bullet.group(2));
                else if (quote.matches()) kept = plainInline(quote.group(1));
                else if (line.strip().startsWith("|") && line.strip().endsWith("|") && line.strip().length() > 1) kept = String.join(" · ", plainCells(line));
                else kept = plainInline(line);
            }
            out.append(out.length() > 0 || i > 0 ? "\n" : "").append(kept);
        }
        return out.toString().replaceAll("\n{3,}", "\n\n").strip();
    }
    private static String[] plainCells(String row) {
        String[] cells = rawCells(row);
        for (int i = 0; i < cells.length; i++) cells[i] = plainInline(cells[i]);
        return cells;
    }
    /** One line's inline marks gone: code spans and links first, so what they contain stays as written. */
    private static String plainInline(String text) {
        List<String> kept = new ArrayList<>();
        StringBuilder staged = new StringBuilder();
        Matcher code = CODE.matcher(text);
        while (code.find()) code.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, code.group(1))));
        code.appendTail(staged);
        // Escaped marks (\*) are literal: set aside before emphasis is read.
        String escaped = staged.toString(); staged.setLength(0);
        Matcher escape = Pattern.compile("\\\\([\\\\`*_{}\\[\\]()#+\\-.!|>~])").matcher(escaped);
        while (escape.find()) escape.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, escape.group(1))));
        escape.appendTail(staged);
        String value = staged.toString(); staged.setLength(0);
        Matcher cite = CITE.matcher(value);
        while (cite.find()) cite.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, "[" + cite.group(1) + "]")));
        cite.appendTail(staged);
        value = staged.toString().replaceAll("!\\[([^\\]]*)\\]\\((https?://[^\\s)]+)\\)", "$1 ($2)");
        staged.setLength(0);
        Matcher link = LINK.matcher(value);
        while (link.find()) link.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, link.group(1).equals(link.group(2)) ? link.group(2) : link.group(1) + " (" + link.group(2) + ")")));
        link.appendTail(staged);
        value = staged.toString();
        value = BOLD.matcher(value).replaceAll(match -> Matcher.quoteReplacement(match.group(1) != null ? match.group(1) : match.group(2)));
        value = ITALIC.matcher(value).replaceAll(match -> Matcher.quoteReplacement(match.group(1) != null ? match.group(1) : match.group(2)));
        value = STRIKE.matcher(value).replaceAll(match -> Matcher.quoteReplacement(match.group(1)));
        for (int i = kept.size() - 1; i >= 0; i--) value = value.replace(token(i), kept.get(i));
        return value;
    }
    /** HTML for one block's inline text (bold, italic, strike, code, links; line breaks kept), for a TextView. */
    public static String inlineHtml(String raw) {
        return inline(escape(raw == null ? "" : raw)).replace("\n", "<br>").replace("<code>", "<tt>").replace("</code>", "</tt>");
    }
    /** The text with each inline citation ([[1]](https://…)) as its number alone ([1]): an answer as it is copied. */
    public static String citations(String text) { return text == null ? "" : CITE.matcher(text).replaceAll("[$1]"); }
    private static void paragraph(List<Block> out, List<String> lines) {
        if (lines.isEmpty()) return;
        out.add(new Block(Block.PARAGRAPH, 0, "", String.join("\n", lines), false));
        lines.clear();
    }
    private static String[] rawCells(String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] cells = trimmed.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) cells[i] = cells[i].strip();
        return cells;
    }

    /** A complete, self-contained document for a WebView (no scripts, no external resources); follows light or dark mode. */
    public static String document(String title, String markdown, String sourcesHtml) {
        return "<!doctype html><html lang=\"ko\"><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">"
            + "<meta name=\"color-scheme\" content=\"light dark\"><style>"
            // Warm Glass: the app's paper, ink and terracotta; coral links in the dark (links 5.4:1 light, 7.8:1 dark on the page).
            + ":root{--bg:#faf9f5;--text:#1a1915;--muted:#6b6962;--accent:#a84b2a;--fill:#eeebe3;--line:#e4e0d6;--strong:#141413}"
            + "@media (prefers-color-scheme:dark){:root{--bg:#1c1b19;--text:#f2f0ea;--muted:#a29f95;--accent:#eb9b7d;--fill:#262522;--line:#35332e;--strong:#faf9f5}}"
            + "body{margin:0;padding:8px 20px 48px;background:var(--bg);color:var(--text);font:17px/1.7 sans-serif;word-break:keep-all;overflow-wrap:anywhere;-webkit-text-size-adjust:100%}"
            + "h1,h2,h3,h4,h5,h6{line-height:1.3;margin:1.4em 0 .5em;color:var(--strong);letter-spacing:-.01em}h1{font-size:1.5em}h2{font-size:1.28em}h3{font-size:1.12em}h4,h5,h6{font-size:1em}"
            + ".title{font-size:1.75em;font-weight:800;line-height:1.25;letter-spacing:-.02em;margin:.2em 0 .9em;color:var(--strong)}"
            + "p{margin:.65em 0}ul,ol{padding-left:1.35em;margin:.5em 0}li{margin:.3em 0}"
            + "a{color:var(--accent);text-decoration:none}b,strong{color:var(--strong)}"
            + "code,tt{font-family:monospace;background:var(--fill);border-radius:7px;padding:.12em .4em;font-size:.9em}"
            + "pre{background:var(--fill);border-radius:14px;padding:14px;overflow-x:auto}pre code{background:none;padding:0}"
            + "blockquote{margin:.9em 0;padding:.3em 0 .3em 14px;border-left:3px solid var(--accent);color:var(--muted)}"
            + "hr{border:0;border-top:1px solid var(--line);margin:1.6em 0}"
            + ".table{overflow-x:auto;margin:.9em 0;border-radius:12px;border:1px solid var(--line)}table{border-collapse:collapse;width:100%;font-size:.93em}"
            + "th,td{border-bottom:1px solid var(--line);padding:9px 12px;text-align:left;vertical-align:top}th{background:var(--fill);color:var(--strong);font-weight:650}tr:last-child td{border-bottom:0}"
            + ".sources{margin-top:2.2em;padding:14px 16px;border-radius:16px;background:var(--fill);font-size:.92em}.sources p{margin:.45em 0}"
            + "</style></head><body>"
            + (title == null || title.isBlank() ? "" : "<div class=\"title\">" + escape(title) + "</div>")
            + html(markdown, true)
            + (sourcesHtml == null || sourcesHtml.isBlank() ? "" : "<div class=\"sources\"><b>출처</b>" + sourcesHtml + "</div>")
            + "</body></html>";
    }

    /** A link list for document(); only http(s) addresses become links. */
    public static String sources(org.json.JSONArray sources) {
        StringBuilder out = new StringBuilder();
        if (sources != null) for (int i = 0; i < sources.length(); i++) {
            org.json.JSONObject source = sources.optJSONObject(i);
            String uri = source == null ? "" : source.optString("uri");
            if (!uri.startsWith("https://") && !uri.startsWith("http://")) continue;
            String title = source.optString("title").isBlank() ? uri : source.optString("title");
            out.append("<p><a href=\"").append(escape(uri)).append("\">").append(escape(title)).append("</a></p>");
        }
        return out.toString();
    }

    public static String escape(String text) {
        StringBuilder out = new StringBuilder(text.length() + 16);
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&#39;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static void flush(StringBuilder out, List<String> paragraph) {
        if (paragraph.isEmpty()) return;
        out.append("<p>").append(String.join("<br>", paragraph)).append("</p>");
        paragraph.clear();
    }
    private static String[] cells(String row) {
        String trimmed = row.strip();
        if (trimmed.startsWith("|")) trimmed = trimmed.substring(1);
        if (trimmed.endsWith("|")) trimmed = trimmed.substring(0, trimmed.length() - 1);
        String[] cells = trimmed.split("\\|", -1);
        for (int i = 0; i < cells.length; i++) cells[i] = inline(cells[i].strip());
        return cells;
    }
    private static void table(StringBuilder out, List<String[]> rows, boolean tables) {
        if (!tables) {
            // TextView has no tables: the header row in bold, each row on its own line with cells separated by dots.
            for (int r = 0; r < rows.size(); r++) {
                String joined = String.join(" · ", rows.get(r));
                out.append("<p>").append(r == 0 ? "<b>" + joined + "</b>" : joined).append("</p>");
            }
            return;
        }
        out.append("<div class=\"table\"><table>");
        for (int r = 0; r < rows.size(); r++) {
            out.append("<tr>");
            for (String cell : rows.get(r)) out.append(r == 0 ? "<th>" : "<td>").append(cell).append(r == 0 ? "</th>" : "</td>");
            out.append("</tr>");
        }
        out.append("</table></div>");
    }
    /** Inline formatting on already escaped text. Code spans and links are set aside so their contents stay literal. */
    private static String inline(String text) {
        List<String> kept = new ArrayList<>();
        StringBuilder staged = new StringBuilder();
        Matcher code = CODE.matcher(text);
        while (code.find()) code.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, "<code>" + code.group(1) + "</code>")));
        code.appendTail(staged);
        String value = staged.toString();
        staged.setLength(0);
        Matcher cite = CITE.matcher(value);
        while (cite.find()) cite.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, "<small><a href=\"" + cite.group(2) + "\">[" + cite.group(1) + "]</a></small>")));
        cite.appendTail(staged);
        value = staged.toString();
        staged.setLength(0);
        Matcher link = LINK.matcher(value);
        while (link.find()) link.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, "<a href=\"" + link.group(2) + "\">" + link.group(1) + "</a>")));
        link.appendTail(staged);
        value = staged.toString();
        staged.setLength(0);
        Matcher bare = BARE_URL.matcher(value);
        while (bare.find()) bare.appendReplacement(staged, Matcher.quoteReplacement(keep(kept, "<a href=\"" + bare.group() + "\">" + bare.group() + "</a>")));
        bare.appendTail(staged);
        value = staged.toString();
        value = BOLD.matcher(value).replaceAll(match -> Matcher.quoteReplacement("<b>" + (match.group(1) != null ? match.group(1) : match.group(2)) + "</b>"));
        value = ITALIC.matcher(value).replaceAll(match -> Matcher.quoteReplacement("<i>" + (match.group(1) != null ? match.group(1) : match.group(2)) + "</i>"));
        value = STRIKE.matcher(value).replaceAll(match -> Matcher.quoteReplacement("<s>" + match.group(1) + "</s>"));
        for (int i = kept.size() - 1; i >= 0; i--) value = value.replace(token(i), kept.get(i));
        return value;
    }
    private static String keep(List<String> kept, String html) { kept.add(html); return token(kept.size() - 1); }
    /** Placeholder made of characters escape() never produces, so it cannot collide with report text. */
    private static String token(int index) { return "\u0000" + index + "\u0000"; }
}
