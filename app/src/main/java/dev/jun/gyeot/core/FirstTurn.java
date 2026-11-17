package dev.jun.gyeot.core;

/**
 * The voice call's first turn. Only the first answer of a call came 3 to 4 seconds late, on every
 * Live model, and waiting before speaking did not help: the wait comes after setup, in the first turn itself, where Gemini
 * reads the call's instructions and function declarations (about 40,000 characters) for the first time. Later turns reuse
 * what it read.
 *
 * So once the call's first connection is ready, before any of the user's words, the call sends one short context line that
 * asks for no reply (clientContent with turnComplete false, the kind of note the call already sends mid-call). It goes once
 * audio has gone out on the connection and the last half second of it was still (VoiceStartupAudio.STILL_BYTES): the held
 * room sound at setupComplete, or the stream after it. Gemini then has something to read the instructions for while the user
 * is still silent, instead of after they stopped.
 *
 * It never goes into the user's words or after them: not after two frames in a row above the room's level (a voice, even a
 * soft one under the speech line; one frame alone is a click, and the greeting's own tick is left out), not when words were
 * dropped, and not once the conversation began another way (typed words, a notice, a function reply, or the server heard or
 * said something). The phone log says which. Once per call, never on a reconnect after the call was ready, never for
 * interpretation, dictation or the browser's Live control.
 */
public final class FirstTurn {
    /** Marked as the app's own notice like the call's other notes, and as not the user's words (Greeting.INSTRUCTIONS counts those). */
    public static final String PRIMER = "[" + Brand.NAME + "] (자동 안내, 사용자의 말이 아니에요. 대답하지 마세요) 음성 통화가 연결됐어요. 사용자가 말하면 그 말에 바로 대답하세요.";
    private FirstTurn() {}
}
