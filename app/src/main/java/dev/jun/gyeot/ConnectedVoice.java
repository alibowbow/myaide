package dev.jun.gyeot;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import dev.jun.gyeot.core.PushToTalk;
import dev.jun.gyeot.core.SpeechStream;
import dev.jun.gyeot.core.SpeechText;
import dev.jun.gyeot.core.VoiceLatency;
import java.util.ArrayDeque;
import java.util.List;
import java.util.UUID;
import org.json.JSONObject;

/**
 * 누르고 말하기, the first connected voice (no tools yet): the phone's own recognizer hears a question while the mic is held
 * (a pause the recognizer takes for the end only starts another listening; letting go is the end), or, from the
 * notification or TalkBack, until it hears the speech end. The main chat answers it as an ordinary turn with the user's
 * main model, and Speech reads the answer a sentence at a time as it streams in, so the first sentence plays before the
 * answer ends. It needs no Gemini key. A press while busy stops at once: the recognizer, the reading, and the reply still
 * being prepared (MainChatEngine.cancel, the chat's own cancel). Nothing is ever sent again on its own. One per process
 * (AppStore.talk()); main thread only.
 */
final class ConnectedVoice {
    static final int PERMISSION_REQUEST = 7417;
    /** 말로 묻기 in 들퍼레's notification: MainActivity listens only for this process's own token, so another app's intent cannot open the mic. */
    static final String EXTRA = "connectedVoice", TOKEN = UUID.randomUUID().toString();
    static final String BUSY = "음성 대화나 녹음을 종료한 뒤 음성으로 물어봐 주세요.", NO_MODEL = "메인 대화에서 사용할 AI를 먼저 연결해 주세요.",
        NO_MIC = "음성으로 물으려면 마이크 권한이 필요해요.", NO_RECOGNIZER = "이 기기에서 쓸 수 있는 음성 인식이 없어요. Android 설정에서 음성 인식 서비스를 확인해 주세요.",
        NOT_HEARD = "말씀을 알아듣지 못했어요. 다시 눌러 말해 주세요.", FAILED = "답변을 받지 못했어요. 화면에서 이유를 확인해 주세요.";
    /** Replies kept as spoken here, so 답변 자동 읽기 does not read them a second time. */
    private static final int VOICED = 16;
    /** Listenings in a row that heard nothing before a held mic stops opening new ones (no loop on a silent or empty recognizer). */
    private static final int QUIET = 3;
    private final Context context; private final AppStore store; private final MainChatEngine chat; private final SpeechInput.Ears ears;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final PushToTalk talk = new PushToTalk();
    private final ArrayDeque<Long> voiced = new ArrayDeque<>();
    private final Speech.Watcher watcher = new Speech.Watcher() {
        @Override public void started(String key, long at) { heard(key, at); }
        @Override public void quiet(boolean stopped) { silent(stopped); }
    };
    private Runnable changed;
    private SpeechInput.Ear ear;
    private SpeechStream stream = new SpeechStream();
    private long reply;
    private boolean ready, ended, said;
    /** The mic is still held: only release() ends the question. */
    private boolean held;
    /** Listenings of this question in a row that heard nothing. */
    private int quiet;
    /** The words of this question so far: earlier listenings of a hold, and the words heard so far while listening. */
    private String earlier = "", partial = "";
    private VoiceLatency.Call call;
    private VoiceLatency.Turn watched;

    ConnectedVoice(Context context, AppStore store, MainChatEngine chat, SpeechInput.Ears ears) {
        this.context = context.getApplicationContext(); this.store = store; this.chat = chat; this.ears = ears;
    }
    PushToTalk.State state() { return talk.state(); }
    boolean busy() { return talk.busy(); }
    /** The recognizer is open: nothing may be read aloud into it. */
    boolean listening() { return talk.listening(); }
    /** Listening and the recognizer is ready for speech. */
    boolean ready() { return talk.listening() && ready; }
    /** A held mic stopped opening listenings (see next()): the release asks what was heard. */
    boolean paused() { return talk.listening() && ear == null; }
    /** The words heard so far while listening. */
    String partial() { return partial; }
    SpeechInput.Ears ears() { return ears; }
    /** This reply was asked and read aloud here. */
    boolean voiced(long id) { return voiced.contains(id); }
    /** One screen hears every change of state (the main chat's mic); null to stop. */
    void onChange(Runnable value) { changed = value; }

