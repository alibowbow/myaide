package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Owns submitted lessons for the lifetime of the app. The original board is committed before any UI plays it. */
final class BoardJobs {
    record Payload(String text, List<MainRequest.Attachment> files) {}
    record Result(String boardId, String lessonId, String title, BoardStore.Doc doc, String error) {}
    interface Listener { void finished(Result result); default void changed() {} }
    private static final class Job {
        final AtomicReference<Runnable> abort = new AtomicReference<>();
        volatile boolean cancelled;
        boolean committed;
    }
    private final Executor network = Executors.newCachedThreadPool();
    final Executor disk = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    final BoardStore boards;
    private final Map<String, Job> running = new LinkedHashMap<>();
    private final Set<Listener> listeners = new LinkedHashSet<>();
    private final Map<String, String> failures = new HashMap<>();

    BoardJobs(Context app) { boards = new BoardStore(app); }
    void watch(Listener listener) { listeners.add(listener); }
    void unwatch(Listener listener) { listeners.remove(listener); }
    boolean running(String id) { return running.containsKey(id); }
    String activeBoard() { return running.keySet().stream().findFirst().orElse(null); }
    private void changed() { for (Listener l : List.copyOf(listeners)) l.changed(); }
    String takeError(String id) { return failures.remove(id); }

    boolean cancel(String id) {
        Job job = running.get(id);
        if (job == null) return false;
        synchronized (job) {
            if (job.committed) return false;
            job.cancelled = true;
        }
        running.remove(id); changed();
        Runnable abort = job.abort.getAndSet(null);
        if (abort != null) network.execute(abort);
        return true;
    }

    void submit(String id, BoardTeacher.Brain brain, Callable<Payload> prepare,
                JSONArray source, Board.Measure ruler, float scale, boolean hand, boolean chalk) {
        if (running(id)) throw new IllegalStateException("이 판의 수업이 이미 진행 중이에요.");
        Job job = new Job();
        running.put(id, job); failures.remove(id); changed();
        network.execute(() -> {
            try {
                Payload payload = prepare.call();
                if (job.cancelled) return;
                BoardScript.Lesson plan = BoardTeacher.plan(brain, payload.text(), payload.files(), source,
                        scale, chalk, "Korean", job.abort, () -> job.cancelled);
                if (!job.cancelled) disk.execute(() -> {
                    try {
                        Result result = null;
                        synchronized (boards) {
                            BoardStore.Entry entry = boards.entry(id);
                            if (entry != null && !job.cancelled) {
                                String lessonId = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
                                BoardStore.Doc doc = compose(boards.load(id), plan, lessonId, ruler, scale, hand);
                                String title = entry.title().isBlank() ? plan.title() : entry.title();
                                synchronized (job) {
                                    if (job.cancelled) return;
                                    boards.save(id, doc.items(), doc.lessons(), doc.classroom(), title, System.currentTimeMillis());
                                    job.committed = true;
                                }
                                result = new Result(id, lessonId, title, doc, "");
                            }
                        }
                        complete(id, job, result);
                    } catch (Exception failed) { fail(id, job, failed); }
                });
            } catch (Exception failed) { fail(id, job, failed); }
            finally { job.abort.set(null); }
        });
    }

    private void fail(String id, Job job, Exception failed) {
        String why = failed.getMessage();
        complete(id, job, new Result(id, "", "", null,
                why == null || why.isBlank() ? "판서를 준비하지 못했어요." : why));
    }

    private void complete(String id, Job job, Result result) {
        main.post(() -> {
            if (running.get(id) != job) return;
            running.remove(id); changed();
            if (result == null) return;
            if (!result.error().isEmpty()) failures.put(id, result.error());
            for (Listener listener : List.copyOf(listeners)) listener.finished(result);
        });
    }

    static BoardStore.Doc compose(BoardStore.Doc base, BoardScript.Lesson lesson, String id,
                                  Board.Measure ruler, float scale, boolean hand) {
        JSONArray items = base.items(), says = arr();
        JSONObject record = obj("id", id, "title", lesson.title(), "says", says);
        BoardScript.Flow flow = new BoardScript.Flow(items, ruler, scale, hand);
        for (int i = 0; i < lesson.steps().size(); i++) {
            var step = lesson.steps().get(i);
            says.put(step.say());
            JSONArray before = items;
            for (JSONObject op : step.draw()) if ("update".equals(op.optString("op"))) {
                String target = op.optString("_target");
                BoardReplay.remember(record, BoardReplay.find(items, target), -1);
                items = BoardAgent.update(items, op, ruler);
            }
            if (items != before) {
                flow.reflow(items);
                for (JSONObject op : step.draw()) if (op.has("_target"))
                    BoardReplay.remember(record, BoardReplay.find(items, op.optString("_target")), i);
            }
            if (flow.y() > Board.DEEPEST - 2000 && step.draw().stream().anyMatch(op -> !"update".equals(op.optString("op"))))
                throw new IllegalArgumentException("이 판이 가득 찼어요. 새 판에서 계속해 주세요.");
            for (JSONObject item : flow.place(step)) {
                if (items.length() >= Board.ITEMS || Board.bounds(item, ruler)[3] > Board.DEEPEST)
                    throw new IllegalArgumentException("이 판이 가득 찼어요. 새 판에서 계속해 주세요.");
                items.put(Board.inLesson(item, id, i)); BoardReplay.remember(record, item, i);
            }
        }
        if (Board.bottom(items, ruler) > Board.DEEPEST) throw new IllegalArgumentException("판의 높이를 넘어요.");
        base.lessons().put(record);
        return new BoardStore.Doc(items, BoardStore.lessons(base.lessons()), base.classroom());
    }
}
