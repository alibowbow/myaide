package dev.jun.gyeot;

import android.app.Activity;
import android.widget.Toast;

/** 유튜브 대본 보기: the transcript the app kept for a video (YouTubeTranscriptClient.saved), to read, select and copy. */
final class YouTubeTranscriptUi {
    private YouTubeTranscriptUi() {}
    /** Whether the words name a video whose transcript is kept. */
    static boolean has(Activity activity, String words) { return YouTubeTranscriptClient.kept(activity, dev.jun.gyeot.core.YouTubeTranscript.id(words)); }
    /** Opens the transcript of the video the words name; says so when there is none. */
    static void show(Activity activity, String words) {
        String text = YouTubeTranscriptClient.saved(activity, dev.jun.gyeot.core.YouTubeTranscript.id(words));
        if (text.isEmpty()) { Toast.makeText(activity, "이 영상의 대본은 보관돼 있지 않아요.", Toast.LENGTH_SHORT).show(); return; }
        int line = text.indexOf('\n');
        PlainTextSheet.show(activity, "유튜브 대본", line > 0 ? text.substring(0, Math.min(line, 80)) : "", text, "대본을 복사했어요");
    }
}
