package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Additional user instructions for an existing run, consumed only at a model-request boundary. */
public interface WorkSteering {
    boolean pending();
    /** The lead's explicitly requested safe-response interruption, never Stop. */
    default WorkInterrupt interrupt() { return null; }
    default void interrupted(String retainedText) throws Exception {}
    /** Durably move accepted instructions to the conversation before returning user turns. */
    JSONArray take() throws Exception;
    WorkSteering NONE = new WorkSteering() {
        public boolean pending() { return false; }
        public JSONArray take() { return arr(); }
    };
    String GUIDANCE = " Additional user messages can arrive while this task runs. Apply the latest user corrections to the ongoing task, retaining the original goal unless changed. Keep actual tool results; do not repeat completed actions. A tool result marked skipped_for_user_instruction means that call never ran: reconsider it under the new instructions before doing anything else.";
    static void append(JSONArray target, JSONArray items) {
        for (int i = 0; items != null && i < items.length(); i++) target.put(items.opt(i));
    }
    static JSONObject skipped() {
        return obj("ok", false, "skipped_for_user_instruction", true,
            "error", "A new user instruction arrived before this call executed. This call did not run. Read the additional user messages and reconsider the next action.");
    }
}
