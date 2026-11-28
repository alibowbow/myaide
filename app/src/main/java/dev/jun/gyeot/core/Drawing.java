package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Drawing as the app's own function, for a ChatGPT connection that takes functions but not OpenAI's hosted image tool (a
 * plan sign-in): ChatGPT calls draw_picture with a prompt, and the app renders with the separately connected Codex OAuth
 * session (CodexImages). API keys use the public Images API. The declaration, instructions, requests and replies are pure
 * data; the app does the network calls. Public API refusal records never disable Codex.
 */
public final class Drawing {
    public static final String NAME = "draw_picture";
    /** OpenAI's image API and model; a connection that refuses it is remembered (OpenAiAccount.refuse). */
    public static final String OPENAI_IMAGES = "https://api.openai.com/v1/images/generations", OPENAI_EDITS = "https://api.openai.com/v1/images/edits",
        OPENAI_MODEL = "gpt-image-2", OPENAI_REFUSAL = "images_api";
    public static final int PROMPT_LIMIT = 4000;
    private static final Pattern RATIO = Pattern.compile("(?<!\\d)(16\\s*[:：/대]\\s*9|9\\s*[:：/대]\\s*16|1\\s*[:：/대]\\s*1|2\\s*[:：/대]\\s*3|3\\s*[:：/대]\\s*2)(?!\\d)");
    /**
     * The shapes GPT Image draws, as a list to pick from (준: "gpt에서 제공하는 비율에 맞게"): the ones OpenAI's image guide
     * lists for gpt-image-2 (read 2026-11-28: 1024x1024 square, 1536x1024 landscape, 1024x1536 portrait, and 16:9 / 9:16 as its
     * 2K/4K sizes), each asked at the size already used here. id (what is kept and sent), ratio, name, size.
     */
    public static final String[][] SHAPES = {{"square", "1:1", "정사각형", "1024x1024"}, {"landscape", "3:2", "가로", "1536x1024"},
        {"portrait", "2:3", "세로", "1024x1536"}, {"16:9", "16:9", "가로 와이드", "1536x864"}, {"9:16", "9:16", "세로 와이드", "864x1536"}};
    /** The shape an aspect names ({id, ratio, name, size}), or null. */
    public static String[] shape(String aspect) {
        String id = normalizeAspect(aspect == null ? "" : aspect.strip());
        for (String[] one : SHAPES) if (one[0].equals(id)) return one;
        return null;
    }
    /** Width over height of the shape (1 for one not known). */
    public static double ratio(String aspect) {
        String[] one = shape(aspect); if (one == null) return 1;
        String[] wh = one[3].split("x"); return Double.parseDouble(wh[0]) / Double.parseDouble(wh[1]);
    }
    /** "와이드스크린 (16:9)": the shape as the list names it. */
    public static String label(String aspect) { String[] one = shape(aspect); return one == null ? "정사각형 (1:1)" : one[2] + " (" + one[1] + ")"; }
    /** Every id, in the list's order. */
    public static java.util.List<String> aspects() { java.util.List<String> all = new java.util.ArrayList<>(); for (String[] one : SHAPES) all.add(one[0]); return all; }
    private static final String ASK = "(?:줘(?:요)?|주세요|줄래(?:요)?|줄\\s*수\\s*있(?:어|나요)|주겠(?:어|니)|봐)(?![가-힣])";
    private static final Pattern DRAW_COMMAND = Pattern.compile("(?i)그려\\s*" + ASK + "|그려[.!?。]*$|\\b(draw|paint|illustrate|render)\\b"),
        IMAGE_NOUN = Pattern.compile("(?i)이미지|그림|사진|일러스트|포스터|로고|배경화면|썸네일|image|picture|photo|illustration|poster|logo|wallpaper|thumbnail"),
        MAKE_COMMAND = Pattern.compile("(?i)(만들어|생성해|제작해)\\s*" + ASK + "|(생성|제작)[.!?。]*$|\\b(generate|create|make)\\b");
    private Drawing() {}

