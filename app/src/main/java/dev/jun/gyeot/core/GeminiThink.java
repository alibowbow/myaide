package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Gemini Flash as the brain, on the user's own Gemini key: the smartest Flash goes first, and a model whose free quota
 * runs out is set aside (core/GeminiQuota keeps that for every Gemini call the app makes, this one included). Here: the
 * request, the answer, and reading Google's quota refusals.
 */
public final class GeminiThink {
    /** Best first: the newest Flash, the stable 2.5 Flash (20 a day each), then Flash-Lite with 500 a day (GeminiWork.FLASH_LITE). */
    public static final String[] MODELS = {"gemini-flash-latest", "gemini-2.5-flash", GeminiWork.FLASH_LITE[0], GeminiWork.FLASH_LITE[1], GeminiWork.FLASH_LITE[2]};
    private static final Pattern LIMIT = Pattern.compile("(?i)limit:\\s*(\\d{1,9})");
    private GeminiThink() {}

    public static String url(String model) { return GeminiSearch.url(model); }
    public static String label(String model) {
        return switch (model) {
            case "gemini-flash-latest" -> "Gemini Flash 최신";
            case "gemini-2.5-flash" -> "Gemini 2.5 Flash";
            case "gemini-flash-lite-latest" -> "Gemini Flash-Lite 최신";
            case "gemini-2.5-flash-lite" -> "Gemini 2.5 Flash-Lite";
            case "gemini-3.5-flash-lite" -> "Gemini 3.5 Flash-Lite";
            case "gemini-3.1-flash-lite" -> "Gemini 3.1 Flash-Lite";
            default -> model;
        };
    }
    public static JSONObject request(String instructions, String input) {
        return obj("systemInstruction", obj("parts", arr(obj("text", instructions))), "contents", arr(obj("role", "user", "parts", arr(obj("text", input)))));
    }
    /** The answer text (thought parts skipped); an empty answer says why. */
    public static String answer(JSONObject response) {
        JSONArray candidates = response.optJSONArray("candidates");
        JSONObject first = candidates == null ? null : candidates.optJSONObject(0), content = first == null ? null : first.optJSONObject("content");
        JSONArray parts = content == null ? null : content.optJSONArray("parts");
        StringBuilder text = new StringBuilder();
        for (int i = 0; parts != null && i < parts.length(); i++) { JSONObject part = parts.optJSONObject(i); if (part != null && !part.optBoolean("thought")) text.append(part.optString("text")); }
        if (text.toString().isBlank()) {
            String reason = first == null ? "" : first.optString("finishReason");
            JSONObject feedback = response.optJSONObject("promptFeedback");
            if (feedback != null && !feedback.optString("blockReason").isBlank()) reason = feedback.optString("blockReason");
            throw new IllegalStateException("Gemini가 답을 만들지 못했어요" + (reason.isBlank() ? "." : " (" + reason + ")."));
        }
        return text.toString().strip();
    }

    /** "day" when the free daily quota of this model is used up, "minute" for a per-minute limit, "" for anything else. */
    public static String quota(int status, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        if (status != 429 && !lower.contains("resource_exhausted") && !lower.contains("quota")) return "";
        if (lower.contains("perday") || lower.contains("per day") || lower.contains("per_day") || lower.contains("daily")) return "day";
        return "minute";
    }
    /** The request limit Google names in a quota error ("limit: 20"), or 0. */
    public static int limit(String message) {
        Matcher matcher = LIMIT.matcher(message == null ? "" : message);
        return matcher.find() ? Integer.parseInt(matcher.group(1)) : 0;
    }
    /** A model this key cannot use at all (unknown or retired name, no access): skip it for good. */
    public static boolean missing(int status, String message) {
        String lower = message == null ? "" : message.toLowerCase(Locale.ROOT);
        return status == 404 || (status == 400 && lower.contains("not found")) || lower.contains("is not supported");
    }
}
