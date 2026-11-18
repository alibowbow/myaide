package dev.jun.gyeot;

import android.app.Activity;
import android.widget.Toast;

/** 유튜브 대본 보기 and 가져온 글 보기: the text the app kept for a video or a web page (KeptText), to read, select and copy. */
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
    /** Whether the words name a web page whose text is kept (가져온 글 보기). */
    static boolean hasPage(Activity activity, String words) { String url = dev.jun.gyeot.core.WebArticle.url(words); return !url.isEmpty() && KeptText.kept(activity, WebArticleClient.KIND, dev.jun.gyeot.core.WebArticle.key(url)); }
    /** Opens the kept text of the page the words name. */
    static void showPage(Activity activity, String words) {
        String url = dev.jun.gyeot.core.WebArticle.url(words), text = url.isEmpty() ? "" : KeptText.saved(activity, WebArticleClient.KIND, dev.jun.gyeot.core.WebArticle.key(url));
        if (text.isEmpty()) { Toast.makeText(activity, "이 주소의 글은 보관돼 있지 않아요.", Toast.LENGTH_SHORT).show(); return; }
        int line = text.indexOf('\n');
        PlainTextSheet.show(activity, "가져온 글", line > 0 ? text.substring(0, Math.min(line, 80)) : "", text, "글을 복사했어요");
    }
}
