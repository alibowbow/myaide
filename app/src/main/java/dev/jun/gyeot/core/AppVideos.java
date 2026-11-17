package dev.jun.gyeot.core;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Voice uses the same durable video queue as the composer. Repeated tool calls never repeat a POST. */
public final class AppVideos {
    public static final String GENERATE = "app_generate_video", STATUS = "app_video_status", PLAY = "app_play_video";
    private AppVideos() {}
    public static JSONObject declaration(String name) {
        if (PLAY.equals(name)) return obj("name", name, "description", "Play an existing video FULL SCREEN inside this app. Never generates a video. Omit selectors for the video just requested, or the latest saved request in a new conversation. If still generating, wait for that exact job. Only verified:true and playing:true mean playback actually started.",
            "parameters", obj("type", "OBJECT", "properties", obj(
                "video_id", obj("type", "STRING", "description", "Exact video_id returned by this app"),
                "query", obj("type", "STRING", "description", "Short subject keywords for an older video. Use video_id OR query, not both."))));
        if (STATUS.equals(name)) return obj("name", name, "description", "Read the actual saved video job state. Omit video_id for the video just requested. Does not generate or open a video.",
            "parameters", obj("type", "OBJECT", "properties", obj("video_id", obj("type", "STRING"))));
        return obj("name", name, "description", "Generate a video in this app with the Grok subscription connected in Settings. Use the actual spoken description. mode=image animates an existing generated image; mode=text creates from a description. Successful queued means accepted, not completed. Never use phone controls to type in this app.",
            "parameters", obj("type", "OBJECT", "properties", obj(
                "batch_index", obj("type", "INTEGER", "description", "Zero-based item index ONLY for an explicit multiple-video request; default 0, reuse the index on retries."),
                "prompt", obj("type", "STRING", "description", "Actual requested scene or motion; do not invent a subject after unclear speech"),
                "mode", obj("type", "STRING", "enum", arr("image", "text")),
                "image_id", obj("type", "STRING", "description", "Exact image_id from app_image_status; omit for the image just generated"),
                "image_query", obj("type", "STRING", "description", "Subject keywords for an older image; cannot combine with image_id"),
                "resolution", obj("type", "STRING", "enum", arr("720p", "1080p")),
                "duration", obj("type", "INTEGER", "description", "1 to 15 seconds; default 8"),
                "aspect", obj("type", "STRING", "enum", arr("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3"))), "required", arr("prompt", "mode")));
    }
    public static final String INSTRUCTIONS = " For an explicit video creation request use app_generate_video, not enqueue_task, app_generate_image or phone control. "
        + "For '이 이미지를 영상으로 만들어줘' use mode=image; omit image_id to animate the picture just generated. If several images match, ask which one. Never silently replace a missing image with text-to-video. "
        + "For a described new scene use mode=text. Ask for the scene if the user only says '영상 만들어줘', or speech is unclear. Honor spoken 1080p, seconds and ratio. "
        + "The prompt stays in the conversation; never tell the user to type because own-app phone actions are blocked. queued:true only means started. "
        + "For an explicit multiple-video request call once per video using batch_index 0, 1, 2 and so on (maximum 12). Requests may run concurrently; retries must reuse the original index. "
        + "For video progress use app_video_status; report its actual state. Only completed:true means a saved video. New videos are available in the app gallery and 들퍼레/영상; historical videos retain their recorded locations. "
        + "For '방금 만든 영상 틀어줘', '영상 재생해줘', '동영상 크게 보여줘' or watching a generated video use app_play_video, not app_open_screen, app_show_image or phone controls. "
        + "Keep the video_id from app_generate_video for that video across turns. For older videos pass short subject keywords in query; if ambiguous ask which candidate. "
        + "Only verified:true AND playing:true means the exact video's first frame is visible and it is playing. waiting:true means playback is scheduled after this job finishes, not started. "
        + "Never claim a gallery tab or a completed generation is playback. A failed/missing/paused result is not still generating. Viewing never authorizes another generation. ";
    public interface Submitter { JSONObject submit(JSONObject source, String prompt, int duration, String resolution, String aspect) throws Exception; }
    public static JSONObject status(JSONArray jobs, String id) {
        if (id == null || id.isBlank()) {
            long at = -1; id = "";
            for (int i = 0; i < jobs.length(); i++) { JSONObject candidate = jobs.optJSONObject(i); if (candidate != null && candidate.optLong("createdAt") >= at) { at = candidate.optLong("createdAt"); id = candidate.optString("id"); } }
        }
        JSONObject job = VideoJobs.find(jobs, id);
        if (job == null) return failure("해당 영상 요청을 찾지 못했어요.");
        boolean done = "done".equals(job.optString("state")) && !job.optString("uri").isBlank();
        return obj("ok", true, "video_id", id, "state", job.optString("state"), "completed", done, "resolution", VideoJobs.resolution(job),
            "duration", job.optInt("duration"), "prompt", job.optString("prompt"), "note", VideoJobs.label(job), "error", job.optString("error"));
    }
    /** Resolve only persisted jobs. Never substitute an older clip for an explicit, pending or failed target. */
    public static JSONObject playback(JSONArray jobs, JSONObject args, String recent) {
        for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) {
            String key = keys.next();
            if (!Set.of("video_id", "query").contains(key) || !(args.opt(key) instanceof String value) || value.length() > 300)
                return playbackFailure("invalid", "영상 식별자 또는 짧은 검색어만 전달해 주세요.");
        }
        String id = args.optString("video_id").strip(), query = args.optString("query").strip();
        if (!id.isEmpty() && !query.isEmpty()) return playbackFailure("invalid", "영상 식별자와 검색어 중 하나만 선택해 주세요.");
        if (!query.isEmpty()) {
            String needle = normalized(query); JSONArray matches = arr(); int count = 0;
            if (needle.isEmpty()) return playbackFailure("invalid", "찾을 영상의 내용을 말씀해 주세요.");
            for (int i = jobs.length() - 1; i >= 0; i--) {
                JSONObject job = jobs.optJSONObject(i);
                if (job != null && normalized(job.optString("prompt") + " " + job.optString("sourcePrompt")).contains(needle)) {
                    id = job.optString("id"); count++;
                    if (matches.length() < 8) matches.put(obj("video_id", id, "prompt", job.optString("prompt"), "state", job.optString("state"), "created_at", job.optLong("createdAt")));
                }
            }
            if (count == 0) return playbackFailure("missing", "해당 내용의 영상을 찾지 못했어요.");
            if (count > 1) { JSONObject ambiguous = playbackFailure("ambiguous", "해당하는 영상이 여러 개예요. 어떤 영상을 재생할지 확인해 주세요."); put(ambiguous, "candidates", matches); put(ambiguous, "count", count); return ambiguous; }
        } else if (id.isEmpty()) id = recent == null ? "" : recent;
        JSONObject result = status(jobs, id);
        if (!result.optBoolean("ok")) return playbackFailure("missing", "요청한 영상을 찾지 못했어요.");
        JSONObject job = VideoJobs.find(jobs, result.optString("video_id"));
        put(result, "verified", false); put(result, "playing", false); put(result, "waiting", false);
        put(result, "playable", result.optBoolean("completed"));
        put(result, "pending", VideoJobs.active(job));
        if (!result.optBoolean("completed") && !VideoJobs.active(job)) {
            put(result, "ok", false);
            if (result.optString("error").isBlank()) put(result, "error", "done".equals(job.optString("state")) ? "저장된 영상 파일을 찾지 못했어요." : "생성이 중단되어 아직 재생할 수 없어요. 갤러리에서 상태를 확인해 주세요.");
        }
        return result;
    }
    private static JSONObject playbackFailure(String state, String error) {
        return obj("ok", false, "state", state, "verified", false, "playing", false, "playable", false, "pending", false, "waiting", false, "error", error);
    }
    private static String normalized(String text) { return text.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}¿¡]+", ""); }
    /** Only an unqualified latest-video request can repair a mistaken gallery navigation. Named subjects use the tool's query. */
    public static boolean playReference(String speech) {
        String text = normalized(speech == null ? "" : speech);
        if (!text.matches(".*(영상|동영상|비디오).*(틀어|재생|보여|열어|띄워).*")) return false;
        return text.replaceAll("방금|만든|만들었던|완성된|최근|마지막|들퍼레|앱에서|갤러리에서|동영상|영상|비디오|전체화면으로|전체화면|크게|확대해서|확대|재생해줄래|재생해주세요|재생해줘|재생해|재생|보여줄래|보여주세요|보여줘|틀어줄래|틀어주세요|틀어줘|열어줘|띄워줘|이거|그거|이걸|그걸|좀|조금|더|그|이|를|을", "").isEmpty();
    }
    public static final class Conversation {
        private final Map<String, JSONObject> calls = new LinkedHashMap<>();
        private final Map<Integer, JSONObject> batch = new LinkedHashMap<>();
        private String heard = "", latest = ""; private long revision, submitted = -1;
        private JSONObject receipt;
        public void reset() { calls.clear(); batch.clear(); heard = ""; latest = ""; revision++; submitted = -1; receipt = null; }
        public void heard(String value) { if (value != null && !value.isBlank() && !value.equals(heard)) { heard = value.strip(); batch.clear(); revision++; } }
        public String latest() { return latest; }
        public JSONObject submit(String id, JSONObject args, JSONArray works, JSONArray gallery, String latestImageThread, BooleanSupplier allowed, Submitter submitter) {
            if (!allowed.getAsBoolean()) return failure("취소된 영상 요청이에요.");
            if (calls.containsKey(id)) return copy(calls.get(id));
            try {
                if (id == null || id.isBlank()) throw new IllegalArgumentException("영상 요청을 확인하지 못했어요.");
                for (java.util.Iterator<String> keys = args.keys(); keys.hasNext();) if (!Set.of("prompt", "mode", "image_id", "image_query", "resolution", "duration", "aspect", "batch_index").contains(keys.next())) throw new IllegalArgumentException("영상 요청 옵션을 확인해 주세요.");
                String prompt = args.opt("prompt") instanceof String ? args.optString("prompt").strip() : "", mode = args.optString("mode");
                if (!Set.of("image", "text").contains(mode) || unclear(prompt) || !heard.isEmpty() && unclear(heard)) throw new IllegalArgumentException("어떤 영상을 만들지 다시 말씀해 주세요.");
                if (AppNavigation.cancelled(heard) || heard.matches("(?is).*(만들지\\s*마|생성하지\\s*마|보여\\s*줘|재생\\s*해|틀어\\s*줘|다\\s*됐어).*")) throw new IllegalArgumentException("현재 발화는 새 영상 생성 요청이 아니에요.");
                int batchIndex = GenerationPool.voiceIndex(heard, args);
                if (batch.containsKey(batchIndex)) { remember(id, batch.get(batchIndex)); return copy(batch.get(batchIndex)); }
                String resolution = GrokVideo.resolution(args.optString("resolution", "1080p"));
                if (heard.matches("(?is).*(1080\\s*p|천팔십|풀\\s*에이치디|full\\s*hd).*")) resolution = "1080p";
                else if (heard.matches("(?is).*(720\\s*p|칠백이십).*")) resolution = "720p";
                int duration = args.optInt("duration", 8); GrokVideo.seconds(duration);
                if (args.has("duration") && (!(args.opt("duration") instanceof Number n) || n.doubleValue() != duration)) throw new IllegalArgumentException("영상 길이는 정수 초로 말씀해 주세요.");
                String aspect = GrokVideo.aspect(args.optString("aspect", "16:9"));
                JSONObject source = null;
                if ("image".equals(mode)) {
                    JSONObject selector = obj();
                    if (!args.optString("image_id").isBlank()) put(selector, "image_id", args.optString("image_id"));
                    if (!args.optString("image_query").isBlank()) put(selector, "query", args.optString("image_query"));
                    JSONObject resolved = AppGallery.resolve(works, gallery, selector, latestImageThread);
                    if (!"ready".equals(resolved.optString("state"))) return obj("ok", false, "queued", false, "completed", false, "image", resolved,
                        "error", "영상으로 만들 이미지를 아직 확정하지 못했어요. 이미지 생성 상태나 대상을 먼저 확인해 주세요.");
                    source = Gallery.find(gallery, resolved.optString("image_id"));
                    if (source == null) throw new IllegalArgumentException("원본 이미지가 없어요.");
                } else if (!args.optString("image_id").isBlank() || !args.optString("image_query").isBlank() || heard.matches("(?is).*(이\\s*(사진|이미지|그림)|방금.*(그림|이미지)).*")) throw new IllegalArgumentException("기존 이미지 영상화는 mode=image로 요청해 주세요.");
                if (!allowed.getAsBoolean()) return failure("취소된 영상 요청이에요.");
                JSONObject job = submitter.submit(source, prompt, duration, resolution, aspect);
                if (job == null || job.optString("id").isBlank()) throw new IllegalStateException("영상 요청을 저장하지 못했어요.");
                latest = job.optString("id"); submitted = revision;
                receipt = obj("ok", true, "queued", true, "completed", false, "video_id", latest, "thread_id", job.optString("threadId"),
                    "resolution", resolution, "duration", duration, "note", "영상 생성 요청을 접수했어요. 작업 대화와 갤러리에서 실제 상태를 확인할 수 있어요.");
                batch.put(batchIndex, copy(receipt)); remember(id, receipt); return copy(receipt);
            } catch (Exception e) { return failure(e.getMessage()); }
        }
        private void remember(String id, JSONObject value) { calls.put(id, copy(value)); while (calls.size() > 32) calls.remove(calls.entrySet().iterator().next().getKey()); }
    }
    private static boolean unclear(String value) {
        String text = value.toLowerCase(java.util.Locale.ROOT).replaceAll("[\\s\\p{Punct}¿¡]+", "");
        return Set.of("", "qué", "que", "네", "응", "예", "뭐", "뭐라고", "영상만들어줘", "동영상생성해줘", "영상생성해줘").contains(text);
    }
    private static JSONObject failure(String error) { return obj("ok", false, "queued", false, "completed", false, "error", error == null ? "영상 요청을 처리하지 못했어요." : error); }
}
