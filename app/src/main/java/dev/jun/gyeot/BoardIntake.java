package dev.jun.gyeot;

import android.os.SystemClock;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the voice assistant hands to 판서 (BoardActivity): screenshots of the phone, top to bottom, and the user's request.
 * Kept in this process under a one-time token that only the intent carries, so nothing outside the app can put pictures
 * or files on a board through it. A hand-off not taken within ten minutes is dropped.
 */
final class BoardIntake {
    private BoardIntake() {}
    record Item(String request, List<byte[]> pictures) {}
    private record Held(Item item, long at) {}
    private static final long KEEP_MS = 10 * 60_000L;
    private static final Map<String, Held> held = new LinkedHashMap<>();

    static synchronized String put(String request, List<byte[]> pictures) {
        drop();
        String token = UUID.randomUUID().toString();
        held.put(token, new Held(new Item(request == null ? "" : request, new ArrayList<>(pictures)), SystemClock.elapsedRealtime()));
        return token;
    }
    /** The hand-off under this token, once; null when there is none (taken, dropped or never made here). */
    static synchronized Item take(String token) {
        drop();
        Held found = token == null ? null : held.remove(token);
        return found == null ? null : found.item();
    }
    private static void drop() {
        long now = SystemClock.elapsedRealtime();
        for (Iterator<Held> it = held.values().iterator(); it.hasNext();) if (now - it.next().at() > KEEP_MS) it.remove();
    }
}
