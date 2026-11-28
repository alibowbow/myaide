package dev.jun.gyeot.core;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.Set;

/**
 * Links to other apps on a web page (tel:, mailto:, sms:, market:, intent:// for KakaoTalk or Naver app sign-in, and other
 * app schemes), handed to Android the way Chrome does: only on the user's own tap on the page in front of them, never from an
 * AI step (that keeps the old refusal, so a page cannot make the AI open the phone's apps). The Android intent itself is made
 * by BrowserAppIntent (parseUri, CATEGORY_BROWSABLE, no component or selector component, no URI grants).
 */
public final class BrowserAppLinks {
    private BrowserAppLinks() {}
    public enum Verdict { NOT_APP, HANDOFF, REFUSE }
    /** Schemes that are the web or the browser itself, or reach files and app data: never handed to another app. */
    private static final Set<String> NEVER = Set.of("http", "https", "javascript", "data", "blob", "file", "content", "about", "resource",
        "chrome", "moz-extension", "view-source", "jar", "ws", "wss", "ftp", "android-app", "gyeot");

    /** The link's scheme in lower case, "" when it has none that is well formed. */
    public static String scheme(String raw) {
        if (raw == null) return "";
        int colon = raw.indexOf(':');
        if (colon <= 0 || colon > 40) return "";
        String scheme = raw.substring(0, colon).toLowerCase(Locale.ROOT);
        return scheme.matches("[a-z][a-z0-9+.-]*") ? scheme : "";
    }
    /** A link another app on the phone opens: a well-formed scheme that is not the web, the browser or a file. */
    public static boolean appLink(String raw) {
        String scheme = scheme(raw);
        return !scheme.isEmpty() && !NEVER.contains(scheme) && raw.length() <= 8192 && raw.chars().noneMatch(c -> c < 32 || c == 127);
    }
    /**
     * Whether to hand the link to Android. tapped: the page's load came from the user's gesture; inFront: the tab is the one on
     * screen; aiWorking: an AI task works in that tab (its click or key, or the page reacting to it), so it is refused.
     */
    public static Verdict decide(String raw, boolean tapped, boolean inFront, boolean aiWorking) {
        if (!appLink(raw)) return Verdict.NOT_APP;
        return tapped && inFront && !aiWorking ? Verdict.HANDOFF : Verdict.REFUSE;
    }
    /** What the app is, for the line the user sees: 전화, 메일, 문자, Play 스토어, or the scheme. */
    public static String label(String raw) {
        String scheme = scheme(raw);
        return switch (scheme) {
            case "tel" -> "전화"; case "mailto" -> "메일"; case "sms", "smsto", "mms" -> "문자"; case "market" -> "Play 스토어";
            case "geo" -> "지도"; case "intent" -> appOfIntent(raw);
            default -> scheme.isEmpty() ? "앱" : scheme + " 앱";
        };
    }
    private static String appOfIntent(String raw) {
        String pkg = field(raw, "package");
        if (pkg.startsWith("com.kakao.talk")) return "카카오톡";
        if (pkg.startsWith("com.nhn.android") || pkg.startsWith("com.naver")) return "네이버 앱";
        String scheme = field(raw, "scheme");
        return !pkg.isEmpty() ? pkg : scheme.isEmpty() ? "앱" : scheme + " 앱";
    }
    /** A field of an intent:// link's #Intent;…;end part ("package", "scheme", "S.browser_fallback_url"), decoded; "" when absent. */
    public static String field(String raw, String name) {
        if (raw == null) return "";
        int marker = raw.indexOf("#Intent;");
        if (marker < 0) return "";
        int end = raw.indexOf(";end", marker);
        String fields = raw.substring(marker + 8, end < 0 ? raw.length() : end);
        for (String part : fields.split(";")) {
            int eq = part.indexOf('=');
            if (eq > 0 && part.substring(0, eq).equals(name))
                try { return URLDecoder.decode(part.substring(eq + 1), StandardCharsets.UTF_8); } catch (IllegalArgumentException bad) { return ""; }
        }
        return "";
    }
    /** The page Chrome opens when no app takes an intent:// link: its S.browser_fallback_url, when it is an http(s) address; else "". */
    public static String fallback(String raw) {
        String url = field(raw, "S.browser_fallback_url");
        try { return url.isEmpty() ? "" : BrowserTools.url(url); } catch (IllegalArgumentException notWeb) { return ""; }
    }
}
