package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import java.util.concurrent.atomic.AtomicInteger;

/** Keeps the main chat's browser work running while the app is in the background (MainBrowsing), with a quiet notification. */
public final class MainBrowserService extends Service {
    private static final String CHANNEL = "main_browser";
    private static final int NOTIFICATION = 4150;
    private static final AtomicInteger HOLDS = new AtomicInteger();
    private static volatile MainBrowserService running;

    /** A reply's browser work started: the service comes up (when Android allows it) and stays while any is under way. */
    static void hold(Context context) {
        if (HOLDS.getAndIncrement() > 0) return;
        try { context.startForegroundService(new Intent(context, MainBrowserService.class)); }
        catch (RuntimeException restricted) { /* Started from the background: the work goes on while the app is open. */ }
    }
    /** That work ended: with none left the service goes. */
    static void release(Context context) {
        if (HOLDS.decrementAndGet() > 0) return;
        HOLDS.set(0);
        new Handler(Looper.getMainLooper()).post(() -> { MainBrowserService service = running; if (service != null && HOLDS.get() == 0) service.finish(); });
    }

    @Override public void onCreate() {
        super.onCreate(); running = this;
        getSystemService(NotificationManager.class).createNotificationChannel(new NotificationChannel(CHANNEL, "메인 대화 브라우저 작업", NotificationManager.IMPORTANCE_LOW));
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        Intent open = new Intent(this, MainActivity.class).putExtra(MainNavigation.TARGET, "chat").addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(this, NOTIFICATION, open, PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("브라우저로 작업하는 중").setContentText("메인 대화의 요청을 처리하고 있어요. 누르면 대화로 가요.")
            .setOnlyAlertOnce(true).setOngoing(true).setContentIntent(tap).build();
        startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        if (HOLDS.get() == 0) finish();
        return START_NOT_STICKY;
    }
    private void finish() { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
    @Override public void onTimeout(int startId, int type) { finish(); }
    @Override public void onDestroy() { if (running == this) running = null; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
