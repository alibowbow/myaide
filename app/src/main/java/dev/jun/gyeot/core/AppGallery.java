package dev.jun.gyeot.core;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Actual image-job state and exact gallery targets, shared by voice and the visible viewer. */
public final class AppGallery {
    public static final String STATUS = "app_image_status", SHOW = "app_show_image";
    private AppGallery() {}
    public static JSONObject declaration(String name) {
        return obj("name", name, "description", SHOW.equals(name)
            ? "Show an existing generated image FULL SCREEN inside this app. If its job is running, wait for that exact job and open it when ready. Never generates another image. Only verified:true means an image is actually visible."
            : "Read the actual state of an image request: running, ready, failed, missing or ambiguous. Never infer progress from an earlier queued receipt. Does not open anything or generate another image.",
            "parameters", obj("type", "OBJECT", "properties", obj(
                "thread_id", obj("type", "STRING", "description", "Exact thread_id from app_generate_image or a previous image result"),
                "image_id", obj("type", "STRING", "description", "Exact image_id returned by this app"),
                "query", obj("type", "STRING", "description", "Short subject keywords for an older image, not the entire voice command. Use only one selector; omit all for the image just requested."))));
    }
    public static final String INSTRUCTIONS = " For '이미지 크게 보여줘', '좀 크게 보여 줄래' or a request to view a generated picture, use app_show_image, not app_open_screen gallery or phone control. "
        + "A gallery list is not a full-screen picture. For '다 됐어?' use app_image_status and read its actual result; an earlier queued:true is not evidence it is still running. "
        + "Keep the thread_id from app_generate_image and use it for that image even if the user refers to it in another language, such as Spain after 스페인. "
        + "Only say the picture is open after verified:true; waiting:true means it will open after completion. A failed or missing result must not be described as still generating. "
        + "If several pictures match, ask which one using their short descriptions. Viewing a picture never authorizes regenerating it. "
        + "Mixed-language recognition errors do not change the previous image subject. A music-video request is a separate request, not permission to generate or open an unrelated picture. ";

