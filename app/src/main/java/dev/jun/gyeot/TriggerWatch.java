package dev.jun.gyeot;

import android.Manifest;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.location.Location;
import android.location.LocationManager;
import android.net.Uri;
import android.os.BatteryManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.service.notification.StatusBarNotification;
import dev.jun.gyeot.core.Deliveries;
import dev.jun.gyeot.core.NoticeActions;
import dev.jun.gyeot.core.Notices;
import dev.jun.gyeot.core.Triggers;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * 조건 알림 (사건 트리거)'s ears: each kind is listened for only while a trigger of that kind is on (sync), and what is heard
 * is shown to the triggers (core/Triggers.match); those that fire are done by TriggerFire.
 *  - NOTICE: what NoticeListener already reads (알림 접근), off its main thread; nothing else to register.
 *  - BLUETOOTH: a manifest receiver for Android's ACL connect/disconnect (one of the broadcasts that still reach a stopped
 *    app), switched on only while a device trigger exists, with 근처 기기 (BLUETOOTH_CONNECT).
 *  - POWER: the charger's broadcasts (and the battery's while a level is asked) on a receiver of this process, which lives
 *    as long as NoticeListener or 폰 조작 keep the app; while the app is gone, a persisted job that runs when charging starts
 *    and every 15 minutes while charging reads the battery (a late "충전 다 됐어" rather than none). Unplugging while the app
 *    is gone is noticed only when it runs again.
 *  - PLACE: Android's proximity alert per place (LocationManager.addProximityAlert: the system watches the circle with
 *    balanced-power fixes every 30 minutes or so, and any fix another app gets at once); it needs 정확한 위치 and ‘항상 허용’,
 *    and is set again after a reboot (TriggerReceiver.Boot) and whenever the app starts.
 *  - PRICE: a persisted network job at least five minutes apart while a price trigger exists (Upbit for won, Binance for
 *    dollars); Doze holds it back further, never on the main thread.
 * 하루 기록 (DayLogs) hears the same doors while it is on, with no trigger needed: the ACL receiver (근처 기기 allowed before,
 * for 호출어 or a device trigger; no new permission asked) writes the car's and other audio devices' links, the power
 * receiver of this process the charger (only while the app already runs: it wakes nothing), a proximity alert the place.
 */
final class TriggerWatch {
    private TriggerWatch() {}
    static final int PRICE_JOB = 4201, CHARGE_JOB = 4202;
    static final long PRICE_MS = 5 * 60_000L, CHARGE_MS = 15 * 60_000L;
    static final String PLACE_ACTION = "dev.jun.gyeot.TRIGGER_PLACE";
    /** Everything heard is matched here, one at a time, off the main thread. */
    static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-triggers"); t.setDaemon(true); return t; });

    /** At the app's start and after a change: listen as the triggers on need (off the main thread). */
    static void start(Context c) {
        Context app = c.getApplicationContext();
        try { IO.execute(() -> { try { sync(app); } catch (RuntimeException broken) { log(app, "● 조건 알림: 듣기 준비 못 함 (" + broken.getClass().getSimpleName() + ")"); } }); } catch (RuntimeException stopped) { /* none */ }
    }

