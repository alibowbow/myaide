package dev.jun.gyeot.core;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Browser sign-in for MCP connectors (MCP authorization, 2025-06-18), as a public native client on the phone. The server's
 * protected-resource metadata (RFC 9728) names its authorization server; that server's metadata (RFC 8414) gives the
 * endpoints. The app registers itself (RFC 7591) with a loopback redirect (RFC 8252), signs in with an authorization code
 * and PKCE S256 (RFC 7636), and names the MCP server as the resource (RFC 8707). Runpod's hosted server accepts sign-ins
 * only on http://localhost or http://127.0.0.1, which is where OpenAiLoopback listens. Everything a server returns is
 * untrusted: it is checked here before it is used, and a server's error text is never shown.
 */
public final class McpOAuth {
    /** The loopback callback path, and the link on the finished page (it opens 설정 → 커넥터). */
    public static final String CALLBACK_PATH = "/callback", RETURN_LINK = "gyeot://openai-done?page=connectors";
    /** Runpod's hosted MCP server: Pods, Serverless endpoints, templates and volumes, after "Sign in with Runpod". */
    public static final String RUNPOD = "https://mcp.runpod.io/", RUNPOD_NAME = "Runpod";
    private static final Pattern METADATA = Pattern.compile("(?i)(?:^|[\\s,])resource_metadata\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");
    private static final Pattern SCOPE = Pattern.compile("(?i)(?:^|[\\s,])scope\\s*=\\s*(?:\"([^\"]*)\"|([^\\s,]+))");
    private McpOAuth() {}

    /** An https address to call: a host, no login part, no fragment. */
    public static String endpoint(String value) {
        try {
            URI uri = URI.create(value == null ? "" : value.strip());
            if ("https".equalsIgnoreCase(uri.getScheme()) && uri.getHost() != null && !uri.getHost().isBlank() && uri.getRawUserInfo() == null && uri.getRawFragment() == null)
                return uri.toString();
        } catch (Exception ignored) {}
        throw new IllegalStateException("커넥터의 로그인 주소가 올바른 https 주소가 아니에요.");
    }

    // ── Discovery ───────────────────────────────────────────────────────────

    /** The resource_metadata address in a 401's WWW-Authenticate header; "" when it names none or not an https one. */
    public static String challengeMetadata(String header) {
        String value = param(METADATA, header);
        if (value.isEmpty()) return "";
        try { return endpoint(value); } catch (RuntimeException e) { return ""; }
    }
    /** The scopes a 401 asks for; "" when it names none. */
    public static String challengeScope(String header) { return scopes(param(SCOPE, header)); }

