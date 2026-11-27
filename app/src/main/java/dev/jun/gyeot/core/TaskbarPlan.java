package dev.jun.gyeot.core;

import java.util.List;

/**
 * 들퍼레 작업 표시줄 (준: "그게 있어야 화면 나누기로 앱 멀티태스크 실행이 가능하거든"): Samsung draws its taskbar only with One UI
 * Home; with 들퍼레 as the home app it falls back to a plain bar whose app area is empty and whose buttons move elsewhere.
 * While another app is in front, 들퍼레 lays its own bar exactly over that one: an all-apps button, the dock's apps, a thin
 * divider, a few recent apps, and recents · home · back where One UI keeps them (72%, 81%, 90%). The far right (the
 * accessibility button) stays the system's own. Plain numbers only, so every rule here is tested off the phone.
 */
public final class TaskbarPlan {
    private TaskbarPlan() {}
    /** The nav buttons' centers, as fractions of the screen's width (One UI's own taskbar, measured on 준's Fold). */
    public static final float RECENTS = 0.72f, HOME = 0.81f, BACK = 0.90f;
    /** Each nav button's touch width, and where the bar's opaque part stops: the accessibility button (≈96%) stays uncovered. */
    public static final float NAV_WIDTH = 0.09f, OPAQUE_END = 0.94f;
    /** The app group's center (One UI's: all-apps at 21%, recent apps up to 64%). */
    public static final float GROUP_CENTER = 0.425f;
    /** Recent apps shown at most, as One UI shows. */
    public static final int MOST_RECENT = 3;
    /** AccessibilityWindowInfo's window types, as numbers (the same values Android uses). */
    public static final int APPLICATION = 1, INPUT_METHOD = 2, SYSTEM = 3, OVERLAY = 4, DIVIDER = 5;

    /**
     * Why the bar is not shown; NONE: it is. RECENTS: the recents screen (or another home screen) is in front. NO_WINDOWS: the
     * window list could not be read, so the keyboard and full screen cannot be told apart: hidden rather than guessed.
     */
    public enum Hidden { NONE, OFF, NOT_HOME_APP, LOCKED, HOME, RECENTS, SMALL_SCREEN, PHONE_CONTROL, NO_WINDOWS, KEYBOARD, FULL_SCREEN, GESTURE_BAR, NO_BOTTOM_BAR }
    /** Where a dragged icon was let go (LEFT, RIGHT) or which part of the screen an app's window took (also TOP, BOTTOM). */
    public enum Side { NONE, LEFT, RIGHT, TOP, BOTTOM }
    /** What is in front: 들퍼레's home screen, the recents screen (or another home), or an app. */
    public enum Top { HOME, RECENTS, APP }
    /** One step of opening an app beside the one in front. */
    public enum Step { ADJACENT, TOGGLE, PAIR, SPLIT, FULL }

    /** A window on screen, as the accessibility service lists it: its type and its bounds in screen pixels. */
    public record Win(int type, int left, int top, int right, int bottom) {
        public int width() { return right - left; }
        public int height() { return bottom - top; }
        long area() { return (long) Math.max(0, width()) * Math.max(0, height()); }
    }
    /** The bar's rectangle in screen pixels: from the left edge to OPAQUE_END, over the system bar's height. */
    public record Bar(int left, int top, int right, int bottom) {
        public int width() { return right - left; }
        public int height() { return bottom - top; }
    }
    /**
     * What the bar needs to know to show or hide. top: what is in front (Top); windowsRead: the window list could be read (it
     * holds at least one app window).
     */
    public record State(boolean enabled, boolean defaultHome, boolean locked, Top top, boolean phoneControl,
                        int screenWidth, int screenHeight, float density, boolean windowsRead, List<Win> windows) {}

    /**
     * What is in front, from when each last came: 들퍼레's home screen (homeAt: its onStart or its own window event, while it
     * is visible), the recents screen or another home (recentsAt) and an app (appAt: a launchable app's window event). The
     * newest wins, so the bar shows as soon as an app opened from the home screen is in front (the home's onStop can come
     * seconds later) and hides on the recents screen, which is no 들퍼레 window and no app.
     */
    public static Top top(boolean homeVisible, long homeAt, long recentsAt, long appAt) {
        if (homeVisible && homeAt >= appAt && homeAt >= recentsAt) return Top.HOME;
        if (recentsAt > appAt && (!homeVisible || recentsAt > homeAt)) return Top.RECENTS;
        return Top.APP;
    }

