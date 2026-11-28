package dev.jun.gyeot;

import android.app.Activity;
import android.app.Application;
import android.app.Dialog;
import android.app.Notification;
import android.app.NotificationManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import dev.jun.gyeot.core.PageBridge;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 미니앱에 다리 놓기 on the phone's screens: a page's message (as its port brings it to ProjectActivity.fromPage) → the compact
 * grant sheet → the call done (a notification on the app's channel opening that page, the calendar's new-event screen filled
 * in, a tool through 퍼레's own act, a memory without the secret one, the light AI's json); a blocked tool refused at once with
 * its reason; 거부 kept, 이번만 ending with the page; a page never reaching another's grants; the ⋮ menu's 권한 listing each
 * family and taking one back (알림 taken back cancels what was set for later).
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class PageBridgeTests {
    private final List<JSONObject> answers = new ArrayList<>();
    private GyeotApp app;

    @Before public void setUp() throws Exception {
        java.lang.reflect.Field f = ProjectMaker.class.getDeclaredField("instance"); f.setAccessible(true); f.set(null, null);
        app = (GyeotApp) RuntimeEnvironment.getApplication();
        app.getSharedPreferences(PageBridgeHost.GRANTS, Context.MODE_PRIVATE).edit().clear().commit();
        app.getSharedPreferences(PageNotices.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        PageBridgeHost.tap = (page, m) -> { synchronized (answers) { answers.add(m); } };
    }
    @After public void tearDown() { PageBridgeHost.tap = null; PageBridgeHost.brain = (store, instructions, input) -> Chores.run(store, instructions, input, true); }

    // ── helpers ──
    private String project(String title) throws Exception {
        return ProjectMaker.get(app).book().create(title, "", "app", "<!doctype html><html><head><title>" + title + "</title></head><body>x</body></html>", 1).id();
    }
    private ActivityController<ProjectActivity> open(String id) {
        return Robolectric.buildActivity(ProjectActivity.class, new Intent(app, ProjectActivity.class).putExtra(ProjectActivity.ID, id)).setup();
    }
    private static int seq;
    /** The page sends a call over its port (ProjectActivity.fromPage); returns its id. */
    private String send(Activity a, String call, JSONObject args) throws Exception {
        String id = "gy-" + (++seq);
        JSONObject m = new JSONObject().put("type", "gyeot").put("id", id).put("call", call).put("args", args == null ? new JSONObject() : args);
        java.lang.reflect.Method from = ProjectActivity.class.getDeclaredMethod("fromPage", String.class); from.setAccessible(true);
        from.invoke(a, m.toString());
        return id;
    }
    private static void idle() { shadowOf(Looper.getMainLooper()).idle(); }
    private static void await(BooleanSupplier done, String what) throws Exception {
        for (int i = 0; i < 400; i++) { idle(); if (done.getAsBoolean()) return; Thread.sleep(10); }
        fail("waited for " + what);
    }
    private JSONObject answer(String id) {
        synchronized (answers) { for (JSONObject m : answers) if (id.equals(m.optString("id"))) return m; }
        return null;
    }
    private JSONObject awaitAnswer(String id) throws Exception { await(() -> answer(id) != null, "the answer to " + id); return answer(id); }
    /** The grant sheet now up (a new one: not before). */
    private Dialog awaitSheet(Dialog before) throws Exception {
        await(() -> { Dialog d = ShadowDialog.getLatestDialog(); return d != null && d != before && d.isShowing(); }, "the grant sheet");
        return ShadowDialog.getLatestDialog();
    }
    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View text(View root, String exactly) { return find(root, v -> v instanceof TextView t && exactly.contentEquals(t.getText())); }
    private static View rowOf(View t) { View v = t; while (v != null && !v.isClickable() && v.getParent() instanceof View p) v = p; return v; }
    private static void settle(View d) {
        var m = d.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            d.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
            d.layout(0, 0, d.getMeasuredWidth(), d.getMeasuredHeight()); idle();
        }
    }
    private static void shot(View v, String name) throws Exception {
        settle(v);
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth()), Math.max(1, v.getHeight()), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    private void choose(Dialog sheet, String pill) {
        View p = text(sheet.getWindow().getDecorView(), pill); assertNotNull("the pill " + pill, p);
        assertTrue(p.performClick()); idle();
    }
    private String grants(String key) { return app.getSharedPreferences(PageBridgeHost.GRANTS, Context.MODE_PRIVATE).getString(key, ""); }
    /** The choice is kept off the main thread: waited for. */
    private void kept(String key, String want) throws Exception { await(() -> want.equals(grants(key)), key + " kept as " + want + " (now " + grants(key) + ")"); }

    /** A notification: asked once in a compact sheet, posted on the app's channel and opening the page; then without asking. */
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void notifyAfterTheGrant() throws Exception {
        String id = project("물 마시기");
        ActivityController<ProjectActivity> c = open(id); ProjectActivity a = c.get();
        String first = send(a, "notify", new JSONObject().put("title", "물 마실 시간").put("text", "한 컵 마셔요"));
        Dialog sheet = awaitSheet(null);
        View d = sheet.getWindow().getDecorView(); settle(d);
        assertNotNull("the page's ask as the title", text(d, "‘물 마시기’가 알림을 보내려 해요"));
        assertNotNull("what this call is", find(d, v -> v instanceof TextView t && t.getText().toString().startsWith("알림 · ‘물 마실 시간’")));
        // Compact (준: 화면은 컴팩트하게): three pills of 34dp, no full-width 48dp bar, the sheet's own button hidden.
        float dp = a.getResources().getDisplayMetrics().density;
        for (String p : new String[]{"거부", "이번만", "허용"}) {
            View pill = text(d, p); assertNotNull(p, pill);
            assertTrue(p + " is a small pill: " + pill.getHeight() / dp + "dp", pill.getHeight() <= 35 * dp);
            assertTrue(p + " is as wide as its words", pill.getWidth() < d.getWidth() / 3);
        }
        assertEquals("no 완료 bar", View.GONE, ((Ui.Sheet) sheet).primary.getVisibility());
        assertNull("nothing answered while asking", answer(first));
        shot(d, "page_bridge_grant");
        choose(sheet, "허용");
        JSONObject ok = awaitAnswer(first);
        assertTrue(ok.toString(), ok.optBoolean("ok"));
        kept("project:" + id, "{\"notify\":\"allow\"}");
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        List<Notification> posted = shadowOf(nm).getAllNotifications();
        assertEquals(1, posted.size());
        Notification n = posted.get(0);
        assertEquals("the app's own channel", PageNotices.CHANNEL, n.getChannelId());
        assertEquals("물 마실 시간", n.extras.getString(Notification.EXTRA_TITLE));
        assertEquals("the page's name under it", "물 마시기", n.extras.getString(Notification.EXTRA_SUB_TEXT));
        Intent tap = shadowOf(n.contentIntent).getSavedIntent();
        assertEquals("a tap opens that page", ProjectActivity.class.getName(), tap.getComponent().getClassName());
        assertEquals(id, tap.getStringExtra(ProjectActivity.ID));
        // Allowed: the next goes without a sheet; one set for later waits with its alarm.
        String second = send(a, "notify", new JSONObject().put("title", "다음 한 컵").put("at", System.currentTimeMillis() + 3_600_000L));
        JSONObject later = awaitAnswer(second);
        assertTrue(later.toString(), later.optBoolean("ok"));
        assertSame("no second sheet", sheet, ShadowDialog.getLatestDialog());
        assertEquals(1, PageNotices.pending(app, "project:" + id));
        assertNotNull("its alarm", shadowOf(app.getSystemService(android.app.AlarmManager.class)).getNextScheduledAlarm());
        // Its own taken away again.
        String cancel = send(a, "notify.cancel", new JSONObject().put("id", later.getJSONObject("value").getString("id")));
        assertEquals(1, awaitAnswer(cancel).getJSONObject("value").getInt("cancelled"));
        assertEquals(0, PageNotices.pending(app, "project:" + id));
        // A fourth notification in a minute waits (its limit), with the reason.
        send(a, "notify", new JSONObject().put("title", "2")); String third = send(a, "notify", new JSONObject().put("title", "3"));
        awaitAnswer(third);
        String fourth = send(a, "notify", new JSONObject().put("title", "4"));
        JSONObject limited = awaitAnswer(fourth);
        assertEquals(PageBridge.LIMIT, limited.optString("code")); assertTrue(limited.optString("error"), limited.optString("error").contains("1분에 3번"));
        assertTrue("logged in 폰 조작 기록", app.store().phoneLogText().contains("페이지 ‘물 마시기’ · 알림"));
        c.pause().stop().destroy();
    }

    /** calendar.add opens the calendar's own screen filled in; a tool goes through 퍼레's act; a blocked tool says why at once. */
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void calendarAndTools() throws Exception {
        app.store().appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        ComponentName calendar = new ComponentName("com.cal", "com.cal.Edit"); pm.addActivityIfNotPresent(calendar);
        IntentFilter insert = new IntentFilter(Intent.ACTION_INSERT); insert.addCategory(Intent.CATEGORY_DEFAULT); insert.addDataType("vnd.android.cursor.dir/event");
        pm.addIntentFilterForActivity(calendar, insert);
        ComponentName clock = new ComponentName("com.clock", "com.clock.Set"); pm.addActivityIfNotPresent(clock);
        IntentFilter timer = new IntentFilter(android.provider.AlarmClock.ACTION_SET_TIMER); timer.addCategory(Intent.CATEGORY_DEFAULT); pm.addIntentFilterForActivity(clock, timer);
        org.robolectric.shadows.ShadowApplication shadow = shadowOf(app);
        String id = project("가계부");
        ActivityController<ProjectActivity> c = open(id); ProjectActivity a = c.get();
        while (shadow.getNextStartedActivity() != null) { /* earlier ones */ }

        String add = send(a, "calendar.add", new JSONObject().put("title", "치과").put("start", "2026-12-01T15:00").put("place", "강남역"));
        Dialog sheet = awaitSheet(null);
        assertNotNull(text(sheet.getWindow().getDecorView(), "‘가계부’가 새 일정을 넣으려 해요"));
        choose(sheet, "허용");
        JSONObject opened = awaitAnswer(add);
        assertTrue(opened.toString(), opened.optBoolean("ok"));
        Intent filled = shadow.getNextStartedActivity();
        assertNotNull("the calendar's own screen", filled);
        assertEquals(Intent.ACTION_INSERT, filled.getAction());
        assertEquals("치과", filled.getStringExtra(android.provider.CalendarContract.Events.TITLE));
        assertEquals("강남역", filled.getStringExtra(android.provider.CalendarContract.Events.EVENT_LOCATION));
        long begin = java.time.LocalDateTime.of(2026, 12, 1, 15, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli();
        assertEquals(begin, filled.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_BEGIN_TIME, 0));
        assertEquals(begin + 3_600_000L, filled.getLongExtra(android.provider.CalendarContract.EXTRA_EVENT_END_TIME, 0));

        // A blocked tool: refused at once, with its reason, and no sheet.
        String forget = send(a, "tool", new JSONObject().put("name", "forget").put("args", new JSONObject().put("what", "전부")));
        JSONObject blocked = awaitAnswer(forget);
        assertFalse(blocked.optBoolean("ok")); assertEquals(PageBridge.BLOCKED, blocked.optString("code"));
        assertTrue(blocked.optString("error"), blocked.optString("error").contains("지우는"));
        assertSame("no sheet for a blocked tool", sheet, ShadowDialog.getLatestDialog());
        String pay = send(a, "tool", new JSONObject().put("name", "pay").put("args", new JSONObject()));
        assertTrue(awaitAnswer(pay).optString("error").contains("결제"));

        // A timer through 퍼레's own act, 이번만.
        String t = send(a, "tool", new JSONObject().put("name", "timer").put("args", new JSONObject().put("seconds", 180)));
        Dialog tools = awaitSheet(sheet);
        assertNotNull(text(tools.getWindow().getDecorView(), "‘가계부’가 ‘타이머’ 도구를 쓰려 해요"));
        choose(tools, "이번만");
        JSONObject ran = awaitAnswer(t);
        assertTrue(ran.toString(), ran.optBoolean("ok"));
        assertEquals("3분 타이머 시작!", ran.getJSONObject("value").optString("said"));
        Intent started = shadow.getNextStartedActivity();
        assertEquals(android.provider.AlarmClock.ACTION_SET_TIMER, started.getAction()); assertEquals(180, started.getIntExtra(android.provider.AlarmClock.EXTRA_LENGTH, 0));
        kept("project:" + id, "{\"calendar\":\"allow\"}");
        String again = send(a, "tool", new JSONObject().put("name", "timer").put("args", new JSONObject().put("seconds", 60)));
        assertTrue("이번만: again while the page is open, no sheet", awaitAnswer(again).optBoolean("ok"));
        assertSame(tools, ShadowDialog.getLatestDialog());
        c.pause().stop().destroy();

        // Opened again: 이번만 ended, so it asks; closed without a choice, the call is refused and nothing is kept.
        ActivityController<ProjectActivity> c2 = open(id); ProjectActivity b = c2.get();
        String asked = send(b, "tool", new JSONObject().put("name", "timer").put("args", new JSONObject().put("seconds", 30)));
        Dialog anew = awaitSheet(tools);
        anew.dismiss(); idle();
        JSONObject closed = awaitAnswer(asked);
        assertEquals(PageBridge.DENIED, closed.optString("code")); assertEquals(PageBridge.DISMISSED, closed.optString("error"));
        assertEquals("{\"calendar\":\"allow\"}", grants("project:" + id));
        c2.pause().stop().destroy();
    }

    /** 거부 is kept and answered at once; another page still asks; memories come without the secret one; the AI's json. */
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void deniedKeptPerPageAndMemoryAndAsk() throws Exception {
        BuddyTalk.rememberStrong(app, "fact", "사용자는 고양이 두 마리를 키워", false, 3);
        BuddyTalk.rememberStrong(app, "fact", "사용자의 고양이 금고 비밀번호는 4321", true, 3);
        String one = project("공부 카드"), other = project("일기장");
        ActivityController<ProjectActivity> c = open(one); ProjectActivity a = c.get();
        String recall = send(a, "memory.recall", new JSONObject().put("query", "고양이"));
        Dialog sheet = awaitSheet(null);
        choose(sheet, "거부");
        JSONObject denied = awaitAnswer(recall);
        assertEquals(PageBridge.DENIED, denied.optString("code"));
        kept("project:" + one, "{\"memory\":\"deny\"}");
        String again = send(a, "memory.recall", new JSONObject().put("query", "고양이"));
        JSONObject refused = awaitAnswer(again);
        assertEquals(PageBridge.DENIED, refused.optString("code"));
        assertTrue("says where to change it: " + refused.optString("error"), refused.optString("error").contains("권한"));
        assertSame("refused at once, no sheet", sheet, ShadowDialog.getLatestDialog());

        // AI: the page's words to the light model as material, json parsed.
        List<String> heard = new ArrayList<>();
        PageBridgeHost.brain = (store, instructions, input) -> { heard.add(instructions); heard.add(input); return "```json\n{\"front\":\"광합성\",\"back\":\"빛으로 양분을 만들어요\"}\n```"; };
        String ask = send(a, "ask", new JSONObject().put("prompt", "광합성 카드 한 장").put("kind", "json"));
        Dialog ai = awaitSheet(sheet);
        assertNotNull(text(ai.getWindow().getDecorView(), "‘공부 카드’가 AI에게 물어보려 해요"));
        choose(ai, "허용");
        JSONObject card = awaitAnswer(ask);
        assertTrue(card.toString(), card.optBoolean("ok"));
        assertEquals("광합성", card.getJSONObject("value").optString("front"));
        assertTrue(heard.get(0), heard.get(0).contains("공부 카드") && heard.get(0).contains("JSON"));
        assertEquals("the page's words, as they were", "광합성 카드 한 장", heard.get(1));
        c.pause().stop().destroy();

        // Another page never reaches this one's choices: it asks for itself, and gets the memories without the secret one.
        ActivityController<ProjectActivity> c2 = open(other); ProjectActivity b = c2.get();
        String mine = send(b, "memory.recall", new JSONObject().put("query", "고양이 비밀번호"));
        Dialog own = awaitSheet(ai);
        assertNotNull(text(own.getWindow().getDecorView(), "‘일기장’이 기억을 찾아보려 해요"));
        choose(own, "허용");
        JSONObject found = awaitAnswer(mine);
        assertTrue(found.toString(), found.optBoolean("ok"));
        String value = found.getJSONArray("value").toString();
        assertTrue(value, value.contains("고양이 두 마리"));
        assertFalse("never the secret one: " + value, value.contains("4321") || value.contains("금고"));
        kept("project:" + one, "{\"memory\":\"deny\",\"ai\":\"allow\"}");
        kept("project:" + other, "{\"memory\":\"allow\"}");
        c2.pause().stop().destroy();
    }

    /**
     * The user's own page on 들퍼레 홈 화면: its calls come through the home screen's page (the Gate, with its secret), only for a
     * page: slot that is on the home screen; the sheet over the home screen; its grants its own ("home:page:<id>"); the answer
     * back to that frame (launcher.bridgeReply), a notification's tap opening that page of the home screen.
     */
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void aPageOnTheHomeScreen() throws Exception {
        app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putString(HomeActivity.PAGES,
            dev.jun.gyeot.core.HomeBoard.save(List.of(new dev.jun.gyeot.core.HomeBoard.Slot("page:abc", "내 대시보드"), new dev.jun.gyeot.core.HomeBoard.Slot("https://piece.test/x.html", "작품")))).commit();
        ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup();
        try {
            HomeActivity a = home.get();
            org.robolectric.util.ReflectionHelpers.setField(a, "ready", true);
            android.webkit.WebView web = org.robolectric.util.ReflectionHelpers.getField(a, "web");
            Object gate = shadowOf(web).getJavascriptInterface("Gyeot");
            String secret = org.robolectric.util.ReflectionHelpers.getField(a, "secret");
            java.lang.reflect.Method call = gate.getClass().getMethod("call", String.class, String.class, String.class); call.setAccessible(true);
            java.util.function.BiFunction<String, String, String> message = (id, src) -> {
                try { return new org.json.JSONArray().put(src).put("내 대시보드").put(new JSONObject().put("type", "gyeot").put("id", id).put("call", "notify").put("args", new JSONObject().put("title", "회의 10분 전")).toString()).toString(); }
                catch (Exception impossible) { throw new IllegalStateException(impossible); }
            };
            // Not the page's secret, a piece from another site, a page not on the home screen: never heard.
            call.invoke(gate, "not the page's", "bridge", message.apply("gy-x", "page:abc"));
            call.invoke(gate, secret, "bridge", message.apply("gy-y", "https://piece.test/x.html"));
            call.invoke(gate, secret, "bridge", message.apply("gy-z", "page:gone"));
            for (int i = 0; i < 20; i++) { idle(); Thread.sleep(10); }
            assertNull(answer("gy-x")); assertNull(answer("gy-y")); assertNull(answer("gy-z"));
            assertNull("no sheet for them", ShadowDialog.getLatestDialog());
            // The page's own call: the sheet over the home screen, then the notification.
            call.invoke(gate, secret, "bridge", message.apply("gy-h1", "page:abc"));
            Dialog sheet = awaitSheet(null);
            View d = sheet.getWindow().getDecorView(); settle(d);
            assertNotNull(text(d, "‘내 대시보드’가 알림을 보내려 해요"));
            shot(d, "page_bridge_home_grant");
            choose(sheet, "허용");
            assertTrue(awaitAnswer("gy-h1").optBoolean("ok"));
            kept("home:page:abc", "{\"notify\":\"allow\"}");
            await(() -> String.valueOf(shadowOf(web).getLastEvaluatedJavascript()).startsWith("launcher.bridgeReply"), "the answer to the page's frame");
            String js = shadowOf(web).getLastEvaluatedJavascript();
            assertTrue("to that page alone: " + js, js.contains("\"page:abc\"") && js.contains("gy-h1"));
            Notification n = shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications().get(0);
            Intent tap = shadowOf(n.contentIntent).getSavedIntent();
            assertEquals("a tap opens the home screen", HomeActivity.class.getName(), tap.getComponent().getClassName());
            assertEquals("on that page", "내 대시보드", tap.getStringExtra(HomeActivity.PAGE));
            assertEquals("a project's grants are not the home page's", "", grants("project:abc"));
        } finally { home.pause().stop().destroy(); }
    }

    /** The ⋮ menu's 권한: each family in one compact list; taking 알림 back asks again and cancels what was set for later. */
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void permissionsListedAndRevoked() throws Exception {
        String id = project("물 마시기");
        ActivityController<ProjectActivity> c = open(id); ProjectActivity a = c.get();
        String first = send(a, "notify", new JSONObject().put("title", "물").put("at", System.currentTimeMillis() + 7_200_000L));
        Dialog sheet = awaitSheet(null);
        choose(sheet, "허용");
        assertTrue(awaitAnswer(first).optBoolean("ok"));
        assertEquals(1, PageNotices.pending(app, "project:" + id));
        String deny = send(a, "day.recall", new JSONObject().put("query", "어제"));
        Dialog day = awaitSheet(sheet); choose(day, "거부"); awaitAnswer(deny);

        // ⋮ → 권한.
        View more = find(a.getWindow().getDecorView(), v -> "프로젝트 메뉴".contentEquals(String.valueOf(v.getContentDescription())));
        assertNotNull(more); assertTrue(more.performClick()); idle();
        android.widget.PopupWindow menu = shadowOf(app).getLatestPopupWindow();
        assertNotNull("the menu", menu);
        View item = text(menu.getContentView(), "권한"); assertNotNull("권한 in the menu", item);
        assertTrue(item.performClick()); idle();
        Dialog list = awaitSheet(day);
        View d = list.getWindow().getDecorView();
        await(() -> text(d, "하루 기록") != null, "the families listed");
        settle(d);
        float dp = a.getResources().getDisplayMetrics().density;
        for (String f : new String[]{"알림", "일정", "기억", "하루 기록", "AI", "도구"}) {
            View row = rowOf(text(d, f)); assertNotNull(f, row);
            assertTrue(f + ": a list row, not a big card: " + row.getHeight() / dp + "dp", row.getHeight() <= 72 * dp);
        }
        assertNotNull("알림: allowed, one set for later", text(d, "허용함 · 예약 알림 1개"));
        assertNotNull("하루 기록: denied", text(d, "거부함"));
        assertEquals("no 완료 bar", View.GONE, ((Ui.Sheet) list).primary.getVisibility());
        shot(d, "page_bridge_permissions");
        // 알림 → 쓸 때 묻기: kept no more, and what it set for later is cancelled.
        assertTrue(rowOf(text(d, "알림")).performClick()); idle();
        android.widget.PopupWindow choices = shadowOf(app).getLatestPopupWindow();
        View ask = text(choices.getContentView(), "쓸 때 묻기"); assertNotNull(ask);
        assertTrue(ask.performClick());
        await(() -> !grants("project:" + id).contains("notify"), "알림 taken back");
        assertEquals(0, PageNotices.pending(app, "project:" + id));
        await(() -> text(d, "허용함 · 예약 알림 1개") == null && text(d, "허용함") == null, "the list again, 알림 no longer allowed");
        // Asked again on the next use.
        list.dismiss(); idle();
        String next = send(a, "notify", new JSONObject().put("title", "물"));
        Dialog anew = awaitSheet(list);
        assertNotNull(text(anew.getWindow().getDecorView(), "‘물 마시기’가 알림을 보내려 해요"));
        choose(anew, "거부");
        assertEquals(PageBridge.DENIED, awaitAnswer(next).optString("code"));
        c.pause().stop().destroy();
    }
}
