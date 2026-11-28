package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.drawable.Icon;
import android.media.MediaMetadata;
import android.media.session.MediaSession;
import android.media.session.PlaybackState;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import dev.jun.gyeot.core.LpPlayback;

/**
 * The LP page's media notification while it plays behind other apps (LpMedia): "LP · <track>", the record, 일시 정지 / 재생
 * and 끄기, with a media session so Android 13+ shows it in its media controls and headphones' buttons reach it; a tap opens
 * the home screen at the LP page. Up only while the LP plays (or a call holds it paused); after a pause from the notification
 * the notification stays, no longer ongoing and with nothing running, so play is a tap away; gone when the page pauses it.
 */
public final class LpService extends Service {
    static final String CHANNEL = "lp";
    static final int NOTIFICATION = 7481;
    static final String SHOW = "show", PLAY = "play", PAUSE = "pause", STOP = "stop";
    private static volatile boolean running;
    private static volatile LpService live;   // (up: brought up to date in place; Android may refuse a start from behind other apps)
    private MediaSession session;
    private PlaybackState said;   // (what the media session says now: for checks)
    private MediaMetadata named;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable check = new Runnable() { @Override public void run() { LpMedia.check(LpService.this); if (running) main.postDelayed(this, 15_000); } };

    static boolean running() { return running; }
    /** (for checks) as if never started. */
    static void reset() { running = false; live = null; }
    /** Up (or brought up to date) for what LpMedia says plays: started when it starts (the page in sight), then updated in place. */
    static void show(Context c) {
        LpService s = live;
        if (s != null && running) { s.main.post(() -> { if (live == s) s.up(LpMedia.state().playing()); }); return; }
        try { c.startForegroundService(new Intent(c, LpService.class).setAction(SHOW)); } catch (RuntimeException refused) { /* it plays on without it */ }
    }
    /** Down: the notification gone, or kept (a pause from it) with its play button. */
    static void hide(Context c, boolean keep) {
        LpService s = live;
        if (s == null || !running) { if (!keep) c.getSystemService(NotificationManager.class).cancel(NOTIFICATION); return; }
        s.main.post(() -> { if (live == s) s.down(keep); });
    }

    @Override public void onCreate() {
        super.onCreate();
        live = this;
        session = new MediaSession(this, "lp");
        session.setCallback(new MediaSession.Callback() {
            @Override public void onPlay() { LpMedia.command(LpService.this, "play", true); }
            @Override public void onPause() { LpMedia.command(LpService.this, "pause", true); }
            @Override public void onStop() { LpMedia.command(LpService.this, "stop", true); }
            @Override public void onCustomAction(String action, android.os.Bundle extras) { if (STOP.equals(action)) LpMedia.command(LpService.this, "stop", true); }
        });
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null || intent.getAction() == null ? "" : intent.getAction();
        switch (action) {
            case PLAY, PAUSE, STOP -> {
                LpMedia.command(this, action, true);
                // (started for a button of a kept notification: up at once, as Android asks, until the page answers)
                if (!running && PLAY.equals(action)) up(true);
                else if (!running) { stopSelf(); return START_NOT_STICKY; }
            }
            case SHOW -> up(LpMedia.state().playing());
            default -> down(false);
        }
        return START_NOT_STICKY;
    }
    /** Nothing running: the notification kept (dismissible, its play button) or gone. */
    private void down(boolean keep) {
        if (keep) up(false);
        running = false; stopForeground(keep ? STOP_FOREGROUND_DETACH : STOP_FOREGROUND_REMOVE); release(); stopSelf();
    }
    private void up(boolean playing) {
        LpPlayback.State s = LpMedia.state();
        session.setMetadata(named = new MediaMetadata.Builder().putString(MediaMetadata.METADATA_KEY_TITLE, s.title().isEmpty() ? "LP" : s.title())
            .putString(MediaMetadata.METADATA_KEY_ARTIST, s.artist()).putString(MediaMetadata.METADATA_KEY_ALBUM, s.album()).build());
        // (Android 13+ draws its media controls from this: play · pause, and 끄기 as the session's own action)
        session.setPlaybackState(said = new PlaybackState.Builder().setActions(PlaybackState.ACTION_PLAY | PlaybackState.ACTION_PAUSE | PlaybackState.ACTION_PLAY_PAUSE | PlaybackState.ACTION_STOP)
            .addCustomAction(new PlaybackState.CustomAction.Builder(STOP, "끄기", R.drawable.ic_close).build())
            .setState(playing ? PlaybackState.STATE_PLAYING : PlaybackState.STATE_PAUSED, PlaybackState.PLAYBACK_POSITION_UNKNOWN, playing ? 1f : 0f).build());
        session.setActive(true);
        try {
            startForeground(NOTIFICATION, notification(this, s, playing, session.getSessionToken()), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK);
            if (!running) { running = true; main.removeCallbacks(check); main.postDelayed(check, 15_000); }
        } catch (RuntimeException refused) { running = false; stopSelf(); }
    }
    /** The notification: its words from core/LpPlayback, its buttons to this service, a tap to the LP page. */
    static Notification notification(Context c, LpPlayback.State s, boolean playing, MediaSession.Token token) {
        NotificationManager nm = c.getSystemService(NotificationManager.class);
        if (nm.getNotificationChannel(CHANNEL) == null) nm.createNotificationChannel(new NotificationChannel(CHANNEL, "LP 재생", NotificationManager.IMPORTANCE_LOW));
        Intent page = new Intent(c, HomeActivity.class).putExtra(HomeActivity.PAGE, "LP").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent open = PendingIntent.getActivity(c, NOTIFICATION, page, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification.Builder b = new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(LpPlayback.title(s)).setContentText(LpPlayback.text(s)).setOnlyAlertOnce(true).setOngoing(playing)
            .setContentIntent(open).setCategory(Notification.CATEGORY_TRANSPORT).setVisibility(Notification.VISIBILITY_PRIVATE)
            .setPublicVersion(new Notification.Builder(c, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("LP").build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, playing ? R.drawable.ic_pause : R.drawable.ic_play), playing ? "일시 정지" : "재생", action(c, playing ? PAUSE : PLAY, 1)).build())
            .addAction(new Notification.Action.Builder(Icon.createWithResource(c, R.drawable.ic_close), "끄기", action(c, STOP, 2)).build());
        Notification.MediaStyle style = new Notification.MediaStyle().setShowActionsInCompactView(0, 1);
        if (token != null) style.setMediaSession(token);
        return b.setStyle(style).build();
    }
    private static PendingIntent action(Context c, String name, int code) {
        Intent i = new Intent(c, LpService.class).setAction(name);
        // (play may start the service again from a kept notification: as a foreground one)
        return PLAY.equals(name) ? PendingIntent.getForegroundService(c, NOTIFICATION + code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)
            : PendingIntent.getService(c, NOTIFICATION + code, i, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
    }
    PlaybackState playbackState() { return said; }
    MediaMetadata metadata() { return named; }
    private void release() { main.removeCallbacks(check); if (session != null) session.setActive(false); }
    @Override public void onDestroy() { running = false; if (live == this) live = null; main.removeCallbacks(check); if (session != null) { session.release(); session = null; } super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }
}
