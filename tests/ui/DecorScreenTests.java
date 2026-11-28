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

    /** 퍼레 홈's pages: photo, board and a piece listed by page; a foldable's opened screen keeps them at reading width. */
    @Test @Config(qualifiers="w714dp-h831dp-xxhdpi") public void listsPagesOnAnOpenedFoldable() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File kept = new File(new File(app.getFilesDir(), "decor"), "opus.js"); kept.getParentFile().mkdirs();
        java.nio.file.Files.writeString(kept.toPath(), DATA);
        app.getSharedPreferences(HtmlWallpaper.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(HtmlWallpaper.LAYOUT,
            "[{\"src\":\"photo\",\"title\":\"원래 사진\"},{\"src\":\"home\",\"title\":\"퍼레 홈\"},{\"src\":\"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/011-victorian-herbarium.html\",\"title\":\"상상 속 식물 표본함\"}]").commit();
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        for (int i = 0; i < 40; i++) { Thread.sleep(50); shadowOf(Looper.getMainLooper()).idle(); }
        layout(decor); shadowOf(Looper.getMainLooper()).idle(); layout(decor);
        assertNotNull("page 1 is the photo", find(decor, v -> v instanceof TextView t && t.getText().toString().equals("1페이지 · 원래 사진")));
        assertNotNull("page 2 is the board", find(decor, v -> v instanceof TextView t && t.getText().toString().equals("2페이지 · 퍼레 홈")));
        assertNotNull("page 3 is the piece", find(decor, v -> v instanceof TextView t && t.getText().toString().equals("3페이지 · 상상 속 식물 표본함")));
        View row = find(decor, v -> v instanceof TextView t && t.getText().toString().equals("1페이지 · 원래 사진"));
        int[] at = new int[2]; row.getLocationOnScreen(at);
        float density = a.getResources().getDisplayMetrics().density;
        assertTrue("kept at a phone's reading width in the middle", at[0] / density > 40);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "decor-pages-fold.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    /** The board's rules: pages from offsets, the old single wallpaper still read, nothing private while locked. */
    @Test public void boardRules() {
        assertEquals(-1, dev.jun.gyeot.core.HomeBoard.page(0.5f, 0f), 0.0);
        assertEquals(2.0, dev.jun.gyeot.core.HomeBoard.page(0.5f, 0.25f), 1e-6);
        assertEquals(5, dev.jun.gyeot.core.HomeBoard.pages(0.25f));
        var single = dev.jun.gyeot.core.HomeBoard.slots("", "https://miaai-lab.github.io/x.html", "식물");
        assertEquals(1, single.size()); assertTrue(dev.jun.gyeot.core.HomeBoard.direct(single));
        var two = dev.jun.gyeot.core.HomeBoard.slots("[{\"src\":\"photo\"},{\"src\":\"home\"},{\"src\":\"javascript:x\"}]", "", "");
        assertEquals("unknown kinds are dropped", 2, two.size());
        assertEquals("before the launcher says, the first page that is not the photo", 1, dev.jun.gyeot.core.HomeBoard.start(two, false));
        assertEquals(0, dev.jun.gyeot.core.HomeBoard.start(two, true));
        long now = 1_800_000_000_000L;
        var known = new dev.jun.gyeot.core.HomeBoard.Known(now, true, true, new dev.jun.gyeot.core.HomeBoard.Event("병원", now + 3_600_000, now + 7_200_000, false, ""), 1,
            java.util.List.of(new dev.jun.gyeot.core.HomeBoard.Notice("카카오톡", "com.kakao.talk", "엄마", "밥 먹었니", now)), 1, java.util.List.of(), 0, false, "들퍼레", java.util.List.of());
        var locked = dev.jun.gyeot.core.HomeBoard.json(known);
        assertFalse("locked: no event", locked.has("next")); assertFalse("locked: no notices", locked.has("notices"));
        var open = dev.jun.gyeot.core.HomeBoard.json(new dev.jun.gyeot.core.HomeBoard.Known(now, false, true, known.next(), 1, known.notices(), 1, java.util.List.of(), 0, false, "들퍼레", java.util.List.of()));
        assertEquals("병원", open.optJSONObject("next").optString("title"));
        var quotes = dev.jun.gyeot.core.HomeBoard.quotes("[{\"symbol\":\"KORUUSDT\",\"lastPrice\":\"21.07\",\"priceChangePercent\":\"2.4\"},{\"symbol\":\"BTCUSDT\",\"lastPrice\":\"97412.5\",\"priceChangePercent\":\"1.84\"},{\"symbol\":\"SOXLUSDT\",\"lastPrice\":\"32.48\",\"priceChangePercent\":\"-3.12\"}]", dev.jun.gyeot.core.HomeBoard.WATCH);
        assertEquals("in the order watched", java.util.List.of("BTC", "SOXL", "KORU"), quotes.stream().map(dev.jun.gyeot.core.HomeBoard.Quote::symbol).toList());
        assertEquals(-3.12, quotes.get(1).change(), 1e-9);
        assertTrue("locked still shows the quotes", locked.has("quotes")); assertFalse("no battery", locked.has("battery"));
    }

    /** With 들퍼레 홈 화면 as the home app, the section is its own pages: the left one (Claude Code), 홈, then the user's pages on the right. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void listsTheHomeScreensRightPages() throws Exception {
        Application app = RuntimeEnvironment.getApplication();
        File kept = new File(new File(app.getFilesDir(), "decor"), "opus.js"); kept.getParentFile().mkdirs();
        java.nio.file.Files.writeString(kept.toPath(), DATA);
        app.getSharedPreferences(HomeActivity.PREFS, android.content.Context.MODE_PRIVATE).edit().putString(HomeActivity.PAGES,
            "[{\"src\":\"https://miaai-lab.github.io/Claude-Opus-5.5-100-HTML-Files/011-victorian-herbarium.html\",\"title\":\"상상 속 식물 표본함\"},{\"src\":\"photo\",\"title\":\"사진\"}]").commit();
        android.app.role.RoleManager roles = app.getSystemService(android.app.role.RoleManager.class);
        org.robolectric.shadows.ShadowRoleManager shadowRoles = (org.robolectric.shadows.ShadowRoleManager) org.robolectric.shadow.api.Shadow.extract(roles);
        shadowRoles.addHeldRole(android.app.role.RoleManager.ROLE_HOME);
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        for (int i = 0; i < 30; i++) { Thread.sleep(40); shadowOf(Looper.getMainLooper()).idle(); }
        layout(decor);
        assertNotNull(find(decor, v -> v instanceof TextView t && t.getText().toString().equals("들퍼레 홈 화면 페이지")));
        // The left page is Claude Code unless turned off (ClaudePageTests: its switch).
        assertNotNull(find(decor, v -> v instanceof TextView t && t.getText().toString().equals("왼쪽 · Claude Code")));
        assertNotNull(find(decor, v -> v instanceof TextView t && t.getText().toString().equals("가운데 · 홈")));
        assertNotNull("the piece on the right", find(decor, v -> v instanceof TextView t && t.getText().toString().equals("오른쪽 1 · 상상 속 식물 표본함")));
        assertNull("only web pages on the right (no photo)", find(decor, v -> v instanceof TextView t && t.getText().toString().startsWith("오른쪽 2")));
        assertNull("not the live wallpaper's pages", find(decor, v -> v instanceof TextView t && t.getText().toString().equals("홈 화면 페이지")));
    }
}
