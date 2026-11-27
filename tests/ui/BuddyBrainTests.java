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
        assertEquals(45, BuddyTools.TOOLS.length());
        // Every tool once, with its Korean description (준: "시킬 수 있는 목록 … 아직 부족").
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < BuddyTools.TOOLS.length(); i++) {
            org.json.JSONObject f = BuddyTools.TOOLS.getJSONObject(i).getJSONObject("function");
            assertTrue("once: " + f.getString("name"), names.add(f.getString("name")));
            assertTrue(f.getString("name"), f.getString("description").codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3));
        }
        for (String t : new String[]{"stopwatch", "show_timers", "calendar_view", "memo", "recorder", "app_settings", "downloads", "gallery", "contacts", "calculator", "email", "translate",
                "coin", "dice", "pick", "world_time", "count_days", "remember", "forget", "call_me", "quiet"}) {
            assertTrue("a tool " + t, names.contains(t));
            assertTrue("in the text form " + t, BuddyTalk.ACTS.contains("\"tool\":\"" + t + "\""));
        }
        assertTrue(BuddyTools.RULE.contains("지어내지"));
        assertTrue(BuddyTools.promised("동전 던져 줘", "좋아, 던져 볼게!")); assertTrue(BuddyTools.promised("나 내일 시험이야, 기억해 줘", "응, 기억할게!"));
        assertTrue(BuddyTools.promised("이제 형이라고 불러", "알겠어, 형이라고 부를게!")); assertTrue(BuddyTools.promised("한 시간만 조용히 해 줘", "응, 조용히 있을게."));
        assertFalse("a song is not a title", BuddyTools.promised("노래 불러 줘", "라라라~ 한 곡 부를게!"));
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
    /**
     * 퍼레's own answers that need no app (준: "시킬 수 있는 목록 … 아직 부족"): a coin, dice, a pick, a sum, the time in another
     * city, the days to a day, a stopwatch's reading, worked out here rather than made up by the brain.
     */
    @Test public void worksThingsOutItself() throws Exception {
        java.util.Random r = new java.util.Random(7);
        assertTrue(dev.jun.gyeot.core.BuddyTricks.coinSay(true, r).contains("앞면")); assertTrue(dev.jun.gyeot.core.BuddyTricks.coinSay(false, r).contains("뒷면"));
        int[] two = dev.jun.gyeot.core.BuddyTricks.dice(2, 6, r); assertEquals(2, two.length);
        for (int d : two) assertTrue(d >= 1 && d <= 6);
        assertEquals("3이랑 5, 합쳐서 8!", dev.jun.gyeot.core.BuddyTricks.diceSay(new int[]{3, 5}));
        assertEquals(java.util.List.of("짜장면", "짬뽕", "볶음밥"), dev.jun.gyeot.core.BuddyTricks.options("짜장면, 짬뽕 아니면 볶음밥"));
        assertEquals(java.util.List.of("a", "b"), dev.jun.gyeot.core.BuddyTricks.options(new org.json.JSONArray().put("a").put("a").put("b")));
        // Sums as said or written.
        assertEquals(84.0, dev.jun.gyeot.core.BuddyTricks.calc("12*7"), 1e-9);
        assertEquals(84.0, dev.jun.gyeot.core.BuddyTricks.calc("12 곱하기 7"), 1e-9);
        assertEquals(5250.0, dev.jun.gyeot.core.BuddyTricks.calc("35000*0.15"), 1e-9);
        assertEquals(105000.0, dev.jun.gyeot.core.BuddyTricks.calc("3만5천*3"), 1e-9);
        assertEquals(7500.0, dev.jun.gyeot.core.BuddyTricks.calc("50000의 15%"), 1e-9);
        assertEquals(30000.0, dev.jun.gyeot.core.BuddyTricks.calc("100만원의 3%"), 1e-9);
        assertEquals(49.0, dev.jun.gyeot.core.BuddyTricks.calc("(3+4)^2"), 1e-9);
        assertEquals(6666.666666, dev.jun.gyeot.core.BuddyTricks.calc("(12,000+8,000)/3"), 1e-5);
        assertNull(dev.jun.gyeot.core.BuddyTricks.calc("안녕")); assertNull(dev.jun.gyeot.core.BuddyTricks.calc("3+"));
        try { dev.jun.gyeot.core.BuddyTricks.calc("5/0"); fail("by zero"); } catch (ArithmeticException expected) { /* said as such */ }
        assertEquals("1,234,567", dev.jun.gyeot.core.BuddyTricks.number(1234567)); assertEquals("0.3333", dev.jun.gyeot.core.BuddyTricks.number(1 / 3.0)); assertEquals("6,666.6667", dev.jun.gyeot.core.BuddyTricks.number(20000 / 3.0));
        // The time elsewhere, from Seoul at 9 in the morning.
        java.time.ZonedDateTime seoul = java.time.ZonedDateTime.of(2026, 11, 27, 9, 0, 0, 0, java.time.ZoneId.of("Asia/Seoul"));
        String[] ny = dev.jun.gyeot.core.BuddyTricks.place("뉴욕은", null);
        assertEquals("America/New_York", ny[0]);
        assertEquals("뉴욕은 지금 어제 오후 7:00야. 여기보다 14시간 느려.", dev.jun.gyeot.core.BuddyTricks.worldTime(ny[1], java.time.ZoneId.of(ny[0]), seoul));
        assertEquals("인도는 지금 오전 5:30야. 여기보다 3시간 30분 느려.", dev.jun.gyeot.core.BuddyTricks.worldTime("인도", java.time.ZoneId.of(dev.jun.gyeot.core.BuddyTricks.place("인도", "")[0]), seoul));
        assertEquals("America/Los_Angeles", dev.jun.gyeot.core.BuddyTricks.place("LA 지금 몇 시", null)[0]);
        assertEquals("America/Chicago", dev.jun.gyeot.core.BuddyTricks.place("dallas", null)[0]);
        assertEquals("Europe/Paris", dev.jun.gyeot.core.BuddyTricks.place("", "Europe/Paris")[0]);
        assertNull(dev.jun.gyeot.core.BuddyTricks.place("어딘가", null));
        // Days to a day, since one, and so many days on.
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 27);
        assertEquals(java.time.LocalDate.of(2026, 12, 25), dev.jun.gyeot.core.BuddyTricks.day("크리스마스", today));
        assertEquals(java.time.LocalDate.of(2027, 3, 1), dev.jun.gyeot.core.BuddyTricks.day("3월 1일", today));
        assertEquals(java.time.LocalDate.of(2026, 8, 19), dev.jun.gyeot.core.BuddyTricks.day("2026-08-19", today));
        assertEquals(today.plusDays(2), dev.jun.gyeot.core.BuddyTricks.day("모레", today));
        assertNull(dev.jun.gyeot.core.BuddyTricks.day("언젠가", today));
        assertEquals("크리스마스까지 28일 남았어!", dev.jun.gyeot.core.BuddyTricks.countDays(java.time.LocalDate.of(2026, 12, 25), "크리스마스", today));
        assertEquals("만난 날부터 오늘까지 100일 지났어. 그날을 1일로 세면 101일째야.", dev.jun.gyeot.core.BuddyTricks.countDays(java.time.LocalDate.of(2026, 8, 19), "만난 날", today));
        assertEquals("여행은 바로 오늘이야!", dev.jun.gyeot.core.BuddyTricks.countDays(today, "여행", today));
        String later = dev.jun.gyeot.core.BuddyTricks.addDays(today, 100, today);
        assertTrue(later, later.startsWith("오늘부터 100일 뒤는 2027년 3월 7일 ") && later.endsWith("요일이야."));
        // A stopwatch's reading, a title, a language, which memories a "잊어 줘" means.
        assertEquals("3.2초", dev.jun.gyeot.core.BuddyTricks.elapsed(3_200)); assertEquals("1분 5초", dev.jun.gyeot.core.BuddyTricks.elapsed(65_000)); assertEquals("1시간", dev.jun.gyeot.core.BuddyTricks.elapsed(3_600_000));
        assertEquals("주인공님", dev.jun.gyeot.core.BuddyTricks.title("주인공님이라고 불러 줘")); assertEquals("형", dev.jun.gyeot.core.BuddyTricks.title("나를 형이라고 불러"));
        assertEquals("", dev.jun.gyeot.core.BuddyTricks.title("아주아주아주아주긴호칭이에요정말로"));
        assertEquals("이제 ‘주인공님’이라고 부를게!", BuddyTools.called("주인공님")); assertEquals("이제 ‘대장’이라고 부를게!", BuddyTools.called("대장")); assertEquals("이제 ‘형아’라고 부를게!", BuddyTools.called("형아"));
        assertEquals("en", dev.jun.gyeot.core.BuddyTricks.language("", "안녕하세요")); assertEquals("ko", dev.jun.gyeot.core.BuddyTricks.language(null, "hello"));
        assertEquals("ja", dev.jun.gyeot.core.BuddyTricks.language("일본어로", "안녕")); assertEquals("zh-CN", dev.jun.gyeot.core.BuddyTricks.language("zh-CN", "안녕"));
        java.util.List<String> kept = java.util.List.of("사용자는 고양이를 좋아한대요", "사용자는 커피를 좋아한대", "사용자가 기억해 달랬어: 차는 지하 2층에 세웠어");
        assertEquals(java.util.List.of(0), dev.jun.gyeot.core.BuddyTricks.matching(kept, "고양이"));
        assertEquals(java.util.List.of(0), dev.jun.gyeot.core.BuddyTricks.matching(kept, "고양이 좋아한다는 거"));
        assertEquals(java.util.List.of(2), dev.jun.gyeot.core.BuddyTricks.matching(kept, "지하 2층"));
        assertEquals(java.util.List.of(), dev.jun.gyeot.core.BuddyTricks.matching(kept, "강아지"));

        // Through BuddyActs, as the brain calls them; their words are the answer.
        android.content.Context c = org.robolectric.RuntimeEnvironment.getApplication();
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"coin\"}"), "동전 던져 줘", false).say().matches(".*(앞면|뒷면).*"));
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"dice\",\"count\":2}"), "주사위 두 개", false).say().contains("합쳐서"));
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"pick\",\"options\":[\"짜장면\",\"짬뽕\"]}"), "골라 줘", false).say().matches(".*(짜장면|짬뽕).*"));
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"calc\",\"expr\":\"35000*0.15\"}"), "35000원의 15퍼센트", false).say().contains("5,250"));
        assertEquals("0으로는 나눌 수가 없어!", BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"calculator\",\"expression\":\"5/0\"}"), "", false).say());
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"world_time\",\"place\":\"뉴욕\"}"), "뉴욕 몇 시야", false).say().startsWith("뉴욕은 지금 "));
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"count_days\",\"date\":\"크리스마스\"}"), "크리스마스 며칠 남았어", false).say().startsWith("크리스마스"));
        assertEquals("", BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"stopwatch\",\"command\":\"start\"}"), "스톱워치 켜 줘", false).say());
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"stopwatch\",\"command\":\"check\"}"), "몇 초야", false).say().endsWith("째야!"));
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"stopwatch\",\"command\":\"stop\"}"), "멈춰", false).say().startsWith("멈췄어, "));
        assertTrue(BuddyActs.run(c, BuddyTalk.loose("{\"tool\":\"stopwatch\",\"command\":\"stop\"}"), "멈춰", false).say().contains("없어"));
        // The text form, as the quick brains write it.
        assertEquals("coin", BuddyTalk.act("던져 볼게!\n할일: {\"tool\":\"flip_coin\"}").optString("tool"));
        org.json.JSONObject sum = BuddyTalk.act("계산해 볼게 {tool: calc, expr: 12*7}");
        assertEquals("calculator", sum.optString("tool")); assertEquals("12*7", sum.optString("expression"));
        assertEquals(java.util.List.of("a", "b"), dev.jun.gyeot.core.BuddyTricks.options(BuddyTalk.loose("{\"tool\":\"choose\",\"choices\":[\"a\",\"b\"]}").opt("options")));
        assertEquals("world_time", BuddyTalk.loose("{\"tool\":\"timezone\",\"city\":\"파리\"}").optString("tool")); assertEquals("파리", BuddyTalk.loose("{\"tool\":\"timezone\",\"city\":\"파리\"}").optString("place"));
        // Its short lines when the brain said none.
        assertEquals("동전 던진다!", BuddyTools.say(new org.json.JSONObject().put("tool", "coin")));
        assertEquals("", BuddyTools.say(new org.json.JSONObject().put("tool", "world_time").put("place", "뉴욕")));
        assertEquals("이제 ‘주인공님’이라고 부를게!", BuddyTools.say(new org.json.JSONObject().put("tool", "call_me").put("title", "주인공님이라고")));
        assertEquals("다시 말 걸게!", BuddyTools.say(new org.json.JSONObject().put("tool", "quiet").put("hours", 0)));
    }
    /** What the user asks of 퍼레 itself: keep this in mind, forget that, call me so, keep quiet a while. */
    @Test public void keepsAndLetsGoWhenAsked() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        BuddyMisses.clear(app);
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"remember\",\"fact\":\"차는 지하 2층 B구역에 세웠어\"}"), "차 지하 2층 B구역에 세웠어 기억해 줘", false);
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> m = BuddyTalk.memories(app);
        assertEquals(1, m.size()); assertEquals("fact", m.get(0).kind()); assertTrue(m.get(0).text(), m.get(0).text().contains("지하 2층"));
        assertEquals("그건 벌써 기억하고 있어!", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"remember\",\"fact\":\"차는 지하 2층 B구역에 세웠어\"}"), "", false).say());
        assertTrue(BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"remember\",\"fact\":\"은행 비밀번호는 0000이야\"}"), "", false).say().contains("안 적어"));
        assertEquals(1, BuddyTalk.memories(app).size());
        BuddyTalk.rememberStrong(app, "fact", "사용자는 고양이를 좋아한대요", false);
        // Forgotten by its words; all at once only in 퍼레의 기억, the way there kept.
        assertEquals("‘고양이’ 기억 지웠어.", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"what\":\"고양이\"}"), "고양이 얘기 잊어 줘", false).say());
        assertEquals(1, BuddyTalk.memories(app).size());
        assertTrue(BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"what\":\"강아지\"}"), "", false).say().contains("원래 없었어"));
        BuddyVoice.Outcome all = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"what\":\"전부\"}"), "다 잊어버려", false);
        assertTrue(all.say(), all.say().contains("퍼레의 기억")); assertEquals(1, BuddyTalk.memories(app).size());
        assertEquals("memory", BuddyMisses.list(app).get(0).fix()); assertEquals("퍼레의 기억 열기", BuddyMisses.fixLabel("memory"));
        assertTrue(BuddyMisses.fixIntent(app, "memory").getBooleanExtra(DecorActivity.MEMORY, false));
        // What it calls the user.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"callme\",\"name\":\"주인공님이라고 불러 줘\"}"), "주인공님이라고 불러 줘", false);
        assertEquals("주인공님", BuddyTalk.title(app));
        // A quiet while: the board starts no talk (env.hush), the brain is told; 0 talks again.
        String quiet = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"quiet\",\"hours\":2}"), "두 시간만 조용히 해 줘", false).say();
        assertTrue(quiet, quiet.contains("먼저 말 안 걸게"));
        assertTrue(BuddyTalk.hushed(app)); assertTrue(BuddyTalk.env(app).optBoolean("hush"));
        assertTrue(BuddyTalk.hushUntil(app) - System.currentTimeMillis() > 7_000_000L);
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"hush\",\"hours\":0}"), "이제 말해도 돼", false);
        assertFalse(BuddyTalk.hushed(app)); assertFalse(BuddyTalk.env(app).optBoolean("hush"));
        String board; try (java.io.InputStream in = app.getAssets().open("decor/board.js")) { board = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        assertTrue("the board keeps quiet while hushed", board.contains("info.env.hush") && board.contains("dnd: !!e.dnd || !!e.hush"));
    }
    /** More apps their own way: the clock's timers, a recorder, downloads, a mail, an app's info, a translator, a day in the calendar, 방해 금지. */
    @Test public void usesMoreAppsTheirOwnWay() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        java.util.function.BiConsumer<String, android.content.IntentFilter> handles = (component, filter) -> {
            android.content.ComponentName c = android.content.ComponentName.unflattenFromString(component); pm.addActivityIfNotPresent(c);
            filter.addCategory(android.content.Intent.CATEGORY_DEFAULT); pm.addIntentFilterForActivity(c, filter);
        };
        handles.accept("com.clock/.Timers", new android.content.IntentFilter(android.provider.AlarmClock.ACTION_SHOW_TIMERS));
        handles.accept("com.rec/.Record", new android.content.IntentFilter(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION));
        handles.accept("com.files/.Downloads", new android.content.IntentFilter(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS));
        android.content.IntentFilter mail = new android.content.IntentFilter(android.content.Intent.ACTION_SENDTO); mail.addDataScheme("mailto");
        handles.accept("com.mail/.Compose", mail);
        android.content.IntentFilter info = new android.content.IntentFilter(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS); info.addDataScheme("package");
        handles.accept("com.android.settings/.AppInfo", info);
        handles.accept("com.android.settings/.Zen", new android.content.IntentFilter("android.settings.ZEN_MODE_PRIORITY_SETTINGS"));
        android.content.IntentFilter web = new android.content.IntentFilter(android.content.Intent.ACTION_VIEW); web.addDataScheme("https");
        handles.accept("com.browser/.Open", web);
        android.content.IntentFilter day = new android.content.IntentFilter(android.content.Intent.ACTION_VIEW); day.addDataScheme("content"); day.addDataAuthority("com.android.calendar", null);
        handles.accept("com.calendar/.Day", day);
        org.robolectric.shadows.ShadowApplication shadow = shadowOf(app);
        BuddyMisses.clear(app);

        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"show_timers\"}"), "타이머 보여 줘", false);
        assertEquals(android.provider.AlarmClock.ACTION_SHOW_TIMERS, shadow.getNextStartedActivity().getAction());
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"recorder\"}"), "녹음 켜 줘", false);
        assertEquals(android.provider.MediaStore.Audio.Media.RECORD_SOUND_ACTION, shadow.getNextStartedActivity().getAction());
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"downloads\"}"), "받은 파일 보여 줘", false);
        assertEquals(android.app.DownloadManager.ACTION_VIEW_DOWNLOADS, shadow.getNextStartedActivity().getAction());

        BuddyVoice.Outcome sent = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"email\",\"to\":\"friend@example.com\",\"subject\":\"안부\",\"text\":\"잘 지내?\"}"), "메일 써 줘", false);
        android.content.Intent written = shadow.getNextStartedActivity();
        assertEquals("mailto:", String.valueOf(written.getData())); assertEquals("friend@example.com", written.getStringArrayExtra(android.content.Intent.EXTRA_EMAIL)[0]);
        assertEquals("잘 지내?", written.getStringExtra(android.content.Intent.EXTRA_TEXT)); assertEquals("메일 써 놨어, 보내기만 눌러 줘!", sent.say());
        BuddyVoice.Outcome mom = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"email\",\"who\":\"엄마\",\"text\":\"곧 갈게\"}"), "엄마한테 메일", false);
        shadow.getNextStartedActivity();
        assertTrue(mom.say(), mom.say().contains("메일 주소를 몰라서")); assertEquals("contacts", BuddyMisses.list(app).get(0).fix());

        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"app_settings\",\"app\":\"카톡\"}"), "카톡 알림 설정 열어 줘", false);
        android.content.Intent details = shadow.getNextStartedActivity();
        assertEquals(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, details.getAction()); assertEquals("package:com.kakao.talk", String.valueOf(details.getData()));

        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"translate\",\"text\":\"안녕\",\"to\":\"일본어\"}"), "안녕을 일본어로 번역해 줘", false);
        android.net.Uri translated = shadow.getNextStartedActivity().getData();
        assertEquals("translate.google.com", translated.getHost()); assertEquals("ja", translated.getQueryParameter("tl")); assertEquals("안녕", translated.getQueryParameter("text"));

        BuddyVoice.Outcome tomorrow = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"calendar_view\",\"date\":\"내일\"}"), "내일 일정 보여 줘", false);
        android.content.Intent opened = shadow.getNextStartedActivity();
        assertTrue(String.valueOf(opened.getData()), String.valueOf(opened.getData()).startsWith("content://com.android.calendar/time/"));
        assertTrue(tomorrow.say(), tomorrow.say().endsWith("일정 열었어!"));

        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"settings\",\"panel\":\"dnd\"}"), "방해 금지 설정 열어 줘", false);
        assertEquals("android.settings.ZEN_MODE_PRIORITY_SETTINGS", shadow.getNextStartedActivity().getAction());

        // Missing apps: why, the way to get one, and what it can do in their place.
        BuddyVoice.Outcome memo = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"memo\",\"text\":\"우유 사기\"}"), "우유 사기 메모해 줘", false);
        assertTrue(memo.say(), memo.say().contains("기억해 줘")); assertEquals("store:메모", BuddyMisses.list(app).get(0).fix());
        assertTrue(BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"gallery\"}"), "사진 보여 줘", false).say().contains("갤러리"));
        assertTrue(BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"app_settings\",\"app\":\"없는앱\"}"), "", false).say().contains("없는앱"));
    }
    /**
     * 퍼레 itself by voice or in the talk (준: "음성 대화로 주사위 꺼내봐 해도 안된다고해", "의상바꿔도 안되고", "음성대화로 퍼레의
     * 상호작용이 안돼"): its toys, clothes and plays are tools; every word names something board.js has; asked while the home
     * screen is away, it is done when the home screen is shown.
     */
    @Test public void playsWithItselfWhenAsked() throws Exception {
        org.json.JSONObject toy = BuddyTools.act(new org.json.JSONArray().put(new org.json.JSONObject().put("name", "buddy_toy").put("arguments", "{\"toy\":\"주사위\"}")));
        assertEquals("launcher.self({\"toy\":\"dice\"})", BuddyBoard.call(toy)); assertEquals("주사위 꺼낼게!", BuddyTools.say(toy));
        org.json.JSONObject wear = BuddyTools.act(new org.json.JSONArray().put(new org.json.JSONObject().put("name", "buddy_outfit").put("arguments", "{\"items\":[\"고양이 귀\",\"목도리\"]}")));
        assertEquals("launcher.self({\"wear\":[\"catears\",\"scarf\"]})", BuddyBoard.call(wear));
        assertEquals("launcher.self({\"wear\":[]})", BuddyBoard.call(new org.json.JSONObject().put("tool", "buddy_outfit").put("items", new org.json.JSONArray().put("기본"))));
        assertEquals("launcher.self({\"wear\":[],\"any\":true})", BuddyBoard.call(new org.json.JSONObject().put("tool", "buddy_outfit").put("items", new org.json.JSONArray().put("아무거나"))));
        assertEquals("launcher.self({\"play\":\"dance\"})", BuddyBoard.call(new org.json.JSONObject().put("tool", "buddy_move").put("play", "춤")));
        // The brains without tools write it as text, as they do the others.
        org.json.JSONObject typed = BuddyTalk.act("좋아!\n할일: {\"tool\":\"toy\",\"toy\":\"공\"}");
        assertEquals("buddy_toy", typed.optString("tool")); assertEquals("launcher.self({\"toy\":\"ball\"})", BuddyBoard.call(typed));
        assertTrue(BuddyTools.promised("퍼레야 주사위 꺼내 봐", "주사위 꺼낼게!"));
        assertTrue(BuddyTools.promised("옷 갈아입어 봐", "고양이 귀 쓸게!"));
        // Every word is something the page has.
        String board; try (java.io.InputStream in = org.robolectric.RuntimeEnvironment.getApplication().getAssets().open("decor/board.js")) { board = new String(in.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8); }
        String wearList = board.substring(board.indexOf("const WEAR = ["), board.indexOf("];", board.indexOf("const WEAR = [")));
        for (String k : BuddyBoard.CLOTHES.values()) if (!k.isEmpty() && !k.equals("any")) assertTrue("WEAR has " + k, wearList.contains("\"" + k + "\""));
        for (String k : BuddyBoard.PLAYS.values()) assertTrue("PLAYS has " + k, board.contains("    " + k + ": end =>"));
        for (String k : BuddyBoard.TOYS.values()) assertTrue("ASK_TOY has " + k, k.equals("away") || board.contains(k + ": \""));
        // Away from the home screen: kept, then done when it is shown.
        BuddyBoard.away();
        BuddyVoice.Outcome later = BuddyActs.run(org.robolectric.RuntimeEnvironment.getApplication(), toy, "주사위 꺼내 봐", true);
        assertEquals("홈 화면에 가면 바로 할게!", later.say());
        java.util.List<String> calls = new java.util.ArrayList<>();
        BuddyBoard.shown(calls::add);
        assertEquals(java.util.List.of("launcher.self({\"toy\":\"dice\"})"), calls);
        assertEquals("", BuddyActs.run(org.robolectric.RuntimeEnvironment.getApplication(), wear, "고양이 귀 써 봐", false).say());
        assertEquals(2, calls.size());
        BuddyBoard.away();
    }
    /** What it does not know about the user it asks (준: "아는것이 전투기 밖에 없어?", "모르면 질문을 하면 되자너"). */
    @Test public void asksWhatItDoesNotKnow() throws Exception {
        String known = "사용자는 전투기 3D 모델링을 한대\n사용자는 커피를 좋아한대";
        long now = 1_800_000_000_000L;
        String q = dev.jun.gyeot.core.BuddyMind.curious(known, new org.json.JSONObject(), now, new java.util.Random(3));
        assertFalse(q.isEmpty()); assertNotEquals("not what it knows", "좋아하는 음료(커피·차 …)", q);
        int k = dev.jun.gyeot.core.BuddyMind.knows(known);
        assertTrue("the coffee it was told counts as known: " + k, k >= 1 && k <= 3);
        org.json.JSONObject asked = new org.json.JSONObject().put(q, now);
        assertEquals("not again within twenty minutes", "", dev.jun.gyeot.core.BuddyMind.curious(known, asked, now + 60_000, new java.util.Random(3)));
        for (int i = 0; i < 20; i++) assertNotEquals("not the same one within three days", q, dev.jun.gyeot.core.BuddyMind.curious(known, asked, now + 3_600_000L, new java.util.Random(i)));
    }
    /**
     * 퍼레's own world and heart (준: "최애 장난감은 변할 수 있어", "그건 퍼레의 감정에 의해 결정되는거라", "자유도가 무한인 상호작용"):
     * its favorite comes from what it lived, moves when another becomes dearer (not within the hour), fades with time; what it
     * wants and does not have becomes a wish; the brain gets all of it.
     */
    @Test public void hasAWorldAndAHeartOfItsOwn() throws Exception {
        dev.jun.gyeot.core.BuddyWorld.know(BuddyBoard.CLOTHES, BuddyBoard.PLAYS);
        org.json.JSONObject w = new org.json.JSONObject(); long t = 1_800_000_000_000L;
        for (int i = 0; i < 4; i++) dev.jun.gyeot.core.BuddyWorld.lived(w, "toy", "dice", 1.6, "6이 나와서", t + i * 60_000);
        assertEquals("dice", dev.jun.gyeot.core.BuddyWorld.favorite(w, "toy", t + 300_000));
        // The ball becomes dearer: not within the hour, then yes.
        for (int i = 0; i < 6; i++) dev.jun.gyeot.core.BuddyWorld.lived(w, "toy", "ball", 2, "사용자가 같이 놀아 줘서", t + 400_000 + i * 1000);
        assertEquals("kept within the hour", "dice", dev.jun.gyeot.core.BuddyWorld.favorite(w, "toy", t + 600_000));
        assertEquals("ball", dev.jun.gyeot.core.BuddyWorld.favorite(w, "toy", t + 2 * 3_600_000L));
        // Things that went badly are the ones it likes least; owned things are no wish; a new one is.
        dev.jun.gyeot.core.BuddyWorld.lived(w, "play", "yoga", -1.5, "넘어져서", t);
        assertEquals("yoga", dev.jun.gyeot.core.BuddyWorld.least(w, "play", t + 1000));
        assertFalse(dev.jun.gyeot.core.BuddyWorld.wish(w, "주사위", t));
        assertTrue(dev.jun.gyeot.core.BuddyWorld.wish(w, "트램펄린", t));
        assertFalse("not twice", dev.jun.gyeot.core.BuddyWorld.wish(w, "트램펄린", t));
        String words = dev.jun.gyeot.core.BuddyWorld.words(w, t + 2 * 3_600_000L);
        assertTrue(words, words.contains("가진 장난감") && words.contains("장난감 공") && words.contains("트램펄린") && words.contains("요가"));
        // A week later the fondness has faded: today's whims decide more.
        assertTrue(dev.jun.gyeot.core.BuddyWorld.wishes(w).contains("트램펄린"));
        org.json.JSONObject wish = BuddyTools.act(new org.json.JSONArray().put(new org.json.JSONObject().put("name", "buddy_wish").put("arguments", "{\"thing\":\"썰매\"}")));
        assertEquals("buddy_wish", wish.optString("tool")); assertEquals("썰매, 소원 목록에 적어 둘게!", BuddyTools.say(wish));
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
        assertFalse(said, said.contains("http") || said.contains("할일"));
        // 준: "이모지도 쓸 수 있게 해줘" — kept in what is shown, not said aloud; no sparkles.
        assertTrue(said, said.contains("😊")); assertEquals("두구두구! 네이버 지도 바로 열어볼게.", BuddyVoice.voiced(said));
        assertFalse(BuddyTalk.spoken("좋아 ✨ 가자").contains("✨"));
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

    /**
     * One subject does not come back again and again (준: "전투기이야기만 수백번 하고 있어"): once in the last eight lines is
     * enough for a long word, twice in the last thirty for any; what it knows on that subject is left out of what it sees.
     */
    @Test public void doesNotKeepComingBackToOneSubject() {
        String[] other = {"하늘이 맑다", "구름 모양이 솜사탕 같아", "붕어빵 팥이 좋아 슈크림이 좋아?", "바람 소리 슝슝", "노래 한 곡 듣고 싶다",
            "고양이 꿈 꿨어", "단풍잎 하나 주웠어", "춤 연습 중이야", "수수께끼 낼게", "간식 냠냠"};
        java.util.List<String> said = new java.util.ArrayList<>(java.util.List.of("전투기 모델링은 잘 돼 가?"));
        said.addAll(java.util.List.of(other));
        assertFalse("once, long ago", dev.jun.gyeot.core.BuddyMind.staleSubject("전투기 날개 멋지더라", said));
        said.add("전투기 소리 쌩!");
        assertTrue("twice in thirty", dev.jun.gyeot.core.BuddyMind.staleSubject("모델링하는 전투기 보고 싶다", said));
        assertTrue("once, just now", dev.jun.gyeot.core.BuddyMind.staleSubject("전투기라서 빠르지", java.util.List.of("전투기 소리 쌩!")));
        assertFalse("a short word once", dev.jun.gyeot.core.BuddyMind.staleSubject("하늘 높다", java.util.List.of("하늘이 맑다")));
        assertEquals("사용자는 고양이를 좋아한대", dev.jun.gyeot.core.BuddyMind.freshLines("사용자는 전투기를 3D로 모델링한대\n사용자는 고양이를 좋아한대",
            dev.jun.gyeot.core.BuddyMind.staleSubjects(said)));
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
