package dev.jun.gyeot.core;

import java.util.List;
import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Explicit API processing speed, independent of model and reasoning effort. */
public final class GptSpeed {
    private GptSpeed() {}
    // Official Fast pricing/guide verified 2026-10-25; Terra model page 2026-10-27. No inferred snapshots or families.
    private static final List<String> DOCUMENTED = List.of("gpt-6.2-astra", "gpt-6.2-sol", "gpt-6-astra", "gpt-6.1-sol", "gpt-6.1-terra", "gpt-6-luna", "gpt-5.6-sol");
    public static String normalize(String speed) {
        if (speed == null || speed.isEmpty() || speed.equals("standard")) return "standard";
        if (speed.equals("fast")) return "fast";
        throw new IllegalArgumentException("지원하는 GPT 속도를 선택해 주세요.");
    }
    /** A submitted Fast choice is consent for one account epoch, not whichever account later dequeues it. */
    public static void requireConsent(JSONObject profile, String currentEpoch) {
        if (!WorkBook.CHATGPT.equals(profile.optString("engine")) || !"fast".equals(normalize(profile.optString("speed", "standard")))) return;
        String submittedEpoch = profile.optString("speedConsentEpoch");
        if (submittedEpoch.isEmpty() || currentEpoch == null || !submittedEpoch.equals(currentEpoch))
            throw new java.util.concurrent.CancellationException("GPT 연결이 바뀌어 저장된 Fast 요청을 멈췄어요. 속도를 다시 선택해 주세요.");
    }
    public static boolean supported(String mode, String model, JSONArray catalog) {
        // SIWC public Responses transport support is not established by Codex's own Fast-mode UI.
        if (!"key".equals(mode) || model == null || catalog == null) return false;
        for (int i = 0; i < catalog.length(); i++) {
            JSONObject item = catalog.optJSONObject(i);
            if (item == null || !model.equals(item.optString("id"))) continue;
            if (item.has("service_tiers")) {
                JSONArray tiers = item.optJSONArray("service_tiers");
                for (int t = 0; tiers != null && t < tiers.length(); t++) {
                    JSONObject entry = tiers.optJSONObject(t);
                    String tier = entry == null ? tiers.optString(t) : entry.optString("id");
                    if (tier.equals("fast") || tier.equals("priority")) return true;
                }
                return false;
            }
            return DOCUMENTED.contains(model);
        }
        return false;
    }
    /** Standard preserves the prior request exactly. Fast is never inferred or sent on another credential route. */
    public static JSONObject apply(JSONObject payload, String mode, String model, JSONArray catalog, String speed) {
        if (normalize(speed).equals("standard")) return payload;
        if (!supported(mode, model, catalog)) throw new IllegalArgumentException("이 연결과 모델에서 Fast 지원을 확인하지 못했어요. 기본 속도를 선택해 주세요.");
        try { payload.put("service_tier", "fast"); } catch (Exception e) { throw new IllegalStateException(e); }
        return payload;
    }
    public static boolean rejected(int status, JSONObject body) {
        if ((status != 400 && status != 422) || body == null) return false;
        JSONObject error = body.optJSONObject("error"); if (error == null) error = body;
        return "service_tier".equals(error.optString("param"))
            || error.optString("message").toLowerCase(Locale.ROOT).contains("service_tier");
    }
    /** Server-confirmed processing tier, not a speed guarantee or a model capability probe. */
    public static String receivedTier(String requested, JSONObject response) {
        if (!normalize(requested).equals("fast")) return "";
        String actual = response == null ? "" : response.optString("service_tier");
        return switch (actual) {
            case "fast", "priority" -> "Fast 처리 확인";
            case "default" -> "Fast를 요청했지만 서버에서 기본 속도로 처리했어요.";
            default -> "Fast를 요청했지만 실제 처리 속도 등급은 확인되지 않았어요.";
        };
    }
}