    // ── listening as needed ─────────────────────────────────────
    /** What to listen for now, from the triggers on: receivers on or off, the jobs set or cancelled, the places watched. */
    static void sync(Context c) {
        Context app = c.getApplicationContext();
        List<Triggers.Trigger> all = TriggerStore.list(app);
        Set<String> kinds = Triggers.kinds(all);
        receivers(app, all);
        JobScheduler jobs = app.getSystemService(JobScheduler.class);
        if (jobs != null) {
            if (Triggers.needsCharging(all)) charging(app, jobs, 0); else jobs.cancel(CHARGE_JOB);
            if (kinds.contains(Triggers.PRICE)) { if (jobs.getPendingJob(PRICE_JOB) == null) prices(app, jobs, PRICE_MS); } else jobs.cancel(PRICE_JOB);
        }
        places(app, all);
    }
    /** The receivers alone (a job that woke the app for the battery sets them, not the jobs). */
    static void receivers(Context app, List<Triggers.Trigger> all) {
        Set<String> kinds = Triggers.kinds(all);
        component(app, TriggerReceiver.Boot.class, !kinds.isEmpty());
        // 하루 기록 on: the car and the charger are its lines too (DayLogs.bluetooth, DayLogs.charge), with no trigger.
        boolean dayLog = dev.jun.gyeot.core.DayLog.on();
        component(app, TriggerReceiver.Bluetooth.class, (kinds.contains(Triggers.BLUETOOTH) || dayLog) && bluetoothAllowed(app));
        power(app, kinds.contains(Triggers.POWER) || dayLog, Triggers.needsLevel(all));
    }
    private static void component(Context c, Class<?> receiver, boolean on) {
        PackageManager pm = c.getPackageManager(); ComponentName name = new ComponentName(c, receiver);
        int want = on ? PackageManager.COMPONENT_ENABLED_STATE_ENABLED : PackageManager.COMPONENT_ENABLED_STATE_DISABLED;
        try { if (pm.getComponentEnabledSetting(name) != want) pm.setComponentEnabledSetting(name, want, PackageManager.DONT_KILL_APP); } catch (RuntimeException refused) { /* as it was */ }
    }
    static void prices(Context c, JobScheduler jobs, long after) {
        JobInfo job = new JobInfo.Builder(PRICE_JOB, new ComponentName(c, TriggerJob.class)).setMinimumLatency(after).setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build();
        try { jobs.schedule(job); } catch (RuntimeException refused) { /* the app's next start sets it */ }
    }
    static void charging(Context c, JobScheduler jobs, long after) {
        if (after == 0 && jobs.getPendingJob(CHARGE_JOB) != null) return;
        JobInfo job = new JobInfo.Builder(CHARGE_JOB, new ComponentName(c, TriggerJob.class)).setRequiresCharging(true).setMinimumLatency(after).setPersisted(true).build();
        try { jobs.schedule(job); } catch (RuntimeException refused) { /* the app's next start sets it */ }
    }

    // the charger and the battery, while the app runs
    private static BroadcastReceiver powerReceiver; private static boolean powerLevel; private static Context powerOn; private static Handler handler;
    private static synchronized Handler handler() {
        if (handler == null) { HandlerThread t = new HandlerThread("gyeot-trigger-power"); t.start(); handler = new Handler(t.getLooper()); }
        return handler;
    }
    /** The looper the power receiver runs on (tests idle it). */
    static android.os.Looper looper() { return handler().getLooper(); }
    private static synchronized void power(Context app, boolean want, boolean level) {
        // Registered on another application object (a test's former one): registered again on this one.
        if (powerReceiver != null && (!want || level != powerLevel || powerOn != app)) { try { powerOn.unregisterReceiver(powerReceiver); } catch (RuntimeException gone) { /* gone */ } powerReceiver = null; powerOn = null; }
        if (!want || powerReceiver != null) return;
        IntentFilter f = new IntentFilter(Intent.ACTION_POWER_CONNECTED); f.addAction(Intent.ACTION_POWER_DISCONNECTED);
        if (level) f.addAction(Intent.ACTION_BATTERY_CHANGED);
        BroadcastReceiver r = new BroadcastReceiver() { @Override public void onReceive(Context ctx, Intent i) { power(app, i); } };
        try { app.registerReceiver(r, f, null, handler(), Context.RECEIVER_EXPORTED); powerReceiver = r; powerLevel = level; powerOn = app; } catch (RuntimeException refused) { /* the job still reads it */ }
    }
    /** A power broadcast or a battery reading, shown to the power triggers. */
    static void power(Context c, Intent i) {
        if (i == null) return;
        String edge = Intent.ACTION_POWER_CONNECTED.equals(i.getAction()) ? Triggers.PLUGGED : Intent.ACTION_POWER_DISCONNECTED.equals(i.getAction()) ? Triggers.UNPLUGGED : "";
        if (edge.isEmpty() && !Intent.ACTION_BATTERY_CHANGED.equals(i.getAction())) return;
        if (!edge.isEmpty()) DayLogs.charge(edge.equals(Triggers.PLUGGED));
        handle(c, edge.isEmpty() ? reading(i, "") : Triggers.Event.power(edge, edge.equals(Triggers.PLUGGED), -1, false, System.currentTimeMillis()));
    }
    /** The battery now (Android's sticky battery broadcast), or null. */
    static Triggers.Event battery(Context c) {
        try { Intent b = c.getApplicationContext().registerReceiver(null, new IntentFilter(Intent.ACTION_BATTERY_CHANGED), Context.RECEIVER_NOT_EXPORTED); return b == null ? null : reading(b, ""); }
        catch (RuntimeException unreadable) { return null; }
    }
    static Triggers.Event reading(Intent b, String edge) {
        int level = b.getIntExtra(BatteryManager.EXTRA_LEVEL, -1), scale = b.getIntExtra(BatteryManager.EXTRA_SCALE, 100);
        int pct = level < 0 || scale <= 0 ? -1 : Math.round(level * 100f / scale);
        boolean plugged = b.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0, full = b.getIntExtra(BatteryManager.EXTRA_STATUS, -1) == BatteryManager.BATTERY_STATUS_FULL;
        return Triggers.Event.power(edge, plugged, pct, full, System.currentTimeMillis());
    }

