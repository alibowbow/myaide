package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.SystemClock;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.inputmethod.InputMethodInfo;
import android.view.inputmethod.InputMethodManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.Macros;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 매크로 녹화: while the user does a thing by hand once, their own presses, typing and scrolls in other apps become a macro's
 * steps (core/Macros.Recording). Started only by the user (퍼레 "매크로 녹화 시작", or the row in 한마디 루틴), with a small
 * bar over every app the whole time ("● 녹화 · 3단계 · 뒤로 · 끝") and an ongoing notification; nothing is recorded in this
 * app's own windows, the system UI or the keyboard, and a password field's text never is. 뒤로 on the bar is the back
 * button as a step (accessibility does not see the back gesture). 끝 saves it and opens its sheet to name, run or delete it.
 * Main thread (accessibility events come there).
 */
final class MacroRecorder {
    private MacroRecorder() {}
    static final int NOTIFICATION = 4310;
    private static Macros.Recording current;
    private static String name = "";
    private static Bar bar;
    private static final Map<String, String> labels = new java.util.concurrent.ConcurrentHashMap<>();

    static boolean recording() { return current != null; }
    static int count() { Macros.Recording r = current; return r == null ? 0 : r.count(); }

    /**
     * Starts recording (main thread): needs 폰 조작's accessibility service. name: what to call it ("" asks at the end).
     * goHome: the home screen comes up, so the user starts from there. Throws with words when it cannot.
     */
    static void start(Context c, String named, boolean goHome) {
        if (!PhoneService.connected()) throw new IllegalStateException("매크로를 녹화하려면 접근성에서 ‘" + dev.jun.gyeot.core.Brand.NAME + " 폰 조작’을 켜 줘.");
        if (current != null) throw new IllegalStateException("이미 녹화 중이야. 다 하면 위의 ‘끝’을 눌러 줘.");
        Context app = c.getApplicationContext();
        String front = goHome ? "" : PhoneService.frontApp();
        current = new Macros.Recording(ignored(app), launchers(app)::contains, front, label(app, front), SystemClock.uptimeMillis());
        name = named == null ? "" : named.strip();
        PhoneService.watchMacro(true);
        Context host = PhoneService.overlayContext();
        if (host != null) { bar = new Bar(host); bar.show(); }
        notifyRecording(app);
        if (goHome) PhoneService.home();
    }
    /** The bar's 뒤로: the back button, kept as a step. */
    static void back() {
        Macros.Recording r = current;
        if (r == null) return;
        r.back(SystemClock.uptimeMillis());
        PhoneService.back();
        if (bar != null) bar.count(r.count());
    }
    /** What ending a recording made: the macro saved, and what the save says it left out or stops before ("" nothing). */
    record Saved(Macros.Macro macro, String note) {}
    /** Ends and saves it (main thread). open: its sheet comes up (the bar's 끝). Throws with words when nothing was recorded. */
    static Saved stop(Context c, boolean open) {
        Macros.Recording r = current;
        if (r == null) throw new IllegalStateException("녹화 중인 매크로가 없어.");
        Context app = c.getApplicationContext();
        end(app);
        Macros.Result done = r.finish();
        List<Macros.Step> steps = new java.util.ArrayList<>();
        // The apps it opens by the names the user knows them by.
        for (Macros.Step s : done.steps()) steps.add(Macros.OPEN.equals(s.kind()) && s.text().isEmpty() ? Macros.Step.open(s.app(), label(app, s.app()), s.gap()) : s);
        String first = "", asked = name;
        name = "";
        for (Macros.Step s : steps) if (Macros.OPEN.equals(s.kind())) { first = s.text(); break; }
        List<Macros.Macro> all = MacroBook.list(app);
        String chosen = !asked.isEmpty() && Macros.find(all, asked) == null ? asked : Macros.freeName(all, asked.isEmpty() ? (first.isEmpty() ? "매크로" : first + " 매크로") : asked);
        Macros.Macro made = new Macros.Macro("", chosen, steps, System.currentTimeMillis(), 0, 0, "");
        MacroBook.change(app, list -> Macros.save(list, made));
        ((GyeotApp) app).store().phoneLog("● 매크로 저장: ‘" + made.name() + "’ · " + steps.size() + "단계");
        if (open) MacrosUi.open(app, made.id());
        return new Saved(made, done.note());
    }
    /** Ends it without keeping anything. */
    static void cancel(Context c) { if (current != null) end(c.getApplicationContext()); }
    /** 폰 조작 went away mid-recording: what was done is kept when there is anything. */
    static void serviceGone(Context c) {
        if (current == null) return;
        try { stop(c, false); } catch (RuntimeException nothing) { /* nothing to keep */ }
    }
    private static void end(Context app) {
        current = null;
        PhoneService.watchMacro(false);
        if (bar != null) { bar.remove(); bar = null; }
        NotificationManager nm = app.getSystemService(NotificationManager.class);
        if (nm != null) nm.cancel(NOTIFICATION);
    }