    /** Where the protected-resource metadata may be: the address the 401 named, then RFC 9728's well-known addresses (path appended, then the root). */
    public static List<String> resourceMetadataUrls(String address, String challenged) {
        URI server = URI.create(endpoint(address));
        List<String> urls = new ArrayList<>();
        if (challenged != null && !challenged.isBlank()) add(urls, endpoint(challenged));
        String path = path(server);
        if (!path.isEmpty()) add(urls, origin(server) + "/.well-known/oauth-protected-resource" + path);
        add(urls, origin(server) + "/.well-known/oauth-protected-resource");
        return urls;
    }
    /**
     * From the protected-resource metadata (null when none could be read): the resource to name (the server's own, which must
     * be on the server's host; else the server's address), the authorization server (the first https one it names; else the
     * server's origin, as MCP 2025-03-26 servers expect) and the scopes to ask for (the 401's, else those the server lists).
     */
    public static JSONObject resource(JSONObject metadata, String address, String challengeScope) {
        URI server = URI.create(endpoint(address));
        String resource = origin(server) + path(server), issuer = origin(server), scope = scopes(challengeScope);
        if (metadata != null) {
            String named = metadata.optString("resource", "").strip();
            if (!named.isEmpty()) {
                URI declared = URI.create(endpoint(named));
                if (!declared.getHost().equalsIgnoreCase(server.getHost()) || port(declared) != port(server))
                    throw new IllegalStateException("커넥터 서버가 다른 주소의 로그인 정보를 알려 줬어요. 주소를 확인해 주세요.");
                resource = declared.toString();
            }
            JSONArray servers = metadata.optJSONArray("authorization_servers");
            if (servers != null && servers.length() > 0) {
                String chosen = "";
                for (int i = 0; i < servers.length() && chosen.isEmpty(); i++) {
                    try { chosen = endpoint(servers.optString(i)); } catch (RuntimeException ignored) {}
                }
                if (chosen.isEmpty()) throw new IllegalStateException("커넥터 서버가 알려 준 로그인 서버 주소가 https가 아니에요.");
                issuer = chosen;
            }
            if (scope.isEmpty()) {
                JSONArray listed = metadata.optJSONArray("scopes_supported");
                StringBuilder joined = new StringBuilder();
                if (listed != null) for (int i = 0; i < listed.length(); i++) joined.append(' ').append(listed.optString(i));
                scope = scopes(joined.toString());
            }
        }
        return obj("resource", resource, "issuer", issuer, "scope", scope);
    }
    /** RFC 8414 and OpenID addresses of an issuer's metadata, in the order MCP clients try them. */
    public static List<String> serverMetadataUrls(String issuer) {
        URI uri = URI.create(endpoint(issuer));
        String origin = origin(uri), path = path(uri);
        List<String> urls = new ArrayList<>();
        add(urls, origin + "/.well-known/oauth-authorization-server" + path);
        add(urls, origin + "/.well-known/openid-configuration" + path);
        if (!path.isEmpty()) add(urls, origin + path + "/.well-known/openid-configuration");
        return urls;
    }
    /** The authorization server's endpoints, checked: it names the issuer that was asked for, every endpoint is https, and it can do PKCE S256. */
    public static JSONObject server(JSONObject metadata, String issuer) {
        if (metadata == null || !same(metadata.optString("issuer"), issuer)) throw new IllegalStateException("커넥터의 로그인 서버 정보를 확인하지 못했어요.");
        JSONArray methods = metadata.optJSONArray("code_challenge_methods_supported");
        if (methods != null && !contains(methods, "S256")) throw new IllegalStateException("커넥터의 로그인 서버가 안전한 로그인 방식(PKCE S256)을 지원하지 않아요.");
        String register = metadata.optString("registration_endpoint", "").strip();
        return obj("issuer", metadata.optString("issuer").strip(), "authorize", endpoint(metadata.optString("authorization_endpoint")),
            "token", endpoint(metadata.optString("token_endpoint")), "register", register.isEmpty() ? "" : endpoint(register));
    }

    // ── Registration and sign-in ────────────────────────────────────────────

