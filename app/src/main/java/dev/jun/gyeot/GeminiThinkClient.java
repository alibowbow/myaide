package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.GeminiThink;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import org.json.JSONObject;

/** Gemini Flash as the brain: one generateContent request on the best model whose free quota is left. Blocking; call it off the main thread. */
final class GeminiThinkClient {
    private GeminiThinkClient() {}
    static final class Answer { final String text, model; Answer(String text, String model) { this.text = text; this.model = model; } }

    /** cancel, when given, always holds an action that aborts the request in flight (safe from any thread). */
    static Answer ask(AppStore store, String key, String instructions, String input, java.util.concurrent.atomic.AtomicReference<Runnable> cancel) throws Exception {
        byte[] body = GeminiThink.request(instructions, input).toString().getBytes(StandardCharsets.UTF_8);
        String today = GeminiThink.day(Instant.now());
        Exception last = null;
        for (String model : GeminiThink.order(store.flashState(), today)) {
            HttpURLConnection connection = (HttpURLConnection) URI.create(GeminiThink.url(model)).toURL().openConnection();
            if (cancel != null) cancel.set(connection::disconnect);
            try {
                connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(90_000);
                connection.setRequestMethod("POST"); connection.setDoOutput(true);
                // The key goes in a header, never the URL, so it cannot end up in an error message.
                connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("x-goog-api-key", key);
                try (OutputStream output = connection.getOutputStream()) { output.write(body); }
                int status = connection.getResponseCode();
                InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
                String text = stream == null ? "" : OpenAiAccount.Http.read(stream, 2 * 1024 * 1024);
                JSONObject json; try { json = new JSONObject(text); } catch (Exception e) { json = new JSONObject(); }
                if (status == 200) {
                    String answer = GeminiThink.answer(json);
                    store.changeFlash(state -> GeminiThink.used(state, model, today));
                    return new Answer(answer, model);
                }
                JSONObject error = json.optJSONObject("error");
                String message = error == null ? "" : error.optString("message");
                last = new IllegalStateException(GeminiProtocol.explain(status + " " + message));
                // The whole error body: the per-day quota id sits in its details, not always in the message.
                String quota = GeminiThink.quota(status, text);
                if (quota.equals("day")) { int limit = GeminiThink.limit(text); store.changeFlash(state -> GeminiThink.full(state, model, today, limit)); continue; }
                if (quota.equals("minute") || status >= 500) continue;
                if (GeminiThink.missing(status, message)) { store.changeFlash(state -> GeminiThink.missing(state, model)); continue; }
                throw (IllegalStateException) last;
            } finally { connection.disconnect(); if (cancel != null) cancel.set(null); }
        }
        throw last != null ? last : new IllegalStateException("Gemini Flash에 묻지 못했어요.");
    }
}
