package dev.jun.gyeot;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.PixelFormat;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.InsetDrawable;
import android.graphics.drawable.RippleDrawable;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewConfiguration;
import android.widget.FrameLayout;
import android.widget.ImageView;
import dev.jun.gyeot.core.TaskbarPlan;
import java.util.List;

/**
 * The 작업 표시줄's look (Taskbar puts it over the system bar): on the bar's own neutral colour (light or dark with the phone),
 * an all-apps button, the dock's apps, a thin divider, the recent apps, then recents · home · back where One UI keeps them
 * (TaskbarPlan). Icons only: no labels, no counts. An app icon dragged up into the screen, or held, opens that app beside the
 * one in front. Its right end fades out before the accessibility button, which stays the system's. Sizes follow the density
 * each show is given (a display-size change, the Fold's other screen).
 */
final class TaskbarView extends FrameLayout {
    /** One app on the bar: its id (HomeActivity.id), package, name and icon. */
    record Item(String id, String pkg, String label, Drawable icon) {}
    interface Actions {
        void open(Item item, View from);
        /** Held: the small menu (화면 나누기로 열기). */
        void menu(Item item, View from);
        /** Dragged at this screen point (raw pixels). */
        void drag(Item item, float x, float y);
        /** Let go: on a half of the screen (split there) or back on the bar (NONE). */
        void drop(Item item, TaskbarPlan.Side side);
        /** The all-apps button: 들퍼레's Launchpad, with its search when held. */
        void apps(boolean search);
        /** 0 recents, 1 home, 2 back. */
        void nav(int which);
        void homeHeld();
    }
    static final int LIGHT = 0xffeceee9, DARK = 0xff1c1d1b, LIGHT_GLYPH = 0xff30312e, DARK_GLYPH = 0xffe8e7e2;
    private final Actions actions;
    private float density;
    private int slop;
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    /** The right end's fade, made again only when its place or colour changes (not on every draw). */
    private LinearGradient fadeShader; private int fadeFrom = -1, fadeColor;
    private boolean dark;
    private int opaqueEnd, fade, screenWidth, barTop, barHeight;
    private TaskbarPlan.Layout layout;

