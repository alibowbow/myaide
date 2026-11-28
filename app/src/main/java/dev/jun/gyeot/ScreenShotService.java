package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.Bitmap;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.Image;
import android.media.ImageReader;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.SystemClock;
import dev.jun.gyeot.core.Brand;

/**
 * 캡쳐's last way (CapturePlan.CONSENT: no 폰 조작 and no recording on): after Android's consent (ScreenRecordActivity in its
 * capture mode), one frame of a screen capture. In the foreground as Android asks of a capture; the frame is taken once the
 * consent's window has gone (the latest one a little after), then the capture ends at once and ScreenCapture saves it.
 */
public final class ScreenShotService extends Service {
    private static final int NOTE = 7467;
    /** How long the consent's window and the see-through start get to leave the screen before the frame is taken. */
    private static final long SETTLE_MS = 700, WAIT_MS = 2000;

    static void start(Context c, int code, Intent consent, int width, int height, int dpi) {
        c.startForegroundService(new Intent(c, ScreenShotService.class).putExtra("code", code).putExtra("consent", consent)
            .putExtra("width", width).putExtra("height", height).putExtra("dpi", dpi));
    }

    private final Handler main = new Handler(Looper.getMainLooper());
    private HandlerThread worker;
    private Handler work;
    private MediaProjection projection;
    private VirtualDisplay display;
    private ImageReader reader;
    private Image latest;
    private boolean busy, ended;

    @Override public void onCreate() {
        super.onCreate();
        worker = new HandlerThread("screen-shot"); worker.start(); work = new Handler(worker.getLooper());
    }
    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        try { startForeground(NOTE, note(), ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION); }
        catch (RuntimeException refused) { ScreenCapture.failed(this, "Android가 화면 캡쳐를 허락하지 않았어요 (" + refused.getClass().getSimpleName() + ")"); end(); return START_NOT_STICKY; }
        if (busy) return START_NOT_STICKY;
        busy = true;
        MediaProjection taken = null;
        try {
            MediaProjectionManager manager = getSystemService(MediaProjectionManager.class);
            Intent consent = intent == null ? null : intent.getParcelableExtra("consent", Intent.class);
            if (manager != null && consent != null) taken = manager.getMediaProjection(intent.getIntExtra("code", 0), consent);
        } catch (RuntimeException refused) { taken = null; }
        if (taken == null) { ScreenCapture.failed(this, "화면 캡쳐 허락을 받지 못했어요 · 다시 눌러서 ‘전체 화면’을 허락해 주세요"); end(); return START_NOT_STICKY; }
        projection = taken;
        // Android 14: the callback before the display; Android ending the capture first ends this one too.
        projection.registerCallback(new MediaProjection.Callback() { @Override public void onStop() { main.post(ScreenShotService.this::end); } }, main);
        int width = Math.max(1, intent.getIntExtra("width", 0)), height = Math.max(1, intent.getIntExtra("height", 0)), dpi = Math.max(120, intent.getIntExtra("dpi", 0));
        try {
            reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3);
            // Every frame is taken as it comes and only the newest kept, so the capture never waits on a full queue.
            reader.setOnImageAvailableListener(r -> {
                Image next; try { next = r.acquireLatestImage(); } catch (RuntimeException full) { return; }
                if (next == null) return;
                if (latest != null) latest.close();
                latest = next;
            }, work);
            display = projection.createVirtualDisplay(Brand.NAME + " 캡쳐", width, height, dpi, DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR, reader.getSurface(), null, null);
        } catch (RuntimeException refused) { display = null; }
        if (display == null) { ScreenCapture.failed(this, "화면을 담을 가상 화면을 만들지 못했어요"); end(); return START_NOT_STICKY; }
        long until = SystemClock.uptimeMillis() + SETTLE_MS + WAIT_MS;
        work.postDelayed(new Runnable() { @Override public void run() {
            Image image = latest;
            if (image == null && SystemClock.uptimeMillis() < until) { work.postDelayed(this, 50); return; }
            Bitmap shot = null;
            if (image != null) { try { shot = ScreenRecorder.pixels(image, width, height); } catch (RuntimeException | OutOfMemoryError failed) { shot = null; } finally { image.close(); latest = null; } }
            Bitmap taken = shot;
            main.post(() -> {
                end();
                if (taken == null) ScreenCapture.failed(ScreenShotService.this, "화면이 담기지 않아서 캡쳐하지 못했어요");
                else ScreenCapture.keep(getApplicationContext(), taken);
            });
        } }, SETTLE_MS);
        return START_NOT_STICKY;
    }
    private void end() {
        if (ended) return;
        ended = true;
        if (display != null) { try { display.release(); } catch (RuntimeException ignored) { /* gone */ } display = null; }
        if (projection != null) { try { projection.stop(); } catch (RuntimeException ignored) { /* stopped */ } projection = null; }
        Handler w = work; ImageReader r = reader; reader = null;
        // The reader goes on its own thread, after any frame it is still handing over.
        if (w != null) w.post(() -> { if (latest != null) { latest.close(); latest = null; } if (r != null) r.close(); worker.quitSafely(); });
        try { stopForeground(STOP_FOREGROUND_REMOVE); } catch (RuntimeException ignored) { /* not in front */ }
        stopSelf();
    }
    @Override public void onDestroy() { end(); super.onDestroy(); }
    @Override public IBinder onBind(Intent intent) { return null; }

    private Notification note() {
        NotificationManager notes = getSystemService(NotificationManager.class);
        if (notes.getNotificationChannel(ScreenCapture.CHANNEL) == null) notes.createNotificationChannel(new NotificationChannel(ScreenCapture.CHANNEL, "캡쳐", NotificationManager.IMPORTANCE_LOW));
        return new Notification.Builder(this, ScreenCapture.CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("화면을 캡쳐하는 중").setOngoing(true)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED).build();
    }
}
