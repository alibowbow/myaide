package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.jun.gyeot.core.BargeIn;
import dev.jun.gyeot.core.RealtimeVoice;
import dev.jun.gyeot.core.ScreenView;
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
 * both said is written into the main chat as it is settled (core/VoiceChatLog), so typing carries on from it. GPT's newest
 * voices run on GPT-Live, a WebRTC call (LiveCall) made with the Codex sign-in. Asked to, it looks at the screen once
 * (VoiceScreen) when 화면 보기 is on. One at a time (AppStore.voiceChat); state is read and changed on the main thread.
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
        /** The function that hands a request to this screen's own AI (GPT-Live delegates to it); "" when there is none. */
        default String handOff() { return ""; }
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
    /** The voice as chosen: one of GPT-Live's newest (the conversation runs on GPT-Live), else the same as voice. */
    final String chosen;
    /** On GPT-Live now; turned off for the process when GPT-Live does not open with this sign-in (then GPT Realtime). */
    private volatile boolean live;
    /** GPT-Live's call. */
    private volatile LiveCall call;
    /** GPT-Live's turns as this app counts them: the user's, and the answers (audio, words and the log follow them). */
    private int liveUser; private volatile int liveAnswer;
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
    /** The realtime conversation's sound route, as a call's (GPT-Live's call keeps its own). */
    private CallRoute route;
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
        this.chosen = voice == null ? "" : voice;
    }
    State state() { return state; }
    /** The words heard or spoken now: the user's as they are written down, then the answer's. */
    String words() { return words; }
    /** Whose the shown words are: the user's (MINE), the AI's (THEIRS), or a note of the app's (NOTE). */
    static final int NOTE = 0, MINE = 1, THEIRS = 2;
    private volatile int wordsBy = NOTE;
    int wordsBy() { return wordsBy; }
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
        deadline();
        changed();
    }
    /** A connection that has not begun in time: GPT-Live goes on in GPT Realtime, anything else ends. */
    private void deadline() {
        main.postDelayed(() -> {
            if (ready || ended) return;
            if (live) { LiveCall c = call; String missing = c == null ? "" : c.missing(); liveFailed("25초 안에 연결되지 않았어요" + (missing.isEmpty() ? "" : " · 아직 안 된 것: " + missing) + (lastStage.isEmpty() ? "" : " · 마지막 단계: " + lastStage)); }
            else end(kind.label + " 음성 연결 시간이 초과됐어요. 네트워크와 로그인을 확인해 주세요.");
        }, CONNECT_MS);
    }
    void toggleMute() {
        if (ended) return;
        muted = !muted; Microphone m = mic; if (m != null) m.muted(muted);
        LiveCall c = call; if (c != null) c.mute(muted || held);
        changed();
    }
    /** Ends the conversation (the user's hand when failure is empty); what was said and settled is written down. */
    void end(String why) {
        if (ended) return;
        ended = true; state = State.ENDED; failure = why == null ? "" : why;
        main.removeCallbacksAndMessages(null);
        Microphone m = mic; mic = null; if (m != null) m.close();
        Speaker s = speaker; speaker = null; if (s != null) s.close();
        CallRoute r = route; route = null; if (r != null) r.close();
        WebSocketClient w = socket; socket = null; if (w != null) shutdown(w);
        closeLive();
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
            String handOff = target.handOff();
            // GPT's newest voices: a GPT-Live call with the Codex sign-in (the ChatGPT plan); no other key opens it.
            boolean onLive = live = kind == RealtimeVoice.Kind.GPT && RealtimeVoice.live(chosen) && store.codexImages.connected();
            String instructions = target.instructions() + RealtimeVoice.screenNote(onLive);
            if (onLive) {
                connectLive(instructions + (handOff.isEmpty() ? "" : "\n\nOn this voice, hand anything the instructions above say to give to " + handOff
                    + " to the app instead (delegation): say what to do in the user's words; the app does it and gives you the result to tell."));
                return;
            }
            org.json.JSONArray tools = target.tools(); tools.put(RealtimeVoice.screenTool());
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
        catch (Exception error) {
            if (live) { String why = error.getMessage(); main.post(() -> { if (!ended) liveFailed(why); }); return; }
            String message = error.getMessage(); main.post(() -> end(kind.label + " 음성 대화를 시작하지 못했어요" + (message == null || message.isBlank() ? "." : ": " + message)));
        }
    }
    /** GPT-Live: a WebRTC call (LiveCall) made with the Codex sign-in; its offer goes up with this session. */
    private void connectLive(String instructions) throws Exception {
        String token = store.codexImages.voiceToken(rejected), account = store.codexImages.voiceAccount();
        LiveCall[] made = new LiveCall[1];
        LiveCall c = made[0] = new LiveCall(context, new LiveCall.Listener() {
            @Override public void opened() { main.post(() -> { if (!ended && call == made[0] && !ready) ready(); }); }
            @Override public void message(String json) { if (call == made[0]) receiveLive(json); }
            @Override public void failed(String why, int status) { main.post(() -> liveFailed(made[0], token, why, status)); }
            @Override public void stage(String text) { if (call == made[0]) { lastStage = text; store.phoneLog("  · GPT 최신 음성 · " + text); } }
        });
        store.phoneLog("→ GPT 최신 음성 연결 · " + RealtimeVoice.voiceName(chosen));
        if (ended) { c.close(); return; }
        call = c; c.mute(muted || held);
        c.start(token, account, RealtimeVoice.liveSession(instructions, chosen));
    }
    /** GPT-Live's call ended or never opened: before the conversation began it goes on in GPT Realtime (a token turned down is refreshed once first). */
    private void liveFailed(LiveCall which, String token, String why, int status) {
        if (ended || which != call) return;
        if (!ready && (status == 401 || status == 403) && attempts++ == 0) { rejected = token; closeLive(); work.execute(this::connect); return; }
        if (!ready) { liveFailed(why); return; }
        end("GPT 최신 음성 연결이 끊겨 음성 대화를 마쳤어요" + (why == null || why.isBlank() ? "." : " · " + why));
    }
    private void closeLive() { LiveCall c = call; call = null; if (c != null) c.close(); }
    private void closed(WebSocketClient which, String token, String reason) {
        if (ended || which != socket) return;
        int status = RealtimeVoice.refused((reason == null ? "" : reason) + " " + lastError);
        // A sign-in's token turned down before the conversation began: refreshed once, then asked again.
        if (!ready && signedIn && (status == 401 || status == 403) && attempts++ == 0) { rejected = token; socket = null; work.execute(this::connect); return; }
        needsSetup = !ready && (status == 401 || status == 403);
        end(ready ? "연결이 끊겨 음성 대화를 마쳤어요." : RealtimeVoice.refusal(kind, status, signedIn));
    }
    /**
     * GPT-Live did not open: the conversation ends there and says why, with no other voice in its place (the user asked
     * for the chosen voice or none). The settings sheet opens with the reason and the connection test.
     */
    private void liveFailed(String why) {
        if (ended) return;
        String reason = "GPT 최신 음성(" + RealtimeVoice.voiceName(chosen) + ")에 연결하지 못했어요" + (why == null || why.isBlank() ? "." : " · " + why);
        store.phoneLog("  ✗ " + reason);
        needsSetup = true;
        end(reason);
    }
    /** An event for the server: GPT-Live's call, or Realtime's socket. */
    private void send(String text) {
        if (live) { LiveCall c = call; if (c != null) c.send(text); return; }
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
        if (ended || live) return;
        RealtimeVoice.Event e = RealtimeVoice.parse(text);
        if (e.type() == RealtimeVoice.Type.AUDIO) { audio(e); return; }
        if (e.type() == RealtimeVoice.Type.OTHER) return;
        main.post(() -> { if (!ended) event(e); });
    }
    /** A GPT-Live event (LiveCall gives those of the source it follows; the audio plays through the call itself). */
    private void receiveLive(String text) {
        if (ended || !live) return;
        RealtimeVoice.Event e = RealtimeVoice.parseLive(text);
        // READY alone is not a call that is up: LiveCall says so (opened) once the SDP applied, WebRTC connected and READY came.
        if (e.type() == RealtimeVoice.Type.OTHER || e.type() == RealtimeVoice.Type.AUDIO || e.type() == RealtimeVoice.Type.READY) return;
        main.post(() -> { if (!ended && live) event(fromLive(e)); });
    }
    /**
     * A GPT-Live event in the shape the conversation follows: its turns get the ids this app gives them (user words and
     * answers are logged in order), its handed-off requests go to the screen's own hand-off function.
     */
    private RealtimeVoice.Event fromLive(RealtimeVoice.Event e) {
        long now = SystemClock.uptimeMillis();
        switch (e.type()) {
            case USER_PARTIAL -> { if (!awaitingWords) { awaitingWords = true; log.heard("lu" + liveUser, now); if (state != State.SPEAKING) { state = State.HEARING; } } return new RealtimeVoice.Event(e.type(), "lu" + liveUser, "", e.text(), null, "full".equals(e.status()) ? "full" : ""); }
            case USER_TEXT -> {
                String id = "lu" + liveUser++; log.heard(id, now);
                // An answer the user cut off may never end on its own: the next one is a new answer.
                if (("live" + liveAnswer).equals(stopped)) liveAnswer++;
                if (state == State.HEARING) state = State.THINKING;
                return new RealtimeVoice.Event(e.type(), id, "", e.text(), null, "");
            }
            case ANSWER_PARTIAL -> { return new RealtimeVoice.Event(e.type(), "", "live" + liveAnswer, e.text(), null, ""); }
            case DONE -> {
                String id = "live" + liveAnswer++;
                if (!e.text().isBlank()) answers.put(id, new StringBuilder(e.text()));
                return new RealtimeVoice.Event(e.type(), "", id, "", null, e.status());
            }
            case FUNCTION_CALL -> {
                String request = "";
                try { request = new org.json.JSONObject(e.text()).optString("request"); } catch (Exception plain) {}
                if (RealtimeVoice.screenRequest(request))
                    return new RealtimeVoice.Event(e.type(), e.item(), "", dev.jun.gyeot.core.Jsons.obj("question", request.replace(RealtimeVoice.SCREEN + ":", "").strip()).toString(), null, RealtimeVoice.SCREEN);
                String handOff = target.handOff();
                return new RealtimeVoice.Event(e.type(), e.item(), "", e.text(), null, handOff.isEmpty() ? RealtimeVoice.DELEGATE : handOff);
            }
            default -> { return e; }
        }
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
                log.heard(e.item(), now); wordsBy = NOTE; words = ""; state = State.HEARING; changed();
            }
            case SPEECH_STOPPED -> { log.heard(e.item(), now); if (state == State.HEARING) { state = State.THINKING; changed(); } }
            case COMMITTED -> log.heard(e.item(), now);
            case USER_PARTIAL -> { words = "full".equals(e.status()) || wordsBy != MINE ? e.text() : words + e.text(); wordsBy = MINE; changed(); }
            case USER_TEXT -> { log.said(e.item(), e.text(), now); if (!e.text().isBlank()) { words = e.text(); wordsBy = MINE; heard = e.text(); } settle(); changed(); }
            case ANSWER_STARTED -> answers.put(e.response(), new StringBuilder());
            case ANSWER_PARTIAL -> {
                StringBuilder b = answers.computeIfAbsent(e.response(), k -> new StringBuilder()); b.append(e.text());
                if (live && state != State.SPEAKING) state = State.SPEAKING;
                if (!e.response().equals(stopped)) { words = b.toString(); wordsBy = THEIRS; changed(); }
            }
            case ANSWER_TEXT -> { if (!e.text().isBlank()) answers.put(e.response(), new StringBuilder(e.text())); }
            case DONE -> {
                StringBuilder b = answers.remove(e.response());
                boolean cut = e.response().equals(stopped) || "cancelled".equals(e.status()) || "incomplete".equals(e.status());
                log.answered(e.response(), b == null ? "" : b.toString(), cut, now); settle();
                if ("failed".equals(e.status()) && (b == null || b.length() == 0)) { wordsBy = NOTE; words = "답을 만들지 못했어요 · 다시 말해 주세요"; changed(); }
                Speaker out = speaker;
                if (state != State.SPEAKING) { state = State.LISTENING; changed(); }
                else if (live) quiet();
                else if (out != null) out.finish(() -> { if (!ended && state == State.SPEAKING) { state = State.LISTENING; changed(); } });
            }
            case ERROR -> {
                if (RealtimeVoice.harmless(e)) return;
                if (live && !ready) { liveFailed("서버 오류 · " + RealtimeVoice.masked(e.text())); return; }
                if (!ready) { end(kind.label + " 음성 대화를 시작하지 못했어요: " + e.text()); return; }
                wordsBy = NOTE; words = e.text().isBlank() ? "음성 연결에 문제가 생겼어요" : e.text(); changed();
            }
            default -> {}
        }
    }
    /** A function the model called: run where the conversation belongs, its output sent back, then the model goes on. */
    private void call(String id, String name, String arguments) {
        wordsBy = NOTE; words = RealtimeVoice.SCREEN.equals(name) ? "화면을 보는 중…" : "하는 중: " + name; state = State.THINKING; changed();
        try {
            calls.execute(() -> {
                String output;
                try {
                    org.json.JSONObject args = new org.json.JSONObject(arguments == null || arguments.isBlank() ? "{}" : arguments);
                    output = RealtimeVoice.SCREEN.equals(name) ? look(id, args.optString("question")) : target.call(name, args, this);
                }
                catch (Exception failed) { output = dev.jun.gyeot.core.Jsons.obj("error", failed.getMessage() == null ? "실패했어요" : failed.getMessage()).toString(); }
                if (ended || output == null) return;
                if (live) { for (org.json.JSONObject piece : RealtimeVoice.liveResult(id, said(output))) send(piece.toString()); return; }
                send(RealtimeVoice.functionOutput(id, output).toString()); send(RealtimeVoice.respond().toString());
            });
        } catch (java.util.concurrent.RejectedExecutionException closing) {}
    }
    /**
     * 화면 보기, asked by the user: one look at the screen. GPT Realtime sees the picture itself (it goes in before the call's
     * output, and the answer follows: null is returned, all is sent); a voice that takes no pictures gets it in words.
     */
    private String look(String id, String question) {
        VoiceScreen.Shot shot;
        try { shot = VoiceScreen.take(store, context); }
        catch (Exception failed) { return dev.jun.gyeot.core.Jsons.obj("ok", false, "error", failed.getMessage() == null ? "화면을 가져오지 못했어요." : failed.getMessage()).toString(); }
        if (ended) return null;
        if (kind == RealtimeVoice.Kind.GPT && !live) {
            send(RealtimeVoice.userImage(shot.jpeg(), null).toString());
            send(RealtimeVoice.functionOutput(id, ScreenView.result(shot.app(), shot.text()).toString()).toString());
            send(RealtimeVoice.respond().toString());
            return null;
        }
        main.post(() -> { if (!ended) { wordsBy = NOTE; words = "화면을 읽는 중…"; changed(); } });
        String asked = question == null || question.isBlank() ? heard : question;
        return VoiceScreen.told(shot, VoiceScreen.describe(store, shot, asked)).toString();
    }
    /** A function's output as words to hand back to GPT-Live: its result, else its note or error. */
    private static String said(String output) {
        try { org.json.JSONObject o = new org.json.JSONObject(output); for (String key : new String[]{"result", "note", "error", "board"}) if (!o.optString(key).isBlank()) return (key.equals("error") ? "못 했어요: " : "") + o.optString(key); }
        catch (Exception plain) { return output; }
        return output;
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
        if (live) { for (org.json.JSONObject piece : RealtimeVoice.liveSays(text)) send(piece.toString()); return; }
        send(RealtimeVoice.userText("[앱 알림 · 사용자가 한 말이 아님] " + text).toString());
        send(RealtimeVoice.respond("Tell the user this result from the app briefly, in speech, in their language.").toString());
    }
    /** Holds the microphone (true) while something else of this screen speaks aloud, so its sound is not taken for the user. */
    void hold(boolean value) { held = value; LiveCall c = call; if (c != null) c.mute(muted || value); main.post(() -> { if (!ended) changed(); }); }
    boolean held() { return held; }
    /** Typed words in the conversation (the bar's field): the model answers them aloud. Main thread. */
    void type(String text) {
        if (ended || text == null || text.isBlank()) return;
        log.said("typed:" + SystemClock.uptimeMillis(), text, SystemClock.uptimeMillis()); settle();
        // GPT-Live takes typed words as context it speaks from (as Codex sends them).
        if (live) { for (org.json.JSONObject piece : RealtimeVoice.liveSays("[사용자가 글로 입력함] " + text)) send(piece.toString()); return; }
        send(RealtimeVoice.userText(text).toString()); send(RealtimeVoice.respond().toString());
    }
    /** The session is set: the microphone opens and the answer's speaker is ready (GPT-Live's call has its own). */
    private void ready() {
        ready = true;
        if (live) { state = State.LISTENING; wordsBy = NOTE; words = ""; changed(); return; }
        try {
            // As a call: the phone's echo cancelling takes the answer out of the microphone, so speaking over it works at an ordinary voice.
            if (route == null) { route = new CallRoute(context, main); route.begin(); }
            speaker = new Speaker(context, true);
            Microphone m = new Microphone(false); m.muted(muted);
            m.start(new Microphone.Sink() {
                @Override public void audio(byte[] pcm) { hear(pcm); }
                @Override public void error(String message) { main.post(() -> end(message)); }
            });
            mic = m;
        } catch (Exception error) { end(error.getMessage() == null ? "마이크를 열지 못했어요." : error.getMessage()); return; }
        state = State.LISTENING; wordsBy = NOTE; words = ""; changed();
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
            // GPT-Live listens while it speaks and stops by itself; Realtime is told to stop.
            barge.reset(); open = true; stopped = playing; out.flush(); send(RealtimeVoice.cancel().toString());
            for (byte[] held : before) send(up(held));
            main.post(() -> { if (!ended) { state = State.HEARING; wordsBy = NOTE; words = ""; changed(); } });
        }
        send(up(pcm));
    }

    /** A microphone frame (16 kHz) as Realtime takes it: 24 kHz PCM. */
    private String up(byte[] pcm16k) { return RealtimeVoice.append(RealtimeVoice.upsample(pcm16k)).toString(); }
    /** GPT-Live finished an answer: the bar shows it speaking until the call has gone quiet. */
    private void quiet() {
        main.postDelayed(() -> {
            if (ended || state != State.SPEAKING) return;
            LiveCall c = call;
            if (c != null && c.playing()) { quiet(); return; }
            state = State.LISTENING; changed();
        }, 300);
    }
    /** Which voice speaks now, for the bar: GPT-Live's chosen one, or the Realtime voice. */
    String speaking() { return live ? RealtimeVoice.voiceName(chosen) : voice; }
    /** The last step GPT-Live's call reached, for why it did not open. */
    private volatile String lastStage = "";

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
