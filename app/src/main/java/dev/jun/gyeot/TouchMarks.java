package dev.jun.gyeot;

import android.content.ContentResolver;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import java.util.ArrayList;
import java.util.List;

/**
 * 누른 위치 표시 (준: "녹화하기에 누른곳 표시하기 기능이 없어"): while a screen recording with it on writes frames, a small soft
 * circle where the screen was touched, drawn in a layer of its own that is meant to be in the video (unlike our controls it is
 * never veiled) and never takes a touch (FLAG_NOT_TOUCHABLE, as 폰 조작's own window, which Android trusts to pass touches on).
 *
 * Where the finger is, in other apps: Android's own 누른 위치 표시 is the private system setting show_touches, which an app
 * cannot write (SettingsProvider: "You cannot change private secure settings." for apps targeting Android 6 or later;
 * systemShowTouches tries it once and notes the answer). Accessibility can listen to the touchscreen (setMotionEventSources),
 * but then it takes the touches away from the apps; the way to only watch them (setObservedMotionEventSources) is a hidden
 * test API. So 폰 조작 tells where a view was pressed, long-pressed or scrolled (its click and scroll events, asked for only
 * while such a recording writes) and the circle goes at that view's middle, a short trail for a scroll: roughly where the
 * finger was, which the start sheet says. motion() takes exact finger positions (down, move, up), should they ever come.
 *
 * Nothing is drawn while paused or while anything else of ours shows (only while frames are written), so the touches on our
 * own controls never leave a circle. Main thread only.
 */
final class TouchMarks {
    static final String SETTING = "screenRecordTouches";
    static boolean wanted(SharedPreferences settings) { return settings.getBoolean(SETTING, false); }
    /** How long a circle takes to fade after the finger lifts, and how long a pressed view's circle stays before fading. */
    static final long FADE_MS = 300, HOLD_MS = 120;
    private static TouchMarks current;
    /** The layer now, or null (tests). */
    static TouchMarks current() { return current; }

    /** A recording with 누른 위치 표시 began: the layer goes up and 폰 조작 starts telling where views are pressed. */
    static void start(Context app) {
        if (current != null) return;
        Context service = PhoneService.overlayContext();
        Context host = service != null ? service : app;
        TouchMarks made = new TouchMarks(host, service != null);
        if (!made.add()) return;
        current = made;
        PhoneService.watchTouches(true);
    }
    /** The recording ended: the layer goes and 폰 조작 stops telling. */
    static void stop() {
        PhoneService.watchTouches(false);
        if (current != null) current.remove();
        current = null;
    }
    /** Paused (or ended): whatever shows goes at once, so no circle is left over when writing goes on. */
    static void clear() { if (current != null) { current.marks.clear(); current.layer.invalidate(); } }
    private static boolean writing() { return ScreenRecordService.writing(); }

