package dev.jun.gyeot;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Macros;
import java.util.List;
import static dev.jun.gyeot.Ui.*;

/**
 * 설정 → 한마디 루틴 → 매크로, compact: one group of rows (매크로 녹화, then each macro with its steps and a small 실행), and
 * a macro's sheet to rename it, see its steps, run it now or delete it. A recording's 끝 and a stopped run's notification
 * open this page with that macro's sheet (opening).
 */
final class MacrosUi {
    /** The macro whose sheet opens with the page (its id), from an intent. */
    static final String OPEN = "gyeot.macro";
    private final Activity activity; private final Ui ui; private final Runnable redraw;
    MacrosUi(Activity activity, Ui ui, Runnable redraw) { this.activity = activity; this.ui = ui; this.redraw = redraw; }

    /** The page that shows a macro's sheet (MainActivity's 설정 → 한마디 루틴). */
    static Intent opening(Context c, String id) {
        return new Intent(c, MainActivity.class).putExtra("tab", 2).putExtra(MainActivity.SETTINGS_PAGE, "routines").putExtra(OPEN, id == null ? "" : id)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);
    }
    /** Opens it from anywhere: from 폰 조작 (Android lets a bound accessibility service open screens from the background). */
    static void open(Context c, String id) {
        Intent go = opening(c, id);
        if (!PhoneService.launch(go)) try { c.startActivity(go); } catch (RuntimeException refused) { /* the notification still opens it */ }
    }

    /** The section: its header, rows and the line under them, added to the page. */
    void section(LinearLayout page) {
        page.addView(ui.sectionHeader("매크로"));
        LinearLayout group = ui.group();
        boolean recording = MacroRecorder.recording();
        ui.addRow(group, ui.listRow(R.drawable.ic_record, RED, recording ? "녹화 중 · " + MacroRecorder.count() + "단계" : "매크로 녹화",
            recording ? "다 하면 화면 위의 ‘끝’을 눌러 주세요" : "한 번 직접 하면 다음엔 그대로 해요", recording ? null : ui.chevron(), recording ? null : this::record), true);
        List<Macros.Macro> all = MacroBook.list(activity);
        for (Macros.Macro m : all)
            ui.addRow(group, ui.listRow(R.drawable.ic_play, MINT, m.name(), Macros.summary(m), ui.smallChip("실행", () -> run(m)), () -> sheet(m)), true);
        ui.add(page, group, 0);
        page.addView(ui.footer("퍼레에게 ‘출근 체크 해 줘’처럼 이름을 말하거나, 조건 알림(‘차 타면 출근 체크 매크로 실행해 줘’)·예약 작업에 걸어 두면 AI 없이 그대로 해요. "
            + "결제·송금·삭제 버튼 앞에서는 멈추고, 비밀번호·은행 앱은 녹화하지도 실행하지도 않아요."));
        String asked = activity.getIntent() == null ? null : activity.getIntent().getStringExtra(OPEN);
        if (asked != null && !asked.isEmpty()) {
            activity.getIntent().removeExtra(OPEN);
            Macros.Macro m = Macros.byId(all, asked);
            if (m != null) page.post(() -> { if (!activity.isFinishing()) sheet(m); });
        }
    }

    private void record() {
        try { MacroRecorder.start(activity, "", true); Toast.makeText(activity, "녹화를 시작했어요. 할 일을 직접 한 뒤 화면 위의 ‘끝’을 눌러 주세요.", Toast.LENGTH_LONG).show(); }
        catch (RuntimeException refused) {
            Toast.makeText(activity, refused.getMessage(), Toast.LENGTH_LONG).show();
            if (!PhoneService.connected()) try { activity.startActivity(BuddyMisses.fixIntent(activity, "accessibility")); } catch (RuntimeException none) { /* said above */ }
        }
        redraw.run();
    }
    private void run(Macros.Macro m) {
        new Thread(() -> {
            BuddyHands.Started s = MacroRunner.start(activity, m, false, "", null);
            activity.runOnUiThread(() -> {
                if (s.ok()) return;
                Toast.makeText(activity, s.say(), Toast.LENGTH_LONG).show();
                android.content.Intent fix = BuddyMisses.fixIntent(activity, s.fix());
                if (fix != null && !"settings:routines".equals(s.fix())) try { activity.startActivity(fix); } catch (RuntimeException none) { /* said above */ }
            });
        }, "gyeot-macro-start").start();
    }

    /** A macro's sheet: its name (to rename), its steps, 지금 실행 and 지우기. */
    void sheet(Macros.Macro m) {
        Ui.Sheet sheet = ui.selectionSheet(m.name(), Macros.summary(m));
        EditText name = ui.input("매크로 이름 · 예: 출근 체크", 1, Macros.NAME);
        name.setText(m.name()); name.setContentDescription("매크로 이름");
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.leftMargin = first.rightMargin = ui.dp(16); first.bottomMargin = ui.dp(10);
        sheet.body.addView(name, first);
        TextView steps = ui.label(Macros.steps(m), 13, MUTED); steps.setLineSpacing(ui.dp(3), 1);
        LinearLayout.LayoutParams list = new LinearLayout.LayoutParams(-1, -2); list.leftMargin = list.rightMargin = ui.dp(20); list.bottomMargin = ui.dp(10);
        sheet.body.addView(steps, list);
        if (!m.last().isEmpty() && !m.last().startsWith("✓")) {
            TextView last = ui.label("지난번: " + m.last(), 13, WARNING);
            sheet.body.addView(last, list);
        }
        LinearLayout actions = ui.row();
        Button now = ui.smallChip("지금 실행", () -> { sheet.dismiss(); run(m); });
        Button delete = ui.smallChip("지우기", () -> ui.confirm("‘" + m.name() + "’ 매크로를 지울까요?", "지우기", () -> { MacroBook.change(activity, all -> Macros.remove(all, m.id())); sheet.dismiss(); redraw.run(); }).show());
        delete.setTextColor(RED);
        actions.addView(now); actions.addView(delete);
        LinearLayout.LayoutParams row = new LinearLayout.LayoutParams(-1, -2); row.leftMargin = row.rightMargin = ui.dp(12); row.bottomMargin = ui.dp(8);
        sheet.body.addView(actions, row);
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            try { MacroBook.change(activity, all -> Macros.save(all, Macros.byId(all, m.id()) == null ? m : Macros.byId(all, m.id()).named(name.getText().toString()))); }
            catch (IllegalArgumentException refused) { Toast.makeText(activity, refused.getMessage(), Toast.LENGTH_LONG).show(); return; }
            sheet.dismiss(); redraw.run();
        });
        sheet.show();
    }
}
