package dev.jun.gyeot;

import android.app.AlarmManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.provider.Settings;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.WorkSchedules;
import java.time.ZoneId;

/**
 * 예약 작업's one alarm: set for the earliest next run, and set again after every change and every time the app starts.
 * It is a private receiver with no intent filter: only this app's own alarm reaches it. Nothing listens for boot, so
 * after the phone restarts the alarm comes back when the app is next opened (a run missed by more than an hour is skipped).
 *
 * With Android's exact alarm permission the run starts on time and may wake the work service from the background. Without
 * it Android may hold the alarm back, and when the service can not be started the user gets a notification that starts
 * the run with a tap.
 */
public final class WorkScheduleAlarm extends BroadcastReceiver {
    static final String ACTION = "dev.jun.gyeot.WORK_SCHEDULE_ALARM";
    private static final String CHANNEL = "results";
    private static final int REMINDER = 2900;
    /** When the service could not be woken, the alarm tries again this much later (until the run is an hour late). */
    private static final long RETRY_MS = 15 * 60_000L;

    @Override public void onReceive(Context context, Intent intent) {
        if (intent == null || !ACTION.equals(intent.getAction())) return;
        AppStore store;
        try { store = ((GyeotApp) context.getApplicationContext()).store(); } catch (Exception e) { return; }
        try { store.changeSchedules(list -> WorkSchedules.skipLate(list, System.currentTimeMillis(), ZoneId.systemDefault())); } catch (Exception ignored) {}
        Intent run = new Intent(context, AssistantService.class).setAction(AssistantService.WORK_SCHEDULE);
        try { context.startForegroundService(run); }
        catch (RuntimeException notAllowed) {
            // Android 12+ refuses to start a foreground service from the background without an exemption (an exact alarm is one).
            remind(context, run);
            long soonest = WorkSchedules.soonest(store.schedules());
            if (soonest > 0) set(context, Math.max(soonest, System.currentTimeMillis() + RETRY_MS));
        }
    }

    /** The alarm for the earliest enabled run, or none. Exact when Android allows it, otherwise as close as Android lets it. */
    static void arm(Context context, long at) {
        if (at <= 0) { cancel(context); return; }
        set(context, at);
    }
    private static void set(Context context, long at) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms == null) return;
        PendingIntent pending = pending(context);
        try {
            if (exact(context)) alarms.setExactAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
            else alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        } catch (SecurityException revoked) {
            // The exact alarm permission was taken back between the check and the call.
            alarms.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, at, pending);
        }
    }
    static void cancel(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        if (alarms != null) alarms.cancel(pending(context));
    }
    private static PendingIntent pending(Context context) {
        Intent intent = new Intent(context, WorkScheduleAlarm.class).setAction(ACTION);
        return PendingIntent.getBroadcast(context, 2901, intent, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
    }

    /** Android lets this app set exact alarms (설정 → 앱 → 알람 및 리마인더). */
    static boolean exact(Context context) {
        AlarmManager alarms = context.getSystemService(AlarmManager.class);
        return alarms != null && alarms.canScheduleExactAlarms();
    }
    /** Android's page where the user allows exact alarms for this app. */
    static Intent exactSettings(Context context) {
        return new Intent(Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, Uri.parse("package:" + context.getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }

    /**
     * On app start and when the app comes back to the screen: future runs follow a changed time zone, and the alarm is set
     * again (alarms do not survive a restart, and the exact alarm permission may have changed).
     */
    static void refresh(Context context, AppStore store) {
        try {
            String zone = ZoneId.systemDefault().getId();
            if (!zone.equals(store.settings.getString("scheduleZone", zone)))
                store.changeSchedules(list -> WorkSchedules.recompute(list, System.currentTimeMillis(), ZoneId.systemDefault()));
            store.settings.edit().putString("scheduleZone", zone).apply();
            arm(context, WorkSchedules.soonest(store.schedules()));
        } catch (Exception ignored) {
            // An unreadable schedule file keeps its original; nothing is armed from it.
        }
    }

    private static void remind(Context context, Intent run) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class);
        if (notifications == null) return;
        if (notifications.getNotificationChannel(CHANNEL) == null) notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "작업 결과", NotificationManager.IMPORTANCE_DEFAULT));
        // A tap is the user's own action, which Android lets start the work service.
        PendingIntent start = PendingIntent.getForegroundService(context, REMINDER, run, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String text = "예약 작업 시간이에요. 눌러서 실행하세요. 설정 → 앱 → " + Brand.NAME + " → 알람 및 리마인더를 허용하면 제시간에 저절로 실행돼요.";
        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(Brand.NAME + " · 예약 작업").setContentText("예약 작업 시간이에요 · 눌러서 실행")
            .setStyle(new Notification.BigTextStyle().bigText(text)).setContentIntent(start).setAutoCancel(true).build();
        try { notifications.notify(REMINDER, notification); } catch (SecurityException noPermission) { /* Notifications are off. */ }
    }
}
