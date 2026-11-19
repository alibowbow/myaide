package dev.jun.gyeot.core;

import static dev.jun.gyeot.core.Jsons.*;

import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A replay changes the on-screen representation only. Saved working objects keep their latest data.
 */
public final class BoardReplay {
    private BoardReplay() {}

    private static final int MAX_EVENTS = 160, MAX_DATA = 120000;

    public static JSONObject find(JSONArray items, String id) {
        for (int i = 0; i < items.length(); i++) {
            JSONObject item = items.optJSONObject(i);
            if (item != null
                    && Board.WIDGET.equals(item.optString("t"))
                    && id.equals(item.optString("wid"))) return item;
        }
        return null;
    }

    public static void remember(JSONObject record, JSONObject item, int step) {
        if (record == null || item == null || !Board.WIDGET.equals(item.optString("t"))) return;
        JSONArray frames = record.optJSONArray("frames");
        if (frames == null) {
            frames = arr();
            put(record, "frames", frames);
        }
        String id = item.optString("wid");
        for (int j = 0; j < frames.length(); j++) {
            JSONObject frame = frames.optJSONObject(j);
            if (frame != null
                    && id.equals(frame.optString("id"))
                    && (step == -1 || frame.optInt("step") == step)) return;
        }
        if (frames.length() >= MAX_EVENTS) return;
        JSONObject frame = obj("id", id, "step", step);
        JSONObject spec = item.optJSONObject("spec");
        if (spec != null && spec.toString().length() + frames.toString().length() < MAX_DATA)
            put(frame, "spec", BoardWidget.copy(spec));
        frames.put(frame);
    }

    public static JSONArray clean(JSONObject record, int steps) {
        JSONArray src = record.optJSONArray("frames"), out = arr();
        int remaining = MAX_DATA;
        for (int i = 0; src != null && i < Math.min(MAX_EVENTS, src.length()); i++) {
            JSONObject event = src.optJSONObject(i);
            if (event == null || !event.optString("id").matches("[a-zA-Z0-9_-]{1,40}")) continue;
            int step = event.optInt("step", -2);
            if (step < -1 || step >= steps) continue;
            JSONObject frame = obj("id", event.optString("id"), "step", step),
                    spec = event.optJSONObject("spec");
            if (spec != null) {
                int size = spec.toString().length();
                if (size <= remaining) {
                    put(frame, "spec", spec);
                    remaining -= size;
                }
            }
            out.put(frame);
        }
        return out;
    }
}
