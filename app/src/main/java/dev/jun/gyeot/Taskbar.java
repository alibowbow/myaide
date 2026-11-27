package dev.jun.gyeot;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.KeyguardManager;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.Configuration;
import android.content.res.Resources;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.drawable.BitmapDrawable;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.os.PowerManager;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.util.LruCache;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.TaskbarPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.function.Consumer;
import org.json.JSONArray;

/**
 * 들퍼레 작업 표시줄 (준: "다른 앱 실행했을 때 최하단 바에 아이콘들이 노란흔적만 남고 우측 버튼 세개는 배열이 달라져", "탭 뒤로 홈 버튼도
 * 원래자리로", "홈화면에서는 안 보이고 다른앱켰을때만", "그게 있어야 화면 나누기로 앱 멀티태스크 실행이 가능하거든"). Samsung ties its
 * taskbar to One UI Home and no API lets another home fill it, so while another app is in front this accessibility overlay
 * (above the system bars, like EdgeButton) lies exactly over the plain bar Samsung falls back to: 들퍼레's all-apps button,
 * the dock's apps, a divider and recent apps on the left; recents · home · back at One UI's places on the right, working
 * through performGlobalAction; the far right (the accessibility button) left to the system.
 *
 * It hides on 들퍼레's home screen, the recents screen, the lock screen, with the keyboard up, in full screen (the system bar
 * went away), during phone control, on a small screen, with gesture navigation, when the window list cannot be read, and
 * whenever 들퍼레 is not the home app (then Samsung's own taskbar is there). Why it is hidden shows on its settings row, and an
 * unexpected reason (no bar window found, no window list, gesture bar) goes once into 폰 조작 기록 with the bottom system
 * window's rectangle. Which app is in front comes from the window-state events the service already receives: package names
 * only, kept in memory for the recent apps and never stored or sent. An icon dragged up into the screen, or held → 화면
 * 나누기로 열기, opens that app beside the current one (split, logged step by step in 폰 조작 기록; a failure also in 퍼레가 못
 * 한 일, with a chip that opens the recents screen, where One UI splits by hand).
 *
 * Everything runs on the main looper, each posted piece behind a guard: a failure takes the bar, its menu and its drop
 * window off the screen (the system bar is never left covered), is written to the diagnostics, and never reaches the
 * service, which Android stops reconnecting after a crash.
 */
final class Taskbar implements AutoCloseable {
    private final AccessibilityService service;
    private final AppStore store;
    private final WindowManager manager;
    private final LauncherApps launcher;
    private final UserManager users;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-taskbar"); t.setDaemon(true); return t; });
    /** Read again on every refresh: a display-size change, or the Fold's two screens, can change it. */
    private float density;
    private final WindowManager.LayoutParams params;
    private final TaskbarView view;
    private final SharedPreferences homePrefs;
    private volatile boolean closed;
    private TaskbarView.Drop drop;
    private View menu, chip;
    private boolean attached, dropAttached;
    /** Apps that came to the front, newest first: package names in memory only. */
    private final ArrayDeque<String> seen = new ArrayDeque<>();
    private String front = "";
    /** When (uptime) 들퍼레's home screen, the recents screen (or another home) and an app last came to the front. */
    private long homeAt, recentsAt, appAt;
    /** The recents screen's window (from its event), so the bar knows when it went away even with no app event after it. */
    private int recentsWindow = -1;
    private volatile Map<String, LauncherActivityInfo> byId = Map.of(), byPackage = Map.of();
    /** Home screens other than 들퍼레's ("package/class") and the packages that show the recents screen. */
    private volatile Set<String> homeScreens = Set.of(), recentsApps = Set.of();
    private volatile List<String> dockIds = List.of(), homeRecent = List.of();
    private final LruCache<String, Drawable> icons = new LruCache<>(64);
    private final Set<String> loading = new HashSet<>();
    private TaskbarPlan.Hidden hidden = TaskbarPlan.Hidden.OFF;
    /** The lowest system window last read ("left,top–right,bottom"), for the log line that says why the bar is hidden. */
    private String bottom = "";
    private final Map<TaskbarPlan.Hidden, Long> logged = new EnumMap<>(TaskbarPlan.Hidden.class);
    private boolean lastOn, lastArmed;
    private long failedAt = -1;
    private final Runnable refresh = this::refresh;
    private final Runnable closeMenu = this::hideMenu, closeChip = this::hideChip;
    private final SharedPreferences.OnSharedPreferenceChangeListener homeChanged = (p, key) -> { if (HomeActivity.DOCK.equals(key) || HomeActivity.RECENT.equals(key)) reload(); };
    private final LauncherApps.Callback appsChanged = new LauncherApps.Callback() {
        @Override public void onPackageRemoved(String p, UserHandle u) { reload(); }
        @Override public void onPackageAdded(String p, UserHandle u) { reload(); }
        @Override public void onPackageChanged(String p, UserHandle u) { synchronized (icons) { icons.evictAll(); } reload(); }
        @Override public void onPackagesAvailable(String[] p, UserHandle u, boolean replacing) { reload(); }
        @Override public void onPackagesUnavailable(String[] p, UserHandle u, boolean replacing) { reload(); }
    };

