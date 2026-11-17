package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.McpOAuth;
import dev.jun.gyeot.core.Net;
import dev.jun.gyeot.core.OpenAiLoopback;
import dev.jun.gyeot.core.OpenAiProtocol;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.json.JSONObject;

/**
 * Browser sign-in (OAuth) for MCP connectors such as Runpod's, and the access token a signed-in
 * connector sends: renewed a minute before it ends, or once when the server refuses it. The sign-in runs here rather than in
 * a screen, so it finishes even when the settings screen is drawn again while the browser is open. The session stays
 * encrypted in SecretStore as connector.<id>.oauth (endpoints, client id, tokens); nothing here logs or shows a token or a
 * server's error text.
 */
final class ConnectorAuth {
    interface Done { void signedIn(JSONObject session); }
    interface Failed { void failed(String reason); }
    private static final long LOGIN_TIMEOUT_MS = 10 * 60_000L;
    private static final int TIMEOUT_MS = 20_000;
    private static final String FORM = "application/x-www-form-urlencoded";
    private static final String EXPIRED = "커넥터 로그인이 만료됐어요. 설정 → 커넥터에서 그 커넥터를 눌러 ‘다시 로그인’해 주세요.";
    private static final Object state = new Object();
    private static final Map<String, Object> locks = new ConcurrentHashMap<>();
    private static final Handler main = new Handler(Looper.getMainLooper());
    private static volatile Thread login;
    private static volatile OpenAiLoopback loopback;
    private ConnectorAuth() {}

    // ── Tokens for requests ─────────────────────────────────────────────────

    /** What McpClient sends for a connector: its browser sign-in's access token (renewed as needed), else the token pasted when it was added. */
    static McpClient.Bearer bearer(AppStore store, String id) throws Exception {
        if (!signedIn(store, id)) return McpClient.fixed(store.connectorSecret(id, "token"));
        return rejected -> access(store, id, rejected);
    }
    /** The connector was added by browser sign-in: its session is saved on this device. */
    static boolean signedIn(AppStore store, String id) { return store.secrets.has("connector." + id + ".oauth"); }
    /** Forgets a connector's sign-in on this device (it is removed, or was added again with a token). */
    static void forget(AppStore store, String id) throws Exception { synchronized (lock(id)) { store.connectorSecret(id, "oauth", ""); } }
    private static Object lock(String id) { return locks.computeIfAbsent(id, key -> new Object()); }

    private static String access(AppStore store, String id, String rejected) throws Exception {
        synchronized (lock(id)) {
            String saved = store.connectorSecret(id, "oauth");
            if (saved.isBlank()) throw new IllegalStateException(EXPIRED);
            JSONObject session = new JSONObject(saved);
            String current = session.optString("accessToken");
            // Another request may have renewed it already; a refused token is renewed once.
            if (rejected == null ? McpOAuth.fresh(session, System.currentTimeMillis()) : !rejected.equals(current)) return current;
            if (session.optString("refreshToken").isBlank()) throw new IllegalStateException(EXPIRED);
            OpenAiAccount.Http.Response reply = post(session.optString("token"), FORM, McpOAuth.refreshForm(session), McpOAuth.basic(session));
            if (McpOAuth.revoked(reply.status, reply.json())) throw new IllegalStateException(EXPIRED);
            if (reply.status >= 300) throw new IllegalStateException(McpOAuth.failure("커넥터", reply.status, reply.json()));
            JSONObject next = McpOAuth.tokens(reply.json(), session, System.currentTimeMillis());
            store.connectorSecret(id, "oauth", next.toString());
            return next.getString("accessToken");
        }
    }

    // ── Sign-in ─────────────────────────────────────────────────────────────

