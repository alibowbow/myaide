package dev.jun.gyeot;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.GeminiProtocol;
import dev.jun.gyeot.core.LiveVoiceSession;
import dev.jun.gyeot.core.RealtimeVoice;
import dev.jun.gyeot.core.VoiceLatency;
import dev.jun.gyeot.core.VoiceStartupAudio;
import java.util.ArrayDeque;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 음성 비서 on one of GPT's newest voices (GPT-Live, as the Codex apps speak it). The voice is a WebRTC call (LiveCall,
 * bridged): the assistant's own microphone goes up and the answer's sound comes back to its own speaker, so its barge-in
 * and sound routes stay as they are. GPT-Live calls no functions; it hands what needs doing to the app (delegation). The
 * hands are a text-only GPT Realtime connection (RealtimeSession in hands mode) with the assistant's instructions and
 * functions: a handed-off request goes to them, they call the functions as the voice assistant always does (the listener
 * runs them and answers through tool()), and their answer goes back into the delegation for the voice to say. What the
 * app itself asks to be said or answered goes to the hands too, and their answer is said. When GPT-Live does not open,
 * the call goes on in GPT Realtime with its own voice (a RealtimeSession), unseen by the voice assistant.
 */
final class GptLiveSession implements LiveVoiceSession {
    private static final long CONNECT_MS = 20_000, QUIET_MS = 1_500;
    private final Context context; private final AppStore store; private final String voice;
    private final GeminiProtocol.Options options; private final GeminiSession.Listener listener;
    private final Handler main = new Handler(Looper.getMainLooper());
    private volatile LiveCall call; private volatile RealtimeSession hands; private volatile LiveVoiceSession fallback;
    private volatile boolean ready, stopped, handsReady, handsDead, refreshed;
    private volatile VoiceLatency.Call latency;
    private volatile VoiceStartupAudio startupAudio = new VoiceStartupAudio(); private volatile boolean sharedStartup;
    /** Requests the voice handed off, oldest first (their delegation ids): each is answered by the hands' next answer. */
    private final ArrayDeque<String> delegations = new ArrayDeque<>();
    /** What waits for the hands to be ready. */
    private final ArrayDeque<Runnable> handsWaiting = new ArrayDeque<>();
    private final StringBuilder handsWords = new StringBuilder(), heard = new StringBuilder();
    /** After the user spoke over the answer: its late sound is not played until their words come, or a moment passes. */
    private volatile long quietUntil;
    private int handsTries;

    GptLiveSession(Context context, AppStore store, String voice, GeminiProtocol.Options options, GeminiSession.Listener listener) {
        this.context = context.getApplicationContext(); this.store = store; this.voice = voice; this.options = options; this.listener = listener;
    }
    /** Whether the voice assistant speaks with GPT-Live now: a GPT-Live voice chosen, the Codex sign-in, and GPT-Live not refused. */
    static boolean wanted(AppStore store) {
        return store.assistantRealtime() == RealtimeVoice.Kind.GPT && RealtimeVoice.live(store.pickedVoice(RealtimeVoice.Kind.GPT))
            && store.codexImages.connected() && !LiveCall.refused;
    }

