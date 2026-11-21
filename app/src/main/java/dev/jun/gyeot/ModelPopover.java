package dev.jun.gyeot;

import android.app.Activity;
import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import dev.jun.gyeot.core.Reasoning;
import dev.jun.gyeot.core.WorkBook;
import java.util.List;
import java.util.function.Consumer;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/**
 * The model menu that opens over 작업창's model chip, the way Claude's and ChatGPT's apps do it: a small card above the
 * chip, no icons. It lists the AI's models with a check, then 생각 깊이 (a slider with one stop per level, like ChatGPT's),
 * 다른 AI, 모든 모델·설정 (the full sheet: search, speed, refresh) and 에이전트 팀. Choices go to the room's own
 * selection (ScopedModels), exactly as the full sheet does.
 */
final class ModelPopover {
    /** What the menu reads from 작업창: catalogs, names and the levels' words. */
    interface Catalog {
        JSONArray models(String engine);
        String label(String engine, String model);
        Reasoning.Profile profile(String engine, String model);
        String effortLabel(String engine, String effort);
        String effortHint(String effort, String fallback);
        String team();
    }
    private static final String[] ENGINES = {WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM};
    private static final int SHOWN = 5;
    private final Activity activity;
    private final Ui ui;
    private final AppStore store;
    private final ScopedModels selection;
    private final Catalog catalog;
    private final Consumer<String> chooseEngine;
    private final Runnable more, team, changed;
    private final LinearLayout card;
    private PopupWindow window;
    /** The chip it opened from: every page is placed against it again, so a shorter page does not float away from it. */
    private View anchor;
    private boolean below;

    ModelPopover(Activity activity, Ui ui, AppStore store, ScopedModels selection, Catalog catalog, Consumer<String> chooseEngine, Runnable more, Runnable team, Runnable changed) {
        this.activity = activity; this.ui = ui; this.store = store; this.selection = selection; this.catalog = catalog;
        this.chooseEngine = chooseEngine; this.more = more; this.team = team; this.changed = changed;
        card = ui.column(); card.setBackground(ui.shape(CARD, 20)); card.setPadding(0, ui.dp(6), 0, ui.dp(6)); card.setElevation(ui.dp(14));
        card.setClipToOutline(true);
    }

