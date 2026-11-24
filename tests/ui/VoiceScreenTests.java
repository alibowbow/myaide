package dev.jun.gyeot;

import android.app.Activity;
import dev.jun.gyeot.core.Brand;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ScrollView;
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

/** 눌러서 말하기 full screen after a call: the conversation fills the screen and a long answer opens at its first line. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class VoiceScreenTests {
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View text(View root, String start) { return find(root, x -> x instanceof TextView t && x.isShown() && t.getText().toString().startsWith(start)); }
    private static void layout(View decor) {
        var m = decor.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    }
    private static void settle(Activity a) { View d = a.getWindow().getDecorView(); for (int i = 0; i < 3; i++) { layout(d); shadowOf(Looper.getMainLooper()).idle(); } }
    private static void shot(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView(); settle(a);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private void fullAnswer(String name) throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        AppStore store = app.store(); store.clearConversation();
        store.transcript("나", "이 앱 출시 계획 정리해 줘");
        StringBuilder answer = new StringBuilder("정해주셔야 할 것\n");
        for (int i = 1; i <= 30; i++) answer.append(i).append(". 확인할 항목 ").append(i).append("번이에요. 설명이 조금 길게 이어져요.\n");
        answer.append("— Claude 분석");
        store.transcript(Brand.NAME, answer.toString());
        Activity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        settle(a);
        View decor = a.getWindow().getDecorView();
        View title = text(decor, "눌러서 말하기"); assertNotNull("orb title", title);
        View words = title; while (!words.isClickable() && words.getParent() instanceof View p) words = p;
        assertTrue(words.performClick()); settle(a);
        View first = text(decor, "정해주셔야 할 것"); assertNotNull("answer", first);
        ScrollView said = null; for (View v = first; v != null; v = v.getParent() instanceof View p ? p : null) if (v instanceof ScrollView s) { said = s; break; }
        assertNotNull(said);
        int screen = decor.getHeight();
        assertTrue("conversation fills full screen: " + said.getHeight() + " of " + screen, said.getHeight() > screen * 0.6);
        // The answer's first line is on screen (not scrolled away above the box).
        int[] at = new int[2], box = new int[2]; first.getLocationInWindow(at); said.getLocationInWindow(box);
        assertTrue("answer starts in view: " + at[1] + " box " + box[1], at[1] >= box[1] && at[1] < box[1] + said.getHeight());
        shot(a, name);
    }
    @Test @Config(qualifiers="w411dp-h891dp") public void phoneFullScreenAnswer() throws Exception { fullAnswer("voice_full_phone"); }
    @Test @Config(qualifiers="w840dp-h900dp") public void tabletFullScreenAnswer() throws Exception { fullAnswer("voice_full_tablet"); }
}
