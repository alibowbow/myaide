package dev.jun.gyeot;

import android.accessibilityservice.AccessibilityService;
import android.app.Activity;
import android.app.role.RoleManager;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;
import android.view.accessibility.AccessibilityWindowInfo;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import dev.jun.gyeot.core.TaskbarPlan;
import dev.jun.gyeot.core.TaskbarPlan.Hidden;
import dev.jun.gyeot.core.TaskbarPlan.Side;
import dev.jun.gyeot.core.TaskbarPlan.Step;
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
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.shadow.api.Shadow;
import org.robolectric.shadows.ShadowAccessibilityWindowInfo;
import org.robolectric.shadows.ShadowWindowManagerImpl;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 들퍼레 작업 표시줄 (Taskbar): its place over the system bar, the nav buttons at One UI's 72/81/90%, the accessibility button
 * left free, what fits, when it shows and hides, the drop side and the split steps; the bar drawn light and dark over an app,
 * and its switch as one compact row beside the edge button's on 바로 부르기.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class TaskbarTests {
    // 준's Fold unfolded, portrait: 1856 × 2160 px at 2.25 (360 dpi), a 48dp (108 px) button bar.
    private static final int W = 1856, H = 2160, BAR = 108; private static final float D = 2.25f;
    private static final Win APP = new Win(TaskbarPlan.APPLICATION, 0, 0, W, H - BAR), NAV = new Win(TaskbarPlan.SYSTEM, 0, H - BAR, W, H),
        STATUS = new Win(TaskbarPlan.SYSTEM, 0, 0, W, 90);

    @After public void reset() { HomeActivity.visible = false; }

    private static TaskbarPlan.State state(boolean enabled, boolean home, boolean locked, boolean homeOnTop, int w, int h, float d, List<Win> windows) {
        boolean read = windows.stream().anyMatch(x -> x.type() == TaskbarPlan.APPLICATION);
        return new TaskbarPlan.State(enabled, home, locked, homeOnTop, false, w, h, d, read, windows, 0);
    }
    private static TaskbarPlan.State shown(List<Win> windows) { return state(true, true, false, false, W, H, D, windows); }

    // ── pure parts ───────────────────────────────────────────────────

    @Test public void barLiesExactlyOverTheSystemBar() {
        TaskbarPlan.State s = shown(List.of(STATUS, APP, NAV));
        assertEquals(Hidden.NONE, TaskbarPlan.hidden(s));
        assertEquals("the system bar's own height", BAR, TaskbarPlan.barHeight(s));
        TaskbarPlan.Bar bar = TaskbarPlan.bar(W, H, TaskbarPlan.barHeight(s));
        assertEquals(0, bar.left()); assertEquals(H - BAR, bar.top()); assertEquals(H, bar.bottom());
        // The list could not be read: the window metrics' inset gives the height.
        TaskbarPlan.State blind = new TaskbarPlan.State(true, true, false, false, false, W, H, D, false, List.of(), BAR);
        assertEquals(Hidden.NONE, TaskbarPlan.hidden(blind)); assertEquals(BAR, TaskbarPlan.barHeight(blind));
        // Once our bar covers the left 94%, the system bar is listed by its uncovered right end: same top, same height.
        TaskbarPlan.State covered = shown(List.of(APP, new Win(TaskbarPlan.SYSTEM, Math.round(W * 0.94f), H - BAR, W, H)));
        assertEquals(Hidden.NONE, TaskbarPlan.hidden(covered)); assertEquals(BAR, TaskbarPlan.barHeight(covered));
    }

    @Test public void navButtonsAtOneUisPlacesAndTheAccessibilityButtonFree() {
        int[] nav = TaskbarPlan.nav(W);
        assertEquals(Math.round(W * 0.72f), nav[0]); assertEquals(Math.round(W * 0.81f), nav[1]); assertEquals(Math.round(W * 0.90f), nav[2]);
        // On the 1080 px screenshot: recents ≈780, home ≈875, back ≈970, as Samsung's own taskbar.
        int[] shot = TaskbarPlan.nav(1080);
        assertTrue(Math.abs(shot[0] - 780) <= 10 && Math.abs(shot[1] - 875) <= 10 && Math.abs(shot[2] - 970) <= 10);
        TaskbarPlan.Layout l = TaskbarPlan.layout(1080, 63, 1.31f, 4, 3);
        // The accessibility button sits at ≈1040-1048 of 1080: the bar stops before 94%.
        assertTrue("opaque end " + l.opaqueEnd(), l.opaqueEnd() <= Math.round(1080 * 0.94f) && l.opaqueEnd() < 1040 - 20);
        TaskbarPlan.Layout big = TaskbarPlan.layout(W, BAR, D, 4, 3);
        assertTrue(big.opaqueEnd() <= W * 0.94f + 1);
        assertTrue("back's touch area ends inside the bar", big.nav()[2] + Math.min(big.navWidth(), 2 * (big.opaqueEnd() - big.nav()[2])) / 2 <= big.opaqueEnd());
    }

    @Test public void iconsThatFitAndTheGroupWhereOneUiHasIt() {
        TaskbarPlan.Layout l = TaskbarPlan.layout(W, BAR, D, 4, 3);
        assertEquals(4, l.shownDock()); assertEquals(3, l.shownRecent());
        assertTrue("a divider between the dock and the recent apps", l.divider() > l.dock()[3] && l.divider() < l.recent()[0]);
        assertTrue("icons 32-36dp: " + l.icon() / D, l.icon() >= 32 * D - 1 && l.icon() <= 36 * D + 1);
        assertTrue("slots apart", l.slot() >= 44 * D - 1);
        int groupRight = l.recent()[2] + l.slot() / 2, navLeft = l.nav()[0] - l.navWidth() / 2;
        assertTrue("the apps end before recents: " + groupRight + " < " + navLeft, groupRight < navLeft);
        // All-apps near One UI's 21%.
        assertTrue("all-apps at " + l.apps() / (float) W, Math.abs(l.apps() / (float) W - 0.21f) < 0.04f);
        // Too many: the recent apps go first, then the dock's last ones; nothing crosses into the nav buttons.
        TaskbarPlan.Layout many = TaskbarPlan.layout(W, BAR, D, 12, 3);
        assertEquals(0, many.shownRecent()); assertTrue(many.shownDock() < 12 && many.shownDock() >= 6);
        assertEquals(-1, many.divider());
        assertTrue(many.dock()[many.shownDock() - 1] + many.slot() / 2 < many.nav()[0] - many.navWidth() / 2);
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
        assertEquals(Hidden.PHONE_CONTROL, TaskbarPlan.hidden(new TaskbarPlan.State(true, true, false, false, true, W, H, D, true, normal, 0)));
        List<Win> typing = new ArrayList<>(normal); typing.add(new Win(TaskbarPlan.INPUT_METHOD, 0, 1300, W, H));
        assertEquals(Hidden.KEYBOARD, TaskbarPlan.hidden(shown(typing)));
        assertEquals("an app in full screen (the system bar went away)", Hidden.FULL_SCREEN, TaskbarPlan.hidden(shown(List.of(new Win(1, 0, 0, W, H)))));
        assertEquals("gesture navigation's thin handle", Hidden.GESTURE_BAR, TaskbarPlan.hidden(shown(List.of(APP, new Win(3, 0, H - 45, W, H)))));
        // The shade pulled down is not a bottom bar.
        assertNull(TaskbarPlan.navigationBar(List.of(new Win(3, 0, 0, W, H)), W, H, D));
        // Our own bar's window is never mistaken for the system's (Taskbar leaves overlays out); a floating window is not a bar.
        assertNull(TaskbarPlan.navigationBar(List.of(new Win(3, 1500, 1700, 1800, 2000)), W, H, D));
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
        assertEquals(Side.LEFT, TaskbarPlan.sideOf(left, W)); assertEquals(Side.RIGHT, TaskbarPlan.sideOf(right, W));
    }

    // ── on the service ───────────────────────────────────────────────

    private static AccessibilityWindowInfo window(int type, Rect bounds) {
        AccessibilityWindowInfo w = AccessibilityWindowInfo.obtain();
        ShadowAccessibilityWindowInfo s = Shadow.extract(w); s.setType(type); s.setBoundsInScreen(bounds);
        return w;
    }
    private static List<View> views(WindowManager manager) { return ((ShadowWindowManagerImpl) Shadow.extract(manager)).getViews(); }
    private static void idle() { for (int i = 0; i < 20; i++) { shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(200)); try { Thread.sleep(20); } catch (InterruptedException e) { return; } } }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void overlayOverTheBarHidesOnHomeAndWorksTheButtons() {
        GyeotApp app = (GyeotApp) RuntimeEnvironment.getApplication(); AppStore store = app.store();
        PhoneService service = Robolectric.buildService(PhoneService.class).create().get();
        RoleManager roles = service.getSystemService(RoleManager.class);
        shadowOf(roles).addAvailableRole(RoleManager.ROLE_HOME); shadowOf(roles).addHeldRole(RoleManager.ROLE_HOME);
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
            assertEquals("up to 94%: the accessibility button stays the system's", Math.round(w * 0.94f), p.width);
            assertEquals(android.view.Gravity.BOTTOM | android.view.Gravity.LEFT, p.gravity);
            assertTrue("touches outside its rect pass through", (p.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL) != 0);
            assertTrue("never takes the keyboard", (p.flags & WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE) != 0);
            assertEquals("laid over the bar, not pushed above it", 0, p.getFitInsetsTypes());
            // Recents, home and back work through the global actions.
            for (String[] b : new String[][]{{"최근 앱", "" + AccessibilityService.GLOBAL_ACTION_RECENTS}, {"홈", "" + AccessibilityService.GLOBAL_ACTION_HOME}, {"뒤로", "" + AccessibilityService.GLOBAL_ACTION_BACK}}) {
                View button = described(view, b[0]); assertNotNull(b[0], button);
                assertTrue(button.performClick());
                List<Integer> done = shadowOf(service).getGlobalActionsPerformed();
                assertEquals(b[0], Integer.parseInt(b[1]), (int) done.get(done.size() - 1));
            }
            // The home screen comes: gone; the keyboard: gone; another app again: back.
            HomeActivity.visible = true; bar.refresh();
            assertEquals(Hidden.HOME, bar.hidden());
            assertFalse(views(manager).contains(view));
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
        view.show(dock, recent, W, BAR, H - BAR, dark);
        view.measure(View.MeasureSpec.makeMeasureSpec(view.layout().opaqueEnd(), View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(BAR, View.MeasureSpec.EXACTLY));
        view.layout(0, 0, view.getMeasuredWidth(), BAR);
        return view;
    }
    private static int centerX(View v) { return v.getLeft() + v.getWidth() / 2; }

    @Test @Config(qualifiers="w825dp-h960dp-360dpi") public void barDrawnLightAndDarkOverAnApp() throws Exception {
        TaskbarView light = bar(false), dark = bar(true);
        for (TaskbarView view : new TaskbarView[]{light, dark}) {
            assertEquals(Math.round(W * 0.72f), centerX(described(view, "최근 앱")), 2);
            assertEquals(Math.round(W * 0.81f), centerX(described(view, "홈")), 2);
            assertEquals(Math.round(W * 0.90f), centerX(described(view, "뒤로")), 2);
            assertNotNull(described(view, "모든 앱")); assertNotNull(described(view, "독 앱 0")); assertNotNull(described(view, "최근 앱 2"));
            assertNull("icons only, no labels", find(view, x -> x instanceof TextView));
            ImageView icon = (ImageView) described(view, "독 앱 0");
            float side = (icon.getWidth() - icon.getPaddingLeft() - icon.getPaddingRight()) / D, tall = (icon.getHeight() - icon.getPaddingTop() - icon.getPaddingBottom()) / D;
            assertTrue("a 32-36dp icon: " + side, side >= 32 && side <= 36);
            assertEquals("square, whatever the drawable", side, tall, 0.5f);
            assertEquals("the bar's own height", BAR, view.getHeight());
        }
        // Over a mock app: the system's fallback buttons (59%, 72%, 85%) are covered (85% sits clear of our own glyphs), the
        // accessibility button (97%) is not.
        int panel = 420; Bitmap shot = Bitmap.createBitmap(W, panel * 2, Bitmap.Config.ARGB_8888); Canvas c = new Canvas(shot);
        int[] covered = new int[2], free = new int[2];
        for (int k = 0; k < 2; k++) {
            boolean isDark = k == 1; TaskbarView view = isDark ? dark : light;
            c.save(); c.translate(0, panel * k); c.clipRect(0, 0, W, panel);
            mockApp(c, panel, isDark);
            c.save(); c.translate(0, panel - BAR); view.draw(c); c.restore();
            c.restore();
            covered[k] = shot.getPixel(Math.round(W * 0.85f), panel * k + panel - BAR / 2);
            free[k] = shot.getPixel(Math.round(W * 0.97f) + 14, panel * k + panel - BAR / 2);
            assertEquals("the plain bar's own button is under ours", view.barColor(), covered[k]);
            assertNotEquals("the accessibility button shows", view.barColor(), free[k]);
        }
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, "taskbar_preview.png"))) { shot.compress(Bitmap.CompressFormat.PNG, 100, out); }
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
        assertTrue(state.getText().toString(), state.getText().toString().startsWith("켜짐 · "));
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
