package dev.jun.gyeot;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.LinearLayout;
import dev.jun.gyeot.core.AgyUsage;
import dev.jun.gyeot.core.Limits;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** 설정 → Antigravity: agy's table of limits as the usage bars, one per family and window, not raw timestamps. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class AgyLimitsTests {
    @Test @Config(qualifiers="w411dp-h891dp") public void tableReadsAsBars() throws Exception {
        long now = java.time.Instant.parse("2026-11-25T00:30:00Z").toEpochMilli();
        String table = "Claude and GPT models   Weekly Limit Remaining       100%        2026-12-02T00:13:50Z\n"
            + "Claude and GPT models   Five Hour Limit Remaining    100%        2026-11-25T05:13:50Z\n"
            + "Gemini Models           Weekly Limit Remaining       98%         2026-12-01T02:48:37Z\n"
            + "Gemini Models           Five Hour Limit Remaining    100%        2026-11-25T04:48:06Z\n";
        List<Limits.Window> windows = AgyUsage.windows(AgyUsage.rows(table), now);
        assertEquals(4, windows.size());
        assertEquals("Claude·GPT 5시간 단위 한도", Limits.heading(windows.get(0)));
        assertEquals("Gemini 주간 사용 한도", Limits.heading(windows.get(3)));
        assertEquals("98% 남음", Limits.remaining(windows.get(3)));
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        Ui ui = new Ui(a);
        LinearLayout box = ui.column(); box.setBackgroundColor(Ui.CARD); box.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), ui.dp(14));
        for (Limits.Window w : windows) box.addView(UsageUi.window(ui, w, now));
        a.setContentView(box, new android.view.ViewGroup.LayoutParams(-1, -2));
        View decor = a.getWindow().getDecorView(); var m = decor.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        assertTrue("four bars stay compact: " + box.getHeight(), box.getHeight() < ui.dp(4 * 72 + 30));
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), box.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); box.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "agy_limits.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
