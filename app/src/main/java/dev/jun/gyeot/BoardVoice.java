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
import java.util.function.LongConsumer;

/**
 * 판서's narration uses its selected Live model and voice, or the device reader. Main thread only.
 * Nothing is read during a call, a recording or while a microphone is listening.
 */
final class BoardVoice {
    /** Makes the selected reader. */
    interface Voices { Speech.Voice create(String kind); }
    static final String LIVE = "live", DEVICE = "device";
    /** Reading in a GPT or Grok realtime voice (BoardRealtime); the kind's name in lower case. */
    static final String GPT = "gpt", GROK = "grok";
    /** 판서's realtime voice when one is chosen ("boardVoiceEngine") and has a sign-in or key; else null. */
    static dev.jun.gyeot.core.RealtimeVoice.Kind realtime(AppStore store) {
        dev.jun.gyeot.core.RealtimeVoice.Kind kind = dev.jun.gyeot.core.RealtimeVoice.Kind.of(store.settings.getString("boardVoiceEngine", ""));
        return kind != null && VoiceCredentials.usable(store, kind) ? kind : null;
    }
    static boolean realtimeKind(String kind) { return GPT.equals(kind) || GROK.equals(kind); }
    /** Maximum wait for audible progress in the current step. */
    static final long LIVE_PATIENCE_MS = 45_000;
    private final Context context; private final AppStore store; private final Voices voices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private Speech.Voice voice; private String kind = ""; private int epoch; private boolean focused, closed; private AudioFocusRequest focus;
    private String activeStatus = "", lastResult = "", loadedModel = "", loadedVoice = "";
    Consumer<String> onStatus;
    BoardVoice(Context context, AppStore store) { this(context, store, null); }
    BoardVoice(Context context, AppStore store, Voices voices) {
        this.context = context.getApplicationContext(); this.store = store;
        this.voices = voices != null ? voices : kind -> LIVE.equals(kind) ? new LiveVoice(this.context, store, loadedModel, loadedVoice)
            : realtimeKind(kind) ? realtimeVoice(this.context, store, dev.jun.gyeot.core.RealtimeVoice.Kind.of(kind))
            : new Speech.DeviceVoice(this.context, Speech.LESSON);
    }

    /** 판서's own voice: the one chosen in its menu, the assistant's until one is. "" is the model's default voice. */
    static String voice(AppStore store) { return store.settings.contains("boardVoice") ? store.settings.getString("boardVoice", "") : store.voice(); }
    /** 판서's own Live model: the one chosen in its menu, the assistant's until one is. */
    static String liveModel(AppStore store) { return GeminiProtocol.liveModel(store.settings.contains("boardLiveModel") ? store.settings.getString("boardLiveModel", "") : store.liveModel()); }

    /** Whether anything would hear the reading as speech to answer (a call, a recording, an open microphone). */
    boolean blocked() { return store.voiceActive || store.recording || ComposerDictation.listening() || store.talk != null && store.talk.listening(); }
    /** A GPT or Grok voice reading through LiveVoice's pacing, with the speaker as its Gemini voice has. */
    static LiveVoice realtimeVoice(Context context, AppStore store, dev.jun.gyeot.core.RealtimeVoice.Kind kind) {
        return new LiveVoice(BoardRealtime.lines(context, store, kind), () -> { Speaker speaker = Speaker.media(context); return new LiveVoice.Out() {
            @Override public boolean play(byte[] pcm, java.util.function.LongConsumer started) { return speaker.play(pcm, started); }
            @Override public void finish(Runnable after) { speaker.finish(after); }
            @Override public void flush() { speaker.flush(); }
            @Override public void close() { speaker.close(); }
        }; }, kind.model, store.pickedVoice(kind));
    }
    String selected() {
        if (store.settings.getBoolean("boardDeviceVoice", false)) return DEVICE;
        dev.jun.gyeot.core.RealtimeVoice.Kind realtime = realtime(store);
        if (realtime != null) return realtime.name().toLowerCase(java.util.Locale.ROOT);
        if (store.settings.contains("boardLiveModel") || !GeminiProtocol.DEFAULT_LIVE.equals(store.liveModel()) || store.secrets.hasKey()) return LIVE;
        return DEVICE;
    }
    String sourceName() {
        String selected = selected();
        if (DEVICE.equals(selected)) return "기기 TTS";
        if (realtimeKind(selected)) { dev.jun.gyeot.core.RealtimeVoice.Kind kind = dev.jun.gyeot.core.RealtimeVoice.Kind.of(selected); return kind.spoken() + " · " + dev.jun.gyeot.core.RealtimeVoice.shown(kind, store.pickedVoice(kind)); }
        String id = liveModel(store);
        for (String[] model : GeminiProtocol.LIVE_MODELS) if (model[0].equals(id)) return model[1].replace(" (기본)", "");
        return id;
    }
    String status() { return activeStatus.isEmpty() ? lastResult : activeStatus; }
    private void active(String value) { activeStatus = value; if (onStatus != null) onStatus.accept(status()); }
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

