package dev.jun.gyeot.core;

import java.util.HashSet;
import java.util.Set;

/**
 * One voice session's view of the default network. Offline, dropped connections wait instead of spending their reconnect
 * budget, and a validated network coming back resumes them once. While roaming, each data-heavy extra (precision
 * captions, the camera) gets one cost notice per session; ordinary mobile data gets none. Main thread only.
 */
public final class VoiceNetwork {
    /** retry(): no attempt now, the network is gone; it resumes once a validated network is back and costs nothing. */
    public static final int WAIT = 0;
    /** retry(): the budget for this window is spent. */
    public static final int GIVE_UP = -1;
    public static final int ATTEMPTS = 4;
    public static final long WINDOW_MS = 120_000;
    public static final String CAPTIONS = "captions", CAMERA = "camera";
    public static final String OFFLINE = "오프라인 · 인터넷이 돌아오면 이어서 연결해요";
    public static final String BACK = "인터넷이 돌아와서 대화를 다시 잇는 중…";
    /**
     * An assistant call shows 듣고 있어요 at once; when its first setup has not completed SLOW_MS
     * later it says so instead, and the orb shows connecting again (it keys on 연결).
     */
    public static final String SLOW = "연결이 늦어지고 있어요…";
    public static final long SLOW_MS = 3000;

    /** Reconnect attempts of one connection kind, counted only when they start while online. */
    public final class Budget {
        private int used;
        private long last;
        /** An attempt was counted but its delayed connect has not started yet. */
        private boolean planned;
        /** The attempt number (1 to ATTEMPTS) to start after its back-off, WAIT while offline, or GIVE_UP. */
        public int retry(long now) {
            if (offline) return WAIT;
            if (now - last > WINDOW_MS) used = 0;
            if (used >= ATTEMPTS) return GIVE_UP;
            used++; last = now; planned = true;
            return used;
        }
        /** The planned attempt is starting now; false while offline, where the return resumes it instead. */
        public boolean start() { planned = false; return !offline; }
        public int used() { return used; }
        private void refund() { if (planned) { planned = false; used = Math.max(0, used - 1); } }
        private void clear() { used = 0; last = 0; planned = false; }
    }

    public final Budget voice = new Budget(), captions = new Budget();
    private boolean offline, roaming;
    private final Set<String> warned = new HashSet<>();

    /** A new session: online until told otherwise, with a fresh budget and no notices given. */
    public void reset() {
        offline = false; roaming = false; warned.clear();
        voice.clear(); captions.clear();
    }
    public boolean offline() { return offline; }
    /** The default network is gone. True when this starts an offline spell; an attempt counted but not started is given back. */
    public boolean lost() {
        if (offline) return false;
        offline = true; voice.refund(); captions.refund();
        return true;
    }
    /** The default network's capabilities. True exactly once per offline spell, when a validated network is back: resume now. */
    public boolean capabilities(boolean validated, boolean metered, boolean roaming) {
        this.roaming = roaming;
        if (!offline || !validated) return false;
        offline = false;
        return true;
    }
    /** The notice to show before a data-heavy extra starts while roaming, once per extra and session; else null. */
    public String costWarning(String feature) {
        if (!roaming || !CAPTIONS.equals(feature) && !CAMERA.equals(feature) || !warned.add(feature)) return null;
        return "로밍 중이에요" + (CAMERA.equals(feature) ? " · 카메라는 1초에 한 장씩 사진을 보내 데이터를 많이 써요" : " · 정밀 자막은 연결을 하나 더 열어 데이터를 더 써요");
    }
}
