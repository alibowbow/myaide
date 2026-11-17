package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.ArrayList;
import java.util.List;
import java.net.URI;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Finds a newer test build among the repository's GitHub releases (published by .github/workflows/android.yml). */
public final class UpdateCheck {
    public static final String REPO = "alibowbow/myaide";
    public static final String RELEASES_API = "https://api.github.com/repos/" + REPO + "/releases?per_page=20";
    public static final String RELEASES_PAGE = "https://github.com/" + REPO + "/releases";
    public static final long MAX_APK_BYTES = 512L * 1024 * 1024;
    private static final Pattern ASSET = Pattern.compile("gyeot-(\\d+(?:\\.\\d+){1,3})-debug\\.apk");
    /** The workflow writes the APK's digest into the release notes. */
    private static final Pattern SHA256 = Pattern.compile("SHA-256\\s*:?\\s*([0-9a-fA-F]{64})");
    private UpdateCheck() {}

    public static final class Update {
        /** url: public download link; apiUrl: the same asset through the API, which a token can read in a private repository. */
        public final String version, url, apiUrl, sha256, page;
        public final long size;
        public final List<Delta> deltas;
        Update(String version, String url, String apiUrl, String sha256, String page, long size, List<Delta> deltas) {
            this.version = version; this.url = url; this.apiUrl = apiUrl; this.sha256 = sha256; this.page = page; this.size = size; this.deltas = List.copyOf(deltas);
        }
    }
    /** Asset name pins the exact installed base; GitHub's digest pins the patch bytes. */
    public static final class Delta {
        public final String baseSha256, sha256, url, apiUrl;
        public final long size;
        Delta(String base, String sha, String url, String api, long size) {
            this.baseSha256 = base; this.sha256 = sha; this.url = url; this.apiUrl = api; this.size = size;
        }
    }
    private static List<Delta> deltas(JSONArray assets, String version, long apkSize) {
        List<Delta> result = new ArrayList<>();
        Pattern name = Pattern.compile("gyeot-" + Pattern.quote(version) + "-arm64-from-([0-9a-f]{64})\\.gdelta");
        for (int i = 0; i < assets.length() && result.size() < 2; i++) {
            JSONObject asset = assets.optJSONObject(i); if (asset == null) continue;
            Matcher match = name.matcher(asset.optString("name"));
            String digest = asset.optString("digest"), url = asset.optString("browser_download_url"), api = asset.optString("url");
            long size = asset.optLong("size", -1);
            if (!match.matches() || !digest.matches("sha256:[0-9a-f]{64}") || size <= 0 || size >= apkSize * 9 / 10) continue;
            if (!safeAssetUrl(url, false)) continue;
            if (!safeAssetUrl(api, true)) api = "";
            result.add(new Delta(match.group(1), digest.substring(7), url, api, size));
        }
        return result;
    }
    public static boolean safeAssetUrl(String url, boolean api) {
        try {
            URI uri = URI.create(url);
            String prefix = api ? "/repos/" + REPO + "/releases/assets/" : "/" + REPO + "/releases/download/";
            return "https".equals(uri.getScheme()) && (api ? "api.github.com" : "github.com").equals(uri.getHost())
                && uri.getUserInfo() == null && uri.getPort() == -1 && uri.getRawPath().startsWith(prefix)
                && !uri.getRawPath().contains("..") && !uri.getRawPath().contains("%") && uri.getFragment() == null;
        } catch (RuntimeException bad) { return false; }
    }
    /** The newest published release whose APK version is above current; null when already up to date. Drafts are ignored. */
    /** The verified file a finished download leaves: gyeot-VERSION.apk (AppUpdater). */
    public static String readyName(String version) { return "gyeot-" + version + ".apk"; }
    /**
     * Whether a downloaded file is still the one to install after a check: only while it is the
     * newest release's own. With nothing newer (null) it is already installed; with another version it is older.
     */
    public static boolean readyStillCurrent(String fileName, Update available) {
        return fileName != null && available != null && fileName.equals(readyName(available.version));
    }
    /** The notice a check leaves: a manual check says what it found; a quiet one only clears a "최신 버전이에요" a newer release made wrong. */
    public static String noticeAfter(boolean quiet, String before, Update available, String upToDate) {
        if (!quiet) return available == null ? upToDate : "";
        return available != null && before != null && before.startsWith("최신 버전이에요") ? "" : before;
    }
    public static Update newest(JSONArray releases, String current) {
        Update best = null;
        for (int i = 0; i < releases.length(); i++) {
            JSONObject release = releases.optJSONObject(i);
            if (release == null || release.optBoolean("draft")) continue;
            JSONArray assets = release.optJSONArray("assets");
            if (assets == null) continue;
            Matcher digest = SHA256.matcher(release.optString("body"));
            String sha = digest.find() ? digest.group(1).toLowerCase(Locale.ROOT) : "";
            for (int a = 0; a < assets.length(); a++) {
                JSONObject asset = assets.optJSONObject(a);
                if (asset == null) continue;
                Matcher name = ASSET.matcher(asset.optString("name"));
                String url = asset.optString("browser_download_url"), apiUrl = asset.optString("url");
                if (!safeAssetUrl(apiUrl, true)) apiUrl = "";
                long size = asset.optLong("size", -1);
                if (!name.matches() || !safeAssetUrl(url, false) || size <= 0 || size > MAX_APK_BYTES) continue;
                String version = name.group(1);
                if (compare(version, current) > 0 && (best == null || compare(version, best.version) > 0))
                    best = new Update(version, url, apiUrl, sha, release.optString("html_url", RELEASES_PAGE), size, deltas(assets, version, size));
            }
        }
        return best;
    }
    /** Numeric dotted-version comparison: 0.10.0 is newer than 0.9.9; missing parts count as 0. */
    public static int compare(String a, String b) {
        String[] left = a.split("\\."), right = b.split("\\.");
        for (int i = 0; i < Math.max(left.length, right.length); i++) {
            long x = i < left.length ? number(left[i]) : 0, y = i < right.length ? number(right[i]) : 0;
            if (x != y) return Long.compare(x, y);
        }
        return 0;
    }
    private static long number(String part) {
        String digits = part.replaceAll("^(\\d+).*", "$1");
        try { return Long.parseLong(digits); } catch (NumberFormatException e) { return 0; }
    }
    /** Release downloads redirect from github.com or the API to GitHub's asset hosts; nothing else is followed. */
    public static boolean allowedHost(String host) {
        if (host == null) return false;
        String lower = host.toLowerCase(Locale.ROOT);
        return lower.equals("github.com") || lower.equals("api.github.com") || lower.equals("objects.githubusercontent.com") || lower.equals("release-assets.githubusercontent.com");
    }
    /** A repository token is only ever sent to the API host, never along a redirect to the asset storage. */
    public static boolean sendsToken(String host) { return "api.github.com".equalsIgnoreCase(host); }
}