    /** Completes after playback, or reports the selected reader's failure. */
    void say(String text, Consumer<String> done) {
        if (closed || text == null || text.isBlank()) { done.accept(null); return; }
        if (blocked()) { done.accept("통화나 녹음 중에는 소리 내어 읽지 않아요."); return; }
        String wanted = selected(), source = sourceName();
        if (LIVE.equals(wanted) && !store.secrets.hasKey()) {
            reset();
            String error = "판서 Live 음성을 쓰려면 설정에서 Gemini 음성 API 키를 등록해 주세요.";
            lastResult = "최근 음성 오류 · " + error; active(""); done.accept(error); return;
        }
        if (!focus()) { done.accept("다른 소리가 나고 있어서 읽지 못했어요."); return; }
        use(wanted);
        int mine = ++epoch;
        active("음성 요청 중 · " + source);
        voice.say(text, problem -> main.post(() -> {
            if (mine != epoch || closed) return;
            if (problem == null) { lastResult = "최근 재생 · " + source; active(""); done.accept(null); return; }
            String detail = problem.isBlank() ? "소리 내어 읽지 못했어요." : problem.strip();
            String category = LIVE.equals(wanted) ? GeminiProtocol.failureKind(detail) : "실패";
            if (detail.length() > 180) detail = detail.substring(0, 180) + "…";
            String error = source + " · " + ("실패".equals(category) ? detail : category + " (" + detail + ")");
            lastResult = "최근 음성 오류 · " + error;
            active(""); release(); done.accept(error);
        }), at -> main.post(() -> {
            if (mine == epoch && !activeStatus.isEmpty()) active("재생 중 · " + source);
        }));
    }
    private void use(String wanted) {
        dev.jun.gyeot.core.RealtimeVoice.Kind realtime = realtimeKind(wanted) ? dev.jun.gyeot.core.RealtimeVoice.Kind.of(wanted) : null;
        String model = LIVE.equals(wanted) ? liveModel(store) : realtime != null ? realtime.model : "", name = LIVE.equals(wanted) ? voice(store) : realtime != null ? store.pickedVoice(realtime) : "";
        if (voice != null && wanted.equals(kind) && model.equals(loadedModel) && name.equals(loadedVoice)) return;
        if (voice != null) voice.close();
        loadedModel = model; loadedVoice = name;
        voice = voices.create(wanted); kind = wanted;
    }
    /** A lesson is on its way: the Live voice connects now, so its first words do not wait for that. */
    void warm() {
        String wanted = selected();
        if (closed || blocked() || !(LIVE.equals(wanted) && store.secrets.hasKey() || realtimeKind(wanted))) return;
        use(wanted);
        if (voice instanceof Ahead ahead) ahead.warm();
    }
    /** What will be said after what is being said: a voice that can, gets it ready, so one step runs into the next without a wait. */
    void prepare(String text) { if (!closed && !activeStatus.isEmpty() && text != null && !text.isBlank() && voice instanceof Ahead ahead && !blocked()) ahead.prepare(text); }
    /** A voice that can work ahead of what it is saying. */
    interface Ahead { void warm(); void prepare(String text); }
    /** Stops what is being read; its done is not called. */
    void stop() { epoch++; active(""); if (voice != null) voice.stop(); release(); }
    /** The voice or the model was changed: the next words are read by a voice made anew. */
    void reset() { stop(); if (voice != null) voice.close(); voice = null; kind = ""; }
    void close() { reset(); closed = true; }

