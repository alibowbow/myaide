package dev.jun.gyeot;

import dev.jun.gyeot.core.Mcp;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A minimal MCP client over Streamable HTTP for connectors the user added: initialize once per app run (keeping the
 * server's session id), then tools/list or tools/call. Blocking; call it off the main thread. The address and token go
 * only to the address's own host, redirects are never followed, and no error message carries either. A connector added by
 * browser sign-in (ConnectorAuth) renews its access token once when the server refuses it.
 */
final class McpClient {
    /** The bearer token a connector sends. rejected is the token the server just refused (null at first); null or blank sends none. */
    interface Bearer { String token(String rejected) throws Exception; }
    /** The server answered 401: it wants a sign-in, or refused the token. challenge is its WWW-Authenticate header ("" when none). */
    static final class SignInNeeded extends IllegalStateException {
        final String challenge;
        SignInNeeded(String message, String challenge) { super(message); this.challenge = challenge == null ? "" : challenge; }
    }
    private record Session(String id, String version) {}
    private record Reply(int status, String body, String session, String challenge) {}
    /** Per connector: the negotiated session ("" id for stateless servers). Dropped when the server forgets it. */
    private static final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private McpClient() {}

    /** A token pasted by the user, or none: sent as it is and never renewed. */
    static Bearer fixed(String token) { return rejected -> rejected == null ? token : null; }

    static JSONArray listTools(String connector, String address, String token, String name) throws Exception { return listTools(connector, address, fixed(token), name); }
    static JSONArray listTools(String connector, String address, Bearer bearer, String name) throws Exception {
        JSONObject result = Mcp.result(exchange(connector, address, bearer, Mcp.request(2, "tools/list", new JSONObject()), name).body, name);
        JSONArray tools = result.optJSONArray("tools");
        return tools == null ? new JSONArray() : tools;
    }
    static String callTool(String connector, String address, String token, String name, String tool, JSONObject arguments) throws Exception { return callTool(connector, address, fixed(token), name, tool, arguments); }
    static String callTool(String connector, String address, Bearer bearer, String name, String tool, JSONObject arguments) throws Exception {
        JSONObject params = new JSONObject().put("name", tool).put("arguments", arguments == null ? new JSONObject() : arguments);
        return Mcp.text(Mcp.result(exchange(connector, address, bearer, Mcp.request(3, "tools/call", params), name).body, name), name);
    }
    static void forget(String connector) { sessions.remove(connector); }

    /** One request with the connector's token. A refused token is renewed once when it can be, in a new session (a 401 ran nothing). */
    private static Reply exchange(String connector, String address, Bearer bearer, JSONObject message, String name) throws Exception {
        String token = bearer.token(null);
        try { return send(connector, address, token, message, name); }
        catch (SignInNeeded refused) {
            if (token == null || token.isBlank()) throw refused;
            String renewed = bearer.token(token);
            if (renewed == null || renewed.isBlank() || renewed.equals(token)) throw refused;
            sessions.remove(connector);
            return send(connector, address, renewed, message, name);
        }
    }
    /** Sends one request in the connector's session, starting (or restarting, once) the session as needed. */
    private static Reply send(String connector, String address, String token, JSONObject message, String name) throws Exception {
        Session session = sessions.get(connector);
        if (session == null) session = open(connector, address, token, name);
        Reply reply = post(address, token, session, message, name);
        // 404 with a session id means the server ended that session: start a new one and try once more.
        if (reply.status == 404 && !session.id.isEmpty()) {
            sessions.remove(connector);
            session = open(connector, address, token, name);
            reply = post(address, token, session, message, name);
        }
        check(reply, name, token);
        return reply;
    }
    private static Session open(String connector, String address, String token, String name) throws Exception {
        Reply init = post(address, token, null, Mcp.initialize("1"), name);
        check(init, name, token);
        JSONObject result = Mcp.result(init.body, name);
        Session session = new Session(init.session == null ? "" : init.session, result.optString("protocolVersion", Mcp.PROTOCOL));
        post(address, token, session, Mcp.notification("notifications/initialized"), name);
        sessions.put(connector, session);
        return session;
    }
    private static void check(Reply reply, String name, String token) {
        if (reply.status == 401) throw new SignInNeeded(token == null || token.isBlank()
            ? name + " 서버가 로그인을 요구해요. 설정 → 커넥터에서 인증 토큰을 비워 두고 추가하면 브라우저 로그인 창이 열려요."
            : name + " 서버가 로그인 정보를 받지 않았어요. 인증 토큰을 확인하거나, 설정 → 커넥터에서 그 커넥터를 눌러 다시 로그인해 주세요.", reply.challenge);
        if (reply.status == 403) throw new IllegalStateException(name + " 서버가 인증을 거절했어요. 주소나 인증 토큰, 계정 권한을 확인해 주세요.");
        if (reply.status >= 500 && Mcp.message(reply.body) == null) throw new IllegalStateException(name + " 서버가 잠시 응답하지 않아요 (" + reply.status + ").");
        if (reply.status >= 400 && Mcp.message(reply.body) == null) throw new IllegalStateException(name + " 서버가 요청을 받지 않았어요 (" + reply.status + "). MCP(Streamable HTTP) 주소인지 확인해 주세요.");
    }
    private static Reply post(String address, String token, Session session, JSONObject message, String name) throws Exception {
        String url = Mcp.address(address);
        byte[] body = message.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(60_000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            if (token != null && !token.isBlank()) connection.setRequestProperty("Authorization", "Bearer " + token.strip());
            if (session != null) {
                connection.setRequestProperty("MCP-Protocol-Version", session.version);
                if (!session.id.isEmpty()) connection.setRequestProperty("Mcp-Session-Id", session.id);
            }
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IllegalStateException(name + " 주소가 다른 곳으로 옮겨졌어요. 새 주소로 다시 추가해 주세요.");
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = input == null ? "" : OpenAiAccount.Http.read(input, 4 * 1024 * 1024);
            // On a 401 the challenge names where the server signs in (RFC 9728); it is read only to start that sign-in.
            return new Reply(status, text, connection.getHeaderField("Mcp-Session-Id"), connection.getHeaderField("WWW-Authenticate"));
        } catch (SocketTimeoutException e) {
            throw new IllegalStateException(name + " 서버의 응답이 늦어요. 조금 뒤 다시 시도해 주세요.");
        } catch (IOException e) {
            // Network errors can name the URL; say what happened without it.
            throw new IllegalStateException(name + " 서버에 닿지 못했어요. 네트워크와 주소를 확인해 주세요.");
        } finally { if (connection != null) connection.disconnect(); }
    }
}