    Taskbar(AccessibilityService service, AppStore store) {
        this.service = service; this.store = store;
        manager = service.getSystemService(WindowManager.class);
        launcher = service.getSystemService(LauncherApps.class);
        users = service.getSystemService(UserManager.class);
        density = service.getResources().getDisplayMetrics().density;
        homePrefs = service.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE);
        params = new WindowManager.LayoutParams(1, 1, WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        // Over the system bar itself: the window must not be pushed above it.
        params.setFitInsetsTypes(0); params.setFitInsetsSides(0);
        params.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        params.gravity = Gravity.BOTTOM | Gravity.LEFT; params.setTitle(Brand.NAME + " 작업 표시줄");
        view = new TaskbarView(service, actions);
        lastOn = store.taskbar(); lastArmed = PhoneService.gate.armed();
        homePrefs.registerOnSharedPreferenceChangeListener(homeChanged);
        try { launcher.registerCallback(appsChanged, main); } catch (RuntimeException ignored) {}
        reload();
    }
    private int dp(float v) { return Math.round(v * density); }

    /** A piece of work for the main looper: skipped once closed; a failure takes the bar down instead of the service. */
    private Runnable guard(Runnable body) {
        return () -> { if (closed) return; try { body.run(); } catch (RuntimeException | LinkageError failure) { fail(failure); } };
    }
    private void fail(Throwable failure) {
        splitting = null;
        try { hideMenu(); } catch (RuntimeException ignored) {}
        try { hideChip(); } catch (RuntimeException ignored) {}
        try { endDrag(); } catch (RuntimeException ignored) {}
        try { detach(); } catch (RuntimeException ignored) {}
        long now = SystemClock.uptimeMillis();
        if (failedAt < 0 || now - failedAt > 60_000 || now < failedAt) { failedAt = now; DiagnosticsExport.record(service, "taskbar", failure); }
    }

    // ── showing and hiding ───────────────────────────────────────────

