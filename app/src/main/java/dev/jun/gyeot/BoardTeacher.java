package dev.jun.gyeot;

import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import dev.jun.gyeot.core.Board;
import dev.jun.gyeot.core.BoardScript;
import dev.jun.gyeot.core.MainRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * Gives a lesson on the board: asks the chosen AI model for the lesson (BoardScript), then goes through its steps,
 * writing each on the BoardView at a hand's pace while BoardVoice reads what the step says. A step is over when its
 * writing is done and its words were heard (or, without sound, after time to read them). Main thread, but for the
 * request to the model.
 */
final class BoardTeacher {
    /** The model call: instructions, the request and its files in, the model's answer out. cancel takes what aborts it. */
    interface Brain { String ask(String instructions, String input, List<MainRequest.Attachment> files, AtomicReference<Runnable> cancel) throws Exception; }
    interface Watcher {
        /** state is IDLE, THINKING, PLAYING or PAUSED; step counts from 1 (0 when there is none); caption is what the step says. */
        void state(String state, int step, int steps, String caption);
        /** The lesson could not be given or read aloud; shown once. */
        void notice(String why);
        /** Something of the lesson is on the board now (for saving), and the lesson's title when it has one. */
        void wrote(String title);
    }
    static final String IDLE = "idle", THINKING = "thinking", PLAYING = "playing", PAUSED = "paused";
    static final long FRAME_MS = 16, BETWEEN_STEPS_MS = 350;
    /** The model call off the main thread; tests run it in place. */
    static Executor work;
    private final ExecutorService worker = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "board-teacher"); t.setDaemon(true); return t; });
    private final Handler main = new Handler(Looper.getMainLooper());
    private final BoardView board; private final BoardVoice voice; private final Brain brain; private final Watcher watcher;
    private final AtomicReference<Runnable> cancel = new AtomicReference<>();
    private String state = IDLE; private int epoch; private boolean muted, closed, voiceNoticed;
    /** This lesson's words could not be read aloud: its steps are timed as if muted, without changing the user's own choice. */
    private boolean voiceless;
    // The lesson being given.
    private BoardScript.Lesson lesson; private BoardScript.Flow flow; private int index;
    private List<JSONObject> placed = new ArrayList<>(); private long[] starts; private long drawTotal, stepStarted, drawnFor;
    private boolean drawn, heard;
    private final Runnable frame = this::frame;

    BoardTeacher(BoardView board, BoardVoice voice, Brain brain, Watcher watcher) {
        this.board = board; this.voice = voice; this.brain = brain; this.watcher = watcher;
        voice.onLost = () -> { if (state.equals(PLAYING)) pause(); };
    }
    String state() { return state; }
    boolean busy() { return !state.equals(IDLE); }
    boolean muted() { return muted; }
    /** Sound off: steps are held long enough to read their words. Takes effect at once. */
    void mute(boolean value) {
        muted = value;
        if (muted && state.equals(PLAYING) && !heard) { voice.stop(); heard = false; }
    }
    private void tell() { BoardScript.Step step = lesson != null && index < lesson.steps().size() ? lesson.steps().get(index) : null; watcher.state(state, lesson == null ? 0 : index + 1, lesson == null ? 0 : lesson.steps().size(), step == null ? "" : step.say()); }

    /** Asks the model for a lesson on the request (with its files) and gives it. Does nothing while a lesson is on. */
    void ask(String request, List<MainRequest.Attachment> files, String language) {
        if (closed || busy()) return;
        int mine = ++epoch; state = THINKING; lesson = null; index = 0; tell();
        String instructions = BoardScript.instructions(language), input = BoardScript.request(request, Board.words(board.items(), 6000), files != null && !files.isEmpty());
        Runnable job = () -> {
            BoardScript.Lesson made = null; String problem = null;
            try { made = BoardScript.parse(brain.ask(instructions, input, files == null ? List.of() : files, cancel)); }
            catch (Exception failed) { problem = failed.getMessage() == null || failed.getMessage().isBlank() ? "AI에게서 판서를 받지 못했어요." : failed.getMessage(); }
            finally { cancel.set(null); }
            BoardScript.Lesson result = made; String why = problem;
            Runnable deliver = () -> {
                if (mine != epoch || closed) return;
                if (result == null) { state = IDLE; tell(); watcher.notice(why); return; }
                begin(result);
            };
            if (work != null) deliver.run(); else main.post(deliver);
        };
        if (work != null) work.execute(job); else worker.execute(job);
    }
    /** Gives a lesson that is already made (ask's second half; tests start here). */
    void begin(BoardScript.Lesson made) {
        lesson = made; flow = new BoardScript.Flow(board.items(), board.measure); index = 0; voiceNoticed = false; voiceless = false;
        state = PLAYING; step();
    }
    private void step() {
        if (lesson == null || index >= lesson.steps().size()) { finish(); return; }
        BoardScript.Step step = lesson.steps().get(index);
        placed = flow.place(step); starts = new long[placed.size()]; drawTotal = 0;
        for (int i = 0; i < placed.size(); i++) { starts[i] = drawTotal; drawTotal += BoardScript.itemMillis(placed.get(i)); board.add(placed.get(i), true); }
        if (!placed.isEmpty()) { float[] b = Board.bounds(placed.get(placed.size() - 1), board.measure); board.follow(b[3]); watcher.wrote(index == 0 ? lesson.title() : null); }
        drawn = placed.isEmpty(); drawnFor = 0; stepStarted = SystemClock.uptimeMillis();
        tell(); speak(step); main.removeCallbacks(frame); main.post(frame);
    }
    private void speak(BoardScript.Step step) {
        heard = false; int mine = epoch, at = index;
        if (muted || voiceless || step.say().isBlank()) return;
        voice.say(step.say(), problem -> {
            if (mine != epoch || at != index || !state.equals(PLAYING)) return;
            if (problem != null) {
                // Not heard: this lesson goes on as if muted, and says why once.
                if (!voiceNoticed) { voiceNoticed = true; watcher.notice(problem); }
                voiceless = true; return;
            }
            heard = true; advanceWhenDone();
        });
    }
    /** One tick of the writing: every item of the step shows as much as its share of the elapsed time. */
    private void frame() {
        if (!state.equals(PLAYING) || lesson == null) return;
        long now = SystemClock.uptimeMillis(); drawnFor = Math.min(drawTotal, drawnFor + FRAME_MS);
        for (int i = 0; i < placed.size(); i++) {
            long length = Math.max(1, BoardScript.itemMillis(placed.get(i)));
            float fraction = (drawnFor - starts[i]) / (float) length;
            if (fraction > 0) board.reveal(placed.get(i), Math.min(1, fraction));
        }
        if (drawnFor >= drawTotal) drawn = true;
        if (drawn && (heard || silentLongEnough(now))) { advance(); return; }
        main.postDelayed(frame, FRAME_MS);
    }
    private boolean silentLongEnough(long now) {
        BoardScript.Step step = lesson.steps().get(index);
        return (muted || voiceless || step.say().isBlank()) && now - stepStarted >= Math.max(drawTotal, BoardScript.readMillis(step.say()));
    }
    private void advanceWhenDone() { if (drawn && state.equals(PLAYING)) advance(); }
    private void advance() {
        main.removeCallbacks(frame);
        for (JSONObject item : placed) board.reveal(item, 1);
        index++; int mine = epoch;
        main.postDelayed(() -> { if (mine == epoch && state.equals(PLAYING)) step(); }, BETWEEN_STEPS_MS);
    }
    private void finish() {
        main.removeCallbacks(frame); board.revealAll(); voice.stop();
        state = IDLE; lesson = null; placed = new ArrayList<>(); epoch++; tell(); watcher.wrote(null);
    }

    /** Holds the lesson where it is: the writing freezes and the voice stops. */
    void pause() { if (!state.equals(PLAYING)) return; state = PAUSED; main.removeCallbacks(frame); voice.stop(); tell(); }
    /** Goes on: the step's words are read again from their start while its writing continues. */
    void resume() {
        if (!state.equals(PAUSED) || lesson == null) return;
        state = PLAYING; tell();
        if (index < lesson.steps().size()) { stepStarted = SystemClock.uptimeMillis(); speak(lesson.steps().get(index)); main.post(frame); } else finish();
    }
    /** Shows the rest of this step at once and moves to the next. */
    void skip() {
        if (lesson == null || !(state.equals(PLAYING) || state.equals(PAUSED))) return;
        voice.stop(); state = PLAYING; drawn = true; advance();
    }
    /** Ends the lesson (or the wait for it): what is written stays, the rest is not written. */
    void stop() {
        if (state.equals(IDLE)) return;
        Runnable abort = cancel.getAndSet(null);
        // The worker is inside the request: what aborts it runs on a thread of its own (it closes a connection).
        if (abort != null) { Thread stopper = new Thread(abort, "board-teacher-stop"); stopper.setDaemon(true); stopper.start(); }
        boolean wrote = lesson != null;
        main.removeCallbacks(frame); board.revealAll(); voice.stop();
        state = IDLE; lesson = null; placed = new ArrayList<>(); epoch++; tell();
        if (wrote) watcher.wrote(null);
    }
    void close() { stop(); closed = true; voice.close(); worker.shutdown(); }
}
