package dev.jun.gyeot;

import android.animation.ValueAnimator;
import android.app.KeyguardManager;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.Configuration;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.view.WindowManager;
import android.view.animation.OvershootInterpolator;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.AppStacks;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;

/**
 * 사이드 독 (준: "사이드 슬라이드 아이콘 ui참고", a MacBook Pro's dock shown): a slim glass pill on the screen edge opposite 퍼레's
 * handle; pulled inward, a glass dock slides out from that edge, upright, with the same apps as 들퍼레 홈 화면's dock (then a
 * line and the recent ones, as a Mac's). In one stroke: pull it in, slide along, and the icons swell under the finger like a
 * Mac's dock; let go on one and it opens. Let go elsewhere and it stays for a tap, then slides away. Drawn by the
 * accessibility service like 퍼레's handle (so over every app, the phone's own home screen too); hidden while locked.
 * Sliding only looks: an app opens on a tap, never when a slide ends (준: "드래그하다가 손을 놓으면 실행이 돼버려").
 */
final class SideDock implements AutoCloseable {
    private final Context context; private final AppStore store; private final WindowManager manager;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final float density; private final int slop, strip, tall;
    private final WindowManager.LayoutParams handleParams;
    private final Pill handle;
    private FrameLayout stage; private LinearLayout shelf; private TextView tip;
    private final List<ImageView> icons = new ArrayList<>(); private final List<LauncherActivityInfo> shown = new ArrayList<>();
    private final Map<String, Drawable> drawn = new HashMap<>(); private long drawnAt;
    private boolean attached, open, pulling, moving, hidden; private float startX, startY; private int startHandleY; private ImageView hot;
    private final Runnable autoClose = () -> close(true), longPress = this::menu;

    SideDock(Context context, AppStore store) {
        this.context = context; this.store = store;
        manager = context.getSystemService(WindowManager.class);
        density = context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop(); strip = dp(22); tall = dp(72);
        handleParams = new WindowManager.LayoutParams(strip, tall, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
        handle = new Pill(context);
        handle.setContentDescription("사이드 독. 화면 쪽으로 밀면 앱 아이콘이 나와요");
        handle.setOnTouchListener((v, e) -> touch(e));
        handle.setOnClickListener(v -> openDock());
        handle.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> v.setSystemGestureExclusionRects(List.of(new Rect(0, 0, r - l, b - t))));
    }
    private int dp(float v) { return Math.round(v * density); }
    private SharedPreferences settings() { return store.settings; }
    /** On the edge opposite 퍼레's handle unless the user moved it. */
    private boolean right() { return settings().getBoolean("sideDockRight", !settings().getBoolean("edgeRight", true)); }
    static boolean on(AppStore store) { return store.settings.getBoolean("sideDock", true); }

    /** Shown or hidden for the state now (every app state change, screen on and off). */
    void refresh() {
        boolean show = on(store) && !hidden && !store.voiceActive && !PhoneService.gate.armed() && unlocked();
        if (show && !attached) {
            place();
            try { manager.addView(handle, handleParams); attached = true; } catch (RuntimeException ignored) {}
        } else if (!show && attached) detach();
        else if (show) { place(); try { manager.updateViewLayout(handle, handleParams); } catch (RuntimeException ignored) {} }
    }
    void screenOff() { hidden = false; detach(); }
    private boolean unlocked() {
        KeyguardManager lock = context.getSystemService(KeyguardManager.class); PowerManager power = context.getSystemService(PowerManager.class);
        return (lock == null || !lock.isKeyguardLocked()) && (power == null || power.isInteractive());
    }
    private Rect screen() {
        try { return manager.getCurrentWindowMetrics().getBounds(); }
        catch (RuntimeException e) { var m = context.getResources().getDisplayMetrics(); return new Rect(0, 0, m.widthPixels, m.heightPixels); }
    }
    private void place() {
        Rect s = screen(); int y = settings().getInt("sideDockY", -1);
        handleParams.gravity = Gravity.TOP | (right() ? Gravity.END : Gravity.START); handleParams.x = 0;
        handleParams.y = Math.max(dp(40), Math.min(y < 0 ? s.height() / 2 - tall / 2 : y, s.height() - tall - dp(40)));
        handle.side(right());
    }

