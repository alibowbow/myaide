package dev.jun.gyeot.core;

import org.json.JSONObject;

/**
 * What a 작업창 room is doing, from the two things that say so: the state saved on the room
 * (WorkBook.RUNNING, WorkCheckpoint.PAUSED, else idle) and whether this process holds a live run for it (the service's
 * Control). Neither alone is the truth: a room saved as running with no live run is ORPHANED, which happens when its
 * result could not be saved (a full disk) and nothing will ever finish it; only a stop does.
 */
public enum WorkRoomPhase {
    /** Nothing runs and nothing waits. */
    IDLE,
    /** A live run is working on it. */
    RUNNING,
    /** A live run is answering read-only after the app restarted mid-run. */
    RECOVERING,
    /** Stopped by a restart and waiting: resumed or cancelled by the user (복구 대기). */
    PAUSED,
    /** Saved as running, but no live run: nothing will finish it until it is stopped. */
    ORPHANED;

    public static WorkRoomPhase of(JSONObject thread, boolean live) {
        if (thread == null) return IDLE;
        String state = thread.optString("state");
        if (WorkBook.RUNNING.equals(state)) {
            if (!live) return ORPHANED;
            JSONObject checkpoint = WorkCheckpoint.get(thread);
            return checkpoint != null && checkpoint.optBoolean("readOnly") ? RECOVERING : RUNNING;
        }
        if (WorkCheckpoint.PAUSED.equals(state)) return PAUSED;
        return live ? RUNNING : IDLE;
    }
    /** A run is going: the composer shows 중지 and new messages queue behind it. */
    public boolean busy() { return this == RUNNING || this == RECOVERING || this == ORPHANED; }
    /** A voice 멈춰 (work_stop) acts on it: a live run, or a room left running with nothing behind it. */
    public boolean voiceStoppable() { return this == RUNNING || this == RECOVERING || this == ORPHANED; }
}
