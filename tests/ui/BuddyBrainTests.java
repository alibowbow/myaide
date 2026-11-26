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

    /** The screen's rotation, the volume and the brightness (준: "가로모드 세로모드 고정 바꾸는게 안돼"). */
    @Test public void turnsTheScreenAndTheSound() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        assertTrue(BuddyTalk.ACTS.contains("rotation") && BuddyTalk.ACTS.contains("volume") && BuddyTalk.ACTS.contains("brightness"));
        BuddyVoice.Outcome o = BuddyActs.run(c, new org.json.JSONObject("{\"tool\":\"rotation\",\"mode\":\"landscape\"}"), "", false);
        if (android.provider.Settings.System.canWrite(c)) {
            assertEquals("가로로 고정했어!", o.say());
            assertEquals(0, android.provider.Settings.System.getInt(c.getContentResolver(), android.provider.Settings.System.ACCELEROMETER_ROTATION));
            assertEquals(android.view.Surface.ROTATION_90, android.provider.Settings.System.getInt(c.getContentResolver(), android.provider.Settings.System.USER_ROTATION));
            assertEquals("자동 회전 켰어!", BuddyActs.run(c, new org.json.JSONObject("{\"tool\":\"rotation\",\"mode\":\"auto\"}"), "", false).say());
        } else assertTrue(o.say(), o.say().contains("시스템 설정 변경"));
        android.media.AudioManager audio = c.getSystemService(android.media.AudioManager.class);
        audio.setStreamVolume(android.media.AudioManager.STREAM_MUSIC, 3, 0);
        assertEquals("소리 키웠어!", BuddyActs.run(c, new org.json.JSONObject("{\"tool\":\"volume\",\"direction\":\"up\"}"), "", false).say());
        assertTrue(audio.getStreamVolume(android.media.AudioManager.STREAM_MUSIC) > 3);
        String bright = BuddyActs.run(c, new org.json.JSONObject("{\"tool\":\"brightness\",\"direction\":\"up\"}"), "", false).say();
        assertTrue(bright, android.provider.Settings.System.canWrite(c) ? bright.equals("화면 밝게 했어!") : bright.contains("시스템 설정 변경"));
    }

    /** 퍼레 over other apps while it talks (준: "퍼레 대화모드일때는 다른 앱 위에 그려지게 해줘"): a small card, its face, its words, ✕. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void talksOverOtherApps() throws Exception {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        boolean[] stopped = {false};
        BuddyOverlay o = new BuddyOverlay(c, () -> stopped[0] = true);
        o.show(); o.state("speaking", "네이버 지도 열었어! 길 찾아 줄 곳 있으면 말해 줘.");
        assertTrue(o.shown());
        android.view.WindowManager wm = c.getSystemService(android.view.WindowManager.class);
        java.util.List<View> views = ((org.robolectric.shadows.ShadowWindowManagerImpl) org.robolectric.shadow.api.Shadow.extract(wm)).getViews();
        View card = views.get(views.size() - 1);
        shot(card, "buddy_overlay");
        float d = c.getResources().getDisplayMetrics().density;
        assertTrue("compact: " + card.getHeight() / d + "dp tall", card.getHeight() / d <= 72);
        assertNotNull(text(card, "네이버 지도 열었어"));
        View close = text(card, "✕"); assertTrue(close.performClick()); assertTrue(stopped[0]);
        o.hide(); assertFalse(o.shown());
    }

    /** The fingerprint can be asked for at all (준: "지문확인을 열지못했어요 뜨면서 메뉴가 안열려"): USE_BIOMETRIC is declared. */
    @Test public void mayAskForTheFingerprint() throws Exception {
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        String[] asked = c.getPackageManager().getPackageInfo(c.getPackageName(), android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions;
        assertTrue(java.util.Arrays.asList(asked).contains(android.Manifest.permission.USE_BIOMETRIC));
    }

    /** 퍼레 does things on the phone (준: "퍼레는 폰조작이 안되네"): the "할일" line read, never said; an app found by what is said. */
    @Test public void doesThingsOnThePhone() {
        String answer = "두구두구! 네이버 지도 바로 열어볼게. https://map.naver.com/ 😊\n할일: {\"tool\":\"open_app\",\"app\":\"네이버 지도\"}";
        org.json.JSONObject act = BuddyTalk.act(answer);
        assertNotNull(act); assertEquals("open_app", act.optString("tool")); assertEquals("네이버 지도", act.optString("app"));
        String said = BuddyTalk.spoken(answer);
        assertFalse(said, said.contains("http") || said.contains("할일") || said.contains("😊"));
        assertTrue(said, said.startsWith("두구두구! 네이버 지도 바로 열어볼게"));
        assertNull(BuddyTalk.act("그냥 이야기야"));
        java.util.Map<String, String[]> apps = new java.util.HashMap<>();
        apps.put("map", new String[]{"네이버 지도", "com.nhn.android.nmap"}); apps.put("gmap", new String[]{"지도", "com.google.android.apps.maps"});
        apps.put("yt", new String[]{"YouTube", "com.google.android.youtube"}); apps.put("talk", new String[]{"카카오톡", "com.kakao.talk"});
        apps.put("music", new String[]{"YouTube Music", "com.google.android.apps.youtube.music"});
        assertEquals("map", BuddyActs.bestApp(apps, "네이버 지도"));
        assertEquals("map", BuddyActs.bestApp(apps, "네이버지도 앱"));
        assertEquals("yt", BuddyActs.bestApp(apps, "유튜브"));
        assertEquals("talk", BuddyActs.bestApp(apps, "카톡"));
        assertEquals("gmap", BuddyActs.bestApp(apps, "구글맵"));
        assertNull(BuddyActs.bestApp(apps, "당근마켓"));
    }

    /** 생각 깊이 (준: "낮음에 가장깊이생각이라고 되어있어"): shallow to deep after the default, "빨라요"/"깊게" by the level, not its place. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void ordersThinkingDepth() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().settings.edit().putString("agyEfforts", "[\"high\",\"xhigh\",\"medium\",\"low\"]").commit();
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        var depth = DecorActivity.class.getDeclaredMethod("brainDepth", String.class, String.class); depth.setAccessible(true);
        depth.invoke(a, dev.jun.gyeot.core.WorkBook.ANTIGRAVITY, "agy-test-model"); shadowOf(Looper.getMainLooper()).idle();
        View d = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        java.util.List<String> words = new java.util.ArrayList<>();
        java.util.function.Consumer<View> walk = new java.util.function.Consumer<>() { public void accept(View v) { if (v instanceof TextView t) words.add(t.getText().toString()); if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) accept(g.getChildAt(i)); } };
        walk.accept(d);
        int low = words.indexOf("Low · 낮음"), mid = words.indexOf("Medium · 중간"), high = words.indexOf("High · 높음"), xhigh = words.indexOf("Extra high · 매우 높음");
        assertTrue(words.toString(), low > 0 && low < mid && mid < high && high < xhigh);
        assertEquals("가장 빨라요", words.get(low + 1));
        assertEquals("가장 깊게 생각해요", words.get(xhigh + 1));
        shot(d, "buddy_brain_depth");
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
        // A friend's casual words (준: "존댓말 대신 친근한 어투로 하자").
        assertTrue(BuddyTalk.persona(a).contains("반말")); assertFalse(BuddyTalk.persona(a).contains("경쾌한 존댓말"));
    }

    /** What is private (준: "병원에 간대요 정도는 민감한 기억이 아닌 듯", "비번이나 사이트 권한요청 고민거리"). */
    @Test public void knowsWhatIsPrivate() {
        for (String open : new String[]{"사용자는 다음 주에 병원에 간대요", "사용자는 감기약을 먹고 있대요", "사용자는 여자친구와 영화를 봤대요", "사용자는 주민센터에 다녀왔대요"})
            assertFalse(open, dev.jun.gyeot.core.BuddyMind.secret(open));
        for (String secret : new String[]{"사용자는 은행 비밀번호를 자주 잊는대요", "사용자는 사이트 권한 요청이 너무 많아 귀찮대요", "사용자는 요즘 이직 고민이 많대요", "사용자는 새 계정 로그인이 안 된대요"})
            assertTrue(secret, dev.jun.gyeot.core.BuddyMind.secret(secret));
        assertFalse(dev.jun.gyeot.core.BuddyMind.asksPersonal("나 병원 가는 날이 언제였지"));
        assertTrue(dev.jun.gyeot.core.BuddyMind.asksPersonal("내 고민 기억나?"));
    }

    /** The same subject is not said again and again (준: "자꾸 전투기 배터리 이야기를 반복해서해"). */
    @Test public void movesOnFromASubject() {
        java.util.List<String> said = java.util.List.of("전투기처럼 배터리 충전 완료!", "오늘 하늘이 맑네", "배터리가 전투기 엔진처럼 쌩쌩해", "점심 뭐 먹었어?");
        assertTrue(dev.jun.gyeot.core.BuddyMind.staleSubject("전투기 타고 날아가고 싶다", said));
        assertTrue(dev.jun.gyeot.core.BuddyMind.staleSubject("배터리도 든든하다", said));
        assertFalse(dev.jun.gyeot.core.BuddyMind.staleSubject("노을이 예쁠 것 같아", said));
    }

    /** No cheering or "go rest" unasked, and not the same first word again (준: "자꾸 화이팅하고 쉬래", "반복적인 패턴이 있어"). */
    @Test public void doesNotNag() {
        for (String nag : new String[]{"오늘 하루 수고 많았어!", "저녁엔 푹 쉬어", "힘내자, 화이팅!", "무리하지 말고 쉬엄쉬엄 해", "잠깐 쉬어 가도 돼"})
            assertTrue(nag, dev.jun.gyeot.core.BuddyMind.nag(nag));
        for (String fine : new String[]{"붕어빵은 팥이야 슈크림이야?", "앱 마을에 새 길이 났대", "노을이 예쁠 것 같아"})
            assertFalse(fine, dev.jun.gyeot.core.BuddyMind.nag(fine));
        java.util.List<String> said = java.util.List.of("저녁이네, 뭐 먹었어?", "뿅! 오늘도 반가워", "노을 봤어?");
        assertTrue(dev.jun.gyeot.core.BuddyMind.sameOpening("저녁엔 산책이지", said));
        assertTrue(dev.jun.gyeot.core.BuddyMind.sameOpening("뿅! 나 왔어", said));
        assertFalse(dev.jun.gyeot.core.BuddyMind.sameOpening("붕어빵 좋아해?", said));
        String persona = dev.jun.gyeot.core.BuddyMind.persona("퍼레", "대장님");
        assertTrue(persona.contains("화이팅") && persona.contains("하지 마"));
        assertFalse(dev.jun.gyeot.core.BuddyMind.TOPICS.toString().contains("응원"));
    }

    /** Korean that reads (준: "가끔 한국어를 못알아듣게 말해"): a line cut at its start or in another script is not said. */
    @Test public void saysReadableKorean() {
        for (String bad : new String[]{"도 구름 낀 사천, 배터리도 살포시 충전 중이네요.", "는 오늘 기분이 좋아요", "今天 날씨 좋네요", "Battery is full today, yay"})
            assertFalse(bad, dev.jun.gyeot.core.BuddyMind.readable(bad));
        for (String good : new String[]{"오늘도 구름 낀 사천, 충전기 앞에서 냠냠 중이야.", "이 노래 좋다, 같이 들썩이자!", "와, 유튜브 영화관 거리 다녀왔어?", "YouTube 보고 왔구나, 재밌었어?"})
            assertTrue(good, dev.jun.gyeot.core.BuddyMind.readable(good));
    }

    /** 내 목소리 (준: "내 목소리를 알아볼 수 있게해줘"): three sentences to read, compact rows; a sentence cut from the microphone. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void enrollsTheVoice() throws Exception {
        shadowOf(org.robolectric.RuntimeEnvironment.getApplication()).grantPermissions(android.Manifest.permission.RECORD_AUDIO);
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        View decor = a.getWindow().getDecorView(); settle(decor);
        View row = text(decor, "내 목소리"); assertNotNull("the voice row", row);
        while (!row.isClickable() && row.getParent() instanceof View p) row = p;
        assertTrue(row.performClick()); shadowOf(Looper.getMainLooper()).idle();
        View d = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        for (String line : DecorActivity.VOICE_LINES) assertNotNull(line, text(d, line));
        assertNotNull("ready to read", text(d, "1번 읽기"));
        shot(d, "buddy_voice_enroll");
        // A sentence with quiet around it is cut where it ends; quiet alone is silent.
        java.util.Random r = new java.util.Random(3); short[] all = new short[16000 * 5];
        for (int i = 0; i < all.length; i++) all[i] = (short) (r.nextGaussian() * 40 + (i > 16000 && i < 16000 * 3 ? Math.sin(i * 2 * Math.PI * 140 / 16000) * 6000 * (1 + Math.sin(i * 2 * Math.PI * 4 / 16000)) : 0));
        dev.jun.gyeot.core.Utterance u = new dev.jun.gyeot.core.Utterance(7000);
        dev.jun.gyeot.core.Utterance.State st = u.add(all, all.length);
        assertEquals(dev.jun.gyeot.core.Utterance.State.DONE, st);
        assertTrue("about two seconds: " + u.pcm().length, u.pcm().length > 16000 * 2 && u.pcm().length < 16000 * 3);
        short[] quiet = new short[16000 * 8]; for (int i = 0; i < quiet.length; i++) quiet[i] = (short) (r.nextGaussian() * 40);
        assertEquals(dev.jun.gyeot.core.Utterance.State.SILENT, new dev.jun.gyeot.core.Utterance(7000).add(quiet, quiet.length));
        // A voice is closer to itself than to another, and the profile survives being saved.
        float[] low = dev.jun.gyeot.core.VoicePrint.mark(voice(110, 1, 3)), low2 = dev.jun.gyeot.core.VoicePrint.mark(voice(112, 2, 3)), low3 = dev.jun.gyeot.core.VoicePrint.mark(voice(108, 3, 3)), high = dev.jun.gyeot.core.VoicePrint.mark(voice(240, 4, 1));
        dev.jun.gyeot.core.VoicePrint.Profile p = dev.jun.gyeot.core.VoicePrint.Profile.empty().enroll(low).enroll(low2).enroll(low3);
        dev.jun.gyeot.core.VoicePrint.Profile back = dev.jun.gyeot.core.VoicePrint.Profile.read(p.json());
        assertTrue(back.ready());
        assertTrue(dev.jun.gyeot.core.VoicePrint.score(back, dev.jun.gyeot.core.VoicePrint.mark(voice(111, 5, 3))) > dev.jun.gyeot.core.VoicePrint.score(back, high));
    }
    /** A buzzy voice for tests: a pitch with its harmonics shaped by a formant-like tilt, over a little noise. */
    private static short[] voice(double f0, long seed, int tilt) {
        java.util.Random r = new java.util.Random(seed); short[] out = new short[16000 * 3];
        for (int i = 0; i < out.length; i++) {
            double t = i / 16000.0, f = f0 * (1 + 0.04 * Math.sin(2 * Math.PI * 3 * t)), v = 0;
            for (int h = 1; h * f0 < 4000; h++) v += Math.sin(2 * Math.PI * f * h * t) / Math.pow(h, tilt * 0.5 + 0.5);
            out[i] = (short) (v * 5000 * (0.6 + 0.4 * Math.sin(2 * Math.PI * 2.5 * t)) + r.nextGaussian() * 30);
        }
        return out;
    }

    /** 퍼레의 기억 (opened after the fingerprint): 오늘, 발견 노트, 약속, 추억; private ones marked 🔒. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void showsTheMemoryBook() throws Exception {
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        long now = System.currentTimeMillis();
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> list = new java.util.ArrayList<>();
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 86_400_000L * 3, "fact", "사용자는 고양이를 좋아한대요", 2, false));
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 86_400_000L, "fact", "사용자는 요즘 이직 고민이 많대요", 2, true));
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 3_600_000L, "promise", "퍼레가 내일 아침 날씨를 알려 주기로 했어요", 2, false));
        list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(now - 7_200_000L, "profile", "평일 아침 8시쯤 지도 앱을 열어요", 3, false));
        BuddyTalk.today(a, "app", "지도·이동");
        var book = DecorActivity.class.getDeclaredMethod("memorySheet", java.util.List.class); book.setAccessible(true);
        book.invoke(a, list); shadowOf(Looper.getMainLooper()).idle();
        View d = ShadowDialog.getLatestDialog().getWindow().getDecorView();
        for (String part : new String[]{"오늘", "발견 노트 · 나에 대해", "우리 약속", "추억"}) assertNotNull(part, text(d, part));
        assertNotNull("a private memory marked", text(d, "🔒 사용자는 요즘 이직 고민이"));
        shot(d, "buddy_memory_book");
    }
}
