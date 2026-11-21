package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;
import dev.jun.gyeot.core.MyTube;

/**
 * 마이튜브 playing on with the screen off or another app in front (화면 꺼져도 계속 재생): a media-playback foreground service
 * with one notification, as a music player's, whose buttons are 이전, 멈춤/재생, 다음 and 끝내기. Started with the playing
 * while the screen is in front; stopped when the queue ends or the user ends it.
 */
public final class MyTubeService extends Service {
    static final String CHANNEL = "mytube";
    private static final int NOTIFICATION = 7451;
    private static final String START = "start", UPDATE = "update", PREVIOUS = "previous", TOGGLE = "toggle", NEXT = "next", END = "end", STOP = "stop";
    private static boolean running;

    static void start(Context context) {
        try { context.startForegroundService(new Intent(context, MyTubeService.class).setAction(START)); } catch (RuntimeException refused) { /* It plays while the screen is open. */ }
    }
    static void stop(Context context) {
        if (!running) return;
        running = false;
        try { context.startService(new Intent(context, MyTubeService.class).setAction(STOP)); } catch (RuntimeException notRunning) {}
    }
    /** The notification follows what plays (only while it is up; a queue that ended takes it down). */
    static void update(Context context) {
        if (!running) return;
        try { context.startService(new Intent(context, MyTubeService.class).setAction(UPDATE)); } catch (RuntimeException refused) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        MyTubePlayer player = MyTubePlayer.get(this);
        if (PREVIOUS.equals(action)) player.previous();
        else if (TOGGLE.equals(action)) player.toggle();
        else if (NEXT.equals(action)) player.next();
        else if (END.equals(action)) { player.stop(); action = STOP; }
        boolean end = STOP.equals(action) || !player.active() || UPDATE.equals(action) && !running;
        // Started with startForegroundService: it goes to the foreground first, even when it ends at once.
        if (!end || START.equals(action)) {
            try { startForeground(NOTIFICATION, notification(player), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); running = !end; }
            catch (RuntimeException refused) { end = true; }
        }
        if (end) { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        return START_NOT_STICKY;
    }
    private Notification notification(MyTubePlayer player) {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "마이튜브 재생", NotificationManager.IMPORTANCE_LOW));
        MyTube.Video now = player.current();
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION, new Intent(this, MyTubeActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(now == null ? "마이튜브" : now.title()).setContentText((now == null ? "" : now.channel() + " · ") + player.status())
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(new Notification.Action.Builder(null, "이전", action(PREVIOUS, 1)).build())
            .addAction(new Notification.Action.Builder(null, player.playing() ? "멈춤" : "재생", action(TOGGLE, 2)).build())
            .addAction(new Notification.Action.Builder(null, "다음", action(NEXT, 3)).build())
            .addAction(new Notification.Action.Builder(null, "끝내기", action(END, 4)).build())
            .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1, 2)).build();
    }
    private PendingIntent action(String name, int code) {
        return PendingIntent.getService(this, NOTIFICATION + code, new Intent(this, MyTubeService.class).setAction(name), PendingIntent.FLAG_IMMUTABLE);
    }
    @Override public void onDestroy() { running = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
