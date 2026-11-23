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
                what.put(id, WorkBook.author(engine) + " · " + new TeamRoster(store, scope).label(engine, model) + "가 " + label); changed(id);
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
