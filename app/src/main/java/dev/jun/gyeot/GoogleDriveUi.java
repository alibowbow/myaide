package dev.jun.gyeot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Intent;
import android.net.Uri;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;

/**
 * 설정 → 커넥터 → Google Drive: one button that opens Google's own sign-in sheet (pick the account, allow), the state of
 * the connection, and the one-time Google Cloud registration with this app's package name and signing SHA-1 ready to copy.
 */
final class GoogleDriveUi {
    static final int CONNECT = 33;
    static final String PACKAGE = "dev.jun.gyeot";
    static final String CONSOLE = "https://console.cloud.google.com/";
    private final Activity activity; private final Ui ui; private final AppStore store; private final Runnable refresh;
    GoogleDriveUi(Activity activity, Ui ui, AppStore store, Runnable refresh) { this.activity = activity; this.ui = ui; this.store = store; this.refresh = refresh; }

    /**
     * 설정 → 커넥터 → Google Drive (page "drive"): the connection's state on top. Before connecting,
     * what Drive is for, the one-time registration and Google's sign-in; once connected, where it is used and whether changes
     * are allowed, what it can do, the connection's checks, and 연결 해제 last.
     */
    View page() {
        LinearLayout page = ui.column();
        GoogleDriveAccount drive = store.drive;
        if (!drive.connected()) {
            page.addView(ui.statusCard(R.drawable.ic_folder, Ui.BLUE, "연결 안 됨", "Google 계정으로 연결해요", null));
            TextView about = ui.text("메인 대화와 작업창의 AI가 Drive 파일을 찾고 읽고, 올리고, 휴대폰으로 받아요.", 14, 400, Ui.MUTED); about.setPadding(ui.dp(16), ui.dp(14), ui.dp(16), 0); page.addView(about);
            TextView once = ui.label("처음이면 Google Cloud에 이 앱을 한 번 등록해 주세요 · 약 5분", 13, Ui.MUTED); once.setPadding(ui.dp(16), ui.dp(16), ui.dp(16), 0); page.addView(once);
            page.addView(ui.actions(ui.chip("Google Cloud 등록 안내 (처음 한 번)", this::guide)));
            LinearLayout.LayoutParams first = new LinearLayout.LayoutParams(-1, -2); first.topMargin = ui.dp(16);
            page.addView(ui.button("Google로 연결", true, () -> connect(false)), first);
            TextView how = ui.label("누르면 Google 계정 선택 → 허용 화면이 떠요. 비밀번호나 키는 이 앱에 저장하지 않아요.", 12, Ui.MUTED); how.setPadding(ui.dp(16), ui.dp(8), ui.dp(16), 0); page.addView(how);
            return page;
        }
        boolean work = store.settings.getBoolean(GoogleDriveAccount.WORK, true), writable = drive.writable();
        LinearLayout top = ui.column(); page.addView(top); state(top, work);
        page.addView(ui.sectionHeader("사용"));
        LinearLayout use = ui.group();
        ui.addRow(use, ui.switchRow("작업창·메인 대화에서 Google Drive 쓰기", "끄면 두 곳 모두 Drive를 쓰지 않아요", work, value -> {
            store.settings.edit().putBoolean(GoogleDriveAccount.WORK, value).apply(); store.changed(); state(top, value);
        }), false);
        ui.addRow(use, ui.switchRow("Drive 파일 수정·정리 허용", writable ? "요청한 파일만 고치고 옮겨요 · 영구 삭제는 안 해요" : "켜면 Google 허용 화면이 한 번 더 떠요(Drive 전체 권한)", writable, value -> {
            if (value == drive.writable()) return;
            if (value) connect(true);
            else { drive.readOnly(); store.status("Drive 파일 수정·정리를 껐어요 · 읽기·올리기·내려받기는 그대로 돼요"); refresh.run(); }
        }), false);
        page.addView(use);
        page.addView(ui.details("할 수 있는 일", "파일 찾기(이름·내용), Google 문서·스프레드시트·프레젠테이션과 글 파일 읽기, 첨부한 파일·만든 파일 올리기, 휴대폰 들퍼레 폴더로 내려받기, 폴더 만들기를 해요. "
            + "기본 권한은 모든 파일 읽기와 이 앱이 만든 파일만 쓰기예요. ‘수정·정리 허용’을 켜면 요청한 파일의 내용 고치기·이름 바꾸기·폴더로 옮기기·휴지통으로 보내기도 해요. "
            + "휴지통은 30일 안에 되돌릴 수 있고, 고친 내용은 Drive 버전 기록에 남아요. 예: “Drive에서 지난주 회의록 찾아서 요약해 줘”, “이 표를 구글 시트로 저장해 줘”."));
        // 앱 백업: this phone's data to Drive, and a backup from another phone into this one.
        page.addView(ui.sectionHeader("앱 백업"));
        page.addView(new DriveBackupUi(activity, ui, store).rows());
        page.addView(ui.footer(DriveBackupUi.about()));
        page.addView(ui.sectionHeader("연결 관리"));
        LinearLayout manage = ui.group();
        ui.addRow(manage, ui.listRow(0, 0, "연결 확인", "Google에 다시 확인해요", null, () -> connect(drive.writable())), false);
        ui.addRow(manage, ui.listRow(0, 0, "Google Cloud 등록 안내", "패키지 이름·SHA-1을 다시 봐요", ui.chevron(), this::guide), false);
        page.addView(manage);
        LinearLayout off = ui.group();
        ui.addRow(off, ui.dangerRow("연결 해제", "Google에도 권한 회수를 요청해요", () -> ui.confirm("이 기기에서 Google Drive 연결을 해제할까요? Google에도 권한 회수를 요청해요. Drive의 파일은 그대로예요.", "해제", this::disconnect).show()), false);
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(16); page.addView(off, gap);
        return page;
    }
    /** The status card: 연결됨 with what Drive may do, or 꺼 둠 while 작업창·메인 대화 leave it alone; redrawn in place when that switch flips. */
    private void state(LinearLayout top, boolean work) {
        String email = store.drive.email(), account = email.isBlank() ? "" : email + " · ";
        top.removeAllViews();
        top.addView(ui.statusCard(R.drawable.ic_folder, Ui.BLUE, work ? "연결됨" : "꺼 둠", account + (!work ? "작업창·메인 대화에서 쓰지 않아요" : store.drive.writable() ? "수정·정리 허용" : "읽기·올리기"), null));
    }

