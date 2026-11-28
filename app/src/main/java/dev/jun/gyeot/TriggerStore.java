package dev.jun.gyeot;

import android.content.Context;
import android.content.SharedPreferences;
import dev.jun.gyeot.core.Triggers;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.UnaryOperator;
import org.json.JSONObject;

/**
 * 조건 알림 (사건 트리거) as kept: the list in this app's own preferences (core/Triggers' json), read once and held in memory
 * (TriggerWatch reads it at every battery change and notice), written only when something changed; and the acts waiting
 * for a tap (a draft, an app Android would not let open from the background), kept so a tap after the app was gone works.
 */
final class TriggerStore {
    private TriggerStore() {}
    static final String PREFS = "gyeot_triggers";
    private static final String LIST = "list", PENDING = "pending";
    /** Held while the list is read, matched and written, so two events never lose each other's change. */
    static final Object LOCK = new Object();
    private static volatile List<Triggers.Trigger> cache;
    private static final List<Runnable> LISTENERS = new CopyOnWriteArrayList<>();

    static SharedPreferences prefs(Context c) { return c.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE); }
    /** The triggers, oldest first (read once; call off the main thread the first time). */
    static List<Triggers.Trigger> list(Context c) {
        List<Triggers.Trigger> now = cache;
        if (now == null) synchronized (LOCK) {
            if (cache == null) cache = Triggers.load(prefs(c).getString(LIST, ""), System.currentTimeMillis());
            now = cache;
        }
        return new ArrayList<>(now);
    }
    /** The list changed as change says, kept, and whoever shows it told. */
    static List<Triggers.Trigger> change(Context c, UnaryOperator<List<Triggers.Trigger>> change) {
        List<Triggers.Trigger> after;
        synchronized (LOCK) { after = change.apply(list(c)); put(c, after); }
        return after;
    }
    /** Keeps the list as given (TriggerWatch, under LOCK). */
    static void put(Context c, List<Triggers.Trigger> all) {
        synchronized (LOCK) { cache = List.copyOf(all); prefs(c).edit().putString(LIST, Triggers.save(all)).apply(); }
        for (Runnable r : LISTENERS) try { r.run(); } catch (RuntimeException ignored) { /* one listener never stops another */ }
    }
    static void listen(Runnable r) { if (r != null) LISTENERS.add(r); }
    static void unlisten(Runnable r) { LISTENERS.remove(r); }
    /** For tests: nothing held in memory (the preferences are read again). */
    static void forget() { synchronized (LOCK) { cache = null; } }

    // ── an act waiting for the user's tap ──
    /** Keeps an act ({"tool": …}) and the words it was asked with until a tap does it; returns its id. At most 20 wait. */
    static String pend(Context c, JSONObject act, String said) {
        String id = "p" + Long.toString(System.nanoTime(), 36);
        synchronized (LOCK) {
            JSONObject all = pending(c);
            try {
                all.put(id, new JSONObject().put("act", act).put("said", said == null ? "" : said).put("at", System.currentTimeMillis()));
                while (all.length() > 20) { String oldest = null; long at = Long.MAX_VALUE; for (java.util.Iterator<String> k = all.keys(); k.hasNext(); ) { String key = k.next(); long a = all.optJSONObject(key).optLong("at"); if (a < at) { at = a; oldest = key; } } all.remove(oldest); }
            } catch (Exception impossible) { /* not kept */ }
            prefs(c).edit().putString(PENDING, all.toString()).apply();
        }
        return id;
    }
    /** The act waiting under id ({"act", "said"}), taken (a second tap does nothing), or null. */
    static JSONObject take(Context c, String id) {
        synchronized (LOCK) {
            JSONObject all = pending(c), one = id == null ? null : all.optJSONObject(id);
            if (one == null) return null;
            all.remove(id); prefs(c).edit().putString(PENDING, all.toString()).apply();
            return one;
        }
    }
    private static JSONObject pending(Context c) { try { return new JSONObject(prefs(c).getString(PENDING, "{}")); } catch (Exception broken) { return new JSONObject(); } }
}
