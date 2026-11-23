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
 * full screen with the network (charts, data) and keeps its own storage. Kept on this phone in one folder: projects.json,
 * and per project index.html, history/ (the versions before each change, newest last, at most HISTORY) and thumb.jpg (how
 * it last looked). ProjectMaker asks the AI with make() and change(); nothing here talks to the network.
 */
public final class ProjectBook {
    /** One project. kind: "app" (made or changed by the AI or the user) or a template's name; hue: its tile's color. */
    public record Project(String id, String title, String about, String kind, long made, long updated, int hue, boolean pinned) {
        public Project { title = clean(title, 60); about = clean(about, 300); kind = kind == null || kind.isBlank() ? "app" : clean(kind, 30); }
        JSONObject json() throws Exception {
            return new JSONObject().put("id", id).put("title", title).put("about", about).put("kind", kind).put("made", made).put("updated", updated).put("hue", hue).put("pinned", pinned);
        }
        static Project of(JSONObject o) { return new Project(o.optString("id"), o.optString("title"), o.optString("about"), o.optString("kind"), o.optLong("made"), o.optLong("updated"), o.optInt("hue"), o.optBoolean("pinned")); }
        public Project titled(String value) { return new Project(id, value, about, kind, made, updated, hue, pinned); }
        public Project pin(boolean value) { return new Project(id, title, about, kind, made, updated, hue, value); }
        Project touched(long at) { return new Project(id, title, about, kind, made, at, hue, pinned); }
    }
    public static final int HISTORY = 20, PROJECTS = 100, MAX_HTML = 1_500_000;
    private final File root;
    public ProjectBook(File root) { this.root = root; }
    public File dir(String id) { return new File(root, id.replaceAll("[^A-Za-z0-9_-]", "_")); }
    public File thumb(String id) { return new File(dir(id), "thumb.jpg"); }

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
        Project p = new Project(UUID.randomUUID().toString().substring(0, 12), name, about, kind, now, now, hue, false);
        write(new File(dir(p.id()), "index.html"), html == null ? "" : html);
        save(p);
        return p;
    }
    public synchronized void save(Project p) throws IOException {
        JSONArray out = new JSONArray(); boolean found = false;
        try {
            for (Project q : projects()) { if (q.id().equals(p.id())) { out.put(p.json()); found = true; } else out.put(q.json()); }
            if (!found) out.put(p.json());
        } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(root, "projects.json"), out.toString());
    }
    public synchronized void remove(String id) throws IOException {
        JSONArray out = new JSONArray();
        try { for (Project q : projects()) if (!q.id().equals(id)) out.put(q.json()); } catch (Exception e) { throw new IOException(e.getMessage(), e); }
        write(new File(root, "projects.json"), out.toString());
        delete(dir(id));
    }
    public synchronized String html(String id) { try { return read(new File(dir(id), "index.html"), ""); } catch (IOException e) { return ""; } }
    /** A new version: the one before goes to history (the oldest past HISTORY are dropped). */
    public synchronized void html(String id, String html) throws IOException {
        Project p = project(id); if (p == null) throw new IOException("프로젝트를 찾지 못했어요.");
        if (html == null || html.isBlank()) throw new IOException("빈 HTML은 저장하지 않아요.");
        if (html.length() > MAX_HTML) throw new IOException("HTML이 너무 커요.");
        File current = new File(dir(id), "index.html"), history = new File(dir(id), "history"); history.mkdirs();
        if (current.isFile() && current.length() > 0) Files.copy(current.toPath(), new File(history, System.currentTimeMillis() + ".html").toPath(), StandardCopyOption.REPLACE_EXISTING);
        File[] old = versions(id);
        for (int i = 0; i < old.length - HISTORY; i++) old[i].delete();
        write(current, html);
        save(p.touched(System.currentTimeMillis()));
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
        File last = all[all.length - 1];
        write(new File(dir(id), "index.html"), read(last, ""));
        last.delete();
        Project p = project(id); if (p != null) save(p.touched(System.currentTimeMillis()));
        return true;
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
    private static String read(File file, String empty) throws IOException { return file.isFile() ? new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8) : empty; }
    private static void write(File file, String text) throws IOException {
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
