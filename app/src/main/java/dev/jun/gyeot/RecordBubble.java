package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.ColorStateList;
import android.graphics.Color;
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
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.RecordPauses;

/**
 * 녹화 중의 버블 (준: "기존에 있던 버블 주위에 버튼을 더 만들고", "녹화하기 누르면 음성대화는 자동으로 종료되게"): the voice bubble
 * goes with the voice conversation that 녹화 시작 ends, so the recording has a bubble of its own, in the same orb look and at
 * the same spot (the user's saved bubble spot, bubbleX · bubbleY), with a red dot on it. Beside it, toward the middle of the
 * screen, a small dark bar: the time it has recorded and its sound in one word, 재개 or 일시정지, and 정지.
 *
 * For 화면 녹화 it is only seen while nothing is written (Android gives an app no way to keep its own window out of its own
 * capture): before the first frame it counts down 3-2-1 at its spot, then RecordControl veils everything of ours and the
 * recording goes. While frames are written it is see-through but still there: a touch on it ticks and pauses at the touch's
 * own time first, and then it shows (RecordControl.veiledTouch); 재개 veils it again and the recording goes on once it is gone.
 * For 녹음 (no picture) it simply stays, with 일시정지 · 정지. The orb is dragged to move it (the voice bubble's spot moves too).
 *
 * It floats with the overlay permission, or as 들퍼레 폰 조작's own window when only that is on; with neither, the notification
 * and the tile carry the same buttons. Main thread only.
 */
final class RecordBubble {
    /** Where a window over every app can go: the app's overlay, else the accessibility service's own window. */
    record Host(Context context, int type) {}
    static Host host(Context app) {
        if (BuddyOverlay.allowed(app)) return new Host(app, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY);
        Context service = PhoneService.overlayContext();
        return service == null ? null : new Host(service, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY);
    }
    /** Whether it can float over other apps now. */
    static boolean possible(Context c) { return host(c.getApplicationContext()) != null; }

    private static final Handler MAIN = new Handler(Looper.getMainLooper());
    private static final int BAR = 36, BUTTON = 30, GAP = 4;
    /** The countdown's steps: 3, 2, 1, each this long. */
    static final long STEP_MS = 700;
    static final int RED = 0xffff4d4f, PAUSED = 0xffffb020, INK = 0xe6202128;
    private static RecordBubble current;
    private static Context waitingApp;
    /** Recording with nowhere to float: looked at again every few seconds, so the bubble comes once the permission is given. */
    private static final Runnable lookAgain = () -> { Context app = waitingApp; waitingApp = null; if (app != null) sync(app); };

    /** The bubble now, or null (tests). */
    static RecordBubble current() { return current; }

    /** Follows the recordings: as each one is now, gone when none is (화면 녹화 first, then 녹음). */
    static void sync(Context app) {
        AppStore store = ((GyeotApp) app.getApplicationContext()).store();
        String screen = RecordControl.screen(), audio = RecordControl.audio(store);
        MAIN.removeCallbacks(lookAgain); waitingApp = null;
        // Finishing the file: it stays see-through until the last frame is in, then goes.
        if ("saving".equals(screen)) screen = "";
        if (screen.isEmpty() && audio.isEmpty()) { if (current != null) current.remove(); current = null; return; }
        Host host = host(app);
        if (host == null) {
            if (current != null) current.remove();
            current = null; waitingApp = app; MAIN.postDelayed(lookAgain, 3000);
            // Held for a countdown nobody can see: off it goes.
            if ("countdown".equals(screen)) RecordControl.clearThen(app, () -> ScreenRecordService.go(app));
            return;
        }
        if (current != null && current.host.context() != host.context()) { current.remove(); current = null; }
        if (current == null) {
            RecordBubble made = new RecordBubble(app.getApplicationContext(), host);
            made.draw(screen, audio);
            if (!made.add()) {
                made.remove(); waitingApp = app; MAIN.postDelayed(lookAgain, 3000);
                if ("countdown".equals(screen)) RecordControl.clearThen(app, () -> ScreenRecordService.go(app));
                return;
            }
            current = made;
            return;
        }
        current.draw(screen, audio);
    }

    private final Context app;
    final Host host;
    private final WindowManager manager;
    private final SharedPreferences settings;
    private final float density;
    private final int size;
    final LinearLayout root, bar;
    final FrameLayout orbBox;
    final BubbleView.Orb orb;
    final View badge, mark;
    final ImageView toggle, stop;
    final TextView time;
    final WindowManager.LayoutParams params;
    private boolean added, removing, screenKind = true, pausedNow, live, counting, orbRight;
    private String state = "", sound = "";
    private int step;
    private final Runnable follow = this::veil;
    private final Runnable tick = new Runnable() { @Override public void run() { if (current == RecordBubble.this) { showTime(); if (live && !pausedNow) MAIN.postDelayed(this, 1000); } } };
    /** The countdown: 3, 2, 1, then everything of ours goes and the recording starts once it is gone. */
    private final Runnable countdown = new Runnable() { @Override public void run() {
        if (current != RecordBubble.this || !"countdown".equals(state)) return;
        if (step > 1) { step--; showTime(); MAIN.postDelayed(this, STEP_MS); return; }
        RecordControl.clearThen(app, () -> ScreenRecordService.go(app));
    } };