    // places
    static boolean locationAllowed(Context c) {
        return c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
            && c.checkSelfPermission(Manifest.permission.ACCESS_BACKGROUND_LOCATION) == PackageManager.PERMISSION_GRANTED;
    }
    static PendingIntent placeIntent(Context c, String id) {
        Intent i = new Intent(c, TriggerReceiver.Place.class).setAction(PLACE_ACTION).setData(Uri.parse("gyeot-trigger:" + id));
        // Mutable: Android adds whether the phone entered or left (KEY_PROXIMITY_ENTERING); explicit, so no one else receives it.
        return PendingIntent.getBroadcast(c, ("place" + id).hashCode(), i, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_MUTABLE);
    }
    private static void places(Context c, List<Triggers.Trigger> all) {
        LocationManager lm = c.getSystemService(LocationManager.class);
        android.content.SharedPreferences p = TriggerStore.prefs(c);
        Set<String> was = new HashSet<>(p.getStringSet("placesOn", Set.of())), want = new HashSet<>();
        boolean allowed = locationAllowed(c);
        for (Triggers.Trigger t : all) if (allowed && t.active() && Triggers.PLACE.equals(t.when().kind()) && t.when().located()) want.add(t.id());
        if (lm != null) {
            for (String id : was) if (!want.contains(id)) try { lm.removeProximityAlert(placeIntent(c, id)); } catch (RuntimeException gone) { /* gone */ }
            for (Triggers.Trigger t : all) if (want.contains(t.id())) {
                PendingIntent pi = placeIntent(c, t.id());
                try { lm.removeProximityAlert(pi); lm.addProximityAlert(t.when().lat(), t.when().lng(), t.when().radius(), -1, pi); }
                catch (RuntimeException refused) { want.remove(t.id()); log(c, "● 조건 알림: 장소 감시를 못 걸었어요 (" + refused.getClass().getSimpleName() + ")"); }
            }
        }
        if (!want.equals(was)) p.edit().putStringSet("placesOn", want).apply();
    }
    /** Where the phone was last seen by any app (no new fix is asked for), when it is recent; null otherwise. */
    @SuppressWarnings("MissingPermission")
    static Triggers.Event here(Context c) {
        if (c.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED && c.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED) return null;
        LocationManager lm = c.getSystemService(LocationManager.class); if (lm == null) return null;
        Location best = null;
        try { for (String provider : lm.getProviders(true)) { Location l = lm.getLastKnownLocation(provider); if (l != null && (best == null || l.getTime() > best.getTime())) best = l; } }
        catch (RuntimeException refused) { return null; }
        if (best == null || System.currentTimeMillis() - best.getTime() > 30 * 60_000L) return null;
        return Triggers.Event.fix(best.getLatitude(), best.getLongitude(), best.hasAccuracy() ? best.getAccuracy() : 100, best.getTime());
    }