    TaskbarView(Context context, Actions actions) {
        super(context);
        this.actions = actions;
        density = context.getResources().getDisplayMetrics().density;
        slop = ViewConfiguration.get(context).getScaledTouchSlop();
        setWillNotDraw(false);
        setClipChildren(false);
    }
    private int dp(float v) { return Math.round(v * density); }
    TaskbarPlan.Layout layout() { return layout; }
    private boolean touching;
    /** A finger is on the bar (or dragging an icon off it): the bar is not rebuilt under it. */
    boolean touching() { return touching; }
    @Override protected void onDetachedFromWindow() { touching = false; super.onDetachedFromWindow(); }
    @Override public boolean dispatchTouchEvent(MotionEvent e) {
        int action = e.getActionMasked();
        if (action == MotionEvent.ACTION_DOWN) touching = true;
        boolean handled = super.dispatchTouchEvent(e);
        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL) touching = false;
        return handled;
    }
    int barColor() { return dark ? DARK : LIGHT; }

    /**
     * Lays the bar out for a screen screenWidth wide whose system bar is barHeight tall and starts at barTop (screen pixels),
     * at this density. What does not fit is left out: recent apps first, then the dock's last ones.
     */
    void show(List<Item> dock, List<Item> recent, int screenWidth, int barHeight, int barTop, boolean dark, float density) {
        this.dark = dark; this.screenWidth = screenWidth; this.barTop = barTop; this.barHeight = barHeight;
        this.density = density; slop = ViewConfiguration.get(getContext()).getScaledTouchSlop();
        layout = TaskbarPlan.layout(screenWidth, barHeight, density, dock.size(), recent.size());
        opaqueEnd = layout.opaqueEnd(); fade = layout.fade();
        removeAllViews();
        int glyph = dark ? DARK_GLYPH : LIGHT_GLYPH;
        ImageView apps = button(new Glyph(Glyph.APPS, glyph, density), "모든 앱", layout.slot(), layout.apps());
        apps.setOnClickListener(v -> { tick(v); actions.apps(false); });
        apps.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); actions.apps(true); return true; });
        for (int i = 0; i < layout.shownDock(); i++) app(dock.get(i), layout.dock()[i]);
        if (layout.divider() >= 0) {
            View line = new View(getContext()); line.setBackgroundColor(dark ? 0x40ffffff : 0x33000000);
            LayoutParams p = new LayoutParams(Math.max(1, dp(1)), Math.round(barHeight * 0.42f)); p.leftMargin = layout.divider(); p.topMargin = Math.round(barHeight * 0.29f);
            addView(line, p);
        }
        for (int j = 0; j < layout.shownRecent(); j++) app(recent.get(j), layout.recent()[j]);
        String[] names = {"최근 앱", "홈", "뒤로"}; int[] kinds = {Glyph.RECENTS, Glyph.HOME, Glyph.BACK};
        for (int k = 0; k < 3; k++) {
            int which = k;
            ImageView nav = button(new Glyph(kinds[k], glyph, density), names[k], Math.min(layout.navWidth(), 2 * (opaqueEnd - layout.nav()[k])), layout.nav()[k]);
            nav.setOnClickListener(v -> { tick(v); actions.nav(which); });
            if (k == 1) nav.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); actions.homeHeld(); return true; });
        }
        invalidate();
    }
    private void tick(View v) { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); }
    private ImageView button(Drawable image, String name, int width, int center) {
        ImageView view = new ImageView(getContext());
        view.setImageDrawable(image); view.setScaleType(ImageView.ScaleType.CENTER); view.setContentDescription(name);
        // A soft round press, inside the slot (no full-height block).
        GradientDrawable mask = new GradientDrawable(); mask.setColor(0xffffffff); mask.setCornerRadius(dp(14));
        int insetX = Math.max(0, (width - dp(44)) / 2);
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x22000000), null, new InsetDrawable(mask, insetX, dp(4), insetX, dp(4))));
        LayoutParams p = new LayoutParams(width, LayoutParams.MATCH_PARENT); p.leftMargin = center - width / 2;
        addView(view, p);
        return view;
    }
    private void app(Item item, int center) {
        ImageView view = new ImageView(getContext());
        Drawable icon = item.icon() != null ? item.icon() : placeholder();
        view.setImageDrawable(icon); view.setScaleType(ImageView.ScaleType.FIT_CENTER); view.setContentDescription(item.label());
        int side = layout.icon(), slot = layout.slot();
        GradientDrawable mask = new GradientDrawable(); mask.setColor(0xffffffff); mask.setCornerRadius(dp(12));
        view.setBackground(new RippleDrawable(ColorStateList.valueOf(dark ? 0x33ffffff : 0x22000000), null, new InsetDrawable(mask, Math.max(0, (slot - side) / 2 - dp(4)), dp(3), Math.max(0, (slot - side) / 2 - dp(4)), dp(3))));
        LayoutParams p = new LayoutParams(slot, LayoutParams.MATCH_PARENT); p.leftMargin = center - slot / 2;
        // A square of the icon's size in the middle of its slot, whatever the drawable's own shape.
        view.setPadding((slot - side) / 2, Math.max(0, (barHeight - side) / 2), (slot - side) / 2, Math.max(0, barHeight - side - (barHeight - side) / 2));
        view.setTag(item);
        view.setOnClickListener(v -> { tick(v); actions.open(item, v); });
        view.setOnLongClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS); actions.menu(item, v); return true; });
        view.setOnTouchListener(new Dragger(item));
        addView(view, p);
    }
    private Drawable placeholder() {
        GradientDrawable d = new GradientDrawable(); d.setColor(dark ? 0x26ffffff : 0x1a000000); d.setCornerRadius(dp(10)); d.setSize(layout.icon(), layout.icon());
        return d;
    }
    /** An icon lifted out of the bar: upward past the touch slop it becomes a drag (no click, no menu) until let go. */
    private final class Dragger implements OnTouchListener {
        private final Item item; private float downX, downY; private boolean dragging;
        Dragger(Item item) { this.item = item; }
        @Override public boolean onTouch(View v, MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN -> { downX = e.getRawX(); downY = e.getRawY(); dragging = false; return false; }
                case MotionEvent.ACTION_MOVE -> {
                    float up = downY - e.getRawY();
                    if (!dragging && up > slop * 2 && up > Math.abs(e.getRawX() - downX)) {
                        dragging = true; v.cancelLongPress(); v.setPressed(false);
                        if (v.getParent() != null) v.getParent().requestDisallowInterceptTouchEvent(true);
                    }
                    if (dragging) { actions.drag(item, e.getRawX(), e.getRawY()); return true; }
                    return false;
                }
                case MotionEvent.ACTION_UP -> {
                    if (!dragging) return false;
                    dragging = false; actions.drop(item, TaskbarPlan.side(e.getRawX(), e.getRawY(), screenWidth, barTop, dp(24))); return true;
                }
                case MotionEvent.ACTION_CANCEL -> { if (!dragging) return false; dragging = false; actions.drop(item, TaskbarPlan.Side.NONE); return true; }
                default -> { return dragging; }
            }
        }
    }

    @Override protected void onDraw(Canvas canvas) {
        int color = barColor(), h = getHeight(), solid = Math.max(0, Math.min(getWidth(), opaqueEnd) - fade);
        fill.setShader(null); fill.setColor(color);
        canvas.drawRect(0, 0, solid, h, fill);
        // The right end fades into the system's own bar, where its accessibility button stays.
        if (fade > 0) {
            if (fadeShader == null || fadeFrom != solid || fadeColor != color) {
                fadeShader = new LinearGradient(solid, 0, solid + fade, 0, color, color & 0x00ffffff, Shader.TileMode.CLAMP); fadeFrom = solid; fadeColor = color;
            }
            fill.setShader(fadeShader);
            canvas.drawRect(solid, 0, solid + fade, h, fill);
            fill.setShader(null);
        }
    }

    /** Clean default glyphs (the user's own Good Lock images cannot be read): One UI's ||| ▢ ‹ and a 3×3 grid for all apps. */
    static final class Glyph extends Drawable {
        static final int APPS = 0, RECENTS = 1, HOME = 2, BACK = 3;
        private final int kind; private final float d;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path path = new Path();
        private final RectF box = new RectF();
        Glyph(int kind, int color, float density) {
            this.kind = kind; d = density;
            paint.setColor(color); paint.setStrokeWidth(1.9f * density); paint.setStrokeCap(Paint.Cap.ROUND); paint.setStrokeJoin(Paint.Join.ROUND);
        }
        @Override public int getIntrinsicWidth() { return Math.round(24 * d); }
        @Override public int getIntrinsicHeight() { return Math.round(24 * d); }
        @Override public void draw(Canvas c) {
            float cx = getBounds().exactCenterX(), cy = getBounds().exactCenterY();
            switch (kind) {
                case APPS -> {
                    paint.setStyle(Paint.Style.FILL);
                    for (int i = -1; i <= 1; i++) for (int j = -1; j <= 1; j++) c.drawCircle(cx + i * 6 * d, cy + j * 6 * d, 1.9f * d, paint);
                }
                case RECENTS -> {
                    paint.setStyle(Paint.Style.STROKE);
                    for (int i = -1; i <= 1; i++) c.drawLine(cx + i * 5.5f * d, cy - 7 * d, cx + i * 5.5f * d, cy + 7 * d, paint);
                }
                case HOME -> {
                    paint.setStyle(Paint.Style.STROKE);
                    float r = 7.5f * d; box.set(cx - r, cy - r, cx + r, cy + r); c.drawRoundRect(box, 5 * d, 5 * d, paint);
                }
                default -> {
                    paint.setStyle(Paint.Style.STROKE);
                    path.reset(); path.moveTo(cx + 3.5f * d, cy - 7.5f * d); path.lineTo(cx - 4 * d, cy); path.lineTo(cx + 3.5f * d, cy + 7.5f * d);
                    c.drawPath(path, paint);
                }
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    /**
     * While an icon is dragged (a window over the whole screen that takes no touches): once it is lifted into the screen,
     * one target over the whole screen above the bar, 놓으면 화면 나누기, and the icon under the finger. One target, not a
     * half: Android puts the app on its split side stage (normally right, or bottom) whatever half it is let go on.
     */
    static final class Drop extends View {
        private static final int ACCENT = 0xffc4643f;
        private static final String SAY = "놓으면 화면 나누기";
        private final float d;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG), words = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF area = new RectF(), label = new RectF();
        private final int[] at = new int[2];
        private Drawable icon, source; private float x, y; private TaskbarPlan.Side side = TaskbarPlan.Side.NONE; private int barTop, iconSize;
        Drop(Context context, float density) {
            super(context);
            d = density;
            words.setColor(0xffffffff); words.setTextSize(15 * d); words.setTextAlign(Paint.Align.CENTER); words.setTypeface(Ui.weight(620));
        }
        float density() { return d; }
        @Override protected void onLayout(boolean changed, int l, int t, int r, int b) { super.onLayout(changed, l, t, r, b); getLocationOnScreen(at); }
        void at(Drawable icon, int iconSize, float x, float y, TaskbarPlan.Side side, int barTop) {
            // Its own copy: the bar's icon keeps its bounds and alpha.
            if (icon != source) { source = icon; Drawable.ConstantState state = icon == null ? null : icon.getConstantState(); this.icon = state != null ? state.newDrawable(getResources()).mutate() : icon; }
            this.iconSize = iconSize; this.x = x; this.y = y; this.side = side; this.barTop = barTop; invalidate();
        }
        TaskbarPlan.Side side() { return side; }
        /** Whether the drop target shows now (the icon is lifted far enough into the screen). */
        boolean armed() { return side != TaskbarPlan.Side.NONE; }
        @Override protected void onDraw(Canvas c) {
            float w = getWidth(), m = 12 * d, top = m, bottom = barTop - at[1] - m;
            if (side != TaskbarPlan.Side.NONE && bottom > top) {
                area.set(m, top, w - m, bottom);
                paint.setStyle(Paint.Style.FILL); paint.setColor(0x33c4643f); c.drawRoundRect(area, 22 * d, 22 * d, paint);
                paint.setStyle(Paint.Style.STROKE); paint.setStrokeWidth(2 * d); paint.setColor(ACCENT); c.drawRoundRect(area, 22 * d, 22 * d, paint);
                // The words on a small pill, readable over any app.
                float tw = words.measureText(SAY), cx = area.centerX(), cy = area.centerY();
                paint.setStyle(Paint.Style.FILL); paint.setColor(0xe6c4643f);
                label.set(cx - tw / 2 - 14 * d, cy - 17 * d, cx + tw / 2 + 14 * d, cy + 17 * d);
                c.drawRoundRect(label, 17 * d, 17 * d, paint);
                c.drawText(SAY, cx, cy + 5.5f * d, words);
            }
            if (icon != null) {
                int s = Math.round(iconSize * 1.2f); float lx = x - at[0], ly = y - at[1];
                icon.setBounds(Math.round(lx - s / 2f), Math.round(ly - s / 2f), Math.round(lx + s / 2f), Math.round(ly + s / 2f));
                icon.setAlpha(235); icon.draw(c);
            }
        }
    }
}
