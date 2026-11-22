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
 * (core/YouTubeTranscript reads both), with no sign-in, from YouTube's own site only. When the page lists no track or its
 * file comes empty, the tracks YouTube's player service gives its Android app, with the key the page carries. When that gives nothing
 * and 유튜브 대본을 Gemini 홈페이지에서 받아 오기 is on, the app's own browser asks Gemini's site in the background
 * (YouTubeGeminiWeb). A transcript read either way is kept in the app's private files for the user to read (유튜브 대본
 * 보기). When neither works the request carries a note that says why, so the answer says so instead of guessing.
 * Never on the main thread.
 */
final class YouTubeTranscriptClient {
    private YouTubeTranscriptClient() {}
    /** The name a computer's browser of the same engine as the app's own (Gecko) gives, for the computer version of the page. */
    private static final String DESKTOP = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0";
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
    /**
     * What the request carries for the video with this id: its transcript as the app read it, which is also kept for
     * reading (saved); or why there is none (which step gave nothing, in words the answer can pass on).
     */
    static String block(android.content.Context context, String id, java.util.function.BooleanSupplier stopped, java.util.function.Consumer<String> progress, AtomicReference<Runnable> cancel) {
        String agent = agent(context);
        synchronized (READ) { String kept = READ.get(id); if (kept != null) return kept; }
        YouTubeTranscript.Video video = new YouTubeTranscript.Video(id, "", "", 0, true, "");
        String why;
        try {
            Map<String, String> headers = new LinkedHashMap<>();
            if (agent != null && !agent.isBlank()) headers.put("User-Agent", agent);
            headers.put("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6"); headers.put("Accept", "text/html,application/xhtml+xml");
            String html = get(YouTubeTranscript.page(id), headers, PAGE_BYTES, cancel);
            JSONObject player = YouTubeTranscript.player(html);
            List<YouTubeTranscript.Track> tracks = YouTubeTranscript.tracks(player);
            if (player != null) video = YouTubeTranscript.video(id, player);
            // The phone page can leave the caption list out of a video that has captions: the computer page is asked too.
            if (tracks.isEmpty()) {
                Map<String, String> desktop = new LinkedHashMap<>(headers); desktop.put("User-Agent", DESKTOP);
                String wide = get(YouTubeTranscript.page(id), desktop, PAGE_BYTES, cancel);
                JSONObject other = YouTubeTranscript.player(wide);
                if (other != null) {
                    List<YouTubeTranscript.Track> listed = YouTubeTranscript.tracks(other);
                    if (player == null || !listed.isEmpty()) { player = other; tracks = listed; headers = desktop; html = wide; video = YouTubeTranscript.video(id, other); }
                }
            }
            if (player == null) why = "유튜브가 보낸 영상 페이지에 영상 정보가 없었어요 (페이지 " + html.length() + "자).";
            else {
                YouTubeTranscript.Track track = YouTubeTranscript.pick(tracks);
                List<YouTubeTranscript.Line> lines = List.of(); String body = "";
                if (track != null) {
                    headers.put("Accept", "*/*");
                    // The caption file as the page names it, then the same file in YouTube's JSON form.
                    body = get(track.url(), headers, CAPTION_BYTES, cancel);
                    lines = YouTubeTranscript.lines(body);
                    if (lines.isEmpty()) { String json = get(track.url() + "&fmt=json3", headers, CAPTION_BYTES, cancel); lines = YouTubeTranscript.lines(json); body = body.length() >= json.length() ? body : json; }
                }
                // Nothing from the page (no track listed, or its file came empty: an address that needs a token the app
                // cannot make): the captions as YouTube's Android app is given them.
                String app = "";
                if (lines.isEmpty()) {
                    try {
                        YouTubeTranscript.Track other = YouTubeTranscript.pick(appTracks(id, html, cancel));
                        if (other == null) app = " 유튜브 앱 방식으로도 자막 목록을 받지 못했어요.";
                        else {
                            Map<String, String> asApp = appHeaders(); asApp.put("Accept", "*/*");
                            List<YouTubeTranscript.Line> got = YouTubeTranscript.lines(get(other.url(), asApp, CAPTION_BYTES, cancel));
                            if (got.isEmpty()) app = " 유튜브 앱 방식으로 받은 자막 파일도 비어 있었어요.";
                            else { track = other; lines = got; }
                        }
                    } catch (IOException | RuntimeException failed) {
                        if (failed instanceof java.util.concurrent.CancellationException) throw (RuntimeException) failed;
                        app = " 유튜브 앱 방식으로도 받지 못했어요" + (failed.getMessage() == null || failed.getMessage().isBlank() ? "." : ": " + failed.getMessage());
                    }
                }
                if (!lines.isEmpty()) { save(context, id, YouTubeTranscript.readable(video, YouTubeTranscript.source(track), YouTubeTranscript.text(lines))); return keep(id, YouTubeTranscript.block(video, track, lines, YouTubeTranscript.LIMIT)); }
                why = (tracks.isEmpty() ? YouTubeTranscript.noTrack(video).replace("이 영상에는 자막이 없어요.", "유튜브가 보낸 영상 정보에 자막 목록이 없었어요 (휴대폰용·컴퓨터용 페이지 모두).")
                    : "영상에 자막은 있는데(" + tracks.size() + "개) 유튜브가 자막 파일을 비워서 보냈어요 (받은 글자 " + body.length() + "자).") + app;
            }
        } catch (IOException | RuntimeException failed) {
            if (failed instanceof java.util.concurrent.CancellationException) throw (RuntimeException) failed;
            why = failed.getMessage() == null || failed.getMessage().isBlank() ? "유튜브에 연결하지 못했어요." : failed.getMessage();
        }
        // The app could not read it itself: its own browser asks Gemini's site, in the background, when that is on.
        if (((GyeotApp) context.getApplicationContext()).store().youtubeGeminiWeb()) {
            try {
                if (progress != null) progress.accept("AI 브라우저가 Gemini 홈페이지에서 대본을 받아 오는 중");
                String words = YouTubeGeminiWeb.transcript(context, id, stopped, progress);
                save(context, id, YouTubeTranscript.readable(video, "Gemini 홈페이지에서 받아 온 대본 (빠지거나 틀린 부분이 있을 수 있어요)", words));
                return keep(id, YouTubeTranscript.fromSite(video, words, YouTubeTranscript.LIMIT));
            } catch (java.util.concurrent.CancellationException halted) { throw halted; }
            catch (Exception failed) { why += " AI 브라우저로 Gemini 홈페이지에서 받아 오는 것도 안 됐어요: " + (failed.getMessage() == null || failed.getMessage().isBlank() ? "이유를 알 수 없어요." : failed.getMessage()); }
        }
        return YouTubeTranscript.missing(video, why);
    }
    /**
     * 대본 보기 when YouTube would not give the captions: Gemini watches the video and writes down what is said, with times, and it
     * is kept like a transcript (saying where it came from). Blocking.
     */
    static String writeDown(android.content.Context context, String id, String title, String channel, String key, Runnable guard, AtomicReference<Runnable> cancel) throws Exception {
        String words = GeminiAudioClient.watchYouTube(key, id, YouTubeTranscript.WRITE_DOWN, guard, cancel);
        if (words == null || words.isBlank()) throw new IOException("Gemini가 대본을 적어 주지 않았어요.");
        save(context, id, YouTubeTranscript.readable(new YouTubeTranscript.Video(id, title == null ? "" : title, channel == null ? "" : channel, 0, true, ""),
            "Gemini가 영상을 보고 받아 적은 대본 (빠지거나 틀린 부분이 있을 수 있어요)", words));
        return saved(context, id);
    }
    static final String KIND = "youtube";
    /** Whether a transcript is kept for this video. */
    static boolean kept(android.content.Context context, String id) { return KeptText.kept(context, KIND, id); }
    /** The transcript kept for reading (YouTubeTranscript.readable), or "" when this video has none. */
    static String saved(android.content.Context context, String id) { return KeptText.saved(context, KIND, id); }
    private static void save(android.content.Context context, String id, String text) { KeptText.save(context, KIND, id, text); }
    private static String keep(String id, String block) { synchronized (READ) { READ.put(id, block); } return block; }
    /**
     * The caption tracks YouTube's player service lists for the video when asked as its Android app asks
     * (YouTubeTranscript.appRequest), with the key the watch page carries; none without a key. Addresses that would need the
     * token the app cannot make are left out; the rest are asked for in plain XML.
     */
    private static List<YouTubeTranscript.Track> appTracks(String id, String html, AtomicReference<Runnable> cancel) throws IOException {
        String key = YouTubeTranscript.serviceKey(html);
        if (key.isEmpty()) return List.of();
        Map<String, String> headers = appHeaders(); headers.put("Content-Type", "application/json"); headers.put("Accept", "application/json");
        String answer = post(YouTubeTranscript.appPlayer(key), headers, YouTubeTranscript.appRequest(id).toString().getBytes(java.nio.charset.StandardCharsets.UTF_8), PAGE_BYTES, cancel);
        JSONObject player;
        try { player = new JSONObject(answer); } catch (org.json.JSONException unreadable) { throw new IOException("유튜브 플레이어 서비스의 답을 읽지 못했어요."); }
        List<YouTubeTranscript.Track> out = new java.util.ArrayList<>();
        for (YouTubeTranscript.Track track : YouTubeTranscript.tracks(player))
            if (!YouTubeTranscript.needsToken(track.url())) out.add(new YouTubeTranscript.Track(YouTubeTranscript.plainXml(track.url()), track.language(), track.name(), track.auto()));
        return out;
    }
    /** The headers the Android app's requests carry. */
    private static Map<String, String> appHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", YouTubeTranscript.APP_AGENT); headers.put("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6");
        return headers;
    }
    /** One answer from YouTube's own player service (an address this class built, on www.youtube.com). */
    private static String post(String url, Map<String, String> headers, byte[] body, int limit, AtomicReference<Runnable> cancel) throws IOException {
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("POST", url, headers, body, TIMEOUT_MS, cancel)) {
            if (response.status == 429) throw new IOException("유튜브가 지금은 요청이 많다고 거절했어요. 잠시 뒤에 다시 시도해 주세요.");
            if (response.status >= 300) throw new IOException("유튜브 플레이어 서비스가 응답하지 않았어요 (HTTP " + response.status + ").");
            return OpenAiAccount.Http.read(response.body, limit);
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
