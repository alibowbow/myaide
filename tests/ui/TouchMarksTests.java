package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Switch;
import android.widget.TextView;
import dev.jun.gyeot.core.RecordPauses;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 누른 위치 표시 (준: "녹화하기에 누른곳 표시하기 기능이 없어"): a compact, remembered switch on the 녹화 시작 sheet that needs 폰
 * 조작 (one tap to it when off); while frames are written a soft circle where the screen was touched, in a layer of its own that
 * is in the video (never veiled) and takes no touch; from exact fingers (down, move, up, a fade) and, as Android gives an app
 * no way to watch other apps' fingers, from 폰 조작's pressed and scrolled views; nothing while off or paused, and nothing for
 * our own controls.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TouchMarksTests {
    private static final String SHOTS = "/tmp/claude-0/-home-user/138b3987-18a5-5339-9471-114de8ffce30/scratchpad/satellite";
    private Application app;
    private AppStore store;
    private PhoneService phone;

    @Before public void start() throws Exception {
        app = RuntimeEnvironment.getApplication();
        store = ((GyeotApp) app).store();
        store.settings.edit().remove(TouchMarks.SETTING).apply();
        ScreenRecordService.clock = () -> SystemClock.uptimeMillis() * 1000L;
    }
    @After public void end() throws Exception {
        TouchMarks.stop();
        set(ScreenRecordService.class, "running", false); set(ScreenRecordService.class, "startedMono", 0L); set(ScreenRecordService.class, "pauses", new RecordPauses());
        RecordControl.changed(app);
        set(PhoneService.class, "instance", null);
        ScreenRecordService.clock = () -> System.nanoTime() / 1000;
        Field open = ScreenRecordActivity.class.getDeclaredField("open"); open.setAccessible(true); open.set(null, false);
    }
    private static void set(Class<?> type, String name, Object value) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
    /** 폰 조작 connected, as Android binds it. */
    private void phoneOn() throws Exception { phone = Robolectric.buildService(PhoneService.class).create().get(); set(PhoneService.class, "instance", phone); }
    /** A 화면 녹화 writing frames now (or paused). */
    private static void recording(boolean paused) throws Exception {
        long now = ScreenRecordService.now();
        RecordPauses p = new RecordPauses(); if (paused) p.pause(now);
        set(ScreenRecordService.class, "running", true); set(ScreenRecordService.class, "saving", false); set(ScreenRecordService.class, "counting", false);
        set(ScreenRecordService.class, "pauses", p); set(ScreenRecordService.class, "startedMono", now - 5_000_000L);
    }
    private static void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private static MotionEvent finger(int action, float x, float y) { long now = SystemClock.uptimeMillis(); return MotionEvent.obtain(now, now, action, x, y, 0); }

    private ScreenRecordActivity sheet() {
        ScreenRecordActivity a = Robolectric.buildActivity(ScreenRecordActivity.class).setup().get();
        idle(10);
        assertNotNull(a.sheet);
        return a;
    }
    private static View sheetView(ScreenRecordActivity a) {
        View decor = a.sheet.getWindow().getDecorView();
        var m = decor.getResources().getDisplayMetrics();
        int width = Math.min(m.widthPixels - Math.round(32 * m.density), Math.round(380 * m.density));
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        return decor;
    }

    @Test public void theSheetsSwitchIsCompactRememberedAndAsksFor폰조작WhenItIsOff() throws Exception {
        float d = app.getResources().getDisplayMetrics().density;
        // 폰 조작 off: the switch cannot be turned on; its line says why and opens it in one tap.
        ScreenRecordActivity a = sheet();
        View panel = sheetView(a);
        Switch toggle = (Switch) find(panel, v -> v instanceof Switch s && "누른 위치 표시".contentEquals(s.getText()));
        assertNotNull("누른 위치 표시 under 녹음할 오디오", toggle);
        assertFalse(toggle.isEnabled()); assertFalse(toggle.isChecked());
        View need = exact(panel, "폰 조작이 필요해요 · 켜기 ›");
        assertNotNull(need);
        while (shadowOf(app).getNextStartedActivity() != null) { }
        assertTrue(need.performClick());
        Intent settings = shadowOf(app).getNextStartedActivity();
        assertNotNull("Android's accessibility settings in one tap", settings);
        assertEquals(Settings.ACTION_ACCESSIBILITY_SETTINGS, settings.getAction());
        assertTrue("with 들퍼레 폰 조작 pointed out", settings.getStringExtra(":settings:fragment_args_key").endsWith("PhoneService"));
        set(ScreenRecordActivity.class, "open", false);

        // 폰 조작 on: off by default, compact like Samsung's line; on, it says the circles are rough; kept for the next time.
        phoneOn();
        ScreenRecordActivity b = sheet();
        View panel2 = sheetView(b);
        Switch on = (Switch) find(panel2, v -> v instanceof Switch s && "누른 위치 표시".contentEquals(s.getText()));
        assertTrue(on.isEnabled()); assertFalse("off by default", on.isChecked());
        assertTrue("one compact line, not a 48dp block: " + on.getHeight() / d, on.getHeight() <= 46 * d);
        assertNull("the note shows only when it is on", exact(panel2, "누른 곳은 대략적으로 보여요"));
        on.setChecked(true);
        View panel3 = sheetView(b);
        assertNotNull(exact(panel3, "누른 곳은 대략적으로 보여요"));
        shotSheet(panel3, "start_sheet_touches");
        assertTrue(exact(panel3, "녹화 시작").performClick());
        assertTrue("remembered", TouchMarks.wanted(store.settings));
        set(ScreenRecordActivity.class, "open", false);
        ScreenRecordActivity c = sheet();
        Switch again = (Switch) find(sheetView(c), v -> v instanceof Switch s && "누른 위치 표시".contentEquals(s.getText()));
        assertTrue("on again next time", again.isChecked());
    }

    @Test public void aCircleFollowsTheFingerFadesAfterItLiftsAndIsNeverVeiled() throws Exception {
        phoneOn();
        recording(false);
        TouchMarks.start(app);
        TouchMarks marks = TouchMarks.current();
        assertNotNull(marks);
        assertTrue("it never takes a touch", (marks.params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) != 0);
        assertEquals("폰 조작's own window (Android lets touches through it)", WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, marks.params.type);
        // Everything of ours is veiled while frames are written; this layer is meant to be in the video and stays.
        RecordControl.clearThen(app, () -> {});
        assertTrue(RecordControl.veiled());
        idle(50);
        assertEquals(1f, marks.layer.getAlpha(), 0f);
        assertTrue(marks.layer.isAttachedToWindow());
        TouchMarks.motion(finger(MotionEvent.ACTION_DOWN, 400, 900));
        assertEquals(1, marks.shown().size());
        TouchMarks.motion(finger(MotionEvent.ACTION_MOVE, 420, 960));
        TouchMarks.Mark m = marks.shown().get(0);
        assertArrayEquals("it follows the drag", new float[]{420, 960}, m.now(), 0.01f);
        assertEquals("with a short trail", 2, m.path.size());
        idle(400);
        assertEquals("held while the finger is down", 1f, m.alpha(SystemClock.uptimeMillis()), 0f);
        TouchMarks.motion(finger(MotionEvent.ACTION_UP, 420, 960));
        idle(150);
        float half = m.alpha(SystemClock.uptimeMillis());
        assertTrue("fading after the lift: " + half, half > 0.3f && half < 0.7f);
        idle(200);
        assertTrue("gone ~300ms after the lift", marks.shown().isEmpty());
        // For 준: a frame with a finger down, a pressed button and a drag in it (the app screen drawn first).
        Canvas[] canvas = new Canvas[1]; Bitmap frame = appScreen(canvas);
        TouchMarks.motion(finger(MotionEvent.ACTION_DOWN, 820, 1320));
        TouchMarks.pressed(new Rect(120, 480, 520, 600));
        long t = SystemClock.uptimeMillis();
        MotionEvent drag = MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, 300, 2000, 0); TouchMarks.motion(drag);
        for (int i = 1; i <= 6; i++) TouchMarks.motion(MotionEvent.obtain(t, t + i * 16, MotionEvent.ACTION_MOVE, 300 + i * 30, 2000 - i * 70, 0));
        shotFrame(marks, frame, canvas[0], "touch_marks_frame");
        RecordControl.unveil();
    }

    @Test public void without폰조작sFingersTheCircleIsWhereAViewWasPressedOrScrolled() throws Exception {
        phoneOn();
        recording(false);
        TouchMarks.start(app);
        TouchMarks marks = TouchMarks.current();
        PhoneService.touched(AccessibilityEvent.TYPE_VIEW_CLICKED, "com.example.chat", app.getPackageName(), new Rect(100, 200, 300, 260), 0, 0);
        assertEquals(1, marks.shown().size());
        assertArrayEquals("at the pressed view's middle", new float[]{200, 230}, marks.shown().get(0).now(), 0.01f);
        // A list scrolled down by 300px: the finger went up, a short trail across its middle.
        PhoneService.touched(AccessibilityEvent.TYPE_VIEW_SCROLLED, "com.example.chat", app.getPackageName(), new Rect(0, 400, 1000, 1600), 0, 300);
        TouchMarks.Mark scroll = marks.shown().get(1);
        assertTrue(scroll.path.size() >= 3);
        assertTrue("upward, against the content", scroll.now()[1] < scroll.path.get(0)[1]);
        // 들퍼레's own windows (the recording's controls): never a circle.
        PhoneService.touched(AccessibilityEvent.TYPE_VIEW_CLICKED, app.getPackageName(), app.getPackageName(), new Rect(0, 0, 100, 100), 0, 0);
        assertEquals(2, marks.shown().size());
        idle(TouchMarks.HOLD_MS + TouchMarks.FADE_MS + 50);
        assertTrue(marks.shown().isEmpty());
    }

    @Test public void nothingIsDrawnWhenOffOrPausedAndPausingClearsIt() throws Exception {
        phoneOn();
        recording(false);
        // Off: no layer at all.
        TouchMarks.pressed(new Rect(0, 0, 100, 100));
        assertNull(TouchMarks.current());
        TouchMarks.start(app);
        TouchMarks marks = TouchMarks.current();
        // Paused: nothing is written, nothing is drawn.
        recording(true);
        TouchMarks.pressed(new Rect(0, 0, 100, 100));
        TouchMarks.motion(finger(MotionEvent.ACTION_DOWN, 50, 50));
        assertTrue(marks.shown().isEmpty());
        // Writing, a circle; then a touch on one of our see-through controls pauses first and the circle goes with it.
        recording(false);
        TouchMarks.pressed(new Rect(0, 0, 100, 100));
        assertEquals(1, marks.shown().size());
        RecordControl.clearThen(app, () -> {});
        View control = new View(app);
        assertTrue(RecordControl.veiledTouch(control, finger(MotionEvent.ACTION_DOWN, 10, 10)));
        RecordControl.veiledTouch(control, finger(MotionEvent.ACTION_UP, 10, 10));
        assertEquals("paused by the touch", "paused", RecordControl.screen());
        assertTrue("its circle is gone and none for the control's touch", marks.shown().isEmpty());
        PhoneService.touched(AccessibilityEvent.TYPE_VIEW_CLICKED, "com.example.chat", app.getPackageName(), new Rect(0, 0, 100, 100), 0, 0);
        assertTrue("while paused nothing is drawn", marks.shown().isEmpty());
        // The recording ends: the layer goes.
        TouchMarks.stop(); idle(10);
        assertNull(TouchMarks.current());
        assertFalse(marks.layer.isAttachedToWindow());
    }

    @Test public void 폰조작AsksForPressAndScrollEventsOnlyWhileSuchARecordingIsOn() throws Exception {
        phoneOn();
        Field watch = PhoneService.class.getDeclaredField("touchWatch"); watch.setAccessible(true);
        PhoneService.watchTouches(true); idle(10);
        assertTrue(watch.getBoolean(phone));
        if (phone.getServiceInfo() != null) assertTrue((phone.getServiceInfo().eventTypes & AccessibilityEvent.TYPE_VIEW_CLICKED) != 0);
        PhoneService.watchTouches(false); idle(10);
        assertFalse(watch.getBoolean(phone));
        if (phone.getServiceInfo() != null) assertEquals(0, phone.getServiceInfo().eventTypes & (AccessibilityEvent.TYPE_VIEW_CLICKED | AccessibilityEvent.TYPE_VIEW_SCROLLED));
    }

    // ── helpers ─────────────────────────────────────────────────────
    private static View find(View v, Predicate<View> match) {
        if (match.test(v)) return v;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View exact(View root, String words) { return find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().equals(words)); }
    private Bitmap appScreen(Canvas[] out) {
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        var m = decor.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        }
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c); a.finish();
        out[0] = c; return b;
    }
    private void shotSheet(View panel, String name) throws Exception {
        Canvas[] c = new Canvas[1]; Bitmap b = appScreen(c);
        c[0].drawColor(0x88000000);
        c[0].save(); c[0].translate((b.getWidth() - panel.getWidth()) / 2f, (b.getHeight() - panel.getHeight()) / 2f); panel.draw(c[0]); c[0].restore();
        save(b, name);
    }
    private void shotFrame(TouchMarks marks, Bitmap b, Canvas c, String name) throws Exception {
        marks.layer.measure(View.MeasureSpec.makeMeasureSpec(b.getWidth(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(b.getHeight(), View.MeasureSpec.EXACTLY));
        marks.layer.layout(0, 0, b.getWidth(), b.getHeight());
        marks.layer.draw(c);
        save(b, name);
    }
    private static void save(Bitmap b, String name) throws Exception {
        for (String dir : new String[]{System.getProperty("ui.output", "build/ui-verification"), SHOTS}) {
            File f = new File(dir); f.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(f, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        }
    }
}
