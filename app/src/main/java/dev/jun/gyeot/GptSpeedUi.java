package dev.jun.gyeot;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;
import dev.jun.gyeot.core.GptSpeed;
import java.util.function.Consumer;

/** Explicit, scoped opt-in. Never changes credentials, projects, or reasoning. */
final class GptSpeedUi {
    static void add(Context context, Ui ui, LinearLayout box, OpenAiAccount account, String model, String selected, Consumer<String> save) {
        String consentEpoch = account.speedEpoch();
        boolean supported = GptSpeed.supported(account.describe().optString("mode"), model, account.models());
        // One slim line: 속도 and a small Standard | Fast switch at its end. What Fast costs is said when it is picked.
        LinearLayout row = ui.row(); row.setGravity(android.view.Gravity.CENTER_VERTICAL); row.setPadding(ui.dp(4), ui.dp(8), 0, ui.dp(4));
        row.addView(ui.text("속도", 14, 650, Ui.TEXT), new LinearLayout.LayoutParams(0, -2, 1));
        // What is saved now; going back (cancel, an unsupported pick, a changed connection) only moves the switch, it saves nothing.
        String[] saved = {"fast".equals(selected) ? "fast" : "standard"}; boolean[] quiet = {false};
        LinearLayout[] track = {null};
        Runnable back = () -> { quiet[0] = true; try { track[0].getChildAt("fast".equals(saved[0]) ? 1 : 0).performClick(); } finally { quiet[0] = false; } };
        track[0] = ui.switcher(new String[]{"Standard", "Fast"}, "fast".equals(saved[0]) ? 1 : 0, index -> {
            if (quiet[0]) return;
            if (index == 0) { if (!"standard".equals(saved[0])) { saved[0] = "standard"; save.accept("standard"); } return; }
            if ("fast".equals(saved[0])) return;
            if (!supported) { back.run(); return; }
            new AlertDialog.Builder(context).setTitle("Fast 사용 · API 요금 증가")
                .setMessage("이 범위에서 선택한 GPT 모델의 다음 요청부터 Fast를 요청해요. Standard보다 토큰당 요금이 높고, 도구 실행 중 후속 요청에도 적용돼요. 추론 수준과 연결 계정은 바뀌지 않아요. 계속할까요?")
                .setNegativeButton("취소", (dialog, which) -> back.run())
                .setOnCancelListener(dialog -> back.run())
                .setPositiveButton("Fast 선택", (dialog, which) -> {
                    if (!consentEpoch.equals(account.speedEpoch())) {
                        android.widget.Toast.makeText(context, "GPT 연결이 바뀌었어요. 모델 설정을 다시 열어 주세요.", android.widget.Toast.LENGTH_LONG).show(); back.run(); return;
                    }
                    saved[0] = "fast"; save.accept("fast");
                }).show();
        });
        if (!supported) { track[0].getChildAt(1).setEnabled(false); track[0].getChildAt(1).setAlpha(0.4f); }
        row.addView(track[0]); box.addView(row);
    }
}