    /** A press that cannot be held (a tap): listens when idle (true), else stops everything at once (false). */
    boolean press() { if (talk.busy()) { stop(); return false; } return listen(false); }
    /**
     * Opens the recognizer for a new question; false (with a notice) when it cannot. held: the mic is held, so only release()
     * ends the question; otherwise the recognizer's own end of speech does.
     */
    boolean listen(boolean held) {
        if (talk.busy()) return false;
        String why = refusal();
        if (why != null) { notice(why); return false; }
        // The recognizer must not hear a reading or a task result, and a reading must not talk over the question.
        store.stopReading(null); store.stopReport();
        talk.press(); int turn = talk.turn();
        stream = new SpeechStream(); reply = 0; ready = false; ended = false; said = false; earlier = ""; partial = ""; watched = null; this.held = held; quiet = 0;
        ScopedModels models = ScopedModels.main(store);
        call = DiagnosticsExport.voiceLatency(context).begin(VoiceLatency.CONNECTED, models.model(models.engine()));
        open(turn);
        changed();
        return true;
    }
    /** One listening of this turn; while the mic is held a new one follows each that ends. */
    private void open(int turn) {
        SpeechInput.Ear opened = ears.open(context); ear = opened;
        opened.start(new SpeechInput.Listener() {
            /** This listening got ready, so it really listened (one that ends before that is not opened again). */
            private boolean listened;
            private boolean current() { return mine(turn) && ear == opened; }
            @Override public void ready() { if (current()) { listened = true; ready = true; if (call != null) call.micStarted(); changed(); } }
            @Override public void partial(String words) { if (current()) { partial = join(earlier, words); changed(); } }
            // While held, the recognizer's end of speech is only a pause; the release is the end.
            @Override public void ended() { if (current() && !held) end(); }
            @Override public void heard(String words) { if (current()) next(turn, listened, words); }
            @Override public void silent(String reason) { if (current()) { if (held || !earlier.isBlank()) next(turn, listened, ""); else fail(turn, reason); } }
            @Override public void failed(String reason) { if (current()) fail(turn, reason); }
        });
    }
    /**
     * A listening ended with these words. A held mic listens again, so a pause does not send half a question; otherwise all
     * the words so far are the question.
     */
    private void next(int turn, boolean listened, String words) {
        closeEar(); earlier = join(earlier, words); quiet = words.isBlank() ? quiet + 1 : 0;
        if (!held) { ask(turn, earlier); return; }
        partial = earlier;
        // One that never got ready, or several in a row that heard nothing, is not opened again: the release asks.
        if (listened && quiet < QUIET) open(turn);
        changed();
    }
    private static String join(String before, String words) { return before.isBlank() ? words.strip() : words.isBlank() ? before : before + " " + words.strip(); }
    /** The recognizer failed: the turn ends with its reason and nothing is asked. */
    private void fail(int turn, String reason) { closeEar(); talk.failed(turn); finishCall(); notice(reason); changed(); }
    /** The mic was let go: the words so far are the question (the recognizer works out the last of them). */
    void release() {
        if (!talk.listening()) return;
        held = false; end();
        if (ear != null) ear.finish(); else ask(talk.turn(), earlier);
    }
    /** Stops at once from any state: the recognizer, the reading, and the reply being prepared (never sent again). */
    void stop() {
        if (!talk.busy()) return;
        long pending = reply;
        talk.stop();
        closeEar();
        Speech speech = store.speech;
        if (speech != null && speech.speaking()) speech.stop();
        if (pending != 0) chat.cancel(pending);
        finishCall(); changed();
    }

