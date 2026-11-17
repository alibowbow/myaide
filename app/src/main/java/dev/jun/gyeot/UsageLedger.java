package dev.jun.gyeot;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import dev.jun.gyeot.core.Limits;
import dev.jun.gyeot.core.MainChatContext;
import dev.jun.gyeot.core.Usage;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/**
 * The usage ledger: a row for every text request that answered, and what each account's provider last
 * said is left of its limits. Metadata only, never a question, an answer, a key or a token: the time, the surface (메인 대화,
 * 작업창, 예약, 브라우저, 음성), the company, the model, the route (API key or subscription), a short one-way hash that tells
 * connections apart (AppStore.usageIdentity), and the token counts, an unknown one left empty. Its own database (usage.db),
 * kept to 90 days and 20,000 rows. Every write runs on the ledger's own thread, never the caller's; a write that fails
 * only leaves a request uncounted.
 */
final class UsageLedger implements AutoCloseable {
    static final String MAIN = "main", WORK = "work", SCHEDULE = "schedule", BROWSER = "browser", VOICE = "voice";
    static final String KEY = "key", PLAN = "plan";
    static final long KEEP_MS = 90L * 24 * 60 * 60 * 1000;
    static final int KEEP_ROWS = 20_000;
    /** The schema: 1 is the usage and limits tables. */
    static final int VERSION = 1;
    /** One request that answered. There is no field for what was asked or answered. */
    record Row(long at, String surface, String company, String model, String route, String connection, Usage usage) {}
    /** One writer, in order, whose thread ends after half a minute without work. */
    private final ExecutorService writes = idleWriter();
    /** Goes up with every write that landed, so a screen can tell when to read again without reading. */
    private final java.util.concurrent.atomic.AtomicLong version = new java.util.concurrent.atomic.AtomicLong();
    private SQLiteDatabase db;

    private static ExecutorService idleWriter() {
        ThreadPoolExecutor writer = new ThreadPoolExecutor(1, 1, 30, TimeUnit.SECONDS, new LinkedBlockingQueue<>(), r -> { Thread t = new Thread(r, "gyeot-usage"); t.setDaemon(true); return t; });
        writer.allowCoreThreadTimeOut(true);
        return writer;
    }
    UsageLedger(Context context) {
        try {
            java.io.File path = context.getDatabasePath("usage.db"); path.getParentFile().mkdirs();
            db = SQLiteDatabase.openOrCreateDatabase(path, null);
            db.enableWriteAheadLogging();
            migrate();
        } catch (Exception unavailable) { if (db != null) try { db.close(); } catch (Exception ignored) {} db = null; }
    }
    private void migrate() {
        if (db.getVersion() >= VERSION) return;
        db.beginTransaction();
        try {
            db.execSQL("CREATE TABLE IF NOT EXISTS usage (id INTEGER PRIMARY KEY AUTOINCREMENT, at INTEGER NOT NULL, surface TEXT NOT NULL, company TEXT NOT NULL, model TEXT NOT NULL,"
                + " route TEXT NOT NULL, connection TEXT NOT NULL, input INTEGER, cache_read INTEGER, cache_write INTEGER, output INTEGER, reasoning INTEGER, total INTEGER)");
            db.execSQL("CREATE INDEX IF NOT EXISTS usage_at ON usage(at)");
            db.execSQL("CREATE TABLE IF NOT EXISTS limits (company TEXT NOT NULL, route TEXT NOT NULL, connection TEXT NOT NULL, at INTEGER NOT NULL, windows TEXT NOT NULL, PRIMARY KEY (company, route))");
            db.setVersion(VERSION);
            db.setTransactionSuccessful();
        } finally { db.endTransaction(); }
    }
    synchronized boolean available() { return db != null && db.isOpen(); }

    /** Counts one request, later, on the ledger's thread. */
    void record(Row row) {
        if (row == null || row.usage() == null) return;
        try { writes.execute(() -> insert(row)); } catch (RejectedExecutionException closed) { /* Closing: nothing more is counted. */ }
    }
    private synchronized void insert(Row row) {
        if (!available()) return;
        try {
            ContentValues v = new ContentValues();
            v.put("at", row.at()); v.put("surface", name(row.surface())); v.put("company", name(row.company())); v.put("model", MainChatContext.clip(row.model() == null ? "" : row.model(), 120));
            v.put("route", name(row.route())); v.put("connection", name(row.connection()));
            Usage u = row.usage();
            count(v, "input", u.input()); count(v, "cache_read", u.cacheRead()); count(v, "cache_write", u.cacheWrite());
            count(v, "output", u.output()); count(v, "reasoning", u.reasoning()); count(v, "total", u.total());
            db.insertOrThrow("usage", null, v);
            // 90 days, and at most the newest 20,000 rows.
            db.delete("usage", "at < ?", new String[]{"" + (System.currentTimeMillis() - KEEP_MS)});
            db.execSQL("DELETE FROM usage WHERE id <= (SELECT id FROM usage ORDER BY id DESC LIMIT 1 OFFSET " + KEEP_ROWS + ")");
            version.incrementAndGet();
        } catch (Exception lost) { /* A row that could not be written only undercounts; it never stops an answer. */ }
    }
    private static void count(ContentValues values, String column, Long count) { if (count == null) values.putNull(column); else values.put(column, count); }
    /** The ledger's own short names (surfaces, companies, routes, hashes); anything else is cut down to them. */
    private static String name(String value) { return value == null ? "" : MainChatContext.clip(value.replaceAll("[^A-Za-z0-9_.-]", ""), 40); }

