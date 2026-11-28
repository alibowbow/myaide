package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.content.ComponentCallbacks2;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Insets;
import android.os.Looper;
import android.os.SystemClock;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowInsets;
import android.webkit.WebView;
import android.widget.FrameLayout;
import android.widget.Switch;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 들퍼레 홈 화면's left page as Claude Code (준: "내 들퍼레 ai 브라우저가 안 뜨고 클로드 페이지만 떠", "일단 1번으로 가자", "뒤로가기로
 * 홈페이지 돌아가는 건 넣지마, 홈버튼이나 우측 스와이프로"): a swipe come to rest on the left page opens the AI 브라우저 itself at
 * claude.ai/code (once, not when the home screen merely starts there or Launchpad covers it, not when turned off); in the browser
 * a swipe in from the right edge goes 홈 and nothing else there is taken; 화면 꾸미기 turns it off and on in one compact row.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ClaudePageTests {
    /** The home screens left started here do not count for the next class's tests (HomeActivity's state is static). */
    @After public void away() { HomeActivity.visible = false; HomeActivity.resumed = false; HomeActivity.shownAt = 0; }
    private static Object field(Object o, String name) throws Exception { java.lang.reflect.Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void layout(Activity a) {
        View decor = a.getWindow().getDecorView(); var m = decor.getResources().getDisplayMetrics();
        for (int i = 0; i < 2; i++) { decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight()); idle(); }
    }
    private static void shot(Activity a, String name) throws Exception {
        layout(a); View decor = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888); Canvas c = new Canvas(b); c.drawColor(0xff05060b); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    /** A call from the launcher page through its one door (HomeActivity.Gate, with the page's secret). */
    private static void page(HomeActivity a, String name, String args) throws Exception {
        WebView web = (WebView) field(a, "web");
        Object gate = shadowOf(web).getJavascriptInterface("Gyeot");
        java.lang.reflect.Method call = gate.getClass().getMethod("call", String.class, String.class, String.class); call.setAccessible(true);
        call.invoke(gate, field(a, "secret"), name, args); idle(); layout(a);
    }
    private static float dp(Activity a, float px) { return px / a.getResources().getDisplayMetrics().density; }
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void bars(HomeActivity a, int top, int bottom) {
        FrameLayout root = (FrameLayout) a.findViewById(android.R.id.content); root = (FrameLayout) root.getChildAt(0);
        root.dispatchApplyWindowInsets(new WindowInsets.Builder().setInsets(WindowInsets.Type.statusBars(), Insets.of(0, top, 0, 0))
            .setInsets(WindowInsets.Type.navigationBars(), Insets.of(0, 0, 0, bottom)).build());
        idle(); layout(a);
    }

    /** The browser the home screen started last (null when none), and its extras. */
    private static android.content.Intent started(HomeActivity a) { return shadowOf(a).getNextStartedActivity(); }

    /**
     * The pages turn back to 홈 under the browser only once it covers them (the home screen stopped), not at a set time after
     * asking for it: in 0.4.677's video the browser took longer and the pages were seen going back to 홈 first.
     */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void thePagesTurnBackOnlyOnceTheBrowserCovers() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ClaudePage.ON, true).commit();
        org.robolectric.android.controller.ActivityController<HomeActivity> c = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = c.get(); idle(); layout(a);
        while (started(a) != null) { /* what the home screen started on its own */ }
        page(a, "left", "[0.5,false,false]"); page(a, "left", "[0,true,false]");
        assertNotNull("the browser asked for", started(a));
        android.webkit.WebView web = (android.webkit.WebView) findView(a.getWindow().getDecorView(), android.webkit.WebView.class);
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        assertFalse("not while the home screen still shows", String.valueOf(shadowOf(web).getLastEvaluatedJavascript()).contains("toHome"));
        c.pause().stop(); idle();
        assertTrue("covered: 홈 under it", String.valueOf(shadowOf(web).getLastEvaluatedJavascript()).contains("launcher.toHome"));
    }
    private static View findView(View v, Class<?> type) {
        if (type.isInstance(v)) return v;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View f = findView(g.getChildAt(i), type); if (f != null) return f; }
        return null;
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void restingOnTheLeftPageOpensTheBrowser() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ClaudePage.ON, true).commit();
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get(); idle(); layout(a);
        while (started(a) != null) { /* what the home screen started on its own while coming up */ }
        // On 홈, and a home screen that merely starts on the left page (drawn again): nothing opens.
        page(a, "left", "[1,true,false]");
        page(a, "left", "[0,true,false]");
        assertNull("no swipe came before: nothing opens", started(a));
        // A swipe that comes to rest on it: the AI 브라우저 at claude.ai/code, from the home screen.
        page(a, "left", "[0.5,false,false]");
        assertNull("mid-swipe: not yet", started(a));
        page(a, "left", "[0,true,false]");
        android.content.Intent open = started(a);
        assertNotNull("rested on the left page: the browser", open);
        assertEquals(BrowserActivity.class.getName(), open.getComponent().getClassName());
        assertEquals("https://claude.ai/code", open.getStringExtra(BrowserActivity.OPEN));
        assertTrue("from the home screen (its ways back to 홈)", open.getBooleanExtra(BrowserActivity.HOME_SIDE, false));
        // A bounce right after: not twice.
        page(a, "left", "[0.4,false,false]"); page(a, "left", "[0,true,false]");
        assertNull("not twice in a moment", started(a));
        // Later, Launchpad over it: nothing; uncovered after a swipe: again.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        page(a, "left", "[0.5,false,true]"); page(a, "left", "[0,true,true]");
        assertNull("covered: nothing", started(a));
        page(a, "left", "[0.5,false,false]"); page(a, "left", "[0,true,false]");
        assertNotNull("again later", started(a));
        // Most of the way there: it opens before the page comes to rest (its picture shows meanwhile), and not again when it does.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        page(a, "left", "[0.6,false,false]"); assertNull(started(a));
        page(a, "left", "[0.2,false,false]"); assertNotNull("opened early", started(a));
        page(a, "left", "[0,true,false]"); assertNull("not twice", started(a));
        // Turned off in 화면 꾸미기: 오늘 is there, nothing opens.
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(2));
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ClaudePage.ON, false).commit(); idle();
        page(a, "left", "[0.5,false,false]"); page(a, "left", "[0,true,false]");
        assertNull("off: nothing opens", started(a));
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ClaudePage.ON, true).commit(); idle();
    }

    @Test public void onlyASwipeComeToRestOpensIt() {
        ClaudePage.Swipe s = new ClaudePage.Swipe();
        assertFalse("starting there", s.opens(0, true, false));
        assertFalse(s.opens(0.7f, false, false)); assertFalse("not yet most of the way", s.opens(0.4f, false, false));
        assertTrue("most of the way there: it opens now, not when the page comes to rest", s.opens(0.2f, false, false));
        assertFalse("once a swipe", s.opens(0.1f, false, false)); assertFalse("not again when it rests", s.opens(0, true, false));
        assertFalse(s.opens(0.6f, false, false));
        assertTrue("came to rest on it (a quick swipe told only its end)", s.opens(0, true, false));
        assertFalse("once", s.opens(0, true, false));
        assertFalse(s.opens(0.5f, false, false)); assertFalse("rested on 홈", s.opens(1, true, false));
        assertFalse("then starting there again", s.opens(0, true, false));
        assertFalse(s.opens(0.5f, false, true)); assertFalse("covered", s.opens(0, true, true));
        assertFalse("a swipe toward the right pages", s.opens(1.5f, false, false) || s.opens(2, true, false));
        assertTrue(ClaudePage.inside("https://claude.ai/code/session_01abc")); assertFalse(ClaudePage.inside("http://claude.ai/code"));
        assertFalse("not claude.ai by its name alone", ClaudePage.inside("https://claude.ai.example.com/"));
    }

    /** A screen's touch, in its own window. */
    private static MotionEvent touch(int action, float x, float y, long at) { return MotionEvent.obtain(0, at, action, x, y, 0); }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void aSwipeInFromTheRightEdgeGoesHome() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(a); page.setBackgroundColor(0xff262624);
        TextView words = new TextView(a); words.setText("Claude Code · claude.ai/code"); words.setTextColor(0xffe8e6dc); words.setPadding(40, 160, 40, 40); page.addView(words);
        a.setContentView(page);
        Ui ui = new Ui(a); int[] homes = {0};
        HomeEdge edge = new HomeEdge(a, ui, () -> homes[0]++); edge.attach(a.findViewById(android.R.id.content));
        layout(a);
        assertEquals("off until it came from the home screen", View.GONE, edge.handle.getVisibility());
        int w = a.getWindow().getDecorView().getWidth(), h = a.getWindow().getDecorView().getHeight(); float d = a.getResources().getDisplayMetrics().density;
        List<Integer> passed = new ArrayList<>();
        java.util.function.Consumer<MotionEvent> pass = e -> passed.add(e.getActionMasked());
        assertFalse("off: nothing taken", edge.touch(touch(MotionEvent.ACTION_DOWN, w - 4, h / 2f, 0), pass));
        edge.on(true); layout(a);
        // The handle: small, at the right edge, kept from the phone's back gesture.
        assertEquals(View.VISIBLE, edge.handle.getVisibility());
        assertEquals("at the right edge", w, edge.handle.getRight());
        assertTrue("narrow (" + dp(a, edge.handle.getWidth()) + "dp)", dp(a, edge.handle.getWidth()) <= HomeEdge.EDGE + 0.5f);
        assertEquals("the stretch Android lets an app keep", HomeEdge.KEPT, dp(a, edge.handle.getHeight()), 1);
        assertFalse("kept from the back gesture", edge.handle.getSystemGestureExclusionRects().isEmpty());
        assertFalse("touches pass through it", edge.handle.isClickable());
        shot(a, "browser-home-edge");
        // A tap at the edge: the page's.
        float x = w - 6 * d, y = h / 2f;
        assertFalse(edge.touch(touch(MotionEvent.ACTION_DOWN, x, y, 0), pass));
        assertFalse(edge.touch(touch(MotionEvent.ACTION_UP, x, y, 40), pass));
        // A scroll down from the edge: the page's.
        assertFalse(edge.touch(touch(MotionEvent.ACTION_DOWN, x, y, 100), pass));
        assertFalse(edge.touch(touch(MotionEvent.ACTION_MOVE, x - 10 * d, y + 80 * d, 140), pass));
        assertFalse("still the page's", edge.touch(touch(MotionEvent.ACTION_MOVE, x - 70 * d, y + 90 * d, 160), pass));
        assertFalse(edge.touch(touch(MotionEvent.ACTION_UP, x - 70 * d, y + 90 * d, 180), pass));
        // A sideways swipe started in the page (not at the edge): the page's.
        assertFalse(edge.touch(touch(MotionEvent.ACTION_DOWN, w / 2f, y, 200), pass));
        assertFalse(edge.touch(touch(MotionEvent.ACTION_MOVE, w / 2f - 120 * d, y, 240), pass));
        assertFalse(edge.touch(touch(MotionEvent.ACTION_UP, w / 2f - 120 * d, y, 260), pass));
        assertEquals("no 홈 yet", 0, homes[0]); assertTrue("nothing cancelled for the page", passed.isEmpty());
        // In from the right edge, mostly sideways, past FAR: 홈, the page told the touch ended, the rest of it kept.
        assertFalse(edge.touch(touch(MotionEvent.ACTION_DOWN, x, y, 300), pass));
        assertFalse("short of FAR", edge.touch(touch(MotionEvent.ACTION_MOVE, x - 30 * d, y + 5 * d, 320), pass));
        assertTrue("taken", edge.touch(touch(MotionEvent.ACTION_MOVE, x - 70 * d, y + 8 * d, 340), pass));
        assertEquals(1, homes[0]); assertEquals("the page's touch cancelled", List.of(MotionEvent.ACTION_CANCEL), passed);
        assertTrue(edge.touch(touch(MotionEvent.ACTION_MOVE, x - 120 * d, y, 360), pass));
        assertTrue(edge.touch(touch(MotionEvent.ACTION_UP, x - 120 * d, y, 380), pass));
        assertEquals("once", 1, homes[0]);
        assertFalse("the next touch is the page's again", edge.touch(touch(MotionEvent.ACTION_DOWN, w / 2f, y, 400), pass));
    }

    /** 준: "Ai브라우저에서 우측 홈으로 돌아갈때 슬라이드 방식으로 전환되게해줘": the screen follows the finger; a third goes 홈, less springs back. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void theSwipeBackFollowsTheFinger() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        FrameLayout page = new FrameLayout(a); a.setContentView(page);
        Ui ui = new Ui(a); int[] homes = {0};
        HomeEdge edge = new HomeEdge(a, ui, () -> homes[0]++); edge.attach(a.findViewById(android.R.id.content)); edge.on(true);
        List<Float> moves = new ArrayList<>(); List<Boolean> ends = new ArrayList<>();
        edge.follow(new HomeEdge.Drag() {
            @Override public boolean begin() { return true; }
            @Override public void move(float dx) { moves.add(dx); }
            @Override public void end(boolean home) { ends.add(home); }
        });
        layout(a);
        int w = a.getWindow().getDecorView().getWidth(), h = a.getWindow().getDecorView().getHeight(); float d = a.getResources().getDisplayMetrics().density, x = w - 6 * d, y = h / 2f;
        java.util.function.Consumer<MotionEvent> pass = e -> {};
        // A short drag: it follows from the first clear sideways move, then springs back.
        assertFalse(edge.touch(touch(MotionEvent.ACTION_DOWN, x, y, 0), pass));
        assertTrue("followed at once (not only past FAR)", edge.touch(touch(MotionEvent.ACTION_MOVE, x - 20 * d, y, 300), pass));
        assertTrue(edge.touch(touch(MotionEvent.ACTION_MOVE, x - 60 * d, y, 600), pass));
        assertTrue(edge.touch(touch(MotionEvent.ACTION_UP, x - 60 * d, y, 900), pass));
        assertEquals("with the finger", -60 * d, moves.get(moves.size() - 1), 1);
        assertEquals("less than a third, slowly: back", List.of(false), ends);
        // Past a third: 홈.
        moves.clear(); ends.clear();
        edge.touch(touch(MotionEvent.ACTION_DOWN, x, y, 2000), pass);
        edge.touch(touch(MotionEvent.ACTION_MOVE, x - 30 * d, y, 2100), pass);
        edge.touch(touch(MotionEvent.ACTION_MOVE, x - w * 0.45f, y, 2400), pass);
        edge.touch(touch(MotionEvent.ACTION_UP, x - w * 0.45f, y, 2500), pass);
        assertEquals(List.of(true), ends);
        // A quick fling, short: 홈 too.
        ends.clear();
        edge.touch(touch(MotionEvent.ACTION_DOWN, x, y, 4000), pass);
        edge.touch(touch(MotionEvent.ACTION_MOVE, x - 20 * d, y, 4010), pass);
        edge.touch(touch(MotionEvent.ACTION_MOVE, x - 70 * d, y, 4030), pass);
        edge.touch(touch(MotionEvent.ACTION_UP, x - 70 * d, y, 4040), pass);
        assertEquals("flung", List.of(true), ends);
        assertEquals("the old jump never ran while following", 0, homes[0]);
    }

    /** 화면 꾸미기: one row for the left page, its switch at the end, as small as the other rows. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void decorSwitchIsCompact() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        android.app.role.RoleManager roles = app.getSystemService(android.app.role.RoleManager.class);
        ((org.robolectric.shadows.ShadowRoleManager) org.robolectric.shadow.api.Shadow.extract(roles)).addHeldRole(android.app.role.RoleManager.ROLE_HOME);
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        for (int i = 0; i < 20; i++) { Thread.sleep(30); idle(); }
        layout(a); View decor = a.getWindow().getDecorView();
        View title = find(decor, v -> v instanceof TextView t && "왼쪽 · Claude Code".equals(t.getText().toString()));
        assertNotNull("on by default: 왼쪽 · Claude Code", title);
        View row = (View) title.getParent().getParent();
        Switch toggle = (Switch) find(row, v -> v instanceof Switch);
        assertNotNull("its switch at the end of the row", toggle); assertTrue(toggle.isChecked());
        assertTrue("compact row (" + dp(a, row.getHeight()) + "dp)", dp(a, row.getHeight()) <= 72);
        assertTrue("small switch (" + dp(a, toggle.getHeight()) + "dp)", dp(a, toggle.getHeight()) <= 40);
        assertTrue("the switch is at the row's end", toggle.getRight() > row.getWidth() * 0.8f);
        shot(a, "claude-decor-switch");
        toggle.performClick(); idle(); layout(a);
        assertFalse(ClaudePage.on(app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE)));
        decor = a.getWindow().getDecorView();
        assertNotNull("off: 오늘 again", find(decor, v -> v instanceof TextView t && "왼쪽 · 오늘".equals(t.getText().toString())));
    }
}
