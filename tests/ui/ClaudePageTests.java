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
 * 들퍼레 홈 화면's left page as Claude Code (준: "홈화면 왼쪽 페이지 자체를 내 ai브라우저로 https://claude.ai/code 이페이지를 띄울 수
 * 있어?", "왼쪽 페이지는 하단 독 없애고"): the AI 브라우저's page over the launcher's first page (a stand-in here, Robolectric has no
 * Gecko), nothing made before it first shows, moved with the reported swipe, in use only when settled on, kept next door,
 * closed two pages away and when the home screen stops; the dock's room is its, the dots' strip and the bars are not; back is
 * the site's then 홈; the right edge hands its swipe to the home page; 화면 꾸미기 turns it off and on in one compact row.
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

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void leftPageFollowsTheSwipeAndRests() throws Exception {
        ActivityController<HomeActivity> run = Robolectric.buildActivity(HomeActivity.class).setup();
        HomeActivity a = run.get(); idle(); layout(a);
        ClaudePage claude = (ClaudePage) field(a, "claude");
        float d = a.getResources().getDisplayMetrics().density; int w = a.getWindow().getDecorView().getWidth();
        bars(a, Math.round(28 * d), Math.round(16 * d));
        // Nothing at boot: no page made or loaded while 홈 is shown.
        assertEquals("nothing made before the page shows", View.GONE, claude.host.getVisibility());
        assertFalse(claude.open()); assertEquals(0, claude.loads);
        page(a, "left", "[1,true,false]");
        assertFalse("홈 settled: still nothing", claude.open());

        // Half swiped in: made and loaded once, drawn half over the screen, not yet in use.
        page(a, "left", "[0.5,false,false]");
        assertTrue(claude.open()); assertEquals(1, claude.loads);
        assertEquals(View.VISIBLE, claude.host.getVisibility());
        assertEquals("moves with the page", -0.5f * w, claude.host.getTranslationX(), 2f);
        assertFalse("not in use mid-swipe", claude.here());
        assertEquals("no edge strip mid-swipe", View.GONE, claude.edge.getVisibility());
        assertEquals("at claude.ai/code", "https://claude.ai/code", claude.url());
        shot(a, "claude-left-half");

        // Settled on it: in place and in use; the bars are kept, the dock's room is the page's (only the dots' strip under it).
        page(a, "left", "[0,true,false]");
        assertTrue(claude.here()); assertEquals(0f, claude.host.getTranslationX(), 0.5f);
        View content = claude.host.getChildAt(0);
        int[] at = new int[2]; content.getLocationInWindow(at);
        assertEquals("starts under the status bar", 28, dp(a, at[1]), 1);
        float under = dp(a, a.getWindow().getDecorView().getHeight() - at[1] - content.getHeight());
        assertEquals("ends over the dots' strip and the system bar, no dock room", 16 + ClaudePage.DOTS, under, 1);
        assertEquals("the right edge's strip is there", View.VISIBLE, claude.edge.getVisibility());
        assertTrue("a thin strip", dp(a, claude.edge.getWidth()) <= 12.5f);
        assertEquals("at the right edge", w, claude.edge.getRight());
        shot(a, "claude-left-shown");

        // 홈 next door: kept, not drawn; back again at once, not loaded again.
        assertTrue(claude.active());
        page(a, "left", "[1,true,false]");
        assertEquals("off the screen's left edge", -w, claude.host.getTranslationX(), 0.5f);
        assertTrue("kept next door", claude.open()); assertFalse("not drawing", claude.active()); assertFalse(claude.here());
        assertEquals(View.GONE, claude.edge.getVisibility());
        int[] off = new int[2]; claude.host.getLocationOnScreen(off);
        assertTrue("untouchable there: wholly off the screen", off[0] + claude.host.getWidth() <= 0);
        page(a, "left", "[0,true,false]");
        assertEquals("not loaded again", 1, claude.loads);
        // Two pages away (LP): closed; coming back opens it again.
        page(a, "left", "[2,true,false]");
        assertFalse("closed two pages away", claude.open());
        page(a, "left", "[0,true,false]");
        assertTrue(claude.open()); assertEquals(2, claude.loads);
        // Launchpad over the pages: the page steps aside, takes no back.
        page(a, "left", "[0,true,true]");
        assertEquals(View.INVISIBLE, claude.host.getVisibility()); assertFalse(claude.here());
        page(a, "left", "[0,true,false]");
        assertTrue(claude.here());

        // The home screen stops: closed. Started again on the left page: opened again where it was.
        run.pause().stop(); idle();
        assertFalse("closed when the home screen stops", claude.open());
        run.restart().resume(); idle(); layout(a);
        assertTrue("opened again on return", claude.open()); assertEquals(3, claude.loads);
        // Memory low: kept while in use, closed when not.
        claude.trim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW); assertTrue(claude.open());
        page(a, "left", "[1,true,false]");
        claude.trim(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW); assertFalse(claude.open());
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void backIsTheSitesThenHome() throws Exception {
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get(); idle(); layout(a);
        ClaudePage claude = (ClaudePage) field(a, "claude");
        WebView web = (WebView) field(a, "web");
        page(a, "left", "[0,true,false]");
        claude.standInHistory(true);
        a.onBackPressed(); idle();
        assertEquals("the site's own back first", 1, claude.backs);
        claude.standInHistory(false);
        a.onBackPressed(); idle();
        assertEquals(1, claude.backs);
        assertEquals("then to 홈", "launcher.goPage(\"홈\")", shadowOf(web).getLastEvaluatedJavascript());
        assertFalse("a home screen never closes", a.isFinishing());
        // On 홈, back is the launcher's again (closing what is open there), once the page is up.
        java.lang.reflect.Field ready = HomeActivity.class.getDeclaredField("ready"); ready.setAccessible(true); ready.setBoolean(a, true);
        page(a, "left", "[1,true,false]");
        a.onBackPressed(); idle();
        assertEquals("launcher.back()", shadowOf(web).getLastEvaluatedJavascript());
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void rightEdgeHandsItsSwipeToTheHomePage() throws Exception {
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get(); idle(); layout(a);
        ClaudePage claude = (ClaudePage) field(a, "claude");
        page(a, "left", "[0,true,false]");
        List<float[]> heard = new ArrayList<>();
        claude.handTo = e -> heard.add(new float[]{e.getActionMasked(), e.getX(), e.getY()});
        View edge = claude.edge; long t = SystemClock.uptimeMillis();
        float x = edge.getWidth() / 2f, y = edge.getHeight() / 2f;
        edge.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0));
        edge.dispatchTouchEvent(MotionEvent.obtain(t, t + 16, MotionEvent.ACTION_MOVE, x - 120, y, 0));
        // The page turns under the finger: the strip stays until the finger is up.
        page(a, "left", "[0.3,false,false]");
        assertEquals("kept while the finger is down", View.VISIBLE, edge.getVisibility());
        edge.dispatchTouchEvent(MotionEvent.obtain(t, t + 32, MotionEvent.ACTION_UP, x - 240, y, 0));
        assertEquals(3, heard.size());
        assertEquals(MotionEvent.ACTION_DOWN, (int) heard.get(0)[0]);
        assertEquals("in the home page's place", edge.getLeft() + x, heard.get(0)[1], 0.5f);
        assertEquals(edge.getTop() + y, heard.get(0)[2], 0.5f);
        assertEquals(edge.getLeft() + x - 240, heard.get(2)[1], 0.5f);
        assertEquals("gone once the finger is up mid-swipe", View.GONE, edge.getVisibility());
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void turnedOffItIsNeverMade() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ClaudePage.ON, false).commit();
        HomeActivity a = Robolectric.buildActivity(HomeActivity.class).setup().get(); idle(); layout(a);
        ClaudePage claude = (ClaudePage) field(a, "claude");
        page(a, "left", "[0,true,false]");
        assertFalse(claude.open()); assertEquals(View.GONE, claude.host.getVisibility()); assertFalse(claude.here());
        // Turned on from 화면 꾸미기 while the left page is shown: made then.
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ClaudePage.ON, true).commit(); idle();
        assertTrue(claude.open()); assertTrue(claude.here());
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putBoolean(ClaudePage.ON, false).commit(); idle();
        assertFalse("off again: closed", claude.open());
    }

    /** Links: claude.ai and its sign-ins stay; other sites (a tap) go to the AI 브라우저; a sign-in's own redirects stay. */
    @Test public void linksStayOrGoToTheBrowser() {
        assertTrue(ClaudePage.stays("https://claude.ai/code/session_01abc", "https://claude.ai/code", true, false));
        assertTrue(ClaudePage.stays("https://claude.ai/login", "", false, true));
        assertTrue("Google sign-in", ClaudePage.stays("https://accounts.google.com/o/oauth2/v2/auth?client_id=x", "https://claude.ai/login", true, false));
        assertTrue("GitHub sign-in for Claude Code", ClaudePage.stays("https://github.com/login/oauth/authorize?client_id=x", "https://claude.ai/code", true, false));
        assertTrue("GitHub app install", ClaudePage.stays("https://github.com/apps/claude/installations/new", "https://claude.ai/code", true, false));
        assertFalse("a repository link goes to the AI 브라우저", ClaudePage.stays("https://github.com/anthropics/claude-code", "https://claude.ai/code", true, false));
        assertFalse("another site, tapped", ClaudePage.stays("https://docs.anthropic.com/en/docs", "https://claude.ai/code", true, false));
        assertTrue("Anthropic's own address as a redirect", ClaudePage.stays("https://claude.com/login", "https://claude.ai/login", false, true));
        assertTrue("a sign-in's step back to its site", ClaudePage.stays("https://auth.example.com/callback", "https://accounts.google.com/signin", false, true));
        assertFalse("but a link tapped on the sign-in page", ClaudePage.stays("https://policies.google.com/privacy", "https://claude.ai/login", true, false));
        assertTrue(ClaudePage.stays("about:blank", "", false, false));
        assertTrue("claude.ai's own file", ClaudePage.stays("blob:https://claude.ai/1234", "", true, false));
        assertFalse(ClaudePage.stays("blob:https://example.com/1234", "", true, false));
        assertFalse(ClaudePage.stays("javascript:alert(1)", "", true, false));
        assertFalse("not claude.ai by its name alone", ClaudePage.stays("https://claude.ai.example.com/", "", true, false));
        assertFalse(ClaudePage.stays("intent://x#Intent;end", "", true, false));
        assertTrue(ClaudePage.inside("https://claude.ai/code")); assertFalse(ClaudePage.inside("http://claude.ai/code"));
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
