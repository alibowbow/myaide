package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.Toast;
import dev.jun.gyeot.core.Brand;
import static dev.jun.gyeot.Ui.*;

/**
 * 공유로 받기: another app's 공유 hands text or an address to this app. A see-through screen shows one sheet over that
 * app: send it to the main chat asking for a summary, or put it in the message box to add words first. Either way the
 * main chat opens; nothing is sent until one of the two is tapped, and closing the sheet does nothing.
 */
public final class ShareActivity extends Activity {
    /** The most of a shared text that is taken; the rest is cut with a note. */
    static final int LIMIT = 20_000;
    /** What is asked of a shared text sent as it is. */
    static final String ASK = "정리해 줘";

    /** The shared words: the subject (a page's or video's title) when the text does not hold it, then the text; "" for none. */
    static String shared(Intent intent) {
        if (intent == null || !Intent.ACTION_SEND.equals(intent.getAction())) return "";
        CharSequence body = intent.getCharSequenceExtra(Intent.EXTRA_TEXT), subject = intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT);
        String text = body == null ? "" : body.toString().strip(), title = subject == null ? "" : subject.toString().strip();
        if (!title.isEmpty() && !text.contains(title)) text = text.isEmpty() ? title : title + "\n" + text;
        return text.length() > LIMIT ? text.substring(0, LIMIT) + "\n…(글이 길어 뒷부분은 줄였어요)" : text;
    }

    @Override protected void onCreate(Bundle state) {
        super.onCreate(state);
        AppStore store = ((GyeotApp) getApplication()).store();
        String text = shared(getIntent());
        if (text.isEmpty()) { Toast.makeText(this, "공유된 글이나 주소가 없어요.", Toast.LENGTH_SHORT).show(); finish(); return; }
        Ui ui = new Ui(this);
        String preview = text.replace('\n', ' '); if (preview.length() > 120) preview = preview.substring(0, 120) + "…";
        Ui.Sheet sheet = ui.selectionSheet(Brand.NAME + "에 보내기", preview);
        sheet.primary.setVisibility(View.GONE);
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(R.drawable.ic_sparkle, INDIGO, "정리해 달라고 보내기", "메인 대화에 보내고 바로 답을 받아요", null, () -> {
            try { store.mainAssistant.enqueue(text + "\n\n" + ASK); }
            catch (Exception failed) {
                // Not sent (no model connected, or the text is not accepted): it waits in the message box instead.
                store.mainDraft = text; store.mainWrite = true;
                Toast.makeText(this, failed.getMessage() == null || failed.getMessage().isBlank() ? "보내지 못해서 메시지 칸에 넣어 뒀어요." : failed.getMessage(), Toast.LENGTH_LONG).show();
            }
            open(sheet);
        }), true);
        ui.addRow(group, ui.listRow(R.drawable.ic_chat, BLUE, "메시지 칸에 넣기", "하고 싶은 말을 덧붙여서 보내요", null, () -> { store.mainDraft = text; store.mainWrite = true; open(sheet); }), true);
        if (!dev.jun.gyeot.core.SecurityLink.first(text).isEmpty()) {
            ui.addRow(group,ui.listRow(R.drawable.ic_lock,GREEN,"링크 보안 검사","주소를 열기 전에 기기 안에서 확인해요",null,
                    () -> PhoneSecurityActivity.sharedLink(this,text)),true);
        }
        sheet.body.addView(group);
        sheet.setOnDismissListener(d -> finish());
        sheet.show();
    }
    private void open(Ui.Sheet sheet) {
        startActivity(new Intent(this, MainActivity.class).putExtra(MainNavigation.TARGET, "chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP));
        sheet.dismiss();
    }
}
