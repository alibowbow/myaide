package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import org.json.JSONObject;

/**
 * Setup settings a Live model refused, remembered so later calls connect once instead of being refused first
 *. A refused setting cost every later call a refused connection, a second connection and the
 * words said in between: the chosen voice on every call, the thinking switch and background function calls on the first
 * call after each app start. One is kept only after a connection on the same model completed its setup without it (Pending),
 * and for a week; then the next call asks with it once more (a model update may take it), and so does choosing the voice again.
 *
 * Saved as one small JSON object (key → when it was refused, epoch milliseconds) in the app's settings. Nothing about the
 * conversation: a kind, a model id and, for a voice, the voice's name.
 */
public final class LiveRefusals {
    public static final long KEEP_MS = 7L * 24 * 60 * 60 * 1000;
    public static final int MAX = 16;
    /** The chosen voice (per model and voice), background function calls (NON_BLOCKING) and 2.5 Flash's thinking switched off. */
    public static final String VOICE = "voice", ASYNC = "async", QUICK = "quick";
    private LiveRefusals() {}

    /** The key of one refusal; a voice refusal names the voice too. */
    public static String key(String kind, String model, String voice) {
        String id = GeminiProtocol.liveModel(model);
        return VOICE.equals(kind) ? kind + ":" + id + ":" + (voice == null ? "" : voice.strip()) : kind + ":" + id;
    }

    /** Whether saved holds this refusal from the last week. A time after now (the clock was set back) does not count. */
    public static boolean refused(String saved, String key, long now) {
        long at = read(saved).optLong(key, 0);
        return at > 0 && at <= now && now - at < KEEP_MS;
    }

    /** saved with this refusal added at now; expired and damaged entries go, and at most MAX are kept (the newest). */
    public static String remember(String saved, String key, long now) {
        JSONObject current = read(saved), next = new JSONObject();
        List<String> keys = new ArrayList<>();
        for (java.util.Iterator<String> names = current.keys(); names.hasNext(); ) {
            String name = names.next();
            if (!name.equals(key) && refused(saved, name, now)) keys.add(name);
        }
        keys.sort((a, b) -> Long.compare(current.optLong(b), current.optLong(a)));
        try {
            next.put(key, now);
            for (int i = 0; i < keys.size() && next.length() < MAX; i++) next.put(keys.get(i), current.optLong(keys.get(i)));
        } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        return next.toString();
    }

    /** Whether a refused thinking setting was named as such, not only a bare 1007 or invalid argument (those stay per run). */
    public static boolean namesThinking(String reason) {
        return reason != null && reason.toLowerCase(java.util.Locale.ROOT).contains("thinking");
    }

    /** saved without this voice's refusals on any model: the user chose the voice again, so each model is asked once more. */
    public static String forgetVoice(String saved, String voice) {
        String name = voice == null ? "" : voice.strip();
        if (name.isEmpty()) return saved == null ? "" : saved;
        JSONObject current = read(saved), next = new JSONObject();
        for (java.util.Iterator<String> names = current.keys(); names.hasNext(); ) {
            String key = names.next();
            if (key.startsWith(VOICE + ":") && key.endsWith(":" + name)) continue;
            try { next.put(key, current.optLong(key)); } catch (Exception impossible) { throw new IllegalStateException(impossible); }
        }
        return next.toString();
    }

    /**
     * The refusals one call met before its first setup completed, each with its model, its voice (for a voice refusal) and the
     * reason that showed it. One is kept only when a connection on the same model then completed its setup without that
     * setting (confirmed). A retry refused for the very same reason shows that the setting it went without was not what the
     * model refused (failed), and a connection on another model, or one that asked for the setting again, proves nothing.
     */
    public static final class Pending {
        private static final class Entry {
            final String kind, model, voice, reason;
            Entry(String kind, String model, String voice, String reason) { this.kind = kind; this.model = model; this.voice = voice; this.reason = reason; }
        }
        private final List<Entry> entries = new ArrayList<>();

        public synchronized void add(String kind, String model, String voice, String reason) {
            entries.add(new Entry(kind, GeminiProtocol.liveModel(model), voice == null ? "" : voice.strip(), reason == null ? "" : reason));
        }
        /** A connection made before the call's first setup completed was refused or dropped for this reason. */
        public synchronized void failed(String reason) {
            String why = reason == null ? "" : reason;
            entries.removeIf(entry -> entry.reason.equals(why));
        }
        /**
         * The keys a connection with these settings confirms by completing its setup: the refusals met on its model that it
         * went without. Every entry goes, kept or not: what the call's later connections do proves nothing about its start.
         */
        public synchronized List<String> confirmed(String model, String voice, boolean async, boolean quick) {
            String id = GeminiProtocol.liveModel(model), spoken = voice == null ? "" : voice.strip();
            List<String> keys = new ArrayList<>();
            for (Entry entry : entries) {
                boolean without = VOICE.equals(entry.kind) ? !spoken.equals(entry.voice) : ASYNC.equals(entry.kind) ? !async : QUICK.equals(entry.kind) && !quick;
                String key = key(entry.kind, entry.model, entry.voice);
                if (without && entry.model.equals(id) && !keys.contains(key)) keys.add(key);
            }
            entries.clear();
            return keys;
        }
        public synchronized void clear() { entries.clear(); }
        public synchronized boolean isEmpty() { return entries.isEmpty(); }
    }

    private static JSONObject read(String saved) {
        if (saved == null || saved.isBlank()) return new JSONObject();
        try { return new JSONObject(saved); } catch (Exception damaged) { return new JSONObject(); }
    }
}
