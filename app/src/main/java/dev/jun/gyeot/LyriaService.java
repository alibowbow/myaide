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

/** 배경음악 (LyriaPlayer) in the background: its notification with 멈춤/재생 and 끄기, up while the music is on. */
public final class LyriaService extends Service {
    static final String CHANNEL = "bgm";
    private static final int NOTIFICATION = 7461;
    private static final String START = "start", UPDATE = "update", TOGGLE = "toggle", END = "end", STOP = "stop";
    private static boolean running;

    static void start(Context context) { try { context.startForegroundService(new Intent(context, LyriaService.class).setAction(START)); } catch (RuntimeException refused) { /* It plays while the app is up. */ } }
    static void stop(Context context) { if (!running) return; running = false; try { context.startService(new Intent(context, LyriaService.class).setAction(STOP)); } catch (RuntimeException ignored) {} }
    static void update(Context context) { if (!running) return; try { context.startService(new Intent(context, LyriaService.class).setAction(UPDATE)); } catch (RuntimeException ignored) {} }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        LyriaPlayer player = LyriaPlayer.get(this);
        if (TOGGLE.equals(action)) player.toggle();
        else if (END.equals(action)) { player.stop(); action = STOP; }
        boolean end = STOP.equals(action) || !player.active() && !START.equals(action) || UPDATE.equals(action) && !running;
        if (!end || START.equals(action)) {
            try { startForeground(NOTIFICATION, notification(player), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK); running = !end; }
            catch (RuntimeException refused) { end = true; }
        }
        if (end) { running = false; stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        return START_NOT_STICKY;
    }
    private Notification notification(LyriaPlayer player) {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications.getNotificationChannel(CHANNEL) == null) notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "배경음악", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("배경음악 · Lyria").setContentText(player.status().isEmpty() ? player.prompt() : player.status())
            .setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open).setCategory(Notification.CATEGORY_TRANSPORT)
            .addAction(new Notification.Action.Builder(null, player.playing() ? "멈춤" : "재생", action(TOGGLE, 1)).build())
            .addAction(new Notification.Action.Builder(null, "끄기", action(END, 2)).build())
            .setStyle(new Notification.MediaStyle().setShowActionsInCompactView(0, 1)).build();
    }
    private PendingIntent action(String name, int code) { return PendingIntent.getService(this, NOTIFICATION + code, new Intent(this, LyriaService.class).setAction(name), PendingIntent.FLAG_IMMUTABLE); }
    @Override public void onDestroy() { running = false; super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
