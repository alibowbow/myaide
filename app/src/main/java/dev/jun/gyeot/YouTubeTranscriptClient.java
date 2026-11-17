package dev.jun.gyeot;

import dev.jun.gyeot.core.YouTubeTranscript;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONObject;

/**
 * Reads a YouTube video's transcript for the main chat: the video's own page, then the caption file the page points to
 * (core/YouTubeTranscript reads both). No sign-in and no key; only www.youtube.com is asked. Never on the main thread.
 * A video whose transcript cannot be read gives a note that says why, so the answer says so instead of guessing.
 */
final class YouTubeTranscriptClient {
    private YouTubeTranscriptClient() {}
    /** The page is about a megabyte; a caption file of a long video a few hundred kilobytes. */
    private static final int PAGE_BYTES = 6 * 1024 * 1024, CAPTION_BYTES = 4 * 1024 * 1024, TIMEOUT_MS = 20_000;
    /** The last few videos read, by id, so a follow-up question or a second try does not ask YouTube again. */
    private static final Map<String, String> READ = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 4; }
    };

    /** What the request carries for the video with this id: its transcript, or why there is none. agent: the phone's own browser name. */
    static String block(String id, String agent, AtomicReference<Runnable> cancel) {
        synchronized (READ) { String kept = READ.get(id); if (kept != null) return kept; }
        YouTubeTranscript.Video video = new YouTubeTranscript.Video(id, "", "", 0, true, "");
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            if (agent != null && !agent.isBlank()) headers.put("User-Agent", agent);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6"); headers.put("Accept", "text/html,application/xhtml+xml");
            String html = get(YouTubeTranscript.page(id), headers, PAGE_BYTES, cancel);
            JSONObject player = YouTubeTranscript.player(html);
            if (player == null) return YouTubeTranscript.missing(video, "유튜브가 영상 정보를 주지 않았어요. 잠시 뒤에 다시 시도해 주세요.");
            video = YouTubeTranscript.video(id, player);
            List<YouTubeTranscript.Track> tracks = YouTubeTranscript.tracks(player);
            YouTubeTranscript.Track track = YouTubeTranscript.pick(tracks);
            if (track == null) return YouTubeTranscript.missing(video, YouTubeTranscript.noTrack(video));
            headers.put("Accept", "*/*");
            List<YouTubeTranscript.Line> lines = YouTubeTranscript.lines(get(track.url(), headers, CAPTION_BYTES, cancel));
            if (lines.isEmpty()) return YouTubeTranscript.missing(video, "자막 파일이 비어 있어요. 유튜브가 이 영상의 자막을 앱에 내주지 않았어요.");
            String block = YouTubeTranscript.block(video, track, lines, YouTubeTranscript.LIMIT);
            synchronized (READ) { READ.put(id, block); }
            return block;
        } catch (IOException | RuntimeException failed) {
            if (failed instanceof java.util.concurrent.CancellationException) throw (RuntimeException) failed;
            String why = failed.getMessage() == null ? "" : failed.getMessage();
            return YouTubeTranscript.missing(video, why.isBlank() ? "유튜브에 연결하지 못했어요." : why);
        }
    }
    /** One page from YouTube's own site, following its redirects there (at most three) and nowhere else. */
    private static String get(String url, Map<String, String> headers, int limit, AtomicReference<Runnable> cancel) throws IOException {
        String address = url;
        for (int hop = 0; hop < 4; hop++) {
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", address, headers, null, TIMEOUT_MS, cancel)) {
                if (response.status >= 300 && response.status < 400) {
                    String next = response.headers.get("location");
                    java.net.URI target = next == null ? null : java.net.URI.create(address).resolve(next);
                    String host = target == null || target.getHost() == null ? "" : target.getHost().toLowerCase(java.util.Locale.ROOT);
                    if (target == null || !"https".equals(target.getScheme()) || !(host.equals("www.youtube.com") || host.equals("m.youtube.com") || host.equals("youtube.com")))
                        throw new IOException("유튜브가 다른 화면(동의·로그인)으로 보내서 영상을 읽지 못했어요.");
                    address = target.toString(); continue;
                }
                if (response.status == 429) throw new IOException("유튜브가 지금은 요청이 많다고 거절했어요. 잠시 뒤에 다시 시도해 주세요.");
                if (response.status >= 300) throw new IOException("유튜브가 응답하지 않았어요 (HTTP " + response.status + ").");
                return OpenAiAccount.Http.read(response.body, limit);
            }
        }
        throw new IOException("유튜브가 주소를 계속 바꿔서 영상을 읽지 못했어요.");
    }
}
