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

/**
 * Keeps the main chat's spoken conversation (VoiceChat) hearing while the screen is off or another app is in front: a
 * microphone foreground service with one notification, whose 끝내기 ends the conversation. Started with the conversation
 * (from the chat screen, in front), stopped when it ends.
 */
public final class VoiceChatService extends Service {
    static final String CHANNEL = "voiceChat";
    private static final int NOTIFICATION = 7431;
    private static final String START = "start", STOP = "stop", END = "end";

    static void start(Context context) {
        try { context.startForegroundService(new Intent(context, VoiceChatService.class).setAction(START)); } catch (RuntimeException refused) { /* It still talks while the app is open. */ }
    }
    static void stop(Context context) {
        try { context.startService(new Intent(context, VoiceChatService.class).setAction(STOP)); } catch (RuntimeException notRunning) {}
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        AppStore store = ((GyeotApp) getApplication()).store();
        if (END.equals(action)) { VoiceChat chat = store.voiceChat; if (chat != null) chat.end(""); action = STOP; }
        if (STOP.equals(action)) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); return START_NOT_STICKY; }
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "메인 대화 음성 대화", NotificationManager.IMPORTANCE_LOW));
        // The notification opens the screen the conversation belongs to.
        VoiceChat on = store.voiceChat; String surface = on == null ? "" : on.target.surface();
        Class<?> screen = "browser".equals(surface) ? BrowserActivity.class : "board".equals(surface) ? BoardActivity.class : MainActivity.class;
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION, new Intent(this, screen).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent end = PendingIntent.getService(this, NOTIFICATION + 1, new Intent(this, VoiceChatService.class).setAction(END), PendingIntent.FLAG_IMMUTABLE);
        // Started with startForegroundService: it goes to the foreground first, even when the conversation already ended.
        VoiceChat chat = store.voiceChat;
        String voice = chat == null ? "음성" : chat.kind.spoken();
        Notification notification = new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(voice + " 대화 중").setContentText((chat == null ? "" : chat.target.label() + "에 ") + "말한 내용이 기록돼요").setOngoing(true).setContentIntent(open)
            .addAction(new Notification.Action.Builder(null, "끝내기", end).build()).build();
        try { startForeground(NOTIFICATION, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE); }
        catch (RuntimeException refused) { stopSelf(); return START_NOT_STICKY; }
        if (store.voiceChat == null) { stopForeground(STOP_FOREGROUND_REMOVE); stopSelf(); }
        return START_NOT_STICKY;
    }
    @Override public IBinder onBind(Intent intent) { return null; }
}
