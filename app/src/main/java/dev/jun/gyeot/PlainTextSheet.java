package dev.jun.gyeot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import static dev.jun.gyeot.Ui.*;

/**
 * 텍스트로 보기: a text shown whole in one block, to read, select and copy as it is. An answer is shown without
 * Markdown's marks (Markdown.plain), so what is copied reads well where Markdown is not drawn, such as a messenger.
 */
final class PlainTextSheet {
    private PlainTextSheet() {}
    /** An answer as plain text. */
    static void answer(Activity activity, String markdown) { show(activity, "텍스트로 보기", "기호 없는 글 · 메신저에 붙여 넣기 좋아요", dev.jun.gyeot.core.Markdown.plain(markdown), "복사했어요"); }
    /** title over one line of subtitle (may be empty), then the text in a block and 전체 복사 under it. */
    static void show(Activity activity, String title, String subtitle, String text, String copied) {
        Ui ui = new Ui(activity);
        Ui.Sheet sheet = ui.selectionSheet(title, subtitle == null ? "" : subtitle);
        TextView body = ui.text(text, 14, 400, TEXT);
        body.setTextIsSelectable(true); body.setLineSpacing(ui.dp(4), 1); body.setPadding(ui.dp(14), ui.dp(12), ui.dp(14), ui.dp(12)); body.setBackground(ui.shape(FILL, 14));
        LinearLayout.LayoutParams around = new LinearLayout.LayoutParams(-1, -2); around.leftMargin = around.rightMargin = ui.dp(16); around.bottomMargin = ui.dp(12);
        sheet.body.addView(body, around);
        sheet.primary.setText("전체 복사");
        sheet.primary.setOnClickListener(v -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText(title, text)); Toast.makeText(activity, copied, Toast.LENGTH_SHORT).show(); }
        });
        sheet.show();
    }
}