    private boolean focus() {
        if (focused) return true;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return true;
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(Speech.LESSON)
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
        interface Events { void ready(); void audio(byte[] pcm); void finished(); void failed(String why, boolean retryable); }
        interface Lines { Line open(String model, String voice, Events events) throws Exception; }
        /** Where the sound goes; Speaker, or a test's own. */
        interface Out { boolean play(byte[] pcm, LongConsumer started); void finish(Runnable done); void flush(); void close(); }
        private static final class Piece {
            final String text; final int step; final List<byte[]> sound = new ArrayList<>(); dev.jun.gyeot.core.SpeechJoin join = new dev.jun.gyeot.core.SpeechJoin();
            boolean sent, complete, dropped; int played, pause; Piece(String text, int step) { this.text = text; this.step = step; }
            void retryUnplayed() { sent = false; sound.clear(); join = new dev.jun.gyeot.core.SpeechJoin(); }
        }
        /** With less than this much sound still to be heard, a sentence the listener is about to need is read by whichever connection is free. */
        static final long HELP_MS = 1200;
        private final class Seat implements Events {
            Line line; boolean ready; Piece piece;
            @Override public void ready() { main.post(() -> { if (seats.contains(this)) { ready = true; pump(); } }); }
            @Override public void audio(byte[] pcm) { main.post(() -> { if (!seats.contains(this) || piece == null || piece.dropped) return; byte[] sound = piece.join.feed(pcm); if (sound.length > 0) piece.sound.add(sound); drain(); pump(); }); }
            @Override public void finished() { main.post(() -> {
                if (!seats.contains(this) || piece == null) return;
                byte[] last = piece.join.end(piece.pause);
                if (last.length > 0) piece.sound.add(last);
                if (piece.sound.isEmpty()) { lost(this, "Gemini가 음성 없이 응답을 끝냈어요.", true); return; }
                piece.complete = true; piece = null; drain(); pump();
            }); }
            @Override public void failed(String why, boolean retryable) { main.post(() -> lost(this, why, retryable)); }
        }
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Lines lines; private final java.util.function.Supplier<Out> outs; private final String model, voice;
        private final List<Seat> seats = new ArrayList<>(); private final List<Piece> queue = new ArrayList<>();
        private final java.util.Map<Integer, Seat> owners = new java.util.HashMap<>(); private long heardUntil;
        private Out out; private boolean closed; private int steps, playing = -1, ahead = -1, epoch; private String aheadText = "";
        private Consumer<String> done; private LongConsumer started; private boolean single, retried;
        private final Runnable patience = () -> {
            if (playing >= 0) broke("45초 동안 다음 음성을 받지 못했어요.");
        };

        LiveVoice(Context context, AppStore store, String selectedModel, String selectedVoice) {
            this((model, voice, events) -> {
                String key = store.secrets.get(); if (key.isBlank()) throw new IllegalStateException("설정에서 Gemini 음성 API 키를 등록해 주세요.");
                GeminiProtocol.Options options = new GeminiProtocol.Options();
                options.model = model; options.voice = voice; options.thinkingLevel = store.liveThinkingLevel();
                boolean extended = GeminiProtocol.extended(model);
                GeminiSession session = new GeminiSession(key, GeminiProtocol.NARRATOR, options, new GeminiSession.Listener() {
                    @Override public void ready() { events.ready(); }
                    @Override public void audio(byte[] pcm) { events.audio(pcm); }
                    // A piece was said. One signal a turn: the utterance's end where turns end later (extended thinking), the turn's end elsewhere.
                    @Override public void utteranceComplete() { if (extended) events.finished(); }
                    @Override public void turnComplete() { if (!extended) events.finished(); }
                    @Override public void failed(String reason, boolean retryable) { events.failed(reason, retryable); }
                });
                session.connect();
                return new Line() {
                    @Override public void say(String piece) {
                        if (!session.send(GeminiProtocol.text(GeminiProtocol.narration(piece, model))))
                            events.failed("문장을 보내기 전에 Live 연결이 끊겼어요.", true);
                    }
                    @Override public void close() { session.close(); }
                };
            }, () -> { Speaker speaker = Speaker.media(context); return new Out() {
                @Override public boolean play(byte[] pcm, LongConsumer started) { return speaker.play(pcm, started); }
                @Override public void finish(Runnable after) { speaker.finish(after); }
                @Override public void flush() { speaker.flush(); }
                @Override public void close() { speaker.close(); }
            }; }, selectedModel, selectedVoice);
        }
        LiveVoice(Lines lines, java.util.function.Supplier<Out> outs, String model, String voice) { this.lines = lines; this.outs = outs; this.model = model; this.voice = voice; }

        /** How many connections read at once: two where pieces are sentences (one reads a step, one gets the next ready), one otherwise. */
        static int seats(String model) { return GeminiProtocol.readsAsWritten(model) ? 1 : 2; }
        private void open() {
            if (closed) return;
            // Open the helper only after the first connection is accepted and there is more work.
            if (!seats.isEmpty() && (single || seats.size() >= seats(model) || !seats.get(0).ready || queue.size() < 2)) return;
            Seat seat = new Seat(); seats.add(seat);
            try { seat.line = lines.open(model, voice, seat); }
            catch (Exception failed) { seat.failed(dev.jun.gyeot.core.Jsons.message(failed, ""), false); }
        }
        @Override public void warm() { open(); }
        private int add(String text, int at) {
            int step = ++steps; int index = at;
            List<String> parts = pieces(text, model);
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
            for (int i = seats.size() - 1; i >= 0; i--) { Seat seat = seats.get(i); if (seat.piece != null && seat.piece.dropped) remove(seat); }
        }
        @Override public void prepare(String text) {
            if (closed || text.equals(aheadText)) return;
            if (ahead >= 0) drop(ahead);
            aheadText = text; ahead = add(text, queue.size()); pump();
        }
        @Override public void say(String text, Consumer<String> done) {
            say(text, done, null);
        }
        @Override public void say(String text, Consumer<String> done, LongConsumer started) {
            if (closed) { done.accept("closed"); return; }
            ++epoch;
            if (playing >= 0) drop(playing);
            this.done = done; this.started = started; retried = false;
            try { if (out == null) out = outs.get(); }
            catch (RuntimeException failed) { broke("기기의 오디오 출력을 열지 못했어요."); return; }
            // What was got ready is said as it is; anything else goes in front of it.
            if (text.equals(aheadText) && ahead >= 0) { playing = ahead; ahead = -1; aheadText = ""; } else playing = add(text, 0);
            waitForAudio();
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
            if (closed || queue.isEmpty()) return;
            open();
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
                try { seat.line.say(next.text); } catch (RuntimeException failed) { seat.failed(dev.jun.gyeot.core.Jsons.message(failed, ""), true); }
            }
        }
        private void waitForAudio() {
            main.removeCallbacks(patience);
            main.postDelayed(patience, LIVE_PATIENCE_MS + lead());
        }
        private boolean play(byte[] pcm) {
            if (!out.play(pcm, started)) { broke("기기에서 Live 음성을 재생하지 못했어요."); return false; }
            started = null;
            long now = android.os.SystemClock.uptimeMillis(); heardUntil = Math.max(heardUntil, now) + dev.jun.gyeot.core.SpeechJoin.millis(pcm.length);
            waitForAudio(); return true;
        }
        /** Sound goes out in the order of the pieces: all that the front piece has, then, once it is whole, the next one's. */
        private void drain() {
            while (out != null && playing >= 0 && !queue.isEmpty()) {
                Piece head = queue.get(0);
                if (head.step != playing) break;
                while (head.played < head.sound.size()) if (!play(head.sound.get(head.played++))) return;
                if (!head.complete) return;
                queue.remove(0);
            }
            if (playing < 0 || (!queue.isEmpty() && queue.get(0).step == playing)) return;
            // The step's last piece is out: it is heard when the sound has played.
            owners.remove(playing);
            Consumer<String> finished = done; int mine = epoch; playing = -1; done = null;
            main.removeCallbacks(patience);
            if (finished != null && out != null) out.finish(() -> main.post(() -> { if (mine == epoch) finished.accept(null); }));
        }
        private void remove(Seat seat) {
            seats.remove(seat);
            owners.values().removeIf(owner -> owner == seat);
            if (seat.line != null) try { seat.line.close(); } catch (RuntimeException ignored) { /* already closed */ }
        }
        private void lost(Seat seat, String reason, boolean retryable) {
            if (!seats.contains(seat)) return;
            remove(seat);
            String why = reason == null || reason.isBlank() ? "Live 연결이 끊겼어요." : reason;
            if (seat.piece != null) {
                // Once part of this sentence reached the speaker, replaying it would repeat words.
                if (seat.piece.played > 0) { broke(why); return; }
                seat.piece.retryUnplayed();
            }
            if (!seats.isEmpty()) {
                single = true; pump(); return;
            }
            if (playing < 0) {
                // Playback already queued for the speaker must still signal completion.
                return;
            }
            if (retryable && !retried && !GeminiProtocol.permanentError(why)) {
                retried = true; single = true; waitForAudio(); pump(); return;
            }
            broke(why);
        }
        private void broke(String why) {
            Consumer<String> failed = done; done = null; started = null; epoch++;
            main.removeCallbacks(patience);
            shut();
            if (failed != null) failed.accept(why);
        }
        private void shut() {
            while (!seats.isEmpty()) remove(seats.get(seats.size() - 1));
            queue.clear(); owners.clear(); heardUntil = 0; playing = -1; ahead = -1; aheadText = ""; single = false;
            Out old = out; out = null; if (old != null) { old.flush(); old.close(); }
        }
        /** Silence now: what is being said is let go. What was got ready for afterwards is kept, as are connections that are free. */
        @Override public void stop() {
            epoch++; done = null; started = null; main.removeCallbacks(patience);
            if (playing >= 0) drop(playing);
            playing = -1; heardUntil = 0; if (out != null) out.flush();
        }
        @Override public void close() { stop(); closed = true; shut(); }
    }
}
