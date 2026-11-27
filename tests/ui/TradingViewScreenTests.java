package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.TradingViewLink;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
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
 * 트레이딩 with the user's own TradingView (준: "Ai 브라우저 구글 로그인으로 트레이딩뷰도 로그인 연동해 줄 수 있어?", "프로젝트에 트뷰"):
 * the 트뷰 chip at the end of the bar in each sign-in state (compact: a slim capsule, the title keeps its room on a phone), and the
 * user's own chart (a stand-in here: no Gecko in Robolectric) only over the whole page when a card's 크게 보기 asks (the page keeps
 * its own layout otherwise), formerly placed above the page on a phone, beside it on a wide landscape
 * screen, all of the screen when made large, out of the way of the page's sheets, and gone when signed out or turned off.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TradingViewScreenTests {
    private static void settle(Activity a) {
        View decor = a.getWindow().getDecorView(); var m = decor.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) { decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle(); }
    }
    private static void shot(Activity a, String name) throws Exception {
        settle(a); View decor = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888); Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private static Object field(Object o, String name) throws Exception { java.lang.reflect.Field f = o.getClass().getDeclaredField(name); f.setAccessible(true); return f.get(o); }
    private static void reset(Class<?> type) throws Exception { java.lang.reflect.Field f = type.getDeclaredField("instance"); f.setAccessible(true); f.set(null, null); }
    /** A message from the page, as its port gives it (trading.html). */
    private static void page(ProjectActivity a, String json) throws Exception {
        java.lang.reflect.Method m = ProjectActivity.class.getDeclaredMethod("fromPage", String.class); m.setAccessible(true); m.invoke(a, json);
        shadowOf(Looper.getMainLooper()).idle(); settle(a);
    }
    private static TextView chip(Activity a, String words) {
        ArrayList<View> found = new ArrayList<>(); a.getWindow().getDecorView().findViewsWithText(found, words, View.FIND_VIEWS_WITH_TEXT);
        for (View v : found) if (v instanceof TextView t && words.contentEquals(t.getText())) return t;
        return null;
    }
    private static float dp(Activity a, int px) { return px / a.getResources().getDisplayMetrics().density; }

    @Before public void fresh() throws Exception {
        reset(ProjectMaker.class); reset(TradingViewLogin.class);
        // Robolectric is no phone: no Gecko runs (the app itself knows it from the build's fingerprint).
        assertTrue("the screen tests run without Gecko by themselves", TradingViewLogin.noGecko);
        TradingViewPane.standIn = true;
    }
    private static String trading(Application app, TradingViewLink.Login state) throws Exception {
        ((GyeotApp) app).store().settings.edit().putString(TradingViewLogin.STATE, state.name()).putLong(TradingViewLogin.CHECKED, System.currentTimeMillis()).commit();
        return ProjectMaker.get(app).fromTemplate(ProjectMaker.TRADING).id();
    }
    private static ProjectActivity open(Application app, String id) {
        return Robolectric.buildActivity(ProjectActivity.class, new Intent(app, ProjectActivity.class).putExtra(ProjectActivity.ID, id)).setup().get();
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void chipStates() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        // Never signed in: 트뷰 로그인, a slim capsule at the end of the bar; the title keeps its room.
        String id = trading(app, TradingViewLink.Login.NONE);
        ProjectActivity a = open(app, id); settle(a);
        TextView login = chip(a, "트뷰 로그인"); assertNotNull("the chip", login);
        assertTrue("on screen", login.isShown() && login.getWidth() > 0);
        assertTrue("compact: at most 48dp high (" + dp(a, login.getHeight()) + ")", dp(a, login.getHeight()) <= 48);
        assertTrue("compact: as wide as its words (" + dp(a, login.getWidth()) + "dp)", dp(a, login.getWidth()) <= 110);
        TextView title = (TextView) field(a, "title");
        assertEquals("트레이딩", title.getText().toString());
        assertTrue("the title is not cut on a phone", title.getLayout() != null && title.getLayout().getEllipsisCount(0) == 0);
        assertTrue("it says what a tap does", login.getContentDescription().toString().contains("Google"));
        // The page takes the chart, but nobody is signed in: the widgets as before (no chart pane).
        page(a, "{\"type\":\"tv_ready\",\"symbol\":\"AMEX:SOXL\",\"interval\":\"D\"}");
        TradingViewPane pane = (TradingViewPane) field(a, "pane");
        assertEquals("signed out: no chart of the user's", View.GONE, pane.host.getVisibility());
        shot(a, "trading-chip-login");

        // Signed out after having been signed in.
        reset(ProjectMaker.class); reset(TradingViewLogin.class);
        a = open(app, trading(app, TradingViewLink.Login.OUT)); settle(a);
        TextView out = chip(a, "로그아웃 상태"); assertNotNull("signed out: 로그아웃 상태", out);
        assertTrue("compact (" + dp(a, out.getWidth()) + "dp)", dp(a, out.getWidth()) <= 120 && dp(a, out.getHeight()) <= 48);
        assertTrue(((TextView) field(a, "title")).getLayout().getEllipsisCount(0) == 0);
        shot(a, "trading-chip-signed-out");

        // Signed in: 내 트뷰. The page keeps its own layout (준: "이거 배열이 너무 이상해 원래 우리의 레이아웃으로 해줘"): the user's chart
        // only over the whole page when a card's 크게 보기 asks for it, back closes it.
        reset(ProjectMaker.class); reset(TradingViewLogin.class);
        a = open(app, trading(app, TradingViewLink.Login.IN)); settle(a);
        TextView mine = chip(a, "내 트뷰"); assertNotNull("signed in: 내 트뷰", mine);
        assertTrue("compact (" + dp(a, mine.getWidth()) + "dp)", dp(a, mine.getWidth()) <= 90);
        pane = (TradingViewPane) field(a, "pane"); View web = (View) field(a, "web"), stage = (View) field(a, "stage");
        assertEquals("before the page says it takes it: nothing", View.GONE, pane.host.getVisibility());
        page(a, "{\"type\":\"tv_ready\",\"symbol\":\"NASDAQ:NVDA\",\"interval\":\"60\"}");
        assertEquals("the page as it is: no chart over or beside it", View.GONE, pane.host.getVisibility());
        assertEquals("", pane.url());
        assertTrue("the page has the whole screen", web.isShown() && Math.abs(web.getMeasuredHeight() - stage.getHeight()) <= 3);
        shot(a, "trading-mine");
        // A card picked or an interval changed: nothing opens; an address that is not a symbol is not taken.
        page(a, "{\"type\":\"tv_symbol\",\"symbol\":\"AMEX:SOXL\",\"interval\":\"D\"}");
        assertEquals(View.GONE, pane.host.getVisibility());
        page(a, "{\"type\":\"tv_symbol\",\"symbol\":\"javascript:alert(1)\",\"interval\":\"x\"}");
        // 크게 보기: the user's chart of that card, all of the screen; back closes it.
        page(a, "{\"type\":\"tv_focus\",\"symbol\":\"AMEX:SOXL\",\"interval\":\"D\"}");
        assertEquals("크게 보기: the user's chart", View.VISIBLE, pane.host.getVisibility());
        assertEquals("of that card and interval (a bad symbol before was not taken)", TradingViewLink.chartUrl("AMEX:SOXL", "D"), pane.url());
        assertEquals("all of it", stage.getHeight(), pane.host.getHeight());
        assertEquals("the page steps away", View.GONE, web.getVisibility());
        // One tap back to the cards (준: "내트뷰에서 다시 트레이딩 격자형 보기로 바로 돌아가는 버튼이 없어"): 격자, in the bar while the chart is large.
        TextView grid = chip(a, "격자"); assertNotNull("격자 in the bar", grid);
        assertTrue("shown, compact (" + dp(a, grid.getWidth()) + "dp)", grid.isShown() && dp(a, grid.getWidth()) <= 90 && dp(a, grid.getHeight()) <= 48);
        assertTrue(((TextView) field(a, "title")).getLayout().getEllipsisCount(0) == 0 || dp(a, ((TextView) field(a, "title")).getWidth()) >= 60);
        shot(a, "trading-mine-large");
        grid.performClick(); shadowOf(Looper.getMainLooper()).idle(); settle(a);
        assertEquals("격자: the chart closes", View.GONE, pane.host.getVisibility());
        assertEquals(View.VISIBLE, web.getVisibility());
        assertFalse("격자 gone with it", grid.isShown());
        page(a, "{\"type\":\"tv_focus\",\"symbol\":\"AMEX:SOXL\",\"interval\":\"D\"}");
        a.onBackPressed(); shadowOf(Looper.getMainLooper()).idle(); settle(a);
        assertEquals("back: closed", View.GONE, pane.host.getVisibility());
        assertEquals("", pane.url());
        assertEquals(View.VISIBLE, web.getVisibility());
        // A sheet of the page (AI 분석, 종목 편집) has the whole screen while it is open.
        page(a, "{\"type\":\"tv_focus\",\"symbol\":\"NASDAQ:NVDA\",\"interval\":\"60\"}");
        page(a, "{\"type\":\"tv_sheet\",\"open\":true}");
        assertEquals("a sheet open: the chart steps aside", View.GONE, pane.host.getVisibility());
        page(a, "{\"type\":\"tv_sheet\",\"open\":false}");
        assertEquals(View.VISIBLE, pane.host.getVisibility());
        // 크게 보기를 무료 위젯으로: the user's chart closes; 크게 보기 makes the widget large again.
        TradingViewLogin.get(app).mine(false); shadowOf(Looper.getMainLooper()).idle(); settle(a);
        assertEquals("turned off: the widgets", View.GONE, pane.host.getVisibility());
        assertEquals("", pane.url());
        TradingViewLogin.get(app).mine(true); shadowOf(Looper.getMainLooper()).idle(); settle(a);
        page(a, "{\"type\":\"tv_focus\",\"symbol\":\"NASDAQ:NVDA\",\"interval\":\"60\"}");
        assertEquals(View.VISIBLE, pane.host.getVisibility());
        // Signed out (the chart's own page said so): the widgets, and the chip says it.
        TradingViewLogin.get(app).heard(new org.json.JSONObject().put("known", true).put("classes", "is-not-authenticated is-not-pro").put("flag", false), "test");
        shadowOf(Looper.getMainLooper()).idle(); settle(a);
        assertEquals("signed out: the widgets", View.GONE, pane.host.getVisibility());
        assertNotNull("signed out: the chip says so", chip(a, "로그아웃 상태"));
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void stopsAndStarts() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        String id = trading(app, TradingViewLink.Login.IN);
        var controller = Robolectric.buildActivity(ProjectActivity.class, new Intent(app, ProjectActivity.class).putExtra(ProjectActivity.ID, id)).setup();
        ProjectActivity a = controller.get(); settle(a);
        page(a, "{\"type\":\"tv_ready\",\"symbol\":\"NASDAQ:QQQ\",\"interval\":\"W\"}");
        page(a, "{\"type\":\"tv_focus\",\"symbol\":\"NASDAQ:QQQ\",\"interval\":\"W\"}");
        TradingViewPane pane = (TradingViewPane) field(a, "pane");
        assertEquals(TradingViewLink.chartUrl("NASDAQ:QQQ", "W"), pane.url());
        controller.pause().stop(); shadowOf(Looper.getMainLooper()).idle();
        assertEquals("stopped: the chart is closed", "", pane.url());
        controller.start().resume(); shadowOf(Looper.getMainLooper()).idle(); settle(a);
        assertEquals("started: the same chart again", TradingViewLink.chartUrl("NASDAQ:QQQ", "W"), pane.url());
        assertEquals(View.VISIBLE, pane.host.getVisibility());
    }

    @Test @Config(qualifiers="w900dp-h700dp-land-night-hdpi") public void wideScreenKeepsThePage() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ProjectActivity a = open(app, trading(app, TradingViewLink.Login.IN)); settle(a);
        page(a, "{\"type\":\"tv_ready\",\"symbol\":\"BINANCE:BTCUSDT\",\"interval\":\"240\"}");
        TradingViewPane pane = (TradingViewPane) field(a, "pane"); View web = (View) field(a, "web"), stage = (View) field(a, "stage");
        assertEquals("wide landscape: the page as it is, no chart beside it", View.GONE, pane.host.getVisibility());
        assertTrue(web.isShown() && Math.abs(web.getMeasuredWidth() - stage.getWidth()) <= 3);
        assertNotNull(chip(a, "내 트뷰"));
        shot(a, "trading-mine-wide");
        page(a, "{\"type\":\"tv_focus\",\"symbol\":\"BINANCE:BTCUSDT\",\"interval\":\"240\"}");
        assertEquals("크게 보기: the whole width", stage.getWidth(), pane.host.getWidth());
        assertEquals("and height", stage.getHeight(), pane.host.getHeight());
        assertEquals(View.GONE, web.getVisibility());
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void otherProjectsKeepTheirBar() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ProjectMaker maker = ProjectMaker.get(app);
        String id = maker.book().create("환율 계산기", "달러·엔·유로 환율을 한 번에 계산해요", "app", "<!doctype html><html><body>x</body></html>", 1).id();
        ProjectActivity a = open(app, id); settle(a);
        assertNull("no 트뷰 chip on another project", chip(a, "트뷰 로그인"));
        assertNull(field(a, "pane"));
        ArrayList<View> refresh = new ArrayList<>(); a.getWindow().getDecorView().findViewsWithText(refresh, "새로 고침", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertFalse("새로 고침 stays in the bar", refresh.isEmpty());
    }
}
