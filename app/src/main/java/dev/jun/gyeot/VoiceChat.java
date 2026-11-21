package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.jun.gyeot.core.BargeIn;
import dev.jun.gyeot.core.RealtimeVoice;
import dev.jun.gyeot.core.VoiceChatLog;
import java.net.Socket;
import java.net.URI;
import java.nio.ByteBuffer;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.java_websocket.client.WebSocketClient;
import org.java_websocket.drafts.Draft_6455;
import org.java_websocket.handshake.ServerHandshake;

/**
 * 메인 대화의 음성 대화: the user talks with GPT (OpenAI Realtime, with the Codex sign-in's ChatGPT plan or an API key) or
 * Grok (xAI Voice Agent, with the Grok sign-in or an xAI key) in real time, in core/RealtimeVoice's events. The phone's
 * microphone goes up at 24 kHz and the answer plays as it comes; the server finds the turns. While the answer plays
 * through the phone the microphone rests, and a voice clearly louder than the answer's echo (core/BargeIn) stops it. What
 * both said is written into the main chat as it is settled (core/VoiceChatLog), so typing carries on from it. One at a
 * time (AppStore.voiceChat); state is read and changed on the main thread.
 */
final class VoiceChat {
    enum State { CONNECTING, LISTENING, HEARING, THINKING, SPEAKING, ENDED }
    /** A sign-in or key this voice needs before it can talk; what to tell the user. */
    static final class Missing extends IllegalStateException { Missing(String message) { super(message); } }
    /** The last 0.8 s of the microphone while the answer plays, sent ahead of a voice that stops it. */
    private static final int ROLL_BYTES = 16000 * 2 * 8 / 10;
    private static final long CONNECT_MS = 25_000, READY_MS = 5_000;

    final RealtimeVoice.Kind kind; final String voice;
    private final Context context; private final AppStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService work = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-voice-chat"); t.setDaemon(true); return t; });
    private final BargeIn barge = new BargeIn();
    private final ArrayDeque<byte[]> roll = new ArrayDeque<>();
    private final VoiceChatLog log = new VoiceChatLog();
    private final Map<String, StringBuilder> answers = new HashMap<>();
    private volatile WebSocketClient socket;
    private volatile Microphone mic;
    private volatile Speaker speaker;
    private volatile boolean ready, ended, muted;
    /** After the user stopped an answer: the microphone goes up whole until the next answer plays. */
    private volatile boolean open;
    /** The answer playing, and the one the user stopped (whose late audio is dropped). */
    private volatile String playing = "", stopped = "";
    private volatile String lastError = "";
    private volatile State state = State.CONNECTING;
    private String words = "", failure = "";
    private Runnable listener;
    /** The sign-in's token the server just turned down, refreshed once; whether the token is a sign-in's (not a key). */
    private String rejected; private boolean signedIn; private int attempts;
    /** The user's line the next answer follows (work thread). */
    private long lastUser;

    VoiceChat(Context context, AppStore store, RealtimeVoice.Kind kind, String voice) {
        this.context = context.getApplicationContext(); this.store = store; this.kind = kind; this.voice = kind.voice(voice);
    }
    State state() { return state; }
    /** The words heard or spoken now: the user's as they are written down, then the answer's. */
    String words() { return words; }
    /** Why it ended, when it did not end by the user's hand. */
    String failure() { return failure; }
    /** Whether it ended for a sign-in or key: missing, or turned down by the server. */
    boolean needsSetup() { return needsSetup; }
    private volatile boolean needsSetup;
    boolean muted() { return muted; }
    void onChange(Runnable listener) { this.listener = listener; }
    private void changed() { Runnable l = listener; if (l != null) l.run(); store.changed(); }

    /** Main thread. */
    void start() {
        VoiceChatService.start(context);
        work.execute(this::connect);
        main.postDelayed(() -> { if (!ready && !ended) end(kind.label + " 음성 연결 시간이 초과됐어요. 네트워크와 로그인을 확인해 주세요."); }, CONNECT_MS);
        changed();
    }
    void toggleMute() {
        if (ended) return;
        muted = !muted; Microphone m = mic; if (m != null) m.muted(muted);
        changed();
    }
    /** Ends the conversation (the user's hand when failure is empty); what was said and settled is written down. */
    void end(String why) {
        if (ended) return;
        ended = true; state = State.ENDED; failure = why == null ? "" : why;
        main.removeCallbacksAndMessages(null);
        Microphone m = mic; mic = null; if (m != null) m.close();
        Speaker s = speaker; speaker = null; if (s != null) s.close();
        WebSocketClient w = socket; socket = null; if (w != null) shutdown(w);
        for (Map.Entry<String, StringBuilder> unfinished : answers.entrySet()) log.answered(unfinished.getKey(), unfinished.getValue().toString(), true, SystemClock.uptimeMillis());
        answers.clear();
        write(log.rest());
        work.shutdown();
        VoiceChatService.stop(context);
        if (store.voiceChat == this) store.voiceChat = null;
        changed();
    }

