package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
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
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 화면 꾸미기 → 퍼레 두뇌 (준: "우리앱이 수십가지 모델을 사용가능한데 세부모델 고를 수가 없어", "메뉴도 너무 허접해"): the quick brains
 * and every AI with its icon, and an AI's own models listed one by one to choose from.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BuddyBrainTests {
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View text(View root, String start) { return find(root, x -> x instanceof TextView t && t.getText().toString().startsWith(start)); }
    private static void settle(View d) {
        var m = d.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            d.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
            d.layout(0, 0, d.getMeasuredWidth(), d.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void shot(View v, String name) throws Exception {
        settle(v);
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }

    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void picksAModelOfAnAi() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().settings.edit().putString("groqModels", "[\"openai/gpt-oss-120b\",\"qwen/qwen3-32b\",\"llama-3.3-70b-versatile\",\"moonshotai/kimi-k2-instruct\"]").commit();
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        View decor = a.getWindow().getDecorView(); settle(decor);
        View row = text(decor, "퍼레 두뇌"); assertNotNull("퍼레 두뇌 row", row);
        while (!row.isClickable() && row.getParent() instanceof View p) row = p;
        assertTrue(row.performClick()); shadowOf(Looper.getMainLooper()).idle();
        Dialog menu = ShadowDialog.getLatestDialog(); assertNotNull("the brain menu", menu);
        View m = menu.getWindow().getDecorView();
        assertNotNull("quick brains", text(m, "빠른 두뇌"));
        assertNotNull("the app's AIs", text(m, "앱의 AI · 모델 직접 고르기"));
        for (String ai : new String[]{"ChatGPT", "Claude", "Gemini", "Grok", "NVIDIA", "Antigravity", "Groq"}) assertNotNull(ai + " listed", text(m, ai));
        shot(m, "buddy_brain_menu");
        // An AI's models, one row each.
        var models = DecorActivity.class.getDeclaredMethod("brainModels", String.class); models.setAccessible(true);
        models.invoke(a, dev.jun.gyeot.core.WorkBook.GROQ); shadowOf(Looper.getMainLooper()).idle();
        Dialog list = ShadowDialog.getLatestDialog(); assertNotSame(menu, list);
        View l = list.getWindow().getDecorView();
        assertNotNull("the count", text(l, "모델 4개"));
        assertNotNull("GPT OSS 120B listed", find(l, v -> v instanceof TextView t && t.getText().toString().contains("GPT OSS 120B")));
        assertNotNull("Qwen listed", find(l, v -> v instanceof TextView t && t.getText().toString().toLowerCase().contains("qwen")));
        shot(l, "buddy_brain_models");
    }

    /** 부르는 호칭 (준: "호칭은 내가 설정할 수 있게해줘"): typed and saved, 퍼레 then uses it. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void setsTheTitle() throws Exception {
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        View decor = a.getWindow().getDecorView(); settle(decor);
        View row = text(decor, "부르는 호칭"); assertNotNull("the title row", row);
        while (!row.isClickable() && row.getParent() instanceof View p) row = p;
        assertTrue(row.performClick()); shadowOf(Looper.getMainLooper()).idle();
        Dialog sheet = ShadowDialog.getLatestDialog(); View d = sheet.getWindow().getDecorView();
        android.widget.EditText field = (android.widget.EditText) find(d, v -> v instanceof android.widget.EditText);
        assertEquals("대장님", field.getText().toString());
        field.setText("주인공님");
        View save = text(d, "저장"); while (!save.isClickable() && save.getParent() instanceof View p) save = p;
        assertTrue(save.performClick()); shadowOf(Looper.getMainLooper()).idle();
        assertEquals("주인공님", BuddyTalk.title(a));
        assertTrue(BuddyTalk.persona(a).contains("'주인공님'"));
    }

    /** 퍼레의 기억 (opened after the fingerprint): 오늘, 발견 노트, 약속, 추억; private ones marked 🔒. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void showsTheMemoryBook() throws Exception {
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        long now = System.currentTimeMillis();
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> list = new java.util.ArrayList<>();
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 86_400_000L * 3, "fact", "사용자는 고양이를 좋아한대요", 2, false));
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 86_400_000L, "fact", "사용자는 다음 주에 병원에 간대요", 2, true));
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 3_600_000L, "promise", "퍼레가 내일 아침 날씨를 알려 주기로 했어요", 2, false));
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 7_200_000L, "profile", "평일 아침 8시쯤 지도 앱을 열어요", 3, false));
        BuddyTalk.today(a, "app", "지도·이동");
        var book = DecorActivity.class.getDeclaredMethod("memorySheet", java.util.List.class); book.setAccessible(true);
        book.invoke(a, list); shadowOf(Looper.getMainLooper()).idle();
        View d = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        for (String part : new String[]{"오늘", "발견 노트 · 나에 대해", "우리 약속", "추억"}) assertNotNull(part, text(d, part));
        assertNotNull("a private memory marked", text(d, "🔒 사용자는 다음 주에 병원에"));
        shot(d, "buddy_memory_book");
    }
}
