package dev.jun.gyeot.core;

import java.io.ByteArrayOutputStream;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Gemini text-to-speech with the user's API key: the generateContent request asking for audio, the 16-bit PCM it returns,
 * and that audio wrapped as a WAV file other apps and the video editor can play.
 */
public final class GeminiSpeech {
    /** Prebuilt voices Gemini offers; the first is the default. */
    public static final List<String> VOICES = List.of("Kore", "Puck", "Charon", "Zephyr", "Fenrir", "Leda", "Orus", "Aoede", "Callirrhoe", "Autonoe",
        "Enceladus", "Iapetus", "Umbriel", "Algieba", "Despina", "Erinome", "Algenib", "Rasalgethi", "Laomedeia", "Achernar", "Alnilam", "Schedar",
        "Gacrux", "Pulcherrima", "Achird", "Zubenelgenubi", "Vindemiatrix", "Sadachbia", "Sadaltager", "Sulafat");
    public static final String MODEL = "gemini-2.5-flash-preview-tts";
    private static final Pattern RATE = Pattern.compile("rate=(\\d{4,6})");
    private GeminiSpeech() {}

    public static String voice(String value) {
        String v = value == null ? "" : value.strip();
        for (String voice : VOICES) if (voice.equalsIgnoreCase(v)) return voice;
        return VOICES.get(0);
    }
    /** A text-to-speech model from the account's catalog (flash first), or the published default. */
    public static String model(JSONArray catalog) {
        String found = "";
        for (int i = 0; catalog != null && i < catalog.length(); i++) {
            JSONObject entry = catalog.optJSONObject(i); String id = entry == null ? "" : entry.optString("id").replaceFirst("^models/", "");
            if (!id.toLowerCase(Locale.ROOT).contains("tts")) continue;
            if (id.contains("flash")) return id;
            if (found.isEmpty()) found = id;
        }
        return found.isEmpty() ? MODEL : found;
    }
    public static String url(String model) { return GeminiWork.BASE + "models/" + model + ":generateContent"; }
    /** The request: the text (with an optional delivery note) and audio-only output in one voice. */
    public static JSONObject request(String text, String voice, String style) {
        String spoken = style == null || style.isBlank() ? text : "Say this " + style.strip() + ":\n" + text;
        return obj("contents", arr(obj("role", "user", "parts", arr(obj("text", spoken)))),
            "generationConfig", obj("responseModalities", arr("AUDIO"),
                "speechConfig", obj("voiceConfig", obj("prebuiltVoiceConfig", obj("voiceName", voice(voice))))));
    }
    /** The audio in a response: {pcm (bytes), rate}; throws with a readable reason when there is none. */
    public record Audio(byte[] pcm, int rate) {
        public double seconds() { return rate <= 0 ? 0 : pcm.length / 2.0 / rate; }
    }
    public static Audio audio(JSONObject response) {
        JSONArray candidates = response == null ? null : response.optJSONArray("candidates");
        JSONObject content = candidates == null || candidates.length() == 0 ? null : candidates.optJSONObject(0).optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        for (int i = 0; parts != null && i < parts.length(); i++) {
            JSONObject inline = parts.optJSONObject(i) == null ? null : parts.optJSONObject(i).optJSONObject("inlineData");
            if (inline == null || inline.optString("data").isEmpty()) continue;
            byte[] pcm = Base64.getDecoder().decode(inline.optString("data"));
            Matcher rate = RATE.matcher(inline.optString("mimeType"));
            if (pcm.length < 2) break;
            return new Audio(pcm, rate.find() ? Integer.parseInt(rate.group(1)) : 24000);
        }
        String reason = candidates == null || candidates.length() == 0 ? response == null ? "" : response.optJSONObject("promptFeedback") == null ? "" : response.optJSONObject("promptFeedback").optString("blockReason") : candidates.optJSONObject(0).optString("finishReason");
        throw new IllegalStateException("Gemini가 음성을 보내지 않았어요." + (reason.isBlank() ? "" : " (" + reason + ")"));
    }
    /** 16-bit mono PCM as a WAV file. */
    public static byte[] wav(byte[] pcm, int rate) {
        ByteArrayOutputStream out = new ByteArrayOutputStream(pcm.length + 44);
        int data = pcm.length - (pcm.length % 2);
        ascii(out, "RIFF"); le32(out, 36 + data); ascii(out, "WAVE"); ascii(out, "fmt "); le32(out, 16); le16(out, 1); le16(out, 1);
        le32(out, rate); le32(out, rate * 2); le16(out, 2); le16(out, 16); ascii(out, "data"); le32(out, data);
        out.write(pcm, 0, data);
        return out.toByteArray();
    }
    private static void ascii(ByteArrayOutputStream out, String text) { for (char c : text.toCharArray()) out.write(c); }
    private static void le16(ByteArrayOutputStream out, int v) { out.write(v & 255); out.write((v >> 8) & 255); }
    private static void le32(ByteArrayOutputStream out, int v) { le16(out, v & 0xffff); le16(out, (v >>> 16) & 0xffff); }
}