    private RecordBubble(Context app, Host host) {
        this.app = app; this.host = host;
        Context c = host.context();
        manager = c.getSystemService(WindowManager.class);
        settings = ((GyeotApp) app).store().settings;
        density = c.getResources().getDisplayMetrics().density;
        size = BubbleView.orbSide(c);
        root = new LinearLayout(c); root.setOrientation(LinearLayout.HORIZONTAL); root.setGravity(Gravity.CENTER_VERTICAL);
        orbBox = new FrameLayout(c);
        orb = new BubbleView.Orb(c);
        orbBox.addView(orb, new FrameLayout.LayoutParams(size, size));
        // The recording's red dot on the orb's rim, top toward the middle of the screen.
        badge = new View(c); badge.setBackground(oval(RED)); badge.setElevation(dp(2));
        int dot = dp(11), inset = dp(BubbleView.ORB_PAD + 3);
        FrameLayout.LayoutParams badgeAt = new FrameLayout.LayoutParams(dot, dot, Gravity.TOP | Gravity.END); badgeAt.topMargin = inset; badgeAt.rightMargin = inset;
        orbBox.addView(badge, badgeAt);
        bar = new LinearLayout(c); bar.setOrientation(LinearLayout.HORIZONTAL); bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setBackground(round(INK, dp(BAR) / 2f)); bar.setElevation(dp(6)); bar.setPadding(dp(12), 0, dp(3), 0);
        mark = new View(c); mark.setBackground(oval(RED));
        time = new TextView(c); time.setTextColor(Color.WHITE); time.setTextSize(TypedValue.COMPLEX_UNIT_SP, 13); time.setTypeface(Typeface.DEFAULT_BOLD);
        time.setFontFeatureSettings("tnum"); time.setIncludeFontPadding(false); time.setPadding(dp(6), 0, dp(4), 0); time.setSingleLine(true);
        bar.addView(mark, new LinearLayout.LayoutParams(dp(8), dp(8)));
        bar.addView(time, new LinearLayout.LayoutParams(-2, -2));
        toggle = button(c, R.drawable.ic_pause, Color.WHITE, "일시정지");
        stop = button(c, R.drawable.ic_stop, RED, "정지");
        for (ImageView b : new ImageView[]{toggle, stop}) { LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(dp(BUTTON), dp(BUTTON)); at.leftMargin = dp(1); bar.addView(b, at); }
        toggle.setOnClickListener(v -> RecordControl.act(app, screenKind ? (pausedNow ? RecordControl.SCREEN_RESUME : RecordControl.SCREEN_PAUSE) : (pausedNow ? RecordControl.AUDIO_RESUME : RecordControl.AUDIO_PAUSE)));
        stop.setOnClickListener(v -> RecordControl.act(app, screenKind ? RecordControl.SCREEN_STOP : RecordControl.AUDIO_STOP));
        // A touch while see-through pauses first (veiledTouch); otherwise the orb is dragged where it is out of the way.
        orb.setOnTouchListener(new View.OnTouchListener() {
            float sx, sy; int ox, oy; boolean moved;
            @Override public boolean onTouch(View v, MotionEvent e) {
                if (RecordControl.veiledTouch(v, e)) return true;
                switch (e.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> { sx = e.getRawX(); sy = e.getRawY(); ox = orbX(); oy = orbY(); moved = false; }
                    case MotionEvent.ACTION_MOVE -> {
                        float dx = e.getRawX() - sx, dy = e.getRawY() - sy;
                        if (!moved && Math.abs(dx) + Math.abs(dy) > dp(6)) moved = true;
                        if (moved) { Rect s = screen(); settings.edit().putInt("bubbleX", clamp(ox - Math.round(dx), s.width() - size)).putInt("bubbleY", clamp(oy + Math.round(dy), s.height() - size)).apply(); place(); }
                    }
                    default -> {}
                }
                return true;
            }
        });
        orb.setContentDescription("화면 녹화 버블");
        params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT, host.type(),
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END;
        params.setTitle("들퍼레 녹화 버블");
        root.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View v) {}
            // Taken off by the system (폰 조작 turned off under it): the next sync puts it back where it can.
            @Override public void onViewDetachedFromWindow(View v) { if (!removing && current == RecordBubble.this) { current = null; MAIN.post(() -> sync(app)); } }
        });
    }
    private static int clamp(int value, int max) { return Math.max(0, Math.min(value, Math.max(0, max))); }

    private boolean add() {
        if (manager == null) return false;
        arrange(); place();
        try { manager.addView(root, params); added = true; } catch (RuntimeException refused) { return false; }
        RecordControl.onVeil(follow); veil();
        return true;
    }
    private void remove() {
        MAIN.removeCallbacks(tick); MAIN.removeCallbacks(countdown);
        RecordControl.offVeil(follow);
        if (!added) return;
        removing = true;
        try { manager.removeView(root); } catch (RuntimeException gone) { /* already off */ }
        added = false;
    }
    /** See-through while frames are written (still touchable: veiledTouch), seen otherwise. */
    private void veil() { root.setAlpha(RecordControl.veiled() ? 0f : 1f); }

    /** As the recording is now: which one (화면 녹화 first), and its state. */
    private void draw(String screen, String audio) {
        screenKind = !screen.isEmpty();
        state = screenKind ? screen : audio;
        pausedNow = "paused".equals(state);
        live = "on".equals(state) || pausedNow;
        sound = screenKind ? ScreenRecordService.sound(settings).label : "";
        int color = pausedNow ? PAUSED : RED;
        mark.setBackground(oval(color)); badge.setBackground(oval(color));
        toggle.setImageResource(pausedNow ? R.drawable.ic_play : R.drawable.ic_pause);
        toggle.setContentDescription(pausedNow ? "재개" : "일시정지");
        toggle.setVisibility(live ? View.VISIBLE : View.GONE);
        stop.setContentDescription(screenKind ? "화면 녹화 정지" : "녹음 정지");
        // While 화면 녹화 writes, only the (see-through) orb is left: a touch on it is where it pauses.
        bar.setVisibility(screenKind && "on".equals(state) ? View.GONE : View.VISIBLE);
        if (!"countdown".equals(state)) counting = false;
        else if (!counting) { counting = true; step = 3; MAIN.removeCallbacks(countdown); MAIN.postDelayed(countdown, STEP_MS); }
        showTime();
        MAIN.removeCallbacks(tick);
        if (live && !pausedNow) MAIN.postDelayed(tick, 1000 - elapsed() % 1000);
        veil();
        arrange(); place();
    }
    /** Whether anything of it can be seen now (tests). */
    boolean showing() { return added && root.getAlpha() > 0 && root.getVisibility() == View.VISIBLE; }
    boolean barShown() { return bar.getVisibility() == View.VISIBLE; }
    private long elapsed() { return screenKind ? ScreenRecordService.elapsedMs() : RecordControl.audioElapsedMs(((GyeotApp) app).store()); }
    private void showTime() {
        String words = "starting".equals(state) ? "곧 시작" : "countdown".equals(state) ? step + "초 뒤 시작"
            : RecordPauses.clock(elapsed()) + (pausedNow && !sound.isEmpty() ? " · " + sound : "");
        time.setText(words);
        String what = screenKind ? "화면 녹화" : "녹음";
        bar.setContentDescription(what + (pausedNow ? " 일시정지 " : " ") + words);
    }

    // ── Where it is: the voice bubble's spot ─────────────────────────

    private Rect screen() {
        try { return manager.getCurrentWindowMetrics().getBounds(); }
        catch (RuntimeException unknown) { var dm = app.getResources().getDisplayMetrics(); return new Rect(0, 0, dm.widthPixels, dm.heightPixels); }
    }
    /** The orb's offset from the screen's right edge and top: the voice bubble's own (FloatingButton). */
    int orbX() { return settings.getInt("bubbleX", 16); }
    int orbY() { return settings.getInt("bubbleY", 220); }
    /** The orb's place on the screen (tests compare it with the voice bubble's). */
    Rect orbOnScreen() { Rect s = screen(); int left = s.width() - orbX() - size; return new Rect(left, orbY(), left + size, orbY() + size); }
    /** The bar on the side toward the middle of the screen: [bar][orb] on the right half, [orb][bar] on the left. */
    private void arrange() {
        boolean right = orbOnScreen().centerX() > screen().centerX();
        if (right == orbRight && root.getChildCount() == 2) return;
        orbRight = right;
        root.removeAllViews();
        LinearLayout.LayoutParams barAt = new LinearLayout.LayoutParams(-2, dp(BAR));
        if (right) { barAt.rightMargin = dp(GAP); root.addView(bar, barAt); root.addView(orbBox, new LinearLayout.LayoutParams(size, size)); }
        else { barAt.leftMargin = dp(GAP); root.addView(orbBox, new LinearLayout.LayoutParams(size, size)); root.addView(bar, barAt); }
    }
    /** The window so that the orb sits exactly at the voice bubble's spot, the bar beside it. */
    private void place() {
        arrange();
        int barWidth = 0;
        if (bar.getVisibility() == View.VISIBLE) {
            bar.measure(View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED), View.MeasureSpec.makeMeasureSpec(dp(BAR), View.MeasureSpec.EXACTLY));
            barWidth = bar.getMeasuredWidth() + dp(GAP);
        }
        params.x = orbRight ? orbX() : Math.max(0, orbX() - barWidth);
        params.y = orbY();
        if (added) try { manager.updateViewLayout(root, params); } catch (RuntimeException gone) { /* off the screen */ }
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
