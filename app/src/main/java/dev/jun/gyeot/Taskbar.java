package dev.jun.gyeot;

import android.accessibilityservice.AccessibilityService;
import android.app.KeyguardManager;
import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.content.res.Configuration;
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
import android.view.WindowInsets;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Brand;
import dev.jun.gyeot.core.TaskbarPlan;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONArray;

/**
 * 들퍼레 작업 표시줄 (준: "다른 앱 실행했을 때 최하단 바에 아이콘들이 노란흔적만 남고 우측 버튼 세개는 배열이 달라져", "탭 뒤로 홈 버튼도
 * 원래자리로", "홈화면에서는 안 보이고 다른앱켰을때만", "그게 있어야 화면 나누기로 앱 멀티태스크 실행이 가능하거든"). Samsung ties its
 * taskbar to One UI Home and no API lets another home fill it, so while another app is in front this accessibility overlay
 * (above the system bars, like EdgeButton) lies exactly over the plain bar Samsung falls back to: 들퍼레's all-apps button,
 * the dock's apps, a divider and recent apps on the left; recents · home · back at One UI's places on the right, working
 * through performGlobalAction; the far right (the accessibility button) left to the system.
 *
 * It hides on 들퍼레's home screen, the lock screen, with the keyboard up, in full screen (the system bar went away), during
 * phone control, on a small screen, with gesture navigation, and whenever 들퍼레 is not the home app (then Samsung's own taskbar
 * is there). Which app is in front comes from the window-state events the service already receives: package names only,
 * kept in memory for the recent apps and never stored or sent. An icon dragged up into the screen, or held → 화면 나누기로
 * 열기, opens that app beside the current one (split, logged step by step in 폰 조작 기록; a failure also in 퍼레가 못 한 일).
 */
