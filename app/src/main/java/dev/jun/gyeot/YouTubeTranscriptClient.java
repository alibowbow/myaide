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
 * (core/YouTubeTranscript reads both), with no sign-in and no key, from YouTube's own site only. Only when that gives
 * nothing, and the user has a Gemini API key, the address goes to Gemini's light model, which writes the words down
 * (GeminiAudioClient.transcribeYouTube). When neither works the request carries a note that says why, so the answer says
 * so instead of guessing.
 * Never on the main thread.
 */
final class YouTubeTranscriptClient {
    private YouTubeTranscriptClient() {}
    /** The page is about a megabyte; a caption file of a long video a few hundred kilobytes. */
    private static final int PAGE_BYTES = 6 * 1024 * 1024, CAPTION_BYTES = 4 * 1024 * 1024, TIMEOUT_MS = 20_000;
    /** The last few videos read, by id, so a follow-up question or a second try does not ask YouTube again. */
    private static final Map<String, String> READ = new LinkedHashMap<>(8, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, String> eldest) { return size() > 4; }
    };

    /**
     * The video a request's newest message is about: the one the message names, or, for a message that is still about a
     * video (YouTubeTranscript.about), the one an earlier message named (earlier: newest first). "" for none.
     */
    static String videoId(String newest, List<String> earlier) {
        String id = YouTubeTranscript.id(newest);
        if (id.isEmpty() && YouTubeTranscript.about(newest)) for (String older : earlier) { id = YouTubeTranscript.id(older); if (!id.isEmpty()) break; }
        return id;
    }
    /** The phone's own browser name, or null when it has no WebView (the connection's own name goes instead). */
    static String agent(android.content.Context context) {
        try { return android.webkit.WebSettings.getDefaultUserAgent(context); } catch (RuntimeException noWebView) { return null; }
    }
    /** The longest video Google is asked to write down (the light model's free allowance a minute holds about this much at low resolution). */
    private static final int LONGEST_SECONDS = 40 * 60;
    /**
     * What the request carries for the video with this id: its transcript as the app read it; when the app cannot read it
     * and there is a Gemini API key (key), the words Google wrote down from the address; else why there is neither (which
     * step gave nothing, in words the answer can pass on). agent: the phone's own browser name.
     */
    static String block(String id, String agent, String key, AtomicReference<Runnable> cancel) {
        synchronized (READ) { String kept = READ.get(id); if (kept != null) return kept; }
        YouTubeTranscript.Video video = new YouTubeTranscript.Video(id, "", "", 0, true, "");
        String why;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            if (agent != null && !agent.isBlank()) headers.put("User-Agent", agent);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6"); headers.put("Accept", "text/html,application/xhtml+xml");
            String html = get(YouTubeTranscript.page(id), headers, PAGE_BYTES, cancel);
            JSONObject player = YouTubeTranscript.player(html);
            if (player == null) why = "유튜브가 보낸 영상 페이지에 영상 정보가 없었어요 (페이지 " + html.length() + "자).";
            else {
                video = YouTubeTranscript.video(id, player);
                List<YouTubeTranscript.Track> tracks = YouTubeTranscript.tracks(player);
                YouTubeTranscript.Track track = YouTubeTranscript.pick(tracks);
                if (track == null) why = YouTubeTranscript.noTrack(video);
                else {
                    headers.put("Accept", "*/*");
                    // The caption file as the page names it, then the same file in YouTube's JSON form.
                    String body = get(track.url(), headers, CAPTION_BYTES, cancel);
                    List<YouTubeTranscript.Line> lines = YouTubeTranscript.lines(body);
                    if (lines.isEmpty()) { String json = get(track.url() + "&fmt=json3", headers, CAPTION_BYTES, cancel); lines = YouTubeTranscript.lines(json); body = body.length() >= json.length() ? body : json; }
                    if (!lines.isEmpty()) return keep(id, YouTubeTranscript.block(video, track, lines, YouTubeTranscript.LIMIT));
                    why = "영상에 자막은 있는데(" + tracks.size() + "개) 유튜브가 자막 파일을 비워서 보냈어요 (받은 글자 " + body.length() + "자).";
                }
            }
        } catch (IOException | RuntimeException failed) {
            if (failed instanceof java.util.concurrent.CancellationException) throw (RuntimeException) failed;
            why = failed.getMessage() == null || failed.getMessage().isBlank() ? "유튜브에 연결하지 못했어요." : failed.getMessage();
        }
        // The app could not read it: with a Gemini API key, Google writes the words down from the address alone.
        if (key != null && !key.isBlank() && (video.seconds() == 0 || video.seconds() <= LONGEST_SECONDS)) {
            try { return keep(id, YouTubeTranscript.heard(video, GeminiAudioClient.transcribeYouTube(key, id, () -> {}, cancel), YouTubeTranscript.LIMIT)); }
            catch (java.util.concurrent.CancellationException stopped) { throw stopped; }
            catch (Exception failed) { why += " Gemini 3.5 Flash Lite에 주소를 보내 봤지만 그쪽도 받아 적지 못했어요" + (failed.getMessage() == null || failed.getMessage().isBlank() ? "." : ": " + failed.getMessage()); }
        } else if (key == null || key.isBlank()) why += " Gemini API 키가 없어서 Gemini에 맡기지도 못했어요.";
        else why += " 영상이 너무 길어서 Gemini에 맡기지 않았어요.";
        return YouTubeTranscript.missing(video, why);
    }
    private static String keep(String id, String block) { synchronized (READ) { READ.put(id, block); } return block; }
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
