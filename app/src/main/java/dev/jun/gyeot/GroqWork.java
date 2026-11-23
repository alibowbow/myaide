package dev.jun.gyeot;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What the app has Groq do with the user's key (GroqAccount), each within Groq's daily free tier (counted with the Gemini ones,
 * GeminiQuotas): 잡일 (titles, memory, summaries, cards, reviews) on GPT OSS 120B, web search on compound, and recordings'
 * transcription on Whisper. Every call is blocking and says why it could not; the caller goes on with Gemini. Off the main thread.
 */
final class GroqWork {
    private GroqWork() {}
    static final String CHORES = "openai/gpt-oss-120b", SEARCH = "groq/compound", WHISPER = "whisper-large-v3";
    /** Whisper on the free tier takes files up to 25 MB; a bigger recording goes to Gemini. */
    static final long WHISPER_BYTES = 24L * 1024 * 1024;

    static boolean on() { AppStore s = GeminiQuotas.store(); return s != null && s.groq.connected(); }
    private static String key() throws Exception {
        AppStore s = GeminiQuotas.store(); String key = s == null ? "" : s.secrets.get(SecretStore.GROQ);
        if (key == null || key.isBlank()) throw new IllegalStateException("Groq 키가 없어요.");
        return key;
    }
    /** Whether the model's free tier can take a request of about tokens now. */
    static boolean ready(String model, int tokens) { return on() && GeminiQuotas.ready(model, tokens); }

    /** One chat request: instructions and input, the answer's text. reasoning: GPT OSS's effort (low keeps 잡일 quick). */
    static String chat(String model, String instructions, String input, String reasoning, int timeoutMs, AtomicReference<Runnable> cancel) throws Exception {
        JSONObject body = new JSONObject().put("model", model).put("temperature", 0.3)
            .put("messages", new JSONArray().put(new JSONObject().put("role", "system").put("content", instructions)).put(new JSONObject().put("role", "user").put("content", input)));
        if (reasoning != null && model.startsWith("openai/gpt-oss")) body.put("reasoning_effort", reasoning);
        JSONObject answer = post(model, "chat/completions", "application/json", body.toString().getBytes(StandardCharsets.UTF_8), timeoutMs, cancel);
        JSONArray choices = answer.optJSONArray("choices");
        JSONObject message = choices == null || choices.length() == 0 ? null : choices.optJSONObject(0).optJSONObject("message");
        String text = message == null ? "" : message.optString("content").strip();
        if (text.isEmpty()) throw new IllegalStateException("Groq가 빈 답을 보냈어요.");
        JSONObject usage = answer.optJSONObject("usage");
        GeminiQuotas.used(model, usage == null ? 0 : usage.optInt("total_tokens"));
        return text;
    }
    /** 잡일 on GPT OSS 120B: quick, low reasoning. */
    static String chores(String instructions, String input) throws Exception {
        int tokens = (instructions.length() + input.length()) * 2 / 3 + 1500;
        if (!ready(CHORES, tokens)) throw new IllegalStateException("Groq 무료 한도를 기다리는 중이에요.");
        return chat(CHORES, instructions, input, "low", 60_000, null);
    }
    /** A web search question to compound (Groq searches and answers); the answer's text. */
    static String search(String instructions, String query) throws Exception {
        if (!ready(SEARCH, 0)) throw new IllegalStateException("Groq 검색 한도를 기다리는 중이에요.");
        return chat(SEARCH, instructions, query, null, 45_000, null);
    }

    /**
     * A recording as text by Whisper, each stretch on its own line with its time ([03:12] …), or null when Groq cannot take
     * it now (not connected, the free tier spent, a file too big): the caller then goes to Gemini.
     */
    static String transcribe(File audio, String mime, AtomicReference<Runnable> cancel) throws Exception {
        if (audio == null || !audio.isFile() || audio.length() == 0 || audio.length() > WHISPER_BYTES || !ready(WHISPER, 0)) return null;
        String boundary = "gyeot" + System.nanoTime(), name = audio.getName().contains(".") ? audio.getName() : audio.getName() + ".m4a";
        ByteArrayOutputStream form = new ByteArrayOutputStream((int) audio.length() + 1024);
        field(form, boundary, "model", WHISPER); field(form, boundary, "response_format", "verbose_json"); field(form, boundary, "temperature", "0");
        form.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + name + "\"\r\nContent-Type: " + (mime == null || mime.isBlank() ? "audio/mp4" : mime) + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        form.write(Files.readAllBytes(audio.toPath()));
        form.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        JSONObject answer = post(WHISPER, "audio/transcriptions", "multipart/form-data; boundary=" + boundary, form.toByteArray(), 600_000, cancel);
        GeminiQuotas.used(WHISPER, 0);
        JSONArray segments = answer.optJSONArray("segments");
        if (segments == null || segments.length() == 0) return answer.optString("text").strip();
        StringBuilder out = new StringBuilder(); long shown = -60;
        for (int i = 0; i < segments.length(); i++) {
            JSONObject one = segments.optJSONObject(i); if (one == null) continue;
            String text = one.optString("text").strip(); if (text.isEmpty()) continue;
            long at = Math.round(one.optDouble("start", 0));
            // A time every half minute or so, so the text reads in paragraphs and a moment can be found.
            if (at - shown >= 30 || out.length() == 0) { out.append(out.length() == 0 ? "" : "\n").append(String.format(java.util.Locale.ROOT, "[%02d:%02d] ", at / 60, at % 60)); shown = at; }
            else out.append(' ');
            out.append(text);
        }
        return out.toString().strip();
    }
    private static void field(ByteArrayOutputStream form, String boundary, String name, String value) throws Exception {
        form.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
    private static JSONObject post(String model, String path, String type, byte[] body, int timeoutMs, AtomicReference<Runnable> cancel) throws Exception {
        String key = key();
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", GroqAccount.API + path, Map.of("Authorization", "Bearer " + key, "Content-Type", type, "Accept", "application/json"), body, timeoutMs, cancel)) {
            String text = OpenAiAccount.Http.read(response.body, 8 * 1024 * 1024);
            if (response.status == 200) return new JSONObject(text);
            GeminiQuotas.refusedGroq(model, response.status, text);
            String why = ""; try { JSONObject error = new JSONObject(text).optJSONObject("error"); why = error == null ? "" : error.optString("message"); } catch (Exception notJson) {}
            throw new IllegalStateException("Groq 오류 HTTP " + response.status + (why.isBlank() ? "" : " (" + (why.length() > 160 ? why.substring(0, 160) + "…" : why) + ")"));
        }
    }
}
