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
import org.json.JSONObject;
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
        var models = BrainPicker.class.getDeclaredMethod("models", Activity.class, Ui.class, Runnable.class, String.class); models.setAccessible(true);
        models.invoke(null, a, new Ui(a), (Runnable) () -> {}, dev.jun.gyeot.core.WorkBook.GROQ); shadowOf(Looper.getMainLooper()).idle();
        Dialog list = ShadowDialog.getLatestDialog(); assertNotSame(menu, list);
        View l = list.getWindow().getDecorView();
        assertNotNull("the count", text(l, "모델 4개"));
        assertNotNull("GPT OSS 120B listed", find(l, v -> v instanceof TextView t && t.getText().toString().contains("GPT OSS 120B")));
        assertNotNull("Qwen listed", find(l, v -> v instanceof TextView t && t.getText().toString().toLowerCase().contains("qwen")));
        shot(l, "buddy_brain_models");
    }

    /** A voice that ends going up is a question (준: "음악소리들려? 라고 말하면 음악소리들려로 알아들어"); one going down is not. */
    /** 준: "퍼레 대화는 간단한 잡담인데 설정 인풋이 많나보네": chitchat goes with one line, not the whole list; "more" asks again. */
    @Test public void chitchatGoesLight() {
        assertTrue("one line, not the whole list (" + BuddyPrompt.ACTS_LITE.length() + " vs " + BuddyPrompt.ACTS.length() + ")", BuddyPrompt.ACTS_LITE.length() * 10 < BuddyPrompt.ACTS.length());
        assertTrue(BuddyPrompt.wantsMore("할일: {\"tool\":\"more\"}"));
        assertTrue(BuddyPrompt.wantsMore("할일 {tool: more}"));
        assertTrue(BuddyPrompt.wantsMore("{\"tool\": \"more\"}"));
        assertFalse(BuddyPrompt.wantsMore("응! 오늘은 더 놀자 ㅎㅎ"));
        assertFalse(BuddyPrompt.wantsMore("알겠어!\n할일: {\"tool\":\"alarm\",\"hour\":7}"));
    }
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
        assertEquals(55, BuddyTools.TOOLS.length());
        // Every tool once, with its Korean description (준: "시킬 수 있는 목록 … 아직 부족").
        java.util.Set<String> names = new java.util.HashSet<>();
        for (int i = 0; i < BuddyTools.TOOLS.length(); i++) {
            org.json.JSONObject f = BuddyTools.TOOLS.getJSONObject(i).getJSONObject("function");
            assertTrue("once: " + f.getString("name"), names.add(f.getString("name")));
            assertTrue(f.getString("name"), f.getString("description").codePoints().anyMatch(c -> c >= 0xAC00 && c <= 0xD7A3));
        }
        for (String t : new String[]{"stopwatch", "show_timers", "calendar_view", "memo", "recorder", "app_settings", "downloads", "gallery", "contacts", "calculator", "email", "translate",
                "coin", "dice", "pick", "world_time", "count_days", "remember", "forget", "call_me", "quiet", "phone_job", "web_job", "work_job", "schedule_job", "research_job", "day_recall", "when_event"}) {
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
        // 문 하나: a job 퍼레 has a tool for, sent elsewhere by the answer (the quick model's "작업으로 맡기기를 눌러 줘"), is asked again.
        assertTrue(BuddyTools.promised("이번 달 카드 지출 분석해서 보고서 써 줘", "작업으로 맡기기를 눌러 줘 📝"));
        assertTrue(BuddyTools.promised("유튜브 스튜디오에 어제 만든 영상 올려 줘", "영상 올리는 건 내가 직접 할 수 없어 😥"));
        assertTrue(BuddyTools.promised("할 일 관리 앱 코드 짜 줘", "작업방에 할 일 관리 앱 만드는 거 맡길게 💻"));
        assertFalse("small talk stays small talk", BuddyTools.promised("심심해", "나도 그런 건 못 해 ㅎㅎ"));
        // 하루 타임라인: the past answered without day_recall (a guess, or "기억이 안 나") is asked again with a tool.
        assertTrue(BuddyTools.promised("지난주 그 집 어디였지?", "을지로 노가리집이었던 것 같아!"));
        assertTrue(BuddyTools.promised("어제 뭐 했더라?", "음, 기억이 잘 안 나네"));
        assertFalse("not the past", BuddyTools.promised("어제 비 왔어?", "응, 조금 왔어"));
        assertTrue("in the rule", BuddyTools.RULE.contains("day_recall") && BuddyTools.rule(false).contains("day_recall"));
        assertEquals("", BuddyTools.say(BuddyTalk.loose("{\"tool\":\"day_recall\",\"query\":\"그 집\"}")));
        assertEquals("the brains' names for it", "day_recall", BuddyTalk.loose("{\"tool\":\"timeline\",\"what\":\"그 집\"}").optString("tool"));
        assertEquals("its words as they name them", "그 집", BuddyTalk.loose("{\"tool\":\"recall\",\"what\":\"그 집\"}").optString("query"));
        // 사건 트리거: "…하면 알려 줄게" with no tool called is asked again with one required; small talk is not.
        assertTrue(BuddyTools.promised("집에 도착하면 알려 줘", "응, 도착하면 알려 줄게!")); assertTrue(BuddyTools.promised("차 타면 매번 지도 켜 줘", "알겠어, 걸어 둘게!"));
        assertTrue(BuddyTools.promised("내 조건 알림 뭐 있어?", "걸어 둔 건 두 개야, 알려 줄게"));
        assertFalse(BuddyTools.promised("비 오면 어떡하지", "우산 꼭 챙겨!"));
        assertTrue("in the rule: when_event", BuddyTools.RULE.contains("when_event") && BuddyTools.rule(false).contains("when_event") && BuddyTalk.ACTS.contains("when_event"));
        assertEquals("", BuddyTools.say(BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"집에 도착하면\"}")));
        for (String job : new String[]{"phone_job", "web_job", "work_job", "schedule_job", "research_job"}) assertTrue("in the rule: " + job, BuddyTools.RULE.contains(job) && BuddyTools.rule(false).contains(job));
        assertFalse("the rule never quotes the words not to say (the quick model said them back)", BuddyTools.RULE.contains("작업으로 맡기기") || BuddyTalk.ACTS.contains("작업으로 맡기기"));
        for (int i = 0; i < BuddyTools.TOOLS.length(); i++) assertEquals("function", BuddyTools.TOOLS.getJSONObject(i).getString("type"));
        // 준: "퍼레의 판단력" — 퍼레's own tools go only with words about 퍼레 (lighter turns), and a forced retry may choose nothing.
        java.util.Set<String> phone = new java.util.HashSet<>(), own = new java.util.HashSet<>();
        for (int i = 0; i < BuddyTools.PHONE.length(); i++) phone.add(BuddyTools.PHONE.getJSONObject(i).getJSONObject("function").getString("name"));
        for (int i = 0; i < BuddyTools.SELF.length(); i++) own.add(BuddyTools.SELF.getJSONObject(i).getJSONObject("function").getString("name"));
        assertEquals(java.util.Set.of("buddy_toy", "buddy_outfit", "buddy_move", "buddy_wish", "remember", "forget", "call_me", "quiet"), own);
        assertEquals("each tool in one set", 55, phone.size() + own.size());
        java.util.Set<String> both = new java.util.HashSet<>(phone); both.retainAll(own); assertTrue(both.isEmpty());
        for (String answer : new String[]{"coin", "dice", "pick", "calculator", "world_time", "count_days", "stopwatch", "day_recall"}) assertTrue("an answer that needs no app goes every turn: " + answer, phone.contains(answer));
        org.json.JSONArray light = BuddyTools.forTurn("손전등 켜 줘");
        assertEquals(47, light.length());
        // 퍼레's eight carry its long lists of names (toys, clothes, plays): about a seventh of all the tools' words (every phone tool
        // added, such as when_event, makes that share smaller; the eight still stay out of a turn not about 퍼레).
        assertTrue("lighter: " + light.toString().length() + " of " + BuddyTools.TOOLS.toString().length(), light.toString().length() < 0.86 * BuddyTools.TOOLS.toString().length());
        assertFalse(BuddyTools.rule(false).contains("buddy_toy")); assertTrue(BuddyTools.rule(true).contains("buddy_toy")); assertEquals(BuddyTools.RULE, BuddyTools.rule(true));
        for (String about : new String[]{"고양이 귀 해 봐", "춤춰 봐", "퍼레야, 주사위 꺼내 봐", "나 내일 시험이야 기억해 줘", "이제 형이라고 불러", "한 시간만 조용히 해 줘", "비눗방울 불어 줘"})
            assertTrue("about 퍼레: " + about, BuddyTools.self(about));
        assertTrue(BuddyTools.forTurn("고양이 귀 해 봐").toString().contains("\"buddy_outfit\"")); assertTrue(BuddyTools.forTurn("춤춰 봐").toString().contains("\"buddy_move\""));
        for (String phoneOnly : new String[]{"손전등 켜 줘", "카메라 켜 줘", "노래 틀어 줘", "내일 일정 뭐야", "동전 던져 줘", "네이버 지도 열어 줘", "소리 좀 줄여 줘"})
            assertFalse("not about 퍼레: " + phoneOnly, BuddyTools.self(phoneOnly));
        org.json.JSONArray nothing = new org.json.JSONArray().put(new org.json.JSONObject().put("name", "nothing").put("arguments", "{}"));
        assertNull("nothing is no act", BuddyTools.act(nothing)); assertTrue(BuddyTools.nothing(nothing)); assertFalse(BuddyTools.nothing(new org.json.JSONArray()));
        org.json.JSONArray retry = BuddyTools.orNothing();
        assertEquals(56, retry.length()); assertEquals("nothing", retry.getJSONObject(55).getJSONObject("function").getString("name"));
        assertEquals("the full list stays as it was", 55, BuddyTools.TOOLS.length());
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
        assertEquals("‘고양이를 좋아한대요’ 잊었어.", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"what\":\"고양이\"}"), "고양이 얘기 잊어 줘", false).say());
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
    /**
     * "기억해 둬", "잊어 줘", "그건 틀렸어 지워" by voice (준: "퍼레의 학습기능 … 강화해줘"): kept as the user's own and as mattering
     * most, let go with the night's notes on it and said back, not learned again by itself for ninety days, back on "되돌려 줘";
     * a private one only after the fingerprint; the words send 퍼레's own tools.
     */
    @Test public void remembersAndForgetsByVoice() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        BuddyMisses.clear(app); BuddyTalk.forgetAll(app);
        JSONObject keep = BuddyTalk.loose("{\"tool\":\"remember\",\"text\":\"나 고양이 키워\"}");
        assertEquals("기억해 둘게!", BuddyTools.say(keep));
        BuddyActs.run(app, keep, "나 고양이 키워 기억해 둬", false);
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> m = BuddyTalk.memories(app);
        assertEquals(1, m.size()); assertEquals("fact", m.get(0).kind());
        assertEquals("the user's own", "사용자는 고양이 키워", m.get(0).text()); assertEquals("asked to be kept: it matters most", 3, m.get(0).weight());
        assertEquals("그건 벌써 기억하고 있어!", BuddyActs.run(app, keep, "고양이 키운다고 기억해", false).say());
        assertEquals("told again", 2, BuddyTalk.memories(app).get(0).seen());
        // Let go, with the night's note on the same thing, and said back as it was known.
        BuddyTalk.rememberStrong(app, "fact", "사용자는 커피를 좋아한대", false);
        BuddyTalk.rememberStrong(app, "fact", "사용자는 내일 부산에 간대", false);
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> withNote = BuddyTalk.memories(app);
        withNote.add(new dev.jun.gyeot.core.BuddyMemory.Entry(System.currentTimeMillis() + 5, "profile", "아침마다 커피를 마셔요", 3, false));
        BuddyTalk.restore(app, withNote);
        JSONObject about = BuddyTalk.loose("{tool:forget,about:커피}");
        assertEquals("forget", about.optString("tool")); assertEquals("커피", about.optString("what"));
        // "커피 좋아한다는 거": the note says nothing of liking, yet it is on the same thing, so it goes too.
        String said = BuddyActs.run(app, BuddyTalk.loose("{tool:forget,about:커피 좋아한다는 거}"), "커피 좋아한다는 거 잊어 줘", false).say();
        assertTrue(said, said.contains("커피를 좋아한대") && said.contains("잊었어"));
        java.util.List<String> left = new java.util.ArrayList<>(); for (dev.jun.gyeot.core.BuddyMemory.Entry e : BuddyTalk.memories(app)) left.add(e.text());
        // (in any order: two kept in the same millisecond may come either way, as on CI's quick runner)
        assertEquals(java.util.Set.of("사용자는 고양이 키워", "사용자는 내일 부산에 간대"), new java.util.HashSet<>(left)); assertEquals(2, left.size());
        // Not learned again by itself (a talk, the night) for ninety days.
        long now = System.currentTimeMillis();
        java.util.Map<String, Long> forgot = BuddyTalk.forgot(app);
        assertTrue(forgot.toString(), dev.jun.gyeot.core.BuddyMind.forgotten("사용자는 커피를 매일 마신대", forgot, now));
        assertFalse(dev.jun.gyeot.core.BuddyMind.forgotten("사용자는 부산에 간대", forgot, now));
        assertFalse("ninety days on", dev.jun.gyeot.core.BuddyMind.forgotten("사용자는 커피를 매일 마신대", forgot, now + 91 * 86_400_000L));
        BuddyTalk.learned(app, java.util.List.of(), "새로: 사용자는 커피를 다시 마신대\n새로: 사용자는 등산을 좋아한대", "나 커피 다시 마셔. 등산도 좋아");
        left.clear(); for (dev.jun.gyeot.core.BuddyMemory.Entry e : BuddyTalk.memories(app)) left.add(e.text());
        assertTrue("the rest is learned: " + left, left.contains("사용자는 등산을 좋아한대"));
        assertFalse("not the subject let go: " + left, left.stream().anyMatch(t -> t.contains("커피")));
        // "되돌려 줘" within two minutes brings it back, and its subject may be learned again.
        assertEquals("다시 기억했어!", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"undo\":true}"), "방금 거 되돌려 줘", false).say());
        left.clear(); for (dev.jun.gyeot.core.BuddyMemory.Entry e : BuddyTalk.memories(app)) left.add(e.text());
        assertTrue(left.toString(), left.contains("사용자는 커피를 좋아한대") && left.contains("아침마다 커피를 마셔요"));
        assertFalse(dev.jun.gyeot.core.BuddyMind.forgotten("사용자는 커피를 매일 마신대", BuddyTalk.forgot(app), now));
        assertTrue(BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"undo\":true}"), "되돌려 줘", false).say().contains("되돌릴 게 없어"));
        // "그건 틀렸어 지워": the one it is about, by its topic.
        said = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"what\":\"부산 가는 거\"}"), "그건 틀렸어 지워", false).say();
        assertTrue(said, said.contains("부산"));
        // The words send 퍼레's own tools (all 48), and the fingerprint comes first for a private one.
        for (String w : new String[]{"그건 틀렸어 지워", "커피 얘기 잊어 줘", "이거 기억해 둬", "방금 거 되돌려 줘", "그거 까먹어"}) {
            assertTrue(w, BuddyTools.self(w)); assertEquals(w, BuddyTools.TOOLS.length(), BuddyTools.forTurn(w).length());
        }
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"remember\",\"fact\":\"사용자는 요즘 이직 생각이 많대\",\"private\":true}"), "이거 비밀로 기억해 둬", false);
        dev.jun.gyeot.core.BuddyMemory.Entry kept = null; for (dev.jun.gyeot.core.BuddyMemory.Entry e : BuddyTalk.memories(app)) if (e.text().contains("이직")) kept = e;
        assertNotNull(kept); assertTrue("private when asked", kept.secret());
        assertTrue(dev.jun.gyeot.core.BuddyMind.asksPersonal("이직 얘기 잊어 줘")); assertTrue(dev.jun.gyeot.core.BuddyMind.asksPersonal("그건 틀렸어 지워"));
        assertFalse("forgetting an umbrella is not a question about memories", dev.jun.gyeot.core.BuddyMind.asksPersonal("나 우산 잊어버렸어"));
        assertFalse(BuddyTalk.unlocked());
        assertTrue(BuddyTalk.needsUnlock(app, "이직 얘기 잊어 줘"));
        BuddyVoice.Outcome locked = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"what\":\"이직\"}"), "이직 얘기 잊어 줘", false);
        assertTrue(locked.say(), locked.say().contains("지문")); assertTrue(BuddyTalk.memories(app).contains(kept));
        BuddyTalk.unlock();
        try {
            BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"forget\",\"what\":\"이직\"}"), "이직 얘기 잊어 줘", false);
            assertFalse(BuddyTalk.memories(app).contains(kept));
        } finally { java.lang.reflect.Field f = BuddyTalk.class.getDeclaredField("unlockedUntil"); f.setAccessible(true); f.setLong(null, 0); }
        // The night's notes that go with it are on what the user named: "커피" takes the coffee note, not the one on friends
        // because the coffee was with a friend.
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> mixed = java.util.List.of(
            new dev.jun.gyeot.core.BuddyMemory.Entry(1, "fact", "사용자는 친구랑 커피를 마셨대", 2, false),
            new dev.jun.gyeot.core.BuddyMemory.Entry(2, "profile", "친구가 많아요", 3, false),
            new dev.jun.gyeot.core.BuddyMemory.Entry(3, "profile", "아침마다 커피를 마셔요", 3, false));
        assertEquals(java.util.List.of("사용자는 친구랑 커피를 마셨대", "아침마다 커피를 마셔요"),
            dev.jun.gyeot.core.BuddyMind.toForget(mixed, "커피").stream().map(dev.jun.gyeot.core.BuddyMemory.Entry::text).toList());
        // The text form knows the words for them too.
        assertEquals("forget", BuddyTalk.loose("{\"tool\":\"지워\",\"what\":\"커피\"}").optString("tool"));
        assertEquals("remember", BuddyTalk.loose("{\"tool\":\"buddy_remember\",\"text\":\"고양이\"}").optString("tool"));
        BuddyTalk.forgetAll(app);
    }
    /**
     * Memory that keeps what matters (준: "퍼레의 학습기능 … 강화해줘"): the routine counted a week to a line, the one that
     * matters least going when it is full, promises done, the night's digest merged into the note (and done only once it
     * answered), and memory.json read once while it does not change.
     */
    @Test public void keepsWhatMatters() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        BuddyTalk.forgetAll(app);
        java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        long day = 86_400_000L, monday = java.time.ZonedDateTime.of(2026, 11, 2, 9, 0, 0, 0, zone).toInstant().toEpochMilli();
        // A hundred things told, then two hundred routine moments over four weeks (Monday 2 to Sunday 29 November): every one
        // told stays, the routine is a line a kind a week.
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> list = new java.util.ArrayList<>();
        for (int i = 0; i < 100; i++) list.add(new dev.jun.gyeot.core.BuddyMemory.Entry(monday - (200 - i) * day, "fact", "사용자는 기억 " + i + "번을 말했대", 2, false));
        for (int i = 0; i < 200; i++) {
            boolean cold = i % 2 == 0;
            list = dev.jun.gyeot.core.BuddyMemory.tally(list, monday + i * 27 * day / 200, zone, cold ? "cold" : "away", cold, n -> (cold ? "추운 날 " : "돌아옴 ") + n + "번");
        }
        int told = 0, routine = 0, coldDays = 0;
        for (dev.jun.gyeot.core.BuddyMemory.Entry e : list) { if ("fact".equals(e.kind())) told++; else routine++; if ("cold".equals(e.kind())) coldDays += e.seen(); }
        assertEquals(100, told);
        assertEquals("a line a kind a week", 8, routine);
        assertEquals("a cold day counted once", 28, coldDays);
        // Full: what matters least goes, never one asked to be kept while a passing moment is there.
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> full = new java.util.ArrayList<>();
        full.add(new dev.jun.gyeot.core.BuddyMemory.Entry(monday - 400 * day, "fact", "사용자는 차를 지하 2층에 세웠대", 3, false));
        for (int i = 0; i < 119; i++) full.add(new dev.jun.gyeot.core.BuddyMemory.Entry(monday - (300 - i) * day, i % 2 == 0 ? "late" : "fact", "기억 " + i, i % 2 == 0 ? 1 : 2, false));
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> more = dev.jun.gyeot.core.BuddyMemory.add(full, monday, "fact", "사용자는 고양이를 좋아한대", 0, 2, false);
        assertEquals(dev.jun.gyeot.core.BuddyMemory.MOST, more.size());
        assertTrue("the oldest, asked to be kept", more.contains(full.get(0)));
        assertFalse("the oldest passing moment went", more.contains(full.get(1)));
        assertEquals("사용자는 고양이를 좋아한대", more.get(more.size() - 1).text());
        // The weather once a day, not every twelve hours of board builds.
        JSONObject freezing = new JSONObject().put("code", 0).put("temp", -8);
        BuddyTalk.weatherMemory(app, freezing, monday); BuddyTalk.weatherMemory(app, freezing, monday + 13 * 3_600_000L);
        assertEquals(1, BuddyTalk.memories(app).size()); assertEquals(1, BuddyTalk.memories(app).get(0).seen());
        BuddyTalk.weatherMemory(app, freezing, monday + day);
        assertEquals(1, BuddyTalk.memories(app).size());
        assertEquals("한 주 동안 영하로 꽁꽁 추운 날이 2번이었어", BuddyTalk.memories(app).get(0).text());
        // A promise done: kept for the memory book, never brought up again; in 퍼레's own lines once a day at most.
        BuddyTalk.forgetAll(app);
        BuddyTalk.rememberStrong(app, "promise", "퍼레가 내일 아침 날씨를 알려 주기로 했어", false);
        assertTrue(BuddyTalk.memoryWords(app, "", false, true, java.util.Set.of()).contains("날씨를 알려 주기로"));
        assertFalse("once a day in its own lines", BuddyTalk.memoryWords(app, "", false, true, java.util.Set.of()).contains("날씨를 알려 주기로"));
        assertTrue("an answer still knows it", BuddyTalk.memoryWords(app, "", true, true, java.util.Set.of()).contains("날씨를 알려 주기로"));
        java.util.List<dev.jun.gyeot.core.BuddyMind.Op> ops = dev.jun.gyeot.core.BuddyMind.parseOps("약속 끝 1");
        assertEquals(dev.jun.gyeot.core.BuddyMind.Change.DONE, ops.get(0).change());
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> promised = BuddyTalk.memories(app);
        BuddyTalk.learned(app, promised, "약속 끝 1", "날씨 알려 줘서 고마워");
        assertTrue(BuddyTalk.memories(app).get(0).done());
        assertTrue("kept as done", dev.jun.gyeot.core.BuddyMemory.read(dev.jun.gyeot.core.BuddyMemory.save(BuddyTalk.memories(app))).get(0).done());
        assertFalse(BuddyTalk.memoryWords(app, "", true, true, java.util.Set.of()).contains("날씨를 알려 주기로"));
        assertTrue(dev.jun.gyeot.core.BuddyMind.related(BuddyTalk.memories(app), "내일 아침 날씨", 3, false).isEmpty());
        // The night's digest: a list under its heading, merged into the note (kept unless taken out), a guess matters least.
        dev.jun.gyeot.core.BuddyMind.Dream d = dev.jun.gyeot.core.BuddyMind.dream("프로필:\n- a\n- b\n추억: 없음");
        assertEquals(java.util.List.of("a", "b"), d.profile()); assertEquals("", d.episode());
        long t0 = monday - 30 * day;
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> note = java.util.List.of(
            new dev.jun.gyeot.core.BuddyMemory.Entry(t0, "profile", "평일 아침 8시쯤 지도 앱을 열어요", 3, false),
            new dev.jun.gyeot.core.BuddyMemory.Entry(t0 + 1, "profile", "고양이를 좋아해요", 3, false));
        dev.jun.gyeot.core.BuddyMind.Dream night = dev.jun.gyeot.core.BuddyMind.dream("프로필:\n- 주말엔 등산을 가요\n- 강아지를 키우는 것 같아요 (추측)\n- 커피를 매일 마셔요\n빼기: 고양이를 좋아해요\n추억: 비 오는 날 같이 음악 들었어");
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> merged = dev.jun.gyeot.core.BuddyMind.mergeProfile(note, night, monday, java.util.Map.of("커피", monday + day));
        java.util.Map<String, Integer> weights = new java.util.HashMap<>(); for (dev.jun.gyeot.core.BuddyMemory.Entry e : merged) weights.put(e.text(), e.weight());
        assertTrue("an old line it did not take out stays: " + weights, weights.containsKey("평일 아침 8시쯤 지도 앱을 열어요"));
        assertFalse("taken out", weights.containsKey("고양이를 좋아해요"));
        assertEquals(Integer.valueOf(3), weights.get("주말엔 등산을 가요"));
        assertEquals("a guess matters least", Integer.valueOf(1), weights.get("강아지를 키우는 것 같아요 (추측)"));
        assertFalse("nothing on a subject let go", weights.keySet().stream().anyMatch(k -> k.contains("커피")));
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> many = new java.util.ArrayList<>();
        for (int i = 0; i < 12; i++) many.add(new dev.jun.gyeot.core.BuddyMemory.Entry(t0 + i, "profile", "노트 " + (char) ('가' + i * 300) + " 줄", 3, false, i == 0 ? 4 : 1, t0 + i));
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> capped = dev.jun.gyeot.core.BuddyMind.mergeProfile(many, dev.jun.gyeot.core.BuddyMind.dream("프로필: 새로 안 것 하나\n프로필: 새로 안 것 둘"), monday, java.util.Map.of());
        assertEquals("twelve lines at most", 12, capped.size()); assertTrue("the one told most stays", capped.contains(many.get(0)));
        // A digest that failed leaves the day to be tried again, two hours on; one that answered is merged and the day done.
        BuddyTalk.Night was = BuddyTalk.night;
        android.content.SharedPreferences prefs = app.getSharedPreferences("buddyTalk", android.content.Context.MODE_PRIVATE);
        try {
            BuddyTalk.forgetAll(app);
            BuddyTalk.restore(app, note);
            java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> old = new java.util.ArrayList<>(BuddyTalk.memories(app));
            old.add(new dev.jun.gyeot.core.BuddyMemory.Entry(monday - 90 * day, "fact", "사용자는 바다를 좋아한대", 2, false, 3, monday - 60 * day));
            BuddyTalk.restore(app, old);
            java.util.List<String> asked = new java.util.ArrayList<>();
            BuddyTalk.night = (c, instructions, input, done, failed) -> { asked.add(input); failed.run(); return true; };
            java.time.ZonedDateTime three = java.time.ZonedDateTime.of(2026, 11, 3, 3, 0, 0, 0, zone);
            BuddyTalk.dream(app, false, false, three);
            assertEquals(1, asked.size()); assertEquals("not done when it failed", "", prefs.getString("dreamt", ""));
            assertTrue("what was told again and again, however old: " + asked.get(0), asked.get(0).contains("사용자는 바다를 좋아한대"));
            BuddyTalk.dream(app, false, false, three.plusMinutes(40));
            assertEquals("tried again two hours on", 1, asked.size());
            BuddyTalk.night = (c, instructions, input, done, failed) -> { asked.add(input); done.accept("프로필:\n- 주말엔 등산을 가요\n추억: 없음"); return true; };
            BuddyTalk.dream(app, false, false, three.plusHours(2));
            assertEquals(2, asked.size()); assertEquals("2026-11-03", prefs.getString("dreamt", ""));
            java.util.List<String> texts = new java.util.ArrayList<>(); for (dev.jun.gyeot.core.BuddyMemory.Entry e : BuddyTalk.memories(app)) texts.add(e.text());
            assertTrue(texts.toString(), texts.contains("주말엔 등산을 가요") && texts.contains("평일 아침 8시쯤 지도 앱을 열어요") && texts.contains("고양이를 좋아해요"));
            BuddyTalk.dream(app, false, false, three.plusHours(2).plusMinutes(30));
            assertEquals("once a day", 2, asked.size());
        } finally { BuddyTalk.night = was; prefs.edit().remove("dreamt").remove("dreamTry").commit(); }
        // Two kept in the same moment are two memories (at names one): the night's first note line and the day, two told in
        // one millisecond; the memory book's delete of one keeps the other.
        BuddyTalk.forgetAll(app);
        long same = System.currentTimeMillis();
        BuddyTalk.dreamt(app, dev.jun.gyeot.core.BuddyMind.dream("프로필: 주말엔 등산을 가요\n추억: 비 오는 날 같이 음악 들었어"), same);
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> night2 = BuddyTalk.memories(app);
        assertEquals(2, night2.size()); assertNotEquals("the note line and the day", night2.get(0).at(), night2.get(1).at());
        dev.jun.gyeot.core.BuddyMemory.Entry day2 = night2.stream().filter(e -> "episode".equals(e.kind())).findFirst().orElseThrow();
        BuddyTalk.forget(app, day2.at());
        assertEquals(java.util.List.of("주말엔 등산을 가요"), BuddyTalk.memories(app).stream().map(dev.jun.gyeot.core.BuddyMemory.Entry::text).toList());
        BuddyTalk.forgetAll(app);
        BuddyTalk.rememberStrong(app, "fact", "사용자는 귤을 좋아한대", false); BuddyTalk.rememberStrong(app, "fact", "사용자는 수영을 배운대", false);
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> both = BuddyTalk.memories(app);
        assertEquals(2, both.size()); assertNotEquals("told in one millisecond", both.get(0).at(), both.get(1).at());
        // memory.json read once while it does not change; again when it does.
        java.io.File file = new java.io.File(new java.io.File(app.getFilesDir(), "buddy"), "memory.json");
        java.nio.file.Files.writeString(file.toPath(), dev.jun.gyeot.core.BuddyMemory.save(note));
        int reads = BuddyTalk.memoryReads;
        assertEquals(2, BuddyTalk.memories(app).size()); BuddyTalk.memories(app); BuddyTalk.life(app); BuddyTalk.needsUnlock(app, "내 고민 기억나?");
        assertEquals("read once", reads + 1, BuddyTalk.memoryReads);
        java.nio.file.Files.writeString(file.toPath(), dev.jun.gyeot.core.BuddyMemory.save(note.subList(0, 1)));
        assertEquals("read again once it changed", 1, BuddyTalk.memories(app).size());
        BuddyTalk.forgetAll(app);
    }
    /**
     * A voice that fits the moment (준: "음성상호작용을 더 강화해줘"): Typecast's emotion and tempo from the line and what the user
     * said, never quick or high late at night, only what the voice can do; calm "음…" sounds for a user who is down.
     */
    @Test public void tonesFitTheMoment() throws Exception {
        java.util.List<String> all = dev.jun.gyeot.core.VoiceTone.ALL;
        dev.jun.gyeot.core.VoiceTone.Tone down = dev.jun.gyeot.core.VoiceTone.of("나 오늘 너무 속상해", "그랬구나, 무슨 일 있었어?", 14, all);
        assertEquals("sad", down.preset()); assertEquals(0.8, down.intensity(), 1e-9); assertTrue("calm sounds too", down.calm());
        dev.jun.gyeot.core.VoiceTone.Tone news = dev.jun.gyeot.core.VoiceTone.of("나 합격했어!", "우와 축하해!", 14, all);
        assertEquals("happy", news.preset()); assertEquals(1.3, news.intensity(), 1e-9); assertFalse(news.calm());
        dev.jun.gyeot.core.VoiceTone.Tone time = dev.jun.gyeot.core.VoiceTone.of("지금 몇 시야", "오후 세 시야", 14, all);
        assertEquals("normal", time.preset()); assertEquals(1.0, time.intensity(), 1e-9);
        dev.jun.gyeot.core.VoiceTone.Tone usual = dev.jun.gyeot.core.VoiceTone.of("심심해", "그럼 영화 볼래?", 14, all);
        assertEquals("happy", usual.preset()); assertEquals(1.1, usual.intensity(), 1e-9); assertEquals(1.05, usual.tempo(), 1e-9);
        // The line's own faces first: a crying one low, 헉 high (not late at night: never quick or high then).
        assertEquals("sad", dev.jun.gyeot.core.VoiceTone.of("", "우산이 젖었어 ㅠㅠ", 14, all).preset());
        assertEquals("sad", dev.jun.gyeot.core.VoiceTone.of("", "그랬구나 \uD83E\uDD7A", 14, all).preset());
        assertEquals("toneup", dev.jun.gyeot.core.VoiceTone.of("", "헉, 진짜?", 14, all).preset());
        assertEquals("normal", dev.jun.gyeot.core.VoiceTone.of("", "헉, 진짜?", 1, all).preset());
        assertEquals(1.0, dev.jun.gyeot.core.VoiceTone.of("심심해", "그럼 영화 볼래?", 1, all).tempo(), 1e-9);
        assertEquals(1.05, dev.jun.gyeot.core.VoiceTone.of("심심해", "그럼 영화 볼래?", 6, all).tempo(), 1e-9);
        assertNotEquals("아파트 is not 아파", "sad", dev.jun.gyeot.core.VoiceTone.of("아파트 시세", "알아볼게!", 14, all).preset());
        // Only what the voice can do.
        assertEquals("normal", dev.jun.gyeot.core.VoiceTone.of("나 오늘 너무 속상해", "", 14, java.util.List.of("normal", "happy")).preset());
        assertEquals("tonedown", dev.jun.gyeot.core.VoiceTone.of("나 오늘 너무 속상해", "", 14, java.util.List.of("normal", "tonedown")).preset());
        assertEquals("normal", dev.jun.gyeot.core.VoiceTone.of("심심해", "", 14, java.util.List.of()).preset());
        // What goes to Typecast.
        JSONObject voice = new JSONObject().put("id", "tc_test").put("emotions", new org.json.JSONArray(java.util.List.of("normal", "happy", "sad")));
        JSONObject sad = BuddyVoice.request(voice, "괜찮아", "wav", down);
        assertEquals("sad", sad.getJSONObject("prompt").getString("emotion_preset"));
        assertEquals(0.8, sad.getJSONObject("prompt").getDouble("emotion_intensity"), 1e-9);
        assertEquals(1.05, sad.getJSONObject("output").getDouble("audio_tempo"), 1e-9);
        JSONObject made = BuddyVoice.request(voice, "응!", "mp3", BuddyVoice.usual(voice));
        assertEquals("its short words as always", "happy", made.getJSONObject("prompt").getString("emotion_preset"));
        assertEquals(1.1, made.getJSONObject("prompt").getDouble("emotion_intensity"), 1e-9);
        assertEquals("plain when the voice has no happy", "normal", BuddyVoice.usual(new JSONObject().put("id", "x")).preset());
        // Calm "음…" for a user who is down: never "오, 좋아!" or "어머나!".
        assertTrue(dev.jun.gyeot.core.VoiceTone.calm("나 오늘 너무 속상해", 14)); assertFalse(dev.jun.gyeot.core.VoiceTone.calm("심심해", 14));
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().settings.edit().putString("typecastVoice", "{\"id\":\"tc_test\"}").commit();
        java.io.File dir = VoicePresets.dir(app, "tc_test"); dir.mkdirs();
        for (int i = 0; i < VoicePresets.LINES.length; i++) java.nio.file.Files.write(new java.io.File(dir, i + ".mp3").toPath(), new byte[]{1});
        for (String w : VoicePresets.CALM) assertTrue(w, java.util.Arrays.stream(VoicePresets.LINES).anyMatch(l -> l[0].equals("think") && l[1].equals(w)));
        java.util.Set<String> picked = new java.util.HashSet<>();
        for (int k = 0; k < 14; k++) { String w = VoicePresets.words(VoicePresets.pick(app, app.store(), "think", true)); assertTrue(w, VoicePresets.CALM.contains(w)); picked.add(w); }
        assertTrue("not one alone: " + picked, picked.size() >= 4);
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
        assertEquals("launcher.self({\"wear\":[\"catears\",\"xscarf\"]})", BuddyBoard.call(wear));
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
    /**
     * Food on its way (준, 2026-11-27: "배민에서 배달한걸 못알아채네", the status bar chip said 조리완료): a delivery app's notice read as
     * its stage and when it comes; ads, other apps and a rider at the shop are not "delivered".
     */
    @Test public void readsFoodOnItsWay() {
        java.time.ZoneId seoul = java.time.ZoneId.of("Asia/Seoul");
        long at = java.time.ZonedDateTime.of(2026, 11, 27, 17, 55, 0, 0, seoul).toInstant().toEpochMilli();
        java.util.function.Function<String, dev.jun.gyeot.core.Deliveries.Delivery> read = words -> dev.jun.gyeot.core.Deliveries.read("com.sampleapp", "배달의민족", "", words, "", at, seoul);
        dev.jun.gyeot.core.Deliveries.Delivery ready = dev.jun.gyeot.core.Deliveries.read("com.sampleapp", "배달의민족", "", "조리완료 조리가 완료되었어요. 라이더님이 곧 픽업해요", "조리완료", at, seoul);
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.READY, ready.stage()); assertEquals("배민", ready.name());
        assertEquals("배달 출발했대, 6:52쯤 온대 🛵", dev.jun.gyeot.core.Deliveries.line(read.apply("라이더님이 음식을 픽업했어요. 6:52 도착 예정"), seoul));
        dev.jun.gyeot.core.Deliveries.Delivery ordered = read.apply("주문이 접수되었어요. 오후 6:40 도착 예정이에요");
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.ORDERED, ordered.stage());
        assertEquals("오후 6:40", dev.jun.gyeot.core.Deliveries.clock(ordered.eta(), seoul));
        assertEquals("오후 6:52", dev.jun.gyeot.core.Deliveries.clock(read.apply("라이더님이 음식을 픽업했어요. 6:52 도착 예정").eta(), seoul));
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.ON_WAY, read.apply("라이더님이 음식을 픽업했어요. 6:52 도착 예정").stage());
        assertEquals(at + 35 * 60_000L, read.apply("가게에서 주문을 접수했어요. 30~40분 후 도착해요").eta());
        assertEquals(at + 12 * 60_000L, dev.jun.gyeot.core.Deliveries.read("com.sampleapp", "배달의민족", "", "배달 중", "12분", at, seoul).eta());
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.COOKING, read.apply("가게에서 음식을 조리하고 있어요. 조리 중").stage());
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.COOKING, read.apply("라이더님이 가게에 도착했어요").stage());
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.NEAR, read.apply("음식이 곧 도착해요").stage());
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.DONE, read.apply("배달이 완료되었어요. 맛있게 드세요!").stage());
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.CANCELED, read.apply("주문이 취소되었어요").stage());
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.ON_WAY, dev.jun.gyeot.core.Deliveries.read("com.coupang.mobile.eats", "쿠팡이츠", "", "배달 파트너가 음식을 픽업했습니다", "", at, seoul).stage());
        assertNull("an ad", read.apply("(광고) 오늘만 배민 쿠폰 3천원 할인! 첫 주문 완료 시 지급"));
        assertNull("not an order", read.apply("이번 주 인기 메뉴를 확인해 보세요"));
        assertNull("not a delivery app", dev.jun.gyeot.core.Deliveries.read("com.kakao.talk", "카카오톡", "엄마", "배달 완료됐대", "", at, seoul));
        dev.jun.gyeot.core.Deliveries.Delivery done = read.apply("배달이 완료되었어요");
        assertNotNull(dev.jun.gyeot.core.Deliveries.current(done, at + 30 * 60_000L));
        assertNull("40 minutes after it came", dev.jun.gyeot.core.Deliveries.current(done, at + 41 * 60_000L));
        assertNotNull(dev.jun.gyeot.core.Deliveries.current(ordered, at + 2 * 3_600_000L));
        assertNull("three quiet hours", dev.jun.gyeot.core.Deliveries.current(ordered, at + 3 * 3_600_000L + 1));
        assertEquals(dev.jun.gyeot.core.Deliveries.Stage.READY, dev.jun.gyeot.core.Deliveries.of(ready.json()).stage());
        assertEquals("배민 배달: 조리 완료(라이더 기다리는 중) (3분 전 알림)", dev.jun.gyeot.core.Deliveries.words(ready, at + 3 * 60_000L, seoul));
    }
    /**
     * 마이튜브의 그림 (준: "유튜브 이미지로 정리가 세로모드 밖에 안돼", "너무 간단하게 요약하는 것 밖에 안돼. 간단 노멀 상세를 설정 가능하게"):
     * the shape set goes to the plan (and a wide one is laid out in columns), and the text to show grows with the amount set.
     */
    @Test public void picturesOfAVideoTakeTheShapeAndAmountSet() {
        String brief = dev.jun.gyeot.core.VideoChat.pictures(1, "portrait", "brief");
        assertTrue("간단 is the handful of labels it was", brief.contains("5 to 8 of them") && brief.contains("\"aspect\": \"portrait\""));
        String normal = dev.jun.gyeot.core.VideoChat.pictures(1, "portrait", "normal"), full = dev.jun.gyeot.core.VideoChat.pictures(1, "portrait", "full");
        assertTrue(normal.contains("10 to 16 pieces of text") && !normal.contains("5 to 8 of them"));
        assertTrue(full.contains("22 to 32 pieces of text") && full.contains("exact numbers, names, steps"));
        assertEquals("노멀 by default", normal, dev.jun.gyeot.core.VideoChat.pictures(1));
        String wide = dev.jun.gyeot.core.VideoChat.pictures(1, "landscape", "normal");
        assertTrue(wide.contains("\"aspect\": \"landscape\"") && wide.contains("aspect is landscape (the shape the user set)") && wide.contains("use the width"));
        assertFalse(wide.contains("\"aspect\": \"portrait\""));
        assertTrue(dev.jun.gyeot.core.VideoChat.pictures(1, "16:9", "full").contains("\"aspect\": \"16:9\""));
        assertTrue(dev.jun.gyeot.core.VideoChat.pictures(1, "square", "brief").contains("a grid or a centered layout"));
        // Colors are never limited, the layout follows the content, and the way to draw turns (준: "색상이나 스타일이 비슷하게 나오는 경향",
        // "내가 직접 요청해도 비슷한 느낌", "색상을 제한하지 말아줘").
        for (String d : new String[]{"brief", "normal", "full"}) {
            String any = dev.jun.gyeot.core.VideoChat.pictures(1, "portrait", d, "a risograph print");
            assertFalse("no fixed colors: " + d, any.contains("three or four colors") || any.contains("white paper"));
            assertTrue(any.contains("no fixed palette") && any.contains("not always a title over a row of equal boxes") && any.contains("This time draw it as a risograph print"));
        }
        assertFalse("no look, no line", normal.contains("This time draw it as"));
        java.util.Set<String> comics = new java.util.HashSet<>(), notes = new java.util.HashSet<>(), free = new java.util.HashSet<>();
        for (int t = 0; t < 8; t++) {
            comics.add(dev.jun.gyeot.core.VideoChat.look("영상 내용을 4컷 만화로 그려줘", t));
            notes.add(dev.jun.gyeot.core.VideoChat.look("대본을 요약해서 그래픽 레코딩 스타일로 그려줘", t));
            free.add(dev.jun.gyeot.core.VideoChat.look("비트코인 형님 한번만 살려주세요 느낌으로 그려줘", t));
        }
        assertTrue("each turn another way: " + comics + notes + free, comics.size() == 8 && notes.size() == 8 && free.size() == 8);
        assertTrue(comics.contains("a Korean webtoon") && notes.contains("chalk notes on a blackboard") && !free.contains("chalk notes on a blackboard"));
        assertEquals("in turn, then again", dev.jun.gyeot.core.VideoChat.look("인포그래픽으로", 0), dev.jun.gyeot.core.VideoChat.look("인포그래픽으로", 9));
        assertTrue("an unknown shape is 세로", dev.jun.gyeot.core.VideoChat.pictures(1, "banana", "normal").contains("\"aspect\": \"portrait\""));
        String three = dev.jun.gyeot.core.VideoChat.pictures(3, "landscape", "full");
        assertTrue(three.contains("{\"pictures\": [{\"prompt\": \"...\", \"aspect\": \"landscape\"}, ...]} with exactly 3 items") && three.contains("22 to 32 pieces of text"));
        for (String[] a : dev.jun.gyeot.core.VideoChat.ASPECTS)
            assertEquals(a[0], a[0], dev.jun.gyeot.core.Drawing.directArguments(new org.json.JSONObject(java.util.Map.of("prompt", "x", "aspect", a[0])).toString()).optString("aspect"));
        // The shapes GPT Image 2 draws (준: "gpt에서 제공하는 비율에 맞게"): every size within its limits, each ratio as named.
        for (String[] one : dev.jun.gyeot.core.Drawing.SHAPES) {
            String size = dev.jun.gyeot.core.Drawing.openAiRequest(new org.json.JSONObject(java.util.Map.of("prompt", "x", "aspect", one[0]))).optString("size");
            assertEquals(one[3], size);
            int w = Integer.parseInt(size.split("x")[0]), h = Integer.parseInt(size.split("x")[1]); long px = (long) w * h;
            assertTrue(size, w % 16 == 0 && h % 16 == 0 && Math.max(w, h) <= 3840 && Math.max(w, h) <= 3 * Math.min(w, h) && px >= 655_360 && px <= 8_294_400);
            String[] r = one[1].split(":");
            assertEquals("the ratio named is the size's: " + one[1], Double.parseDouble(r[0]) / Double.parseDouble(r[1]), (double) w / h, 0.02);
            assertEquals("the words pick it: " + one[1], one[0], dev.jun.gyeot.core.Drawing.aspect(one[1] + " 비율로 그려 줘"));
        }
        assertEquals("GPT's own five only (준: \"그건 우리랑 전혀 상관없는 회사 서비스인데 그걸 왜따라해\")", java.util.List.of("square", "landscape", "portrait", "16:9", "9:16"), dev.jun.gyeot.core.Drawing.aspects());
        assertEquals("not a shape it draws", "", dev.jun.gyeot.core.Drawing.aspect("21:9 비율로"));
        assertEquals("가로 와이드 (16:9)", dev.jun.gyeot.core.Drawing.label("16:9"));
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
        // At most one question in three hours (준: "맥락과 무관한 무의미한 질문들이 반복돼").
        assertEquals("not again within three hours", "", dev.jun.gyeot.core.BuddyMind.curious(known, asked, now + 60_000, new java.util.Random(3)));
        assertEquals("not again within three hours", "", dev.jun.gyeot.core.BuddyMind.curious(known, asked, now + 2 * 3_600_000L, new java.util.Random(3)));
        for (int i = 0; i < 20; i++) assertNotEquals("not the same one within three days", q, dev.jun.gyeot.core.BuddyMind.curious(known, asked, now + 4 * 3_600_000L, new java.util.Random(i)));
        assertFalse("no question topics", dev.jun.gyeot.core.BuddyMind.TOPICS.toString().matches(".*(질문|퀴즈|수수께끼).*"));
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
    @Test public void hearsItsName() throws Exception {
        // 준: "퍼레가 자기 이름을 못알아듣고 자꾸 벌레 버려 이런걸로 알아들어"
        assertEquals("퍼레야", dev.jun.gyeot.core.BuddyMind.nameHeard("벌레야"));
        // 준: "실시간 음악생성 lyria 연동 실패", "유튜브 꺼줘 실패", "우측 lp페이지 전환도 못알아들어": tools of their own, also as text
        assertEquals("make_music", BuddyTalk.act("음악 만들어 볼게!\n할일: {\"tool\":\"lyria\",\"mood\":\"calm piano\"}").optString("tool"));
        assertEquals("close_app", BuddyTalk.act("끌게!\n할일: {\"tool\":\"close_app\",\"app\":\"유튜브\"}").optString("tool"));
        assertEquals("home_page", BuddyTalk.act("넘길게!\n할일: {\"tool\":\"page\",\"page\":\"LP\"}").optString("tool"));
        assertEquals("유튜브 끌게!", BuddyTools.say(new org.json.JSONObject().put("tool", "close_app").put("app", "유튜브")));
        // 준: "퍼레가 자기 이름 잘 못알아듣는것"
        assertEquals("퍼레야, 지금 몇 시야", dev.jun.gyeot.core.BuddyMind.nameHeard("펠레야 지금 몇 시야"));
        assertEquals("퍼레야", dev.jun.gyeot.core.BuddyMind.nameHeard("포래야"));
        assertEquals("오늘 퍼레야 뭐 해", dev.jun.gyeot.core.BuddyMind.nameHeard("오늘 필레야 뭐 해"));
        // 준: "음성종료, 들어가, 이제 쉬어 등의 말에 엉뚱한 반응을 해", "난 음성종료하란건데": these end the talk, with the name or not
        for (String bye : new String[]{"음성 종료", "음성종료", "종료", "퍼레야 음성 종료", "들어가", "들어가 있어", "이제 쉬어", "쉬어", "이제 쉬어 퍼레야", "대화 끝", "음성 꺼 줘", "그만해", "잘 가", "나중에 봐"})
            assertEquals(bye, dev.jun.gyeot.core.VoiceTurns.Kind.BYE, dev.jun.gyeot.core.VoiceTurns.of(bye, false, false, false));
        for (String talk : new String[]{"회의 종료 언제야?", "들어가는 길에 우유 사야 해", "쉬어야 할 것 같아", "퍼레야 오늘 날씨 어때"})
            assertEquals(talk, dev.jun.gyeot.core.VoiceTurns.Kind.NONE, dev.jun.gyeot.core.VoiceTurns.of(talk, false, false, false));
        assertEquals(dev.jun.gyeot.core.VoiceTurns.Kind.CALL, dev.jun.gyeot.core.VoiceTurns.of("퍼레야", false, false, false));
        // "네이버지도 꺼줘" over 네이버 지도 (2026-11-27): the brain said "네이버지도 닫을게 👋" and called nothing. Now an app to turn
        // off is heard on the phone, and "닫을게" counts as a promise that must come with a tool.
        assertEquals("네이버지도", dev.jun.gyeot.core.VoiceTurns.closing("네이버지도 꺼줘"));
        assertEquals("유튜브", dev.jun.gyeot.core.VoiceTurns.closing("퍼레야 유튜브 좀 꺼 줘"));
        assertEquals("카카오톡", dev.jun.gyeot.core.VoiceTurns.closing("카카오톡을 닫아 줘"));
        assertEquals("티맵", dev.jun.gyeot.core.VoiceTurns.closing("티맵 종료해 줘"));
        for (String here : new String[]{"이거 꺼 줘", "이 앱 닫아 줘", "앱 꺼 줘", "지금 앱 종료해", "여기서 나가 줘"})
            assertEquals(here, "", dev.jun.gyeot.core.VoiceTurns.closing(here));
        for (String not : new String[]{"꺼 줘", "불 꺼 줘", "와이파이 꺼 줘", "알람 꺼", "음성 꺼 줘", "화면 꺼 줘", "광고 닫아 줘", "음악 꺼 줘", "유튜브 꺼 줄 수 있어? 아니 그냥 두자", "오늘 날씨 어때"})
            assertNull(not, dev.jun.gyeot.core.VoiceTurns.closing(not));
        assertTrue(BuddyTools.promised("네이버지도 꺼줘", "네이버지도 닫을게 👋"));
        assertTrue(BuddyTools.promised("LP 페이지로 넘겨 줘", "LP로 넘길게!"));
        // "이거 꺼 줘" is done on the phone, with no brain: close_app, "끌게!".
        java.util.concurrent.CountDownLatch got = new java.util.concurrent.CountDownLatch(1);
        java.util.concurrent.atomic.AtomicReference<org.json.JSONObject> did = new java.util.concurrent.atomic.AtomicReference<>();
        java.util.concurrent.atomic.AtomicReference<String> answer = new java.util.concurrent.atomic.AtomicReference<>();
        BuddyTalk.reply(org.robolectric.RuntimeEnvironment.getApplication(), new BuddyTalk.Ask("이거 꺼 줘", java.util.List.of(), false, did::set, null,
            l -> { answer.set(l); got.countDown(); }, why -> got.countDown(), false, ""));
        assertTrue(got.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals("close_app", did.get().optString("tool")); assertEquals("끌게!", answer.get());
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
        // A sound whose length is not known ahead (the phone's voice): measured until it is said, then the usual window.
        playing.add(5000, 65_000, 4000); playing.end(6200);
        assertEquals("said: the window from its real end", 4000, playing.level(6800), 0);
        assertEquals("not a minute of a guess", 0, playing.level(7000), 0);
        playing.clear();

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
        var depth = BrainPicker.class.getDeclaredMethod("depth", Activity.class, Ui.class, Runnable.class, String.class, String.class); depth.setAccessible(true);
        depth.invoke(null, a, new Ui(a), (Runnable) () -> {}, dev.jun.gyeot.core.WorkBook.ANTIGRAVITY, "agy-test-model"); shadowOf(Looper.getMainLooper()).idle();
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

    /**
     * The picture's shape as a list (준: "이미지크기를 이런식으로 선택할 수 있게해줘", "gpt에서 제공하는 비율에 맞게"): a row per shape
     * GPT draws with its outline, name and ratio, compact (44dp rows).
     */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void picturesShapesAsAList() throws Exception {
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get(); shadowOf(Looper.getMainLooper()).idle();
        String[] applied = {""};
        new Ui(a).imageOptions("16:9", false, (chosen, editing) -> applied[0] = chosen).show(); shadowOf(Looper.getMainLooper()).idle();
        View d = ShadowDialog.getLatestDialog().getWindow().getDecorView(); settle(d);
        float density = a.getResources().getDisplayMetrics().density;
        for (String row : new String[]{"정사각형 (1:1)", "가로 (3:2)", "세로 (2:3)", "가로 와이드 (16:9)", "세로 와이드 (9:16)"}) {
            View t = text(d, row); assertNotNull(row, t);
            assertTrue("compact row: " + row, ((View) t.getParent()).getHeight() / density <= 45);
        }
        for (String other : new String[]{"파노라마", "울트라", "클래식", "시네마틱"}) assertNull("no other service's names: " + other, text(d, other));
        View story = text(d, "세로 와이드 (9:16)"); assertTrue(((View) story.getParent()).performClick()); shadowOf(Looper.getMainLooper()).idle(); settle(d);
        shot(d, "picture_shapes");
        View apply = text(d, "대화창에 적용"); while (!apply.isClickable() && apply.getParent() instanceof View p) apply = p;
        assertTrue(apply.performClick()); shadowOf(Looper.getMainLooper()).idle();
        assertEquals("9:16", applied[0]);
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

    /** The phone's recognizer played by a test: each listening it opens is kept, in order. */
    private static SpeechInput.Ears fakeEars(java.util.List<SpeechInput.Listener> ears) {
        return new SpeechInput.Ears() {
            @Override public SpeechInput.Ear open(android.content.Context c) {
                return new SpeechInput.Ear() { @Override public void start(SpeechInput.Listener l) { ears.add(l); } @Override public void finish() {} @Override public void close() {} };
            }
            @Override public boolean available(android.content.Context c) { return true; }
        };
    }
    /** The main looper run (and the talk's threads let run) until done says so, a few virtual seconds at most. */
    private static void settle(java.util.function.BooleanSupplier done) {
        for (int i = 0; i < 300 && !done.getAsBoolean(); i++) {
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20));
        }
    }
    private static Object field(Object o, String name) throws Exception { var f = BuddyVoice.class.getDeclaredField(name); f.setAccessible(true); return f.get(o); }

    /**
     * Only a guessed alarm or timer waits for a yes (준: "퍼레의 판단력 … 강화해줘"): a length or a time the user said is set at
     * once and read back as it is set; an hour without 오전·오후, or none, is asked; and "아니 8시로" to the read-back sets that
     * one alarm with no second question.
     */
    @Test public void confirmsOnlyAGuessedTime() throws Exception {
        java.util.function.Function<String, org.json.JSONObject> json = t -> { try { return new org.json.JSONObject(t); } catch (org.json.JSONException e) { throw new AssertionError(e); } };
        assertTrue(dev.jun.gyeot.core.DeviceActions.stated("timer", json.apply("{\"seconds\":180}"), "3분 타이머 맞춰 줘"));
        assertFalse(dev.jun.gyeot.core.DeviceActions.stated("timer", json.apply("{\"seconds\":180}"), "라면 타이머 맞춰 줘"));
        assertTrue(dev.jun.gyeot.core.DeviceActions.stated("timer", json.apply("{\"seconds\":5400}"), "한 시간 반 타이머"));
        assertTrue(dev.jun.gyeot.core.DeviceActions.stated("timer", json.apply("{\"seconds\":90}"), "1분 30초만 재 줘"));
        assertFalse("two lengths", dev.jun.gyeot.core.DeviceActions.stated("timer", json.apply("{\"seconds\":300}"), "3분이랑 5분 타이머"));
        assertTrue(dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":7,\"minute\":30}"), "일곱 시 반에 깨워 줘"));
        assertFalse("waking at 7 is the morning", dev.jun.gyeot.core.DeviceActions.halfDayUnsaid(7, "일곱 시 반에 깨워 줘"));
        assertTrue(dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":7}"), "7시 알람"));
        assertTrue("7 or 19", dev.jun.gyeot.core.DeviceActions.halfDayUnsaid(7, "7시 알람"));
        assertTrue(dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":15}"), "오후 3시 알람"));
        assertFalse(dev.jun.gyeot.core.DeviceActions.halfDayUnsaid(15, "오후 3시 알람"));
        assertFalse("the half of the day said must agree", dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":19}"), "아침 7시 알람"));
        assertFalse("two times", dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":8}"), "7시 말고 8시로"));
        assertFalse("the minute said", dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":7}"), "7시 반에 알람"));
        assertFalse("worked out, not said", dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":18,\"minute\":50}"), "7시 10분 전에 알람"));
        assertFalse("days not said", dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":7,\"days\":[\"mon\"]}"), "아침 7시 알람"));
        assertTrue(dev.jun.gyeot.core.DeviceActions.stated("alarm", json.apply("{\"hour\":7,\"minute\":30}"), "7:30에 알람"));
        assertTrue(BuddyTools.RULE, BuddyTools.RULE.contains("'알겠어!'만"));

        // On a phone with a clock app.
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        android.content.ComponentName clock = new android.content.ComponentName("com.clock", "com.clock.Set"); pm.addActivityIfNotPresent(clock);
        for (String action : new String[]{android.provider.AlarmClock.ACTION_SET_ALARM, android.provider.AlarmClock.ACTION_SET_TIMER}) {
            android.content.IntentFilter f = new android.content.IntentFilter(action); f.addCategory(android.content.Intent.CATEGORY_DEFAULT); pm.addIntentFilterForActivity(clock, f);
        }
        org.robolectric.shadows.ShadowApplication shadow = shadowOf(app);
        while (shadow.getNextStartedActivity() != null) { /* earlier ones */ }
        BuddyVoice.Outcome timer = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"timer\",\"seconds\":180}"), "3분 타이머 맞춰 줘", false);
        assertNull("set at once", timer.confirm()); assertEquals("3분 타이머 시작!", timer.say());
        android.content.Intent started = shadow.getNextStartedActivity();
        assertEquals(android.provider.AlarmClock.ACTION_SET_TIMER, started.getAction()); assertEquals(180, started.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, 0));
        BuddyVoice.Outcome seven = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":7}"), "7시 알람 맞춰 줘", false);
        assertNotNull("7 or 19: asked", seven.confirm()); assertTrue(seven.say(), seven.say().endsWith("오전 7:00에 알람 맞출까?"));
        assertNull("nothing set yet", shadow.getNextStartedActivity());
        BuddyVoice.Outcome morning = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":7}"), "아침 7시에 깨워 줘", false);
        assertNull(morning.confirm()); assertTrue(morning.say(), morning.say().endsWith("오전 7:00에 알람 맞췄어!"));
        assertEquals(7, shadow.getNextStartedActivity().getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1));
        assertNotNull("no time said: asked", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":7}"), "알람 맞춰 줘", false).confirm());
        assertNull(BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":15}"), "오후 3시 알람", false).confirm());
        assertEquals(15, shadow.getNextStartedActivity().getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1));
        assertNull("typed chat says yes itself, as before", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":9}"), "네", false).confirm());
        while (shadow.getNextStartedActivity() != null) { /* that one */ }

        // In a talk: the read-back asked, the user cuts in with "아니 8시로": one alarm at 8:00, no second question.
        java.util.List<SpeechInput.Listener> ears = new java.util.ArrayList<>();
        java.util.List<BuddyTalk.Ask> asks = new java.util.concurrent.CopyOnWriteArrayList<>();
        SpeechInput.Ears before = BuddyVoice.ears; java.util.function.BiConsumer<android.content.Context, BuddyTalk.Ask> was = BuddyVoice.brain;
        BuddyVoice.ears = fakeEars(ears); BuddyVoice.brain = (c, ask) -> asks.add(ask);
        BuddyVoice talk = new BuddyVoice(app, app.store());
        try {
            talk.attach((state, text) -> {}, then -> then.run());
            talk.start(); settle(() -> ears.size() == 1);
            ears.get(0).ready(); ears.get(0).heard("알람 맞춰 줘");
            settle(() -> asks.size() == 1);
            asks.get(0).doIt().accept(BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":7}")); asks.get(0).done().accept("");
            settle(() -> { try { return field(talk, "confirming") != null; } catch (Exception e) { return false; } });
            assertNotNull("it asks", field(talk, "confirming"));
            assertNull("and sets nothing", shadow.getNextStartedActivity());
            var over = BuddyVoice.class.getDeclaredMethod("talkedOver", boolean.class); over.setAccessible(true);
            over.invoke(talk, false);
            settle(() -> ears.size() == 2);
            ears.get(1).ready(); ears.get(1).heard("아니 8시로");
            settle(() -> asks.size() == 2);
            String recent = asks.get(1).recent();
            assertTrue(recent, recent.contains("퍼레가 방금 ‘") && recent.contains("오전 7:00에 알람 맞출까?’라고 물었어"));
            asks.get(1).doIt().accept(BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":8}")); asks.get(1).done().accept("");
            settle(() -> shadow.peekNextStartedActivity() != null);
            android.content.Intent eight = shadow.getNextStartedActivity();
            assertNotNull("set", eight);
            assertEquals(android.provider.AlarmClock.ACTION_SET_ALARM, eight.getAction()); assertEquals(8, eight.getIntExtra(android.provider.AlarmClock.EXTRA_HOUR, -1));
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(500));
            assertNull("one alarm", shadow.getNextStartedActivity());
            assertNull("no second question", field(talk, "confirming"));
        } finally { talk.stop(); BuddyVoice.ears = before; BuddyVoice.brain = was; }
    }

    /**
     * What 퍼레 was about to do and just did (준: "퍼레의 판단력 … 강화해줘"): a thing promised is held when the user cuts in, let
     * go on "아니 멜론으로" (and the answer is told what it was), done on "응" or on silence; the next answers know what was just
     * done; an app that did not come up is said once, with the tap that clears the way, and never over the user's words.
     */
    @Test public void knowsWhatItJustDid() throws Exception {
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        android.content.ComponentName youtube = new android.content.ComponentName("com.google.android.youtube", "com.google.android.youtube.Home");
        pm.addActivityIfNotPresent(youtube);
        android.content.IntentFilter search = new android.content.IntentFilter(android.content.Intent.ACTION_SEARCH); search.addCategory(android.content.Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(youtube, search);
        android.content.ComponentName kakao = new android.content.ComponentName("com.kakao.talk", "com.kakao.talk.Share");
        pm.addActivityIfNotPresent(kakao);
        android.content.IntentFilter send = new android.content.IntentFilter(android.content.Intent.ACTION_SEND); send.addDataType("text/plain"); send.addCategory(android.content.Intent.CATEGORY_DEFAULT);
        pm.addIntentFilterForActivity(kakao, send);
        org.robolectric.shadows.ShadowApplication shadow = shadowOf(app);
        while (shadow.getNextStartedActivity() != null) { /* earlier ones */ }

        // What went wrong is kept with its kind; entries from before kinds read as a failure.
        BuddyMisses.clear(app);
        BuddyMisses.add(app, "아니 멜론으로", "play · ‘좋은날’", "고쳐 말했어요", "", BuddyMisses.CORRECTED);
        assertEquals(BuddyMisses.CORRECTED, BuddyMisses.list(app).get(0).kind());
        app.getSharedPreferences("buddyTalk", android.content.Context.MODE_PRIVATE).edit().putString("misses", "[{\"at\":1,\"said\":\"켜 줘\",\"what\":\"torch\",\"why\":\"안 됐어\",\"fix\":\"\"}]").commit();
        assertEquals(BuddyMisses.FAIL, BuddyMisses.list(app).get(0).kind());
        BuddyMisses.clear(app);
        org.json.JSONObject play = BuddyTalk.loose("{\"tool\":\"play\",\"query\":\"아이유 좋은날\",\"app\":\"YouTube\"}");
        assertEquals("play · ‘아이유 좋은날’ · YouTube", BuddyActs.about(play));
        assertEquals("alarm · 07:30", BuddyActs.about(BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":7,\"minute\":30}")));
        for (String no : new String[]{"아니 멜론으로", "그거 말고 다른 거", "잠깐만", "유튜브 말고 멜론"}) assertTrue(no, dev.jun.gyeot.core.VoiceTurns.corrects(no));
        for (String yes : new String[]{"응", "좋아 틀어 줘", "아이유 노래 좋다", ""}) assertFalse(yes, dev.jun.gyeot.core.VoiceTurns.corrects(yes));

        java.util.List<SpeechInput.Listener> ears = new java.util.ArrayList<>();
        java.util.List<BuddyTalk.Ask> asks = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<String> shown = new java.util.concurrent.CopyOnWriteArrayList<>();
        boolean[] comesUp = {true};
        SpeechInput.Ears before = BuddyVoice.ears; java.util.function.BiConsumer<android.content.Context, BuddyTalk.Ask> was = BuddyVoice.brain; BuddyActs.Watch watched = BuddyActs.watch;
        BuddyVoice.ears = fakeEars(ears); BuddyVoice.brain = (c, ask) -> asks.add(ask);
        // 폰 조작 on: the app is looked for in front for 4 s (PhoneService.whenShown), here it comes up or not as the test says.
        android.os.Handler main = new android.os.Handler(Looper.getMainLooper());
        BuddyActs.watch = new BuddyActs.Watch() {
            @Override public boolean on() { return true; }
            @Override public void whenShown(String pkg, long withinMs, java.util.function.Consumer<Boolean> then) { boolean up = comesUp[0]; main.postDelayed(() -> then.accept(up), withinMs + 500); }
        };
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(java.util.Locale.KOREAN);
        BuddyVoice talk = new BuddyVoice(app, app.store());
        var over = BuddyVoice.class.getDeclaredMethod("talkedOver", boolean.class); over.setAccessible(true);
        java.util.function.BooleanSupplier saying = () -> { try { return (boolean) field(talk, "saying"); } catch (Exception e) { return false; } };
        try {
            talk.attach(new BuddyVoice.Show() {
                @Override public void state(String state, String text) { shown.add(state + ":" + text); }
                @Override public void state(String state, String text, int ms, String go) { shown.add(state + ":" + text + (go == null || go.isEmpty() ? "" : "|" + go)); }
            }, then -> then.run());
            talk.start(); settle(() -> ears.size() == 1);

            // "틀어 줄게!" cut short, then "아니 멜론으로": YouTube is never opened, the correction is kept, the answer knows.
            ears.get(0).ready(); ears.get(0).heard("유튜브에서 아이유 좋은날 틀어 줘");
            settle(() -> asks.size() == 1);
            asks.get(0).doIt().accept(play); asks.get(0).part().accept("아이유 좋은날 틀어 줄게!");
            settle(saying);
            over.invoke(talk, false);
            settle(() -> ears.size() == 2);
            assertNull("nothing done while the user speaks", shadow.getNextStartedActivity());
            ears.get(1).ready(); ears.get(1).heard("아니 멜론으로");
            settle(() -> asks.size() == 2);
            assertNull("no YouTube", shadow.getNextStartedActivity());
            assertEquals(BuddyMisses.CORRECTED, BuddyMisses.list(app).get(0).kind());
            assertTrue(asks.get(1).recent(), asks.get(1).recent().contains("방금 하려던 일: play · ‘아이유 좋은날’ · YouTube"));
            asks.get(1).done().accept("");
            settle(() -> ears.size() == 3);

            // Cut short again, then "응": the thing promised is done.
            ears.get(2).ready(); ears.get(2).heard("유튜브에서 아이유 좋은날 틀어 줘");
            settle(() -> asks.size() == 3);
            asks.get(2).doIt().accept(play); asks.get(2).part().accept("틀어 줄게!");
            settle(saying);
            over.invoke(talk, false);
            settle(() -> ears.size() == 4);
            ears.get(3).ready(); ears.get(3).heard("응");
            settle(() -> shadow.peekNextStartedActivity() != null);
            android.content.Intent yt = shadow.getNextStartedActivity();
            assertNotNull("done on a yes", yt); assertEquals("com.google.android.youtube", yt.getPackage());
            assertEquals("a yes is not asked", 3, asks.size());
            settle(() -> ears.size() == 5);

            // The next answer knows what was just done; "아니 그거 말고" within 30 s is kept as a correction of it.
            ears.get(4).ready(); ears.get(4).heard("이거 무슨 노래야");
            settle(() -> asks.size() == 4);
            assertTrue(asks.get(3).recent(), asks.get(3).recent().startsWith("방금 한 일: play · ‘아이유 좋은날’ · YouTube ("));
            asks.get(3).done().accept("");
            settle(() -> ears.size() == 6);
            ears.get(5).ready(); ears.get(5).heard("아니 그거 말고 다른 거");
            settle(() -> asks.size() == 5);
            assertEquals(BuddyMisses.CORRECTED, BuddyMisses.list(app).get(0).kind()); assertEquals("play · ‘아이유 좋은날’ · YouTube", BuddyMisses.list(app).get(0).what());
            asks.get(4).done().accept("");
            settle(() -> ears.size() == 7);

            // Cut short, then nothing said: done all the same.
            ears.get(6).ready(); ears.get(6).heard("유튜브에서 아이유 좋은날 틀어 줘");
            settle(() -> asks.size() == 6);
            asks.get(5).doIt().accept(play); asks.get(5).part().accept("틀어 줄게!");
            settle(saying);
            over.invoke(talk, false);
            settle(() -> ears.size() == 8);
            ears.get(7).ready(); ears.get(7).silent("말이 없어요");
            settle(() -> shadow.peekNextStartedActivity() != null);
            assertNotNull("done on silence", shadow.getNextStartedActivity());
            settle(() -> ears.size() == 9);

            // KakaoTalk opened but never came up: said once while it listens, the words open the way through. (YouTube came up
            // each time: nothing was kept or said for it.)
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(5000));
            assertTrue("came up: nothing kept " + BuddyMisses.list(app), BuddyMisses.list(app).stream().noneMatch(m -> BuddyMisses.NOT_SHOWN.equals(m.kind())));
            assertTrue(shown.stream().noneMatch(x -> x.contains("안 열렸어")));
            comesUp[0] = false; BuddyMisses.clear(app); shown.clear();
            ears.get(8).ready(); ears.get(8).heard("카톡으로 안녕 보내 줘");
            settle(() -> asks.size() == 7);
            asks.get(6).doIt().accept(BuddyTalk.loose("{\"tool\":\"share\",\"text\":\"안녕\",\"app\":\"카카오톡\"}")); asks.get(6).done().accept("");
            settle(() -> ears.size() == 10);
            assertNotNull("shared", shadow.getNextStartedActivity());
            ears.get(9).ready();
            settle(() -> !BuddyMisses.list(app).isEmpty());
            assertEquals(BuddyMisses.NOT_SHOWN, BuddyMisses.list(app).get(0).kind()); assertEquals("overlay", BuddyMisses.list(app).get(0).fix());
            shadowOf(Looper.getMainLooper()).idle();
            android.speech.tts.TextToSpeech voice = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance();
            assertNotNull("said in the phone's voice", voice);
            shadowOf(voice).getOnInitListener().onInit(android.speech.tts.TextToSpeech.SUCCESS);
            String late = "speaking:어, ‘카카오톡’ 안 열렸어.|fix:overlay";
            settle(() -> shown.contains(late));
            assertTrue(shown.toString(), shown.contains(late));
            settle(() -> ears.size() == 11);
            assertEquals("it listens on after it", 11, ears.size());

            // Again, with the user already talking: nothing said over them (kept all the same).
            ears.get(10).ready(); ears.get(10).heard("카톡으로 안녕 보내 줘");
            settle(() -> asks.size() == 8);
            asks.get(7).doIt().accept(BuddyTalk.loose("{\"tool\":\"share\",\"text\":\"안녕\",\"app\":\"카카오톡\"}")); asks.get(7).done().accept("");
            settle(() -> ears.size() == 12);
            ears.get(11).ready(); ears.get(11).partial("근데");
            settle(() -> BuddyMisses.list(app).size() == 2);
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(1000));
            assertEquals("said once: " + shown, 1, shown.stream().filter(late::equals).count());

            // Cut short, then a guest's "응": a guest never does a thing on the phone, so the user's thing waits (it is not let
            // go either) and the quiet after does it.
            comesUp[0] = true; while (shadow.getNextStartedActivity() != null) { /* the shares above */ }
            var think = BuddyVoice.class.getDeclaredMethod("think", String.class, boolean.class); think.setAccessible(true);
            ears.get(11).heard("유튜브에서 아이유 좋은날 틀어 줘");
            settle(() -> asks.size() == 9);
            asks.get(8).doIt().accept(play); asks.get(8).part().accept("틀어 줄게!");
            settle(saying);
            over.invoke(talk, false);
            settle(() -> ears.size() == 13);
            think.invoke(talk, "응", true);
            settle(() -> asks.size() == 10);
            assertNull("a guest's yes does not do it", shadow.getNextStartedActivity());
            assertNotNull("it waits for the user", field(talk, "held"));
            assertNull("a guest's answer does nothing", asks.get(9).doIt());
            asks.get(9).done().accept("");
            settle(() -> ears.size() == 14);
            ears.get(13).ready(); ears.get(13).silent("말이 없어요");
            settle(() -> shadow.peekNextStartedActivity() != null);
            android.content.Intent after = shadow.getNextStartedActivity();
            assertNotNull("done on the quiet after", after); assertEquals("com.google.android.youtube", after.getPackage());

            // A talk ended while an answer was awaited: the next one awaits none (else what did not open is never said in it).
            settle(() -> ears.size() == 15);
            ears.get(14).ready(); ears.get(14).heard("오늘 뭐 하지");
            settle(() -> asks.size() == 11);
            assertNotEquals(0, field(talk, "awaited"));
            talk.stop(); talk.start();
            assertEquals("a new talk awaits nothing", 0, field(talk, "awaited"));
        } finally { talk.stop(); BuddyVoice.ears = before; BuddyVoice.brain = was; BuddyActs.watch = watched; }
    }

    /**
     * What 퍼레 knew, set right (준: "퍼레의 학습기능 … 강화해줘"): a talk's lessons replace what the user corrected, reinforce
     * what they told again and keep the rest as new, and what was told often or lately comes to mind first.
     */
    @Test public void correctsWhatItKnew() {
        long t = 1_700_000_000_000L, day = 86_400_000L;
        java.util.function.Function<java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry>, java.util.List<String>> texts = l -> l.stream().map(dev.jun.gyeot.core.BuddyMemory.Entry::text).toList();
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> coffee = java.util.List.of(new dev.jun.gyeot.core.BuddyMemory.Entry(t, "fact", "사용자는 커피를 좋아한대", 2, false));
        // Said the other way round: the new words in its place, not a second memory beside it (one known as it was named, told now).
        var no = dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("새로: 사용자는 커피를 안 좋아한대"), "나 커피 안 좋아해", t + day, false);
        assertEquals(java.util.List.of("사용자는 커피를 안 좋아한대"), texts.apply(no));
        assertEquals(t, no.get(0).at()); assertEquals(t + day, no.get(0).last()); assertEquals(1, no.get(0).seen());
        // Set right by its number; a "고침" about something else is kept as new, the one known left as it was.
        assertEquals(java.util.List.of("사용자는 요즘 커피를 끊었대"), texts.apply(dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("고침 1: 사용자는 요즘 커피를 끊었대"), "커피 끊었어", t + day, false)));
        assertEquals(java.util.List.of("사용자는 커피를 좋아한대", "사용자는 내일 부산에 간대"), texts.apply(dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("고침 1: 사용자는 내일 부산에 간대"), "내일 부산 가", t + day, false)));
        // Let go only when the talk was about it, and a private one only once the fingerprint passed.
        assertTrue(dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("지움 1"), "아니 나 커피 안 마셔", t, false).isEmpty());
        assertEquals("not about it", 1, dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("지움 1"), "내일 부산 가", t, false).size());
        java.util.List<dev.jun.gyeot.core.BuddyMemory.Entry> worry = java.util.List.of(new dev.jun.gyeot.core.BuddyMemory.Entry(t, "fact", "사용자는 요즘 이직 고민이 많대", 2, true));
        assertEquals("private, locked", 1, dev.jun.gyeot.core.BuddyMind.learned(worry, worry, dev.jun.gyeot.core.BuddyMind.parseOps("지움 1"), "이직 고민은 끝났어", t, false).size());
        assertTrue("private, unlocked", dev.jun.gyeot.core.BuddyMind.learned(worry, worry, dev.jun.gyeot.core.BuddyMind.parseOps("지움 1"), "이직 고민은 끝났어", t, true).isEmpty());
        // Told again twice: told three times, and it matters more.
        var twice = dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("같음 1\n같음 1"), "커피 좋아", t, false);
        assertEquals(1, twice.size()); assertEquals(3, twice.get(0).seen()); assertEquals(3, twice.get(0).weight());
        // Nearly the same words written as new: told again (no longer dropped, nor kept twice).
        var again = dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("새로: 사용자는 커피를 좋아한대요"), "커피 좋아", t + day, false);
        assertEquals(1, again.size()); assertEquals(2, again.get(0).seen()); assertEquals(t + day, again.get(0).last());
        assertEquals("a new thing", 2, dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("새로: 사용자는 고양이를 좋아한대"), "고양이 좋아", t, false).size());
        assertEquals("no numbers of four digits", 1, dev.jun.gyeot.core.BuddyMind.learned(coffee, coffee, dev.jun.gyeot.core.BuddyMind.parseOps("새로: 사용자의 현관 번호는 4821이래"), "", t, false).size());
        // What was told more often comes to mind first (the same weight, told as lately), and lately counts from when it was last told.
        dev.jun.gyeot.core.BuddyMemory.Entry cat = new dev.jun.gyeot.core.BuddyMemory.Entry(t, "fact", "사용자는 고양이를 좋아한대", 2, false);
        dev.jun.gyeot.core.BuddyMemory.Entry dog = dev.jun.gyeot.core.BuddyMemory.confirm(java.util.List.of(new dev.jun.gyeot.core.BuddyMemory.Entry(t, "fact", "사용자는 강아지를 키운대", 2, false)), t, t).get(0);
        assertEquals(2, dog.seen()); assertEquals(2, dog.weight());
        assertEquals(dog, dev.jun.gyeot.core.BuddyMind.recall(java.util.List.of(cat, dog), "", t + 3 * day, null, false, 2).get(0));
        dev.jun.gyeot.core.BuddyMemory.Entry sea = new dev.jun.gyeot.core.BuddyMemory.Entry(t - 30 * day, "fact", "사용자는 바다를 좋아한대", 2, false, 1, t);
        dev.jun.gyeot.core.BuddyMemory.Entry hill = new dev.jun.gyeot.core.BuddyMemory.Entry(t - 10 * day, "fact", "사용자는 산을 좋아한대", 2, false);
        assertEquals("told a month ago, again today", sea, dev.jun.gyeot.core.BuddyMind.recall(java.util.List.of(hill, sea), "", t + day, null, false, 2).get(0));
        // The opposite: one says no, and it is the same thing.
        assertTrue(dev.jun.gyeot.core.BuddyMind.negates("사용자는 커피를 좋아한대", "사용자는 커피를 싫어한대"));
        assertFalse(dev.jun.gyeot.core.BuddyMind.negates("사용자는 커피를 좋아한대", "사용자는 커피를 좋아한대요"));
        assertFalse("another thing", dev.jun.gyeot.core.BuddyMind.negates("사용자는 커피를 좋아한대", "사용자는 고양이를 안 좋아한대"));
        // The lessons as written, at most four, "민감" kept as private.
        var ops = dev.jun.gyeot.core.BuddyMind.parseOps("- 새로: 사용자는 고양이를 좋아한대\n민감 고침 2: 사용자는 이직 고민이 끝났대\n지움 3\n같음 1 (또 말함)\n약속: 퍼레가 내일 날씨를 알려 주기로 했어");
        assertEquals(4, ops.size());
        assertEquals(dev.jun.gyeot.core.BuddyMind.Change.NEW, ops.get(0).change()); assertEquals("사용자는 고양이를 좋아한대", ops.get(0).text());
        assertEquals(dev.jun.gyeot.core.BuddyMind.Change.FIX, ops.get(1).change()); assertEquals(2, ops.get(1).n()); assertTrue("민감 is private", ops.get(1).secret());
        assertEquals(dev.jun.gyeot.core.BuddyMind.Change.DROP, ops.get(2).change()); assertEquals(dev.jun.gyeot.core.BuddyMind.Change.SAME, ops.get(3).change());
        assertEquals("promise", dev.jun.gyeot.core.BuddyMind.parseOps("약속: 퍼레가 내일 날씨를 알려 주기로 했어").get(0).kind());
        assertTrue(dev.jun.gyeot.core.BuddyMind.parseOps("없음").isEmpty()); assertTrue(dev.jun.gyeot.core.BuddyMind.parseOps("사용자는 커피를 좋아한대").isEmpty());
        // Kept as before: memories saved before seen and last read as told once, when they were kept; saved and read back the same.
        var old = dev.jun.gyeot.core.BuddyMemory.read("[{\"at\":5,\"kind\":\"fact\",\"text\":\"사용자는 바다를 좋아한대\",\"weight\":2}]").get(0);
        assertEquals(1, old.seen()); assertEquals(5, old.last());
        assertEquals(twice, dev.jun.gyeot.core.BuddyMemory.read(dev.jun.gyeot.core.BuddyMemory.save(twice)));

        // On the phone: what it knew goes with an answer that corrects it, and the lessons are kept in its memory.
        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        BuddyTalk.forgetAll(app);
        BuddyTalk.rememberStrong(app, "fact", "사용자는 커피를 좋아한대", false);
        assertTrue("what it knew goes with the correction", BuddyTalk.corrected(app, "아니 나 이제 커피 안 마셔").contains("예전: ‘사용자는 커피를 좋아한대’"));
        assertEquals("not a correction", "", BuddyTalk.corrected(app, "커피 마시고 싶다"));
        assertEquals("nothing known about it", "", BuddyTalk.corrected(app, "아니 오늘은 산책할래"));
        var shown = dev.jun.gyeot.core.BuddyMind.related(BuddyTalk.memories(app), "나 이제 커피 끊었어", 6, false);
        assertEquals(1, shown.size());
        BuddyTalk.learned(app, shown, "고침 1: 사용자는 요즘 커피를 끊었대\n새로: 사용자는 고양이를 좋아한대", "나 이제 커피 끊었어. 고양이 좋아");
        assertEquals(java.util.List.of("사용자는 요즘 커피를 끊었대", "사용자는 고양이를 좋아한대"), texts.apply(BuddyTalk.memories(app)));
        BuddyTalk.forgetAll(app);
    }
    /**
     * Learned once a talk, from all of it (준: "퍼레의 학습기능 … 강화해줘"): no voice answer learns on its own; what the user said
     * (a short "고양이 좋아" too) and what was done for them go to the learning brain when the talk ends, every six of their
     * turns, and before a guest's words; a guest's turns never.
     */
    @Test public void learnsFromTheWholeTalk() throws Exception {
        long at = java.time.LocalDateTime.of(2026, 11, 27, 22, 10).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals("22:10 노래 ‘아이유 좋은날’(멜론)", BuddyActs.line(BuddyTalk.loose("{\"tool\":\"play\",\"query\":\"아이유 좋은날\",\"app\":\"멜론\"}"), at));
        assertEquals("a torch tells nothing of the user", "", BuddyActs.line(BuddyTalk.loose("{\"tool\":\"torch\",\"on\":true}"), at));
        java.util.List<String[]> turns = java.util.List.of(new String[]{"user", "어제 산책했어"}, new String[]{"assistant", "좋았겠다!"},
            new String[]{"user", "고양이 좋아"}, new String[]{"assistant", "나도!"}, BuddyVoice.talkTurn("user", "내 통장 비밀번호 알려 줄까", true));
        String in = dev.jun.gyeot.core.BuddyMind.talkInput(turns, 2, java.util.List.of("22:10 노래 ‘아이유 좋은날’(멜론)"), java.util.List.of(new dev.jun.gyeot.core.BuddyMemory.Entry(1, "fact", "사용자는 강아지를 키운대", 2, false)));
        assertTrue(in, in.startsWith("앞의 대화(이미 배운 것, 참고만):\n사용자: 어제 산책했어\n퍼레: 좋았겠다!"));
        assertTrue(in, in.contains("대화:\n사용자: 고양이 좋아\n퍼레: 나도!\n"));
        assertFalse("a guest's words", in.contains("통장"));
        assertTrue(in, in.contains("한 일(사용자가 시켜서 퍼레가 한 것):\n- 22:10 노래 ‘아이유 좋은날’(멜론)"));
        assertTrue(in, in.endsWith("지금 알고 있는 것:\n1. 사용자는 강아지를 키운대"));
        java.util.List<String[]> long30 = new java.util.ArrayList<>(); for (int i = 0; i < 30; i++) long30.add(new String[]{"user", "말 " + i});
        String last = dev.jun.gyeot.core.BuddyMind.talkInput(long30, 0, java.util.List.of(), java.util.List.of());
        assertTrue("the last sixteen", last.contains("사용자: 말 14\n") && !last.contains("사용자: 말 13\n") && last.contains("사용자: 말 29"));
        assertTrue("a short word is worth learning", dev.jun.gyeot.core.BuddyMind.worthLearning(java.util.List.<String[]>of(new String[]{"user", "고양이 좋아"}), 0));
        assertFalse("less than four letters", dev.jun.gyeot.core.BuddyMind.worthLearning(java.util.List.<String[]>of(new String[]{"user", "응"}, new String[]{"assistant", "좋아, 그럼 내일 같이 하자!"}), 0));
        assertFalse("a guest's", dev.jun.gyeot.core.BuddyMind.worthLearning(java.util.List.<String[]>of(BuddyVoice.talkTurn("user", "나는 커피를 좋아해", true)), 0));
        assertFalse("learned before", dev.jun.gyeot.core.BuddyMind.worthLearning(java.util.List.<String[]>of(new String[]{"user", "나는 커피를 좋아해"}), 1));

        GyeotApp app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.store().appVisible = true;
        java.util.List<SpeechInput.Listener> ears = new java.util.ArrayList<>();
        java.util.List<BuddyTalk.Ask> asks = new java.util.concurrent.CopyOnWriteArrayList<>();
        java.util.List<Object[]> learned = new java.util.ArrayList<>();
        SpeechInput.Ears before = BuddyVoice.ears; java.util.function.BiConsumer<android.content.Context, BuddyTalk.Ask> was = BuddyVoice.brain; BuddyVoice.Learner learnedBy = BuddyVoice.learner;
        BuddyVoice.ears = fakeEars(ears); BuddyVoice.brain = (c, ask) -> asks.add(ask);
        BuddyVoice.learner = (c, t, from, acts) -> learned.add(new Object[]{new java.util.ArrayList<>(t), from, new java.util.ArrayList<>(acts)});
        BuddyVoice talk = new BuddyVoice(app, app.store());
        try {
            talk.attach((state, text) -> {}, then -> then.run());
            talk.start(); settle(() -> ears.size() == 1);
            ears.get(0).ready(); ears.get(0).heard("고양이 좋아");
            settle(() -> asks.size() == 1);
            assertFalse("no answer learns on its own", asks.get(0).learnNow());
            asks.get(0).done().accept("");
            settle(() -> ears.size() == 2);
            // A thing done for the user: one line for what the talk taught.
            ears.get(1).ready(); ears.get(1).heard("아이유 좋은날 검색해 줘");
            settle(() -> asks.size() == 2);
            asks.get(1).doIt().accept(BuddyTalk.loose("{\"tool\":\"search\",\"query\":\"아이유 좋은날\"}")); asks.get(1).done().accept("");
            settle(() -> ears.size() == 3);
            assertTrue("nothing learned while it goes on", learned.isEmpty());
            // A guest speaks: what the user said is learned first, once.
            var think = BuddyVoice.class.getDeclaredMethod("think", String.class, boolean.class); think.setAccessible(true);
            think.invoke(talk, "안녕, 나는 친구야", true);
            assertEquals(1, learned.size());
            @SuppressWarnings("unchecked") java.util.List<String[]> taught = (java.util.List<String[]>) learned.get(0)[0];
            assertEquals(0, learned.get(0)[1]);
            assertTrue("a short word too", taught.stream().anyMatch(x -> x[1].equals("고양이 좋아")));
            @SuppressWarnings("unchecked") java.util.List<String> done = (java.util.List<String>) learned.get(0)[2];
            assertEquals(1, done.size()); assertTrue(done.get(0), done.get(0).matches("\\d\\d:\\d\\d 검색 ‘아이유 좋은날’"));
            settle(() -> asks.size() == 3);
            assertTrue(asks.get(2).guest());
            asks.get(2).done().accept("");
            settle(() -> ears.size() == 4);
            // It ends with only the guest's words since: nothing more to learn.
            talk.stop();
            assertEquals("a guest's turns never", 1, learned.size());

            // Called again soon: the talk goes on, and a long one learns every six of the user's turns, the kept ones not again.
            talk.start(); settle(() -> ears.size() == 5);
            for (int i = 0; i < BuddyVoice.LEARN_TURNS; i++) {
                int n = asks.size(), heard = ears.size();
                ears.get(heard - 1).ready(); ears.get(heard - 1).heard("오늘 본 영화 " + (i + 1) + "번째 얘기");
                settle(() -> asks.size() == n + 1);
                asks.get(n).done().accept("");
                settle(() -> ears.size() == heard + 1);
            }
            assertEquals(2, learned.size());
            @SuppressWarnings("unchecked") java.util.List<String[]> six = (java.util.List<String[]>) learned.get(1)[0];
            int from = (int) learned.get(1)[1];
            assertEquals("the guest's kept turn is not learned again", 1, from);
            assertEquals(BuddyVoice.LEARN_TURNS, six.size() - from);
            // One more, and the talk ends: what came after is learned as it ends, and only that.
            ears.get(ears.size() - 1).ready(); ears.get(ears.size() - 1).heard("내일은 바다 보러 갈 거야");
            settle(() -> asks.size() == 2 + 1 + BuddyVoice.LEARN_TURNS + 1);
            asks.get(asks.size() - 1).done().accept("");
            settle(() -> ears.size() == 5 + BuddyVoice.LEARN_TURNS + 1);
            talk.stop();
            assertEquals("learned as it ends", 3, learned.size());
            assertEquals(1 + BuddyVoice.LEARN_TURNS, (int) learned.get(2)[1]);
            talk.stop();
            assertEquals("learned already", 3, learned.size());
            // A talk that goes on from the typed 퍼레와 대화: those turns were learned there, so only what is said by voice is.
            BuddyVoice typed = new BuddyVoice(app, app.store());
            try {
                typed.attach((state, text) -> {}, then -> then.run());
                typed.start("[[\"user\",\"나 다음 주에 제주도 가\"],[\"assistant\",\"우와 좋겠다!\"],[\"user\",\"귤 많이 먹을 거야\"]]");
                settle(() -> ears.size() == 5 + BuddyVoice.LEARN_TURNS + 2);
                ears.get(ears.size() - 1).ready(); ears.get(ears.size() - 1).heard("한라산도 올라갈 거야");
                settle(() -> asks.size() == 2 + 1 + BuddyVoice.LEARN_TURNS + 2);
                asks.get(asks.size() - 1).done().accept("");
                settle(() -> ears.size() == 5 + BuddyVoice.LEARN_TURNS + 3);
                typed.stop();
                assertEquals(4, learned.size());
                assertEquals("the typed turns are not learned again", 3, (int) learned.get(3)[1]);
            } finally { typed.stop(); }
            // An act that went wrong is told as one (kept in 퍼레가 못 한 일, not learned from).
            app.store().appVisible = false;
            org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
            assertTrue("it could not open", BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"search\",\"query\":\"아이유\"}"), "아이유 검색해 줘", false).missed());
            app.store().appVisible = true;
            assertFalse(BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"search\",\"query\":\"아이유\"}"), "아이유 검색해 줘", false).missed());
        } finally { talk.stop(); BuddyVoice.ears = before; BuddyVoice.brain = was; BuddyVoice.learner = learnedBy; app.store().appVisible = false; }
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
