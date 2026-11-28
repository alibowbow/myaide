package dev.jun.gyeot;

import android.app.Activity;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Toast;
import dev.jun.gyeot.core.Routines;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/** 설정 → 한마디 루틴: the user's words and what each stands for, with a sheet to make, change or delete one. */
final class RoutinesUi {
    private final Activity activity; private final Ui ui; private final AppStore store; private final Runnable redraw;
    RoutinesUi(Activity activity, Ui ui, AppStore store, Runnable redraw) { this.activity = activity; this.ui = ui; this.store = store; this.redraw = redraw; }

    /** The page's content: what a routine is, the routines, and the button that makes one. */
    LinearLayout page() {
        LinearLayout page = ui.column(); JSONArray list = store.routines();
        LinearLayout about = ui.card();
        about.addView(ui.label("자주 시키는 일을 한마디로 묶어 두세요. 음성 비서·메인 대화·작업창에서 그 한마디만 말하거나 보내면 적어 둔 일을 차례로 해요.", 13, MUTED));
        ui.add(page, about, 16);
        if (list.length() > 0) {
            page.addView(ui.sectionHeader("내 루틴"));
            LinearLayout group = ui.group();
            for (int i = 0; i < list.length(); i++) {
                JSONObject routine = list.optJSONObject(i);
                ui.addRow(group, ui.listRow(R.drawable.ic_bolt, MINT, routine.optString("word"), routine.optString("does").replace('\n', ' '), ui.chevron(), () -> edit(routine)), true);
            }
            ui.add(page, group, 16);
        }
        boolean full = list.length() >= Routines.MOST;
        Button make = ui.button(full ? "루틴은 " + Routines.MOST + "개까지예요" : "루틴 만들기", !full, () -> { if (!full) edit(null); });
        make.setEnabled(!full); ui.add(page, make, 0);
        page.addView(ui.footer("한마디만 따로 말했을 때만 루틴으로 알아들어요. 긴 문장 속에 섞여 있으면 보통 말로 들어요. 음성 대화 중에 바꿨다면 위에 뜨는 ‘지금 적용’을 눌러 주세요. "
            + "할 일에 ‘출근 체크 매크로 실행’처럼 매크로를 적으면 음성 비서가 그 매크로를 그대로 해요."));
        // 매크로: what the user did by hand once, done again (MacrosUi).
        new MacrosUi(activity, ui, redraw).section(page);
        return page;
    }

    /** Makes a routine (null) or changes one. */
    void edit(JSONObject routine) {
        boolean fresh = routine == null;
        Ui.Sheet sheet = ui.selectionSheet(fresh ? "루틴 만들기" : "루틴 고치기", "한마디만 말하면 아래에 적은 일을 해요");
        EditText word = ui.input("한마디 · 예: 퇴근", 1, Routines.WORD), does = ui.input("할 일 · 예: 오늘 남은 일정을 알려 주고, 내일 아침 날씨도 알려 줘", 4, Routines.DOES);
        word.setContentDescription("한마디"); does.setContentDescription("할 일");
        if (!fresh) { word.setText(routine.optString("word")); does.setText(routine.optString("does")); }
        LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.leftMargin = first.rightMargin = ui.dp(16); first.bottomMargin = ui.dp(10);
        LinearLayout.LayoutParams second = new LinearLayout.LayoutParams(-1, -2); second.leftMargin = second.rightMargin = ui.dp(16); second.bottomMargin = ui.dp(12);
        sheet.body.addView(word, first); sheet.body.addView(does, second);
        if (!fresh) {
            Button delete = ui.button("이 루틴 지우기", false, () -> { store.saveRoutines(Routines.remove(store.routines(), routine.optString("id"))); sheet.dismiss(); redraw.run(); });
            delete.setTextColor(RED);
            LinearLayout.LayoutParams below = new LinearLayout.LayoutParams(-1, -2); below.leftMargin = below.rightMargin = ui.dp(16); below.bottomMargin = ui.dp(8);
            sheet.body.addView(delete, below);
        }
        sheet.primary.setText("저장");
        sheet.primary.setOnClickListener(v -> {
            try { store.saveRoutines(Routines.save(store.routines(), fresh ? "" : routine.optString("id"), word.getText().toString(), does.getText().toString())); }
            catch (IllegalArgumentException refused) { Toast.makeText(activity, refused.getMessage(), Toast.LENGTH_LONG).show(); return; }
            sheet.dismiss(); redraw.run();
        });
        sheet.show();
    }
}
