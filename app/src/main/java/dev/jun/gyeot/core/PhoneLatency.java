package dev.jun.gyeot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.function.LongSupplier;
import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Per-task monotonic timings only: no text, screen, account, app or credential data. */
public final class PhoneLatency {
    public enum Stage { ROUTER, MODEL, SCREEN_READ, ACTION, OBSERVATION, EXPLICIT_WAIT, OTHER_TOOL }
    public enum Outcome { SUCCESS, FAILED, CANCELLED }
    /** These boundaries must not be pooled: a delegated phone task excludes upstream voice/model time. */
    public enum StartBoundary { COMMAND_COMMITTED, PHONE_TASK_RECEIVED }
    public enum SampleKind { RUNTIME, SYNTHETIC }
    private final LongSupplier clock;
    private final long queued, received;
    private final StartBoundary boundary;
    private final SampleKind kind;
    private long execution, firstDispatch, firstAccepted, ended;
    private boolean started, dispatched, accepted, recorded;
    private Outcome outcome;
    private final EnumMap<Stage, Long> nanos = new EnumMap<>(Stage.class);
    private final EnumMap<Stage, Integer> counts = new EnumMap<>(Stage.class);

    public PhoneLatency() { this(System::nanoTime, StartBoundary.PHONE_TASK_RECEIVED, SampleKind.RUNTIME); }
    /** A supplied test clock is synthetic unless the full constructor explicitly says otherwise. */
    public PhoneLatency(LongSupplier clock) { this(clock, StartBoundary.PHONE_TASK_RECEIVED, SampleKind.SYNTHETIC); }
    private PhoneLatency(LongSupplier clock, StartBoundary boundary, SampleKind kind) {
        this(clock, clock.getAsLong(), boundary, kind);
    }
    public PhoneLatency(long acceptedNanos, StartBoundary boundary) {
        this(System::nanoTime, acceptedNanos, boundary, SampleKind.RUNTIME);
    }
    public PhoneLatency(long acceptedNanos) { this(acceptedNanos, StartBoundary.PHONE_TASK_RECEIVED); }
    public PhoneLatency(LongSupplier clock, long acceptedNanos) {
        this(clock, acceptedNanos, StartBoundary.PHONE_TASK_RECEIVED, SampleKind.SYNTHETIC);
    }
    public PhoneLatency(LongSupplier clock, long acceptedNanos, StartBoundary boundary, SampleKind kind) {
        this.clock = Objects.requireNonNull(clock); queued = acceptedNanos; received = clock.getAsLong();
        this.boundary = Objects.requireNonNull(boundary); this.kind = Objects.requireNonNull(kind);
    }
    public synchronized void startExecution() {
        if (!started && outcome == null) { execution = clock.getAsLong(); started = true; }
    }
    public long start() { return clock.getAsLong(); }
    /** Called in finally, so failures and cancellations retain elapsed wait; finished tasks are immutable. */
    public synchronized void finish(Stage stage, long start) {
        if (outcome != null) return;
        nanos.merge(stage, Math.max(0, clock.getAsLong() - start), Long::sum);
        counts.merge(stage, 1, Integer::sum);
    }
    /** Immediately before invoking the existing guarded executor. This is an attempt, not an OS effect. */
    public synchronized void markFirstActionDispatch() {
        if (!dispatched && outcome == null) { firstDispatch = clock.getAsLong(); dispatched = true; }
    }
    /** Only after a non-wait phone_action returns ok. Does not assert a visible screen change. */
    public synchronized void markFirstActionAccepted() {
        if (dispatched && !accepted && outcome == null) { firstAccepted = clock.getAsLong(); accepted = true; }
    }
    /** First terminal outcome wins, including cancellation after an action was already sent. */
    public synchronized void finishTask(Outcome value) {
        Objects.requireNonNull(value);
        if (outcome == null) { ended = clock.getAsLong(); outcome = value; }
    }
    public static boolean isAction(String name, JSONObject args) {
        return "phone_action".equals(name) && args != null
            && PhoneTools.ACTIONS.contains(args.optString("action")) && !"wait".equals(args.optString("action"));
    }
    public static Stage tool(String name, JSONObject args) {
        if ("phone_screen".equals(name)) return args != null && args.optBoolean("after_action") ? Stage.OBSERVATION : Stage.SCREEN_READ;
        if ("phone_action".equals(name)) return args != null && "wait".equals(args.optString("action")) ? Stage.EXPLICIT_WAIT : Stage.ACTION;
        return Stage.OTHER_TOOL;
    }
    private long ms(Stage stage) { return nanos.getOrDefault(stage, 0L) / 1_000_000; }
    private static long elapsedMs(long start, long end) { return Math.max(0, end - start) / 1_000_000; }
    public synchronized JSONObject snapshot() {
        long now = outcome == null ? clock.getAsLong() : ended;
        return obj("sample_kind", kind.name().toLowerCase(java.util.Locale.ROOT),
            "start_boundary", boundary.name().toLowerCase(java.util.Locale.ROOT),
            "outcome", outcome == null ? "running" : outcome.name().toLowerCase(java.util.Locale.ROOT),
            "transcription_ms", JSONObject.NULL, "endpointer_ms", JSONObject.NULL,
            "voice_handoff_ms", JSONObject.NULL, "first_visible_effect_ms", JSONObject.NULL,
            "first_action_dispatch_ms", dispatched ? elapsedMs(queued, firstDispatch) : JSONObject.NULL,
            "first_action_accepted_ms", accepted ? elapsedMs(queued, firstAccepted) : JSONObject.NULL,
            "queue_ms", elapsedMs(received, started ? execution : now),
            "command_to_execution_ms", started && boundary == StartBoundary.COMMAND_COMMITTED ? elapsedMs(queued, execution) : JSONObject.NULL,
            "router_ms", ms(Stage.ROUTER), "llm_ms", ms(Stage.MODEL), "screen_read_ms", ms(Stage.SCREEN_READ),
            "action_ms", ms(Stage.ACTION), "observation_ms", ms(Stage.OBSERVATION),
            "explicit_wait_ms", ms(Stage.EXPLICIT_WAIT), "other_tool_ms", ms(Stage.OTHER_TOOL),
            "model_turns", counts.getOrDefault(Stage.MODEL, 0),
            "executor_tool_calls", counts.getOrDefault(Stage.SCREEN_READ, 0) + counts.getOrDefault(Stage.ACTION, 0)
                + counts.getOrDefault(Stage.OBSERVATION, 0) + counts.getOrDefault(Stage.EXPLICIT_WAIT, 0) + counts.getOrDefault(Stage.OTHER_TOOL, 0),
            "task_elapsed_ms", elapsedMs(queued, now));
    }

