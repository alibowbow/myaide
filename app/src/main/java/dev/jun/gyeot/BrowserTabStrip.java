package dev.jun.gyeot;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.Drawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import java.util.*;
import static dev.jun.gyeot.Ui.*;

/** Persistent Chrome-style tabs. Selecting a title never triggers its separate close target. */
final class BrowserTabStrip extends LinearLayout {
    record Item(String id, String title, boolean selected, Runnable select, Runnable close) {}
    final LinearLayout actions, navigation;
    private final Ui ui;
    private final HorizontalScrollView scroll;
    private final LinearLayout tabs;
    private final ImageView add;
    private List<String> rendered = List.of(), renderedIds = List.of();
    private String selectedId = "";
    private View selectedView;
    private boolean pendingReveal;
    private record TabParts(View mark, TextView title) {}
    private final Map<View, TabParts> tabParts = new IdentityHashMap<>();

    BrowserTabStrip(Context context, Ui ui, Runnable newTab) {
        super(context); this.ui = ui;
        setOrientation(HORIZONTAL); setGravity(Gravity.CENTER_VERTICAL); setBackgroundColor(FILL);
        setPadding(ui.dp(4), 0, ui.dp(4), 0);
        navigation = ui.row(); addView(navigation, new LayoutParams(-2, rowHeight()));
        scroll = new HorizontalScrollView(context); scroll.setHorizontalScrollBarEnabled(false);
        scroll.setFillViewport(false); scroll.setHorizontalFadingEdgeEnabled(false); scroll.setContentDescription("열린 탭 · 좌우로 스크롤");
        tabs = new LinearLayout(context) {
            @Override protected void onLayout(boolean changed, int l, int t, int r, int b) {
                super.onLayout(changed, l, t, r, b);
                if (pendingReveal) { pendingReveal = false; revealSelected(); }
            }
        };
        tabs.setOrientation(HORIZONTAL); tabs.setGravity(Gravity.CENTER_VERTICAL);
        scroll.addView(tabs, new HorizontalScrollView.LayoutParams(-2, -1));
        addView(scroll, new LayoutParams(0, rowHeight(), 1));
        actions = ui.row();
        add = ui.quietIcon(R.drawable.ic_add, "새 탭 열기", newTab);
        add.setPadding(ui.dp(13), ui.dp(13), ui.dp(13), ui.dp(13));
        add.setTooltipText("새 탭");
        tabs.addView(add, new LayoutParams(ui.dp(48), rowHeight()));
        addView(actions, new LayoutParams(-2, rowHeight()));
        scroll.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> {
            if (r - l != or - ol) revealSelected();
        });
    }

    int rowHeight() { return ui.dp(Math.max(40, 22 * getResources().getConfiguration().fontScale + 8)); }

    HorizontalScrollView scrollView() { return scroll; }
    @Override protected void onMeasure(int width, int height) {
        super.onMeasure(width, height);
        int room=scroll.getMeasuredWidth(); if(room<=0)return;
        int natural=ui.dp(Math.round(Math.min(224,180*Math.max(1f,getResources().getConfiguration().fontScale)))+24);
        boolean changed=false;
        for(int i=0;i<tabs.getChildCount();i++) {
            View tab=tabs.getChildAt(i);if(tab==add)continue;
            TabParts parts=tabParts.get(tab);
            boolean compact=room<ui.dp(180)&&getResources().getConfiguration().fontScale>=1.5f;
            if(parts!=null) {int visibility=compact?GONE:VISIBLE;if(parts.mark().getVisibility()!=visibility){parts.mark().setVisibility(visibility);changed=true;}parts.title().setPadding(compact?ui.dp(8):0,0,0,0);}
            LayoutParams p=(LayoutParams)tab.getLayoutParams();int wanted=Math.min(natural,room);
            if(p.width!=wanted){p.width=wanted;tab.setLayoutParams(p);changed=true;}
        }
        if(changed)super.onMeasure(width,height);
    }

    void render(List<Item> items, boolean canAdd) {
        add.setEnabled(canAdd); add.setAlpha(canAdd ? 1f : .35f);
        add.setContentDescription(canAdd ? "새 탭 열기" : "새 탭 열기 · 최대 6개");
        List<String> state = new ArrayList<>(), ids = new ArrayList<>();
        String nextSelected = "";
        for (Item item : items) {
            ids.add(item.id()); state.add(item.id()); state.add(item.title()); state.add(Boolean.toString(item.selected()));
            if (item.selected()) nextSelected = item.id();
        }
        if (state.equals(rendered)) return;
        boolean selectionChanged = !selectedId.equals(nextSelected) || !renderedIds.equals(ids);
        renderedIds = ids;
        rendered = state; selectedId = nextSelected;
        tabs.removeAllViews(); tabParts.clear(); selectedView = null;
        for (int i = 0; i < items.size(); i++) {
            Item item = items.get(i);
            LinearLayout tab = ui.row(); tab.setSelected(item.selected());
            // Reserve the shoulders inside the view: no out-of-bounds drawing to clip at
            // the first/last tab, and no overlap with a neighbouring 48dp-wide close target.
            int shoulder = ui.dp(12);
            tab.setPadding(shoulder, 0, shoulder, 0);
            boolean divider = !item.selected() && i + 1 < items.size() && !items.get(i + 1).selected();
            tab.setBackground(new TabSurface(item.selected(), divider, shoulder));
            TextView mark = ui.text(monogram(item.title()), 12, 650, item.selected() ? ACCENT : MUTED);
            mark.setTextSize(android.util.TypedValue.COMPLEX_UNIT_DIP,12); mark.setGravity(Gravity.CENTER); mark.setBackground(ui.shape(item.selected() ? ACCENT_SOFT : FILL, 6));
            mark.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO); mark.setOnClickListener(v -> item.select().run());
            LayoutParams monogram = new LayoutParams(ui.dp(22), ui.dp(22)); monogram.leftMargin = ui.dp(12); monogram.rightMargin = ui.dp(8); tab.addView(mark, monogram);
            TextView title = ui.text(item.title(), 13, item.selected() ? 650 : 450, item.selected() ? TEXT : MUTED);
            title.setSingleLine(true); title.setEllipsize(android.text.TextUtils.TruncateAt.END);
            title.setGravity(Gravity.CENTER_VERTICAL); title.setPadding(0, 0, 0, 0);
            title.setTooltipText(item.title());
            title.setSelected(item.selected()); title.setContentDescription(item.title() + (item.selected() ? ", 현재 탭" : ", 탭 전환"));
            title.setOnClickListener(v -> item.select().run()); Ui.pressable(title, false);
            tab.addView(title, new LayoutParams(0, -1, 1)); tabParts.put(tab,new TabParts(mark,title));
            ImageView close = ui.quietIcon(R.drawable.ic_close, item.title() + " 탭 닫기", item.close());
            close.setPadding(ui.dp(14), ui.dp(14), ui.dp(14), ui.dp(14)); close.setImageTintList(android.content.res.ColorStateList.valueOf(item.selected() ? TEXT : MUTED));
            close.setTooltipText("탭 닫기");
            tab.addView(close, new LayoutParams(ui.dp(48), -1));
            int width = Math.round(Math.min(224, 180 * Math.max(1f, getResources().getConfiguration().fontScale)));
            LayoutParams size = new LayoutParams(ui.dp(width) + shoulder * 2, rowHeight());
            tabs.addView(tab, size);
            if (item.selected()) selectedView = tab;
        }
        tabs.addView(add, new LayoutParams(ui.dp(48), rowHeight()));
        // Updates to a background title must not fight the user's horizontal scrolling.
        if (selectionChanged) pendingReveal = true;
    }

    /** One continuous surface into the toolbar: convex top corners, concave lower shoulders. */
    private static final class TabSurface extends Drawable {
        private final boolean selected, divider;
        private final float radius;
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Path outline = new Path();
        TabSurface(boolean selected, boolean divider, float radius) {
            this.selected = selected; this.divider = divider; this.radius = radius;
        }
        @Override protected void onBoundsChange(Rect bounds) {
            outline.reset();
            float l = bounds.left, t = bounds.top, r = bounds.right, b = bounds.bottom;
            float s = Math.min(radius, Math.min(bounds.width() / 4f, bounds.height() / 2f));
            float k = .55228475f; // Quarter-circle tangent, shared by both lower shoulders.
            outline.moveTo(l, b);
            outline.cubicTo(l + s * k, b, l + s, b - s + s * k, l + s, b - s);
            outline.lineTo(l + s, t + s);
            outline.cubicTo(l + s, t + s - s * k, l + 2 * s - s * k, t, l + 2 * s, t);
            outline.lineTo(r - 2 * s, t);
            outline.cubicTo(r - 2 * s + s * k, t, r - s, t + s - s * k, r - s, t + s);
            outline.lineTo(r - s, b - s);
            outline.cubicTo(r - s, b - s + s * k, r - s * k, b, r, b);
            outline.close();
        }
        @Override public void draw(Canvas canvas) {
            paint.setColor(selected ? CARD : SEPARATOR);
            if (selected) canvas.drawPath(outline, paint);
            else if (divider) {
                Rect b = getBounds();
                float thickness = Math.max(1f, radius / 12f);
                canvas.drawRoundRect(b.right - thickness, b.top + b.height() * .27f,
                    b.right, b.bottom - b.height() * .27f, thickness / 2, thickness / 2, paint);
            }
        }
        @Override public void setAlpha(int alpha) { paint.setAlpha(alpha); invalidateSelf(); }
        @Override public void setColorFilter(ColorFilter filter) { paint.setColorFilter(filter); invalidateSelf(); }
        @Override public int getOpacity() { return PixelFormat.TRANSLUCENT; }
    }

    private static String monogram(String title) {
        String value = title == null ? "" : title.strip();
        return value.isEmpty() ? "W" : value.substring(0, value.offsetByCodePoints(0, 1)).toUpperCase(Locale.ROOT);
    }

    private void revealSelected() {
        if (selectedView == null || scroll.getWidth() == 0) return;
        int left = selectedView.getLeft(), right = selectedView.getRight();
        int viewportLeft = scroll.getScrollX(), viewportRight = viewportLeft + scroll.getWidth();
        if (left < viewportLeft) scroll.scrollTo(left, 0);
        else if (right > viewportRight) scroll.scrollTo(right - scroll.getWidth(), 0);
    }
}