    // ── connection (work thread) ───────────────────────────────────────────
    private record Credential(String token, boolean signIn) {}
    private Credential credential() throws Exception {
        if (kind == RealtimeVoice.Kind.GPT) {
            String key = store.secrets.get(SecretStore.OPENAI_VOICE);
            if (!key.isBlank()) return new Credential(key, false);
            if (store.codexImages.connected()) return new Credential(store.codexImages.voiceToken(rejected), true);
            if ("key".equals(store.openai.describe().optString("mode"))) return new Credential(store.openai.bearer(), false);
            throw new Missing("GPT 음성 대화는 Codex 로그인(ChatGPT 구독)으로 해요. 음성 대화 설정에서 Codex로 로그인해 주세요.");
        }
        String key = store.secrets.get(SecretStore.XAI_VOICE);
        if (!key.isBlank()) return new Credential(key, false);
        if (store.grok.connected()) return new Credential(store.grok.voiceToken(rejected), true);
        throw new Missing("Grok 음성 대화는 Grok 구독 로그인이나 xAI API 키로 해요. 음성 대화 설정에서 연결해 주세요.");
    }
    private void connect() {
        if (ended) return;
        try {
            String instructions = store.mainAssistant.voiceInstructions();
            Credential credential = credential(); signedIn = credential.signIn();
            String token = credential.token();
            WebSocketClient client = new WebSocketClient(URI.create(kind.url()), new Draft_6455(), Map.of("Authorization", "Bearer " + token), 15_000) {
                @Override public void onOpen(ServerHandshake handshake) {
                    send(RealtimeVoice.session(kind, instructions, voice).toString());
                    // A server that takes the session without saying so still hears the user.
                    main.postDelayed(() -> { if (!ended && !ready) ready(); }, READY_MS);
                }
                @Override public void onMessage(String text) { receive(text); }
                @Override public void onMessage(ByteBuffer bytes) {}
                @Override public void onClose(int code, String reason, boolean remote) { main.post(() -> closed(this, token, reason)); }
                @Override public void onError(Exception error) { lastError = error == null || error.getMessage() == null ? "" : error.getMessage(); }
            };
            client.setConnectionLostTimeout(30); client.setTcpNoDelay(true);
            if (ended) return;
            socket = client; client.connect();
        } catch (Missing missing) { needsSetup = true; main.post(() -> end(missing.getMessage())); }
        catch (Exception error) { String message = error.getMessage(); main.post(() -> end(kind.label + " 음성 대화를 시작하지 못했어요" + (message == null || message.isBlank() ? "." : ": " + message))); }
    }
    private void closed(WebSocketClient which, String token, String reason) {
        if (ended || which != socket) return;
        int status = RealtimeVoice.refused((reason == null ? "" : reason) + " " + lastError);
        // A sign-in's token turned down before the conversation began: refreshed once, then asked again.
        if (!ready && signedIn && (status == 401 || status == 403) && attempts++ == 0) { rejected = token; socket = null; work.execute(this::connect); return; }
        needsSetup = !ready && (status == 401 || status == 403);
        end(ready ? "연결이 끊겨 음성 대화를 마쳤어요." : RealtimeVoice.refusal(kind, status, signedIn));
    }
    private void send(String text) {
        WebSocketClient w = socket;
        if (w != null && w.isOpen()) try { w.send(text); } catch (Exception dropped) {}
    }
    /** WebSocketClient.close() does nothing before the handshake, so a stalled connect is cut at the socket. */
    private static void shutdown(WebSocketClient w) {
        if (!w.isOpen()) { Socket raw = w.getSocket(); if (raw != null) try { raw.close(); } catch (Exception ignored) {} }
        try { w.close(); } catch (Exception ignored) {}
    }

