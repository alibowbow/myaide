package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Color;
import android.graphics.Outline;
import android.graphics.PixelFormat;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.provider.MediaStore;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.CapturePlan;
import java.io.IOException;
import java.io.OutputStream;
import java.time.LocalDateTime;
import java.util.List;

/**
 * 캡쳐 (준: "캡쳐도 만들어줘"), from the home screen's satellite or the recording's floating controls: the satellite (or the
 * controls) leave the screen first, then the first way of CapturePlan that works takes it, without Android's consent prompt
 * when it can (들퍼레 폰 조작 takes it itself; else it presses the phone's own screenshot; while recording, a frame of the
 * recording's own capture; else Android's consent once, ScreenShotService). 들퍼레's own shots go to Pictures/들퍼레, and a
 * small thumbnail shows for 3 seconds with 공유 (the share sheet); a tap on it opens the picture. Without anywhere to float,
 * a notification carries the same. Main thread, except keep() and save().
 */
final class ScreenCapture {
    private ScreenCapture() {}
    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    static final String CHANNEL = "capture";
    private static final int NOTE = 7466;
    private static volatile boolean busy;
    private static volatile long busySince;
    private static View thumb;
    private static WindowManager thumbManager;
    private static final Runnable thumbGone = ScreenCapture::hideThumb;

    /** The ways it would try now, in order. */
    static List<CapturePlan.Way> ways(Context app) {
        return CapturePlan.ways(PhoneService.connected(), SecurityWatch.paused(app), Build.VERSION.SDK_INT, ScreenRecordService.started() && !ScreenRecordService.saving());
    }
    /** 캡쳐: the controls step aside, and in a moment the screen is taken. A second press while one is being taken is ignored. */
    static void take(Context c) {
        Context app = c.getApplicationContext();
        long now = SystemClock.uptimeMillis();
        if (busy && now - busySince < 10_000) return;
        busy = true; busySince = now;
        hideThumb();
        RecordPill.hide(true);
        List<CapturePlan.Way> ways = ways(app);
        MAIN.postDelayed(() -> next(app, ways, 0), CapturePlan.SETTLE_MS);
    }
    private static void next(Context app, List<CapturePlan.Way> ways, int at) {
        if (at >= ways.size()) { failed(app, "화면을 캡쳐하지 못했어요"); return; }
        switch (ways.get(at)) {
            case SERVICE -> PhoneService.shootScreen((shot, why) -> { if (shot != null) keep(app, shot); else next(app, ways, at + 1); });
            case SYSTEM -> {
                if (!PhoneService.systemScreenshot()) { next(app, ways, at + 1); return; }
                // The phone takes it a moment later: the controls come back after that, so they are not in it.
                busy = false;
                MAIN.postDelayed(() -> RecordPill.hide(false), 1500);
                store(app).status("휴대폰이 화면을 캡쳐했어요 · 사진 앱의 스크린샷에 있어요");
            }
            case RECORDING -> ScreenRecordService.grab(app);
            case CONSENT -> {
                // Android's consent once, then one frame (ScreenShotService saves it and comes back through keep()).
                busy = false; RecordPill.hide(false);
                RecordControl.open(app, ScreenRecordActivity.CAPTURE);
            }
        }
    }

