package dev.jun.gyeot;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.ActivityInfo;
import android.content.pm.ApplicationInfo;
import android.content.pm.LauncherActivityInfo;
import android.content.pm.LauncherApps;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.os.Process;
import android.os.SystemClock;
import android.os.UserHandle;
import android.os.UserManager;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import dev.jun.gyeot.core.TaskbarPlan;
import dev.jun.gyeot.core.TaskbarPlan.Hidden;
import dev.jun.gyeot.core.TaskbarPlan.Side;
import dev.jun.gyeot.core.TaskbarPlan.Step;
import dev.jun.gyeot.core.TaskbarPlan.Top;
import dev.jun.gyeot.core.TaskbarPlan.Win;
import java.io.File;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Predicate;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import org.robolectric.android.controller.ActivityController;
import org.robolectric.android.controller.ServiceController;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import org.robolectric.util.ReflectionHelpers;
import org.robolectric.util.ReflectionHelpers.ClassParameter;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 들퍼레 작업 표시줄 (Taskbar): its place over the system bar, up to the system's own nav buttons (never covered), the accessibility button
 * left free, what fits, when it shows and hides (the recents screen and an app opened from home included), the split steps
 * as the controller runs them (started from the home screen activity, the split read from the windows' places alone, no
 * toggle over a split, the log line, the chip to the recents screen), why it is hidden on its settings row and in the phone
 * log, and its switch as one compact row beside the edge button's on 바로 부르기.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TaskbarTests {
    // 준's Fold unfolded, portrait: 1856 × 2160 px at 2.25 (360 dpi), a 48dp (108 px) button bar.
    private static final int W = 1856, H = 2160, BAR = 108; private static final float D = 2.25f;
    private static final Win APP = new Win(TaskbarPlan.APPLICATION, 0, 0, W, H - BAR), NAV = new Win(TaskbarPlan.SYSTEM, 0, H - BAR, W, H),
        STATUS = new Win(TaskbarPlan.SYSTEM, 0, 0, W, 90);

    @org.junit.Before public void fresh() { Taskbar.forgetRole(); }
    @After public void reset() { HomeActivity.visible = false; HomeActivity.shownAt = 0; Taskbar.forgetRole(); }

    private static TaskbarPlan.State state(boolean enabled, boolean home, boolean locked, boolean homeOnTop, int w, int h, float d, List<Win> windows) {
        boolean read = windows.stream().anyMatch(x -> x.type() == TaskbarPlan.APPLICATION);
        return new TaskbarPlan.State(enabled, home, locked, homeOnTop ? Top.HOME : Top.APP, false, w, h, d, read, windows);
    }
    private static TaskbarPlan.State shown(List<Win> windows) { return state(true, true, false, false, W, H, D, windows); }

    // ── pure parts ───────────────────────────────────────────────────

    @Test public void barLiesExactlyOverTheSystemBar() {
        TaskbarPlan.State s = shown(List.of(STATUS, APP, NAV));
        assertEquals(Hidden.NONE, TaskbarPlan.hidden(s));
        assertEquals("the system bar's own height", BAR, TaskbarPlan.barHeight(s));
        TaskbarPlan.Bar bar = TaskbarPlan.bar(W, H, TaskbarPlan.barHeight(s));
        assertEquals(0, bar.left()); assertEquals(H - BAR, bar.top()); assertEquals(H, bar.bottom());
        // The list could not be read: hidden (the metrics' inset still reports a bar a full-screen app or the keyboard hid).
        TaskbarPlan.State blind = new TaskbarPlan.State(true, true, false, Top.APP, false, W, H, D, false, List.of());
        assertEquals(Hidden.NO_WINDOWS, TaskbarPlan.hidden(blind)); assertEquals(0, TaskbarPlan.barHeight(blind));
        // Once our bar covers the left 94%, the system bar is listed by its uncovered right end: same top, same height.
        TaskbarPlan.State covered = shown(List.of(APP, new Win(TaskbarPlan.SYSTEM, Math.round(W * 0.94f), H - BAR, W, H)));
        assertEquals(Hidden.NONE, TaskbarPlan.hidden(covered)); assertEquals(BAR, TaskbarPlan.barHeight(covered));
    }

    /**
     * The system's own recents · home · back are never covered (준, 2026-11-27: "탭버튼 홈버튼 뒤로가기 버튼 위치 모양이 자꾸 바껴":
     * the bar drew its own at One UI's 72/81/90% while the phone's, in the user's theme, sit at 59/72/85%): the bar stops
     * before the leftmost one read from the screen, else before 53% of the width, and draws none of its own.
     */
    @Test public void theSystemsOwnButtonsStayUncovered() {
        TaskbarPlan.Layout read = TaskbarPlan.layout(1080, 63, 1.31f, 4, 3, 615);
        assertEquals("no buttons of its own", 0, read.nav().length);
        assertTrue("it ends before the system's recents (615): " + read.opaqueEnd(), read.opaqueEnd() <= 615 - 4 && read.opaqueEnd() > 560);
        TaskbarPlan.Layout guessed = TaskbarPlan.layout(W, BAR, D, 4, 3);
        assertEquals("not read: 53% of the width", Math.round(W * 0.53f) - Math.round(4 * D), guessed.opaqueEnd());
        assertEquals("a reading that cannot be the buttons is not used", guessed.opaqueEnd(), TaskbarPlan.layout(W, BAR, D, 4, 3, 12).opaqueEnd());
        assertEquals(guessed.opaqueEnd(), TaskbarPlan.layout(W, BAR, D, 4, 3, W + 40).opaqueEnd());
        assertEquals("no fade: the system bar shows through", 0, guessed.fade());
    }

    @Test public void iconsThatFitAndTheGroupWhereOneUiHasIt() {
        TaskbarPlan.Layout l = TaskbarPlan.layout(W, BAR, D, 4, 3);
        assertEquals(4, l.shownDock()); assertEquals(3, l.shownRecent());
        assertTrue("a divider between the dock and the recent apps", l.divider() > l.dock()[3] && l.divider() < l.recent()[0]);
        assertTrue("icons 32-36dp: " + l.icon() / D, l.icon() >= 32 * D - 1 && l.icon() <= 36 * D + 1);
        assertTrue("slots apart", l.slot() >= 44 * D - 1);
        int groupRight = l.recent()[2] + l.slot() / 2;
        assertTrue("the apps end before the system's buttons: " + groupRight + " < " + l.opaqueEnd(), groupRight < l.opaqueEnd());
        // The group in the middle of the room left of the system's buttons.
        int groupLeft = l.apps() - l.slot() / 2;
        assertTrue("centered: " + groupLeft + " and " + (l.opaqueEnd() - groupRight), Math.abs(groupLeft - (l.opaqueEnd() - groupRight)) <= Math.round(2 * D) + 1);
        // Too many: the recent apps go first, then the dock's last ones; nothing crosses into the nav buttons.
        TaskbarPlan.Layout many = TaskbarPlan.layout(W, BAR, D, 12, 3);
        assertEquals(0, many.shownRecent()); assertTrue(many.shownDock() < 12 && many.shownDock() >= 6);
        assertEquals(-1, many.divider());
        assertTrue(many.dock()[many.shownDock() - 1] + many.slot() / 2 < many.opaqueEnd());
        assertTrue(many.apps() - many.slot() / 2 >= 0);
        // A tablet in landscape: wider slots, still clear of the buttons.
        TaskbarPlan.Layout wide = TaskbarPlan.layout(2960, 108, 2.25f, 6, 3);
        assertEquals(6, wide.shownDock()); assertEquals(3, wide.shownRecent()); assertTrue(wide.slot() <= 56 * 2.25f + 1);
        // No dock, no recents: only all-apps, and no divider.
        TaskbarPlan.Layout none = TaskbarPlan.layout(W, BAR, D, 0, 0);
        assertEquals(-1, none.divider()); assertEquals(0, none.shownDock());
    }

    @Test public void showsOnlyOverAnotherAppOnALargeScreenWithAButtonBar() {
        List<Win> normal = List.of(STATUS, APP, NAV);
        assertEquals(Hidden.OFF, TaskbarPlan.hidden(state(false, true, false, false, W, H, D, normal)));
        assertEquals("Samsung's own taskbar is there when One UI Home is the home app", Hidden.NOT_HOME_APP, TaskbarPlan.hidden(state(true, false, false, false, W, H, D, normal)));
        assertEquals(Hidden.LOCKED, TaskbarPlan.hidden(state(true, true, true, false, W, H, D, normal)));
        assertEquals("never on 들퍼레's home screen", Hidden.HOME, TaskbarPlan.hidden(state(true, true, false, true, W, H, D, normal)));
        // A phone (or the Fold's cover screen) in portrait: a wide bar, but a small screen.
        assertEquals(Hidden.SMALL_SCREEN, TaskbarPlan.hidden(state(true, true, false, false, 1080, 2340, 2.625f, List.of(new Win(1, 0, 0, 1080, 2214), new Win(3, 0, 2214, 1080, 2340)))));
        assertEquals(Hidden.PHONE_CONTROL, TaskbarPlan.hidden(new TaskbarPlan.State(true, true, false, Top.APP, true, W, H, D, true, normal)));
        assertEquals("the recents screen", Hidden.RECENTS, TaskbarPlan.hidden(new TaskbarPlan.State(true, true, false, Top.RECENTS, false, W, H, D, true, normal)));
        List<Win> typing = new ArrayList<>(normal); typing.add(new Win(TaskbarPlan.INPUT_METHOD, 0, 1300, W, H));
        assertEquals(Hidden.KEYBOARD, TaskbarPlan.hidden(shown(typing)));
        assertEquals("an app in full screen (the system bar went away)", Hidden.FULL_SCREEN, TaskbarPlan.hidden(shown(List.of(new Win(1, 0, 0, W, H)))));
        assertEquals("gesture navigation's thin handle", Hidden.GESTURE_BAR, TaskbarPlan.hidden(shown(List.of(APP, new Win(3, 0, H - 45, W, H)))));
        // The shade pulled down is not a bottom bar.
        assertNull(TaskbarPlan.navigationBar(List.of(new Win(3, 0, 0, W, H)), W, H, D));
        // Our own bar's window is never mistaken for the system's (Taskbar leaves overlays out); a floating window is not a bar.
        assertNull(TaskbarPlan.navigationBar(List.of(new Win(3, 1500, 1700, 1800, 2000)), W, H, D));
    }

    @Test public void whatIsInFrontByWhatCameLast() {
        assertEquals("the home screen, shown last", Top.HOME, TaskbarPlan.top(true, 100, 0, 50));
        assertEquals("an app opened from home before the home's onStop", Top.APP, TaskbarPlan.top(true, 100, 0, 150));
        assertEquals("the recents screen", Top.RECENTS, TaskbarPlan.top(false, 100, 200, 150));
        assertEquals("the recents screen over the home screen", Top.RECENTS, TaskbarPlan.top(true, 100, 200, 150));
        assertEquals("back to an app from recents", Top.APP, TaskbarPlan.top(false, 100, 200, 250));
        assertEquals("the home screen after recents", Top.HOME, TaskbarPlan.top(true, 300, 200, 250));
        assertEquals("nothing seen yet", Top.APP, TaskbarPlan.top(false, 0, 0, 0));
    }

    @Test public void dropSideAndSplitSteps() {
        int barTop = H - BAR, lift = Math.round(24 * D);
        assertEquals("let go on the bar: nothing", Side.NONE, TaskbarPlan.side(300, H - 40, W, barTop, lift));
        assertEquals("just above the bar is not far enough", Side.NONE, TaskbarPlan.side(300, barTop - 10, W, barTop, lift));
        assertEquals(Side.LEFT, TaskbarPlan.side(300, 900, W, barTop, lift));
        assertEquals(Side.RIGHT, TaskbarPlan.side(1500, 900, W, barTop, lift));
        assertNull(TaskbarPlan.first(Side.NONE));
        assertEquals(Step.ADJACENT, TaskbarPlan.first(Side.LEFT));
        // Launch-adjacent splits the screen (Android 13-15's side stage): done.
        assertEquals(Step.SPLIT, TaskbarPlan.next(Step.ADJACENT, true, false, false));
        // It did not: ask the system to split, then bring the previous app beside it.
        assertEquals(Step.TOGGLE, TaskbarPlan.next(Step.ADJACENT, false, false, false));
        assertEquals(Step.PAIR, TaskbarPlan.next(Step.TOGGLE, true, true, false));
        assertEquals("AOSP answers the toggle with false: full screen, logged", Step.FULL, TaskbarPlan.next(Step.TOGGLE, false, false, false));
        assertEquals(Step.FULL, TaskbarPlan.next(Step.TOGGLE, false, true, false));
        assertEquals(Step.SPLIT, TaskbarPlan.next(Step.PAIR, true, true, false));
        assertEquals(Step.FULL, TaskbarPlan.next(Step.PAIR, false, true, false));
        assertEquals("already split: no toggle that would undo it", Step.FULL, TaskbarPlan.next(Step.ADJACENT, false, false, true));
        // Looked at again right before the toggle: a late split is kept, a split the user had is never toggled.
        assertTrue(TaskbarPlan.mayToggle(false, false));
        assertFalse("split after the wait", TaskbarPlan.mayToggle(true, false));
        assertFalse("split before", TaskbarPlan.mayToggle(false, true));
        // The flags: beside the current app; a second window only for the same app.
        int other = Taskbar.splitFlags("com.kakao.talk", "com.android.chrome"), same = Taskbar.splitFlags("com.android.chrome", "com.android.chrome");
        assertTrue((other & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0 && (other & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
        assertEquals(0, other & Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
        assertTrue((same & Intent.FLAG_ACTIVITY_MULTIPLE_TASK) != 0 && (same & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0);
    }

    @Test public void splitIsTwoAppsSharingTheScreen() {
        Win left = new Win(1, 0, 90, W / 2 - 8, H - BAR), right = new Win(1, W / 2 + 8, 90, W, H - BAR);
        assertTrue(TaskbarPlan.split(List.of(STATUS, left, right, NAV), W, H));
        assertTrue("a divider by itself", TaskbarPlan.split(List.of(APP, new Win(TaskbarPlan.DIVIDER, W / 2 - 8, 0, W / 2 + 8, H)), W, H));
        assertFalse("one app", TaskbarPlan.split(List.of(STATUS, APP, NAV), W, H));
        assertFalse("a pop-up window over a full app", TaskbarPlan.split(List.of(APP, new Win(1, 400, 400, 900, 1000)), W, H));
        Win top = new Win(1, 0, 0, W, H / 2), bottom = new Win(1, 0, H / 2, W, H - BAR);
        assertTrue("one above the other", TaskbarPlan.split(List.of(top, bottom), W, H));
        assertEquals(Side.LEFT, TaskbarPlan.sideOf(left, W, H)); assertEquals(Side.RIGHT, TaskbarPlan.sideOf(right, W, H));
        // Stacked (AOSP's portrait split): by y, not by x (both halves are centered in x).
        assertEquals(Side.TOP, TaskbarPlan.sideOf(top, W, H)); assertEquals(Side.BOTTOM, TaskbarPlan.sideOf(bottom, W, H));
        assertEquals("위", TaskbarPlan.words(Side.TOP)); assertEquals("오른쪽", TaskbarPlan.words(Side.RIGHT));
    }

    // ── on the service ───────────────────────────────────────────────

    private static AccessibilityWindowInfo window(int type, Rect bounds) {
        AccessibilityWindowInfo w = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo s = Shadow.extract(w); s.setType(type); s.setBoundsInScreen(bounds);
        return w;
    }
    private static List<View> views(WindowManager manager) { return ((ShadowWindowManagerImpl) Shadow.extract(manager)).getViews(); }
    private static void idle() { for (int i = 0; i < 20; i++) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)); try { Thread.sleep(20); } catch (InterruptedException e) { return; } } }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void overlayOverTheBarHidesOnHomeAndLeavesTheButtons() {
        GyeotApp app = (GyeotApp) RuntimeEnvironment.getApplication(); AppStore store = app.store();
        PhoneService service = Robolectric.buildService(PhoneService.class).create().get();
        RoleManager roles = service.getSystemService(RoleManager.class);
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME); shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME); Taskbar.forgetRole();
        Rect screen = service.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds();
        int w = screen.width(), h = screen.height();
        List<AccessibilityWindowInfo> windows = new ArrayList<>(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, w, h - BAR)),
            window(AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, h - BAR, w, h))));
        shadowOf(service).setWindows(windows);
        Taskbar bar = new Taskbar(service, store);
        try {
            bar.refresh(); idle();
            assertEquals(Hidden.NONE, bar.hidden());
            WindowManager manager = service.getSystemService(WindowManager.class);
            List<View> added = views(manager);
            TaskbarView view = (TaskbarView) added.stream().filter(v -> v instanceof TaskbarView).findFirst().orElse(null);
            assertNotNull("the bar is on screen", view);
            WindowManager.LayoutParams p = (WindowManager.LayoutParams) view.getLayoutParams();
            assertEquals(WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY, p.type);
            assertEquals("the system bar's height", BAR, p.height);
            assertEquals("up to the system's own buttons (53% when they cannot be read): they stay the system's", TaskbarPlan.layout(w, BAR, service.getResources().getDisplayMetrics().density, 0, 0).opaqueEnd(), p.width);
            assertEquals(android.view.Gravity.BOTTOM | android.view.Gravity.LEFT, p.gravity);
            assertTrue("touches outside its rect pass through", (p.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0);
            assertTrue("never takes the keyboard", (p.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
            assertEquals("laid over the bar, not pushed above it", 0, p.getFitInsetsTypes());
            // Recents, home and back are the system's own: none drawn here.
            for (String b : new String[]{"최근 앱", "홈", "뒤로"}) assertNull(b + " is the system's", described(view, b));
            // The home screen comes: gone; the keyboard: gone; another app again: back.
            HomeActivity.visible = true; bar.refresh();
            assertEquals(Hidden.HOME, bar.hidden());
            assertFalse(views(manager).contains(view));
            // In front (resumed), it stays gone even when an app's window event comes after it (a screen recorder's countdown).
            HomeActivity.visible = false; HomeActivity.resumed = true; bar.refresh();
            assertEquals("home resumed: no bar, whatever came last", Hidden.HOME, bar.hidden());
            HomeActivity.resumed = false; HomeActivity.visible = true; bar.refresh();
            HomeActivity.visible = false; windows.add(window(AccessibilityWindowInfo.TYPE_INPUT_METHOD, new Rect(0, h / 2, w, h))); shadowOf(service).setWindows(windows); bar.refresh();
            assertEquals(Hidden.KEYBOARD, bar.hidden());
            windows.remove(2); shadowOf(service).setWindows(windows); bar.refresh();
            assertEquals(Hidden.NONE, bar.hidden()); assertTrue(views(manager).contains(view));
            // Full screen: the system bar is no longer listed.
            shadowOf(service).setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, w, h)))); bar.refresh();
            assertEquals(Hidden.FULL_SCREEN, bar.hidden());
            store.settings.edit().putBoolean("taskbar", false).apply(); bar.refresh();
            assertEquals(Hidden.OFF, bar.hidden());
        } finally { bar.close(); store.settings.edit().remove("taskbar").apply(); }
    }

    // ── the controller: front app, recents, split, why hidden ────────

    private static final String CHROME = "com.android.chrome", KAKAO = "com.kakao.talk", LAUNCHER = "com.sec.android.app.launcher";

    /** A launchable app as LauncherApps lists it (its hidden constructors, as the system builds it). */
    private static LauncherActivityInfo app(Context context, String pkg, String cls, String label) throws Exception {
        ActivityInfo ai = new ActivityInfo(); ai.packageName = pkg; ai.name = cls; ai.nonLocalizedLabel = label; ai.exported = true; ai.enabled = true;
        ai.applicationInfo = new ApplicationInfo(); ai.applicationInfo.packageName = pkg; ai.applicationInfo.nonLocalizedLabel = label; ai.applicationInfo.enabled = true;
        Class<?> statesClass = Class.forName("android.content.pm.IncrementalStatesInfo"), internalClass = Class.forName("android.content.pm.LauncherActivityInfoInternal");
        Object states = ReflectionHelpers.callConstructor(statesClass, ClassParameter.from(boolean.class, false), ClassParameter.from(float.class, 1f), ClassParameter.from(long.class, 0L));
        Object internal = ReflectionHelpers.callConstructor(internalClass, ClassParameter.from(ActivityInfo.class, ai), ClassParameter.from(statesClass, states), ClassParameter.from(UserHandle.class, Process.myUserHandle()));
        return ReflectionHelpers.callConstructor(LauncherActivityInfo.class, ClassParameter.from(Context.class, context), ClassParameter.from(internalClass, internal));
    }
    private static AccessibilityWindowInfo window(int type, Rect bounds, int id, String title) {
        AccessibilityWindowInfo w = window(type, bounds);
        ShadowAccessibilityWindowInfo shadow = Shadow.extract(w); shadow.setId(id);
        if (title != null) shadow.setTitle(title);
        return w;
    }
    /** An app's window came to the front, as Android reports it (package and activity class; the window's id). */
    private static AccessibilityEvent cameUp(String pkg, String cls, int window) {
        AccessibilityEvent e = AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED);
        e.setPackageName(pkg); e.setClassName(cls);
        ReflectionHelpers.callInstanceMethod(e, "setWindowId", ClassParameter.from(int.class, window));
        return e;
    }
    private static void step(long ms) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms)); }

    /** The phone as on 준's Fold: 들퍼레 the home app, Chrome in front over the system bar, KakaoTalk on the dock. */
    private static final class Phone implements AutoCloseable {
        final GyeotApp app = (GyeotApp) RuntimeEnvironment.getApplication(); final AppStore store = app.store();
        final ServiceController<PhoneService> controller = Robolectric.buildService(PhoneService.class).create();
        final PhoneService service = controller.get();
        final WindowManager manager = service.getSystemService(WindowManager.class);
        final int w, h;
        ActivityController<HomeActivity> home;
        Taskbar bar;
        Phone(boolean withHome) throws Exception {
            RoleManager roles = service.getSystemService(RoleManager.class);
            shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME); shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME); Taskbar.forgetRole();
            Rect screen = manager.getCurrentWindowMetrics().getBounds(); w = screen.width(); h = screen.height();
            LauncherApps launcher = service.getSystemService(LauncherApps.class);
            LauncherActivityInfo chrome = app(service, CHROME, CHROME + ".Main", "Chrome"), kakao = app(service, KAKAO, KAKAO + ".Main", "카카오톡");
            shadowOf(launcher).addActivity(Process.myUserHandle(), chrome); shadowOf(launcher).addActivity(Process.myUserHandle(), kakao);
            // One UI Home: a home screen with no icon of its own, so what it shows while 들퍼레 is home is the recents screen.
            ComponentName oneUi = new ComponentName(LAUNCHER, LAUNCHER + ".activities.LauncherActivity");
            shadowOf(app.getPackageManager()).addActivityIfNotPresent(oneUi);
            IntentFilter homeFilter = new IntentFilter(Intent.ACTION_MAIN); homeFilter.addCategory(Intent.CATEGORY_HOME); homeFilter.addCategory(Intent.CATEGORY_DEFAULT);
            shadowOf(app.getPackageManager()).addIntentFilterForActivity(oneUi, homeFilter);
            String kakaoId = HomeActivity.id(kakao, service.getSystemService(UserManager.class));
            app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().putString(HomeActivity.DOCK, "[\"" + kakaoId + "\"]").commit();
            windows(normal());
            if (withHome) { home = Robolectric.buildActivity(HomeActivity.class).setup(); home.pause().stop(); }
            else ReflectionHelpers.setStaticField(HomeActivity.class, "alive", new java.lang.ref.WeakReference<HomeActivity>(null));
            while (shadowOf(app).getNextStartedActivity() != null) { /* drained */ }
            bar = new Taskbar(service, store);
            idle(); bar.windowsChanged(cameUp(CHROME, CHROME + ".Main", 3)); idle();
        }
        List<AccessibilityWindowInfo> normal() {
            return new ArrayList<>(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, w, h - BAR), 3, "Chrome"),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, h - BAR, w, h), 9, null)));
        }
        /** Chrome on the left and title on the right, split. */
        List<AccessibilityWindowInfo> split(String title) {
            return new ArrayList<>(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, w / 2 - 8, h - BAR), 3, "Chrome"),
                window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(w / 2 + 8, 0, w, h - BAR), 4, title),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, h - BAR, w, h), 9, null)));
        }
        void windows(List<AccessibilityWindowInfo> list) { shadowOf(service).setWindows(list); }
        TaskbarView view() { return (TaskbarView) views(manager).stream().filter(v -> v instanceof TaskbarView).findFirst().orElse(null); }
        /** A pill over the bar (the menu, the chip) by its words. */
        TextView pill(String words) {
            for (View v : views(manager)) { View t = find(v, x -> x instanceof TextView tv && tv.getText().toString().equals(words)); if (t != null) return (TextView) t; }
            return null;
        }
        /** Holds KakaoTalk on the bar and picks 화면 나누기로 열기. */
        void splitKakao() {
            View icon = described(view(), "카카오톡"); assertNotNull("KakaoTalk on the dock", icon);
            assertTrue(icon.performLongClick());
            TextView item = pill("화면 나누기로 열기"); assertNotNull("the held icon's menu", item);
            assertTrue(item.performClick());
        }
        List<Integer> actions() { return shadowOf(service).getGlobalActionsPerformed(); }
        @Override public void close() {
            if (bar != null) bar.close();
            if (home != null) home.destroy();
            store.settings.edit().remove("taskbar").apply();
            app.getSharedPreferences(HomeActivity.PREFS, Context.MODE_PRIVATE).edit().remove(HomeActivity.DOCK).commit();
            store.clearPhoneLog(); BuddyMisses.clear(app);
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void splitStartsFromTheHomeScreenAndIsLogged() throws Exception {
        try (Phone phone = new Phone(true)) {
            assertEquals(Hidden.NONE, phone.bar.hidden());
            phone.splitKakao();
            Intent started = shadowOf(phone.app).getNextStartedActivity();
            assertNotNull("KakaoTalk started", started);
            assertEquals(new ComponentName(KAKAO, KAKAO + ".Main"), started.getComponent());
            assertTrue("beside the app in front", (started.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0 && (started.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
            assertEquals("another app: no second task", 0, started.getFlags() & Intent.FLAG_ACTIVITY_MULTIPLE_TASK);
            // Android splits the screen and KakaoTalk takes the right half.
            phone.windows(phone.split("카카오톡")); phone.bar.windowsChanged(cameUp(KAKAO, KAKAO + ".Main", 4));
            idle();
            String log = phone.store.phoneLogText();
            assertTrue(log, log.contains("화면 나누기 · 카카오톡 · 길게 눌러 고르기 · 길: adjacent(홈에서) · 나뉨 · 오른쪽에 열림 · API 35"));
            assertFalse("no toggle once split", phone.actions().contains(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN));
            assertTrue("nothing missed", BuddyMisses.list(phone.app).isEmpty());
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void aColdStartWithNoReadableWindowStillCountsAsSplit() throws Exception {
        try (Phone phone = new Phone(true)) {
            phone.splitKakao();
            // The new half is still its starting window: no title, no event from the app yet; the places alone show the split.
            step(400); phone.windows(phone.split(null));
            step(2000); idle();
            String log = phone.store.phoneLogText();
            assertTrue(log, log.contains("길: adjacent(홈에서) · 나뉨 · API 35"));
            assertFalse("no toggle that would undo it", phone.actions().contains(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN));
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void noSplitTriesTheToggleThenOffersTheRecentsScreen() throws Exception {
        try (Phone phone = new Phone(true)) {
            phone.splitKakao();
            idle(); idle();
            String log = phone.store.phoneLogText();
            assertTrue(log, log.contains("화면 나누기 · 카카오톡 · 길게 눌러 고르기 · 길: adjacent(홈에서) → toggle 받음 · 안 나뉨 · API 35"));
            assertTrue(phone.actions().contains(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN));
            BuddyMisses.Miss miss = BuddyMisses.list(phone.app).get(0);
            assertEquals("화면 나누기 · 카카오톡", miss.what()); assertEquals(BuddyMisses.NOT_SHOWN, miss.kind());
            assertTrue(miss.why(), miss.why().contains("Android ") && miss.why().contains("API 35"));
            // One tap to the recents screen, where One UI splits by hand.
            TextView chip = phone.pill("안 나뉘었어요 · 최근 앱에서 나누기"); assertNotNull("the chip over the bar", chip);
            View pill = chip.getRootView(); WindowManager.LayoutParams at = (WindowManager.LayoutParams) pill.getLayoutParams();
            float density = phone.service.getResources().getDisplayMetrics().density;
            assertTrue("a small pill, not a big card: " + pill.getMeasuredHeight() / density + "dp", pill.getMeasuredHeight() <= 52 * density);
            assertEquals("over the recents button", Math.round(phone.w * TaskbarPlan.RECENTS), at.x + pill.getMeasuredWidth() / 2, 2);
            assertEquals("just above the bar", BAR + Math.round(6 * density), at.y);
            // Drawn over a mock app with the bar under it (the preview).
            int panel = 420; Bitmap shot = Bitmap.createBitmap(phone.w, panel, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(shot);
            mockApp(c, panel, false);
            TaskbarView bar = phone.view(); c.save(); c.translate(0, panel - BAR); bar.draw(c); c.restore();
            pill.layout(0, 0, pill.getMeasuredWidth(), pill.getMeasuredHeight());
            c.save(); c.translate(at.x, panel - at.y - pill.getMeasuredHeight()); pill.draw(c); c.restore();
            File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
            try (FileOutputStream out = new FileOutputStream(new File(dir, "taskbar_chip.png"))) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); }
            assertTrue(chip.performClick());
            assertEquals(AccessibilityService.GLOBAL_ACTION_RECENTS, (int) phone.actions().get(phone.actions().size() - 1));
            assertNull("gone once used", phone.pill("안 나뉘었어요 · 최근 앱에서 나누기"));
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void onAScreenSplitBeforeItNeedsTheAppAndNeverToggles() throws Exception {
        try (Phone phone = new Phone(true)) {
            phone.windows(phone.split("메모")); phone.bar.refresh();
            phone.splitKakao();
            idle();
            String log = phone.store.phoneLogText();
            assertTrue(log, log.contains("길: 나뉜 화면에 adjacent(홈에서) · 안 나뉨"));
            assertFalse("the user's split is not toggled away", phone.actions().contains(AccessibilityService.GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN));
            // Again, and this time KakaoTalk takes the right half.
            phone.store.clearPhoneLog(); phone.splitKakao();
            phone.windows(phone.split("카카오톡")); phone.bar.windowsChanged(cameUp(KAKAO, KAKAO + ".Main", 4)); idle();
            log = phone.store.phoneLogText();
            assertTrue(log, log.contains("길: 나뉜 화면에 adjacent(홈에서) · 나뉨 · 오른쪽에 열림"));
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void withNoHomeActivityTheServiceStartsItAndSaysSo() throws Exception {
        try (Phone phone = new Phone(false)) {
            phone.splitKakao();
            Intent started = shadowOf(phone.app).getNextStartedActivity();
            assertNotNull(started); assertTrue((started.getFlags() & Intent.FLAG_ACTIVITY_LAUNCH_ADJACENT) != 0);
            phone.windows(phone.split("카카오톡")); idle();
            String log = phone.store.phoneLogText();
            assertTrue(log, log.contains("길: adjacent(서비스, 홈 없음: Android 14+는 무시해요) · 나뉨"));
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void recentsScreenAndAnAppOpenedFromHome() throws Exception {
        try (Phone phone = new Phone(false)) {
            assertEquals(Hidden.NONE, phone.bar.hidden());
            // One UI's recents screen comes up over Chrome: hidden.
            List<AccessibilityWindowInfo> recents = new ArrayList<>(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, phone.w, phone.h - BAR), 7, null),
                window(AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, phone.h - BAR, phone.w, phone.h), 9, null)));
            phone.windows(recents); phone.bar.windowsChanged(cameUp(LAUNCHER, "com.android.quickstep.RecentsActivity", 7)); step(200);
            assertEquals(Hidden.RECENTS, phone.bar.hidden()); assertNull(phone.view());
            // Back to Chrome with no event from it: the recents window is gone, so the bar is back.
            phone.windows(phone.normal()); phone.bar.windowsChanged(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)); step(200);
            assertEquals(Hidden.NONE, phone.bar.hidden()); assertNotNull(phone.view());
            // The home screen comes (onStart): hidden; an app opened from it shows the bar before the home's onStop.
            HomeActivity.visible = true; HomeActivity.shownAt = SystemClock.uptimeMillis(); phone.bar.later(); step(200);
            assertEquals(Hidden.HOME, phone.bar.hidden());
            step(50); phone.bar.windowsChanged(cameUp(KAKAO, KAKAO + ".Main", 4)); step(200);
            assertEquals("in front already, while the home is still visible", Hidden.NONE, phone.bar.hidden());
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void keepsUpWhileTheStoreKeepsChanging() throws Exception {
        try (Phone phone = new Phone(false)) {
            assertEquals(Hidden.NONE, phone.bar.hidden());
            HomeActivity.visible = true; HomeActivity.shownAt = SystemClock.uptimeMillis() + 1;
            // A voice reply streams: requests every 50 ms. The first one's look still comes 120 ms later.
            for (int i = 0; i < 6; i++) { phone.bar.later(); phone.store.changed(); phone.bar.storeChanged(); step(50); }
            assertEquals(Hidden.HOME, phone.bar.hidden());
        }
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void nothingComesBackAfterClose() throws Exception {
        GyeotApp app = (GyeotApp) RuntimeEnvironment.getApplication(); AppStore store = app.store();
        PhoneService service = Robolectric.buildService(PhoneService.class).create().get();
        RoleManager roles = service.getSystemService(RoleManager.class);
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME); shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME); Taskbar.forgetRole();
        WindowManager manager = service.getSystemService(WindowManager.class);
        Rect screen = manager.getCurrentWindowMetrics().getBounds();
        shadowOf(service).setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, screen.width(), screen.height() - BAR)),
            window(AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, screen.height() - BAR, screen.width(), screen.height()))));
        // Closed while its apps are still loading: the late answer, a later() and a refresh() put nothing on screen.
        Taskbar bar = new Taskbar(service, store); bar.close();
        idle(); bar.later(); bar.refresh(); idle();
        assertTrue(views(manager).stream().noneMatch(v -> v instanceof TaskbarView));
    }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void whyItIsHiddenOnTheRowAndInThePhoneLog() throws Exception {
        GyeotApp app = (GyeotApp) RuntimeEnvironment.getApplication(); AppStore store = app.store();
        ServiceController<PhoneService> controller = Robolectric.buildService(PhoneService.class).create();
        PhoneService service = controller.get();
        RoleManager roles = service.getSystemService(RoleManager.class);
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME); shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME); Taskbar.forgetRole();
        Rect screen = service.getSystemService(WindowManager.class).getCurrentWindowMetrics().getBounds(); int w = screen.width(), h = screen.height();
        try {
            // One UI lists no bar window (or an app is in full screen): every app reads as full screen. Said, not silent.
            shadowOf(service).setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, w, h))));
            service.onServiceConnected(); idle();
            assertEquals(Hidden.FULL_SCREEN, PhoneService.taskbarHidden());
            assertEquals("숨김 · 아래 바 창을 못 찾았어요(전체 화면으로 봄)", Taskbar.status(service, store));
            String log = store.phoneLogText();
            assertTrue(log, log.contains("작업 표시줄 · 숨김: 아래 바 창을 못 찾았어요(전체 화면으로 봄) · 화면 " + w + "×" + h + " · 아래 시스템 창 없음 · 창 1개"));
            // Gesture navigation's thin handle.
            shadowOf(service).setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, w, h - 45)), window(AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, h - 45, w, h))));
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)); idle();
            assertEquals(Hidden.GESTURE_BAR, PhoneService.taskbarHidden());
            assertTrue(Taskbar.status(service, store), Taskbar.status(service, store).startsWith("숨김 · 제스처 내비게이션"));
            assertTrue(store.phoneLogText().contains("아래 시스템 창 0," + (h - 45) + "–" + w + "," + h));
            // The button bar: shown, and its height logged once.
            shadowOf(service).setWindows(List.of(window(AccessibilityWindowInfo.TYPE_APPLICATION, new Rect(0, 0, w, h - BAR)), window(AccessibilityWindowInfo.TYPE_SYSTEM, new Rect(0, h - BAR, w, h))));
            service.onAccessibilityEvent(AccessibilityEvent.obtain(AccessibilityEvent.TYPE_WINDOWS_CHANGED)); idle();
            assertEquals(Hidden.NONE, PhoneService.taskbarHidden());
            assertEquals("켜짐 · 지금 화면 아래에 나와 있어요", Taskbar.status(service, store));
            assertTrue(store.phoneLogText().contains("작업 표시줄 · 나타남 · 높이 " + BAR + "px"));
        } finally { controller.destroy(); store.clearPhoneLog(); }
    }

    // ── the bar's look ───────────────────────────────────────────────

    private static View find(View view, Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static View described(View root, String words) { return find(root, x -> x.getContentDescription() != null && x.getContentDescription().toString().equals(words)); }
    /** An app icon's look: a rounded square in the app's colour with a white mark. */
    private static Drawable fakeIcon(int color) {
        GradientDrawable back = new GradientDrawable(); back.setColor(color); back.setCornerRadius(20);
        GradientDrawable mark = new GradientDrawable(); mark.setShape(GradientDrawable.OVAL); mark.setColor(0xffffffff);
        android.graphics.drawable.LayerDrawable d = new android.graphics.drawable.LayerDrawable(new Drawable[]{back, mark});
        d.setLayerInset(1, 24, 24, 24, 24); return d;
    }
    private static final TaskbarView.Actions NOTHING = new TaskbarView.Actions() {
        @Override public void open(TaskbarView.Item item, View from) {}
        @Override public void menu(TaskbarView.Item item, View from) {}
        @Override public void drag(TaskbarView.Item item, float x, float y) {}
        @Override public void drop(TaskbarView.Item item, Side side) {}
        @Override public void apps(boolean search) {}
        @Override public void nav(int which) {}
        @Override public void homeHeld() {}
    };
    private static TaskbarView bar(boolean dark) {
        TaskbarView view = new TaskbarView(RuntimeEnvironment.getApplication(), NOTHING);
        int[] colors = {0xff3c7be0, 0xff22a35a, 0xfff2b230, 0xffe0503c, 0xff8a5cd6, 0xff1aa3b8, 0xff444444};
        List<TaskbarView.Item> dock = new ArrayList<>(), recent = new ArrayList<>();
        for (int i = 0; i < 4; i++) dock.add(new TaskbarView.Item("d" + i, "a.d" + i, "독 앱 " + i, fakeIcon(colors[i])));
        for (int i = 0; i < 3; i++) recent.add(new TaskbarView.Item("r" + i, "a.r" + i, "최근 앱 " + i, fakeIcon(colors[4 + i])));
        view.show(dock, recent, W, BAR, H - BAR, dark, D);
        view.measure(View.MeasureSpec.makeMeasureSpec(view.layout().opaqueEnd(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(BAR, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), BAR);
        return view;
    }
    private static int centerX(View v) { return v.getLeft() + v.getWidth() / 2; }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void barDrawnLightAndDarkOverAnApp() throws Exception {
        TaskbarView light = bar(false), dark = bar(true);
        for (TaskbarView view : new TaskbarView[]{light, dark}) {
            for (String b : new String[]{"최근 앱", "홈", "뒤로"}) assertNull(b + " is the system's", described(view, b));
            assertNotNull(described(view, "모든 앱")); assertNotNull(described(view, "독 앱 0")); assertNotNull(described(view, "최근 앱 2"));
            assertNull("icons only, no labels", find(view, x -> x instanceof TextView));
            ImageView icon = (ImageView) described(view, "독 앱 0");
            float side = (icon.getWidth() - icon.getPaddingLeft() - icon.getPaddingRight()) / D, tall = (icon.getHeight() - icon.getPaddingTop() - icon.getPaddingBottom()) / D;
            assertTrue("a 32-36dp icon: " + side, side >= 32 && side <= 36);
            assertEquals("square, whatever the drawable", side, tall, 0.5f);
            assertEquals("the bar's own height", BAR, view.getHeight());
        }
        // Over a mock app: the system's fallback buttons (59%, 72%, 85%) and the accessibility button (97%) all show; under the
        // apps the system bar itself shows through (no fill of its own).
        int panel = 420; Bitmap shot = Bitmap.createBitmap(W, panel * 2, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(shot);
        int[] covered = new int[2], free = new int[2];
        for (int k = 0; k < 2; k++) {
            boolean isDark = k == 1; TaskbarView view = isDark ? dark : light;
            c.save(); c.translate(0, panel * k); c.clipRect(0, 0, W, panel);
            mockApp(c, panel, isDark);
            c.save(); c.translate(0, panel - BAR); view.draw(c); c.restore();
            c.restore();
            int page = isDark ? 0xff202124 : 0xffe6e9e2;
            covered[k] = shot.getPixel(Math.round(W * 0.59f), panel * k + panel - BAR / 2);
            free[k] = shot.getPixel(Math.round(W * 0.97f) + 14, panel * k + panel - BAR / 2);
            assertNotEquals("the system's recents shows (59%)", page, covered[k]);
            assertNotEquals("the accessibility button shows", page, free[k]);
            assertEquals("between the apps, the system bar itself (no fill of ours)", page, shot.getPixel(view.layout().opaqueEnd() - 3, panel * k + panel - 6));
        }
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "taskbar_preview.png"))) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); }
        // An icon dragged up into the screen: one target over everything above the bar (Android picks the side), the icon
        // under the finger.
        Bitmap screen = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888); Canvas s = new Canvas(screen);
        mockApp(s, H, false);
        s.save(); s.translate(0, H - BAR); light.draw(s); s.restore();
        TaskbarView.Drop drop = new TaskbarView.Drop(RuntimeEnvironment.getApplication(), D);
        drop.at(fakeIcon(0xfff2b230), Math.round(34 * D), 560, 1180, TaskbarPlan.side(560, 1180, W, H - BAR, Math.round(24 * D)), H - BAR);
        assertTrue("lifted into the screen: the target shows", drop.armed());
        drop.measure(View.MeasureSpec.makeMeasureSpec(W, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(H, View.MeasureSpec.EXACTLY));
        drop.layout(0, 0, W, H); drop.draw(s);
        // The target spans both halves (no half promised): let go on the left, the right half is tinted as well.
        assertEquals(screen.getPixel(Math.round(W * 0.25f), 300), screen.getPixel(Math.round(W * 0.75f), 300));
        assertNotEquals("the page's own card colour, tinted", 0xfffbfcf7, screen.getPixel(Math.round(W * 0.75f), 300));
        try (FileOutputStream out = new FileOutputStream(new File(dir, "taskbar_drop.png"))) { screen.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    /** A browser-like page with Samsung's plain bar under it: its buttons at the fallback places and the accessibility button. */
    private static void mockApp(Canvas c, int h, boolean dark) {
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        int page = dark ? 0xff202124 : 0xffe6e9e2, card = dark ? 0xff2d2e31 : 0xfffbfcf7, ink = dark ? 0xff5f6368 : 0xffc9ccc4;
        c.drawColor(page);
        p.setColor(card); c.drawRoundRect(new RectF(160, 30, W / 2f - 20, h - BAR - 30), 36, 36, p); c.drawRoundRect(new RectF(W / 2f + 20, 30, W - 160, h - BAR - 30), 36, 36, p);
        p.setColor(ink); for (int i = 0; i < 4; i++) { c.drawRoundRect(new RectF(220, 80 + i * 56, W / 2f - 120 - i * 60, 104 + i * 56), 12, 12, p); c.drawRoundRect(new RectF(W / 2f + 80, 80 + i * 56, W - 260 - i * 40, 104 + i * 56), 12, 12, p); }
        // The plain system bar: the app's colour, its buttons where Samsung puts them without One UI Home.
        p.setColor(page); c.drawRect(0, h - BAR, W, h, p);
        p.setColor(dark ? 0xff9aa0a6 : 0xff5f6368);
        for (float x : new float[]{0.59f, 0.72f, 0.85f}) c.drawCircle(W * x, h - BAR / 2f, 20, p);
        float ax = W * 0.97f + 14, ay = h - BAR / 2f;
        c.drawCircle(ax, ay - 22, 7, p); p.setStrokeWidth(6); c.drawLine(ax - 22, ay - 8, ax + 22, ay - 8, p); c.drawLine(ax, ay - 8, ax, ay + 10, p);
        c.drawLine(ax, ay + 10, ax - 12, ay + 28, p); c.drawLine(ax, ay + 10, ax + 12, ay + 28, p);
    }

    // ── its switch ───────────────────────────────────────────────────

    @Test @Config(qualifiers="w840dp-h900dp") public void switchRowBesideTheEdgeButtonsAndCompact() throws Exception {
        Activity a = Robolectric.buildActivity(MainActivity.class, new Intent().putExtra(MainActivity.SETTINGS_PAGE, "quick")).setup().get();
        settle(a);
        View decor = a.getWindow().getDecorView();
        View toggle = find(decor, x -> x instanceof Switch s && x.isShown() && s.getText().toString().equals("작업 표시줄"));
        View edge = find(decor, x -> x instanceof Switch s && x.isShown() && s.getText().toString().equals("화면 가장자리 버튼"));
        assertNotNull("작업 표시줄 switch", toggle); assertNotNull(edge);
        View row = (View) toggle.getParent(), edgeRow = (View) edge.getParent();
        assertSame("in the same list as the edge button", edgeRow.getParent(), row.getParent());
        float density = a.getResources().getDisplayMetrics().density;
        assertTrue("one list row, not a big card: " + row.getHeight() / density + "dp", row.getHeight() <= 96 * density);
        assertTrue("on by default", ((Switch) toggle).isChecked());
        TextView state = (TextView) ((ViewGroup) row).getChildAt(1);
        assertEquals("not the home app here: said first, no accessibility prompt", "켜짐 · " + dev.jun.gyeot.core.Brand.I + " 홈 화면 앱일 때 나와요", state.getText().toString());
        assertNull("no 폰 조작 prompt where the bar cannot appear", find(decor, x -> x instanceof TextView t && x.isShown() && t.getText().toString().equals("폰 조작 1단계 켜러 가기")));
        assertTrue(row.performClick()); settle(a);
        AppStore store = ((GyeotApp) a.getApplication()).store();
        assertFalse("the row turns it off", store.taskbar());
        View again = find(a.getWindow().getDecorView(), x -> x instanceof Switch s && x.isShown() && s.getText().toString().equals("작업 표시줄"));
        TextView now = (TextView) ((ViewGroup) again.getParent()).getChildAt(1);
        assertEquals("꺼져 있어요", now.getText().toString());
        shot(a, "taskbar_setting");
        store.settings.edit().remove("taskbar").apply();
    }
    private static void settle(Activity a) {
        View d = a.getWindow().getDecorView(); var m = d.getResources().getDisplayMetrics();
        for (int i = 0; i < 3; i++) {
            d.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.EXACTLY));
            d.layout(0, 0, d.getMeasuredWidth(), d.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
        }
    }
    private static void shot(Activity a, String name) throws Exception {
        View decor = a.getWindow().getDecorView(); settle(a);
        Bitmap b = Bitmap.createBitmap(decor.getWidth(), decor.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(Ui.BG); decor.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
}
