package dev.jun.gyeot.core;

import java.util.Objects;
import java.util.List;
import java.util.LinkedHashSet;
import java.util.function.LongSupplier;

/**
 * Per-lane liveness evidence. Main thread only; callers still enforce lane/lease identity.
 * Elapsed time never proves that a website or model has failed. This policy only asks
 * for a fresh, read-only observation when evidence is missing or repeatedly idle.
 */
public final class BrowserLongTaskPolicy {
    public enum Signal { IDLE, PAGE_LOADING, PAGE_GENERATING, UNKNOWN }
    public enum Phase {
        RUNNING, WAITING_MODEL, WAITING_ACTION, PAGE_LOADING, PAGE_GENERATING,
        OBSERVATION_LOST, STALLED_CANDIDATE, TERMINAL_ERROR, USER_STOPPED
    }
    public enum Disposition { CONTINUE, RECHECK, PAUSE }
    /** Only executor evidence, never model speculation or a generic successful read. */
    public enum Resolution { EFFECT_VERIFIED, ACKNOWLEDGED_ONCE, DEFINITELY_NOT_DISPATCHED }
    public record Assessment(Phase phase, Disposition disposition, String reasonCode,
                             boolean mutationUnresolved, long observationAgeMs) {}
    /** No page text, credentials, approval tokens or process-relative timestamps. */
    public record Checkpoint(String targetId, Phase phase, String reasonCode, String unresolvedActionId,
                             long lastOperationId, long unresolvedOperationId, List<String> verifiedActionIds) {
        public Checkpoint { verifiedActionIds = List.copyOf(verifiedActionIds); }
    }

    public static final long DEFAULT_IDLE_WINDOW_MS = 240_000;
    public static final long DEFAULT_MAX_SAMPLE_GAP_MS = 60_000;
    public static final long DEFAULT_FRESHNESS_MS = 15_000;
    private final String targetId;
    private final LongSupplier clock;
    private final long idleWindowMs, maxSampleGapMs, freshnessMs;
    private long lastNow = -1, lastObservation = -1, lastSequence = -1, idleSince = -1;
    private int idleSamples;
    private String document = "", fingerprint = "", unresolvedActionId = "";
    private static final int MAX_VERIFIED_ACTIONS = 256;
    private final LinkedHashSet<String> verifiedActions = new LinkedHashSet<>();
    private Checkpoint recoverySource;
    private boolean resumeNeedsObservation;
    private Signal signal = Signal.UNKNOWN;
    private long latestOperation = -1, modelOperation = -1, actionOperation = -1;
    private long unresolvedOperation = -1;
    private Phase stoppedPhase;
    private String stopReason = "";

    public BrowserLongTaskPolicy(String targetId, LongSupplier clock) {
        this(targetId, clock, DEFAULT_IDLE_WINDOW_MS, DEFAULT_MAX_SAMPLE_GAP_MS, DEFAULT_FRESHNESS_MS);
    }
    public BrowserLongTaskPolicy(String targetId, LongSupplier clock, long idleWindowMs,
                                 long maxSampleGapMs, long freshnessMs) {
        this.targetId = identity(targetId); this.clock = Objects.requireNonNull(clock);
        if (idleWindowMs <= 0 || maxSampleGapMs <= 0 || freshnessMs <= 0
                || maxSampleGapMs > idleWindowMs || freshnessMs > maxSampleGapMs)
            throw new IllegalArgumentException("Invalid evidence windows");
        this.idleWindowMs = idleWindowMs; this.maxSampleGapMs = maxSampleGapMs; this.freshnessMs = freshnessMs;
    }

