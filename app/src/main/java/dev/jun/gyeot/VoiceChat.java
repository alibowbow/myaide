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
    /**
     * Where a spoken conversation belongs (the main chat, 작업창, the AI browser, 판서): what the model is told, the functions
     * it may call there, and where what was said is written. Every method but surface() and label() is called off the main
     * thread and may block.
     */
    interface Target {
        /** Which screen the conversation belongs to ("chat", "work", "browser", "board"); its bar shows there. */
        String surface();
        /** The screen's name for the bar and the notification (메인 대화, 작업창, AI 브라우저, 판서). */
        String label();
        /** What the voice can do here, in a sentence for the settings sheet. */
        default String about() { return "말한 내용은 " + label() + "에 기록돼요."; }
        String instructions() throws Exception;
        /** Realtime function tools (RealtimeVoice.tools), or none. */
        default org.json.JSONArray tools() { return new org.json.JSONArray(); }
        /**
         * Runs a function the model called; what it returns goes back to the model as the call's output. chat gives the user's
         * own last words (heard) and takes a result that comes later (report), for work that outlasts the call.
         */
        default String call(String name, org.json.JSONObject args, VoiceChat chat) throws Exception { return "{\"error\":\"no such function\"}"; }
        /** Writes a settled line (user's, or the answer to reply); returns its id, or 0 when it is not kept. */
        long line(boolean user, String text, long reply, String voice) throws Exception;
    }
    /** A sign-in or key this voice needs before it can talk; what to tell the user. */
    static final class Missing extends IllegalStateException { Missing(String message) { super(message); } }
    /** The last 0.8 s of the microphone while the answer plays, sent ahead of a voice that stops it. */
    private static final int ROLL_BYTES = 16000 * 2 * 8 / 10;
    private static final long CONNECT_MS = 25_000, READY_MS = 5_000;

    final RealtimeVoice.Kind kind; final String voice; final Target target;
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
    /** The bars showing this conversation (VoiceBar), told of every change on the main thread. */
    private final java.util.concurrent.CopyOnWriteArrayList<Runnable> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** The sign-in's token the server just turned down, refreshed once; whether the token is a sign-in's (not a key). */
    private String rejected; private boolean signedIn; private int attempts;
    /** The user's line the next answer follows (work thread). */
    private long lastUser;

    /** Functions the model called run here, off the socket; several at once, so "멈춰" is not held behind a slow task. */
    private final ExecutorService calls = Executors.newFixedThreadPool(3, r -> { Thread t = new Thread(r, "gyeot-voice-calls"); t.setDaemon(true); return t; });
    /** The user's last words as written down, and whether a turn of theirs still waits for its words (main thread writes). */
    private volatile String heard = ""; private volatile boolean awaitingWords;
    /** Results that came later (report), told once the user is not speaking and nothing is being said. Main thread. */
    private final ArrayDeque<String> reports = new ArrayDeque<>();
    /** While true the microphone sends nothing (판서 speaking its lesson aloud): its sound is not the user. */
    private volatile boolean held;
    VoiceChat(Context context, AppStore store, RealtimeVoice.Kind kind, String voice, Target target) {
        this.context = context.getApplicationContext(); this.store = store; this.kind = kind; this.voice = kind.voice(voice); this.target = target;
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
    void listen(Runnable listener) { listeners.addIfAbsent(listener); }
    void unlisten(Runnable listener) { listeners.remove(listener); }
    private void changed() { if (state == State.LISTENING && !reports.isEmpty()) deliver(); for (Runnable l : listeners) l.run(); store.changed(); }

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
        work.shutdown(); calls.shutdownNow();
        VoiceChatService.stop(context);
        if (store.voiceChat == this) store.voiceChat = null;
        changed();
    }

    // ── connection (work thread) ───────────────────────────────────────────
    private VoiceCredentials.Credential credential() throws Exception {
        VoiceCredentials.Credential found = VoiceCredentials.get(store, kind, rejected);
        if (found != null) return found;
        throw new Missing(kind == RealtimeVoice.Kind.GPT ? "GPT 음성 대화는 Codex 로그인(ChatGPT 구독)으로 해요. 음성 대화 설정에서 Codex로 로그인해 주세요."
            : "Grok 음성 대화는 Grok 구독 로그인이나 xAI API 키로 해요. 음성 대화 설정에서 연결해 주세요.");
    }
    private void connect() {
        if (ended) return;
        try {
            String instructions = target.instructions(); org.json.JSONArray tools = target.tools();
            VoiceCredentials.Credential credential = credential(); signedIn = credential.signIn();
            String token = credential.token();
            WebSocketClient client = new WebSocketClient(URI.create(kind.url()), new Draft_6455(), Map.of("Authorization", "Bearer " + token), 15_000) {
                @Override public void onOpen(ServerHandshake handshake) {
                    send(RealtimeVoice.session(kind, instructions, voice, tools).toString());
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
            case FUNCTION_CALL -> call(e.item(), e.status(), e.text());
            case SPEECH_STARTED -> {
                awaitingWords = true;
                // The user began to speak: an answer still playing stops (the server stops making it).
                Speaker out = speaker; if (out != null && out.isPlaying()) { stopped = playing; out.flush(); }
                log.heard(e.item(), now); words = ""; state = State.HEARING; changed();
            }
            case SPEECH_STOPPED -> { log.heard(e.item(), now); if (state == State.HEARING) { state = State.THINKING; changed(); } }
            case COMMITTED -> log.heard(e.item(), now);
            case USER_PARTIAL -> { words = "full".equals(e.status()) ? e.text() : words + e.text(); changed(); }
            case USER_TEXT -> { log.said(e.item(), e.text(), now); if (!e.text().isBlank()) { words = e.text(); heard = e.text(); } settle(); changed(); }
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
    /** A function the model called: run where the conversation belongs, its output sent back, then the model goes on. */
    private void call(String id, String name, String arguments) {
        words = "하는 중: " + name; state = State.THINKING; changed();
        try {
            calls.execute(() -> {
                String output;
                try { output = target.call(name, new org.json.JSONObject(arguments == null || arguments.isBlank() ? "{}" : arguments), this); }
                catch (Exception failed) { output = dev.jun.gyeot.core.Jsons.obj("error", failed.getMessage() == null ? "실패했어요" : failed.getMessage()).toString(); }
                if (ended) return;
                send(RealtimeVoice.functionOutput(id, output).toString()); send(RealtimeVoice.respond().toString());
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {}
    }
    /**
     * The user's own last words as written down (never the model's rephrasing), for what must rest on what they said (a
     * browser task's authority). Waits up to waitMs while a turn of theirs is still being written down. Off the main thread.
     */
    String heard(long waitMs) {
        long until = SystemClock.uptimeMillis() + waitMs;
        while (awaitingWords && !ended && SystemClock.uptimeMillis() < until) { try { Thread.sleep(100); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); break; } }
        return heard;
    }
    /** A result that came after its call returned (a task finished): told as soon as the user is not speaking. Any thread. */
    void report(String text) {
        if (text == null || text.isBlank()) return;
        main.post(() -> { if (ended) return; reports.addLast(text); deliver(); });
    }
    private void deliver() {
        if (ended || reports.isEmpty() || state == State.HEARING || state == State.SPEAKING || state == State.THINKING) return;
        String text = reports.removeFirst();
        send(RealtimeVoice.userText("[앱 알림 · 사용자가 한 말이 아님] " + text).toString());
        send(RealtimeVoice.respond("Tell the user this result from the app briefly, in speech, in their language.").toString());
    }
    /** Holds the microphone (true) while something else of this screen speaks aloud, so its sound is not taken for the user. */
    void hold(boolean value) { held = value; main.post(() -> { if (!ended) changed(); }); }
    boolean held() { return held; }
    /** Typed words in the conversation (the bar's field): the model answers them aloud. Main thread. */
    void type(String text) {
        if (ended || text == null || text.isBlank()) return;
        log.said("typed:" + SystemClock.uptimeMillis(), text, SystemClock.uptimeMillis()); settle();
        send(RealtimeVoice.userText(text).toString()); send(RealtimeVoice.respond().toString());
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
        if (ended || muted || held) return;
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
        write(log.drain(SystemClock.uptimeMillis())); awaitingWords = log.waiting();
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
                    long id = target.line(line.user(), text, line.user() ? 0 : lastUser, label);
                    if (line.user()) lastUser = id;
                } catch (Exception unsaved) { /* An empty or unwritable line is left out; the conversation goes on. */ }
            }
        };
        try { work.execute(task); } catch (java.util.concurrent.RejectedExecutionException closing) { new Thread(task, "gyeot-voice-chat-last").start(); }
    }
}
