package dev.jun.gyeot;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 설정 → 커넥터 → Gmail: Google's own sign-in sheet for Gmail's scopes, the connection's state, whether the main chat uses it
 * and may send, and the one step the Google Cloud project needs besides Drive's registration: the Gmail API turned on.
 */
final class GmailUi {
    static final int CONNECT = 34;
    private final Activity activity; private final Ui ui; private final AppStore store; private final Runnable refresh;
    GmailUi(Activity activity, Ui ui, AppStore store, Runnable refresh) { this.activity = activity; this.ui = ui; this.store = store; this.refresh = refresh; }

    View page() {
        LinearLayout page = ui.column();
        GmailAccount gmail = store.gmail;
        if (!gmail.connected()) {
            page.addView(ui.statusCard(R.drawable.ic_send, Ui.RED, "연결 안 됨", "Google 계정으로 연결해요", null));
            TextView about = ui.text("메인 대화에서 메일을 찾고 읽고, 답장·새 메일을 초안으로 쓰거나 보내요.", 14, 400, Ui.MUTED); about.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), 0); page.addView(about);
            TextView once = ui.label("처음 한 번: Google Drive 등록에 쓴 Google Cloud 프로젝트에서 Gmail API를 켜 주세요", 13, Ui.MUTED); once.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0); page.addView(once);
            page.addView(ui.actions(ui.chip("Gmail API 켜기", () -> open(GoogleDriveUi.CONSOLE + "apis/library/gmail.googleapis.com")),
                ui.chip("앱 등록 안내", () -> activity.startActivity(new Intent(activity, MainActivity.class).putExtra("tab", 2).putExtra(MainActivity.SETTINGS_PAGE, "drive").addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)))));
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.topMargin = ui.dp(16);
            page.addView(ui.button("Google로 연결", true, this::connect), first);
            TextView how = ui.label("Google 계정 선택 → 허용 화면이 떠요. 두 항목(메일 읽기, 초안 쓰기·보내기)을 모두 허용해 주세요. 확인되지 않은 앱이라는 화면이 나오면 ‘계속’을 눌러요. 비밀번호나 키는 저장하지 않아요.", 12, Ui.MUTED);
            how.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0); page.addView(how);
            return page;
        }
        boolean work = store.settings.getBoolean(GmailAccount.WORK, true), sends = gmail.sends();
        page.addView(ui.statusCard(R.drawable.ic_send, Ui.RED, work ? "연결됨" : "꺼 둠", gmail.email() + (work ? sends ? " · 읽기·초안·보내기" : " · 읽기·초안" : " · 메인 대화에서 쓰지 않아요"), null));
        page.addView(ui.sectionHeader("사용"));
        LinearLayout use = ui.group();
        ui.addRow(use, ui.switchRow("메인 대화에서 Gmail 쓰기", "끄면 메일을 찾거나 쓰지 않아요", work, value -> { store.settings.edit().putBoolean(GmailAccount.WORK, value).apply(); store.changed(); refresh.run(); }), false);
        ui.addRow(use, ui.switchRow("메일 보내기 허용", sends ? "‘보내 줘’라고 할 때만 보내요 · 그 밖에는 초안" : "보내지 않고 모두 초안으로 저장해요", sends,
            value -> { store.settings.edit().putBoolean(GmailAccount.SEND, value).apply(); store.changed(); refresh.run(); }), false);
        page.addView(use);
        page.addView(ui.details("할 수 있는 일", "메일 찾기(보낸 사람·제목·날짜·안 읽음·첨부, Gmail 검색어 그대로), 메일과 스레드 읽기(첨부는 이름과 크기만), 답장·새 메일을 초안으로 저장, "
            + "‘보내 줘’라고 할 때 보내기를 해요. 지우거나 라벨을 바꾸지는 않아요. 예: “오늘 안 읽은 메일 정리해 줘”, “김 대리 메일에 목요일 3시 괜찮다고 답장 초안 써 줘”, “그거 보내 줘”."));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16);
        page.addView(ui.button("연결 해제", false, () -> ui.confirm("Gmail 연결을 해제할까요? Google에 준 권한도 거둬요.", "해제", () -> new Thread(() -> {
            store.gmail.disconnect(); activity.runOnUiThread(() -> { store.status("Gmail 연결을 해제했어요"); refresh.run(); });
        }, "gyeot-gmail-off").start()).show()), gap);
        return page;
    }
    private void open(String url) { try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { store.error("브라우저를 열지 못했어요."); } }
    void connect() {
        store.status("Google 로그인 화면을 여는 중…");
        GmailAccount.begin(activity, CONNECT, this::finish, reason -> activity.runOnUiThread(() -> { store.error(reason); refresh.run(); }));
    }
    /** Google's sheet came back. Returns whether the result was Gmail's. */
    boolean onResult(int request, int result, Intent data) {
        if (request != CONNECT) return false;
        if (result != Activity.RESULT_OK || data == null) { store.status("Gmail 연결을 취소했어요"); refresh.run(); return true; }
        try { finish(GmailAccount.finish(activity, data)); } catch (Exception e) { store.error(GmailAccount.explain(e)); }
        return true;
    }
    private void finish(String token) {
        store.status("Gmail을 확인하는 중…");
        new Thread(() -> {
            try { String email = store.gmail.remember(token); activity.runOnUiThread(() -> { store.status((email.isBlank() ? "Gmail" : email) + "(으)로 연결했어요"); refresh.run(); }); }
            catch (Exception e) { activity.runOnUiThread(() -> { store.error(e.getMessage()); refresh.run(); }); }
        }, "gyeot-gmail-check").start();
    }
}
