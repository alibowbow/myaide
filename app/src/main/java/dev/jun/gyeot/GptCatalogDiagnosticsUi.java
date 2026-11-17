package dev.jun.gyeot;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;

/** User-opened local evidence; clipboard copy is explicit and never transmits a report. */
final class GptCatalogDiagnosticsUi {
    static AlertDialog show(Context context, OpenAiAccount account) {
        String epoch=account.speedEpoch();
        String captured=account.modelsDiagnostics();
        String report=epoch.equals(account.speedEpoch()) ? captured : "GPT 연결이 바뀌었어요. 진단창을 다시 열어 확인해 주세요.";
        AlertDialog dialog=new AlertDialog.Builder(context).setTitle("모델 목록 진단").setMessage(report)
            .setNegativeButton("닫기",null).setPositiveButton("진단 복사",null).create();
        dialog.show();
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            if(!dialog.isShowing()||!epoch.equals(account.speedEpoch()))return;
            ClipboardManager clipboard=(ClipboardManager)context.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard!=null)clipboard.setPrimaryClip(ClipData.newPlainText("GPT 모델 목록 진단",report));
            dialog.dismiss();
        });
        return dialog;
    }
}
