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

    @Test public void theSlideHandsOverWithoutThePhonesOwnTransition() {
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Intent seamless = BrowserActivity.homeIntent(a, true), plain = BrowserActivity.homeIntent(a, false);
        assertEquals(HomeActivity.class.getName(), seamless.getComponent().getClassName());
        assertEquals("홈", seamless.getStringExtra(HomeActivity.PAGE));
        assertTrue("after the slide: no transition of the phone's own", (seamless.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION) != 0);
        assertTrue((seamless.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals("without the slide (the user's photo): the phone's own", 0, plain.getFlags() & Intent.FLAG_ACTIVITY_NO_ANIMATION);
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
        LinearLayout zoom = ui.row(); zoom.addView(ui.pill("−", 0, false, () -> {})); zoom.addView(ui.pill("100%", 0, false, () -> {})); zoom.addView(ui.pill("+", 0, false, () -> {}));
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
        assertTrue("the zoom row no taller than its 34dp buttons need", group.getChildAt(6).getHeight() <= Math.round(43 * d));
        shot(sheet.panel, "browser_menu_compact");

        // On a phone it drops from its button (right edge under it), not across the bottom.
        Rect screen = new Rect(0, 0, ui.dp(411), ui.dp(860)), button = new Rect(ui.dp(365), ui.dp(60), ui.dp(411), ui.dp(106));
        Rect at = BrowserPopup.placement(screen, button, width, sheet.panel.getMeasuredHeight(), true, ui.dp(8));
        assertEquals("under the button", button.bottom + ui.dp(8), at.top);
        assertTrue("its right edge by the button's", Math.abs(at.right - (screen.right - ui.dp(8))) <= 1);
        assertEquals(width, at.width());
    }
}
