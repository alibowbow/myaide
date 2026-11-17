package dev.jun.gyeot;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioFocusRequest;
import android.media.AudioManager;
import android.net.ConnectivityManager;
import android.net.Network;
import android.net.NetworkCapabilities;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Toast;
import dev.jun.gyeot.core.GeminiSpeech;
import dev.jun.gyeot.core.SpeechText;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.json.JSONObject;

/**
 * Says text aloud a sentence at a time (core/SpeechText): Gemini speech with the API key on a validated network, else the
 * device's own TextToSpeech in Korean. One queue for the process (AppStore.speech()), for 읽어 주기 now and other spoken
 * output later. It holds transient audio focus while it talks, stops when a call or another app takes the focus, and never
 * speaks during a voice call, a recording or a composer mic. Main thread only.
 */
final class Speech {
    /** 답변 자동 읽기: every new final main-chat answer is read aloud; off by default. */
    static final String AUTO_READ = "autoReadAnswers";
    /** After this long with nothing to say, the engine is shut down (TextToSpeech unbound, the Gemini player closed). */
    static final long IDLE_MS = 30_000;
    /** Gemini says the first piece alone, so it starts soon, then joins sentences up to this many characters per request. */
    static final int GEMINI_PIECE = 600;
    static final String BLOCKED = "음성 대화·녹음·음성 입력 중에는 읽어 주지 않아요.", NOTHING = "읽을 글이 없어요.", NO_FOCUS = "지금은 소리를 낼 수 없어 읽지 않았어요.",
        FALLBACK = "Gemini 음성이 끊겨 남은 글은 기기 음성으로 읽어요.";
    static final AudioAttributes ATTRIBUTES = new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_ASSISTANT).setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build();

    /** One way to say text. done runs on the main thread with null once it was heard, or the reason it could not be said. */
    interface Voice {
        void say(String text, Consumer<String> done); void stop(); void close();
        /** As say; started, when the voice can tell, gets the System.nanoTime ms its sound began (main thread). */
        default void say(String text, Consumer<String> done, LongConsumer started) { say(text, done); }
    }
    /** Makes the voice for an engine (SpeechText.GEMINI or DEVICE); tests pass their own. */
    interface Voices { Voice create(String engine); }
    /** Hears how reading goes, on the main thread (ConnectedVoice): a piece's first sound, and the end of reading. */
    interface Watcher {
        /** The piece starting with a line from key began to sound at this System.nanoTime ms (VoiceLatency's clock). */
        void started(String key, long at);
        /** Nothing is left to say: read to the end (stopped false), or cut off by a stop (true). */
        void quiet(boolean stopped);
    }

    /** A sentence and the text it came from (a second tap on the same key stops it). */
    private record Line(String key, String text) {}
    /** Lines handed to the voice as one piece, in order; failed pieces wait for the piece before them. */
    private static final class Piece {
        final List<Line> lines; final String text; boolean finished; String problem;
        Piece(List<Line> lines, String text) { this.lines = lines; this.text = text; }
    }
    private final Context context; private final AppStore store; private final Voices voices;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ArrayDeque<Line> queue = new ArrayDeque<>();
    /** At most two pieces with the voice: one playing, the next prepared (Gemini fetches it while the first plays). */
    private final ArrayDeque<Piece> said = new ArrayDeque<>();
    private final Runnable idle = this::idle;
    private Voice voice; private String engine = "";
    /** The next piece is a reading's first: Gemini says it alone so the first sound comes soon. */
    private boolean firstPiece;
    private int epoch;
    private AudioFocusRequest focus; private boolean focused;
    private Watcher watcher;

    Speech(Context context, AppStore store) { this(context, store, null); }
    Speech(Context context, AppStore store, Voices voices) {
        this.context = context.getApplicationContext(); this.store = store;
        this.voices = voices != null ? voices : engine -> SpeechText.GEMINI.equals(engine) ? new GeminiVoice(this.context, store) : new DeviceVoice(this.context);
    }

    /** 읽어 주기: reads this text from its start, or stops at once when it is the one being read (a second tap). */
    void toggle(String key, String text) {
        if (reading(key)) { stop(); return; }
        stop(); enqueue(key, text);
    }
    /** Adds the text's sentences after whatever is being read; false when nothing was queued (refused, or nothing to say). */
    boolean enqueue(String key, String text) {
        if (blocked()) { notice(BLOCKED); return false; }
        List<String> sentences = SpeechText.sentences(text);
        if (sentences.isEmpty()) { notice(NOTHING); return false; }
        return add(key, sentences);
    }
    /** Adds sentences already split (an answer streaming in, core/SpeechStream) after whatever is being read; false when refused. */
    boolean enqueue(String key, List<String> sentences) {
        if (blocked()) { notice(BLOCKED); return false; }
        return sentences.isEmpty() || add(key, sentences);
    }
    void watch(Watcher value) { watcher = value; }
    private boolean add(String key, List<String> sentences) {
        boolean fresh = !speaking();
        for (String sentence : sentences) queue.add(new Line(key, sentence));
        main.removeCallbacks(idle);
        if (fresh && !begin()) return false;
        pump();
        return true;
    }
    /** Something is queued or being said. */
    boolean speaking() { return !queue.isEmpty() || !said.isEmpty(); }
    /** The sentence being said (or next, when nothing is said yet) comes from this key. */
    boolean reading(String key) {
        Piece head = said.peek();
        Line line = head != null ? head.lines.get(0) : queue.peek();
        return line != null && line.key().equals(key);
    }
    /** Silences now and drops the queue. */
    void stop() {
        boolean was = speaking();
        epoch++; queue.clear(); said.clear();
        if (voice != null) voice.stop();
        release(); later();
        if (was && watcher != null) watcher.quiet(true);
    }
    /** A voice call or recording is starting: stop at once and say why. */
    void interrupt(String why) { if (!speaking()) return; stop(); notice(why); }
    /** Which engine a new reading uses now: Gemini with the API key on a validated network, else the device voice. */
    String choose() { return SpeechText.engine(store.geminiSpeech() && store.gemini.hasKey(), online(context)); }
    static boolean online(Context context) {
        try {
            ConnectivityManager manager = context.getSystemService(ConnectivityManager.class);
            Network active = manager == null ? null : manager.getActiveNetwork();
            NetworkCapabilities now = active == null ? null : manager.getNetworkCapabilities(active);
            return now != null && now.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED);
        } catch (RuntimeException unknown) { return false; }
    }

    /** A new reading: pick the engine (a key and the network may have changed since the last one) and take the audio focus. */
    private boolean begin() {
        firstPiece = true;
        use(choose());
        if (!focus()) { stop(); notice(NO_FOCUS); return false; }
        return true;
    }
    private void use(String wanted) {
        if (voice != null && wanted.equals(engine)) return;
        if (voice != null) voice.close();
        voice = voices.create(wanted); engine = wanted;
    }
    /** A call or recording is running, or a composer mic or 누르고 말하기 is asking, listening or transcribing (it would hear the reading). */
    private boolean blocked() { return store.voiceActive || store.recording || ComposerDictation.listening() || store.talk != null && store.talk.listening(); }
    /** Hands pieces to the voice until two are out. */
    private void pump() {
        while (said.size() < 2 && !queue.isEmpty()) {
            if (blocked()) { interrupt(BLOCKED); return; }
            List<Line> lines = new ArrayList<>(); StringBuilder text = new StringBuilder();
            // The device voice queues sentences itself; Gemini joins them after the first, so a long answer is a few requests, not dozens.
            boolean join = SpeechText.GEMINI.equals(engine) && !firstPiece;
            String key = queue.peek().key();
            do { Line line = queue.poll(); lines.add(line); text.append(text.length() == 0 ? "" : " ").append(line.text()); }
            while (join && !queue.isEmpty() && queue.peek().key().equals(key) && text.length() + 1 + queue.peek().text().length() <= GEMINI_PIECE);
            firstPiece = false;
            Piece piece = new Piece(lines, text.toString()); said.add(piece);
            int mine = epoch;
            voice.say(piece.text, problem -> heard(mine, piece, problem), at -> { if (mine == epoch && watcher != null) watcher.started(piece.lines.get(0).key(), at); });
        }
    }
    private void heard(int mine, Piece piece, String problem) {
        if (mine != epoch || !said.contains(piece)) return;
        piece.finished = true; piece.problem = problem;
        while (!said.isEmpty() && said.peek().finished) {
            Piece head = said.poll();
            if (head.problem != null) { failed(head); return; }
        }
        if (!speaking()) { release(); later(); if (watcher != null) watcher.quiet(false); return; }
        pump();
    }
    /** A piece could not be said. From Gemini, it and the rest go to the device voice (free and local); from the device, reading stops. */
    private void failed(Piece head) {
        if (!SpeechText.GEMINI.equals(engine)) { String why = head.problem; stop(); notice(why.isBlank() ? "기기 음성으로 읽지 못했어요." : why); return; }
        List<Line> rest = new ArrayList<>(head.lines);
        for (Piece later : said) rest.addAll(later.lines);
        epoch++; said.clear();
        for (int i = rest.size() - 1; i >= 0; i--) queue.addFirst(rest.get(i));
        use(SpeechText.DEVICE);
        notice(FALLBACK);
        pump();
    }
    private boolean focus() {
        if (focused) return true;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio == null) return true;
        // The Gemini voice's own Speaker asking for focus only ducks this request; a call or another app taking it stops reading.
        // stop() gives the request back: a transient loser stays in Android's focus stack and would take the focus back from the music.
        if (focus == null) focus = new AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK).setAudioAttributes(ATTRIBUTES)
            .setOnAudioFocusChangeListener(change -> { if (change == AudioManager.AUDIOFOCUS_LOSS || change == AudioManager.AUDIOFOCUS_LOSS_TRANSIENT) stop(); }, main).build();
        focused = audio.requestAudioFocus(focus) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED;
        return focused;
    }
    private void release() {
        if (!focused) return;
        focused = false;
        AudioManager audio = context.getSystemService(AudioManager.class);
        if (audio != null && focus != null) audio.abandonAudioFocusRequest(focus);
    }
    private void later() { main.removeCallbacks(idle); main.postDelayed(idle, IDLE_MS); }
    private void idle() { if (speaking() || voice == null) return; voice.close(); voice = null; engine = ""; }
    private void notice(String text) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show(); }

    /** The device's TextToSpeech in Korean: bound on first use, free and local. */
    static final class DeviceVoice implements Voice {
        static final String NO_ENGINE = "기기에서 쓸 수 있는 음성 엔진이 없어 읽지 못했어요. Android 설정 → 텍스트 음성 변환에서 확인해 주세요.",
            NO_KOREAN = "기기 음성에 한국어가 없어 읽지 못했어요. Android 설정 → 텍스트 음성 변환에서 한국어 음성을 받아 주세요.";
        private final Handler main = new Handler(Looper.getMainLooper());
        private final Map<String, Consumer<String>> pending = new HashMap<>();
        private final Map<String, LongConsumer> starting = new HashMap<>();
        /** Said before the engine finished binding; spoken (or failed) once it is ready. */
        private final List<Object[]> waiting = new ArrayList<>();
        private final TextToSpeech tts;
        /** 0 while binding, 1 ready, -1 unusable (problem says why). */
        private int state; private String problem = ""; private int next;
        DeviceVoice(Context context) {
            tts = new TextToSpeech(context, this::ready);
            tts.setAudioAttributes(ATTRIBUTES);
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener() {
                // Called just before the engine plays the utterance: the closest the device voice tells to its first sound.
                @Override public void onStart(String id) { long at = System.nanoTime() / 1_000_000; main.post(() -> { LongConsumer started = starting.remove(id); if (started != null) started.accept(at); }); }
                @Override public void onDone(String id) { main.post(() -> finish(id, null)); }
                @Override public void onError(String id) { main.post(() -> finish(id, "기기 음성으로 읽지 못했어요.")); }
                @Override public void onError(String id, int code) { onError(id); }
            });
        }
        private void ready(int status) {
            if (state != 0) return;
            if (status != TextToSpeech.SUCCESS) { state = -1; problem = NO_ENGINE; }
            else if (tts.setLanguage(Locale.KOREAN) < TextToSpeech.LANG_AVAILABLE) { state = -1; problem = NO_KOREAN; }
            else state = 1;
            List<Object[]> queued = new ArrayList<>(waiting); waiting.clear();
            for (Object[] item : queued) say((String) item[0], cast(item[1]), (LongConsumer) item[2]);
        }
        @SuppressWarnings("unchecked") private static Consumer<String> cast(Object done) { return (Consumer<String>) done; }
        @Override public void say(String text, Consumer<String> done) { say(text, done, null); }
        @Override public void say(String text, Consumer<String> done, LongConsumer started) {
            if (state < 0) { String why = problem; main.post(() -> done.accept(why)); return; }
            if (state == 0) { waiting.add(new Object[]{text, done, started}); return; }
            String id = "speech-" + (++next);
            pending.put(id, done); if (started != null) starting.put(id, started);
            if (tts.speak(text, TextToSpeech.QUEUE_ADD, null, id) != TextToSpeech.SUCCESS) { pending.remove(id); starting.remove(id); main.post(() -> done.accept("기기 음성으로 읽지 못했어요.")); }
        }
        private void finish(String id, String problem) { starting.remove(id); Consumer<String> done = pending.remove(id); if (done != null) done.accept(problem); }
        @Override public void stop() { pending.clear(); starting.clear(); waiting.clear(); if (state == 1) tts.stop(); }
        @Override public void close() { stop(); state = -1; problem = NO_ENGINE; tts.shutdown(); }
    }

    /**
     * Gemini speech with the API key: one generateContent request per piece, the next fetched while this one plays through
     * Speaker. After a failed request the voice is spent for that reading: its later pieces fail at once without a request,
     * and nothing is retried. A stop that cuts a request off is not a failure, so the next reading asks Gemini again.
     */
    static final class GeminiVoice implements Voice {
        /** One request for a piece, returning its 24 kHz PCM; it leaves the action that aborts it in cancel. Tests pass their own. */
        interface Fetch { byte[] pcm(String text, AtomicReference<Runnable> cancel) throws Exception; }
        private final Context context; private final AppStore store; private final Fetch fetch;
        private final Handler main = new Handler(Looper.getMainLooper());
        private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "speech-gemini"); t.setDaemon(true); return t; });
        private final AtomicReference<Runnable> cancel = new AtomicReference<>();
        private final AtomicInteger epoch = new AtomicInteger();
        /** The epoch whose request failed; only that reading's later pieces are refused, never the next one's. */
        private volatile int spentIn = -1;
        private Speaker speaker; private boolean closed;
        GeminiVoice(Context context, AppStore store) { this(context, store, null); }
        GeminiVoice(Context context, AppStore store, Fetch fetch) { this.context = context; this.store = store; this.fetch = fetch != null ? fetch : this::request; }
        @Override public void say(String text, Consumer<String> done) { say(text, done, null); }
        @Override public void say(String text, Consumer<String> done, LongConsumer started) {
            int mine = epoch.get();
            try {
                worker.execute(() -> {
                    if (mine != epoch.get()) return;
                    try {
                        if (spentIn == mine) throw new IllegalStateException("spent");
                        byte[] pcm;
                        try { pcm = fetch.pcm(text, cancel); } finally { cancel.set(null); }
                        if (mine != epoch.get()) return;
                        Speaker out = speaker();
                        // Speaker tells the chunk's first audible frame (AudioTrack timestamp) from its own thread.
                        if (!out.play(pcm, started == null ? null : at -> main.post(() -> { if (mine == epoch.get()) started.accept(at); }))) throw new IllegalStateException("queue");
                        // A stop between the check above and play() flushed before this piece was queued: flush it too.
                        if (mine != epoch.get()) { out.flush(); return; }
                        out.finish(() -> { if (mine == epoch.get()) done.accept(null); });
                    } catch (Exception failed) {
                        // After a stop, mine is already stale, so the aborted request spends nothing the next reading uses.
                        spentIn = mine;
                        main.post(() -> { if (mine == epoch.get()) done.accept(""); });
                    }
                });
            } catch (RejectedExecutionException closedNow) { main.post(() -> done.accept("")); }
        }
        private byte[] request(String text, AtomicReference<Runnable> cancel) throws Exception {
            return synthesize(store.gemini.apiKey(), GeminiSpeech.model(store.gemini.keyModels()), text, store.voice(), cancel);
        }
        /**
         * One Gemini speech request for text in a voice (a Live voice name; "" is GeminiSpeech's default) on an API key, as 24
         * kHz PCM. The call greeting's clip (Greeter) is made with it too, on the voice key its call used.
         */
        static byte[] synthesize(String key, String model, String text, String voice, AtomicReference<Runnable> cancel) throws Exception {
            byte[] body = GeminiSpeech.request(text, voice, null).toString().getBytes(StandardCharsets.UTF_8);
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", GeminiSpeech.url(model), GeminiWorkClient.apiHeaders(key, "application/json"), body, 60_000, cancel)) {
                String answer = OpenAiAccount.Http.read(response.body, 16 * 1024 * 1024);
                if (response.status >= 300) throw new IllegalStateException("HTTP " + response.status);
                GeminiSpeech.Audio audio = GeminiSpeech.audio(new JSONObject(answer));
                // Speaker plays 24 kHz, the rate Gemini speech returns; anything else is said by the device voice instead.
                if (audio.rate() != 24000) throw new IllegalStateException("rate " + audio.rate());
                return audio.pcm();
            }
        }
        private synchronized Speaker speaker() {
            if (closed) throw new IllegalStateException("closed");
            if (speaker == null) speaker = new Speaker(context);
            return speaker;
        }
        @Override public void stop() {
            epoch.incrementAndGet();
            Runnable abort = cancel.getAndSet(null);
            // Disconnecting can block on the socket; never on the main thread.
            if (abort != null) { Thread thread = new Thread(() -> { try { abort.run(); } catch (Exception ignored) {} }, "speech-abort"); thread.setDaemon(true); thread.start(); }
            Speaker out; synchronized (this) { out = speaker; }
            if (out != null) out.flush();
        }
        @Override public void close() {
            stop(); worker.shutdownNow();
            Speaker out; synchronized (this) { closed = true; out = speaker; speaker = null; }
            if (out != null) out.close();
        }
    }
}
