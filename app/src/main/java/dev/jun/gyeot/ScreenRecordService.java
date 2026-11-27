package dev.jun.gyeot;

import android.Manifest;
import android.app.KeyguardManager;
import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.graphics.Point;
import android.hardware.display.DisplayManager;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.view.Display;
import android.widget.Toast;
import dev.jun.gyeot.core.ScreenRecordPlan;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

/**
 * 화면 녹화 (준: "휴대폰 자체에 녹화기능 있는데 전체화면 녹화시 화질을 최고로 해도 저화질로 저장돼"): the foreground service that
 * holds the screen capture while it records. ScreenRecordActivity gets Android's consent and starts it; it goes to the
 * foreground (screen capture, and the microphone when there is sound) before it takes the projection, as Android 14 asks,
 * and shows "화면 녹화 중" with the time and 중지. When the screen turns (or folds), the recording follows it at the new size
 * in the next file. The recording stops from that button, the quick settings tile, the app's row, or Android itself (its
 * screen-sharing chip, another app's capture, the lock screen: MediaProjection.Callback.onStop, which says which); every way
 * finishes the file. Then "저장했어요" opens the video, or a plain reason says why nothing was saved.
 */
public final class ScreenRecordService extends Service {
    static final String CHANNEL = "screenRecord", DONE_CHANNEL = "screenRecordDone";
    private static final int NOTIFICATION = 7461, DONE = 7462;
    private static final String START = "start", STOP = "stop";
    static final String QUALITY = "screenRecordQuality", SOUND = "screenRecordSound";
    /** When notifications are off, what the ongoing notification would have told. */
    static final String NO_NOTIFICATIONS = "알림이 꺼져 있어서 녹화 중 표시가 안 보여요 · 타일이나 들퍼레에서 멈춰요";
    private static volatile boolean running, saving;
    private static volatile long startedAt;
    private static volatile String label = "";

    /** Whether a recording is on (from the start until its file is finished). */
    static boolean running() { return running; }
    /** Whether the recording was stopped and its file is being finished. */
    static boolean saving() { return saving; }
    static long startedAt() { return startedAt; }
    /** The plan in use ("1440×3120 · 60fps · HEVC 48Mbps"), once the encoder runs. */
    static String label() { return label; }
    static ScreenRecordPlan.Quality quality(SharedPreferences settings) { return ScreenRecordPlan.Quality.of(settings.getString(QUALITY, "")); }
    static ScreenRecordPlan.Sound sound(SharedPreferences settings) { return ScreenRecordPlan.Sound.of(settings.getString(SOUND, "")); }

