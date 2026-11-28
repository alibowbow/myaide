package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Insets;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.RippleDrawable;
import android.os.Handler;
import android.os.Looper;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.WindowMetrics;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.RecordPauses;

/**
 * 녹화 화면 위 조작 (준: "녹화 시작 일시정지 및 정지 버튼도 구현해줘 녹화화면상에서 바로 조작할 수 있기"; 화면은 컴팩트하게): while a
 * 화면 녹화 or 녹음 is on, a small pill over every app, 36dp high: ● and the time it has recorded (the pauses left out), 일시정지 /
 * 재개, 중지 and 캡쳐. A tap on the time folds it into a 30dp dot (a tap on the dot opens it again); either one is dragged where
 * it is out of the way, and it always stays clear of the status bar and the navigation bar. It sits over other apps with the
 * overlay permission, or as 들퍼레 폰 조작's own window when only that is on; with neither, the notification carries the same
 * buttons and one that opens the permission.
 *
 * Android gives an app no way to keep one of its windows out of its own screen capture (only the system's windows can be),
 * so the pill shows in the recording: that is why it stays this small and folds to a dot. For 캡쳐 it leaves the screen for a
 * moment (hide). Main thread only.
 */
final class RecordPill {
    /** Where a window over every app can go: the app's overlay, else the accessibility service's own window. */
    record Host(Context context, int type) {}
    static Host host(Context app) {
        if (BuddyOverlay.allowed(app)) return new Host(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        Context service = PhoneService.overlayContext();
        return service == null ? null : new Host(service, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    }
    /** Whether the controls can float over other apps now. */
    static boolean possible(Context c) { return host(c.getApplicationContext()) != null; }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int BAR = 36, BUTTON = 30, DOT = 30;
    static final int RED = 0xffff4d4f, PAUSED = 0xffffb020, INK = 0xe6202128;
    private static RecordPill current;
    private static boolean hidden;
    private static Context waitingApp;
    /** Recording with nowhere to float: looked at again every few seconds, so the pill comes once the permission is given. */
    private static final Runnable lookAgain = () -> { Context app = waitingApp; waitingApp = null; if (app != null) sync(app); };

    /** The pill on screen now, or null (tests). */
    static RecordPill current() { return current; }

    /** Follows the recordings: shown while one is on, drawn as it is now, gone when none is. */
    static void sync(Context app) {
        AppStore store = ((GyeotApp) app.getApplicationContext()).store();
        String screen = RecordControl.screen(), audio = RecordControl.audio(store);
        MAIN.removeCallbacks(lookAgain); waitingApp = null;
        if (screen.isEmpty() && audio.isEmpty()) { if (current != null) current.remove(); current = null; return; }
        Host host = host(app);
        if (host == null) {
            if (current != null) current.remove();
            current = null; waitingApp = app; MAIN.postDelayed(lookAgain, 3000);
            return;
        }
        if (current != null && current.host.context() != host.context()) { current.remove(); current = null; }
        if (current == null) {
            RecordPill made = new RecordPill(app.getApplicationContext(), host);
            // Drawn first, so its first place is worked out at its real width.
            made.draw(screen, audio, store);
            if (!made.add()) { made.remove(); waitingApp = app; MAIN.postDelayed(lookAgain, 3000); return; }
            current = made;
            return;
        }
        current.draw(screen, audio, store);
    }
    /** Off the screen for a moment (캡쳐: so it is not in the picture), and back. */
    static void hide(boolean on) {
        hidden = on;
        if (current != null) current.root.setVisibility(on ? View.INVISIBLE : View.VISIBLE);
    }

    private final Context app;
    final Host host;
    private final WindowManager manager;
    private final SharedPreferences prefs;
    private final float density;
    final FrameLayout root;
    final LinearLayout bar, grip;
    final FrameLayout dot;
    final View mark, dotMark;
    final ImageView mic, pause, stop, shot;
    final TextView time;
    final WindowManager.LayoutParams params;
    private boolean added, removing, screenKind = true, pausedNow, live;
    private String state = "";
    private final Runnable tick = new Runnable() { @Override public void run() { if (current == RecordPill.this) { showTime(); if (live && !pausedNow) MAIN.postDelayed(this, 1000); } } };

    private RecordPill(Context app, Host host) {
        this.app = app; this.host = host;
        Context c = host.context();
        manager = c.getSystemService(WindowManager.class);
        prefs = app.getSharedPreferences("recordPill", Context.MODE_PRIVATE);
        density = c.getResources().getDisplayMetrics().density;
        root = new FrameLayout(c);
        bar = new LinearLayout(c); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(INK, dp(BAR) / 2f)); bar.setElevation(dp(6)); bar.setPadding(dp(2), 0, dp(3), 0);
        grip = new LinearLayout(c); grip.setOrientation(LinearLayout.HORIZONTAL); grip.setGravity(Gravity.CENTER_VERTICAL); grip.setPadding(dp(10), 0, dp(6), 0);
        mark = new View(c); mark.setBackground(oval(RED));
        mic = new ImageView(c); mic.setImageResource(R.drawable.ic_mic); mic.setImageTintList(ColorStateList.valueOf(RED)); mic.setVisibility(View.GONE);
        time = new TextView(c); time.setTextColor(Color.WHITE); time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setFontFeatureSettings("tnum"); time.setIncludeFontPadding(false); time.setPadding(dp(6), 0, 0, 0); time.setSingleLine(true);
        grip.addView(mark, new LinearLayout.LayoutParams(dp(9), dp(9)));
        grip.addView(mic, new LinearLayout.LayoutParams(dp(14), dp(14)));
        grip.addView(time, new LinearLayout.LayoutParams(-2, -2));
        bar.addView(grip, new LinearLayout.LayoutParams(-2, dp(BAR)));
        pause = button(c, R.drawable.ic_pause, Color.WHITE, "일시정지");
        stop = button(c, R.drawable.ic_stop, RED, "중지");
        shot = button(c, R.drawable.ic_capture, Color.WHITE, "캡쳐");
        for (ImageView b : new ImageView[]{pause, stop, shot}) { LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)); at.leftMargin = dp(1); bar.addView(b, at); }
        dot = new FrameLayout(c); dot.setBackground(round(INK, dp(DOT) / 2f)); dot.setElevation(dp(6));
        dotMark = new View(c); dotMark.setBackground(oval(RED));
        dot.addView(dotMark, new FrameLayout.LayoutParams(dp(10), dp(10), Gravity.CENTER));
        root.addView(bar, new FrameLayout.LayoutParams(-2, dp(BAR)));
        root.addView(dot, new FrameLayout.LayoutParams(dp(DOT), dp(DOT)));
        // Room for the shadow around it.
        root.setPadding(dp(4), dp(4), dp(4), dp(4)); root.setClipToPadding(false);
        boolean folded = prefs.getBoolean("folded", false);
        bar.setVisibility(folded ? View.GONE : View.VISIBLE); dot.setVisibility(folded ? View.VISIBLE : View.GONE);
        drag(grip, () -> fold(true));
        drag(dot, () -> fold(false));
        pause.setOnClickListener(v -> RecordControl.act(app, screenKind ? (pausedNow ? RecordControl.SCREEN_RESUME : RecordControl.SCREEN_PAUSE) : (pausedNow ? RecordControl.AUDIO_RESUME : RecordControl.AUDIO_PAUSE)));
        stop.setOnClickListener(v -> RecordControl.act(app, screenKind ? RecordControl.SCREEN_STOP : RecordControl.AUDIO_STOP));
        shot.setOnClickListener(v -> RecordControl.act(app, RecordControl.CAPTURE));
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, host.type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.START;
        params.setTitle("들퍼레 녹화 조작");
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            // Taken off by the system (폰 조작 turned off under it): the next sync puts it back where it can.
            @Override public void onViewDetachedFromWindow(View v) { if (!removing && current == RecordPill.this) { current = null; MAIN.post(() -> sync(app)); } }
        });
    }

    private boolean add() {
        if (manager == null) return false;
        Rect safe = safe();
        int[] size = measure();
        params.x = prefs.getInt("x", safe.right - size[0] - dp(8));
        params.y = prefs.getInt("y", safe.top + dp(8));
        clamp(size);
        try { manager.addView(root, params); added = true; } catch (RuntimeException refused) { return false; }
        root.setVisibility(hidden ? View.INVISIBLE : View.VISIBLE);
        return true;
    }
    private void remove() {
        MAIN.removeCallbacks(tick);
        if (!added) return;
        removing = true;
        try { manager.removeView(root); } catch (RuntimeException gone) { /* already off */ }
        added = false;
    }

    /** As the recording is now: which one (화면 녹화 first), paused or not, starting or saving. */
    private void draw(String screen, String audio, AppStore store) {
        screenKind = !screen.isEmpty();
        state = screenKind ? screen : audio;
        pausedNow = "paused".equals(state);
        live = "on".equals(state) || pausedNow;
        int color = pausedNow ? PAUSED : RED;
        mark.setBackground(oval(color)); dotMark.setBackground(oval(color)); mic.setImageTintList(ColorStateList.valueOf(color));
        mark.setVisibility(screenKind ? View.VISIBLE : View.GONE); mic.setVisibility(screenKind ? View.GONE : View.VISIBLE);
        pause.setImageResource(pausedNow ? R.drawable.ic_play : R.drawable.ic_pause);
        pause.setContentDescription(pausedNow ? "재개" : "일시정지");
        pause.setVisibility(live ? View.VISIBLE : View.GONE);
        stop.setVisibility("saving".equals(state) ? View.GONE : View.VISIBLE);
        stop.setContentDescription(screenKind ? "화면 녹화 중지" : "녹음 중지");
        shot.setVisibility(live ? View.VISIBLE : View.GONE);
        showTime();
        MAIN.removeCallbacks(tick);
        if (live && !pausedNow) MAIN.postDelayed(tick, 1000 - elapsed() % 1000);
        root.post(() -> clamp(measure()));
    }
    private long elapsed() { return screenKind ? ScreenRecordService.elapsedMs() : RecordControl.audioElapsedMs(((GyeotApp) app).store()); }
    private void showTime() {
        String words = "starting".equals(state) ? "준비 중" : "saving".equals(state) ? "저장 중" : RecordPauses.clock(elapsed());
        time.setText(words);
        String what = screenKind ? "화면 녹화" : "녹음";
        grip.setContentDescription(what + (pausedNow ? " 일시정지 " : " ") + words + " · 누르면 접기, 끌어서 옮기기");
        dot.setContentDescription(what + (pausedNow ? " 일시정지 · " : " 중 · ") + "누르면 조작 버튼 펼치기");
    }
    /** Folds into the dot (or opens again), keeping the side of the screen it is on. */
    void fold(boolean in) {
        int[] before = measure();
        boolean right = params.x + before[0] / 2 > safe().centerX();
        bar.setVisibility(in ? View.GONE : View.VISIBLE); dot.setVisibility(in ? View.VISIBLE : View.GONE);
        int[] after = measure();
        if (right) params.x += before[0] - after[0];
        prefs.edit().putBoolean("folded", in).apply();
        clamp(after); save();
    }
    boolean folded() { return dot.getVisibility() == View.VISIBLE; }

    // ── Where it is ──────────────────────────────────────────────────

    /** The screen without its bars and cutout: the pill never covers the navigation bar or the status bar. */
    Rect safe() {
        Rect bounds; Insets in = Insets.NONE;
        try {
            WindowMetrics m = manager.getCurrentWindowMetrics();
            bounds = m.getBounds();
            in = m.getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
        } catch (RuntimeException unknown) {
            var dm = app.getResources().getDisplayMetrics(); bounds = new Rect(0, 0, dm.widthPixels, dm.heightPixels);
        }
        // Without insets (a test, an odd launcher), a status bar's and a gesture bar's height all the same.
        int top = Math.max(in.top, dp(24)), bottom = Math.max(in.bottom, dp(24));
        return new Rect(bounds.left + in.left, bounds.top + top, bounds.right - in.right, bounds.bottom - bottom);
    }
    private int[] measure() {
        root.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return new int[]{root.getMeasuredWidth(), root.getMeasuredHeight()};
    }
    private void clamp(int[] size) {
        Rect safe = safe();
        params.x = Math.max(safe.left, Math.min(params.x, safe.right - size[0]));
        params.y = Math.max(safe.top, Math.min(params.y, safe.bottom - size[1]));
        if (added) try { manager.updateViewLayout(root, params); } catch (RuntimeException gone) { /* off the screen */ }
    }
    private void save() { prefs.edit().putInt("x", params.x).putInt("y", params.y).apply(); }
    /** Dragged by handle; a touch that did not move is a tap (also for TalkBack, through its click). */
    private void drag(View handle, Runnable tap) {
        int slop = ViewConfiguration.get(host.context()).getScaledTouchSlop();
        handle.setOnClickListener(v -> tap.run());
        handle.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> { sx = e.getRawX(); sy = e.getRawY(); ox = params.x; oy = params.y; moved = false; v.setPressed(true); return true; }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.hypot(dx, dy) < slop) return true;
                        moved = true; v.setPressed(false);
                        params.x = ox + Math.round(dx); params.y = oy + Math.round(dy);
                        clamp(measure());
                        return true;
                    }
                    case MotionEvent.ACTION_UP -> { v.setPressed(false); if (moved) save(); else v.performClick(); return true; }
                    case MotionEvent.ACTION_CANCEL -> { v.setPressed(false); if (moved) save(); return true; }
                    default -> { return false; }
                }
            }
        });
    }

    // ── Drawing ──────────────────────────────────────────────────────

    private ImageView button(Context c, int icon, int tint, String name) {
        ImageView b = new ImageView(c);
        b.setImageResource(icon); b.setImageTintList(ColorStateList.valueOf(tint)); b.setContentDescription(name);
        b.setScaleType(ImageView.ScaleType.CENTER_INSIDE); b.setPadding(dp(7), dp(7), dp(7), dp(7));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf(0x40ffffff), null, oval(Color.WHITE)));
        b.setClickable(true); b.setFocusable(true);
        return b;
    }
    private static GradientDrawable oval(int color) { GradientDrawable g = new GradientDrawable(); g.setShape(GradientDrawable.OVAL); g.setColor(color); return g; }
    private GradientDrawable round(int color, float radius) {
        GradientDrawable g = new GradientDrawable(); g.setColor(color); g.setCornerRadius(radius); g.setStroke(Math.max(1, dp(1) / 2), 0x29ffffff); return g;
    }
    private int dp(float v) { return Math.round(v * density); }
}