    private boolean pendingFull;
    /** Google's sheet for read + this app's files, or (full) for the whole Drive when the user allows changes. */
    void connect(boolean full) {
        pendingFull = full;
        store.status(full ? "Drive 수정·정리 권한을 요청하는 중…" : "Google 로그인 화면을 여는 중…");
        GoogleDriveAccount.authorizer.begin(activity, CONNECT, full, this::finish, reason -> activity.runOnUiThread(() -> { store.error(reason); refresh.run(); }));
    }
    /** Google's sheet came back. Returns whether the result was Drive's. */
    boolean onResult(int request, int result, Intent data) {
        if (request != CONNECT) return false;
        if (result != Activity.RESULT_OK || data == null) { store.status("Google Drive 연결을 취소했어요"); refresh.run(); return true; }
        try { finish(GoogleDriveAccount.authorizer.finish(activity, data)); }
        catch (Exception e) { store.error(GoogleDriveAccount.explain(e)); }
        return true;
    }
    private void finish(GoogleDriveAccount.Grant grant) {
        store.status("Google Drive를 확인하는 중…");
        boolean wanted = pendingFull;
        new Thread(() -> {
            try {
                String email = store.drive.remember(grant), who = email.isBlank() ? "연결" : email + "(으)로 연결";
                String message = grant.full() ? "Google Drive에 " + who + "했어요 · 파일 수정·정리까지 맡길 수 있어요"
                    : wanted ? "Drive 전체 권한이 허용되지 않아서 읽기·올리기·내려받기만 돼요"
                    : "Google Drive에 " + who + "했어요 · 메인 대화와 작업창에서 Drive 파일을 맡겨 보세요";
                activity.runOnUiThread(() -> { store.status(message); refresh.run(); });
            } catch (Exception e) {
                String reason = e instanceof java.io.IOException ? dev.jun.gyeot.core.Net.explain(e) : e.getMessage() == null ? "Google Drive를 확인하지 못했어요." : e.getMessage();
                activity.runOnUiThread(() -> store.error(reason));
            }
        }, "gyeot-drive-connect").start();
    }
    private void disconnect() {
        store.status("Google Drive 연결을 해제하는 중…");
        new Thread(() -> { store.drive.disconnect(); activity.runOnUiThread(() -> { store.status("Google Drive 연결을 해제했어요"); refresh.run(); }); }, "gyeot-drive-disconnect").start();
    }

