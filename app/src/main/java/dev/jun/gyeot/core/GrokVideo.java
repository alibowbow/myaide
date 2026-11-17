package dev.jun.gyeot.core;

import java.net.URI;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Text/image-to-video wire format. An image sets its own ratio; OAuth is the only credential path. */
public final class GrokVideo {
    public static final String MODEL = "grok-imagine-video-1.5", BASE = "https://api.x.ai/v1/videos";
    public static final int DEFAULT_SECONDS = 8;
    private GrokVideo() {}
    public static String prompt(String text) {
        String value = text == null ? "" : text.strip();
        if (value.isEmpty()) return "Animate this image with subtle natural motion and a slow cinematic camera movement. Preserve its composition, subjects and style.";
        if (value.length() > 4000) throw new IllegalArgumentException("움직임 설명은 4,000자 이내로 적어 주세요.");
        return value;
    }
    public static int seconds(int seconds) {
        if (seconds < 1 || seconds > 15) throw new IllegalArgumentException("영상 길이는 1~15초로 선택해 주세요.");
        return seconds;
    }
    public static JSONObject request(String prompt, String image, int duration) {
        return request(prompt, image, duration, "720p");
    }
    public static String resolution(String value) {
        if (!java.util.Set.of("720p", "1080p").contains(value == null ? "" : value)) throw new IllegalArgumentException("화질은 720p 또는 1080p로 선택해 주세요.");
        return value;
    }
    public static String aspect(String value) {
        if (!java.util.Set.of("1:1", "16:9", "9:16", "4:3", "3:4", "3:2", "2:3").contains(value == null ? "" : value)) throw new IllegalArgumentException("영상 비율을 다시 선택해 주세요.");
        return value;
    }
    public static JSONObject textRequest(String text, int duration, String resolution, String aspect) {
        if (text == null || text.isBlank()) throw new IllegalArgumentException("만들 영상의 내용을 말씀하거나 입력해 주세요.");
        return obj("model", MODEL, "prompt", prompt(text), "duration", seconds(duration), "resolution", resolution(resolution), "aspect_ratio", aspect(aspect));
    }
    public static JSONObject request(String prompt, String image, int duration, String resolution) {
        if (image == null || !image.matches("data:image/(jpeg|png);base64,[A-Za-z0-9+/=]+") || image.length() > 20 * 1024 * 1024)
            throw new IllegalArgumentException("영상으로 만들 이미지 파일을 읽지 못했어요.");
        return obj("model", MODEL, "prompt", prompt(prompt), "image", obj("url", image), "duration", seconds(duration), "resolution", resolution(resolution));
    }
    public static String requestId(String id) {
        if (id == null || !id.matches("[A-Za-z0-9_-]{1,180}")) throw new IllegalArgumentException("Grok 영상 요청 번호를 확인하지 못했어요.");
        return id;
    }
    public static String downloadUrl(String value) {
        URI uri;
        try { uri = URI.create(value); } catch (Exception e) { throw new IllegalArgumentException("Grok 영상 주소를 확인하지 못했어요."); }
        String host = uri.getHost();
        if (!"https".equals(uri.getScheme()) || host == null || !(host.equals("x.ai") || host.endsWith(".x.ai"))
            || uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("Grok 공식 영상 다운로드 주소가 아니에요.");
        return value;
    }
    public static JSONObject result(JSONObject response) {
        String status = response.optString("status");
        if ("pending".equals(status)) return obj("state", "running");
        if ("failed".equals(status) || "expired".equals(status)) return obj("state", "failed", "error", "Grok에서 영상 생성을 완료하지 못했어요. 요청이 거절되었거나 만료됐어요.");
        if (!"done".equals(status)) throw new IllegalStateException("Grok 영상 상태 응답을 확인하지 못했어요.");
        JSONObject video = response.optJSONObject("video");
        if (video == null || !video.optBoolean("respect_moderation", false))
            return obj("state", "failed", "error", "Grok에서 이 영상의 제공을 허용하지 않았어요.");
        return obj("state", "downloading", "url", downloadUrl(video.optString("url")), "duration", video.optDouble("duration"));
    }
}
