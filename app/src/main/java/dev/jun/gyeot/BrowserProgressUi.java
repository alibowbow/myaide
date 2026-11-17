package dev.jun.gyeot;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.os.SystemClock;
import android.widget.ImageView;
import java.util.ArrayList;
import java.util.Collection;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongPredicate;
import java.util.function.Supplier;

/** UI observations only: never invent a task start, reasoning, percentage, or completion estimate. */
final class BrowserProgressUi {
    private BrowserProgressUi() {}

    /** A stable copy of one task's visible execution feedback, independent of engine implementation. */
    record Snapshot(Object tab, long lease, boolean running, String stage, List<String> actions,
                    long observedStartedElapsed, long updatedElapsed) {
        Snapshot {
            stage = BrowserProgressUi.stage(stage);
            actions = copyActions(actions);
        }
    }

    /**
     * The legacy engine has no monotonic start timestamp. This clock measures only the time
     * since this Activity first observed a running task, and labels it accordingly.
     * All methods are called on the UI thread. Switching tabs retains each observed task's clock.
     */
    static final class ObservationClock {
        private final Map<Object, Observation> observations = new IdentityHashMap<>();

        Snapshot observe(Object tab, long lease, boolean running, String stage, List<String> actions, long now) {
            List<String> copiedActions = copyActions(actions);
            String observedStage = stage == null ? "" : stage;
            if (tab == null || !running) {
                observations.remove(tab);
                return new Snapshot(tab, lease, false, stage, copiedActions, -1, -1);
            }
            long observedNow = Math.max(0, now);
            Observation previous = observations.get(tab);
            if (previous == null || previous.lease != lease) {
                previous = new Observation(lease, observedNow, observedStage, copiedActions);
                observations.put(tab, previous);
            } else if (!Objects.equals(previous.stage, observedStage) || !previous.actions.equals(copiedActions)) {
                previous.updated = Math.max(previous.updated, observedNow);
                previous.stage = observedStage;
                previous.actions = copiedActions;
            }
            return new Snapshot(tab, lease, true, stage, copiedActions, previous.started, previous.updated);
        }

        /** Release references to closed tabs; call with the engine's current tab list. */
        void retainTabs(Collection<?> liveTabs) {
            observations.entrySet().removeIf(entry -> liveTabs == null
                || liveTabs.stream().noneMatch(live -> live == entry.getKey()));
        }

        void clear() { observations.clear(); }

        private static final class Observation {
            final long lease, started;
            long updated;
            String stage;
            List<String> actions;
            Observation(long lease, long now, String stage, List<String> actions) {
                this.lease = lease;
                started = updated = now;
                this.stage = stage;
                this.actions = actions;
            }
        }
    }

    static String elapsed(Snapshot snapshot, long now) {
        if (snapshot == null || !snapshot.running() || snapshot.observedStartedElapsed() < 0) return "";
        long seconds = Math.max(0, now - snapshot.observedStartedElapsed()) / 1000;
        return "관찰 경과 " + (seconds / 60) + ":" + String.format(Locale.ROOT, "%02d", seconds % 60);
    }

    static String waiting(Snapshot snapshot, long now) {
        if (snapshot == null || !snapshot.running() || snapshot.updatedElapsed() < 0) return "";
        long quiet = Math.max(0, now - snapshot.updatedElapsed()) / 1000;
        return quiet >= 30 ? "새 진행 알림 없이 " + quiet + "초 · 응답을 기다리는 중" : "";
    }

    static String stage(String status) {
        if (status == null || status.isBlank()) return "작업 진행 중";
        String text = status.replace('\n', ' ').replace('\r', ' ').strip();
        int points = text.codePointCount(0, text.length());
        return points > 160 ? text.substring(0, text.offsetByCodePoints(0, 160)) + "…" : text;
    }

    private static List<String> copyActions(List<String> actions) {
        if (actions == null || actions.isEmpty()) return List.of();
        List<String> copy = new ArrayList<>();
        for (String action : actions) if (action != null && !action.isBlank()) copy.add(action);
        return List.copyOf(copy);
    }

    /** A stop request belongs to the captured task, never whichever task is current later. */
    interface StopTarget {
        boolean canStop();
        /** True only when the captured task passed the checks and its stop callback was dispatched. */
        boolean stop();
    }

    /**
     * Adapter seam for the per-tab engine owner. Legacy wiring checks lease/current-tab ownership
     * before invoking stop(String); integration replaces only stopLease with stopTask(lease,...).
     * Evaluate and invoke on the UI thread, where the engine serializes task changes.
     */
    static StopTarget stopTarget(Snapshot captured, Supplier<Object> currentTab, LongPredicate leaseValid,
                                 BooleanSupplier running, BooleanSupplier taskOnCurrentTab,
                                 LongConsumer stopLease) {
        return new StopTarget() {
            private boolean consumed;
            @Override public boolean canStop() {
                return !consumed && captured != null && captured.running() && captured.tab() != null
                    && currentTab.get() == captured.tab() && leaseValid.test(captured.lease())
                    && running.getAsBoolean() && taskOnCurrentTab.getAsBoolean();
            }
            @Override public boolean stop() {
                if (!canStop()) return false;
                consumed = true;
                stopLease.accept(captured.lease());
                return true;
            }
        };
    }

    /** Same toolbar footprint; an indeterminate arc conveys activity, never percentage complete. */
    static final class Indicator extends ImageView {
        private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
        private boolean running;
        Indicator(Context context) {
            super(context);
            ring.setStyle(Paint.Style.STROKE);
            ring.setStrokeCap(Paint.Cap.ROUND);
        }
        void running(boolean value) { if (running != value) { running = value; invalidate(); } }
        boolean running() { return running; }
        @Override protected void onDraw(Canvas canvas) {
            super.onDraw(canvas);
            if (!running) return;
            float density = getResources().getDisplayMetrics().density;
            float radius = Math.max(0, Math.min(getWidth(), getHeight()) / 2f - 5 * density);
            ring.setColor(Ui.ACCENT);
            ring.setStrokeWidth(1.8f * density);
            float angle = ValueAnimator.areAnimatorsEnabled() ? (SystemClock.uptimeMillis() % 1600L) * 360f / 1600f : -90;
            float x = getWidth() / 2f, y = getHeight() / 2f;
            canvas.drawArc(x - radius, y - radius, x + radius, y + radius, angle, 80, false, ring);
        }
    }
}
