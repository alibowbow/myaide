package dev.jun.gyeot;

import android.content.Context;
import android.os.Looper;
import dev.jun.gyeot.core.BuddyMemory;
import dev.jun.gyeot.core.BuddyRecall;
import dev.jun.gyeot.core.TalkLog;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.json.JSONObject;

/**
 * 퍼레's memory and its talks found by meaning (기억 2.0): each memory the user told and each of their turns in a talk becomes
 * one vector from NVIDIA's free embedding model (BuddyRecall.EMBED_MODEL, on the user's own NVIDIA key, the place 퍼레's
 * words already go), kept on the phone beside the memory (buddy/memory.vec, buddy/talks.vec); a question becomes one vector
 * too, within a time box, and the nearest come to mind (BuddyRecall.find, TalkLog.find). Private memories, passwords and keys
 * never leave the phone, and e-mail addresses, phone numbers and long numbers are taken out first (BuddyRecall.outgoing).
 * Gemini is never asked (its free tier may use what is sent). No NVIDIA key, a failure or a slow answer: words alone.
 * Vectors are made a batch at a time on one background thread, only for what is new or said anew; never on the main thread.
 */
final class BuddyMeaning {
    private BuddyMeaning() {}
    /** How texts become vectors (tests put their own: nothing goes on the network then). query: a question, not a kept thing. */
    interface Embed { List<float[]> vectors(AppStore store, List<String> texts, boolean query, int timeoutMs) throws Exception; }
    static volatile Embed embed = BuddyMeaning::nvidia;
    /** A failure waits this long before NVIDIA is asked again (words alone meanwhile). */
    static final long PAUSE_MS = 10 * 60_000L;
    private static volatile long pausedUntil;
    /** The time boxes: a typed answer may wait this long for the question's vector, a spoken one less. */
    static final int CHAT_MS = 1_500, VOICE_MS = 600;
    /** At most this many of the user's newest turns are kept as vectors. */
    static final int TALK_VECTORS = 1_500;
    private static final ExecutorService INDEX = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-buddy-meaning"); t.setDaemon(true); return t; });
    private static final ExecutorService ASK = Executors.newCachedThreadPool(r -> { Thread t = new Thread(r, "gyeot-buddy-meaning-ask"); t.setDaemon(true); return t; });
    private static final AtomicBoolean memoriesDue = new AtomicBoolean(), talksDue = new AtomicBoolean();

    /** Whether vectors can be made now: NVIDIA connected and not paused after a failure. */
    static boolean on(Context c) {
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        return store.nim.connected() && System.currentTimeMillis() >= pausedUntil;
    }
    private static List<float[]> nvidia(AppStore store, List<String> texts, boolean query, int timeoutMs) throws Exception {
        String key = dev.jun.gyeot.core.NimProtocol.key(store.secrets.get(SecretStore.NIM));
        byte[] body = BuddyRecall.request(texts, query).toString().getBytes(StandardCharsets.UTF_8);
        try (OpenAiAccount.Http.Exchange r = OpenAiAccount.Http.open("POST", BuddyRecall.EMBED_URL,
                Map.of("Authorization", "Bearer " + key, "Content-Type", "application/json", "Accept", "application/json"), body, timeoutMs, null)) {
            if (r.status != 200) throw new dev.jun.gyeot.core.ProviderError(r.status, "NVIDIA 임베딩 HTTP " + r.status);
            JSONObject answer = new JSONObject(OpenAiAccount.Http.read(r.body, 16 * 1024 * 1024));
            List<float[]> out = BuddyRecall.vectors(answer, texts.size());
            if (out == null) throw new IllegalStateException("NVIDIA 임베딩 답을 읽지 못했어요.");
            UsageLedger.count(store, UsageLedger.BUDDY, dev.jun.gyeot.core.WorkBook.NIM, BuddyRecall.EMBED_MODEL, dev.jun.gyeot.core.Usage.chat(answer.optJSONObject("usage")));
            return out;
        }
    }

