package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;
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
 * 트레이딩's TradingView button (준: "내트뷰 버튼은 너무 복잡해", "내트뷰 누르면 그냥 ai브라우저로만 열리기 / 다른 메뉴는 없애줘",
 * "로그인도 없애줘", "내트뷰라고 하지말고 트레이딩뷰 공식 아이콘만 쓰자"): TradingView's mark alone in the bar (no words, no menu,
 * no sign-in state), compact, the title keeping its room on a phone; a tap opens the picked symbol's chart in the AI 브라우저.
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
    private static View described(Activity a, String words) {
        ArrayList<View> found = new ArrayList<>(); a.getWindow().getDecorView().findViewsWithText(found, words, View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        return found.isEmpty() ? null : found.get(0);
    }
    private static float dp(Activity a, int px) { return px / a.getResources().getDisplayMetrics().density; }
    /** A message from the page, as its port gives it (trading.html). */
    private static void page(ProjectActivity a, String json) throws Exception {
        java.lang.reflect.Method m = ProjectActivity.class.getDeclaredMethod("fromPage", String.class); m.setAccessible(true); m.invoke(a, json);
        shadowOf(Looper.getMainLooper()).idle(); settle(a);
    }

    @Before public void fresh() throws Exception { reset(ProjectMaker.class); }
    private static ProjectActivity open(Application app, String id) {
        return Robolectric.buildActivity(ProjectActivity.class, new Intent(app, ProjectActivity.class).putExtra(ProjectActivity.ID, id)).setup().get();
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void oneButtonOpensTheChart() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ProjectActivity a = open(app, ProjectMaker.get(app).fromTemplate(ProjectMaker.TRADING).id()); settle(a);
        View tv = described(a, "트레이딩뷰에서 열기"); assertNotNull("TradingView's button in the bar", tv);
        assertTrue("an icon, no words", tv instanceof ImageView);
        assertTrue("on screen", tv.isShown() && tv.getWidth() > 0);
        assertTrue("compact: 44dp (" + dp(a, tv.getWidth()) + "×" + dp(a, tv.getHeight()) + ")", dp(a, tv.getWidth()) <= 44.5f && dp(a, tv.getHeight()) <= 44.5f);
        for (String gone : new String[]{"내 트뷰", "트뷰 로그인", "로그아웃 상태", "격자"}) {
            ArrayList<View> found = new ArrayList<>(); a.getWindow().getDecorView().findViewsWithText(found, gone, View.FIND_VIEWS_WITH_TEXT);
            assertTrue("no " + gone, found.isEmpty());
        }
        TextView title = (TextView) field(a, "title");
        assertEquals("트레이딩", title.getText().toString());
        assertTrue("the title is not cut on a phone", title.getLayout() != null && title.getLayout().getEllipsisCount(0) == 0);
        shot(a, "trading-tradingview-button");

        // The page picked nothing yet: TradingView's chart as it is.
        tv.performClick(); shadowOf(Looper.getMainLooper()).idle();
        Intent opened = shadowOf(a).getNextStartedActivity();
        assertNotNull("the AI 브라우저 opens", opened);
        assertEquals(BrowserActivity.class.getName(), opened.getComponent().getClassName());
        assertEquals("at TradingView's chart", "https://www.tradingview.com/chart/", opened.getStringExtra(BrowserActivity.OPEN));
        // The page's pick (the card made large, its interval): that chart; a symbol that is not one is not taken.
        page(a, "{\"type\":\"tv_symbol\",\"symbol\":\"NASDAQ:QQQ\",\"interval\":\"60\"}");
        page(a, "{\"type\":\"tv_symbol\",\"symbol\":\"javascript:alert(1)\",\"interval\":\"x\"}");
        tv.performClick(); shadowOf(Looper.getMainLooper()).idle();
        opened = shadowOf(a).getNextStartedActivity();
        assertNotNull(opened);
        assertEquals("that symbol and interval", "https://www.tradingview.com/chart/?symbol=NASDAQ%3AQQQ&interval=60", opened.getStringExtra(BrowserActivity.OPEN));
        // 새로 고침 is in the menu (the button has its place in the bar).
        ArrayList<View> refresh = new ArrayList<>(); a.getWindow().getDecorView().findViewsWithText(refresh, "새로 고침", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        assertTrue("no 새로 고침 icon in 트레이딩's bar", refresh.isEmpty());
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void otherProjectsKeepTheirBar() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        ProjectMaker maker = ProjectMaker.get(app);
        String id = maker.book().create("환율 계산기", "달러·엔·유로 환율을 한 번에 계산해요", "app", "<!doctype html><html><body>x</body></html>", 1).id();
        ProjectActivity a = open(app, id); settle(a);
        assertNull("no TradingView button on another project", described(a, "트레이딩뷰에서 열기"));
        assertNotNull("새로 고침 stays in the bar", described(a, "새로 고침"));
    }
}