    // ── connection ───────────────────────────────────────────────────────────
    @Override public void connect(String resume) {
        main.postDelayed(() -> { if (!ready && !stopped && fallback == null) fallBack("연결 시간 초과"); }, CONNECT_MS);
        startHands();
        Thread open = new Thread(this::openCall, "gyeot-gpt-live");
        open.setDaemon(true); open.start();
    }
    private void openCall() {
        if (stopped || fallback != null) return;
        String token, account;
        try { token = store.codexImages.voiceToken(null); account = store.codexImages.voiceAccount(); }
        catch (Exception error) { main.post(() -> fallBack(error.getMessage())); return; }
        startCall(token, account);
    }
    private void startCall(String token, String account) {
        String instructions = GeminiProtocol.instructions(GeminiProtocol.ASSISTANT, options) + RealtimeVoice.LIVE_HANDS;
        LiveCall[] made = new LiveCall[1];
        LiveCall c = made[0] = new LiveCall(context, new LiveCall.Listener() {
            @Override public void opened() { main.post(() -> { if (call == made[0]) becomeReady(); }); }
            @Override public void message(String json) { if (call == made[0]) event(json); }
            @Override public void failed(String why, int status) { main.post(() -> callFailed(made[0], token, account, why, status)); }
            @Override public void audio(byte[] pcm) {
                if (call != made[0] || stopped || System.nanoTime() / 1_000_000 < quietUntil) return;
                VoiceLatency.Call measured = latency; if (measured != null) measured.output();
                listener.audio(pcm);
            }
        }, true);
        if (stopped || fallback != null) { c.close(); return; }
        call = c; listener.opened();
        c.start(token, account, RealtimeVoice.liveSession(instructions, voice));
    }
    private void becomeReady() {
        if (stopped || ready || fallback != null) return;
        ready = true;
        VoiceLatency.Call measured = latency; if (measured != null) measured.setupComplete();
        LiveCall c = call;
        for (byte[] pcm : startupAudio.drain(System.nanoTime() / 1_000_000)) if (c != null) c.push(RealtimeVoice.upsample(pcm));
        boolean dropped = startupAudio.takeDropped();
        store.phoneLog("  · GPT 최신 음성(GPT-Live) 연결 · " + RealtimeVoice.voiceName(voice));
        listener.ready();
        if (dropped) listener.startupAudioDropped();
    }
    private void callFailed(LiveCall which, String token, String account, String why, int status) {
        if (stopped || which != call) return;
        // A sign-in's token turned down before the call began: refreshed once, then asked again.
        if (!ready && (status == 401 || status == 403) && !refreshed) {
            refreshed = true; which.close(); call = null;
            Thread again = new Thread(() -> {
                try { startCall(store.codexImages.voiceToken(token), store.codexImages.voiceAccount()); }
                catch (Exception error) { main.post(() -> fallBack(error.getMessage())); }
            }, "gyeot-gpt-live"); again.setDaemon(true); again.start();
            return;
        }
        if (!ready) { fallBack(why); return; }
        stopped = true; closeParts();
        listener.failed("GPT 최신 음성 연결이 끊겼어요" + (why == null || why.isBlank() ? "" : " (" + why + ")"), true);
    }
    /**
     * GPT-Live did not open: the call goes on in GPT Realtime with its own voice (a RealtimeSession takes over everything
     * from here), and GPT-Live is not tried again in this process until a voice is chosen again.
     */
    private void fallBack(String why) {
        if (stopped || fallback != null || ready) return;
        LiveCall.refused = true; closeParts();
        store.phoneLog("  · GPT 최신 음성이 열리지 않아 실시간 목소리로 이어요" + (why == null || why.isBlank() ? "" : " · " + why));
        RealtimeSession next = new RealtimeSession(store, RealtimeVoice.Kind.GPT, store.assistantVoice(RealtimeVoice.Kind.GPT), options, listener);
        next.keepStartupAudio(startupAudio);
        VoiceLatency.Call measured = latency; if (measured != null) next.latency(measured);
        fallback = next;
        next.connect("");
    }
    private void closeParts() {
        main.removeCallbacksAndMessages(null);
        LiveCall c = call; call = null; if (c != null) c.close();
        RealtimeSession h = hands; hands = null; if (h != null) h.close();
    }
    @Override public void close() {
        stopped = true; ready = false; closeParts();
        LiveVoiceSession f = fallback; if (f != null) f.close();
        if (!sharedStartup) startupAudio.clear();
    }
    @Override public void afterClosed(Runnable continuation) { LiveVoiceSession f = fallback; if (f != null) f.afterClosed(continuation); else continuation.run(); }

