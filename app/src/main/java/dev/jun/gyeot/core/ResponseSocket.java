package dev.jun.gyeot.core;

import java.io.IOException;
import java.net.Socket;
import java.net.URI;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;
import org.json.JSONObject;

/** One sequential Responses socket per phone task. It never owns or executes device tools. */
public final class ResponseSocket implements AutoCloseable {
    public static final class Unavailable extends IOException {
        public Unavailable(String message) { super(message); }
    }
    public static final class ApiFailure extends Exception {
        public final int status;
        public final JSONObject body;
        ApiFailure(JSONObject event) { super("GPT 실시간 요청이 거부됐어요."); status = event.optInt("status", 400); body = event; }
        public String code() { JSONObject error = body.optJSONObject("error"); return error == null ? "" : error.optString("code"); }
        public boolean previousMissing() { return code().equals("previous_response_not_found"); }
        public boolean transportUnsupported() {
            JSONObject error = body.optJSONObject("error"); String param = error == null ? "" : error.optString("param");
            return code().startsWith("websocket_") || param.equals("stream_id") || param.equals("type");
        }
    }
    private static final int MAX_MESSAGE = 4 * 1024 * 1024;
    private final ArrayBlockingQueue<String> incoming = new ArrayBlockingQueue<>(512);
    private final CompletableFuture<Boolean> opened = new CompletableFuture<>();
    private final AtomicBoolean closed = new AtomicBoolean();
    private final AtomicReference<Runnable> cancel;
    private final Runnable guard, activity, abort = this::close;
    private final WebSocketClient socket;
    private volatile String failure;
    private volatile boolean corrupt;

    public ResponseSocket(URI uri, Map<String, String> headers, AtomicReference<Runnable> cancel, Runnable activity, Runnable guard) {
        this.cancel = cancel; this.activity = activity; this.guard = guard;
        socket = new WebSocketClient(uri, new Draft_6455(), headers, 3000) {
            @Override public void onOpen(ServerHandshake handshake) {
                if (closed.get()) { close(); opened.complete(false); }
                else opened.complete(true);
            }
            @Override public void onMessage(String raw) {
                if (closed.get()) return;
                if (raw.length() > MAX_MESSAGE || !incoming.offer(raw)) { corrupt = true; fail("GPT 실시간 응답의 크기 제한을 초과했어요."); }
            }
            @Override public void onClose(int code, String reason, boolean remote) { fail("GPT 실시간 연결이 닫혔어요."); }
            @Override public void onError(Exception error) { fail("GPT 실시간 연결을 사용할 수 없어요."); }
        };
        socket.setConnectionLostTimeout(30); socket.setTcpNoDelay(true);
    }
    private void fail(String message) { failure = message; opened.complete(false); }
    /** Installs cancellation before connecting; guard polling also covers a socket created just after a stop. */
    public void connect() throws Exception {
        guard.run(); cancel.set(abort); guard.run(); socket.connect();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3);
        while (true) {
            guard.run();
            long remaining = deadline - System.nanoTime();
            if (remaining <= 0) throw new Unavailable("GPT 실시간 연결을 기다리는 시간이 초과됐어요.");
            try {
                if (!opened.get(Math.min(100, Math.max(1, TimeUnit.NANOSECONDS.toMillis(remaining))), TimeUnit.MILLISECONDS))
                    throw new Unavailable("GPT 실시간 연결을 사용할 수 없어요.");
                guard.run(); return;
            } catch (TimeoutException pending) { /* Poll only the local connection state. */ }
        }
    }
    public OpenAiProtocol.Stream exchange(JSONObject request) throws Exception {
        guard.run();
        if (closed.get() || !socket.isOpen()) throw new Unavailable("GPT 실시간 연결이 닫혔어요.");
        if (!incoming.isEmpty()) throw new Unavailable("이전 GPT 응답이 남아 있어 연결을 다시 확인해야 해요.");
        try { socket.send(request.toString()); }
        catch (RuntimeException disconnected) { throw new Unavailable("GPT 실시간 요청을 보내지 못했어요."); }
        OpenAiProtocol.Stream stream = new OpenAiProtocol.Stream();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120);
        while (true) {
            guard.run();
            if (corrupt) throw new IllegalStateException("GPT 실시간 응답의 크기 제한을 초과했어요.");
            if (System.nanoTime() >= deadline) throw new IllegalStateException("GPT 실시간 응답 시간이 초과됐어요.");
            String raw = incoming.poll(100, TimeUnit.MILLISECONDS);
            if (raw == null) {
                if (closed.get() || failure != null) throw new Unavailable("GPT 응답이 끝나기 전에 실시간 연결이 끊겼어요.");
                continue;
            }
            activity.run(); JSONObject event;
            try { event = new JSONObject(raw); }
            catch (Exception invalid) { throw new IllegalStateException("GPT 실시간 응답 형식이 올바르지 않아요."); }
            String lane = event.optString("stream_id", "phone");
            if (!lane.equals("phone")) throw new IllegalStateException("다른 GPT 작업의 응답을 받아 실행하지 않았어요.");
            if ("error".equals(event.optString("type"))) throw new ApiFailure(event);
            stream.event(event); guard.run();
            if (stream.failure() != null) throw new IllegalStateException(stream.failure());
            if (stream.completed()) return stream;
        }
    }
    @Override public void close() {
        if (!closed.compareAndSet(false, true)) return;
        cancel.compareAndSet(abort, null); opened.complete(false); incoming.clear();
        if (!socket.isOpen()) {
            Socket connecting = socket.getSocket();
            if (connecting != null) try { connecting.close(); } catch (IOException ignored) {}
        }
        socket.close();
    }
}