    /** The function ChatGPT calls (Responses API shape, strict). */
    public static JSONObject tool() {
        return obj("type", "function", "name", NAME, "strict", true,
            "description", "Makes a picture for the user (illustration, photo-like image, poster, logo, diagram-like art) or changes the picture attached to their newest message "
                + "or the one drawn just before. The picture is shown on the user's phone and saved to their gallery.",
            "parameters", obj("type", "object", "additionalProperties", false, "required", arr("prompt", "edit", "aspect"),
                "properties", obj(
                    "prompt", obj("type", "string", "description", "The full picture description in English: subject, setting, style, composition, lighting, colors, and any text to show, word for word."),
                    "edit", obj("type", "boolean", "description", "true to change the attached or just-drawn picture, false for a new picture."),
                    "aspect", obj("type", "string", "enum", new JSONArray(aspects()),
                        "description", "Match the user's ratio exactly: square=1:1, portrait=2:3, landscape=3:2, or 16:9, 9:16."))));
    }
    /** What ChatGPT is told when the function is there. */
    public static final String INSTRUCTIONS = "You can draw and edit pictures with the draw_picture function: call it when the user asks for a picture, illustration, logo, poster or a change "
        + "to an attached or earlier picture, never otherwise. Write its prompt in English with full detail, and set edit when changing the attached or just-drawn picture. "
        + "This function is available in THIS request even if earlier assistant messages said drawing was unavailable. Those old capability claims are stale. "
        + "For a drawing request, call the function before claiming you cannot draw; report only the result it actually returns. "
        + "If the user says to try again after a failed drawing request, reuse the earlier user's full visual description as the drawing prompt. "
        + "Honor the exact requested aspect ratio, including 9:16 and 16:9. "
        + "The picture reaches the user's phone, so once it is made describe it in a sentence instead of linking it. If drawing fails, say why in one sentence and give the prompt. "
        + "Never draw text art in place of a picture. ";

    /** The function's arguments, checked: {prompt, edit, aspect}. */
    public static JSONObject arguments(String json) {
        JSONObject raw;
        try { raw = new JSONObject(json == null ? "{}" : json); } catch (Exception e) { throw new IllegalArgumentException("그림 요청 형식이 올바르지 않아요."); }
        String prompt = raw.optString("prompt").strip();
        if (prompt.isEmpty()) throw new IllegalArgumentException("그릴 내용이 비어 있어요.");
        if (prompt.length() > PROMPT_LIMIT) prompt = prompt.substring(0, PROMPT_LIMIT);
        String aspect = normalizeAspect(raw.optString("aspect"));
        return obj("prompt", prompt, "edit", raw.optBoolean("edit"), "aspect", aspect.isEmpty() ? "square" : aspect);
    }

    private static String normalizeAspect(String value) {
        return switch (value) {
            case "square", "1:1" -> "square";
            case "portrait", "2:3" -> "portrait";
            case "landscape", "3:2" -> "landscape";
            case "9:16", "16:9" -> value;
            default -> "";
        };
    }
    /** The last explicit ratio wins, e.g. "16:9 말고 9:16으로". Empty means no ratio was specified. */
    public static String aspect(String text) {
        Matcher match = RATIO.matcher(text == null ? "" : text); String result = "";
        while (match.find()) result = normalizeAspect(match.group(1).replaceAll("\\s", "").replaceAll("[：/대]", ":"));
        return result;
    }