    // ── the hands ────────────────────────────────────────────────────────────
    private void startHands() {
        if (stopped || fallback != null) return;
        RealtimeSession[] made = new RealtimeSession[1];
        made[0] = new RealtimeSession(store, RealtimeVoice.Kind.GPT, "marin", options, new GeminiSession.Listener() {
            @Override public void ready() { main.post(() -> {
                if (hands != made[0] || stopped) return;
                handsReady = true; handsDead = false;
                while (!handsWaiting.isEmpty()) handsWaiting.removeFirst().run();
            }); }
            @Override public void tools(JSONArray calls) { if (hands == made[0]) listener.tools(calls); }
            @Override public void replied(String id, String name, JSONObject result, long millis, String scheduling) { listener.replied(id, name, result, millis, scheduling); }
            @Override public void outputText(String text) { if (hands == made[0] && text != null) synchronized (handsWords) { handsWords.append(text); } }
            @Override public void turnComplete() { main.post(() -> { if (hands == made[0]) handsAnswered(); }); }
            @Override public void failed(String reason, boolean retryable) { main.post(() -> handsFailed(made[0], reason, retryable)); }
        }, true);
        hands = made[0];
        made[0].connect("");
    }
    private void handsFailed(RealtimeSession which, String reason, boolean retryable) {
        if (stopped || which != hands) return;
        handsReady = false; hands = null;
        store.phoneLog("  · GPT 최신 음성의 앱 기능 연결 끊김 · " + (reason == null ? "" : reason));
        if (retryable && handsTries++ < 3) { main.postDelayed(this::startHands, 1500); return; }
        handsDead = true;
        // What waits for the hands is answered now, so the voice is not left waiting.
        String id; while ((id = delegations.poll()) != null) for (JSONObject piece : RealtimeVoice.liveResult(id, "지금은 앱 기능을 쓸 수 없어요" + (reason == null || reason.isBlank() ? "." : ": " + reason))) send(piece);
        handsWaiting.clear();
    }
    /** Runs a step on the hands once they are ready (main thread). */
    private void withHands(Runnable step) {
        if (stopped) return;
        if (handsReady && hands != null) step.run(); else if (!handsDead) handsWaiting.addLast(step);
    }
    /** The hands answered: their words go into the oldest delegation, or are said as the app's own words. Main thread. */
    private void handsAnswered() {
        String text; synchronized (handsWords) { text = handsWords.toString().strip(); handsWords.setLength(0); }
        String id = delegations.poll();
        if (id != null) { for (JSONObject piece : RealtimeVoice.liveResult(id, text.isEmpty() ? "(끝났어요)" : text)) send(piece); return; }
        if (!text.isEmpty()) for (JSONObject piece : RealtimeVoice.liveSays(text)) send(piece);
    }

    // ── what comes from the voice ────────────────────────────────────────────
    private void event(String json) {
        RealtimeVoice.Event e = RealtimeVoice.parseLive(json);
        switch (e.type()) {
            case READY -> main.post(this::becomeReady);
            case USER_PARTIAL -> {
                quietUntil = 0;
                String so; synchronized (heard) { heard.append(e.text()); so = heard.toString(); }
                VoiceLatency.Call measured = latency; if (measured != null) measured.input();
                if (!so.isBlank()) listener.transcriptLanguage(so, "", true);
            }
            case USER_TEXT -> {
                quietUntil = 0;
                String said; synchronized (heard) { said = e.text().isBlank() ? heard.toString() : e.text(); heard.setLength(0); }
                if (said.isBlank()) return;
                listener.transcriptLanguage(said, "", false);
                // The hands keep up with what was said, without answering it.
                main.post(() -> withHands(() -> { RealtimeSession h = hands; if (h != null) h.note("[대화] 사용자: " + said); }));
            }
            case ANSWER_PARTIAL -> listener.outputText(e.text());
            case DONE -> {
                VoiceLatency.Call measured = latency; if (measured != null) measured.turnComplete();
                listener.utteranceComplete(); listener.turnComplete();
            }
            case FUNCTION_CALL -> {
                String request = "";
                try { request = new JSONObject(e.text()).optString("request"); } catch (Exception plain) {}
                String id = e.item(), asked = request;
                main.post(() -> handOff(id, asked));
            }
            case ERROR -> {
                if (RealtimeVoice.harmless(e)) return;
                if (!ready) main.post(() -> fallBack(e.text()));
                else store.phoneLog("  · GPT 최신 음성 오류 · " + e.text());
            }
            default -> {}
        }
    }
    /** A request the voice handed off: to the hands, whose answer goes back into it. Main thread. */
    private void handOff(String id, String request) {
        if (stopped || id == null || id.isBlank()) return;
        if (handsDead) { for (JSONObject piece : RealtimeVoice.liveResult(id, "지금은 앱 기능을 쓸 수 없어요.")) send(piece); return; }
        store.phoneLog("→ GPT 최신 음성이 맡긴 일 · " + (request.length() > 80 ? request.substring(0, 80) + "…" : request));
        delegations.addLast(id);
        withHands(() -> { RealtimeSession h = hands; if (h != null) h.text("[맡긴 일] " + request); });
    }
    private void send(JSONObject message) { LiveCall c = call; if (c != null && !stopped) c.send(message.toString()); }

    // ── state ────────────────────────────────────────────────────────────────
    @Override public boolean ready() { LiveVoiceSession f = fallback; return f != null ? f.ready() : ready; }
    @Override public String resumeHandle() { return ""; }
    @Override public void resumeHandle(String handle) {}
    @Override public boolean reasoning() { LiveVoiceSession f = fallback; return f != null && f.reasoning(); }
    @Override public void latency(VoiceLatency.Call call) { latency = call; LiveVoiceSession f = fallback; if (f != null) f.latency(call); }
    @Override public boolean browserQuietOutput() { LiveVoiceSession f = fallback; return f != null && f.browserQuietOutput(); }
    @Override public int unanswered() { LiveVoiceSession f = fallback; if (f != null) return f.unanswered(); RealtimeSession h = hands; return h == null ? 0 : h.unanswered(); }
    @Override public boolean waiting(String id) { LiveVoiceSession f = fallback; if (f != null) return f.waiting(id); RealtimeSession h = hands; return h != null && h.waiting(id); }
    /** The hands (GPT) take pictures: a look at the screen goes to them. */
    @Override public boolean sees() { return true; }

