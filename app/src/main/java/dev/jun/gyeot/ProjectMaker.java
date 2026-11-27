package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.ProjectBook;
import dev.jun.gyeot.core.WorkBook;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

/**
 * 프로젝트's maker: the AI (ScopedModels.projects) writing a project's HTML from what the user asked, or changing it, one job
 * per project off the main thread; the screens watch it. Also the projects' book and the templates (트레이딩).
 */
final class ProjectMaker {
    interface Listener { void changed(String project); }
    private static ProjectMaker instance;
    /** Some project is being made or changed now (RunningWork). */
    static boolean anyRunning() { ProjectMaker m = instance; return m != null && !m.running.isEmpty(); }
    static synchronized ProjectMaker get(Context c) { if (instance == null) instance = new ProjectMaker(c.getApplicationContext()); return instance; }

    private final Context app; private final ProjectBook book;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "gyeot-project"); t.setDaemon(true); return t; });
    private final Map<String, AtomicReference<Runnable>> running = new ConcurrentHashMap<>();
    private final Map<String, String> what = new ConcurrentHashMap<>(), failed = new ConcurrentHashMap<>(), said = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    private ProjectMaker(Context app) { this.app = app; this.book = new ProjectBook(new File(app.getFilesDir(), "projects"), () -> ProjectStores.current(app)); }
    ProjectBook book() { return book; }
    private AppStore store() { return ((GyeotApp) app).store(); }

    void watch(Listener l) { listeners.add(l); }
    void unwatch(Listener l) { listeners.remove(l); }
    private void changed(String id) { main.post(() -> { for (Listener l : listeners) l.changed(id); }); }
    /** A project changed elsewhere (the chats' tools): the screens showing it draw it again. */
    void touched(String id) { changed(id); }
    boolean busy(String id) { return running.containsKey(id); }
    String doing(String id) { String w = what.get(id); return w == null ? "" : w; }
    /** The last job's failure (once: reading it clears it). */
    String failure(String id) { return failed.remove(id); }
    /** What the AI said its last change was (once). */
    String summary(String id) { return said.remove(id); }
    private final java.util.Set<String> stopping = ConcurrentHashMap.newKeySet();
    void stop(String id) { AtomicReference<Runnable> c = running.get(id); if (c == null) return; stopping.add(id); Runnable r = c.getAndSet(null); if (r != null) try { r.run(); } catch (RuntimeException ignored) {} }

    // ── templates ────────────────────────────────────────────────────

    record Template(String kind, String title, String about, String asset, int hue) {}
    static final Template TRADING = new Template("trading", "트레이딩", "SOXL · NVDA · QQQ · BTCUSDT · BTCZ 차트를 트레이딩뷰로 한눈에", "projects/trading.html", 0);
    static final List<Template> TEMPLATES = List.of(TRADING);
    ProjectBook.Project fromTemplate(Template t) throws IOException {
        String html;
        try (InputStream in = app.getAssets().open(t.asset())) { html = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
        return book.create(t.title(), t.about(), t.kind(), html, t.hue());
    }
    /**
     * The template's pages shipped before, by SHA-256: a project still exactly one of them was never changed by the user, so it
     * takes the newest at once. The first had no AI 차트 분석; 0.4.513 no 방금 바꾼 것 되돌리기; 0.4.526 no 분석 스타일; the last
     * (7fe20c…) no 내 트뷰 차트 (the user's own TradingView above the cards); 449426… the cards turned into a list beside that chart
     * (준: "이거 배열이 너무 이상해 원래 우리의 레이아웃으로 해줘": now the cards keep their charts and 크게 보기 opens the user's own);
     * 879483… no 텍스트 복사 on the news; e524c7… the 내 트뷰 mode (the app's TradingView chart and sign-in, gone: 준 "로그인도
     * 없애줘"; the bar's TradingView button opens the chart in the AI 브라우저).
     */
    private static final java.util.Set<String> OLD_TRADING = java.util.Set.of("f960956429b84eda68d057d8255e0203dcaf0dde0b64bd53aca821f902c49ef8",
        "419eb6e647b423181d7bcd35d4c92680d6f6c7b09e6912264c673ce7739cf008", "603e83b7ac93ed513be7c56bb180eca9b4dbbac2799f2ff256bd018acd937048",
        "70bdfcabe4df4f7cdfbe0d80fab533302d8119e6f308d1fad4750d2981763155", "64ae67608b07f7cd8b1084b859069a3d3de296ad2f3b7cb7e1dce82a13ad6afc", "777fa08c4d930ad183d6955292d8aa1afb703c781cfa52e3181a89af17dd20bc", "cf598aa60a397d6bc99555f5f2568d178db4a842d31a68dd7238fb14febd10c2",
        "7fe20c1aae708543c6b75bc160a836d18e0fb4c251aa78d6cf425b0a9ed132b3", "44942650a1305faba8a05cd877eb04719b56d46222970efa22ddeabde734f220",
        "879483fb811c0a4a655ce35b8893c25083a043c0498beac2419b03d0c12917d0", "e524c7e7efa2127d3294f12199165f369a2d690fd056d608397b47881e1fb91e");
    /**
     * A 트레이딩 made from an older template gets the new one (AI 차트 분석 · 최신 뉴스): at once when the user never changed it
     * (the old page stays in its versions), else only when asked (offerUpgrade). Returns whether the page changed.
     */
    boolean upgrade(String id, boolean changedByUser) {
        ProjectBook.Project p = book.project(id); if (p == null || !TRADING.kind().equals(p.kind()) || busy(id)) return false;
        String html = book.html(id);
        try {
            boolean shipped = OLD_TRADING.contains(sha256(html));
            // A page the user changed that already has the app's AI is theirs; one never changed takes the newest template.
            if (html.contains("gyeot-app") && !shipped) return false;
            if (!changedByUser && !shipped) return false;
            String fresh; try (InputStream in = app.getAssets().open(TRADING.asset())) { fresh = new String(in.readAllBytes(), StandardCharsets.UTF_8); }
            if (fresh.equals(html)) return false;
            book.html(id, fresh); changed(id); return true;
        } catch (Exception failed) { return false; }
    }
    /** A 트레이딩 the user changed, still without the new template's AI 차트 분석 · 최신 뉴스. */
    boolean offerUpgrade(String id) {
        ProjectBook.Project p = book.project(id);
        return p != null && TRADING.kind().equals(p.kind()) && !book.html(id).isBlank() && !book.html(id).contains("gyeot-app");
    }
    private static String sha256(String text) throws Exception {
        byte[] d = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
        StringBuilder hex = new StringBuilder(); for (byte b : d) hex.append(String.format(java.util.Locale.ROOT, "%02x", b)); return hex.toString();
    }
    /** The projects the user asked for once, put in the first time 프로젝트 opens (not again if removed). */
    void seed(AppStore store) {
        if (store.settings.getBoolean("projectsSeeded", false)) return;
        try { if (book.projects().isEmpty()) fromTemplate(TRADING); store.settings.edit().putBoolean("projectsSeeded", true).apply(); } catch (IOException ignored) {}
    }

    // ── the AI ───────────────────────────────────────────────────────

    /** The AI writes the project from the request (a new project: its HTML empty until then). */
    boolean make(String id, String request, boolean rename) { return start(id, "만드는 중", () -> ProjectBook.make(request), rename); }
    /** The AI changes the project as asked, from its HTML as it is. */
    boolean change(String id, String request) { return start(id, "고치는 중", () -> ProjectBook.change(book.html(id), request), false); }
    private boolean start(String id, String label, java.util.function.Supplier<String> input, boolean naming) {
        if (book.project(id) == null) return false;
        AtomicReference<Runnable> cancel = new AtomicReference<>();
        if (running.putIfAbsent(id, cancel) != null) return false;
        failed.remove(id);
        what.put(id, label); changed(id);
        jobs.execute(() -> {
            try {
                AppStore store = store(); ScopedModels scope = ScopedModels.projects(store);
                String engine = scope.engine();
                if (engine == null || engine.isBlank() || !store.ready(engine)) throw new IllegalStateException("프로젝트를 만들 AI가 연결되지 않았어요. 아래 모델 이름을 눌러 골라 주세요.");
                String model = scope.model(engine), effort = scope.effectiveEffort(engine, model);
                what.put(id, WorkBook.author(engine) + " · " + new TeamRoster(store, scope).label(engine, model) + " · " + label); changed(id);
                String answer = FileAiOperationsUi.complete(store, FileAiOperationsUi.Connection.capture(store, engine), model, effort, ProjectBook.RULES, input.get(), () -> {}, cancel);
                if (stopping.contains(id)) throw new IOException("멈췄어요.");
                String html = ProjectBook.document(answer);
                if (html.isBlank()) throw new IOException("AI가 HTML을 보내지 않았어요. 요청을 조금 더 구체적으로 적어 다시 해 주세요.");
                book.html(id, html);
                ProjectBook.Project p = book.project(id);
                if (naming && p != null) { String t = ProjectBook.title(html); if (!t.isBlank()) book.save(p.titled(t)); }
                String s = ProjectBook.summary(answer); if (!s.isBlank()) said.put(id, s);
            } catch (Exception e) {
                failed.put(id, stopping.remove(id) ? "멈췄어요." : e.getMessage() == null || e.getMessage().isBlank() ? "만들지 못했어요." : e.getMessage());
            } finally {
                running.remove(id); what.remove(id); stopping.remove(id); changed(id);
            }
        });
        return true;
    }
}
