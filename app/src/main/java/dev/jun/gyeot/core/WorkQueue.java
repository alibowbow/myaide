package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Persisted instructions: steer the active provider at a boundary, or remain queued for a later run. */
public final class WorkQueue {
    public static final int LIMIT = 8;
    private WorkQueue() {}
    public static int size(JSONObject thread) {
        JSONArray queue = thread == null ? null : thread.optJSONArray("pendingInstructions");
        return queue == null ? 0 : queue.length();
    }
    public static JSONObject first(JSONObject thread) {
        return size(thread) == 0 ? null : thread.optJSONArray("pendingInstructions").optJSONObject(0);
    }
    public static void add(JSONObject thread, String text, JSONArray rawFiles, String engine, long now) {
        if (thread == null) throw new IllegalArgumentException("이 작업을 찾지 못했어요.");
        String clean = text == null ? "" : text.strip();
        if (clean.length() > WorkBook.TEXT_LIMIT) throw new IllegalArgumentException("추가 지시가 너무 길어요.");
        JSONArray files = WorkBook.attachments(rawFiles);
        if (clean.isEmpty() && files.length() == 0) throw new IllegalArgumentException("보낼 내용을 적어 주세요.");
        if (size(thread) >= LIMIT) throw new IllegalStateException("대기 지시가 8개예요. 일부가 끝난 뒤 보내 주세요.");
        JSONArray queue = thread.optJSONArray("pendingInstructions");
        if (queue == null) { queue = new JSONArray(); put(thread, "pendingInstructions", queue); }
        queue.put(obj("text", clean, "attachments", files, "engine", WorkBook.engine(engine), "at", now));
        put(thread, "updatedAt", now);
    }
    public static void add(JSONObject thread, String text, JSONArray files, String engine, String model, String effort, long now) {
        add(thread, text, files, engine, now);
        JSONObject item = thread.optJSONArray("pendingInstructions").optJSONObject(size(thread) - 1);
        put(item, "model", model); put(item, "effort", effort);
    }
    public static void add(JSONObject thread, String text, JSONArray files, String engine, String model, String effort, JSONObject selection, long now) {
        add(thread, text, files, engine, model, effort, now);
        try { put(thread.optJSONArray("pendingInstructions").optJSONObject(size(thread) - 1), "modelSelection", new JSONObject(selection.toString())); }
        catch (Exception e) { throw new IllegalArgumentException("모델 설정을 보관하지 못했어요.", e); }
    }
    /** Called in the same durable transaction as WorkBook.ask, never on network dispatch alone. */
    public static void removeFirst(JSONObject thread) {
        if (size(thread) > 0) thread.optJSONArray("pendingInstructions").remove(0);
    }
    public static void clear(JSONObject thread) { if (thread != null) thread.remove("pendingInstructions"); }
    public static boolean canSteer(JSONObject thread, String engine) {
        JSONObject next = first(thread);
        if (thread == null || !WorkBook.RUNNING.equals(thread.optString("state")) || next == null || !WorkBook.engine(engine).equals(next.optString("engine"))) return false;
        JSONObject checkpoint = WorkCheckpoint.get(thread);
        if (checkpoint != null && checkpoint.optBoolean("readOnly")) return false;
        if (next.has("model") && (checkpoint == null || !next.optString("model").equals(checkpoint.optString("model"))
                || !next.optString("effort").equals(checkpoint.optString("effort")))) return false;
        JSONObject requestedSelection = next.optJSONObject("modelSelection"), activeSelection = checkpoint == null ? null : checkpoint.optJSONObject("modelSelection");
        if (requestedSelection != null && (activeSelection == null || !requestedSelection.toString().equals(activeSelection.toString()))) return false;
        JSONArray files = next.optJSONArray("attachments");
        for (int i = 0; files != null && i < files.length(); i++) {
            String kind = files.optJSONObject(i).optString("kind");
            if (WorkBook.VIDEO.equals(kind) || WorkBook.AUDIO.equals(kind)) return false;
        }
        return true;
    }
    /** Run inside the same atomic save as the conversation update. FIFO, no silent provider switch. */
    public static JSONArray takeForRun(JSONObject thread, String engine) {
        JSONArray turns = arr(); int characters = 0; boolean attached = false;
        while (canSteer(thread, engine)) {
            JSONObject entry = first(thread);
            JSONObject message = obj("role", WorkBook.USER, "text", entry.optString("text"), "at", entry.optLong("at"), "steering", true);
            if (entry.optBoolean(WorkBook.VOICE)) { put(message, WorkBook.VOICE, true); if (!entry.optString(WorkBook.HEARD).isBlank()) put(message, WorkBook.HEARD, entry.optString(WorkBook.HEARD)); }
            JSONArray files = entry.optJSONArray("attachments");
            boolean hasFiles = files != null && files.length() > 0;
            if (hasFiles) put(message, "attachments", files);
            JSONArray input = WorkBook.input(obj("messages", arr(message)));
            JSONObject turn = input.optJSONObject(0);
            if (turn == null) throw new IllegalStateException("추가 지시를 읽지 못했어요.");
            int size = turn.optString("content").length();
            if (turns.length() > 0 && (attached || hasFiles || characters + size > WorkBook.INPUT_BUDGET)) break;
            WorkBook.steer(thread, message); removeFirst(thread);
            turns.put(turn); characters += size; attached |= hasFiles;
        }
        return turns;
    }
}