    void show(View anchor) {
        main();
        ScrollView scroll = new ScrollView(activity); scroll.setVerticalScrollBarEnabled(false); scroll.addView(card);
        int width = Math.min(ui.dp(300), activity.getResources().getDisplayMetrics().widthPixels - ui.dp(24));
        window = new PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(0)); window.setOutsideTouchable(true); window.setElevation(ui.dp(14));
        window.setAnimationStyle(android.R.style.Animation_Dialog);
        place(anchor, width);
    }
    void dismiss() { if (window != null) window.dismiss(); }
    /** Above the chip, right edges lined up, inside the screen; below it when there is no room above. */
    private void place(View anchor, int width) {
        this.anchor = anchor;
        int height = measure(width);
        int[] at = new int[2]; anchor.getLocationInWindow(at);
        below = at[1] - height - ui.dp(8) < ui.dp(48);
        window.setHeight(height);
        int[] xy = position(width, height);
        window.showAtLocation(anchor.getRootView(), Gravity.NO_GRAVITY, xy[0], xy[1]);
    }
    private int measure(int width) {
        card.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        return Math.min(card.getMeasuredHeight(), activity.getResources().getDisplayMetrics().heightPixels - ui.dp(96));
    }
    /** Where a card of this size sits: its bottom just above the chip (or its top just under it), right edges lined up. */
    int[] position(int width, int height) {
        int[] at = new int[2]; anchor.getLocationInWindow(at);
        int x = Math.max(ui.dp(12), Math.min(at[0] + anchor.getWidth() - width, activity.getResources().getDisplayMetrics().widthPixels - width - ui.dp(12)));
        int y = below ? at[1] + anchor.getHeight() + ui.dp(8) : Math.max(ui.dp(12), at[1] - height - ui.dp(8));
        return new int[] {x, y};
    }
    /** A page changed: new size, and placed against the chip again (PopupWindow.update alone keeps the old top edge). */
    private void resize() {
        if (window == null || !window.isShowing() || anchor == null) return;
        int width = window.getWidth(), height = measure(width);
        int[] xy = position(width, height);
        window.update(xy[0], xy[1], width, height);
    }

    // ── Pages ───────────────────────────────────────────────────────────────

    /** The AI's models (a few, the chosen one always among them), then depth, other AIs, everything, team. */
    private void main() {
        card.removeAllViews();
        String engine = selection.engine(), current = selection.model(engine);
        card.addView(caption(WorkBook.author(engine)));
        JSONArray models = catalog.models(engine); int shown = 0; boolean currentShown = false;
        for (int i = 0; i < models.length() && shown < SHOWN; i++) {
            JSONObject model = models.optJSONObject(i); if (model == null) continue;
            String id = model.optString("id");
            if (shown == SHOWN - 1 && !currentShown && !id.equals(current) && contains(models, current)) continue;
            shown++; currentShown |= id.equals(current);
            card.addView(choice(catalog.label(engine, id), id.equals(current), () -> {
                if (!id.equals(selection.model(engine))) {
                    try { selection.model(engine, id); } catch (Exception e) { store.error(e.getMessage()); return; }
                    changed.run();
                }
                dismiss();
            }));
        }
        if (!currentShown && !current.isBlank()) card.addView(choice(catalog.label(engine, current), true, this::dismiss));
        card.addView(divider());
        Reasoning.Profile profile = catalog.profile(engine, current);
        if (!current.isBlank() && !profile.efforts.isEmpty()) {
            String saved = selection.effort(engine, current);
            card.addView(row("생각 깊이", catalog.effortLabel(engine, saved.isEmpty() ? profile.defaultEffort : saved), true, this::depth));
        }
        card.addView(row("다른 AI", WorkBook.author(engine), true, this::engines));
        card.addView(row(models.length() > SHOWN ? "더 많은 모델·설정" : "모델 설정", "", true, () -> { dismiss(); more.run(); }));
        // A screen without agent teams (마이튜브) passes no team: no row for it.
        if (team != null) { card.addView(divider()); card.addView(row("에이전트 팀", catalog.team(), true, () -> { dismiss(); team.run(); })); }
        resize();
    }
    /** Like ChatGPT's: the level in large type over the model's name, a slider with one stop per level, and what it means. */
    private void depth() {
        card.removeAllViews();
        String engine = selection.engine(), model = selection.model(engine);
        Reasoning.Profile profile = catalog.profile(engine, model);
        List<String> levels = profile.efforts;
        String saved = selection.effort(engine, model), shown = saved.isEmpty() ? profile.defaultEffort : saved;
        LinearLayout top = ui.row(); top.setPadding(ui.dp(4), 0, ui.dp(4), 0);
        top.addView(icon(R.drawable.ic_back, MUTED, "뒤로", this::main), new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        String fallback = profile.defaultEffort.isEmpty() ? "기본" : "기본 · " + catalog.effortLabel(engine, profile.defaultEffort);
        TextView level = ui.text(saved.isEmpty() ? fallback : catalog.effortLabel(engine, saved), 20, 700, TEXT); level.setGravity(Gravity.CENTER); level.setMaxLines(1); level.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(level, new LinearLayout.LayoutParams(0, -2, 1));
        ImageView reset = icon(R.drawable.ic_refresh, saved.isEmpty() ? SEPARATOR : MUTED, "기본 깊이로 되돌리기", () -> { if (!selection.effort(engine, model).isEmpty()) set(engine, model, ""); });
        reset.setEnabled(!saved.isEmpty());
        top.addView(reset, new LinearLayout.LayoutParams(ui.dp(44), ui.dp(44)));
        card.addView(top);
        TextView name = ui.text(catalog.label(engine, model), 13, 500, MUTED); name.setGravity(Gravity.CENTER); name.setMaxLines(1); name.setEllipsize(TextUtils.TruncateAt.END); name.setPadding(ui.dp(52), 0, ui.dp(52), 0);
        card.addView(name, new LinearLayout.LayoutParams(-1, -2));
        Slider slider = new Slider(activity, levels.size(), Math.max(0, levels.indexOf(shown)), index -> set(engine, model, levels.get(index)));
        slider.setContentDescription("생각 깊이, 지금 " + catalog.effortLabel(engine, shown));
        LinearLayout.LayoutParams sp = new LinearLayout.LayoutParams(-1, ui.dp(44)); sp.setMargins(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(4)); card.addView(slider, sp);
        LinearLayout ends = ui.row(); ends.setPadding(ui.dp(18), 0, ui.dp(18), 0);
        TextView first = ui.text(catalog.effortLabel(engine, levels.get(0)), 11.5f, 500, MUTED), last = ui.text(catalog.effortLabel(engine, levels.get(levels.size() - 1)), 11.5f, 500, MUTED);
        ends.addView(first, new LinearLayout.LayoutParams(0, -2, 1)); last.setGravity(Gravity.END); ends.addView(last, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(ends);
        TextView hint = ui.text(catalog.effortHint(saved, profile.defaultEffort), 12.5f, 450, MUTED); hint.setPadding(ui.dp(18), ui.dp(10), ui.dp(18), ui.dp(10)); hint.setLineSpacing(ui.dp(2), 1);
        card.addView(hint);
        resize();
    }
    private void set(String engine, String model, String effort) {
        try { selection.effort(engine, model, effort); } catch (Exception e) { store.error(e.getMessage()); return; }
        changed.run(); depth();
    }
    /** The five AIs: the one answering checked, each with its model or what it still needs. */
    private void engines() {
        card.removeAllViews();
        card.addView(back("다른 AI"));
        String engine = selection.engine();
        for (String option : ENGINES) {
            String model = selection.model(option);
            String detail = store.ready(option) ? (model.isBlank() ? "" : catalog.label(option, model)) : "연결 필요";
            card.addView(row(WorkBook.author(option), detail, false, option.equals(engine), () -> {
                dismiss();
                if (!option.equals(selection.engine())) chooseEngine.accept(option);
            }));
        }
        resize();
    }

    // ── Rows ────────────────────────────────────────────────────────────────

    private TextView caption(String value) {
        TextView view = ui.text(value, 12, 600, MUTED); view.setPadding(ui.dp(18), ui.dp(8), ui.dp(18), ui.dp(4)); return view;
    }
    private View back(String title) {
        LinearLayout row = ui.row(); row.setPadding(ui.dp(4), 0, ui.dp(16), 0); row.setMinimumHeight(ui.dp(44));
        ImageView arrow = new ImageView(activity); arrow.setImageResource(R.drawable.ic_back); arrow.setImageTintList(ColorStateList.valueOf(MUTED)); arrow.setScaleType(ImageView.ScaleType.CENTER_INSIDE); arrow.setPadding(ui.dp(12), ui.dp(12), ui.dp(4), ui.dp(12));
        row.addView(arrow, new LinearLayout.LayoutParams(ui.dp(36), ui.dp(44)));
        row.addView(ui.text(title, 13, 600, MUTED));
        row.setContentDescription("뒤로"); row.setOnClickListener(v -> main()); Ui.pressable(row, true);
        return row;
    }
    /** A plain 44dp icon button (no tile), tinted. */
    private ImageView icon(int res, int tint, String description, Runnable action) {
        ImageView view = new ImageView(activity); view.setImageResource(res); view.setImageTintList(ColorStateList.valueOf(tint));
        view.setScaleType(ImageView.ScaleType.CENTER_INSIDE); view.setPadding(ui.dp(12), ui.dp(12), ui.dp(12), ui.dp(12));
        view.setContentDescription(description); view.setOnClickListener(v -> action.run()); Ui.pressable(view, true);
        return view;
    }
    /** A model: its name and a check when it answers now. */
    private View choice(String name, boolean on, Runnable action) {
        LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(46)); row.setPadding(ui.dp(18), 0, ui.dp(16), 0);
        TextView label = ui.text(name, 15.5f, on ? 600 : 450, TEXT); label.setMaxLines(1); label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView check = ui.text(on ? "✓" : "", 17, 700, ACCENT); check.setGravity(Gravity.END); row.addView(check, new LinearLayout.LayoutParams(ui.dp(28), -2));
        row.setSelected(on); row.setContentDescription(name + (on ? ", 선택됨" : "")); row.setOnClickListener(v -> action.run()); Ui.pressable(row, true);
        return row;
    }
    private View row(String title, String value, boolean arrow, Runnable action) { return row(title, value, arrow, false, action); }
    /** A setting: its name in bold, its value quiet on the right, a chevron when it opens more, a check when chosen. */
    private View row(String title, String value, boolean arrow, boolean on, Runnable action) {
        LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(46)); row.setPadding(ui.dp(18), 0, ui.dp(16), 0);
        row.addView(ui.text(title, 15, 600, TEXT), new LinearLayout.LayoutParams(-2, -2));
        TextView detail = ui.text(value, 13.5f, 450, MUTED); detail.setMaxLines(1); detail.setEllipsize(TextUtils.TruncateAt.END); detail.setGravity(Gravity.END);
        detail.setPadding(ui.dp(12), 0, ui.dp(6), 0); row.addView(detail, new LinearLayout.LayoutParams(0, -2, 1));
        TextView end = ui.text(on ? "✓" : arrow ? "›" : "", on ? 17 : 20, on ? 700 : 500, on ? ACCENT : MUTED); end.setGravity(Gravity.END);
        row.addView(end, new LinearLayout.LayoutParams(ui.dp(18), -2));
        row.setSelected(on); row.setContentDescription(title + (value.isEmpty() ? "" : ", " + value) + (on ? ", 선택됨" : ""));
        row.setOnClickListener(v -> action.run()); Ui.pressable(row, true);
        return row;
    }
    private View divider() {
        View line = new View(activity); line.setBackgroundColor(SEPARATOR);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, Math.max(1, ui.dp(1))); p.setMargins(ui.dp(16), ui.dp(6), ui.dp(16), ui.dp(6)); line.setLayoutParams(p);
        return line;
    }
    private static boolean contains(JSONArray models, String id) {
        for (int i = 0; i < models.length(); i++) if (models.optJSONObject(i) != null && id.equals(models.optJSONObject(i).optString("id"))) return true;
        return false;
    }

    /**
     * ChatGPT's level slider: a rounded track with a dot per level, filled up to the white thumb on the chosen one. Tap or
     * drag; it settles on the nearest level and reports it once it changes. Each level is also reachable by accessibility
     * actions (scroll forward/backward).
     */
    static final class Slider extends View {
        private final int count; private final Consumer<Integer> picked;
        private int index; private float drag = -1;
        private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG), dot = new Paint(Paint.ANTI_ALIAS_FLAG), thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final RectF box = new RectF();
        Slider(Context context, int count, int index, Consumer<Integer> picked) {
            super(context); this.count = Math.max(1, count); this.index = Math.max(0, Math.min(this.count - 1, index)); this.picked = picked;
            track.setColor(FILL); fill.setColor(TEXT); dot.setColor(MUTED); thumb.setColor(0xffffffff);
            thumb.setShadowLayer(dp(3), 0, dp(1), 0x40000000); setLayerType(LAYER_TYPE_SOFTWARE, thumb);
            setFocusable(true); setClickable(true);
        }
        private float dp(float v) { return v * getResources().getDisplayMetrics().density; }
        private float stop(int i) { float r = getHeight() / 2f; return count == 1 ? getWidth() / 2f : r + (getWidth() - 2 * r) * i / (count - 1); }
        @Override protected void onDraw(Canvas canvas) {
            float h = getHeight(), r = h / 2f;
            box.set(0, 0, getWidth(), h); canvas.drawRoundRect(box, r, r, track);
            float x = drag >= 0 ? Math.max(r, Math.min(getWidth() - r, drag)) : stop(index);
            box.set(0, 0, x + r, h); canvas.drawRoundRect(box, r, r, fill);
            for (int i = 0; i < count; i++) if (stop(i) > x + r * 0.6f) canvas.drawCircle(stop(i), r, dp(2.5f), dot);
            canvas.drawCircle(x, r, r - dp(3), thumb);
        }
        @Override public boolean onTouchEvent(MotionEvent e) {
            switch (e.getActionMasked()) {
                case MotionEvent.ACTION_DOWN, MotionEvent.ACTION_MOVE -> { getParent().requestDisallowInterceptTouchEvent(true); drag = e.getX(); invalidate(); return true; }
                case MotionEvent.ACTION_UP -> { settle(e.getX()); performClick(); return true; }
                case MotionEvent.ACTION_CANCEL -> { drag = -1; invalidate(); return true; }
                default -> { return super.onTouchEvent(e); }
            }
        }
        @Override public boolean performClick() { return super.performClick(); }
        private void settle(float x) {
            int best = 0; for (int i = 1; i < count; i++) if (Math.abs(stop(i) - x) < Math.abs(stop(best) - x)) best = i;
            drag = -1; choose(best);
        }
        private void choose(int next) { if (next == index) { invalidate(); return; } index = next; invalidate(); picked.accept(next); }
        @Override public void onInitializeAccessibilityNodeInfo(android.view.accessibility.AccessibilityNodeInfo info) {
            super.onInitializeAccessibilityNodeInfo(info); info.setClassName("android.widget.SeekBar");
            if (index < count - 1) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_FORWARD);
            if (index > 0) info.addAction(android.view.accessibility.AccessibilityNodeInfo.AccessibilityAction.ACTION_SCROLL_BACKWARD);
        }
        @Override public boolean performAccessibilityAction(int action, android.os.Bundle args) {
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_FORWARD && index < count - 1) { choose(index + 1); return true; }
            if (action == android.view.accessibility.AccessibilityNodeInfo.ACTION_SCROLL_BACKWARD && index > 0) { choose(index - 1); return true; }
            return super.performAccessibilityAction(action, args);
        }
    }
}