    // ── events (socket thread, then the main thread) ──────────────────────────
    private void receive(String text) {
        if (ended) return;
        RealtimeVoice.Event e = RealtimeVoice.parse(text);
        if (e.type() == RealtimeVoice.Type.AUDIO) { audio(e); return; }
        if (e.type() == RealtimeVoice.Type.OTHER) return;
        main.post(() -> { if (!ended) event(e); });
    }
    /** An answer's audio plays at once, unless the user stopped that answer. */
    private void audio(RealtimeVoice.Event e) {
        Speaker out = speaker; byte[] pcm = e.audio();
        if (out == null || pcm == null || pcm.length == 0 || e.response().equals(stopped)) return;
        if (!e.response().equals(playing)) { playing = e.response(); open = false; }
        out.play(pcm);
        if (state != State.SPEAKING) main.post(() -> { if (!ended && state != State.SPEAKING) { state = State.SPEAKING; changed(); } });
    }
    private void event(RealtimeVoice.Event e) {
        long now = SystemClock.uptimeMillis();
        switch (e.type()) {
            case READY -> { if (!ready) ready(); }
            case SPEECH_STARTED -> {
                // The user began to speak: an answer still playing stops (the server stops making it).
                Speaker out = speaker; if (out != null && out.isPlaying()) { stopped = playing; out.flush(); }
                log.heard(e.item(), now); words = ""; state = State.HEARING; changed();
            }
            case SPEECH_STOPPED -> { log.heard(e.item(), now); if (state == State.HEARING) { state = State.THINKING; changed(); } }
            case COMMITTED -> log.heard(e.item(), now);
            case USER_PARTIAL -> { words = "full".equals(e.status()) ? e.text() : words + e.text(); changed(); }
            case USER_TEXT -> { log.said(e.item(), e.text(), now); if (!e.text().isBlank()) words = e.text(); settle(); changed(); }
            case ANSWER_STARTED -> answers.put(e.response(), new StringBuilder());
            case ANSWER_PARTIAL -> {
                StringBuilder b = answers.computeIfAbsent(e.response(), k -> new StringBuilder()); b.append(e.text());
                if (!e.response().equals(stopped)) { words = b.toString(); changed(); }
            }
            case ANSWER_TEXT -> { if (!e.text().isBlank()) answers.put(e.response(), new StringBuilder(e.text())); }
            case DONE -> {
                StringBuilder b = answers.remove(e.response());
                boolean cut = e.response().equals(stopped) || "cancelled".equals(e.status()) || "incomplete".equals(e.status());
                log.answered(e.response(), b == null ? "" : b.toString(), cut, now); settle();
                if ("failed".equals(e.status()) && (b == null || b.length() == 0)) { words = "답을 만들지 못했어요 · 다시 말해 주세요"; changed(); }
                Speaker out = speaker;
                if (state != State.SPEAKING) { state = State.LISTENING; changed(); }
                else if (out != null) out.finish(() -> { if (!ended && state == State.SPEAKING) { state = State.LISTENING; changed(); } });
            }
            case ERROR -> {
                if (RealtimeVoice.harmless(e)) return;
                if (!ready) { end(kind.label + " 음성 대화를 시작하지 못했어요: " + e.text()); return; }
                words = e.text().isBlank() ? "음성 연결에 문제가 생겼어요" : e.text(); changed();
            }
            default -> {}
        }
    }
    /** The session is set: the microphone opens and the answer's speaker is ready. */
    private void ready() {
        ready = true;
        try {
            speaker = new Speaker(context);
            Microphone m = new Microphone(false); m.muted(muted);
            m.start(new Microphone.Sink() {
                @Override public void audio(byte[] pcm) { hear(pcm); }
                @Override public void error(String message) { main.post(() -> end(message)); }
            });
            mic = m;
        } catch (Exception error) { end(error.getMessage() == null ? "마이크를 열지 못했어요." : error.getMessage()); return; }
        state = State.LISTENING; words = ""; changed();
    }
    /** Microphone thread: the user's voice goes up, except the answer's own echo while it plays. */
    private void hear(byte[] pcm) {
        if (ended || muted) return;
        Speaker out = speaker;
        if (out != null && !open && out.isAudible()) {
            boolean voice = barge.hear(BargeIn.rms(pcm), out.level(), pcm.length / 32);
            byte[][] before;
            synchronized (roll) {
                if (!voice) {
                    roll.addLast(pcm); int bytes = 0; for (byte[] held : roll) bytes += held.length;
                    while (bytes > ROLL_BYTES && !roll.isEmpty()) bytes -= roll.removeFirst().length;
                    return;
                }
                before = roll.toArray(new byte[0][]); roll.clear();
            }
            // The user speaks over the answer: it stops here and there, and they are heard from just before they began.
            barge.reset(); open = true; stopped = playing; out.flush(); send(RealtimeVoice.cancel().toString());
            for (byte[] held : before) send(RealtimeVoice.append(RealtimeVoice.upsample(held)).toString());
            main.post(() -> { if (!ended) { state = State.HEARING; words = ""; changed(); } });
        }
        send(RealtimeVoice.append(RealtimeVoice.upsample(pcm)).toString());
    }

    // ── the chat ──────────────────────────────────────────────────────────
    /** Writes what is settled; a turn still waiting for its words is looked at again in a second. */
    private void settle() {
        write(log.drain(SystemClock.uptimeMillis()));
        main.removeCallbacks(settler);
        if (log.waiting()) main.postDelayed(settler, 1000);
    }
    private final Runnable settler = () -> { if (!ended) settle(); };
    private void write(java.util.List<VoiceChatLog.Line> lines) {
        if (lines.isEmpty()) return;
        String label = kind.spoken();
        Runnable task = () -> {
            for (VoiceChatLog.Line line : lines) {
                try {
                    String text = line.cut() ? line.text() + " …" : line.text();
                    long id = store.mainAssistant.spoken(line.user(), text, line.user() ? 0 : lastUser, label);
                    if (line.user()) lastUser = id;
                } catch (RuntimeException unsaved) { /* An empty or unwritable line is left out; the conversation goes on. */ }
            }
        };
        try { work.execute(task); } catch (java.util.concurrent.RejectedExecutionException closing) { new Thread(task, "gyeot-voice-chat-last").start(); }
    }
}
