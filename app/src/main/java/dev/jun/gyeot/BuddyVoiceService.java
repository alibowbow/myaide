package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.net.Uri;
import android.os.IBinder;
import android.provider.Settings;

/**
 * Keeps 퍼레's voice talk hearing and speaking when another app comes to the front or the screen turns off (준: "퍼레가 말할때
 * 브라우저라도 켜면 바로 꺼져버려"): a microphone foreground service with one notification, whose 끝내기 ends the talk and, when
 * 퍼레 may not yet be drawn over other apps, a button that opens that permission. Started with the talk (from the home screen,
 * in front), stopped when it ends.
 */
public final class BuddyVoiceService extends Service {
    static final String CHANNEL = "buddyVoice";
    private static final int NOTIFICATION = 7451;
    private static final String START = "start", STOP = "stop", END = "end";

    static void start(Context context) {
        try { context.startForegroundService(new Intent(context, BuddyVoiceService.class).setAction(START)); } catch (RuntimeException refused) { /* It still talks while the home screen is open. */ }
    }
    static void stop(Context context) {
        try { context.startService(new Intent(context, BuddyVoiceService.class).setAction(STOP)); } catch (RuntimeException notRunning) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        if (END.equals(action)) { BuddyVoice talk = BuddyVoice.current(); if (talk != null) talk.stop(); action = STOP; }
        if (STOP.equals(action)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "퍼레와 음성 대화", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION, new Intent(this, HomeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent end = PendingIntent.getService(this, NOTIFICATION + 1, new Intent(this, BuddyVoiceService.class).setAction(END), PendingIntent.FLAG_IMMUTABLE);
        Notification.Builder n = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("퍼레와 이야기하는 중").setContentText(BuddyOverlay.allowed(this) ? "다른 앱을 써도 퍼레가 위에서 들어요" : "다른 앱 위에 퍼레를 보이려면 '위에 보이기'를 눌러 주세요")
            .setOngoing(true).setContentIntent(open).addAction(new Notification.Action.Builder(null, "끝내기", end).build());
        if (!BuddyOverlay.allowed(this)) {
            PendingIntent allow = PendingIntent.getActivity(this, NOTIFICATION + 2, new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName())).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
            n.addAction(new Notification.Action.Builder(null, "위에 보이기", allow).build());
        }
        // Started with startForegroundService: it goes to the foreground first, even when the talk already ended.
        try { startForeground(NOTIFICATION, n.build(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); }
        catch (RuntimeException refused) { stopSelf(); return START_NOT_STICKY; }
        BuddyVoice talk = BuddyVoice.current();
        if (talk == null || !talk.on()) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
