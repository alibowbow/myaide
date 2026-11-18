package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import dev.jun.gyeot.core.GeminiProtocol;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * 판서's narration. A step's words are read by the Gemini Live voice the assistant speaks with (one quiet connection
 * that only reads aloud; a model that would shorten a passage is handed it a sentence at a time); when that cannot be
 * used or fails, by Gemini's speech model, and then by the device's own voice. Main thread only. Nothing is read during a call, a recording or while a microphone is listening.
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
    /** The first rung to try now: the chosen Live model's voice with a Gemini key and a network, the device's voice otherwise. */
    String first() { return hasKey() && Speech.online(context) ? LIVE : DEVICE; }
    /**
     * A step's words as they are handed to a Live model. One that reads a script word for word gets the whole step at
     * once. One that would say a passage in its own shorter words (2.5 Flash native audio) gets it a sentence at a time:
     * a single sentence is read as it is, and the narration stays in step with the writing.
     */
    static List<String> pieces(String text, String liveModel) {
        List<String> out = new ArrayList<>(); String whole = text == null ? "" : text.strip();
        if (whole.isEmpty()) return out;
        if (GeminiProtocol.readsAsWritten(liveModel)) { out.add(whole); return out; }
        for (String sentence : dev.jun.gyeot.core.SpeechText.sentences(whole)) if (!sentence.isBlank()) out.add(sentence.strip());
        if (out.isEmpty()) out.add(whole);
        return out;
    }
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
        private Consumer<String> done; private Runnable patience;
        /** The step's pieces not yet sent, whether one is being said, and the model's ways (set when it connects). */
        private final java.util.ArrayDeque<String> pieces = new java.util.ArrayDeque<>(); private boolean awaiting, extended; private String model = "";
        LiveVoice(Context context, AppStore store) { this.context = context; this.store = store; }
        @Override public void say(String text, Consumer<String> done) {
            if (closed) { done.accept("closed"); return; }
            int mine = ++epoch; this.done = done; pieces.clear(); awaiting = false; pieces.addAll(BoardVoice.pieces(text, session == null ? store.liveModel() : model));
            patience = () -> fail(mine, "읽는 데 너무 오래 걸렸어요.");
            main.postDelayed(patience, LIVE_PATIENCE_MS);
            try {
                if (session == null) connect(mine); else if (ready) send();
            } catch (Exception failed) { fail(mine, ""); }
        }
        private void connect(int mine) throws Exception {
            String key = store.secrets.get();
            if (key.isBlank()) throw new IllegalStateException("no key");
            GeminiProtocol.Options options = store.options(false); model = options.model; extended = GeminiProtocol.extended(model);
            GeminiSession[] self = new GeminiSession[1];
            self[0] = new GeminiSession(key, GeminiProtocol.NARRATOR, options, new GeminiSession.Listener() {
                @Override public void ready() { main.post(() -> { if (session != self[0] || closed) return; ready = true; send(); }); }
                @Override public void audio(byte[] pcm) {
                    // Played from the connection's own thread, in order; a stop flushes what is queued.
                    Speaker out = speaker; if (out != null && session == self[0]) out.play(pcm);
                }
                // A piece was said. One signal a turn: the utterance's end where turns end later (extended thinking), the turn's end elsewhere.
                @Override public void utteranceComplete() { if (extended) main.post(() -> { if (session == self[0]) heard(); }); }
                @Override public void turnComplete() { if (!extended) main.post(() -> { if (session == self[0]) heard(); }); }
                @Override public void failed(String reason, boolean retryable) { main.post(() -> { if (session == self[0]) fail(epoch, reason == null ? "" : reason); }); }
            });
            session = self[0]; speaker = new Speaker(context);
            session.connect();
        }
        private void send() {
            if (session == null || !ready || awaiting) return;
            String piece = pieces.poll(); if (piece == null) return;
            awaiting = true; session.text(GeminiProtocol.narration(piece, model));
        }
        /** The model finished a piece: the next one goes out at once (its sound queues behind), and after the last the step is done once the sound has played. */
        private void heard() {
            Consumer<String> finished = done; int mine = epoch; if (finished == null || !awaiting) return;
            awaiting = false;
            if (!pieces.isEmpty()) { send(); return; }
            Speaker out = speaker; if (out == null) return;
            out.finish(() -> main.post(() -> { if (mine != epoch || done != finished) return; done = null; main.removeCallbacks(patience); finished.accept(null); }));
        }
        private void fail(int mine, String why) {
            if (mine != epoch) return;
            Consumer<String> failed = done; done = null; pieces.clear(); awaiting = false; main.removeCallbacks(patience);
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
        @Override public void stop() { epoch++; done = null; pieces.clear(); awaiting = false; if (patience != null) main.removeCallbacks(patience); drop(); }
        @Override public void close() { stop(); closed = true; }
    }
}