    /**
     * Clear image commands bypass the text model, so a stale capability refusal cannot prevent rendering. Other requests
     * keep their normal AI/tools path. Messages and attachments are the stored conversation BEFORE the new user turn.
     */
    public static JSONObject chatArguments(String text, JSONArray messages, JSONArray attached, boolean codex) {
        return chatArgumentsLazily(text, () -> messages, attached, codex);
    }
    /**
     * chatArguments reading the conversation only when the draft could be an image request (a drawing command, a retry or
     * edit wording); the answer is the same. The 작업창 composer asks on every keystroke while a turn runs, and most drafts
     * are ordinary instructions that never need the room's messages.
     */
    public static JSONObject chatArgumentsLazily(String text, java.util.function.Supplier<JSONArray> history, JSONArray attached, boolean codex) {
        String latest = text == null ? "" : text.strip();
        // Quoted examples/code and questions about image generation are not commands to render them.
        String command = latest.replaceAll("(?s)```.*?```|`[^`]*`|\"[^\"]*\"|“[^”]*”|‘[^’]*’|'[^']*'", " ");
        if (command.isBlank() || command.matches("(?is).*(그리|그려|생성|만들|제작|편집|수정).{0,8}(지\\s*마|지\\s*말|지\\s*않|하지\\s*마).*")) return null;
        if (command.matches("(?is).*\\b(don['’]t|do\\s+not|never)\\s+(draw|paint|illustrate|render|generate|create|make)\\b.*")) return null;
        if (command.matches("(?is).*(코드로|svg|mermaid|ascii|아스키|html|canvas|how\\s+(to|do|can)|what\\s+does).*")) return null;
        if (command.matches("(?is).*(프롬프트|prompt|방법|사용법|기능|버튼|코드|오류|에러|뜻|의미).*(써|작성|알려|설명|추천|고쳐|수정|추가|도입|번역|translate|write|explain).*")) return null;
        boolean ratioOnly = !aspect(latest).isEmpty() && RATIO.matcher(latest).replaceAll("").replaceAll("비율|으로|로|[\\s.!?。]", "").isEmpty();
        boolean editing = ratioOnly || command.matches("(?is).*(바꿔|고쳐|수정해|편집해|밝게|어둡게|지워|추가해|넣어\\s*줘|빼\\s*줘|색.*변경|edit\\b|change\\b|brighter\\b|darker\\b).*"),
            draw = imageCommand(command), retrying = retry(latest);
        // Without one of these the answer below is null whatever came before, so the messages are not read at all.
        if (!draw && !retrying && !editing) return null;
        JSONArray messages = history == null ? null : history.get();
        boolean reference = hasImage(attached), previousPicture = false, earlierPhoto = false; String previousShape = "";
        JSONObject lastAnswer = null;
        for (int i = messages == null ? -1 : messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i); if (message == null || WorkBook.ERROR.equals(message.optString("role"))) continue;
            if (WorkBook.ASSISTANT.equals(message.optString("role"))) {
                lastAnswer = message; JSONArray made = message.optJSONArray("files");
                for (int f = 0; made != null && f < made.length(); f++) {
                    JSONObject picture = made.optJSONObject(f);
                    if (picture != null && !picture.optString("picture").isEmpty()) { previousPicture = true; previousShape = normalizeAspect(picture.optString("pictureAspect")); }
                }
            }
            break;
        }
        for (int i = messages == null ? -1 : messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message != null && WorkBook.USER.equals(message.optString("role"))) { earlierPhoto = hasImage(message.optJSONArray("attachments")); break; }
        }
        boolean recentImage = previousPicture || earlierPhoto;
        String previous = formPrompt("", messages);
        boolean continuation = continuation(latest), again = retrying && (recentImage || imageCommand(previous));
        if (!draw && !again && !((reference || recentImage) && editing)) return null;
        boolean edit = reference || recentImage && (editing || continuation || command.matches("(?is).*(이\\s*(그림|사진|이미지)|방금|직전|이전|same|this|that).*"));
        String prompt = latest;
        boolean refersBack = continuation || command.matches("(?is).*(이\\s*(거|것|걸|내용)|위\\s*(내용|설명)|방금|직전|아까|그거|그걸|첫\\s*번째|두\\s*번째|this|that|above|previous).*"), inherited = false;
        if (refersBack && !previous.isEmpty()) {
            String context = previous.length() > 1600 ? previous.substring(0, 1600) : previous;
            String answer = !continuation && lastAnswer != null && !previousPicture ? lastAnswer.optString("text") : "";
            if (answer.length() > 1000) answer = answer.substring(0, 1000);
            prompt = "Earlier user description:\n" + context + (answer.isBlank() ? "" : "\nEarlier answer:\n" + answer) + "\n\nLatest image request (follow this):\n" + latest;
            inherited = true;
        }
        if (continuation && !inherited && !edit) throw new IllegalArgumentException("그릴 대상을 함께 적어 주세요. 예: ‘고양이를 9:16으로 그려줘’");
        String shape = aspect(latest);
        if (shape.isEmpty() && (inherited || edit)) shape = !previousShape.isEmpty() ? previousShape : previousAspect(messages);
        JSONObject result = arguments(obj("prompt", prompt, "edit", edit, "aspect", shape).toString());
        // A historical ratio is context; the latest user's explicit ratio has priority.
        return obj("prompt", result.optString("prompt"), "edit", edit, "aspect", shape.isEmpty() ? "square" : shape, "codex", codex);
    }
    private static boolean imageCommand(String text) {
        return DRAW_COMMAND.matcher(text).find() || IMAGE_NOUN.matcher(text).find() && MAKE_COMMAND.matcher(text).find();
    }
    private static String previousAspect(JSONArray messages) {
        for (int i = messages == null ? -1 : messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !WorkBook.USER.equals(message.optString("role"))) continue;
            String text = message.optString("text"), shape = aspect(text);
            if (!shape.isEmpty()) return shape;
            if (imageCommand(text) && !continuation(text)) break; // A new subject without a ratio started square.
        }
        return "";
    }
    private static boolean hasImage(JSONArray files) {
        for (int i = 0; files != null && i < files.length(); i++)
            if (files.optJSONObject(i) != null && WorkBook.IMAGE.equals(files.optJSONObject(i).optString("kind"))) return true;
        return false;
    }
    private static boolean continuation(String text) {
        String shortText = RATIO.matcher(text).replaceAll("").replaceAll("비율|으로|로", "").strip();
        return retry(shortText) || shortText.matches("(?i)(다시\\s*)?(그려\\s*주세요|생성해\\s*주세요|만들어\\s*줘)[.!?。]*");
    }

    /** Reuse the user's earlier description for a short retry; assistant capability claims are never a visual prompt. */
    public static String formPrompt(String draft, JSONArray messages) {
        String text = draft == null ? "" : draft.strip();
        if (!text.isBlank() && !continuation(text)) return text;
        for (int i = messages == null ? -1 : messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i);
            if (message == null || !WorkBook.USER.equals(message.optString("role"))) continue;
            String previous = message.optString("text").strip();
            if (!previous.isBlank() && !continuation(previous)) return previous;
        }
        return "";
    }
    private static boolean retry(String text) { return text.matches("(?i)(다시|다시 해\\s*줘|다시 시도|(?:다시\\s*)?그려\\s*줘|생성해\\s*줘|retry|try again)[.!?。]*"); }
    /** The app's explicit image action pins the selected renderer; losing Codex never switches a queued job to a paid API. */
    public static JSONObject directArguments(String json) {
        JSONObject checked = arguments(json);
        try { checked.put("codex", new JSONObject(json).optBoolean("codex")); return checked; }
        catch (Exception e) { throw new IllegalArgumentException("그림 요청 형식이 올바르지 않아요."); }
    }
    /** An explicitly selected image mode uses the ordinary chat draft, including its earlier description on a retry. */
    public static JSONObject composerArguments(String text, JSONArray messages, String aspect, boolean edit, boolean codex) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("그릴 내용을 대화 입력창에 적어 주세요.");
        String prompt = formPrompt(text, messages);
        if (prompt.isEmpty()) throw new IllegalArgumentException("그릴 대상을 함께 적어 주세요.");
        return directArguments(obj("prompt", prompt, "aspect", aspect, "edit", edit, "codex", codex).toString());
    }

    /** OpenAI's image API: one picture of the asked shape, as base64. */
    public static JSONObject openAiRequest(JSONObject args) { return obj("model", OPENAI_MODEL, "prompt", args.optString("prompt"), "size", size(args), "n", 1); }
    private static String size(JSONObject args) { String[] one = shape(args.optString("aspect")); return one == null ? "1024x1024" : one[3]; }
    /** An edit: the image API's multipart form with the prompt and the pictures to change ([{data (base64), mime}]). */
    public static byte[] openAiEdit(JSONObject args, JSONArray sources, String boundary) {
        java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
        String[][] fields = {{"model", OPENAI_MODEL}, {"prompt", args.optString("prompt")}, {"size", size(args)}, {"n", "1"}};
        for (String[] field : fields) write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + field[0] + "\"\r\n\r\n" + field[1] + "\r\n");
        for (int i = 0; sources != null && i < sources.length(); i++) {
            JSONObject source = sources.optJSONObject(i); if (source == null || source.optString("data").isEmpty()) continue;
            String mime = source.optString("mime", "image/png").matches("image/(png|jpeg|webp)") ? source.optString("mime") : "image/png";
            String extension = mime.equals("image/jpeg") ? "jpg" : mime.substring(6);
            write(body, "--" + boundary + "\r\nContent-Disposition: form-data; name=\"image[]\"; filename=\"picture-" + (i + 1) + "." + extension + "\"\r\nContent-Type: " + mime + "\r\n\r\n");
            byte[] bytes = java.util.Base64.getDecoder().decode(source.optString("data"));
            body.write(bytes, 0, bytes.length); write(body, "\r\n");
        }
        write(body, "--" + boundary + "--\r\n");
        return body.toByteArray();
    }
    private static void write(java.io.ByteArrayOutputStream out, String text) { byte[] bytes = text.getBytes(java.nio.charset.StandardCharsets.UTF_8); out.write(bytes, 0, bytes.length); }
    /** The picture in an image API reply, {data, format}, or null. */
    public static JSONObject openAiPicture(JSONObject reply) {
        JSONArray data = reply == null ? null : reply.optJSONArray("data");
        JSONObject first = data == null ? null : data.optJSONObject(0);
        String base64 = first == null ? "" : first.optString("b64_json");
        if (base64.isEmpty()) return null;
        String format = reply.optString("output_format", "png").toLowerCase(Locale.ROOT);
        return obj("data", base64, "format", format.matches("png|jpeg|webp") ? format : "png", "revised", first.optString("revised_prompt"));
    }
    /**
     * A connection that will not use the image API at all (a plan sign-in, a key without image access), as against a busy
     * server or a refused prompt, which are worth trying again.
     */
    public static boolean refusedService(int status, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return status == 401 || status == 403 || status == 404 || lower.contains("subscription") || lower.contains("supports only") || lower.contains("api key")
            || lower.contains("not supported") || lower.contains("verified") || lower.contains("scope");
    }

    /** The pictures of the user's newest message that an edit changes: [{data, mime}]. */
    public static JSONArray sources(JSONArray turns) {
        JSONArray result = arr();
        JSONObject last = turns == null || turns.length() == 0 ? null : turns.optJSONObject(turns.length() - 1);
        JSONArray files = last == null ? null : last.optJSONArray("files");
        for (int i = 0; files != null && i < files.length() && result.length() < 3; i++) {
            JSONObject file = files.optJSONObject(i);
            if (file != null && WorkBook.IMAGE.equals(file.optString("kind")) && !file.optString("data").isEmpty())
                result.put(obj("data", file.optString("data"), "mime", file.optString("mime", "image/png")));
        }
        return result;
    }
    /** Editing a referenced earlier photo keeps using that original, including after a failed/empty answer. */
    public static JSONArray editSources(JSONArray turns) {
        for (int i = turns == null ? -1 : turns.length() - 1; i >= 0; i--) {
            JSONObject turn = turns.optJSONObject(i);
            if (turn == null || !"user".equals(turn.optString("role"))) continue;
            JSONArray found = sources(arr(turn));
            if (found.length() > 0) return found;
        }
        return arr();
    }
    /** The draw_picture calls in a finished response's output items. */
    public static JSONArray calls(JSONArray output) {
        JSONArray calls = arr();
        for (int i = 0; output != null && i < output.length(); i++) {
            JSONObject item = output.optJSONObject(i);
            if (item != null && "function_call".equals(item.optString("type")) && NAME.equals(item.optString("name"))) calls.put(item);
        }
        return calls;
    }
    /** What goes back to ChatGPT after a call: never the picture itself. */
    public static JSONObject result(JSONObject picture, String by, String error) {
        return picture != null ? obj("ok", true, "made_by", by, "note", "The picture was made and is shown to the user; describe it in a sentence.")
            : obj("ok", false, "error", error == null || error.isBlank() ? "drawing failed" : error);
    }
}