    /** The dynamic client registration request: a public client (no secret) with one loopback redirect. */
    public static JSONObject registration(String redirectUri, String clientName) {
        return obj("client_name", clientName, "redirect_uris", arr(redirectUri), "grant_types", arr("authorization_code", "refresh_token"),
            "response_types", arr("code"), "token_endpoint_auth_method", "none");
    }
    /** The registered client: {clientId, clientSecret, authMethod}. A secret, when the server insists on one, goes only to the token endpoint. */
    public static JSONObject client(JSONObject reply) {
        String id = reply == null ? "" : reply.optString("client_id"), secret = reply == null ? "" : reply.optString("client_secret");
        String method = reply == null ? "none" : reply.optString("token_endpoint_auth_method", secret.isEmpty() ? "none" : "client_secret_basic");
        boolean known = method.equals("none") || method.equals("client_secret_post") || method.equals("client_secret_basic");
        if (!printable(id, 512) || (!secret.isEmpty() && !printable(secret, 2048)) || !known || (!method.equals("none") && secret.isEmpty()))
            throw new IllegalStateException("커넥터 서버에 앱을 등록하지 못했어요. 다시 시도해 주세요.");
        return obj("clientId", id, "clientSecret", secret, "authMethod", method);
    }
    /** The sign-in page: an authorization code with PKCE S256 and state, the MCP server as the resource, and scopes when there are any. */
    public static String authorizeUrl(String endpoint, String clientId, String redirectUri, String state, String verifier, String resource, String scope) {
        Map<String, String> params = new LinkedHashMap<>();
        params.put("response_type", "code"); params.put("client_id", clientId); params.put("redirect_uri", redirectUri); params.put("state", state);
        params.put("code_challenge", OpenAiProtocol.challenge(verifier)); params.put("code_challenge_method", "S256"); params.put("resource", resource);
        if (scope != null && !scope.isBlank()) params.put("scope", scope);
        String base = endpoint(endpoint);
        return base + (base.contains("?") ? "&" : "?") + OpenAiProtocol.form(params);
    }
    /** The code in the loopback redirect (its state already checked by OpenAiLoopback); a reply naming another issuer is refused (RFC 9207). */
    public static String code(String target, String issuer) {
        int mark = target == null ? -1 : target.indexOf('?');
        if (mark < 0 || !target.substring(0, mark).equals(CALLBACK_PATH)) throw new IllegalArgumentException("커넥터 로그인 응답을 확인하지 못했어요. 다시 시도해 주세요.");
        Map<String, List<String>> query = OpenAiProtocol.query(target.substring(mark + 1));
        List<String> error = query.get("error"), codes = query.get("code"), from = query.get("iss");
        if (error != null && !error.isEmpty())
            throw new IllegalStateException("access_denied".equals(error.get(0)) ? "커넥터 로그인을 취소했어요." : "커넥터 로그인에 실패했어요 (" + error.get(0).replaceAll("[^a-z_]", "") + ").");
        if (from != null && (from.size() != 1 || !same(from.get(0), issuer))) throw new IllegalStateException("다른 로그인 서버의 응답이라 받지 않았어요. 다시 시도해 주세요.");
        if (codes == null || codes.size() != 1 || codes.get(0).isBlank() || codes.get(0).length() > 4096) throw new IllegalArgumentException("커넥터 로그인 코드를 받지 못했어요. 다시 시도해 주세요.");
        return codes.get(0);
    }
    /** The token request for the code: the verifier, the same redirect and resource, and the client. */
    public static String codeForm(JSONObject client, String code, String verifier, String redirectUri, String resource) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "authorization_code"); body.put("code", code); body.put("redirect_uri", redirectUri); body.put("code_verifier", verifier);
        body.put("client_id", client.optString("clientId")); body.put("resource", resource);
        if ("client_secret_post".equals(client.optString("authMethod"))) body.put("client_secret", client.optString("clientSecret"));
        return OpenAiProtocol.form(body);
    }
    /** The token request that renews a saved sign-in. */
    public static String refreshForm(JSONObject session) {
        Map<String, String> body = new LinkedHashMap<>();
        body.put("grant_type", "refresh_token"); body.put("refresh_token", session.optString("refreshToken")); body.put("client_id", session.optString("clientId"));
        if (!session.optString("resource").isBlank()) body.put("resource", session.optString("resource"));
        if ("client_secret_post".equals(session.optString("authMethod"))) body.put("client_secret", session.optString("clientSecret"));
        return OpenAiProtocol.form(body);
    }
    /** The Authorization header for client_secret_basic (RFC 6749 §2.3.1), or null for every other client. */
    public static String basic(JSONObject client) {
        if (!"client_secret_basic".equals(client.optString("authMethod"))) return null;
        String pair = URLEncoder.encode(client.optString("clientId"), StandardCharsets.UTF_8) + ":" + URLEncoder.encode(client.optString("clientSecret"), StandardCharsets.UTF_8);
        return "Basic " + Base64.getEncoder().encodeToString(pair.getBytes(StandardCharsets.UTF_8));
    }

    // ── Tokens ──────────────────────────────────────────────────────────────

    /**
     * A token answer merged into the saved sign-in: the access token, the refresh token (the previous one when a renewal sends
     * none) and when the access token ends (0 when the server did not say). Throws for anything that is not a usable bearer token.
     */
    public static JSONObject tokens(JSONObject reply, JSONObject previous, long now) {
        String access = reply == null ? "" : reply.optString("access_token"), refresh = reply == null ? "" : reply.optString("refresh_token");
        if (refresh.isBlank() && previous != null) refresh = previous.optString("refreshToken");
        if (!printable(access, 16384) || (!refresh.isEmpty() && !printable(refresh, 16384)) || !"bearer".equalsIgnoreCase(reply.optString("token_type", "Bearer")))
            throw new IllegalStateException("커넥터 로그인 토큰을 확인하지 못했어요. 다시 로그인해 주세요.");
        long seconds = reply.optLong("expires_in", 0);
        JSONObject result = previous == null ? obj() : copy(previous);
        put(result, "accessToken", access); put(result, "refreshToken", refresh);
        put(result, "expiresAt", seconds > 0 && seconds <= 366L * 86400 ? now + seconds * 1000 : 0L);
        return result;
    }
    /** The sign-in to keep for a connector: issuer, token endpoint, client, resource, scope and tokens. */
    public static JSONObject session(JSONObject server, JSONObject client, String resource, String scope, JSONObject tokens) {
        JSONObject result = copy(tokens);
        put(result, "issuer", server.optString("issuer")); put(result, "token", server.optString("token"));
        put(result, "clientId", client.optString("clientId")); put(result, "clientSecret", client.optString("clientSecret")); put(result, "authMethod", client.optString("authMethod"));
        put(result, "resource", resource); put(result, "scope", scope == null ? "" : scope);
        return result;
    }
    /** The access token is good for at least another minute, or the server never said when it ends. */
    public static boolean fresh(JSONObject session, long now) { long end = session == null ? 0 : session.optLong("expiresAt"); return end == 0 || end > now + 60_000; }
    /** The refresh token is no longer accepted: the user signs in again. */
    public static boolean revoked(int status, JSONObject body) { return (status == 400 || status == 401) && body != null && "invalid_grant".equals(body.optString("error")); }
    /** Korean text for a failed registration or token call. Only the OAuth error code (letters and _) is shown, never the server's text. */
    public static String failure(String name, int status, JSONObject body) {
        String error = body == null ? "" : body.optString("error").replaceAll("[^a-z_]", "");
        String description = body == null ? "" : body.optString("error_description").toLowerCase(Locale.ROOT);
        if (error.equals("invalid_redirect_uri") || description.contains("redirect_uri"))
            return "‘" + name + "’ 서버가 앱의 로그인 주소(http://127.0.0.1)를 받지 않아요. 인증 토큰(API 키)으로 연결해 주세요.";
        if (error.equals("invalid_grant")) return "‘" + name + "’ 로그인이 만료됐어요. 설정 → 커넥터에서 다시 로그인해 주세요.";
        return "‘" + name + "’ 로그인을 마치지 못했어요 (HTTP " + status + (error.isEmpty() ? "" : " · " + error) + "). 다시 시도해 주세요.";
    }

    // ── Helpers ─────────────────────────────────────────────────────────────

    private static String param(Pattern pattern, String header) {
        if (header == null || header.isBlank()) return "";
        Matcher match = pattern.matcher(header);
        if (!match.find()) return "";
        String value = match.group(1) != null ? match.group(1) : match.group(2);
        return value == null ? "" : value.strip();
    }
    /** Space-separated scope tokens (RFC 6749 §3.3) that are safe to send; anything else is dropped. */
    private static String scopes(String text) {
        StringBuilder out = new StringBuilder();
        if (text != null) for (String part : text.strip().split(" +")) {
            boolean safe = !part.isEmpty() && part.length() <= 200 && out.length() + part.length() < 1000;
            for (int i = 0; safe && i < part.length(); i++) { char c = part.charAt(i); safe = c > 32 && c < 127 && c != '"' && c != '\\'; }
            if (safe) out.append(out.length() == 0 ? "" : " ").append(part);
        }
        return out.toString();
    }
    /** Printable ASCII without spaces, 1..limit characters. */
    private static boolean printable(String value, int limit) {
        if (value == null || value.isEmpty() || value.length() > limit) return false;
        for (int i = 0; i < value.length(); i++) { char c = value.charAt(i); if (c <= 32 || c >= 127) return false; }
        return true;
    }
    private static boolean contains(JSONArray values, String wanted) { for (int i = 0; i < values.length(); i++) if (wanted.equals(values.optString(i))) return true; return false; }
    private static void add(List<String> list, String value) { if (!list.contains(value)) list.add(value); }
    private static String origin(URI uri) { return "https://" + uri.getRawAuthority(); }
    /** The path without trailing slashes ("" for the root). */
    private static String path(URI uri) { String value = uri.getRawPath() == null ? "" : uri.getRawPath(); while (value.endsWith("/")) value = value.substring(0, value.length() - 1); return value; }
    private static int port(URI uri) { return uri.getPort() < 0 ? 443 : uri.getPort(); }
    private static boolean same(String a, String b) { return a != null && b != null && !a.isBlank() && noSlash(a).equals(noSlash(b)); }
    private static String noSlash(String value) { String text = value.strip(); while (text.endsWith("/")) text = text.substring(0, text.length() - 1); return text; }
}
