package dev.jun.gyeot;

import android.app.KeyguardManager;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.EdgeSpot;
import java.util.List;

/**
 * Optional "들퍼레" handle on the screen edge, drawn by the accessibility service so nothing has to keep running or listening.
 * Swiping it into the screen (or a tap) starts voice through QuickVoiceActivity at once; a drag along the edge moves it; a
 * long press offers 열기 · 반대쪽으로 · 잠깐 숨기기 · 버튼 끄기. It hides during voice, phone control and on the lock screen. It never reads the screen.
 *
 * It sits just below Samsung's own Edge panel handle, on its side (준: "손잡이 위치를 기존 삼성엣지 아래로 해줘"), and follows it
 * when it moves, until the user drags this one (EdgeSpot); Samsung's is looked for among the windows the service already
 * lists, at most every few seconds. While a screen recording writes frames it is see-through, still where it was (RecordControl's
 * veil), and a touch on it pauses the recording first.
 */
final class EdgeButton implements AutoCloseable {
    /**
     * Warm Glass: a terracotta handle (about 3:1 on white and on black screens, steadier than the old violet's 2.6 to 4.1),
     * a warm dark menu, coral for the main choice (6:1 or more on the menu) and the dark-mode error red for 버튼 끄기.
     */
    private static final int HANDLE = 0xccc4643f, MENU = 0xf2262522, MENU_TEXT = 0xfff2f0ea, ACCENT = 0xffeb9b7d, OFF = 0xffff7062;
    private final Context context;
    private final AppStore store;
    private final WindowManager manager;
    private final WindowManager.LayoutParams params;
    private final EdgeHandleView view;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density;
    private final int width, height, slop, swipe;
    private final Runnable longPress = this::longPress, closeMenu = this::hideMenu;
    private LinearLayout menu;
    private boolean attached, moved, pressed, hidden, swiped;
    /** Samsung's Edge handle as last seen (null: none), and when the windows were last looked at. */
    private EdgeSpot.Handle samsung;
    private long lookedAt;
    private final Runnable lookForSamsung = this::lookForSamsung;
    /** See-through while a screen recording writes frames (still touchable: a touch pauses it first). */
    private final Runnable veil = this::veil;
    private void veil() { boolean on = RecordControl.veiled(); view.setAlpha(on ? 0f : 1f); if (on) { hideMenu(); main.removeCallbacks(longPress); } }
    private float startX, startY;
    private int originalY;

    EdgeButton(Context context, AppStore store) {
        this.context = context; this.store = store;
        manager = context.getSystemService(WindowManager.class);
        density = context.getResources().getDisplayMetrics().density;
        // A generous touch strip around a slim pill; a short swipe inward is enough to call.
        // A short handle (60dp drawn in a 76dp strip); a pull of 36dp calls, and the strip widens while it is pulled.
        width = dp(28); height = dp(76); slop = ViewConfiguration.get(context).getScaledTouchSlop(); swipe = dp(36);
        params = new WindowManager.LayoutParams(width, height, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        view = new EdgeHandleView(context, HANDLE);
        view.setContentDescription(Brand.NAME + " 부르기. 화면 쪽으로 밀거나 누르면 음성 대화, 길게 누르면 메뉴");
        view.setOnTouchListener((v, event) -> touch(event));
        // Touches are handled above; these serve TalkBack's double tap and double-tap-and-hold.
        view.setOnClickListener(v -> talk());
        view.setOnLongClickListener(v -> { if (menu == null) showMenu(); else hideMenu(); return true; });
        // Gesture navigation would otherwise turn a drag on the handle into "back".
        view.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.setSystemGestureExclusionRects(List.of(new Rect(0, 0, r - l, b - t))));
        // Once (준 asked for it): below Samsung's handle, even if this one was dragged before; a drag after this is kept.
        if (!store.settings.getBoolean("edgeBelowSamsung", false))
            store.settings.edit().putBoolean("edgeBelowSamsung", true).putBoolean(BY_USER, false).apply();
        placeNow();
        RecordControl.onVeil(veil); veil.run();
        main.post(lookForSamsung);
    }
    /** Set once the user dragged the handle or chose its side: from then on it stays where they put it. */
    static final String BY_USER = "edgeYByUser";
    private boolean byUser() { return store.settings.getBoolean(BY_USER, false); }
    /** Where it goes now: where the user put it, else below Samsung's Edge handle (or its default place). */
    private void placeNow() {
        if (byUser()) { place(right(), store.settings.getInt("edgeY", -1)); return; }
        Rect screen = screen(); android.graphics.Insets bars = bars();
        place(samsung != null ? samsung.right() : right(), EdgeSpot.top(samsung, screen.height(), bars.top, bars.bottom, height, dp(12)));
    }
    private android.graphics.Insets bars() {
        try { return manager.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(android.view.WindowInsets.Type.systemBars()); }
        catch (RuntimeException unknown) { return android.graphics.Insets.NONE; }
    }
    /** Windows came or went: Samsung's handle is looked for again, at most every 3 seconds. */
    void windowsChanged() {
        if (byUser() || main.hasCallbacks(lookForSamsung)) return;
        main.postDelayed(lookForSamsung, Math.max(300, 3000 - (android.os.SystemClock.uptimeMillis() - lookedAt)));
    }
    /** Samsung's Edge handle among the service's windows (only narrow ones against a side edge are opened to read their app). */
    private void lookForSamsung() {
        lookedAt = android.os.SystemClock.uptimeMillis();
        if (!(context instanceof android.accessibilityservice.AccessibilityService service)) return;
        Rect screen = screen(); java.util.List<EdgeSpot.Win> wins = new java.util.ArrayList<>();
        try {
            for (android.view.accessibility.AccessibilityWindowInfo w : service.getWindows()) {
                Rect b = new Rect(); w.getBoundsInScreen(b);
                if (b.width() <= 0 || b.width() > screen.width() / 5 || (b.left > 2 && b.right < screen.width() - 2)) continue;
                CharSequence title = w.getTitle(); String pkg = null;
                android.view.accessibility.AccessibilityNodeInfo root = w.getRoot();
                if (root != null && root.getPackageName() != null) pkg = root.getPackageName().toString();
                wins.add(new EdgeSpot.Win(pkg, title == null ? null : title.toString(), b.left, b.top, b.right, b.bottom));
            }
        } catch (RuntimeException unreadable) { return; }
        EdgeSpot.Handle found = EdgeSpot.find(wins, screen.width());
        if (java.util.Objects.equals(found, samsung)) return;
        samsung = found;
        if (byUser()) return;
        placeNow();
        if (attached) try { manager.updateViewLayout(view, params); } catch (RuntimeException ignored) {}
    }
    /** Samsung's handle as last seen, for the tests. */
    void samsung(EdgeSpot.Handle handle) { samsung = handle; placeNow(); }
    WindowManager.LayoutParams params() { return params; }
    View handle() { return view; }
    private int dp(int value) { return Math.round(value * density); }
    private boolean right() { return store.settings.getBoolean("edgeRight", true); }