    /** An exact finger (screen pixels): down starts a circle, move follows it, up lets it fade. */
    static void motion(MotionEvent e) {
        TouchMarks t = current;
        if (t == null) return;
        long now = SystemClock.uptimeMillis();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> {
                if (!writing()) return;
                int i = e.getActionIndex();
                t.marks.add(new Mark(e.getPointerId(i), e.getRawX(i), e.getRawY(i), now));
            }
            case MotionEvent.ACTION_MOVE -> {
                for (int i = 0; i < e.getPointerCount(); i++) { Mark m = t.find(e.getPointerId(i)); if (m != null) m.to(e.getRawX(i), e.getRawY(i)); }
            }
            case MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> { Mark m = t.find(e.getPointerId(e.getActionIndex())); if (m != null) m.liftedAt = now; }
            case MotionEvent.ACTION_CANCEL -> { for (Mark m : t.marks) if (m.liftedAt == 0) m.liftedAt = now; }
            default -> { return; }
        }
        t.layer.postInvalidateOnAnimation();
    }
    /** A view was pressed (or long-pressed) in another app: a circle at its middle, held a moment, then fading. */
    static void pressed(Rect bounds) {
        TouchMarks t = current;
        if (t == null || !writing() || bounds.isEmpty()) return;
        long now = SystemClock.uptimeMillis();
        Mark m = new Mark(-1, bounds.exactCenterX(), bounds.exactCenterY(), now);
        m.liftedAt = now + HOLD_MS;
        t.marks.add(m);
        t.layer.postInvalidateOnAnimation();
    }
    /** A view scrolled by (dx, dy): a short trail across its middle, the way the finger went (against the content). */
    static void scrolled(Rect bounds, int dx, int dy) {
        TouchMarks t = current;
        if (t == null || !writing() || bounds.isEmpty()) return;
        float reach = Math.min(bounds.width(), bounds.height()) / 4f;
        float len = (float) Math.hypot(dx, dy);
        float ux = len == 0 ? 0 : -dx / len, uy = len == 0 ? -1 : -dy / len;
        long now = SystemClock.uptimeMillis();
        Mark m = new Mark(-1, bounds.exactCenterX() - ux * reach, bounds.exactCenterY() - uy * reach, now);
        for (int i = 1; i <= 4; i++) m.to(bounds.exactCenterX() - ux * reach + ux * reach * 2 * i / 4, bounds.exactCenterY() - uy * reach + uy * reach * 2 * i / 4);
        m.liftedAt = now + HOLD_MS;
        t.marks.add(m);
        t.layer.postInvalidateOnAnimation();
    }

    /**
     * Android's own 누른 위치 표시 (show_touches) written back as it is: an app gets "You cannot change private secure settings."
     * (or a missing WRITE_SETTINGS), which shows on the phone why 들퍼레 draws its own circles. Returns what happened.
     */
    static String systemShowTouches(Context c) {
        ContentResolver r = c.getContentResolver();
        try { Settings.System.putInt(r, "show_touches", Settings.System.getInt(r, "show_touches", 0)); return "written"; }
        catch (RuntimeException refused) { return refused.getClass().getSimpleName() + ": " + refused.getMessage(); }
    }

    /** One finger: where it is, where it was (a short trail), when it went down and when it lifted (0: still down). */
    static final class Mark {
        final int pointer; final long at; long liftedAt;
        final List<float[]> path = new ArrayList<>();
        Mark(int pointer, float x, float y, long at) { this.pointer = pointer; this.at = at; path.add(new float[]{x, y}); }
        void to(float x, float y) { path.add(new float[]{x, y}); if (path.size() > 8) path.remove(0); }
        float[] now() { return path.get(path.size() - 1); }
        /** 1 while down (and while held), then down to 0 over FADE_MS. */
        float alpha(long now) { return liftedAt == 0 || now <= liftedAt ? 1f : Math.max(0f, 1f - (now - liftedAt) / (float) FADE_MS); }
    }
    final List<Mark> marks = new ArrayList<>();
    /** The circles still seen now (the faded ones go), for the tests. */
    List<Mark> shown() { long now = SystemClock.uptimeMillis(); marks.removeIf(m -> m.alpha(now) <= 0); return marks; }
    private Mark find(int pointer) { for (int i = marks.size() - 1; i >= 0; i--) { Mark m = marks.get(i); if (m.pointer == pointer && m.liftedAt == 0) return m; } return null; }

    private final Context host;
    private final WindowManager manager;
    final WindowManager.LayoutParams params;
    final View layer;
    private boolean added;
    private final float density;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), ring = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG);

    private TouchMarks(Context host, boolean accessibility) {
        this.host = host;
        manager = host.getSystemService(WindowManager.class);
        density = host.getResources().getDisplayMetrics().density;
        // A soft grey disc with a white ring and a thin dark edge: seen on light and dark screens alike.
        fill.setColor(0xff8e8e96); ring.setStyle(Paint.Style.STROKE); ring.setStrokeWidth(2 * density); ring.setColor(0xffffffff);
        edge.setStyle(Paint.Style.STROKE); edge.setStrokeWidth(density); edge.setColor(0xff2a2a30);
        layer = new View(host) {
            @Override protected void onDraw(Canvas canvas) { paint(canvas); }
        };
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            accessibility ? WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY : WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.setTitle("들퍼레 누른 위치");
        // Another app's overlay lets touches through only at 80% opacity or less (Android 12); 폰 조작's own window is trusted.
        if (!accessibility) params.alpha = 0.8f;
    }
    private boolean add() {
        if (manager == null) return false;
        try { manager.addView(layer, params); added = true; return true; } catch (RuntimeException refused) { return false; }
    }
    private void remove() {
        if (!added) return;
        try { manager.removeView(layer); } catch (RuntimeException gone) { /* off */ }
        added = false;
    }
    /** The circles now: a soft white disc with a dark ring (seen on light and dark screens), its trail as smaller dots. */
    private void paint(Canvas canvas) {
        long now = SystemClock.uptimeMillis();
        float r = 18 * density;
        for (int i = marks.size() - 1; i >= 0; i--) {
            Mark m = marks.get(i);
            float a = m.alpha(now);
            if (a <= 0) { marks.remove(i); continue; }
            for (int k = 0; k < m.path.size() - 1; k++) {
                float[] p = m.path.get(k); float share = (k + 1f) / m.path.size();
                fill.setAlpha(Math.round(90 * a * share)); canvas.drawCircle(p[0], p[1], r * (0.35f + 0.4f * share), fill);
            }
            float[] p = m.now();
            fill.setAlpha(Math.round(120 * a)); canvas.drawCircle(p[0], p[1], r, fill);
            ring.setAlpha(Math.round(220 * a)); canvas.drawCircle(p[0], p[1], r, ring);
            edge.setAlpha(Math.round(110 * a)); canvas.drawCircle(p[0], p[1], r + 1.5f * density, edge);
        }
        if (!marks.isEmpty()) layer.postInvalidateOnAnimation();
    }
}
