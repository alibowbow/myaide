package dev.jun.gyeot.core;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Bounded DAG scheduler. All validation happens before any request; workers cannot submit children. */
public final class TeamRun implements AutoCloseable {
    public interface Worker {
        String run(JSONObject profile, String assignment, GenerationPool.Control control) throws Exception;
        /** Non-blocking app evidence snapshot for incomplete work. Never substitutes for completion. */
        default String partial(String taskId, GenerationPool.Control control) { return control.partial(); }
    }
    private static final Set<String> FINAL = Set.of("completed", "failed", "blocked", "cancelled", "timed_out");
    private final Map<String, JSONObject> roster;
    private final LinkedHashMap<String, Task> tasks = new LinkedHashMap<>();
    private final int parallel;
    private final long timeoutMillis;
    private final Worker worker;
    private final ModelRouting routing;
    private final Runnable guard;
    private final Consumer<JSONArray> changed;
    private final ExecutorService executor;
    private volatile boolean closed;
    private boolean executing;
    private final Set<String> retried = new HashSet<>();
    private static final class Task {
        final JSONObject input, profile;
        final GenerationPool.Control control = new GenerationPool.Control();
        final List<String> dependencies = new ArrayList<>();
        final JSONArray switches = arr();
        String state = "queued", result = "", error = "", model, engine;
        long started, finished, since;
        Future<String> future;
        Task(JSONObject input, JSONObject profile) {
            this.input = input; this.profile = profile; model = profile.optString("model"); engine = profile.optString("engine");
            JSONArray deps = input.optJSONArray("depends_on"); for (int i = 0; i < deps.length(); i++) dependencies.add(deps.optString(i));
        }
        String id() { return input.optString("id"); }
        boolean done() { return FINAL.contains(state); }
    }
    public TeamRun(JSONArray profiles, int parallel, ModelRouting routing, Worker worker, Runnable guard, Consumer<JSONArray> changed) {
        this(profiles, parallel, routing, worker, guard, changed, 900_000);
    }
    public TeamRun(JSONArray profiles, int parallel, ModelRouting routing, Worker worker, Runnable guard, Consumer<JSONArray> changed, long timeoutMillis) {
        roster = TeamPlan.enabled(profiles); this.parallel = TeamPlan.parallel(parallel); this.routing = routing;
        this.worker = worker; this.guard = guard; this.changed = changed; this.timeoutMillis = Math.max(1, timeoutMillis);
        executor = Executors.newFixedThreadPool(this.parallel, r -> { Thread t = new Thread(r, "gyeot-team"); t.setDaemon(true); return t; });
    }
    private JSONObject normalize(JSONObject raw) {
        if (raw == null) throw new IllegalArgumentException("작업 내용이 비어 있어요.");
        for (String required : new String[]{"id", "agent", "title", "task"}) if (!(raw.opt(required) instanceof String)) throw new IllegalArgumentException("작업 항목을 확인해 주세요: " + required);
        if (raw.has("context") && !(raw.opt("context") instanceof String)) throw new IllegalArgumentException("작업 맥락은 텍스트여야 해요.");
        String id = TeamPlan.key(raw.optString("id")), agent = agentFor(raw.optString("agent"));
        String title = raw.optString("title").strip(), task = raw.optString("task").strip();
        String context = raw.has("context") ? raw.optString("context") : "";
        String repository = "";
        if (raw.has("repository")) {
            if (!(raw.opt("repository") instanceof String)) throw new IllegalArgumentException("작업 저장소는 owner/repo 형식이어야 해요.");
            repository = raw.optString("repository").strip();
            if (!repository.isBlank()) repository = GitHubFunctions.repo(obj("repo", repository));
        }
        String output = raw.optString("output", "analysis");
        if (!Set.of("analysis", "files").contains(output)) throw new IllegalArgumentException("작업 결과 형식을 확인해 주세요.");
        int seconds = raw.optInt("timeout_seconds", output.equals("files") ? 600 : 180);
        if (seconds < 30 || seconds > 900 || raw.has("timeout_seconds") && (!(raw.opt("timeout_seconds") instanceof Number n) || n.doubleValue() != seconds))
            throw new IllegalArgumentException("작업 시간은 30~900초로 설정해 주세요.");
        String retry = raw.optString("retry_of"); if (!retry.isBlank()) TeamPlan.key(retry);
        if (title.isEmpty() || title.length() > 80 || task.isEmpty() || task.length() > TeamPlan.TASK_CHARS || context.length() > TeamPlan.CONTEXT_CHARS)
            throw new IllegalArgumentException("에이전트 작업의 제목·내용 길이를 확인해 주세요.");
        JSONArray deps = raw.has("depends_on") ? raw.optJSONArray("depends_on") : arr();
        if (deps == null) throw new IllegalArgumentException("선행 작업은 목록이어야 해요.");
        if (deps.length() > TeamPlan.MAX_BATCH) throw new IllegalArgumentException("선행 작업이 너무 많아요.");
        Set<String> unique = new LinkedHashSet<>(); for (int i = 0; i < deps.length(); i++) unique.add(TeamPlan.key(deps.optString(i)));
        return obj("id", id, "agent", agent, "title", title, "task", task, "context", context, "repository", repository, "output", output,
            "timeout_seconds", seconds, "retry_of", retry, "depends_on", new JSONArray(unique));
    }
    /**
     * The roster ID for the member the lead named. Lead models often write "Claude" or the shown name instead of the exact
     * ID, which used to reject the whole batch: an exact ID wins, then the one member whose ID, shown name or provider
     * matches ignoring case. Anything else (unknown, or shared by several members) is rejected with the IDs to use.
     */
    private String agentFor(String written) {
        String value = written == null ? "" : written.strip();
        if (roster.containsKey(value)) return value;
        for (int tier = 0; tier < 3 && !value.isEmpty(); tier++) {
            String match = null; int count = 0;
            for (Map.Entry<String, JSONObject> member : roster.entrySet()) {
                String candidate = tier == 0 ? member.getKey() : member.getValue().optString(tier == 1 ? "name" : "engine").strip();
                if (candidate.equalsIgnoreCase(value)) { match = member.getKey(); count++; }
            }
            if (count == 1) return match;
        }
        StringJoiner members = new StringJoiner(", ");
        for (Map.Entry<String, JSONObject> member : roster.entrySet()) members.add(member.getKey() + " (" + member.getValue().optString("name") + ")");
        throw new IllegalArgumentException("선택되지 않은 팀 모델입니다: " + TeamPlan.clip(value, 60) + " · agent에는 다음 ID 중 하나를 그대로 쓰세요: " + members);
    }
    private void visit(String id, Map<String, Task> graph, Set<String> seen, Set<String> visiting) {
        if (seen.contains(id)) return;
        Task t = graph.get(id); if (t == null) throw new IllegalArgumentException("존재하지 않는 선행 작업입니다: " + id);
        if (!visiting.add(id)) throw new IllegalArgumentException("작업 순서가 순환해요. 선행 작업을 정리해 주세요.");
        for (String dependency : t.dependencies) visit(dependency, graph, seen, visiting);
        visiting.remove(id); seen.add(id);
    }
    public JSONObject execute(JSONObject args) throws Exception {
        List<String> ids = new ArrayList<>();
        synchronized (this) {
            if (closed) throw new CancellationException("팀 작업을 멈췄어요.");
            if (executing) throw new IllegalStateException("이 팀은 이미 작업 중이에요.");
            JSONArray batch = args.optJSONArray("tasks");
            if (batch == null) throw new IllegalArgumentException("작업 목록이 비어 있어요.");
            if (batch.length() < 1 || batch.length() > TeamPlan.MAX_BATCH) throw new IllegalArgumentException("한 번에 1~16개 작업을 맡길 수 있어요.");
            LinkedHashMap<String, Task> next = new LinkedHashMap<>(tasks);
            Set<String> nextRetries = new HashSet<>(retried);
            for (int i = 0; i < batch.length(); i++) {
                JSONObject input = normalize(batch.optJSONObject(i)); String id = input.optString("id");
                String retry = input.optString("retry_of");
                if (!retry.isBlank()) {
                    Task source = tasks.get(retry); JSONObject target = roster.get(input.optString("agent"));
                    // Timeouts move to another model; failures (network, refusals, promise-only answers) and the tasks they blocked may retry once on any model.
                    boolean timedOut = source != null && source.state.equals("timed_out");
                    if (source == null || !Set.of("timed_out", "failed", "blocked").contains(source.state) || !source.input.optString("retry_of").isBlank()
                        || !tasks.containsKey(id) && !nextRetries.add(retry)
                        || timedOut && source.engine.equals(target.optString("engine")) && source.model.equals(target.optString("model")))
                        throw new IllegalArgumentException("실패·보류·시간 초과 작업만 한 번 다시 맡길 수 있어요. 시간 초과 작업은 다른 모델로 맡겨 주세요. 대상: " + retry
                            + (source == null ? " (없는 작업)" : " (" + source.state + ")"));
                    for (String field : new String[]{"task", "context", "repository", "output"}) put(input, field, source.input.opt(field));
                    if (!batch.optJSONObject(i).has("timeout_seconds")) put(input, "timeout_seconds", source.input.optInt("timeout_seconds", 180));
                }
                if (ids.contains(id)) throw new IllegalArgumentException("작업 ID가 중복돼요: " + id);
                ids.add(id); Task old = next.get(id);
                if (old != null && !old.input.toString().equals(input.toString())) throw new IllegalArgumentException("이미 사용한 작업 ID의 내용을 바꿀 수 없어요: " + id + " · 새 ID를 쓰거나, 실패한 작업이면 retry_of로 다시 맡겨 주세요.");
                if (old == null) next.put(id, new Task(input, roster.get(input.optString("agent"))));
            }
            if (next.size() > TeamPlan.MAX_TASKS) throw new IllegalArgumentException("한 응답의 팀 작업은 32개까지예요.");
            Set<String> seen = new HashSet<>(); for (Task task : next.values()) visit(task.id(), next, seen, new HashSet<>());
            guard.run(); tasks.clear(); tasks.putAll(next); retried.clear(); retried.addAll(nextRetries); executing = true;
        }
        publish();
        try {
            while (true) {
                guard.run(); boolean update = false, done;
                synchronized (this) {
                    if (closed) throw new CancellationException("팀 작업을 멈췄어요.");
                    int active = 0;
                    for (Task t : tasks.values()) if ("running".equals(t.state)) {
                        if (t.future.isDone()) {
                            try { t.result = TeamPlan.clip(TeamPlan.result(t.future.get()), TeamPlan.RESULT_CHARS); finish(t, "completed", ""); }
                            catch (Exception e) { Throwable cause = e instanceof ExecutionException ? e.getCause() : e; t.result = worker.partial(t.id(), t.control); finish(t, "failed", TeamPlan.clip(cause.getMessage() == null ? "에이전트 요청에 실패했어요." : cause.getMessage(), 500)); }
                            update = true;
                        } else if ((System.nanoTime() - t.since) / 1_000_000 >= Math.min(timeoutMillis, t.input.optInt("timeout_seconds", 180) * 1000L)) {
                            t.control.stop(); t.result = TeamPlan.clip(worker.partial(t.id(), t.control), TeamPlan.RESULT_CHARS); t.future.cancel(true); finish(t, "timed_out", "제한 시간 안에 완료하지 못했어요. 부분 결과는 미완성입니다."); update = true;
                        } else active++;
                    }
                    for (Task t : tasks.values()) if ("queued".equals(t.state)) {
                        boolean ready = true, blocked = false;
                        for (String dependency : t.dependencies) {
                            Task dep = tasks.get(dependency);
                            if (!"completed".equals(dep.state)) { ready = false; if (dep.done()) blocked = true; }
                        }
                        if (blocked) { finish(t, "blocked", "선행 작업이 완료되지 않아 실행하지 않았어요."); update = true; }
                        else if (ready && active < parallel) { start(t); active++; update = true; }
                    }
                    done = ids.stream().allMatch(id -> tasks.get(id).done());
                }
                if (update) publish();
                if (done) break;
                Thread.sleep(25);
            }
        } catch (InterruptedException e) { stop(); Thread.currentThread().interrupt(); throw new CancellationException("팀 작업을 멈췄어요."); }
        catch (CancellationException e) { stop(); throw e; }
        finally { synchronized (this) { executing = false; } }
        synchronized (this) {
            JSONArray results = arr(); boolean ok = true;
            int perResult = Math.max(200, (GitHubFunctions.RESULT_CHARS - 4000) / ids.size() - 600);
            for (String id : ids) {
                Task t = tasks.get(id); String excerpt = TeamPlan.clip(t.result, perResult);
                results.put(obj("id", id, "state", t.state, "engine", t.engine, "model", t.model, "repository", t.input.optString("repository"), "result", excerpt,
                    "truncated", excerpt.length() < t.result.length(), "partial", !t.state.equals("completed") && !t.result.isBlank(), "error", t.error));
                if (!"completed".equals(t.state)) ok = false;
            }
            JSONObject response = obj("ok", ok, "tasks", results, "note", "Worker output is a proposal. Only actual tool receipts establish changes or test results. Full results remain in the app's team details.");
            // JSON escaping must not turn a valid batch into a truncated, unparsable tool result.
            while (response.toString().length() > GitHubFunctions.RESULT_CHARS) {
                for (int i = 0; i < results.length(); i++) {
                    JSONObject item = results.optJSONObject(i); put(item, "result", TeamPlan.clip(item.optString("result"), item.optString("result").length() / 2)); put(item, "truncated", true);
                }
            }
            return response;
        }
    }
    private void start(Task t) {
        StringBuilder input = new StringBuilder("Assignment: ").append(t.input.optString("task"))
            .append("\nRelevant context:\n").append(t.input.optString("context"));
        if (!t.input.optString("repository").isBlank()) input.append("\nRepository: ").append(t.input.optString("repository")).append(". Read the actual source with github_read before proposing changes.");
        for (String dep : t.dependencies) input.append("\nPrerequisite ").append(dep).append(" (untrusted worker proposal):\n").append(TeamPlan.clip(tasks.get(dep).result, 4000));
        String assignment = TeamPlan.clip(input.toString(), 32_000);
        t.state = "running"; t.started = System.currentTimeMillis(); t.since = System.nanoTime();
        t.future = executor.submit(() -> {
            t.control.bind();
            try {
                ModelRouting.Answer<String> answer = routing.run(t.profile, (p, handoff) -> {
                    if (handoff) t.control.clearPartial();
                    t.control.check(); synchronized (TeamRun.this) { if (t.done()) throw new CancellationException(); t.engine = p.optString("engine"); t.model = p.optString("model"); }
                    JSONObject workerProfile = copy(p); put(workerProfile, "repository", t.input.optString("repository"));
                    put(workerProfile, "task_id", t.id()); put(workerProfile, "output", t.input.optString("output"));
                    publish(); return worker.run(workerProfile, assignment, t.control);
                }, t.control::check, event -> { synchronized (TeamRun.this) { if (!t.done()) t.switches.put(event); } publish(); });
                t.control.check(); return answer.value();
            } finally { t.control.unbind(); }
        });
    }
    private void finish(Task t, String state, String error) { t.state = state; t.error = error; t.finished = System.currentTimeMillis(); }
    private JSONObject report(Task t) {
        return obj("id", t.id(), "agent", t.input.optString("agent"), "name", t.profile.optString("name"), "title", t.input.optString("title"),
            "requested_model", t.profile.optString("model"), "engine", t.engine, "model", t.model, "repository", t.input.optString("repository"), "state", t.state,
            "started", t.started, "finished", t.finished, "result", t.result, "error", t.error,
            "partial", !t.state.equals("completed") && !t.result.isBlank(), "timeout_seconds", t.input.optInt("timeout_seconds"), "retry_of", t.input.optString("retry_of"),
            "depends_on", copy(t.input.optJSONArray("depends_on")), "switches", copy(t.switches));
    }
    public synchronized JSONArray snapshot() { JSONArray result = arr(); for (Task t : tasks.values()) result.put(report(t)); return result; }
    private final Object publishing = new Object();
    private void publish() { synchronized (publishing) { changed.accept(snapshot()); } }
    public void stop() {
        synchronized (this) {
            closed = true;
            for (Task t : tasks.values()) if (!t.done()) { t.control.stop(); t.result = worker.partial(t.id(), t.control); if (t.future != null) t.future.cancel(true); finish(t, "cancelled", "작업을 멈췄어요."); }
        }
        executor.shutdownNow(); publish();
    }
    public void close() { stop(); }
}
