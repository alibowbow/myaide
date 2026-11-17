package dev.jun.gyeot.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.SecureRandom;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.LongSupplier;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 페이지: the user's own documents on this phone, Markdown or HTML, each in one category. The 작업창 and
 * the main chat save them (PageTools); the 페이지 screen lists, opens, moves, renames and deletes them.
 *
 * Kept in the app's private files, never in the 들퍼레 folder (where the app may not rename, move or overwrite a file in
 * every storage mode): one index (index.json: the categories and every page's title, category, format and dates) and one
 * body file per page, so a long document never makes the index slow or too big to read. A new text goes to a new file
 * (&lt;id&gt;-&lt;rev&gt;.md or .html) and the index then names it; the old file goes only after that, so a failed or interrupted
 * change leaves the old page whole, never half of either. Every file is written to a temporary file that is synced and then
 * moved into place in one step; files no index entry names are removed on the next load. An index that cannot be read is
 * kept as it is and every change is refused, the way AppStore treats its lists. Thread-safe; listeners hear each change
 * after it is saved.
 */
public final class PageBook {
    public static final int FORMAT = 1;
    public static final int TITLE_LIMIT = 120, CATEGORY_LIMIT = 30, BODY_LIMIT = 1_000_000, PAGE_LIMIT = 3_000, CATEGORY_COUNT = 200;
    public static final int PREVIEW = 160, TAG_LIMIT = 5, TAG_CHARS = 20, ORIGIN_CHARS = 80;
    /** The index is read whole; one larger than this is treated as damaged rather than loaded. */
    static final long INDEX_BYTES = 8L * 1024 * 1024;
    public static final String DEFAULT_CATEGORY = "기타", UNTITLED = "제목 없는 페이지";
    public static final String MARKDOWN = "markdown", HTML = "html";
    /** Where a page came from: the 작업창, the main chat, or the user's own hands on the 페이지 screen. */
    public static final String WORK = "work", CHAT = "chat", USER = "user";

    private static final Pattern ID = Pattern.compile("p[0-9a-f]{16}");
    /** A body file: the page's id, the revision of its text, its format. */
    private static final Pattern FILE = Pattern.compile("(p[0-9a-f]{16})-([1-9][0-9]{0,8})\\.(md|html)");
    private static final Pattern CONTROL = Pattern.compile("[\\p{Cntrl}\\u200B-\\u200F\\u2028\\u2029\\uFEFF]");
    private static final Pattern SPACES = Pattern.compile("\\s+");
    /** A whole document, or one that starts with a tag: stored as HTML even when the caller said markdown. */
    private static final Pattern HTML_DOCUMENT = Pattern.compile("(?is)^\\s*(?:<!doctype\\s+html|<html[\\s>]|<head[\\s>]|<body[\\s>])");
    /** A tag; [^<>] keeps each try to the next bracket, so text full of lone '<' stays linear. */
    private static final Pattern TAGS = Pattern.compile("<[^<>]*>");
    private static final Pattern ANY_TAG = Pattern.compile("</?[A-Za-z][A-Za-z0-9-]*(?:\\s[^<>]*)?/?>");
    /** How much of a text the title and preview rules read: the start of a document is enough, and a long one stays quick. */
    static final int SCAN = 200_000;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** One page as the index keeps it. preview: the first plain words of the body, for lists and search. */
    public record Page(String id, String title, String category, String format, long created, long updated, int chars,
                       String preview, String source, String origin, boolean pinned, List<String> tags, int rev) {
        public boolean html() { return HTML.equals(format); }
        public String extension() { return html() ? ".html" : ".md"; }
        /** The body file's name: a new revision for every new text. */
        String fileName() { return id + "-" + rev + extension(); }
        Page inCategory(String name) { return new Page(id, title, name, format, created, updated, chars, preview, source, origin, pinned, tags, rev); }
        Page pinned(boolean on) { return new Page(id, title, category, format, created, updated, chars, preview, source, origin, on, tags, rev); }
        JSONObject json() {
            return obj("id", id, "title", title, "category", category, "format", format, "created", created, "updated", updated,
                "chars", chars, "preview", preview, "source", source, "origin", origin, "pinned", pinned, "tags", new JSONArray(tags), "rev", rev);
        }
        static Page of(JSONObject json) {
            List<String> tags = new ArrayList<>();
            JSONArray list = json.optJSONArray("tags");
            for (int i = 0; list != null && i < list.length() && tags.size() < TAG_LIMIT; i++) { String tag = tag(list.optString(i)); if (!tag.isEmpty() && !tags.contains(tag)) tags.add(tag); }
            String format = HTML.equals(json.optString("format")) ? HTML : MARKDOWN;
            return new Page(json.optString("id"), PageBook.title(json.optString("title"), ""), PageBook.category(json.optString("category")), format,
                json.optLong("created"), json.optLong("updated"), Math.max(0, json.optInt("chars")), json.optString("preview"),
                json.optString("source", USER), clean(json.optString("origin"), ORIGIN_CHARS), json.optBoolean("pinned"), List.copyOf(tags), Math.max(1, json.optInt("rev", 1)));
        }
    }
    /** A category with how many pages it holds and when one of them last changed (0 for an empty one). */
    public record Category(String name, int count, long updated) {}

    private final File dir;
    private final LongSupplier clock;
    private final List<Runnable> listeners = new CopyOnWriteArrayList<>();
    /** The index in memory: category names in the order they were made, and page entries newest first. */
    private final List<String> categories = new ArrayList<>();
    private final List<Page> pages = new ArrayList<>();
    private boolean unreadable;
    private String problem = "";
    private long version;

    public PageBook(File dir) { this(dir, System::currentTimeMillis); }
    public PageBook(File dir, LongSupplier clock) { this.dir = dir; this.clock = clock; load(); }

    public File dir() { return dir; }
    /** Bumped on every saved change, for screens that redraw only when something changed. */
    public synchronized long version() { return version; }
    /** Why the pages could not be read ("" when they could); every change is refused while this is set. */
    public synchronized String problem() { return problem; }
    public void listen(Runnable listener) { if (listener != null) listeners.add(listener); }
    public void unlisten(Runnable listener) { listeners.remove(listener); }

    // ── Reading ─────────────────────────────────────────────────────────────────────────────────────────

    /** Every page, pinned first, then most recently changed first. */
    public synchronized List<Page> pages() {
        List<Page> out = new ArrayList<>(pages);
        out.sort(ORDER);
        return out;
    }
    /** The pages of one category, in the same order. */
    public synchronized List<Page> pages(String category) {
        String key = key(category); List<Page> out = new ArrayList<>();
        for (Page page : pages) if (key(page.category()).equals(key)) out.add(page);
        out.sort(ORDER);
        return out;
    }
    public synchronized Page page(String id) {
        for (Page page : pages) if (page.id().equals(id)) return page;
        return null;
    }
    /** The categories in Korean dictionary order (기타 last), each with its count; empty ones the user made are kept. */
    public synchronized List<Category> categories() {
        Map<String, int[]> counts = new LinkedHashMap<>(); Map<String, long[]> latest = new LinkedHashMap<>();
        for (String name : categories) { counts.put(name, new int[1]); latest.put(name, new long[1]); }
        for (Page page : pages) {
            String name = existing(page.category()); if (name == null) name = page.category();
            counts.computeIfAbsent(name, k -> new int[1])[0]++;
            long[] at = latest.computeIfAbsent(name, k -> new long[1]); at[0] = Math.max(at[0], page.updated());
        }
        List<Category> out = new ArrayList<>();
        for (Map.Entry<String, int[]> entry : counts.entrySet()) out.add(new Category(entry.getKey(), entry.getValue()[0], latest.get(entry.getKey())[0]));
        Collator korean = Collator.getInstance(Locale.KOREAN);
        out.sort((a, b) -> a.name().equals(DEFAULT_CATEGORY) != b.name().equals(DEFAULT_CATEGORY) ? (a.name().equals(DEFAULT_CATEGORY) ? 1 : -1) : korean.compare(a.name(), b.name()));
        return out;
    }
    /** The page's text, or an IOException that says the file is gone. */
    public String body(String id) throws IOException {
        Page page = page(id);
        if (page == null) throw new IOException("페이지를 찾지 못했어요. 지워졌을 수 있어요.");
        // Read outside the lock (a long text never holds the book): a change that lands meanwhile removes this revision's
        // file, so a failed read is tried again on the page as it is then.
        for (int attempt = 0; ; attempt++) {
            File file = file(page);
            try { if (file.isFile()) return new String(read(file, (long) BODY_LIMIT * 4 + 16), StandardCharsets.UTF_8); }
            catch (IOException unreadable) { /* below */ }
            Page now = page(id);
            if (now == null) throw new IOException("페이지를 찾지 못했어요. 지워졌을 수 있어요.");
            if (now.rev() != page.rev() && attempt < 3) { page = now; continue; }
            // The message never carries the file's path.
            throw new IOException(file.isFile() ? "페이지 내용 파일을 읽지 못했어요." : "페이지 내용 파일을 찾지 못했어요. 목록에서 지우고 다시 저장해 주세요.");
        }
    }
    /**
     * Pages whose title, category, tags or first words hold every word of query (any case), in an optional category;
     * when fewer than limit match, the pages' full text is searched too, newest first. A blank query lists the newest.
     */
    public List<Page> find(String query, String category, int limit) {
        List<Page> scope = category == null || category.isBlank() ? pages() : pages(category);
        int max = Math.max(1, Math.min(limit, 100));
        List<String> words = new ArrayList<>();
        for (String word : SPACES.split(query == null ? "" : query.strip().toLowerCase(Locale.ROOT))) if (!word.isEmpty()) words.add(word);
        List<Page> out = new ArrayList<>();
        if (words.isEmpty()) { for (Page page : scope) { if (out.size() >= max) break; out.add(page); } return out; }
        for (Page page : scope) if (out.size() < max && contains(String.join(" ", page.title(), page.category(), String.join(" ", page.tags()), page.preview()), words)) out.add(page);
        // Bodies are read one at a time and only when the index alone did not fill the answer.
        int scanned = 0;
        for (Page page : scope) {
            if (out.size() >= max || scanned >= 400) break;
            if (out.contains(page)) continue;
            scanned++;
            try { if (contains(body(page.id()), words)) out.add(page); } catch (IOException missing) { /* listed as missing when opened */ }
        }
        return out;
    }

    // ── Changing ────────────────────────────────────────────────────────────────────────────────────────

    /**
     * Saves a new page. format is markdown or html (anything else is decided from the text); a text that is one fenced
     * html block, or a whole HTML document, is stored as HTML. A blank title is taken from the document's own title or
     * first heading. The category is matched to an existing one ignoring case and spaces, so a model's "여행 " lands in 여행.
     */
    public Page save(String title, String category, String format, String content, String source, String origin) throws IOException {
        Page saved;
        synchronized (this) {
            writable();
            if (pages.size() >= PAGE_LIMIT) throw new IllegalArgumentException("페이지는 " + String.format(Locale.ROOT, "%,d", PAGE_LIMIT) + "개까지 저장할 수 있어요. 필요 없는 페이지를 지운 뒤 다시 저장해 주세요.");
            String[] decided = decide(format, content);
            String kind = decided[0], text = decided[1];
            check(text);
            String name = assign(category);
            long now = clock.getAsLong();
            saved = new Page(newId(), title(title, text, kind), name, kind, now, now, text.length(), preview(text, kind),
                source(source), clean(origin, ORIGIN_CHARS), false, List.of(), 1);
            File body = file(saved);
            write(body, text);
            List<Page> next = new ArrayList<>(pages); next.add(0, saved);
            try { commit(categoriesWith(name), next); }
            catch (IOException failed) { if (!body.delete()) body.deleteOnExit(); throw failed; }
        }
        changed();
        return saved;
    }
    /**
     * Changes a page: any of title, category, a whole new content (with its format decided again) or text appended at the end
     * (before &lt;/body&gt; in HTML). Null leaves a part as it is.
     */
    public Page update(String id, String title, String category, String content, String append) throws IOException {
        return update(id, title, category, null, content, append);
    }
    /** The same with the new content's format given (markdown or html; null keeps the page's own unless the text says otherwise). */
    public Page update(String id, String title, String category, String format, String content, String append) throws IOException {
        return update(id, title, category, format, content, append, -1);
    }
    /**
     * The same, only if the page is still as it was when it was read (its updated time, checked under the book's lock), else
     * IllegalStateException and nothing changes: a whole new text written from an older reading never overwrites a change made
     * since. expected below 0 checks nothing.
     */
    public Page update(String id, String title, String category, String format, String content, String append, long expected) throws IOException {
        Page next;
        synchronized (this) { next = apply(id, title, category, format, content, append, expected); }
        changed();
        return next;
    }
    /**
     * Changes a page's text from its current text, all under the book's lock, so nothing saved in between is lost: change gets
     * the whole text and gives back the new one (PageTools applies a model's {old_text, new_text} edits this way). title and
     * category as in update.
     */
    public Page edit(String id, String title, String category, UnaryOperator<String> change) throws IOException {
        Page next;
        synchronized (this) { writable(); require(id); next = apply(id, title, category, null, change.apply(body(id)), null, -1); }
        changed();
        return next;
    }
    private Page apply(String id, String title, String category, String format, String content, String append, long expected) throws IOException {
        writable();
        Page old = require(id);
        if (expected >= 0 && old.updated() != expected) throw new IllegalStateException("The page changed after it was read. Read it again (page_read from offset 0), then send the complete new text. Nothing was changed.");
        if (content != null && append != null) throw new IllegalArgumentException("content와 append는 함께 쓸 수 없어요. 하나만 주세요.");
        String kind = old.format(), text = null;
        if (content != null) { String[] decided = decide(format != null && !format.isBlank() ? format : old.format(), content); kind = decided[0]; text = decided[1]; }
        else if (append != null) {
            if (append.isBlank()) throw new IllegalArgumentException("이어 쓸 내용이 비어 있어요.");
            text = appended(body(id), append, old.html());
        }
        if (text != null) check(text);
        String name = category == null ? old.category() : assign(category);
        String nextTitle = title == null ? old.title() : title(title, text == null ? "" : text, kind);
        long now = clock.getAsLong();
        Page next = new Page(old.id(), nextTitle, name, kind, old.created(), Math.max(now, old.updated() + 1), text == null ? old.chars() : text.length(),
            text == null ? old.preview() : preview(text, kind), old.source(), old.origin(), old.pinned(), old.tags(), text == null ? old.rev() : old.rev() + 1);
        if (text == null) { commit(categoriesWith(name), replaced(old, next)); return next; }
        // The new text in a file of its own: until the index names it, the old file and the old index are the page.
        File fresh = file(next);
        write(fresh, text);
        try { commit(categoriesWith(name), replaced(old, next)); }
        catch (IOException | RuntimeException failed) { if (!fresh.delete()) fresh.deleteOnExit(); throw failed; }
        File stale = file(old); if (!stale.delete()) stale.deleteOnExit();
        return next;
    }
    /** A whole new text for a page, its format decided again. */
    public Page replace(String id, String content) throws IOException { return update(id, null, null, content, null); }
    public Page rename(String id, String title) throws IOException {
        if (title == null || title.isBlank()) throw new IllegalArgumentException("새 제목을 적어 주세요.");
        return update(id, title, null, null, null);
    }
    public Page move(String id, String category) throws IOException { return update(id, null, category == null ? "" : category, null, null); }
    public Page pin(String id, boolean pinned) throws IOException {
        Page next;
        synchronized (this) {
            writable(); Page old = require(id);
            if (old.pinned() == pinned) return old;
            next = old.pinned(pinned);
            commit(new ArrayList<>(categories), replaced(old, next));
        }
        changed();
        return next;
    }
    /** Removes the page from the index first, then its file: a crash in between leaves only an unused file, removed on the next load. */
    public void delete(String id) throws IOException {
        synchronized (this) {
            writable(); Page old = require(id);
            List<Page> next = new ArrayList<>(pages); next.remove(old);
            commit(new ArrayList<>(categories), next);
            File body = file(old); if (body.exists() && !body.delete()) body.deleteOnExit();
        }
        changed();
    }
    /** An empty category for the user to file pages into; the existing one (any case, any spaces) when it is already there. */
    public String addCategory(String name) throws IOException {
        String made;
        synchronized (this) {
            writable();
            String exact = category(name);
            if (exact.isEmpty()) throw new IllegalArgumentException("카테고리 이름을 적어 주세요.");
            String found = existing(exact);
            if (found != null) return found;
            if (categories.size() >= CATEGORY_COUNT) throw new IllegalArgumentException("카테고리는 " + CATEGORY_COUNT + "개까지 만들 수 있어요.");
            made = exact;
            commit(categoriesWith(made), new ArrayList<>(pages));
        }
        changed();
        return made;
    }
    /** Renames a category; into one that already exists, the two become one (its spelling kept). */
    public String renameCategory(String from, String to) throws IOException {
        String target;
        synchronized (this) {
            writable();
            String source = existing(category(from));
            if (source == null) throw new IllegalArgumentException("그 카테고리를 찾지 못했어요.");
            String wanted = category(to);
            if (wanted.isEmpty()) throw new IllegalArgumentException("새 카테고리 이름을 적어 주세요.");
            String found = existing(wanted);
            target = found != null && !key(found).equals(key(source)) ? found : wanted;
            List<String> names = new ArrayList<>();
            for (String name : categories) { if (key(name).equals(key(source))) { if (!names.contains(target)) names.add(target); } else if (!(found != null && key(name).equals(key(target)) && names.contains(target))) names.add(name); }
            if (!names.contains(target)) names.add(target);
            List<Page> next = new ArrayList<>();
            for (Page page : pages) next.add(key(page.category()).equals(key(source)) ? page.inCategory(target) : page);
            commit(dedupe(names), next);
        }
        changed();
        return target;
    }
    /** Removes a category; its pages, if any, go to 기타. 기타 itself can be removed only once it is empty. */
    public int deleteCategory(String name) throws IOException {
        int moved = 0;
        synchronized (this) {
            writable();
            String source = existing(category(name));
            if (source == null) throw new IllegalArgumentException("그 카테고리를 찾지 못했어요.");
            boolean fallback = key(source).equals(key(DEFAULT_CATEGORY));
            List<Page> next = new ArrayList<>();
            for (Page page : pages) {
                if (key(page.category()).equals(key(source))) {
                    if (fallback) throw new IllegalArgumentException("'" + DEFAULT_CATEGORY + "'에 페이지가 있어서 지울 수 없어요. 페이지를 옮기거나 지운 뒤 다시 해 주세요.");
                    moved++; next.add(page.inCategory(DEFAULT_CATEGORY));
                } else next.add(page);
            }
            List<String> names = new ArrayList<>();
            for (String existing : categories) if (!key(existing).equals(key(source))) names.add(existing);
            if (moved > 0 && existing(DEFAULT_CATEGORY, names) == null) names.add(DEFAULT_CATEGORY);
            commit(names, next);
        }
        changed();
        return moved;
    }

    // ── Text rules (also used by PageTools) ─────────────────────────────────────────────────────────────

    /** A title on one line: control characters gone, spaces collapsed, at most TITLE_LIMIT characters; the fallback when blank. */
    static String title(String value, String fallback) {
        String text = clean(value, TITLE_LIMIT);
        return text.isEmpty() ? fallback : text;
    }
    /** A category name: one line, no slashes, at most CATEGORY_LIMIT characters; "" when nothing is left. */
    public static String category(String value) {
        String text = clean(value == null ? "" : value.replace('/', ' ').replace('\\', ' '), CATEGORY_LIMIT);
        return text;
    }
    static String tag(String value) { return clean(value == null ? "" : value.replace('#', ' '), TAG_CHARS); }
    /** Collapses whitespace, removes control and invisible characters, and cuts at limit characters (never inside a pair). */
    static String clean(String value, int limit) {
        if (value == null) return "";
        String text = SPACES.matcher(CONTROL.matcher(value).replaceAll(" ")).replaceAll(" ").strip();
        if (text.length() <= limit) return text;
        int end = limit; if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end).strip();
    }
    /** {format, text}: html for a whole document or a single fenced html block (unwrapped), the caller's format otherwise (html only with a tag in it). */
    public static String[] decide(String format, String content) {
        String text = content == null ? "" : content;
        String fenced = fencedHtml(text);
        if (fenced != null) return new String[]{HTML, fenced};
        String asked = format == null ? "" : format.strip().toLowerCase(Locale.ROOT);
        // A text with no tag at all is Markdown (or plain text) whatever was asked: as HTML it would show as one run of words.
        boolean html = HTML_DOCUMENT.matcher(text).find() || (asked.equals("html") || asked.equals("htm")) && ANY_TAG.matcher(text).find();
        return new String[]{html ? HTML : MARKDOWN, text};
    }
    /** The title a document gives itself: HTML's &lt;title&gt; or first &lt;h1&gt;, Markdown's first heading, else its first line. */
    static String title(String asked, String text, String format) {
        String given = clean(asked, TITLE_LIMIT);
        if (!given.isEmpty()) return given;
        String start = cut(text == null ? "" : text, SCAN);
        if (HTML.equals(format)) {
            String lower = lower(start);
            for (String name : new String[]{"title", "h1"}) {
                String inner = element(start, lower, name);
                if (inner != null) { String found = clean(entities(TAGS.matcher(inner).replaceAll(" ")), TITLE_LIMIT); if (!found.isEmpty()) return found; }
            }
        } else {
            String first = firstHeading(start);
            if (first != null) { String found = clean(Markdown.plain(first), TITLE_LIMIT); if (!found.isEmpty()) return found; }
        }
        String plain = HTML.equals(format) ? entities(TAGS.matcher(visible(start)).replaceAll("\n")) : Markdown.plain(cut(start, 20_000));
        for (String line : plain.split("\n")) { String found = clean(line, TITLE_LIMIT); if (!found.isEmpty()) return found; }
        return UNTITLED;
    }
    /** The title a page saved from this text would get: the first suggestion of a save form. */
    public static String suggestedTitle(String text) { String[] decided = decide(null, text); return title("", decided[1], decided[0]); }
    /** The first plain words of a body, for the list and the index search. */
    static String preview(String text, String format) {
        String plain = HTML.equals(format) ? entities(TAGS.matcher(visible(cut(text, SCAN))).replaceAll(" ")) : Markdown.plain(cut(text, 20_000));
        return clean(plain, PREVIEW);
    }
    /**
     * The text of one fenced html block that is the whole text, without its fences; null otherwise. A text with another fence
     * line inside (an answer with several code blocks) is no single block and stays Markdown.
     */
    static String fencedHtml(String text) {
        String whole = text.strip();
        if (!(whole.startsWith("```") || whole.startsWith("~~~"))) return null;
        char mark = whole.charAt(0);
        int open = whole.indexOf('\n'), close = whole.lastIndexOf('\n');
        if (open < 0 || close <= open) return null;
        String info = whole.substring(0, open).replace(String.valueOf(mark), " ").strip().toLowerCase(Locale.ROOT);
        if (!(info.equals("html") || info.equals("htm"))) return null;
        String last = whole.substring(close + 1).strip();
        if (last.length() < 3 || !last.chars().allMatch(c -> c == mark)) return null;
        String inner = whole.substring(open + 1, close);
        for (String line : inner.split("\n", -1)) { String start = line.stripLeading(); if (start.startsWith("```") || start.startsWith("~~~")) return null; }
        return inner;
    }
    /** The first Markdown heading's text (# to ######), not a # line inside a fenced code block (a shell comment); null for none. By hand, line by line. */
    static String firstHeading(String text) {
        boolean fenced = false;
        for (int at = 0; at < text.length(); ) {
            int end = text.indexOf('\n', at); if (end < 0) end = text.length();
            int i = at, indent = 0;
            while (i < end && (text.charAt(i) == ' ' || text.charAt(i) == '\t')) { i++; indent++; }
            if (text.startsWith("```", i) || text.startsWith("~~~", i)) fenced = !fenced;
            else if (!fenced && indent <= 3) {
                int hashes = i;
                while (hashes < end && text.charAt(hashes) == '#') hashes++;
                if (hashes > i && hashes - i <= 6 && hashes < end && (text.charAt(hashes) == ' ' || text.charAt(hashes) == '\t')) {
                    String found = heading(text.substring(hashes, end));
                    if (!found.isEmpty()) return found;
                }
            }
            at = end + 1;
        }
        return null;
    }
    /** A heading's text without the closing #s Markdown allows ("## 제목 ##"), trimmed by hand: a pattern for them backtracks. */
    public static String heading(String line) {
        int end = line.length();
        while (end > 0 && (line.charAt(end - 1) == ' ' || line.charAt(end - 1) == '\t')) end--;
        int hashes = end;
        while (hashes > 0 && line.charAt(hashes - 1) == '#') hashes--;
        if (hashes < end && (hashes == 0 || line.charAt(hashes - 1) == ' ' || line.charAt(hashes - 1) == '\t')) end = hashes;
        return line.substring(0, end).strip();
    }
    private static final String[] HIDDEN = {"script", "style", "head", "template"};
    /**
     * HTML without what a reader does not see: script, style, head and template elements (an unclosed one hides the rest).
     * By hand, in one pass: a pattern with a backreference goes quadratic on many unclosed tags.
     */
    static String visible(String html) {
        String lower = lower(html);
        StringBuilder out = new StringBuilder(html.length());
        int[] next = new int[HIDDEN.length]; java.util.Arrays.fill(next, -2);
        int at = 0;
        while (at < html.length()) {
            int found = -1, which = -1;
            for (int t = 0; t < HIDDEN.length; t++) {
                if (next[t] != -1 && next[t] < at) next[t] = tag(lower, HIDDEN[t], at);
                if (next[t] >= 0 && (found < 0 || next[t] < found)) { found = next[t]; which = t; }
            }
            if (found < 0) { out.append(html, at, html.length()); break; }
            out.append(html, at, found).append(' ');
            int closing = lower.indexOf("</" + HIDDEN[which], found + 1 + HIDDEN[which].length());
            int end = closing < 0 ? -1 : lower.indexOf('>', closing);
            at = end < 0 ? html.length() : end + 1;
        }
        return out.toString();
    }
    /** What the first &lt;name&gt; element holds, or null when there is none or it is never closed. */
    private static String element(String html, String lower, String name) {
        int open = tag(lower, name, 0);
        if (open < 0) return null;
        int start = lower.indexOf('>', open);
        int end = start < 0 ? -1 : lower.indexOf("</" + name, start + 1);
        return end < 0 ? null : html.substring(start + 1, end);
    }
    /** Where &lt;name starts as a tag (not &lt;names) at or after from, or -1. */
    private static int tag(String lower, String name, int from) {
        String open = "<" + name;
        for (int i = lower.indexOf(open, from); i >= 0; i = lower.indexOf(open, i + 1)) {
            int after = i + open.length();
            if (after >= lower.length() || !Character.isLetterOrDigit(lower.charAt(after))) return i;
        }
        return -1;
    }
    /**
     * The text with only A to Z lower-cased, so every character stays where it was: a tag found in it is at the same place in
     * the text itself. (toLowerCase makes 'İ' two characters, and every offset after it would be one off.)
     */
    static String lower(String text) {
        char[] chars = text.toCharArray();
        for (int i = 0; i < chars.length; i++) if (chars[i] >= 'A' && chars[i] <= 'Z') chars[i] += 32;
        return new String(chars);
    }
    private static String cut(String text, int limit) {
        if (text.length() <= limit) return text;
        int end = limit; if (Character.isHighSurrogate(text.charAt(end - 1))) end--;
        return text.substring(0, end);
    }
    private static String entities(String html) {
        return html.replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
    }
    /** Text added at the end: after a blank line in Markdown, just before &lt;/body&gt; (or at the end) in HTML. */
    static String appended(String body, String text, boolean html) {
        if (!html) return body.stripTrailing() + "\n\n" + text.strip() + "\n";
        int close = lower(body).lastIndexOf("</body>");
        return close < 0 ? body + "\n" + text : body.substring(0, close) + text + "\n" + body.substring(close);
    }

    // ── Internals ───────────────────────────────────────────────────────────────────────────────────────

    private static final Comparator<Page> ORDER = (a, b) -> a.pinned() != b.pinned() ? (a.pinned() ? -1 : 1) : Long.compare(b.updated(), a.updated());
    private static String key(String name) { return SPACES.matcher(name == null ? "" : name).replaceAll("").toLowerCase(Locale.ROOT); }
    private static boolean contains(String text, List<String> words) {
        String lower = text.toLowerCase(Locale.ROOT);
        for (String word : words) if (!lower.contains(word)) return false;
        return true;
    }
    private static String source(String value) { return WORK.equals(value) || CHAT.equals(value) ? value : USER; }
    private String existing(String name) { return existing(name, categories); }
    private static String existing(String name, List<String> names) {
        String key = key(name);
        for (String existing : names) if (key(existing).equals(key)) return existing;
        return null;
    }
    /** The category a page goes into: the existing spelling when there is one, else the cleaned new name, else 기타. */
    private String assign(String asked) {
        String name = category(asked);
        if (name.isEmpty()) name = DEFAULT_CATEGORY;
        String found = existing(name);
        if (found != null) return found;
        if (categories.size() >= CATEGORY_COUNT) {
            String fallback = existing(DEFAULT_CATEGORY);
            if (fallback != null) return fallback;
        }
        return name;
    }
    private List<String> categoriesWith(String name) {
        List<String> next = new ArrayList<>(categories);
        if (existing(name, next) == null) next.add(name);
        return next;
    }
    private static List<String> dedupe(List<String> names) {
        List<String> out = new ArrayList<>();
        for (String name : names) if (existing(name, out) == null) out.add(name);
        return out;
    }
    private List<Page> replaced(Page old, Page next) {
        List<Page> list = new ArrayList<>(pages);
        list.set(list.indexOf(old), next);
        return list;
    }
    private Page require(String id) {
        Page page = page(id);
        if (page == null) throw new IllegalArgumentException("페이지를 찾지 못했어요. page_find로 id를 다시 확인해 주세요.");
        return page;
    }
    private void check(String text) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("저장할 내용이 비어 있어요.");
        if (text.length() > BODY_LIMIT) throw new IllegalArgumentException("페이지 하나는 " + String.format(Locale.ROOT, "%,d", BODY_LIMIT) + "자까지 저장할 수 있어요. 나눠서 저장해 주세요.");
    }
    private void writable() {
        if (unreadable) throw new IllegalStateException("페이지 목록 파일을 읽지 못해서 바꾸지 않았어요. 원본 파일은 그대로 보존했어요." + (problem.isEmpty() ? "" : " (" + problem + ")"));
    }
    private String newId() {
        while (true) {
            byte[] bytes = new byte[8]; RANDOM.nextBytes(bytes);
            StringBuilder id = new StringBuilder("p");
            for (byte b : bytes) id.append(Character.forDigit((b >> 4) & 15, 16)).append(Character.forDigit(b & 15, 16));
            if (page(id.toString()) == null) return id.toString();
        }
    }
    /** The page's body file as it is now (its newest revision). */
    public File file(Page page) { return new File(dir, page.fileName()); }
    private File index() { return new File(dir, "index.json"); }

    /** Writes the index and then adopts it: memory changes only once the file says the same. */
    private void commit(List<String> names, List<Page> next) throws IOException {
        JSONArray list = new JSONArray(); for (Page page : next) list.put(page.json());
        write(index(), obj("format", FORMAT, "categories", new JSONArray(names), "pages", list).toString());
        categories.clear(); categories.addAll(names);
        pages.clear(); pages.addAll(next);
        version++;
    }
    private void changed() { for (Runnable listener : listeners) { try { listener.run(); } catch (RuntimeException ignored) { /* one listener never stops the others */ } } }

    private synchronized void load() {
        categories.clear(); pages.clear(); unreadable = false; problem = "";
        File index = index();
        if (!index.exists()) { sweep(); return; }
        try {
            JSONObject json = new JSONObject(new String(read(index, INDEX_BYTES), StandardCharsets.UTF_8));
            if (json.optInt("format") != FORMAT) throw new Damaged("목록 파일의 형식(" + clean(String.valueOf(json.opt("format")), 12) + ")을 알 수 없어요");
            JSONArray names = json.optJSONArray("categories");
            for (int i = 0; names != null && i < names.length(); i++) { String name = category(names.optString(i)); if (!name.isEmpty() && existing(name) == null) categories.add(name); }
            JSONArray list = json.optJSONArray("pages");
            for (int i = 0; list != null && i < list.length(); i++) {
                JSONObject item = list.optJSONObject(i);
                if (item == null || !ID.matcher(item.optString("id")).matches()) continue;
                Page page = Page.of(item);
                if (page.category().isEmpty()) page = page.inCategory(DEFAULT_CATEGORY);
                if (page(page.id()) != null) continue;
                pages.add(page);
                if (existing(page.category()) == null) categories.add(page.category());
            }
            sweep();
        } catch (Exception damaged) {
            // A short, fixed reason: a parser's own message can carry the whole file (every title and first words), a file
            // error its path, and the reason is shown on the screen and told to the models.
            unreadable = true; problem = damaged instanceof Damaged own ? own.getMessage() : "목록 파일의 내용이 올바르지 않아요";
            categories.clear(); pages.clear();
        }
    }
    /** A reason PageBook gives itself, safe to show. */
    private static final class Damaged extends IOException { Damaged(String reason) { super(reason); } }
    /**
     * Removes body files the index does not name (a change or a delete interrupted before or after the index was saved: an
     * older revision, or a new one the index never took) and stray temporary files. Other files are left alone.
     */
    private void sweep() {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File file : files) {
            String name = file.getName();
            if (name.endsWith(".tmp")) { if (!file.delete()) file.deleteOnExit(); continue; }
            Matcher body = FILE.matcher(name);
            if (!body.matches()) continue;
            Page page = page(body.group(1));
            if (page == null || !name.equals(page.fileName())) { if (!file.delete()) file.deleteOnExit(); }
        }
    }

    static byte[] read(File file, long limit) throws IOException {
        if (file.length() > limit) throw new IOException(file.getName() + " 파일이 너무 커요");
        try (InputStream in = new FileInputStream(file)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream((int) Math.min(Math.max(16, file.length()), Integer.MAX_VALUE));
            byte[] buffer = new byte[16 * 1024]; int n; long total = 0;
            while ((n = in.read(buffer)) > 0) { total += n; if (total > limit) throw new IOException(file.getName() + " 파일이 너무 커요"); out.write(buffer, 0, n); }
            return out.toByteArray();
        }
    }
    /** Temporary file, synced, then moved over the target in one step. */
    static void write(File target, String text) throws IOException {
        File parent = target.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) throw new IOException("페이지 폴더를 만들지 못했어요.");
        File temporary = new File(target.getPath() + ".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(text.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        catch (IOException failed) { if (!temporary.delete()) temporary.deleteOnExit(); throw failed; }
        try { Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (IOException failed) { if (!temporary.delete()) temporary.deleteOnExit(); throw failed; }
    }
}
