package dev.jun.gyeot.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** xAI public device authorization, as used by the open-source Hermes integration. No client secret or API key. */
public final class GrokOAuth {
    public static final String CLIENT_ID = "b1a00492-073a-47ea-816f-4c329264a828";
    public static final String DISCOVERY = "https://auth.x.ai/.well-known/openid-configuration";
    public static final String DEVICE = "https://auth.x.ai/oauth2/device/code";
    public static final String SCOPE = "openid profile email offline_access grok-cli:access api:access";
    private GrokOAuth() {}
    public static String endpoint(String url) {
        URI uri;
        try { uri = URI.create(url); } catch (Exception e) { throw new IllegalArgumentException("Grok 로그인 주소가 올바르지 않아요."); }
        if (!"https".equals(uri.getScheme()) || !("auth.x.ai".equals(uri.getHost()) || "accounts.x.ai".equals(uri.getHost()))
            || uri.getUserInfo() != null || uri.getFragment() != null || (uri.getPort() != -1 && uri.getPort() != 443))
            throw new IllegalArgumentException("Grok 공식 로그인 주소를 확인하지 못했어요.");
        return url;
    }
    public static String form(String... pairs) {
        StringBuilder result = new StringBuilder();
        for (int i = 0; i < pairs.length; i += 2) {
            if (result.length() > 0) result.append('&');
            result.append(URLEncoder.encode(pairs[i], StandardCharsets.UTF_8)).append('=')
                .append(URLEncoder.encode(pairs[i + 1], StandardCharsets.UTF_8));
        }
        return result.toString();
    }
    public static String deviceForm() { return form("client_id", CLIENT_ID, "scope", SCOPE); }
    public static String pollForm(String code) { return form("grant_type", "urn:ietf:params:oauth:grant-type:device_code", "client_id", CLIENT_ID, "device_code", code); }
    public static String refreshForm(String token) { return form("grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token", token); }
    public static JSONObject device(JSONObject value, long now) {
        String code = value.optString("device_code"), user = value.optString("user_code");
        long seconds = value.optLong("expires_in"), interval = value.optLong("interval", 5);
        if (code.isBlank() || code.length() > 8192 || user.isBlank() || user.length() > 80 || seconds <= 0 || seconds > 3600 || interval <= 0 || interval > 120)
            throw new IllegalArgumentException("Grok 로그인 응답을 확인하지 못했어요.");
        String url = endpoint(value.optString("verification_uri"));
        String complete = value.optString("verification_uri_complete");
        return obj("deviceCode", code, "userCode", user, "url", complete.isBlank() ? url : endpoint(complete), "expiresAt", now + seconds * 1000, "interval", interval);
    }
    public static JSONObject tokens(JSONObject reply, JSONObject previous, String tokenEndpoint, long now) {
        String access = reply.optString("access_token"), refresh = reply.optString("refresh_token");
        if (refresh.isBlank() && previous != null) refresh = previous.optString("refreshToken");
        long seconds = reply.optLong("expires_in");
        if (access.isBlank() || refresh.isBlank() || access.length() > 65536 || refresh.length() > 65536 || access.chars().anyMatch(c -> c <= 32 || c == 127) || seconds <= 0 || seconds > 365L * 86400
            || !"Bearer".equalsIgnoreCase(reply.optString("token_type", "Bearer")))
            throw new IllegalArgumentException("Grok 로그인 토큰을 확인하지 못했어요. 다시 로그인해 주세요.");
        return obj("accessToken", access, "refreshToken", refresh, "expiresAt", now + seconds * 1000,
            "tokenEndpoint", endpoint(tokenEndpoint), "accountId", previous == null ? java.util.UUID.randomUUID().toString() : previous.optString("accountId"));
    }
    /** Never expose provider response bodies (which may contain credentials) in app logs. */
    public static String error(int status) {
        return switch (status) {
            case 401 -> "Grok 로그인이 만료됐어요. 설정 → Grok에서 다시 로그인해 주세요.";
            case 402, 403 -> "이 Grok 구독 계정에는 요청한 기능의 권한이 허용되지 않았어요. 구독 종류와 계정 권한을 확인해 주세요.";
            case 429 -> "Grok 사용 한도에 도달했어요. 잠시 후 다시 시도해 주세요.";
            default -> "Grok 요청을 마치지 못했어요 (HTTP " + status + ").";
        };
    }
}