    /** The system's navigation bar among the windows: a system window along the bottom edge, reaching the right edge. */
    public static Win navigationBar(List<Win> windows, int width, int height, float density) {
        if (windows == null) return null;
        Win best = null;
        for (Win w : windows) {
            if (w.type() != SYSTEM || w.bottom() < height - Math.round(2 * density) || w.right() < width - Math.round(2 * density)) continue;
            // Not the shade (near full screen) nor a side bar (phone landscape): a strip in the bottom fifth.
            if (w.top() < height * 0.8f || w.height() <= 0) continue;
            if (best == null || w.top() < best.top()) best = w;
        }
        return best;
    }
    public static boolean keyboard(List<Win> windows) {
        if (windows != null) for (Win w : windows) if (w.type() == INPUT_METHOD && w.height() > 0) return true;
        return false;
    }
    /** The bar's height: the navigation bar's own (0 when the list could not be read or holds none). */
    public static int barHeight(State s) {
        if (!s.windowsRead()) return 0;
        Win nav = navigationBar(s.windows(), s.screenWidth(), s.screenHeight(), s.density());
        return nav == null ? 0 : nav.height();
    }
    /** NONE when the bar shows; else the first reason it does not. */
    public static Hidden hidden(State s) {
        if (!s.enabled()) return Hidden.OFF;
        if (!s.defaultHome()) return Hidden.NOT_HOME_APP;
        if (s.locked()) return Hidden.LOCKED;
        if (s.top() == Top.HOME) return Hidden.HOME;
        if (s.top() == Top.RECENTS) return Hidden.RECENTS;
        if (Math.min(s.screenWidth(), s.screenHeight()) / s.density() < 600) return Hidden.SMALL_SCREEN;
        if (s.phoneControl()) return Hidden.PHONE_CONTROL;
        // Without the list the keyboard and full screen cannot be seen (the metrics' inset reports a bar a full-screen app
        // hid): hidden, and said so on the settings row and in the phone log.
        if (!s.windowsRead()) return Hidden.NO_WINDOWS;
        if (keyboard(s.windows())) return Hidden.KEYBOARD;
        // The list was read and holds no bar along the bottom: an app in full screen hid it.
        if (navigationBar(s.windows(), s.screenWidth(), s.screenHeight(), s.density()) == null) return Hidden.FULL_SCREEN;
        int h = barHeight(s);
        if (h <= 0) return Hidden.NO_BOTTOM_BAR;
        // Gesture navigation leaves only a thin handle: buttons would steal its swipes.
        if (h < 32 * s.density()) return Hidden.GESTURE_BAR;
        if (h > s.screenHeight() * 0.15f) return Hidden.NO_BOTTOM_BAR;
        return Hidden.NONE;
    }
    public static Bar bar(int width, int height, int barHeight) {
        return new Bar(0, height - barHeight, Math.round(width * OPAQUE_END), height);
    }
    /** The centers of recents, home and back. */
    public static int[] nav(int width) { return new int[]{Math.round(width * RECENTS), Math.round(width * HOME), Math.round(width * BACK)}; }

    /**
     * Where everything sits on a bar width wide (the screen's width), in pixels from its left edge. dock and recent: how
     * many shown (what fits: the recent apps go first, then the dock's last ones).
     */
    public record Layout(int slot, int icon, int apps, int[] dock, int divider, int[] recent, int[] nav, int navWidth, int opaqueEnd, int fade) {
        public int shownDock() { return dock.length; }
        public int shownRecent() { return recent.length; }
    }
    public static Layout layout(int width, int barHeight, float density, int dockCount, int recentCount) {
        int slot = Math.max(Math.round(44 * density), Math.min(Math.round(56 * density), Math.round(width * 0.06f)));
        int icon = Math.min(Math.round(34 * density), Math.round(barHeight * 0.78f));
        int[] nav = nav(width); int navWidth = Math.round(width * NAV_WIDTH);
        int gap = slot / 4, margin = Math.round(8 * density);
        int limitLeft = margin, limitRight = nav[0] - navWidth / 2 - margin;
        int d = Math.max(0, dockCount), r = Math.max(0, Math.min(MOST_RECENT, recentCount));
        while (groupWidth(slot, gap, d, r) > limitRight - limitLeft && (d > 0 || r > 0)) { if (r > 0) r--; else d--; }
        int group = groupWidth(slot, gap, d, r);
        int left = Math.round(width * GROUP_CENTER) - group / 2;
        left = Math.max(limitLeft, Math.min(left, limitRight - group));
        int[] dock = new int[d], recent = new int[r];
        for (int i = 0; i < d; i++) dock[i] = left + slot * (1 + i) + slot / 2;
        int divider = d > 0 && r > 0 ? left + slot * (1 + d) + gap / 2 : -1;
        int recentLeft = left + slot * (1 + d) + (d > 0 && r > 0 ? gap : 0);
        for (int j = 0; j < r; j++) recent[j] = recentLeft + slot * j + slot / 2;
        return new Layout(slot, icon, left + slot / 2, dock, divider, recent, nav, navWidth, Math.round(width * OPAQUE_END), Math.round(12 * density));
    }
    private static int groupWidth(int slot, int gap, int dock, int recent) { return slot * (1 + dock + recent) + (dock > 0 && recent > 0 ? gap : 0); }

