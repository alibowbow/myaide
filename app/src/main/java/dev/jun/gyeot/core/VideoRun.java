package dev.jun.gyeot.core;

import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** One POST at most; every continuation with a durable request id uses GET only. */
public final class VideoRun {
    public interface Backend {
        JSONObject prepare(JSONObject job) throws Exception;
        String create(JSONObject request) throws Exception;
        JSONObject poll(String requestId) throws Exception;
        JSONObject download(String url, JSONObject job) throws Exception;
    }
    public interface Journal { void save(JSONObject job) throws Exception; }
    public interface Clock { long now(); default long wallTime() { return now(); } void waitFor(long millis) throws Exception; }
    public static final class Rejected extends Exception { public Rejected(String message) { super(message); } }
    private VideoRun() {}
    public static void run(JSONObject job, Backend backend, Journal journal, Runnable guard, Clock clock) throws Exception {
        if ("done".equals(job.optString("state"))) return;
        if (job.optString("requestId").isEmpty() && !"queued".equals(job.optString("state")))
            throw new IllegalStateException("접수 번호가 없어 이 요청을 다시 전송하지 않았어요.");
        long deadline = clock.now() + 15 * 60_000L;
        try {
            guard.run();
            if (job.optString("requestId").isEmpty()) {
                JSONObject request = backend.prepare(job); guard.run();
                put(job, "state", "submitting"); journal.save(job);
                String id = GrokVideo.requestId(backend.create(request));
                put(job, "requestId", id); put(job, "state", "running"); journal.save(job);
            }
            put(job, "state", "running"); put(job, "error", ""); journal.save(job);
            while (clock.now() < deadline) {
                guard.run(); JSONObject result = GrokVideo.result(backend.poll(GrokVideo.requestId(job.optString("requestId"))));
                if ("failed".equals(result.optString("state"))) { put(job, "state", "failed"); put(job, "error", result.optString("error")); journal.save(job); return; }
                if ("downloading".equals(result.optString("state"))) {
                    guard.run(); put(job, "state", "downloading"); journal.save(job);
                    JSONObject local = backend.download(result.getString("url"), job);
                    if (local.optString("uri").isBlank()) throw new IllegalStateException("영상 파일을 저장하지 못했어요.");
                    put(job, "uri", local.getString("uri")); put(job, "size", local.optLong("size"));
                    put(job, "state", "done"); put(job, "completedAt", clock.wallTime()); journal.save(job); return;
                }
                clock.waitFor(5000);
            }
            put(job, "state", "paused"); put(job, "error", "결과를 기다리는 시간이 길어졌어요. 기존 요청 결과를 다시 확인할 수 있어요."); journal.save(job);
        } catch (Exception error) {
            boolean received = !job.optString("requestId").isBlank();
            String state = received ? "paused" : error instanceof Rejected || "queued".equals(job.optString("state")) ? "failed" : "unknown";
            put(job, "state", state);
            String note = "unknown".equals(state) ? "접수 여부를 확인할 수 없어요. 중복 생성을 막기 위해 자동 재전송하지 않았어요."
                : error instanceof Rejected || error instanceof IllegalStateException || error instanceof IllegalArgumentException ? error.getMessage()
                : error instanceof InterruptedException || error instanceof java.util.concurrent.CancellationException ? "결과 확인을 중지했어요. Grok의 영상 생성은 계속될 수 있어요."
                : "연결 또는 저장 중 문제가 생겼어요. 기존 요청의 결과를 다시 확인해 주세요.";
            put(job, "error", note); journal.save(job);
            if (error instanceof InterruptedException) Thread.currentThread().interrupt();
        }
    }
}