    private String refusal() {
        if (store.voiceActive || store.recording) return BUSY;
        if (!store.ready(ScopedModels.main(store).engine())) return NO_MODEL;
        if (context.checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return NO_MIC;
        if (!ears.available(context)) return NO_RECOGNIZER;
        return null;
    }
    private boolean mine(int turn) { return turn == talk.turn(); }
    /** The end of speech: a hold's release, or for a tap the recognizer hearing it. */
    private void end() { if (ended) return; ended = true; if (call != null) call.speechEnded(); }
    private void ask(int turn, String words) {
        end(); closeEar(); partial = "";
        if (!talk.heard(turn, words)) { finishCall(); notice(NOT_HEARD); changed(); return; }
        try {
            // A spoken question: its reply counts as 음성 in the usage ledger.
            reply = chat.enqueue(words.strip(), UsageLedger.VOICE, new MainChatEngine.Listener() {
                /** Pieces not yet on the main thread: one post takes all of them, so a busy main thread is not handed every piece. */
                private final StringBuilder waiting = new StringBuilder();
                @Override public void text(String delta) {
                    synchronized (waiting) { boolean posted = waiting.length() > 0; waiting.append(delta); if (posted) return; }
                    main.post(() -> { String all; synchronized (waiting) { all = waiting.toString(); waiting.setLength(0); } streamed(turn, all); });
                }
                @Override public void ended() { main.post(() -> answered(turn)); }
            });
            voiced.addLast(reply); while (voiced.size() > VOICED) voiced.removeFirst();
        } catch (Exception refused) {
            talk.failed(turn); finishCall();
            notice(refused.getMessage() == null || refused.getMessage().isBlank() ? FAILED : refused.getMessage());
        }
        changed();
    }
    /** A piece of the answer; once the spoken turn has ended (stopped, or Speech refused) the rest is not parsed. */
    private void streamed(int turn, String delta) { if (mine(turn) && talk.busy()) say(turn, stream.add(delta), true); }
    /**
     * Hands sentences to Speech; the answer's first one is the turn's output for VoiceLatency (a failure notice is not an
     * answer, so it is no sample). False when Speech refused (the turn ends).
     */
    private boolean say(int turn, List<String> sentences, boolean answer) {
        if (sentences.isEmpty() || !talk.busy()) return true;
        Speech speech = store.speech(); speech.watch(watcher);
        if (!said && answer && call != null) { call.output(); watched = call.awaitAudible(); }
        said = true;
        // A refusal (a call or recording started, no audio focus) ends the spoken turn; the reply still lands in the chat.
        if (!speech.enqueue(key(), sentences)) { talk.failed(turn); finishCall(); changed(); return false; }
        if (talk.spoke(turn)) changed();
        return true;
    }
    /** The reply ended: the rest is said, or why it ended; a reply stopped from the chat stops the reading too. */
    private void answered(int turn) {
        if (!mine(turn) || !talk.busy()) return;
        JSONObject message = store.mainChat.message(reply);
        String state = message == null ? "" : message.optString("state");
        if (state.equals("cancelled")) { talk.stop(); Speech speech = store.speech; if (speech != null && speech.speaking()) speech.stop(); finishCall(); changed(); return; }
        // A reply that did not stream (a local status, a provider without deltas) is said whole from the chat.
        boolean done = state.equals("done");
        List<String> rest = !done ? List.of(FAILED) : stream.text().isBlank() ? SpeechText.sentences(message.optString("text")) : stream.finish();
        if (!say(turn, rest, done)) return;
        Speech speech = store.speech;
        talk.answered(turn, speech != null && speech.speaking());
        if (!talk.busy()) finishCall();
        changed();
    }
    private void heard(String key, long at) {
        VoiceLatency.Turn first = watched;
        if (first == null || reply == 0 || !key.equals(key())) return;
        watched = null; first.audible(at);
    }
    private void silent(boolean stopped) {
        if (!talk.busy() || talk.listening()) return;
        int turn = talk.turn();
        // Someone else stopped the reading (leaving the app, a call, 읽기 멈추기): the spoken turn ends, the reply still lands in the chat.
        if (stopped) talk.failed(turn); else talk.quiet(turn);
        if (!talk.busy()) finishCall();
        changed();
    }
    /** The Speech key of this turn's answer: the main chat's own, so its 읽어 주기 shows 읽기 멈추기 meanwhile. */
    private String key() { return "main:" + reply; }
    private void closeEar() { SpeechInput.Ear open = ear; ear = null; ready = false; if (open != null) open.close(); }
    private void finishCall() {
        VoiceLatency.Call done = call; call = null;
        if (done == null) return;
        done.turnComplete(); done.end();
        DiagnosticsExport.saveVoiceLatency(context);
    }
    private void changed() { Runnable listener = changed; if (listener != null) listener.run(); }
    private void notice(String text) { Toast.makeText(context, text, Toast.LENGTH_SHORT).show(); }
}
