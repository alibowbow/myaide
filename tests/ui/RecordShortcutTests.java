package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.TextView;
import dev.jun.gyeot.core.CapturePlan;
import dev.jun.gyeot.core.CapturePlan.Way;
import dev.jun.gyeot.core.RecordPauses;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
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
 * 녹화·녹음·캡쳐 바로가기 (준: "녹화하기가 너무 번거로워. 홈에 바로가기를 만들자 … 그중 하나가 녹화하기야 캡쳐도 만들어줘. 녹화 시작
 * 일시정지 및 정지 버튼도 구현해줘 녹화화면상에서 바로 조작할 수 있기", "팝업아이콘 구성은 녹화하기 녹음하기 캡쳐 이 세가지로"): the
 * satellite's actions through the home screen's bridge reach the right screens and services (RecordControl, one controller);
 * the floating controls are small, pause, resume and stop, fold to a dot, and say what the notification says; 캡쳐 takes the
 * way the phone allows without a prompt first.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RecordShortcutTests {
    private Application app;
    private AppStore store;
    private static final String SHOTS = "/tmp/claude-0/-home-user/138b3987-18a5-5339-9471-114de8ffce30/scratchpad/satellite";

    @Before public void start() {
        app = RuntimeEnvironment.getApplication();
        store = ((GyeotApp) app).store();
        app.getSharedPreferences("recordPill", 0).edit().clear().commit();
    }
    @After public void end() throws Exception {
        screen(false, false, 0);
        store.recording = false; store.recordingPaused = false; store.recordingStartedAt = 0; store.recordingPauses = new RecordPauses();
        RecordPill.sync(app);
        set(ScreenCapture.class, "busy", false);
        ShadowSettings.setCanDrawOverlays(false);
    }

    // ── state, as the services hold it ──────────────────────────────
    private static void set(Class<?> type, String name, Object value) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
    private static Object get(Class<?> type, String name) throws Exception { Field f = type.getDeclaredField(name); f.setAccessible(true); return f.get(null); }
    /** A 화면 녹화 as ScreenRecordService holds it: running, its encoder started secondsAgo, paused or not. */
    private static void screen(boolean on, boolean paused, long secondsAgo) throws Exception {
        set(ScreenRecordService.class, "running", on);
        set(ScreenRecordService.class, "saving", false);
        long now = System.nanoTime() / 1000;
        RecordPauses p = new RecordPauses();
        if (paused) p.pause(now);
        set(ScreenRecordService.class, "pauses", p);
        set(ScreenRecordService.class, "paused", paused);
        set(ScreenRecordService.class, "startedMono", on ? now - secondsAgo * 1_000_000L : 0L);
    }
    private Intent nextService() { return shadowOf(app).getNextStartedService(); }
    private Intent nextActivity() { return shadowOf(app).getNextStartedActivity(); }
    private void drain() { while (nextService() != null) { } while (nextActivity() != null) { } }
    private static void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }
    private void expectService(Class<?> service, String action) {
        Intent i = nextService();
        assertNotNull("a start of " + service.getSimpleName() + " " + action, i);
        assertEquals(service.getName(), i.getComponent().getClassName());
        assertEquals(action, i.getAction());
    }
    private void expectStart(String mode) {
        Intent i = nextActivity();
        assertNotNull("the see-through start (" + mode + ")", i);
        assertEquals(ScreenRecordActivity.class.getName(), i.getComponent().getClassName());
        assertEquals(mode, i.getStringExtra(ScreenRecordActivity.MODE));
    }

    @Test public void theSatellitesActionsThroughTheHomeScreensBridgeReachTheRightScreensAndServices() throws Exception {
        org.robolectric.android.controller.ActivityController<HomeActivity> shown = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity home = shown.get();
        try {
            idle(100);
            WebView web = find(home.getWindow().getDecorView(), WebView.class);
            assertNotNull(web);
            Object gate = shadowOf(web).getJavascriptInterface("Gyeot");
            assertNotNull("the page's one door", gate);
            Field secret = HomeActivity.class.getDeclaredField("secret"); secret.setAccessible(true);
            String token = (String) secret.get(home);
            Method call = gate.getClass().getDeclaredMethod("call", String.class, String.class, String.class); call.setAccessible(true);
            java.util.function.Consumer<String> page = what -> { try { call.invoke(gate, token, "record", "[\"" + what + "\"]"); idle(10); } catch (Exception e) { throw new AssertionError(e); } };
            drain();
            // 녹화하기: Android's consent through the see-through start (the existing way, once).
            page.accept("screen");
            expectStart(null);
            // 녹음하기 without the microphone: the see-through start asks for it first.
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
            page.accept("audio");
            expectStart(ScreenRecordActivity.AUDIO);
            // With it: 녹음 starts at once in AssistantService.
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
            page.accept("audio");
            expectService(AssistantService.class, AssistantService.RECORD_START);
            // While 녹음 is on: its 정지, and the floating controls' pause and resume.
            store.recording = true; store.recordingStartedAt = System.currentTimeMillis();
            page.accept("audioStop"); expectService(AssistantService.class, AssistantService.RECORD_STOP);
            RecordControl.act(app, RecordControl.AUDIO_PAUSE); expectService(AssistantService.class, AssistantService.RECORD_PAUSE);
            store.recordingPaused = true;
            RecordControl.act(app, RecordControl.AUDIO_RESUME); expectService(AssistantService.class, AssistantService.RECORD_RESUME);
            store.recording = false; store.recordingPaused = false;
            // While 화면 녹화 is on: 정지 (and 녹화하기 again stops it rather than asking twice), pause, resume.
            screen(true, false, 5);
            page.accept("screenStop"); expectService(ScreenRecordService.class, "stop");
            page.accept("screen"); expectService(ScreenRecordService.class, "stop");
            assertNull("no second consent while it records", nextActivity());
            RecordControl.act(app, RecordControl.SCREEN_PAUSE); expectService(ScreenRecordService.class, "pause");
            RecordControl.act(app, RecordControl.SCREEN_RESUME);
            assertNull("not paused: nothing to resume", nextService());
            screen(true, true, 5);
            RecordControl.act(app, RecordControl.SCREEN_RESUME); expectService(ScreenRecordService.class, "resume");
            // 캡쳐 while it records and 폰 조작 is off: a frame of the recording itself, no second consent.
            drain();
            page.accept("capture");
            assertNull("the satellite gets a moment to leave the screen first", nextService());
            idle(CapturePlan.SETTLE_MS + 50);
            expectService(ScreenRecordService.class, "grab");
            set(ScreenCapture.class, "busy", false);
            // 캡쳐 with nothing on and 폰 조작 off: Android's consent once, then one frame.
            screen(false, false, 0);
            drain();
            page.accept("capture");
            idle(CapturePlan.SETTLE_MS + 50);
            expectStart(ScreenRecordActivity.CAPTURE);
            // What the page shows: the dot while one is on.
            assertEquals("{\"screen\":\"\",\"audio\":\"\"}", RecordControl.state(store).toString());
            screen(true, true, 5);
            assertEquals("{\"screen\":\"paused\",\"audio\":\"\"}", RecordControl.state(store).toString());
            assertFalse("not a bridge call it knows", RecordControl.act(app, "erase"));
        } finally {
            // The home screen leaves as it would (its static "on screen" flag is read by the taskbar's tests).
            shown.pause().stop().destroy();
        }
    }

    @Test public void captureTakesTheWayThePhoneAllowsWithoutAPromptFirst() throws Exception {
        assertEquals("폰 조작 on: it takes the screen itself, else the phone's own screenshot, else the consent",
            List.of(Way.SERVICE, Way.SYSTEM, Way.CONSENT), CapturePlan.ways(true, false, 35, false));
        assertEquals("Android 10 has no takeScreenshot: the phone's own screenshot", List.of(Way.SYSTEM, Way.CONSENT), CapturePlan.ways(true, false, 29, false));
        assertEquals("recording: a frame of the recording before a second consent", List.of(Way.SERVICE, Way.SYSTEM, Way.RECORDING), CapturePlan.ways(true, false, 35, true));
        assertEquals(List.of(Way.RECORDING), CapturePlan.ways(false, false, 35, true));
        assertEquals("폰 조작 off: the consent", List.of(Way.CONSENT), CapturePlan.ways(false, false, 35, false));
        assertEquals("paused by 보안 점검: the service is not used", List.of(Way.CONSENT), CapturePlan.ways(true, true, 35, false));
        // On this phone now: no accessibility service.
        assertEquals(List.of(Way.CONSENT), ScreenCapture.ways(app));
        screen(true, false, 3);
        assertEquals(List.of(Way.RECORDING), ScreenCapture.ways(app));
        assertTrue(CapturePlan.fileName(java.time.LocalDateTime.of(2026, 11, 28, 10, 15, 0)).matches("들퍼레_캡쳐_20261128_101500\\.png"));
        assertEquals("Pictures/들퍼레", CapturePlan.FOLDER);
    }

    @Test public void theFloatingControlsAreSmallPauseResumeAndStopAndSayWhatTheNotificationSays() throws Exception {
        float d = app.getResources().getDisplayMetrics().density;
        // Nowhere to float (no overlay permission, no 폰 조작): no controls, and the notification offers the permission.
        ShadowSettings.setCanDrawOverlays(false);
        screen(true, false, 83);
        RecordPill.sync(app);
        assertNull(RecordPill.current());
        assertFalse(RecordPill.possible(app));
        assertEquals(List.of("일시정지", "중지", "화면 위 버튼 켜기"), actions(ScreenRecordService.notification(app, "1440×3120 · 60fps · HEVC 48Mbps · 기기 소리", true, false, 83_000, true)));
        // Allowed: the pill over every app.
        ShadowSettings.setCanDrawOverlays(true);
        RecordPill.sync(app);
        RecordPill pill = RecordPill.current();
        assertNotNull("the controls over other apps", pill);
        assertEquals(android.view.WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, pill.params.type);
        layout(pill.root);
        assertEquals("1:23", pill.time.getText().toString());
        assertTrue("36dp high, not a 48dp bar: " + pill.bar.getHeight() / d, pill.bar.getHeight() <= 40 * d);
        assertTrue("small, not the width of the screen: " + pill.bar.getWidth() / d + "dp", pill.bar.getWidth() <= 200 * d);
        for (ImageView b : new ImageView[]{pill.pause, pill.stop, pill.shot}) assertTrue("a small icon button: " + b.getWidth() / d, b.getWidth() <= 32 * d && b.getWidth() >= 28 * d);
        assertEquals("일시정지", pill.pause.getContentDescription().toString());
        // Clear of the status bar and the navigation bar.
        android.graphics.Rect safe = pill.safe();
        assertTrue("below the status bar: " + pill.params.y, pill.params.y >= safe.top);
        assertTrue("above the navigation bar", pill.params.y + pill.root.getMeasuredHeight() <= safe.bottom);
        shot(pill, "pill_recording");
        // Its buttons go to the service.
        drain();
        assertTrue(pill.pause.performClick()); expectService(ScreenRecordService.class, "pause");
        // The service paused: the controls, the notification and the tile's words follow.
        screen(true, true, 83);
        RecordControl.changed(app); idle(10);
        assertSame("the same pill, redrawn", pill, RecordPill.current());
        assertEquals("재개", pill.pause.getContentDescription().toString());
        assertEquals("1:23", pill.time.getText().toString());
        idle(3_000);
        assertEquals("the time stands still while paused", "1:23", pill.time.getText().toString());
        Notification paused = ScreenRecordService.notification(app, "1440×3120 · 60fps · HEVC 48Mbps · 기기 소리", true, true, ScreenRecordService.elapsedMs(), false);
        assertEquals(List.of("재개", "중지"), actions(paused));
        assertEquals("화면 녹화 일시정지 · 1:23", paused.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        assertFalse("no running clock while paused", paused.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        Notification going = ScreenRecordService.notification(app, "1440×3120", true, false, 83_000, false);
        assertEquals(List.of("일시정지", "중지"), actions(going));
        assertTrue(going.extras.getBoolean(Notification.EXTRA_SHOW_CHRONOMETER));
        assertTrue("its clock leaves the pauses out: " + (System.currentTimeMillis() - going.when), Math.abs(System.currentTimeMillis() - going.when - 83_000) < 2_000);
        assertEquals("before the encoder runs: only 중지", List.of("중지"), actions(ScreenRecordService.notification(app, "준비하는 중", false, false, 0, true)));
        assertEquals("paused", RecordControl.screen());
        shot(pill, "pill_paused");
        assertTrue(pill.pause.performClick()); expectService(ScreenRecordService.class, "resume");
        assertTrue(pill.stop.performClick()); expectService(ScreenRecordService.class, "stop");
        // A tap on the time folds it to a dot; a tap on the dot opens it again.
        assertTrue(pill.grip.performClick());
        assertTrue(pill.folded());
        layout(pill.root);
        assertTrue("a small dot: " + pill.dot.getWidth() / d, pill.dot.getWidth() <= 32 * d && pill.dot.getHeight() <= 32 * d);
        shot(pill, "pill_folded");
        assertTrue(pill.dot.performClick());
        assertFalse(pill.folded());
        // 녹음 alone: the same controls, for the microphone.
        screen(false, false, 0);
        store.recording = true; store.recordingPaused = false; store.recordingStartedAt = System.currentTimeMillis() - 7_000; store.recordingPauses = new RecordPauses();
        RecordPill.sync(app);
        pill = RecordPill.current(); assertNotNull(pill); layout(pill.root);
        assertEquals("0:07", pill.time.getText().toString());
        assertEquals(View.VISIBLE, pill.mic.getVisibility());
        drain();
        assertTrue(pill.pause.performClick()); expectService(AssistantService.class, AssistantService.RECORD_PAUSE);
        assertTrue(pill.stop.performClick()); expectService(AssistantService.class, AssistantService.RECORD_STOP);
        // Nothing on: gone.
        store.recording = false;
        RecordPill.sync(app);
        assertNull(RecordPill.current());
    }

    @Test public void theAppsRowSaysPausedWithTheTimeStandingStill() throws Exception {
        screen(true, true, 83);
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        settle(decor);
        View tab = exact(decor, "녹음"); assertNotNull(tab);
        assertTrue(tab.performClick()); settle(decor);
        View line = starts(decor, "일시정지 · 1분 23초");
        assertNotNull("화면 녹화's row: paused, the pauses left out", line);
        screen(false, false, 0);
        store.recording = true; store.recordingPaused = true; store.recordingStartedAt = System.currentTimeMillis() - 30_000;
        store.recordingPauses = new RecordPauses(); store.recordingPauses.pause(System.currentTimeMillis() - 10_000);
        assertEquals(20_000, RecordControl.audioElapsedMs(store), 1_000);
        assertEquals("paused", RecordControl.audio(store));
    }

    // ── helpers ─────────────────────────────────────────────────────
    private static List<String> actions(Notification n) {
        List<String> out = new ArrayList<>();
        if (n.actions != null) for (Notification.Action x : n.actions) out.add(x.title.toString());
        return out;
    }
    private static <T> T find(View v, Class<T> type) {
        if (type.isInstance(v)) return type.cast(v);
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { T t = find(g.getChildAt(i), type); if (t != null) return t; }
        return null;
    }
    private static View findView(View v, java.util.function.Predicate<View> match) {
        if (match.test(v)) return v;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = findView(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View exact(View root, String words) { return findView(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().equals(words)); }
    private static View starts(View root, String words) { return findView(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().startsWith(words)); }
    private static void layout(View v) {
        v.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight());
    }
    private static void settle(View decor) {
        var m = decor.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
            decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
            shadowOf(Looper.getMainLooper()).idle();
        }
    }
    /**
     * For 준: the controls where they float, over a phone-width app screen (the app's own 비서 screen stands in for "any app"),
     * and the same piece alone, larger, to see it.
     */
    private void shot(RecordPill pill, String name) throws Exception {
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        settle(decor);
        layout(pill.root);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        // The status bar's band, as on a phone, so the pill's place under it shows.
        android.graphics.Paint bar = new android.graphics.Paint(); bar.setColor(0x22000000);
        c.drawRect(0, 0, b.getWidth(), pill.safe().top, bar); c.drawRect(0, pill.safe().bottom, b.getWidth(), b.getHeight(), bar);
        c.save(); c.translate(pill.params.x, pill.params.y); pill.root.draw(c); c.restore();
        save(b, name);
        Bitmap alone = Bitmap.createBitmap(pill.root.getWidth() + 48, pill.root.getHeight() + 48, Bitmap.Config.ARGB_8888);
        Canvas ca = new Canvas(alone); ca.drawColor(0xffe9e6df); ca.translate(24, 24); pill.root.draw(ca);
        save(alone, name + "_alone");
        a.finish();
    }
    private static void save(Bitmap b, String name) throws Exception {
        for (String dir : new String[]{System.getProperty("ui.output", "build/ui-verification"), SHOTS}) {
            File d = new File(dir); d.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(d, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        }
    }
}
