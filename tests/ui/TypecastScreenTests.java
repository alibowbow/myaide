package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import java.io.File;
import java.io.FileOutputStream;
import java.util.function.Predicate;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 설정 → AI 연결 → 타입캐스트 opens its own page (준: "타입캐스트 메뉴가 눌러도 안 들어가져": the page was missing from the
 * settings pages, so its row went back to the list). Its row on the settings home opens it too.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TypecastScreenTests {
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View text(View root, String start) { return find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().startsWith(start)); }
    private static void settle(Activity a) {
        View d = a.getWindow().getDecorView(); var m = d.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            d.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
            d.layout(0, 0, d.getMeasuredWidth(), d.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void shot(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView(); settle(a);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    @Test @Config(qualifiers="w411dp-h891dp") public void opensItsPage() throws Exception {
        Activity a = Robolectric.buildActivity(MainActivity.class, new Intent().putExtra(MainActivity.SETTINGS_PAGE, "typecast")).setup().get();
        settle(a);
        View decor = a.getWindow().getDecorView();
        assertNotNull("the key button of the Typecast page", text(decor, "API 키 넣기"));
        assertNotNull("what it is for", text(decor, "홈 화면 퍼레를 꾹 누르면"));
        shot(a, "typecast_page");
    }
    @Test @Config(qualifiers="w411dp-h891dp") public void rowOpensThePage() throws Exception {
        Activity a = Robolectric.buildActivity(MainActivity.class, new Intent().putExtra(MainActivity.SETTINGS_PAGE, "groq")).setup().get();
        settle(a);
        // From Groq back to the list, then its 타입캐스트 row.
        a.onBackPressed(); settle(a);
        View decor = a.getWindow().getDecorView();
        View row = text(decor, "타입캐스트"); assertNotNull("타입캐스트 row on the settings list", row);
        while (!row.isClickable() && row.getParent() instanceof View p) row = p;
        assertTrue(row.performClick()); settle(a);
        assertNotNull("the Typecast page opened", text(a.getWindow().getDecorView(), "API 키 넣기"));
    }
}