    /** A stale callback cannot clear a newer model/action operation. */
    public boolean beginModel(long operationId) {
        if (!canBegin(operationId)) return false;
        latestOperation = operationId; modelOperation = operationId; return true;
    }
    public boolean finishModel(long operationId) {
        if (operationId < 0 || modelOperation != operationId) return false;
        modelOperation = -1; return true;
    }
    /**
     * Invoke before dispatch. A false result must prevent dispatch. Persist checkpoint
     * before a mutation can leave the process; a failed save must fail closed.
     */
    public boolean beginAction(long operationId, String actionId, boolean mutation) {
        if (!canBegin(operationId) || mutation && (resumeNeedsObservation || !unresolvedActionId.isEmpty()
                || verifiedActions.contains(actionId) || verifiedActions.size() >= MAX_VERIFIED_ACTIONS)) return false;
        if (mutation) { unresolvedActionId = identity(actionId); unresolvedOperation = operationId; resetIdle(); }
        latestOperation = operationId; actionOperation = operationId; return true;
    }
    /** Receiving an acknowledgement is not evidence that the intended effect completed. */
    public boolean finishAction(long operationId) {
        if (operationId < 0 || actionOperation != operationId) return false;
        actionOperation = -1; return true;
    }
    private boolean canBegin(long operationId) {
        return stoppedPhase == null && operationId >= 0 && operationId > latestOperation
            && modelOperation < 0 && actionOperation < 0;
    }
    public boolean reconcileMutation(String observedTargetId, String actionId, long operationId, Resolution resolution) {
        if (resolution == null || !targetId.equals(observedTargetId) || unresolvedActionId.isEmpty()
                || !unresolvedActionId.equals(actionId) || unresolvedOperation != operationId) return false;
        if (resolution != Resolution.DEFINITELY_NOT_DISPATCHED) verifiedActions.add(actionId);
        unresolvedActionId = ""; unresolvedOperation = -1; return true;
    }
    public boolean mutationUnresolved() { return !unresolvedActionId.isEmpty(); }

