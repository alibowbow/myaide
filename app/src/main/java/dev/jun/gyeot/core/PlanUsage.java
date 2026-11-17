package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * A subscription's own usage page, read on request: the windows
 * Codex shows in /status (the ChatGPT plan's 5-hour and weekly Codex limits) and the weekly pool Grok Build shows in
 * /usage. Neither address is a public API: they are the ones openai/codex and the Grok Build CLI call themselves, with
 * the same sign-in, so either may change. A body in another shape gives Limits.NONE, never a guessed number.
 */
public final class PlanUsage {
    /** openai/codex's backend client, ChatGPT path style: GET {base}/wham/usage with the Codex sign-in's token and account. */
    public static final String CODEX = "https://chatgpt.com/backend-api/wham/usage";
    /** The Grok Build CLI's /usage: GET with the auth.x.ai sign-in's token. */
    public static final String GROK = "https://cli-chat-proxy.grok.com/v1/billing?format=credits";
    /** Opening the page reads again only after this long; the button reads at once. */
    public static final long AUTO_MS = 5 * 60_000L;
    /** The route the Codex sign-in's windows are kept under in the usage ledger, beside the ChatGPT connection's own. */
    public static final String CODEX_ROUTE = "codex";
    private PlanUsage() {}

    public static Map<String, String> codexHeaders(String token, String account) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("chatgpt-account-id", account);
        headers.put("Accept", "application/json");
        headers.put("User-Agent", "codex-cli");
        return headers;
    }
    public static Map<String, String> grokHeaders(String token) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + token);
        headers.put("X-XAI-Token-Auth", "xai-grok-cli");
        headers.put("Accept", "application/json");
        return headers;
    }

    /**
     * Codex's usage body: rate_limit.primary_window and secondary_window ({used_percent, limit_window_seconds, reset_at in
     * Unix seconds, reset_after_seconds}), then additional_rate_limits[] ({limit_name, rate_limit}) named for their limit.
     */
    public static Limits codex(JSONObject body, long now) {
        if (body == null) return Limits.NONE;
        List<Limits.Window> found = new ArrayList<>();
        JSONObject rate = body.optJSONObject("rate_limit");
        if (rate != null) { codex(rate.optJSONObject("primary_window"), "", "짧은 한도", now, found); codex(rate.optJSONObject("secondary_window"), "", "긴 한도", now, found); }
        JSONArray more = body.optJSONArray("additional_rate_limits");
        for (int i = 0; more != null && i < more.length() && i < 4; i++) {
            JSONObject extra = more.optJSONObject(i); if (extra == null || extra.optJSONObject("rate_limit") == null) continue;
            String name = extra.optString("limit_name").replaceAll("[^A-Za-z0-9 ._-]", "").strip();
            if (name.length() > 30) name = name.substring(0, 30).strip();
            if (name.isEmpty()) continue;
            JSONObject limit = extra.optJSONObject("rate_limit");
            codex(limit.optJSONObject("primary_window"), name + " ", "한도", now, found);
            codex(limit.optJSONObject("secondary_window"), name + " ", "긴 한도", now, found);
        }
        return found.isEmpty() ? Limits.NONE : new Limits(found, now);
    }
    private static void codex(JSONObject window, String prefix, String fallback, long now, List<Limits.Window> found) {
        if (window == null || !window.has("used_percent")) return;
        double used = window.optDouble("used_percent", Double.NaN);
        if (!Double.isFinite(used)) return;
        long seconds = Math.max(0, window.optLong("limit_window_seconds")), at = window.optLong("reset_at"), after = window.optLong("reset_after_seconds", -1);
        long reset = at > 0 ? (at > 1_000_000_000_000L ? at : at * 1000) : after >= 0 && window.has("reset_after_seconds") ? now + after * 1000 : 0;
        // As Codex reads them: a window with nothing in it is not one.
        if (used == 0 && seconds == 0 && reset == 0) return;
        String name = seconds > 0 ? Limits.length(Math.round(seconds / 60.0)) : fallback;
        found.add(new Limits.Window(prefix + name, clamp(1 - used / 100), reset, false));
    }

    /**
     * Grok's billing body: config.creditUsagePercent (the share used of the whole pool; proto3 JSON leaves a 0 out), the
     * pool's period config.currentPeriod {type, end} (else config.billingPeriodEnd), and config.productUsage[] per product.
     * Without a percent and without a period it is not this shape: NONE.
     */
    public static Limits grok(JSONObject body, long now) {
        JSONObject config = body == null ? null : body.optJSONObject("config");
        if (config == null) return Limits.NONE;
        JSONObject period = config.optJSONObject("currentPeriod");
        long end = Limits.instant(period == null ? "" : period.optString("end"));
        if (end == 0) end = Limits.instant(config.optString("billingPeriodEnd"));
        boolean said = config.has("creditUsagePercent");
        if (!said && end == 0) return Limits.NONE;
        double used = config.optDouble("creditUsagePercent", 0);
        if (!Double.isFinite(used)) return Limits.NONE;
        String type = period == null ? "" : period.optString("type"), name = type.contains("WEEK") ? "주간" : type.contains("MONTH") ? "월간" : type.contains("DAY") ? "하루" : "사용량";
        List<Limits.Window> found = new ArrayList<>();
        found.add(new Limits.Window(name, clamp(1 - used / 100), end, false));
        JSONArray products = config.optJSONArray("productUsage");
        for (int i = 0; products != null && i < products.length() && i < 4; i++) {
            JSONObject product = products.optJSONObject(i);
            if (product == null || !product.has("usagePercent")) continue;
            double share = product.optDouble("usagePercent", Double.NaN);
            String label = product(product.optString("product"));
            if (!Double.isFinite(share) || label.isEmpty()) continue;
            found.add(new Limits.Window(label + " " + name, clamp(1 - share / 100), end, false));
        }
        return new Limits(found, now);
    }
    /** "GrokBuild" → "Build", "GrokChat" → "채팅", another product by its own name. */
    static String product(String value) {
        String name = value == null ? "" : value.replaceAll("[^A-Za-z0-9 ._-]", "").strip();
        if (name.equalsIgnoreCase("GrokChat")) return "채팅";
        if (name.startsWith("Grok") && name.length() > 4) name = name.substring(4);
        return name.length() > 20 ? name.substring(0, 20) : name;
    }
    /** What to show when a read failed: the status said in words. */
    public static String failure(int status) {
        return switch (status) {
            case 401, 403 -> "로그인이 이 사용량을 볼 수 없다고 했어요(HTTP " + status + ")";
            case 404 -> "사용량 주소가 바뀌었어요(HTTP 404)";
            case 429 -> "너무 자주 물어서 잠시 뒤에 다시 확인해요(HTTP 429)";
            default -> "사용량을 받지 못했어요(HTTP " + status + ")";
        };
    }
    private static double clamp(double value) { return Double.isNaN(value) ? 0 : Math.max(0, Math.min(1, value)); }
}
