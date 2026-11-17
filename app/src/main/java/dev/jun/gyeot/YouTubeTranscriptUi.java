package dev.jun.gyeot;

import android.app.Activity;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.widget.TextView;
import android.widget.Toast;
import static dev.jun.gyeot.Ui.*;

/** 유튜브 대본 보기: the transcript the app kept for a video (YouTubeTranscriptClient.saved), to read, select and copy. */
final class YouTubeTranscriptUi {
    private YouTubeTranscriptUi() {}
    /** Whether the words name a video whose transcript is kept. */
    static boolean has(Activity activity, String words) { return YouTubeTranscriptClient.kept(activity, dev.jun.gyeot.core.YouTubeTranscript.id(words)); }
    /** Opens the transcript of the video the words name; says so when there is none. */
    static void show(Activity activity, String words) {
        String text = YouTubeTranscriptClient.saved(activity, dev.jun.gyeot.core.YouTubeTranscript.id(words));
        if (text.isEmpty()) { Toast.makeText(activity, "이 영상의 대본은 보관돼 있지 않아요.", Toast.LENGTH_SHORT).show(); return; }
        Ui ui = new Ui(activity);
        int line = text.indexOf('\n');
        Ui.Sheet sheet = ui.selectionSheet("유튜브 대본", line > 0 ? text.substring(0, Math.min(line, 80)) : "");
        TextView body = ui.text(line > 0 ? text.substring(line + 1).strip() : text, 14, 400, TEXT);
        body.setTextIsSelectable(true); body.setLineSpacing(ui.dp(4), 1); body.setPadding(ui.dp(16), ui.dp(4), ui.dp(16), ui.dp(12));
        sheet.body.addView(body);
        sheet.primary.setText("전체 복사");
        sheet.primary.setOnClickListener(v -> {
            ClipboardManager clipboard = activity.getSystemService(ClipboardManager.class);
            if (clipboard != null) { clipboard.setPrimaryClip(ClipData.newPlainText("유튜브 대본", text)); Toast.makeText(activity, "대본을 복사했어요", Toast.LENGTH_SHORT).show(); }
        });
        sheet.show();
    }
}
