package dev.jun.gyeot.core;

import java.util.HashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/** Room-local display state, fenced by the exact run owner rather than a reusable room ID. */
public final class WorkRoomState {
    public static final class Snapshot {
        public final boolean present;
        public final String text, stage, author, model;
        private Snapshot(boolean present, String text, String stage, String author, String model) {
            this.present = present; this.text = text; this.stage = stage; this.author = author; this.model = model;
        }
    }
    private static final Snapshot EMPTY = new Snapshot(false, "", "", "", "");
    private static final class Entry {
        final Object token;
        Snapshot state;
        Entry(Object token, Snapshot state) { this.token = token; this.state = state; }
    }
    private final Map<String, Entry> rooms = new HashMap<>();
    private static String value(String text) { return text == null ? "" : text; }

    public synchronized void begin(String id, Object token, String author, String model) {
        if (token == null) throw new NullPointerException("run owner");
        rooms.put(id, new Entry(token, new Snapshot(true, "", "차례를 기다리는 중", value(author), value(model))));
    }
    public synchronized boolean update(String id, Object token, String text, String stage, String author, String model) {
        Entry entry = rooms.get(id);
        if (entry == null || entry.token != token) return false;
        Snapshot old = entry.state;
        entry.state = new Snapshot(true, text == null ? old.text : text, stage == null ? old.stage : stage,
                author == null ? old.author : author, model == null ? old.model : model);
        return true;
    }
    public synchronized Snapshot get(String id) { Entry entry = rooms.get(id); return entry == null ? EMPTY : entry.state; }
    public synchronized boolean remove(String id, Object token) {
        Entry entry = rooms.get(id);
        if (entry == null || entry.token != token) return false;
        rooms.remove(id); return true;
    }
    public synchronized boolean isEmpty() { return rooms.isEmpty(); }

    /** Display/persist a local terminal snapshot; never assert that remote effects were cancelled. */
    public static JSONArray locallyStoppedTeam(JSONArray source) {
        JSONArray result = source == null ? new JSONArray() : Jsons.copy(source);
        for (int i = 0; i < result.length(); i++) {
            JSONObject row = result.optJSONObject(i);
            if (row == null) continue;
            String state = row.optString("state");
            if ("running".equals(state) || "queued".equals(state)) {
                Jsons.put(row, "state", "cancelled");
                Jsons.put(row, "localStop", true);
                Jsons.put(row, "error", "기기에서 중지했어요. 이미 보낸 요청과 원격 변경의 종료 여부는 확인되지 않았어요.");
            }
        }
        return result;
    }
}
