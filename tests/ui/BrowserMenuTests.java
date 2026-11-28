package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Rect;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * The AI 브라우저's menu and what it opens (준: "Ai 브라우저 메뉴창이 어지럽게 나열되어 있어 체계적이지 않고 필요없는 메뉴도 있고
 * 거기서 뜨는 팝업메뉴는 쓸데없이 큼직하고 컴팩트하지도 않아"): rows of 42dp in 14.5sp, a card about 300dp wide dropping from its
 * button on a phone (not across the bottom), the page's actions as icons in one row. And the swipe back to 홈 hands over
 * with no transition of the phone's own (준: "슬라이드 전환할때 홈으로 바로 가지않고 다시 깜빡이는 효과가 들어가").
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BrowserMenuTests {
    private static void shot(View v, String name) throws Exception {
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    /**
     * Opened as a new task (the home screen's left page, notifications, 퍼레), the browser is a task of its own, so what is
     * behind it is the home screen and the swipe back can make it see-through over 홈 (not over the app's main screen).
     */
    @Test public void theBrowserIsATaskOfItsOwn() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        android.content.pm.ActivityInfo info = a.getPackageManager().getActivityInfo(new android.content.ComponentName(a, BrowserActivity.class), 0);
        assertEquals("dev.jun.gyeot.browser", info.taskAffinity);
        assertNotEquals("not the main screen's task", a.getPackageManager().getActivityInfo(new android.content.ComponentName(a, MainActivity.class), 0).taskAffinity, info.taskAffinity);
    }

    @Test public void theSlideHandsOverWithoutThePhonesOwnTransition() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Intent seamless = BrowserActivity.homeIntent(a, true), plain = BrowserActivity.homeIntent(a, false);
        assertEquals(HomeActivity.class.getName(), seamless.getComponent().getClassName());
        assertEquals("홈", seamless.getStringExtra(HomeActivity.PAGE));
        assertTrue("after the slide: no transition of the phone's own", (seamless.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0);
        assertTrue((seamless.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals("without the slide (the user's photo): the phone's own", 0, plain.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION);
    }

    /**
     * The phone plays its own going-home transition anyway (0.4.675's video: the wallpaper, then 홈 fading in): 홈's picture is
     * laid over every window meanwhile and lifted once 홈 is in front and that has run; never left up; nothing without a window
     * over other apps.
     */
    @Test public void homesPictureCoversThePhonesTransition() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Bitmap picture = Bitmap.createBitmap(60, 120, Bitmap.Config.ARGB_8888);
        Rect at = new Rect(0, 0, 100, 200);
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        assertFalse("no window over other apps: nothing", HomeCurtain.cover(a, picture, at));
        assertFalse(HomeCurtain.covering());
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        assertTrue(HomeCurtain.cover(a, picture, at));
        assertTrue(HomeCurtain.covering());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(300));
        assertTrue("still up while 홈 is not in front", HomeCurtain.covering());
        HomeCurtain.homeShown();
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(HomeCurtain.AFTER_HOME_MS - 50));
        assertTrue("up while the phone's transition runs", HomeCurtain.covering());
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
        assertFalse("lifted after it", HomeCurtain.covering());
        // 홈 says its entering has finished: lifted at once, also when its resume comes after.
        assertTrue(HomeCurtain.cover(a, picture, at));
        HomeCurtain.homeDrawn(); HomeCurtain.homeShown();
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(HomeCurtain.AFTER_DRAWN_MS + 200));
        assertFalse("lifted when 홈's entering ended", HomeCurtain.covering());
        // 홈 never says it is in front: lifted anyway.
        assertTrue(HomeCurtain.cover(a, picture, at));
        shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(HomeCurtain.MOST_MS + 400));
        assertFalse("never left over the screen", HomeCurtain.covering());
        assertFalse("no picture: nothing", HomeCurtain.cover(a, null, at));
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void theMenuIsCompact() throws Exception {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a); float d = a.getResources().getDisplayMetrics().density;
        Ui.Sheet sheet = ui.selectionSheet("google.com", null);
        LinearLayout quick = ui.row();
        for (int icon : new int[]{R.drawable.ic_nav_forward, R.drawable.ic_heart, R.drawable.ic_share, R.drawable.ic_copy, R.drawable.ic_refresh})
            quick.addView(ui.quietIcon(icon, "icon", () -> {}), new LinearLayout.LayoutParams(0, ui.dp(40), 1));
        sheet.body.addView(quick);
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_pages, Ui.MUTED, "탭 3개", null, null, () -> {}), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_lock, Ui.MUTED, "새 시크릿 탭", null, null, () -> {}), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_browser, Ui.MUTED, "데스크톱 사이트", null, ui.toggle("", false, on -> {}), () -> {}), true);
        LinearLayout zoom = ui.row();
        for (String w : new String[]{"−", "100%", "+"}) { TextView b = ui.pill(w, 0, false, () -> {}); b.setMinHeight(ui.dp(30)); b.setMinimumHeight(ui.dp(30)); b.setHeight(ui.dp(30)); zoom.addView(b, new LinearLayout.LayoutParams(-2, ui.dp(30))); }
        ui.addRow(group, ui.listRow(R.drawable.ic_text, Ui.MUTED, "배율", null, zoom, null), true);
        sheet.body.addView(group);
        BrowserPopup.prepare(ui, sheet, false);
        View row = group.getChildAt(0);
        int width = ui.dp(296);
        sheet.panel.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(ui.dp(800), View.MeasureSpec.AT_MOST));
        sheet.panel.layout(0, 0, sheet.panel.getMeasuredWidth(), sheet.panel.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        assertTrue("a row is 42dp, not 48 " + row.getHeight() / d, row.getHeight() <= Math.round(43 * d));
        TextView title = ((Ui.ListRow) row).parts.title();
        assertEquals("its words 14.5sp", 14.5f, title.getTextSize() / a.getResources().getDisplayMetrics().scaledDensity, .05f);
        assertTrue("the zoom row a row too (44dp) " + group.getChildAt(6).getHeight() / d, group.getChildAt(6).getHeight() <= Math.round(45 * d));
        // A single-line centered word is brought into view before its first drawing (onPreDraw, as on a phone's window).
        for (int i = 0; i < zoom.getChildCount(); i++) ((TextView) zoom.getChildAt(i)).onPreDraw();
        shot(sheet.panel, "browser_menu_compact");

        // On a phone it drops from its button (right edge under it), not across the bottom.
        Rect screen = new Rect(0, 0, ui.dp(411), ui.dp(860)), button = new Rect(ui.dp(365), ui.dp(60), ui.dp(411), ui.dp(106));
        Rect at = BrowserPopup.placement(screen, button, width, sheet.panel.getMeasuredHeight(), true, ui.dp(8));
        assertEquals("under the button", button.bottom + ui.dp(8), at.top);
        assertTrue("its right edge by the button's", Math.abs(at.right - (screen.right - ui.dp(8))) <= 1);
        assertEquals(width, at.width());
    }
}
