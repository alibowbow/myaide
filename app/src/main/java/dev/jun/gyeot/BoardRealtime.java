package dev.jun.gyeot;

import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.RealtimeVoice;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.Map;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

/**
 * 판서's narration in a GPT or Grok voice (BoardVoice): one realtime connection that reads each piece it is given aloud
 * (RealtimeVoice.narrator and READ_ALOUD) and reports its sound and its end to BoardVoice.LiveVoice, which plays and paces
 * them as it does for Gemini Live. The sign-in or key is the voice's own (VoiceCredentials).
 */
final class BoardRealtime implements BoardVoice.LiveVoice.Line {
    static BoardVoice.LiveVoice.Lines lines(AppStore store, RealtimeVoice.Kind kind) {
        return (model, voice, events) -> { BoardRealtime line = new BoardRealtime(store, kind, voice, events); line.connect(); return line; };
    }
    private final AppStore store; private final RealtimeVoice.Kind kind; private final String voice; private final BoardVoice.LiveVoice.Events events;
    private volatile WebSocketClient socket; private volatile boolean ready, closed, failed;
    private final ArrayDeque<String> waiting = new ArrayDeque<>();
    private String reading = "";

    private BoardRealtime(AppStore store, RealtimeVoice.Kind kind, String voice, BoardVoice.LiveVoice.Events events) {
        this.store = store; this.kind = kind; this.voice = voice; this.events = events;
    }
    private void connect() {
        Thread t = new Thread(() -> {
            try {
                VoiceCredentials.Credential credential = VoiceCredentials.get(store, kind, null);
                if (credential == null) { fail(kind.label + " 음성으로 읽으려면 " + (kind == RealtimeVoice.Kind.GPT ? "Codex 로그인이나 OpenAI API 키" : "Grok 로그인이나 xAI API 키") + "가 필요해요.", false); return; }
                if (closed) return;
                WebSocketClient client = new WebSocketClient(URI.create(kind.url()), new Draft_6455(), Map.of("Authorization", "Bearer " + credential.token()), 15_000) {
                    @Override public void onOpen(ServerHandshake handshake) {
                        send(RealtimeVoice.narrator(kind, GeminiProtocol.NARRATION, voice).toString());
                        // A server that takes the session without saying so is ready anyway.
                        new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(BoardRealtime.this::opened, 4000);
                    }
                    @Override public void onMessage(String text) { receive(text); }
                    @Override public void onMessage(ByteBuffer bytes) {}
                    @Override public void onClose(int code, String reason, boolean remote) {
                        if (closed) return;
                        int status = RealtimeVoice.refused(reason);
                        fail(status > 0 ? RealtimeVoice.refusal(kind, status, credential.signIn()) : kind.label + " 음성 연결이 끊겼어요.", status == 0 || status >= 500);
                    }
                    @Override public void onError(Exception error) {}
                };
                client.setConnectionLostTimeout(30); client.setTcpNoDelay(true);
                socket = client; client.connect();
            } catch (Exception error) { fail(error.getMessage() == null ? kind.label + " 음성에 연결하지 못했어요." : error.getMessage(), true); }
        }, "gyeot-board-realtime");
        t.setDaemon(true); t.start();
    }
    private synchronized void opened() {
        if (ready || closed || failed) return;
        ready = true; events.ready();
        String next = waiting.poll(); if (next != null) read(next);
    }
    private void receive(String text) {
        if (closed) return;
        RealtimeVoice.Event e = RealtimeVoice.parse(text);
        switch (e.type()) {
            case READY -> opened();
            case AUDIO -> { if (e.audio() != null && e.audio().length > 0) events.audio(e.audio()); }
            case DONE -> {
                synchronized (this) { reading = ""; }
                if ("completed".equals(e.status()) || e.status().isEmpty()) events.finished();
                else fail(kind.label + " 음성이 문장을 끝까지 읽지 못했어요.", true);
            }
            case ERROR -> { if (!RealtimeVoice.harmless(e)) fail(e.text().isBlank() ? kind.label + " 음성 오류" : e.text(), ready); }
            default -> {}
        }
    }
    private void fail(String why, boolean retryable) {
        synchronized (this) { if (failed || closed) return; failed = true; }
        events.failed(why, retryable);
    }
    @Override public synchronized void say(String piece) {
        if (closed || failed) return;
        if (!ready) { waiting.add(piece); return; }
        read(piece);
    }
    private void read(String piece) {
        WebSocketClient w = socket; reading = piece;
        if (w == null || !w.isOpen()) { fail(kind.label + " 음성 연결이 끊겼어요.", true); return; }
        try { w.send(RealtimeVoice.userText(piece).toString()); w.send(RealtimeVoice.respond(RealtimeVoice.READ_ALOUD).toString()); }
        catch (Exception lost) { fail(kind.label + " 음성 연결이 끊겼어요.", true); }
    }
    @Override public void close() {
        closed = true; WebSocketClient w = socket; socket = null; if (w == null) return;
        if (!w.isOpen()) { Socket raw = w.getSocket(); if (raw != null) try { raw.close(); } catch (Exception ignored) {} }
        try { w.close(); } catch (Exception ignored) {}
    }
}
