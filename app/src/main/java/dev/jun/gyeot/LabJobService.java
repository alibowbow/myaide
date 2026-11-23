package dev.jun.gyeot;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps 연구실's research running while the app is in the background, with what it is doing in the notification. */
public final class LabJobService extends Service implements LabRunner.Listener {
    private static final String CHANNEL = "lab_jobs";
    private static final int NOTIFICATION = 433;
    private LabRunner runner;
    private NotificationManager notifications;
    private int latestStart;

    @Override public void onCreate() {
        super.onCreate();
        runner = LabRunner.get(this);
        runner.watch(this);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "연구실 작업", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStart = startId;
        startForeground(NOTIFICATION, status(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (!runner.anyRunning()) stopSelfResult(startId);
        return START_NOT_STICKY;
    }
    private Notification status() {
        String id = runner.firstRunning();
        dev.jun.gyeot.core.LabBook.Project p = id == null ? null : runner.book().project(id);
        Intent open = new Intent(this, LabActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (id != null) open.putExtra(LabActivity.OPEN, id);
        PendingIntent tap = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        String what = id == null ? "" : runner.activity(id);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(p == null ? "연구실에서 연구하는 중" : "연구 중 · " + p.title())
            .setContentText(what.isBlank() ? "누르면 연구실로 이동해요." : what)
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(tap).build();
    }
    @Override public void changed(String project) {
        if (latestStart == 0) return;
        if (!runner.anyRunning()) stopSelfResult(latestStart);
        else notifications.notify(NOTIFICATION, status());
    }
    @Override public void onTimeout(int startId, int type) { stopSelf(startId); }
    @Override public void onDestroy() { runner.unwatch(this); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
