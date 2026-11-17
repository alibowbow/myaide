package dev.jun.gyeot.core;

/**
 * 누르고 말하기 as states: idle → listening (while held, or until the recognizer hears the end) → thinking (the main chat
 * prepares the answer) → speaking (its sentences are read as they arrive) → idle. A press while busy stops everything. Every
 * event names the turn it belongs to, so a late recognizer result, sentence or reply after a stop changes nothing.
 */
public final class PushToTalk {
    public enum State { IDLE, LISTENING, THINKING, SPEAKING }
    private State state = State.IDLE;
    private int turn;
    /** The reply of this turn has ended (answered, failed or stopped); speaking then ends the turn when it goes quiet. */
    private boolean answered;

    public State state() { return state; }
    public int turn() { return turn; }
    public boolean busy() { return state != State.IDLE; }
    public boolean listening() { return state == State.LISTENING; }

    /** A press: when idle a new turn starts listening (true); while busy everything stops (false). */
    public boolean press() {
        if (busy()) { stop(); return false; }
        turn++; state = State.LISTENING; answered = false;
        return true;
    }
    /** Stops whatever runs, from any state; later events of the old turn are ignored. */
    public void stop() { turn++; state = State.IDLE; }
    /** The recognizer's final words: something to ask moves on to thinking (true); nothing heard ends the turn. */
    public boolean heard(int at, String words) {
        if (at != turn || state != State.LISTENING) return false;
        if (words == null || words.isBlank()) { state = State.IDLE; return false; }
        state = State.THINKING;
        return true;
    }
    /** A sentence of the answer went to the speech queue. */
    public boolean spoke(int at) {
        if (at != turn || (state != State.THINKING && state != State.SPEAKING)) return false;
        state = State.SPEAKING;
        return true;
    }
    /** The reply has ended; speaking says whether its sentences are still being read. */
    public void answered(int at, boolean speaking) {
        if (at != turn || (state != State.THINKING && state != State.SPEAKING)) return;
        answered = true;
        state = speaking ? State.SPEAKING : State.IDLE;
    }
    /** Reading went quiet: the turn ends once the reply has ended, else it waits for more of it. */
    public void quiet(int at) {
        if (at != turn || state != State.SPEAKING) return;
        state = answered ? State.IDLE : State.THINKING;
    }
    /** Listening failed, or the answer cannot be said: the turn ends. */
    public void failed(int at) { if (at == turn && busy()) state = State.IDLE; }
}
