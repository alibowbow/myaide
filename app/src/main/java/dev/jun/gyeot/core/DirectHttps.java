package dev.jun.gyeot.core;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;
import java.net.URLEncoder;
import java.net.UnknownHostException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import javax.net.ssl.HostnameVerifier;
import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * HTTPS that does not depend on the phone's DNS. Some Private DNS, VPN or network settings hide one host (seen with
 * auth.openai.com) while apps that use public DNS still reach it. Then the name is looked up over DNS-over-HTTPS at fixed
 * resolver addresses and the connection goes straight to that address. TLS still checks the certificate against the real
 * host name, so a wrong address cannot impersonate the service.
 */
public final class DirectHttps {
    private static final String[] RESOLVERS = {"https://1.1.1.1/dns-query?type=%s&name=%s", "https://8.8.8.8/resolve?type=%s&name=%s"};
    /** Hosts the system resolver could not find during this run; later calls go direct at once. */
    private static final Set<String> DIRECT = ConcurrentHashMap.newKeySet();
    /** Marks a lookup that failed over public DNS too, so the explanation can say that the detour was tried. */
    public static final String PUBLIC_DNS_FAILED = "공개 DNS로도 찾지 못함";
    private DirectHttps() {}
    /** How a host is looked up over public DNS; tests put their own. */
    interface Lookup { List<InetAddress> addresses(String host) throws IOException; }
    static volatile Lookup lookup = DirectHttps::resolve;

    public static boolean preferred(String host) { return host != null && DIRECT.contains(host.toLowerCase(Locale.ROOT)); }
    public static void remember(String host) { if (host != null) DIRECT.add(host.toLowerCase(Locale.ROOT)); }

