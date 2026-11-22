package dev.jun.gyeot;

import android.content.Context;
import dev.jun.gyeot.core.GlobalSearch;
import dev.jun.gyeot.core.GlobalSearch.Hit;
import dev.jun.gyeot.core.SemanticSearch;
import dev.jun.gyeot.core.SemanticSearch.Doc;
import dev.jun.gyeot.core.SemanticSearch.Entry;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import org.json.JSONObject;

/**
 * 뜻으로 찾기 on the phone (core/SemanticSearch): the index file, building it a batch a minute in the background while it
 * is on, and finding by a query's meaning. Nothing is sent until it is turned on in 전체 검색. A run sends at most RUN
 * things, newest first, and stops for the day before the embedding model's free requests run short for queries.
 */
final class SemanticIndex {
    private SemanticIndex() {}
    /** Things sent in one run, and how long after a run the next may start. */
    static final int RUN = 200;
    static final long AGAIN = 20 * 60_000L, BATCH_GAP = 62_000L;
    private static final String API = "https://generativelanguage.googleapis.com/v1beta/models/";
    private static final ExecutorService worker = Executors.newSingleThreadExecutor(runnable -> { Thread t = new Thread(runnable, "semantic-index"); t.setDaemon(true); return t; });
    private static Map<String, Entry> index;
    private static volatile boolean running;
    private static volatile long lastRun;
    /** What the last look found: things indexed as they are now, and things worth indexing (-1: not looked yet). */
    private static volatile int ready = -1, total = -1;
    private static volatile String problem = "";
    /** Query vectors already asked, so typing back to a query costs nothing. */
    private static final Map<String, float[]> asked = new LinkedHashMap<>(16, .75f, true) {
        @Override protected boolean removeEldestEntry(Map.Entry<String, float[]> eldest) { return size() > 40; }
    };

