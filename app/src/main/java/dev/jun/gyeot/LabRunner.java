package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.LabBook;
import dev.jun.gyeot.core.LabSources;
import dev.jun.gyeot.core.WorkBook;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 연구실's engine: one job at a time per project, off the main thread and kept alive by LabJobService. A job is a 토론 (each
 * debater answers on its own model, in parallel, then rebuts for the rounds set, and the judge sums it up), 문헌 조사 (search
 * words → open scholarly APIs → a summary citing [S1]…), an experiment (Python in the app's Linux, fixed up to twice, then
 * read), 비평, 보고서, or 자율 연구: 책임연구원 choosing the next of these until the budget is spent, a question only the user
 * can answer comes up, or an experiment waits for the user's yes. Everything said is written to LabBook as it arrives.
 */
final class LabRunner {
    interface Listener { void changed(String project); }
    private static LabRunner instance;
    /** Some project's job runs now (an update would stop it: RunningWork). */
    static boolean runningNow() { LabRunner r = instance; return r != null && r.anyRunning(); }
    static synchronized LabRunner get(Context c) { if (instance == null) instance = new LabRunner(c.getApplicationContext()); return instance; }

    private final Context app; private final LabBook book;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService jobs = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "gyeot-lab"); t.setDaemon(true); return t; });
    private final ExecutorService voices = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "gyeot-lab-ask"); t.setDaemon(true); return t; });
    private final Map<String, Job> running = new ConcurrentHashMap<>();
    private final List<Listener> listeners = new CopyOnWriteArrayList<>();
    /** Groq's per-minute tokens and agy's one CLI: researchers on these answer one after another, not all at once. */
    private final Map<String, Object> serial = new ConcurrentHashMap<>();
    /** The project open on screen while 연구실 is in front (no notification for it then). */
    static volatile String onScreen;

    static final class Job {
        final String project, title; volatile String what = ""; volatile boolean stopped;
        final List<AtomicReference<Runnable>> cancels = new CopyOnWriteArrayList<>();
        final Set<String> speaking = ConcurrentHashMap.newKeySet();
        Job(String project, String title) { this.project = project; this.title = title; }
    }
    private static final class Stopped extends RuntimeException { Stopped() { super("멈췄어요."); } }

    private LabRunner(Context app) { this.app = app; this.book = new LabBook(new File(app.getFilesDir(), "lab")); }
    LabBook book() { return book; }
    private AppStore store() { return ((GyeotApp) app).store(); }

    void watch(Listener l) { listeners.add(l); }
    void unwatch(Listener l) { listeners.remove(l); }
    private void changed(String project) { main.post(() -> { for (Listener l : listeners) l.changed(project); }); }

    boolean busy(String project) { return running.containsKey(project); }
    boolean anyRunning() { return !running.isEmpty(); }
    /** What the project's job is doing now ("2/2라운드 · 토론 중"), or "". */
    String activity(String project) { Job j = running.get(project); return j == null ? "" : j.what; }
    String jobTitle(String project) { Job j = running.get(project); return j == null ? "" : j.title; }
    Set<String> speaking(String project) { Job j = running.get(project); return j == null ? Set.of() : Set.copyOf(j.speaking); }
    String firstRunning() { for (String id : running.keySet()) return id; return null; }
    void stop(String project) {
        Job j = running.get(project); if (j == null) return;
        j.stopped = true; j.what = "멈추는 중";
        for (AtomicReference<Runnable> c : j.cancels) { Runnable r = c.getAndSet(null); if (r != null) try { r.run(); } catch (RuntimeException ignored) {} }
        try { if ("running".equals(book.autopilot(project))) book.autopilot(project, "paused"); } catch (IOException ignored) {}
        changed(project);
    }

    // ── what the screen asks for ────────────────────────────────────

    /**
     * The composer's words: an answer to the question 자율 연구 asked (it goes on), "@역할 …" for one researcher, otherwise a
     * 토론 on them. False when a job is running already.
     */
    boolean say(String project, String text) throws IOException {
        String words = text == null ? "" : text.strip(); if (words.isEmpty() || busy(project)) return false;
        JSONObject pending = book.pending(project);
        if (pending != null && "ask".equals(pending.optString("action"))) {
            book.note(project, "user", "", words, "", obj("answer", true));
            book.pending(project, null);
            try { JSONObject s = book.state(project); s.put("asked", true); book.state(project, s); } catch (Exception ignored) {}
            return autopilot(project, true);
        }
        LabBook.Role to = LabBook.mention(words);
        book.note(project, "user", "", words, "", to == null ? obj("debate", true) : obj("to", to.id()));
        if (to != null) { String message = LabBook.withoutMention(words, to); return start(project, to.name() + " 답변", j -> direct(j, to, message.isEmpty() ? words : message)); }
        return start(project, "토론", j -> debate(j, words));
    }
    boolean debate(String project, String topic) { return start(project, "토론", j -> debate(j, topic)); }
    boolean search(String project, String focus) { return start(project, "문헌 조사", j -> search(j, focus)); }
    boolean experiment(String project, String goal) { return start(project, "실험", j -> experiment(j, goal)); }
    boolean critique(String project) { return start(project, "비평", this::critique); }
    boolean report(String project) { return start(project, "보고서", this::report); }
    boolean plan(String project) { return start(project, "계획", this::plan); }
    boolean ideate(String project, String focus) { return start(project, "새 아이디어", j -> ideate(j, focus)); }
    /**
     * 가설→결론 (준: "가설 제시 부터 결론도출까지의 파이프라인"): new idea (the hypothesis) → literature → 사전 등록 → experiments
     * → 비평 → 판정 → 논문 → 동료 심사·수정, in that order, as a 자율 연구 run (its budget, its 승인 for experiments, 이어서 연구).
     */
    boolean pipeline(String project, String focus) {
        return start(project, "가설→결론", j -> {
            book.resetRun(project);
            JSONObject s = book.state(project); s.put("pipeline", true); s.put("pipe", 0); s.put("pipeFocus", focus == null ? "" : focus); book.state(project, s);
            LabBook.Project p = project(project);
            book.note(project, "system", "", "가설부터 결론까지 연구를 시작했어요 · 새 아이디어 → 문헌 → 사전 등록 → 실험 → 비평 → 판정 → 논문·심사 · 이번 한도 AI 답 " + p.budget() + "번", "", null);
            book.autopilot(project, "running"); changed(project);
            pipeline(j);
        });
    }
    /** 논문: written, reviewed by two researchers, then revised. */
    boolean paper(String project, String extra) { return start(project, "논문", j -> paper(j, extra)); }
    /** 동료 심사 again on the paper there is, then its revision. */
    boolean review(String project) { return start(project, "동료 심사", j -> { if (book.paper(j.project).isBlank()) throw new IllegalStateException("아직 논문이 없어요. 먼저 ‘논문 쓰기’를 해 주세요."); peerAndRevise(j); }); }
    /** 자율 연구. resume: go on with the run's budget as it is (after the user answered); else a new run with the budget full. */
    boolean autopilot(String project, boolean resume) {
        return start(project, "자율 연구", j -> {
            if (!resume) { book.resetRun(project); LabBook.Project p = project(project); book.note(project, "system", "", "자율 연구를 시작했어요 · 이번 한도 AI 답 " + p.budget() + "번", "", null); }
            book.autopilot(project, "running"); changed(project);
            loop(j);
        });
    }
    /** The user's answer to an experiment 자율 연구 asked for: yes runs it, no skips it; either way the run goes on. */
    boolean approve(String project, boolean yes) throws IOException {
        JSONObject pending = book.pending(project); if (pending == null || busy(project)) return false;
        String focus = pending.optString("focus");
        book.pending(project, null);
        book.note(project, "user", "", yes ? "실험 승인 · " + focus : "실험은 건너뛰어 주세요 · " + focus, "", obj("approval", yes));
        return start(project, "자율 연구", j -> {
            book.autopilot(project, "running"); changed(project);
            if (yes) experiment(j, focus);
            loop(j);
        });
    }

    // ── the job ──────────────────────────────────────────────────────

    private interface Body { void run(Job job) throws Exception; }
    private boolean start(String project, String title, Body body) {
        if (project(project) == null) return false;
        Job job = new Job(project, title);
        if (running.putIfAbsent(project, job) != null) return false;
        job.what = title + " 준비 중";
        changed(project);
        try { app.startForegroundService(new Intent(app, LabJobService.class)); } catch (RuntimeException ignored) { /* Started from the background: the job still runs while the app does. */ }
        jobs.execute(() -> {
            String failure = null; boolean stopped = false;
            try { body.run(job); }
            catch (Stopped | CancellationException e) { stopped = true; }
            catch (Exception e) {
                if (job.stopped) stopped = true;
                else failure = e.getMessage() == null || e.getMessage().isBlank() ? e.getClass().getSimpleName() : e.getMessage();
            }
            try {
                if (stopped) book.note(project, "system", "", LabBook.eul(title) + " 멈췄어요.", "", null);
                else if (failure != null) book.note(project, "error", "", failure, "", null);
                if ((stopped || failure != null) && "running".equals(book.autopilot(project))) book.autopilot(project, "paused");
                for (LabBook.Stage s : LabBook.STAGES) if ("doing".equals(book.stage(project, s.id()))) book.stage(project, s.id(), "");
            } catch (IOException ignored) {}
            running.remove(project, job);
            changed(project);
            if (!stopped) tell(project, title, failure);
        });
        return true;
    }
    private LabBook.Project project(String id) { return book.project(id); }
    private void check(Job j) { if (j.stopped) throw new Stopped(); }

    /** One researcher's answer on its own model: {text, who answered ("Claude · Opus 4.7")}. Counts against the budget. */
    private String[] ask(Job j, String roleId, String input) throws Exception {
        check(j);
        LabBook.Project p = project(j.project); if (p == null) throw new IllegalStateException("이 연구를 찾지 못했어요.");
        LabBook.Role role = LabBook.role(roleId); AppStore store = store();
        ScopedModels scope = ScopedModels.lab(store, p.id(), roleId);
        String engine = scope.engine();
        if (engine == null || engine.isBlank() || !store.ready(engine)) throw new IllegalStateException(role.name() + "의 AI가 연결되지 않았어요. 연구원 탭에서 모델을 골라 주세요.");
        String model = scope.model(engine), effort = scope.effectiveEffort(engine, model);
        String by = WorkBook.author(engine) + " · " + new TeamRoster(store, scope).label(engine, model);
        AtomicReference<Runnable> cancel = new AtomicReference<>(); j.cancels.add(cancel);
        j.speaking.add(roleId); changed(p.id());
        try {
            java.util.concurrent.Callable<String> call = () -> FileAiOperationsUi.complete(store, FileAiOperationsUi.Connection.capture(store, engine), model, effort, LabBook.brief(p, role), input,
                () -> { if (j.stopped) throw new CancellationException(); }, cancel);
            String text;
            if (WorkBook.GROQ.equals(engine) || WorkBook.ANTIGRAVITY.equals(engine)) { synchronized (serial.computeIfAbsent(engine, k -> new Object())) { check(j); text = call.call(); } }
            else text = call.call();
            check(j);
            book.spend(p.id(), 1);
            if (text == null || text.isBlank()) throw new IOException(role.name() + "의 답이 비어 있어요.");
            return new String[]{text.strip(), by};
        } catch (CancellationException e) { throw new Stopped(); }
        catch (Exception e) { if (j.stopped) throw new Stopped(); throw new IOException(role.name() + " (" + by + "): " + (e.getMessage() == null ? "답하지 못했어요." : e.getMessage()), e); }
        finally { j.speaking.remove(roleId); j.cancels.remove(cancel); changed(p.id()); }
    }
    private String digest(String project, int budget) { return LabBook.digest(book.notes(project), budget); }
    private void what(Job j, String value) { j.what = value; changed(j.project); }
    private static JSONObject obj(Object... pairs) {
        JSONObject o = new JSONObject();
        try { for (int i = 0; i + 1 < pairs.length; i += 2) o.put((String) pairs[i], pairs[i + 1]); } catch (Exception ignored) {}
        return o;
    }

    // ── 토론 ─────────────────────────────────────────────────────────

    private void debate(Job j, String topic) throws Exception {
        LabBook.Project p = project(j.project); String id = p.id();
        List<String> debaters = new ArrayList<>(p.debaters());
        if (debaters.size() < 2) debaters = List.of(LabBook.THEORIST.id(), LabBook.CRITIC.id());
        book.stage(id, "debate", "doing");
        String digest = digest(id, 2500);
        Map<String, String> latest = new LinkedHashMap<>(), who = new LinkedHashMap<>();
        StringBuilder transcript = new StringBuilder();
        for (int round = 1; round <= p.rounds(); round++) {
            check(j);
            int r = round; what(j, "토론 " + round + "/" + p.rounds() + "라운드");
            Map<String, Future<String[]>> asked = new LinkedHashMap<>();
            Map<String, String> before = new LinkedHashMap<>(latest);
            for (String d : debaters) {
                String input = r == 1 ? LabBook.debateFirst(topic, digest) : LabBook.debateRebut(topic, others(before, who, d), before.getOrDefault(d, ""), r);
                asked.put(d, voices.submit(() -> {
                    String[] a = ask(j, d, input);
                    book.note(id, "say", d, a[0], a[1], obj("round", r, "topic", topic));
                    changed(id);
                    return a;
                }));
            }
            int answered = 0; String lastFailure = null;
            for (Map.Entry<String, Future<String[]>> e : asked.entrySet()) {
                try { String[] a = e.getValue().get(); latest.put(e.getKey(), LabBook.prose(a[0])); who.put(e.getKey(), a[1]); answered++;
                    transcript.append("[").append(r).append("라운드 · ").append(LabBook.role(e.getKey()).name()).append("]\n").append(LabBook.clip(LabBook.prose(a[0]), 2400)).append("\n\n"); }
                catch (java.util.concurrent.ExecutionException failed) {
                    Throwable cause = failed.getCause(); if (cause instanceof Stopped || j.stopped) throw new Stopped();
                    lastFailure = cause == null ? "답하지 못했어요." : cause.getMessage();
                    book.note(id, "error", e.getKey(), lastFailure, "", null);
                }
            }
            if (answered == 0) throw new IOException("토론에서 아무도 답하지 못했어요. " + (lastFailure == null ? "" : lastFailure));
        }
        check(j);
        what(j, LabBook.ida(LabBook.role(p.judge()).name()) + " 토론을 정리하는 중");
        String[] s = ask(j, p.judge(), LabBook.judge(topic, transcript.toString()));
        book.claims(id, LabBook.claims(s[0], LabBook.role(p.judge()).name()));
        book.note(id, "judge", p.judge(), LabBook.prose(s[0]), s[1], obj("topic", topic));
        book.stage(id, "debate", "done");
    }
    private static String others(Map<String, String> latest, Map<String, String> who, String self) {
        StringBuilder out = new StringBuilder();
        for (Map.Entry<String, String> e : latest.entrySet()) if (!e.getKey().equals(self))
            out.append("[").append(LabBook.role(e.getKey()).name()).append(who.containsKey(e.getKey()) ? " · " + who.get(e.getKey()) : "").append("]\n").append(LabBook.clip(e.getValue(), 2000)).append("\n\n");
        return out.length() == 0 ? "(아직 없음)" : out.toString().strip();
    }

    // ── 문헌 조사 ─────────────────────────────────────────────────────

    private void search(Job j, String focus) throws Exception {
        LabBook.Project p = project(j.project); String id = p.id();
        book.stage(id, "literature", "doing");
        what(j, "문헌조사원이 검색어를 정하는 중");
        String[] q = ask(j, LabBook.SCOUT.id(), LabBook.searchPrompt(focus, digest(id, 2500)));
        List<String> queries = LabBook.queries(q[0], focus.isBlank() ? p.question().isBlank() ? p.title() : p.question() : focus);
        List<String> sources = LabBook.field(p.field()).sources();
        List<LabBook.Source> found = new ArrayList<>(); List<String> failed = new ArrayList<>();
        for (String source : sources) {
            check(j); what(j, LabSources.name(source) + "에서 논문 찾는 중");
            int before = found.size(); String why = null;
            for (String query : queries) {
                try { found.addAll(fetch(j, source, query)); }
                catch (Stopped s) { throw s; }
                catch (Exception e) { why = e.getMessage(); }
                if ("arxiv".equals(source)) Thread.sleep(1200);
            }
            if (found.size() == before && why != null) failed.add(LabSources.name(source));
        }
        List<LabBook.Source> merged = LabSources.merge(found, 10);
        if (merged.isEmpty()) {
            book.note(id, "search", LabBook.SCOUT.id(), "찾은 논문이 없어요" + (failed.isEmpty() ? "." : " (" + String.join(", ", failed) + " 응답 없음)."), q[1], obj("focus", focus, "queries", new JSONArray(queries), "sources", new JSONArray()));
            book.stage(id, "literature", "done");
            return;
        }
        List<LabBook.Source> keyed = book.sources(id, merged);
        what(j, "문헌조사원이 논문 " + keyed.size() + "편을 읽는 중");
        String[] s = ask(j, LabBook.SCOUT.id(), LabBook.literature(focus, LabBook.sourceList(keyed, true)));
        book.claims(id, LabBook.claims(s[0], LabBook.SCOUT.name()));
        JSONArray keys = new JSONArray(); for (LabBook.Source k : keyed) keys.put(k.key());
        book.note(id, "search", LabBook.SCOUT.id(), LabBook.prose(s[0]), s[1], obj("focus", focus, "queries", new JSONArray(queries), "sources", keys, "failed", new JSONArray(failed)));
        book.stage(id, "literature", "done");
    }
    private List<LabBook.Source> fetch(Job j, String source, String query) throws Exception {
        String body = get(j, LabSources.url(source, query));
        return switch (source) {
            case "arxiv" -> LabSources.arxiv(body);
            case "semantic" -> LabSources.semantic(body);
            case "pubmed" -> { List<String> ids = LabSources.pubmedIds(body); yield ids.isEmpty() ? List.<LabBook.Source>of() : LabSources.pubmed(get(j, LabSources.pubmedFetch(ids))); }
            default -> LabSources.openAlex(body);
        };
    }
    private String get(Job j, String url) throws Exception {
        check(j);
        AtomicReference<Runnable> cancel = new AtomicReference<>(); j.cancels.add(cancel);
        try (OpenAiAccount.Http.Exchange r = OpenAiAccount.Http.open("GET", url, Map.of("User-Agent", "GyeotLab/1.0 (research assistant)", "Accept", "application/json, application/atom+xml, text/xml"), null, 25_000, cancel)) {
            if (r.status == 429) throw new IOException("요청이 많다고 잠시 거절했어요.");
            if (r.status >= 300) throw new IOException("HTTP " + r.status);
            return OpenAiAccount.Http.read(r.body, 3_000_000);
        } finally { j.cancels.remove(cancel); }
    }

    // ── 실험 ─────────────────────────────────────────────────────────

    private void experiment(Job j, String goal) throws Exception {
        LabBook.Project p = project(j.project); String id = p.id();
        if (!LabPython.ready(app)) throw new IllegalStateException("계산 환경이 아직 없어요. 진행판에서 ‘계산 환경 준비’를 먼저 해 주세요.");
        String aim = goal == null || goal.isBlank() ? (p.question().isBlank() ? p.title() : p.question()) : goal.strip();
        book.stage(id, "experiment", "doing");
        int n = book.experiments(id) + 1;
        what(j, "실험가가 실험 E" + n + " 코드를 쓰는 중");
        String data = LabBook.dataList(new File(book.files(id), "data"));
        String[] a = ask(j, LabBook.EXPERIMENTER.id(), LabBook.experiment(aim, digest(id, 3000) + (idea(id).isBlank() ? "" : "\n\n연구하는 아이디어:\n" + idea(id)) + prereg(id), LabPython.available(app), data));
        String code = LabBook.code(a[0]), about = LabBook.beforeCode(a[0]);
        if (code.isBlank()) throw new IOException("실험가가 코드를 보내지 않았어요. 다시 시도해 주세요.");
        LabPython.Run run = null; int attempts = 0;
        for (int attempt = 0; attempt < 3; attempt++) {
            check(j); attempts = attempt + 1;
            what(j, "리눅스에서 실험 E" + n + " 실행 중" + (attempt > 0 ? " · 고친 코드 " + attempt : ""));
            AtomicReference<Runnable> cancel = new AtomicReference<>(); j.cancels.add(cancel);
            try { run = LabPython.run(app, id, n, code, book.files(id), cancel); } finally { j.cancels.remove(cancel); }
            check(j);
            if (run.exit() == 0 || attempt == 2) break;
            what(j, "실험가가 오류를 고치는 중 · E" + n);
            String[] f = ask(j, LabBook.EXPERIMENTER.id(), LabBook.fix(code, LabBook.clip(tail(run.output(), 3000), 3000)));
            String fixed = LabBook.code(f[0]); if (fixed.isBlank() || fixed.equals(code)) break;
            code = fixed;
        }
        JSONArray figures = new JSONArray(); for (File f : run.figures()) figures.put(f.getName());
        JSONArray tables = new JSONArray(); for (File f : run.tables()) tables.put(f.getName());
        book.note(id, "run", LabBook.EXPERIMENTER.id(), about, a[1], obj("n", n, "goal", aim, "code", code, "exit", run.exit(), "millis", run.millis(), "output", run.output(), "figures", figures, "tables", tables, "attempts", attempts));
        book.stage(id, "experiment", "done");
        changed(id);
        if (run.exit() != 0 && run.output().isBlank()) return;
        book.stage(id, "analysis", "doing");
        what(j, "실험 E" + n + " 결과를 해석하는 중");
        String[] s = ask(j, LabBook.EXPERIMENTER.id(), LabBook.analysis(aim, n, code, run.output(), run.figures().size()));
        book.claims(id, LabBook.claims(s[0], LabBook.EXPERIMENTER.name()));
        book.note(id, "analysis", LabBook.EXPERIMENTER.id(), LabBook.prose(s[0]), s[1], obj("n", n));
        book.stage(id, "analysis", "done");
    }
    private static String tail(String s, int max) { return s.length() > max ? s.substring(s.length() - max) : s; }

    // ── 비평 · 보고서 · 계획 · 한 명에게 ─────────────────────────────

    private void critique(Job j) throws Exception {
        String id = j.project; book.stage(id, "review", "doing");
        what(j, "비평가가 근거를 따지는 중");
        String[] a = ask(j, LabBook.CRITIC.id(), LabBook.critique(digest(id, 6000), LabBook.ledger(book.claims(id))));
        book.claims(id, LabBook.claims(a[0], LabBook.CRITIC.name()));
        book.note(id, "critique", LabBook.CRITIC.id(), LabBook.prose(a[0]), a[1], null);
        book.stage(id, "review", "done");
    }
    private void report(Job j) throws Exception { report(j, false); }
    /** mid: a 중간 보고서 while 자율 연구 goes on (the stage stays open; the final one replaces it). */
    private void report(Job j, boolean mid) throws Exception {
        String id = j.project; if (!mid) book.stage(id, "report", "doing");
        what(j, mid ? "기록원이 중간 보고서를 쓰는 중" : "기록원이 보고서를 쓰는 중");
        String[] a = ask(j, LabBook.SCRIBE.id(), LabBook.report(digest(id, 9000), LabBook.ledger(book.claims(id)), LabBook.sourceList(book.sources(id), false)));
        String text = LabBook.prose(a[0]);
        book.report(id, text);
        book.note(id, "report", LabBook.SCRIBE.id(), text, a[1], mid ? obj("mid", true) : null);
        if (!mid) book.stage(id, "report", "done");
    }
    private void plan(Job j) throws Exception {
        String id = j.project; book.stage(id, "plan", "doing");
        what(j, "책임연구원이 계획을 세우는 중");
        String[] a = ask(j, LabBook.PI.id(), LabBook.plan(digest(id, 2500)));
        book.note(id, "plan", LabBook.PI.id(), LabBook.prose(a[0]), a[1], null);
        book.stage(id, "plan", "done");
    }
    private void direct(Job j, LabBook.Role to, String message) throws Exception {
        what(j, LabBook.ida(to.name()) + " 답하는 중");
        String[] a = ask(j, to.id(), LabBook.direct(message, digest(j.project, 3000)));
        book.claims(j.project, LabBook.claims(a[0], to.name()));
        book.note(j.project, "say", to.id(), LabBook.prose(a[0]), a[1], obj("direct", true));
    }

    // ── 새 연구: 아이디어 · 새로움 확인 · 논문 · 동료 심사 ──────────────

    /**
     * 새 아이디어: 책임연구원 proposes up to five testable ideas; each is searched for in the field's papers (the closest kept as
     * sources), 문헌조사원 scores how new, how testable here and how much it matters against them, and the best becomes the
     * run's idea (state "idea"), which the next steps and the paper follow.
     */
    private void ideate(Job j, String focus) throws Exception {
        LabBook.Project p = project(j.project); String id = p.id();
        book.stage(id, "idea", "doing");
        what(j, "책임연구원이 새 아이디어를 내는 중");
        String[] a = ask(j, LabBook.PI.id(), LabBook.ideas(focus == null || focus.isBlank() ? (p.question().isBlank() ? p.title() : p.question()) : focus, digest(id, 3000)));
        List<LabBook.Idea> ideas = LabBook.ideaList(a[0]);
        if (ideas.isEmpty()) throw new IOException("책임연구원이 아이디어를 내지 못했어요. 다시 해 주세요.");
        String source = LabBook.field(p.field()).sources().get(0);
        StringBuilder compared = new StringBuilder(); List<List<LabBook.Source>> nearest = new ArrayList<>();
        for (int i = 0; i < ideas.size(); i++) {
            LabBook.Idea idea = ideas.get(i); check(j);
            what(j, "새로운지 확인하는 중 · " + (i + 1) + "/" + ideas.size() + " " + idea.title());
            List<LabBook.Source> found = new ArrayList<>();
            for (String q : idea.queries().isEmpty() ? List.of(idea.title()) : idea.queries()) {
                try { found.addAll(fetch(j, source, q)); } catch (Stopped s) { throw s; } catch (Exception unreachable) { /* the other words, or none */ }
                if ("arxiv".equals(source)) Thread.sleep(1200);
                if (found.isEmpty() && !"openalex".equals(source)) { try { found.addAll(fetch(j, "openalex", q)); } catch (Stopped s) { throw s; } catch (Exception ignored) {} }
            }
            List<LabBook.Source> keyed = book.sources(id, LabSources.merge(found, 3));
            nearest.add(keyed);
            compared.append("아이디어 ").append(i + 1).append(". ").append(idea.title()).append("\n가설: ").append(idea.hypothesis()).append("\n새로운 점(제안자 주장): ").append(idea.novelty())
                .append("\n확인 방법: ").append(idea.test()).append("\n가장 가까운 논문:\n").append(keyed.isEmpty() ? "(찾지 못함)" : LabBook.sourceList(keyed, true)).append("\n\n");
        }
        what(j, "문헌조사원이 새로움을 따지는 중");
        String[] s = ask(j, LabBook.SCOUT.id(), LabBook.novelty(compared.toString().strip()));
        java.util.Map<Integer, LabBook.IdeaScore> scores = LabBook.scores(s[0]);
        int best = 0, bestTotal = -1; JSONArray list = new JSONArray(); StringBuilder text = new StringBuilder();
        for (int i = 0; i < ideas.size(); i++) {
            LabBook.IdeaScore sc = scores.getOrDefault(i + 1, new LabBook.IdeaScore(3, 3, 3, "", ""));
            if (sc.total() > bestTotal) { bestTotal = sc.total(); best = i; }
            JSONObject o = ideas.get(i).json();
            o.put("novelty_score", sc.novelty()).put("feasible", sc.feasible()).put("impact", sc.impact()).put("closest", sc.closest()).put("note", sc.note());
            list.put(o);
        }
        for (int i = 0; i < ideas.size(); i++) {
            JSONObject o = list.getJSONObject(i);
            text.append(i == best ? "**★ " : "**").append(i + 1).append(". ").append(o.optString("title")).append("**\n")
                .append(o.optString("hypothesis")).append("\n새로움 ").append(o.optInt("novelty_score")).append("/5 · 확인 가능 ").append(o.optInt("feasible")).append("/5 · 의미 ").append(o.optInt("impact")).append("/5")
                .append(o.optString("closest").isBlank() ? "" : " · 가장 가까운 연구 " + o.optString("closest")).append(o.optString("note").isBlank() ? "" : "\n" + o.optString("note")).append("\n\n");
        }
        text.append("고른 아이디어: ").append(best + 1).append("번 · ").append(list.getJSONObject(best).optString("title"));
        JSONObject state = book.state(id); state.put("ideas", list); state.put("idea", list.getJSONObject(best)); book.state(id, state);
        book.note(id, "ideas", LabBook.PI.id(), text.toString(), a[1] + " · 새로움 판단 " + s[1], obj("ideas", list, "chosen", best, "focus", focus == null ? "" : focus));
        book.claims(id, List.of(new LabBook.Claim("가설: " + ideas.get(best).hypothesis(), "check", "새 아이디어 " + (best + 1) + "번", LabBook.PI.name(), System.currentTimeMillis())));
        book.stage(id, "idea", "done");
    }
    /** The run's chosen idea as the paper and the next steps read it ("" when none was chosen). */
    private String idea(String id) {
        JSONObject i = book.state(id).optJSONObject("idea"); if (i == null) return "";
        return i.optString("title") + "\n가설: " + i.optString("hypothesis") + "\n확인 방법: " + i.optString("test") + "\n예상: " + i.optString("outcome");
    }
    /** 논문: 기록원 writes it from the record only; then 동료 심사 and the revision. */
    private void paper(Job j, String extra) throws Exception {
        String id = j.project; book.stage(id, "paper", "doing");
        what(j, "기록원이 논문을 쓰는 중");
        String experiments = LabBook.experiments(book.notes(id));
        String[] a = ask(j, LabBook.SCRIBE.id(), LabBook.paper(digest(id, 7000), LabBook.ledger(book.claims(id)), LabBook.sourceList(book.sources(id), false), experiments, idea(id), extra));
        String text = LabBook.prose(a[0]);
        book.paper(id, text);
        book.note(id, "paper", LabBook.SCRIBE.id(), text, a[1], obj("version", 1));
        changed(id);
        peerAndRevise(j);
    }
    /** 동료 심사 by 비평가 and 이론가 (on their own models, at once), then 기록원's revision answering them. */
    private void peerAndRevise(Job j) throws Exception {
        String id = j.project; book.stage(id, "paper", "doing");
        String paper = book.paper(id), ledger = LabBook.ledger(book.claims(id)), experiments = LabBook.experiments(book.notes(id));
        int version = 1; for (LabBook.Note n : book.notes(id)) if ("paper".equals(n.kind())) version = Math.max(version, n.data().optInt("version", 1));
        what(j, "동료 심사 중 · 비평가와 이론가");
        Map<String, Future<String[]>> asked = new LinkedHashMap<>();
        for (String reviewer : List.of(LabBook.CRITIC.id(), LabBook.THEORIST.id())) asked.put(reviewer, voices.submit(() -> ask(j, reviewer, LabBook.peer(paper, ledger, experiments))));
        StringBuilder reviews = new StringBuilder(); int answered = 0;
        for (Map.Entry<String, Future<String[]>> e : asked.entrySet()) {
            try {
                String[] r = e.getValue().get(); LabBook.Review score = LabBook.review(r[0]);
                book.note(id, "peer", e.getKey(), LabBook.prose(r[0]), r[1], obj("version", version, "novelty", score.novelty(), "soundness", score.soundness(), "clarity", score.clarity(), "significance", score.significance(), "decision", score.decision()));
                reviews.append("[심사위원 · ").append(LabBook.role(e.getKey()).name()).append(" · ").append(score.line()).append("]\n").append(LabBook.prose(r[0])).append("\n\n");
                answered++; changed(id);
            } catch (java.util.concurrent.ExecutionException failed) {
                Throwable cause = failed.getCause(); if (cause instanceof Stopped || j.stopped) throw new Stopped();
                book.note(id, "error", e.getKey(), cause == null ? "심사하지 못했어요." : cause.getMessage(), "", null);
            }
        }
        if (answered == 0) throw new IOException("동료 심사를 받지 못했어요.");
        check(j);
        what(j, "기록원이 심사를 반영해 논문을 고치는 중");
        String[] a = ask(j, LabBook.SCRIBE.id(), LabBook.revise(paper, reviews.toString(), ledger, LabBook.sourceList(book.sources(id), false), experiments));
        String text = LabBook.prose(a[0]);
        book.paper(id, text);
        book.note(id, "paper", LabBook.SCRIBE.id(), text, a[1], obj("version", version + 1, "revised", true));
        book.stage(id, "paper", "done");
    }

    // ── 가설→결론 파이프라인 ──────────────────────────────────────────

    private static final String[] PIPE = {"ideate", "search", "prereg", "experiment:0", "experiment:1", "critique", "verdict", "paper"};
    private void pipeline(Job j) throws Exception {
        String id = j.project;
        for (;;) {
            check(j);
            JSONObject s = book.state(id); int at = s.optInt("pipe", 0);
            if (at >= PIPE.length) {
                s.put("pipeline", false); book.state(id, s);
                finish(id, "done", "가설부터 결론까지 마쳤어요 · 판정은 토론방에, 심사를 거쳐 고친 논문은 산출물 탭에 있어요."); return;
            }
            LabBook.Project p = project(id); if (p == null) return;
            int left = p.budget() - book.spent(id);
            if (left <= 2) { finish(id, "paused", "이번 한도(AI 답 " + p.budget() + "번)를 다 써서 멈췄어요. 진행판에서 ‘이어서 연구’를 누르면 남은 단계(" + (at + 1) + "/" + PIPE.length + ")부터 계속해요."); return; }
            String step = PIPE[at];
            // Moved on first: an experiment waiting for 승인 goes on from the step after it.
            s.put("pipe", at + 1); book.state(id, s);
            String idea = idea(id);
            switch (step) {
                case "ideate" -> {
                    String focus = s.optString("pipeFocus");
                    if (!idea.isBlank() && focus.isBlank()) { book.note(id, "system", "", "고른 아이디어로 이어가요 · " + s.optJSONObject("idea").optString("title"), "", null); continue; }
                    note(id, at, "ideate", focus, "가설이 될 새 아이디어를 내고 새로운지 확인해요");
                    ideate(j, focus);
                }
                case "search" -> { note(id, at, "search", ideaTitle(id), "이 가설과 가까운 연구를 찾아요"); search(j, ideaTitle(id)); }
                case "prereg" -> { note(id, at, "prereg", "", "실험 전에 예측과 반증 기준을 정해 둬요"); prereg(j); }
                case "experiment:0", "experiment:1" -> {
                    List<String> planned = registered(id); int k = step.endsWith("1") ? 1 : 0;
                    if (k >= planned.size()) continue;
                    if (!LabPython.ready(app)) { book.note(id, "system", "", "계산 환경이 없어 실험 E" + (k + 1) + "은 건너뛰어요 · 진행판에서 ‘계산 환경 준비’를 하면 다음엔 돌려요.", "", null); continue; }
                    note(id, at, "experiment", planned.get(k), "사전 등록한 실험 E" + (k + 1) + "을 돌려요");
                    if (p.approve()) { hold(id, "experiment", new LabBook.Step("experiment", planned.get(k), "사전 등록한 실험이에요."), "사전 등록한 실험을 리눅스에서 돌려도 될까요?\n\n" + planned.get(k)); return; }
                    experiment(j, planned.get(k));
                }
                case "critique" -> { note(id, at, "critique", "", "결과와 주장의 근거를 따져요"); critique(j); }
                case "verdict" -> { note(id, at, "verdict", "", "사전 등록한 예측과 결과를 맞춰 판정해요"); verdict(j); }
                default -> { note(id, at, "paper", "", "논문으로 쓰고 동료 심사를 받아 고쳐요"); paper(j, ""); exportQuietly(j); }
            }
        }
    }
    private void note(String id, int at, String action, String focus, String why) throws IOException {
        book.note(id, "step", LabBook.PI.id(), why, "", obj("action", action, "focus", focus, "pipeline", (at + 1) + "/" + PIPE.length));
        changed(id);
    }
    private String ideaTitle(String id) { JSONObject i = book.state(id).optJSONObject("idea"); if (i == null) { LabBook.Project p = project(id); return p.question().isBlank() ? p.title() : p.question(); } return i.optString("title") + " — " + i.optString("hypothesis"); }
    /** The 사전 등록 as the experiments and the verdict read it ("" when there is none). */
    private String prereg(String id) { String t = book.state(id).optString("prereg"); return t.isBlank() ? "" : "\n\n사전 등록:\n" + LabBook.clip(t, 2500); }
    private List<String> registered(String id) {
        JSONArray a = book.state(id).optJSONArray("registered"); List<String> out = new ArrayList<>();
        for (int i = 0; a != null && i < a.length(); i++) out.add(a.optString(i));
        return out;
    }
    private void prereg(Job j) throws Exception {
        String id = j.project; book.stage(id, "prereg", "doing");
        what(j, "책임연구원이 사전 등록을 쓰는 중");
        String[] a = ask(j, LabBook.PI.id(), LabBook.prereg(digest(id, 3500), idea(id), LabBook.dataList(new File(book.files(id), "data"))));
        String text = LabBook.prose(a[0]);
        JSONObject s = book.state(id); s.put("prereg", text); s.put("registered", new JSONArray(LabBook.registered(a[0]))); book.state(id, s);
        book.note(id, "prereg", LabBook.PI.id(), text, a[1], obj("experiments", new JSONArray(LabBook.registered(a[0]))));
        book.stage(id, "prereg", "done");
    }
    private void verdict(Job j) throws Exception {
        String id = j.project; book.stage(id, "verdict", "doing");
        what(j, "책임연구원이 예측과 결과를 맞춰 판정하는 중");
        String[] a = ask(j, LabBook.PI.id(), LabBook.verdict(digest(id, 3000), book.state(id).optString("prereg"), LabBook.experiments(book.notes(id)), LabBook.ledger(book.claims(id))));
        String v = LabBook.verdictOf(a[0]);
        book.claims(id, LabBook.claims(a[0], LabBook.PI.name()));
        JSONObject s = book.state(id); s.put("verdict", v); book.state(id, s);
        book.note(id, "verdict", LabBook.PI.id(), LabBook.prose(a[0]), a[1], obj("verdict", v));
        book.stage(id, "verdict", "done");
    }

    // ── 구글 시트 ─────────────────────────────────────────────────────

    private static final java.util.regex.Pattern SHEET = java.util.regex.Pattern.compile("/spreadsheets/d/([A-Za-z0-9_-]{20,})");
    /**
     * 구글 시트 가져오기: a Google Sheet (its address) read as CSV through the Drive connection and kept as the project's data
     * (data/<name>.csv), where experiments read it. Returns what was kept. Blocking.
     */
    String importSheet(String project, String address) throws Exception {
        GoogleDriveAccount drive = store().drive;
        if (!drive.connected()) throw new IllegalStateException("Google Drive가 연결되지 않았어요. 설정 → 커넥터 → Google Drive에서 연결해 주세요.");
        java.util.regex.Matcher m = SHEET.matcher(address == null ? "" : address);
        if (!m.find()) throw new IllegalArgumentException("구글 시트 주소를 붙여 주세요 (docs.google.com/spreadsheets/d/…).");
        String sheet = m.group(1), name = "sheet";
        try { name = new JSONObject(drive.get(dev.jun.gyeot.core.GoogleDrive.metadataUrl(sheet), 64 * 1024)).optString("name", "sheet"); } catch (Exception unnamed) { /* the address is enough */ }
        String csv = drive.get(dev.jun.gyeot.core.GoogleDrive.readUrl(sheet, "application/vnd.google-apps.spreadsheet"), 8 * 1024 * 1024);
        if (csv == null || csv.isBlank()) throw new IOException("시트가 비어 있어요 (첫 번째 탭을 읽어요).");
        String file = name.replaceAll("[^A-Za-z0-9가-힣._-]+", "_").replaceAll("_+", "_"); if (file.isBlank() || file.equals("_")) file = "sheet";
        file = (file.length() > 40 ? file.substring(0, 40) : file).replaceAll("[가-힣]", "") ; if (file.replace("_", "").isBlank()) file = "sheet" + System.currentTimeMillis() % 10000;
        File dir = new File(book.files(project), "data"); dir.mkdirs();
        java.nio.file.Files.write(new File(dir, file + ".csv").toPath(), csv.getBytes(java.nio.charset.StandardCharsets.UTF_8));
        String described = LabBook.dataList(dir);
        int rows = Math.max(0, csv.split("\n", -1).length - 1);
        book.note(project, "data", "", "구글 시트 ‘" + name + "’를 데이터로 가져왔어요 · data/" + file + ".csv · " + rows + "행\n실험가가 이 데이터를 읽어 분석할 수 있어요.", "", obj("file", file + ".csv", "sheet", sheet, "name", name));
        changed(project);
        return described;
    }
    /**
     * 구글 시트로: every result table the experiments wrote (e<n>-*.csv) as its own Google Sheet in the user's Drive (CSV
     * converted by Drive), the links kept in a note. Returns how many. Blocking.
     */
    int exportSheets(String project) throws Exception {
        GoogleDriveAccount drive = store().drive;
        if (!drive.connected()) throw new IllegalStateException("Google Drive가 연결되지 않았어요. 설정 → 커넥터 → Google Drive에서 연결해 주세요.");
        File[] tables = book.files(project).listFiles((d, name) -> name.matches("e\\d+-.*\\.csv"));
        if (tables == null || tables.length == 0) throw new IllegalStateException("아직 결과 표가 없어요. 실험이 out/에 CSV를 저장하면 여기서 시트로 보낼 수 있어요.");
        java.util.Arrays.sort(tables);
        LabBook.Project p = project(project); StringBuilder links = new StringBuilder(); JSONArray made = new JSONArray();
        for (File f : tables) {
            JSONObject meta = dev.jun.gyeot.core.GoogleDrive.upload("들퍼레 연구실 · " + p.title() + " · " + f.getName().replace(".csv", ""), "application/vnd.google-apps.spreadsheet", "");
            JSONObject got = drive.upload(meta, "text/csv", java.nio.file.Files.readAllBytes(f.toPath()), null);
            String link = got.optString("webViewLink", "https://docs.google.com/spreadsheets/d/" + got.optString("id"));
            links.append("- [").append(f.getName()).append("](").append(link).append(")\n"); made.put(new JSONObject().put("file", f.getName()).put("link", link));
        }
        book.note(project, "sheets", "", "결과 표 " + tables.length + "개를 구글 시트로 보냈어요:\n" + links.toString().strip(), "", obj("sheets", made));
        changed(project);
        return tables.length;
    }
    /** At the end of 가설→결론, the result tables go to Google Sheets when Drive is connected (quietly, a failure only noted). */
    private void exportQuietly(Job j) {
        try { if (store().drive.connected()) { what(j, "결과 표를 구글 시트로 보내는 중"); exportSheets(j.project); } }
        catch (Exception e) { try { book.note(j.project, "system", "", "결과 표를 구글 시트로 보내지 못했어요 · " + (e.getMessage() == null ? "" : e.getMessage()), "", null); } catch (IOException ignored) {} }
    }

    // ── 자율 연구 ─────────────────────────────────────────────────────

    private void loop(Job j) throws Exception {
        String id = j.project;
        if (book.state(id).optBoolean("pipeline")) { pipeline(j); return; }
        boolean planned = false; for (LabBook.Note n : book.notes(id)) if ("plan".equals(n.kind())) planned = true;
        if (!planned) plan(j);
        for (int steps = 0; ; steps++) {
            check(j);
            LabBook.Project p = project(id); if (p == null) return;
            int left = p.budget() - book.spent(id);
            if (left <= 2) { finish(id, "paused", "이번 한도(AI 답 " + p.budget() + "번)를 다 써서 멈췄어요. 진행판에서 ‘이어서 연구’를 누르면 한도를 새로 채워 계속해요."); return; }
            if (steps >= 16) { finish(id, "paused", "한 번에 16단계까지 진행해요. 결과를 보고 ‘이어서 연구’를 눌러 주세요."); return; }
            // Half the budget used: a 중간 보고서 first, so a result comes before the end (once a run).
            if (p.budget() >= 12 && book.spent(id) >= p.budget() / 2 && !book.state(id).optBoolean("midReport")) {
                JSONObject s = book.state(id); s.put("midReport", true); book.state(id, s);
                report(j, true); changed(id);
            }
            what(j, "책임연구원이 다음 단계를 정하는 중");
            boolean python = LabPython.ready(app), asked = book.state(id).optBoolean("asked");
            String chosen = idea(id);
            String[] a = ask(j, LabBook.PI.id(), LabBook.next(digest(id, 6000) + (chosen.isBlank() ? "" : "\n\n지금 연구하는 새 아이디어:\n" + chosen), LabBook.stages(book.state(id)), left - 1, python, asked));
            LabBook.Step step = LabBook.step(a[0]);
            if ("experiment".equals(step.action()) && !python) step = new LabBook.Step("critique", step.focus(), "계산 환경이 없어 실험 대신 검토해요.");
            if ("ask".equals(step.action()) && asked) step = new LabBook.Step("critique", step.focus(), step.why());
            // What is known so far, in a few lines, before the next step (책임연구원 says it with its choice: no extra answer).
            if (!step.learned().isBlank()) book.note(id, "learned", LabBook.PI.id(), step.learned(), a[1], null);
            book.note(id, "step", LabBook.PI.id(), step.why(), a[1], obj("action", step.action(), "focus", step.focus()));
            changed(id);
            switch (step.action()) {
                case "search" -> search(j, step.focus());
                case "debate" -> debate(j, step.focus().isBlank() ? (p.question().isBlank() ? p.title() : p.question()) : step.focus());
                case "experiment" -> {
                    if (p.approve()) { hold(id, "experiment", step, "이 실험을 리눅스에서 돌려도 될까요?\n\n" + step.focus() + (step.why().isBlank() ? "" : "\n\n이유: " + step.why())); return; }
                    experiment(j, step.focus());
                }
                case "critique" -> critique(j);
                case "ideate" -> ideate(j, step.focus());
                case "paper" -> { paper(j, ""); finish(id, "done", "자율 연구를 마쳤어요. 산출물 탭에서 동료 심사를 거쳐 고친 논문을 볼 수 있어요."); return; }
                case "ask" -> { hold(id, "ask", step, step.focus().isBlank() ? step.why() : step.focus()); return; }
                default -> { report(j); finish(id, "done", "자율 연구를 마쳤어요. 산출물 탭에서 보고서를 볼 수 있어요."); return; }
            }
        }
    }
    private void hold(String id, String action, LabBook.Step step, String text) throws IOException {
        book.pending(id, obj("action", action, "focus", step.focus(), "why", step.why()));
        book.note(id, "ask", LabBook.PI.id(), text, "", obj("action", action, "focus", step.focus()));
        book.autopilot(id, "waiting");
        changed(id);
    }
    private void finish(String id, String state, String text) throws IOException {
        book.autopilot(id, state);
        book.note(id, "system", "", text, "", null);
    }

    // ── telling the user ─────────────────────────────────────────────

    private static final String CHANNEL = "lab_results";
    /** A job ended while its project is not on screen: what happened, and a tap back to it. */
    private void tell(String project, String title, String failure) {
        if (project.equals(onScreen)) return;
        LabBook.Project p = project(project); if (p == null) return;
        String state = book.autopilot(project);
        String headline = failure != null ? LabBook.eul(title) + " 마치지 못했어요" : "waiting".equals(state) ? "연구원이 기다리고 있어요" : "자율 연구".equals(title) && "done".equals(state) ? "자율 연구를 마쳤어요" : LabBook.eul(title) + " 마쳤어요";
        String detail = failure != null ? failure : "waiting".equals(state) ? "승인하거나 답해 주면 이어서 연구해요." : p.title();
        try {
            NotificationManager nm = app.getSystemService(NotificationManager.class); if (nm == null) return;
            nm.createNotificationChannel(new NotificationChannel(CHANNEL, "연구실 소식", NotificationManager.IMPORTANCE_DEFAULT));
            Intent open = new Intent(app, LabActivity.class).putExtra(LabActivity.OPEN, project).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            PendingIntent tap = PendingIntent.getActivity(app, project.hashCode(), open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
            Notification n = new Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("연구실 · " + headline)
                .setContentText(detail).setStyle(new Notification.BigTextStyle().bigText(p.title() + "\n" + detail)).setAutoCancel(true).setContentIntent(tap).build();
            nm.notify(("lab:" + project).hashCode(), n);
        } catch (RuntimeException ignored) {}
    }
}