    /** Bounded in-memory nearest-rank percentiles. Never mix synthetic/runtime or different start boundaries. */
    public static final class Summary {
        private final int capacity;
        private final SampleKind kind;
        private final StartBoundary boundary;
        private final ArrayDeque<JSONObject> samples = new ArrayDeque<>();
        private long total;
        public Summary(int capacity, SampleKind kind, StartBoundary boundary) {
            if (capacity < 1 || capacity > 10_000) throw new IllegalArgumentException("capacity must be 1..10000");
            this.capacity = capacity; this.kind = Objects.requireNonNull(kind); this.boundary = Objects.requireNonNull(boundary);
        }
        /** Record every terminal task once, including no-action, failure and cancellation outcomes. */
        public synchronized boolean add(PhoneLatency latency) {
            JSONObject sample;
            synchronized (latency) {
                if (latency.kind != kind || latency.boundary != boundary) throw new IllegalArgumentException("latency cohorts differ");
                if (latency.outcome == null || latency.recorded) return false;
                sample = latency.snapshot(); latency.recorded = true;
            }
            total++; samples.addLast(sample);
            if (samples.size() > capacity) samples.removeFirst();
            return true;
        }
        public synchronized JSONObject snapshot() {
            List<Long> dispatch = new ArrayList<>(), accepted = new ArrayList<>();
            int successes = 0, failures = 0, cancelled = 0;
            for (JSONObject value : samples) {
                if (!value.isNull("first_action_dispatch_ms")) dispatch.add(value.optLong("first_action_dispatch_ms"));
                if (!value.isNull("first_action_accepted_ms")) accepted.add(value.optLong("first_action_accepted_ms"));
                if ("success".equals(value.optString("outcome"))) successes++;
                else if ("failed".equals(value.optString("outcome"))) failures++;
                else if ("cancelled".equals(value.optString("outcome"))) cancelled++;
            }
            Collections.sort(dispatch); Collections.sort(accepted);
            return obj("sample_kind", kind.name().toLowerCase(java.util.Locale.ROOT),
                "start_boundary", boundary.name().toLowerCase(java.util.Locale.ROOT), "percentile_method", "nearest_rank",
                "tasks_recorded_total", total, "window_capacity", capacity, "tasks_in_window", samples.size(),
                "window_success", successes, "window_failed", failures, "window_cancelled", cancelled,
                "window_no_dispatch", samples.size() - dispatch.size(), "window_no_accepted_action", samples.size() - accepted.size(),
                "dispatch_samples", dispatch.size(), "accepted_samples", accepted.size(),
                "first_action_dispatch_p50_ms", percentile(dispatch, 50), "first_action_dispatch_p95_ms", percentile(dispatch, 95),
                "first_action_accepted_p50_ms", percentile(accepted, 50), "first_action_accepted_p95_ms", percentile(accepted, 95));
        }
        private static Object percentile(List<Long> sorted, int percent) {
            return sorted.isEmpty() ? JSONObject.NULL : sorted.get((percent * sorted.size() + 99) / 100 - 1);
        }
    }
}
