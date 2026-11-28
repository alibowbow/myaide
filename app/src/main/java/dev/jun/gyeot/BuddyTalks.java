package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.DayLog;
import dev.jun.gyeot.core.TalkLog;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.json.JSONObject;

/**
 * Every talk with 퍼레 kept on the phone (기억 2.0, core/TalkLog): the turns of 퍼레와 대화 (by the page's id for the talk) and of
 * the voice talk, written as they are learned from (BuddyTalk.learnTalk), a guest's never; a talk deleted on the page goes
 * here too (forget). "지난번에 내가 뭐라고 했지?" is answered from them (recall: day_recall's words about a talk), by the words
 * said and by meaning (BuddyMeaning). The file (buddy/talks.jsonl) is read once and again only when it changed; written on
 * one background thread, never the main one.
 */
final class BuddyTalks {
    private BuddyTalks() {}
    private static final ExecutorService IO = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-buddy-talks"); t.setDaemon(true); return t; });
    private static List<TalkLog.Turn> cache; private static long cacheLength = -2, cacheModified = -2; private static String cachePath = "";
    private static File file(Context c) { return new File(new File(c.getFilesDir(), "buddy"), "talks.jsonl"); }
    /** The turns kept, oldest first (read only when the file changed). Off the main thread. */
    static synchronized List<TalkLog.Turn> all(Context c) {
        File f = file(c);
        long length = f.isFile() ? f.length() : -1, modified = f.isFile() ? f.lastModified() : -1;
        if (cache != null && f.getPath().equals(cachePath) && length == cacheLength && modified == cacheModified) return cache;
        List<TalkLog.Turn> read;
        try { read = f.isFile() ? TalkLog.read(new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8)) : new ArrayList<>(); }
        catch (Exception unreadable) { read = new ArrayList<>(); }
        keep(f, read);
        return cache;
    }
    private static void keep(File f, List<TalkLog.Turn> list) {
        cache = List.copyOf(list); cachePath = f.getPath();
        cacheLength = f.isFile() ? f.length() : -1; cacheModified = f.isFile() ? f.lastModified() : -1;
    }
    private static synchronized void write(Context c, List<TalkLog.Turn> list) {
        File f = file(c);
        try {
            f.getParentFile().mkdirs();
            File tmp = new File(f.getPath() + ".tmp");
            Files.write(tmp.toPath(), TalkLog.save(list).getBytes(StandardCharsets.UTF_8));
            if (!tmp.renameTo(f)) Files.write(f.toPath(), TalkLog.save(list).getBytes(StandardCharsets.UTF_8));
            keep(f, list);
        } catch (Exception unwritable) { cache = null; }
    }
    /** A talk's turns from turn from on kept (conv: the page's id for it, TalkLog.VOICE for the voice talk), off the main thread. */
    static void add(Context c, List<String[]> turns, int from, String conv) {
        if (turns == null || turns.isEmpty()) return;
        Context app = c.getApplicationContext(); List<String[]> copy = new ArrayList<>(turns); long now = System.currentTimeMillis();
        try {
            IO.execute(() -> {
                List<TalkLog.Turn> added = TalkLog.added(copy, from, conv, now);
                if (added.isEmpty()) return;
                write(app, TalkLog.kept(all(app), added, now));
                BuddyMeaning.talksChanged(app);
            });
        } catch (RuntimeException closing) { /* the app is going */ }
    }
    /** A talk deleted on the page ("*": every typed one): its turns go, and their vectors with them. */
    static void forget(Context c, String conv) {
        Context app = c.getApplicationContext();
        try { IO.execute(() -> { List<TalkLog.Turn> was = all(app), left = TalkLog.without(was, conv); if (left.size() != was.size()) { write(app, left); BuddyMeaning.talksChanged(app); } }); }
        catch (RuntimeException closing) { /* the app is going */ }
    }
    /** Waits for the writes asked before (tests, and a recall right after a talk). */
    static void settle() { try { IO.submit(() -> {}).get(10, TimeUnit.SECONDS); } catch (Exception ignored) {} }

    /**
     * The answer to words about what was said in a talk ("지난번에 내가 뭐라고 했지?", TalkLog.asks), or null for other words
     * (day_recall then reads the day's own lines): the turns found, with when, or what was looked through. Off the main thread.
     */
    static String recall(Context c, JSONObject act, String heard) {
        String said = heard == null || heard.isBlank() || dev.jun.gyeot.core.DeviceActions.affirmative(heard) ? (act == null ? "" : act.optString("query")) : heard.strip();
        if (!TalkLog.asks(said)) return null;
        settle();
        List<TalkLog.Turn> all = all(c);
        ZonedDateTime now = ZonedDateTime.now(ZoneId.systemDefault());
        if (all.isEmpty()) return "아직 찾아볼 우리 대화 기록이 없어. 이제부터 나눈 얘기는 여기 남겨 둘게.";
        DayLog.Range r = DayLog.period(said, now.toLocalDate());
        List<String> words = TalkLog.words(said);
        float[] q = words.isEmpty() ? null : BuddyMeaning.query(c, String.join(" ", words), BuddyMeaning.CHAT_MS);
        List<TalkLog.Turn> hits = TalkLog.find(all, said, BuddyMeaning.talkVectors(c), q, r == null ? null : r.from(), r == null ? null : r.to(), now.getZone(), now.toInstant().toEpochMilli(), 3);
        if (hits.isEmpty()) {
            int looked = 0; for (TalkLog.Turn t : all) if (t.user()) looked++;
            return TalkLog.notFound(words, looked, r == null ? "" : r.label());
        }
        return TalkLog.answer(hits, now);
    }
    /** Forgets what was read (tests start clean). */
    static synchronized void reset() { cache = null; cacheLength = cacheModified = -2; cachePath = ""; }
}
