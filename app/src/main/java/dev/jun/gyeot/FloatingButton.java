package dev.jun.gyeot;

import android.app.KeyguardManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.SharedPreferences;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Brand;

/**
 * Optional small control, not a second app screen; it never captures keyboard focus. A tap opens a short menu (mute, 화면 보기
 * when allowed, phone control back on after a stop, end, open 들퍼레), a long press ends the voice session at once, and a drag
 * moves it. It is drawn by BubbleView: a warm dark glass orb with a dusk rim that moves with the call (listening, speaking,
 * connecting, thinking). While phone control is on, a small dark moon
 * with a white stop mark floats clear of the orb above it, toward the middle of the screen: part of the same button, moving
 * with it. Tapping the moon stops phone control at once.
 */
final class FloatingButton implements AutoCloseable, PhoneService.Anchor {
    /** Warm Glass: coral choices (6:1 or more) and the dark-mode error red for 음성 종료, on a warm dark menu. */
    private static final int LIVE = 0xffeb9b7d, MENU = 0xf2262522, MENU_TEXT = 0xfff2f0ea, END = 0xffff7062;
    private final Context context;
    private final WindowManager manager;
    private final WindowManager.LayoutParams params;
    private final FrameLayout view;
    private final BubbleView.Orb orb;
    private final BubbleView.Moon satellite;
    private final SharedPreferences settings;
    private final Runnable mute, stop, look, rearm;
    private final Handler main = new Handler(Looper.getMainLooper());
    /** The orb view's side (the orb with room for its shadow and glow). */
    private final int size;
    private final float density;
    private LinearLayout menu;
    private boolean attached, moved, dragging, muted, phoneOn, satelliteLeft = true;
    /**
     * The screen going off, coming on and being unlocked: the orb moves only while someone can see it. An overlay gets no
     * visibility change for these (Android hides it on the lock screen itself), the same reason EdgeButton listens to them.
     */
    private final BroadcastReceiver screenState = new BroadcastReceiver() {
        @Override public void onReceive(Context context, Intent intent) { orb.awake(!Intent.ACTION_SCREEN_OFF.equals(intent.getAction()) && unlocked()); }
    };
    private boolean screenWatched;
    private String thinkingBy = "";
    /** The moon shows while phone control is on, and stays (as ▶) while it can be turned back on in this call. */
    private boolean satelliteShown;
    private float startX, startY;
    /** The orb's offset from the screen's top-right corner (what is saved); the window grows from it toward the satellite. */
    private int orbX, orbY, originalX, originalY;
    /** Where Android really put the window against what its params say (status bar and the like), measured after layout. */
    private int shiftX, shiftY;

