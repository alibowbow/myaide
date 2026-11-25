package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
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
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/** 내 휴대폰 → 화면 꾸미기: the HTML 100 pieces listed a few at a time by kind, compact, with the wallpaper line on top. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DecorScreenTests {
    private static final String DATA = "window.OPUS_HTML_ATLAS = {\"categories\": [{\"id\": \"art\", \"name\": \"애니메이션·아트\"}, {\"id\": \"web\", \"name\": \"웹·디자인\"}, {\"id\": \"science\", \"name\": \"과학·시뮬레이션\"}, {\"id\": \"tools\", \"name\": \"도구·대시보드\"}, {\"id\": \"game\", \"name\": \"게임\"}, {\"id\": \"music\", \"name\": \"음악·사운드\"}], \"cases\": [{\"id\": \"html-009\", \"number\": \"009\", \"category\": \"art\", \"title\": \"직접 그리는 별자리\", \"originalTitle\": \"Asterism — Draw Your Own Sky\", \"summary\": \"밤하늘의 별을 골라 선으로 연결하는 천체 지도입니다. 하늘을 움직이며 나만의 별자리 모양을 그릴 수 있습니다.\", \"demo\": \"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/009-constellation-mapper.html\", \"media\": {\"poster\": \"\"}}, {\"id\": \"html-010\", \"number\": \"010\", \"category\": \"art\", \"title\": \"액체 크롬 조각\", \"originalTitle\": \"Mercury\", \"summary\": \"금속 방울과 글자가 서로 앞뒤로 겹치는 WebGL 작품입니다. 커서를 따라 크롬 표면이 늘어나고 작은 방울이 움직입니다.\", \"demo\": \"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/010-liquid-mercury.html\", \"media\": {\"poster\": \"\"}}, {\"id\": \"html-011\", \"number\": \"011\", \"category\": \"art\", \"title\": \"상상 속 식물 표본함\", \"originalTitle\": \"Herbarium Imaginarium\", \"summary\": \"오래된 식물 표본처럼 가지와 꽃을 절차적으로 생성합니다. 새 표본을 수집하면 줄기가 자라고 잎이 펼쳐집니다.\", \"demo\": \"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/011-victorian-herbarium.html\", \"media\": {\"poster\": \"\"}}, {\"id\": \"html-012\", \"number\": \"012\", \"category\": \"web\", \"title\": \"날씨에 따라 바뀌는 대시보드\", \"originalTitle\": \"Nimbus — Weather for Port Aurelia\", \"summary\": \"가상 도시의 날씨를 보여주는 인터페이스입니다. 날짜를 선택하면 비·눈·낮·밤에 맞춰 배경과 카드의 색이 함께 바뀝니다.\", \"demo\": \"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/012-nimbus-weather.html\", \"media\": {\"poster\": \"\"}}, {\"id\": \"html-013\", \"number\": \"013\", \"category\": \"science\", \"title\": \"뜨개질로 보는 생명 게임\", \"originalTitle\": \"Woolgathering\", \"summary\": \"콘웨이 생명 게임의 세포를 털실 코처럼 표현합니다. 천 위에 무늬를 그리고 재생 속도를 바꾸며 다음 세대를 관찰합니다.\", \"demo\": \"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/013-knitted-life.html\", \"media\": {\"poster\": \"\"}}, {\"id\": \"html-014\", \"number\": \"014\", \"category\": \"art\", \"title\": \"스크롤로 연주하는 글자\", \"originalTitle\": \"MOVE — A Manifesto in Motion\", \"summary\": \"문장이 조립되고 늘어나고 잘리는 타이포그래피 작품입니다. 스크롤을 앞뒤로 움직이면 각 장면의 변화를 되짚을 수 있습니다.\", \"demo\": \"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/014-kinetic-manifesto.html\", \"media\": {\"poster\": \"\"}}]};";
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void layout(View decor) {
        var m = decor.getResources().getDisplayMetrics();
        decor.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
        decor.layout(0, 0, decor.getMeasuredWidth(), decor.getMeasuredHeight());
    }
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void listsPieces() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File kept = new File(new File(app.getFilesDir(), "decor"), "opus.js"); kept.getParentFile().mkdirs();
        java.nio.file.Files.writeString(kept.toPath(), DATA);
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        View decor = a.getWindow().getDecorView(); boolean listed = false;
        for (int i = 0; i < 80 && !listed; i++) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle(); listed = find(decor, v -> v instanceof TextView t && t.getText().toString().contains("011 · ")) != null; }
        assertTrue("the pieces show", listed);
        layout(decor); shadowOf(Looper.getMainLooper()).idle(); layout(decor);
        assertNotNull("kinds as chips", find(decor, v -> v instanceof TextView t && t.getText().toString().equals("애니메이션·아트")));
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "decor.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
