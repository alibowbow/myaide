package dev.jun.gyeot;

import android.app.Application;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import dev.jun.gyeot.core.RecordPauses;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowSettings;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * Nothing of ours is in the video (준: "같이 녹화되면 안되지", "녹화하는 동안 모든 오버레이"): a fake screen hands a frame every 16ms,
 * stamped on the recorder's clock, through the same gate the file uses (the shared RecordPauses), while the real recording
 * bubble counts down and goes, our other overlays (the voice bubble, the edge handle, 퍼레's card) are veiled, a touch on any
 * see-through one pauses first and they all show, and 재개 veils them again. No frame taken while any of them could be seen
 * (or within two frames after) reaches the file; the frames in between do.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RecordGateTests {
    private static final long FRAME_MS = 16;
    private Application app;
    private AppStore store;
    private FloatingButton voice;
    private EdgeButton edge;
    private BuddyOverlay buddy;

    @Before public void start() throws Exception {
        app = RuntimeEnvironment.getApplication();
        store = ((GyeotApp) app).store();
        ShadowSettings.setCanDrawOverlays(true);
        // The recorder's clock is the looper's here, so the frames' times and the overlays' moves are on one clock.
        ScreenRecordService.clock = () -> SystemClock.uptimeMillis() * 1000L;
        set("running", true); set("saving", false); set("counting", false); set("startedMono", 0L); set("pauses", new RecordPauses());
    }
    @After public void end() throws Exception {
        set("running", false); set("counting", false); set("startedMono", 0L); set("pauses", new RecordPauses());
        RecordControl.changed(app);
        if (voice != null) voice.close();
        if (edge != null) edge.close();
        if (buddy != null) buddy.hide();
        ScreenRecordService.clock = () -> System.nanoTime() / 1000;
        ShadowSettings.setCanDrawOverlays(false);
    }
    private static void set(String name, Object value) throws Exception { Field f = ScreenRecordService.class.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
    private static Object get(String name) throws Exception { Field f = ScreenRecordService.class.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    private static boolean seen(View v) { return v != null && v.isAttachedToWindow() && v.getVisibility() == View.VISIBLE && v.getAlpha() > 0; }
    private static void touch(View v) {
        long now = SystemClock.uptimeMillis();
        MotionEvent down = MotionEvent.obtain(now, now, MotionEvent.ACTION_DOWN, 5, 5, 0), up = MotionEvent.obtain(now, now + 40, MotionEvent.ACTION_UP, 5, 5, 0);
        v.dispatchTouchEvent(down); v.dispatchTouchEvent(up); down.recycle(); up.recycle();
    }

    /** The fake screen: one frame every 16ms, what the file keeps of it, and whether anything of ours was in it. */
    private final class Film {
        final List<Long> written = new ArrayList<>(), tainted = new ArrayList<>(), ourFrames = new ArrayList<>();
        final ArrayDeque<Boolean> lately = new ArrayDeque<>();
        final List<BooleanSupplier> ours = new ArrayList<>();
        void run(long ms) {
            for (long t = 0; t < ms; t += FRAME_MS) {
                shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(FRAME_MS));
                boolean any = false;
                for (BooleanSupplier o : ours) any |= o.getAsBoolean();
                lately.addLast(any);
                if (lately.size() > 3) lately.removeFirst();
                // This frame shows something of ours if it was drawn now or in the two frames before (the screen lags a little).
                boolean in = lately.contains(true);
                long pts = ScreenRecordService.now();
                if (in) ourFrames.add(pts);
                if (ScreenRecordService.pauses().video(pts, true) >= 0) { written.add(pts); if (in) tainted.add(pts); }
            }
        }
    }

    @Test public void framesTakenWhileAnythingOfOursIsOnTheScreenNeverReachTheFile() throws Exception {
        // Our other overlays, as they float over other apps: the voice bubble, the edge handle and 퍼레's card.
        store.settings.edit().putBoolean("edgeButton", true).putInt("bubbleX", 16).putInt("bubbleY", 600).apply();
        voice = new FloatingButton(app, store.settings, () -> {}, () -> {}, null, null); voice.show();
        PhoneService phone = Robolectric.buildService(PhoneService.class).create().get();
        edge = new EdgeButton(phone, store); edge.refresh();
        buddy = new BuddyOverlay(app, () -> {}); buddy.show();
        shadowOf(Looper.getMainLooper()).idle();
        Film film = new Film();
        film.ours.add(() -> RecordBubble.current() != null && RecordBubble.current().showing());
        film.ours.add(() -> seen(voice.view()));
        film.ours.add(() -> seen(edge.handle()));
        film.ours.add(() -> buddy.shown() && seen(buddyCard()));
        assertTrue("the voice bubble is up", seen(voice.view()));
        assertTrue("the edge handle is up: attached " + edge.handle().isAttachedToWindow() + " alpha " + edge.handle().getAlpha() + " vis " + edge.handle().getVisibility() + " on " + store.edgeButton() + " armed " + PhoneService.gate.armed() + " power " + app.getSystemService(android.os.PowerManager.class).isInteractive(), seen(edge.handle()));

        // Started: held before the encoder even runs; the recording bubble says 곧 시작 at the bubble's spot.
        ScreenRecordService.hold(app);
        assertTrue(ScreenRecordService.counting());
        RecordControl.changed(app);
        film.run(300);
        RecordBubble bubble = RecordBubble.current();
        assertNotNull(bubble); assertTrue("the recording bubble is on the screen before the start", bubble.showing());
        assertEquals("곧 시작", bubble.time.getText().toString());
        assertEquals("nothing written before the encoder runs", 0, film.written.size());
        // The encoder runs: 3, 2, 1, then everything of ours is veiled, and the recording starts once that is drawn.
        set("startedMono", (Long) get("heldAt"));
        RecordControl.changed(app);
        film.run(500);
        assertEquals("countdown", RecordControl.screen());
        assertTrue(bubble.time.getText().toString(), bubble.time.getText().toString().endsWith("초 뒤 시작"));
        assertEquals("nothing written while it counts down", 0, film.written.size());
        film.run(2_500);
        assertEquals("no frame with anything of ours in it was written at the start: " + film.tainted, List.of(), film.tainted);
        assertEquals("on", RecordControl.screen());
        assertTrue(RecordControl.veiled());
        assertFalse("the recording bubble is see-through", bubble.showing());
        assertFalse("its bar is gone: only the orb is left to touch", bubble.barShown());
        assertFalse("the voice bubble too", seen(voice.view()));
        assertFalse("the edge handle too", seen(edge.handle()));
        assertTrue("…still there to touch", voice.view().isAttachedToWindow() && edge.handle().isAttachedToWindow());
        int first = film.written.size();
        assertTrue("it records once everything of ours is gone: " + first, first > 20);
        assertTrue("the time starts at 0:00 when it goes: " + ScreenRecordService.elapsedMs(), ScreenRecordService.elapsedMs() < 1_500);

        // A touch on the see-through recording bubble: paused at the touch's time first, then everything shows.
        touch(bubble.orb);
        assertEquals("paused", RecordControl.screen());
        assertFalse(RecordControl.veiled());
        assertTrue("the recording bubble shows once it is paused", bubble.showing() && bubble.barShown());
        assertEquals("재개", bubble.toggle.getContentDescription().toString());
        assertTrue("the voice bubble and the handle are back", seen(voice.view()) && seen(edge.handle()));
        film.run(800);
        int paused = film.written.size();
        // 재개: everything veiled first, the recording goes on once that is drawn.
        assertTrue(bubble.toggle.performClick());
        assertFalse("off the screen at once", bubble.showing() || seen(voice.view()) || seen(edge.handle()));
        assertEquals("still paused until it is gone", "paused", RecordControl.screen());
        film.run(800);
        assertEquals("on", RecordControl.screen());
        assertTrue("it records again", film.written.size() - paused > 30);
        // A touch on the see-through edge handle pauses too; so does one on the see-through voice bubble.
        touch(edge.handle());
        assertEquals("paused", RecordControl.screen());
        assertTrue(seen(edge.handle()));
        film.run(400);
        ScreenRecordService.resume(app);
        film.run(800);
        assertEquals("on", RecordControl.screen());
        touch(voice.orb());
        assertEquals("paused", RecordControl.screen());
        assertNull("the touch only paused: no menu opened under the finger", voice.menu());
        film.run(400);
        // Paused from the notification, then 정지: nothing of ours written, and the recording bubble goes with it.
        ScreenRecordService.resume(app);
        film.run(800);
        ScreenRecordService.pause(app);
        assertTrue(bubble.showing());
        int before = film.written.size();
        film.run(400);
        assertEquals("nothing written while paused", before, film.written.size());
        set("saving", true);
        RecordControl.changed(app);
        assertNull("finishing the file: the recording bubble goes", RecordBubble.current());

        assertEquals("no frame with anything of ours in it was written: " + film.tainted, List.of(), film.tainted);
        assertTrue("…while something of ours really was on the screen during " + film.ourFrames.size() + " frames (the gate kept them out)", film.ourFrames.size() > 100);
        // And the file plays on without a hole where they were.
        RecordPauses gate = ScreenRecordService.pauses();
        long last = -1, gap = 0;
        for (long pts : film.written) { long t = gate.map(pts); if (last >= 0) gap = Math.max(gap, t - last); last = t; }
        assertTrue("no hole in the file: " + gap, gap <= 2 * FRAME_MS * 1000);
    }

    @Test public void withNowhereToFloatItStartsAtOnceWithNothingOfOursOnTheScreen() throws Exception {
        ShadowSettings.setCanDrawOverlays(false);
        ScreenRecordService.hold(app);
        assertFalse("no countdown nobody can see", ScreenRecordService.counting());
        set("startedMono", (Long) get("heldAt"));
        // As the service does once the encoder runs with no bubble to count down on.
        RecordControl.clearThen(app, () -> ScreenRecordService.go(app));
        Film film = new Film();
        film.run(400);
        assertNull(RecordBubble.current());
        assertEquals("on", RecordControl.screen());
        assertTrue(film.written.size() >= 15);
    }

    private View buddyCard() { try { Field f = BuddyOverlay.class.getDeclaredField("card"); f.setAccessible(true); return (View) f.get(buddy); } catch (ReflectiveOperationException e) { throw new AssertionError(e); } }
}
