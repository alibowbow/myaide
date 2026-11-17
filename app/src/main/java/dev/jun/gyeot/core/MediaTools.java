package dev.jun.gyeot.core;

import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Media tools any Work model can call (Claude, Gemini, Grok, NIM, and ChatGPT where its own image tool is off): a GPT image
 * picture, a Grok video from a picture or a description, and Gemini text-to-speech. The app runs them with the user's
 * connections and saves every result to the gallery and the 들퍼레 folder; each returns an id the next tool can use.
 */
public final class MediaTools {
    public static final String PICTURE = "make_picture", VIDEO = "make_video", SPEECH = "make_speech";
    public static final List<String> NAMES = List.of(PICTURE, VIDEO, SPEECH, VideoEdit.EDIT, VideoEdit.DOWNLOAD);
    public static final int TEXT_LIMIT = 4000, SPEECH_LIMIT = 5000;
    private MediaTools() {}

    public static boolean is(String name) { return NAMES.contains(name); }
    /** The declarations for the tools the user's connections allow. */
    public static JSONArray declarations(boolean picture, boolean video, boolean speech) {
        JSONArray out = arr();
        if (picture) out.put(obj("name", PICTURE,
            "description", "Draw one picture with GPT image (the user's ChatGPT image connection). It is saved to the gallery and the user's 들퍼레/이미지 folder. Returns {id, name, uri}; pass the id to make_video to animate it, or as edit_from to change it.",
            "parameters", obj("type", "object", "additionalProperties", false, "required", arr("prompt"), "properties", obj(
                "prompt", obj("type", "string", "description", "The full picture description in English: subject, setting, style, composition, lighting, colors, any text to show word for word."),
                "aspect", obj("type", "string", "enum", arr("square", "portrait", "landscape", "9:16", "16:9", "4:3", "3:4")),
                "edit_from", obj("type", "string", "description", "Optional id of a picture made earlier (from make_picture or the gallery) to change instead of drawing anew.")))));
        if (video) out.put(obj("name", VIDEO,
            "description", "Make a short video with Grok Imagine (the user's Grok connection), from a picture id or from a description alone. Takes a few minutes; waits for the result. Saved to the gallery's videos and 들퍼레/영상. Returns {id, uri, seconds}.",
            "parameters", obj("type", "object", "additionalProperties", false, "required", arr("prompt"), "properties", obj(
                "prompt", obj("type", "string", "description", "The motion, camera and scene to show, in English or the user's language."),
                "picture_id", obj("type", "string", "description", "Optional id of a picture (make_picture or the gallery) to animate."),
                "seconds", obj("type", "integer", "minimum", 1, "maximum", 15),
                "aspect", obj("type", "string", "enum", arr("16:9", "9:16", "1:1", "4:3", "3:4", "3:2", "2:3")),
                "resolution", obj("type", "string", "enum", arr("720p", "1080p"))))));
        if (speech) out.put(obj("name", SPEECH,
            "description", "Turn text into spoken audio with Gemini text-to-speech (the user's Gemini API key). Saved as a WAV file in 들퍼레/오디오. Returns {id, uri, seconds}.",
            "parameters", obj("type", "object", "additionalProperties", false, "required", arr("text"), "properties", obj(
                "text", obj("type", "string", "description", "Exactly what to say, in any language."),
                "voice", obj("type", "string", "enum", new JSONArray(GeminiSpeech.VOICES)),
                "style", obj("type", "string", "description", "Optional delivery, e.g. 'calm and warm', 'excited sports announcer'.")))));
        return out;
    }
    /** What the model is told about the tools it was given. */
    public static String instructions(boolean picture, boolean video, boolean speech) {
        if (!picture && !video && !speech) return "";
        StringBuilder out = new StringBuilder("\nMedia tools: ");
        if (picture) out.append(PICTURE).append(" draws a picture with GPT image; ");
        if (video) out.append(VIDEO).append(" makes a Grok video from a picture id or a description; ");
        if (speech) out.append(SPEECH).append(" turns text into Gemini speech audio; ");
        // The chaining example names only the tools offered, so it never suggests one that is not there.
        List<String> chain = new java.util.ArrayList<>();
        if (picture) chain.add("a picture"); if (video) chain.add(picture ? "a video of it" : "a video"); if (speech) chain.add("narration");
        out.append("each saves its result to the gallery and the user's 들퍼레 folder and returns an id. ")
            .append(chain.size() > 1 ? "Chain them by id (for example " + String.join(", then ", chain) + "). " : "").append("Each call costs the user's usage: make only what was asked, never repeat a call that returned ok, and say a file exists only after its tool returned ok. Keep the returned names and ids in your answer so the user can find the files.");
        return out.toString();
    }

    /** make_picture arguments for Drawing.directArguments: {prompt, aspect, edit, codex} plus the checked edit_from id. */
    public static JSONObject picture(JSONObject args, boolean codex) {
        String prompt = text(args, "prompt", TEXT_LIMIT, "그릴 내용을 적어 주세요.");
        String from = id(args.optString("edit_from"));
        String aspect = args.optString("aspect");
        return obj("prompt", prompt, "aspect", aspect.isBlank() ? "square" : aspect, "edit", !from.isEmpty(), "edit_from", from, "codex", codex);
    }
    /** make_video arguments: {prompt, picture_id, seconds, aspect, resolution}, checked like the gallery's video sheet. */
    public static JSONObject video(JSONObject args) {
        String prompt = GrokVideo.prompt(text(args, "prompt", TEXT_LIMIT, "영상 내용을 적어 주세요."));
        int seconds = GrokVideo.seconds(args.has("seconds") ? args.optInt("seconds", 8) : 8);
        String aspect = GrokVideo.aspect(args.optString("aspect").isBlank() ? "16:9" : args.optString("aspect"));
        String resolution = GrokVideo.resolution(args.optString("resolution").isBlank() ? "720p" : args.optString("resolution"));
        return obj("prompt", prompt, "picture_id", id(args.optString("picture_id")), "seconds", seconds, "aspect", aspect, "resolution", resolution);
    }
    /** make_speech arguments: {text, voice, style}; an unknown voice becomes the default. */
    public static JSONObject speech(JSONObject args) {
        String text = text(args, "text", SPEECH_LIMIT, "읽을 문장을 적어 주세요.");
        String voice = GeminiSpeech.voice(args.optString("voice"));
        String style = args.optString("style").strip();
        return obj("text", text, "voice", voice, "style", style.length() > 200 ? style.substring(0, 200) : style);
    }
    private static String text(JSONObject args, String key, int limit, String empty) {
        String value = args == null ? "" : args.optString(key).strip();
        if (value.isEmpty()) throw new IllegalArgumentException(empty);
        if (value.length() > limit) throw new IllegalArgumentException(String.format(Locale.ROOT, "%,d자 이내로 적어 주세요.", limit));
        return value;
    }
    /** A media id as the gallery and video records use (safe characters only), or "". */
    static String id(String value) { String v = value == null ? "" : value.strip(); return WorkBook.safeId(v) ? v : ""; }
    /** A short Korean stage line while a tool runs. */
    public static String stage(String name) {
        return switch (name) { case PICTURE -> "GPT 이미지로 그리는 중"; case VIDEO -> "Grok으로 영상 만드는 중"; case SPEECH -> "Gemini로 음성 만드는 중"; default -> ""; };
    }
}
