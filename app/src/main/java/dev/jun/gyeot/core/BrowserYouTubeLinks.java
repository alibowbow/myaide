package dev.jun.gyeot.core;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Narrow URL policy. Never returns an Android intent or a page-supplied fallback. */
public final class BrowserYouTubeLinks {
    private BrowserYouTubeLinks() {}
    private static final Set<String> HOSTS = Set.of("youtube.com", "www.youtube.com", "m.youtube.com", "youtu.be");
    private static boolean clean(String value) {
        return value != null && !value.isBlank() && value.length() <= 4096
            && value.chars().noneMatch(c -> c <= 32 || c == 127 || c == '\\');
    }
    private static URI https(String raw) {
        if (!clean(raw)) return null;
        try {
            URI u = URI.create(raw);
            return "https".equalsIgnoreCase(u.getScheme()) && u.getHost() != null
                && HOSTS.contains(u.getHost().toLowerCase(Locale.ROOT)) && u.getRawUserInfo() == null
                && (u.getPort() == -1 || u.getPort() == 443) ? u : null;
        } catch (RuntimeException e) { return null; }
    }
    public static boolean origin(String raw) { return https(raw) != null; }
    private static boolean id(String s) { return s != null && s.matches("[A-Za-z0-9_-]{11}"); }
    private static boolean list(String s) { return s != null && s.matches("[A-Za-z0-9_-]{1,128}"); }
    /** Canonical public resource only; unknown query fields/fragments never reach another app. */
    public static String webUrl(String raw) {
        URI u = https(raw); if (u == null) return null;
        try {
            Map<String,String> q = new HashMap<>();
            if (u.getRawQuery() != null) for (String pair : u.getRawQuery().split("&", -1)) {
                String[] p = pair.split("=", 2); String k = URLDecoder.decode(p[0], StandardCharsets.UTF_8);
                if (Set.of("v", "list", "t").contains(k)) {
                    if (q.containsKey(k)) return null;
                    q.put(k, URLDecoder.decode(p.length == 2 ? p[1] : "", StandardCharsets.UTF_8));
                }
            }
            String path = u.getRawPath() == null ? "" : u.getRawPath(), video = null, out;
            if (u.getHost().equalsIgnoreCase("youtu.be")) { if (!path.matches("/[A-Za-z0-9_-]{11}")) return null; video = path.substring(1); }
            else if (path.equals("/watch")) { video = q.get("v"); if (!id(video)) return null; }
            else if (path.matches("/(shorts|live)/[A-Za-z0-9_-]{11}")) video = path.substring(path.lastIndexOf('/') + 1);
            if (video != null) out = "https://www.youtube.com/watch?v=" + video;
            else if (path.equals("/playlist") && list(q.get("list"))) return "https://www.youtube.com/playlist?list=" + q.get("list");
            else if (path.isEmpty() || path.equals("/")) return "https://www.youtube.com/";
            else if (path.matches("/channel/[A-Za-z0-9_-]{1,128}") || path.matches("/@[A-Za-z0-9._-]{1,100}")) return "https://www.youtube.com" + path;
            else return null; // Not login, redirect, arbitrary nested URL, or a write/action endpoint.
            if (list(q.get("list"))) out += "&list=" + q.get("list");
            String time = q.get("t"); if (time != null && time.matches("[0-9]{1,6}(s|m|h)?")) out += "&t=" + time;
            return out;
        } catch (RuntimeException e) { return null; }
    }
    /** Only a page's direct user gesture may offer a YouTube app handoff. HTTPS stays in-browser. */
    public static String appLink(String raw, String current, String trigger, boolean gesture, boolean redirect, boolean appDriven) {
        URI page = https(current), from = https(trigger);
        if (!gesture || redirect || appDriven || page == null || from == null
                || !page.getHost().equalsIgnoreCase(from.getHost()) || !clean(raw)) return null;
        if (raw.startsWith("vnd.youtube:")) return vnd(raw);
        if (!raw.startsWith("intent://")) return null;
        int marker = raw.indexOf("#Intent;");
        if (marker < 0 || !raw.endsWith(";end") || marker != raw.lastIndexOf("#Intent;")) return null;
        String scheme = null, pkg = null;
        Set<String> seen = new HashSet<>();
        for (String field : raw.substring(marker + 8, raw.length() - 4).split(";", -1)) {
            String[] p = field.split("=", 2); if (p.length != 2 || !seen.add(p[0])) return null;
            switch (p[0]) {
                case "scheme" -> scheme = p[1];
                case "package" -> pkg = p[1];
                case "action" -> { if (!p[1].equals("android.intent.action.VIEW")) return null; }
                case "category" -> { if (!p[1].equals("android.intent.category.BROWSABLE")) return null; }
                // This untrusted value is deliberately neither decoded nor loaded nor forwarded.
                case "S.browser_fallback_url" -> { }
                default -> { return null; } // Reject component/selector/flags and all other extras.
            }
        }
        if (!"com.google.android.youtube".equals(pkg)) return null;
        String tail = raw.substring("intent:".length(), marker);
        if ("https".equals(scheme)) return webUrl("https:" + tail);
        if ("vnd.youtube".equals(scheme)) return vnd("vnd.youtube:" + tail);
        return null;
    }
    private static String vnd(String raw) {
        String tail = raw.substring("vnd.youtube:".length());
        if (tail.startsWith("//")) tail = tail.substring(2);
        // Android's simple vnd.youtube:<video-id> and its URL-shaped counterpart.
        if (id(tail)) return "https://www.youtube.com/watch?v=" + tail;
        if (tail.matches("[A-Za-z0-9_-]{11}\\?.*")) return webUrl("https://youtu.be/" + tail);
        if (tail.startsWith("www.youtube.com/") || tail.startsWith("m.youtube.com/") || tail.startsWith("youtube.com/")) return webUrl("https://" + tail);
        return null;
    }
}