    public static void requested(JSONObject thread, String prompt, long at) {
        put(thread, "imageRequest", obj("prompt", prompt, "at", at));
    }
    private static String normalized(String value) { return value == null ? "" : value.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}¿¡]+", ""); }
    /** A narrow fallback when the voice model mistakenly asks only to open the gallery tab. */
    public static boolean enlargeReference(String speech) {
        String text = normalized(speech == null ? "" : speech.replaceAll("¿[^?]*\\?", ""));
        if (!text.matches(".*(크게|확대|전체화면).*(보여|열|띄워).*")) return false;
        if (text.matches(".*(하지|말고|마세요|취소|그만|뮤직|비디오|영상|유튜브|삼성|구글|번역|문장|예시).*")) return false;
        return text.replaceAll("전체화면으로|전체화면|으로|크게|확대해서|확대|보여줄래|보여주세요|보여줘|보여|열어줘|띄워줘|사진|이미지|그림|그거|이거|그걸|이걸|방금|아까|좀|조금|더|그|이|를|을", "").isBlank();
    }
    public static JSONObject resolve(JSONArray works, JSONArray gallery, JSONObject args, String recentThread) {
        try {
            int selectors = 0;
            for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) {
                String key = keys.next();
                if (!Set.of("thread_id", "image_id", "query").contains(key) || !(args.opt(key) instanceof String value) || value.length() > 300)
                    return failure("이미지 식별자나 짧은 검색어만 전달해 주세요.");
                if (!value.isBlank()) selectors++;
            }
            if (selectors > 1) return failure("이미지나 작업 식별자, 검색어 중 하나만 선택해 주세요.");
            String image = args.optString("image_id").strip(), thread = args.optString("thread_id").strip(), query = args.optString("query").strip();
            if (!image.isEmpty()) {
                JSONObject entry = Gallery.find(gallery, image);
                return entry == null ? missing() : ready(entry);
            }
            if (!query.isEmpty()) return search(works, gallery, query);
            if (thread.isEmpty()) thread = recentThread == null ? "" : recentThread;
            if (!thread.isEmpty()) return thread(works, gallery, thread);
            // A new voice session can still find a running or failed image request saved by an earlier session.
            long at = -1; String latest = ""; JSONObject picture = null;
            for (int i = 0; i < gallery.length(); i++) {
                JSONObject entry = gallery.optJSONObject(i); if (entry != null && entry.optLong("at") > at) { at = entry.optLong("at"); picture = entry; }
            }
            for (int i = 0; i < works.length(); i++) {
                JSONObject work = works.optJSONObject(i), request = work == null ? null : work.optJSONObject("imageRequest");
                if (request != null && request.optLong("at") > at) { at = request.optLong("at"); latest = work.optString("id"); picture = null; }
            }
            return !latest.isEmpty() ? thread(works, gallery, latest) : picture == null ? missing() : ready(picture);
        } catch (Exception invalid) { return failure("이미지 요청을 확인하지 못했어요."); }
    }
    private static JSONObject search(JSONArray works, JSONArray gallery, String query) {
        Map<String, JSONObject> found = new LinkedHashMap<>(); String[] words = query.toLowerCase(Locale.ROOT).split("\\s+");
        for (int i = 0; i < gallery.length(); i++) {
            JSONObject entry = gallery.optJSONObject(i);
            if (entry != null && matches(entry.optString("prompt") + " " + Gallery.prompt(entry) + " " + entry.optString("title"), words))
                found.put("image:" + entry.optString("id"), ready(entry));
        }
        for (int i = 0; i < works.length(); i++) {
            JSONObject work = works.optJSONObject(i), request = work == null ? null : work.optJSONObject("imageRequest");
            if (request != null && matches(request.optString("prompt"), words)) {
                JSONObject state = thread(works, gallery, work.optString("id"));
                if (!"ready".equals(state.optString("state"))) found.put("thread:" + work.optString("id"), state);
            }
        }
        if (found.isEmpty()) return missing();
        if (found.size() == 1) return found.values().iterator().next();
        JSONArray choices = arr();
        for (JSONObject candidate : found.values()) {
            if (choices.length() == 6) break;
            choices.put(obj("thread_id", candidate.optString("thread_id"), "image_id", candidate.optString("image_id"), "prompt", candidate.optString("prompt"), "state", candidate.optString("state")));
        }
        return obj("ok", false, "state", "ambiguous", "completed", false, "verified", false, "candidates", choices, "note", "여러 이미지가 맞아요. 어떤 이미지를 볼지 확인해 주세요.");
    }
    private static boolean matches(String value, String[] words) {
        String text = normalized(value);
        for (String word : words) if (!text.contains(normalized(word))) return false;
        return true;
    }
    private static JSONObject thread(JSONArray works, JSONArray gallery, String id) {
        JSONObject work = WorkBook.find(works, id), request = work == null ? null : work.optJSONObject("imageRequest");
        long since = request == null ? 0 : request.optLong("at"); JSONObject latest = null;
        for (int i = 0; i < gallery.length(); i++) {
            JSONObject entry = gallery.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("thread")) && entry.optLong("at") >= since
                && (latest == null || entry.optLong("at") > latest.optLong("at"))) latest = entry;
        }
        if (latest != null) return ready(latest);
        if (work == null || request == null) return missing();
        String prompt = request == null ? WorkBook.request(work) : request.optString("prompt");
        if (WorkBook.RUNNING.equals(work.optString("state"))) return obj("ok", true, "state", "running", "completed", false, "verified", false,
            "thread_id", id, "prompt", prompt, "note", "이미지 작업이 아직 진행 중이에요. 완료된 이미지는 아직 없어요.");
        JSONArray messages = work.optJSONArray("messages"); String error = "작업은 끝났지만 갤러리에 저장된 이미지를 찾지 못했어요.";
        for (int i = messages == null ? -1 : messages.length() - 1; i >= 0; i--) {
            JSONObject message = messages.optJSONObject(i); if (message == null || message.optLong("at") < since) continue;
            if (WorkBook.ERROR.equals(message.optString("role"))) { error = message.optString("text"); break; }
            if (WorkBook.USER.equals(message.optString("role"))) break;
        }
        JSONObject result = failure(error); put(result, "thread_id", id); put(result, "prompt", prompt); return result;
    }
    private static JSONObject ready(JSONObject entry) { return obj("ok", true, "state", "ready", "completed", true, "verified", false,
        "thread_id", entry.optString("thread"), "image_id", entry.optString("id"), "prompt", Gallery.prompt(entry), "note", "이미지가 완성되어 갤러리에 저장되어 있어요. 아직 전체 화면 표시를 확인한 것은 아니에요."); }
    private static JSONObject missing() { return obj("ok", false, "state", "missing", "completed", false, "verified", false, "note", "요청한 이미지나 생성 작업을 찾지 못했어요. 진행 중이라고 추측하지 마세요."); }
    public static JSONObject failure(String error) { return obj("ok", false, "state", "failed", "completed", false, "verified", false, "error", error); }
}
