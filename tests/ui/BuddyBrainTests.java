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

    /** A voice that ends going up is a question (준: "음악소리들려? 라고 말하면 음악소리들려로 알아들어"); one going down is not. */
    @Test public void hearsAQuestionInTheVoice() {
        assertTrue("rising", dev.jun.gyeot.core.Intonation.rising(glide(150, 150, 230)));
        assertFalse("falling", dev.jun.gyeot.core.Intonation.rising(glide(170, 170, 120)));
        assertFalse("flat", dev.jun.gyeot.core.Intonation.rising(glide(160, 160, 160)));
        assertTrue(BuddyTalk.ACTS.length() > 0);
    }
    /**
     * 준: "1번 진행" — what 퍼레 is asked, run through its acts on a phone with Naver Map and YouTube: what opens, and what it
     * could not do kept with the tap that clears the way.
     */
    @Test public void actsOnWhatWasAsked() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        android.content.ComponentName nmap = new android.content.ComponentName("com.nhn.android.nmap", "com.nhn.android.nmap.Main");
        pm.addActivityIfNotPresent(nmap);
        android.content.IntentFilter view = new android.content.IntentFilter(android.content.Intent.ACTION_VIEW); view.addCategory(android.content.Intent.CATEGORY_DEFAULT); view.addDataScheme("nmap");
        pm.addIntentFilterForActivity(nmap, view);
        android.content.ComponentName youtube = new android.content.ComponentName("com.google.android.youtube", "com.google.android.youtube.Home");
        pm.addActivityIfNotPresent(youtube);
        android.content.IntentFilter search = new android.content.IntentFilter(android.content.Intent.ACTION_SEARCH); search.addCategory(android.content.Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(youtube, search);
        app.store().savedPlaces(java.util.List.of(new dev.jun.gyeot.core.SavedPlaces.Saved(java.util.List.of("집", "우리집"), "래미안", "부산 수영구", 35.15, 129.11)));
        BuddyMisses.clear(app);
        org.robolectric.shadows.ShadowApplication shadow = shadowOf(app);

        // "집으로 가자": Naver Map's navigation to the saved place, at once.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"navigate\",\"destination\":\"집\"}"), "집으로 가자", false);
        android.content.Intent home = shadow.getNextStartedActivity();
        assertNotNull("navigation opened", home);
        assertTrue(String.valueOf(home.getData()), String.valueOf(home.getData()).startsWith("nmap://") && String.valueOf(home.getData()).contains("35.15"));

        // "유튜브에서 아이유 신곡 틀어 줘": YouTube's own search for it.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"play\",\"query\":\"아이유 신곡\",\"app\":\"유튜브\"}"), "유튜브에서 아이유 신곡 틀어 줘", false);
        android.content.Intent played = shadow.getNextStartedActivity();
        assertNotNull("YouTube opened", played);
        assertEquals("com.google.android.youtube", played.getPackage()); assertEquals("아이유 신곡", played.getStringExtra(android.app.SearchManager.QUERY));

        // "회사로 가자" before 회사 is saved: not a map search for the word, the place to save it.
        BuddyVoice.Outcome work = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"navigate\",\"destination\":\"회사\"}"), "회사로 가자", false);
        android.content.Intent save = shadow.getNextStartedActivity();
        assertNotNull(save); assertEquals(NavigationActivity.class.getName(), save.getComponent().getClassName()); assertEquals("회사", save.getStringExtra(NavigationActivity.ADD));
        assertTrue(work.say(), work.say().contains("회사"));

        // An app that is not here, and a thing it cannot do: kept, with the tap that clears the way where there is one.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"open_app\",\"app\":\"없는앱\"}"), "없는앱 열어 줘", false);
        BuddyVoice.Outcome pay = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"pay\",\"who\":\"엄마\"}"), "엄마한테 만 원 보내 줘", false);
        assertEquals("그건 아직 내가 못 해.", pay.say());
        java.util.List<BuddyMisses.Miss> misses = BuddyMisses.list(app);
        assertEquals(3, misses.size());
        assertEquals("엄마한테 만 원 보내 줘", misses.get(0).said());
        assertEquals("store:없는앱", misses.get(1).fix()); assertEquals("앱 찾기", BuddyMisses.fixLabel(misses.get(1).fix()));
        assertEquals("place:회사", misses.get(2).fix()); assertNotNull(BuddyMisses.fixIntent(app, misses.get(2).fix()));
    }
    /** 준: "4번" — apps their own way: KakaoTalk's share, a text, the dialer (never a call by itself), the camera, a new event, a page. */
    @Test public void usesAppsTheirOwnWay() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        java.util.function.BiConsumer<String, android.content.IntentFilter> handles = (component, filter) -> {
            android.content.ComponentName c = android.content.ComponentName.unflattenFromString(component); pm.addActivityIfNotPresent(c);
            filter.addCategory(android.content.Intent.CATEGORY_DEFAULT); pm.addIntentFilterForActivity(c, filter);
        };
        android.content.IntentFilter send = new android.content.IntentFilter(android.content.Intent.ACTION_SEND); send.addDataType("text/plain");
        handles.accept("com.kakao.talk/.Share", send);
        android.content.IntentFilter sms = new android.content.IntentFilter(android.content.Intent.ACTION_SENDTO); sms.addDataScheme("smsto");
        handles.accept("com.sms/.Compose", sms);
        android.content.IntentFilter dial = new android.content.IntentFilter(android.content.Intent.ACTION_DIAL); dial.addDataScheme("tel");
        handles.accept("com.dialer/.Dial", dial);
        handles.accept("com.camera/.Shoot", new android.content.IntentFilter(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA));
        android.content.IntentFilter insert = new android.content.IntentFilter(android.content.Intent.ACTION_INSERT); insert.addDataType("vnd.android.cursor.dir/event");
        handles.accept("com.calendar/.Edit", insert);
        org.robolectric.shadows.ShadowApplication shadow = shadowOf(app);
        BuddyMisses.clear(app);

        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"share\",\"text\":\"내일 7시에 만나\",\"app\":\"카톡\"}"), "카톡으로 내일 7시에 만나자고 보내 줘", false);
        android.content.Intent shared = shadow.getNextStartedActivity();
        assertEquals("com.kakao.talk", shared.getPackage()); assertEquals("내일 7시에 만나", shared.getStringExtra(android.content.Intent.EXTRA_TEXT));

        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"message\",\"number\":\"010-1234-5678\",\"text\":\"곧 도착\"}"), "문자 보내 줘", false);
        android.content.Intent text = shadow.getNextStartedActivity();
        assertEquals("smsto:01012345678", String.valueOf(text.getData())); assertEquals("곧 도착", text.getStringExtra("sms_body"));

        // No contacts permission: the dialer opens empty, and the way to allow it is kept.
        BuddyVoice.Outcome mom = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"call\",\"who\":\"엄마\"}"), "엄마한테 전화해 줘", false);
        android.content.Intent dialed = shadow.getNextStartedActivity();
        assertEquals(android.content.Intent.ACTION_DIAL, dialed.getAction()); assertTrue(mom.say(), mom.say().contains("연락처"));
        assertEquals("contacts", BuddyMisses.list(app).get(0).fix()); assertEquals("연락처 허용", BuddyMisses.fixLabel("contacts"));
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"call\",\"number\":\"01012345678\"}"), "전화 걸어 줘", false);
        assertEquals("tel:01012345678", String.valueOf(shadow.getNextStartedActivity().getData()));

        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"camera\",\"mode\":\"selfie\"}"), "셀카 찍자", false);
        android.content.Intent cam = shadow.getNextStartedActivity();
        assertEquals(android.provider.MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA, cam.getAction()); assertTrue(cam.getBooleanExtra("android.intent.extra.USE_FRONT_CAMERA", false));

        BuddyVoice.Outcome event = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"calendar_add\",\"title\":\"치과\",\"date\":\"내일\",\"hour\":15,\"minute\":30}"), "내일 세 시 반 치과 일정 넣어 줘", false);
        android.content.Intent added = shadow.getNextStartedActivity();
        assertEquals("치과", added.getStringExtra(android.provider.CalendarContract.Events.TITLE));
        java.time.ZonedDateTime begin = java.time.Instant.ofEpochMilli(added.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0)).atZone(java.time.ZoneId.systemDefault());
        assertEquals(java.time.LocalDate.now().plusDays(1), begin.toLocalDate()); assertEquals(15, begin.getHour()); assertEquals(30, begin.getMinute());
        assertTrue(event.say(), event.say().contains("저장"));
        java.time.LocalDate tomorrow = java.time.LocalDate.now().plusDays(1);
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"calendar_add\",\"title\":\"치과\",\"date\":\"" + tomorrow.withYear(tomorrow.getYear() - 2) + "\",\"hour\":15}"), "내일 치과", false);
        assertEquals("a guessed year", tomorrow, java.time.Instant.ofEpochMilli(shadow.getNextStartedActivity().getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0)).atZone(java.time.ZoneId.systemDefault()).toLocalDate());
        assertTrue(BuddyTools.promised("내일 3시에 치과 일정 넣어줘", "캘린더에 넣어둘게")); assertTrue(BuddyTools.promised("셀카 찍을래", "셀카 모드로 열게"));

        // A page: https only.
        assertEquals("그 주소는 열 수가 없어.", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"open_url\",\"url\":\"javascript:alert(1)\"}"), "열어 줘", false).say());
        assertEquals("call", BuddyTalk.loose("{\"tool\":\"dial\",\"who\":\"아빠\"}").optString("tool"));
        assertEquals("calendar_add", BuddyTalk.loose("{\"tool\":\"add_event\",\"title\":\"회의\"}").optString("tool"));
    }
    @Test public void doesThingsWithToolsAndSpeaksAsItWrites() throws Exception {
        // 준: "2번" — a tool the brain calls is the act, with a short line when it said none.
        org.json.JSONObject a = BuddyTools.act(new org.json.JSONArray().put(new org.json.JSONObject().put("name", "open_app").put("arguments", "{\"app\":\"YouTube\"}")));
        assertEquals("open_app", a.optString("tool")); assertEquals("YouTube", a.optString("app")); assertEquals("YouTube 열게!", BuddyTools.say(a));
        org.json.JSONObject home = BuddyTools.act(new org.json.JSONArray().put(new org.json.JSONObject().put("name", "navigate").put("arguments", "{\"destination\":\"집\",\"mode\":\"walk\"}")));
        assertEquals("navigate", home.optString("tool")); assertEquals("walk", home.optString("mode")); assertEquals("집으로 안내할게!", BuddyTools.say(home));
        assertEquals("show_alarms", BuddyTools.act(new org.json.JSONArray().put(new org.json.JSONObject().put("name", "show_alarms").put("arguments", "{}"))).optString("tool"));
        assertNull(BuddyTools.act(new org.json.JSONArray()));
        assertEquals(20, BuddyTools.TOOLS.length());
        assertTrue(BuddyTools.promised("3분 타이머 맞춰 줘", "3분 타이머 시작할게!"));
        assertFalse("small talk is never forced into a tool", BuddyTools.promised("오늘 기분 어때", "나 오늘 완전 좋아!"));
        assertFalse(BuddyTools.promised("유튜브 틀어 줘", "어떤 노래가 좋아?"));
        for (int i = 0; i < BuddyTools.TOOLS.length(); i++) assertEquals("function", BuddyTools.TOOLS.getJSONObject(i).getString("type"));
        // 준: "3번" — each sentence said as soon as it is written; a word or two waits for the next; no act text, digits kept.
        java.util.List<String> said = new java.util.ArrayList<>();
        BuddyTalk.Sentences s = new BuddyTalk.Sentences(said::add);
        for (String piece : new String[]{"응! 지금", " 바로 틀어 줄게. 아이유", " 신곡 좋더라~ 7시에", " 또 듣자\n할일: {\"tool\":\"play\"}"}) s.add(piece);
        s.end();
        assertEquals(java.util.List.of("응! 지금 바로 틀어 줄게.", "아이유 신곡 좋더라~", "7시에 또 듣자"), said);
        // Talking over 퍼레: its own echo and a short "응" do not stop it; half a second of the user's voice does.
        dev.jun.gyeot.core.TalkOver over = new dev.jun.gyeot.core.TalkOver(); short[] f = new short[dev.jun.gyeot.core.TalkOver.FRAME];
        java.util.Arrays.fill(f, (short) 600); for (int i = 0; i < 50; i++) assertFalse("echo", over.add(f, f.length));
        java.util.Arrays.fill(f, (short) 5000); for (int i = 0; i < 10; i++) assertFalse("응", over.add(f, f.length));
        java.util.Arrays.fill(f, (short) 600); for (int i = 0; i < 10; i++) assertFalse(over.add(f, f.length));
        java.util.Arrays.fill(f, (short) 5000); boolean stopped = false; for (int i = 0; i < 30 && !stopped; i++) stopped = over.add(f, f.length);
        assertTrue("talked over", stopped);
    }
    @Test public void hearsItsName() {
        // 준: "퍼레가 자기 이름을 못알아듣고 자꾸 벌레 버려 이런걸로 알아들어"
        assertEquals("퍼레야", dev.jun.gyeot.core.BuddyMind.nameHeard("벌레야"));
        assertEquals("퍼레야", dev.jun.gyeot.core.BuddyMind.nameHeard("버려!"));
        assertEquals("퍼레야, 지금 몇 시야", dev.jun.gyeot.core.BuddyMind.nameHeard("버려 지금 몇 시야"));
        assertEquals("퍼레야, 음악 틀어 줘", dev.jun.gyeot.core.BuddyMind.nameHeard("거래야 음악 틀어 줘"));
        assertEquals("오늘 퍼레야 뭐 해", dev.jun.gyeot.core.BuddyMind.nameHeard("오늘 벌레야 뭐 해"));
        assertEquals("벌레가 나왔어", dev.jun.gyeot.core.BuddyMind.nameHeard("벌레가 나왔어"));
        assertEquals("이거 버려야 해?", dev.jun.gyeot.core.BuddyMind.nameHeard("이거 버려야 해?"));
        assertEquals("거래 내역 보여 줘", dev.jun.gyeot.core.BuddyMind.nameHeard("거래 내역 보여 줘"));
        assertEquals("퍼레야, 안녕", BuddyTalk.heard(org.robolectric.RuntimeEnvironment.getApplication(), "펄레야 안녕"));
    }
    /** 1.6 s of a voiced sound at one pitch, then 0.4 s gliding from b to c (Hz), with a little noise. */
    private static short[] glide(double a, double b, double c) {
        java.util.Random r = new java.util.Random(5); int n = 16000 * 2; short[] out = new short[n]; double phase = 0;
        for (int i = 0; i < n; i++) {
            double t = i / 16000.0, f = t < 1.6 ? a + (b - a) * t / 1.6 : b + (c - b) * (t - 1.6) / 0.4;
            phase += 2 * Math.PI * f / 16000; double v = 0;
            for (int h = 1; h <= 6; h++) v += Math.sin(phase * h) / h;
            out[i] = (short) (v * 6000 + r.nextGaussian() * 40);
        }
        return out;
    }

    /** 퍼레 knows the saved places (준: "우리앱에 네비게이션 설정해놓은것을 퍼레가 모르고 있어"): their words in its brief, "집" goes there. */
    @Test public void knowsTheSavedPlaces() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().savedPlaces(java.util.List.of(new dev.jun.gyeot.core.SavedPlaces.Saved(java.util.List.of("집", "우리집"), "사천 우리집", "경남 사천시 용현면", 35.0, 128.0, "car", java.util.List.of())));
        String brief = BuddyTalk.places(app.store());
        assertTrue(brief, brief.contains("집/우리집") && brief.contains("사천 우리집"));
        // The saved place is used as it is: no search, the maps app asked for (none installed in the test, so it says so).
        BuddyVoice.Outcome o = BuddyActs.run(app, new org.json.JSONObject("{\"tool\":\"navigate\",\"destination\":\"집\"}"), "", false);
        assertNotNull(o); assertTrue(o.say(), o.say().contains("지도 앱"));
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
        // 준: "홈화면에서는 15초 다른 앱 위에서는 3분으로 하자"
        assertEquals(15_000L, BuddyVoice.QUIET_HOME); assertEquals(180_000L, BuddyVoice.QUIET_OVER);
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

    /** 퍼레's short words in its own voice (준: "프리셋 보이스 100개", "타입캐스트로"): a hundred, kept by voice, no repeat of the last few. */
    @Test public void keepsItsShortWords() throws Exception {
        assertEquals(100, VoicePresets.LINES.length);
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().settings.edit().putString("typecastVoice", "{\"id\":\"tc_test\"}").commit();
        assertNull("none made yet", VoicePresets.pick(app, app.store(), "think"));
        java.io.File dir = VoicePresets.dir(app, "tc_test"); dir.mkdirs();
        for (int i = 0; i < VoicePresets.LINES.length; i++) java.nio.file.Files.write(new java.io.File(dir, i + ".mp3").toPath(), new byte[]{1});
        assertArrayEquals(new int[]{100, 100}, VoicePresets.ready(app, app.store()));
        java.util.List<String> heard = new java.util.ArrayList<>();
        for (int k = 0; k < 6; k++) {
            java.io.File f = VoicePresets.pick(app, app.store(), "think"); assertNotNull(f);
            String w = VoicePresets.words(f); assertFalse("no repeat: " + heard + " " + w, heard.contains(w)); heard.add(w);
        }
        for (int k = 0; k < 4; k++) { String w = VoicePresets.words(VoicePresets.pick(app, app.store(), "bye")); assertTrue(w, java.util.Arrays.stream(VoicePresets.LINES).anyMatch(l -> l[0].equals("bye") && l[1].equals(w))); }
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
        // 준: 퍼레가 "{tool:openapp,app:YouTube}"를 그대로 외쳤다: a bare {…}, quotes or not, its tool's own name, never said.
        String loose = "유튜브 열고 최신곡 틀게!\n{tool:openapp,app:YouTube}";
        org.json.JSONObject open = BuddyTalk.act(loose);
        assertNotNull(open); assertEquals("open_app", open.optString("tool")); assertEquals("YouTube", open.optString("app"));
        assertEquals("유튜브 열고 최신곡 틀게!", BuddyTalk.spoken(loose));
        org.json.JSONObject play = BuddyTalk.act("틀어 줄게 {\"tool\":\"play_music\",\"song\":\"아이유 신곡\",\"app\":\"유튜브\"}");
        assertEquals("play", play.optString("tool")); assertEquals("아이유 신곡", play.optString("query"));
        org.json.JSONObject home = BuddyTalk.act("집으로 갈게!\n할 일: {tool: navigation, destination: 우리 집}");
        assertEquals("navigate", home.optString("tool")); assertEquals("우리 집", home.optString("destination"));
        assertNull(BuddyTalk.act("{t} 안녕"));
        // 집으로 가자: the saved place, said as 집, 우리 집, or copied with its name.
        java.util.List<dev.jun.gyeot.core.SavedPlaces.Saved> saved = java.util.List.of(new dev.jun.gyeot.core.SavedPlaces.Saved(java.util.List.of("우리집"), "래미안", "부산", 35.1, 129.1),
            new dev.jun.gyeot.core.SavedPlaces.Saved(java.util.List.of("엄마집"), "엄마", "부산", 35.2, 129.2));
        assertEquals("래미안", dev.jun.gyeot.core.SavedPlaces.match("집", saved).name());
        assertEquals("래미안", dev.jun.gyeot.core.SavedPlaces.match("우리 집으로", saved).name());
        assertEquals("래미안", dev.jun.gyeot.core.SavedPlaces.match("우리집(래미안)", saved).name());
        assertEquals("엄마", dev.jun.gyeot.core.SavedPlaces.match("엄마집", saved).name());
        assertNull(dev.jun.gyeot.core.SavedPlaces.match("회사", saved));
        assertTrue(dev.jun.gyeot.core.SavedPlaces.own("회사로")); assertFalse(dev.jun.gyeot.core.SavedPlaces.own("해운대"));
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