    /** A picture taken: saved off the main thread, then the thumbnail. Any thread; the bitmap is not used after. */
    static void keep(Context c, Bitmap shot) {
        Context app = c.getApplicationContext();
        Runnable work = () -> {
            Uri saved = null; String why = "";
            try { saved = save(app, shot); } catch (IOException | RuntimeException failed) { why = failed.getMessage() == null ? failed.getClass().getSimpleName() : failed.getMessage(); }
            Bitmap small = null;
            try { small = Bitmap.createScaledBitmap(shot, Math.max(1, shot.getWidth() * 240 / Math.max(1, shot.getHeight())), 240, true); } catch (RuntimeException | OutOfMemoryError none) { /* no thumbnail */ }
            if (small != shot) shot.recycle();
            Uri uri = saved; String reason = why; Bitmap picture = small;
            MAIN.post(() -> { if (uri == null) failed(app, "캡쳐한 화면을 저장하지 못했어요" + (reason.isEmpty() ? "" : " · " + reason)); else done(app, uri, picture); });
        };
        if (Looper.myLooper() == Looper.getMainLooper()) { Thread t = new Thread(work, "gyeot-capture-save"); t.setDaemon(true); t.start(); } else work.run();
    }
    /** Into Pictures/들퍼레 (MediaStore, pending while it is written). Off the main thread. */
    static Uri save(Context app, Bitmap shot) throws IOException {
        ContentResolver resolver = app.getContentResolver();
        ContentValues values = new ContentValues();
        values.put(MediaStore.MediaColumns.DISPLAY_NAME, CapturePlan.fileName(LocalDateTime.now()));
        values.put(MediaStore.MediaColumns.MIME_TYPE, "image/png");
        values.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/" + Brand.NAME);
        values.put(MediaStore.MediaColumns.IS_PENDING, 1);
        Uri uri = resolver.insert(MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY), values);
        if (uri == null) throw new IOException("사진 저장 위치(" + CapturePlan.FOLDER + ")를 만들지 못했어요");
        try (OutputStream out = resolver.openOutputStream(uri)) {
            if (out == null || !shot.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("사진 파일에 쓰지 못했어요");
        } catch (IOException | RuntimeException failed) {
            try { resolver.delete(uri, null, null); } catch (RuntimeException ignored) { /* best effort */ }
            throw failed instanceof IOException io ? io : new IOException("사진 파일에 쓰지 못했어요", failed);
        }
        ContentValues published = new ContentValues(); published.put(MediaStore.MediaColumns.IS_PENDING, 0);
        resolver.update(uri, published, null, null);
        return uri;
    }
    private static void done(Context app, Uri uri, Bitmap picture) {
        busy = false; RecordPill.hide(false);
        store(app).status("캡쳐했어요 · " + CapturePlan.FOLDER);
        if (!showThumb(app, uri, picture)) notifySaved(app, uri, picture);
    }
    /** Why there is no picture, where the user sees it (a toast too: it may have been pressed over another app). */
    static void failed(Context c, String why) {
        Context app = c.getApplicationContext();
        Runnable say = () -> {
            busy = false; RecordPill.hide(false);
            store(app).error(why);
            try { Toast.makeText(app, why, Toast.LENGTH_LONG).show(); } catch (RuntimeException ignored) { /* no toast */ }
        };
        if (Looper.myLooper() == Looper.getMainLooper()) say.run(); else MAIN.post(say);
    }
    private static AppStore store(Context app) { return ((GyeotApp) app.getApplicationContext()).store(); }

    // ── The thumbnail ──────────────────────────────────────────────

