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

    /** A progress line that is the outcome to keep showing (a channel that could not be read), not a step. */
    static final String NOTE = "note:";
    /**
     * The broadcasts on air now of these channels (a channel not on air adds nothing). A channel whose page could not be read
     * is said (progress, as a NOTE), not taken for one that is not on air. Blocking.
     */
    static List<MyTube.Video> onAir(List<MyTube.Channel> channels, Consumer<String> progress, AtomicReference<Runnable> cancel) throws Exception {
        List<MyTube.Video> out = new ArrayList<>(); List<String> unread = new ArrayList<>();
        for (MyTube.Channel c : channels) {
            progress.accept("‘" + c.title() + "’ 라이브를 확인하는 중");
            try { for (MyTube.Video v : channelLive(c.path(), cancel)) if (v.live() && out.stream().noneMatch(o -> o.id().equals(v.id()))) out.add(v.because(c.title() + " · 지금 방송 중")); }
            catch (java.util.concurrent.CancellationException stop) { throw stop; }
            catch (Exception failed) { if (!NO_LIVE.equals(failed.getMessage())) unread.add(c.title()); }
        }
        if (!unread.isEmpty()) progress.accept(NOTE + "‘" + String.join("’, ‘", unread) + "’ 채널을 읽지 못했어요 · ‘라이브 확인’을 다시 눌러 주세요");
        return out;
    }
    private static final String NO_LIVE = "이 채널은 라이브 방송이 없어요.";
    /** Chooses the feed's videos afresh and keeps them; progress says what it is doing. Blocking. */
    static List<MyTube.Video> refresh(Context context, AppStore store, MyTube.Feed feed, Consumer<String> progress, AtomicReference<Runnable> cancel) throws Exception {
        MyTubeBook book = book(context);
        // A 저장 목록 is the user's own: shown as saved, nothing chosen in; with 라이브만 보기, its channels' broadcasts on air now.
        if (feed.saved() || MyTube.SAVED_FEED_NAME.equals(feed.name()) || MyTube.OLD_SAVED_FEED_NAME.equals(feed.name())) {
            List<MyTube.Video> kept = book.liveWatch(feed.id()) ? onAir(book.sources(feed.id()), progress, cancel) : book.pinned(feed.id());
            book.videos(feed.id(), kept, System.currentTimeMillis()); return kept;
        }
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
        // 곡 수 · 영상 수: more searches and candidates for a bigger feed.
        int want = book.size(feed.id()), searches = MyTube.queriesFor(want), pool = Math.min(240, Math.max(CANDIDATES, want * 3));
        List<String> queries = new ArrayList<>();
        if (ai != null) {
            progress.accept("AI(" + ai.name + ")가 검색어를 고르는 중");
            String asked = MyTube.queriesInput(feed, referenceTitle, reference, taste, today) + (want > MyTube.SIZE ? "\n고를 개수: " + want + "개 · 검색어는 " + searches + "개까지" : "");
            try { queries = MyTube.queries(ai.ask(MyTube.QUERIES, asked, cancel), searches); }
            catch (java.util.concurrent.CancellationException stop) { throw stop; }
            catch (Exception failed) { progress.accept("검색어를 정하지 못해 설명으로 찾아요"); }
        }
        if (queries.isEmpty()) queries = List.of(fallbackQuery(feed));
        // What is never shown: the reference channel's own videos, and what the user skipped (and, for music, played lately).
        Set<String> leaveOut = new HashSet<>();
        for (MyTube.Video v : reference) leaveOut.add(v.id());
        for (MyTube.Video v : taste.skipped()) leaveOut.add(v.id());
        if (feed.kind() == MyTube.Kind.MUSIC) { List<String> played = book.played(feed.id()); leaveOut.addAll(played.subList(0, Math.min(80, played.size()))); }
        // A live feed: the channels saved into it first (their broadcasts on air now), then live broadcasts only.
        boolean live = feed.liveOnly();
        List<MyTube.Video> onAir = new ArrayList<>();
        if (live) for (MyTube.Channel c : book.sources(feed.id())) {
            progress.accept("‘" + c.title() + "’ 라이브를 확인하는 중");
            try { for (MyTube.Video v : channelLive(c.path(), cancel)) if (v.live() && onAir.stream().noneMatch(o -> o.id().equals(v.id()))) onAir.add(v.because("저장한 채널 · 지금 방송 중")); }
            catch (java.util.concurrent.CancellationException stop) { throw stop; }
            catch (Exception notOnAir) { /* that channel has no broadcast now */ }
        }
        for (MyTube.Video v : onAir) leaveOut.add(v.id());
        Map<String, MyTube.Video> found = new LinkedHashMap<>();
        for (String query : queries) {
            progress.accept("유튜브에서 " + (live ? "라이브를 " : "") + "찾는 중 · " + query);
            JSONObject data = MyTube.initialData(get(live ? MyTube.liveSearchUrl(query) : MyTube.searchUrl(query, feed.kind(), feed.days()), cancel));
            List<MyTube.Video> listed = data == null ? List.of() : MyTube.videos(data);
            if (feed.kind() == MyTube.Kind.NEWS) listed = MyTube.recent(listed, feed.days());
            for (MyTube.Video v : listed) {
                // A live feed takes only broadcasts on air now; any other feed none (a broadcast never ends, and the feed plays on).
                if (v.live() != live || leaveOut.contains(v.id()) || (!referenceTitle.isEmpty() && referenceTitle.equals(v.channel()))) continue;
                found.putIfAbsent(v.id(), v);
            }
            if (found.size() >= pool) break;
        }
        List<MyTube.Video> candidates = new ArrayList<>(found.values());
        if (candidates.size() > pool) candidates = candidates.subList(0, pool);
        if (candidates.isEmpty() && live) {
            if (onAir.isEmpty()) throw new IOException("지금 방송 중인 라이브를 찾지 못했어요. 잠시 뒤에 다시 골라 보세요.");
            book.videos(feed.id(), onAir, System.currentTimeMillis()); return onAir;
        }
        if (candidates.isEmpty()) throw new IOException(feed.kind() == MyTube.Kind.NEWS ? "최근 " + Math.max(1, feed.days()) + "일 안에 올라온 영상을 찾지 못했어요. 기간을 늘리거나 설명을 바꿔 보세요." : "찾은 영상이 없어요. 설명을 바꿔 보세요.");
        List<MyTube.Video> picks = List.of();
        if (ai != null) {
            progress.accept("AI(" + ai.name + ")가 " + candidates.size() + "개 중에서 고르는 중");
            try { picks = MyTube.picks(ai.ask(MyTube.PICKS, MyTube.picksInput(feed, candidates, referenceTitle, taste, want, today), cancel), candidates, want); }
            catch (java.util.concurrent.CancellationException stop) { throw stop; }
            catch (Exception failed) { progress.accept("AI가 고르지 못해 유튜브 순서대로 보여 줘요"); }
        }
        if (picks.isEmpty()) picks = new ArrayList<>(candidates.subList(0, Math.min(want, candidates.size())));
        // What the user kept stays on top: the saved channels' broadcasts (a live feed), or the videos saved into it.
        List<MyTube.Video> shown = new ArrayList<>(live ? onAir : book.pinned(feed.id()));
        for (MyTube.Video v : picks) if (shown.stream().noneMatch(k -> k.id().equals(v.id()))) shown.add(v);
        book.videos(feed.id(), shown, System.currentTimeMillis());
        return shown;
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
    /** 쇼츠 검색: YouTube's Shorts for the words, as YouTube orders them. Blocking. */
    static List<MyTube.Video> searchShorts(String words, AtomicReference<Runnable> cancel) throws IOException {
        JSONObject data = MyTube.initialData(get(MyTube.shortsSearchUrl(words), cancel));
        if (data == null) throw new IOException("유튜브 쇼츠 검색 결과를 읽지 못했어요. 잠시 뒤에 다시 해 주세요.");
        List<MyTube.Video> found = MyTube.shorts(data);
        return found.size() > 40 ? new ArrayList<>(found.subList(0, 40)) : found;
    }
    /** A channel's Shorts tab (newest first), each with the channel's name. Blocking. */
    static List<MyTube.Video> channelShorts(String path, String channel, AtomicReference<Runnable> cancel) throws IOException {
        JSONObject data = MyTube.initialData(get(MyTube.channelShortsUrl(path), cancel));
        if (data == null) throw new IOException("채널 쇼츠를 읽지 못했어요.");
        List<MyTube.Video> out = new ArrayList<>();
        for (MyTube.Video v : MyTube.shorts(data)) { out.add(new MyTube.Video(v.id(), v.title(), channel == null ? "" : channel, v.published(), v.length(), v.views(), v.reason())); if (out.size() >= 48) break; }
        return out;
    }
    /** A channel's 라이브 tab: on air now first, then its past broadcasts. Blocking. */
    static List<MyTube.Video> channelLive(String path, AtomicReference<Runnable> cancel) throws IOException {
        JSONObject data = MyTube.initialData(get(MyTube.channelStreamsUrl(path), cancel));
        if (data == null) throw new IOException(NO_LIVE);
        List<MyTube.Video> all = new ArrayList<>(MyTube.videos(data));
        all.sort((a, b) -> Boolean.compare(b.live(), a.live()));
        return all.size() > 40 ? new ArrayList<>(all.subList(0, 40)) : all;
    }
    /** The channel a video is from (its name and page, by oEmbed), or null when YouTube does not say. Blocking. */
    static MyTube.Channel channelOf(String videoId, AtomicReference<Runnable> cancel) {
        try { return MyTube.channelFromOembed(new JSONObject(get(MyTube.oembedUrl(videoId), cancel))); } catch (Exception unknown) { return null; }
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
    /** The AI scope chose asked once with no tools; who answered goes to by ("Claude · Opus 4.7"). Blocking. */
    static String askScoped(AppStore store, ScopedModels scope, String instructions, String input, AtomicReference<Runnable> cancel, java.util.function.Consumer<String> by) throws Exception {
        Ai ai = Ai.of(store, scope);
        if (ai == null) throw new IOException("물어보려면 아래에서 AI와 모델을 골라 주세요.");
        if (by != null) by.accept(ai.name + " · " + new TeamRoster(store, scope).label(ai.engine, ai.model));
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
                // A screen's own choice keeps its 생각 깊이; the main chat's is asked lightly.
                String chosen = scope == null ? "" : scope.effort(engine, model);
                String effort = new TeamRoster(store).profile(engine, model).normalize(chosen == null || chosen.isBlank() ? "low" : chosen);
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