final class Taskbar implements AutoCloseable {
    private final AccessibilityService service;
    private final AppStore store;
    private final WindowManager manager;
    private final LauncherApps launcher;
    private final UserManager users;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService io = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-taskbar"); t.setDaemon(true); return t; });
    private final float density;
    private final WindowManager.LayoutParams params;
    private final TaskbarView view;
    private final SharedPreferences homePrefs;
    private TaskbarView.Drop drop;
    private View menu;
    private boolean attached, dropAttached;
    /** Apps that came to the front, newest first: package names in memory only. */
    private final ArrayDeque<String> seen = new ArrayDeque<>();
    private String front = "";
    private volatile Map<String, LauncherActivityInfo> byId = Map.of(), byPackage = Map.of();
    private volatile Set<String> homes = Set.of();
    private volatile List<String> dockIds = List.of(), homeRecent = List.of();
    private final LruCache<String, Drawable> icons = new LruCache<>(64);
    private final Set<String> loading = new HashSet<>();
    private TaskbarPlan.Hidden hidden = TaskbarPlan.Hidden.OFF;
    private long roleAt = -1; private boolean roleHeld;
    private final Runnable refresh = this::refresh;
    private final Runnable closeMenu = this::hideMenu;
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
        homePrefs.registerOnSharedPreferenceChangeListener(homeChanged);
        try { launcher.registerCallback(appsChanged, main); } catch (RuntimeException ignored) {}
        reload();
    }
    private int dp(float v) { return Math.round(v * density); }

    // ── showing and hiding ───────────────────────────────────────────

    /** A window appeared, went or changed (or an app came to the front): looked at once things settle. */
    void windowsChanged(AccessibilityEvent event) {
        if (event.getEventType() == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED && store.taskbar()) noteFront(event.getPackageName(), event.getClassName());
        later();
    }
    void later() { main.removeCallbacks(refresh); main.postDelayed(refresh, 120); }
    void screenOff() { hideMenu(); endDrag(); detach(); }
    void configurationChanged() { later(); }
    /** Why the bar is hidden now (NONE: shown), for the settings row and the tests. */
    TaskbarPlan.Hidden hidden() { return hidden; }

    void refresh() {
        main.removeCallbacks(refresh);
        TaskbarPlan.State state = state();
        hidden = TaskbarPlan.hidden(state);
        if (hidden == TaskbarPlan.Hidden.NONE) show(TaskbarPlan.barHeight(state)); else { hideMenu(); endDrag(); detach(); }
    }
    private TaskbarPlan.State state() {
        Rect screen = screen(); float d = density;
        boolean enabled = store.taskbar(), home = enabled && defaultHome(), locked = home && locked();
        // The window list is read only when nothing cheaper already hides the bar.
        boolean look = enabled && home && !locked && !HomeActivity.visible && Math.min(screen.width(), screen.height()) / d >= 600;
        List<TaskbarPlan.Win> windows = look ? windows() : List.of();
        boolean read = false; for (TaskbarPlan.Win w : windows) if (w.type() == TaskbarPlan.APPLICATION) { read = true; break; }
        return new TaskbarPlan.State(enabled, home, locked, HomeActivity.visible, PhoneService.gate.armed(), screen.width(), screen.height(), d, read, windows, navInset());
    }
    private boolean defaultHome() {
        long now = SystemClock.uptimeMillis();
        if (roleAt < 0 || now - roleAt > 3000) { roleAt = now; roleHeld = homeHeld(service); }
        return roleHeld;
    }
    static boolean homeHeld(Context context) {
        try { RoleManager roles = context.getSystemService(RoleManager.class); return roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && roles.isRoleHeld(RoleManager.ROLE_HOME); }
        catch (RuntimeException unknown) { return false; }
    }
    private boolean locked() {
        KeyguardManager lock = service.getSystemService(KeyguardManager.class); PowerManager power = service.getSystemService(PowerManager.class);
        return (lock != null && lock.isKeyguardLocked()) || (power != null && !power.isInteractive());
    }
    private Rect screen() {
        try { return manager.getCurrentWindowMetrics().getBounds(); }
        catch (RuntimeException e) { android.util.DisplayMetrics m = service.getResources().getDisplayMetrics(); return new Rect(0, 0, m.widthPixels, m.heightPixels); }
    }
    private int navInset() {
        try { return manager.getCurrentWindowMetrics().getWindowInsets().getInsetsIgnoringVisibility(WindowInsets.Type.navigationBars()).bottom; }
        catch (RuntimeException unknown) { return 0; }
    }
    /** The windows on screen as plain rectangles; this bar's own windows left out. */
    private List<TaskbarPlan.Win> windows() {
        List<TaskbarPlan.Win> out = new ArrayList<>();
        try {
            for (AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                Rect b = new Rect(); w.getBoundsInScreen(b);
                out.add(new TaskbarPlan.Win(w.getType(), b.left, b.top, b.right, b.bottom));
            }
        } catch (RuntimeException unavailable) { /* none read */ }
        return out;
    }
    private boolean dark() { return (service.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES; }

    /** What the bar shows now, so it is rebuilt only when that changes (never under a finger). */
    private String drawn = "";
    private void show(int height) {
        Rect screen = screen(); boolean dark = dark();
        List<TaskbarView.Item> dock = items(dockIds, Set.of()), recent = recent(dock);
        StringBuilder key = new StringBuilder().append(screen.width()).append('x').append(screen.height()).append('/').append(height).append(dark);
        for (TaskbarView.Item i : dock) key.append('|').append(i.id()).append(i.icon() != null);
        key.append('#'); for (TaskbarView.Item i : recent) key.append('|').append(i.id()).append(i.icon() != null);
        if (!key.toString().equals(drawn) || view.layout() == null) {
            if (attached && view.touching()) { later(); return; }
            drawn = key.toString();
            view.show(dock, recent, screen.width(), height, screen.height() - height, dark);
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

    /** Every launchable app (each profile), the home apps, the dock and the home's recent list: read off the main thread. */
    private void reload() {
        try {
            io.execute(() -> {
                Map<String, LauncherActivityInfo> ids = new HashMap<>(), packages = new HashMap<>();
                UserHandle me = Process.myUserHandle();
                try {
                    for (UserHandle u : launcher.getProfiles()) for (LauncherActivityInfo info : launcher.getActivityList(null, u)) {
                        ids.put(HomeActivity.id(info, users), info);
                        if (u.equals(me)) packages.putIfAbsent(info.getComponentName().getPackageName(), info);
                    }
                } catch (RuntimeException unreadable) { /* none */ }
                Set<String> homeApps = new HashSet<>();
                try {
                    for (android.content.pm.ResolveInfo r : service.getPackageManager().queryIntentActivities(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME), 0))
                        if (r.activityInfo != null) homeApps.add(r.activityInfo.packageName);
                } catch (RuntimeException unreadable) { /* none */ }
                List<String> dock = list(homePrefs.getString(HomeActivity.DOCK, "[]")), recent = list(homePrefs.getString(HomeActivity.RECENT, "[]"));
                main.post(() -> { byId = ids; byPackage = packages; homes = homeApps; dockIds = dock; homeRecent = recent; if (attached) refresh(); else later(); });
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
                main.post(() -> { loading.remove(id); if (icon != null) { synchronized (icons) { icons.put(id, icon); } if (attached) refresh(); } });
            });
        } catch (RuntimeException stopped) { loading.remove(id); }
    }
    /** The app now in front, from a window-state event: launchable apps only, not a home screen, the keyboard or the system. */
    private void noteFront(CharSequence pkg, CharSequence cls) {
        if (pkg == null) return;
        String p = pkg.toString(), c = cls == null ? "" : cls.toString();
        boolean own = p.equals(service.getPackageName());
        // 들퍼레 itself counts only for its main screen (not its home screen, voice window or overlays).
        if (own && !c.equals(MainActivity.class.getName())) return;
        if ((!own && homes.contains(p)) || !byPackage.containsKey(p)) return;
        front = p;
        if (!p.equals(seen.peekFirst())) { seen.remove(p); seen.addFirst(p); while (seen.size() > 8) seen.removeLast(); }
    }

    // ── what the bar does ────────────────────────────────────────────

    private final TaskbarView.Actions actions = new TaskbarView.Actions() {
        @Override public void open(TaskbarView.Item item, View from) {
            hideMenu();
            LauncherActivityInfo info = byId.get(item.id()); if (info == null) return;
            int[] at = new int[2]; from.getLocationOnScreen(at);
            Rect bounds = new Rect(at[0], at[1], at[0] + from.getWidth(), at[1] + from.getHeight());
            try { launcher.startMainActivity(info.getComponentName(), info.getUser(), bounds, null); }
            catch (RuntimeException gone) { Toast.makeText(service, item.label() + " 앱을 열지 못했어요.", Toast.LENGTH_SHORT).show(); reload(); }
        }
        @Override public void menu(TaskbarView.Item item, View from) { showMenu(item, from); }
        @Override public void drag(TaskbarView.Item item, float x, float y) { hideMenu(); dragAt(item, x, y); }
        @Override public void drop(TaskbarView.Item item, TaskbarPlan.Side side) {
            endDrag();
            if (TaskbarPlan.first(side) != null) split(item, side, "끌어 놓기");
        }
        @Override public void apps(boolean search) {
            hideMenu();
            // As a home intent, so the home screen's own task comes forward (an explicit start alone would make a second one).
            Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setComponent(new android.content.ComponentName(service, HomeActivity.class));
            try { service.startActivity(home.putExtra(HomeActivity.SHOW, search ? "search" : "apps").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); }
            catch (RuntimeException refused) { service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME); }
        }
        @Override public void nav(int which) {
            hideMenu();
            service.performGlobalAction(which == 0 ? AccessibilityService.GLOBAL_ACTION_RECENTS : which == 1 ? AccessibilityService.GLOBAL_ACTION_HOME : AccessibilityService.GLOBAL_ACTION_BACK);
        }
        /** Home held, as the system's home button does: the phone's assistant (들퍼레 itself when it is the one). */
        @Override public void homeHeld() {
            hideMenu();
            boolean ours = false;
            try { RoleManager roles = service.getSystemService(RoleManager.class); ours = roles != null && roles.isRoleHeld(RoleManager.ROLE_ASSISTANT); } catch (RuntimeException ignored) {}
            try {
                if (!ours) { service.startActivity(new Intent(Intent.ACTION_ASSIST).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); return; }
            } catch (RuntimeException none) { /* 들퍼레 then */ }
            try { service.startActivity(new Intent(service, QuickVoiceActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)); } catch (RuntimeException ignored) {}
        }
    };

    /** Held: a small menu just above the icon, 화면 나누기로 열기 (and gone on any touch outside it or after six seconds). */
    private void showMenu(TaskbarView.Item item, View from) {
        hideMenu();
        boolean dark = dark();
        LinearLayout row = new LinearLayout(service); row.setOrientation(LinearLayout.HORIZONTAL);
        GradientDrawable panel = new GradientDrawable(); panel.setColor(dark ? 0xf2262522 : 0xf7ffffff); panel.setCornerRadius(20 * density);
        if (!dark) panel.setStroke(Math.max(1, dp(0.5f)), 0x1f000000);
        row.setBackground(panel); row.setElevation(10 * density); int pad = dp(4); row.setPadding(pad, pad, pad, pad);
        TextView split = new TextView(service); split.setText("화면 나누기로 열기"); split.setTextSize(14); split.setTypeface(Ui.weight(620));
        split.setTextColor(dark ? 0xffeb9b7d : 0xffb94e2b); split.setPadding(dp(14), dp(9), dp(14), dp(9));
        split.setContentDescription(item.label() + " 화면 나누기로 열기");
        split.setOnClickListener(v -> { hideMenu(); split(item, TaskbarPlan.Side.NONE, "길게 눌러 고르기"); });
        row.addView(split);
        WindowManager.LayoutParams p = new WindowManager.LayoutParams(WindowManager.LayoutParams.WRAP_CONTENT, WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE | WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL
            | WindowManager.LayoutParams.FLAG_WATCH_OUTSIDE_TOUCH | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS, PixelFormat.TRANSLUCENT);
        p.setFitInsetsTypes(0); p.gravity = Gravity.BOTTOM | Gravity.LEFT;
        row.measure(View.MeasureSpec.UNSPECIFIED, View.MeasureSpec.UNSPECIFIED);
        int[] at = new int[2]; from.getLocationOnScreen(at);
        p.x = Math.max(dp(8), at[0] + from.getWidth() / 2 - row.getMeasuredWidth() / 2); p.y = params.height + dp(6);
        row.setOnTouchListener((v, e) -> { if (e.getActionMasked() == MotionEvent.ACTION_OUTSIDE) { hideMenu(); return true; } return false; });
        try { manager.addView(row, p); menu = row; main.postDelayed(closeMenu, 6000); } catch (RuntimeException ignored) {}
    }
    private void hideMenu() {
        main.removeCallbacks(closeMenu);
        View current = menu; menu = null;
        if (current != null) try { manager.removeView(current); } catch (RuntimeException ignored) {}
    }
    private void dragAt(TaskbarView.Item item, float x, float y) {
        Rect screen = screen(); int barTop = screen.height() - params.height;
        if (drop == null) drop = new TaskbarView.Drop(service);
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

    /** One try at opening an app beside the one in front: what was asked, and each step taken (for the log). */
    private static final class Attempt {
        final TaskbarView.Item item; final LauncherActivityInfo info; final TaskbarPlan.Side wanted; final String how, before; final boolean wasSplit;
        final List<String> path = new ArrayList<>();
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
        Rect screen = screen();
        splitting = new Attempt(item, info, wanted, how, front, TaskbarPlan.split(windows(), screen.width(), screen.height()));
        step(splitting, TaskbarPlan.Step.ADJACENT);
    }
    private void step(Attempt a, TaskbarPlan.Step step) {
        switch (step) {
            case ADJACENT -> {
                String ran = launchBeside(a.info, a.before);
                a.path.add((a.wasSplit ? "나뉜 화면에 " : "") + ran);
                if (!ran.startsWith("adjacent")) { finish(a, false, "옆에 열 수 없는 앱"); return; }
                waitForSplit(a, a.item.pkg(), 1800, shown -> step(a, TaskbarPlan.next(TaskbarPlan.Step.ADJACENT, shown, false, a.wasSplit)));
            }
            case TOGGLE -> {
                boolean accepted = service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN);
                a.path.add("toggle " + (accepted ? "받음" : "거절"));
                if (!accepted) { step(a, TaskbarPlan.next(TaskbarPlan.Step.TOGGLE, false, false, a.wasSplit)); return; }
                waitForSplit(a, null, 1500, shown -> step(a, TaskbarPlan.next(TaskbarPlan.Step.TOGGLE, shown, true, a.wasSplit)));
            }
            case PAIR -> {
                // The app now on one half is the one asked for; the one that was in front comes back beside it.
                LauncherActivityInfo previous = a.before == null || a.before.equals(a.item.pkg()) ? null : byPackage.get(a.before);
                if (previous == null) { a.path.add("pair 없음"); finish(a, true, "다른 쪽은 직접 골라요"); return; }
                a.path.add("pair " + launchBeside(previous, a.item.pkg()));
                waitForSplit(a, a.before, 1800, shown -> step(a, TaskbarPlan.next(TaskbarPlan.Step.PAIR, shown, true, a.wasSplit)));
            }
            case SPLIT -> finish(a, true, "");
            case FULL -> finish(a, false, "");
        }
    }
    /** Starts the app beside the one in front and says how: "adjacent", or why not ("other profile", "refused"). */
    private String launchBeside(LauncherActivityInfo info, String front) {
        String pkg = info.getComponentName().getPackageName();
        if (!info.getUser().equals(Process.myUserHandle())) {
            // A work-profile app opens through LauncherApps, which takes no flags: in full screen.
            try { launcher.startMainActivity(info.getComponentName(), info.getUser(), null, null); } catch (RuntimeException ignored) {}
            return "다른 프로필(전체 화면)";
        }
        int flags = splitFlags(pkg, front);
        try {
            service.startActivity(new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER).setComponent(info.getComponentName()).addFlags(flags));
            return "adjacent" + ((flags & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0 ? "+새 창" : "");
        } catch (RuntimeException refused) { return "실행 거절(" + refused.getClass().getSimpleName() + ")"; }
    }
    /** Looks every 200 ms until the screen is split with pkg on it (any app when null), or within ms it is not. */
    private void waitForSplit(Attempt a, String pkg, long ms, java.util.function.Consumer<Boolean> then) {
        long until = SystemClock.uptimeMillis() + ms;
        Runnable[] tick = new Runnable[1];
        tick[0] = () -> {
            if (splitting != a) return;
            boolean shown = splitWith(pkg) != null;
            if (shown || SystemClock.uptimeMillis() >= until) then.accept(shown); else main.postDelayed(tick[0], 200);
        };
        main.postDelayed(tick[0], 300);
    }
    /**
     * The screen split, with pkg's window on it (any app when pkg is null): that window, or null. Only the package of each app
     * window's root is read, never its contents.
     */
    private TaskbarPlan.Win splitWith(String pkg) {
        Rect screen = screen();
        List<TaskbarPlan.Win> all = new ArrayList<>(); TaskbarPlan.Win found = null;
        try {
            for (AccessibilityWindowInfo w : service.getWindows()) {
                if (w.getType() == AccessibilityWindowInfo.TYPE_ACCESSIBILITY_OVERLAY) continue;
                Rect b = new Rect(); w.getBoundsInScreen(b);
                TaskbarPlan.Win win = new TaskbarPlan.Win(w.getType(), b.left, b.top, b.right, b.bottom); all.add(win);
                if (w.getType() != AccessibilityWindowInfo.TYPE_APPLICATION) continue;
                if (pkg == null) { if (found == null) found = win; continue; }
                AccessibilityNodeInfo root = w.getRoot();
                if (root != null && root.getPackageName() != null && pkg.contentEquals(root.getPackageName())) found = win;
            }
        } catch (RuntimeException unavailable) { return null; }
        return found != null && TaskbarPlan.split(all, screen.width(), screen.height()) ? found : null;
    }
    /**
     * The try is over: one line in 폰 조작 기록 (how it was asked, each step, whether the screen split and on which side); when
     * it did not, also in 퍼레가 못 한 일 with the phone's Android version, and the manual way in a toast.
     */
    private void finish(Attempt a, boolean split, String note) {
        if (splitting == a) splitting = null;
        String side = "";
        if (split) {
            TaskbarPlan.Win at = splitWith(a.item.pkg());
            TaskbarPlan.Side landed = TaskbarPlan.sideOf(at, screen().width());
            side = landed == TaskbarPlan.Side.NONE ? "" : " · " + (landed == TaskbarPlan.Side.LEFT ? "왼쪽" : "오른쪽") + "에 열림"
                + (a.wanted == TaskbarPlan.Side.NONE || a.wanted == landed ? "" : "(놓은 쪽은 " + (a.wanted == TaskbarPlan.Side.LEFT ? "왼쪽" : "오른쪽") + ", 쪽은 Android가 정해요)");
        }
        boolean opened = a.path.stream().noneMatch(step -> step.contains("실행 거절"));
        String line = "화면 나누기 · " + a.item.label() + " · " + a.how + " · 길: " + String.join(" → ", a.path) + " · " + (split ? "나뉨" + side : "안 나뉨") + (note.isEmpty() ? "" : " · " + note);
        store.phoneLog("  · " + line);
        if (!split) {
            BuddyMisses.add(service, a.how, "화면 나누기 · " + a.item.label(),
                "길: " + String.join(" → ", a.path) + " · 화면이 나뉘지 않았어요 · Android " + Build.VERSION.RELEASE + " " + Build.MANUFACTURER, "", BuddyMisses.NOT_SHOWN);
            Toast.makeText(service, (opened ? "화면 나누기가 되지 않아 " + a.item.label() + " 앱을 그냥 열었어요." : a.item.label() + " 앱을 열지 못했어요.")
                + " 최근 앱(|||)에서 앱 아이콘을 누르면 ‘분할 화면으로 열기’가 있어요.", Toast.LENGTH_LONG).show();
        }
    }

    /** The bar's state under its switch on 바로 부르기. */
    static String status(Context context, AppStore store) {
        if (!store.taskbar()) return "꺼져 있어요";
        if (!PhoneService.connected()) return "켜짐 · 폰 조작 1단계(접근성)를 켜면 나타나요";
        if (!homeHeld(context)) return "켜짐 · " + Brand.I + " 홈 화면 앱일 때 나와요";
        if (context.getResources().getConfiguration().smallestScreenWidthDp < 600) return "켜짐 · 큰 화면(펼친 폴드·태블릿)에서 나와요";
        return "켜짐 · 다른 앱을 쓸 때 화면 아래에 나와요";
    }

    @Override public void close() {
        main.removeCallbacksAndMessages(null); splitting = null;
        hideMenu(); endDrag(); detach();
        homePrefs.unregisterOnSharedPreferenceChangeListener(homeChanged);
        try { launcher.unregisterCallback(appsChanged); } catch (RuntimeException ignored) {}
        io.shutdownNow();
    }
}
