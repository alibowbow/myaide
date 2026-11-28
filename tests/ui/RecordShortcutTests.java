package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Notification;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.webkit.WebView;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.RecordPauses;
import dev.jun.gyeot.core.ScreenRecordPlan;
import java.io.File;
import java.io.FileOutputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
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
import org.robolectric.shadows.ShadowActivity;
import org.robolectric.shadows.ShadowSettings;
import org.robolectric.shadows.ShadowToast;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 녹화 (준: "녹화하기가 너무 번거로워. 홈에 바로가기를 만들자", "기존에 있던 버블 주위에 버튼을 더 만들고 … 폰조작 멈추기 버튼은 녹화로
 * 대체", "녹화하기 누르면 음성대화는 자동으로 종료되게", "화면만 / 미디어 / 마이크+미디어 — 이걸 설정 가능하게 해줘"): the home screen's
 * satellite reaches the right screens and services through its bridge (RecordControl, one controller); every 녹화 starts with
 * the compact 화면 녹화 시작 sheet, whose 녹화 시작 ends a voice conversation and asks only for what its sound needs; the voice
 * bubble's satellite is 녹화, in the old stop moon's place, and phone control's stop is first in its menu; the recording gets
 * a bubble of its own at the voice bubble's spot, small, with 재개 · 정지 and the time when paused; the notification agrees.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class, qualifiers = "w411dp-h891dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class RecordShortcutTests {
    private Application app;
    private AppStore store;
    private FloatingButton voice;
    private static final String SHOTS = "/tmp/claude-0/-home-user/138b3987-18a5-5339-9471-114de8ffce30/scratchpad/satellite";

    @Before public void start() {
        app = RuntimeEnvironment.getApplication();
        store = ((GyeotApp) app).store();
        store.settings.edit().putInt("bubbleX", 16).putInt("bubbleY", 600).remove(ScreenRecordService.SOUND).apply();
    }
    @After public void end() throws Exception {
        screen(false, false, 0);
        store.recording = false; store.recordingPaused = false; store.recordingStartedAt = 0; store.recordingPauses = new RecordPauses(); store.voiceActive = false;
        RecordControl.changed(app);
        if (voice != null) voice.close();
        ShadowSettings.setCanDrawOverlays(false);
        Field open = ScreenRecordActivity.class.getDeclaredField("open"); open.setAccessible(true); open.set(null, false);
    }

    // ── state, as the services hold it ──────────────────────────────
    private static void set(String name, Object value) throws Exception { Field f = ScreenRecordService.class.getDeclaredField(name); f.setAccessible(true); f.set(null, value); }
    /** A 화면 녹화 as ScreenRecordService holds it: running, its encoder started secondsAgo, paused or not. */
    private static void screen(boolean on, boolean paused, long secondsAgo) throws Exception {
        set("running", on); set("saving", false); set("counting", false);
        long now = ScreenRecordService.now();
        RecordPauses p = new RecordPauses();
        if (paused) p.pause(now);
        set("pauses", p);
        set("startedMono", on ? now - secondsAgo * 1_000_000L : 0L);
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
    private Intent expectStart(String mode) {
        Intent i = nextActivity();
        assertNotNull("the see-through start (" + mode + ")", i);
        assertEquals(ScreenRecordActivity.class.getName(), i.getComponent().getClassName());
        assertEquals(mode, i.getStringExtra(ScreenRecordActivity.MODE));
        return i;
    }

    @Test public void theHomeSatellitesActionsThroughTheBridgeReachTheRightScreensAndServices() throws Exception {
        org.robolectric.android.controller.ActivityController<HomeActivity> shown = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity home = shown.get();
        try {
            idle(100);
            WebView web = find(home.getWindow().getDecorView(), WebView.class);
            Object gate = shadowOf(web).getJavascriptInterface("Gyeot");
            assertNotNull("the page's one door", gate);
            Field secret = HomeActivity.class.getDeclaredField("secret"); secret.setAccessible(true);
            String token = (String) secret.get(home);
            Method call = gate.getClass().getDeclaredMethod("call", String.class, String.class, String.class); call.setAccessible(true);
            java.util.function.Consumer<String> page = what -> { try { call.invoke(gate, token, "record", "[\"" + what + "\"]"); idle(10); } catch (Exception e) { throw new AssertionError(e); } };
            drain();
            // 녹화하기: the start sheet and Android's consent, through the see-through start.
            page.accept("screen");
            expectStart(null);
            // 녹음하기 without the microphone: the see-through start asks for it first; with it, 녹음 starts at once.
            shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
            page.accept("audio");
            expectStart(ScreenRecordActivity.AUDIO);
            shadowOf(app).grantPermissions(Manifest.permission.RECORD_AUDIO);
            page.accept("audio");
            expectService(AssistantService.class, AssistantService.RECORD_START);
            // While 녹음 is on: its 정지, and the recording bubble's pause and resume.
            store.recording = true; store.recordingStartedAt = System.currentTimeMillis();
            page.accept("audioStop"); expectService(AssistantService.class, AssistantService.RECORD_STOP);
            RecordControl.act(app, RecordControl.AUDIO_PAUSE); expectService(AssistantService.class, AssistantService.RECORD_PAUSE);
            store.recordingPaused = true;
            RecordControl.act(app, RecordControl.AUDIO_RESUME); expectService(AssistantService.class, AssistantService.RECORD_RESUME);
            store.recording = false; store.recordingPaused = false;
            // While 화면 녹화 is on: 정지 (녹화하기 again stops it rather than asking twice), pause at once, resume once all is veiled.
            screen(true, false, 5);
            page.accept("screenStop"); expectService(ScreenRecordService.class, "stop");
            page.accept("screen"); expectService(ScreenRecordService.class, "stop");
            assertNull("no second consent while it records", nextActivity());
            RecordControl.act(app, RecordControl.SCREEN_PAUSE);
            assertEquals("held at once", "paused", RecordControl.screen());
            expectService(ScreenRecordService.class, "sync");
            RecordControl.act(app, RecordControl.SCREEN_PAUSE);
            assertNull("paused already", nextService());
            RecordControl.act(app, RecordControl.SCREEN_RESUME);
            assertEquals("still held until everything of ours is off the screen", "paused", RecordControl.screen());
            idle(RecordControl.GONE_WAIT_MS + 60);
            assertEquals("on", RecordControl.screen());
            expectService(ScreenRecordService.class, "sync");
            assertEquals("{\"screen\":\"on\",\"audio\":\"\"}", RecordControl.state(store).toString());
            assertFalse("not a bridge call it knows", RecordControl.act(app, "capture"));
        } finally {
            // The home screen leaves as it would (its static "on screen" flag is read by the taskbar's tests).
            shown.pause().stop().destroy();
        }
    }

    // ── 화면 녹화 시작 ──────────────────────────────────────────────────

    private static List<View> options(View root) {
        List<View> out = new ArrayList<>();
        collect(root, v -> v instanceof LinearLayout && v.getContentDescription() != null && v.getContentDescription().toString().startsWith("녹음할 오디오 "), out);
        return out;
    }
    private ScreenRecordActivity sheet() {
        ScreenRecordActivity a = Robolectric.buildActivity(ScreenRecordActivity.class).setup().get();
        idle(10);
        assertNotNull("the start sheet first, before anything else", a.sheet);
        assertTrue(a.sheet.isShowing());
        return a;
    }
    private static View sheetView(Dialog d) {
        View decor = d.getWindow().getDecorView();
        var m = decor.getResources().getDisplayMetrics();
        int width = Math.min(m.widthPixels - Math.round(32 * m.density), Math.round(380 * m.density));
        decor.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
        return decor;
    }

    @Test public void everyRecordingStartsWithACompactSheetWhoseChoiceAsksOnlyForWhatItNeeds() throws Exception {
        float d = app.getResources().getDisplayMetrics().density;
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS);
        shadowOf(app).denyPermissions(Manifest.permission.RECORD_AUDIO);
        store.voiceActive = true;
        drain();
        ScreenRecordActivity a = sheet();
        View panel = sheetView(a.sheet);
        assertNotNull(exact(panel, "화면 녹화 시작"));
        assertNotNull(exact(panel, "녹음할 오디오"));
        List<View> choices = options(panel);
        assertEquals("화면만 · 미디어 · 마이크+미디어", 3, choices.size());
        assertEquals(List.of("화면만", "미디어", "마이크+미디어"), choices.stream().map(v -> ((TextView) ((ViewGroup) v).getChildAt(1)).getText().toString()).toList());
        int top = choices.get(0).getTop();
        for (View v : choices) {
            assertEquals("in one row", top, v.getTop());
            ImageView icon = (ImageView) ((ViewGroup) v).getChildAt(0);
            assertTrue("small icons: " + icon.getHeight() / d, icon.getHeight() <= 30 * d);
        }
        assertTrue("the last one used is chosen (미디어 by default)", choices.get(1).isSelected());
        assertTrue("compact, not a tall card: " + panel.getHeight() / d + "dp", panel.getHeight() <= 360 * d);
        assertTrue("narrower than the screen: " + panel.getWidth() / d, panel.getWidth() <= 380 * d);
        shot(panel, "start_sheet_light", a);
        assertNull("the voice conversation goes on while the sheet is open", nextService());
        // 화면만: no sound track, no microphone asked; 녹화 시작 ends the voice conversation, then Android's consent.
        assertTrue(choices.get(0).performClick());
        assertTrue(choices.get(0).isSelected()); assertFalse(choices.get(1).isSelected());
        assertTrue(exact(panel, "녹화 시작").performClick());
        idle(10);
        assertEquals(ScreenRecordPlan.Sound.NONE, ScreenRecordService.sound(store.settings));
        expectService(AssistantService.class, AssistantService.END_LIVE);
        ShadowActivity.PermissionsRequest asked = shadowOf(a).getLastRequestedPermission();
        assertTrue("화면만 asks for no microphone", asked == null || !Arrays.asList(asked.requestedPermissions).contains(Manifest.permission.RECORD_AUDIO));
        store.voiceActive = false;
        // The settings sheet's 소리 shows the same (one setting).
        assertEquals("화면만", ScreenRecordService.sound(store.settings).label);

        // 마이크+미디어, the microphone not allowed yet: it is asked for, then the consent.
        Field open = ScreenRecordActivity.class.getDeclaredField("open"); open.setAccessible(true); open.set(null, false);
        ScreenRecordActivity b = sheet();
        View panel2 = sheetView(b.sheet);
        assertTrue("the last choice is kept: 화면만", options(panel2).get(0).isSelected());
        assertTrue(options(panel2).get(2).performClick());
        assertTrue(exact(panel2, "녹화 시작").performClick());
        ShadowActivity.PermissionsRequest mic = shadowOf(b).getLastRequestedPermission();
        assertNotNull(mic);
        assertTrue("마이크+미디어 asks for the microphone", Arrays.asList(mic.requestedPermissions).contains(Manifest.permission.RECORD_AUDIO));
        assertEquals(ScreenRecordPlan.Sound.BOTH, ScreenRecordService.sound(store.settings));

        // 취소: nothing starts, nothing is kept.
        open.set(null, false); drain();
        ScreenRecordActivity c = sheet();
        View panel3 = sheetView(c.sheet);
        assertTrue(options(panel3).get(1).performClick());
        assertTrue(exact(panel3, "취소").performClick());
        idle(10);
        assertTrue("it closes", c.isFinishing());
        assertNull(nextService());
        assertEquals("취소 keeps the choice as it was", ScreenRecordPlan.Sound.BOTH, ScreenRecordService.sound(store.settings));
    }

    @Test @Config(qualifiers = "w411dp-h891dp-night-xxhdpi") public void theStartSheetInDarkMode() throws Exception {
        ScreenRecordActivity a = sheet();
        View panel = sheetView(a.sheet);
        assertEquals(3, options(panel).size());
        shot(panel, "start_sheet_dark", a);
    }

    // ── The voice bubble: 녹화 in the old moon's place ───────────────

    @Test public void theVoiceBubblesSatelliteIsRecordingAndPhoneControlsStopIsFirstInItsMenu() throws Exception {
        float d = app.getResources().getDisplayMetrics().density;
        boolean[] rearmed = new boolean[1];
        voice = new FloatingButton(app, store.settings, () -> {}, () -> {}, null, () -> rearmed[0] = true);
        voice.show(); idle(10);
        BubbleView.Moon moon = voice.satellite();
        assertEquals("one satellite, always there", View.VISIBLE, moon.getVisibility());
        assertEquals("화면 녹화", moon.getContentDescription().toString());
        // Exactly where the phone-control stop moon was: above the orb toward the middle of the screen.
        android.widget.FrameLayout.LayoutParams at = (android.widget.FrameLayout.LayoutParams) moon.getLayoutParams();
        BubbleView.Orb probe = new BubbleView.Orb(app); BubbleView.Moon old = new BubbleView.Moon(app);
        android.widget.FrameLayout box = new android.widget.FrameLayout(app); box.addView(probe); box.addView(old);
        BubbleView.place(probe, old, true, true);
        android.widget.FrameLayout.LayoutParams was = (android.widget.FrameLayout.LayoutParams) old.getLayoutParams();
        assertEquals(was.gravity, at.gravity); assertEquals(was.bottomMargin, at.bottomMargin); assertEquals(was.rightMargin, at.rightMargin);
        assertTrue("a small moon: " + BubbleView.moonSide(app) / d, BubbleView.moonSide(app) <= 32 * d);
        shotBubble("bubble_record_satellite");
        // A tap on it: the 화면 녹화 시작 sheet over whatever is open.
        drain();
        assertTrue(moon.performClick());
        expectStart(null);
        // Phone control on: 폰 조작 멈추기 is the first line of the bubble's menu, red, one tap; it says so.
        voice.armed(true);
        assertTrue(voice.orb().performClick());
        LinearLayout menu = voice.menu();
        assertNotNull(menu);
        TextView first = (TextView) menu.getChildAt(0);
        assertEquals("폰 조작 멈추기", first.getText().toString());
        assertTrue(first.performClick());
        assertEquals("폰 조작을 멈췄어요 · 버블 메뉴에서 다시 켤 수 있어요", ShadowToast.getTextOfLatestToast());
        assertNull("the menu closed", voice.menu());
        // Stopped: 폰 조작 다시 켜기 first.
        voice.armed(false);
        assertTrue(voice.orb().performClick());
        TextView again = (TextView) voice.menu().getChildAt(0);
        assertEquals("폰 조작 다시 켜기", again.getText().toString());
        assertTrue(again.performClick());
        assertTrue(rearmed[0]);
    }

    @Test public void recordingFromTheBubbleEndsVoiceAndTheRecordingBubbleTakesItsSpot() throws Exception {
        float d = app.getResources().getDisplayMetrics().density;
        ShadowSettings.setCanDrawOverlays(true);
        shadowOf(app).grantPermissions(Manifest.permission.POST_NOTIFICATIONS, Manifest.permission.RECORD_AUDIO);
        store.voiceActive = true;
        voice = new FloatingButton(app, store.settings, () -> {}, () -> {}, null, null);
        voice.show(); idle(10);
        WindowManager.LayoutParams vp = (WindowManager.LayoutParams) voice.view().getLayoutParams();
        int screenWidth = app.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds().width(), side = BubbleView.orbSide(app);
        // The voice orb on the screen: its window is right-anchored and the orb sits in its bottom corner toward the edge.
        int voiceOrbRight = screenWidth - vp.x, voiceOrbTop = vp.y + vp.height - side;
        drain();
        assertTrue(voice.satellite().performClick());
        Intent opened = expectStart(null);
        ScreenRecordActivity a = Robolectric.buildActivity(ScreenRecordActivity.class, opened).setup().get();
        idle(10);
        assertTrue(exact(sheetView(a.sheet), "녹화 시작").performClick());
        expectService(AssistantService.class, AssistantService.END_LIVE);
        // The voice conversation ends (its bubble with it); the recording starts held, and its own bubble is at that spot.
        voice.close(); voice = null; store.voiceActive = false;
        set("running", true); set("saving", false); set("pauses", new RecordPauses());
        ScreenRecordService.hold(app);
        set("startedMono", ScreenRecordService.now());
        RecordControl.changed(app);
        RecordBubble bubble = RecordBubble.current();
        assertNotNull("the recording's own bubble", bubble);
        assertEquals("right-anchored like the voice bubble", android.view.Gravity.TOP | android.view.Gravity.END, bubble.params.gravity);
        assertEquals("its orb's right edge where the voice orb's was", voiceOrbRight, screenWidth - bubble.params.x);
        assertEquals("and its top", voiceOrbTop, bubble.params.y);
        assertEquals("the orb at the window's end, the bar toward the middle", bubble.orbBox, bubble.root.getChildAt(bubble.root.getChildCount() - 1));
        // Paused: 재개 · 정지 and the time with its sound in one word, small.
        screen(true, true, 83);
        RecordControl.changed(app); idle(10);
        assertSame(bubble, RecordBubble.current());
        layout(bubble.root);
        assertEquals("1:23 · 미디어", bubble.time.getText().toString());
        assertEquals("재개", bubble.toggle.getContentDescription().toString());
        assertTrue("36dp high, not a 48dp bar: " + bubble.bar.getHeight() / d, bubble.bar.getHeight() <= 40 * d);
        assertTrue("small: the orb and a short bar, " + bubble.root.getWidth() / d + "dp", bubble.root.getWidth() <= 240 * d);
        for (ImageView b : new ImageView[]{bubble.toggle, bubble.stop}) assertTrue("a small icon button", b.getWidth() <= 32 * d && b.getWidth() >= 28 * d);
        shotOverlay(bubble.root, bubble.params, "bubble_recording_paused");
        drain();
        assertTrue(bubble.stop.performClick()); expectService(ScreenRecordService.class, "stop");
        // The notification says the same, with 재개 beside 정지.
        Notification paused = ScreenRecordService.notification(app, "1440×3120 · 60fps · HEVC 48Mbps · 미디어", true, true, ScreenRecordService.elapsedMs(), false);
        assertEquals(List.of("재개", "중지"), actions(paused));
        assertEquals("화면 녹화 일시정지 · 1:23", paused.extras.getCharSequence(Notification.EXTRA_TITLE).toString());
        Notification going = ScreenRecordService.notification(app, "1440×3120", true, false, 83_000, false);
        assertEquals(List.of("일시정지", "중지"), actions(going));
        assertTrue("its clock leaves the pauses out", Math.abs(System.currentTimeMillis() - going.when - 83_000) < 2_000);
        assertEquals("without anywhere to float, the notification opens the permission in one tap", List.of("일시정지", "중지", "화면 위 버튼 켜기"),
            actions(ScreenRecordService.notification(app, "1440×3120", true, false, 83_000, true)));
        // 녹음 alone: the bubble stays (no picture), with 일시정지 · 정지.
        screen(false, false, 0);
        store.recording = true; store.recordingPaused = false; store.recordingStartedAt = System.currentTimeMillis() - 7_000; store.recordingPauses = new RecordPauses();
        RecordControl.changed(app);
        bubble = RecordBubble.current(); assertNotNull(bubble); layout(bubble.root);
        assertTrue("녹음 has no picture: it stays seen", bubble.showing() && bubble.barShown());
        assertEquals("0:07", bubble.time.getText().toString());
        assertTrue(bubble.toggle.performClick()); expectService(AssistantService.class, AssistantService.RECORD_PAUSE);
        store.recording = false;
        RecordControl.changed(app);
        assertNull(RecordBubble.current());
    }

    @Test public void theAppsRowSaysPausedWithTheTimeStandingStill() throws Exception {
        screen(true, true, 83);
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        settle(decor);
        View tab = exact(decor, "녹음"); assertNotNull(tab);
        assertTrue(tab.performClick()); settle(decor);
        assertNotNull("화면 녹화's row: paused, the pauses left out", starts(decor, "일시정지 · 1분 23초"));
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
    private static void collect(View v, Predicate<View> match, List<View> out) {
        if (match.test(v)) out.add(v);
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) collect(g.getChildAt(i), match, out);
    }
    private static View findView(View v, Predicate<View> match) { List<View> out = new ArrayList<>(); collect(v, match, out); return out.isEmpty() ? null : out.get(0); }
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
    /** For 준: an app screen at phone width (the app's own 비서 screen stands in for "any app"), as a canvas to draw over. */
    private Bitmap appScreen(Canvas[] out) {
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        settle(decor);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        a.finish();
        out[0] = c;
        return b;
    }
    /** A right-anchored overlay (gravity END) drawn where it floats. */
    private void shotOverlay(View root, WindowManager.LayoutParams p, String name) throws Exception {
        layout(root);
        Canvas[] c = new Canvas[1]; Bitmap b = appScreen(c);
        c[0].save(); c[0].translate(b.getWidth() - p.x - root.getWidth(), p.y); root.draw(c[0]); c[0].restore();
        save(b, name);
        Bitmap alone = Bitmap.createBitmap(root.getWidth() + 48, root.getHeight() + 48, Bitmap.Config.ARGB_8888);
        Canvas ca = new Canvas(alone); ca.drawColor(0xffe9e6df); ca.translate(24, 24); root.draw(ca);
        save(alone, name + "_alone");
    }
    private void shotBubble(String name) throws Exception {
        View root = voice.view();
        WindowManager.LayoutParams p = (WindowManager.LayoutParams) root.getLayoutParams();
        root.measure(View.MeasureSpec.makeMeasureSpec(p.width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(p.height, View.MeasureSpec.EXACTLY));
        root.layout(0, 0, p.width, p.height);
        Canvas[] c = new Canvas[1]; Bitmap b = appScreen(c);
        c[0].save(); c[0].translate(b.getWidth() - p.x - p.width, p.y); root.draw(c[0]); c[0].restore();
        save(b, name);
        Bitmap alone = Bitmap.createBitmap(p.width + 48, p.height + 48, Bitmap.Config.ARGB_8888);
        Canvas ca = new Canvas(alone); ca.drawColor(0xffe9e6df); ca.translate(24, 24); root.draw(ca);
        save(alone, name + "_alone");
    }
    /** The sheet over a dimmed app screen, as it shows. */
    private void shot(View panel, String name, Activity a) throws Exception {
        Canvas[] c = new Canvas[1]; Bitmap b = appScreen(c);
        c[0].drawColor(0x88000000);
        c[0].save(); c[0].translate((b.getWidth() - panel.getWidth()) / 2f, (b.getHeight() - panel.getHeight()) / 2f); panel.draw(c[0]); c[0].restore();
        save(b, name);
    }
    private static void save(Bitmap b, String name) throws Exception {
        for (String dir : new String[]{System.getProperty("ui.output", "build/ui-verification"), SHOTS}) {
            File f = new File(dir); f.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(f, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
        }
    }
}
