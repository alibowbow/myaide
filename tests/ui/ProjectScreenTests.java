package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import java.io.File;
import java.io.FileOutputStream;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class ProjectScreenTests {
    private static void shot(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView(); var m = decor.getResources().getDisplayMetrics();
        for (int i = 0; i < 2; i++) { decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY)); decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle(); }
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888); Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    /** The app menu (☰) a screen shows, shown on screen or not; null when it has none. */
    private static View appMenu(Activity a) {
        java.util.ArrayList<View> found = new java.util.ArrayList<>();
        a.getWindow().getDecorView().findViewsWithText(found, "앱 메뉴 열기", View.FIND_VIEWS_WITH_CONTENT_DESCRIPTION);
        return found.isEmpty() ? null : found.get(0);
    }
    private static boolean onScreen(View v) { for (View x = v; x != null; x = x.getParent() instanceof View p ? p : null) if (x.getVisibility() != View.VISIBLE) return false; return v.getWidth() > 0; }
    private static void fresh() throws Exception { java.lang.reflect.Field f = ProjectMaker.class.getDeclaredField("instance"); f.setAccessible(true); f.set(null, null); }
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void phoneProjects() throws Exception {
        fresh(); Application app = RuntimeEnvironment.getApplication();
        ((GyeotApp) app).store().settings.edit().putInt("pagesSection", 1).commit();
        ProjectMaker maker = ProjectMaker.get(app);
        maker.seed(((GyeotApp) app).store());
        maker.book().create("환율 계산기", "달러·엔·유로 환율을 한 번에 계산해요", "app", "<!doctype html><html><head><title>t</title></head><body>x</body></html>", 1);
        assertEquals(2, maker.book().projects().size());
        Activity a = Robolectric.buildActivity(PagesActivity.class, new Intent(app, PagesActivity.class)).setup().get();
        shot(a, "pages-projects");
        // 준: "페이지에 햄버거 버튼이 없어": a phone-wide secondary screen has the ☰ at the very top.
        View menu = appMenu(a); assertNotNull(menu); assertTrue("☰ shown on a phone", onScreen(menu));
        int[] at = new int[2]; menu.getLocationOnScreen(at); assertTrue("☰ at the top: " + at[1], at[1] < a.getResources().getDisplayMetrics().density * 120);
    }
    @Test @Config(qualifiers="w900dp-h700dp-land-night-hdpi") public void wideProjects() throws Exception {
        fresh(); Application app = RuntimeEnvironment.getApplication();
        ((GyeotApp) app).store().settings.edit().putInt("pagesSection", 1).commit();
        ProjectMaker maker = ProjectMaker.get(app); maker.seed(((GyeotApp) app).store());
        maker.book().create("환율 계산기", "달러·엔·유로 환율을 한 번에 계산해요", "app", "<html></html>", 1);
        maker.book().create("운동 기록장", "세트와 무게를 적고 주간 그래프로 봐요", "app", "<html></html>", 3);
        Activity a = Robolectric.buildActivity(PagesActivity.class, new Intent(app, PagesActivity.class)).setup().get();
        shot(a, "pages-projects-wide");
        // Wide: the rail is the menu, no ☰ bar on top.
        View menu = appMenu(a); assertTrue("no ☰ bar when the rail shows", menu == null || !onScreen(menu));
        String id = maker.book().projects().get(0).id();
        Activity p = Robolectric.buildActivity(ProjectActivity.class, new Intent(app, ProjectActivity.class).putExtra(ProjectActivity.ID, id)).setup().get();
        shot(p, "project-view");
    }
}
