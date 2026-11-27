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
        assertTrue(BuddyTools.promised("소리 좀 줄여 줘", "줄여 놓을게!"));
        for (int i = 0; i < BuddyTools.TOOLS.length(); i++) assertEquals("function", BuddyTools.TOOLS.getJSONObject(i).getString("type"));
        // 준: "퍼레의 판단력" — 퍼레's own tools go only with words about 퍼레 (lighter turns), and a forced retry may choose nothing.
        java.util.Set<String> phone = new java.util.HashSet<>(), own = new java.util.HashSet<>();
        for (int i = 0; i < BuddyTools.PHONE.length(); i++) phone.add(BuddyTools.PHONE.getJSONObject(i).getJSONObject("function").getString("name"));
        for (int i = 0; i < BuddyTools.SELF.length(); i++) own.add(BuddyTools.SELF.getJSONObject(i).getJSONObject("function").getString("name"));
        assertEquals(java.util.Set.of("buddy_toy", "buddy_outfit", "buddy_move", "buddy_wish", "remember", "forget", "call_me", "quiet"), own);
        assertEquals("each tool in one set", 45, phone.size() + own.size());
        java.util.Set<String> both = new java.util.HashSet<>(phone); both.retainAll(own); assertTrue(both.isEmpty());
        for (String answer : new String[]{"coin", "dice", "pick", "calculator", "world_time", "count_days", "stopwatch"}) assertTrue("an answer that needs no app goes every turn: " + answer, phone.contains(answer));
        org.json.JSONArray light = BuddyTools.forTurn("손전등 켜 줘");
        assertEquals(37, light.length());
        // 퍼레's eight carry its long lists of names (toys, clothes, plays): about a fifth of all the tools' words.
        assertTrue("lighter: " + light.toString().length() + " of " + BuddyTools.TOOLS.toString().length(), light.toString().length() < 0.85 * BuddyTools.TOOLS.toString().length());
        assertFalse(BuddyTools.rule(false).contains("buddy_toy")); assertTrue(BuddyTools.rule(true).contains("buddy_toy")); assertEquals(BuddyTools.RULE, BuddyTools.rule(true));
        for (String about : new String[]{"고양이 귀 해 봐", "춤춰 봐", "퍼레야, 주사위 꺼내 봐", "나 내일 시험이야 기억해 줘", "이제 형이라고 불러", "한 시간만 조용히 해 줘", "비눗방울 불어 줘"})
            assertTrue("about 퍼레: " + about, BuddyTools.self(about));
        assertTrue(BuddyTools.forTurn("고양이 귀 해 봐").toString().contains("\"buddy_outfit\"")); assertTrue(BuddyTools.forTurn("춤춰 봐").toString().contains("\"buddy_move\""));
        for (String phoneOnly : new String[]{"손전등 켜 줘", "카메라 켜 줘", "노래 틀어 줘", "내일 일정 뭐야", "동전 던져 줘", "네이버 지도 열어 줘", "소리 좀 줄여 줘"})
            assertFalse("not about 퍼레: " + phoneOnly, BuddyTools.self(phoneOnly));
        org.json.JSONArray nothing = new org.json.JSONArray().put(new org.json.JSONObject().put("name", "nothing").put("arguments", "{}"));
        assertNull("nothing is no act", BuddyTools.act(nothing)); assertTrue(BuddyTools.nothing(nothing)); assertFalse(BuddyTools.nothing(new org.json.JSONArray()));
        org.json.JSONArray retry = BuddyTools.orNothing();
        assertEquals(46, retry.length()); assertEquals("nothing", retry.getJSONObject(45).getJSONObject("function").getString("name"));
        assertEquals("the full list stays as it was", 45, BuddyTools.TOOLS.length());
        // 준: "3번" — each sentence said as soon as it is written; a word or two waits for the next; no act text, digits kept.
        java.util.List<String> said = new java.util.ArrayList<>();
        BuddyTalk.Sentences s = new BuddyTalk.Sentences(said::add);
        for (String piece : new String[]{"응! 지금", " 바로 틀어 줄게. 아이유", " 신곡 좋더라~ 7시에", " 또 듣자\n할일: {\"tool\":\"play\"}"}) s.add(piece);
        s.end();
        assertEquals(java.util.List.of("응! 지금 바로 틀어 줄게.", "아이유 신곡 좋더라~", "7시에 또 듣자"), said);
        // Talking over 퍼레 (its own echo never stops it, the user does): talksOverWithoutCuttingItself.
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

    /**
     * 퍼레's answer said as one stream (준: "음성대화 너무 느려", core/SayQueue): the sentences play in the order they were written
     * whichever sound came first, the next one is asked for as soon as one plays and never more than one ahead, a sentence
     * with no sound keeps its place with its fallback, and a talk cut short asks for and plays nothing more.
     */
    @Test public void speaksSentencesInOrder() throws Exception {
        dev.jun.gyeot.core.SayQueue q = new dev.jun.gyeot.core.SayQueue();
        dev.jun.gyeot.core.SayQueue.Clip a = q.add("안녕!"), b = q.add("오늘 날씨 진짜 좋다."), c = q.add("산책 갈래?");
        assertSame("the first is asked for at once", a, q.nextToFetch());
        assertNull("nothing ahead before the first plays", q.nextToFetch());
        assertNull("nothing to play before its first sound", q.take(10));
        q.arrived(a, new byte[]{1, 0});
        assertSame(a, q.take(10)); assertEquals(dev.jun.gyeot.core.SayQueue.State.PLAYING, a.state());
        assertSame("the next is asked for as soon as one plays", b, q.nextToFetch());
        assertNull("one ahead, never more", q.nextToFetch());
        // The second's sound all comes while the first still streams: it still plays second.
        q.arrived(b, new byte[]{2, 0, 3, 0}); q.fetched(b);
        assertFalse(a.ended); assertEquals(dev.jun.gyeot.core.SayQueue.State.READY, b.state());
        assertNull("not ahead of the one before it", q.nextToFetch());
        q.arrived(a, new byte[]{4, 0}); q.fetched(a); q.played(a);
        assertSame(b, q.take(10)); assertEquals(4, b.bytes);
        assertSame(c, q.nextToFetch());
        // No sound for the third: in its place, said from Typecast's whole file.
        q.failed(c, dev.jun.gyeot.core.SayQueue.Fallback.WHOLE); q.close(); q.played(b);
        assertSame("a failed one keeps its place", c, q.take(10));
        assertEquals(dev.jun.gyeot.core.SayQueue.State.FAILED, c.state()); assertEquals(dev.jun.gyeot.core.SayQueue.Fallback.WHOLE, c.fallback());
        q.played(c);
        assertNull(q.take(10)); assertTrue("all said", q.over());
        // A later one's sound reported first (out of its turn): it still waits for the one before it.
        dev.jun.gyeot.core.SayQueue turn = new dev.jun.gyeot.core.SayQueue();
        dev.jun.gyeot.core.SayQueue.Clip one = turn.add("첫 문장이야."), two = turn.add("둘째 문장이야.");
        assertSame(one, turn.nextToFetch());
        turn.arrived(two, new byte[]{5, 0}); turn.fetched(two);
        assertNull("not before the first", turn.take(10));
        turn.arrived(one, new byte[]{6, 0});
        assertSame(one, turn.take(10)); turn.played(one); assertSame(two, turn.take(10));
        // A fetch that broke after some sound: what came is said, no fallback.
        dev.jun.gyeot.core.SayQueue some = new dev.jun.gyeot.core.SayQueue(); dev.jun.gyeot.core.SayQueue.Clip s = some.add("반가워.");
        assertSame(s, some.nextToFetch()); some.arrived(s, new byte[]{1, 0}); some.failed(s, dev.jun.gyeot.core.SayQueue.Fallback.PHONE);
        assertTrue(s.ended); assertEquals(dev.jun.gyeot.core.SayQueue.Fallback.NONE, s.fallback()); assertSame(s, some.take(10));
        // Talked over: the fetch on its way stops (one not sent yet never goes), nothing more is asked for or played.
        dev.jun.gyeot.core.SayQueue cut = new dev.jun.gyeot.core.SayQueue();
        dev.jun.gyeot.core.SayQueue.Clip x = cut.add("하나 둘 셋."); cut.add("넷 다섯.");
        java.util.concurrent.CountDownLatch stopped = new java.util.concurrent.CountDownLatch(1);
        assertSame(x, cut.nextToFetch()); assertTrue(dev.jun.gyeot.core.RequestAbort.register(x.cancel, stopped::countDown));
        cut.arrived(x, new byte[]{1, 0});
        cut.cancelAll();
        assertTrue("its fetch stopped", stopped.await(2, java.util.concurrent.TimeUnit.SECONDS));
        assertTrue("nothing kept", x.chunks.isEmpty());
        assertNull(cut.take(10)); assertNull(cut.nextToFetch()); assertTrue(cut.over());
        dev.jun.gyeot.core.SayQueue.Clip late = cut.add("여섯.");
        assertNull("nothing after it", cut.nextToFetch()); assertNull(cut.take(10));
        assertFalse("a request of its own after it is never sent", dev.jun.gyeot.core.RequestAbort.register(x.cancel, () -> {}));
        assertEquals(dev.jun.gyeot.core.SayQueue.State.DONE, late.state());
        // How long words take before their sound tells it (the bubble types along), and the breath between two sentences.
        assertEquals(6 * 95, BuddyVoice.sayMs("안녕 반가워"));
        assertTrue(BuddyVoice.BETWEEN_MS < 200 && BuddyVoice.GAP_MS > BuddyVoice.BETWEEN_MS);
    }

    /** A 20 ms microphone frame whose RMS is level. */
    private static short[] frame(double level) {
        short[] f = new short[dev.jun.gyeot.core.OverEar.FRAME];
        for (int i = 0; i < f.length; i++) f[i] = (short) (i % 2 == 0 ? level : -level);
        return f;
    }
    /**
     * Talking over 퍼레 (준: "퍼레가 말하는 중에 끼어들면 멈추고 듣기", core/OverEar): its own echo never stops it, however long it
     * talks; a short "응" does not; the user does within half a second, and what they say is kept from just before their voice
     * began. What plays is still known in the pause between two sentences (core/PlayLevel).
     */
    @Test public void talksOverWithoutCuttingItself() {
        dev.jun.gyeot.core.PlayLevel playing = new dev.jun.gyeot.core.PlayLevel();
        playing.add(0, 1000, 6000);
        assertEquals("the 400 ms pause between two sentences still plays", 6000, playing.level(1400), 0);
        playing.add(1400, 2400, 5000);
        assertEquals(5000, playing.level(2000), 0);
        assertEquals("nothing played lately", 0, playing.level(3200), 0);
        playing.clear(); assertEquals("cut short", 0, playing.level(2000), 0);

        // 퍼레's own echo through the echo canceller: 0.45 of what plays, for ten seconds.
        dev.jun.gyeot.core.BargeIn barge = new dev.jun.gyeot.core.BargeIn();
        dev.jun.gyeot.core.OverEar ear = new dev.jun.gyeot.core.OverEar(barge);
        short[] echo = frame(0.45 * 6000), user = frame(2.5 * 0.45 * 6000);
        for (int i = 0; i < 500; i++) assertEquals("its own voice, frame " + i, dev.jun.gyeot.core.OverEar.Step.ECHO, ear.add(echo, echo.length, 6000));
        // A 300 ms "응" over it, then the echo again: it goes on.
        for (int i = 0; i < 15; i++) assertEquals("응", dev.jun.gyeot.core.OverEar.Step.ECHO, ear.add(user, user.length, 6000));
        for (int i = 0; i < 50; i++) assertEquals(dev.jun.gyeot.core.OverEar.Step.ECHO, ear.add(echo, echo.length, 6000));
        // The next answer (what the echo sounds like kept): the user at 2.5 times the echo stops it within half a second.
        ear = new dev.jun.gyeot.core.OverEar(barge); int frames = 0;
        while (frames < 40 && ear.add(user, user.length, 6000) != dev.jun.gyeot.core.OverEar.Step.OVER) frames++;
        assertTrue("talked over after " + (frames + 1) * 20 + " ms", (frames + 1) * 20 <= 500);

        // What they said, from just before their voice began: 퍼레's echo (words of 250 ms with short pauses), the user over it
        // from 1.2 s to 2.8 s (syllables of 240 ms, 60 ms between), 퍼레 stopped as soon as it hears them, then quiet.
        java.util.Random r = new java.util.Random(3);
        int rate = dev.jun.gyeot.core.WakeWord.RATE, size = dev.jun.gyeot.core.OverEar.FRAME, starts = 60 * size, ends = rate * 28 / 10, n = rate * 5;
        short[] mic = new short[n];
        dev.jun.gyeot.core.OverEar heard = new dev.jun.gyeot.core.OverEar(barge);
        dev.jun.gyeot.core.OverEar.Step step = dev.jun.gyeot.core.OverEar.Step.ECHO; int over = -1;
        for (int at = 0; at + size <= n; at += size) {
            // 퍼레 speaks until the user stops it.
            for (int i = at; i < at + size; i++) {
                double t = i / (double) rate;
                double e = over < 0 && i % 5280 < 4000 ? Math.sin(2 * Math.PI * 180 * t) * 2700 * Math.sqrt(2) : 0;
                double v = i >= starts && i < ends && (i - starts) % 4800 < 3840 ? Math.sin(2 * Math.PI * 140 * t) * 7000 * Math.sqrt(2) + Math.sin(2 * Math.PI * 280 * t) * 2000 : 0;
                mic[i] = (short) Math.max(-32768, Math.min(32767, Math.round(v + e + r.nextGaussian() * 30)));
            }
            short[] f = java.util.Arrays.copyOfRange(mic, at, at + size);
            if (over < 0) {
                step = heard.add(f, f.length, 6000);
                if (step == dev.jun.gyeot.core.OverEar.Step.OVER) { over = at; step = heard.hear(4000); }
                continue;
            }
            step = heard.add(f, f.length, 0);
            if (step == dev.jun.gyeot.core.OverEar.Step.DONE || step == dev.jun.gyeot.core.OverEar.Step.SILENT) break;
        }
        assertTrue("stopped once they spoke: " + over, over > starts && over - starts <= rate / 2);
        assertEquals(dev.jun.gyeot.core.OverEar.Step.DONE, step);
        short[] said = heard.pcm();
        int first = starts - 15 * dev.jun.gyeot.core.OverEar.FRAME, keep = rate * 3 / 10;
        assertArrayEquals("it starts 300 ms before their voice and keeps its first 300 ms",
            java.util.Arrays.copyOfRange(mic, first, starts + keep), java.util.Arrays.copyOf(said, starts + keep - first));
        assertTrue("all of what they said: " + said.length, said.length >= ends - first);
        // After an answer it hears the next words itself, with no barge-in check any more.
        dev.jun.gyeot.core.OverEar next = new dev.jun.gyeot.core.OverEar(barge); next.listen(7000);
        assertNotEquals(dev.jun.gyeot.core.OverEar.Step.OVER, next.add(user, user.length, 6000));
        assertTrue(next.hearing());
    }
    /**
     * The words 퍼레 answers itself, with no AI and no Typecast (준: "음성상호작용을 더 강화해줘", core/VoiceTurns): a bare call, an
     * end, 그만 while music plays, 뭐라고?, 잠깐 right after being cut short, and a no to an alarm waiting for a yes; talk that only
     * starts with those words is talk.
     */
    @Test public void handlesTurnWordsItself() throws Exception {
        dev.jun.gyeot.core.VoiceTurns.Kind CALL = dev.jun.gyeot.core.VoiceTurns.Kind.CALL, BYE = dev.jun.gyeot.core.VoiceTurns.Kind.BYE, PAUSE = dev.jun.gyeot.core.VoiceTurns.Kind.PAUSE,
            REPEAT = dev.jun.gyeot.core.VoiceTurns.Kind.REPEAT, HUSH = dev.jun.gyeot.core.VoiceTurns.Kind.HUSH, NO = dev.jun.gyeot.core.VoiceTurns.Kind.NO, NONE = dev.jun.gyeot.core.VoiceTurns.Kind.NONE;
        Object[][] table = {
            // heard, just cut, confirming, music on, what it is
            {"퍼레야", false, false, false, CALL}, {"퍼레야!", false, false, false, CALL}, {"들퍼레", false, false, false, CALL},
            {"그만할래", false, false, false, BYE}, {"됐어 고마워", false, false, false, BYE}, {"이제 그만하자.", false, false, true, BYE}, {"잘 자", false, false, false, BYE},
            {"그만", false, false, true, PAUSE}, {"그만", false, false, false, BYE},
            {"그만 먹고 싶어", false, false, true, NONE}, {"다 됐어?", false, false, false, NONE}, {"그만큼 좋아", false, false, false, NONE}, {"끝?", false, false, false, NONE},
            {"뭐라고?", false, false, false, REPEAT}, {"다시 말해 줘", false, false, false, REPEAT},
            {"잠깐", true, false, false, HUSH}, {"잠깐만", true, false, false, HUSH}, {"잠깐", false, false, false, NONE},
            {"됐어", false, true, false, NO}, {"아니", false, true, false, NO}, {"아니", false, false, false, NONE}, {"됐어", false, false, false, NONE},
            {"퍼레야 오늘 날씨 어때", false, false, false, NONE}, {"", false, false, false, NONE}, {null, false, false, false, NONE},
        };
        for (Object[] row : table)
            assertEquals(String.valueOf(row[0]), row[4], dev.jun.gyeot.core.VoiceTurns.of((String) row[0], (boolean) row[1], (boolean) row[2], (boolean) row[3]));

        // In a talk, with the phone's recognizer played by the test: a bare call is answered and it listens again; an end
        // ends it; neither goes to the brain.
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        java.util.List<SpeechInput.Listener> ears = new java.util.ArrayList<>();
        SpeechInput.Ears before = BuddyVoice.ears;
        BuddyVoice.ears = new SpeechInput.Ears() {
            @Override public SpeechInput.Ear open(android.content.Context c) {
                return new SpeechInput.Ear() { @Override public void start(SpeechInput.Listener l) { ears.add(l); } @Override public void finish() {} @Override public void close() {} };
            }
            @Override public boolean available(android.content.Context c) { return true; }
        };
        try {
            BuddyVoice talk = new BuddyVoice(app, app.store());
            java.util.List<String> shown = new java.util.ArrayList<>();
            talk.attach((state, text) -> shown.add(state + ":" + text), then -> then.run());
            talk.start();
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, ears.size());
            assertFalse("not listening before the recognizer is ready: " + shown, shown.contains("listening:"));
            ears.get(0).ready(); assertEquals("listening:", shown.get(shown.size() - 1));
            ears.get(0).heard("퍼레야");
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertTrue(talk.on()); assertEquals("it listens again: " + shown, 2, ears.size());
            ears.get(1).ready();
            ears.get(1).heard("그만할래");
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(400));
            assertFalse("ended", talk.on());
            assertEquals("done:" + BuddyVoice.BYE_WORDS, shown.get(shown.size() - 1));
            assertEquals("the brain was never asked", 0, talk.answers);
        } finally { BuddyVoice.ears = before; }
    }

    /**
     * Over music, 퍼레 lets a song's words go (준: "음성상호작용을 더 강화해줘"): only words with its name, or said to the music
     * itself, get through; and both ears are told this phone's own words (its names, the saved places, the apps just used, the
     * names in what it said last), Whisper with a short prompt whose echo on a short clip counts as nothing.
     */
    @Test public void ignoresSongsAndKnowsItsWords() throws Exception {
        assertNull(dev.jun.gyeot.core.VoiceHints.musicGate("너의 손을 잡고", true));
        assertNull(dev.jun.gyeot.core.VoiceHints.musicGate("다음 곡도 너를 생각하며 걸어가", true));
        assertEquals("다음 곡", dev.jun.gyeot.core.VoiceHints.musicGate(dev.jun.gyeot.core.BuddyMind.nameHeard("버레야 다음 곡"), true));
        assertEquals("다음 곡", dev.jun.gyeot.core.VoiceHints.musicGate("다음 곡", true));
        assertEquals("소리 좀 줄여 줘", dev.jun.gyeot.core.VoiceHints.musicGate("소리 좀 줄여 줘", true));
        assertEquals("퍼레야", dev.jun.gyeot.core.VoiceHints.musicGate("퍼레야", true));
        assertEquals("퍼레야", dev.jun.gyeot.core.VoiceHints.musicGate("퍼레야!", true));
        assertEquals("지금 몇 시야", dev.jun.gyeot.core.VoiceHints.musicGate("퍼레야 지금 몇 시야", true));
        assertNull("a name inside other words is not a call", dev.jun.gyeot.core.VoiceHints.musicGate("퍼레가 좋아", true));
        assertEquals("아무 말", dev.jun.gyeot.core.VoiceHints.musicGate("아무 말", false));

        java.util.List<String> names = java.util.List.of("퍼레", "퍼레야", "들퍼레");
        java.util.List<String> hints = dev.jun.gyeot.core.VoiceHints.build(names, java.util.List.of("회사", "집"), java.util.List.of("네이버 지도", "카카오톡"), java.util.List.of("주사위", "고양이 귀"), "아이유 좋은날 틀어 줄게! 🎵");
        for (String w : new String[]{"퍼레", "회사", "네이버 지도", "주사위", "아이유", "좋은날"}) assertTrue(w + " in " + hints, hints.contains(w));
        for (String w : new String[]{"틀어", "줄게"}) assertFalse(w + " is not a name: " + hints, hints.contains(w));
        java.util.List<String> lots = new java.util.ArrayList<>(); for (int i = 0; i < 300; i++) lots.add("장난감" + i);
        java.util.List<String> full = dev.jun.gyeot.core.VoiceHints.build(names, lots, lots, lots, "아이유 좋은날 틀어 줄게!");
        assertTrue(full.size() <= dev.jun.gyeot.core.VoiceHints.MOST); assertTrue("the last words always fit: " + full, full.contains("좋은날"));
        String prompt = dev.jun.gyeot.core.VoiceHints.prompt("아이유 좋은날 틀어 줄게! 🎵", hints);
        assertTrue(prompt, prompt.startsWith("퍼레야, 아이유 좋은날 틀어 줄게!")); assertTrue(prompt, prompt.contains("회사"));
        assertTrue(prompt, prompt.length() <= 120); assertFalse(prompt, prompt.contains("🎵"));
        assertTrue(dev.jun.gyeot.core.VoiceHints.prompt("가".repeat(300), full).length() <= 120);
        // The phone's recognizer leans toward them (its names alone when there are none).
        assertEquals(hints, SpeechInput.intent(hints).getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_BIASING_STRINGS));
        assertEquals(names, SpeechInput.intent(java.util.List.of()).getStringArrayListExtra(android.speech.RecognizerIntent.EXTRA_BIASING_STRINGS));
        // Whisper's echo of its prompt on a short clip is nothing; said for longer, or other words, it is what was said.
        String told = dev.jun.gyeot.core.VoiceHints.prompt("", java.util.List.of());
        assertEquals("퍼레야,", told);
        assertEquals(600, GroqWork.ms(dev.jun.gyeot.core.Utterance.wav(new short[9600], 16000)));
        assertEquals(2000, GroqWork.ms(dev.jun.gyeot.core.Utterance.wav(new short[32000], 16000)));
        assertTrue(dev.jun.gyeot.core.VoiceHints.echo("퍼레야,", told, 600));
        assertFalse(dev.jun.gyeot.core.VoiceHints.echo("퍼레야,", told, 2000));
        assertFalse(dev.jun.gyeot.core.VoiceHints.echo("다음 곡", told, 600));

        // In a talk over another app with music on, the phone's recognizer played by the test: a song's words are let go
        // (the brain never asked, a note in the bubble, the talk goes on), a bare call is answered, a request with the name
        // goes to the brain.
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        java.util.List<SpeechInput.Listener> ears = new java.util.ArrayList<>();
        SpeechInput.Ears before = BuddyVoice.ears; java.util.function.Predicate<android.content.Context> was = BuddyVoice.musicCheck;
        BuddyVoice.ears = new SpeechInput.Ears() {
            @Override public SpeechInput.Ear open(android.content.Context c) {
                return new SpeechInput.Ear() { @Override public void start(SpeechInput.Listener l) { ears.add(l); } @Override public void finish() {} @Override public void close() {} };
            }
            @Override public boolean available(android.content.Context c) { return true; }
        };
        BuddyVoice.musicCheck = c -> true;
        BuddyVoice talk = new BuddyVoice(app, app.store());
        try {
            talk.start();
            android.view.WindowManager wm = app.getSystemService(android.view.WindowManager.class);
            java.util.function.Supplier<String> bubble = () -> {
                java.util.List<View> views = ((org.robolectric.shadows.ShadowWindowManagerImpl) org.robolectric.shadow.api.Shadow.extract(wm)).getViews();
                View t = views.isEmpty() ? null : find(views.get(views.size() - 1), x -> x instanceof TextView && ((TextView) x).getMaxWidth() > 0 && ((TextView) x).getMaxWidth() < Integer.MAX_VALUE);
                return t == null ? "" : ((TextView) t).getText().toString();
            };
            for (int i = 0; i < 100 && ears.isEmpty(); i++) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle(); }
            assertEquals(1, ears.size());
            ears.get(0).ready();
            for (int i = 0; i < 100 && !"♪".equals(bubble.get()); i++) { Thread.sleep(10); shadowOf(Looper.getMainLooper()).idle(); }
            assertEquals("a note while the music plays", "♪", bubble.get());
            ears.get(0).partial("너의 손을");
            assertEquals("the song is not shown as heard", "♪", bubble.get());
            ears.get(0).heard("너의 손을 잡고 걸어가");
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500));
            assertTrue(talk.on()); assertEquals("it listens on", 2, ears.size());
            assertEquals("the brain was not asked", 0, talk.answers);
            assertTrue(app.store().phoneLogText().contains("음악 중 이름 없는 말 넘김"));
            ears.get(1).ready(); ears.get(1).heard("퍼레야");
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1500));
            assertEquals("a bare call is answered, and it listens", 3, ears.size()); assertEquals(0, talk.answers);
            ears.get(2).ready(); ears.get(2).heard("퍼레야, 지금 몇 시야");
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals("words with its name go to the brain", 1, talk.answers);
        } finally { talk.stop(); BuddyVoice.ears = before; BuddyVoice.musicCheck = was; }
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

    /**
     * The user's work stays out of 퍼레's picture of them (준: "나를 이해한 바탕으로 일상적인 말걸기, 업무적인 것 말고"): the assistant's
     * note goes with 퍼레's talk without its work sections, however the user retitled them, or its lines on work and money,
     * unless the user asks about their work; what 퍼레 thinks it knows (and still asks) is not fooled by it either.
     */
    @Test public void keepsWorkOutOfItsTalk() throws Exception {
        String note = "## 하는 일·관심사\n- 영상 채널을 운영하고 게임 개발에 관심이 많다\n## 진행 중인 일\n- 게임엔진 작업\n- BGM 음악 고르기\n"
            + "## 생활 패턴\n- 밤늦게 잔다\n- 출근 전에 영상 편집을 한다\n## 좋아하는 방식\n- 작업 요청은 결론부터\n## 자주 나오는 사람·곳\n- 회의는 판교 사무실에서";
        String everyday = dev.jun.gyeot.core.BuddyMind.everyday(note);
        assertEquals("## 생활 패턴\n- 밤늦게 잔다", everyday);
        assertTrue("the whole note seems to tell its games and music", dev.jun.gyeot.core.BuddyMind.knows(note) >= 2);
        assertEquals("neither games nor music from the user's work", 0, dev.jun.gyeot.core.BuddyMind.knows(everyday));
        assertEquals("a heading edited by hand", everyday, dev.jun.gyeot.core.BuddyMind.everyday(note.replace("## 진행 중인 일", "## 진행 중인 일들")));
        assertEquals(everyday, dev.jun.gyeot.core.BuddyMind.everyday(note.replace("## 진행 중인 일", "##진행중인 일")));
        assertEquals("before any heading, lines on work go", "고양이를 좋아한다", dev.jun.gyeot.core.BuddyMind.everyday("고양이를 좋아한다\n프로젝트 마감이 다음 주"));
        assertTrue(dev.jun.gyeot.core.BuddyMind.asksWork("요즘 작업 잘 돼?")); assertTrue(dev.jun.gyeot.core.BuddyMind.asksWork("일은 어때?"));
        assertFalse(dev.jun.gyeot.core.BuddyMind.asksWork("뭐 먹을까")); assertFalse(dev.jun.gyeot.core.BuddyMind.asksWork("생일 많이 축하해"));
        // What the voice talk's brain reads, and what 퍼레 thinks it knows.
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().settings.edit().putBoolean("proactive", true).putString("aboutAi", note).commit();
        String bored = BuddyTalk.replyInput(app, "심심해", java.util.List.of(), false, "");
        assertFalse(bored, bored.contains("게임엔진")); assertTrue(bored, bored.contains("밤늦게 잔다")); assertTrue(bored, bored.contains("먼저 꺼내지 마"));
        assertTrue("asked about work, the whole note", BuddyTalk.replyInput(app, "요즘 작업 잘 돼?", java.util.List.of(), false, "").contains("게임엔진"));
        assertFalse("a guest none of it", BuddyTalk.replyInput(app, "요즘 작업 잘 돼?", java.util.List.of(), true, "").contains("밤늦게"));
        assertFalse("what it thinks it knows has no work in it", BuddyTalk.knownWords(app, app.store()).contains("게임엔진"));
    }

    /**
     * What 퍼레 does not say unasked, aloud or typed (준: "자꾸 화이팅하고 쉬래", "자꾸 파일 정리하라는 엉뚱한 이야기를 하는데"): cheering
     * or rest when the user did not say they were tired, chores they did not bring up, coins and stocks when they did not ask.
     */
    @Test public void doesNotNagAloud() {
        java.util.List<String> said = new java.util.ArrayList<>();
        BuddyTalk.Sentences s = new BuddyTalk.Sentences(said::add, "오늘 뭐 할까");
        s.add("좋아! 근데 푹 쉬어. 영화 볼래?"); s.end();
        assertEquals("'좋아!' is too short alone and goes with the nag", java.util.List.of("영화 볼래?"), said);
        assertEquals("영화 볼래?", s.said());
        java.util.List<String> comfort = new java.util.ArrayList<>();
        BuddyTalk.Sentences t = new BuddyTalk.Sentences(comfort::add, "너무 피곤해");
        t.add("에고, 오늘은 푹 쉬어. 내가 옆에 있을게."); t.end();
        assertEquals("tired, a word of comfort is theirs", java.util.List.of("에고, 오늘은 푹 쉬어.", "내가 옆에 있을게."), comfort);
        // The whole answer is what was said aloud; nothing left is nothing to say.
        assertEquals("영화 볼래?", BuddyTalk.sayable("좋아! 근데 푹 쉬어. 영화 볼래?", "오늘 뭐 할까"));
        assertEquals("", BuddyTalk.sayable("오늘도 수고했어! 화이팅이야.", "오늘 뭐 할까"));
        assertFalse(dev.jun.gyeot.core.BuddyMind.unasked("배터리 80% 남았어", "배터리 얼마야"));
        assertFalse(dev.jun.gyeot.core.BuddyMind.unasked("저장공간 20GB 남았어", "저장공간 얼마 남았어"));
        assertTrue(dev.jun.gyeot.core.BuddyMind.unasked("사진 정리 한번 해 볼까?", "심심해"));
        assertTrue(dev.jun.gyeot.core.BuddyMind.unasked("요즘 비트코인 시세 좋더라", "뭐 하고 놀까"));
        assertFalse(dev.jun.gyeot.core.BuddyMind.unasked("비트코인은 지금 좀 내렸대", "비트코인 얼마야"));
        assertTrue(dev.jun.gyeot.core.BuddyMind.tired("배가 아파")); assertFalse("아파트 is no pain", dev.jun.gyeot.core.BuddyMind.tired("아파트 앞이야"));
        // A typed answer loses the stock line it was not asked for, its lines and list kept.
        assertEquals("오늘 날씨 맑아.\n- 산책 어때?", dev.jun.gyeot.core.BuddyMind.keepAsked("오늘 날씨 맑아. 주식은 계속 오르는 중이야.\n- 산책 어때?", "오늘 뭐 할까"));
        assertEquals("", dev.jun.gyeot.core.BuddyMind.keepAsked("수고했어! 푹 쉬어.", "오늘 뭐 할까"));
        assertEquals("주식은 계속 오르는 중이야.", dev.jun.gyeot.core.BuddyMind.keepAsked("주식은 계속 오르는 중이야.", "주식 어때"));
    }

    /**
     * The talk goes on (준: "음성상호작용을 더 강화해줘"): the turns typed in 퍼레와 대화 go into the voice talk, the last talk is
     * there for ten minutes, a brain that fails once asks to hear it again (the second time in a row ends it, saying why), a
     * slow brain gets one more "음…", an answer with nothing left to say listens on, and a guest's voice neither hears the
     * user's talk nor does a thing on the phone.
     */
    @Test public void keepsTheTalkGoing() throws Exception {
        assertEquals(2, BuddyVoice.seed("[[\"user\",\"내일 부산 가\"],[\"assistant\",\"좋겠다!\"]]").size());
        assertEquals("user", BuddyVoice.seed("[[\"user\",\"내일 부산 가\"]]").get(0)[0]);
        org.json.JSONArray twelve = new org.json.JSONArray(); for (int i = 0; i < 12; i++) twelve.put(new org.json.JSONArray().put(i % 2 == 0 ? "user" : "assistant").put("말 " + i + " " + "가".repeat(300)));
        java.util.List<String[]> last = BuddyVoice.seed(twelve.toString());
        assertEquals(8, last.size()); assertTrue(last.get(0)[1].startsWith("말 4 ")); assertEquals(200, last.get(0)[1].length());
        assertTrue(BuddyVoice.seed("not json").isEmpty()); assertTrue(BuddyVoice.seed(null).isEmpty());
        // Why a brain could not answer, in a few words.
        assertEquals("Groq HTTP 429", BuddyTalk.why("Groq", new IllegalStateException("Groq 오류 HTTP 429 (Rate limit reached)"), 8_000));
        assertEquals("NVIDIA HTTP 429", BuddyTalk.why("NVIDIA", new dev.jun.gyeot.core.ModelLimit(429), 8_000));
        assertEquals("시간 초과 9초", BuddyTalk.why("Groq", new java.net.SocketTimeoutException("Read timed out"), 9_000));
        assertEquals("빈 답", BuddyTalk.why("Groq", new IllegalStateException("Groq가 빈 답을 보냈어요."), 8_000));
        assertEquals("Groq 키가 없어요.", BuddyTalk.why("Groq", new IllegalStateException("Groq 키가 없어요."), 8_000));

        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        java.util.List<SpeechInput.Listener> ears = new java.util.ArrayList<>();
        java.util.List<BuddyTalk.Ask> asks = new java.util.concurrent.CopyOnWriteArrayList<>();
        String[] brain = {"wait"};
        SpeechInput.Ears before = BuddyVoice.ears; java.util.function.BiConsumer<android.content.Context, BuddyTalk.Ask> was = BuddyVoice.brain;
        BuddyVoice.ears = new SpeechInput.Ears() {
            @Override public SpeechInput.Ear open(android.content.Context c) {
                return new SpeechInput.Ear() { @Override public void start(SpeechInput.Listener l) { ears.add(l); } @Override public void finish() {} @Override public void close() {} };
            }
            @Override public boolean available(android.content.Context c) { return true; }
        };
        BuddyVoice.brain = (c, ask) -> { asks.add(ask); if (brain[0].equals("fail")) ask.failed().accept("Groq HTTP 429"); };
        BuddyVoice talk = new BuddyVoice(app, app.store());
        java.util.List<String> shown = new java.util.ArrayList<>();
        java.util.function.BooleanSupplier[] until = new java.util.function.BooleanSupplier[1];
        Runnable settle = () -> { for (int i = 0; i < 200 && !until[0].getAsBoolean(); i++) { try { Thread.sleep(10); } catch (InterruptedException e) { return; } shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20)); } };
        try {
            talk.attach((state, text) -> shown.add(state + ":" + text), then -> then.run());
            // What was typed goes on by voice.
            talk.start("[[\"user\",\"내일 부산 가\"],[\"assistant\",\"좋겠다! 조심히 다녀와\"]]");
            shadowOf(Looper.getMainLooper()).idle();
            assertEquals(1, ears.size());
            ears.get(0).ready(); ears.get(0).heard("뭐 챙겨 갈까");
            until[0] = () -> !asks.isEmpty(); settle.run();
            assertEquals(1, asks.size());
            assertTrue("the typed turns go along", asks.get(0).turns().stream().anyMatch(x -> x[1].equals("내일 부산 가")));
            assertNotNull("the user's own words may do a thing", asks.get(0).doIt());
            // A slow brain: one more "음…" once 3.5 s have gone by with no sound, and only once.
            talk.cued = "";
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BuddyVoice.STILL_MS + 200));
            assertEquals("think", talk.cued);
            talk.cued = ""; shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(BuddyVoice.STILL_MS + 200));
            assertEquals("once a turn", "", talk.cued);
            // Every sentence left out (BuddyMind.unasked): a little sound, and it listens on.
            asks.get(0).done().accept("");
            until[0] = () -> ears.size() == 2; settle.run();
            assertTrue(talk.on()); assertEquals("react", talk.cued); assertEquals(2, ears.size());

            // The brain fails: "다시 말해 줄래?", it listens on, and why is kept.
            brain[0] = "fail";
            ears.get(1).ready(); ears.get(1).heard("부산 날씨 어때");
            until[0] = () -> ears.size() == 3; settle.run();
            assertTrue("one failure does not end it", talk.on()); assertEquals("again", talk.cued); assertEquals(3, ears.size());
            assertEquals("음성 대화 두뇌", BuddyMisses.list(app).get(0).what());
            assertTrue(BuddyMisses.list(app).get(0).why(), BuddyMisses.list(app).get(0).why().matches("Groq HTTP 429 \\(\\d\\d:\\d\\d\\)"));
            assertTrue(app.store().phoneLogText().contains("두뇌가 답하지 못함 (Groq HTTP 429)"));
            // Twice in a row: the talk ends, saying so.
            ears.get(2).ready(); ears.get(2).heard("부산 날씨 어때");
            until[0] = () -> !talk.on(); settle.run();
            assertFalse(talk.on());
            assertEquals("error:지금은 대답이 안 떠올라. 조금 있다 다시 불러 줘", shown.get(shown.size() - 1));
            assertEquals(2, BuddyMisses.list(app).size());

            // Called again five minutes later: it goes on from that talk.
            brain[0] = "wait"; asks.clear();
            talk.endedAt = android.os.SystemClock.elapsedRealtime() - 5 * 60_000L;
            talk.start();
            until[0] = () -> ears.size() == 4; settle.run();
            ears.get(3).ready(); ears.get(3).heard("다시 왔어");
            until[0] = () -> !asks.isEmpty(); settle.run();
            assertTrue("five minutes on, the talk goes on", asks.get(0).turns().stream().anyMatch(x -> x[1].equals("뭐 챙겨 갈까")));
            // Fifteen minutes on, afresh.
            talk.stop(); asks.clear();
            talk.endedAt = android.os.SystemClock.elapsedRealtime() - 15 * 60_000L;
            talk.start();
            until[0] = () -> ears.size() == 5; settle.run();
            ears.get(4).ready(); ears.get(4).heard("안녕");
            until[0] = () -> !asks.isEmpty(); settle.run();
            assertTrue("fifteen minutes on, afresh", asks.get(0).turns().isEmpty());

            // Another's voice (VoicePrint: STRANGER): the talk so far is not theirs, and their words do nothing on the phone.
            talk.stop(); asks.clear();
            talk.start("[[\"user\",\"내 비밀 일기 썼어\"]]");
            shadowOf(Looper.getMainLooper()).idle();
            var think = BuddyVoice.class.getDeclaredMethod("think", String.class, boolean.class); think.setAccessible(true);
            think.invoke(talk, "안녕, 반가워", true);
            until[0] = () -> !asks.isEmpty(); settle.run();
            BuddyTalk.Ask guest = asks.get(0);
            assertTrue(guest.guest()); assertNull("a guest's turn never gets a doIt", guest.doIt()); assertTrue("nor the user's talk", guest.turns().isEmpty());
        } finally { talk.stop(); BuddyVoice.ears = before; BuddyVoice.brain = was; }
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
