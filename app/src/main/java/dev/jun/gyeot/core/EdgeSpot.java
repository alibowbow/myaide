package dev.jun.gyeot.core;

import java.util.List;
import java.util.Locale;

/**
 * Where 들퍼레's edge handle sits (준: "손잡이 위치를 기존 삼성엣지 아래로 해줘"): just below Samsung's own Edge panel handle, on its
 * side, a small gap apart, so the two never overlap; above it when there is no room below; and, when Samsung's cannot be seen
 * (not a Samsung phone, its Edge panel off, not shown to accessibility), on the right at about 60% of the screen's height, below
 * where Samsung's sits by default. Always inside the screen, clear of the status bar and the navigation bar. Pure Java.
 */
public final class EdgeSpot {
    private EdgeSpot() {}
    /** Samsung's Edge panel service, whose window the handle is. */
    public static final String SAMSUNG = "com.samsung.android.app.cocktailbarservice";
    /** With no Samsung handle seen: our handle's top at this share of the screen's height, on the right. */
    public static final double DEFAULT_SHARE = 0.6;

    /** A window on the screen as the accessibility service lists it: its app (root package) or title, and its bounds. */
    public record Win(String pkg, String title, int left, int top, int right, int bottom) {}
    /** Samsung's handle: its side and its top and bottom on the screen. */
    public record Handle(boolean right, int top, int bottom) {}

    /**
     * Samsung's Edge handle among the windows: one of the Edge panel service's (by its package, or a title that names it), a
     * narrow strip against the left or the right edge. The narrowest when there are several; null when none.
     */
    public static Handle find(List<Win> windows, int screenWidth) {
        Handle best = null; int narrowest = Integer.MAX_VALUE;
        for (Win w : windows) {
            String title = w.title() == null ? "" : w.title().toLowerCase(Locale.ROOT);
            boolean samsung = SAMSUNG.equals(w.pkg()) || title.contains("cocktail") || title.contains("edge handle") || title.contains("edgehandle");
            int width = w.right() - w.left(), height = w.bottom() - w.top();
            if (!samsung || width <= 0 || height <= 0 || width > screenWidth / 5) continue;
            boolean right = w.right() >= screenWidth - 2, left = w.left() <= 2;
            if (!right && !left) continue;
            if (width < narrowest) { narrowest = width; best = new Handle(right, w.top(), w.bottom()); }
        }
        return best;
    }

    /**
     * Our handle's top (its window, height tall): gap below Samsung's, or above it when there is no room below, or at
     * DEFAULT_SHARE of the screen without one; between the status bar (topInset) and the navigation bar (bottomInset).
     */
    public static int top(Handle samsung, int screenHeight, int topInset, int bottomInset, int height, int gap) {
        int min = Math.max(0, topInset), max = screenHeight - Math.max(0, bottomInset) - height;
        if (max <= min) return min;
        if (samsung == null) return clamp((int) Math.round(screenHeight * DEFAULT_SHARE), min, max);
        int below = samsung.bottom() + gap;
        if (below <= max) return Math.max(min, below);
        int above = samsung.top() - gap - height;
        if (above >= min) return above;
        return clamp(below, min, max);
    }
    private static int clamp(int v, int min, int max) { return Math.max(min, Math.min(v, max)); }
}