    /**
     * Signs in to the MCP server at address in the browser: finds where it signs in (challenge is the WWW-Authenticate header
     * of its 401, or ""), registers this app with a loopback redirect, opens the sign-in page and waits for the answer (ten
     * minutes at most). done gets the session to save; failed gets a reason to show. Both run off the main thread. A new
     * sign-in cancels one still waiting.
     */
    static void signIn(Context context, String name, String address, String challenge, Done done, Failed failed) {
        Context app = context.getApplicationContext();
        Thread attempt;
        synchronized (state) {
            cancel();
            attempt = new Thread(() -> run(app, name, address, challenge, done, failed), "gyeot-connector-signin");
            attempt.setDaemon(true); login = attempt;
        }
        attempt.start();
    }
    static void cancel() {
        synchronized (state) {
            OpenAiLoopback server = loopback; if (server != null) server.close();
            if (login != null) login.interrupt();
            login = null; loopback = null;
        }
    }
    private static void run(Context app, String name, String address, String challenge, Done done, Failed failed) {
        Thread self = Thread.currentThread();
        try {
            JSONObject described = null;
            for (String url : McpOAuth.resourceMetadataUrls(address, McpOAuth.challengeMetadata(challenge))) { described = json(url); if (described != null) break; }
            JSONObject target = McpOAuth.resource(described, address, McpOAuth.challengeScope(challenge));
            String issuer = target.getString("issuer"), resource = target.getString("resource"), scope = target.optString("scope");
            JSONObject metadata = null;
            for (String url : McpOAuth.serverMetadataUrls(issuer)) { metadata = json(url); if (metadata != null) break; }
            if (metadata == null) throw new IllegalStateException("‘" + name + "’ 서버의 로그인 정보를 찾지 못했어요. 이 서버는 인증 토큰으로 연결해 주세요.");
            JSONObject server = McpOAuth.server(metadata, issuer);
            if (server.optString("register").isEmpty()) throw new IllegalStateException("‘" + name + "’ 서버는 앱 등록(동적 클라이언트 등록)을 받지 않아요. 인증 토큰(API 키)으로 연결해 주세요.");
            try (OpenAiLoopback redirect = new OpenAiLoopback(0, "127.0.0.1", McpOAuth.CALLBACK_PATH, name, McpOAuth.RETURN_LINK)) {
                synchronized (state) { if (login != self) return; loopback = redirect; }
                OpenAiAccount.Http.Response registered = post(server.getString("register"), "application/json", McpOAuth.registration(redirect.redirectUri, Brand.NAME).toString(), null);
                if (registered.status >= 300) throw new IllegalStateException(McpOAuth.failure(name, registered.status, registered.json()));
                JSONObject client = McpOAuth.client(registered.json());
                String stateValue = OpenAiProtocol.randomValue(), verifier = OpenAiProtocol.randomValue();
                String page = McpOAuth.authorizeUrl(server.getString("authorize"), client.getString("clientId"), redirect.redirectUri, stateValue, verifier, resource, scope);
                main.post(() -> { if (login == self) open(app, page, failed); });
                String code = McpOAuth.code(redirect.await(stateValue, LOGIN_TIMEOUT_MS), server.getString("issuer"));
                OpenAiAccount.Http.Response token = post(server.getString("token"), FORM, McpOAuth.codeForm(client, code, verifier, redirect.redirectUri, resource), McpOAuth.basic(client));
                if (token.status >= 300) throw new IllegalStateException(McpOAuth.failure(name, token.status, token.json()));
                JSONObject session = McpOAuth.session(server, client, resource, scope, McpOAuth.tokens(token.json(), null, System.currentTimeMillis()));
                if (login != self) return;
                done.signedIn(session);
            }
        } catch (Exception error) {
            if (login == self) {
                String reason = error instanceof IllegalStateException || error instanceof IllegalArgumentException ? error.getMessage() : Net.explain(error);
                failed.failed(reason == null || reason.isBlank() ? "‘" + name + "’ 로그인을 마치지 못했어요. 다시 시도해 주세요." : reason);
            }
        } finally {
            synchronized (state) { if (login == self) { login = null; loopback = null; } }
        }
    }
    private static void open(Context app, String url, Failed failed) {
        try { app.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (Exception e) { cancel(); failed.failed("브라우저를 열지 못했어요. 기본 브라우저 설정을 확인해 주세요."); }
    }

    // ── HTTPS (no redirects, bounded bodies; see OpenAiAccount.Http) ────────

    /** A JSON document, or null when the address has none (any status but 200, or not JSON). */
    private static JSONObject json(String url) throws Exception {
        OpenAiAccount.Http.Response reply = OpenAiAccount.Http.send("GET", McpOAuth.endpoint(url), null, null, null, TIMEOUT_MS);
        if (reply.status != 200) return null;
        try { return new JSONObject(reply.body); } catch (Exception notJson) { return null; }
    }
    private static OpenAiAccount.Http.Response post(String url, String type, String body, String authorization) throws Exception {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Accept", "application/json"); headers.put("Content-Type", type);
        if (authorization != null) headers.put("Authorization", authorization);
        try (OpenAiAccount.Http.Exchange exchange = OpenAiAccount.Http.open("POST", McpOAuth.endpoint(url), headers, body.getBytes(StandardCharsets.UTF_8), TIMEOUT_MS, null)) {
            return new OpenAiAccount.Http.Response(exchange.status, OpenAiAccount.Http.read(exchange.body, 1024 * 1024));
        }
    }
}
