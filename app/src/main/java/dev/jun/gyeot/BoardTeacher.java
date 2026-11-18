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
import org.json.JSONArray;
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
    /** The board's lessons as they are kept ([{id, title, says}]); a new lesson is added to them. Set with the board. */
    private JSONArray lessons = new JSONArray();
    /** The board's text scale (글자 크기) and letters (글씨체): what a new lesson is written at and in. */
    float textScale = 1; boolean hand;
    /** Giving a kept lesson again: its items are on the board already and only shown anew. */
    private boolean replaying; private String lessonId = ""; private List<List<JSONObject>> kept = new ArrayList<>();

    BoardTeacher(BoardView board, BoardVoice voice, Brain brain, Watcher watcher) {
        this.board = board; this.voice = voice; this.brain = brain; this.watcher = watcher;
        voice.onLost = () -> { if (state.equals(PLAYING)) pause(); };
    }
    String state() { return state; }
    void lessons(JSONArray value) { lessons = value == null ? new JSONArray() : value; }
    JSONArray lessons() { return lessons; }
    /** The newest lesson that still has writing on the board, or "". */
    String latest() {
        for (int i = lessons.length() - 1; i >= 0; i--) { String id = lessons.optJSONObject(i).optString("id"); if (!written(id, 0).isEmpty()) return id; }
        return "";
    }
    private JSONObject lesson(String id) { for (int i = 0; i < lessons.length(); i++) if (lessons.optJSONObject(i).optString("id").equals(id)) return lessons.optJSONObject(i); return null; }
    /** A lesson's items still on the board from a step on, step by step (a step whose writing was erased is empty). */
    private List<List<JSONObject>> written(String id, int from) {
        JSONObject record = lesson(id); List<List<JSONObject>> steps = new ArrayList<>(); if (record == null) return steps;
        int count = record.optJSONArray("says") == null ? 0 : record.optJSONArray("says").length(); boolean any = false;
        for (int k = 0; k < count; k++) steps.add(new ArrayList<>());
        JSONArray items = board.items();
        for (int i = 0; i < items.length(); i++) { JSONObject item = items.optJSONObject(i); if (item != null && id.equals(item.optString("ls")) && item.optInt("st") >= from && item.optInt("st") < count) { steps.get(item.optInt("st")).add(item); any = true; } }
        // Steps at the end that left nothing on the board (the lesson was stopped early, or they were erased) are not given again.
        while (!steps.isEmpty() && steps.get(steps.size() - 1).isEmpty()) steps.remove(steps.size() - 1);
        return any ? steps : new ArrayList<>();
    }
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
        String instructions = BoardScript.instructions(language, textScale, BoardScript.shown(board.items()).size()), input = BoardScript.request(request, Board.words(board.items(), 6000), files != null && !files.isEmpty());
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
        lesson = made; flow = new BoardScript.Flow(board.items(), board.measure, textScale, hand); index = 0; voiceNoticed = false; voiceless = false; replaying = false;
        lessonId = java.util.UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        JSONArray says = new JSONArray(); for (BoardScript.Step step : made.steps()) says.put(step.say());
        lessons.put(dev.jun.gyeot.core.Jsons.obj("id", lessonId, "title", made.title(), "says", says));
        state = PLAYING; step();
    }
    /**
     * Gives a kept lesson again from a step: its writing from there on disappears and is written anew while its words
     * are read. Nothing happens while a lesson is on; says so when the lesson's writing is no longer on the board.
     */
    void replay(String id, int from) {
        if (closed || busy()) return;
        JSONObject record = lesson(id); List<List<JSONObject>> steps = record == null ? new ArrayList<>() : written(id, Math.max(0, from));
        if (steps.isEmpty()) { watcher.notice("다시 보여 줄 판서가 판에 남아 있지 않아요."); return; }
        List<BoardScript.Step> script = new ArrayList<>(); JSONArray says = record.optJSONArray("says");
        for (int k = 0; k < steps.size(); k++) { script.add(new BoardScript.Step(says.optString(k), List.of())); if (k >= from) for (JSONObject item : steps.get(k)) board.reveal(item, 0); }
        epoch++; lesson = new BoardScript.Lesson(record.optString("title"), script); kept = steps; replaying = true; lessonId = id; index = Math.max(0, Math.min(from, steps.size() - 1)); voiceNoticed = false; voiceless = false;
        state = PLAYING; step();
    }
    private void step() {
        if (lesson == null || index >= lesson.steps().size()) { finish(); return; }
        BoardScript.Step step = lesson.steps().get(index);
        placed = replaying ? kept.get(index) : flow.place(step); starts = new long[placed.size()]; drawTotal = 0;
        for (int i = 0; i < placed.size(); i++) {
            starts[i] = drawTotal; drawTotal += BoardScript.itemMillis(placed.get(i));
            if (replaying) board.reveal(placed.get(i), 0); else board.add(Board.inLesson(placed.get(i), lessonId, index), true);
        }
        if (!placed.isEmpty()) {
            // The page follows the writing: the lowest thing of the step comes into view (a mark on a picture stays where the picture is).
            float lowest = 0, highest = Float.MAX_VALUE; for (JSONObject item : placed) { float[] b = Board.bounds(item, board.measure); lowest = Math.max(lowest, b[3]); highest = Math.min(highest, b[1]); }
            board.follow(lowest - highest > board.shown() * .5f ? highest + board.shown() * .45f : lowest);
            if (!replaying) watcher.wrote(index == 0 ? lesson.title() : null);
        }
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
        boolean again = replaying;
        state = IDLE; lesson = null; placed = new ArrayList<>(); replaying = false; epoch++; tell(); if (!again) watcher.wrote(null);
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
        boolean wrote = lesson != null && !replaying;
        main.removeCallbacks(frame); board.revealAll(); voice.stop();
        state = IDLE; lesson = null; placed = new ArrayList<>(); replaying = false; epoch++; tell();
        if (wrote) watcher.wrote(null);
    }
    void close() { stop(); closed = true; voice.close(); worker.shutdown(); }
}
