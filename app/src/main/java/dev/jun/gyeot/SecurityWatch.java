package dev.jun.gyeot;

import android.app.*;
import android.app.job.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.database.ContentObserver;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import dev.jun.gyeot.core.PhoneRisk;
import java.util.*;
import org.json.*;

/** Local baseline and bounded change history. Never stores screen, notification or network content. */
final class SecurityWatch {
    static final String PAUSE_MESSAGE = "긴급 보호 중이에요. 내 휴대폰 → 휴대폰 보안에서 다시 시작할 수 있어요.";
    private static final int PERIODIC = 34710, EVENT = 34711, ALERT = 34712;
    static final String CHANNEL = "phone-security";
    private static boolean observing;
    private static ContentObserver observer;
    private static BroadcastReceiver packages;
    static SharedPreferences prefs(Context c) { return c.getSharedPreferences("phone-security", Context.MODE_PRIVATE); }
    static boolean paused(Context c) { return prefs(c).getBoolean("paused", false); }
    static boolean enabled(Context c) { return prefs(c).getBoolean("watch", false); }
    static void pause(Context c, boolean pause) {
        prefs(c).edit().putBoolean("paused", pause).apply();
        AppStore store = ((GyeotApp)c.getApplicationContext()).store();
        if (pause) {
            if (store.voiceChat!=null) store.voiceChat.end(PAUSE_MESSAGE);
            PhoneService.stopByUser();
            BrowserEngine.stopForProtection();
            WorkScheduleAlarm.cancel(c);
            c.stopService(new Intent(c, AssistantService.class));
        } else WorkScheduleAlarm.refresh(c, store);
    }
    static JSONArray history(Context c) {
        try { return new JSONArray(prefs(c).getString("history", "[]")); }
        catch (JSONException e) { return new JSONArray(); }
    }
    private static JSONObject state(String label, String source, Collection<String> powers) throws JSONException {
        return new JSONObject().put("name", label).put("source", source).put("powers", new JSONArray(powers));
    }
    static List<String> powers(PhoneRisk.App app) {
        List<String> values = new ArrayList<>();
        if (app.accessibility()) values.add("접근성");
        if (app.admin()) values.add("기기 관리자");
        if (app.notifications()) values.add("알림 접근");
        for (String key : List.of("READ_SMS","RECEIVE_SMS","SEND_SMS","READ_CALL_LOG"))
            if (app.granted().contains(key)) values.add(key);
        return values;
    }
    private static void record(JSONArray history, long time, String id, String message, boolean alert) throws JSONException {
        history.put(new JSONObject().put("at", time).put("pkg", id).put("message", message).put("alert", alert));
    }
    record Snapshot(PhoneRisk.Device device, int alerts) {}
    /** Capture and commit in the same order, even if an automatic and manual scan overlap. */
    static synchronized Snapshot refresh(Context c, java.util.function.BooleanSupplier cancelled) throws JSONException {
        if (cancelled.getAsBoolean()) return null;
        var device=PhoneCheck.device(c);
        var apps=PhoneCheck.userApps(c);
        if (cancelled.getAsBoolean()) return null;
        return new Snapshot(device, compare(c,apps,device));
    }
    private static int compare(Context c,List<PhoneRisk.App> apps,PhoneRisk.Device device) throws JSONException {
        if (apps.isEmpty()) throw new IllegalStateException("앱 목록을 읽지 못했어요.");
        SharedPreferences p = prefs(c);
        boolean first = !p.contains("baseline");
        JSONObject old = new JSONObject(p.getString("baseline", "{}")), next = new JSONObject();
        JSONArray events = history(c);
        long now = System.currentTimeMillis();
        int alerts = 0;
        for (PhoneRisk.App app : apps) {
            if (app.system()) continue;
            JSONObject previous = old.optJSONObject(app.id());
            List<String> current = powers(app);
            next.put(app.id(), state(app.label(), app.installer(), current));
            if (first) continue;
            List<String> gained = new ArrayList<>(current);
            List<String> lost = new ArrayList<>();
            if (previous != null) {
                JSONArray earlier = previous.optJSONArray("powers");
                if (earlier != null) for (int i=0; i<earlier.length(); i++) {
                    String power=earlier.optString(i); gained.remove(power);
                    if (!current.contains(power)) lost.add(power);
                }
            }
            boolean sourceChanged=previous!=null && !app.installer().equals(previous.optString("source"));
            if (previous == null || !gained.isEmpty() || !lost.isEmpty() || sourceChanged) {
                String source = app.installer().isBlank() ? "설치 출처 미확인" : PhoneRisk.fromStore(app) ? "스토어 설치" : "스토어 외 설치";
                String message = app.label() + (previous == null ? " · 새로 확인 · " + source : sourceChanged ? " · 설치 출처 변경 · " + source : " · 권한 변경")
                        + (gained.isEmpty() ? "" : " · " + String.join(", ", gained) + " 사용")
                        + (lost.isEmpty() ? "" : " · " + String.join(", ",lost) + " 해제");
                boolean alert = !gained.isEmpty() || ((previous==null || sourceChanged) && !app.installer().isBlank() && !PhoneRisk.fromStore(app));
                record(events, now, app.id(), message, alert);
                if (alert) alerts++;
            }
        }
        for (Iterator<String> ids=old.keys(); ids.hasNext();) {
            String id=ids.next();
            if (!next.has(id)) record(events,now,id,old.getJSONObject(id).optString("name",id)+" · 현재 앱 목록에서 사라짐",false);
        }
        if (!first && p.getBoolean("lock",device.lock()) && !device.lock()) {
            record(events,now,"","화면 잠금이 해제됐어요",true); alerts++;
        }
        if (!first && !p.getBoolean("adb",device.adb()) && device.adb()) {
            record(events,now,"","USB 디버깅이 켜졌어요",true); alerts++;
        }
        while (events.length()>80) events.remove(0);
        p.edit().putString("baseline",next.toString()).putString("history",events.toString()).putLong("last",now)
                .putBoolean("lock",device.lock()).putBoolean("adb",device.adb()).apply();
        return alerts;
    }
    static void setEnabled(Context context, boolean on) {
        Context c = context.getApplicationContext();
        prefs(c).edit().putBoolean("watch",on).apply();
        restore(c);
        if (on) enqueue(c);
    }
    static void restore(Context c) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (!enabled(c)) {
            jobs.cancel(PERIODIC); jobs.cancel(EVENT);
            if (observing) {
                c.unregisterReceiver(packages);
                c.getContentResolver().unregisterContentObserver(observer);
                observing=false;
            }
            return;
        }
        ComponentName component = new ComponentName(c, SecurityJob.class);
        if (jobs.getPendingJob(PERIODIC)==null)
            jobs.schedule(new JobInfo.Builder(PERIODIC,component).setPeriodic(6*60*60_000L).setRequiresBatteryNotLow(true).setPersisted(true).build());
        if (observing) return;
        observer = new ContentObserver(new Handler(Looper.getMainLooper())) {
            @Override public void onChange(boolean selfChange) { enqueue(c); }
        };
        for (String key : List.of(Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES,"enabled_notification_listeners")) {
            try { c.getContentResolver().registerContentObserver(Settings.Secure.getUriFor(key),false,observer); }
            catch (SecurityException ignored) { /* The periodic scan still runs on restricted profiles. */ }
        }
        packages = new BroadcastReceiver() {
            @Override public void onReceive(Context context, Intent intent) { enqueue(c); }
        };
        IntentFilter filter = new IntentFilter(Intent.ACTION_PACKAGE_ADDED);
        filter.addAction(Intent.ACTION_PACKAGE_REPLACED); filter.addAction(Intent.ACTION_PACKAGE_REMOVED); filter.addDataScheme("package");
        c.registerReceiver(packages,filter,Context.RECEIVER_NOT_EXPORTED);
        observing = true;
    }
    static void enqueue(Context c) {
        if (!enabled(c)) return;
        JobScheduler jobs = c.getSystemService(JobScheduler.class);
        if (jobs.getPendingJob(EVENT)==null)
            jobs.schedule(new JobInfo.Builder(EVENT,new ComponentName(c,SecurityJob.class)).setMinimumLatency(3000).setRequiresBatteryNotLow(true).build());
    }
    static void notifyChanges(Context c, int count) {
        NotificationManager nm=c.getSystemService(NotificationManager.class);
        if (!nm.areNotificationsEnabled()) return;
        nm.createNotificationChannel(new NotificationChannel(CHANNEL,"휴대폰 보안 변경",NotificationManager.IMPORTANCE_DEFAULT));
        PendingIntent open=PendingIntent.getActivity(c,ALERT,new Intent(c,PhoneSecurityActivity.class).putExtra("changes",true),
                PendingIntent.FLAG_IMMUTABLE|PendingIntent.FLAG_UPDATE_CURRENT);
        nm.notify(ALERT,new Notification.Builder(c,CHANNEL).setSmallIcon(R.drawable.ic_lock).setContentTitle("휴대폰 보안 변경")
                .setContentText("확인할 앱 또는 보안 설정 변경 "+count+"건").setVisibility(Notification.VISIBILITY_PRIVATE)
                .setContentIntent(open).setAutoCancel(true).build());
    }
}
