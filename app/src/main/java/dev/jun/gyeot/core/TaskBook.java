package dev.jun.gyeot.core;

import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Serialized task transitions reject stale network callbacks and never replay interrupted jobs. */
public final class TaskBook {
    private JSONArray tasks = new JSONArray();
    /** Bumped on every change so screens can skip work when nothing moved. */
    private long version;
    public synchronized void restore(String json) throws Exception {
        JSONArray next = new JSONArray(json);
        for (int i = 0; i < next.length(); i++) {
            JSONObject task = next.getJSONObject(i);
            if (task.optString("state").equals("running") || task.optString("state").equals("queued")) {
                task.put("state", "interrupted").put("error", "앱 실행이 종료되어 작업을 이어가지 않았어요. 다시 요청해 주세요.");
            }
        }
        tasks = next; version++;
    }
    public static final String GEMINI = "gemini", CHATGPT = "chatgpt", RECORDING = "recording";
    public synchronized JSONObject enqueue(String request) { return enqueue(request, GEMINI); }
    public synchronized JSONObject enqueue(String request, String engine) { return enqueue(request, engine, null); }
    /**
     * engine: GEMINI researches with Google Search; CHATGPT analyzes the supplied text; RECORDING transcribes and summarizes the
     * recording named in extra ("recording"). Records without one are Gemini tasks. extra fields are stored on the task.
     */
    public synchronized JSONObject enqueue(String request, String engine, JSONObject extra) {
        if (request == null || request.isBlank()) throw new IllegalArgumentException("작업 내용을 입력해 주세요.");
        if (!GEMINI.equals(engine) && !CHATGPT.equals(engine) && !RECORDING.equals(engine)) throw new IllegalArgumentException("알 수 없는 작업 엔진이에요.");
        if (RECORDING.equals(engine) && (extra == null || extra.optString("recording").isBlank())) throw new IllegalArgumentException("요약할 녹음을 찾지 못했어요.");
        if (request.length() > 12000) throw new IllegalArgumentException("요청은 12,000자 이하로 입력해 주세요.");
        int waiting = 0;
        for (int i = 0; i < tasks.length(); i++) if (tasks.optJSONObject(i).optString("state").matches("queued|running")) waiting++;
        if (waiting >= 12) throw new IllegalStateException("진행 중인 작업이 많아요. 완료 후 다시 요청해 주세요.");
        JSONObject task = obj("id", UUID.randomUUID().toString(), "request", request.trim(), "engine", engine, "state", "queued", "createdAt", System.currentTimeMillis());
        if (extra != null) for (java.util.Iterator<String> keys = extra.keys(); keys.hasNext(); ) {
            String key = keys.next();
            if (!task.has(key)) { try { task.put(key, extra.get(key)); } catch (Exception e) { throw new IllegalStateException(e); } }
        }
        tasks.put(task); version++;
        while (tasks.length() > 60) {
            int remove = -1;
            for (int i = 0; i < tasks.length(); i++) if (!tasks.optJSONObject(i).optString("state").matches("queued|running")) { remove = i; break; }
            if (remove < 0) break;
            tasks.remove(remove);
        }
        return copy(task);
    }
    public synchronized JSONObject claim() {
        for (int i = 0; i < tasks.length(); i++) if (tasks.optJSONObject(i).optString("state").equals("running")) return null;
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task.optString("state").equals("queued")) {
                try { task.put("state", "running").put("startedAt", System.currentTimeMillis()); version++; }
                catch (Exception e) { throw new IllegalStateException(e); }
                return copy(task);
            }
        }
        return null;
    }
    public synchronized boolean complete(String id, String report, JSONArray sources, String searchHtml) {
        return finish(id, "completed", obj("report", report, "sources", sources, "searchHtml", searchHtml));
    }
    public synchronized boolean fail(String id, String reason) { return finish(id, "failed", obj("error", reason)); }
    private boolean finish(String id, String state, JSONObject extra) {
        JSONObject task = find(id);
        if (task == null || !task.optString("state").equals("running")) return false;
        try {
            task.put("state", state).put("finishedAt", System.currentTimeMillis());
            java.util.Iterator<String> keys = extra.keys();
            while (keys.hasNext()) { String key = keys.next(); task.put(key, extra.get(key)); }
            version++; return true;
        } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public synchronized boolean cancel(String id) {
        JSONObject task = find(id);
        if (task == null || !task.optString("state").matches("queued|running")) return false;
        try { task.put("state", "cancelled").put("finishedAt", System.currentTimeMillis()); version++; return true; }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public synchronized void cancelAll() {
        for (int i = 0; i < tasks.length(); i++) cancel(tasks.optJSONObject(i).optString("id"));
    }
    /** Deletes one finished record. Queued/running work must be cancelled first so a worker never loses its record. */
    public synchronized boolean remove(String id) {
        for (int i = 0; i < tasks.length(); i++) {
            JSONObject task = tasks.optJSONObject(i);
            if (task.optString("id").equals(id)) { if (pending(task)) return false; tasks.remove(i); version++; return true; }
        }
        return false;
    }
    public synchronized int clearFinished() {
        int removed = 0;
        for (int i = tasks.length() - 1; i >= 0; i--) if (!pending(tasks.optJSONObject(i))) { tasks.remove(i); removed++; }
        if (removed > 0) version++;
        return removed;
    }
    /** The most recently requested unfinished task, for "cancel that" without an id. */
    public synchronized String latestPendingId() {
        for (int i = tasks.length() - 1; i >= 0; i--) if (pending(tasks.optJSONObject(i))) return tasks.optJSONObject(i).optString("id");
        return "";
    }
    public synchronized JSONObject get(String id) { JSONObject task = find(id); return task == null ? null : copy(task); }
    private static boolean pending(JSONObject task) { return task.optString("state").matches("queued|running"); }
    private JSONObject find(String id) {
        for (int i = 0; i < tasks.length(); i++) if (tasks.optJSONObject(i).optString("id").equals(id)) return tasks.optJSONObject(i);
        return null;
    }
    public synchronized JSONArray snapshot() {
        try { return new JSONArray(tasks.toString()); } catch (Exception e) { throw new IllegalStateException(e); }
    }
    public synchronized int pendingCount() {
        int count = 0;
        for (int i = 0; i < tasks.length(); i++) if (tasks.optJSONObject(i).optString("state").matches("queued|running")) count++;
        return count;
    }
    public synchronized String serialize() { return tasks.toString(); }
    public synchronized long version() { return version; }
}