    // ── the handle's stroke: pull in (and pick), slide along the edge to move it, hold for the menu ──
    private boolean touch(MotionEvent e) {
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN -> { startX = e.getRawX(); startY = e.getRawY(); startHandleY = handleParams.y; pulling = false; moving = false; main.postDelayed(longPress, ViewConfiguration.getLongPressTimeout()); }
            case MotionEvent.ACTION_MOVE -> {
                float dx = e.getRawX() - startX, dy = e.getRawY() - startY, inward = right() ? -dx : dx;
                if (!pulling && !moving) {
                    if (inward > slop && inward > Math.abs(dy) * 0.7f) { pulling = true; main.removeCallbacks(longPress); handle.performHapticFeedback(HapticFeedbackConstants.CONFIRM); openDock(); }
                    else if (Math.abs(dy) > slop * 2 && Math.abs(dy) > Math.abs(dx)) { moving = true; main.removeCallbacks(longPress); }
                }
                if (pulling) swell(e.getRawY(), e.getRawX());
                if (moving && attached) {
                    Rect s = screen(); handleParams.y = Math.max(dp(40), Math.min(startHandleY + Math.round(dy), s.height() - tall - dp(40)));
                    try { manager.updateViewLayout(handle, handleParams); } catch (RuntimeException ignored) {}
                }
            }
            case MotionEvent.ACTION_UP -> {
                main.removeCallbacks(longPress);
                if (moving) { settings().edit().putInt("sideDockY", handleParams.y).apply(); return true; }
                if (pulling) { rest(); armClose(); return true; }
                if (Math.abs(e.getRawX() - startX) < slop && Math.abs(e.getRawY() - startY) < slop) openDock();
            }
            case MotionEvent.ACTION_CANCEL -> { main.removeCallbacks(longPress); if (pulling) { rest(); armClose(); } }
            default -> {}
        }
        return true;
    }

    // ── the dock ────────────────────────────────────────────────────
    private boolean dark() { return (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }
    private void openDock() {
        if (open) { armClose(); return; }
        List<LauncherActivityInfo> apps = apps();
        if (apps.isEmpty()) { Toast.makeText(context, "독에 넣을 앱이 아직 없어요. 들퍼레 홈 화면에서 앱을 길게 눌러 독에 넣어 주세요.", Toast.LENGTH_LONG).show(); return; }
        boolean right = right(), dark = dark();
        stage = new FrameLayout(context);
        stage.setOnTouchListener((v, ev) -> { if (ev.getActionMasked() == MotionEvent.ACTION_DOWN) { close(true); return true; } return false; });
        shelf = new LinearLayout(context); shelf.setOrientation(LinearLayout.VERTICAL); shelf.setGravity(Gravity.CENTER_HORIZONTAL);
        int pad = dp(9); shelf.setPadding(pad, dp(11), pad, dp(11));
        GradientDrawable glass = new GradientDrawable(); glass.setCornerRadius(dp(24));
        glass.setColor(dark ? 0xc8262630 : 0xd9f4f4f7); glass.setStroke(Math.max(1, dp(0.8f)), dark ? 0x33ffffff : 0x99ffffff);
        shelf.setBackground(glass); shelf.setElevation(dp(14));
        icons.clear(); shown.clear();
        int size = dp(46), recentFrom = recentStart;
        for (int i = 0; i < apps.size(); i++) {
            if (i == recentFrom && i > 0) {
                View line = new View(context); line.setBackgroundColor(dark ? 0x40ffffff : 0x33000000);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(dp(30), Math.max(1, dp(0.8f))); lp.setMargins(0, dp(5), 0, dp(5)); shelf.addView(line, lp);
            }
            LauncherActivityInfo info = apps.get(i);
            ImageView icon = new ImageView(context); icon.setImageDrawable(iconOf(info)); icon.setScaleType(ImageView.ScaleType.FIT_CENTER);
            icon.setContentDescription(String.valueOf(info.getLabel())); icon.setPivotX(right ? size : 0); icon.setPivotY(size / 2f);
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(size, size); lp.setMargins(0, dp(4), 0, dp(4));
            shelf.addView(icon, lp); icons.add(icon); shown.add(info);
        }
        // Taps and strokes on the dock itself: the same swelling, and the app under the finger when it lifts.
        float[] down = new float[2]; boolean[] slid = new boolean[1];
        shelf.setOnTouchListener((v, ev) -> {
            switch (ev.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> { down[0] = ev.getRawX(); down[1] = ev.getRawY(); slid[0] = false; main.removeCallbacks(autoClose); swell(ev.getRawY(), ev.getRawX()); }
                case MotionEvent.ACTION_MOVE -> {
                    if (Math.hypot(ev.getRawX() - down[0], ev.getRawY() - down[1]) > slop) slid[0] = true;
                    main.removeCallbacks(autoClose); swell(ev.getRawY(), ev.getRawX());
                }
                // Only a tap opens: a slide that ends on an icon does not.
                case MotionEvent.ACTION_UP -> { ImageView pick = slid[0] ? null : hot; rest(); if (pick != null) launch(pick); else armClose(); }
                case MotionEvent.ACTION_CANCEL -> { rest(); armClose(); }
                default -> {}
            }
            return true;
        });
        Rect s = screen(); int tallest = s.height() - dp(120);
        FrameLayout.LayoutParams at = new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | (right ? Gravity.END : Gravity.START));
        shelf.measure(View.MeasureSpec.makeMeasureSpec(s.width(), View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(tallest, View.MeasureSpec.AT_MOST));
        int h = shelf.getMeasuredHeight(), mid = handleParams.y + tall / 2;
        at.topMargin = Math.max(dp(48), Math.min(mid - h / 2, s.height() - h - dp(60)));
        if (right) at.rightMargin = dp(10); else at.leftMargin = dp(10);
        stage.addView(shelf, at);
        tip = new TextView(context); tip.setTextSize(13); tip.setTextColor(0xffffffff); tip.setTypeface(Ui.weight(650)); tip.setPadding(dp(10), dp(5), dp(10), dp(5));
        GradientDrawable tipBack = new GradientDrawable(); tipBack.setColor(0xe0222228); tipBack.setCornerRadius(dp(9)); tip.setBackground(tipBack); tip.setVisibility(View.INVISIBLE);
        stage.addView(tip, new FrameLayout.LayoutParams(-2, -2, Gravity.TOP | Gravity.START));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(-1, -1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_DIM_BEHIND, PixelFormat.TRANSLUCENT);
        p.dimAmount = 0.1f;
        // The screen behind softly blurred, as an opened panel on a Mac or an iPhone (where the phone allows it).
        try { if (manager.isCrossWindowBlurEnabled()) { p.flags |= WindowManager.LayoutParams.FLAG_BLUR_BEHIND; p.setBlurBehindRadius(dp(14)); } } catch (RuntimeException ignored) {}
        try { manager.addView(stage, p); open = true; } catch (RuntimeException failed) { stage = null; return; }
        // In from its edge with a little spring; the icons follow one after another.
        float away = (right ? 1 : -1) * (shelf.getMeasuredWidth() + dp(20));
        shelf.setTranslationX(away); shelf.animate().translationX(0).setDuration(360).setInterpolator(new OvershootInterpolator(1.05f)).start();
        for (int i = 0; i < icons.size(); i++) { ImageView ic = icons.get(i); ic.setScaleX(0.5f); ic.setScaleY(0.5f); ic.setAlpha(0f); ic.animate().scaleX(1).scaleY(1).alpha(1).setStartDelay(60 + i * 24L).setDuration(380).setInterpolator(new OvershootInterpolator(1.6f)).start(); }
        if (!pulling) armClose();
    }
    /** Icons near the finger swell (most under it, a Mac's falloff) and lean out from the edge; the name shows beside it. Looking only. */
    private void swell(float rawY, float rawX) {
        if (!open || shelf == null) return;
        boolean right = right(); float reach = dp(118), lift = dp(26);
        ImageView best = null; float bestD = Float.MAX_VALUE; int[] at = new int[2];
        for (ImageView ic : icons) {
            ic.animate().cancel(); ic.getLocationOnScreen(at);
            float cy = at[1] + ic.getHeight() / 2f, d = Math.abs(cy - rawY), k = Math.max(0, 1 - d / reach), s = 1 + 0.6f * (float) Math.pow(k, 1.6);
            ic.setAlpha(1); ic.setScaleX(s); ic.setScaleY(s); ic.setTranslationX((right ? -1 : 1) * (s - 1) * lift);
            if (d < bestD) { bestD = d; best = ic; }
        }
        // Only a finger over the dock picks (a finger far from it chooses nothing).
        int[] shelfAt = new int[2]; shelf.getLocationOnScreen(shelfAt);
        boolean near = right ? rawX > shelfAt[0] - dp(70) : rawX < shelfAt[0] + shelf.getWidth() + dp(70);
        ImageView pick = near && bestD < dp(36) ? best : null;
        if (pick != hot) { hot = pick; if (pick != null) pick.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); }
        if (hot != null) {
            tip.setText(hot.getContentDescription()); tip.measure(0, 0); hot.getLocationOnScreen(at); int[] stageAt = new int[2]; stage.getLocationOnScreen(stageAt);
            float x = right ? shelfAt[0] - tip.getMeasuredWidth() - dp(26) : shelfAt[0] + shelf.getWidth() + dp(26);
            tip.setX(x - stageAt[0]); tip.setY(at[1] - stageAt[1] + hot.getHeight() / 2f - tip.getMeasuredHeight() / 2f); tip.setVisibility(View.VISIBLE);
        } else tip.setVisibility(View.INVISIBLE);
    }
    private void rest() {
        pulling = false; if (tip != null) tip.setVisibility(View.INVISIBLE);
        for (ImageView ic : icons) ic.animate().scaleX(1).scaleY(1).translationX(0).setStartDelay(0).setDuration(320).setInterpolator(new OvershootInterpolator(1.4f)).start();
    }
    private void armClose() { main.removeCallbacks(autoClose); main.postDelayed(autoClose, 4500); }
    private void close(boolean animate) {
        main.removeCallbacks(autoClose); hot = null;
        FrameLayout gone = stage; LinearLayout s = shelf; stage = null; shelf = null; open = false; icons.clear();
        if (gone == null) return;
        Runnable remove = () -> { try { manager.removeView(gone); } catch (RuntimeException ignored) {} };
        if (animate && s != null) s.animate().translationX((right() ? 1 : -1) * (s.getWidth() + dp(20))).setDuration(200).withEndAction(remove).start();
        else remove.run();
    }
    /** The app opens (a little hop first, a Mac's dock), and becomes the newest of the recent ones (shared with 들퍼레 홈 화면). */
    private void launch(ImageView icon) {
        int i = icons.indexOf(icon); if (i < 0) return;
        LauncherActivityInfo info = shown.get(i);
        icon.animate().translationX((right() ? -1 : 1) * dp(16)).setDuration(130).withEndAction(() -> icon.animate().translationX(0).setDuration(220).setInterpolator(new OvershootInterpolator(2f)).start()).start();
        int[] at = new int[2]; icon.getLocationOnScreen(at); Rect from = new Rect(at[0], at[1], at[0] + icon.getWidth(), at[1] + icon.getHeight());
        try { context.getSystemService(LauncherApps.class).startMainActivity(info.getComponentName(), info.getUser(), from, null); }
        catch (RuntimeException refused) { Toast.makeText(context, "이 앱을 열지 못했어요.", Toast.LENGTH_SHORT).show(); }
        remember(info);
        main.postDelayed(() -> close(false), 260);
    }

    // ── which apps: 들퍼레 홈 화면's dock, then the recent ones ──────
    private int recentStart;
    private List<LauncherActivityInfo> apps() {
        LauncherApps launcher = context.getSystemService(LauncherApps.class); UserManager users = context.getSystemService(UserManager.class);
        Map<String, LauncherActivityInfo> all = new HashMap<>(); List<String> packages = new ArrayList<>(), ids = new ArrayList<>();
        try { for (UserHandle u : launcher.getProfiles()) for (LauncherActivityInfo info : launcher.getActivityList(null, u)) { String id = HomeActivity.id(info, users); all.put(id, info); ids.add(id); packages.add(info.getApplicationInfo().packageName); } }
        catch (RuntimeException unreadable) { return List.of(); }
        SharedPreferences home = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        List<String> dock = list(home.getString(HomeActivity.DOCK, null));
        if (dock == null) { dock = new ArrayList<>(); for (String pkg : AppStacks.dock(packages, 7)) dock.add(ids.get(packages.indexOf(pkg))); }
        List<LauncherActivityInfo> out = new ArrayList<>();
        for (String id : dock) if (all.containsKey(id) && out.size() < 9) out.add(all.get(id));
        recentStart = out.size();
        List<String> recent = list(home.getString(HomeActivity.RECENT, "[]"));
        for (String id : recent == null ? List.<String>of() : recent) if (all.containsKey(id) && !dock.contains(id) && out.size() - recentStart < 3) out.add(all.get(id));
        return out;
    }
    private static List<String> list(String json) {
        if (json == null) return null;
        try { JSONArray a = new JSONArray(json); List<String> out = new ArrayList<>(); for (int i = 0; i < a.length(); i++) out.add(a.optString(i)); return out; }
        catch (Exception e) { return null; }
    }
    private Drawable iconOf(LauncherActivityInfo info) {
        if (System.currentTimeMillis() - drawnAt > 10 * 60_000L) { drawn.clear(); drawnAt = System.currentTimeMillis(); }
        String key = info.getComponentName().flattenToShortString() + info.getUser().hashCode();
        Drawable d = drawn.get(key);
        if (d == null) { try { d = info.getBadgedIcon(context.getResources().getDisplayMetrics().densityDpi); } catch (RuntimeException e) { d = new GradientDrawable(); } drawn.put(key, d); }
        return d;
    }
    private void remember(LauncherActivityInfo info) {
        SharedPreferences home = context.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        String id = HomeActivity.id(info, context.getSystemService(UserManager.class));
        JSONArray recent = new JSONArray(); recent.put(id);
        List<String> old = list(home.getString(HomeActivity.RECENT, "[]"));
        for (String o : old == null ? List.<String>of() : old) if (!o.equals(id) && recent.length() < 6) recent.put(o);
        home.edit().putString(HomeActivity.RECENT, recent.toString()).apply();
    }

    // ── the handle's menu ────────────────────────────────────────────
    private void menu() {
        if (!attached) return;
        handle.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS);
        String[] names = {right() ? "왼쪽으로" : "오른쪽으로", "잠깐 숨기기", "사이드 독 끄기"};
        LinearLayout row = new LinearLayout(context); row.setOrientation(LinearLayout.HORIZONTAL); row.setPadding(dp(6), dp(6), dp(6), dp(6));
        GradientDrawable back = new GradientDrawable(); back.setColor(0xf2262522); back.setCornerRadius(dp(22)); row.setBackground(back); row.setElevation(dp(12));
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(-2, -2, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH, PixelFormat.TRANSLUCENT);
        p.gravity = handleParams.gravity; p.x = strip + dp(4); p.y = handleParams.y;
        Runnable[] gone = new Runnable[1];
        gone[0] = () -> { try { manager.removeView(row); } catch (RuntimeException ignored) {} };
        for (int i = 0; i < names.length; i++) {
            int which = i; TextView t = new TextView(context); t.setText(names[i]); t.setTextSize(14); t.setTypeface(Ui.weight(650));
            t.setTextColor(i == 2 ? 0xffff7062 : 0xfff2f0ea); t.setPadding(dp(14), dp(10), dp(14), dp(10));
            t.setOnClickListener(v -> {
                gone[0].run();
                if (which == 0) { settings().edit().putBoolean("sideDockRight", !right()).apply(); refresh(); }
                else if (which == 1) { hidden = true; refresh(); Toast.makeText(context, "사이드 독을 숨겼어요. 화면을 껐다 켜면 다시 나타나요.", Toast.LENGTH_SHORT).show(); }
                else { settings().edit().putBoolean("sideDock", false).apply(); store.changed(); refresh(); Toast.makeText(context, "사이드 독을 껐어요. 내 휴대폰 → 화면 꾸미기에서 다시 켤 수 있어요.", Toast.LENGTH_LONG).show(); }
            });
            row.addView(t);
        }
        row.setOnTouchListener((v, ev) -> { if (ev.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { gone[0].run(); return true; } return false; });
        try { manager.addView(row, p); main.postDelayed(gone[0], 6000); } catch (RuntimeException ignored) {}
    }
    private void detach() {
        main.removeCallbacks(longPress); close(false);
        if (attached) try { manager.removeView(handle); } catch (RuntimeException ignored) {}
        attached = false;
    }
    @Override public void close() { detach(); main.removeCallbacksAndMessages(null); }

    /** The handle: a slim frosted pill against the edge (a Mac's and an iPhone's quiet grabbers), seen on light and dark screens. */
    private static final class Pill extends View {
        private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG), edge = new Paint(Paint.ANTI_ALIAS_FLAG); private final RectF box = new RectF(); private boolean right = true;
        Pill(Context c) { super(c); fill.setColor(0xd9ffffff); fill.setShadowLayer(6 * c.getResources().getDisplayMetrics().density, 0, 0, 0x55000000); edge.setStyle(Paint.Style.STROKE); edge.setColor(0x33000000); edge.setStrokeWidth(c.getResources().getDisplayMetrics().density); setLayerType(LAYER_TYPE_SOFTWARE, null); }
        void side(boolean right) { this.right = right; invalidate(); }
        @Override protected void onDraw(Canvas c) {
            float d = getResources().getDisplayMetrics().density, w = 5 * d, h = getHeight() - 16 * d, x = right ? getWidth() - w - 4 * d : 4 * d;
            box.set(x, 8 * d, x + w, 8 * d + h); c.drawRoundRect(box, w / 2, w / 2, fill); c.drawRoundRect(box, w / 2, w / 2, edge);
        }
    }
}