    /** look is null when 화면 보기 is off or this is not the voice assistant; rearm is null when phone control is not allowed. */
    FloatingButton(Context context, SharedPreferences settings, Runnable mute, Runnable stop, Runnable look, Runnable rearm) {
        this.context = context; this.settings = settings; this.mute = mute; this.stop = stop; this.look = look; this.rearm = rearm;
        density = context.getResources().getDisplayMetrics().density;
        size = BubbleView.orbSide(context);
        manager = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
        params = new WindowManager.LayoutParams(size, size, WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL, PixelFormat.TRANSLUCENT);
        params.gravity = Gravity.TOP | Gravity.END; orbX = settings.getInt("bubbleX", 16); orbY = settings.getInt("bubbleY", 220);
        view = new FrameLayout(context);
        orb = new BubbleView.Orb(context); satellite = new BubbleView.Moon(context);
        satellite.setContentDescription(Brand.NAME + "의 폰 조작 즉시 멈추기");
        if (rearm != null && PhoneService.connected()) satelliteShown = true;
        view.addView(orb); view.addView(satellite); BubbleView.place(orb, satellite, true, false);
        orb.setOnClickListener(v -> { if (menu == null) showMenu(); else hideMenu(); });
        orb.setOnLongClickListener(v -> { hideMenu(); stop.run(); return true; });
        // The moon is phone control's switch: ■ stops at once; after a stop it stays as ▶ and one tap turns control back on.
        satellite.setOnClickListener(v -> {
            hideMenu();
            if (phoneOn) {
                PhoneService.stopByUser();
                if (rearm != null) android.widget.Toast.makeText(context, "폰 조작을 멈췄어요 · 위성을 다시 누르면 켜져요", android.widget.Toast.LENGTH_SHORT).show();
            } else if (rearm != null) rearm.run();
        });
        View.OnTouchListener drag = (v, event) -> {
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> { startX = event.getRawX(); startY = event.getRawY(); originalX = orbX; originalY = orbY; moved = false; dragging = true; }
                case MotionEvent.ACTION_MOVE -> {
                    float dx = event.getRawX() - startX, dy = event.getRawY() - startY;
                    // A drag must never turn into the long-press that ends the voice session, or into a stop.
                    if (!moved && Math.abs(dx) + Math.abs(dy) > 12) { moved = true; v.cancelLongPress(); v.setPressed(false); hideMenu(); }
                    if (moved && attached) {
                        Rect screen = manager.getCurrentWindowMetrics().getBounds();
                        orbX = clamp(originalX - Math.round(dx), screen.width() - size); orbY = clamp(originalY + Math.round(dy), screen.height() - size);
                        layoutWindow(); return true;
                    }
                }
                case MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    dragging = false;
                    if (moved) { v.setPressed(false); settings.edit().putInt("bubbleX", orbX).putInt("bubbleY", orbY).apply(); return true; }
                }
                default -> {}
            }
            return false;
        };
        orb.setOnTouchListener(drag); satellite.setOnTouchListener(drag);
        // Measure where the window really is once it is laid out (and whenever its size changes), then tell phone control.
        view.getViewTreeObserver().addOnGlobalLayoutListener(this::measured);
        update(false);
    }
    private static int clamp(int value, int max) { return Math.max(0, Math.min(value, Math.max(0, max))); }

    void show() {
        layoutWindow();
        try { manager.addView(view, params); attached = true; } catch (Exception ignored) { return; }
        try {
            IntentFilter filter = new IntentFilter(Intent.ACTION_SCREEN_OFF); filter.addAction(Intent.ACTION_SCREEN_ON); filter.addAction(Intent.ACTION_USER_PRESENT);
            context.registerReceiver(screenState, filter, Context.RECEIVER_NOT_EXPORTED); screenWatched = true;
        } catch (RuntimeException ignored) { /* the orb then moves whenever it is shown, as before */ }
        orb.awake(unlocked());
        dock();
    }
    /** The screen is on and the keyguard is not up (EdgeButton's test). */
    private boolean unlocked() {
        KeyguardManager lock = context.getSystemService(KeyguardManager.class); PowerManager power = context.getSystemService(PowerManager.class);
        return (lock == null || !lock.isKeyguardLocked()) && (power == null || power.isInteractive());
    }
    /** On screen now. From Android 15 the overlay permission lets an app start activities from the background only while one is. */
    boolean visible() { return attached && view.isShown(); }
    /**
     * Sizes and places the window for the orb's position: just the orb, or the orb with room for the satellite on the side
     * facing the middle of the screen. The orb itself never moves when the satellite comes or goes.
     */
    private void layoutWindow() {
        Rect screen = manager.getCurrentWindowMetrics().getBounds();
        satelliteLeft = orbX + size / 2 < screen.width() / 2;
        boolean moon = phoneOn || satelliteShown;
        int[] window = BubbleView.window(context, moon);
        params.width = window[0]; params.height = window[1];
        params.y = Math.max(0, orbY - (window[1] - size));
        params.x = satelliteLeft ? orbX : Math.max(0, orbX - (window[0] - size));
        BubbleView.place(orb, satellite, satelliteLeft, moon);
        satellite.paused(!phoneOn);
        satellite.setContentDescription(phoneOn ? Brand.NAME + "의 폰 조작 즉시 멈추기" : Brand.NAME + "의 폰 조작 다시 켜기");
        if (attached) { try { manager.updateViewLayout(view, params); } catch (Exception ignored) {} dock(); }
    }
    private void measured() {
        if (!attached || dragging || !view.isAttachedToWindow()) return;
        Rect screen = manager.getCurrentWindowMetrics().getBounds(); int[] at = new int[2]; view.getLocationOnScreen(at);
        shiftX = at[0] - (screen.width() - params.x - params.width); shiftY = at[1] - params.y;
        dock();
    }
    /** Tells phone control where the button is on screen, so the model never touches it. */
    private void dock() {
        Rect screen = manager.getCurrentWindowMetrics().getBounds();
        int left = screen.width() - params.x - params.width + shiftX, top = params.y + shiftY;
        PhoneService.dock(this, new Rect(left, top, left + params.width, top + params.height));
    }
    @Override public void armed(boolean on) {
        if (phoneOn == on) return;
        phoneOn = on; if (on && rearm != null) satelliteShown = true; layoutWindow(); update(muted);
    }
    /** The call's state (VoiceOrb.of): the orb shows it, so the user can see the call listening or speaking. */
    void voice(VoiceOrb.State state) { orb.voice(state); }
    /** Where the orb asks, each frame, whether an answer still plays (the call's speaker): it shows speaking until heard out. */
    void playing(java.util.function.BooleanSupplier playing) { orb.playing(playing); }
    /** A microphone chunk (16-bit PCM): the orb's bars follow its loudness. Called on the microphone's thread. */
    void heard(byte[] pcm) { orb.level(BubbleView.loudness(pcm, pcm.length)); }
    /** The brain is thinking (by names who), or done: the orb animates meanwhile. */
    void thinking(String by) {
        thinkingBy = by == null ? "" : by;
        orb.thinking(!thinkingBy.isEmpty()); update(muted);
    }
    void update(boolean muted) {
        this.muted = muted;
        orb.muted(muted);
        orb.setContentDescription((muted ? Brand.NAME + " 마이크 음소거됨" : Brand.NAME + " 음성") + (thinkingBy.isEmpty() ? "" : ", " + thinkingBy + "가 생각하는 중") + (phoneOn ? ", 폰 조작 중. 위에 떠 있는 작은 멈춤 버튼으로 바로 멈출 수 있어요" : satelliteShown ? ", 폰 조작 멈춤. 위의 작은 버튼을 누르면 다시 켜져요" : "")
            + ". 누르면 메뉴(음소거·" + (look != null ? "화면 보기·" : "") + (rearm != null && !phoneOn ? "폰 조작 다시 켜기·" : "") + "목소리 설정·음성 종료·" + Brand.NAME + " 열기), 길게 누르면 음성 종료");
        if (menu != null) { hideMenu(); showMenu(); }
    }
    /** A short column of pills beside the bubble; it closes on a choice, an outside touch, or after a few seconds. */
    private void showMenu() {
        if (!attached) return;
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(6 * density); row.setPadding(pad, pad, pad, pad);
        GradientDrawable panel = new GradientDrawable(); panel.setColor(MENU); panel.setCornerRadius(24 * density); row.setBackground(panel); row.setElevation(12 * density);
        row.addView(pill(muted ? "다시 듣기" : "음소거", MENU_TEXT, () -> { hideMenu(); mute.run(); }));
        if (look != null) row.addView(pill("화면 보기", LIVE, () -> { hideMenu(); look.run(); }));
        if (rearm != null && !phoneOn) row.addView(pill("폰 조작 다시 켜기", LIVE, () -> { hideMenu(); rearm.run(); }));
        // 목소리 설정: the voice, its speaking style, loudness and speed (설정 → 목소리와 대화), over whatever is open.
        row.addView(pill("목소리 설정", LIVE, () -> {
            hideMenu();
            try { context.startActivity(new Intent(context, MainActivity.class).putExtra(MainActivity.SETTINGS_PAGE, "voice").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)); } catch (Exception ignored) {}
        }));
        row.addView(pill("음성 종료", END, () -> { hideMenu(); stop.run(); }));
        row.addView(pill(Brand.NAME + " 열기", LIVE, () -> {
            hideMenu();
            try { context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); } catch (Exception ignored) {}
        }));
        WindowManager.LayoutParams menuParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        menuParams.gravity = Gravity.TOP | Gravity.END; menuParams.x = orbX + size + Math.round(8 * density); menuParams.y = orbY;
        row.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideMenu(); return true; } return false; });
        try { manager.addView(row, menuParams); menu = row; main.postDelayed(this::hideMenu, 6000); } catch (Exception ignored) {}
    }
    private TextView pill(String text, int color, Runnable action) {
        TextView pill = new TextView(context); pill.setText(text); pill.setTextSize(14); pill.setTextColor(color); pill.setTypeface(Ui.weight(650));
        int x = Math.round(14 * density), y = Math.round(10 * density); pill.setPadding(x, y, x, y);
        pill.setOnClickListener(v -> action.run());
        return pill;
    }
    private void hideMenu() {
        main.removeCallbacksAndMessages(null);
        View current = menu; menu = null;
        if (current != null) try { manager.removeView(current); } catch (Exception ignored) {}
    }
    @Override public void close() {
        hideMenu(); PhoneService.undock(this);
        if (screenWatched) { screenWatched = false; try { context.unregisterReceiver(screenState); } catch (IllegalArgumentException ignored) {} }
        if (attached) try { manager.removeView(view); } catch (Exception ignored) {}
        attached = false;
    }
}
