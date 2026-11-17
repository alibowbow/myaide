package dev.jun.gyeot;

import dev.jun.gyeot.core.DecisionPhone;
import dev.jun.gyeot.core.GeminiWork;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * Gemini Decisions (gemini-decision-1): one POST per decision with the user's Gemini API key in its header, never in the
 * address. Blocking; off the main thread. A refusal or a slow answer is an error the caller hands back on; nothing here
 * retries, so a decision is never asked twice for one step.
 */
final class GeminiDecisions {
    /** A step's decision waits this long at most: past it the voice model goes on by itself. */
    static final int TIMEOUT_MS = 8_000;
    private GeminiDecisions() {}

    static DecisionPhone.Client client(String key, AtomicReference<Runnable> cancel) {
        return body -> decide(key, body, cancel);
    }
    static JSONObject decide(String key, JSONObject body, AtomicReference<Runnable> cancel) throws Exception {
        // The resolver's headers: the saved API key in its header, a blank one refused before anything is sent.
        Map<String, String> headers = GeminiWorkClient.apiHeaders(key, "application/json");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", DecisionPhone.ENDPOINT, headers, body.toString().getBytes(StandardCharsets.UTF_8), TIMEOUT_MS, cancel)) {
            String text = OpenAiAccount.Http.read(response.body, 1024 * 1024);
            if (response.status != 200) {
                String why = ""; try { why = GeminiWork.message(new JSONObject(text)); } catch (Exception notJson) {}
                throw new IOException("Gemini 디시전 오류 HTTP " + response.status + (why.isBlank() ? "" : " (" + (why.length() > 160 ? why.substring(0, 160) + "…" : why) + ")"));
            }
            return new JSONObject(text);
        } finally { if (cancel != null) cancel.set(null); }
    }
}
