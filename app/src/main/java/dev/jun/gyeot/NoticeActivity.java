package dev.jun.gyeot;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.WindowInsets;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import dev.jun.gyeot.core.Notices;
import java.util.List;

/**
 * 내 휴대폰 → 알림 비서: turning it on (with Android's 알림 접근 for this app), a summary of what came in (Gemma 4), and the
 * notifications kept for a day. The voice assistant reads them with recent_notifications; 오늘의 제안 sees the night's.
 */
public final class NoticeActivity extends Activity {
    private Ui ui; private AppStore store; private LinearLayout body; private TextView summary;
    private final Handler main = new Handler(Looper.getMainLooper());
    private boolean destroyed;

    @Override public void onCreate(Bundle state) { super.onCreate(state); ui = new Ui(this); store = ((GyeotApp) getApplication()).store(); draw(); }
    @Override protected void onResume() { super.onResume(); if (body != null) draw(); }
    @Override protected void onDestroy() { destroyed = true; super.onDestroy(); }

    private void draw() {
        ScrollView scroll = new ScrollView(this); body = ui.column(); body.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(24)); scroll.addView(body);
        scroll.setBackgroundColor(Ui.BG);
        scroll.setOnApplyWindowInsetsListener((v, insets) -> {
            var bars = insets.getInsets(WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            v.setPadding(bars.left, bars.top, bars.right, Math.max(bars.bottom, insets.getInsets(WindowInsets.Type.ime()).bottom)); return insets;
        });
        setContentView(scroll); scroll.requestApplyInsets();
        View back = ui.backButton("내 휴대폰", () -> { if (!HtmlWallpaper.backHome(this)) finish(); }); back.setMinimumHeight(ui.dp(48)); body.addView(back, new LinearLayout.LayoutParams(-2, -2));
        TextView title = ui.title("알림 비서", 26); title.setAccessibilityHeading(true); ui.add(body, title, 4);
        ui.add(body, ui.label("다른 앱의 알림을 하루 동안 모아 두고, 요약하거나 음성 대화에서 “무슨 알림 왔어?”라고 물으면 알려 드려요.", 13, Ui.MUTED), 12);

        boolean on = NoticeListener.on(this), allowed = NoticeListener.allowed(this);
        LinearLayout use = ui.group();
        ui.addRow(use, ui.switchRow("알림 비서 쓰기", on ? (allowed ? "켜져 있어요 · 알림을 모으는 중" : "‘알림 접근’을 허용해야 모을 수 있어요") : "꺼져 있어요", on, value -> {
            store.settings.edit().putBoolean("noticeOn", value).apply();
            if (!value) NoticeListener.clear(this);
            else if (!NoticeListener.allowed(this)) openAccess();
            draw();
        }), true);
        ui.addRow(use, ui.listRow(R.drawable.ic_lock, Ui.GREEN, "알림 접근 권한", allowed ? "허용됨" : "허용 안 됨 · 눌러서 허용하기", ui.chevron(), this::openAccess), false);
        body.addView(use);
        body.addView(ui.footer("알림은 이 폰에만 하루 동안 남아요. 인증번호·비밀번호가 담긴 알림은 모으지 않아요. 요약할 때만 Gemini(Gemma 4)로 보내요."));
        // 알림을 행동으로: what the home screen does with them (core/NoticeActions).
        body.addView(ui.footer("택배·예약·메신저 약속은 홈 화면에 작은 버튼으로 떠요(택배 오늘 도착 · 일정에 넣기 · 답장). 답장은 보내기를 눌러야만 보내져요. 카드·은행·증권 알림은 읽지 않아요."));

        List<Notices.Notice> list = NoticeListener.list(this);
        body.addView(ui.sectionHeader("오늘 온 알림 · " + list.size() + "개"));
        LinearLayout actions = ui.row();
        actions.addView(ui.chip("AI로 요약", this::summarize));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-2, -2); gap.leftMargin = ui.dp(8);
        actions.addView(ui.chip("모두 지우기", () -> new AlertDialog.Builder(this).setTitle("모은 알림을 모두 지울까요?").setNegativeButton("취소", null)
            .setPositiveButton("지우기", (d, w) -> { NoticeListener.clear(this); draw(); }).show()), gap);
        ui.add(body, actions, 8);
        summary = ui.text("", 14, 400, Ui.TEXT); summary.setLineSpacing(ui.dp(3), 1); summary.setTextIsSelectable(true); summary.setVisibility(View.GONE);
        ui.add(body, summary, 8);
        long now = System.currentTimeMillis();
        List<String> lines = Notices.lines(Notices.recent(list, now, 24, 100), now);
        if (lines.isEmpty()) ui.add(body, ui.label(on ? "아직 모은 알림이 없어요." : "알림 비서를 켜면 여기에 모여요.", 14, Ui.MUTED), 8);
        else { LinearLayout group = ui.group(); for (int i = 0; i < lines.size(); i++) { TextView row = ui.text(lines.get(i), 13.5f, 420, Ui.TEXT); row.setPadding(ui.dp(14), ui.dp(10), ui.dp(14), ui.dp(10)); group.addView(row); } body.addView(group); }
    }
    private void openAccess() {
        try { startActivity(new Intent(android.provider.Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)); }
        catch (Exception e) { Toast.makeText(this, "설정 → 알림 → 기기 및 앱 알림(알림 접근)에서 들퍼레를 허용해 주세요.", Toast.LENGTH_LONG).show(); }
    }
    /** AI로 요약: the last day's notices to Gemma 4 (light work), the answer shown above the list. */
    private void summarize() {
        long now = System.currentTimeMillis();
        List<String> lines = Notices.lines(Notices.recent(NoticeListener.list(this), now, 24, 120), now);
        if (lines.isEmpty()) { Toast.makeText(this, "요약할 알림이 없어요.", Toast.LENGTH_SHORT).show(); return; }
        summary.setVisibility(View.VISIBLE); summary.setText("요약하는 중…");
        new Thread(() -> {
            String said;
            try { said = Chores.run(store, Notices.SUMMARY, String.join("\n", lines), true); }
            catch (Exception e) { said = e.getMessage() == null ? "요약하지 못했어요." : e.getMessage(); }
            String text = said;
            main.post(() -> { if (!destroyed && summary != null) summary.setText(RichText.render(text)); });
        }, "notice-summary").start();
    }
    @Override protected void onNewIntent(android.content.Intent intent) { super.onNewIntent(intent); setIntent(intent); }
    @SuppressWarnings("deprecation")
    @Override public void onBackPressed() { if (!HtmlWallpaper.backHome(this)) super.onBackPressed(); }
}