    static Intent view(Uri uri) { return new Intent(Intent.ACTION_VIEW).setDataAndType(uri, "image/png").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION | Intent.FLAG_ACTIVITY_NEW_TASK); }
    static Intent share(Uri uri) {
        return Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION), "캡쳐 공유")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
    }
    /** The thumbnail shown now, or null (tests). */
    static View thumbnail() { return thumb; }
    /**
     * A small card at the bottom corner, above the navigation bar, for 3 seconds: the picture (a tap opens it) and 공유. False
     * when nothing can float over other apps.
     */
    static boolean showThumb(Context app, Uri uri, Bitmap picture) {
        RecordPill.Host host = RecordPill.host(app);
        if (host == null) return false;
        hideThumb();
        Context c = host.context();
        float d = c.getResources().getDisplayMetrics().density;
        LinearLayout card = new LinearLayout(c); card.setOrientation(LinearLayout.HORIZONTAL); card.setGravity(Gravity.CENTER_VERTICAL);
        GradientDrawable bg = new GradientDrawable(); bg.setColor(RecordPill.INK); bg.setCornerRadius(14 * d); bg.setStroke(Math.max(1, Math.round(d / 2)), 0x29ffffff);
        card.setBackground(bg); card.setElevation(8 * d);
        int pad = Math.round(6 * d); card.setPadding(pad, pad, pad, pad);
        ImageView pic = new ImageView(c); pic.setScaleType(ImageView.ScaleType.CENTER_CROP); pic.setContentDescription("캡쳐한 화면 · 누르면 열기");
        int high = Math.round(56 * d), wide = picture == null ? Math.round(28 * d) : Math.max(Math.round(24 * d), Math.min(Math.round(80 * d), high * picture.getWidth() / Math.max(1, picture.getHeight())));
        if (picture != null) pic.setImageBitmap(picture); else pic.setBackgroundColor(0x33ffffff);
        pic.setOutlineProvider(new ViewOutlineProvider() { @Override public void getOutline(View v, Outline o) { o.setRoundRect(0, 0, v.getWidth(), v.getHeight(), 8 * d); } });
        pic.setClipToOutline(true);
        card.addView(pic, new LinearLayout.LayoutParams(wide, high));
        TextView said = new TextView(c); said.setText("캡쳐했어요"); said.setTextColor(Color.WHITE); said.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); said.setTypeface(Typeface.DEFAULT_BOLD);
        said.setPadding(Math.round(10 * d), 0, Math.round(8 * d), 0);
        card.addView(said, new LinearLayout.LayoutParams(-2, -2));
        TextView send = new TextView(c); send.setText("공유"); send.setTextColor(Color.WHITE); send.setTextSize(TypedValue.COMPLEX_UNIT_SP, 12.5f); send.setTypeface(Typeface.DEFAULT_BOLD);
        GradientDrawable chip = new GradientDrawable(); chip.setColor(0x33ffffff); chip.setCornerRadius(99 * d); send.setBackground(chip);
        send.setPadding(Math.round(12 * d), Math.round(6 * d), Math.round(12 * d), Math.round(6 * d)); send.setContentDescription("캡쳐한 화면 공유");
        card.addView(send, new LinearLayout.LayoutParams(-2, -2));
        card.setOnClickListener(v -> { hideThumb(); try { app.startActivity(view(uri)); } catch (RuntimeException none) { Toast.makeText(app, "사진을 열 앱이 없어요 · " + CapturePlan.FOLDER, Toast.LENGTH_SHORT).show(); } });
        send.setOnClickListener(v -> { hideThumb(); try { app.startActivity(share(uri)); } catch (RuntimeException none) { Toast.makeText(app, "공유할 앱이 없어요", Toast.LENGTH_SHORT).show(); } });
        WindowManager manager = c.getSystemService(WindowManager.class);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, host.type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.BOTTOM | Gravity.START; params.setTitle("들퍼레 캡쳐");
        int bottom = Math.round(24 * d);
        try { bottom = Math.max(bottom, manager.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()).bottom); } catch (RuntimeException unknown) { /* the least */ }
        params.x = Math.round(12 * d); params.y = bottom + Math.round(12 * d);
        try { manager.addView(card, params); } catch (RuntimeException refused) { return false; }
        thumb = card; thumbManager = manager;
        MAIN.removeCallbacks(thumbGone); MAIN.postDelayed(thumbGone, 3000);
        return true;
    }
    private static void hideThumb() {
        MAIN.removeCallbacks(thumbGone);
        View shown = thumb; WindowManager manager = thumbManager;
        thumb = null; thumbManager = null;
        if (shown != null && manager != null) try { manager.removeView(shown); } catch (RuntimeException gone) { /* already off */ }
    }
    /** Nowhere to float: a quiet notification with the picture and 공유, and a short toast. */
    private static void notifySaved(Context app, Uri uri, Bitmap picture) {
        try { Toast.makeText(app, "캡쳐했어요 · " + CapturePlan.FOLDER, Toast.LENGTH_SHORT).show(); } catch (RuntimeException ignored) { /* no toast */ }
        NotificationManager notes = app.getSystemService(NotificationManager.class);
        if (notes == null) return;
        try {
            if (notes.getNotificationChannel(CHANNEL) == null) notes.createNotificationChannel(new NotificationChannel(CHANNEL, "캡쳐", NotificationManager.IMPORTANCE_LOW));
            Notification.Builder b = new Notification.Builder(app, CHANNEL).setSmallIcon(R.drawable.ic_notification).setContentTitle("캡쳐했어요").setContentText(CapturePlan.FOLDER)
                .setAutoCancel(true).setTimeoutAfter(60_000)
                .setContentIntent(PendingIntent.getActivity(app, NOTE, view(uri), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT))
                .addAction(new Notification.Action.Builder(null, "공유", PendingIntent.getActivity(app, NOTE + 1, share(uri), PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT)).build());
            if (picture != null) b.setLargeIcon(picture).setStyle(new Notification.BigPictureStyle().bigPicture(picture));
            notes.notify(NOTE, b.build());
        } catch (RuntimeException ignored) { /* not allowed */ }
    }
}
