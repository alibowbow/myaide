package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Small, in-memory progress records, independent of Android, JSON, persistence, and network code.
 * All times are caller-supplied monotonic milliseconds, not wall-clock timestamps. All public
 * operations are synchronized, and returned snapshots do not change with subsequent callbacks.
 *
 * Labels must be short, app-authored status descriptions. Never pass prompts, model thinking,
 * streamed answer content, tool arguments, credentials, or request/response bodies to this class.
 * There is deliberately no content, logging, serialization, or message-queue API here.
 * Use the token overloads when reusing a room ID; stale run callbacks are rejected atomically.
 */
public final class WorkRunState {
    public static final int DEFAULT_MAX_RUNS = 60, DEFAULT_MAX_EVENTS = 12, LABEL_LIMIT = 160;
    public static final long DEFAULT_STALL_AFTER_MS = 120_000L;
    public static final String STARTED = "작업 시작", FINISHED = "작업 완료";

    private final int maxRuns, maxEvents;
    private final long stallAfterMs;
    private final LinkedHashMap<String, Run> runs = new LinkedHashMap<>();

    public WorkRunState() {
        this(DEFAULT_MAX_RUNS, DEFAULT_MAX_EVENTS, DEFAULT_STALL_AFTER_MS);
    }

    /** A zero stall threshold is allowed; negative thresholds and nonpositive bounds are not. */
    public WorkRunState(int maxRuns, int maxEventsPerRun, long stallAfterMs) {
        if (maxRuns < 1 || maxEventsPerRun < 1 || stallAfterMs < 0) {
            throw new IllegalArgumentException("Progress bounds must be positive and the stall threshold nonnegative.");
        }
        this.maxRuns = maxRuns;
        this.maxEvents = maxEventsPerRun;
        this.stallAfterMs = stallAfterMs;
    }

    /** Starts a fresh run, replacing any record with this ID. No old progress carries over. */
    public synchronized void begin(String id, long nowMs) { begin(id, null, nowMs); }

    public synchronized void begin(String id, Object token, long nowMs) {
        if (id == null || id.trim().isEmpty()) throw new IllegalArgumentException("A run ID is required.");
        long at = Math.max(0L, nowMs);
        runs.remove(id);
        runs.put(id, new Run(id, token, at));
        trimRuns();
    }

    /**
     * Records a stage transition and activity. Repeating the current label refreshes activity
     * without adding a duplicate event; returning to an earlier stage remains a real transition.
     * A blank label is activity only. Unknown and finished runs ignore late callbacks.
     */
    public synchronized void stage(String id, String label, long nowMs) { stage(id, null, label, nowMs); }

    public synchronized void stage(String id, Object token, String label, long nowMs) {
        Run run = active(id, token);
        if (run == null) return;
        touch(run, nowMs);
        append(run, cleanLabel(label), run.lastActivityAtMs);
    }

    /** Records transport/tool activity without storing its content or adding a timeline event. */
    public synchronized void activity(String id, long nowMs) { activity(id, null, nowMs); }

    public synchronized void activity(String id, Object token, long nowMs) {
        Run run = active(id, token);
        if (run != null) touch(run, nowMs);
    }

    /** Completes a run, freezing its elapsed duration. Duplicate finish callbacks are ignored. */
    public synchronized void finish(String id, String label, long nowMs) { finish(id, null, label, nowMs); }

    public synchronized void finish(String id, Object token, String label, long nowMs) {
        Run run = active(id, token);
        if (run == null) return;
        touch(run, nowMs);
        String clean = cleanLabel(label);
        append(run, clean.isEmpty() ? FINISHED : clean, run.lastActivityAtMs);
        run.finishedAtMs = run.lastActivityAtMs;
        run.running = false;
    }

    /** Returns null for an unknown/evicted ID. Reading progress never counts as run activity. */
    public synchronized Snapshot snapshot(String id, long nowMs) {
        Run run = runs.get(id);
        if (run == null) return null;
        long end = run.running ? Math.max(nowMs, run.lastActivityAtMs) : run.finishedAtMs;
        long elapsed = end - run.startedAtMs;
        long inactive = end - run.lastActivityAtMs;
        boolean stalled = run.running && inactive > stallAfterMs;
        String summary = run.label + " · 경과 " + duration(elapsed);
        if (run.running) {
            summary += " · 마지막 활동 " + (inactive < 1_000L ? "방금" : duration(inactive) + " 전");
            if (stalled) summary += " · 응답 지연";
        }
        return new Snapshot(run, elapsed, inactive, stalled, summary);
    }