    // ── what was heard ──────────────────────────────────────────
    /** Shown to every trigger on; those that fire are done (TriggerFire) and the list kept. Off the main thread. */
    static void handle(Context c, Triggers.Event e) {
        if (e == null) return;
        Context app = c.getApplicationContext();
        List<Triggers.Fired> fired;
        synchronized (TriggerStore.LOCK) {
            Triggers.Outcome o = Triggers.match(TriggerStore.list(app), e, System.currentTimeMillis());
            if (!o.changed()) return;
            TriggerStore.put(app, o.list());
            fired = o.fired();
        }
        boolean ended = false;
        for (Triggers.Fired f : fired) {
            ended |= f.trigger().done();
            try { TriggerFire.fire(app, f); } catch (RuntimeException failed) { log(app, "● 조건 알림: " + Triggers.title(f.trigger()) + " · 못 함 (" + failed.getClass().getSimpleName() + ")"); }
        }
        // A once that fired: what only it needed is no longer listened for.
        if (ended) sync(app);
    }
    /** A notice another app posted (NoticeListener, off its main thread): its delivery stage, parcel, sender or words. */
    static void notice(Context c, StatusBarNotification posted) {
        if (posted == null || c.getPackageName().equals(posted.getPackageName())) return;
        if (!Triggers.kinds(TriggerStore.list(c)).contains(Triggers.NOTICE)) return;
        Notification n = posted.getNotification();
        if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return;
        String pkg = posted.getPackageName(), label = label(c, pkg);
        long at = posted.getPostTime();
        boolean call = Notification.CATEGORY_CALL.equals(n.category);
        Deliveries.Delivery d = Deliveries.app(pkg, label) ? NoticeListener.deliveryOf(c, posted) : null;
        if (d != null) { handle(c, Triggers.Event.notice(pkg, label, Triggers.DELIVERY, "", d.text(), d.stage().id, "", at)); return; }
        // Ongoing ones (music, navigation, downloads) are not news; an incoming call is.
        if ((posted.isOngoing() || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) && !call) return;
        boolean chat = call || NoticeActions.messenger(pkg, label) || Notification.CATEGORY_MESSAGE.equals(n.category);
        String[] words = NoticeListener.actWords(posted, chat);
        if (words == null) return;
        // Codes and passwords are never read into anything.
        if (Notices.secret(words[0] + " " + words[1])) return;
        NoticeActions.Act a = call ? null : NoticeListener.actOf(c, posted);
        if (a != null && NoticeActions.PARCEL.equals(a.kind())) { handle(c, Triggers.Event.notice(pkg, label, Triggers.PARCEL, words[0], words[1], a.stage(), a.key(), at)); return; }
        handle(c, Triggers.Event.notice(pkg, label, chat ? Triggers.MESSAGE : Triggers.APP, words[0], words[1], "", "", at));
    }
    private static String label(Context c, String pkg) {
        try { PackageManager pm = c.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception gone) { return pkg; }
    }
    /** Android's ACL broadcast for a paired device (TriggerReceiver.Bluetooth). */
    static void bluetooth(Context c, Intent i) {
        if (i == null) return;
        String edge = BluetoothDevice.ACTION_ACL_CONNECTED.equals(i.getAction()) ? Triggers.CONNECT : BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(i.getAction()) ? Triggers.DISCONNECT : "";
        if (edge.isEmpty()) return;
        BluetoothDevice device = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice.class);
        if (device == null) return;
        String name = ""; boolean audio = false, bonded = false;
        try { name = device.getName() == null ? "" : device.getName(); } catch (SecurityException denied) { /* no name */ }
        try {
            android.bluetooth.BluetoothClass kind = device.getBluetoothClass();
            audio = kind != null && kind.getMajorDeviceClass() == android.bluetooth.BluetoothClass.Device.Major.AUDIO_VIDEO;
            bonded = device.getBondState() == BluetoothDevice.BOND_BONDED;
        } catch (SecurityException denied) { /* not known */ }
        try { DayLogs.bluetooth(c, Triggers.CONNECT.equals(edge), device.getAddress(), name, audio, bonded); } catch (RuntimeException failed) { /* no line */ }
        handle(c, Triggers.Event.bluetooth(edge, device.getAddress(), name, System.currentTimeMillis()));
    }
    /** Android's proximity alert for one place trigger (TriggerReceiver.Place). */
    static void place(Context c, Intent i) {
        if (i == null || !PLACE_ACTION.equals(i.getAction()) || i.getData() == null || !i.hasExtra(LocationManager.KEY_PROXIMITY_ENTERING)) return;
        String id = i.getData().getSchemeSpecificPart(); boolean entering = i.getBooleanExtra(LocationManager.KEY_PROXIMITY_ENTERING, false);
        // 하루 기록: arrived at or left the place this alert watches (DayLogs.proximity).
        Triggers.Trigger t = Triggers.find(TriggerStore.list(c), id);
        if (t != null) DayLogs.proximity(c, t.when().name(), entering);
        handle(c, Triggers.Event.proximity(id, entering, System.currentTimeMillis()));
    }

    // prices
    /** Where a price comes from (tests put their own): the price of symbol in unit, NaN when it could not be read. */
    interface Quotes { double price(String symbol, String unit); }
    static Quotes quotes = TriggerWatch::fetch;
    /** Each price the triggers on need, read once and shown to them; how many were asked. Off the main thread. */
    static int prices(Context c) {
        List<String[]> wanted = Triggers.quotes(TriggerStore.list(c));
        for (String[] q : wanted) {
            double p = quotes.price(q[0], q[1]);
            if (p > 0) handle(c, Triggers.Event.price(q[0], q[1], p, System.currentTimeMillis()));
        }
        return wanted.size();
    }
    static double fetch(String symbol, String unit) {
        for (String url : Triggers.quoteUrls(symbol, unit)) { double p = Triggers.quote(get(url)); if (p > 0) return p; }
        return Double.NaN;
    }
    private static String get(String url) {
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection) new java.net.URL(url).openConnection();
            c.setConnectTimeout(8_000); c.setReadTimeout(8_000); c.setRequestProperty("User-Agent", "Gyeot/1.0"); c.setRequestProperty("Accept", "application/json");
            if (c.getResponseCode() != 200) return null;
            try (InputStream in = c.getInputStream()) { return new String(in.readNBytes(64_000), StandardCharsets.UTF_8); }
        } catch (Exception offline) { return null; }
        finally { if (c != null) c.disconnect(); }
    }

    // ── what the user may need to allow ─────────────────────────
    static boolean bluetoothAllowed(Context c) { return c.checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED; }
    /** What keeps a trigger from being heard, in a few words for its row ("" none), and the tap that clears it (BuddyMisses' fix). */
    static String[] problem(Context c, Triggers.Trigger t) {
        if (!t.active()) return new String[]{"", ""};
        switch (t.when().kind()) {
            case Triggers.PLACE -> { if (!locationAllowed(c)) return new String[]{"위치 ‘항상 허용’이 필요해요", "allow:location"}; }
            case Triggers.BLUETOOTH -> {
                if (!bluetoothAllowed(c)) return new String[]{"근처 기기 권한이 필요해요", "allow:bluetooth"};
                if (t.when().id().isEmpty() && !t.when().car()) return new String[]{"기기를 골라 주세요", "pick:" + t.id()};
                if (t.when().id().isEmpty()) return new String[]{"차를 골라 주세요", "pick:" + t.id()};
            }
            case Triggers.NOTICE -> { if (!NoticeListener.allowed(c)) return new String[]{"알림 접근이 필요해요", "notices"}; }
            default -> { }
        }
        return new String[]{"", ""};
    }
    /** The phone's paired devices as {name, address} (근처 기기 allowed), empty otherwise. */
    static List<String[]> bonded(Context c) {
        List<String[]> out = new ArrayList<>();
        if (!bluetoothAllowed(c)) return out;
        BluetoothManager manager = c.getSystemService(BluetoothManager.class); BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        if (adapter == null) return out;
        try { for (BluetoothDevice d : adapter.getBondedDevices()) { String n = d.getName(); out.add(new String[]{n == null || n.isBlank() ? d.getAddress() : n, d.getAddress()}); } }
        catch (SecurityException denied) { /* none */ }
        return out;
    }
    static void log(Context c, String line) { try { ((GyeotApp) c.getApplicationContext()).store().phoneLog(line); } catch (RuntimeException none) { /* no log */ } }
}