    // ── what the events say ──
    /** One accessibility event while recording (main thread): a window, a press, typing or a scroll. */
    static void event(Context c, AccessibilityEvent e) {
        Macros.Recording r = current;
        if (r == null || e == null) return;
        String pkg = e.getPackageName() == null ? "" : e.getPackageName().toString();
        long now = SystemClock.uptimeMillis();
        int[] size = PhoneService.screenSize();
        switch (e.getEventType()) {
            case AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED -> r.window(pkg, e.getClassName() == null ? "" : e.getClassName().toString(), label(c, pkg), now);
            case AccessibilityEvent.TYPE_VIEW_CLICKED, AccessibilityEvent.TYPE_VIEW_LONG_CLICKED ->
                r.click(pkg, target(e.getSource(), size), e.getEventType() == AccessibilityEvent.TYPE_VIEW_LONG_CLICKED, now);
            case AccessibilityEvent.TYPE_VIEW_TEXT_CHANGED -> {
                AccessibilityNodeInfo node = e.getSource();
                if (node == null || !node.isEditable()) return;
                boolean password = e.isPassword() || node.isPassword();
                r.typed(pkg, target(node, size), password || node.getText() == null ? "" : node.getText().toString(), password, now);
            }
            case AccessibilityEvent.TYPE_VIEW_SCROLLED -> r.scrolled(pkg, target(e.getSource(), size), e.getScrollDeltaX(), e.getScrollDeltaY(), now);
            default -> { return; }
        }
        if (bar != null) bar.count(r.count());
    }
    /**
     * A control as a macro finds it again: its words, view id, class, role, place among its siblings and bounds. A row with no
     * words of its own is kept as the first label inside it (not inside another control), with the row as its host.
     */
    static Macros.Target target(AccessibilityNodeInfo node, int[] screen) {
        if (node == null) return null;
        Macros.Target self = self(node, screen, null);
        if (node.isEditable() || !self.text().isEmpty() || !self.desc().isEmpty()) return self;
        AccessibilityNodeInfo label = labelInside(node);
        return label == null ? self : self(label, screen, self);
    }
    private static Macros.Target self(AccessibilityNodeInfo node, int[] screen, Macros.Target host) {
        Rect box = new Rect(); node.getBoundsInScreen(box);
        boolean edit = node.isEditable();
        return new Macros.Target(text(node.getViewIdResourceName()), edit || node.isPassword() ? "" : text(node.getText()), text(node.getContentDescription()),
            edit ? text(node.getHintText()) : "", text(node.getClassName()), PhoneService.roleOf(node), place(node),
            new int[]{box.left, box.top, box.right, box.bottom}, screen, edit, host);
    }
    private static AccessibilityNodeInfo labelInside(AccessibilityNodeInfo row) {
        java.util.ArrayDeque<AccessibilityNodeInfo> queue = new java.util.ArrayDeque<>();
        for (int i = 0; i < row.getChildCount(); i++) { AccessibilityNodeInfo child = row.getChild(i); if (child != null) queue.add(child); }
        for (int seen = 0; !queue.isEmpty() && seen < 60; seen++) {
            AccessibilityNodeInfo node = queue.removeFirst();
            if (node.isClickable() || node.isPassword() || node.isEditable()) continue;
            if (node.isVisibleToUser() && (!text(node.getText()).isEmpty() || !text(node.getContentDescription()).isEmpty())) return node;
            for (int i = 0; i < node.getChildCount(); i++) { AccessibilityNodeInfo child = node.getChild(i); if (child != null) queue.add(child); }
        }
        return null;
    }
    private static int place(AccessibilityNodeInfo node) {
        try {
            AccessibilityNodeInfo parent = node.getParent();
            for (int i = 0; parent != null && i < parent.getChildCount(); i++) if (node.equals(parent.getChild(i))) return i;
        } catch (RuntimeException gone) { /* unknown */ }
        return -1;
    }
    private static String text(CharSequence v) { if (v == null) return ""; String s = v.toString().strip(); return s.length() > 120 ? s.substring(0, 120) : s; }

