package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.GeminiProtocol;
import java.util.function.Consumer;

/**
 * 판서's narration. A step's words are read by the Gemini Live voice the assistant speaks with (one quiet connection
 * that only reads aloud); when that cannot be used or fails, by Gemini's speech model, and then by the device's own
 * voice. Main thread only. Nothing is read during a call, a recording or while a microphone is listening.
 */
final class BoardVoice {
    /** Makes the voice for a rung of the ladder (LIVE, SPEECH or DEVICE); tests pass their own. */
    interface Voices { Speech.Voice create(String kind); }
    static final String LIVE = "live", SPEECH = "speech", DEVICE = "device";
    /** A step that Live has not finished reading after this long is given to the next voice. */
    static final long LIVE_PATIENCE_MS = 45_000;
    private final Context context; private final AppStore store; private final Voices voices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Speech.Voice voice; private String kind = ""; private int epoch; private boolean focused, closed; private AudioFocusRequest focus;
    BoardVoice(Context context, AppStore store) { this(context, store, null); }
    BoardVoice(Context context, AppStore store, Voices voices) {
        this.context = context.getApplicationContext(); this.store = store;
        this.voices = voices != null ? voices : kind -> LIVE.equals(kind) ? new LiveVoice(this.context, store) : SPEECH.equals(kind) ? new Speech.GeminiVoice(this.context, store) : new Speech.DeviceVoice(this.context);
    }
    /** Whether anything would hear the reading as speech to answer (a call, a recording, an open microphone). */
    boolean blocked() { return store.voiceActive || store.recording || ComposerDictation.listening() || store.talk != null && store.talk.listening(); }
    /** The first rung to try now: Live with a Gemini key and a network, the device's voice otherwise. */
    String first() { return hasKey() && Speech.online(context) ? LIVE : DEVICE; }
    private boolean hasKey() { try { return store.secrets.hasKey(); } catch (RuntimeException unknown) { return false; } }
    private static String next(String kind) { return LIVE.equals(kind) ? SPEECH : SPEECH.equals(kind) ? DEVICE : ""; }
    String kind() { return kind; }

    /**
     * Reads text aloud; done gets null when it was heard, or why it was not (after every voice was tried). A voice that
     * fails is replaced by the next one for the rest of the lesson.
     */
    void say(String text, Consumer<String> done) {
        if (closed || text == null || text.isBlank()) { done.accept(null); return; }
        if (blocked()) { done.accept("통화나 녹음 중에는 소리 내어 읽지 않아요."); return; }
        if (voice == null) use(first());
        if (!focus()) { done.accept("다른 소리가 나고 있어서 읽지 못했어요."); return; }
        attempt(text, done, ++epoch);
    }
    private void attempt(String text, Consumer<String> done, int mine) {
        Speech.Voice now = voice; String tried = kind;
        now.say(text, problem -> main.post(() -> {
            if (mine != epoch || closed) return;
            if (problem == null) { done.accept(null); return; }
            String fallback = next(tried);
            // The key's speech model needs the same key and network Live did; without them only the device voice is left.
            if (SPEECH.equals(fallback) && !(hasKey() && Speech.online(context))) fallback = DEVICE;
            if (fallback.isEmpty()) { done.accept(problem.isBlank() ? "소리 내어 읽지 못했어요." : problem); return; }
            use(fallback); attempt(text, done, mine);
        }));
    }
    private void use(String wanted) {
        if (voice != null) voice.close();
        voice = voices.create(wanted); kind = wanted;
    }
    /** Stops what is being read; its done is not called. */
    void stop() { epoch++; if (voice != null) voice.stop(); release(); }
    void close() { stop(); closed = true; if (voice != null) voice.close(); voice = null; kind = ""; }

    private boolean focus() {
        if (focused) return true;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return true;
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(Speech.ATTRIBUTES)
            .setOnAudioFocusChangeListener(change -> { if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) lost(); }, main).build();
        focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focused;
    }
    /** Another app or a call took the sound: the lesson hears it as a stop (set by BoardTeacher). */
    Runnable onLost = () -> {};
    private void lost() { stop(); onLost.run(); }
    private void release() {
        if (!focused) return;
        focused = false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
    }

    /** The Gemini Live connection that reads scripts aloud: opened on the first step, kept for the lesson. */
    static final class LiveVoice implements Speech.Voice {
        private final Context context; private final AppStore store;
        private final Handler main = new Handler(Looper.getMainLooper());
        private GeminiSession session; private Speaker speaker; private boolean ready, closed; private int epoch;
        private String waiting; private Consumer<String> done; private Runnable patience;
        LiveVoice(Context context, AppStore store) { this.context = context; this.store = store; }
        @Override public void say(String text, Consumer<String> done) {
            if (closed) { done.accept("closed"); return; }
            int mine = ++epoch; this.done = done; waiting = text;
            patience = () -> fail(mine, "읽는 데 너무 오래 걸렸어요.");
            main.postDelayed(patience, LIVE_PATIENCE_MS);
            try {
                if (session == null) connect(mine); else if (ready) send();
            } catch (Exception failed) { fail(mine, ""); }
        }
        private void connect(int mine) throws Exception {
            String key = store.secrets.get();
            if (key.isBlank()) throw new IllegalStateException("no key");
            GeminiProtocol.Options options = store.options(false);
            GeminiSession[] self = new GeminiSession[1];
            self[0] = new GeminiSession(key, GeminiProtocol.NARRATOR, options, new GeminiSession.Listener() {
                @Override public void ready() { main.post(() -> { if (session != self[0] || closed) return; ready = true; send(); }); }
                @Override public void audio(byte[] pcm) {
                    // Played from the connection's own thread, in order; a stop flushes what is queued.
                    Speaker out = speaker; if (out != null && session == self[0]) out.play(pcm);
                }
                // The step was said: utteranceComplete on every model, turnComplete as well where the turn ends with it.
                @Override public void utteranceComplete() { main.post(() -> { if (session == self[0]) heard(); }); }
                @Override public void turnComplete() { main.post(() -> { if (session == self[0]) heard(); }); }
                @Override public void failed(String reason, boolean retryable) { main.post(() -> { if (session == self[0]) fail(epoch, reason == null ? "" : reason); }); }
            });
            session = self[0]; speaker = new Speaker(context);
            session.connect();
        }
        private void send() {
            String text = waiting; if (text == null || session == null) return;
            waiting = null; session.text(text);
        }
        /** The model finished the step: done once the queued sound has played. */
        private void heard() {
            Consumer<String> finished = done; int mine = epoch; if (finished == null || waiting != null) return;
            Speaker out = speaker; if (out == null) return;
            out.finish(() -> main.post(() -> { if (mine != epoch || done != finished) return; done = null; main.removeCallbacks(patience); finished.accept(null); }));
        }
        private void fail(int mine, String why) {
            if (mine != epoch) return;
            Consumer<String> failed = done; done = null; waiting = null; main.removeCallbacks(patience);
            drop();
            if (failed != null) failed.accept(why);
        }
        private void drop() {
            GeminiSession old = session; session = null; ready = false;
            if (old != null) old.close();
            Speaker out = speaker; speaker = null;
            if (out != null) { out.flush(); out.close(); }
        }
        /** Silence now. The connection would go on speaking the step, so it is closed; the next step opens a new one. */
        @Override public void stop() { epoch++; done = null; waiting = null; if (patience != null) main.removeCallbacks(patience); drop(); }
        @Override public void close() { stop(); closed = true; }
    }
}
