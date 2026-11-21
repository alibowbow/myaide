package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.MyTube;
import dev.jun.gyeot.core.MyTubeBook;
import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import org.json.JSONObject;

/**
 * 마이튜브's refresh: YouTube's own search pages (and, for a feed measured against a channel, that channel's newest
 * videos) read without a sign-in or key, then the main chat's AI chooses from what was found (core/MyTube's QUERIES and
 * PICKS). Without an AI connection the feed shows YouTube's results as they came. Never on the main thread.
 */
final class MyTubeClient {
    static final int CANDIDATES = 60, PICKS = 20;
    private static final String DESKTOP = "Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0";
    private static final int PAGE_BYTES = 6 * 1024 * 1024, TIMEOUT_MS = 20_000;
    private static MyTubeBook book;
    static synchronized MyTubeBook book(Context context) {
        File dir = new File(context.getApplicationContext().getFilesDir(), "mytube");
        if (book == null || !book.dir().equals(dir)) book = new MyTubeBook(dir);
        return book;
    }

    /** Chooses the feed's videos afresh and keeps them; progress says what it is doing. Blocking. */
    static List<MyTube.Video> refresh(Context context, AppStore store, MyTube.Feed feed, Consumer<String> progress, AtomicReference<Runnable> cancel) throws Exception {
        MyTubeBook book = book(context);
        MyTube.Taste taste = book.taste(feed.id());
        String today = java.time.LocalDate.now().toString();
        // The channel a VIDEOS feed is measured against: its newest videos tell what "like it" means, and are never chosen.
        List<MyTube.Video> reference = List.of(); String referenceTitle = "";
        String path = MyTube.channelPath(feed.channel());
        if (feed.kind() == MyTube.Kind.VIDEOS && !path.isEmpty()) {
            progress.accept("기준 채널의 최근 영상을 읽는 중");
            JSONObject data = MyTube.initialData(get(MyTube.channelVideosUrl(path), cancel));
            if (data == null) throw new IOException("기준 채널을 읽지 못했어요. 채널 주소를 확인해 주세요.");
            reference = MyTube.videos(data); referenceTitle = MyTube.channelTitle(data);
        }
        Ai ai = Ai.of(store);
        List<String> queries = new ArrayList<>();
        if (ai != null) {
            progress.accept("AI(" + ai.name + ")가 검색어를 고르는 중");
            try { queries = MyTube.queries(ai.ask(MyTube.QUERIES, MyTube.queriesInput(feed, referenceTitle, reference, taste, today), cancel)); }
            catch (java.util.concurrent.CancellationException stop) { throw stop; }
            catch (Exception failed) { progress.accept("검색어를 정하지 못해 설명으로 찾아요"); }
        }
        if (queries.isEmpty()) queries = List.of(fallbackQuery(feed));
        // What is never shown: the reference channel's own videos, and what the user skipped (and, for music, played lately).
        Set<String> leaveOut = new HashSet<>();
        for (MyTube.Video v : reference) leaveOut.add(v.id());
        for (MyTube.Video v : taste.skipped()) leaveOut.add(v.id());
        if (feed.kind() == MyTube.Kind.MUSIC) { List<String> played = book.played(feed.id()); leaveOut.addAll(played.subList(0, Math.min(80, played.size()))); }
        Map<String, MyTube.Video> found = new LinkedHashMap<>();
        for (String query : queries) {
            progress.accept("유튜브에서 찾는 중 · " + query);
            JSONObject data = MyTube.initialData(get(MyTube.searchUrl(query, feed.kind(), feed.days()), cancel));
            List<MyTube.Video> listed = data == null ? List.of() : MyTube.videos(data);
            if (feed.kind() == MyTube.Kind.NEWS) listed = MyTube.recent(listed, feed.days());
            for (MyTube.Video v : listed) {
                // A broadcast on air never ends: a feed that plays on would stop on it.
                if (v.live() || leaveOut.contains(v.id()) || (!referenceTitle.isEmpty() && referenceTitle.equals(v.channel()))) continue;
                found.putIfAbsent(v.id(), v);
            }
            if (found.size() >= CANDIDATES) break;
        }
        List<MyTube.Video> candidates = new ArrayList<>(found.values());
        if (candidates.size() > CANDIDATES) candidates = candidates.subList(0, CANDIDATES);
        if (candidates.isEmpty()) throw new IOException(feed.kind() == MyTube.Kind.NEWS ? "최근 " + Math.max(1, feed.days()) + "일 안에 올라온 영상을 찾지 못했어요. 기간을 늘리거나 설명을 바꿔 보세요." : "찾은 영상이 없어요. 설명을 바꿔 보세요.");
        List<MyTube.Video> picks = List.of();
        if (ai != null) {
            progress.accept("AI(" + ai.name + ")가 " + candidates.size() + "개 중에서 고르는 중");
            try { picks = MyTube.picks(ai.ask(MyTube.PICKS, MyTube.picksInput(feed, candidates, referenceTitle, taste, PICKS, today), cancel), candidates, PICKS); }
            catch (java.util.concurrent.CancellationException stop) { throw stop; }
            catch (Exception failed) { progress.accept("AI가 고르지 못해 유튜브 순서대로 보여 줘요"); }
        }
        if (picks.isEmpty()) picks = new ArrayList<>(candidates.subList(0, Math.min(PICKS, candidates.size())));
        book.videos(feed.id(), picks, System.currentTimeMillis());
        return picks;
    }
    /** 직접 검색: YouTube's own results for the words (videos only), as YouTube orders them. Blocking. */
    static List<MyTube.Video> search(String words, AtomicReference<Runnable> cancel) throws IOException {
        JSONObject data = MyTube.initialData(get(MyTube.searchUrl(words, MyTube.Kind.VIDEOS, 0), cancel));
        if (data == null) throw new IOException("유튜브 검색 결과를 읽지 못했어요. 잠시 뒤에 다시 해 주세요.");
        List<MyTube.Video> found = MyTube.videos(data);
        return found.size() > 40 ? new ArrayList<>(found.subList(0, 40)) : found;
    }
    /** The channels a search names (YouTube's channel filter), at most three; none when the page could not be read. Blocking. */
    static List<MyTube.Channel> channels(String words, AtomicReference<Runnable> cancel) {
        try {
            JSONObject data = MyTube.initialData(get(MyTube.channelSearchUrl(words), cancel));
            List<MyTube.Channel> found = data == null ? List.of() : MyTube.channels(data);
            return found.size() > 3 ? new ArrayList<>(found.subList(0, 3)) : found;
        } catch (Exception unreadable) { return List.of(); }
    }
    /** A channel: its broadcast on air now first (its 라이브 tab), then its newest videos (its 동영상 tab). Blocking. */
    static List<MyTube.Video> channelVideos(String path, AtomicReference<Runnable> cancel) throws IOException {
        Map<String, MyTube.Video> found = new LinkedHashMap<>();
        try {
            JSONObject streams = MyTube.initialData(get(MyTube.channelStreamsUrl(path), cancel));
            if (streams != null) for (MyTube.Video v : MyTube.videos(streams)) if (v.live()) found.putIfAbsent(v.id(), v);
        } catch (java.util.concurrent.CancellationException stop) { throw stop; }
        catch (IOException noStreamsTab) { /* a channel that never broadcast */ }
        JSONObject data = MyTube.initialData(get(MyTube.channelVideosUrl(path), cancel));
        if (data == null && found.isEmpty()) throw new IOException("채널 영상을 읽지 못했어요.");
        if (data != null) for (MyTube.Video v : MyTube.videos(data)) found.putIfAbsent(v.id(), v);
        List<MyTube.Video> all = new ArrayList<>(found.values());
        return all.size() > 40 ? new ArrayList<>(all.subList(0, 40)) : all;
    }
    /** A video opened by its link: YouTube's own title and channel for it (oEmbed), or a plain one when it says nothing. Blocking. */
    static MyTube.Video video(String id, AtomicReference<Runnable> cancel) {
        try { return MyTube.fromOembed(id, new JSONObject(get(MyTube.oembedUrl(id), cancel))); }
        catch (Exception unknown) { return MyTube.fromOembed(id, null); }
    }
    /** The main chat's AI asked once with no tools (내용 정리); says how to connect one when there is none. Blocking. */
    static String ask(AppStore store, String instructions, String input, AtomicReference<Runnable> cancel) throws Exception {
        Ai ai = Ai.of(store);
        if (ai == null) throw new IOException("정리하려면 메인 대화의 AI를 연결해 주세요.");
        return ai.ask(instructions, input, cancel);
    }
    /** 마이튜브 AI (its own model choice) asked once with no tools. Blocking. */
    static String askMyTube(AppStore store, String instructions, String input, AtomicReference<Runnable> cancel) throws Exception {
        Ai ai = Ai.of(store, ScopedModels.mytube(store));
        if (ai == null) throw new IOException("마이튜브 AI를 쓰려면 모델을 고르거나 AI를 연결해 주세요.");
        return ai.ask(instructions, input, cancel);
    }
    /** The name and model 마이튜브 AI talks with, for its model chip ("" when none is connected). */
    static String myTubeModel(AppStore store) {
        try { Ai ai = Ai.of(store, ScopedModels.mytube(store)); return ai == null ? "" : ai.name + " · " + new TeamRoster(store).label(ai.engine, ai.model); }
        catch (Exception unavailable) { return ""; }
    }
    /** What to search when no AI chose: the feed's description, or its name. */
    static String fallbackQuery(MyTube.Feed feed) {
        String about = feed.about() == null ? "" : feed.about().strip();
        String query = about.isEmpty() ? feed.name() : about.split("[.。\\n]")[0];
        return query.length() > 80 ? query.substring(0, 80) : query;
    }

