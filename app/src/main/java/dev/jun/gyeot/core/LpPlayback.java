package dev.jun.gyeot.core;

/**
 * The LP page playing on behind other apps (준: "Lp가 다른창 열면 재생이 끊겨"), and 퍼레 hearing it (준: "퍼레가 lp에서 노래
 * 나오는 걸 인식못하네"): what the page last said it plays, and what follows from it. A media notification and the audio focus
 * while it plays (the notification stays, without the service, after a pause asked from it or by a call, so play is a tap
 * away; gone when the page itself is paused or stopped), the music 퍼레 hears, and what a change of the audio focus asks of the
 * page. Pure Java: LpMedia and LpService do the Android side.
 */
public final class LpPlayback {
    private LpPlayback() {}

    /** What the page said: playing or not, the track, its artist and record (or 내 음악's side), whether it is out of sight, when. */
    public record State(boolean playing, String title, String artist, String album, boolean hidden, long at) {
        public static final State NONE = new State(false, "", "", "", false, 0);
        public State {
            title = clip(title, 120); artist = clip(artist, 120); album = clip(album, 120);
        }
    }
    /** A page that stopped saying it plays for this long (closed, its renderer gone) is taken as stopped. */
    public static final long STALE_MS = 45_000;

    /** Whether it plays now: as last said, and said lately enough. */
    public static boolean playing(State s, long now) { return s != null && s.playing() && now - s.at() <= STALE_MS; }

    /** The notification's title and text: "LP · <track>", the record (and the artist). */
    public static String title(State s) { return s == null || s.title().isEmpty() ? "LP" : "LP · " + s.title(); }
    public static String text(State s) {
        if (s == null) return "";
        return s.artist().isEmpty() ? s.album() : s.album().isEmpty() ? s.artist() : s.artist() + " · " + s.album();
    }

    /** A change of the audio focus (AudioManager.AUDIOFOCUS_…). */
    public enum Focus { GAIN, LOSS, LOSS_TRANSIENT, LOSS_TRANSIENT_CAN_DUCK }
    /** What to do on it: the page's command ("play", "pause" or none), and whether it waits to come back when the focus does. */
    public record Answer(String command, boolean resumeLater) {}
    /**
     * Lost for good (other music): paused, not back by itself. Lost for a while (a call, a voice assistant): paused, and played
     * again when the focus comes back, if it was this that paused it. Ducking (directions, a notification sound): nothing here,
     * Android lowers it. Back: played again only if it was paused for a while.
     */
    public static Answer focus(Focus f, boolean playing, boolean resumeLater) {
        return switch (f) {
            case LOSS -> new Answer(playing ? "pause" : "", false);
            case LOSS_TRANSIENT -> new Answer(playing ? "pause" : "", playing || resumeLater);
            case LOSS_TRANSIENT_CAN_DUCK -> new Answer("", resumeLater);
            case GAIN -> new Answer(resumeLater && !playing ? "play" : "", false);
        };
    }
    /**
     * Whether the app takes the audio focus for what plays: the three records, made by the page's own Web Audio, yes; 내 음악 no,
     * a file the web view plays itself and takes the focus for on its own (준: "Lp는 예전 버전에선 내음악이 잘 나왔는데 왜 갑자기 안
     * 나오게 된거야", "내음악이 여전히 안 나와"): with both taking it, each took it from the other, the web view silenced the file
     * as the app took it, the app paused the page as the web view took it, and the record turned in silence or stopped at once.
     */
    public static boolean takesFocus(State s) { return s != null && (s.album() == null || !s.album().startsWith("내 음악")); }
    /** Whether the media service should run: while it plays, or while a call holds it paused for a while. */
    public static boolean serviceWanted(State s, long now, boolean resumeLater) { return playing(s, now) || resumeLater; }

    /** The commands the page takes (the notification's buttons, the focus). */
    public static boolean command(String c) { return "play".equals(c) || "pause".equals(c) || "toggle".equals(c) || "stop".equals(c); }

    private static String clip(String s, int n) {
        String t = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ").strip();
        return t.length() > n ? t.substring(0, n) : t;
    }
}
