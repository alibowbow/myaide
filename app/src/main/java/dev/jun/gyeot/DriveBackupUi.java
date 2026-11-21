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
        ui.addRow(group, ui.listRow(0, 0, "지금 백업하기", "설정·기억·기록·페이지·앱 파일과 들퍼레 폴더의 사진·영상·파일까지 Drive에 올려요", null, this::backup), false);
        ui.addRow(group, ui.listRow(0, 0, "백업 가져오기", "Drive의 백업을 이 폰에 더해요 · 들퍼레 폴더 파일도 받아요", ui.chevron(), this::choose), false);
        return group;
    }
    static String about() {
        return "들어가는 것: 앱 설정, 기억·나에 대해, 메모, 대화 기록, 작업창, 예약, 한마디 루틴, 페이지(HTML 포함), 메인 대화, 앱 안의 파일(갤러리·녹음·판서·마이튜브·아바타 등), "
            + "들퍼레 폴더 전체(사진·영상·음악·문서·백업). 앱 데이터는 내 드라이브 › 들퍼레 백업에 ‘들퍼레-백업-날짜-시간.zip’으로, 들퍼레 폴더는 그 안의 ‘들퍼레 폴더’에 같은 구조로 저장돼요. "
            + "다음 백업부터 들퍼레 폴더는 새로 생기거나 바뀐 파일만 올려요. API 키·로그인·저장한 비밀번호는 보안을 위해 넣지 않아요(이 폰의 보안 키로 잠겨 있어 다른 폰에서는 열 수 없어요). "
            + "새 폰에서는 같은 Google 계정으로 Drive를 연결한 뒤 ‘백업 가져오기’를 누르면 돼요. 가져오기는 더하기만 해요: 이 폰에 있는 것은 바꾸거나 지우지 않고, 이 폰에서 아직 정하지 않은 설정만 백업 값으로 채워요.";
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
                    activity.runOnUiThread(() -> store.status("앱 데이터를 Drive에 올리는 중… " + size));
                    String root = DriveFolderBackup.root(store);
                    AppBackupFiles.upload(store, file, name, root);
                    file.delete();
                    // Then the 들퍼레 folder, file by file: only what is new or changed goes up.
                    String files;
                    try {
                        DriveFolderBackup.Result folder = DriveFolderBackup.upload(activity, store, root, line -> activity.runOnUiThread(() -> store.status(line)), () -> false);
                        files = "들퍼레 폴더 파일 " + folder.files + "개" + (folder.moved > 0 ? " (새로 " + folder.moved + "개 · " + AppBackup.size(folder.bytes) + ")" : " (바뀐 것 없음)")
                            + (folder.notes.isEmpty() ? "" : " · 빠진 파일 " + folder.notes.size() + "개");
                    } catch (java.io.IOException noFolder) { files = "들퍼레 폴더는 연결되지 않아 빼고 백업했어요"; }
                    String summary = AppBackup.summary(counts), done = files;
                    activity.runOnUiThread(() -> store.status("내 드라이브 › 들퍼레 백업에 백업했어요 · 앱 데이터 " + size + (summary.isEmpty() ? "" : " (" + summary + ")") + " · " + done));
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
                    file.delete();
                    // Then the 들퍼레 folder's files that this phone does not have yet.
                    activity.runOnUiThread(() -> store.status("들퍼레 폴더 파일을 받는 중…"));
                    String files;
                    try {
                        DriveFolderBackup.Result folder = DriveFolderBackup.download(activity, store, line -> activity.runOnUiThread(() -> store.status(line)), () -> false);
                        files = folder.files == 0 ? "" : "들퍼레 폴더 파일 " + folder.moved + "개 받음 (Drive에 " + folder.files + "개" + (folder.moved > 0 ? " · " + AppBackup.size(folder.bytes) : "") + ")";
                        for (String note : folder.notes) added.optJSONArray("skipped").put(note);
                    } catch (java.io.IOException noFolder) { files = "들퍼레 폴더가 연결되지 않아 파일은 받지 않았어요. 설정 → 내 휴대폰 › 저장 위치에서 연결한 뒤 다시 가져오면 받아요."; }
                    String summary = AppBackup.summary(added); JSONArray skipped = added.optJSONArray("skipped");
                    StringBuilder notes = new StringBuilder();
                    for (int i = 0; skipped != null && i < skipped.length(); i++) notes.append("\n· ").append(skipped.optString(i));
                    String got = files;
                    String message = (summary.isEmpty() ? "앱 데이터는 새로 더한 것이 없어요. 이 폰에 이미 있는 내용이에요." : "가져왔어요 · " + summary) + (got.isEmpty() ? "" : "\n" + got) + notes;
                    boolean restart = added.optBoolean("restart");
                    activity.runOnUiThread(() -> { store.status(summary.isEmpty() && got.isEmpty() ? "새로 더한 것이 없어요" : "백업을 가져왔어요" + (summary.isEmpty() ? "" : " · " + summary)); result(message, restart); });
                } catch (Exception failed) { String reason = reason(failed, "백업을 가져오지 못했어요."); activity.runOnUiThread(() -> store.error(reason)); }
                finally { file.delete(); busy = false; }
            }
        }, "gyeot-restore").start();
    }
    /** What came in and what was left out, to read at leisure. */
    private void result(String message, boolean restart) {
        if (activity.isFinishing() || activity.isDestroyed()) return;
        Ui.Sheet sheet = ui.selectionSheet("백업 가져오기", "");
        TextView text = ui.text(message, 15, 450, TEXT); text.setLineSpacing(ui.dp(4), 1); text.setPadding(ui.dp(4), ui.dp(2), ui.dp(4), ui.dp(8)); text.setTextIsSelectable(true);
        sheet.body.addView(text);
        sheet.body.addView(ui.footer(restart ? "설정과 앱 파일은 앱을 닫았다 다시 열면 모두 보여요. AI 연결(키·로그인)은 이 폰에서 다시 해 주세요."
            : "AI 연결(키·로그인)은 이 폰에서 다시 해 주세요."));
        sheet.primary.setText(restart ? "앱 닫기 (다시 열어 주세요)" : "확인");
        if (restart) sheet.primary.setOnClickListener(v -> { sheet.dismiss(); activity.finishAffinity(); new android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(() -> System.exit(0), 300); });
        sheet.show();
    }
    private static String reason(Exception failed, String fallback) {
        if (failed instanceof java.io.IOException) return dev.jun.gyeot.core.Net.explain(failed);
        return failed.getMessage() == null || failed.getMessage().isBlank() ? fallback : failed.getMessage();
    }
}
