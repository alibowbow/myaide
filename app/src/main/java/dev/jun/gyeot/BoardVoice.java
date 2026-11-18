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
 * 판서's narration. A step's words are read by a Gemini Live voice (quiet connections that only read aloud; a model
 * that would shorten a passage is handed it a sentence at a time); when that cannot be used or fails, by the device's
 * own voice. The voice and the Live model are 판서's own choices (boardVoice,
 * boardLiveModel), which start as the assistant's. Main thread only. Nothing is read during a call, a recording or
 * while a microphone is listening.
 */
final class BoardVoice {
    /** Makes the voice for a rung of the ladder (LIVE or DEVICE); tests pass their own. */
    interface Voices { Speech.Voice create(String kind); }
    static final String LIVE = "live", DEVICE = "device";
    /** A step that Live has not finished reading after this long is given to the next voice. */
    static final long LIVE_PATIENCE_MS = 45_000;
    private final Context context; private final AppStore store; private final Voices voices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Speech.Voice voice; private String kind = ""; private int epoch; private boolean focused, closed; private AudioFocusRequest focus;
    BoardVoice(Context context, AppStore store) { this(context, store, null); }
    BoardVoice(Context context, AppStore store, Voices voices) {
        this.context = context.getApplicationContext(); this.store = store;
        this.voices = voices != null ? voices : kind -> LIVE.equals(kind) ? new LiveVoice(this.context, store) : new Speech.DeviceVoice(this.context);
    }

    /** 판서's own voice: the one chosen in its menu, the assistant's until one is. "" is the model's default voice. */
    static String voice(AppStore store) { return store.settings.contains("boardVoice") ? store.settings.getString("boardVoice", "") : store.voice(); }
    /** 판서's own Live model: the one chosen in its menu, the assistant's until one is. */
    static String liveModel(AppStore store) { return GeminiProtocol.liveModel(store.settings.contains("boardLiveModel") ? store.settings.getString("boardLiveModel", "") : store.liveModel()); }

