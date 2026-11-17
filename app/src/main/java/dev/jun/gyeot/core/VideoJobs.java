package dev.jun.gyeot.core;

import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Durable video requests, separate from the source images. Uncertain POSTs are never automatically repeated. */
public final class VideoJobs {
    private VideoJobs() {}
    public static JSONObject find(JSONArray jobs, String id) {
        for (int i = 0; i < jobs.length(); i++) { JSONObject job = jobs.optJSONObject(i); if (job != null && id.equals(job.optString("id"))) return job; }
        return null;
    }
    public static boolean active(JSONObject job) { return java.util.Set.of("queued", "submitting", "running", "downloading").contains(job.optString("state")); }
    public static boolean resumable(JSONObject job) { return "paused".equals(job.optString("state")) && !job.optString("requestId").isBlank(); }
    public static JSONObject create(JSONArray jobs, JSONObject image, String prompt, int seconds, String account, long now) {
        if (image == null || !WorkBook.safeId(image.optString("id"))) throw new IllegalArgumentException("원본 이미지를 선택해 주세요.");
        return create(jobs, image, prompt, seconds, "720p", "16:9", account, now);
    }
    /** A null image explicitly requests text-to-video. Old records have 720p quality. */
    public static String resolution(JSONObject job) { return GrokVideo.resolution(job.optString("resolution", "720p")); }
    public static JSONObject create(JSONArray jobs, JSONObject image, String prompt, int seconds, String resolution, String aspect, String account, long now) {
        if (image != null && !WorkBook.safeId(image.optString("id"))) throw new IllegalArgumentException("원본 이미지를 선택해 주세요.");
        if (image == null) GrokVideo.textRequest(prompt, seconds, resolution, aspect);
        if (account == null || account.isBlank()) throw new IllegalStateException("설정 → Grok에서 구독 계정으로 로그인해 주세요.");
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject previous = jobs.optJSONObject(i);
            if (previous != null && image != null && image.optString("id").equals(previous.optString("sourceId")) && (active(previous) || resumable(previous)))
                throw new IllegalStateException("이 이미지의 영상 요청이 남아 있어요. 갤러리에서 기존 요청 결과를 먼저 확인해 주세요.");
        }
        JSONObject job = obj("id", "v" + java.util.UUID.randomUUID().toString().replace("-", ""), "sourceId", image == null ? "" : image.optString("id"),
            "sourcePrompt", image == null ? "" : Gallery.prompt(image), "sourceUri", image == null ? "" : image.optString("uri"), "prompt", GrokVideo.prompt(prompt), "duration", GrokVideo.seconds(seconds),
            "resolution", GrokVideo.resolution(resolution), "aspect", GrokVideo.aspect(aspect),
            "model", GrokVideo.MODEL, "accountId", account, "createdAt", now, "state", "queued");
        jobs.put(job); return job;
    }
    public static void recover(JSONArray jobs) {
        for (int i = 0; i < jobs.length(); i++) {
            JSONObject job = jobs.optJSONObject(i); if (job == null || !active(job)) continue;
            if (!job.optString("requestId").isBlank()) { put(job, "state", "paused"); put(job, "error", "앱이 중단됐어요. 기존 요청의 결과를 다시 확인할 수 있어요."); }
            else if ("queued".equals(job.optString("state"))) { put(job, "state", "failed"); put(job, "error", "전송 전에 앱이 종료됐어요. 다시 영상화를 요청해 주세요."); }
            else { put(job, "state", "unknown"); put(job, "error", "전송 중 앱이 중단되어 접수 여부를 확인할 수 없어요. 중복 생성을 막기 위해 자동 재전송하지 않았어요."); }
        }
    }
    public static String label(JSONObject job) {
        return switch (job.optString("state")) {
            case "queued" -> "영상 생성 대기 중"; case "submitting" -> "Grok에 요청 보내는 중";
            case "running" -> "Grok 영상 생성 중"; case "downloading" -> "완성된 영상 저장 중";
            case "done" -> "영상 완성 · " + resolution(job) + " · " + job.optInt("duration") + "초"; case "paused" -> "결과 다시 확인";
            case "unknown" -> "접수 여부 확인 필요"; default -> "영상 생성 실패";
        };
    }
}
