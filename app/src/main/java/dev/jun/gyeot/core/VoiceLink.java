package dev.jun.gyeot.core;

/**
 * Where a voice call's connection is, set by the service where the sockets open, become ready,
 * drop, back off or wait for the network, never read back out of the status text. The orb shows 연결하는 중 from
 * connecting(), so a reconnect back-off ("다시 잇는 중") or a model switch is never shown as listening, and a caption
 * socket's retry ("자막 연결을 …") never makes a live call look like it is connecting.
 */
public enum VoiceLink {
    /** No call. */
    ENDED,
    /** The first connection, without the greeting: the call is not listening yet. */
    CONNECTING,
    /** The first connection with 켤 때 인사하기: the microphone already listens (its pre-roll keeps the words) while it connects. */
    WARMING,
    /** WARMING took longer than VoiceNetwork's slow mark: the call says so. */
    SLOW,
    /** Every socket the call needs is ready. */
    READY,
    /** The call is connecting again: a drop, a server's rotation, the network back, or a switch of model or settings. */
    RECONNECTING,
    /** No validated network: the call waits for one. */
    OFFLINE;

    /** The orb shows 연결하는 중. */
    public boolean connecting() { return this == CONNECTING || this == SLOW || this == RECONNECTING; }
    /** The call hears the user: the listening status may be shown. */
    public boolean listening() { return this == READY || this == WARMING; }
}
