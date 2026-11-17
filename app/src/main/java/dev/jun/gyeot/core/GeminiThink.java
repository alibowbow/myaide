package dev.jun.gyeot.core;

import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Gemini Flash as the brain, on the user's own Gemini key. Free-tier limits differ by model and key and change over time,
 * so none is assumed: the smartest Flash goes first, and when a model's free daily quota runs out (a 429 naming a per-day
 * limit) it is set aside until the quota resets at midnight Pacific time and the next model answers. The limit Google
 * reports in that error is kept, so settings can show what this key really gets.
 */
public final class GeminiThink {
    /** Best first: the newest Flash, the stable 2.5 Flash, then the lighter Flash-Lite models with larger free quotas. */
    public static final String[] MODELS = {"gemini-flash-latest", "gemini-2.5-flash", "gemini-flash-lite-latest", "gemini-2.5-flash-lite"};
    /** Free daily quotas reset at midnight Pacific time. */
    private static final ZoneId QUOTA_ZONE = ZoneId.of("America/Los_Angeles");
    private static final Pattern LIMIT = Pattern.compile("(?i)limit:\\s*(\\d{1,9})");
    private GeminiThink() {}

    public static String url(String model) { return GeminiSearch.url(model); }
    public static String label(String model) {
        return switch (model) {
            case "gemini-flash-latest" -> "Gemini Flash 최신";
            case "gemini-2.5-flash" -> "Gemini 2.5 Flash";
            case "gemini-flash-lite-latest" -> "Gemini Flash-Lite 최신";
            case "gemini-2.5-flash-lite" -> "Gemini 2.5 Flash-Lite";
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
    /** The quota day for a moment: the Pacific-time date. */
    public static String day(Instant now) { return now.atZone(QUOTA_ZONE).toLocalDate().toString(); }

    /**
     * Per-model state kept by the app: {model: {day, uses, full, limit, missing}}. The order to try now: models not used up
     * today and not missing, best first; when every model is used up, the best one is tried anyway in case the quota reset.
     */
    public static List<String> order(JSONObject state, String today) {
        List<String> order = new ArrayList<>();
        for (String model : MODELS) {
            JSONObject entry = state == null ? null : state.optJSONObject(model);
            if (entry != null && entry.optBoolean("missing")) continue;
            if (entry != null && entry.optBoolean("full") && today.equals(entry.optString("day"))) continue;
            order.add(model);
        }
        if (order.isEmpty()) order.add(MODELS[0]);
        return order;
    }
    /** Counts one answered request for the model today. */
    public static void used(JSONObject state, String model, String today) {
        JSONObject entry = today(state, model, today);
        put(entry, "uses", entry.optInt("uses") + 1);
    }
    /** Marks the model's free daily quota used up today, with the limit Google named. */
    public static void full(JSONObject state, String model, String today, int limit) {
        JSONObject entry = today(state, model, today);
        put(entry, "full", true);
        if (limit > 0) put(entry, "limit", limit);
    }
    public static void missing(JSONObject state, String model) { JSONObject entry = state.optJSONObject(model); if (entry == null) { entry = new JSONObject(); put(state, model, entry); } put(entry, "missing", true); }
    private static JSONObject today(JSONObject state, String model, String today) {
        JSONObject entry = state.optJSONObject(model);
        if (entry == null || !today.equals(entry.optString("day"))) {
            int limit = entry == null ? 0 : entry.optInt("limit");
            entry = obj("day", today, "uses", 0, "full", false);
            if (limit > 0) put(entry, "limit", limit);
            put(state, model, entry);
        }
        return entry;
    }
    /** One line for settings: what answered today and which free quotas ran out, with the limits Google reported. */
    public static String summary(JSONObject state, String today) {
        List<String> parts = new ArrayList<>();
        for (String model : MODELS) {
            JSONObject entry = state == null ? null : state.optJSONObject(model);
            if (entry == null || entry.optBoolean("missing")) continue;
            boolean current = today.equals(entry.optString("day"));
            int uses = current ? entry.optInt("uses") : 0, limit = entry.optInt("limit");
            if (current && entry.optBoolean("full")) parts.add(label(model) + " 오늘 무료 한도 다 씀" + (limit > 0 ? " (하루 " + limit + "회)" : ""));
            else if (uses > 0) parts.add(label(model) + " 오늘 " + uses + "회" + (limit > 0 ? " / 하루 " + limit + "회" : ""));
        }
        return parts.isEmpty() ? "오늘은 아직 쓰지 않았어요." : String.join(" · ", parts);
    }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
