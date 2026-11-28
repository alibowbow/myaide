package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The AI browser's own lists, kept as JSON in settings: 스킬 (a named request kept to run again, optionally from a start
 * page), 북마크 (pages kept, in folders), the bookmarks of another browser read from its exported HTML file (Chrome, Edge,
 * Safari and Firefox all export the same Netscape bookmark format), 프로젝트 (a named set of tabs to open again), and the
 * request 끝까지 조사 sends. Pure Java.
 */
public final class BrowserKit {
    private BrowserKit() {}
    /** Skills kept; 북마크 kept (준: "북마크는 필요없어 100개 제한하자": the oldest goes, and the user is told). */
    public static final int MAX_SKILLS = 1000, MAX_BOOKMARKS = 100;
    /** The longest skill name, skill request, start address and project name the sheets take. */
    public static final int SKILL_NAME = 200, SKILL_PROMPT = 20_000, URL_LIMIT = 8000, PROJECT_NAME = 100;

    // ── 끝까지 조사 ───────────────────────────────────────────────────────

    /** The request 끝까지 조사 sends: the user's words, with how to research them to the end and report. */
    public static String deep(String request) {
        return "[끝까지 조사] 아래 요청을 끝까지 깊이 조사해 주세요.\n"
            + "- 서로 다른 출처를 3곳 이상 새 탭에서 열어 읽고 비교해요 (다 읽은 탭은 닫아요).\n"
            + "- 빠진 부분이나 서로 다른 내용이 있으면 검색을 이어 가며 확인하고, 결론이 분명해질 때까지 멈추지 않아요.\n"
            + "- 로그인·결제·전송·삭제는 하지 않고, 읽기와 검색만 해요.\n"
            + "- 마지막 답은 보고서로: 핵심 결론, 근거, 서로 다른 점, 출처 링크 목록.\n\n"
            + "요청: " + (request == null ? "" : request.strip());
    }

    // ── 스킬 ──────────────────────────────────────────────────────────────

