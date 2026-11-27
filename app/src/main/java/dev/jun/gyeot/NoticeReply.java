package dev.jun.gyeot;

import android.app.Activity;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.RemoteInput;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.NoticeActions;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import static dev.jun.gyeot.Ui.*;

/**
 * 답장 (알림을 행동으로): a small sheet over the message's words with a short reply draft by 퍼레's brain (BuddyTalk's quick
 * models; the message is data, not instructions), which the user can change; sent only when they tap 보내기, through the
 * notification's own reply action (RemoteInput, as a watch answers). A notice without one says so and offers to open the chat.
 * Nothing is ever sent by itself.
 */
final class NoticeReply {
    private NoticeReply() {}

    /** The notice's own reply action: a free-form RemoteInput (the one marked as reply first), from its actions or its watch actions. */
    static Notification.Action replyAction(Notification n) {
        if (n == null) return null;
        List<Notification.Action> all = new ArrayList<>();
        if (n.actions != null) all.addAll(Arrays.asList(n.actions));
        try { all.addAll(new Notification.WearableExtender(n).getActions()); } catch (RuntimeException unreadable) { /* the phone's own */ }
        Notification.Action best = null;
        for (Notification.Action a : all) {
            if (a == null || a.actionIntent == null || a.getRemoteInputs() == null) continue;
            boolean free = false; for (RemoteInput r : a.getRemoteInputs()) if (r != null && r.getAllowFreeFormInput()) free = true;
            if (!free) continue;
            if (a.getSemanticAction() == Notification.Action.SEMANTIC_ACTION_REPLY) return a;
            if (best == null) best = a;
        }
        return best;
    }
    /** The words into the reply action, as the user typed them (the app then sends them as its own reply). */
    static void send(Context c, Notification.Action reply, String text) throws PendingIntent.CanceledException {
        Intent fill = new Intent(); Bundle results = new Bundle();
        for (RemoteInput r : reply.getRemoteInputs()) if (r.getAllowFreeFormInput()) results.putCharSequence(r.getResultKey(), text);
        RemoteInput.addResultsToIntent(reply.getRemoteInputs(), fill, results);
        RemoteInput.setResultsSource(fill, RemoteInput.SOURCE_FREE_FORM_INPUT);
        reply.actionIntent.send(c, 0, fill);
    }

    /** The sheet over activity; closed runs when it goes (sent, closed or the chat opened). */
    static Ui.Sheet show(Activity activity, NoticeActions.Act a, NoticeListener.Live live, Runnable closed) {
        Ui ui = new Ui(activity);
        Handler main = new Handler(Looper.getMainLooper());
        java.time.ZoneId zone = java.time.ZoneId.systemDefault(); long now = System.currentTimeMillis();
        Notification.Action reply = live == null ? null : live.reply();
        String who = a.who().isEmpty() ? "" : a.who();
        Ui.Sheet sheet = ui.selectionSheet(who.isEmpty() ? "답장" : who + "에게 답장", a.name() + (a.begin() > 0 ? " · " + NoticeActions.when(a.begin(), a.timed(), now, zone) + (a.place().isEmpty() ? "" : " " + a.place()) : ""));
        TextView said = ui.text("“" + a.text().replace('\n', ' ') + "”", SUBHEAD, 450, MUTED);
        said.setMaxLines(3); said.setEllipsize(android.text.TextUtils.TruncateAt.END); said.setPadding(ui.dp(4), 0, ui.dp(4), ui.dp(10));
        sheet.body.addView(said);
        EditText field = ui.input("보낼 답장", 2, 300); field.setContentDescription("보낼 답장");
        boolean[] edited = {false}, filling = {true};
        field.setText(NoticeActions.draft(a, now, zone)); filling[0] = false;
        field.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int st, int c, int af) {}
            @Override public void onTextChanged(CharSequence s, int st, int b, int c) { if (!filling[0]) edited[0] = true; }
            @Override public void afterTextChanged(Editable s) {}
        });
        sheet.body.addView(field, new LinearLayout.LayoutParams(-1, -2));
        TextView status = ui.footer(reply == null ? "이 알림에는 바로 답장하는 버튼이 없어요. 채팅을 열어 보내 주세요." : "퍼레가 초안을 쓰는 중이에요 · 고쳐서 보내도 돼요");
        status.setPadding(ui.dp(4), ui.dp(8), ui.dp(4), 0);
        sheet.body.addView(status);
        // Compact pills at the end of one line, not a full-width bar (CLAUDE.md: 화면은 컴팩트하게).
        LinearLayout acts = ui.row(); acts.setGravity(Gravity.END | Gravity.CENTER_VERTICAL); acts.setPadding(0, ui.dp(12), 0, 0);
        Runnable openChat = () -> { boolean opened = NoticeActActivity.open(activity, a, live); if (!opened) Toast.makeText(activity, "채팅을 열 수 없어요", Toast.LENGTH_SHORT).show(); else sheet.dismiss(); };
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
        if (reply == null) {
            acts.addView(ui.pill("복사", 0, false, () -> {
                ClipboardManager clip = activity.getSystemService(ClipboardManager.class);
                if (clip != null) { clip.setPrimaryClip(ClipData.newPlainText("답장", field.getText().toString())); Toast.makeText(activity, "복사했어요", Toast.LENGTH_SHORT).show(); }
            }));
            acts.addView(ui.pill("채팅 열기", 0, true, openChat), gap);
        } else {
            acts.addView(ui.pill("채팅 열기", 0, false, openChat));
            TextView send = ui.pill("보내기", R.drawable.ic_send, true, () -> {
                String text = field.getText().toString().strip();
                if (text.isEmpty()) { Toast.makeText(activity, "보낼 말을 적어 주세요", Toast.LENGTH_SHORT).show(); return; }
                try {
                    send(activity, reply, text);
                    try { ((GyeotApp) activity.getApplicationContext()).store().phoneLog("● 답장 보냄: " + a.name()); } catch (RuntimeException ignored) {}
                    Toast.makeText(activity, "보냈어요", Toast.LENGTH_SHORT).show();
                    sheet.dismiss();
                } catch (PendingIntent.CanceledException | RuntimeException gone) {
                    status.setText("알림이 사라져서 여기서는 보낼 수 없어요. 채팅을 열어 보내 주세요.");
                }
            });
            send.setContentDescription("보내기: " + (who.isEmpty() ? "" : who + "에게 ") + "답장 보내기");
            acts.addView(send, gap);
        }
        sheet.body.addView(acts, new LinearLayout.LayoutParams(-1, -2));
        sheet.footer.setVisibility(View.GONE);
        sheet.setOnDismissListener(d -> { if (closed != null) closed.run(); });
        sheet.show();
        // 퍼레's brain writes a better draft; it fills the field only if the user has not changed it yet.
        if (reply != null) try {
            BuddyTalk.ask(activity, NoticeActions.DRAFT_RULES, NoticeActions.draftInput(a, zone), 0.4, 8_000, NoticeActions::cleanDraft,
                draft -> main.post(() -> {
                    if (!sheet.isShowing()) return;
                    if (!edited[0]) { filling[0] = true; field.setText(draft); field.setSelection(field.length()); filling[0] = false; }
                    status.setText("퍼레가 쓴 초안이에요 · 고쳐서 보내도 돼요");
                }),
                why -> main.post(() -> { if (sheet.isShowing()) status.setText("기본 초안이에요 · 고쳐서 보내도 돼요"); }));
        } catch (RuntimeException noBrain) { status.setText("기본 초안이에요 · 고쳐서 보내도 돼요"); }
        return sheet;
    }
}