    /** After Android's consent: the screen's size here (current orientation), its refresh rate, and the consent's result. */
    static void start(Context context, int resultCode, Intent consent, int width, int height, int dpi, float refresh) {
        Intent intent = new Intent(context, ScreenRecordService.class).setAction(START).putExtra("code", resultCode).putExtra("consent", consent)
            .putExtra("width", width).putExtra("height", height).putExtra("dpi", dpi).putExtra("refresh", refresh);
        context.startForegroundService(intent);
    }
    static void stop(Context context) {
        try { context.startService(new Intent(context, ScreenRecordService.class).setAction(STOP)); } catch (RuntimeException notRunning) { /* nothing to stop */ }
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler work;
    private MediaProjection projection;
    private ScreenRecorder recorder;
    /** The recorder once it runs, for the worker's own steps (a turn of the screen). */
    private volatile ScreenRecorder live;
    private boolean stopping, destroyed;
    private String stopWhy = "", sounds = "";
    private int types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
    /** The screen size last reported, so a display change that is not a new size (a refresh rate) asks nothing. */
    private int seenWidth, seenHeight;
    private DisplayManager.DisplayListener display;
    private final MediaProjection.Callback watch = new MediaProjection.Callback() {
        // Android ended the capture by itself: the file is finished, not lost, and the notification says why.
        @Override public void onStop() { if (!stopping) finish(endedBy()); }
        // Android 14 and later: the screen turned, folded or changed resolution (and once just after the capture began).
        @Override public void onCapturedContentResize(int width, int height) { turned(width, height); }
    };

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("screen-record"); worker.start(); work = new Handler(worker.getLooper());
    }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? STOP : intent.getAction();
        if (START.equals(action)) begin(intent);
        else finish("");
        return START_NOT_STICKY;
    }

    private void begin(Intent intent) {
        if (projection != null || stopping) {
            // Already recording (a second tap came through): every startForegroundService still has to be answered.
            try { startForeground(NOTIFICATION, recording(stopping ? "저장하는 중" : label), types); }
            catch (RuntimeException refused) { if (!running) answerAnyway(); }
            if (!running) quit();
            return;
        }
        SharedPreferences settings = ((GyeotApp) getApplication()).store().settings;
        ScreenRecordPlan.Quality quality = quality(settings);
        ScreenRecordPlan.Sound wanted = sound(settings);
        boolean allowed = checkSelfPermission(Manifest.permission.RECORD_AUDIO) == PackageManager.PERMISSION_GRANTED;
        ScreenRecordPlan.Sound sound = wanted.needsRecordPermission() && !allowed ? ScreenRecordPlan.Sound.NONE : wanted;
        String early = sound != wanted ? "마이크 권한이 없어서 소리 없이 녹화했어요" : "";
        startedAt = System.currentTimeMillis(); label = "";
        // To the foreground before the projection is taken (Android 14). The phone's sound is also captured through the
        // recording permission, so any sound holds the microphone type; without it the capture would go silent in the background.
        types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION | (sound.any() ? ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE : 0);
        try { startForeground(NOTIFICATION, recording("준비하는 중"), types); }
        catch (RuntimeException refused) {
            types = ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION;
            try { startForeground(NOTIFICATION, recording("준비하는 중"), types); sound = ScreenRecordPlan.Sound.NONE; early = "마이크를 쓸 수 없어서 소리 없이 녹화했어요"; }
            catch (RuntimeException again) {
                answerAnyway();
                failed("화면 녹화를 시작하지 못했어요 · Android가 화면 녹화 서비스를 허락하지 않았어요 (" + again.getClass().getSimpleName() + ")"); quit(); return;
            }
        }
        MediaProjection taken = null;
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent consent = intent.getParcelableExtra("consent", Intent.class);
            if (manager != null && consent != null) taken = manager.getMediaProjection(intent.getIntExtra("code", 0), consent);
        } catch (RuntimeException refused) { taken = null; }
        if (taken == null) { failed("화면 녹화 허락을 받지 못했어요 · 다시 눌러서 ‘전체 화면’을 허락해 주세요"); quit(); return; }
        projection = taken;
        // Android 14: the callback is registered before the VirtualDisplay is made.
        projection.registerCallback(watch, main);
        running = true; changed();
        int width = intent.getIntExtra("width", 0), height = intent.getIntExtra("height", 0), dpi = intent.getIntExtra("dpi", 0);
        float refresh = intent.getFloatExtra("refresh", 0);
        seenWidth = width; seenHeight = height;
        watchDisplay();
        ScreenRecordPlan.Sound chosen = sound; String note = early; MediaProjection held = projection;
        // Files, encoders and the display on the worker thread, never the main thread.
        work.post(() -> {
            String cleared = ScreenRecorder.clearUnfinished(this);
            if (!cleared.isEmpty()) main.post(() -> { try { ((GyeotApp) getApplication()).store().status(cleared); } catch (RuntimeException ignored) { /* no screens */ } });
            ScreenRecorder made = new ScreenRecorder(this, held, width, height, dpi, refresh, quality, chosen, why -> main.post(() -> finish(why)));
            try {
                ScreenRecordPlan.Plan plan = made.start();
                live = made;
                String extra = note.isEmpty() ? made.note() : note;
                String heard = (chosen == ScreenRecordPlan.Sound.NONE ? "소리 없음" : chosen.label) + (extra.isEmpty() ? "" : " · " + extra);
                main.post(() -> {
                    sounds = heard;
                    // A stop that came while it was starting (or the service ended) finishes what has just started.
                    if (stopping) { saving = true; changed(); work.post(() -> save(made.stop(), stopWhy)); return; }
                    recorder = made; label = plan.label(); startedAt = System.currentTimeMillis();
                    show(recording(plan.label() + " · " + heard));
                    changed();
                });
            } catch (Exception failed) {
                made.release();
                main.post(() -> { failed(failed.getMessage() == null ? "화면 녹화를 시작하지 못했어요" : failed.getMessage()); end(); });
            }
        });
    }
    /**
     * Android 14 and later answer a start with any foreground type only when its conditions hold; a short service has none.
     * When nothing else was allowed it answers with that, so the app is not ended for leaving the start unanswered.
     */
    private void answerAnyway() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return;
        try { startForeground(NOTIFICATION, recording("시작하지 못했어요"), ServiceInfo.FOREGROUND_SERVICE_TYPE_SHORT_SERVICE); } catch (RuntimeException ignored) { /* nothing more to try */ }
    }

    /** On Android 13 the projection does not report a turned screen: the default display's changes are watched instead. */
    private void watchDisplay() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE || display != null) return;
        DisplayManager displays = getSystemService(DisplayManager.class);
        if (displays == null) return;
        display = new DisplayManager.DisplayListener() {
            @Override public void onDisplayAdded(int id) {}
            @Override public void onDisplayRemoved(int id) {}
            @SuppressWarnings("deprecation")
            @Override public void onDisplayChanged(int id) {
                if (id != Display.DEFAULT_DISPLAY) return;
                Display screen = displays.getDisplay(id);
                if (screen == null) return;
                Point size = new Point(); screen.getRealSize(size);
                turned(size.x, size.y);
            }
        };
        try { displays.registerDisplayListener(display, main); } catch (RuntimeException unavailable) { display = null; }
    }
    private void unwatchDisplay() {
        if (display == null) return;
        try { getSystemService(DisplayManager.class).unregisterDisplayListener(display); } catch (RuntimeException ignored) { /* gone */ }
        display = null;
    }
    /** The screen's size changed (on the main thread): the recorder follows it on the worker, then the notification says the new size. */
    private void turned(int width, int height) {
        if (stopping || projection == null || width <= 0 || height <= 0) return;
        if (width == seenWidth && height == seenHeight) return;
        seenWidth = width; seenHeight = height;
        work.post(() -> {
            ScreenRecorder current = live;
            if (current == null) return;
            ScreenRecordPlan.Plan plan = current.resize(width, height);
            main.post(() -> {
                if (stopping || recorder != current) return;
                if (plan != null) label = plan.label();
                String extra = current.note();
                show(recording(label + " · " + sounds + (extra.isEmpty() || sounds.contains(extra) ? "" : " · " + extra)));
                changed();
            });
        });
    }
    /** Why Android ended the capture by itself, with the time. */
    private String endedBy() {
        String time = LocalTime.now().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ROOT));
        KeyguardManager keyguard = getSystemService(KeyguardManager.class);
        if (keyguard != null && keyguard.isKeyguardLocked()) return "화면이 잠겨서 Android가 녹화를 끝냈어요 (" + time + ")";
        return "Android가 화면 공유를 끝냈어요 (" + time + " · 상태 표시줄의 중지, 또는 다른 앱의 화면 녹화)";
    }

    /** Ends the recording (why: why it stopped by itself, or ""), finishes the file on the worker thread and leaves. */
    private void finish(String why) {
        if (stopping) return;
        stopping = true; stopWhy = why == null ? "" : why;
        if (projection == null) { quit(); return; }
        ScreenRecorder current = recorder;
        // Still starting: the start's own step sees stopping and finishes the file (see begin).
        if (current == null) return;
        saving = true;
        show(recording("저장하는 중"));
        changed();
        work.post(() -> save(current.stop(), stopWhy));
    }
    /** On the worker thread: the saved notification (or why nothing was saved), then the service ends. */
    private void save(ScreenRecorder.Result result, String why) {
        live = null;
        String problem = why == null || why.isEmpty() ? result.problem() : why;
        if (result.saved()) saved(result, problem);
        else failed(problem.isEmpty() ? "녹화한 화면이 없어서 저장하지 않았어요" : problem + " · 저장한 화면이 없어요");
        main.post(this::end);
    }
    private void end() {
        unwatchDisplay();
        if (projection != null) { try { projection.unregisterCallback(watch); projection.stop(); } catch (RuntimeException ignored) { /* already stopped */ } projection = null; }
        recorder = null; live = null;
        quit();
        // Destroyed while it was still finishing: nothing else will end the worker.
        if (destroyed && worker != null) worker.quitSafely();
    }
    private void quit() {
        running = false; saving = false; label = "";
        if (destroyed) { try { channels().cancel(NOTIFICATION); } catch (RuntimeException ignored) { /* none */ } }
        else { try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (RuntimeException ignored) { /* not in front */ } stopSelf(); }
        changed();
    }
    @Override public void onDestroy() {
        // Normally the file is already finished (save() and end() run before stopSelf). Ended from outside while it starts
        // or records: stopping makes the start's step finish the file, or the recording's file is finished here, on the
        // worker, which quits once end() has run.
        destroyed = true;
        unwatchDisplay();
        if (!stopping) {
            stopping = true; stopWhy = "";
            ScreenRecorder current = recorder;
            if (current != null) { saving = true; work.post(() -> save(current.stop(), "")); }
        }
        if (projection == null && recorder == null) {
            if (worker != null) worker.quitSafely();
            running = false; saving = false;
        }
        super.onDestroy();
    }
    @Override public IBinder onBind(Intent intent) { return null; }

    /** The app's screens and the tile (it observes the store while the shade is open) follow the recording. */
    private void changed() {
        try { ((GyeotApp) getApplication()).store().changed(); } catch (RuntimeException ignored) { /* no screens */ }
    }

    // ── Notifications ─────────────────────────────────────────────────

    private NotificationManager channels() {
        NotificationManager notifications = getSystemService(NotificationManager.class);
        if (notifications.getNotificationChannel(CHANNEL) == null)
            notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "화면 녹화 중", NotificationManager.IMPORTANCE_LOW));
        if (notifications.getNotificationChannel(DONE_CHANNEL) == null)
            notifications.createNotificationChannel(new NotificationChannel(DONE_CHANNEL, "화면 녹화 저장", NotificationManager.IMPORTANCE_DEFAULT));
        return notifications;
    }
    private void show(Notification notification) { if (destroyed) return; try { channels().notify(NOTIFICATION, notification); } catch (RuntimeException ignored) { /* not allowed */ } }
    /** "화면 녹화 중", the time since it began, what it records, and 중지. */
    private Notification recording(String text) {
        channels();
        PendingIntent stopIt = PendingIntent.getService(this, NOTIFICATION, new Intent(this, ScreenRecordService.class).setAction(STOP), PendingIntent.FLAG_IMMUTABLE);
        PendingIntent open = PendingIntent.getActivity(this, NOTIFICATION + 1, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE);
        return new Notification.Builder(this, CHANNEL).setSmallIcon(R.drawable.ic_record).setContentTitle("화면 녹화 중").setContentText(text)
            .setUsesChronometer(true).setWhen(startedAt).setShowWhen(true).setOngoing(true).setOnlyAlertOnce(true).setContentIntent(open)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .addAction(new Notification.Action.Builder(null, "중지", stopIt).build()).build();
    }
    /** Notifications are off for the app: the same words as a toast (a service may show text toasts), so the result is not silent. */
    private void toastWhenSilent(String text) {
        boolean on;
        try { on = channels().areNotificationsEnabled(); } catch (RuntimeException unknown) { on = true; }
        if (on) return;
        main.post(() -> { try { Toast.makeText(getApplicationContext(), text, Toast.LENGTH_LONG).show(); } catch (RuntimeException ignored) { /* no toast */ } });
    }
    /** "저장했어요": a tap plays the video; 공유 hands it to another app. */
    private void saved(ScreenRecorder.Result result, String problem) {
        List<Uri> files = result.files();
        Uri first = files.get(0);
        long seconds = result.durationUs() / 1_000_000;
        String text = ScreenRecordPlan.FOLDER + " · " + AssistantService.durationText(seconds * 1000) + " · " + ScreenRecordPlan.size(result.bytes())
            + (files.size() > 1 ? " · 파일 " + files.size() + "개" : "");
        String more = (problem.isEmpty() ? "" : "\n" + problem + " (그때까지 저장했어요)") + (result.note().isEmpty() ? "" : "\n" + result.note());
        Intent view = new Intent(Intent.ACTION_VIEW).setDataAndType(first, "video/mp4").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK);
        Intent send = Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, first).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "화면 녹화 공유")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        Notification n = new Notification.Builder(this, DONE_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("저장했어요")
            .setContentText(text).setStyle(new Notification.BigTextStyle().bigText(text + more)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, DONE, view, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
            .addAction(new Notification.Action.Builder(null, "공유", PendingIntent.getActivity(this, DONE + 1, send, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build())
            .build();
        try { channels().notify(DONE, n); } catch (RuntimeException ignored) { /* not allowed */ }
        toastWhenSilent("화면 녹화를 저장했어요 · " + text + more.replace('\n', ' '));
        main.post(() -> { try { ((GyeotApp) getApplication()).store().status("화면 녹화를 저장했어요 · " + ScreenRecordPlan.FOLDER + (problem.isEmpty() ? "" : " · " + problem)); } catch (RuntimeException ignored) { /* no screens */ } });
    }
    /** Why there is no recording, in plain words, where the user will see it. */
    private void failed(String why) {
        Notification n = new Notification.Builder(this, DONE_CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("화면 녹화를 저장하지 못했어요")
            .setContentText(why).setStyle(new Notification.BigTextStyle().bigText(why)).setAutoCancel(true)
            .setContentIntent(PendingIntent.getActivity(this, DONE + 2, new Intent(this, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK), PendingIntent.FLAG_IMMUTABLE))
            .build();
        try { channels().notify(DONE, n); } catch (RuntimeException ignored) { /* not allowed */ }
        toastWhenSilent("화면 녹화를 저장하지 못했어요 · " + why);
        main.post(() -> { try { ((GyeotApp) getApplication()).store().error(why); } catch (RuntimeException ignored) { /* no screens */ } });
    }
}