    /** Whether anything would hear the reading as speech to answer (a call, a recording, an open microphone). */
    boolean blocked() { return store.voiceActive || store.recording || ComposerDictation.listening() || store.talk != null && store.talk.listening(); }
    /** The first rung to try now: the chosen Live model's voice with a Gemini key and a network, the device's voice otherwise. */
    String first() { return hasKey() && Speech.online(context) ? LIVE : DEVICE; }
    private boolean hasKey() { try { return store.secrets.hasKey(); } catch (RuntimeException unknown) { return false; } }
    /** After Live comes the device's own voice. Gemini's speech model is not a rung: it has a small quota of its own. */
    private static String next(String kind) { return LIVE.equals(kind) ? DEVICE : ""; }
    String kind() { return kind; }
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
            if (fallback.isEmpty()) { done.accept(problem.isBlank() ? "소리 내어 읽지 못했어요." : problem); return; }
            use(fallback); attempt(text, done, mine);
        }));
    }
    private void use(String wanted) {
        if (voice != null) voice.close();
        voice = voices.create(wanted); kind = wanted;
    }
    /** A lesson is on its way: the Live voice connects now, so its first words do not wait for that. */
    void warm() { if (closed || blocked() || !LIVE.equals(voice == null ? first() : kind)) return; if (voice == null) use(LIVE); if (voice instanceof Ahead ahead) ahead.warm(); }
    /** What will be said after what is being said: a voice that can, gets it ready, so one step runs into the next without a wait. */
    void prepare(String text) { if (!closed && text != null && !text.isBlank() && voice instanceof Ahead ahead && !blocked()) ahead.prepare(text); }
    /** A voice that can work ahead of what it is saying. */
    interface Ahead { void warm(); void prepare(String text); }
    /** Stops what is being read; its done is not called. */
    void stop() { epoch++; if (voice != null) voice.stop(); release(); }
    /** The voice or the model was changed: the next words are read by a voice made anew. */
    void reset() { stop(); if (voice != null) voice.close(); voice = null; kind = ""; }
    void close() { reset(); closed = true; }

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

    /**
     * The Gemini Live reading. What is to be said is cut into pieces (the whole step, or its sentences for a model that
     * shortens passages), and the pieces are made ahead of being heard: each connection reads its next piece as soon as
     * it has finished one, their sound is kept in order, and a piece plays the moment the one before it has. With two
     * connections the next sentence is being made while this one sounds, so sentences follow one another without the
     * model's thinking time between them; and a step handed over early (prepare) is ready when its turn comes.
     */
    static final class LiveVoice implements Speech.Voice, Ahead {
        /** One reading connection, as this class needs it; GeminiSession behind it, or a test's own. */
        interface Line { void say(String piece); void close(); }
        /** What a line tells: it can take a piece, sound of its piece, its piece is finished, it broke. Called on any thread. */
        interface Events { void ready(); void audio(byte[] pcm); void finished(); void failed(String why); }
        interface Lines { Line open(String model, String voice, Events events) throws Exception; }
        /** Where the sound goes; Speaker, or a test's own. */
        interface Out { void play(byte[] pcm); void finish(Runnable done); void flush(); void close(); }
        private static final class Piece { final String text; final int step; final List<byte[]> sound = new ArrayList<>(); boolean sent, complete, dropped; int played; Piece(String text, int step) { this.text = text; this.step = step; } }
        private final class Seat implements Events {
            Line line; boolean ready, broken; Piece piece;
            @Override public void ready() { main.post(() -> { if (seats.contains(this) && !broken) { ready = true; pump(); } }); }
            @Override public void audio(byte[] pcm) { main.post(() -> { if (seats.contains(this) && piece != null && !piece.dropped) { piece.sound.add(pcm); drain(); } }); }
            @Override public void finished() { main.post(() -> { if (!seats.contains(this) || piece == null) return; piece.complete = true; piece = null; pump(); drain(); }); }
            @Override public void failed(String why) { main.post(() -> { if (seats.contains(this)) broke(why == null ? "" : why); }); }
        }
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Lines lines; private final java.util.function.Supplier<Out> outs; private final java.util.function.Supplier<String> model, voice;
        private final List<Seat> seats = new ArrayList<>(); private final List<Piece> queue = new ArrayList<>();
        private Out out; private boolean closed; private int steps, playing = -1, ahead = -1, epoch; private String aheadText = "", using = "";
        private Consumer<String> done; private Runnable patience;

        LiveVoice(Context context, AppStore store) {
            this((model, voice, events) -> {
                String key = store.secrets.get(); if (key.isBlank()) throw new IllegalStateException("no key");
                GeminiProtocol.Options options = store.options(false); options.model = model; options.voice = voice;
                boolean extended = GeminiProtocol.extended(model); GeminiSession[] self = new GeminiSession[1];
                self[0] = new GeminiSession(key, GeminiProtocol.NARRATOR, options, new GeminiSession.Listener() {
                    @Override public void ready() { events.ready(); }
                    @Override public void audio(byte[] pcm) { events.audio(pcm); }
                    // A piece was said. One signal a turn: the utterance's end where turns end later (extended thinking), the turn's end elsewhere.
                    @Override public void utteranceComplete() { if (extended) events.finished(); }
                    @Override public void turnComplete() { if (!extended) events.finished(); }
                    @Override public void failed(String reason, boolean retryable) { events.failed(reason); }
                });
                self[0].connect();
                return new Line() {
                    @Override public void say(String piece) { self[0].text(GeminiProtocol.narration(piece, model)); }
                    @Override public void close() { self[0].close(); }
                };
            }, () -> { Speaker speaker = new Speaker(context); return new Out() {
                @Override public void play(byte[] pcm) { speaker.play(pcm); }
                @Override public void finish(Runnable after) { speaker.finish(after); }
                @Override public void flush() { speaker.flush(); }
                @Override public void close() { speaker.close(); }
            }; }, () -> liveModel(store), () -> BoardVoice.voice(store));
        }
        LiveVoice(Lines lines, java.util.function.Supplier<Out> outs, java.util.function.Supplier<String> model, java.util.function.Supplier<String> voice) { this.lines = lines; this.outs = outs; this.model = model; this.voice = voice; }

        /** How many connections read at once: two where pieces are sentences (one makes the next while one sounds), one otherwise. */
        static int seats(String model) { return GeminiProtocol.readsAsWritten(model) ? 1 : 2; }
        private boolean open() {
            if (closed) return false;
            if (using.isEmpty()) using = model.get();
            try { while (seats.size() < seats(using)) { Seat seat = new Seat(); seats.add(seat); seat.line = lines.open(using, voice.get(), seat); } }
            catch (Exception failed) { return false; }
            if (out == null) out = outs.get();
            return true;
        }
        @Override public void warm() { open(); }
        private int add(String text, int at) {
            int step = ++steps; int index = at;
            for (String piece : pieces(text, using.isEmpty() ? model.get() : using)) queue.add(Math.min(index++, queue.size()), new Piece(piece, step));
            return step;
        }
        private void drop(int step) {
            for (int i = queue.size() - 1; i >= 0; i--) { Piece piece = queue.get(i); if (piece.step != step) continue; piece.dropped = true; queue.remove(i); }
            // A connection in the middle of a dropped piece would go on reading it: it is closed, and opened again when next needed.
            for (int i = seats.size() - 1; i >= 0; i--) { Seat seat = seats.get(i); if (seat.piece != null && seat.piece.dropped) { seats.remove(i); try { seat.line.close(); } catch (RuntimeException ignored) { /* gone already */ } } }
        }
        @Override public void prepare(String text) {
            if (closed || text.equals(aheadText)) return;
            if (ahead >= 0) drop(ahead);
            if (!open()) return;
            aheadText = text; ahead = add(text, queue.size()); pump();
        }
        @Override public void say(String text, Consumer<String> done) {
            if (closed) { done.accept("closed"); return; }
            int mine = ++epoch;
            if (playing >= 0) drop(playing);
            if (!open()) { broke(""); done.accept(""); return; }
            this.done = done;
            // What was got ready is said as it is; anything else goes in front of it.
            if (text.equals(aheadText) && ahead >= 0) { playing = ahead; ahead = -1; aheadText = ""; } else playing = add(text, 0);
            if (patience != null) main.removeCallbacks(patience);
            patience = () -> { if (mine == epoch) broke("읽는 데 너무 오래 걸렸어요."); };
            main.postDelayed(patience, LIVE_PATIENCE_MS);
            pump(); drain();
        }
        /** Every free connection takes the next piece nobody has. */
        private void pump() {
            if (closed || !open()) return;
            for (Seat seat : seats) {
                if (!seat.ready || seat.piece != null) continue;
                Piece next = null; for (Piece piece : queue) if (!piece.sent) { next = piece; break; }
                if (next == null) return;
                next.sent = true; seat.piece = next;
                try { seat.line.say(next.text); } catch (RuntimeException failed) { broke(""); return; }
            }
        }
        /** Sound goes out in the order of the pieces: all that the front piece has, then, once it is whole, the next one's. */
        private void drain() {
            while (out != null && playing >= 0 && !queue.isEmpty()) {
                Piece head = queue.get(0);
                if (head.step != playing) break;
                while (head.played < head.sound.size()) out.play(head.sound.get(head.played++));
                if (!head.complete) return;
                queue.remove(0);
            }
            if (playing < 0 || (!queue.isEmpty() && queue.get(0).step == playing)) return;
            // The step's last piece is out: it is heard when the sound has played.
            Consumer<String> finished = done; int mine = epoch; playing = -1; done = null;
            if (patience != null) main.removeCallbacks(patience);
            if (finished != null && out != null) out.finish(() -> main.post(() -> { if (mine == epoch) finished.accept(null); }));
        }
        /** A connection broke: everything is let go, and whoever is waiting to hear is told. */
        private void broke(String why) {
            Consumer<String> failed = done; done = null; epoch++;
            if (patience != null) main.removeCallbacks(patience);
            shut();
            if (failed != null) failed.accept(why);
        }
        private void shut() {
            for (Seat seat : seats) { seat.broken = true; try { if (seat.line != null) seat.line.close(); } catch (RuntimeException ignored) { /* gone already */ } }
            seats.clear(); queue.clear(); playing = -1; ahead = -1; aheadText = ""; using = "";
            Out old = out; out = null; if (old != null) { old.flush(); old.close(); }
        }
        /** Silence now: what is being said is let go. What was got ready for afterwards is kept, as are connections that are free. */
        @Override public void stop() {
            epoch++; done = null; if (patience != null) main.removeCallbacks(patience);
            if (playing >= 0) drop(playing);
            playing = -1; if (out != null) out.flush();
        }
        @Override public void close() { stop(); closed = true; shut(); }
    }
}
