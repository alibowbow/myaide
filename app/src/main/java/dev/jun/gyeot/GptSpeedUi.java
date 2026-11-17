package dev.jun.gyeot;

import android.app.AlertDialog;
import android.content.Context;
import android.widget.LinearLayout;
import dev.jun.gyeot.core.GptSpeed;
import java.util.function.Consumer;

/** Explicit, scoped opt-in. Never changes credentials, projects, or reasoning. */
final class GptSpeedUi {
    static void add(Context context, Ui ui, LinearLayout box, OpenAiAccount account, String model, String selected, Consumer<String> save) {
        box.addView(ui.sectionHeader("속도 · 추론 수준과 별도"));
        String consentEpoch = account.speedEpoch();
        boolean supported = GptSpeed.supported(account.describe().optString("mode"), model, account.models());
        box.addView(ui.footer("Standard는 기존 요청 방식(프로젝트 기본값)을 유지해요. 프로젝트가 Fast로 설정돼 있으면 Fast 요금이 적용될 수 있어요. 여기서 Fast를 선택하면 추가 요금으로 빠른 처리를 요청하며 추론 수준은 유지해요."));
        LinearLayout row = ui.row();
        android.widget.Button standard = ui.chip("Standard" + ("fast".equals(selected) ? "" : " ✓"), () -> save.accept("standard"));
        android.widget.Button fast = ui.chip("Fast" + ("fast".equals(selected) ? " ✓" : ""), () -> {
            if (!supported) return;
            new AlertDialog.Builder(context).setTitle("Fast 사용 · API 요금 증가")
                .setMessage("이 범위에서 선택한 GPT 모델의 다음 요청부터 Fast를 요청해요. Standard보다 토큰당 요금이 높고, 도구 실행 중 후속 요청에도 적용돼요. 추론 수준과 연결 계정은 바뀌지 않아요. 계속할까요?")
                .setNegativeButton("취소", null).setPositiveButton("Fast 선택", (dialog, which) -> {
                    if (!consentEpoch.equals(account.speedEpoch())) {
                        android.widget.Toast.makeText(context, "GPT 연결이 바뀌었어요. 모델 설정을 다시 열어 주세요.", android.widget.Toast.LENGTH_LONG).show(); return;
                    }
                    save.accept("fast");
                }).show();
        });
        fast.setEnabled(supported); row.addView(standard, new LinearLayout.LayoutParams(0, -2, 1)); row.addView(fast, new LinearLayout.LayoutParams(0, -2, 1)); box.addView(row);
        if (!supported) box.addView(ui.footer(account.usesPlan() ? "현재 ChatGPT 로그인 연결에서는 Fast 지원을 확인하지 못했어요. ChatGPT Work의 속도 설정을 이 연결에 그대로 적용하지 않아요." : "현재 계정의 이 모델에 대해 Fast 지원이 확인되지 않았어요. 연결이나 모델을 자동으로 바꾸지 않아요."));
    }
}
