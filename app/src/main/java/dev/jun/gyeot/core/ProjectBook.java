package dev.jun.gyeot.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 페이지 → 프로젝트: the user's own small web apps (as Claude's artifacts and ChatGPT's sites), each one HTML file that runs
 * full screen with the network (charts, data) and keeps its own storage.
 * <p>
 * Where they live: each project is a folder in the user's 들퍼레 folder, 들퍼레/프로젝트/<이름>/index.html (Store), so a file
 * manager or another editor can open, change, add or remove them, and they stay when the app is removed. This book keeps,
 * in the app's own folder, what the folder does not: projects.json (name, description, pinned …), each project's copy of
 * its page as last seen (index.html, what is shown at once), history/ (the versions before each change, at most HISTORY)
 * and thumb.jpg. sync() reads the folder: a page changed there comes in (the one before it goes to history), a folder
 * made there becomes a project, a folder removed there removes it. Without the 들퍼레 folder connected, projects stay in the
 * app's folder alone and go out to 들퍼레 once it is connected. Nothing here talks to the network.
 */
public final class ProjectBook {
    /**
     * One project. kind: "app" (made or changed by the AI or the user), "folder" (found in 들퍼레/프로젝트) or a template's
     * name; hue: its tile's color; folder: its folder's name in 들퍼레/프로젝트 ("" while it is in the app alone); seen: the
     * page's time in that folder when last read or written.
     */
    public record Project(String id, String title, String about, String kind, long made, long updated, int hue, boolean pinned, String folder, long seen) {
        public Project { title = clean(title, 60); about = clean(about, 300); kind = kind == null || kind.isBlank() ? "app" : clean(kind, 30); folder = folder == null ? "" : folder; }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("title", title).put("about", about).put("kind", kind).put("made", made).put("updated", updated).put("hue", hue).put("pinned", pinned)
                .put("folder", folder).put("seen", seen);
        }
        static Project of(JSONObject o) {
            return new Project(o.optString("id"), o.optString("title"), o.optString("about"), o.optString("kind"), o.optLong("made"), o.optLong("updated"), o.optInt("hue"), o.optBoolean("pinned"),
                o.optString("folder"), o.optLong("seen"));
        }
        public Project titled(String value) { return new Project(id, value, about, kind, made, updated, hue, pinned, folder, seen); }
        public Project pin(boolean value) { return new Project(id, title, about, kind, made, updated, hue, value, folder, seen); }
        public Project described(String value) { return new Project(id, title, value, kind, made, updated, hue, pinned, folder, seen); }
        Project touched(long at) { return new Project(id, title, about, kind, made, at, hue, pinned, folder, seen); }
        Project at(String place, long time) { return new Project(id, title, about, kind, made, updated, hue, pinned, place, time); }
    }

    /** The folder the projects live in: 들퍼레/프로젝트, one folder per project with its index.html. */
    public interface Store {
        record Item(String folder, long modified) {}
        /** The project folders (those holding index.html) and their page's time. */
        List<Item> list() throws IOException;
        String read(String folder) throws IOException;
        /** Writes the page (making the folder when needed) and returns its time after writing. */
        long write(String folder, String html) throws IOException;
        void rename(String from, String to) throws IOException;
        void delete(String folder) throws IOException;
        /** Where it is, for the screen: "들퍼레/프로젝트". */
        String where();
    }
    /** A Store on a plain folder (the phone's all-files access, and tests). */
    public static final class DirStore implements Store {
        private final File root; private final String where;
        public DirStore(File root, String where) { this.root = root; this.where = where; }
        private File folder(String name) throws IOException {
            String clean = folderName(name); if (!clean.equals(name)) throw new IOException("안전하지 않은 폴더 이름이에요.");
            File f = new File(root, name); if (Files.isSymbolicLink(f.toPath())) throw new IOException("링크 폴더는 쓰지 않아요."); return f;
        }
        @Override public List<Item> list() throws IOException {
            List<Item> out = new ArrayList<>(); File[] dirs = root.listFiles();
            if (dirs == null) { if (!root.exists()) return out; throw new IOException("프로젝트 폴더를 읽지 못했어요."); }
            for (File d : dirs) {
                if (!d.isDirectory() || Files.isSymbolicLink(d.toPath()) || d.getName().startsWith(".")) continue;
                File page = new File(d, "index.html"); if (page.isFile() && !Files.isSymbolicLink(page.toPath())) out.add(new Item(d.getName(), page.lastModified()));
            }
            return out;
        }
        @Override public String read(String name) throws IOException { return ProjectBook.read(new File(folder(name), "index.html"), ""); }
        @Override public long write(String name, String html) throws IOException {
            File d = folder(name); if (!d.isDirectory() && !d.mkdirs()) throw new IOException("프로젝트 폴더를 만들지 못했어요.");
            File page = new File(d, "index.html"); ProjectBook.write(page, html); return page.lastModified();
        }
        @Override public void rename(String from, String to) throws IOException {
            File a = folder(from), b = folder(to); if (b.exists()) throw new IOException("같은 이름의 프로젝트 폴더가 있어요."); if (!a.renameTo(b)) throw new IOException("폴더 이름을 바꾸지 못했어요.");
        }
        @Override public void delete(String name) throws IOException { ProjectBook.delete(folder(name)); }
        @Override public String where() { return where; }
    }

    public static final int HISTORY = 20, PROJECTS = 100, MAX_HTML = 1_500_000;
    private final File root; private final java.util.function.Supplier<Store> stores;
    /** stores: the 들퍼레 folder's projects when it is connected, else null (asked each time: a grant can come and go). */
    public ProjectBook(File root, java.util.function.Supplier<Store> stores) { this.root = root; this.stores = stores == null ? () -> null : stores; }
    public ProjectBook(File root) { this(root, null); }
    public File dir(String id) { return new File(root, id.replaceAll("[^A-Za-z0-9_-]", "_")); }
    public File thumb(String id) { return new File(dir(id), "thumb.jpg"); }
    private Store store() { try { return stores.get(); } catch (RuntimeException unavailable) { return null; } }
    /** "들퍼레/프로젝트" when the folder is connected, else "" (the projects are in the app alone). */
    public String where() { Store s = store(); return s == null ? "" : s.where(); }

    /** Every project: the pinned first, then the most recently changed. */
    public synchronized List<Project> projects() {
        List<Project> out = new ArrayList<>();
        try {
            JSONArray list = new JSONArray(read(new File(root, "projects.json"), "[]"));
            for (int i = 0; i < list.length(); i++) { JSONObject o = list.optJSONObject(i); if (o != null && !o.optString("id").isBlank()) out.add(Project.of(o)); }
        } catch (Exception ignored) {}
        out.sort((a, b) -> a.pinned() != b.pinned() ? (a.pinned() ? -1 : 1) : Long.compare(b.updated(), a.updated()));
        return out;
    }
    public synchronized Project project(String id) { for (Project p : projects()) if (p.id().equals(id)) return p; return null; }
    public synchronized Project create(String title, String about, String kind, String html, int hue) throws IOException {
        if (projects().size() >= PROJECTS) throw new IllegalStateException("프로젝트는 " + PROJECTS + "개까지 둘 수 있어요. 쓰지 않는 프로젝트를 지워 주세요.");
        long now = System.currentTimeMillis();
        String name = clean(title, 60).isBlank() ? (clean(about, 24).isBlank() ? "새 프로젝트" : clean(about, 24)) : title;
        Project p = new Project(UUID.randomUUID().toString().substring(0, 12), name, about, kind, now, now, hue, false, "", 0);
        String page = html == null ? "" : html;
        write(new File(dir(p.id()), "index.html"), page);
        save(p);
        if (!page.isBlank()) export(p, page);
        return project(p.id());
    }
    public synchronized void save(Project p) throws IOException {
        JSONArray out = new JSONArray(); boolean found = false;
        try {
            for (Project q : projects()) { if (q.id().equals(p.id())) { out.put(p.json()); found = true; } else out.put(q.json()); }
            if (!found) out.put(p.json());
        } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(root, "projects.json"), out.toString());
    }
    /** A new name; its folder in 들퍼레/프로젝트 follows it when it can. */
    public synchronized Project rename(String id, String title) throws IOException {
        Project p = project(id); if (p == null) throw new IOException("프로젝트를 찾지 못했어요.");
        Project named = p.titled(title); Store s = store();
        if (s != null && !p.folder().isEmpty()) {
            String folder = unique(folderName(named.title()), p.folder());
            if (!folder.equals(p.folder())) try { s.rename(p.folder(), folder); seen(p.folder(), false); seen(folder, true); named = named.at(folder, p.seen()); } catch (IOException kept) { /* The folder keeps its name. */ }
        }
        save(named);
        return named;
    }
    /** Removes the project, and its folder in 들퍼레/프로젝트. */
    public synchronized void remove(String id) throws IOException {
        Project p = project(id); Store s = store();
        if (p != null && s != null && !p.folder().isEmpty()) { s.delete(p.folder()); seen(p.folder(), false); }
        JSONArray out = new JSONArray();
        try { for (Project q : projects()) if (!q.id().equals(id)) out.put(q.json()); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(root, "projects.json"), out.toString());
        delete(dir(id));
    }
    /** The page as last seen (the app's copy; sync() and pull() bring in a change made in the folder). */
    public synchronized String html(String id) { try { return read(new File(dir(id), "index.html"), ""); } catch (IOException e) { return ""; } }
    /** A new version: the one before goes to history (the oldest past HISTORY are dropped), and the folder gets it too. */
    public synchronized void html(String id, String html) throws IOException {
        Project p = project(id); if (p == null) throw new IOException("프로젝트를 찾지 못했어요.");
        if (html == null || html.isBlank()) throw new IOException("빈 HTML은 저장하지 않아요.");
        if (html.length() > MAX_HTML) throw new IOException("HTML이 너무 커요.");
        keep(id, html);
        export(project(id), html);
    }
    /** The app's copy becomes html, the one before it goes to history. */
    private void keep(String id, String html) throws IOException {
        Project p = project(id); if (p == null) return;
        File current = new File(dir(id), "index.html"), history = new File(dir(id), "history"); history.mkdirs();
        if (current.isFile() && current.length() > 0) {
            if (read(current, "").equals(html)) return;
            File to = new File(history, System.currentTimeMillis() + ".html"); int n = 1;
            while (to.exists()) to = new File(history, (System.currentTimeMillis() + n++) + ".html");
            Files.copy(current.toPath(), to.toPath());
        }
        File[] old = versions(id);
        for (int i = 0; i < old.length - HISTORY; i++) old[i].delete();
        write(current, html);
        save(p.touched(System.currentTimeMillis()));
    }
    /** The page into the project's folder in 들퍼레/프로젝트 (made with a name from its title the first time). */
    private void export(Project p, String html) {
        Store s = store(); if (s == null || p == null) return;
        try {
            String folder = p.folder().isEmpty() ? unique(folderName(p.title()), null) : p.folder();
            long at = s.write(folder, html); seen(folder, true);
            Project now = project(p.id()); if (now != null) save(now.at(folder, at));
        } catch (IOException notNow) { /* Kept in the app: sync() writes it out later. */ }
    }
    /** The versions kept before the current one, oldest first. */
    public synchronized File[] versions(String id) {
        File[] files = new File(dir(id), "history").listFiles((d, name) -> name.endsWith(".html"));
        if (files == null) return new File[0];
        java.util.Arrays.sort(files, (a, b) -> a.getName().compareTo(b.getName()));
        return files;
    }
    /** 되돌리기: the newest version kept becomes the current one again (and leaves history). False when there is none. */
    public synchronized boolean undo(String id) throws IOException {
        File[] all = versions(id); if (all.length == 0) return false;
        File last = all[all.length - 1]; String back = read(last, "");
        write(new File(dir(id), "index.html"), back);
        last.delete();
        Project p = project(id); if (p != null) { save(p.touched(System.currentTimeMillis())); export(project(id), back); }
        return true;
    }

    // ── the 들퍼레 folder ───────────────────────────────────────────

    /**
     * Brings the projects and the 들퍼레/프로젝트 folder together (off the main thread): a page changed in the folder comes in,
     * a folder made there becomes a project, a folder removed there (one this phone had seen) removes its project, and a
     * project kept only in the app (made before the folder was connected, or brought back from a backup) goes out to it.
     * Returns whether anything changed; false too when the folder is not connected or could not be read.
     */
    public synchronized boolean sync() {
        Store s = store(); if (s == null) return false;
        List<Store.Item> items;
        try { items = s.list(); } catch (IOException unreadable) { return false; }
        java.util.Map<String, Store.Item> byFolder = new java.util.HashMap<>();
        for (Store.Item i : items) byFolder.put(i.folder(), i);
        boolean changed = false;
        java.util.Set<String> known = new java.util.HashSet<>();
        for (Project p : projects()) {
            if (p.folder().isEmpty()) { String page = html(p.id()); if (!page.isBlank()) { export(p, page); changed = true; } continue; }
            known.add(p.folder());
            Store.Item item = byFolder.get(p.folder());
            if (item == null) {
                if (seen(p.folder())) { try { remove(p.id()); } catch (IOException ignored) {} changed = true; }
                else { String page = html(p.id()); if (!page.isBlank()) { export(p, page); changed = true; } }
                continue;
            }
            if (item.modified() != p.seen()) {
                try {
                    String page = s.read(p.folder());
                    if (!page.isBlank() && page.length() <= MAX_HTML) { keep(p.id(), page); changed = true; }
                    Project now = project(p.id()); if (now != null) save(now.at(p.folder(), item.modified()));
                    seen(p.folder(), true);
                } catch (IOException ignored) {}
            }
        }
        for (Store.Item item : items) {
            if (known.contains(item.folder())) continue;
            try {
                String page = s.read(item.folder()); if (page.isBlank() || page.length() > MAX_HTML) continue;
                long now = System.currentTimeMillis();
                String title = title(page); if (title.isBlank()) title = item.folder();
                Project p = new Project(UUID.randomUUID().toString().substring(0, 12), title, "들퍼레/프로젝트/" + item.folder(), "folder", now, now, Math.floorMod(item.folder().hashCode(), 8), false, item.folder(), item.modified());
                write(new File(dir(p.id()), "index.html"), page);
                save(p); seen(item.folder(), true); changed = true;
            } catch (IOException ignored) {}
        }
        return changed;
    }
    /** Brings in one project's page when it changed in the folder (opening it). Returns whether it did. */
    public synchronized boolean pull(String id) {
        Project p = project(id); Store s = store(); if (p == null || s == null || p.folder().isEmpty()) return false;
        try {
            for (Store.Item item : s.list()) if (item.folder().equals(p.folder())) {
                if (item.modified() == p.seen()) return false;
                String page = s.read(p.folder()); if (page.isBlank() || page.length() > MAX_HTML) return false;
                keep(id, page); Project now = project(id); if (now != null) save(now.at(p.folder(), item.modified())); seen(p.folder(), true);
                return true;
            }
        } catch (IOException ignored) {}
        return false;
    }
    /**
     * The folders this phone has seen in 들퍼레/프로젝트 (.seen, never in a backup): a project whose folder is gone from there
     * is removed only when this phone saw it, so a project brought back from a backup is written out instead.
     */
    private boolean seen(String folder) { try { return lines(new File(root, ".seen")).contains(folder); } catch (IOException e) { return false; } }
    private void seen(String folder, boolean on) {
        try {
            File f = new File(root, ".seen"); java.util.Set<String> all = new java.util.LinkedHashSet<>(lines(f));
            if (on ? all.add(folder) : all.remove(folder)) write(f, String.join("\n", all));
        } catch (IOException ignored) {}
    }
    private static List<String> lines(File f) throws IOException { List<String> out = new ArrayList<>(); for (String l : read(f, "").split("\n")) if (!l.isBlank()) out.add(l.strip()); return out; }
    /** A folder name from a title: no path or reserved characters, at most 50 characters. */
    public static String folderName(String title) {
        String t = title == null ? "" : title.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", " ").replaceAll("\\s+", " ").strip();
        while (t.startsWith(".")) t = t.substring(1).strip();
        if (t.length() > 50) t = t.substring(0, 50).strip();
        return t.isEmpty() ? "프로젝트" : t;
    }
    /** name, or "name 2", "name 3" … when another project already has that folder (except own). */
    private String unique(String name, String own) {
        java.util.Set<String> taken = new java.util.HashSet<>();
        for (Project p : projects()) if (!p.folder().isEmpty() && !p.folder().equals(own)) taken.add(p.folder().toLowerCase(Locale.ROOT));
        Store s = store();
        if (s != null) try { for (Store.Item i : s.list()) if (!i.folder().equals(own)) taken.add(i.folder().toLowerCase(Locale.ROOT)); } catch (IOException ignored) {}
        String out = name; int n = 2;
        while (taken.contains(out.toLowerCase(Locale.ROOT))) out = name + " " + n++;
        return out;
    }

    // ── what the AI is told ───────────────────────────────────────────

    /** The rules every project answer follows. */
    public static final String RULES = """
        너는 사용자의 휴대폰 앱 '들퍼레'의 프로젝트 제작자야. Claude의 아티팩트나 ChatGPT의 Sites처럼, 사용자가 말한 것을 실제로 쓸 수 있는 웹 앱 하나로 만들어.
        규칙:
        - 결과는 완전한 HTML 문서 하나(<!doctype html>부터 </html>까지)를 ```html 코드 블록 하나에 담아. 블록 앞에 무엇을 만들었는지 한두 문장만 써.
        - 파일 하나로 끝나게: CSS와 JavaScript는 문서 안에. 외부 라이브러리는 https CDN(cdn.jsdelivr.net, cdnjs.cloudflare.com, unpkg.com)만, 버전을 고정해서.
        - 휴대폰 세로 화면이 먼저야. 폭 360px에서도 가로 스크롤이 없고, 태블릿에서는 넓게 쓰도록 반응형으로. 누르는 곳은 44px 이상.
        - 밝은/어두운 화면 둘 다: prefers-color-scheme와 <html data-theme="dark|light">(앱이 넣어 줘)를 모두 따라. 색은 :root의 변수로.
        - 고급스럽고 정갈하게: 시스템 글꼴(-apple-system, 'Pretendard', 'Noto Sans KR', sans-serif), 넉넉한 여백, 둥근 카드, 그림자는 은은하게, 강조색 하나.
        - 화면 글은 한국어로. 사용자가 고칠 수 있는 설정(목록, 숫자)은 화면에서 바로 바꾸게 하고 localStorage에 저장해.
        - 차트·시세는 TradingView 무료 위젯(https://s3.tradingview.com/external-embedding/…)을 쓰고, 그 밖의 데이터는 키가 필요 없는 공개 API만 fetch로.
        - alert/confirm/prompt, 새 창 열기, 개인 정보 수집은 하지 마.
        """;
    public static String make(String request) {
        return "만들 것:\n" + request.strip() + "\n\n위 규칙대로 완전한 HTML 문서 하나를 만들어 줘.";
    }
    public static String change(String html, String request) {
        return "지금 프로젝트의 HTML:\n```html\n" + html + "\n```\n\n사용자의 요청:\n" + request.strip() + "\n\n"
            + "요청대로 고친 전체 HTML 문서를 ```html 블록 하나로 다시 줘. 요청과 상관없는 부분(디자인, 저장된 설정의 키 이름)은 그대로 둬.";
    }
    private static final Pattern FENCE = Pattern.compile("```(?:html|HTML)?[ \\t]*\\n(.*?)```", Pattern.DOTALL);
    /** The HTML document in an answer: its ```html block (the longest), else the answer itself when it is a document. */
    public static String document(String answer) {
        if (answer == null) return "";
        Matcher m = FENCE.matcher(answer); String best = "";
        while (m.find()) if (m.group(1).length() > best.length()) best = m.group(1);
        String out = best.isBlank() ? answer : best;
        String low = out.toLowerCase(Locale.ROOT);
        int start = low.indexOf("<!doctype"); if (start < 0) start = low.indexOf("<html");
        int end = low.lastIndexOf("</html>");
        if (start < 0 || end < start) return low.contains("<body") || low.contains("<div") ? out.strip() : "";
        return out.substring(start, end + 7).strip();
    }
    /** What the AI said before its HTML (its one-line summary). */
    public static String summary(String answer) { if (answer == null) return ""; int at = answer.indexOf("```"); return clean(at < 0 ? "" : answer.substring(0, at), 300); }
    /** The page's <title>, for a project made without a name. */
    public static String title(String html) {
        Matcher m = Pattern.compile("<title[^>]*>(.*?)</title>", Pattern.DOTALL | Pattern.CASE_INSENSITIVE).matcher(html == null ? "" : html);
        return m.find() ? clean(m.group(1).replaceAll("<[^>]+>", "").replaceAll("\\s+", " "), 60) : "";
    }
    /**
     * The document as it runs: the app's theme on <html> before anything else, so the page can follow it even where the
     * WebView's own color scheme does not.
     */
    public static String themed(String html, boolean dark) {
        String mark = "<script>document.documentElement.setAttribute('data-theme','" + (dark ? "dark" : "light") + "')</script>";
        String low = html.toLowerCase(Locale.ROOT); int head = low.indexOf("<head");
        if (head >= 0) { int close = low.indexOf('>', head); if (close > 0) return html.substring(0, close + 1) + mark + html.substring(close + 1); }
        return mark + html;
    }

    static String clean(String s, int max) { String t = s == null ? "" : s.replace('\u0000', ' ').strip(); return t.length() > max ? t.substring(0, max).strip() : t; }
    static String read(File file, String empty) throws IOException { return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : empty; }
    static void write(File file, String text) throws IOException {
        file.getParentFile().mkdirs();
        File temp = new File(file.getParentFile(), file.getName() + ".tmp");
        Files.write(temp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
    }
    static void delete(File f) {
        if (f == null || !f.exists()) return;
        File[] kids = f.isDirectory() ? f.listFiles() : null;
        if (kids != null) for (File k : kids) delete(k);
        f.delete();
    }
}
