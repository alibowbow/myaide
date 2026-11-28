package dev.jun.gyeot;

import android.Manifest;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothClass;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.os.Looper;
import android.webkit.WebView;
import dev.jun.gyeot.core.DayLog;
import dev.jun.gyeot.core.Habits;
import dev.jun.gyeot.core.SavedPlaces;
import dev.jun.gyeot.core.Triggers;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
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
import org.robolectric.util.ReflectionHelpers;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 하루 기록's moments and 퍼레's routine suggestion (core/Habits, 준's plan stage 2: "같은 행동이 반복되면 퍼레가 루틴을 딱 한 번
 * 제안해요"): the car's ACL broadcast and the charger's broadcast each write one line with no trigger at all, and nothing while
 * 하루 기록 is off; a saved place the phone is found at is a line; a habit in the lines is suggested once, with its two
 * choices, on the home screen; 열어 줘 makes the trigger (kept in TriggerStore, said back), 괜찮아 is kept and never asked again,
 * let go once it waits a week, twice it is dropped.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class HabitsTests {
    private GyeotApp app; private AppStore store;
    private static final ZoneId ZONE = ZoneId.systemDefault();
    private static final String KIA = "AA:BB:CC:DD:EE:FF", MAP = "com.nhn.android.nmap";
    private final java.util.function.LongSupplier realClock = BuddyHabits.clock;

    @Before public void setUp() throws Exception {
        app = (GyeotApp) RuntimeEnvironment.getApplication(); store = app.store();
        settleIo();
        store.settings.edit().putBoolean(DayLogs.ON, true).remove(WakeWordFiles.CAR).remove("savedPlaces").commit();
        app.getSharedPreferences(TriggerStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        TriggerStore.forget(); BuddyHabits.clear(app);
        DayLog.flush(); DayLog.book().clear();
        app.getSharedPreferences("gyeot_daylog", Context.MODE_PRIVATE).edit().clear().commit();
        ReflectionHelpers.setStaticField(DayLogs.class, "seen", null); ReflectionHelpers.setStaticField(DayLogs.class, "lookedAt", 0L);
    }
    @After public void tearDown() throws Exception {
        BuddyHabits.clock = realClock;
        TriggerStore.change(app, all -> new ArrayList<>()); TriggerWatch.sync(app); settleIo(); TriggerStore.forget();
        DayLog.flush();
    }
    private static void settleIo() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        TriggerWatch.IO.execute(latch::countDown);
        assertTrue("the triggers' thread", latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    private void idle() { shadowOf(Looper.getMainLooper()).idle(); shadowOf(TriggerWatch.looper()).idle(); }
    private static LocalDate today() { return LocalDate.now(ZONE); }
    private static long at(LocalDate d, int hour, int minute) { return d.atTime(hour, minute).atZone(ZONE).toInstant().toEpochMilli(); }
    private List<DayLog.Event> lines(String kind) {
        // Twice: a line handed over from the day log's own thread (DayLogs.here, proximity) is queued behind the first wait.
        DayLog.flush(); DayLog.flush();
        List<DayLog.Event> out = new ArrayList<>();
        for (DayLog.Event e : DayLog.book().day(today())) if (e.kind().equals(kind)) out.add(e);
        return out;
    }
    private BluetoothAdapter adapter() { return app.getSystemService(android.bluetooth.BluetoothManager.class).getAdapter(); }
    private BluetoothDevice device(String address, String name, int bluetoothClass, boolean bonded) {
        BluetoothDevice d = adapter().getRemoteDevice(address);
        shadowOf(d).setName(name);
        if (bluetoothClass != 0) shadowOf(d).setBluetoothClass(ReflectionHelpers.callConstructor(BluetoothClass.class, ReflectionHelpers.ClassParameter.from(int.class, bluetoothClass)));
        shadowOf(d).setBondState(bonded ? BluetoothDevice.BOND_BONDED : BluetoothDevice.BOND_NONE);
        return d;
    }
    private void acl(BluetoothDevice d, boolean in) throws Exception {
        new TriggerReceiver.Bluetooth().onReceive(app, new Intent(in ? BluetoothDevice.ACTION_ACL_CONNECTED : BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, d));
        settleIo();
    }
    private int bluetoothDoor() { return app.getPackageManager().getComponentEnabledSetting(new ComponentName(app, TriggerReceiver.Bluetooth.class)); }

    // ── the car: its ACL broadcast is a line with no trigger, nothing while off ──
    @Test public void aCarLinkIsOneLineWithNoTrigger() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        store.settings.edit().putString(WakeWordFiles.CAR, KIA).putString(WakeWordFiles.CAR_NAME, "Kia Motors").commit();
        assertTrue("no trigger at all", TriggerStore.list(app).isEmpty());
        TriggerWatch.sync(app);
        assertEquals("the ACL door is on while 하루 기록 is", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, bluetoothDoor());
        BluetoothDevice kia = device(KIA, "Kia Motors", 0x200408, true);
        acl(kia, true);
        List<DayLog.Event> car = lines(DayLog.CAR);
        assertEquals(car.toString(), 1, car.size());
        assertEquals("차에 탐", car.get(0).text()); assertEquals("Kia Motors", car.get(0).app());
        acl(kia, true);
        assertEquals("a link back within minutes is the same drive", 1, lines(DayLog.CAR).size());
        acl(kia, false);
        assertEquals("getting out", 2, lines(DayLog.CAR).size());
        // Not the 호출어's car, but a car's name: the car too. Earbuds by their name; a watch is no line.
        acl(device("12:34:56:78:9A:BC", "Hyundai BlueLink", 0x200408, true), true);
        assertEquals(3, lines(DayLog.CAR).size());
        acl(device("11:22:33:44:55:66", "Galaxy Buds2 Pro", 0x240404, true), true);
        List<DayLog.Event> buds = lines(DayLog.DEVICE);
        assertEquals(buds.toString(), 1, buds.size()); assertEquals("‘Galaxy Buds2 Pro’ 연결", buds.get(0).text());
        acl(device("66:55:44:33:22:11", "Galaxy Watch6", 0x000704, true), true);
        acl(device("77:55:44:33:22:11", "JBL Flip", 0x240414, false), true);
        assertEquals("a watch, and a speaker never paired: no line", 1, lines(DayLog.DEVICE).size());
        // Off: the door closes, and a broadcast that still comes is no line.
        DayLogs.turn(app, store, false); settleIo();
        assertEquals("off: the door is off", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, bluetoothDoor());
        acl(kia, false);
        assertEquals("off: nothing written", 3, lines(DayLog.CAR).size());
        assertEquals(4, DayLog.book().count(today()));
        DayLogs.turn(app, store, true); settleIo();
        assertEquals(PackageManager.COMPONENT_ENABLED_STATE_ENABLED, bluetoothDoor());
        // Without 근처 기기 (never asked for here) the door stays shut.
        shadowOf(app).denyPermissions(Manifest.permission.BLUETOOTH_CONNECT); TriggerWatch.sync(app);
        assertEquals("no new permission: no door", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, bluetoothDoor());
    }

    // ── the charger: its broadcast on the receiver of this process, with no trigger ──
    @Test public void aChargerBroadcastIsOneLineWithNoTrigger() throws Exception {
        assertTrue(TriggerStore.list(app).isEmpty());
        TriggerWatch.sync(app);
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED)); idle();
        List<DayLog.Event> plugged = lines(DayLog.CHARGE);
        assertEquals(plugged.toString(), 1, plugged.size()); assertEquals("충전기 꽂음", plugged.get(0).text());
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED)); idle();
        assertEquals("a flaky cable is one line", 1, lines(DayLog.CHARGE).size());
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_DISCONNECTED)); idle();
        assertEquals("unplugged", 2, lines(DayLog.CHARGE).size()); assertEquals("충전기 뺌", lines(DayLog.CHARGE).get(1).text());
        DayLogs.turn(app, store, false); settleIo();
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED)); idle();
        DayLog.flush();
        assertEquals("off: nothing written", 2, DayLog.book().count(today()));
    }

    // ── a saved place: the fix the phone already has, when the home screen comes, against 자주 가는 곳 ──
    @Test public void aSavedPlaceTheHomeScreenFindsIsALine() throws Exception {
        store.settings.edit().putString("savedPlaces", SavedPlaces.save(List.of(new SavedPlaces.Saved(List.of("집"), "우리 집", "서울", 37.5, 127.0)))).commit();
        shadowOf(app).grantPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        LocationManager lm = app.getSystemService(LocationManager.class);
        shadowOf(lm).setProviderEnabled(LocationManager.GPS_PROVIDER, true);
        java.util.function.BiConsumer<Double, Float> fix = (lat, acc) -> {
            Location l = new Location(LocationManager.GPS_PROVIDER); l.setLatitude(lat); l.setLongitude(127.0); l.setAccuracy(acc); l.setTime(System.currentTimeMillis());
            shadowOf(lm).setLastKnownLocation(LocationManager.GPS_PROVIDER, l);
            ReflectionHelpers.setStaticField(DayLogs.class, "lookedAt", 0L);
            DayLogs.here(app);
        };
        fix.accept(37.52, 20f);
        assertTrue("away first: where the phone is, no line", lines(DayLog.VISIT).isEmpty());
        fix.accept(37.5001, 20f);
        List<DayLog.Event> home = lines(DayLog.VISIT);
        assertEquals(home.toString(), 1, home.size()); assertEquals("‘집’ 도착", home.get(0).text());
        fix.accept(37.5001, 20f);
        assertEquals("still home: no new line", 1, lines(DayLog.VISIT).size());
        fix.accept(37.53, 3000f);
        assertEquals("a coarse fix is not believed", 1, lines(DayLog.VISIT).size());
        // Without a location permission given before, nothing is read (none is asked for).
        shadowOf(app).denyPermissions(Manifest.permission.ACCESS_FINE_LOCATION);
        fix.accept(37.53, 20f);
        assertEquals("no permission: nothing", 1, lines(DayLog.VISIT).size());
    }

    // ── a place a trigger already watches: Android's proximity alert is a line too ──
    @Test public void aPlaceAlertIsALine() throws Exception {
        Triggers.Trigger work = Triggers.make(Triggers.Condition.place(Triggers.ARRIVE, "회사", 37.56, 126.97, 150), Triggers.Action.tell(""), Triggers.EVERY, "회사 도착하면 알려 줘", System.currentTimeMillis() - 3_600_000L);
        TriggerStore.change(app, all -> Triggers.add(all, work));
        java.util.function.Consumer<Boolean> alert = in -> {
            new TriggerReceiver.Place().onReceive(app, new Intent(TriggerWatch.PLACE_ACTION).setData(android.net.Uri.parse("gyeot-trigger:" + work.id())).putExtra(LocationManager.KEY_PROXIMITY_ENTERING, in));
            try { settleIo(); } catch (Exception e) { throw new AssertionError(e); }
        };
        alert.accept(true);
        assertTrue("Android says entering whenever the alert is set: a first look is no arrival", lines(DayLog.VISIT).isEmpty());
        alert.accept(false);
        alert.accept(true);
        alert.accept(true);
        List<DayLog.Event> v = lines(DayLog.VISIT);
        assertEquals(v.toString(), 2, v.size());
        assertEquals("‘회사’에서 나옴", v.get(0).text()); assertEquals("‘회사’ 도착", v.get(1).text());
    }

    // ── the habit: found once a day, shown once with its choices, a yes makes the trigger ──
    /** Five mornings back from day: the car at 08:00, Naver Map at 08:02, the day's other apps. */
    private void drives(LocalDate day) {
        DayLog.Book b = DayLog.book();
        for (int back = 1; back <= 5; back++) {
            LocalDate d = day.minusDays(back);
            b.put(DayLog.car("Kia Motors", KIA, true, at(d, 8, 0)));
            b.put(DayLog.Event.of(at(d, 8, 2), DayLog.APP, "네이버 지도", "네이버 지도", "", "").withGroup("app:" + MAP).withDuration(12 * 60_000L));
            b.put(DayLog.Event.of(at(d, 12, 30), DayLog.APP, "카카오톡", "카카오톡", "", "").withGroup("app:com.kakao.talk").withDuration(3 * 60_000L));
        }
    }
    private void clockAt(LocalDate d) { long t = at(d, 20, 0); BuddyHabits.clock = () -> t; }
    @Test public void theSuggestionComesOnceAndAYesMakesTheTrigger() throws Exception {
        // 근처 기기 was given (the car's lines need it): the trigger needs nothing more.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        drives(today()); clockAt(today());
        Habits.Suggestion s = BuddyHabits.due(app);
        assertNotNull("five mornings of the car and Naver Map", s);
        JSONObject page = new JSONObject(BuddyHabits.page(s));
        assertEquals("차 타면 네이버 지도 자주 켜더라. 다음부터 차 타면 바로 열어 줄까?", page.getString("text"));
        assertEquals("열어 줘", page.getString("yes")); assertEquals("괜찮아", page.getString("no"));
        assertEquals("the same one until it is shown", s.key(), BuddyHabits.due(app).key());
        assertEquals("", BuddyHabits.heard(app, "car|other", "shown")[0]);
        BuddyHabits.heard(app, s.key(), "shown");
        assertNull("shown once: no other today", BuddyHabits.due(app));
        String[] back = BuddyHabits.heard(app, s.key(), "yes");
        assertEquals("said back in one line", "좋아! 차에 타면 네이버 지도 열게. 매번.", back[0]);
        List<Triggers.Trigger> all = TriggerStore.list(app);
        assertEquals("the trigger is kept", 1, all.size());
        Triggers.Trigger t = all.get(0);
        assertEquals(Triggers.BLUETOOTH, t.when().kind()); assertEquals(Triggers.CONNECT, t.when().edge()); assertEquals(KIA, t.when().id());
        assertEquals(Triggers.OPEN_APP, t.then().kind()); assertEquals("네이버 지도", t.then().target()); assertTrue(t.every() && t.active());
        assertEquals("a second tap does nothing", "", BuddyHabits.heard(app, s.key(), "yes")[0]);
        assertEquals("still one", 1, TriggerStore.list(app).size());
        // The days after: the same habit is never asked again (it has its trigger now).
        LocalDate next = today().plusDays(1); drives(next); clockAt(next);
        assertNull(BuddyHabits.due(app));
    }
    @Test public void aNoIsKeptAndALetGoWaitsAWeek() throws Exception {
        drives(today()); clockAt(today());
        Habits.Suggestion s = BuddyHabits.due(app);
        assertNotNull(s);
        BuddyHabits.heard(app, s.key(), "shown");
        assertEquals("알겠어, 다시 안 물어볼게.", BuddyHabits.heard(app, s.key(), "no")[0]);
        assertTrue("no trigger", TriggerStore.list(app).isEmpty());
        assertEquals("the refusal is kept", Habits.REFUSED, Habits.load(BuddyHabits.prefs(app).getString("asked", "{}")).get(s.key()).state());
        for (int later : new int[]{1, 8, 30}) {
            LocalDate d = today().plusDays(later); drives(d); clockAt(d);
            assertNull("a no is never asked again (" + later + " days on)", BuddyHabits.due(app));
        }
        // Let go (shown, no answer): not again for a week, once more after it, then dropped.
        DayLog.book().clear(); BuddyHabits.clear(app);
        drives(today()); clockAt(today());
        Habits.Suggestion first = BuddyHabits.due(app);
        assertNotNull(first); BuddyHabits.heard(app, first.key(), "shown");
        LocalDate d3 = today().plusDays(3); drives(d3); clockAt(d3);
        assertNull("let go three days ago", BuddyHabits.due(app));
        LocalDate d8 = today().plusDays(8); drives(d8); clockAt(d8);
        Habits.Suggestion again = BuddyHabits.due(app);
        assertNotNull("a week later, once more", again); assertEquals(first.key(), again.key());
        BuddyHabits.heard(app, again.key(), "shown");
        LocalDate d20 = today().plusDays(20); drives(d20); clockAt(d20);
        assertNull("let go twice: dropped", BuddyHabits.due(app));
        // 하루 기록 off: nothing is looked for.
        DayLog.book().clear(); BuddyHabits.clear(app); drives(today()); clockAt(today());
        DayLogs.turn(app, store, false); settleIo();
        assertNull("off: no suggestion", BuddyHabits.due(app));
        DayLogs.turn(app, store, true); settleIo();
    }

    // ── on the home screen: pushed to the page once with its choices; the page's tap reaches the trigger ──
    @Test public void theHomeScreenSaysItAndItsTapMakesTheTrigger() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        drives(today()); clockAt(today());
        ActivityController<HomeActivity> home = Robolectric.buildActivity(HomeActivity.class).setup();
        try {
            HomeActivity a = home.get();
            ReflectionHelpers.setField(a, "ready", true);
            WebView web = ReflectionHelpers.getField(a, "web");
            ReflectionHelpers.callInstanceMethod(a, "habits");
            String pushed = await(web, "launcher.routine");
            assertNotNull("pushed to the page", pushed);
            JSONObject o = new JSONObject(pushed.substring(pushed.indexOf('(') + 1, pushed.lastIndexOf(')')));
            assertEquals("car|" + MAP, o.getString("key")); assertTrue(o.getString("text").startsWith("차 타면 네이버 지도 자주 켜더라"));
            assertEquals("열어 줘", o.getString("yes")); assertEquals("괜찮아", o.getString("no"));
            // The page's bridge: shown, then 열어 줘.
            Object gate = shadowOf(web).getJavascriptInterface("Gyeot");
            String secret = ReflectionHelpers.getField(a, "secret");
            java.lang.reflect.Method call = gate.getClass().getMethod("call", String.class, String.class, String.class); call.setAccessible(true);
            call.invoke(gate, secret, "routine", "[\"car|" + MAP + "\",\"shown\"]");
            call.invoke(gate, "not the page's", "routine", "[\"car|" + MAP + "\",\"yes\"]");
            call.invoke(gate, secret, "routine", "[\"car|" + MAP + "\",\"yes\"]");
            String said = await(web, "launcher.jobDone");
            assertNotNull("said back on the page", said);
            assertTrue(said, said.contains("좋아! 차에 타면 네이버 지도 열게. 매번."));
            assertEquals("the trigger, once", 1, TriggerStore.list(app).size());
            ReflectionHelpers.callInstanceMethod(a, "habits");
            Thread.sleep(300); shadowOf(Looper.getMainLooper()).idle();
            assertFalse("not pushed again today", String.valueOf(shadowOf(web).getLastEvaluatedJavascript()).startsWith("launcher.routine"));
        } finally { home.pause().stop().destroy(); }
    }
    /** The page's script that starts with start, once the home screen's own thread has handed it over (10 s at most). */
    private static String await(WebView web, String start) throws Exception {
        for (int i = 0; i < 200; i++) {
            shadowOf(Looper.getMainLooper()).idle();
            String last = shadowOf(web).getLastEvaluatedJavascript();
            if (last != null && last.startsWith(start)) return last;
            Thread.sleep(50);
        }
        return null;
    }
}