    /** Where a dragged icon was let go: lifted at least lift above the bar into the screen, on the left or right half. */
    public static Side side(float x, float y, int width, int barTop, int lift) {
        if (y > barTop - lift) return Side.NONE;
        return x < width / 2f ? Side.LEFT : Side.RIGHT;
    }

    /**
     * Two app windows side by side (or one above the other) that share the screen: split screen. A small floating window
     * (pop-up view) does not count. A divider window counts by itself.
     */
    public static boolean split(List<Win> windows, int width, int height) {
        if (windows == null) return false;
        long screen = (long) width * height; java.util.List<Win> big = new java.util.ArrayList<>();
        for (Win w : windows) {
            if (w.type() == DIVIDER) return true;
            if (w.type() == APPLICATION && w.area() >= screen / 5 && w.area() < screen * 9 / 10) big.add(w);
        }
        for (int i = 0; i < big.size(); i++) for (int j = i + 1; j < big.size(); j++) {
            Win a = big.get(i), b = big.get(j);
            long overlap = (long) Math.max(0, Math.min(a.right(), b.right()) - Math.max(a.left(), b.left())) * Math.max(0, Math.min(a.bottom(), b.bottom()) - Math.max(a.top(), b.top()));
            if (overlap * 10 < Math.min(a.area(), b.area())) return true;
        }
        return false;
    }
    /**
     * Which part of the screen an app's window took: TOP or BOTTOM when it spans the width (split one above the other, as
     * AOSP does in portrait), else LEFT or RIGHT by its center.
     */
    public static Side sideOf(Win w, int width, int height) {
        if (w == null || w.width() <= 0 || w.height() <= 0) return Side.NONE;
        if (w.width() >= width * 0.8f && w.height() < height * 0.75f) return (w.top() + w.bottom()) / 2f < height / 2f ? Side.TOP : Side.BOTTOM;
        return (w.left() + w.right()) / 2f < width / 2f ? Side.LEFT : Side.RIGHT;
    }
    public static String words(Side side) {
        return switch (side) { case LEFT -> "왼쪽"; case RIGHT -> "오른쪽"; case TOP -> "위"; case BOTTOM -> "아래"; case NONE -> ""; };
    }

    /**
     * Opening an app beside the one in front, step by step (Taskbar runs them and logs each). ADJACENT: the app is started
     * with FLAG_ACTIVITY_LAUNCH_ADJACENT from a live 들퍼레 activity (the home screen, stopped behind the app). Shell's
     * StageCoordinator makes the split side stage the launch-adjacent root, so such a start enters split screen with the app
     * in front on the other side. The source activity matters: from Android 14 on, ActivityStarter drops the flag when a
     * start has no source activity (a start from the accessibility service itself; android14/15/16 computeLaunchingTaskFlags,
     * Android 16 logs "Launch adjacent ignored due to missing source activity"), so the service is only the fallback when no
     * home activity is alive. When the screen did not split, TOGGLE asks the system to split it
     * (GLOBAL_ACTION_TOGGLE_SPLIT_SCREEN: AOSP 13 to 15 answer false, a maker may handle it; never sent while the screen is
     * split already, see mayToggle) and PAIR brings the app that was in front back beside it. SPLIT: done; FULL: the app
     * opened in full screen (logged, and the user is offered the recents screen, where One UI splits by hand).
     */
    public static Step next(Step ran, boolean splitShown, boolean toggleAccepted, boolean wasSplit) {
        return switch (ran) {
            // Already split and still not beside: toggling would undo the split the user has, so stop there.
            case ADJACENT -> splitShown ? Step.SPLIT : wasSplit ? Step.FULL : Step.TOGGLE;
            case TOGGLE -> toggleAccepted && splitShown ? Step.PAIR : Step.FULL;
            case PAIR -> splitShown ? Step.SPLIT : Step.FULL;
            case SPLIT, FULL -> ran;
        };
    }
    /**
     * Checked again right before TOGGLE: a split that showed up late (a cold start whose window came after the wait) must
     * not be toggled away, nor a split the user had before.
     */
    public static boolean mayToggle(boolean splitNow, boolean wasSplit) { return !splitNow && !wasSplit; }
    /** The first step for a drop: nothing when it was let go back on the bar. */
    public static Step first(Side side) { return side == Side.NONE ? null : Step.ADJACENT; }
    /** A second window of the same app (two browsers side by side) needs a task of its own; another app moves its own task. */
    public static boolean ownTask(String app, String front) { return app != null && app.equals(front); }
}
