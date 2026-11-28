package dev.jun.gyeot.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Process-local browser jobs outlive a voice tool call. Never replay a job after process death. Main thread only. */
public final class BrowserJobs {
    public interface Starter { void start(String request, Consumer<JSONObject> complete); }
    /** Voice browser jobs kept at once; running jobs are never evicted, finished ones make room. */
    private static final int LIMIT = 64;
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    private String latest = "";
    private static final class Job {
        final String id = UUID.randomUUID().toString(), call, request;
        JSONObject result;
        Job(String call, String request) { this.call = call; this.request = request; }
    }
    public JSONObject submit(String call, String request, Starter starter, Consumer<JSONObject> completed) {
        if (call == null || call.isBlank() || request == null || request.isBlank() || request.length() > BrowserPrompts.REQUEST_LIMIT)
            return obj("ok", false, "error", String.format(java.util.Locale.ROOT, "%,d자 이내로 브라우저에서 할 일을 알려 주세요.", BrowserPrompts.REQUEST_LIMIT));
        for (Job job : jobs.values()) if (job.call.equals(call)) return status(job.id);
        // The caller binds each job to its tab lane. A different tab must not be
        // blocked by the most recently submitted task; running jobs are never evicted.
        while (jobs.size() >= LIMIT) {
            String finished = jobs.values().stream().filter(j -> j.result != null).map(j -> j.id).findFirst().orElse(null);
            if (finished == null) return obj("ok", false, "busy", true, "retryable", false,
                "error", "동시에 실행 중인 작업이 너무 많아요. 기존 작업을 마친 뒤 다시 지시해 주세요.");
            jobs.remove(finished);
        }
        Job job = new Job(call, request.strip()); jobs.put(job.id, job); latest = job.id;
        Consumer<JSONObject> finish = value -> {
            if (job.result != null) return;
            job.result = value == null ? obj("ok", false, "error", "브라우저 결과를 받지 못했어요.") : copy(value);
            completed.accept(status(job.id));
        };
        try { starter.start(job.request, finish); }
        catch (Exception e) { finish.accept(obj("ok", false, "error", "브라우저 작업을 시작하지 못했어요. " + message(e, ""))); }
        return status(job.id);
    }
    public JSONObject status(String id) {
        Job job = jobs.get(id == null || id.isBlank() ? latest : id);
        if (job == null) return obj("ok", false, "state", "missing", "error", "이 실행에서 해당 브라우저 작업을 찾지 못했어요. 이전 작업을 자동 반복하지 않습니다.");
        JSONObject value = job.result == null ? obj("ok", true) : copy(job.result);
        String state = job.result == null ? "running" : BrowserTools.resultState(job.result);
        put(value, "job_id", job.id); put(value, "request", job.request); put(value, "accepted", true);
        put(value, "finished", job.result != null); put(value, "completed", state.equals("done")); put(value, "state", state);
        return value;
    }
}