    /** The one-time registration in the user's own Google Cloud project, step by step, with the values to paste. */
    void guide() {
        String sha1 = GoogleDriveAccount.signingSha1(activity);
        Ui.Sheet sheet = ui.new Sheet("Google Cloud 등록 안내", "처음 한 번만 해요 · 약 5분");
        LinearLayout body = sheet.body;
        body.addView(ui.label("Google은 Drive에 들어가는 앱을 Google Cloud에 등록하게 해요. 내 Google 계정으로 무료 프로젝트를 하나 만들고, 아래 두 값을 넣으면 돼요.", 13, Ui.MUTED));
        LinearLayout values = ui.card(); values.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12));
        values.addView(value("패키지 이름", PACKAGE, true));
        values.addView(value("SHA-1 인증서 지문", sha1.isBlank() ? "이 기기에서 읽지 못했어요" : sha1, !sha1.isBlank()));
        LinearLayout.LayoutParams gap = new LinearLayout.LayoutParams(-1, -2); gap.topMargin = ui.dp(12); body.addView(values, gap);
        String[][] steps = {
            {"1. 프로젝트 만들기", "Google Cloud 콘솔에서 새 프로젝트를 만들어요(이름 예: 들퍼레). 결제 정보는 필요 없어요.", CONSOLE + "projectcreate"},
            {"2. Google Drive API 사용", "‘Google Drive API’ 화면에서 ‘사용’을 눌러요.", CONSOLE + "apis/library/drive.googleapis.com"},
            {"3. Google 인증 플랫폼 구성", "‘시작하기’ → 앱 이름(들퍼레)·지원 이메일 → 대상 ‘외부’ → 연락처 이메일 → 동의 후 ‘만들기’.", CONSOLE + "auth/overview"},
            {"4. 테스트 사용자 추가", "‘대상’에서 ‘테스트 사용자 추가’로 연결할 내 Gmail 주소를 넣어요.", CONSOLE + "auth/audience"},
            {"5. 데이터 액세스 (권장)", "‘범위 추가 또는 삭제’에서 …/auth/drive.readonly 와 …/auth/drive.file 을 골라 저장해요. 파일 수정·정리까지 쓰려면 …/auth/drive 도 함께 골라요.", CONSOLE + "auth/scopes"},
            {"6. Android 클라이언트 만들기", "‘클라이언트’ → ‘클라이언트 만들기’ → 유형 ‘Android’ → 위 패키지 이름과 SHA-1을 붙여 넣고 ‘만들기’. (클라이언트 ID나 비밀번호는 앱에 넣을 필요 없어요.)", CONSOLE + "auth/clients/create"},
            {"7. 연결", "몇 분 기다린 뒤 이 화면의 ‘Google로 연결’을 눌러요. ‘확인되지 않은 앱’ 경고가 나오면 내 프로젝트라서 그래요: ‘고급’ → ‘들퍼레(으)로 이동’.", null},
        };
        for (String[] step : steps) {
            LinearLayout card = ui.card(); card.setPadding(ui.dp(16), ui.dp(10), ui.dp(16), ui.dp(12));
            card.addView(ui.text(step[0], 15, 650, Ui.TEXT)); card.addView(ui.label(step[1], 13, Ui.MUTED));
            if (step[2] != null) { String address = step[2]; card.addView(ui.actions(ui.chip("열기", () -> open(address)))); }
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(-1, -2); p.topMargin = ui.dp(10); body.addView(card, p);
        }
        body.addView(ui.footer("테스트 모드에서는 Google이 7일마다 다시 연결을 요청할 수 있어요. ‘대상’에서 ‘앱 게시’를 하면 그 제한이 없어지고, 내 계정만 쓰는 데에는 Google 검토가 필요 없어요."));
        body.addView(ui.footer("앱을 다른 서명 키로 다시 설치하면 SHA-1이 바뀌어요. 그때는 6단계의 SHA-1만 새 값으로 바꿔 주세요."));
        sheet.primary.setText(store.drive.connected() ? "닫기" : "Google로 연결");
        sheet.primary.setOnClickListener(v -> { sheet.dismiss(); if (!store.drive.connected()) connect(false); });
        sheet.show();
    }
    private View value(String label, String value, boolean copyable) {
        LinearLayout row = ui.column();
        row.addView(ui.label(label, 12, Ui.MUTED));
        TextView text = ui.text(value, 14, 600, Ui.TEXT); text.setTextIsSelectable(true); text.setTypeface(android.graphics.Typeface.MONOSPACE); row.addView(text);
        if (copyable) row.addView(ui.actions(ui.chip(label + " 복사", () -> {
            activity.getSystemService(ClipboardManager.class).setPrimaryClip(ClipData.newPlainText(label, value));
            store.status(label + "을(를) 복사했어요");
        })));
        row.setPadding(0, ui.dp(4), 0, ui.dp(4));
        return row;
    }
    private void open(String address) {
        try { activity.startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(address))); }
        catch (Exception e) { store.error("링크를 열 수 없어요."); }
    }
}
