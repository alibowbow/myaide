package dev.jun.gyeot;

import android.app.Activity;
import android.content.res.ColorStateList;
import android.graphics.drawable.ColorDrawable;
import android.text.TextUtils;
import android.view.Gravity;
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
 * chip, no icons. It lists the AI's models with a check, then 생각 깊이 (its levels in a small card beside the menu, as Claude's),
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
    private static final String[] ENGINES = {WorkBook.CHATGPT, WorkBook.CLAUDE, WorkBook.GEMINI, WorkBook.GROK, WorkBook.NIM, WorkBook.ANTIGRAVITY};
    private static final int SHOWN = 5;
    private final Activity activity;
    private final Ui ui;
    private final AppStore store;
    private final ScopedModels selection;
    private final Catalog catalog;
    private final Consumer<String> chooseEngine;
    private final Runnable more, team, changed;
    private final LinearLayout card;
    private PopupWindow window, flyout;
    /** The 생각 깊이 row of the menu: its levels open beside it (flyout). */
    private View depthRow;
    /** Every change goes through this (the main chat asks first when replies being prepared would stop); by default at once. */
    private Consumer<Runnable> confirm = Runnable::run;
    /** The chip it opened from: every page is placed against it again, so a shorter page does not float away from it. */
    private View anchor;
    private boolean below;

    ModelPopover(Activity activity, Ui ui, AppStore store, ScopedModels selection, Catalog catalog, Consumer<String> chooseEngine, Runnable more, Runnable team, Runnable changed) {
        this.activity = activity; this.ui = ui; this.store = store; this.selection = selection; this.catalog = catalog;
        this.chooseEngine = chooseEngine; this.more = more; this.team = team; this.changed = changed;
        card = ui.column(); card.setBackground(ui.shape(CARD, 18)); card.setPadding(0, ui.dp(4), 0, ui.dp(4)); card.setElevation(ui.dp(14));
        card.setClipToOutline(true);
    }

    /** Changes go through confirm (it runs them, at once or after asking). */
    ModelPopover guarded(Consumer<Runnable> confirm) { this.confirm = confirm == null ? Runnable::run : confirm; return this; }
    void show(View anchor) {
        main();
        ScrollView scroll = new ScrollView(activity); scroll.setVerticalScrollBarEnabled(false); scroll.addView(card);
        int width = Math.min(ui.dp(264), activity.getResources().getDisplayMetrics().widthPixels - ui.dp(24));
        window = new PopupWindow(scroll, width, ViewGroup.LayoutParams.WRAP_CONTENT, true);
        window.setBackgroundDrawable(new ColorDrawable(0)); window.setOutsideTouchable(true); window.setElevation(ui.dp(14));
        window.setAnimationStyle(android.R.style.Animation_Dialog);
        window.setOnDismissListener(() -> { if (flyout != null) flyout.dismiss(); });
        place(anchor, width);
    }
    void dismiss() { if (flyout != null) flyout.dismiss(); if (window != null) window.dismiss(); }
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
                dismiss();
                if (!id.equals(selection.model(engine))) confirm.accept(() -> {
                    try { selection.model(engine, id); } catch (Exception e) { store.error(e.getMessage()); return; }
                    changed.run();
                });
            }));
        }
        if (!currentShown && !current.isBlank()) card.addView(choice(catalog.label(engine, current), true, this::dismiss));
        card.addView(divider());
        Reasoning.Profile profile = catalog.profile(engine, current);
        if (!current.isBlank() && !profile.efforts.isEmpty()) {
            depthRow = row("생각 깊이", catalog.effortLabel(engine, shown(engine, profile, selection.effort(engine, current))), true, this::flyout); card.addView(depthRow);
        }
        card.addView(row("다른 AI", WorkBook.author(engine), true, this::engines));
        card.addView(row(models.length() > SHOWN ? "더 많은 모델·설정" : "모델 설정", "", true, () -> { dismiss(); more.run(); }));
        // A screen without agent teams (마이튜브) passes no team: no row for it.
        if (team != null) { card.addView(divider()); card.addView(row("에이전트 팀", catalog.team(), true, () -> { dismiss(); team.run(); })); }
        resize();
    }
    /** The level in effect: the saved one, else the model's default (Claude, which advertises none, thinks at 높음 by default). */
    static String shown(String engine, Reasoning.Profile profile, String saved) {
        if (!saved.isEmpty()) return saved;
        if (!profile.defaultEffort.isEmpty()) return profile.defaultEffort;
        return WorkBook.CLAUDE.equals(engine) && profile.efforts.contains("high") ? "high" : "";
    }
    /** The level marked 추천: the model's default, Claude's 중간 as on claude.ai. */
    static String recommended(String engine, Reasoning.Profile profile) {
        if (!profile.defaultEffort.isEmpty()) return profile.defaultEffort;
        return WorkBook.CLAUDE.equals(engine) && profile.efforts.contains("medium") ? "medium" : "";
    }
    /**
     * 생각 깊이, as Claude's app does it: a small card beside the menu (on a narrow screen just above its row), each level with
     * 추천 on the recommended one and a check on the chosen one. Claude's card is claude.ai's: 최대 says what it costs, and
     * Ultracode is a switch under the levels. Other AIs get a line on what depth costs instead. The menu stays where it is.
     */
    private void flyout() {
        if (flyout != null && flyout.isShowing()) { flyout.dismiss(); return; }
        String engine = selection.engine(), model = selection.model(engine);
        Reasoning.Profile profile = catalog.profile(engine, model);
        List<String> levels = profile.efforts; if (levels.isEmpty() || depthRow == null) return;
        boolean claude = WorkBook.CLAUDE.equals(engine);
        String shown = shown(engine, profile, selection.effort(engine, model)), recommended = recommended(engine, profile);
        LinearLayout box = ui.column(); box.setBackground(ui.shape(CARD, 18)); box.setPadding(0, ui.dp(6), 0, ui.dp(6)); box.setClipToOutline(true);
        if (!claude) {
            TextView hint = ui.text("깊이 생각할수록 답이 철저해지지만, 시간이 더 걸리고 사용량이 늘어요.", 12, 450, MUTED);
            hint.setPadding(ui.dp(14), ui.dp(4), ui.dp(14), ui.dp(6)); hint.setLineSpacing(ui.dp(1.5f), 1); box.addView(hint);
        }
        for (String level : levels) {
            boolean on = level.equals(shown);
            LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(38)); row.setPadding(ui.dp(14), 0, ui.dp(12), 0); row.setGravity(Gravity.CENTER_VERTICAL);
            String name = catalog.effortLabel(engine, level);
            row.addView(ui.text(name, 14.5f, on ? 650 : 500, TEXT), new LinearLayout.LayoutParams(-2, -2));
            boolean costly = claude && "max".equals(level);
            if (level.equals(recommended) || costly) {
                // 추천 in a quiet pill; Claude's 최대 says, in an amber pill, that it uses far more.
                TextView badge = ui.text(costly ? "⚠ 3.5× 이상 사용량" : "추천", 10.5f, 600, costly ? WARNING : MUTED);
                badge.setBackground(ui.shape(costly ? (WARNING & 0x00ffffff) | 0x26000000 : FILL, PILL)); badge.setPadding(ui.dp(7), ui.dp(1), ui.dp(7), ui.dp(2)); badge.setMaxLines(1);
                LinearLayout.LayoutParams at = new LinearLayout.LayoutParams(-2, -2); at.leftMargin = ui.dp(7); row.addView(badge, at);
            }
            row.addView(new View(activity), new LinearLayout.LayoutParams(0, 1, 1));
            TextView check = ui.text(on ? "✓" : "", 15, 700, ACCENT); check.setGravity(Gravity.END); row.addView(check, new LinearLayout.LayoutParams(ui.dp(20), -2));
            row.setSelected(on); row.setContentDescription("생각 깊이 " + name + (level.equals(recommended) ? ", 추천" : "") + (costly ? ", 3.5배 이상 사용량" : "") + (on ? ", 선택됨" : ""));
            row.setOnClickListener(v -> set(engine, model, level)); Ui.pressable(row, true);
            box.addView(row);
        }
        if (claude) { box.addView(divider()); box.addView(ultracode()); }
        int width = Math.min(ui.dp(claude ? 248 : 236), activity.getResources().getDisplayMetrics().widthPixels - ui.dp(24));
        box.measure(View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED));
        int height = box.getMeasuredHeight();
        flyout = new PopupWindow(box, width, height, true);
        flyout.setBackgroundDrawable(new ColorDrawable(0)); flyout.setOutsideTouchable(true); flyout.setElevation(ui.dp(16));
        flyout.setAnimationStyle(android.R.style.Animation_Dialog);
        int[] xy = flyoutAt(width, height);
        flyout.showAtLocation(anchor.getRootView(), Gravity.NO_GRAVITY, xy[0], xy[1]);
    }
    /** Beside the menu on its left, level with the 생각 깊이 row, when there is room; else just above that row (below when no room above). */
    int[] flyoutAt(int width, int height) {
        int screenWidth = activity.getResources().getDisplayMetrics().widthPixels, screenHeight = activity.getResources().getDisplayMetrics().heightPixels;
        // On-screen places, made relative to the screen's own window (the menu is a popup: its own window coordinates would not do).
        int[] menu = new int[2], row = new int[2], base = new int[2];
        card.getLocationOnScreen(menu); depthRow.getLocationOnScreen(row); anchor.getRootView().getLocationOnScreen(base);
        menu[0] -= base[0]; menu[1] -= base[1]; row[0] -= base[0]; row[1] -= base[1];
        int x, y;
        if (menu[0] - width - ui.dp(8) >= ui.dp(8)) {
            x = menu[0] - width - ui.dp(8);
            y = Math.max(ui.dp(12), Math.min(row[1] + depthRow.getHeight() - height, screenHeight - height - ui.dp(12)));
        } else {
            x = Math.max(ui.dp(12), Math.min(menu[0] + card.getWidth() - width, screenWidth - width - ui.dp(12)));
            y = row[1] - height - ui.dp(4);
            if (y < ui.dp(12)) y = Math.min(row[1] + depthRow.getHeight() + ui.dp(4), screenHeight - height - ui.dp(12));
        }
        return new int[]{x, y};
    }
    /** Claude's Ultracode switch: big work split up, each conclusion checked, gaps closed (ClaudeClient.ULTRACODE). Saved at once. */
    private View ultracode() {
        LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(40)); row.setPadding(ui.dp(14), 0, ui.dp(10), 0); row.setGravity(Gravity.CENTER_VERTICAL);
        row.addView(ui.text("Ultracode", 14.5f, 500, TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        android.widget.Switch toggle = ui.toggle("", store.claude.ultracode(), on -> {
            store.claude.ultracode(on); describe(row, on);
        });
        toggle.setPadding(0, 0, 0, 0); toggle.setSwitchPadding(0); toggle.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        row.addView(toggle, new LinearLayout.LayoutParams(-2, -2));
        describe(row, toggle.isChecked()); row.setOnClickListener(v -> toggle.setChecked(!toggle.isChecked())); Ui.pressable(row, true);
        return row;
    }
    private static void describe(View row, boolean on) { row.setContentDescription("Ultracode, " + (on ? "켜짐" : "꺼짐") + " · 큰 일은 나눠서 끝까지 검증해 답해요"); }
    private void set(String engine, String model, String effort) {
        if (flyout != null) flyout.dismiss();
        if (effort.equals(selection.effort(engine, model))) return;
        confirm.accept(() -> {
            try { selection.effort(engine, model, effort); } catch (Exception e) { store.error(e.getMessage()); return; }
            changed.run(); if (window != null && window.isShowing()) main();
        });
    }
    /** The five AIs: the one answering checked, each with its model or what it still needs. */
    private void engines() {
        card.removeAllViews();
        card.addView(back("다른 AI"));
        String engine = selection.engine();
        for (String option : ENGINES) {
            String model = selection.model(option);
            String detail = store.ready(option) ? (model.isBlank() ? "" : catalog.label(option, model)) : "연결 필요";
            // Picking an AI goes straight on to its models (and 생각 깊이) in the same menu, as choosing a model should;
            // only an AI still to be connected closes it (its settings open instead).
            card.addView(row(WorkBook.author(option), detail, false, option.equals(engine), () -> {
                if (option.equals(selection.engine())) { main(); return; }
                confirm.accept(() -> {
                    chooseEngine.accept(option);
                    if (window != null && window.isShowing() && store.ready(option) && option.equals(selection.engine())) main(); else dismiss();
                });
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
        LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(40)); row.setPadding(ui.dp(16), 0, ui.dp(14), 0);
        TextView label = ui.text(name, 14.5f, on ? 600 : 450, TEXT); label.setMaxLines(1); label.setEllipsize(TextUtils.TruncateAt.END);
        row.addView(label, new LinearLayout.LayoutParams(0, -2, 1));
        TextView check = ui.text(on ? "✓" : "", 17, 700, ACCENT); check.setGravity(Gravity.END); row.addView(check, new LinearLayout.LayoutParams(ui.dp(28), -2));
        row.setSelected(on); row.setContentDescription(name + (on ? ", 선택됨" : "")); row.setOnClickListener(v -> action.run()); Ui.pressable(row, true);
        return row;
    }
    private View row(String title, String value, boolean arrow, Runnable action) { return row(title, value, arrow, false, action); }
    /** A setting: its name in bold, its value quiet on the right, a chevron when it opens more, a check when chosen. */
    private View row(String title, String value, boolean arrow, boolean on, Runnable action) {
        LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(40)); row.setPadding(ui.dp(16), 0, ui.dp(14), 0);
        row.addView(ui.text(title, 14.5f, 600, TEXT), new LinearLayout.LayoutParams(-2, -2));
        TextView detail = ui.text(value, 13, 450, MUTED); detail.setMaxLines(1); detail.setEllipsize(TextUtils.TruncateAt.END); detail.setGravity(Gravity.END);
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

}