    /** The open 전체 검색 screen, told (on the main thread) when the index moved on; screens elsewhere are not redrawn. */
    static volatile Runnable listener;
    private static final android.os.Handler main = new android.os.Handler(android.os.Looper.getMainLooper());
    private static void tell() { main.post(() -> { Runnable each = listener; if (each != null) each.run(); }); }
    private static File file(Context context) { return new File(new File(context.getFilesDir(), "semantic"), "index.bin"); }
    private static synchronized Map<String, Entry> load(Context context) {
        if (index != null) return index;
        File file = file(context);
        if (!file.isFile()) return index = new LinkedHashMap<>();
        try (DataInputStream in = new DataInputStream(new BufferedInputStream(new FileInputStream(file)))) { index = SemanticSearch.read(in); }
        catch (IOException e) { index = new LinkedHashMap<>(); }
        return index;
    }
    private static synchronized void save(Context context) {
        if (index == null) return;
        File file = file(context), parent = file.getParentFile(), next = new File(parent, "index.bin.new");
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) return;
        try (DataOutputStream out = new DataOutputStream(new BufferedOutputStream(new FileOutputStream(next)))) { SemanticSearch.write(out, new ArrayList<>(index.values())); }
        catch (IOException e) { next.delete(); return; }
        if (!next.renameTo(file)) next.delete();
    }
    /** Turned off: the index and its file go, so nothing kept stays behind. */
    static void forget(Context context) {
        synchronized (SemanticIndex.class) { index = new LinkedHashMap<>(); }
        synchronized (asked) { asked.clear(); }
        ready = -1; total = -1; problem = "";
        worker.execute(() -> { File file = file(context); if (file.isFile()) file.delete(); });
    }
    private static String key(AppStore store) { try { return store.secrets.workKey(); } catch (Exception e) { return ""; } }

    /** One line for the switch: how far the index has come, or why it waits. */
    static String progress() {
        if (!problem.isEmpty()) return problem;
        if (total < 0) return running ? "색인을 만드는 중이에요…" : "곧 색인을 만들기 시작해요.";
        if (total == 0) return "아직 찾을 것이 없어요.";
        return (ready >= total ? "모두 색인했어요" : running ? "색인하는 중" : "색인 중 (조금씩 이어서 해요)") + " · " + ready + " / " + total + "개";
    }
    /** Turned on: the first run starts now. */
    static void start(Context context, AppStore store) { lastRun = 0; problem = ""; catchUpIfDue(context, store); tell(); }
    /** Builds the index on, when it is on, a key is there, and the last run was a while ago. Any thread. */
    static void catchUpIfDue(Context context, AppStore store) {
        if (!store.semanticSearch() || running || System.currentTimeMillis() - lastRun < AGAIN) return;
        Context app = context.getApplicationContext();
        running = true; lastRun = System.currentTimeMillis();
        worker.execute(() -> { try { catchUp(app, store); } catch (RuntimeException ignored) {} finally { running = false; lastRun = System.currentTimeMillis(); tell(); } });
    }
    private static void catchUp(Context context, AppStore store) {
        String key = key(store);
        if (key.isBlank()) { problem = "설정 → Gemini API 키를 넣으면 색인을 만들어요."; return; }
        problem = "";
        List<Doc> docs = GlobalSearchSource.docs(context, store);
        Map<String, Entry> map = load(context);
        boolean pruned; synchronized (SemanticIndex.class) { pruned = SemanticSearch.prune(map, docs); }
        if (pruned) save(context);
        String model = GeminiQuotas.usable(SemanticSearch.MODELS);
        if (model == null) { problem = "이 Gemini 키로는 임베딩 모델을 쓸 수 없어요."; return; }
        total = SemanticSearch.worthCount(docs);
        synchronized (SemanticIndex.class) { ready = SemanticSearch.ready(docs, map, model); }
        tell();
        int perDay = GeminiQuotas.perDay(model), budget = perDay <= 0 ? RUN : perDay - SemanticSearch.QUERY_RESERVE - GeminiQuotas.uses(model);
        List<Doc> pending;
        synchronized (SemanticIndex.class) { pending = SemanticSearch.pending(docs, map, model, Math.min(RUN, budget)); }
        for (int from = 0; from < pending.size() && store.semanticSearch(); from += SemanticSearch.BATCH) {
            if (from > 0) try { Thread.sleep(BATCH_GAP); } catch (InterruptedException e) { return; }
            List<Doc> batch = pending.subList(from, Math.min(pending.size(), from + SemanticSearch.BATCH));
            int tokens = 0; for (Doc doc : batch) tokens += SemanticSearch.body(doc).length() * 2 / 3 + 8;
            if (!GeminiQuotas.ready(model, tokens)) return;
            List<float[]> vectors;
            try {
                JSONObject answer = post(key, model, "batchEmbedContents", SemanticSearch.batch(model, batch));
                GeminiQuotas.used(model, tokens, batch.size());
                vectors = SemanticSearch.vectors(answer, batch.size());
            } catch (Refused refused) { if (refused.quota) return; problem = refused.getMessage(); return; }
            catch (IOException e) { return; }
            if (vectors == null) return;
            if (!store.semanticSearch()) return;
            synchronized (SemanticIndex.class) {
                for (int i = 0; i < batch.size(); i++) {
                    Doc doc = batch.get(i);
                    map.put(doc.key(), new Entry(doc.key(), SemanticSearch.hash(doc), model, doc.time(), doc.title() == null ? "" : doc.title(), SemanticSearch.snippet(doc), vectors.get(i)));
                }
                ready = SemanticSearch.ready(docs, map, model);
            }
            save(context); tell();
        }
    }

    /** True when there is an index to search. */
    static boolean searchable(Context context, AppStore store) {
        if (!store.semanticSearch()) return false;
        Map<String, Entry> map = load(context);
        synchronized (SemanticIndex.class) { return !map.isEmpty(); }
    }
    /**
     * The things nearest the query's meaning, as rows (their kind and id open them as a word search's do); empty when it is off,
     * there is no index, or Gemini cannot answer now. Blocking; off the main thread.
     */
    static List<Hit> find(Context context, AppStore store, String query) {
        List<Hit> out = new ArrayList<>();
        String text = query == null ? "" : query.strip();
        if (!store.semanticSearch() || text.length() < 2) return out;
        Map<String, Entry> map = load(context); List<Entry> entries; String model;
        synchronized (SemanticIndex.class) { entries = new ArrayList<>(map.values()); }
        model = SemanticSearch.model(entries);
        if (model == null) return out;
        float[] vector; synchronized (asked) { vector = asked.get(model + "\n" + text); }
        if (vector == null) {
            String key = key(store);
            if (key.isBlank() || !GeminiQuotas.ready(model, text.length())) return out;
            try {
                JSONObject answer = post(key, model, "embedContent", SemanticSearch.query(text));
                GeminiQuotas.used(model, text.length(), 1);
                vector = SemanticSearch.vector(answer);
            } catch (IOException e) { return out; }
            if (vector == null) return out;
            synchronized (asked) { asked.put(model + "\n" + text, vector); }
        }
        for (SemanticSearch.Scored scored : SemanticSearch.rank(vector, entries, model, SemanticSearch.SHOWN, SemanticSearch.LEAST)) {
            Entry entry = scored.entry();
            out.add(new Hit(entry.kind(), entry.id(), entry.title().isBlank() ? GlobalSearch.label(entry.kind()) : entry.title(), entry.snippet(), entry.time(), false));
        }
        return out;
    }

    /** Google said no: quota (day or minute, wait) or another reason (the message says it). */
    private static final class Refused extends IOException {
        final boolean quota;
        Refused(String message, boolean quota) { super(message); this.quota = quota; }
    }
    private static JSONObject post(String key, String model, String method, JSONObject request) throws IOException {
        byte[] body = request.toString().getBytes(StandardCharsets.UTF_8);
        HttpURLConnection connection = (HttpURLConnection) URI.create(API + model + ":" + method).toURL().openConnection();
        try {
            connection.setConnectTimeout(20_000); connection.setReadTimeout(60_000); connection.setRequestMethod("POST"); connection.setDoOutput(true);
            connection.setRequestProperty("Content-Type", "application/json"); connection.setRequestProperty("x-goog-api-key", key);
            connection.setFixedLengthStreamingMode(body.length);
            try (OutputStream output = connection.getOutputStream()) { output.write(body); }
            int status = connection.getResponseCode();
            String text = read(status >= 400 ? connection.getErrorStream() : connection.getInputStream());
            if (status == 200) { try { return new JSONObject(text); } catch (Exception e) { throw new IOException("임베딩 답을 읽지 못했어요."); } }
            boolean quota = GeminiQuotas.refused(model, status, text) && status == 429;
            String message;
            try { JSONObject error = new JSONObject(text).optJSONObject("error"); message = error == null ? "" : error.optString("message"); } catch (Exception e) { message = ""; }
            throw new Refused(quota ? "Gemini 무료 한도를 기다려요." : "색인을 만들지 못했어요 (" + status + (message.isBlank() ? "" : ": " + message.strip()) + ")", quota);
        } finally { connection.disconnect(); }
    }
    private static String read(InputStream input) throws IOException {
        if (input == null) return "";
        try (InputStream in = input; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[16384]; int size; while ((size = in.read(buffer)) != -1) out.write(buffer, 0, size);
            return out.toString(StandardCharsets.UTF_8);
        }
    }
}