    /** The main chat's AI (its engine and model), asked once with no tools; null when it has no connection. */
    private record Ai(AppStore store, String engine, String model, String effort, String name) {
        static Ai of(AppStore store) { return of(store, null); }
        /** With scope, that screen's own choice (마이튜브 AI's), else the main chat's. */
        static Ai of(AppStore store, ScopedModels scope) {
            try {
                ScopedModels models = scope != null ? scope : ScopedModels.main(store); String engine = models.engine();
                if (engine == null || engine.isBlank() || !store.ready(engine)) return null;
                String model = models.model(engine);
                String effort = new TeamRoster(store).profile(engine, model).normalize("low");
                return new Ai(store, engine, model, effort, dev.jun.gyeot.core.WorkBook.author(engine));
            } catch (Exception unavailable) { return null; }
        }
        String ask(String instructions, String input, AtomicReference<Runnable> cancel) throws Exception {
            return FileAiOperationsUi.complete(store, FileAiOperationsUi.Connection.capture(store, engine), model, effort, instructions, input, () -> {}, cancel);
        }
    }

    /** One page from YouTube's own site, following its redirects there (at most three) and nowhere else. */
    static String get(String url, AtomicReference<Runnable> cancel) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("User-Agent", DESKTOP); headers.put("Accept-Language", "ko-KR,ko;q=0.9,en;q=0.6"); headers.put("Accept", "text/html,application/xhtml+xml");
        // Past the cookie-consent page some regions show first.
        headers.put("Cookie", "SOCS=CAI; CONSENT=YES+1");
        String address = url;
        for (int hop = 0; hop < 4; hop++) {
            try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", address, headers, null, TIMEOUT_MS, cancel)) {
                if (response.status >= 300 && response.status < 400) {
                    String next = response.headers.get("location");
                    java.net.URI target = next == null ? null : java.net.URI.create(address).resolve(next);
                    String host = target == null || target.getHost() == null ? "" : target.getHost().toLowerCase(java.util.Locale.ROOT);
                    if (target == null || !"https".equals(target.getScheme()) || !(host.equals("www.youtube.com") || host.equals("m.youtube.com") || host.equals("youtube.com")))
                        throw new IOException("유튜브가 다른 화면(동의·로그인)으로 보내서 읽지 못했어요.");
                    address = target.toString(); continue;
                }
                if (response.status == 404) throw new IOException("유튜브에 그런 페이지가 없어요. 채널 주소를 확인해 주세요.");
                if (response.status == 429) throw new IOException("유튜브가 지금은 요청이 많다고 거절했어요. 잠시 뒤에 다시 해 주세요.");
                if (response.status >= 300) throw new IOException("유튜브가 응답하지 않았어요 (HTTP " + response.status + ").");
                return OpenAiAccount.Http.read(response.body, PAGE_BYTES);
            }
        }
        throw new IOException("유튜브가 주소를 계속 바꿔서 읽지 못했어요.");
    }
}