    /** Shows or hides the handle for the current state; called on every app state change and screen on/off. */
    void refresh() {
        // Hidden while 퍼레 talks on the home screen too (준: "퍼레랑 음성대화에서는 우측바 버블 띄우지 말기").
        boolean show = store.edgeButton() && !hidden && !store.voiceActive && !store.buddyTalking && !PhoneService.gate.armed() && unlocked();
        if (show && !attached) {
            if (byUser()) place(right(), params.y); else placeNow();
            try { manager.addView(view, params); attached = true; } catch (RuntimeException ignored) {}
        } else if (!show && attached) detach();
    }
    /** The screen went off: forget "잠깐 숨기기" and hide until the user is back. */
    void screenOff() { hidden = false; detach(); }
    private boolean unlocked() {
        KeyguardManager lock = context.getSystemService(KeyguardManager.class); PowerManager power = context.getSystemService(PowerManager.class);
        return (lock == null || !lock.isKeyguardLocked()) && (power == null || power.isInteractive());
    }
    private void place(boolean right, int y) {
        Rect screen = screen();
        params.gravity = Gravity.TOP | (right ? Gravity.END : Gravity.START); params.x = 0;
        params.y = Math.max(0, Math.min(y < 0 ? screen.height() / 3 : y, screen.height() - height));
        // A flat rounded trapezoid against the edge (EdgeHandleView); the rest of the window is a transparent touch margin.
        view.side(right);
    }
    /** While pulled the strip widens inward so the stretched handle has room; at rest it is the slim touch strip again. */
    private void widen(boolean wide) {
        int want = wide ? width + dp(96) : width;
        if (params.width == want || !attached) return;
        params.width = want;
        try { manager.updateViewLayout(view, params); } catch (RuntimeException ignored) {}
    }
    /** The window's top on the screen, so a raw touch height becomes one inside the handle. */
    private float screenY() { int[] at = new int[2]; view.getLocationOnScreen(at); return at[1]; }
    private Rect screen() {
        try { return manager.getCurrentWindowMetrics().getBounds(); }
        catch (RuntimeException e) { android.util.DisplayMetrics m = context.getResources().getDisplayMetrics(); return new Rect(0, 0, m.widthPixels, m.heightPixels); }
    }
    private boolean touch(MotionEvent event) {
        // See-through while a screen recording writes frames: a touch pauses it first (then it shows again).
        if (RecordControl.veiledTouch(view, event)) return true;
        switch (event.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> {
                startX = event.getRawX(); startY = event.getRawY(); originalY = params.y; moved = false; pressed = false; swiped = false;
                main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout());
            }
            case MotionEvent.ACTION_MOVE -> {
                if (swiped) return true;
                float dx = event.getRawX() - startX, dy = event.getRawY() - startY, inward = right() ? -dx : dx;
                if (!moved && !pressed) {
                    // Pulled inward, it stretches after the finger (a little resistance); far enough, it calls at once and snaps back.
                    if (inward > slop && inward > Math.abs(dy) * 0.8f) {
                        main.removeCallbacks(longPress); widen(true);
                        view.pull(Math.min(dp(84), inward * 0.85f), event.getRawY() - screenY());
                        if (inward > swipe) {
                            swiped = true; view.performHapticFeedback(HapticFeedbackConstants.CONFIRM);
                            view.release(() -> widen(false)); talk(); return true;
                        }
                        return true;
                    }
                    if (Math.abs(dy) > slop && Math.abs(dy) > Math.abs(dx)) { moved = true; main.removeCallbacks(longPress); hideMenu(); view.release(() -> widen(false)); }
                }
                if (moved && attached) {
                    Rect screen = screen();
                    params.y = Math.max(0, Math.min(originalY + Math.round(dy), screen.height() - height));
                    try { manager.updateViewLayout(view, params); } catch (RuntimeException ignored) {}
                }
            }
            case MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress);
                // Let go before it called: the strand springs back.
                if (!swiped && view.pull() > 0) { view.release(() -> widen(false)); return true; }
                if (moved) store.settings.edit().putInt("edgeY", params.y).putBoolean(BY_USER, true).apply();
                else if (!pressed && !swiped) talk();
            }
            case MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); view.release(() -> widen(false)); }
            default -> {}
        }
        return true;
    }
    private void talk() {
        hideMenu();
        try { context.startActivity(new Intent(context, QuickVoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
        catch (RuntimeException e) { store.error(Brand.EUL + " 부르지 못했어요: " + e.getClass().getSimpleName()); }
    }
    private void longPress() {
        pressed = true; view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        if (menu == null) showMenu(); else hideMenu();
    }

    // ── Menu ──────────────────────────────────────────────────────────────

    private void showMenu() {
        if (!attached) return;
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL);
        int pad = dp(6); row.setPadding(pad, pad, pad, pad);
        GradientDrawable panel = new GradientDrawable(); panel.setColor(MENU); panel.setCornerRadius(24 * density); row.setBackground(panel); row.setElevation(12 * density);
        row.addView(pill(Brand.NAME + " 열기", ACCENT, () -> {
            hideMenu();
            try { context.startActivity(new Intent(context, MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP)); } catch (RuntimeException ignored) {}
        }));
        row.addView(pill(right() ? "왼쪽으로" : "오른쪽으로", MENU_TEXT, () -> {
            hideMenu(); boolean side = !right();
            store.settings.edit().putBoolean("edgeRight", side).putInt("edgeY", params.y).putBoolean(BY_USER, true).apply(); place(side, params.y);
            try { manager.updateViewLayout(view, params); } catch (RuntimeException ignored) {}
        }));
        row.addView(pill("잠깐 숨기기", MENU_TEXT, () -> {
            hideMenu(); hidden = true; refresh();
            Toast.makeText(context, Brand.NAME + " 버튼을 숨겼어요. 화면을 껐다 켜면 다시 나타나요.", Toast.LENGTH_SHORT).show();
        }));
        row.addView(pill("버튼 끄기", OFF, () -> {
            hideMenu(); store.settings.edit().putBoolean("edgeButton", false).apply(); store.changed(); refresh();
            Toast.makeText(context, "가장자리 버튼을 껐어요. " + Brand.NAME + " 설정 → 바로 부르기에서 다시 켤 수 있어요.", Toast.LENGTH_LONG).show();
        }));
        WindowManager.LayoutParams menuParams = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        menuParams.gravity = params.gravity; menuParams.x = width + dp(4); menuParams.y = Math.max(0, Math.min(params.y, screen().height() - dp(64)));
        row.setOnTouchListener((v, event) -> { if (event.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideMenu(); return true; } return false; });
        try { manager.addView(row, menuParams); menu = row; main.postDelayed(closeMenu, 6000); } catch (RuntimeException ignored) {}
    }
    private TextView pill(String text, int color, Runnable action) {
        TextView pill = new TextView(context); pill.setText(text); pill.setTextSize(14); pill.setTextColor(color); pill.setTypeface(Ui.weight(650));
        pill.setPadding(dp(14), dp(10), dp(14), dp(10));
        pill.setOnClickListener(v -> action.run());
        return pill;
    }
    private void hideMenu() {
        main.removeCallbacks(closeMenu);
        View current = menu; menu = null;
        if (current != null) try { manager.removeView(current); } catch (RuntimeException ignored) {}
    }
    private void detach() {
        hideMenu(); main.removeCallbacks(longPress); params.width = width;
        if (attached) try { manager.removeView(view); } catch (RuntimeException ignored) {}
        attached = false;
    }
    @Override public void close() { RecordControl.offVeil(veil); detach(); main.removeCallbacksAndMessages(null); }
}
