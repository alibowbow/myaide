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
     * shortens passages), and the pieces are made ahead of being heard: a connection reads its next piece as soon as
     * it has finished one, their sound is kept in order, and a piece plays the moment the one before it has.
     * Sentences made apart are joined to sound like one reading. A step's sentences are read by one connection, one
     * after another, so they come in one voice at one pace (two connections read the same voice a little differently);
     * the other connection gets the next step ready meanwhile, and reads a sentence of this step only when the listener
     * would otherwise have to wait for it. And each sentence's own silence, a different length every time, is taken off
     * and a pause of one length put in its place (SpeechJoin).
     */
    static final class LiveVoice implements Speech.Voice, Ahead {
        /** One reading connection, as this class needs it; GeminiSession behind it, or a test's own. */
        interface Line { void say(String piece); void close(); }
        /** What a line tells: it can take a piece, sound of its piece, its piece is finished, it broke. Called on any thread. */
        interface Events { void ready(); void audio(byte[] pcm); void finished(); void failed(String why); }
        interface Lines { Line open(String model, String voice, Events events) throws Exception; }
        /** Where the sound goes; Speaker, or a test's own. */
        interface Out { void play(byte[] pcm); void finish(Runnable done); void flush(); void close(); }
        private static final class Piece {
            final String text; final int step; final List<byte[]> sound = new ArrayList<>(); final dev.jun.gyeot.core.SpeechJoin join = new dev.jun.gyeot.core.SpeechJoin();
            boolean sent, complete, dropped; int played, pause; Piece(String text, int step) { this.text = text; this.step = step; }
        }
        /** With less than this much sound still to be heard, a sentence the listener is about to need is read by whichever connection is free. */
        static final long HELP_MS = 1200;
        private final class Seat implements Events {
            Line line; boolean ready, broken; Piece piece;
            @Override public void ready() { main.post(() -> { if (seats.contains(this) && !broken) { ready = true; pump(); } }); }
            @Override public void audio(byte[] pcm) { main.post(() -> { if (!seats.contains(this) || piece == null || piece.dropped) return; byte[] sound = piece.join.feed(pcm); if (sound.length > 0) piece.sound.add(sound); drain(); pump(); }); }
            @Override public void finished() { main.post(() -> { if (!seats.contains(this) || piece == null) return; byte[] last = piece.join.end(piece.pause); if (last.length > 0) piece.sound.add(last); piece.complete = true; piece = null; drain(); pump(); }); }
            @Override public void failed(String why) { main.post(() -> { if (seats.contains(this)) broke(why == null ? "" : why); }); }
        }
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Lines lines; private final java.util.function.Supplier<Out> outs; private final java.util.function.Supplier<String> model, voice;
        private final List<Seat> seats = new ArrayList<>(); private final List<Piece> queue = new ArrayList<>();
        private final java.util.Map<Integer, Seat> owners = new java.util.HashMap<>(); private long heardUntil;
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

        /** How many connections read at once: two where pieces are sentences (one reads a step, one gets the next ready), one otherwise. */
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
            List<String> parts = pieces(text, using.isEmpty() ? model.get() : using);
            for (int i = 0; i < parts.size(); i++) {
                Piece piece = new Piece(parts.get(i), step);
                // A pause after every sentence but the step's last: after that one the writing goes on.
                piece.pause = i + 1 < parts.size() ? dev.jun.gyeot.core.SpeechJoin.pause(parts.get(i)) : 0;
                queue.add(Math.min(index++, queue.size()), piece);
            }
            return step;
        }
        private void drop(int step) {
            for (int i = queue.size() - 1; i >= 0; i--) { Piece piece = queue.get(i); if (piece.step != step) continue; piece.dropped = true; queue.remove(i); }
            owners.remove(step);
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
            // What is made already goes to the speaker first: who reads what next depends on how much is left to hear.
            drain(); pump();
        }
        /** How much sound is still to be heard of what was given to the speaker. */
        private long lead() { return Math.max(0, heardUntil - android.os.SystemClock.uptimeMillis()); }
        /**
         * Every free connection takes the next piece that is its to read: of a step nobody reads yet, or of the step it
         * reads already. A piece of the step another connection is busy reading is taken only when it is of the step
         * being heard and the sound is about to run out.
         */
        private void pump() {
            if (closed || queue.isEmpty() || !open()) return;
            for (Seat seat : seats) {
                if (!seat.ready || seat.piece != null) continue;
                Piece next = null;
                for (Piece piece : queue) {
                    if (piece.sent) continue;
                    Seat owner = owners.get(piece.step);
                    if (owner == null || owner == seat || !seats.contains(owner)) { next = piece; break; }
                    if (owner.piece != null && piece.step == playing && lead() < HELP_MS) { next = piece; break; }
                }
                if (next == null) continue;
                Seat owner = owners.get(next.step); if (owner == null || !seats.contains(owner)) owners.put(next.step, seat);
                next.sent = true; seat.piece = next;
                try { seat.line.say(next.text); } catch (RuntimeException failed) { broke(""); return; }
            }
        }
        private void play(byte[] pcm) {
            long now = android.os.SystemClock.uptimeMillis(); heardUntil = Math.max(heardUntil, now) + dev.jun.gyeot.core.SpeechJoin.millis(pcm.length);
            out.play(pcm);
        }
        /** Sound goes out in the order of the pieces: all that the front piece has, then, once it is whole, the next one's. */
        private void drain() {
            while (out != null && playing >= 0 && !queue.isEmpty()) {
                Piece head = queue.get(0);
                if (head.step != playing) break;
                while (head.played < head.sound.size()) play(head.sound.get(head.played++));
                if (!head.complete) return;
                queue.remove(0);
            }
            if (playing < 0 || (!queue.isEmpty() && queue.get(0).step == playing)) return;
            // The step's last piece is out: it is heard when the sound has played.
            owners.remove(playing);
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
            seats.clear(); queue.clear(); owners.clear(); heardUntil = 0; playing = -1; ahead = -1; aheadText = ""; using = "";
            Out old = out; out = null; if (old != null) { old.flush(); old.close(); }
        }
        /** Silence now: what is being said is let go. What was got ready for afterwards is kept, as are connections that are free. */
        @Override public void stop() {
            epoch++; done = null; if (patience != null) main.removeCallbacks(patience);
            if (playing >= 0) drop(playing);
            playing = -1; heardUntil = 0; if (out != null) out.flush();
        }
        @Override public void close() { stop(); closed = true; shut(); }
    }
}
