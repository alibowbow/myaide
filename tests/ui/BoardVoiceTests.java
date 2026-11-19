package dev.jun.gyeot;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import dev.jun.gyeot.core.BoardScript;
import dev.jun.gyeot.core.GeminiProtocol;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.LongConsumer;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
@LooperMode(LooperMode.Mode.PAUSED)
public class BoardVoiceTests {
    private AppStore store;
    @After public void closeDatabases() {
        if (store != null) { store.mainChat.close(); store.usage.close(); }
    }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void seconds(int count) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(count)); }
    private static final String FIRST = "첫 문장이에요.", NEXT = "다음 단계예요.";

    private static class Line implements BoardVoice.LiveVoice.Line {
        final BoardVoice.LiveVoice.Events events;
        final List<String> sent = new ArrayList<>();
        boolean closed;
        Line(BoardVoice.LiveVoice.Events events) { this.events = events; }
        public void say(String text) { sent.add(text); }
        public void close() { closed = true; }
        void ready() { events.ready(); idle(); }
        void sound(int marker) {
            byte[] pcm = new byte[480]; Arrays.fill(pcm, (byte) marker);
            events.audio(pcm); idle();
        }
        void end() { events.finished(); idle(); }
        void fail(String why, boolean retryable) { events.failed(why, retryable); idle(); }
    }
    private static class Output implements BoardVoice.LiveVoice.Out {
        final List<Integer> heard = new ArrayList<>();
        Runnable completion;
        public boolean play(byte[] pcm, LongConsumer started) {
            heard.add((int) pcm[0]); if (started != null) started.accept(1); return true;
        }
        public void finish(Runnable done) { completion = done; }
        public void flush() { completion = null; }
        public void close() {}
        void finishPlayback() { assertNotNull(completion); completion.run(); idle(); }
    }
    private static class Reader {
        final List<Line> lines = new ArrayList<>();
        final List<String> results = new ArrayList<>();
        final Output out = new Output();
        final BoardVoice.LiveVoice live = new BoardVoice.LiveVoice((model, voice, events) -> {
            assertEquals(GeminiProtocol.FLASH_25, model); assertEquals("Kore", voice);
            Line line = new Line(events); lines.add(line); return line;
        }, () -> out, GeminiProtocol.FLASH_25, "Kore");
        void say(String text) { live.say(text, results::add); idle(); }
        void heardOnce() {
            out.finishPlayback(); assertEquals(1, results.size()); assertNull(results.get(0));
        }
    }

    @Test public void refusedHelperLeavesCurrentAudioAndPreparedStepOnSameLiveModel() {
        Reader r = new Reader();
        r.live.warm(); assertEquals(1, r.lines.size()); r.say(FIRST);
        Line main = r.lines.get(0); main.ready(); main.sound(11);
        r.live.prepare(NEXT);
        Line helper = r.lines.get(1); helper.ready();
        assertEquals(List.of(NEXT), helper.sent);
        helper.sound(21); helper.fail("429 RESOURCE_EXHAUSTED", false);
        assertFalse(main.closed); assertTrue(helper.closed);
        main.sound(12); main.end(); r.heardOnce();
        assertEquals(List.of(FIRST, NEXT), main.sent);
        r.say(NEXT); main.sound(22); main.end(); r.out.finishPlayback();
        assertEquals(2, r.lines.size()); assertEquals(Arrays.asList(null, null), r.results);
        assertEquals(List.of(11, 12, 22), r.out.heard);
        r.live.close();
    }
    @Test public void disconnectBeforeAudioReconnectsOnceAndIgnoresLateCallbacks() {
        Reader r = new Reader(); r.say(FIRST);
        Line failed = r.lines.get(0); failed.ready(); failed.fail("network disconnected", true);
        Line retry = r.lines.get(1); retry.ready(); failed.sound(9); failed.end();
        assertEquals(List.of(FIRST), retry.sent);
        retry.sound(13); retry.end(); r.heardOnce();
        assertEquals(List.of(13), r.out.heard); r.live.close();
    }
    @Test public void permanentFailuresAndAlreadyPlayedWordsAreNeverRetried() {
        Reader refused = new Reader(); refused.say(FIRST);
        refused.lines.get(0).fail("403 PERMISSION_DENIED", true);
        assertEquals(List.of("403 PERMISSION_DENIED"), refused.results); assertEquals(1, refused.lines.size());
        refused.live.close();
        Reader partial = new Reader(); partial.say(FIRST);
        Line line = partial.lines.get(0); line.ready(); line.sound(16); line.fail("network disconnected", true);
        assertEquals(1, partial.lines.size()); assertEquals(List.of("network disconnected"), partial.results);
        assertEquals(List.of(16), partial.out.heard); partial.live.close();
    }
    @Test public void silentTurnsHaveOneRetryAndCannotMarkSpeechAsHeard() {
        Reader r = new Reader(); r.say(FIRST);
        Line first = r.lines.get(0); first.ready(); first.sound(0); first.end();
        Line retry = r.lines.get(1); retry.ready(); retry.end();
        assertEquals(2, r.lines.size()); assertTrue(r.out.heard.isEmpty());
        assertEquals(1, r.results.size()); assertTrue(r.results.get(0).contains("음성 없이"));
        r.live.close();
    }
    @Test public void activeAudioCanExceedFortyFiveSecondsAndIdleSocketCannotCancelSpeaker() {
        Reader r = new Reader(); r.say(FIRST);
        Line main = r.lines.get(0); main.ready(); seconds(30); main.sound(8);
        seconds(30); main.sound(9); main.end(); main.fail("idle session closed", true);
        seconds(50); assertTrue(r.results.isEmpty()); r.heardOnce();
        assertEquals(List.of(8, 9), r.out.heard); r.live.close();
    }
    @Test public void stalledReaderTimesOutAndCancelledReaderCannotPlayLateAudio() {
        Reader stalled = new Reader(); stalled.say(FIRST); stalled.lines.get(0).ready(); seconds(46);
        assertEquals(1, stalled.results.size()); assertTrue(stalled.results.get(0).contains("45초")); stalled.live.close();
        Reader stopped = new Reader(); stopped.say(FIRST);
        Line old = stopped.lines.get(0); old.ready(); stopped.live.stop();
        old.sound(17); old.end(); seconds(46);
        assertTrue(stopped.results.isEmpty()); assertTrue(stopped.out.heard.isEmpty()); stopped.live.close();
    }
    @Test public void synchronousConnectionRefusalReportsTheReasonAndNextReadCanOpenAgain() {
        List<String> errors = new ArrayList<>();
        List<Line> fresh = new ArrayList<>();
        Output out = new Output();
        BoardVoice.LiveVoice live = new BoardVoice.LiveVoice((model, name, events) -> {
            if (errors.isEmpty()) throw new IllegalStateException("저장된 음성 키를 읽지 못했어요.");
            Line line = new Line(events); fresh.add(line); return line;
        }, () -> out, GeminiProtocol.FLASH_25, "Kore");
        live.say(FIRST, errors::add); idle(); assertEquals(List.of("저장된 음성 키를 읽지 못했어요."), errors);
        live.say(NEXT, errors::add); Line line = fresh.get(0); line.ready(); line.sound(11); line.end(); out.finishPlayback();
        assertNull(errors.get(1)); live.close();
    }

    private static final class ScriptVoice implements Speech.Voice {
        final List<String> lines = new ArrayList<>(); Consumer<String> completion;
        public void say(String text, Consumer<String> done) { lines.add(text); completion = done; }
        public void close() {}
        public void stop() {}
        void finish(String why) { completion.accept(why); idle(); }
    }
    private static void keyPresent(Context context) {
        // Only presence is checked. The injected readers never access a credential or the network.
        context.getSharedPreferences("secret", Context.MODE_PRIVATE).edit().putString(SecretStore.GEMINI, "fixture").apply();
    }
    @Test public void displayedSourceTracksPlaybackFromTheSelectedLiveReader() {
        Application context = RuntimeEnvironment.getApplication(); store = new AppStore(context); keyPresent(context);
        store.settings.edit().putString("boardLiveModel", GeminiProtocol.FLASH_25).apply();
        Reader reader = new Reader(); List<String> updates = new ArrayList<>(), results = new ArrayList<>();
        BoardVoice voice = new BoardVoice(context, store, kind -> reader.live); voice.onStatus = updates::add;
        voice.say(FIRST, results::add); Line line = reader.lines.get(0); line.ready();
        assertTrue(voice.status().startsWith("음성 요청 중 · Gemini 2.5"));
        line.sound(9); assertTrue(voice.status().startsWith("재생 중 · Gemini 2.5"));
        line.end(); assertTrue(results.isEmpty());
        reader.out.finishPlayback(); assertTrue(voice.status().startsWith("최근 재생 · Gemini 2.5"));
        assertEquals(3, updates.size()); assertNull(results.get(0)); voice.close();
    }
    @Test public void explicitAndInheritedLiveSelectionsKeepTheirRouteEvenWithoutValidatedInternet() {
        Application context = RuntimeEnvironment.getApplication(); store = new AppStore(context);
        assertFalse(Speech.online(context));
        ScriptVoice live = new ScriptVoice(); List<String> created = new ArrayList<>(), results = new ArrayList<>();
        BoardVoice voice = new BoardVoice(context, store, kind -> { created.add(kind); return live; });
        assertEquals(BoardVoice.DEVICE, voice.selected());
        store.settings.edit().putString("liveModel", GeminiProtocol.FLASH_25).apply();
        voice.say(FIRST, results::add); assertTrue(created.isEmpty()); assertTrue(results.get(0).contains("API 키"));
        keyPresent(context); voice.say(FIRST, results::add);
        assertEquals(List.of(BoardVoice.LIVE), created); live.finish("429 quota exceeded");
        assertTrue(voice.status().contains("한도 초과")); assertTrue(results.get(1).contains("429"));
        voice.stop(); store.settings.edit().putString("boardLiveModel", GeminiProtocol.FLASH_25).apply();
        voice.say(NEXT, results::add); live.finish(null);
        assertTrue(voice.status().contains("최근 재생 · Gemini 2.5")); assertNull(results.get(2));
        store.settings.edit().putBoolean("boardDeviceVoice", true).apply();
        voice.say(FIRST, results::add); live.finish(null);
        assertEquals(List.of(BoardVoice.LIVE, BoardVoice.DEVICE), created); assertTrue(voice.status().contains("최근 재생 · 기기 TTS"));
        voice.close();
    }
    @Test public void lessonResumeAndUnmuteRetryTheCurrentStepAfterLiveFailure() {
        Application context = RuntimeEnvironment.getApplication(); store = new AppStore(context); keyPresent(context);
        store.settings.edit().putString("boardLiveModel", GeminiProtocol.FLASH_25).apply();
        ScriptVoice live = new ScriptVoice(); List<String> notices = new ArrayList<>();
        BoardVoice voice = new BoardVoice(context, store, kind -> { assertEquals(BoardVoice.LIVE, kind); return live; });
        BoardTeacher teacher = new BoardTeacher(new BoardView(context), voice, null, new BoardTeacher.Watcher() {
            public void state(String state, int step, int steps, String caption) {}
            public void notice(String why) { notices.add(why); }
            public void wrote(String title) {}
        });
        teacher.begin(new BoardScript.Lesson("수업", List.of(new BoardScript.Step(FIRST, List.of()))));
        live.finish("429 quota exceeded"); teacher.pause(); teacher.resume();
        live.finish("network disconnected"); teacher.mute(true); teacher.mute(false);
        assertEquals(List.of(FIRST, FIRST, FIRST), live.lines); assertEquals(2, notices.size());
        teacher.close();
    }
}
