package dev.jun.gyeot;

import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import dev.jun.gyeot.core.InterpreterConfig;

/** Peer language selectors shared by the interpreter home and voice settings. */
final class InterpreterUi {
    private final Ui ui;
    private final AppStore store;
    private final Runnable changed;
    private final LinearLayout root;
    InterpreterUi(Ui ui, AppStore store, Runnable changed) {
        this.ui = ui; this.store = store; this.changed = changed; root = ui.column(); render();
    }
    View view() { return root; }
    private void render() {
        root.removeAllViews(); InterpreterConfig pair = store.interpreterConfig();
        LinearLayout row = ui.row();
        Button first = ui.button("A 언어\n" + InterpreterConfig.label(pair.a) + " ▾", false, () -> choose(true));
        first.setContentDescription("A 언어 선택 · " + InterpreterConfig.label(pair.a)); first.setMinimumHeight(ui.dp(76));
        row.addView(first, new LinearLayout.LayoutParams(0, -2, 1));
        Button swap = ui.chip("⇄", () -> save(store.interpreterConfig().swapped())); swap.setContentDescription("A와 B 언어 서로 바꾸기");
        LinearLayout.LayoutParams middle = new LinearLayout.LayoutParams(ui.dp(48), ui.dp(48)); middle.setMargins(ui.dp(6), 0, ui.dp(6), 0); row.addView(swap, middle);
        Button second = ui.button("B 언어\n" + InterpreterConfig.label(pair.b) + " ▾", false, () -> choose(false));
        second.setContentDescription("B 언어 선택 · " + InterpreterConfig.label(pair.b)); second.setMinimumHeight(ui.dp(76));
        row.addView(second, new LinearLayout.LayoutParams(0, -2, 1)); root.addView(row);
        String[] modes = {InterpreterConfig.AUTO, InterpreterConfig.A_TO_B, InterpreterConfig.B_TO_A};
        int selected = pair.automatic() ? 0 : InterpreterConfig.A_TO_B.equals(pair.mode) ? 1 : 2;
        LinearLayout direction = ui.segmented(new String[]{"자동 ↔", "A → B", "B → A"}, selected, index -> save(store.interpreterConfig().mode(modes[index])));
        direction.getChildAt(0).setContentDescription("자동 양방향" + (pair.automatic() ? ", 선택됨" : ""));
        LinearLayout.LayoutParams dp = new LinearLayout.LayoutParams(-1, -2); dp.topMargin = ui.dp(10); root.addView(direction, dp);
        root.addView(ui.footer(pair.automatic() ? "" + pair.description() + " · 말하는 언어에 맞춰 자동 통역해요. 서로 번갈아 말해 주세요."
            : pair.description() + " · 한 방향으로 계속 통역해요."));
        if (pair.automatic()) root.addView(ui.label("양방향은 두 번역 연결의 사용량이 함께 집계돼요.", 11, Ui.MUTED));
    }
    private void save(InterpreterConfig pair) {
        if (pair.fingerprint().equals(store.interpreterConfig().fingerprint())) return;
        store.interpreterConfig(pair); render(); changed.run();
    }
    private void choose(boolean first) {
        InterpreterConfig pair = store.interpreterConfig(); String selected = first ? pair.a : pair.b, other = first ? pair.b : pair.a;
        Ui.Sheet sheet = ui.selectionSheet((first ? "A" : "B") + " 언어", "각 사람이 사용할 언어를 골라 주세요");
        sheet.primary.setText("닫기");
        for (String[] language : InterpreterConfig.LANGUAGES) {
            String code = language[2]; boolean blocked = code.equals(other), on = code.equals(selected);
            LinearLayout row = ui.row(); row.setMinimumHeight(ui.dp(52)); row.setPadding(ui.dp(12), ui.dp(10), ui.dp(12), ui.dp(10));
            row.addView(ui.text(language[1], 16, on ? 650 : 450, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
            row.addView(ui.text(blocked ? (first ? "B" : "A") + "에서 사용 중" : on ? "✓" : "", 12, 500, Ui.MUTED));
            row.setContentDescription(language[1] + (blocked ? ", 다른 쪽에서 사용 중" : on ? ", 선택됨" : ""));
            row.setEnabled(!blocked); row.setAlpha(blocked ? .45f : 1f);
            if (!blocked) {
                row.setOnClickListener(v -> {
                    sheet.dismiss(); InterpreterConfig current = store.interpreterConfig();
                    if (!code.equals(first ? current.b : current.a)) save(current.language(first, code));
                }); Ui.pressable(row, true);
            }
            sheet.body.addView(row);
        }
        sheet.show();
    }
}
