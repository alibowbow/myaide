package dev.jun.gyeot.core;

import java.util.Base64;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Turning a local recording into text with Gemini: requests for inline audio or an uploaded file, and reading the answer.
 * Recordings never leave the phone until the user asks for a summary.
 */
public final class GeminiAudio {
    /** Tried in order; the dedicated transcription model first, then a general model that also understands audio. */
    public static final String[] MODELS = {GeminiProtocol.FILE_TRANSCRIBE, "gemini-2.5-flash"};
    /** Audio up to this size goes inline (base64 keeps the request under Gemini's 20 MB limit); larger files are uploaded. */
    public static final long INLINE_LIMIT = 14L * 1024 * 1024;
    public static final String MIME = "audio/mp4";
    public static final String UPLOAD = "https://generativelanguage.googleapis.com/upload/v1beta/files";
    public static final String API = "https://generativelanguage.googleapis.com/v1beta/";
    private GeminiAudio() {}

    public static String prompt() {
        return "Transcribe this recording verbatim in its original language (usually Korean). When the speaker changes and it is clear, start the line with 화자 A:, 화자 B: and so on. "
            + "Write inaudible parts as [안 들림]. Do not summarize, translate, or add anything. Output only the transcript.";
    }
    public static JSONObject inline(byte[] audio) { return inline(audio, MIME); }
    /** Audio of any type Gemini takes (a recording is audio/mp4; an attached file keeps its own type). */
    public static JSONObject inline(byte[] audio, String mime) {
        return obj("contents", arr(obj("role", "user", "parts", arr(obj("text", prompt()), obj("inline_data", obj("mime_type", mime, "data", Base64.getEncoder().encodeToString(audio)))))));
    }
    public static JSONObject uploaded(String fileUri) { return uploaded(fileUri, MIME); }
    public static JSONObject uploaded(String fileUri, String mime) {
        return obj("contents", arr(obj("role", "user", "parts", arr(obj("text", prompt()), obj("file_data", obj("mime_type", mime, "file_uri", fileUri))))));
    }
    /** A text-only request, used to summarize with Gemini when ChatGPT is not connected. */
    public static JSONObject text(String instructions, String input) {
        return obj("systemInstruction", obj("parts", arr(obj("text", instructions))), "contents", arr(obj("role", "user", "parts", arr(obj("text", input)))));
    }
    public static JSONObject startUpload(String displayName) { return obj("file", obj("display_name", displayName)); }

    /** The answer text of a generateContent response, thought parts skipped; explains an empty answer. */
    public static String answer(JSONObject response) {
        JSONArray candidates = response.optJSONArray("candidates");
        JSONObject first = candidates == null ? null : candidates.optJSONObject(0);
        JSONObject content = first == null ? null : first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        if (parts != null) for (int i = 0; i < parts.length(); i++) {
            JSONObject part = parts.optJSONObject(i);
            if (part != null && !part.optBoolean("thought")) text.append(part.optString("text"));
        }
        if (text.toString().isBlank()) {
            String reason = first == null ? "" : first.optString("finishReason");
            JSONObject feedback = response.optJSONObject("promptFeedback");
            if (feedback != null && !feedback.optString("blockReason").isBlank()) reason = feedback.optString("blockReason");
            throw new IllegalStateException("녹음에서 글을 얻지 못했어요" + (reason.isBlank() ? ". 말소리가 녹음됐는지 확인해 주세요." : " (" + reason + ")."));
        }
        return text.toString().strip();
    }
    /** The file record inside an upload or files.get response ({"file": {...}} or the bare record). */
    public static JSONObject file(JSONObject response) {
        JSONObject file = response.optJSONObject("file");
        return file == null ? response : file;
    }
    /** Only Gemini's own API host gets the key; upload URLs it hands back are checked against it. */
    public static boolean ownHost(String url) {
        return url != null && url.startsWith("https://generativelanguage.googleapis.com/");
    }
}
