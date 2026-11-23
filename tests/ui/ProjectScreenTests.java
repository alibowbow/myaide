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
    }
    @Test @Config(qualifiers="w900dp-h700dp-land-night-hdpi") public void wideProjects() throws Exception {
        fresh(); Application app = RuntimeEnvironment.getApplication();
        ((GyeotApp) app).store().settings.edit().putInt("pagesSection", 1).commit();
        ProjectMaker maker = ProjectMaker.get(app); maker.seed(((GyeotApp) app).store());
        maker.book().create("환율 계산기", "달러·엔·유로 환율을 한 번에 계산해요", "app", "<html></html>", 1);
        maker.book().create("운동 기록장", "세트와 무게를 적고 주간 그래프로 봐요", "app", "<html></html>", 3);
        Activity a = Robolectric.buildActivity(PagesActivity.class, new Intent(app, PagesActivity.class)).setup().get();
        shot(a, "pages-projects-wide");
        String id = maker.book().projects().get(0).id();
        Activity p = Robolectric.buildActivity(ProjectActivity.class, new Intent(app, ProjectActivity.class).putExtra(ProjectActivity.ID, id)).setup().get();
        shot(p, "project-view");
    }
}