    /**
     * Accept only a newly obtained real DOM observation on this lane's current document.
     * sequence is increasing per sample; cached/duplicate callbacks do not renew freshness.
     * fingerprint excludes volatile DOM refs. Busy signals require visible site evidence.
     */
    public boolean observe(long sequence, String documentId, String semanticFingerprint, Signal observedSignal) {
        long now = now();
        if (stoppedPhase != null || sequence < 0 || sequence <= lastSequence) return false;
        lastSequence = sequence;
        if (documentId == null || documentId.isBlank() || semanticFingerprint == null || observedSignal == null) {
            observationUnavailable(); return false;
        }
        // A fresh real DOM read satisfies observation-first even when site liveness is unknown.
        resumeNeedsObservation = false;
        if (observedSignal == Signal.UNKNOWN) { observationUnavailable(); return true; }
        boolean continuousIdle = observedSignal == Signal.IDLE && signal == Signal.IDLE
            && documentId.equals(document) && semanticFingerprint.equals(fingerprint)
            && lastObservation >= 0 && now - lastObservation <= maxSampleGapMs;
        if (observedSignal == Signal.IDLE) {
            if (!continuousIdle) { idleSince = now; idleSamples = 1; }
            else if (idleSamples < Integer.MAX_VALUE) idleSamples++;
        } else resetIdle();
        document = documentId; fingerprint = semanticFingerprint; signal = observedSignal; lastObservation = now;
        return true;
    }
    /** Bridge errors, absent DOM, and unknown samples break the idle evidence chain. */
    public void observationUnavailable() {
        signal = Signal.UNKNOWN; lastObservation = -1; resetIdle();
    }
    public void terminalError(String reasonCode) { stop(Phase.TERMINAL_ERROR, reasonCode); }
    public void userStop() { stop(Phase.USER_STOPPED, "user_stopped"); }
    private void stop(Phase phase, String reasonCode) {
        if (stoppedPhase != null) return;
        String validatedReason = reason(reasonCode);
        stoppedPhase = phase; stopReason = validatedReason;
        modelOperation = -1; actionOperation = -1;
        // The executor cancels transport separately. Uncertain effects remain fenced.
    }
    public Assessment assess() {
        long now = now();
        long age = lastObservation < 0 ? -1 : now - lastObservation;
        if (stoppedPhase != null) return assessment(stoppedPhase, Disposition.PAUSE, stopReason, age);
        if (actionOperation >= 0) return assessment(Phase.WAITING_ACTION, Disposition.CONTINUE, "action_in_flight", age);
        if (modelOperation >= 0) return assessment(Phase.WAITING_MODEL, Disposition.CONTINUE, "model_in_flight", age);
        if (age < 0 || age > freshnessMs || signal == Signal.UNKNOWN)
            return assessment(Phase.OBSERVATION_LOST, Disposition.RECHECK, "fresh_observation_required", age);
        if (signal == Signal.PAGE_LOADING)
            return assessment(Phase.PAGE_LOADING, Disposition.CONTINUE, "observed_page_loading", age);
        if (signal == Signal.PAGE_GENERATING)
            return assessment(Phase.PAGE_GENERATING, Disposition.CONTINUE, "observed_page_generating", age);
        if (idleSamples >= 3 && idleSince >= 0 && now - idleSince >= idleWindowMs)
            return assessment(Phase.STALLED_CANDIDATE, Disposition.RECHECK, "repeated_idle_requires_recheck", age);
        return assessment(Phase.RUNNING, Disposition.CONTINUE, "observed_page", age);
    }
    private Assessment assessment(Phase phase, Disposition disposition, String reason, long age) {
        return new Assessment(phase, disposition, reason, mutationUnresolved(), age);
    }
    public Checkpoint checkpoint() {
        Assessment state = assess();
        return new Checkpoint(targetId, state.phase(), state.reasonCode(), unresolvedActionId,
            latestOperation, unresolvedOperation, List.copyOf(verifiedActions));
    }
    /**
     * Resume is observation-first, never replay. Restore the existing task's user context
     * separately, and obtain fresh target-specific approvals for later mutations.
     */
    public static BrowserLongTaskPolicy resume(Checkpoint saved, String targetId, LongSupplier clock) {
        Objects.requireNonNull(saved);
        if (!identity(targetId).equals(saved.targetId())) throw new IllegalArgumentException("Wrong recovery target");
        BrowserLongTaskPolicy policy = new BrowserLongTaskPolicy(targetId, clock);
        policy.resumeNeedsObservation = true;
        if (saved.unresolvedActionId() == null) throw new IllegalArgumentException("Missing mutation state");
        if (!saved.unresolvedActionId().isEmpty()) policy.unresolvedActionId = identity(saved.unresolvedActionId());
        if (saved.phase() == null || saved.verifiedActionIds().size() > MAX_VERIFIED_ACTIONS)
            throw new IllegalArgumentException("Invalid recovery state");
        if (saved.lastOperationId() < -1 || saved.unresolvedOperationId() < -1
                || saved.unresolvedOperationId() > saved.lastOperationId()
                || saved.unresolvedActionId().isEmpty() != (saved.unresolvedOperationId() == -1))
            throw new IllegalArgumentException("Invalid recovery operation");
        policy.latestOperation = saved.lastOperationId(); policy.unresolvedOperation = saved.unresolvedOperationId();
        reason(saved.reasonCode());
        for (String actionId : saved.verifiedActionIds()) {
            if (!policy.verifiedActions.add(identity(actionId)) || policy.unresolvedActionId.equals(actionId))
                throw new IllegalArgumentException("Conflicting mutation state");
        }
        policy.recoverySource = saved;
        return policy;
    }
    /** Preserve this in the existing task outcome history; it is not a fresh observation or approval. */
    public Checkpoint recoverySource() { return recoverySource; }
    private void resetIdle() { idleSince = -1; idleSamples = 0; }
    private long now() {
        long value = clock.getAsLong();
        if (value < 0) throw new IllegalStateException("Monotonic clock must be nonnegative");
        if (lastNow >= 0 && value < lastNow) observationUnavailable();
        lastNow = value; return value;
    }
    private static String identity(String value) {
        if (value == null || !value.matches("[A-Za-z0-9_./:-]{1,160}"))
            throw new IllegalArgumentException("Expected opaque identity");
        return value;
    }
    private static String reason(String value) {
        if (value == null || !value.matches("[a-z][a-z0-9_]{0,79}"))
            throw new IllegalArgumentException("Expected reason code");
        return value;
    }
}
