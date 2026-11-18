package dev.jun.gyeot;

import android.app.Activity;
import android.view.View;
import android.widget.LinearLayout;
import android.widget.TextView;
import dev.jun.gyeot.core.AppBackup;
import java.io.File;
import java.time.ZoneId;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.Ui.*;

/**
 * 설정 → 커넥터 → Google Drive의 앱 백업: 지금 백업하기 makes a backup of this phone's data and uploads it to the user's
 * Drive; 백업 가져오기 lists the backups there and, after a confirmation, adds one to this phone (AppBackupFiles). One
 * runs at a time; each says what it did in the status line.
 */
final class DriveBackupUi {
    private static final Object RUNNING = new Object();
    private static volatile boolean busy;
    private final Activity activity; private final Ui ui; private final AppStore store;
    DriveBackupUi(Activity activity, Ui ui, AppStore store) { this.activity = activity; this.ui = ui; this.store = store; }

    /** The two rows, for the Drive page. */
    LinearLayout rows() {
        LinearLayout group = ui.group();
        ui.addRow(group, ui.listRow(0, 0, "지금 백업하기", "기억·메모·대화 기록·작업창·예약·루틴·페이지·메인 대화를 Drive에 올려요", null, this::backup), false);
        ui.addRow(group, ui.listRow(0, 0, "백업 가져오기", "다른 폰에서 올린 백업을 이 폰에 더해요", ui.chevron(), this::choose), false);
        return group;
    }
    static String about() {
        return "새 폰에서는 같은 Google 계정으로 Drive를 연결한 뒤 ‘백업 가져오기’를 누르면 돼요. 가져오기는 더하기만 해요: 이 폰에 있는 것은 바꾸거나 지우지 않아요. "
            + "API 키와 로그인, 음성·모델 같은 기기 설정, 첨부 파일과 갤러리의 사진·영상은 백업에 들어가지 않아요. 백업 파일은 내 Drive에 그대로 저장돼요.";
    }

    private void backup() {
        if (busy) { store.status("백업 작업이 이미 진행 중이에요"); return; }
        busy = true; store.status("백업을 만드는 중…");
        new Thread(() -> {
            synchronized (RUNNING) {
                File file = new File(activity.getCacheDir(), "backup-out.zip"); file.delete();
                try {
                    JSONObject counts = AppBackupFiles.create(activity, store, file);
                    String name = AppBackup.fileName(System.currentTimeMillis(), ZoneId.systemDefault()), size = AppBackup.size(file.length());
                    activity.runOnUiThread(() -> store.status("Drive에 올리는 중… " + size));
                    AppBackupFiles.upload(store, file, name);
                    String summary = AppBackup.summary(counts);
                    activity.runOnUiThread(() -> store.status("Drive에 백업했어요 · " + name + " · " + size + (summary.isEmpty() ? "" : " · " + summary)));
                } catch (Exception failed) { String reason = reason(failed, "백업하지 못했어요."); activity.runOnUiThread(() -> store.error(reason)); }
                finally { file.delete(); busy = false; }
            }
        }, "gyeot-backup").start();
    }
    private void choose() {
        if (busy) { store.status("백업 작업이 이미 진행 중이에요"); return; }
        Ui.Sheet sheet = ui.selectionSheet("백업 가져오기", "Drive에 올려 둔 백업이에요");
        sheet.primary.setVisibility(View.GONE);
        TextView working = ui.label("Drive에서 백업을 찾는 중…", 15, MUTED); working.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(10)); sheet.body.addView(working);
        sheet.show();
        new Thread(() -> {
            try {
                List<AppBackup.Listed> found = AppBackupFiles.list(store);
                activity.runOnUiThread(() -> { if (sheet.isShowing()) fill(sheet, found); });
            } catch (Exception failed) { String reason = reason(failed, "Drive의 백업을 찾지 못했어요."); activity.runOnUiThread(() -> { if (sheet.isShowing()) { sheet.body.removeAllViews(); sheet.body.addView(ui.banner(reason, ERROR)); } }); }
        }, "gyeot-backup-list").start();
    }
    private void fill(Ui.Sheet sheet, List<AppBackup.Listed> found) {
        sheet.body.removeAllViews();
        if (found.isEmpty()) {
            TextView none = ui.label("이 Google 계정의 Drive에 이 앱이 올린 백업이 없어요.\n예전 폰에서 ‘지금 백업하기’를 먼저 눌러 주세요.", 15, MUTED);
            none.setPadding(ui.dp(4), ui.dp(6), ui.dp(4), ui.dp(10)); none.setLineSpacing(ui.dp(4), 1); sheet.body.addView(none); return;
        }
        LinearLayout group = ui.group();
        for (AppBackup.Listed backup : found)
            ui.addRow(group, ui.listRow(R.drawable.ic_folder, BLUE, backup.name(), AppBackup.size(backup.size()), ui.chevron(), () -> { sheet.dismiss();
                ui.confirm("‘" + backup.name() + "’을 이 폰에 가져올까요? 이 폰에 있는 것은 그대로 두고, 백업에만 있는 것을 더해요.", "가져오기", () -> restore(backup)).show(); }), true);
        sheet.body.addView(group);
        sheet.body.addView(ui.footer(about()));
    }
    private void restore(AppBackup.Listed backup) {
        if (busy) { store.status("백업 작업이 이미 진행 중이에요"); return; }
        busy = true; store.status("백업을 내려받는 중… " + AppBackup.size(backup.size()));
        new Thread(() -> {
            synchronized (RUNNING) {
                File file = new File(activity.getCacheDir(), "backup-down.zip");
                try {
                    AppBackupFiles.download(store, backup.id(), file);
                    activity.runOnUiThread(() -> store.status("백업을 이 폰에 더하는 중…"));
                    JSONObject added = AppBackupFiles.restore(activity, store, file);
                    String summary = AppBackup.summary(added); JSONArray skipped = added.optJSONArray("skipped");
                    StringBuilder notes = new StringBuilder();
                    for (int i = 0; skipped != null && i < skipped.length(); i++) notes.append("\n· ").append(skipped.optString(i));
                    String message = (summary.isEmpty() ? "새로 더한 것이 없어요. 이 폰에 이미 있는 내용이에요." : "가져왔어요 · " + summary) + notes;
                    activity.runOnUiThread(() -> { store.status(summary.isEmpty() ? "새로 더한 것이 없어요" : "백업을 가져왔어요 · " + summary); result(message); });
                } catch (Exception failed) { String reason = reason(failed, "백업을 가져오지 못했어요."); activity.runOnUiThread(() -> store.error(reason)); }
                finally { file.delete(); busy = false; }
            }
        }, "gyeot-restore").start();
    }
    /** What came in and what was left out, to read at leisure. */
    private void result(String message) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Ui.Sheet sheet = ui.selectionSheet("백업 가져오기", "");
        TextView text = ui.text(message, 15, 450, TEXT); text.setLineSpacing(ui.dp(4), 1); text.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(8)); text.setTextIsSelectable(true);
        sheet.body.addView(text);
        sheet.body.addView(ui.footer("AI 연결(키·로그인)과 음성·모델 설정은 이 폰에서 다시 해 주세요."));
        sheet.primary.setText("확인");
        sheet.show();
    }
    private static String reason(Exception failed, String fallback) {
        if (failed instanceof java.io.IOException) return dev.jun.gyeot.core.Net.explain(failed);
        return failed.getMessage() == null || failed.getMessage().isBlank() ? fallback : failed.getMessage();
    }
}