    // ── the phone's own windows ──
    /** This app, the system UI and the keyboards: never steps. */
    static Set<String> ignored(Context c) {
        Set<String> out = new HashSet<>(Set.of(c.getPackageName(), "com.android.systemui"));
        try {
            InputMethodManager ime = c.getSystemService(InputMethodManager.class);
            if (ime != null) for (InputMethodInfo m : ime.getEnabledInputMethodList()) out.add(m.getPackageName());
        } catch (RuntimeException none) { /* no keyboards known */ }
        return out;
    }
    /** The home screen apps (their recents too): leaving to them and coming back from them are no taps. */
    static Set<String> launchers(Context c) {
        Set<String> out = new HashSet<>();
        try {
            for (ResolveInfo info : c.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), PackageManager.MATCH_ALL))
                if (info.activityInfo != null) out.add(info.activityInfo.packageName);
        } catch (RuntimeException none) { /* none known */ }
        return out;
    }
    /** An app's name as the user knows it ("카카오T"), "" when unknown; remembered. */
    static String label(Context c, String pkg) {
        if (pkg == null || pkg.isEmpty()) return "";
        String known = labels.get(pkg);
        if (known != null) return known;
        String found = "";
        try { ApplicationInfo info = c.getPackageManager().getApplicationInfo(pkg, 0); found = c.getPackageManager().getApplicationLabel(info).toString(); }
        catch (Exception unknown) { /* none */ }
        labels.put(pkg, found);
        return found;
    }

    // ── while it records ──
    private static void notifyRecording(Context c) {
        NotificationManager nm = MacroRunner.channel(c, false);
        if (nm == null) return;
        PendingIntent end = PendingIntent.getBroadcast(c, NOTIFICATION, new Intent(c, MacroReceiver.class).setAction(MacroReceiver.RECORD_STOP), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        PendingIntent cancel = PendingIntent.getBroadcast(c, NOTIFICATION + 1, new Intent(c, MacroReceiver.class).setAction(MacroReceiver.RECORD_CANCEL), PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        Notification n = new Notification.Builder(c, MacroRunner.CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("매크로 녹화 중").setContentText("할 일을 직접 한 뒤 ‘끝’을 눌러 주세요")
            .setOngoing(true).setOnlyAlertOnce(true).setCategory(Notification.CATEGORY_PROGRESS)
            .addAction(new Notification.Action.Builder(null, "끝", end).build()).addAction(new Notification.Action.Builder(null, "취소", cancel).build()).build();
        try { nm.notify(NOTIFICATION, n); } catch (SecurityException off) { /* notifications are off: the bar still shows */ }
    }

    /**
     * The bar over every app while it records: "● 3단계", 뒤로, 끝 and ✕, one small pill (34dp) at the top that can be dragged
     * up and down; it takes only the touches on itself (폰 조작's own window, so the app below gets every other touch).
     */
    static final class Bar {
        private final WindowManager manager; final LinearLayout view; final TextView steps; final WindowManager.LayoutParams params;
        private boolean added;
        Bar(Context host) {
            manager = host.getSystemService(WindowManager.class);
            float d = host.getResources().getDisplayMetrics().density;
            view = new LinearLayout(host); view.setOrientation(LinearLayout.HORIZONTAL); view.setGravity(Gravity.CENTER_VERTICAL);
            GradientDrawable pill = new GradientDrawable(); pill.setColor(0xF0222228); pill.setCornerRadius(17 * d); view.setBackground(pill);
            view.setPadding(Math.round(12 * d), 0, Math.round(4 * d), 0);
            view.setContentDescription("매크로 녹화 중");
            steps = text(host, "● 0단계", 0xFFFF6B6B, d); steps.setContentDescription("녹화한 단계");
            view.addView(steps);
            view.addView(button(host, "뒤로", d, MacroRecorder::back));
            view.addView(button(host, "끝", d, () -> { try { stop(host, true); } catch (RuntimeException nothing) { android.widget.Toast.makeText(host, nothing.getMessage(), android.widget.Toast.LENGTH_LONG).show(); cancel(host); } }));
            view.addView(button(host, "✕", d, () -> cancel(host)));
            params = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, Math.round(34 * d), WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN, PixelFormat.TRANSLUCENT);
            params.gravity = Gravity.TOP | Gravity.CENTER_HORIZONTAL; params.y = Math.round(44 * d);
            params.setTitle("들퍼레 매크로 녹화");
            float[] from = new float[2]; int[] at = new int[1];
            steps.setOnTouchListener((v, ev) -> {
                switch (ev.getActionMasked()) {
                    case MotionEvent.ACTION_DOWN -> { from[1] = ev.getRawY(); at[0] = params.y; return true; }
                    case MotionEvent.ACTION_MOVE -> { params.y = Math.max(0, at[0] + Math.round(ev.getRawY() - from[1])); if (added) try { manager.updateViewLayout(view, params); } catch (RuntimeException gone) {} return true; }
                    default -> { return true; }
                }
            });
        }
        private static TextView text(Context c, String value, int color, float d) {
            TextView t = new TextView(c); t.setText(value); t.setTextColor(color); t.setTextSize(13); t.setGravity(Gravity.CENTER_VERTICAL);
            t.setPadding(0, 0, Math.round(6 * d), 0); t.setMinHeight(Math.round(34 * d));
            return t;
        }
        private static TextView button(Context c, String value, float d, Runnable action) {
            TextView t = text(c, value, 0xFFFFFFFF, d);
            t.setPadding(Math.round(10 * d), 0, Math.round(10 * d), 0);
            t.setOnClickListener(v -> action.run());
            t.setContentDescription(value.equals("✕") ? "녹화 취소" : "녹화 " + value);
            return t;
        }
        void count(int n) { steps.setText("● " + n + "단계"); }
        void show() { if (manager == null) return; try { manager.addView(view, params); added = true; } catch (RuntimeException refused) { added = false; } }
        void remove() { if (!added) return; try { manager.removeView(view); } catch (RuntimeException gone) { /* off */ } added = false; }
    }
}