    /** What a provider reported for an account's limits, kept (later, on the ledger's thread) as the latest for its company and route. */
    void limits(String company, String route, String connection, Limits limits) { limits(company, route, connection, limits, null); }
    /** As above; after (when not null) runs on the ledger's thread once the write has landed, or failed. */
    void limits(String company, String route, String connection, Limits limits, Runnable after) {
        if (limits == null || limits.empty()) return;
        String windows = limits.json().toString();
        try {
            writes.execute(() -> {
                try {
                    synchronized (this) {
                        if (!available()) return;
                        try {
                            ContentValues v = new ContentValues();
                            v.put("company", name(company)); v.put("route", name(route)); v.put("connection", name(connection)); v.put("at", limits.at()); v.put("windows", windows);
                            db.insertWithOnConflict("limits", null, v, SQLiteDatabase.CONFLICT_REPLACE);
                            version.incrementAndGet();
                        } catch (Exception lost) { /* The page shows 모름 until the next response. */ }
                    }
                } finally { if (after != null) after.run(); }
            });
        } catch (RejectedExecutionException closed) { if (after != null) after.run(); }
    }
    /** How many writes have landed so far (UsageUi.fingerprint). */
    long version() { return version.get(); }
    /** The latest limits for an account, when they were reported for this same connection; Limits.NONE otherwise. */
    synchronized Limits limits(String company, String route, String connection) {
        if (!available() || connection == null || connection.isBlank()) return Limits.NONE;
        try (Cursor c = db.rawQuery("SELECT connection, windows FROM limits WHERE company=? AND route=?", new String[]{name(company), name(route)})) {
            if (!c.moveToFirst() || !name(connection).equals(c.getString(0))) return Limits.NONE;
            return Limits.of(new JSONObject(c.getString(1)));
        } catch (Exception unreadable) { return Limits.NONE; }
    }

    /** The newest rows first, at most limit, as {at, surface, company, model, route, connection, input?, …}: absent counts are unknown. */
    synchronized JSONArray rows(int limit) {
        JSONArray rows = arr();
        if (!available()) return rows;
        try (Cursor c = db.rawQuery("SELECT * FROM usage ORDER BY id DESC LIMIT ?", new String[]{"" + Math.max(1, limit)})) {
            while (c.moveToNext()) {
                JSONObject row = obj();
                for (int i = 0; i < c.getColumnCount(); i++) if (!c.isNull(i)) put(row, c.getColumnName(i), c.getType(i) == Cursor.FIELD_TYPE_INTEGER ? c.getLong(i) : c.getString(i));
                rows.put(row);
            }
        } catch (Exception unreadable) { return arr(); }
        return rows;
    }
    /**
     * Per company since a time, most requests first: {company, requests, tokens (the known totals summed), unknown (requests
     * whose total was not told), read and prompt (cache read and whole prompt, from requests that told both)}.
     */
    synchronized JSONArray totals(long since) {
        JSONArray totals = arr();
        if (!available()) return totals;
        String split = "input IS NOT NULL AND cache_read IS NOT NULL";
        try (Cursor c = db.rawQuery("SELECT company, COUNT(*), COALESCE(SUM(total), 0), SUM(CASE WHEN total IS NULL THEN 1 ELSE 0 END), COALESCE(SUM(CASE WHEN " + split + " THEN cache_read END), 0),"
                + " COALESCE(SUM(CASE WHEN " + split + " THEN input + cache_read + COALESCE(cache_write, 0) END), 0) FROM usage WHERE at >= ? GROUP BY company ORDER BY COUNT(*) DESC", new String[]{"" + since})) {
            while (c.moveToNext()) totals.put(obj("company", c.getString(0), "requests", c.getLong(1), "tokens", c.getLong(2), "unknown", c.getLong(3), "read", c.getLong(4), "prompt", c.getLong(5)));
        } catch (Exception unreadable) { return arr(); }
        return totals;
    }
    /** Waits for the writes asked for so far; never from the main thread (tests). */
    void flush() { try { writes.submit(() -> {}).get(5, TimeUnit.SECONDS); } catch (Exception ignored) {} }
    @Override public void close() {
        writes.shutdown();
        try { writes.awaitTermination(2, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (this) { if (db != null) db.close(); db = null; }
    }
}
