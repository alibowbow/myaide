package dev.jun.gyeot.core;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * Codex OAuth image protocol, adapted from lidge-ai/ima2-gen's MIT-licensed chatgptLogin,
 * chatgptAuth and codexBackend. See legal/IMA2-GEN-LICENSE.txt. Separate from the dynamic
 * Sign in with ChatGPT client: its resource-scoped tokens cannot call this backend.
 */
public final class CodexImages {
    public static final String CLIENT_ID = "app_EMoamEEZ73f0CkXaXp7hrann";
    public static final String TOKEN = "https://auth.openai.com/oauth/token";
    public static final String BASE = "https://chatgpt.com/backend-api/codex";
    public static final String REDIRECT = "http://localhost:1455/auth/callback";
    public static final String SCOPE = "openid profile email offline_access api.connectors.read api.connectors.invoke";
    private static final String AUTH = "https://api.openai.com/auth";
    private CodexImages() {}

    public static String authorize(String state, String nonce, String verifier) {
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code"); query.put("client_id", CLIENT_ID); query.put("redirect_uri", REDIRECT);
        query.put("scope", SCOPE); query.put("code_challenge", OpenAiProtocol.challenge(verifier)); query.put("code_challenge_method", "S256");
        query.put("id_token_add_organizations", "true"); query.put("codex_cli_simplified_flow", "true");
        query.put("state", state); query.put("nonce", nonce); query.put("originator", "codex_cli_rs");
        return "https://auth.openai.com/oauth/authorize?" + OpenAiProtocol.form(query);
    }
    public static String exchange(String code, String verifier) {
        Map<String, String> form = new LinkedHashMap<>();
        form.put("grant_type", "authorization_code"); form.put("client_id", CLIENT_ID); form.put("code", code);
        form.put("code_verifier", verifier); form.put("redirect_uri", REDIRECT);
        return OpenAiProtocol.form(form);
    }
    public static String refresh(String token) {
        return OpenAiProtocol.form(Map.of("grant_type", "refresh_token", "client_id", CLIENT_ID, "refresh_token", token));
    }
    private static JSONObject claims(String token) {
        try {
            String[] parts = token.split("\\.");
            return parts.length == 3 ? new JSONObject(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8)) : obj();
        } catch (Exception e) { return obj(); }
    }
    private static String accountId(JSONObject claims) {
        JSONObject auth = claims.optJSONObject(AUTH);
        String id = claims.optString("chatgpt_account_id", auth == null ? "" : auth.optString("chatgpt_account_id"));
        JSONArray organizations = claims.optJSONArray("organizations");
        if (id.isBlank() && organizations != null && organizations.optJSONObject(0) != null) id = organizations.optJSONObject(0).optString("id");
        return id;
    }
    /** Only accepts tokens delivered by the TLS token endpoint; checks OIDC claims before saving identity. */
    public static JSONObject tokens(JSONObject reply, JSONObject previous, String nonce, long now) {
        String access = reply.optString("access_token"), refresh = reply.optString("refresh_token", previous == null ? "" : previous.optString("refreshToken"));
        if (access.isBlank() || access.matches("(?s).*\\s.*") || refresh.isBlank()
                || !reply.optString("token_type", "Bearer").equalsIgnoreCase("bearer"))
            throw new IllegalStateException("Codex 로그인 정보가 완전하지 않아요. 다시 로그인해 주세요.");
        String id = reply.optString("id_token");
        JSONObject identity = !id.isBlank() ? OpenAiProtocol.identity(id, CLIENT_ID, nonce, now / 1000) : previous;
        if (identity == null || (previous != null && !previous.optString("sub").equals(identity.optString("sub"))))
            throw new IllegalStateException("Codex 계정이 바뀌었어요. 다시 로그인해 주세요.");
        JSONObject idClaims = claims(id), accessClaims = claims(access);
        String account = accountId(idClaims);
        if (account.isBlank()) account = accountId(accessClaims);
        if (account.isBlank() && previous != null) account = previous.optString("accountId");
        if (!account.matches("[A-Za-z0-9_-]{1,200}") || (previous != null && !account.equals(previous.optString("accountId"))))
            throw new IllegalStateException("Codex 계정 정보를 확인하지 못했어요. 다시 로그인해 주세요.");
        long seconds = reply.optLong("expires_in");
        long expires = seconds > 0 && seconds <= 31_536_000 ? now + seconds * 1000 : accessClaims.optLong("exp") * 1000;
        if (expires <= now) throw new IllegalStateException("Codex 로그인 정보가 만료됐어요. 다시 로그인해 주세요.");
        return obj("accessToken", access, "refreshToken", refresh, "expiresAt", expires, "accountId", account,
            "sub", identity.optString("sub"), "email", identity.optString("email"), "name", identity.optString("name"));
    }

    /** Codex edits take JSON data URLs, not the public Images API's multipart form. */
    public static JSONObject request(JSONObject args, JSONArray sources) {
        JSONObject body = Drawing.openAiRequest(args);
        if (args.optBoolean("edit")) {
            JSONArray images = arr();
            for (int i = 0; sources != null && i < sources.length() && images.length() < 3; i++) {
                JSONObject source = sources.optJSONObject(i);
                if (source == null || source.optString("data").isBlank()) continue;
                String mime = source.optString("mime", "image/png");
                if (!mime.matches("image/(png|jpeg|webp)")) throw new IllegalArgumentException("편집할 사진 형식을 확인해 주세요.");
                images.put(obj("image_url", "data:" + mime + ";base64," + source.optString("data")));
            }
            if (images.length() == 0) throw new IllegalArgumentException("고칠 그림을 먼저 첨부해 주세요.");
            try { body.put("images", images); } catch (Exception e) { throw new IllegalStateException(e); }
        }
        return body;
    }
    public interface Session {
        JSONObject get() throws Exception;
        /** Compare before refresh so a different in-flight request's rotated token wins. */
        JSONObject refresh(String rejectedToken) throws Exception;
    }
    public interface Transport { Reply post(String url, Map<String, String> headers, byte[] body) throws Exception; }
    public static final class Reply {
        public final int status; public final JSONObject json;
        public Reply(int status, JSONObject json) { this.status = status; this.json = json; }
    }
    /** One render; only a rejected token is retried, once. Never falls back to an API key or another provider. */
    public static JSONObject draw(Session sessions, Transport transport, JSONObject args, JSONArray sources, Runnable guard) throws Exception {
        byte[] body = request(args, sources).toString().getBytes(StandardCharsets.UTF_8);
        String url = BASE + (args.optBoolean("edit") ? "/images/edits" : "/images/generations");
        guard.run(); JSONObject session = sessions.get();
        for (int attempt = 0; attempt < 2; attempt++) {
            guard.run();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + session.getString("accessToken"));
            headers.put("chatgpt-account-id", session.getString("accountId"));
            headers.put("Content-Type", "application/json"); headers.put("Accept", "application/json");
            Reply reply = transport.post(url, headers, body); guard.run();
            if (reply.status == 401 && attempt == 0) { session = sessions.refresh(session.getString("accessToken")); continue; }
            if (reply.status == 401) throw new IllegalStateException("이미지용 Codex 로그인이 만료됐어요. 설정 → ChatGPT에서 Codex로 다시 로그인해 주세요.");
            if (reply.status == 403) throw new IllegalStateException("이 Codex 계정은 이미지 생성을 사용할 수 없어요. 계정·플랜 권한을 확인해 주세요.");
            if (reply.status >= 300) throw new IllegalStateException(OpenAiProtocol.explain(reply.status, OpenAiProtocol.failure(reply.json, "")));
            JSONObject picture = Drawing.openAiPicture(reply.json);
            if (picture == null) throw new IllegalStateException("Codex가 그림을 보내지 않았어요. 다시 요청해 주세요.");
            picture.put("model", Drawing.OPENAI_MODEL); return picture;
        }
        throw new IllegalStateException("Codex 이미지 생성에 실패했어요.");
    }

    /**
     * One request with a body the caller built (GPT Image 2.6 GIF or SVG), to path "/images/generations" or "/images/edits".
     * Only a rejected token is refreshed and retried, once; any other status comes back for the caller to read.
     */
    public static Reply send(Session sessions, Transport transport, String path, JSONObject body, Runnable guard) throws Exception {
        if (!path.equals("/images/generations") && !path.equals("/images/edits")) throw new IllegalArgumentException("Codex 이미지 경로가 올바르지 않아요.");
        byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
        guard.run(); JSONObject session = sessions.get();
        for (int attempt = 0; attempt < 2; attempt++) {
            guard.run();
            Map<String, String> headers = new LinkedHashMap<>();
            headers.put("Authorization", "Bearer " + session.getString("accessToken"));
            headers.put("chatgpt-account-id", session.getString("accountId"));
            headers.put("Content-Type", "application/json"); headers.put("Accept", "application/json");
            Reply reply = transport.post(BASE + path, headers, bytes); guard.run();
            if (reply.status == 401 && attempt == 0) { session = sessions.refresh(session.getString("accessToken")); continue; }
            if (reply.status == 401) throw new IllegalStateException("이미지용 Codex 로그인이 만료됐어요. 설정 → ChatGPT에서 Codex로 다시 로그인해 주세요.");
            return reply;
        }
        throw new IllegalStateException("Codex 이미지 요청에 실패했어요.");
    }
}