    /** An HTTP response whose body streams from the socket; close it to end the request (also from another thread). */
    public static final class Response implements Closeable {
        public final int status;
        public final Map<String, String> headers;
        public final InputStream body;
        private final Socket socket;
        Response(int status, Map<String, String> headers, InputStream body, Socket socket) { this.status = status; this.headers = headers; this.body = body; this.socket = socket; }
        public String text(int limit) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[8192]; int count;
            while ((count = body.read(buffer)) > 0) { if (bytes.size() + count > limit) throw new IOException("응답이 너무 커요."); bytes.write(buffer, 0, count); }
            return bytes.toString(StandardCharsets.UTF_8.name());
        }
        /** Closes the underlying TCP socket without a TLS write, so it is safe from any thread. */
        @Override public void close() { try { socket.close(); } catch (IOException ignored) {} }
    }

    /** IPv4 addresses for host from public DNS-over-HTTPS, or IPv6 when there are none. */
    public static List<InetAddress> resolve(String host) throws IOException {
        IOException last = null;
        for (String type : new String[]{"A", "AAAA"}) {
            for (String resolver : RESOLVERS) {
                try {
                    List<InetAddress> found = addresses(host, query(String.format(resolver, type, URLEncoder.encode(host, StandardCharsets.UTF_8))), type.equals("A") ? 1 : 28);
                    if (!found.isEmpty()) return found;
                } catch (IOException error) { last = error; }
            }
        }
        // Reported as a lookup failure for the host itself (not the resolver's address), so the message names what was missing.
        UnknownHostException missing = new UnknownHostException(host + ": " + PUBLIC_DNS_FAILED);
        if (last != null) missing.initCause(last);
        throw missing;
    }
    private static String query(String url) throws IOException {
        HttpURLConnection connection = (HttpURLConnection) URI.create(url).toURL().openConnection();
        try {
            connection.setInstanceFollowRedirects(false); connection.setConnectTimeout(8_000); connection.setReadTimeout(8_000);
            connection.setRequestProperty("Accept", "application/dns-json");
            if (connection.getResponseCode() != 200) throw new IOException("DNS-over-HTTPS " + connection.getResponseCode());
            try (InputStream input = connection.getInputStream()) {
                ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int count;
                while ((count = input.read(buffer)) > 0) { if (bytes.size() + count > 64 * 1024) throw new IOException("DNS 응답이 너무 커요."); bytes.write(buffer, 0, count); }
                return bytes.toString(StandardCharsets.UTF_8.name());
            }
        } finally { connection.disconnect(); }
    }
    /** Addresses of the given record type from a DNS JSON answer (RFC 8484-style JSON used by Cloudflare and Google). */
    public static List<InetAddress> addresses(String host, String json, int type) throws IOException {
        List<InetAddress> result = new ArrayList<>();
        JSONObject answer;
        try { answer = new JSONObject(json); } catch (Exception e) { throw new IOException("DNS 응답 형식이 올바르지 않아요."); }
        if (answer.optInt("Status", -1) != 0) return result;
        JSONArray records = answer.optJSONArray("Answer");
        if (records == null) return result;
        for (int i = 0; i < records.length(); i++) {
            JSONObject record = records.optJSONObject(i);
            if (record == null || record.optInt("type") != type) continue;
            String data = record.optString("data");
            // Only literal addresses are accepted, so building the InetAddress never triggers another lookup.
            boolean literal = type == 1 ? data.matches("\\d{1,3}(\\.\\d{1,3}){3}") : data.matches("[0-9A-Fa-f:]+") && data.contains(":");
            if (!literal) continue;
            result.add(InetAddress.getByAddress(host, InetAddress.getByName(data).getAddress()));
        }
        return result;
    }

    /**
     * One HTTPS request over a socket opened to a publicly resolved address. verifier is an extra host-name check on top of
     * the TLS endpoint identification (Android passes its platform verifier); null skips the extra check. cancel, when given,
     * holds a stop from before any network I/O, the public DNS lookup included (RequestAbort.register), and then an action
     * that closes the socket in use; a stop during the lookup sends nothing, and once someone takes the action out, no
     * further address is tried.
     */
    public static Response send(String method, URI url, Map<String, String> headers, byte[] body, int timeoutMs, HostnameVerifier verifier, AtomicReference<Runnable> cancel) throws IOException {
        if (!"https".equals(url.getScheme()) || url.getHost() == null) throw new IOException("https 주소만 직접 연결할 수 있어요.");
        String host = url.getHost();
        int port = url.getPort() < 0 ? 443 : url.getPort();
        AtomicBoolean stopped = new AtomicBoolean();
        Runnable held = () -> stopped.set(true);
        if (!RequestAbort.register(cancel, held)) throw stopped();
        List<InetAddress> addresses = lookup.addresses(host);
        IOException last = null;
        for (InetAddress address : addresses) {
            Socket raw = new Socket();
            Runnable abort = () -> { stopped.set(true); close(raw); };
            // Stopped during the lookup or the last address's attempt (its stop run or taken out): nothing more is sent.
            if (stopped.get() || cancel != null && !cancel.compareAndSet(held, abort)) { close(raw); throw stopped(); }
            held = abort;
            try {
                raw.connect(new InetSocketAddress(address, port), 15_000); raw.setSoTimeout(timeoutMs);
                SSLSocket tls = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(raw, host, port, true);
                tls.setSoTimeout(timeoutMs);
                SSLParameters parameters = tls.getSSLParameters();
                parameters.setEndpointIdentificationAlgorithm("HTTPS"); parameters.setServerNames(List.of(new SNIHostName(host)));
                tls.setSSLParameters(parameters);
                tls.startHandshake();
                if (verifier != null && !verifier.verify(host, tls.getSession())) throw new IOException(host + " 인증서를 확인하지 못했어요.");
                return exchange(tls, raw, method, url, host, headers, body);
            } catch (IOException error) {
                close(raw);
                if (stopped.get() || cancel != null && cancel.get() != abort) throw error;
                last = error;
            }
        }
        throw last != null ? last : new IOException(host + "에 연결하지 못했어요.");
    }
    /** What a request stopped before it was sent throws: nothing reached the server. */
    public static InterruptedIOException stopped() { return new InterruptedIOException("요청을 멈췄어요."); }
    private static void close(Socket socket) { try { socket.close(); } catch (IOException ignored) {} }
    /** Writes an HTTP/1.1 request and parses the response head; the body is decoded from chunked or length-delimited framing. */
    static Response exchange(Socket socket, Socket raw, String method, URI url, String host, Map<String, String> headers, byte[] body) throws IOException {
        String path = (url.getRawPath() == null || url.getRawPath().isEmpty() ? "/" : url.getRawPath()) + (url.getRawQuery() == null ? "" : "?" + url.getRawQuery());
        StringBuilder head = new StringBuilder(method).append(' ').append(path).append(" HTTP/1.1\r\nHost: ").append(host)
            .append("\r\nConnection: close\r\nAccept-Encoding: identity\r\nUser-Agent: gyeot-android\r\n");
        for (Map.Entry<String, String> header : headers.entrySet()) {
            if ((header.getKey() + header.getValue()).matches("(?s).*[\\r\\n].*")) throw new IOException("잘못된 요청 헤더예요.");
            head.append(header.getKey()).append(": ").append(header.getValue()).append("\r\n");
        }
        if (body != null) head.append("Content-Length: ").append(body.length).append("\r\n");
        head.append("\r\n");
        OutputStream output = socket.getOutputStream();
        output.write(head.toString().getBytes(StandardCharsets.UTF_8));
        if (body != null) output.write(body);
        output.flush();
        InputStream input = new BufferedInputStream(socket.getInputStream());
        String[] status = line(input).split(" ", 3);
        if (status.length < 2 || !status[0].startsWith("HTTP/1.")) throw new IOException("올바르지 않은 HTTP 응답이에요.");
        int code;
        try { code = Integer.parseInt(status[1]); } catch (NumberFormatException e) { throw new IOException("올바르지 않은 HTTP 상태예요."); }
        Map<String, String> fields = new LinkedHashMap<>();
        for (String line = line(input); !line.isEmpty(); line = line(input)) {
            int colon = line.indexOf(':');
            if (colon > 0) fields.put(line.substring(0, colon).strip().toLowerCase(Locale.ROOT), line.substring(colon + 1).strip());
        }
        InputStream content;
        if (fields.getOrDefault("transfer-encoding", "").toLowerCase(Locale.ROOT).contains("chunked")) content = new Chunked(input);
        else if (fields.containsKey("content-length")) {
            long length;
            try { length = Long.parseLong(fields.get("content-length")); } catch (NumberFormatException e) { throw new IOException("올바르지 않은 Content-Length예요."); }
            content = new Bounded(input, length);
        } else content = input;
        return new Response(code, fields, content, raw);
    }
    /** One header line without its line break, at most 16 KB. */
    static String line(InputStream input) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int current;
        while ((current = input.read()) >= 0 && current != '\n') { if (bytes.size() >= 16 * 1024) throw new IOException("HTTP 헤더가 너무 길어요."); bytes.write(current); }
        if (current < 0 && bytes.size() == 0) throw new IOException("연결이 응답 없이 끊겼어요.");
        String text = bytes.toString(StandardCharsets.ISO_8859_1.name());
        return text.endsWith("\r") ? text.substring(0, text.length() - 1) : text;
    }
    private static final class Bounded extends InputStream {
        private final InputStream input; private long left;
        Bounded(InputStream input, long length) { this.input = input; left = length; }
        @Override public int read() throws IOException { if (left <= 0) return -1; int value = input.read(); if (value >= 0) left--; return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (left <= 0) return -1;
            int count = input.read(buffer, offset, (int) Math.min(length, left));
            if (count > 0) left -= count;
            return count;
        }
    }
    /** Transfer-Encoding: chunked decoder (sizes in hex, optional extensions, trailers ignored). */
    private static final class Chunked extends InputStream {
        private final InputStream input; private long left; private boolean done;
        Chunked(InputStream input) { this.input = input; }
        private boolean next() throws IOException {
            if (done) return false;
            if (left > 0) return true;
            String size = line(input);
            if (size.isEmpty()) size = line(input); // the CRLF that ends the previous chunk
            int extension = size.indexOf(';');
            try { left = Long.parseLong((extension >= 0 ? size.substring(0, extension) : size).strip(), 16); }
            catch (NumberFormatException e) { throw new IOException("올바르지 않은 청크 크기예요."); }
            if (left == 0) { for (String trailer = line(input); !trailer.isEmpty(); trailer = line(input)) {} done = true; return false; }
            return true;
        }
        @Override public int read() throws IOException { if (!next()) return -1; int value = input.read(); if (value >= 0) left--; return value; }
        @Override public int read(byte[] buffer, int offset, int length) throws IOException {
            if (!next()) return -1;
            int count = input.read(buffer, offset, (int) Math.min(length, left));
            if (count > 0) left -= count;
            return count;
        }
    }
}
