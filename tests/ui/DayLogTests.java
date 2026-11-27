package dev.jun.gyeot;

import android.app.Activity;
import android.app.Dialog;
import android.app.Notification;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.view.accessibility.AccessibilityEvent;
import android.widget.Switch;
import android.widget.TextView;
import dev.jun.gyeot.core.BuddyJobs;
import dev.jun.gyeot.core.DayLog;
import dev.jun.gyeot.core.NaverPlaces;
import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadows.ShadowDialog;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 하루 타임라인 (준이 승인한 계획 2단계): the sources' hooks write the day's lines (a notice, food, a booking, a route, the app in
 * front, 퍼레's act and job), codes and money never; off writes nothing; day_recall answers "지난주 그 집" from a booking and a
 * route with their time and app; the prompts carry a short digest; the settings row stays compact.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class DayLogTests {
    private GyeotApp app; private AppStore store;
    private final java.util.function.LongSupplier realClock = DayLogs.clock; private final DayLogs.Apps realApps = DayLogs.apps;
    private static final ZoneId ZONE = ZoneId.systemDefault();

    @Before public void setUp() throws Exception {
        app = (GyeotApp) RuntimeEnvironment.getApplication(); store = app.store();
        store.settings.edit().putBoolean("noticeOn", true).putBoolean(DayLogs.ON, true).commit();
        DayLog.flush(); DayLog.book().clear();
        NoticeListener.forget(); BuddyJobs.clear(); BuddyMisses.clear(app);
        java.lang.reflect.Field last = NoticeListener.class.getDeclaredField("lastDelivery"); last.setAccessible(true); last.set(null, null);
    }
    @After public void tearDown() { DayLogs.clock = realClock; DayLogs.apps = realApps; BuddyJobs.clear(); DayLog.flush(); }

    private static long at(LocalDate d, int hour, int minute) { return d.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli(); }
    private static LocalDate today() { return LocalDate.now(ZONE); }
    private static LocalDate monday() { return today().minusDays(today().getDayOfWeek().getValue() - 1L); }
    private List<DayLog.Event> day(LocalDate d) { DayLog.flush(); return DayLog.book().day(d); }
    /** The day's file as written (to see what never reaches it). */
    private String file(LocalDate d) throws Exception { DayLog.flush(); File f = new File(new File(app.getFilesDir(), "daylog"), d + ".jsonl"); return f.isFile() ? Files.readString(f.toPath(), StandardCharsets.UTF_8) : ""; }
    private DayLog.Event find(LocalDate d, String kind, String words) {
        for (DayLog.Event e : day(d)) if (e.kind().equals(kind) && (e.app() + " " + e.text() + " " + e.place()).contains(words)) return e;
        return null;
    }
    /** Waits for a line that comes from another thread first (the notice listener's own). */
    private DayLog.Event await(LocalDate d, String kind, String words) throws Exception {
        for (int i = 0; i < 100; i++) { DayLog.Event e = find(d, kind, words); if (e != null) return e; Thread.sleep(30); }
        return null;
    }
    private void label(String pkg, String name) {
        PackageInfo info = new PackageInfo(); info.packageName = pkg; info.applicationInfo = new ApplicationInfo();
        info.applicationInfo.packageName = pkg; info.applicationInfo.nonLocalizedLabel = name;
        shadowOf(app.getPackageManager()).installPackage(info);
    }
    private static StatusBarNotification posted(GyeotApp app, String pkg, int id, String title, String text, long when) {
        Notification n = new Notification.Builder(app, "x").setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle(title).setContentText(text)
            .setStyle(new Notification.BigTextStyle().bigText(text)).build();
        return new StatusBarNotification(pkg, pkg, id, null, Process.myUid(), 0, 0, n, Process.myUserHandle(), when);
    }

    /** A notice, food, a booking: each a short line; a code never, and a bank's notice only "알림". */
    @Test public void noticesBecomeShortLinesAndSecretsNever() throws Exception {
        label("com.kakao.talk", "카카오톡"); label("com.kbstar.kbbank", "KB스타뱅킹"); label("com.catchtable.android", "캐치테이블");
        NoticeListener listener = Robolectric.buildService(NoticeListener.class).create().get();
        long now = System.currentTimeMillis();
        listener.onNotificationPosted(posted(app, "com.kakao.talk", 1, "엄마", "오늘 저녁 일찍 들어오니? 김치찌개 끓여 놨어 빨리 와", now));
        listener.onNotificationPosted(posted(app, "com.kbstar.kbbank", 2, "입출금 알림", "급여 3,200,000원 입금 잔액 4,120,000원", now));
        listener.onNotificationPosted(posted(app, "com.samsung.android.messaging", 3, "1588-1234", "[Web발신] 인증번호 [482913]를 입력해 주세요", now));
        listener.onNotificationPosted(posted(app, "com.sampleapp", 4, "주문 접수", "BHC치킨 강남점에서 주문을 접수했어요. 40~50분 후 도착 예정", now));
        DayLog.Event mom = await(today(), DayLog.NOTICE, "엄마");
        assertNotNull("the chat's line", mom);
        assertEquals("카카오톡", mom.app());
        assertTrue(mom.text(), mom.text().startsWith("엄마: 오늘 저녁 일찍") && !mom.text().contains("빨리 와"));
        DayLog.Event bank = find(today(), DayLog.NOTICE, "KB스타뱅킹");
        assertNotNull("the bank's line", bank); assertEquals("only that one came", "알림", bank.text());
        DayLog.Event order = await(today(), DayLog.ORDER, "BHC치킨");
        assertNotNull("the order", order); assertEquals("배민", order.app()); assertEquals("BHC치킨 강남점", order.place());
        String kept = file(today());
        assertFalse("the code never reaches the file", kept.contains("482913") || kept.contains("인증번호"));
        assertFalse("the money never does", kept.contains("3,200,000") || kept.contains("입금") || kept.contains("잔액"));
        // A booking read from its notice (another thread): its place and time, posted last week.
        LocalDate wednesday = monday().minusDays(5);
        listener.onNotificationPosted(posted(app, "com.catchtable.android", 5, "캐치테이블", "[캐치테이블] 예약 확정 안내\n을지로 노가리집\n" + wednesday.plusDays(1).getMonthValue() + "월 " + wednesday.plusDays(1).getDayOfMonth() + "일 19:00, 2명", at(wednesday, 15, 0)));
        DayLog.Event booking = await(wednesday, DayLog.PLAN, "을지로 노가리집");
        assertNotNull("the booking on its day: " + day(wednesday), booking);
        assertEquals("을지로 노가리집", booking.place()); assertTrue(booking.text(), booking.text().contains("예약") && booking.text().contains("19:00"));
    }

    /** A route opened (퍼레 or the voice assistant) with its address; the app in front as sessions; 퍼레's act and job. */
    @Test public void routesAppsAndBuddysActsAreLines() throws Exception {
        store.appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        android.content.ComponentName nmap = new android.content.ComponentName("com.nhn.android.nmap", "com.nhn.android.nmap.Main");
        pm.addActivityIfNotPresent(nmap);
        android.content.IntentFilter view = new android.content.IntentFilter(Intent.ACTION_VIEW); view.addCategory(Intent.CATEGORY_DEFAULT); view.addDataScheme("nmap");
        pm.addIntentFilterForActivity(nmap, view);
        android.content.ComponentName clock = new android.content.ComponentName("com.clock", "com.clock.Set"); pm.addActivityIfNotPresent(clock);
        android.content.IntentFilter alarm = new android.content.IntentFilter(android.provider.AlarmClock.ACTION_SET_ALARM); alarm.addCategory(Intent.CATEGORY_DEFAULT); pm.addIntentFilterForActivity(clock, alarm);
        store.savedPlaces(List.of(new dev.jun.gyeot.core.SavedPlaces.Saved(List.of("집", "우리집"), "래미안", "부산 수영구", 35.15, 129.11)));

        // "집으로 가자": the saved place's route, its name and address.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"navigate\",\"destination\":\"집\"}"), "집으로 가자", false);
        DayLog.Event route = find(today(), DayLog.PLACE, "래미안");
        assertNotNull("the route: " + day(today()), route);
        assertEquals("네이버 지도", route.app()); assertEquals("‘래미안’ 길 안내", route.text()); assertTrue(route.place(), route.place().contains("부산 수영구"));
        // An alarm set (said with its time): 퍼레's act; one that waits for a yes is not a line yet.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":7}"), "7시 알람 맞춰 줘", false);
        assertNull("waiting for a yes", find(today(), DayLog.ACT, "알람"));
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"alarm\",\"hour\":7}"), "아침 7시에 깨워 줘", false);
        assertNotNull("set", find(today(), DayLog.ACT, "알람 07:00"));
        // An app that is not here: said as not done.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"open_app\",\"app\":\"없는앱\"}"), "없는앱 열어 줘", false);
        assertNotNull(find(today(), DayLog.ACT, "없는앱 열기 · 못 함"));
        // A coin is no line.
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"coin\"}"), "동전 던져 줘", false);
        assertNull(find(today(), DayLog.ACT, "동전"));
        // A job 퍼레 took on: one line, said again as it ends.
        BuddyJobs.Job job = BuddyJobs.start(BuddyJobs.PHONE, "배민", "지난번 메뉴 다시 담기", System.currentTimeMillis());
        BuddyJobs.progress(job, 3, "배민", "장바구니 열기");
        BuddyJobs.end(job, BuddyJobs.State.DONE, "담았어요", System.currentTimeMillis());
        DayLog.flush(); DayLog.flush();
        List<DayLog.Event> jobs = new ArrayList<>(); for (DayLog.Event e : day(today())) if (DayLog.JOB.equals(e.kind())) jobs.add(e);
        assertEquals("one line a job: " + jobs, 1, jobs.size()); assertTrue(jobs.get(0).text(), jobs.get(0).text().endsWith("끝남"));

        // The app in front, from 폰 조작's window events: sessions, the keyboard and the shade changing nothing.
        long[] now = {at(today(), 9, 0)}; DayLogs.clock = () -> now[0];
        DayLogs.apps = (c, pkg, cls) -> switch (pkg) { case "com.nhn.android.nmap" -> "네이버 지도"; case "com.kakao.talk" -> "카카오톡"; case "com.launcher" -> DayLogs.HOME; default -> null; };
        PhoneService service = Robolectric.buildService(PhoneService.class).create().get();
        java.util.function.BiConsumer<String, String> window = (pkg, cls) -> {
            AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED); e.setPackageName(pkg); e.setClassName(cls); service.onAccessibilityEvent(e);
        };
        window.accept("com.nhn.android.nmap", "com.nhn.android.nmap.Main");
        now[0] += 7 * 60_000L; window.accept("com.samsung.android.honeyboard", "android.inputmethodservice.SoftInputWindow");
        now[0] += 5 * 60_000L; window.accept("com.android.systemui", "android.widget.FrameLayout");
        now[0] += 60_000L; window.accept("com.kakao.talk", "com.kakao.talk.activity.main.MainActivity");
        now[0] += 2 * 60_000L; window.accept("com.launcher", "com.launcher.Home");
        DayLog.flush(); DayLog.flush();
        DayLog.Event map = find(today(), DayLog.APP, "네이버 지도"), talk = find(today(), DayLog.APP, "카카오톡");
        assertNotNull("the map's session", map); assertEquals("13 minutes, the keyboard and shade inside it", 13 * 60_000L, map.duration());
        assertNotNull("then KakaoTalk's", talk); assertEquals(2 * 60_000L, talk.duration());
        // The screen going off ends the one in front.
        window.accept("com.nhn.android.nmap", "com.nhn.android.nmap.Main"); now[0] += 2 * 60_000L;
        DayLogs.screenOff(); DayLog.flush(); DayLog.flush();
        assertEquals("back within three minutes: the same session", 15 * 60_000L, find(today(), DayLog.APP, "네이버 지도").duration());
    }

    /** Off: nothing kept, whatever comes; day_recall says it is off, with the one tap that turns it on. */
    @Test public void offKeepsNothing() throws Exception {
        DayLogs.turn(store, false);
        label("com.kakao.talk", "카카오톡");
        NoticeListener listener = Robolectric.buildService(NoticeListener.class).create().get();
        listener.onNotificationPosted(posted(app, "com.kakao.talk", 9, "민수", "내일 7시 강남역에서 보자", System.currentTimeMillis()));
        BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"open_app\",\"app\":\"없는앱\"}"), "없는앱 열어 줘", false);
        DayLog.add(DayLog.TALK, "퍼레", "‘아무 말’");
        Thread.sleep(200); DayLog.flush();
        assertEquals("nothing written", 0, DayLog.book().count(today()));
        assertTrue(DayLog.book().kept().isEmpty());
        BuddyVoice.Outcome o = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"day_recall\",\"query\":\"그 집\"}"), "지난주 그 집 어디였지?", false);
        assertTrue(o.say(), o.say().startsWith("하루 기록이 꺼져 있어서"));
        assertTrue("kept as not done", o.missed());
        BuddyMisses.Miss miss = BuddyMisses.list(app).get(0);
        assertEquals("daylog", miss.fix()); assertEquals("하루 기록 켜기", BuddyMisses.fixLabel(miss.fix()));
        Intent fix = BuddyMisses.fixIntent(app, miss.fix());
        assertTrue("its sheet", fix.getBooleanExtra(DecorActivity.DAYLOG, false));
        DayLogs.turn(store, true);
    }

    /** "지난주 그 집 어디였지?": the booking and the route of last week, with their time and app; nothing found says where it looked. */
    @Test public void recallsLastWeeksPlaceFromRealLines() throws Exception {
        store.appVisible = true;
        org.robolectric.shadows.ShadowPackageManager pm = shadowOf(app.getPackageManager());
        android.content.ComponentName nmap = new android.content.ComponentName("com.nhn.android.nmap", "com.nhn.android.nmap.Main");
        pm.addActivityIfNotPresent(nmap);
        android.content.IntentFilter view = new android.content.IntentFilter(Intent.ACTION_VIEW); view.addCategory(Intent.CATEGORY_DEFAULT); view.addDataScheme("nmap");
        pm.addIntentFilterForActivity(nmap, view);
        LocalDate thursday = monday().minusDays(4);
        // Last Thursday 18:40, the route there (as the voice assistant or 퍼레 opens it, its place found first).
        long[] now = {at(thursday, 18, 40)}; DayLogs.clock = () -> now[0];
        NaverPlaces.Place place = new NaverPlaces.Place("을지로 노가리집", "서울 중구 을지로 123", 37.566, 126.991);
        DayLogs.placeHint(place);
        assertTrue(DeviceActionRunner.run(app, dev.jun.gyeot.core.DeviceActions.navigateTo(place, "car"), false).optBoolean("ok"));
        // Something else today, so the week's place is not the only line.
        now[0] = System.currentTimeMillis(); DayLog.add(DayLog.navigated("네이버 지도", "navigation", "강남역", "", now[0]));
        DayLog.flush();
        BuddyVoice.Outcome o = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"day_recall\",\"query\":\"그 집\"}"), "지난주 그 집 어디였지?", false);
        assertFalse(o.missed());
        assertTrue(o.say(), o.say().contains("을지로 노가리집") && o.say().contains("오후 6:40") && o.say().contains("네이버 지도") && o.say().contains("서울 중구 을지로 123"));
        assertFalse("not today's: " + o.say(), o.say().contains("강남역"));
        // Typed in 퍼레와 대화 (the text form), the same answer, found by the words typed.
        JSONObject typed = BuddyTalk.act("찾아볼게!\n할일: {\"tool\":\"day_recall\",\"query\":\"그 집\"}");
        assertEquals("day_recall", typed.optString("tool"));
        // Nothing there: what it looked through, never a guess.
        BuddyVoice.Outcome none = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"day_recall\",\"query\":\"스타벅스\"}"), "지난주에 간 스타벅스 어디였지?", false);
        assertTrue(none.say(), none.say().contains("‘스타벅스’는 없었어") && none.say().contains("길 안내"));
        assertFalse(none.say(), none.say().contains("노가리"));
        // The main chat's question about the past carries the same lines, as data.
        String block = DayLogs.mainChat(app, "지난주 그 집 어디였지?", System.currentTimeMillis());
        assertTrue(block, block.startsWith("Untrusted data") && block.contains("을지로 노가리집"));
        assertEquals("a question not about the past carries nothing", "", DayLogs.mainChat(app, "오늘 날씨 어때?", System.currentTimeMillis()));
    }

    /** The night's memory and 오늘의 제안 get the day in short lines, capped however busy the day was. */
    @Test public void promptsGetACappedDigest() throws Exception {
        LocalDate yesterday = today().minusDays(1);
        for (int i = 0; i < 300; i++) DayLog.add(DayLog.notice("com.kakao.talk", "카카오톡", "친구" + (i % 30), "메시지 내용 " + i, at(yesterday, 0, 0) + i * 240_000L));
        for (int i = 0; i < 80; i++) DayLog.add(DayLog.navigated("네이버 지도", "navigation", "장소" + i, "", at(yesterday, 0, 0) + i * 900_000L));
        DayLog.add(DayLog.Event.of(at(yesterday, 9, 0), DayLog.APP, "YouTube", "YouTube", "", "").withGroup("app:yt").withDuration(80 * 60_000L));
        DayLog.flush();
        String digest = DayLogs.digest(app, yesterday, dev.jun.gyeot.core.Proactive.DAY_LIMIT);
        assertTrue("capped: " + digest.length(), digest.length() <= dev.jun.gyeot.core.Proactive.DAY_LIMIT);
        assertTrue(digest, digest.contains("하루 기록") && digest.contains("YouTube 1시간 20분") && digest.contains("카카오톡"));
        assertFalse("no message words: " + digest, digest.contains("메시지 내용"));
        String brief = dev.jun.gyeot.core.Proactive.briefInput(System.currentTimeMillis(), ZONE, "", "", "", List.of(), new ArrayList<>(), new org.json.JSONArray(), digest);
        assertTrue(brief.contains("어제 하루 (휴대폰의 하루 기록):\n" + digest.substring(0, 20)));
        assertTrue("the morning's material stays in bounds", brief.length() <= dev.jun.gyeot.core.Proactive.MATERIAL_LIMIT);
        // The night's: before six it is still yesterday's day.
        String night = BuddyTalk.dreamInput(app, at(today(), 3, 0));
        assertTrue(night, night.contains(yesterday.getMonthValue() + "월 " + yesterday.getDayOfMonth() + "일") && night.contains("하루 기록"));
        int start = night.indexOf("하루 기록:"), end = night.indexOf("지금까지의 발견 노트");
        assertTrue("the day's part is short: " + (end - start), start >= 0 && end - start <= BuddyTalk.DAY_DIGEST + 20);
        // Off: none.
        DayLogs.turn(store, false);
        assertEquals("", DayLogs.digest(app, yesterday, 900));
        DayLogs.turn(store, true);
    }

    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
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
    private static View rowOf(View t) { View v = t; while (v != null && !v.isClickable() && v.getParent() instanceof View p) v = p; return v; }

    /** 화면 꾸미기 → 하루 기록: one compact row with its switch; its sheet holds how long (a switch the width of its words) and 모두 지우기. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void theSettingsRowIsCompact() throws Exception {
        DayLog.add(DayLog.TALK, "퍼레", "‘내일 날씨’"); DayLog.flush();
        Activity a = Robolectric.buildActivity(DecorActivity.class).setup().get();
        View decor = a.getWindow().getDecorView();
        for (int i = 0; i < 20; i++) { Thread.sleep(20); DayLog.flush(); shadowOf(Looper.getMainLooper()).idle(); }
        settle(decor);
        View name = find(decor, v -> v instanceof TextView t && "하루 기록".contentEquals(t.getText()));
        assertNotNull("the row", name);
        View row = rowOf(name); float dp = a.getResources().getDisplayMetrics().density;
        View memory = rowOf(find(decor, v -> v instanceof TextView t && "퍼레의 기억".contentEquals(t.getText())));
        assertTrue("a list row like its neighbour, not a big card: " + row.getHeight() / dp + "dp", row.getHeight() <= memory.getHeight() + 1 && row.getHeight() <= 72 * dp);
        assertEquals("full width like the rows of its group", memory.getWidth(), row.getWidth());
        View sub = find(row, v -> v instanceof TextView t && t.getText().toString().startsWith("켜짐 · 90일 보관"));
        assertNotNull("its state", sub);
        assertTrue(((TextView) sub).getText().toString(), ((TextView) sub).getText().toString().endsWith("오늘 1개"));
        Switch toggle = (Switch) find(row, v -> v instanceof Switch);
        assertNotNull("its switch at the end", toggle); assertTrue(toggle.isChecked());
        shot(decor, "daylog_row");
        assertTrue(row.performClick()); shadowOf(Looper.getMainLooper()).idle();
        Dialog sheet = ShadowDialog.getLatestDialog(); assertNotNull("its sheet", sheet);
        View s = sheet.getWindow().getDecorView();
        for (int i = 0; i < 10; i++) { Thread.sleep(20); DayLog.flush(); shadowOf(Looper.getMainLooper()).idle(); }
        settle(s);
        View keep = find(s, v -> v instanceof TextView t && "보관 기간".contentEquals(t.getText()));
        assertNotNull(keep);
        View year = find(s, v -> v instanceof TextView t && "1년".contentEquals(t.getText()));
        assertNotNull("1년 a choice", year);
        assertTrue("the switch is small: " + year.getHeight() / dp + "dp", year.getHeight() <= 40 * dp);
        assertTrue("not as wide as the sheet", rowOf(year).getWidth() < s.getWidth() * 0.7);
        shot(s, "daylog_sheet");
        // 30일: kept so from now on.
        View thirty = rowOf(find(s, v -> v instanceof TextView t && "30일".contentEquals(t.getText())));
        assertTrue(thirty.performClick()); shadowOf(Looper.getMainLooper()).idle();
        assertEquals(30, DayLogs.keep(store));
        // 모두 지우기 asks first, then everything goes.
        View wipe = rowOf(find(s, v -> v instanceof TextView t && "모두 지우기".contentEquals(t.getText())));
        assertTrue(wipe.performClick()); shadowOf(Looper.getMainLooper()).idle();
        Dialog ask = ShadowDialog.getLatestDialog(); assertNotSame(sheet, ask);
        View go = find(ask.getWindow().getDecorView(), v -> v instanceof android.widget.Button b && "모두 지우기".contentEquals(b.getText()));
        assertNotNull("the question's button", go);
        assertEquals("not yet", 1, DayLog.book().count(today()));
        assertTrue(go.performClick());
        for (int i = 0; i < 10; i++) { Thread.sleep(20); DayLog.flush(); shadowOf(Looper.getMainLooper()).idle(); }
        assertEquals("gone", 0, DayLog.book().count(today())); assertTrue(DayLog.book().kept().isEmpty());
        store.settings.edit().putInt(DayLogs.KEEP, DayLog.KEEP_DAYS).commit();
    }
}