    // ── the microphone ───────────────────────────────────────────────────────
    @Override public boolean audio(byte[] pcm) {
        LiveVoiceSession f = fallback; if (f != null) return f.audio(pcm);
        if (stopped) return false;
        int level = VoiceStartupAudio.rms(pcm);
        if (level >= 160) { VoiceLatency.Call measured = latency; if (measured != null) measured.voiced(level); }
        LiveCall c = call;
        for (byte[] frame : startupAudio.accept(pcm, ready, System.nanoTime() / 1_000_000)) if (c != null) { c.push(RealtimeVoice.upsample(frame)); VoiceLatency.Call measured = latency; if (measured != null) measured.pcmSent(); }
        return true;
    }
    @Override public void appSound(long from, long to) { LiveVoiceSession f = fallback; if (f != null) f.appSound(from, to); else startupAudio.appSound(from, to); }
    @Override public void keepStartupAudio(VoiceStartupAudio shared) { if (shared != null) { shared.keepLead(); startupAudio = shared; sharedStartup = true; } }
    @Override public void primeFirstTurn(String text) {}
    @Override public void clearStartupAudio() { LiveVoiceSession f = fallback; if (f != null) f.clearStartupAudio(); else startupAudio.clear(); }
    @Override public void endAudio() { LiveVoiceSession f = fallback; if (f != null) f.endAudio(); }
    /** The phone speaker's barge-in: the rest of the answer's sound is not played; GPT-Live hears the user and stops. */
    @Override public void bargeIn() {
        LiveVoiceSession f = fallback; if (f != null) { f.bargeIn(); return; }
        quietUntil = System.nanoTime() / 1_000_000 + QUIET_MS;
        VoiceLatency.Call measured = latency; if (measured != null) measured.interrupted();
        listener.interrupted();
    }

    // ── what goes to the model ──────────────────────────────────────────────
    /** The app's own turn that wants an answer: the hands answer it (with functions if need be) and the voice says it. */
    @Override public void text(String text) {
        LiveVoiceSession f = fallback; if (f != null) { f.text(text); return; }
        main.post(() -> withHands(() -> { RealtimeSession h = hands; if (h != null) h.text(text); }));
    }
    @Override public void browserReport(String text) { LiveVoiceSession f = fallback; if (f != null) f.browserReport(text); else text(text); }
    /** Context without a reply: for the hands, and for the voice on its commentary channel. */
    @Override public void note(String text) {
        LiveVoiceSession f = fallback; if (f != null) { f.note(text); return; }
        main.post(() -> {
            withHands(() -> { RealtimeSession h = hands; if (h != null) h.note(text); });
            for (JSONObject piece : RealtimeVoice.liveNotes(text)) send(piece);
        });
    }
    @Override public void tool(String id, String name, JSONObject value) { tool(id, name, value, null); }
    @Override public void tool(String id, String name, JSONObject value, String scheduling) {
        LiveVoiceSession f = fallback; if (f != null) { f.tool(id, name, value, scheduling); return; }
        RealtimeSession h = hands; if (h != null) h.tool(id, name, value, scheduling); else listener.replied(id, name, value, -1, scheduling);
    }
    @Override public void browserTool(String id, String name, JSONObject value, boolean speak) {
        LiveVoiceSession f = fallback; if (f != null) { f.browserTool(id, name, value, speak); return; }
        tool(id, name, value, speak ? null : "SILENT");
    }
    @Override public void cameraFrame(String jpegBase64) { LiveVoiceSession f = fallback; if (f != null) f.cameraFrame(jpegBase64); }
    @Override public boolean image(String jpegBase64) {
        LiveVoiceSession f = fallback; if (f != null) return f.image(jpegBase64);
        RealtimeSession h = hands; return h != null && h.image(jpegBase64);
    }
    @Override public boolean imageTurn(String jpegBase64, String app, String text) {
        LiveVoiceSession f = fallback; if (f != null) return f.imageTurn(jpegBase64, app, text);
        RealtimeSession h = hands; return h != null && h.imageTurn(jpegBase64, app, text);
    }
}