    // ── a question's vector, kept a while (one answer asks for it two or three times) ──
    private static final Map<String, Object[]> asked = new LinkedHashMap<>(16, 0.75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, Object[]> e) { return size() > 32; }
    };
    /**
     * The question's vector within timeoutMs, or null: no NVIDIA, paused after a failure, slow, nothing that may go (a
     * password, a key), or the main thread (never waited on there).
     */
    static float[] query(Context c, String text, int timeoutMs) {
        String t = BuddyRecall.outgoing(text, false);
        if (t.isEmpty() || Looper.myLooper() == Looper.getMainLooper() || !on(c)) return null;
        long now = System.currentTimeMillis();
        synchronized (asked) {
            Object[] kept = asked.get(t);
            if (kept != null && kept[0] instanceof float[] v && now - (long) kept[1] < 30 * 60_000L) return v;
            // Still on its way from an earlier ask that gave up waiting: not waited for twice (it is kept when it comes).
            if (kept != null && kept[0] == WAITING && now - (long) kept[1] < 60_000L) return null;
            asked.put(t, new Object[]{WAITING, now});
        }
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        Embed by = embed;
        Future<float[]> f = ASK.submit(() -> {
            try {
                List<float[]> v = by.vectors(store, List.of(t), true, Math.max(timeoutMs, 3_000));
                float[] out = v == null || v.isEmpty() ? null : v.get(0);
                synchronized (asked) { if (out != null) asked.put(t, new Object[]{out, System.currentTimeMillis()}); else asked.remove(t); }
                return out;
            } catch (Exception failed) { pausedUntil = System.currentTimeMillis() + PAUSE_MS; synchronized (asked) { asked.remove(t); } return null; }
        });
        try { return f.get(timeoutMs, TimeUnit.MILLISECONDS); }
        catch (java.util.concurrent.TimeoutException slow) { return null; }
        catch (InterruptedException stopped) { Thread.currentThread().interrupt(); return null; }
        catch (Exception failed) { return null; }
    }
    private static final Object WAITING = new Object();

    // ── the vectors kept: read once, written as they change ──
    private static Map<String, float[]> memoryVectors, talkVectors;
    private static File file(Context c, String name) { return new File(new File(c.getFilesDir(), "buddy"), name); }
    private static Map<String, float[]> load(File f) {
        if (!f.isFile()) return new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(f)))) { return BuddyRecall.read(in, BuddyRecall.EMBED_MODEL); }
        catch (Exception unreadable) { return new LinkedHashMap<>(); }
    }
    private static void store(File f, Map<String, float[]> vectors) {
        try {
            f.getParentFile().mkdirs();
            if (vectors.isEmpty()) { f.delete(); return; }
            File tmp = new File(f.getPath() + ".tmp");
            try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(tmp)))) { BuddyRecall.write(out, BuddyRecall.EMBED_MODEL, vectors); }
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f); }
        } catch (Exception unwritable) { /* made again next time */ }
    }
    /** The memories' vectors as kept now (read from the phone once). Off the main thread. */
    static synchronized Map<String, float[]> memoryVectors(Context c) {
        if (memoryVectors == null) memoryVectors = load(file(c, "memory.vec"));
        return memoryVectors;
    }
    /** The talks' vectors as kept now (read once). Off the main thread. */
    static synchronized Map<String, float[]> talkVectors(Context c) {
        if (talkVectors == null) talkVectors = load(file(c, "talks.vec"));
        return talkVectors;
    }
    /** All of it let go (기억 모두 지우기): the vectors go with the memories. */
    static synchronized void forgetMemories(Context c) { memoryVectors = new LinkedHashMap<>(); file(c, "memory.vec").delete(); }

    // ── made in the background ──
    /** The memories changed: their vectors brought up to date soon (the new and the said anew made, the deleted let go). */
    static void memoriesChanged(Context c) {
        Context app = c.getApplicationContext();
        if (memoriesDue.compareAndSet(false, true)) try { INDEX.execute(() -> { memoriesDue.set(false); indexMemories(app); }); } catch (RuntimeException closing) { memoriesDue.set(false); }
    }
    /** The talks changed: the new turns' vectors made soon. */
    static void talksChanged(Context c) {
        Context app = c.getApplicationContext();
        if (talksDue.compareAndSet(false, true)) try { INDEX.execute(() -> { talksDue.set(false); indexTalks(app); }); } catch (RuntimeException closing) { talksDue.set(false); }
    }
    /** Waits until what was asked to be made is made (tests). */
    static void settle() { try { INDEX.submit(() -> {}).get(20, TimeUnit.SECONDS); } catch (Exception ignored) {} }

    private static void indexMemories(Context c) {
        // Nothing to make and nothing kept to let go: the memories are not even read.
        if (!on(c) && memoryVectors(c).isEmpty()) return;
        List<BuddyMemory.Entry> all = BuddyTalk.memories(c);
        List<String> keys = new ArrayList<>(); for (BuddyMemory.Entry e : all) keys.add(BuddyRecall.key(e));
        Map<String, float[]> now;
        synchronized (BuddyMeaning.class) { now = BuddyRecall.kept(memoryVectors(c), keys); }
        List<String> wantKeys = new ArrayList<>(), texts = new ArrayList<>();
        for (BuddyMemory.Entry e : all) {
            String k = BuddyRecall.key(e); if (now.containsKey(k)) continue;
            String t = BuddyRecall.outgoing(e.text(), e.secret()); if (t.isEmpty()) continue;
            wantKeys.add(k); texts.add(t);
        }
        boolean pruned = now.size() != memoryVectors(c).size();
        boolean made = embedInto(c, now, wantKeys, texts);
        if (made || pruned) synchronized (BuddyMeaning.class) { memoryVectors = now; store(file(c, "memory.vec"), now); }
    }
    private static void indexTalks(Context c) {
        if (!on(c) && talkVectors(c).isEmpty()) return;
        List<TalkLog.Turn> all = BuddyTalks.all(c);
        List<String> keys = new ArrayList<>();
        List<TalkLog.Turn> mine = new ArrayList<>(); for (TalkLog.Turn t : all) if (t.user()) mine.add(t);
        mine = mine.subList(Math.max(0, mine.size() - TALK_VECTORS), mine.size());
        for (TalkLog.Turn t : mine) keys.add(t.key());
        Map<String, float[]> now;
        synchronized (BuddyMeaning.class) { now = BuddyRecall.kept(talkVectors(c), keys); }
        List<String> wantKeys = new ArrayList<>(), texts = new ArrayList<>();
        // The newest first: a talk just had is the likeliest to be asked about.
        for (int i = mine.size() - 1; i >= 0; i--) {
            TalkLog.Turn t = mine.get(i); if (now.containsKey(t.key()) || t.text().replaceAll("\\s", "").length() < 4) continue;
            String out = BuddyRecall.outgoing(t.text(), false); if (out.isEmpty()) continue;
            wantKeys.add(t.key()); texts.add(out);
        }
        boolean pruned = now.size() != talkVectors(c).size();
        boolean made = embedInto(c, now, wantKeys, texts);
        if (made || pruned) synchronized (BuddyMeaning.class) { talkVectors = now; store(file(c, "talks.vec"), now); }
    }
    /** Up to four batches made into vectors (the rest the next time); stops at the first failure, which pauses NVIDIA a while. */
    private static boolean embedInto(Context c, Map<String, float[]> into, List<String> keys, List<String> texts) {
        if (keys.isEmpty() || !on(c)) return false;
        AppStore store = ((GyeotApp) c.getApplicationContext()).store();
        boolean made = false;
        for (int from = 0, batches = 0; from < keys.size() && batches < 4; from += BuddyRecall.BATCH, batches++) {
            int to = Math.min(keys.size(), from + BuddyRecall.BATCH);
            try {
                List<float[]> v = embed.vectors(store, texts.subList(from, to), false, 30_000);
                if (v == null || v.size() != to - from) break;
                for (int i = from; i < to; i++) into.put(keys.get(i), v.get(i - from));
                made = true;
            } catch (Exception failed) { pausedUntil = System.currentTimeMillis() + PAUSE_MS; break; }
        }
        return made;
    }
    /** Forgets what was kept in memory (tests start clean). */
    static synchronized void reset() { memoryVectors = null; talkVectors = null; pausedUntil = 0; synchronized (asked) { asked.clear(); } }
}