    public record Skill(String id, String name, String prompt, String url, long created) {
        public JSONObject json() { return obj("id", id, "name", name, "prompt", prompt, "url", url, "created", created); }
        public static Skill of(JSONObject o) { return new Skill(o.optString("id"), o.optString("name"), o.optString("prompt"), o.optString("url"), o.optLong("created")); }
        /** The request it sends: its words, after opening its start page when it has one. */
        public String request() { return url.isBlank() ? prompt : "먼저 " + url + " 을(를) 열고, 그 페이지에서 다음을 해 주세요.\n" + prompt; }
    }
    public static List<Skill> skills(String saved) {
        List<Skill> out = new ArrayList<>();
        try { JSONArray list = new JSONArray(saved == null || saved.isBlank() ? "[]" : saved); for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("prompt").isBlank()) out.add(Skill.of(o)); } }
        catch (Exception unreadable) { return out; }
        return out;
    }
    /** Adds or replaces (same id) a skill; the name is the request's start when blank. Newest first. */
    public static String saveSkill(String saved, String id, String name, String prompt, String url, long now) {
        String words = prompt == null ? "" : prompt.strip(); if (words.isEmpty()) throw new IllegalArgumentException("스킬이 할 일을 적어 주세요.");
        if (words.length() > SKILL_PROMPT) throw new IllegalArgumentException("스킬이 할 일은 " + String.format(Locale.ROOT, "%,d", SKILL_PROMPT) + "자까지 적을 수 있어요.");
        String title = name == null || name.isBlank() ? (words.length() > 24 ? words.substring(0, 24) + "…" : words) : name.strip();
        if (title.length() > SKILL_NAME) title = title.substring(0, SKILL_NAME);
        String page = url == null ? "" : url.strip();
        if (page.length() > URL_LIMIT) throw new IllegalArgumentException("시작 페이지 주소가 너무 길어요 (" + URL_LIMIT + "자까지).");
        if (!page.isEmpty()) page = BrowserTools.url(page.matches("(?i)[a-z][a-z0-9+.-]*://.*") ? page : "https://" + page); // "naver.com" works too
        List<Skill> list = skills(saved); String key = id == null || id.isBlank() ? UUID.randomUUID().toString() : id; long created = now;
        for (Skill s : list) if (s.id().equals(key)) created = s.created();
        list.removeIf(s -> s.id().equals(key));
        list.add(0, new Skill(key, title, words, page, created));
        while (list.size() > MAX_SKILLS) list.remove(list.size() - 1);
        JSONArray out = arr(); for (Skill s : list) out.put(s.json()); return out.toString();
    }
    public static String deleteSkill(String saved, String id) {
        JSONArray out = arr(); for (Skill s : skills(saved)) if (!s.id().equals(id)) out.put(s.json()); return out.toString();
    }

    // ── 북마크 ────────────────────────────────────────────────────────────

    public record Bookmark(String url, String title, String folder, long added) {
        public JSONObject json() { return obj("url", url, "title", title, "folder", folder, "added", added); }
        public static Bookmark of(JSONObject o) { return new Bookmark(o.optString("url"), o.optString("title"), o.optString("folder"), o.optLong("added")); }
    }
    public static List<Bookmark> bookmarks(String saved) {
        List<Bookmark> out = new ArrayList<>();
        try { JSONArray list = new JSONArray(saved == null || saved.isBlank() ? "[]" : saved); for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("url").isBlank()) out.add(Bookmark.of(o)); } }
        catch (Exception unreadable) { return out; }
        return out;
    }
    public static boolean bookmarked(String saved, String url) { String key = norm(url); for (Bookmark b : bookmarks(saved)) if (norm(b.url()).equals(key)) return true; return false; }
    private static String norm(String url) { String u = url == null ? "" : url.strip(); while (u.endsWith("/")) u = u.substring(0, u.length() - 1); return u.replaceFirst("^https?://(www\\.)?", "").toLowerCase(Locale.ROOT); }
    /**
     * What adding bookmarks did: the list to keep, how many of the pages were new and stayed, and the oldest bookmarks let go
     * (those already kept or new ones) so that at most MAX_BOOKMARKS stay. Nothing is let go without the user being told (note).
     */
    public record Added(String saved, int added, List<Bookmark> dropped) {
        /** One short line about what was let go ("" when nothing was): the bookmark by name when one, else how many. */
        public String note() {
            if (dropped.isEmpty()) return "";
            if (dropped.size() == 1) {
                String title = dropped.get(0).title().isBlank() ? dropped.get(0).url() : dropped.get(0).title();
                return "북마크는 " + MAX_BOOKMARKS + "개까지라 가장 오래된 ‘" + (title.length() > 30 ? title.substring(0, 29) + "…" : title) + "’를 뺐어요";
            }
            return "북마크는 " + MAX_BOOKMARKS + "개까지라 가장 오래된 " + dropped.size() + "개를 뺐어요";
        }
    }
    /** Adds pages not kept yet (newest first), skipping those already there; past MAX_BOOKMARKS the oldest (by added) go. */
    public static Added add(String saved, List<Bookmark> pages) {
        List<Bookmark> list = bookmarks(saved); java.util.Set<String> seen = new java.util.HashSet<>(); for (Bookmark b : list) seen.add(norm(b.url()));
        List<Bookmark> fresh = new ArrayList<>();
        for (Bookmark b : pages) { if (b.url().isBlank() || !b.url().matches("(?i)https?://.+") || !seen.add(norm(b.url()))) continue; fresh.add(b); }
        int added = fresh.size();
        fresh.addAll(list);
        List<Bookmark> dropped = new ArrayList<>();
        if (fresh.size() > MAX_BOOKMARKS) {
            // The oldest first; of two added at the same time, the one further down the list (kept longer) goes first.
            List<Integer> order = new ArrayList<>(); for (int i = 0; i < fresh.size(); i++) order.add(i);
            List<Bookmark> all = fresh;
            order.sort((a, b) -> all.get(a).added() != all.get(b).added() ? Long.compare(all.get(a).added(), all.get(b).added()) : Integer.compare(b, a));
            java.util.Set<Integer> gone = new java.util.HashSet<>(order.subList(0, fresh.size() - MAX_BOOKMARKS));
            List<Bookmark> kept = new ArrayList<>(); int newOnes = added;
            for (int i = 0; i < fresh.size(); i++) {
                if (!gone.contains(i)) { kept.add(fresh.get(i)); continue; }
                dropped.add(fresh.get(i)); if (i < newOnes) added--;
            }
            fresh = kept;
        }
        JSONArray out = arr(); for (Bookmark b : fresh) out.put(b.json());
        return new Added(out.toString(), added, dropped);
    }
    public static String addBookmarks(String saved, List<Bookmark> pages) { return add(saved, pages).saved(); }
    public static String deleteBookmark(String saved, String url) {
        String key = norm(url); JSONArray out = arr(); for (Bookmark b : bookmarks(saved)) if (!norm(b.url()).equals(key)) out.put(b.json()); return out.toString();
    }

    // ── 프로젝트 ──────────────────────────────────────────────────────────

    /** A project: a name for a set of pages worked on together (the tabs open when it was saved), opened again as tabs. */
    public record Project(String id, String name, List<Bookmark> pages, long saved) {
        public JSONObject json() { JSONArray list = arr(); for (Bookmark b : pages) list.put(b.json()); return obj("id", id, "name", name, "pages", list, "saved", saved); }
        public static Project of(JSONObject o) {
            List<Bookmark> pages = new ArrayList<>(); JSONArray list = o.optJSONArray("pages");
            for (int i = 0; list != null && i < list.length(); i++) { JSONObject p = list.optJSONObject(i); if (p != null && !p.optString("url").isBlank()) pages.add(Bookmark.of(p)); }
            return new Project(o.optString("id"), o.optString("name"), pages, o.optLong("saved"));
        }
    }
    /** Projects kept; a project keeps every web page tab open when it is saved. */
    public static final int MAX_PROJECTS = 1000;
    public static List<Project> projects(String saved) {
        List<Project> out = new ArrayList<>();
        try { JSONArray list = new JSONArray(saved == null || saved.isBlank() ? "[]" : saved); for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null) { Project p = Project.of(o); if (!p.pages().isEmpty()) out.add(p); } } }
        catch (Exception unreadable) { return out; }
        return out;
    }
    /** Saves (or, same name, replaces) a project of these pages: every web page among them, each once. Newest first. */
    public static String saveProject(String saved, String name, List<Bookmark> pages, long now) {
        String title = name == null ? "" : name.strip(); if (title.isEmpty()) throw new IllegalArgumentException("프로젝트 이름을 적어 주세요.");
        if (title.length() > PROJECT_NAME) title = title.substring(0, PROJECT_NAME).strip();
        List<Bookmark> kept = new ArrayList<>(); java.util.Set<String> seen = new java.util.HashSet<>();
        for (Bookmark b : pages) if (b.url().matches("(?i)https?://.+") && seen.add(norm(b.url()))) kept.add(b);
        if (kept.isEmpty()) throw new IllegalArgumentException("저장할 웹 페이지 탭이 없어요.");
        List<Project> list = projects(saved); String id = UUID.randomUUID().toString();
        for (Project p : list) if (p.name().equalsIgnoreCase(title)) id = p.id();
        String named = title;
        String key = id; list.removeIf(p -> p.id().equals(key));
        list.add(0, new Project(key, named, kept, now));
        while (list.size() > MAX_PROJECTS) list.remove(list.size() - 1);
        JSONArray out = arr(); for (Project p : list) out.put(p.json()); return out.toString();
    }
    public static String deleteProject(String saved, String id) {
        JSONArray out = arr(); for (Project p : projects(saved)) if (!p.id().equals(id)) out.put(p.json()); return out.toString();
    }

    private static final Pattern FOLDER = Pattern.compile("(?is)<H3[^>]*>(.*?)</H3>"), LINK = Pattern.compile("(?is)<A\\s[^>]*HREF=\"([^\"]+)\"[^>]*>(.*?)</A>"),
        OPEN = Pattern.compile("(?i)<DL>"), CLOSE = Pattern.compile("(?i)</DL>"), ADDED = Pattern.compile("(?i)ADD_DATE=\"(\\d+)\"");
    /**
     * The pages in an exported bookmarks file (the Netscape format Chrome, Edge, Safari and Firefox write), each with the
     * folder path it was in ("즐겨찾기 표시줄 / 뉴스"). Only http(s) pages, all of them: add then keeps MAX_BOOKMARKS and
     * says how many it let go.
     */
    public static List<Bookmark> parseExport(String html, long now) {
        List<Bookmark> out = new ArrayList<>(); if (html == null) return out;
        List<String> path = new ArrayList<>(); String pendingFolder = null;
        Pattern any = Pattern.compile("(?is)<H3[^>]*>.*?</H3>|<A\\s[^>]*>.*?</A>|<DL>|</DL>");
        Matcher m = any.matcher(html);
        while (m.find()) {
            String token = m.group();
            if (OPEN.matcher(token).matches()) { if (pendingFolder != null) { path.add(pendingFolder); pendingFolder = null; } else path.add(""); continue; }
            if (CLOSE.matcher(token).matches()) { if (!path.isEmpty()) path.remove(path.size() - 1); continue; }
            Matcher folder = FOLDER.matcher(token);
            if (folder.matches()) { pendingFolder = text(folder.group(1)); continue; }
            Matcher link = LINK.matcher(token);
            if (link.matches()) {
                String url = unescape(link.group(1)).strip(); if (!url.matches("(?i)https?://.+")) continue;
                String title = text(link.group(2)); Matcher added = ADDED.matcher(token);
                long when = added.find() ? Long.parseLong(added.group(1)) * (added.group(1).length() > 11 ? 1 : 1000) : now;
                StringBuilder folders = new StringBuilder(); for (String p : path) if (!p.isBlank()) folders.append(folders.length() == 0 ? "" : " / ").append(p);
                out.add(new Bookmark(url, title.isBlank() ? url : title, folders.toString(), when));
            }
        }
        return out;
    }
    private static String text(String html) { return unescape(html.replaceAll("(?s)<[^>]+>", "")).replaceAll("\\s+", " ").strip(); }
    private static String unescape(String s) {
        return s.replace("&amp;", "&").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&#x27;", "'");
    }
}
