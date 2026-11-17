package dev.jun.gyeot.core;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

/** One-shot loopback listener for the OAuth redirect (RFC 8252). Only 127.0.0.1, the exact Host and the callback path are accepted. */
public final class OpenAiLoopback implements AutoCloseable {
    private final ServerSocket server;
    private final String host, path, product, back;
    public final String redirectUri;
    public OpenAiLoopback() throws IOException {
        this(0, "127.0.0.1");
    }
    /** Codex's registered client requires localhost:1455; the existing dynamic client keeps its ephemeral port. */
    public OpenAiLoopback(int port, String hostname) throws IOException { this(port, hostname, OpenAiProtocol.CALLBACK_PATH, "ChatGPT", "gyeot://openai-done"); }
    /** Google's installed-app sign-in (Gemini subscription) waits on its own callback path; product names the sign-in in messages. */
    /** back: the app link on the finished page; it opens the settings page the sign-in started from. */
    public OpenAiLoopback(int port, String hostname, String path, String product, String back) throws IOException {
        if (back == null || !back.matches("gyeot://openai-done(\\?page=[a-z]{1,20})?")) throw new IllegalArgumentException("Return link required");
        this.back = back;
        if (!hostname.equals("127.0.0.1") && !hostname.equals("localhost")) throw new IllegalArgumentException("Loopback host required");
        if (path == null || !path.matches("/[a-z0-9/_-]{1,40}")) throw new IllegalArgumentException("Loopback path required");
        this.path = path; this.product = product;
        server = new ServerSocket(port, 8, InetAddress.getByName("127.0.0.1"));
        host = hostname + ":" + server.getLocalPort();
        redirectUri = "http://" + host + path;
    }
    /** Returns the request target of the first redirect carrying the expected state; other requests are answered and ignored. */
    public String await(String state, long timeoutMs) throws IOException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (true) {
            long left = deadline - System.currentTimeMillis();
            if (left <= 0) throw new IOException(product + " 로그인 시간이 지났어요. 다시 시도해 주세요.");
            server.setSoTimeout((int) Math.min(left, Integer.MAX_VALUE));
            Socket socket;
            try { socket = server.accept(); }
            catch (SocketTimeoutException timeout) { continue; }
            try (Socket client = socket) {
                client.setSoTimeout(10_000);
                String[] request = head(client.getInputStream());
                String[] line = request[0].split(" ");
                String target = line.length == 3 ? line[1] : "";
                if (!line[0].equals("GET") || !target.startsWith(path + "?") || !host.equalsIgnoreCase(request[1])) { reply(client, 404, "<p>Not found</p>"); continue; }
                // Unrelated local requests must not consume the pending sign-in.
                if (!OpenAiProtocol.stateMatches(target, state)) { reply(client, 400, "<p>로그인을 시작한 " + Brand.NAME + " 화면에서 다시 시도해 주세요.</p>"); continue; }
                reply(client, 200, "<h1>로그인이 끝났어요</h1><p>" + Brand.I + " 연결을 마무리하고 있어요. 이 탭은 닫아도 돼요.</p><p><a href=\"" + back + "\">" + Brand.EURO + " 돌아가기</a></p>");
                return target;
            } catch (IOException ignored) {
                // A half-open or malformed local connection never ends the sign-in; keep listening until the deadline.
            }
        }
    }
    /** Request line and Host header, reading at most 16 KB until the blank line that ends the headers. */
    static String[] head(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int current;
        while (bytes.size() < 16 * 1024 && (current = input.read()) >= 0) {
            bytes.write(current);
            if (current == '\n') { String text = bytes.toString("ISO-8859-1"); if (text.endsWith("\r\n\r\n") || text.endsWith("\n\n")) break; }
        }
        String[] lines = bytes.toString("ISO-8859-1").split("\r?\n");
        String host = "";
        for (int i = 1; i < lines.length; i++) if (lines[i].toLowerCase(Locale.ROOT).startsWith("host:")) host = lines[i].substring(5).strip();
        return new String[]{lines.length == 0 ? "" : lines[0], host};
    }
    private static void reply(Socket socket, int status, String body) throws IOException {
        String page = "<!doctype html><html lang=\"ko\"><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width\"><title>" + Brand.NAME + "</title>"
            + "<style>body{font:17px system-ui;max-width:32rem;margin:15vh auto;padding:24px;color:#202123}a{color:#0b7a5a;font-weight:600}</style>" + body + "</html>";
        byte[] bytes = page.getBytes(StandardCharsets.UTF_8);
        String header = "HTTP/1.1 " + status + (status == 200 ? " OK" : status == 400 ? " Bad Request" : " Not Found") + "\r\nContent-Type: text/html; charset=utf-8\r\n"
            + "Content-Length: " + bytes.length + "\r\nCache-Control: no-store\r\nReferrer-Policy: no-referrer\r\nContent-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\nConnection: close\r\n\r\n";
        OutputStream output = socket.getOutputStream(); output.write(header.getBytes(StandardCharsets.ISO_8859_1)); output.write(bytes); output.flush();
    }
    @Override public void close() { try { server.close(); } catch (IOException ignored) {} }
}

