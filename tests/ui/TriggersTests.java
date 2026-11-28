package dev.jun.gyeot;

import android.Manifest;
import android.app.Activity;
import android.app.Notification;
import android.app.NotificationManager;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Looper;
import android.os.Process;
import android.service.notification.StatusBarNotification;
import android.view.View;
import android.view.ViewGroup;
import android.widget.Switch;
import android.widget.TextView;
import dev.jun.gyeot.core.Triggers;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
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
 * 사건 트리거 (준's plan, stage 2): a trigger made by speaking to 퍼레 (the when_event tool) is said back clearly and kept;
 * each kind is heard the way the phone hears it (the charger's broadcast, a paired device's ACL broadcast, a delivery
 * app's notice, Android's proximity alert, a price read by its job) and fires once per event, with a notification in plain
 * words; a draft is never sent, only opened by a tap; and the list in 예약 작업 stays compact.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TriggersTests {
    private GyeotApp app;
    private final List<JSONObject> done = new CopyOnWriteArrayList<>();
    private TriggerFire.Doer was;
    private TriggerWatch.Quotes quotesWas;

    @Before public void fresh() throws Exception {
        app = (GyeotApp) RuntimeEnvironment.getApplication();
        // The app's own start syncs on the triggers' thread: let it finish before the test changes anything.
        settleIo();
        app.getSharedPreferences(TriggerStore.PREFS, Context.MODE_PRIVATE).edit().clear().commit();
        TriggerStore.forget(); BuddyMisses.clear(app);
        was = TriggerFire.doer; quotesWas = TriggerWatch.quotes;
        TriggerFire.doer = (c, act, heard) -> { done.add(act); return new BuddyVoice.Outcome("", true, null); };
        app.getSystemService(NotificationManager.class).cancelAll();
    }
    @After public void clean() throws Exception {
        TriggerFire.doer = was; TriggerWatch.quotes = quotesWas;
        TriggerStore.change(app, all -> new ArrayList<>()); TriggerWatch.sync(app); settleIo();
        TriggerStore.forget();
    }
    /** Waits until everything given to the triggers' thread so far has run. */
    private static void settleIo() throws Exception {
        java.util.concurrent.CountDownLatch latch = new java.util.concurrent.CountDownLatch(1);
        TriggerWatch.IO.execute(latch::countDown);
        assertTrue("the triggers' thread", latch.await(10, java.util.concurrent.TimeUnit.SECONDS));
    }
    private List<String> notices() {
        List<String> out = new ArrayList<>();
        for (Notification n : shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications()) {
            CharSequence text = n.extras.getCharSequence(Notification.EXTRA_TEXT); out.add(text == null ? "" : text.toString());
        }
        return out;
    }
    private Triggers.Trigger add(String heard) {
        Triggers.Draft d = Triggers.read("", "", "", "", "", null, heard);
        assertNotNull(heard, d.when());
        Triggers.Trigger t = Triggers.make(d.when(), d.then(), d.mode(), heard, System.currentTimeMillis() - 10 * 60_000L);
        TriggerStore.change(app, all -> Triggers.add(all, t));
        return t;
    }
    private Triggers.Trigger stored(String id) { return Triggers.find(TriggerStore.list(app), id); }
    private Triggers.Trigger addPrimed(Triggers.Trigger t) { TriggerStore.change(app, all -> Triggers.replace(all, t)); return t; }
    /** The adapter the app reads (BluetoothManager's, as WakeWordUi and TriggerWatch.bonded read it). */
    private BluetoothAdapter adapter() { return app.getSystemService(android.bluetooth.BluetoothManager.class).getAdapter(); }
    private void idle() { shadowOf(Looper.getMainLooper()).idle(); shadowOf(TriggerWatch.looper()).idle(); }

    // ── the charger: Android's broadcast on the receiver that lives while the app does ──
    @Test public void aPowerBroadcastFiresTheRightTriggerOnce() throws Exception {
        Triggers.Trigger in = add("충전기 꽂을 때마다 알려 줘"), out = add("충전기 빼면 알려 줘"), full = add("충전 다 되면 알려 줘");
        Triggers.Event now = Triggers.Event.power("", false, 60, false, System.currentTimeMillis());
        addPrimed(Triggers.prime(stored(in.id()), now)); addPrimed(Triggers.prime(stored(out.id()), now)); addPrimed(Triggers.prime(stored(full.id()), now));
        TriggerWatch.sync(app);
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED)); idle();
        assertEquals("plugged in: told", 1, stored(in.id()).times());
        assertEquals("not the unplugged one", 0, stored(out.id()).times());
        assertEquals("not full", 0, stored(full.id()).times());
        assertTrue(notices().toString(), notices().contains("충전기 꽂았네"));
        // The same plugging again (a flaky cable): the cooldown keeps it one event.
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_CONNECTED)); idle();
        assertEquals("once per event", 1, stored(in.id()).times());
        // The battery's own broadcast, full: the level trigger, once.
        Intent battery = new Intent(Intent.ACTION_BATTERY_CHANGED).putExtra(BatteryManager.EXTRA_LEVEL, 100).putExtra(BatteryManager.EXTRA_SCALE, 100)
            .putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_FULL).putExtra(BatteryManager.EXTRA_PLUGGED, BatteryManager.BATTERY_PLUGGED_AC);
        app.sendStickyBroadcast(battery); idle();
        assertEquals("full", 1, stored(full.id()).times()); assertTrue("once: done", stored(full.id()).done());
        app.sendStickyBroadcast(battery); idle();
        assertEquals("full stays full", 1, stored(full.id()).times());
        assertTrue(notices().toString(), notices().contains("충전 다 됐어 (100%)"));
        app.sendBroadcast(new Intent(Intent.ACTION_POWER_DISCONNECTED)); idle();
        assertEquals("unplugged", 1, stored(out.id()).times());
        // A trigger that needs charging has the persisted job that wakes the app when charging starts.
        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(TriggerWatch.CHARGE_JOB);
        assertNotNull("the charging job", job); assertTrue(job.isRequireCharging()); assertTrue(job.isPersisted());
    }

    // ── the car: a paired device's ACL broadcast, through its manifest receiver ──
    @Test public void aBluetoothBroadcastFiresTheCarOnce() throws Exception {
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        Triggers.Trigger car = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", "AA:BB:CC:DD:EE:FF"), Triggers.Action.tell(""), Triggers.EVERY, "차 타면 매번 알려 줘", System.currentTimeMillis());
        TriggerStore.change(app, all -> Triggers.add(all, car));
        TriggerWatch.sync(app);
        PackageManager pm = app.getPackageManager();
        assertEquals("its receiver on while a device trigger is", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(new ComponentName(app, TriggerReceiver.Bluetooth.class)));
        BluetoothDevice kia = adapter().getRemoteDevice("AA:BB:CC:DD:EE:FF");
        BluetoothDevice buds = adapter().getRemoteDevice("11:22:33:44:55:66");
        new TriggerReceiver.Bluetooth().onReceive(app, new Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, buds)); settleIo();
        assertEquals("another device", 0, stored(car.id()).times());
        new TriggerReceiver.Bluetooth().onReceive(app, new Intent(BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, kia)); settleIo();
        assertEquals("getting out is not getting in", 0, stored(car.id()).times());
        new TriggerReceiver.Bluetooth().onReceive(app, new Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, kia)); settleIo();
        assertEquals("in the car", 1, stored(car.id()).times());
        assertTrue(notices().toString(), notices().contains("차에 탔네"));
        new TriggerReceiver.Bluetooth().onReceive(app, new Intent(BluetoothDevice.ACTION_ACL_CONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, kia)); settleIo();
        assertEquals("a link back within minutes is the same drive", 1, stored(car.id()).times());
        new TriggerReceiver.Bluetooth().onReceive(app, new Intent("dev.jun.gyeot.FAKE").putExtra(BluetoothDevice.EXTRA_DEVICE, kia)); settleIo();
        assertEquals("only Android's broadcasts", 1, stored(car.id()).times());
        // No device trigger left: the receiver stays on only for 하루 기록 (the car's lines, HabitsTests); with it off, off again.
        TriggerStore.change(app, all -> Triggers.remove(all, car.id())); TriggerWatch.sync(app);
        assertEquals("하루 기록 still hears the car", PackageManager.COMPONENT_ENABLED_STATE_ENABLED, pm.getComponentEnabledSetting(new ComponentName(app, TriggerReceiver.Bluetooth.class)));
        AppStore store = app.store();
        store.settings.edit().putBoolean(DayLogs.ON, false).commit(); TriggerWatch.sync(app);
        try {
            assertEquals(PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(new ComponentName(app, TriggerReceiver.Bluetooth.class)));
            assertEquals("nothing on: the boot door is off too", PackageManager.COMPONENT_ENABLED_STATE_DISABLED, pm.getComponentEnabledSetting(new ComponentName(app, TriggerReceiver.Boot.class)));
        } finally { store.settings.edit().putBoolean(DayLogs.ON, true).commit(); }
    }

    // ── a delivery app's notice, as NoticeListener hands it over ──
    private StatusBarNotification notice(String pkg, String title, String text, boolean ongoing) {
        Notification.Builder b = new Notification.Builder(app, "x").setSmallIcon(android.R.drawable.sym_def_app_icon).setContentTitle(title).setContentText(text).setOngoing(ongoing);
        return new StatusBarNotification(pkg, pkg, (int) (Math.random() * 1000), null, Process.myUid(), 0, 0, b.build(), Process.myUserHandle(), System.currentTimeMillis());
    }
    @Test public void aNotificationFiresTheDeliveryOnce() throws Exception {
        Triggers.Trigger onWay = add("배달 출발하면 알려 줘");
        TriggerWatch.notice(app, notice("com.sampleapp", "배달의민족", "가게에서 조리를 시작했어요", true));
        assertEquals("cooking: not yet", 0, stored(onWay.id()).times());
        TriggerWatch.notice(app, notice("com.sampleapp", "배달의민족", "라이더가 음식을 픽업해 배달을 시작했어요", true));
        assertEquals("on its way: told", 1, stored(onWay.id()).times());
        assertTrue("once: done", stored(onWay.id()).done());
        assertTrue(notices().toString(), notices().contains("배달 출발했대 🛵"));
        TriggerWatch.notice(app, notice("com.sampleapp", "배달의민족", "라이더가 곧 도착해요", true));
        assertEquals("told once", 1, stored(onWay.id()).times());
        // A messenger: mom's KakaoTalk, not another's; codes are never read into anything.
        Triggers.Trigger mom = add("엄마한테 카톡 오면 알려 줘");
        TriggerWatch.notice(app, notice("com.kakao.talk", "민수", "뭐해?", false));
        assertEquals(0, stored(mom.id()).times());
        TriggerWatch.notice(app, notice("com.kakao.talk", "엄마", "인증번호 123456", false));
        assertEquals("a code is not news", 0, stored(mom.id()).times());
        TriggerWatch.notice(app, notice("com.kakao.talk", "엄마", "저녁 먹으러 와", false));
        assertEquals("mom", 1, stored(mom.id()).times());
        assertTrue(notices().toString(), notices().contains("엄마한테서 카톡 왔어"));
    }

    // ── a place: Android's proximity alert for that trigger, through the private receiver ──
    @Test public void aProximityAlertFiresTheArrivalOnce() throws Exception {
        Triggers.Trigger home = Triggers.make(Triggers.Condition.place(Triggers.ARRIVE, "집", 37.5, 127.0, 150), Triggers.Action.draft("message", "엄마", "", ""), Triggers.ONCE, "집에 도착하면 엄마한테 문자 보내 줘", System.currentTimeMillis() - 3_600_000L);
        TriggerStore.change(app, all -> Triggers.add(all, home));
        Intent alert = new Intent(TriggerWatch.PLACE_ACTION).setData(Uri.parse("gyeot-trigger:" + home.id())).putExtra(LocationManager.KEY_PROXIMITY_ENTERING, true);
        new TriggerReceiver.Place().onReceive(app, alert); settleIo();
        assertEquals("arrived", 1, stored(home.id()).times());
        assertTrue("a draft: nothing done by itself", done.isEmpty());
        String said = notices().get(0);
        assertEquals("집에 도착했어 · 엄마한테 보낼 문자 써 뒀어, 눌러서 보내 줘", said);
        // The tap opens the draft (the user sends), once.
        Notification n = shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications().get(0);
        Intent tap = shadowOf(n.contentIntent).getSavedIntent();
        assertEquals(TriggerActivity.class.getName(), tap.getComponent().getClassName());
        Activity a = Robolectric.buildActivity(TriggerActivity.class, tap).setup().get();
        for (int i = 0; i < 100 && done.isEmpty(); i++) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle(); }
        assertEquals("the message act, a draft the user sends", "message", done.get(0).optString("tool")); assertEquals("엄마", done.get(0).optString("who"));
        for (int i = 0; i < 50 && !a.isFinishing(); i++) { Thread.sleep(20); shadowOf(Looper.getMainLooper()).idle(); }
        assertTrue("the see-through screen goes", a.isFinishing());
        assertNull("a second tap does nothing", TriggerStore.take(app, tap.getStringExtra(TriggerActivity.FIRE)));
        new TriggerReceiver.Place().onReceive(app, alert); settleIo();
        assertEquals("once", 1, stored(home.id()).times());
    }

    // ── a price: the job's read with a stubbed quote ──
    @Test public void pricePollingWithAStubbedQuote() throws Exception {
        double[] price = {9.7e7};
        List<String> asked = new CopyOnWriteArrayList<>();
        TriggerWatch.quotes = (symbol, unit) -> { asked.add(symbol + "/" + unit); return price[0]; };
        BuddyVoice.Outcome o = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"비트코인 1억 넘으면\"}"), "비트코인 1억 넘으면 알려 줘", false);
        assertEquals("said back, and no price of its own", "비트코인이 1억 원을 넘으면 알려 줄게. 한 번만.", o.say());
        settleIo();
        JobInfo job = app.getSystemService(JobScheduler.class).getPendingJob(TriggerWatch.PRICE_JOB);
        assertNotNull("polled only while a price trigger exists", job);
        assertTrue("gently: five minutes at least", job.getMinLatencyMillis() >= 5 * 60_000L); assertEquals(JobInfo.NETWORK_TYPE_ANY, job.getNetworkType());
        Triggers.Trigger t = TriggerStore.list(app).get(0);
        assertTrue("under the line when made: armed", t.armed());
        assertEquals("one read", 1, TriggerWatch.prices(app));
        assertEquals(0, stored(t.id()).times());
        price[0] = 1.0023e8;
        assertEquals(1, TriggerWatch.prices(app));
        assertEquals("crossed", 1, stored(t.id()).times());
        assertTrue(notices().toString(), notices().contains("비트코인이 1억 원을 넘었어 (지금 1억 23만 원)"));
        assertEquals("done: nothing to read", 0, TriggerWatch.prices(app));
        assertTrue("won from Upbit's symbol", asked.contains("BTC/KRW"));
        TriggerWatch.sync(app);
        assertNull("no price trigger left: no polling", app.getSystemService(JobScheduler.class).getPendingJob(TriggerWatch.PRICE_JOB));
    }

    // ── the tool: made from words, said back, the way through when something is not allowed yet, listed, off, deleted ──
    @Test public void theToolMakesTriggersAndSaysThemBack() throws Exception {
        JSONObject full = BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"충전 다 되면\",\"then\":\"tell\",\"every\":true}");
        assertEquals("the act says it back", "", BuddyTools.say(full));
        assertEquals("the user's words over the brain's every", "충전이 다 되면 알려 줄게. 한 번만.", BuddyActs.run(app, full, "충전 다 되면 알려 줘", false).say());
        // The car without 근처 기기: made, and the one line why with its tap (a notification and 퍼레가 못 한 일).
        BuddyVoice.Outcome noNearby = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"차에 타면\",\"then\":\"open_app\",\"what\":\"네이버 지도\"}"), "차 타면 매번 네이버 지도 켜 줘", false);
        assertTrue(noNearby.say(), noNearby.say().startsWith("차에 타면 네이버 지도 열게. 매번.") && noNearby.say().contains("근처 기기"));
        assertEquals("allow:bluetooth", BuddyMisses.list(app).get(0).fix());
        assertEquals(TriggerActivity.class.getName(), BuddyMisses.fixIntent(app, "allow:bluetooth").getComponent().getClassName());
        assertTrue(shadowOf(app.getSystemService(NotificationManager.class)).getAllNotifications().stream().anyMatch(n -> String.valueOf(n.extras.getCharSequence(Notification.EXTRA_TITLE)).contains("권한 허용")));
        // With it, and one paired car: picked by itself, said back plainly.
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        BluetoothDevice kia = adapter().getRemoteDevice("AA:BB:CC:DD:EE:FF"); shadowOf(kia).setName("KIA MOTORS");
        BluetoothDevice buds = adapter().getRemoteDevice("11:22:33:44:55:66"); shadowOf(buds).setName("Galaxy Buds2");
        shadowOf(adapter()).setBondedDevices(new java.util.HashSet<>(List.of(kia, buds)));
        BuddyVoice.Outcome car = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"차에서 내리면\"}"), "차에서 내리면 알려 줘", false);
        assertEquals("차에서 내리면 알려 줄게. 한 번만.", car.say());
        Triggers.Trigger out = TriggerStore.list(app).get(2);
        assertEquals("the car among the paired", "AA:BB:CC:DD:EE:FF", out.when().id());
        // A place not saved: where it is saved, one tap; a message: a draft, said so.
        BuddyVoice.Outcome nowhere = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"집에 도착하면\"}"), "집에 도착하면 알려 줘", false);
        assertTrue(nowhere.missed()); assertTrue(nowhere.say(), nowhere.say().contains("자주 가는 곳")); assertEquals("place:집", BuddyMisses.list(app).get(0).fix());
        app.store().settings.edit().putString("savedPlaces", "[{\"words\":[\"집\"],\"name\":\"우리 집\",\"address\":\"서울\",\"lat\":37.5,\"lng\":127.0}]").commit();
        BuddyVoice.Outcome text = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"집에 도착하면\",\"then\":\"message\",\"who\":\"엄마\",\"text\":\"알림\"}"), "집에 도착하면 엄마한테 문자 보내 줘", false);
        assertTrue(text.say(), text.say().startsWith("집에 도착하면 엄마한테 보낼 문자를 써 둘게. 보내기는 너가 눌러 줘. 한 번만.") && text.say().contains("항상 허용"));
        assertEquals("allow:location", BuddyMisses.list(app).get(0).fix());
        Triggers.Trigger home = TriggerStore.list(app).get(3);
        assertTrue("found by 자주 가는 곳", home.when().located()); assertEquals("", home.then().text());
        // Listed, off, on, deleted by words.
        String list = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"command\":\"list\"}"), "내 조건 알림 뭐 있어?", false).say();
        assertTrue(list, list.startsWith("걸어 둔 건 네 개야.") && list.contains("차에 타면 네이버 지도 열기, 매번."));
        // The quick model's own answer (buddy_eval 2026-11-27): delete for "…꺼 줘"; the user's words say off.
        String off = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"command\":\"delete\",\"what\":\"차 타면 지도 켜 줘\",\"when\":\"차 타면\"}"), "차 타면 지도 켜는 거 꺼 줘", false).say();
        assertEquals("‘차에 타면 네이버 지도 열기’ 껐어. 다시 켜 달라면 켤게.", off);
        assertFalse(TriggerStore.list(app).get(1).on());
        String byWords = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"충전 다 되면\"}"), "충전 알림 지워 줘", false).say();
        assertEquals("the words say delete though the brain said make", "‘충전이 다 되면 알려 주기’ 지웠어.", byWords);
        assertEquals(3, TriggerStore.list(app).size());
        // Never a payment, whatever the event.
        BuddyVoice.Outcome pay = BuddyActs.run(app, BuddyTalk.loose("{\"tool\":\"when_event\",\"when\":\"집에 도착하면\",\"then\":\"phone_job\",\"what\":\"쿠팡에서 결제하기\"}"), "집에 도착하면 쿠팡에서 결제해 줘", false);
        assertTrue(pay.missed()); assertTrue(pay.say(), pay.say().contains("결제"));
        assertEquals(3, TriggerStore.list(app).size());
    }

    // ── an app Android would not open from behind: the tap does it ──
    @Test public void anAppThatCannotOpenNowWaitsForTheTap() throws Exception {
        Triggers.Trigger car = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", "AA:BB:CC:DD:EE:FF"), Triggers.Action.open("네이버 지도"), Triggers.EVERY, "차 타면 네이버 지도 켜 줘", System.currentTimeMillis());
        TriggerStore.change(app, all -> Triggers.add(all, car));
        TriggerWatch.handle(app, Triggers.Event.bluetooth(Triggers.CONNECT, "AA:BB:CC:DD:EE:FF", "KIA", System.currentTimeMillis()));
        assertTrue("not started from the background (no 폰 조작, no window)", done.isEmpty());
        assertTrue(notices().toString(), notices().contains("차에 탔네 · 눌러서 네이버 지도 열기"));
    }

    // ── the list in 예약 작업: one compact group, no new screen ──
    private static View find(View v, Predicate<View> match) {
        if (match.test(v)) return v;
        if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void all(View v, Predicate<View> match, List<View> out) { if (match.test(v)) out.add(v); if (v instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) all(g.getChildAt(i), match, out); }
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
        Canvas c = new Canvas(b); c.drawColor(0xff8a8f99); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    @Test @Config(qualifiers = "w411dp-h891dp-xxhdpi") public void theListSectionIsCompact() throws Exception {
        Triggers.Trigger car = Triggers.make(Triggers.Condition.bluetooth(Triggers.CONNECT, "차", "AA:BB"), Triggers.Action.open("네이버 지도"), Triggers.EVERY, "", System.currentTimeMillis());
        Triggers.Trigger full = Triggers.make(Triggers.Condition.power(Triggers.FULL, 100), Triggers.Action.tell(""), Triggers.ONCE, "", System.currentTimeMillis());
        Triggers.Trigger home = Triggers.make(Triggers.Condition.place(Triggers.ARRIVE, "집", 37.5, 127.0, 150), Triggers.Action.draft("message", "엄마", "", ""), Triggers.ONCE, "", System.currentTimeMillis());
        TriggerStore.change(app, all -> Triggers.add(Triggers.add(Triggers.add(all, car), full), home));
        shadowOf(app).grantPermissions(Manifest.permission.BLUETOOTH_CONNECT);
        Activity a = Robolectric.buildActivity(Activity.class).setup().get();
        new WorkSchedulesUi(a, new Ui(a), app.store(), room -> {}).show();
        shadowOf(Looper.getMainLooper()).idle();
        View s = ShadowDialog.getLatestDialog().getWindow().getDecorView(); settle(s);
        float dp = s.getResources().getDisplayMetrics().density;
        assertNotNull("its heading", find(s, v -> v instanceof TextView t && "조건 알림".contentEquals(t.getText())));
        View row = find(s, v -> v instanceof Ui.ListRow r && r.parts != null && "차에 타면 → 네이버 지도 열기".contentEquals(r.parts.title().getText()));
        assertNotNull("a row: condition → action", row);
        assertTrue("a list row, not a big card: " + row.getHeight() / dp + "dp", row.getHeight() <= 76 * dp);
        assertEquals("매번", ((Ui.ListRow) row).parts.subtitle().getText().toString());
        View homeRow = find(s, v -> v instanceof Ui.ListRow r && r.parts != null && "집에 도착하면 → 엄마한테 보낼 문자 쓰기".contentEquals(r.parts.title().getText()));
        assertNotNull(homeRow);
        assertTrue("what it needs, on its row", ((Ui.ListRow) homeRow).parts.subtitle().getText().toString().contains("위치 ‘항상 허용’이 필요해요"));
        List<View> switches = new ArrayList<>(); all(s, v -> v instanceof Switch, switches);
        assertEquals("one switch a trigger", 3, switches.size());
        for (View sw : switches) assertTrue("a small switch: " + sw.getHeight() / dp + "dp", sw.getHeight() <= 48 * dp && sw.getWidth() < s.getWidth() * 0.3);
        List<View> rows = new ArrayList<>(); all(s, v -> v instanceof Ui.ListRow r && r.parts != null && r.parts.trailing() instanceof Switch, rows);
        int total = 0; for (View r : rows) total += r.getHeight();
        assertTrue("three triggers in under 230dp: " + total / dp, total <= 230 * dp);
        shot(s, "triggers_list");
        // Off by its switch: kept, not listened for.
        Switch first = (Switch) ((Ui.ListRow) row).parts.trailing();
        first.performClick(); shadowOf(Looper.getMainLooper()).idle();
        assertFalse(TriggerStore.list(app).get(0).on());
        assertFalse("off: not listened for", Triggers.kinds(TriggerStore.list(app)).contains(Triggers.BLUETOOTH));
    }
}
