package dev.jun.gyeot.core;

import java.io.DataInput;
import java.io.DataOutput;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * 뜻으로 찾기: 전체 검색 by meaning as well as by words. Each thing the app keeps as text (GlobalSearch's kinds) becomes one
 * vector from Gemini's embedding model, kept on the phone; a query becomes one vector too, and the nearest things are the
 * results, whatever words they use. Gemini's free tier allows 1,000 embedding requests a day and 30,000 tokens a minute, so
 * the index is built a few batches at a time, newest first, and only what changed is sent again (hash). Pure Java: the
 * request bodies, the vectors, the index file and the ranking.
 */
public final class SemanticSearch {
    private SemanticSearch() {}
    /** The embedding models, best first; one index keeps to one model (vectors of two models do not compare). */
    public static final String[] MODELS = {"gemini-embedding-001", "gemini-embedding-2", "gemini-embedding-2-preview"};
    /** Short vectors: a quarter of the size, nearly as good for search. */
    public static final int DIMENSIONS = 768;
    /** The characters of one thing sent (its title and first words), within one batch's share of the minute's tokens. */
    public static final int DOC_CHARS = 1_200;
    /** Things in one batch request; at most one batch a minute keeps under the tokens a minute. */
    public static final int BATCH = 20;
    /** Leaves this many requests a day for queries: the index stops for the day once the rest is used. */
    public static final int QUERY_RESERVE = 300;
    /** How close a result has to be (cosine), and how many are shown. */
    public static final double LEAST = 0.62, SPREAD = 0.15;
    public static final int SHOWN = 12, SNIPPET = 90;
    private static final int FILE_VERSION = 1;

    /** One thing to index: its GlobalSearch kind and id, title, text and time. */
    public record Doc(String kind, String id, String title, String text, long time) {
        public String key() { return kind + "/" + id; }
    }
    /** One indexed thing: its key, the hash of what was sent, the model, its time, title and first words, and its vector. */
    public record Entry(String key, int hash, String model, long time, String title, String snippet, float[] vector) {
        public String kind() { int cut = key.indexOf('/'); return cut < 0 ? key : key.substring(0, cut); }
        public String id() { int cut = key.indexOf('/'); return cut < 0 ? "" : key.substring(cut + 1); }
    }
    /** A result: the entry and how close it is. */
    public record Scored(Entry entry, double score) {}

    /** What is sent for a thing: its title, then its first words, the whitespace folded. */
    public static String body(Doc doc) {
        String title = doc.title() == null ? "" : doc.title().strip(), text = doc.text() == null ? "" : doc.text().replaceAll("\\s+", " ").strip();
        String all = title.isEmpty() ? text : text.isEmpty() ? title : title + "\n" + text;
        return all.length() <= DOC_CHARS ? all : all.substring(0, DOC_CHARS);
    }
    public static int hash(Doc doc) { return body(doc).hashCode(); }
    /** The first words of a thing, for its row. */
    public static String snippet(Doc doc) {
        String text = doc.text() == null ? "" : doc.text().replaceAll("\\s+", " ").strip();
        return text.length() <= SNIPPET ? text : text.substring(0, SNIPPET).strip() + "…";
    }
    /** Worth indexing: a few words at least, some of them its own (a chat message's title alone is the same for all). */
    public static boolean worth(Doc doc) { return body(doc).length() >= 8 && doc.text() != null && doc.text().strip().length() >= 2; }

    /** The things to send now for model, newest first, at most most: new, changed since, or of another model. */
    public static List<Doc> pending(List<Doc> docs, Map<String, Entry> index, String model, int most) {
        List<Doc> out = new ArrayList<>();
        for (Doc doc : docs) {
            if (!worth(doc)) continue;
            Entry entry = index.get(doc.key());
            if (entry == null || entry.hash() != hash(doc) || !entry.model().equals(model)) out.add(doc);
        }
        out.sort(Comparator.comparingLong(Doc::time).reversed());
        return out.size() <= most ? out : new ArrayList<>(out.subList(0, Math.max(0, most)));
    }
    /** Drops the entries whose thing is gone; true when any was dropped. */
    public static boolean prune(Map<String, Entry> index, List<Doc> docs) {
        Set<String> keys = new HashSet<>(); for (Doc doc : docs) keys.add(doc.key());
        return index.keySet().removeIf(key -> !keys.contains(key));
    }
    /** How many of the things are indexed with model, as they are now. */
    public static int ready(List<Doc> docs, Map<String, Entry> index, String model) {
        int ready = 0;
        for (Doc doc : docs) { Entry entry = index.get(doc.key()); if (entry != null && entry.model().equals(model) && entry.hash() == hash(doc)) ready++; }
        return ready;
    }
    /** How many of the things are worth indexing. */
    public static int worthCount(List<Doc> docs) { int n = 0; for (Doc doc : docs) if (worth(doc)) n++; return n; }
    /** The model most entries were made with (the one a query has to use), or null for an empty index. */
    public static String model(Collection<Entry> entries) {
        Map<String, Integer> counts = new LinkedHashMap<>();
        for (Entry entry : entries) counts.merge(entry.model(), 1, Integer::sum);
        return counts.entrySet().stream().max(Map.Entry.comparingByValue()).map(Map.Entry::getKey).orElse(null);
    }
    /** A batch request for docs (batchEmbedContents), for finding by a query later. */
    public static JSONObject batch(String model, List<Doc> docs) {
        JSONArray requests = new JSONArray();
        for (Doc doc : docs) {
            JSONObject one = obj("model", "models/" + model, "content", obj("parts", arr(obj("text", body(doc)))), "taskType", "RETRIEVAL_DOCUMENT", "outputDimensionality", DIMENSIONS);
            String title = doc.title() == null ? "" : doc.title().strip();
            if (!title.isEmpty()) put(one, "title", title.length() > 200 ? title.substring(0, 200) : title);
            requests.put(one);
        }
        return obj("requests", requests);
    }
    /** A query request (embedContent). */
    public static JSONObject query(String text) {
        return obj("content", obj("parts", arr(obj("text", text.strip()))), "taskType", "RETRIEVAL_QUERY", "outputDimensionality", DIMENSIONS);
    }
    /** The vectors of a batch answer, in order, each made unit length; null when the answer does not hold one per doc. */
    public static List<float[]> vectors(JSONObject answer, int count) {
        JSONArray embeddings = answer == null ? null : answer.optJSONArray("embeddings");
        if (embeddings == null || embeddings.length() != count) return null;
        List<float[]> out = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            float[] vector = unit(embeddings.optJSONObject(i) == null ? null : embeddings.optJSONObject(i).optJSONArray("values"));
            if (vector == null) return null;
            out.add(vector);
        }
        return out;
    }
    /** The vector of a query answer, unit length, or null. */
    public static float[] vector(JSONObject answer) {
        JSONObject embedding = answer == null ? null : answer.optJSONObject("embedding");
        return unit(embedding == null ? null : embedding.optJSONArray("values"));
    }
    static float[] unit(JSONArray values) {
        if (values == null || values.length() == 0) return null;
        float[] out = new float[values.length()]; double sum = 0;
        for (int i = 0; i < out.length; i++) { out[i] = (float) values.optDouble(i, 0); sum += out[i] * (double) out[i]; }
        if (sum <= 0) return null;
        float scale = (float) (1 / Math.sqrt(sum)); for (int i = 0; i < out.length; i++) out[i] *= scale;
        return out;
    }
    /** How close two unit vectors are (1 the same, 0 unrelated); 0 when their sizes differ. */
    public static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) return 0;
        double dot = 0; for (int i = 0; i < a.length; i++) dot += a[i] * (double) b[i];
        return dot;
    }
    /** The entries of model nearest the query, closest first: at least least close, at most limit. */
    public static List<Scored> rank(float[] query, Collection<Entry> entries, String model, int limit, double least) {
        List<Scored> out = new ArrayList<>();
        for (Entry entry : entries) {
            if (!entry.model().equals(model)) continue;
            double score = cosine(query, entry.vector()); if (score >= least) out.add(new Scored(entry, score));
        }
        out.sort(Comparator.comparingDouble(Scored::score).reversed());
        // Far behind the closest: more likely noise than meaning.
        if (!out.isEmpty()) { double floor = out.get(0).score() - SPREAD; out.removeIf(scored -> scored.score() < floor); }
        return out.size() <= limit ? out : new ArrayList<>(out.subList(0, limit));
    }

    /** The index file: a version, the count, then each entry with its vector as floats. */
    public static void write(DataOutput out, Collection<Entry> entries) throws IOException {
        out.writeInt(FILE_VERSION); out.writeInt(entries.size());
        for (Entry entry : entries) {
            out.writeUTF(entry.key()); out.writeInt(entry.hash()); out.writeUTF(entry.model()); out.writeLong(entry.time());
            out.writeUTF(cut(entry.title(), 300)); out.writeUTF(cut(entry.snippet(), 300));
            out.writeInt(entry.vector().length); for (float value : entry.vector()) out.writeFloat(value);
        }
    }
    /** Reads an index file; an older or broken one reads as empty (it is built again). */
    public static Map<String, Entry> read(DataInput in) {
        Map<String, Entry> index = new LinkedHashMap<>();
        try {
            if (in.readInt() != FILE_VERSION) return index;
            int count = in.readInt();
            for (int i = 0; i < count; i++) {
                String key = in.readUTF(); int hash = in.readInt(); String model = in.readUTF(); long time = in.readLong();
                String title = in.readUTF(), snippet = in.readUTF();
                int size = in.readInt(); if (size <= 0 || size > 4096) return new LinkedHashMap<>();
                float[] vector = new float[size]; for (int k = 0; k < size; k++) vector[k] = in.readFloat();
                index.put(key, new Entry(key, hash, model, time, title, snippet, vector));
            }
        } catch (IOException | RuntimeException broken) { return new LinkedHashMap<>(); }
        return index;
    }
    private static String cut(String text, int most) { String value = text == null ? "" : text; return value.length() <= most ? value : value.substring(0, most); }
    private static void put(JSONObject target, String key, Object value) { try { target.put(key, value); } catch (Exception e) { throw new IllegalArgumentException(e); } }
}
