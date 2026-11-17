package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Connection-local continuation state. Its cursor refers to the controller's real, unmodified input history. */
public final class PhoneResponseChain {
    private String responseId = "", format = "", prefix = "";
    private int consumed;
    public int offset(JSONArray input, String currentFormat) {
        if (responseId.isEmpty() || !format.equals(currentFormat) || input.length() < consumed
                || !slice(input, 0, consumed).toString().equals(prefix)) { reset(); return 0; }
        return consumed;
    }
    public JSONObject request(JSONObject body) {
        try {
            JSONObject event = new JSONObject(body.toString());
            event.remove("stream"); event.remove("background");
            event.put("type", "response.create").put("stream_id", "phone");
            if (!responseId.isEmpty()) event.put("previous_response_id", responseId);
            return event;
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    /** Accept only after the complete response and its command format have both been validated. */
    public void accept(String id, JSONArray input, PhoneAgent.Reply reply, String currentFormat) {
        reset();
        if (id == null || id.isBlank()) return;
        try {
            JSONArray next = new JSONArray(input.toString());
            for (int i = 0; i < reply.output.length(); i++) next.put(reply.output.opt(i));
            responseId = id; format = currentFormat; consumed = next.length(); prefix = next.toString();
        } catch (Exception invalid) { throw new IllegalStateException(invalid); }
    }
    public void reset() { responseId = ""; format = ""; prefix = ""; consumed = 0; }
    public static JSONArray slice(JSONArray input, int start) { return slice(input, start, input.length()); }
    private static JSONArray slice(JSONArray input, int start, int end) {
        JSONArray result = arr();
        for (int i = start; i < end; i++) result.put(input.opt(i));
        return result;
    }
}
