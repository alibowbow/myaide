package dev.jun.gyeot.core;

import java.util.Objects;

/** Provider-independent, one-shot ownership for a composer recording. No global model settings. */
public final class DraftDictation {
    public enum State { IDLE, PERMISSION, RECORDING, TRANSCRIBING }
    public static final class Ticket {
        private final long generation;
        public final String scope, draft;
        public final int cursor;
        private Ticket(long generation, String scope, String draft, int cursor) {
            this.generation = generation; this.scope = scope; this.draft = draft; this.cursor = cursor;
        }
    }
    private long generation;
    private Ticket active;
    private State state = State.IDLE;

    public synchronized Ticket begin(String scope, String draft, int cursor, boolean permissionGranted) {
        if (state != State.IDLE) throw new IllegalStateException("이미 음성을 입력하고 있어요.");
        Objects.requireNonNull(scope); Objects.requireNonNull(draft);
        active = new Ticket(++generation, scope, draft, Math.max(0, Math.min(cursor, draft.length())));
        state = permissionGranted ? State.RECORDING : State.PERMISSION;
        return active;
    }
    public synchronized State state() { return state; }
    public synchronized boolean owns(Ticket ticket, String scope) {
        return ticket != null && active == ticket && ticket.generation == generation && ticket.scope.equals(scope);
    }
    public synchronized boolean permission(Ticket ticket, String scope, boolean granted) {
        if (!owns(ticket, scope) || state != State.PERMISSION) return false;
        if (!granted) { cancel(); return false; }
        state = State.RECORDING; return true;
    }
    public synchronized boolean stop(Ticket ticket, String scope) {
        if (!owns(ticket, scope) || state != State.RECORDING) return false;
        state = State.TRANSCRIBING; return true;
    }
    /** Called only on the UI thread. null means stale/edited/empty; never submit a message here. */
    public synchronized String complete(Ticket ticket, String scope, String currentDraft, String transcript) {
        if (!owns(ticket, scope) || state != State.TRANSCRIBING) return null;
        cancel(); // Consume before any UI callback; duplicate completions cannot insert twice.
        if (!ticket.draft.equals(currentDraft) || transcript == null || transcript.isBlank()) return null;
        String text = transcript.strip();
        String left = currentDraft.substring(0, ticket.cursor), right = currentDraft.substring(ticket.cursor);
        return left + (!left.isEmpty() && !Character.isWhitespace(left.charAt(left.length() - 1)) ? " " : "")
            + text + (!right.isEmpty() && !Character.isWhitespace(right.charAt(0)) ? " " : "") + right;
    }
    /** Cancel on navigation, room switch, pause, destruction, error, or explicit Cancel. */
    public synchronized void cancel() { ++generation; active = null; state = State.IDLE; }
}
