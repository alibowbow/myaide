package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The phone-log lines (폰 조작 기록) for the voice model's tool calls and this app's replies, so the log shows what the
 * model chose and what it got back. Only short, non-secret arguments: a screen, an app, an action, a web
 * address's host, an enum. Never a request, typed text, a message, a time or a destination.
 */
public final class VoiceToolLog {
    private VoiceToolLog() {}
    /** Longest app name or error excerpt kept on a line. */
    private static final int SHORT = 24, ERROR = 60;
    private static final Set<String> ENUMS = Set.of("command", "panel", "screen");

    /** "→ phone_action open_app 크롬", "→ app_open_screen browser", "→ media play", "→ browser_task". */
    public static String call(String name, JSONObject args) {
        String tool = name == null || name.isBlank() ? "?" : name;
        JSONObject a = args == null ? new JSONObject() : args;
        StringBuilder line = new StringBuilder("→ ").append(tool);
        switch (tool) {
            case "app_open_screen" -> line.append(' ').append(AppNavigation.SCREENS.contains(a.optString("screen")) ? a.optString("screen") : "?");
            case "phone_action" -> {
                String action = a.optString("action");
                line.append(' ').append(PhoneTools.ACTIONS.contains(action) ? action : "?");
                if ("open_app".equals(action) || "open_url".equals(action) && !a.optString("app").isBlank()) line.append(' ').append(clip(a.optString("app"), SHORT));
                if ("open_url".equals(action)) line.append(' ').append(host(a.optString("url")));
                if ("wait".equals(action)) line.append(' ').append(Math.round(PhoneTools.waitMillis(a) / 1000.0)).append("초");
                if (!a.optString("element").isBlank()) line.append(" #").append(clip(a.optString("element"), 8));
            }
            case "phone_apps" -> { if (!a.optString("query").isBlank()) line.append(' ').append(clip(a.optString("query"), SHORT)); }
            case "browser_control" -> line.append(' ').append(clip(a.optString("action"), 12));
            case "torch" -> line.append(a.optBoolean("on") ? " on" : " off");
            case "device_alarm", "device_timer" -> { if (a.optBoolean("confirmed")) line.append(" confirmed"); }
            default -> {
                for (String key : ENUMS) if (a.has(key) && a.optString(key).matches("[a-z_]{1,20}")) { line.append(' ').append(a.optString(key)); break; }
            }
        }
        return line.toString().strip();
    }
    /** "← phone_screen ok · 카카오톡 · 요소 23개 · 312ms", "← phone_action automation_forbidden · 120ms", "· 조용히" for a silent reply. */
    public static String reply(String name, JSONObject result, long millis, String scheduling) {
        String tool = name == null || name.isBlank() ? "?" : name;
        JSONObject r = result == null ? new JSONObject() : result;
        StringBuilder line = new StringBuilder("← ").append(tool).append(' ').append(outcome(tool, r));
        String detail = detail(tool, r);
        if (!detail.isEmpty()) line.append(" · ").append(detail);
        if ("SILENT".equals(scheduling) || "silent".equals(r.optString("voice_feedback"))) line.append(" · 조용히");
        if (millis >= 0) line.append(" · ").append(millis).append("ms");
        return line.toString();
    }
    /** ok, or the reply's error_code, its outcome (needs_confirmation), or "error": with a short excerpt of its message only for a
     * tool this app built itself, whose sentences are its own. Another tool's error (search, the brain, a connector's server)
     * may carry an external server's words, so its line says only "error". */
    static String outcome(String tool, JSONObject r) {
        String code = r.optString("error_code");
        if (!code.isBlank() && code.matches("[A-Za-z0-9_.:-]{1,48}")) return code;
        boolean failed = r.has("ok") ? !r.optBoolean("ok") : r.has("error") && !r.optString("error").isBlank();
        // A system action's own outcome says more than ok: set, sent, needs_confirmation, no_app, blocked.
        String outcome = r.optString("outcome");
        if (!outcome.isBlank() && outcome.matches("[a-z_]{1,32}")) return outcome;
        if (!failed) return "ok";
        String error = r.optString("error").strip().replaceAll("\\s+", " ");
        return error.isEmpty() || !own(tool) ? "error" : "error " + clip(error, ERROR);
    }
    /** The tools this app built itself: phone_*, browser_*, app_*, the system actions, the phone-log clear and the screen look. */
    static boolean own(String tool) {
        return tool.startsWith("phone_") || tool.startsWith("browser_") || tool.startsWith("app_") || DeviceActions.NAMES.contains(tool)
            || PhoneLogActions.FUNCTION.equals(tool) || ScreenView.FUNCTION.equals(tool);
    }
    private static String detail(String tool, JSONObject r) {
        // A screen of this app opened for the call, whichever tool asked (app_open_screen, a misrouted browser_task, a picture).
        if (r.optBoolean("verified")) return "표시 확인";
        if (r.optBoolean("tap_to_open")) return "알림으로 열기";
        switch (tool) {
            case "phone_screen" -> {
                JSONArray elements = r.optJSONArray("elements");
                if (elements == null) return "";
                String app = r.optString("app_label", r.optString("app"));
                return (app.isBlank() ? "" : clip(app, SHORT) + " · ") + "요소 " + elements.length() + "개";
            }
            case "phone_apps" -> { JSONArray apps = r.optJSONArray("apps"); return apps == null ? "" : "앱 " + apps.length() + "개"; }
            case "phone_action" -> {
                StringBuilder out = new StringBuilder();
                if (r.optBoolean("reread")) out.append("다시 읽고 실행");
                JSONObject after = r.optJSONObject("screen_after");
                if (after != null && after.optBoolean("ok", after.optJSONArray("elements") != null)) {
                    if (out.length() > 0) out.append(" · ");
                    String app = after.optString("app_label", after.optString("app"));
                    out.append("뒤 화면 ").append(app.isBlank() ? "읽음" : clip(app, SHORT));
                }
                return out.toString();
            }
            case "browser_task", "browser_task_status" -> {
                if (r.optBoolean("finished")) return "끝남";
                if (r.optBoolean("duplicate_request")) return "같은 요청";
                if (r.optBoolean("accepted")) return "접수";
                return "";
            }
            default -> { return DeviceActions.NAMES.contains(tool) && r.optString("route").matches("[a-z_]{1,24}") ? r.optString("route") : ""; }
        }
    }
    private static final String[][] STAGES = {{"queue", "대기"}, {"read", "읽기"}, {"judge", "판단"}, {"act", "실행"}, {"observe", "확인"}, {"visual", "보완"}, {"total", "전체"}};
    /** "읽기 120 · 전체 135ms", "판단 12 · 실행 30 · 확인 420 · 전체 470ms" from PhoneService's stage times; "" without any. */
    public static String steps(JSONObject timing) {
        if (timing == null || timing.length() == 0) return "";
        StringBuilder out = new StringBuilder();
        for (String[] stage : STAGES) {
            if (!timing.has(stage[0])) continue;
            long ms = timing.optLong(stage[0]);
            if (ms <= 0 && !"total".equals(stage[0])) continue;
            if (out.length() > 0) out.append(" · ");
            out.append(stage[1]).append(' ').append(Math.max(0, ms));
        }
        return out.length() == 0 ? "" : out.append("ms").toString();
    }
    /** The host of a web address, or "?" when there is none; never its path or query. */
    static String host(String url) {
        String value = url == null ? "" : url.strip();
        if (value.isEmpty()) return "?";
        try {
            String host = new java.net.URI(value.matches("(?i)^[a-z][a-z0-9+.-]*://.*") ? value : "https://" + value).getHost();
            return host == null || host.isBlank() ? "?" : clip(host.toLowerCase(Locale.ROOT), 40);
        } catch (Exception invalid) { return "?"; }
    }
    private static String clip(String text, int limit) {
        String value = text == null ? "" : text.strip().replaceAll("[\\r\\n\\t]+", " ");
        if (value.codePointCount(0, value.length()) <= limit) return value;
        return value.substring(0, value.offsetByCodePoints(0, limit)) + "…";
    }
}