    private Run active(String id, Object token) {
        Run run = runs.get(id);
        return run != null && run.running && run.token == token ? run : null;
    }

    private void touch(Run run, long nowMs) {
        // Callbacks may arrive out of order. Activity timestamps must never move backwards.
        run.lastActivityAtMs = Math.max(run.lastActivityAtMs, nowMs);
    }

    private void append(Run run, String label, long at) {
        if (label.isEmpty() || label.equals(run.label)) return;
        run.label = label;
        run.events.add(new Event(label, at));
        while (run.events.size() > maxEvents) run.events.remove(0);
    }

    private void trimRuns() {
        while (runs.size() > maxRuns) {
            // Prefer completed history over active work, but keep the configured hard bound.
            String drop = null;
            for (Map.Entry<String, Run> entry : runs.entrySet()) {
                if (!entry.getValue().running) { drop = entry.getKey(); break; }
            }
            if (drop != null) runs.remove(drop);
            else {
                Iterator<Map.Entry<String, Run>> oldest = runs.entrySet().iterator();
                oldest.next();
                oldest.remove();
            }
        }
    }

    private static String cleanLabel(String label) {
        if (label == null) return "";
        StringBuilder clean = new StringBuilder(Math.min(label.length(), LABEL_LIMIT));
        boolean space = false;
        for (int i = 0; i < label.length() && clean.length() < LABEL_LIMIT; i++) {
            char c = label.charAt(i);
            if (Character.isWhitespace(c) || Character.isISOControl(c)) {
                space = clean.length() > 0;
            } else {
                if (space) clean.append(' ');
                if (clean.length() < LABEL_LIMIT) clean.append(c);
                space = false;
            }
        }
        // Do not leave a truncated UTF-16 surrogate pair or trailing whitespace in the UI.
        int end = clean.length();
        while (end > 0 && (clean.charAt(end - 1) == ' ' || Character.isHighSurrogate(clean.charAt(end - 1)))) end--;
        return clean.substring(0, end);
    }

    private static String duration(long ms) {
        long seconds = ms / 1_000L, minutes = seconds / 60L, hours = minutes / 60L;
        if (hours > 0) return hours + "시간 " + (minutes % 60L) + "분";
        if (minutes > 0) return minutes + "분 " + (seconds % 60L) + "초";
        return seconds + "초";
    }

    private static final class Run {
        final String id;
        final Object token;
        final long startedAtMs;
        long lastActivityAtMs, finishedAtMs = -1L;
        boolean running = true;
        String label = STARTED;
        final ArrayList<Event> events = new ArrayList<>();

        Run(String id, Object token, long nowMs) {
            this.id = id; this.token = token;
            startedAtMs = lastActivityAtMs = nowMs;
            events.add(new Event(STARTED, nowMs));
        }
    }

    /** One immutable, content-free status transition, at its first occurrence in this stage. */
    public static final class Event {
        public final String label;
        public final long timestampMs;

        private Event(String label, long timestampMs) {
            this.label = label;
            this.timestampMs = timestampMs;
        }
    }

    /** Immutable copy suitable for reading from a UI thread after the callback lock is released. */
    public static final class Snapshot {
        public final String id, label, summary;
        public final boolean running, stalled;
        /** finishedAtMs is -1 while running; all other times are monotonic milliseconds. */
        public final long startedAtMs, lastActivityAtMs, finishedAtMs, elapsedMs, inactiveMs;
        public final List<Event> events;

        private Snapshot(Run run, long elapsedMs, long inactiveMs, boolean stalled, String summary) {
            id = run.id;
            label = run.label;
            this.summary = summary;
            running = run.running;
            this.stalled = stalled;
            startedAtMs = run.startedAtMs;
            lastActivityAtMs = run.lastActivityAtMs;
            finishedAtMs = run.finishedAtMs;
            this.elapsedMs = elapsedMs;
            this.inactiveMs = inactiveMs;
            events = Collections.unmodifiableList(new ArrayList<>(run.events));
        }
    }
}