    /** A window appeared, went or changed (or an app came to the front): looked at once things settle. */
    void windowsChanged(AccessibilityEvent event) {
        if (closed) return;
        try {
            if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && store.taskbar())
                noteFront(event.getPackageName(), event.getClassName(), event.getWindowId());
        } catch (RuntimeException failure) { fail(failure); return; }
        later();
    }
    /**
     * Looked at again 120 ms from the first request: one already waiting is left alone, so a stream of requests (window
     * events, the store changing while a voice reply streams) never keeps pushing it back.
     */
    void later() { if (!closed && !main.hasCallbacks(refresh)) main.postDelayed(refresh, 120); }
    /** The store changed: only the switch and phone control matter to the bar. */
    void storeChanged() {
        boolean on = store.taskbar(), armed = PhoneService.gate.armed();
        if (on == lastOn && armed == lastArmed) return;
        lastOn = on; lastArmed = armed; later();
    }
    void screenOff() { if (closed) return; try { hideMenu(); hideChip(); endDrag(); detach(); } catch (RuntimeException failure) { fail(failure); } }
    void configurationChanged() { later(); }
    /** Why the bar is hidden now (NONE: shown), for the settings row and the tests. */
    TaskbarPlan.Hidden hidden() { return hidden; }

    void refresh() {
        if (closed) return;
        try { look(); } catch (RuntimeException | LinkageError failure) { fail(failure); }
    }
    private void look() {
        main.removeCallbacks(refresh);
        float d = service.getResources().getDisplayMetrics().density;
        if (d != density) {
            // A new density: icons, the drop window and the layout are made again at the new size.
            density = d; synchronized (icons) { icons.evictAll(); } drawn = "";
            endDrag(); drop = null; hideMenu(); hideChip();
        }
        TaskbarPlan.State state = state();
        TaskbarPlan.Hidden now = TaskbarPlan.hidden(state);
        boolean changed = now != hidden;
        hidden = now;
        if (now == TaskbarPlan.Hidden.NONE) show(TaskbarPlan.barHeight(state)); else { hideMenu(); hideChip(); endDrag(); detach(); }
        if (changed) { note(state); store.changed(); }
    }
    private TaskbarPlan.State state() {
        Rect screen = screen(); float d = density;
        boolean enabled = store.taskbar(), home = enabled && homeHeld(service), locked = home && locked();
        // 들퍼레's home in front (resumed): no bar, whatever window event came last (a screen recorder's countdown, an app going
        // away behind it) — it stayed on the home screen for seconds (2026-11-27).
        TaskbarPlan.Top top = HomeActivity.resumed ? TaskbarPlan.Top.HOME : TaskbarPlan.top(HomeActivity.visible, Math.max(homeAt, HomeActivity.shownAt), recentsAt, appAt);
        // The window list is read only when nothing cheaper already hides the bar.
        boolean look = enabled && home && !locked && top != TaskbarPlan.Top.HOME && Math.min(screen.width(), screen.height()) / d >= 600;
        Set<Integer> appWindows = new HashSet<>();
        List<TaskbarPlan.Win> windows = look ? windows(appWindows) : List.of();
        boolean read = !appWindows.isEmpty();
        // The recents screen's window is gone (back to an app without a new event from it): an app is in front.
        if (top == TaskbarPlan.Top.RECENTS && read && recentsWindow >= 0 && !appWindows.contains(recentsWindow)) { recentsAt = 0; recentsWindow = -1; top = TaskbarPlan.Top.APP; }
        if (look) bottom = lowest(windows);
        return new TaskbarPlan.State(enabled, home, locked, top, PhoneService.gate.armed(), screen.width(), screen.height(), d, read, windows);
    }
    private static long roleAt = -1;
    private static boolean roleHeld;
    /** Whether 들퍼레 is the home app: one RoleManager call per 3 seconds at most (the settings row asks on every change). */
    static synchronized boolean homeHeld(Context context) {
        long now = SystemClock.uptimeMillis();
        if (roleAt < 0 || now - roleAt > 3000 || now < roleAt) {
            roleAt = now;
            try { RoleManager roles = context.getSystemService(RoleManager.class); roleHeld = roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME); }
            catch (RuntimeException unknown) { roleHeld = false; }
        }
        return roleHeld;
    }
    /** The next homeHeld asks RoleManager again (tests, and the settings page when the user may have just changed it). */
    static synchronized void forgetRole() { roleAt = -1; }
    /** Whether the bar can appear on this phone at all: 들퍼레 is the home app and the screen is large (unfolded, tablet). */
    static boolean applies(Context context) {
        return homeHeld(context) && context.getResources().getConfiguration().smallestScreenWidthDp >= 600;
    }
    private boolean locked() {
        KeyguardManager lock = service.getSystemService(KeyguardManager.class); PowerManager power = service.getSystemService(PowerManager.class);
        return (lock != null && lock.isKeyguardLocked()) || (power != null && !power.isInteractive());
    }
    private Rect screen() {
        try { return manager.getCurrentWindowMetrics().getBounds(); }
        catch (RuntimeException e) { android.util.DisplayMetrics m = service.getResources().getDisplayMetrics(); return new Rect(0, 0, m.widthPixels, m.heightPixels); }
    }
    /** The windows on screen as plain rectangles (only their type, place and id); this bar's own windows left out. */
    private List<TaskbarPlan.Win> windows(Set<Integer> appWindows) {
        List<TaskbarPlan.Win> out = new ArrayList<>();
        Rect screen = screen(); int found = -1;
        try {
            for (AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                Rect b = new Rect(); w.getBoundsInScreen(b);
                out.add(new TaskbarPlan.Win(w.getType(), b.left, b.top, b.right, b.bottom));
                if (w.getType() == AccessibilityWindowInfo.TYPE_APPLICATION && appWindows != null) appWindows.add(w.getId());
                // The system bar along the bottom: where its own buttons start (the bar stops before them).
                if (w.getType() == AccessibilityWindowInfo.TYPE_SYSTEM && b.bottom >= screen.height() - 2 && b.right >= screen.width() - 2 && b.top > screen.height() * 0.8f) {
                    int left = buttonsLeft(w); if (left > 0) found = found < 0 ? left : Math.min(found, left);
                }
            }
        } catch (RuntimeException unavailable) { /* none read */ }
        if (found > 0) buttonsLeft = found;
        return out;
    }
    /** The system's own buttons' left edge last read (-1: never read; TaskbarPlan.BUTTONS_LEFT then). */
    private int buttonsLeft = -1;
    private static final String[] BUTTON_IDS = {"com.android.systemui:id/recent_apps", "com.android.systemui:id/home", "com.android.systemui:id/back"};
    private static final java.util.regex.Pattern BUTTON_NAMES = java.util.regex.Pattern.compile("(?i)^(최근 앱|최근 실행 앱|홈|뒤로|recent apps|recents|overview|home|back)$");
    /** The leftmost of recents · home · back in the system bar's window (by their ids, else their names), or -1. */
    private static int buttonsLeft(AccessibilityWindowInfo window) {
        android.view.accessibility.AccessibilityNodeInfo root = window.getRoot(); if (root == null) return -1;
        int left = -1; Rect b = new Rect();
        for (String id : BUTTON_IDS)
            for (android.view.accessibility.AccessibilityNodeInfo n : root.findAccessibilityNodeInfosByViewId(id)) {
                if (!n.isVisibleToUser()) continue; n.getBoundsInScreen(b); if (b.width() > 0) left = left < 0 ? b.left : Math.min(left, b.left);
            }
        if (left > 0) return left;
        // Not by id (another maker's bar): by the names the buttons say, a few levels down.
        java.util.ArrayDeque<android.view.accessibility.AccessibilityNodeInfo> todo = new java.util.ArrayDeque<>(); todo.add(root); int seen = 0;
        while (!todo.isEmpty() && seen++ < 80) {
            android.view.accessibility.AccessibilityNodeInfo n = todo.poll();
            CharSequence said = n.getContentDescription();
            if (said != null && BUTTON_NAMES.matcher(said.toString().trim()).matches() && n.isVisibleToUser()) { n.getBoundsInScreen(b); if (b.width() > 0) left = left < 0 ? b.left : Math.min(left, b.left); }
            for (int i = 0; i < n.getChildCount(); i++) { android.view.accessibility.AccessibilityNodeInfo c = n.getChild(i); if (c != null) todo.add(c); }
        }
        return left;
    }
    private static String lowest(List<TaskbarPlan.Win> windows) {
        TaskbarPlan.Win low = null;
        for (TaskbarPlan.Win w : windows) if (w.type() == TaskbarPlan.SYSTEM && (low == null || w.bottom() > low.bottom() || (w.bottom() == low.bottom() && w.top() > low.top()))) low = w;
        return low == null ? "없음" : low.left() + "," + low.top() + "–" + low.right() + "," + low.bottom();
    }
    /**
     * Why the bar went away, once in 폰 조작 기록 when it is a reason the phone itself may cause (One UI not listing its bar
     * window, no window list, gesture navigation), and when it first shows: with the screen, the bar's height and the lowest
     * system window, so it can be fixed from the phone's log. Each reason at most once in ten minutes.
     */
    private void note(TaskbarPlan.State s) {
        TaskbarPlan.Hidden h = hidden;
        boolean worth = h == TaskbarPlan.Hidden.NONE || h == TaskbarPlan.Hidden.FULL_SCREEN || h == TaskbarPlan.Hidden.NO_WINDOWS
            || h == TaskbarPlan.Hidden.NO_BOTTOM_BAR || h == TaskbarPlan.Hidden.GESTURE_BAR;
        if (!worth) return;
        long now = SystemClock.uptimeMillis(); Long last = logged.get(h);
        if (last != null && now - last < 600_000 && now >= last) return;
        logged.put(h, now);
        int barHeight = TaskbarPlan.barHeight(s);
        store.phoneLog("  · 작업 표시줄 · " + (h == TaskbarPlan.Hidden.NONE ? "나타남 · 높이 " + barHeight + "px" : "숨김: " + reason(h))
            + " · 화면 " + s.screenWidth() + "×" + s.screenHeight() + " · 아래 시스템 창 " + bottom + " · 창 " + s.windows().size() + "개");
    }
    /** Why it is hidden, in a few words (the settings row and the phone log). */
    static String reason(TaskbarPlan.Hidden h) {
        return switch (h) {
            case NONE -> "지금 화면 아래에 나와 있어요";
            case OFF -> "꺼져 있어요";
            case NOT_HOME_APP -> Brand.I + " 홈 화면 앱이 아니에요";
            case LOCKED -> "잠금 화면";
            case HOME -> "홈 화면";
            case RECENTS -> "최근 앱 화면";
            case SMALL_SCREEN -> "작은 화면";
            case PHONE_CONTROL -> "폰 조작 중";
            case NO_WINDOWS -> "화면의 창 목록을 읽지 못했어요";
            case KEYBOARD -> "키보드가 떠 있어요";
            case FULL_SCREEN -> "아래 바 창을 못 찾았어요(전체 화면으로 봄)";
            case GESTURE_BAR -> "제스처 내비게이션(버튼 내비게이션에서 나와요)";
            case NO_BOTTOM_BAR -> "화면 아래에 바가 없어요";
        };
    }
    private boolean dark() { return (service.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    /** What the bar shows now, so it is rebuilt only when that changes (never under a finger). */
    private String drawn = "";
    private void show(int height) {
        Rect screen = screen(); boolean dark = dark();
        List<TaskbarView.Item> dock = items(dockIds, Set.of()), recent = recent(dock);
        StringBuilder key = new StringBuilder().append(screen.width()).append('x').append(screen.height()).append('/').append(height).append(dark).append('@').append(density).append('<').append(buttonsLeft);
        for (TaskbarView.Item i : dock) key.append('|').append(i.id()).append(i.icon() != null);
        key.append('#'); for (TaskbarView.Item i : recent) key.append('|').append(i.id()).append(i.icon() != null);
        if (!key.toString().equals(drawn) || view.layout() == null) {
            if (attached && view.touching()) { later(); return; }
            drawn = key.toString();
            view.show(dock, recent, screen.width(), height, screen.height() - height, dark, density, buttonsLeft);
        }
        int width = view.layout().opaqueEnd();
        boolean changed = params.width != width || params.height != height;
        params.width = width; params.height = height; params.x = 0; params.y = 0;
        try {
            if (!attached) { manager.addView(view, params); attached = true; }
            else if (changed) manager.updateViewLayout(view, params);
        } catch (RuntimeException refused) { attached = false; }
    }
    private void detach() {
        if (attached) try { manager.removeView(view); } catch (RuntimeException ignored) {}
        attached = false; drawn = "";
    }

    // ── the apps on it ───────────────────────────────────────────────

    /**
     * Every launchable app (each profile), the other home screens and the recents screen's package, the dock and the home's
     * recent list: read off the main thread.
     */
    private void reload() {
        if (closed) return;
        try {
            io.execute(() -> {
                Map<String, LauncherActivityInfo> ids = new HashMap<>(), packages = new HashMap<>();
                UserHandle me = Process.myUserHandle();
                List<UserHandle> profiles;
                try { profiles = launcher.getProfiles(); } catch (RuntimeException unreadable) { profiles = List.of(me); }
                try {
                    for (UserHandle u : profiles) for (LauncherActivityInfo info : launcher.getActivityList(null, u)) {
                        ids.put(HomeActivity.id(info, users), info);
                        if (u.equals(me)) packages.putIfAbsent(info.getComponentName().getPackageName(), info);
                    }
                } catch (RuntimeException unreadable) { /* none */ }
                String own = service.getPackageName();
                Set<String> screens = new HashSet<>(), recents = new HashSet<>();
                try {
                    for (android.content.pm.ResolveInfo r : service.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0)) {
                        if (r.activityInfo == null || own.equals(r.activityInfo.packageName)) continue;
                        screens.add(r.activityInfo.packageName + "/" + r.activityInfo.name);
                        // A home app with no icon of its own (One UI Home): what it shows while 들퍼레 is home is the recents screen.
                        if (!packages.containsKey(r.activityInfo.packageName)) recents.add(r.activityInfo.packageName);
                    }
                } catch (RuntimeException unreadable) { /* none */ }
                // The phone's own recents screen (One UI: com.sec.android.app.launcher/com.android.quickstep.RecentsActivity).
                try {
                    int id = Resources.getSystem().getIdentifier("config_recentsComponentName", "string", "android");
                    ComponentName c = id == 0 ? null : ComponentName.unflattenFromString(Resources.getSystem().getString(id));
                    if (c != null && !own.equals(c.getPackageName())) recents.add(c.getPackageName());
                } catch (RuntimeException unreadable) { /* the home apps then */ }
                List<String> dock = list(homePrefs.getString(HomeActivity.DOCK, "[]")), recent = list(homePrefs.getString(HomeActivity.RECENT, "[]"));
                main.post(guard(() -> { byId = ids; byPackage = packages; homeScreens = screens; recentsApps = recents; dockIds = dock; homeRecent = recent; later(); }));
            });
        } catch (RuntimeException stopped) { /* closed */ }
    }
    private static List<String> list(String json) {
        List<String> out = new ArrayList<>();
        try { JSONArray a = new JSONArray(json); for (int i = 0; i < a.length(); i++) { String s = a.optString(i, ""); if (!s.isEmpty()) out.add(s); } } catch (Exception unreadable) { /* none */ }
        return out;
    }
    private List<TaskbarView.Item> items(List<String> ids, Set<String> leaveOut) {
        List<TaskbarView.Item> out = new ArrayList<>();
        for (String id : ids) { LauncherActivityInfo info = byId.get(id); if (info != null && !leaveOut.contains(id)) out.add(item(id, info)); }
        return out;
    }
    /** The recent apps: what came to the front lately (newest first), then the home's own recent list; not what the dock has. */
    private List<TaskbarView.Item> recent(List<TaskbarView.Item> dock) {
        Set<String> taken = new HashSet<>(); for (TaskbarView.Item i : dock) taken.add(i.id());
        List<String> ids = new ArrayList<>();
        Map<String, LauncherActivityInfo> packages = byPackage;
        for (String pkg : seen) { LauncherActivityInfo info = packages.get(pkg); if (info != null) ids.add(HomeActivity.id(info, users)); }
        ids.addAll(homeRecent);
        List<TaskbarView.Item> out = new ArrayList<>(); Set<String> added = new HashSet<>();
        for (String id : ids) {
            if (out.size() >= TaskbarPlan.MOST_RECENT) break;
            LauncherActivityInfo info = byId.get(id);
            if (info == null || taken.contains(id) || !added.add(id)) continue;
            out.add(item(id, info));
        }
        return out;
    }
    private TaskbarView.Item item(String id, LauncherActivityInfo info) {
        Drawable icon; synchronized (icons) { icon = icons.get(id); }
        if (icon == null) loadIcon(id, info);
        return new TaskbarView.Item(id, info.getComponentName().getPackageName(), String.valueOf(info.getLabel()), icon);
    }
    /** An app's icon as the phone draws it (work badge included), drawn once off the main thread at the bar's size. */
    private void loadIcon(String id, LauncherActivityInfo info) {
        if (!loading.add(id)) return;
        int size = dp(34), dpi = service.getResources().getDisplayMetrics().densityDpi;
        try {
            io.execute(() -> {
                Drawable done = null;
                try {
                    Drawable d = info.getBadgedIcon(dpi);
                    Bitmap b = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888); d.setBounds(0, 0, size, size); d.draw(new Canvas(b));
                    done = new BitmapDrawable(service.getResources(), b);
                } catch (Exception | OutOfMemoryError unreadable) { /* the placeholder stays */ }
                Drawable icon = done;
                // Each icon only asks for a look: the first show's icons arrive as one rebuild, not one per icon.
                main.post(guard(() -> { loading.remove(id); if (icon != null && size == dp(34)) { synchronized (icons) { icons.put(id, icon); } later(); } }));
            });
        } catch (RuntimeException stopped) { loading.remove(id); }
    }
    /**
     * Something came to the front, from a window-state event: 들퍼레's home screen, the recents screen (or another home), or
     * a launchable app (not the keyboard or the system). Only the package and the activity's class are looked at.
     */
    private void noteFront(CharSequence pkg, CharSequence cls, int window) {
        if (pkg == null) return;
        String p = pkg.toString(), c = cls == null ? "" : cls.toString();
        long now = SystemClock.uptimeMillis();
        if (p.equals(service.getPackageName())) {
            if (c.equals(HomeActivity.class.getName())) { homeAt = now; return; }
            // 들퍼레 itself counts as an app only for its main screen (not its voice window or overlays).
            if (!c.equals(MainActivity.class.getName())) return;
        } else if (recentsApps.contains(p) || homeScreens.contains(p + "/" + c)) { recentsAt = now; recentsWindow = window; return; }
        if (!byPackage.containsKey(p)) return;
        appAt = now; front = p;
        if (splitting != null) splitting.came.add(p);
        if (!p.equals(seen.peekFirst())) { seen.remove(p); seen.addFirst(p); while (seen.size() > 8) seen.removeLast(); }
    }

    // ── what the bar does ────────────────────────────────────────────

    private final TaskbarView.Actions actions = new TaskbarView.Actions() {
        @Override public void open(TaskbarView.Item item, View from) {
            run(() -> {
                hideMenu(); hideChip();
                LauncherActivityInfo info = byId.get(item.id()); if (info == null) return;
                int[] at = new int[2]; from.getLocationOnScreen(at);
                Rect bounds = new Rect(at[0], at[1], at[0] + from.getWidth(), at[1] + from.getHeight());
                try { launcher.startMainActivity(info.getComponentName(), info.getUser(), bounds, null); }
                catch (RuntimeException gone) { Toast.makeText(service, item.label() + " 앱을 열지 못했어요.", Toast.LENGTH_SHORT).show(); reload(); }
            });
        }
        @Override public void menu(TaskbarView.Item item, View from) { run(() -> showMenu(item, from)); }
        @Override public void drag(TaskbarView.Item item, float x, float y) { run(() -> { hideMenu(); hideChip(); dragAt(item, x, y); }); }
        @Override public void drop(TaskbarView.Item item, TaskbarPlan.Side side) {
            run(() -> { endDrag(); if (TaskbarPlan.first(side) != null) split(item, side, "끌어 놓기"); });
        }
        @Override public void apps(boolean search) {
            run(() -> {
                hideMenu(); hideChip();
                // As a home intent, so the home screen's own task comes forward (an explicit start alone would make a second one).
                Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(new ComponentName(service, HomeActivity.class));
                try { service.startActivity(home.putExtra(HomeActivity.SHOW, search ? "search" : "apps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
                catch (RuntimeException refused) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
            });
        }
        @Override public void nav(int which) {
            run(() -> {
                hideMenu(); hideChip();
                service.performGlobalAction(which == 0 ? AccessibilityService.GLOBAL_ACTION_RECENTS : which == 1 ? AccessibilityService.GLOBAL_ACTION_HOME : AccessibilityService.GLOBAL_ACTION_BACK);
            });
        }
        /** Home held, as the system's home button does: the phone's assistant (들퍼레 itself when it is the one). */
        @Override public void homeHeld() {
            run(() -> {
                hideMenu(); hideChip();
                boolean ours = false;
                try { RoleManager roles = service.getSystemService(RoleManager.class); ours = roles != null && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT); } catch (RuntimeException ignored) {}
                try {
                    if (!ours) { service.startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
                } catch (RuntimeException none) { /* 들퍼레 then */ }
                try { service.startActivity(new Intent(service, QuickVoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) {}
            });
        }
    };
    /** A touch on the bar, run under the same guard as everything posted. */
    private void run(Runnable body) { guard(body).run(); }

    /** A small pill over the bar with one action in it (the menu, the chip after a split that did not happen). */
    private LinearLayout pill(String words, String description, Runnable tap) {
        boolean dark = dark();
        LinearLayout row = new LinearLayout(service); row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable panel = new GradientDrawable(); panel.setColor(dark ? 0xf2262522 : 0xf7ffffff); panel.setCornerRadius(20 * density);
        if (!dark) panel.setStroke(Math.max(1, dp(0.5f)), 0x1f000000);
        row.setBackground(panel); row.setElevation(10 * density); int pad = dp(4); row.setPadding(pad, pad, pad, pad);
        TextView text = new TextView(service); text.setText(words); text.setTextSize(14); text.setTypeface(Ui.weight(620));
        text.setTextColor(dark ? 0xffeb9b7d : 0xffb94e2b); text.setPadding(dp(14), dp(9), dp(14), dp(9));
        text.setContentDescription(description);
        text.setOnClickListener(v -> run(tap));
        row.addView(text);
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        return row;
    }
    private WindowManager.LayoutParams pillParams(View row, int center, boolean watchOutside) {
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | (watchOutside ? WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH : 0) | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0); p.gravity = Gravity.BOTTOM | Gravity.LEFT;
        int width = screen().width();
        p.x = Math.max(dp(8), Math.min(center - row.getMeasuredWidth() / 2, width - row.getMeasuredWidth() - dp(8))); p.y = params.height + dp(6);
        return p;
    }
    /** Held: a small menu just above the icon, 화면 나누기로 열기 (and gone on any touch outside it or after six seconds). */
    private void showMenu(TaskbarView.Item item, View from) {
        hideMenu(); hideChip();
        LinearLayout row = pill("화면 나누기로 열기", item.label() + " 화면 나누기로 열기", () -> { hideMenu(); split(item, TaskbarPlan.Side.NONE, "길게 눌러 고르기"); });
        int[] at = new int[2]; from.getLocationOnScreen(at);
        WindowManager.LayoutParams p = pillParams(row, at[0] + from.getWidth() / 2, true);
        row.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideMenu(); return true; } return false; });
        try { manager.addView(row, p); menu = row; main.postDelayed(closeMenu, 6000); } catch (RuntimeException ignored) {}
    }
    private void hideMenu() {
        main.removeCallbacks(closeMenu);
        View current = menu; menu = null;
        if (current != null) try { manager.removeView(current); } catch (RuntimeException ignored) {}
    }
    /**
     * The screen did not split: one tap away from the recents screen, where One UI's own 분할 화면으로 열기 is (a chip over
     * the recents button for eight seconds; a toast when the bar is not on screen).
     */
    private void offerRecents(String label) {
        hideChip();
        if (!attached) { Toast.makeText(service, "화면 나누기가 되지 않아 " + label + " 앱을 그냥 열었어요. 최근 앱(|||)에서 앱 아이콘을 누르면 ‘분할 화면으로 열기’가 있어요.", Toast.LENGTH_LONG).show(); return; }
        LinearLayout row = pill("안 나뉘었어요 · 최근 앱에서 나누기", "화면이 나뉘지 않았어요. 최근 앱 화면 열기", () -> { hideChip(); service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_RECENTS); });
        WindowManager.LayoutParams p = pillParams(row, TaskbarPlan.nav(screen().width())[0], false);
        try { manager.addView(row, p); chip = row; main.postDelayed(closeChip, 8000); } catch (RuntimeException ignored) {}
    }
    private void hideChip() {
        main.removeCallbacks(closeChip);
        View current = chip; chip = null;
        if (current != null) try { manager.removeView(current); } catch (RuntimeException ignored) {}
    }
    private void dragAt(TaskbarView.Item item, float x, float y) {
        Rect screen = screen(); int barTop = screen.height() - params.height;
        if (drop == null || drop.density() != density) { endDrag(); drop = new TaskbarView.Drop(service, density); }
        drop.at(item.icon(), dp(34), x, y, TaskbarPlan.side(x, y, screen.width(), barTop, dp(24)), barTop);
        if (dropAttached) return;
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.MATCH_PARENT, WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
            | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0); p.layoutInDisplayCutoutMode = WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS;
        try { manager.addView(drop, p); dropAttached = true; } catch (RuntimeException ignored) {}
    }
    private void endDrag() {
        if (dropAttached && drop != null) try { manager.removeView(drop); } catch (RuntimeException ignored) {}
        dropAttached = false;
    }

    // ── 화면 나누기 ──────────────────────────────────────────────────

    /** One try at opening an app beside the one in front: what was asked, each step taken (for the log), what came since. */
    private static final class Attempt {
        final TaskbarView.Item item; final LauncherActivityInfo info; final TaskbarPlan.Side wanted; final String how, before; final boolean wasSplit;
        final List<String> path = new ArrayList<>();
        /** Apps whose window came to the front since the try began (their window-state events). */
        final Set<String> came = new HashSet<>();
        Attempt(TaskbarView.Item item, LauncherActivityInfo info, TaskbarPlan.Side wanted, String how, String before, boolean wasSplit) {
            this.item = item; this.info = info; this.wanted = wanted; this.how = how; this.before = before; this.wasSplit = wasSplit;
        }
    }
    private Attempt splitting;
    /** The flags for starting an app beside the current one (TaskbarPlan.ownTask: a second window of the same app). */
    static int splitFlags(String app, String front) {
        return Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT | (TaskbarPlan.ownTask(app, front) ? Intent.FLAG_ACTIVITY_MULTIPLE_TASK : 0);
    }
    private void split(TaskbarView.Item item, TaskbarPlan.Side wanted, String how) {
        LauncherActivityInfo info = byId.get(item.id()); if (info == null || splitting != null) return;
        hideChip();
        splitting = new Attempt(item, info, wanted, how, front, splitNow());
        step(splitting, TaskbarPlan.Step.ADJACENT);
    }
    private boolean splitNow() { Rect screen = screen(); return TaskbarPlan.split(windows(null), screen.width(), screen.height()); }
    private void step(Attempt a, TaskbarPlan.Step step) {
        if (closed || splitting != a) return;
        switch (step) {
            case ADJACENT -> {
                String ran = launchBeside(a.info, a.before);
                a.path.add((a.wasSplit ? "나뉜 화면에 " : "") + ran);
                if (!ran.startsWith("adjacent")) { finish(a, false, "옆에 열 수 없는 앱"); return; }
                // Up to 3 s for a cold start. On a screen split before, its own window event must show the app came.
                waitForSplit(a, a.item.pkg(), a.wasSplit, 3000, shown -> step(a, TaskbarPlan.next(TaskbarPlan.Step.ADJACENT, shown, false, a.wasSplit)));
            }
            case TOGGLE -> {
                // Looked at once more: a split that came after the wait is kept, never toggled away.
                boolean now = splitNow();
                if (!TaskbarPlan.mayToggle(now, a.wasSplit)) { if (now) a.path.add("늦게 나뉨"); step(a, now ? TaskbarPlan.Step.SPLIT : TaskbarPlan.Step.FULL); return; }
                boolean accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
                a.path.add("toggle " + (accepted ? "받음" : "거절"));
                if (!accepted) { step(a, TaskbarPlan.next(TaskbarPlan.Step.TOGGLE, false, false, a.wasSplit)); return; }
                waitForSplit(a, null, false, 1500, shown -> step(a, TaskbarPlan.next(TaskbarPlan.Step.TOGGLE, shown, true, a.wasSplit)));
            }
            case PAIR -> {
                // The app now on one half is the one asked for; the one that was in front comes back beside it.
                LauncherActivityInfo previous = a.before == null || a.before.equals(a.item.pkg()) ? null : byPackage.get(a.before);
                if (previous == null) { a.path.add("pair 없음"); finish(a, true, "다른 쪽은 직접 골라요"); return; }
                a.path.add("pair " + launchBeside(previous, a.item.pkg()));
                waitForSplit(a, a.before, true, 3000, shown -> step(a, TaskbarPlan.next(TaskbarPlan.Step.PAIR, shown, true, a.wasSplit)));
            }
            case SPLIT -> finish(a, true, "");
            case FULL -> finish(a, false, "");
        }
    }
    /**
     * Starts the app beside the one in front and says how: "adjacent(홈에서)", "adjacent(서비스…)", or why not ("다른 프로필",
     * "실행 거절"). From 들퍼레's home screen activity when it is alive (it is, stopped behind the app, whenever the bar shows):
     * Android 14+ keeps FLAG_ACTIVITY_LAUNCH_ADJACENT only on a start with a source activity, and the home task, being a home,
     * is not what Shell pulls into the split; the app in front is. The service's own start is the fallback (Android 13 keeps
     * the flag there).
     */
    private String launchBeside(LauncherActivityInfo info, String front) {
        String pkg = info.getComponentName().getPackageName();
        if (!info.getUser().equals(Process.myUserHandle())) {
            // A work-profile app opens through LauncherApps, which takes no flags: in full screen.
            try { launcher.startMainActivity(info.getComponentName(), info.getUser(), null, null); } catch (RuntimeException ignored) {}
            return "다른 프로필(전체 화면)";
        }
        int flags = splitFlags(pkg, front);
        String extra = (flags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0 ? "+새 창" : "";
        Intent intent = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(info.getComponentName()).addFlags(flags);
        Activity home = HomeActivity.alive();
        String fallback = home == null ? "홈 없음" : "홈 거절";
        if (home != null) {
            try { home.startActivity(intent); return "adjacent(홈에서)" + extra; }
            catch (RuntimeException refused) { /* the service then */ }
        }
        try {
            service.startActivity(intent);
            return "adjacent(서비스, " + fallback + (Build.VERSION.SDK_INT >= 34 ? ": Android 14+는 무시해요" : "") + ")" + extra;
        } catch (RuntimeException refused) { return "실행 거절(" + refused.getClass().getSimpleName() + ")"; }
    }
    /**
     * Looks every 200 ms, up to ms, until the screen is split: by the windows' places alone (two app windows sharing the
     * screen, or a divider). needApp: also pkg's own window event since the try began (the screen was split already, so the
     * places alone say nothing). No window's content is read.
     */
    private void waitForSplit(Attempt a, String pkg, boolean needApp, long ms, Consumer<Boolean> then) {
        long until = SystemClock.uptimeMillis() + ms;
        Runnable[] tick = new Runnable[1];
        tick[0] = guard(() -> {
            if (splitting != a) return;
            boolean shown = splitNow() && (pkg == null || !needApp || a.came.contains(pkg));
            if (shown || SystemClock.uptimeMillis() >= until) then.accept(shown); else main.postDelayed(tick[0], 200);
        });
        main.postDelayed(tick[0], 300);
    }
    /** The window the system lists under this app's name (its window title, as TalkBack reads it), for the side it took. */
    private TaskbarPlan.Win windowOf(String label) {
        if (label == null || label.isEmpty()) return null;
        try {
            for (AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                CharSequence title = w.getTitle();
                if (title == null || !title.toString().contains(label)) continue;
                Rect b = new Rect(); w.getBoundsInScreen(b);
                return new TaskbarPlan.Win(w.getType(), b.left, b.top, b.right, b.bottom);
            }
        } catch (RuntimeException unavailable) { /* unknown */ }
        return null;
    }
    /**
     * The try is over: one line in 폰 조작 기록 (how it was asked, each step, whether the screen split and where, the Android
     * API level); when it did not split, also in 퍼레가 못 한 일 with the Android version, and a chip to the recents screen.
     */
    private void finish(Attempt a, boolean split, String note) {
        if (splitting == a) splitting = null;
        String side = "";
        if (split) {
            Rect screen = screen();
            TaskbarPlan.Side landed = TaskbarPlan.sideOf(windowOf(a.item.label()), screen.width(), screen.height());
            if (landed != TaskbarPlan.Side.NONE) side = " · " + TaskbarPlan.words(landed) + "에 열림"
                + (a.wanted == TaskbarPlan.Side.NONE || a.wanted == landed ? "" : "(놓은 곳 " + TaskbarPlan.words(a.wanted) + ", 쪽은 Android가 정해요)");
        }
        boolean opened = a.path.stream().noneMatch(step -> step.contains("실행 거절"));
        String line = "화면 나누기 · " + a.item.label() + " · " + a.how + " · 길: " + String.join(" → ", a.path) + " · " + (split ? "나뉨" + side : "안 나뉨")
            + (note.isEmpty() ? "" : " · " + note) + " · API " + Build.VERSION.SDK_INT;
        store.phoneLog("  · " + line);
        if (!split) {
            BuddyMisses.add(service, a.how, "화면 나누기 · " + a.item.label(),
                "길: " + String.join(" → ", a.path) + " · 화면이 나뉘지 않았어요 · Android " + Build.VERSION.RELEASE + "(API " + Build.VERSION.SDK_INT + ") " + Build.MANUFACTURER,
                "", BuddyMisses.NOT_SHOWN);
            if (opened) offerRecents(a.item.label());
            else Toast.makeText(service, a.item.label() + " 앱을 열지 못했어요.", Toast.LENGTH_SHORT).show();
        }
    }

    /**
     * The bar's state under its switch on 바로 부르기: first what keeps it away on this phone (not the home app, a small
     * screen), then the accessibility step, then why it is hidden right now.
     */
    static String status(Context context, AppStore store) {
        if (!store.taskbar()) return "꺼져 있어요";
        if (!homeHeld(context)) return "켜짐 · " + Brand.I + " 홈 화면 앱일 때 나와요";
        if (context.getResources().getConfiguration().smallestScreenWidthDp < 600) return "켜짐 · 큰 화면(펼친 폴드·태블릿)에서 나와요";
        if (!PhoneService.connected()) return "켜짐 · 폰 조작 1단계(접근성)를 켜면 나타나요";
        TaskbarPlan.Hidden live = PhoneService.taskbarHidden();
        if (live == null) return "켜짐 · 시작하지 못했어요(진단 기록에 남겼어요)";
        return switch (live) {
            case NONE -> "켜짐 · 지금 화면 아래에 나와 있어요";
            case FULL_SCREEN, NO_WINDOWS, GESTURE_BAR, NO_BOTTOM_BAR -> "숨김 · " + reason(live);
            case KEYBOARD -> "켜짐 · 키보드가 내려가면 나와요";
            case PHONE_CONTROL -> "켜짐 · 폰 조작이 끝나면 다시 나와요";
            default -> "켜짐 · 다른 앱을 쓸 때 화면 아래에 나와요";
        };
    }

    @Override public void close() {
        closed = true;
        main.removeCallbacksAndMessages(null); splitting = null;
        try { hideMenu(); hideChip(); endDrag(); detach(); } catch (RuntimeException ignored) {}
        homePrefs.unregisterOnSharedPreferenceChangeListener(homeChanged);
        try { launcher.unregisterCallback(appsChanged); } catch (RuntimeException ignored) {}
        io.shutdownNow();
    }
}
