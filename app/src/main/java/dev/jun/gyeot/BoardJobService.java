package dev.jun.gyeot;

import android.app.*;
import android.content.*;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/** Keeps a user-submitted lesson running while the app is in the background. */
public final class BoardJobService extends Service implements BoardJobs.Listener {
    private static final String CHANNEL = "board_lessons";
    private static final int NOTIFICATION = 432;
    private BoardJobs jobs;
    private NotificationManager notifications;
    private int latestStart;

    @Override public void onCreate() {
        super.onCreate();
        jobs = ((GyeotApp) getApplication()).boardJobs();
        jobs.watch(this);
        notifications = getSystemService(NotificationManager.class);
        notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "판서 작업", NotificationManager.IMPORTANCE_LOW));
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        latestStart = startId;
        String id = jobs.activeBoard();
        startForeground(NOTIFICATION, status(id), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (id == null) stopSelfResult(startId);
        return START_NOT_STICKY;
    }

    private Notification status(String id) {
        Intent open = new Intent(this, BoardActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        if (id != null) open.putExtra(BoardActivity.OPEN, id);
        PendingIntent tap = PendingIntent.getActivity(this, NOTIFICATION, open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
                .setContentTitle("판서를 준비하는 중").setContentText("누르면 요청을 보낸 판으로 이동해요.")
                .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(tap).build();
    }

    @Override public void finished(BoardJobs.Result result) {}
    @Override public void changed() {
        if (latestStart == 0) return;
        String id = jobs.activeBoard();
        if (id == null) stopSelfResult(latestStart);
        else notifications.notify(NOTIFICATION, status(id));
    }
    @Override public void onTimeout(int startId, int type) { stopSelf(startId); }
    @Override public void onDestroy() { jobs.unwatch(this); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
