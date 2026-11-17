package dev.jun.gyeot;

import dev.jun.gyeot.core.Archive;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.nio.charset.StandardCharsets;

/**
 * Calls one tool on the connected 마이아카이브 MCP server (stateless Streamable HTTP, one tools/call per request). Blocking;
 * call it off the main thread. The address carries the key, so it goes only to its own host, redirects are never followed,
 * and no error message ever includes it.
 */
final class ArchiveClient {
    private ArchiveClient() {}

    /** tool is {"name": ..., "arguments": {...}} as Archive.request builds it; returns the tool's text. */
    static String call(String address, JSONObject tool) throws Exception {
        String url = Archive.address(address);
        if (!Archive.sameHost(url, url)) throw new IllegalStateException("마이아카이브 연결 주소를 확인해 주세요.");
        byte[] body = Archive.call(1, tool.optString("name"), tool.optJSONObject("arguments")).toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = null;
        try {
            connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(15_000); connection.setReadTimeout(40_000);
            connection.setRequestMethod("POST"); connection.setDoOutput(true); connection.setFixedLengthStreamingMode(body.length);
            connection.setRequestProperty("Content-Type", "application/json");
            connection.setRequestProperty("Accept", "application/json, text/event-stream");
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            if (status >= 300 && status < 400) throw new IllegalStateException("마이아카이브 주소가 다른 곳으로 옮겨졌어요. 앱에서 새 연결 주소를 만들어 넣어 주세요.");
            InputStream input = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
            String text = input == null ? "" : OpenAiAccount.Http.read(input, 4 * 1024 * 1024);
            if (status >= 500 && !text.strip().startsWith("{")) throw new IllegalStateException("마이아카이브 서버가 잠시 응답하지 않아요 (" + status + "). 조금 뒤 다시 시도해 주세요.");
            return Archive.result(text);
        } catch (SocketTimeoutException e) {
            throw new IllegalStateException("마이아카이브 서버의 응답이 늦어요. 조금 뒤 다시 시도해 주세요.");
        } catch (IOException e) {
            // Network errors can name the URL; say what happened without it.
            throw new IllegalStateException("마이아카이브 서버에 닿지 못했어요. 네트워크를 확인해 주세요.");
        } finally { if (connection != null) connection.disconnect(); }
    }
}
