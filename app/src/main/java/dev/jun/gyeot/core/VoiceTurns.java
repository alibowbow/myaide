package dev.jun.gyeot.core;

import java.util.regex.Pattern;

/**
 * The few words of a voice talk that 퍼레 answers on the phone, with no AI and no Typecast: a bare call (퍼레야), an end
 * (그만할래, 잘 자), music stopped (그만, while it plays), "뭐라고?" (the last answer again), "잠깐" right after the user cut it
 * short (it just listens), and a no to an alarm or a timer waiting for a yes. Only a short thing said as a whole counts:
 * "그만 먹고 싶어" and "다 됐어?" are talk. Pure Java.
 */
public final class VoiceTurns {
    public enum Kind { CALL, BYE, PAUSE, REPEAT, HUSH, NO, NONE }
    /** Longer than this (without spaces and punctuation), what was said is talk, never one of these words. */
    static final int LONGEST = 12;
    private static final Pattern CALL = Pattern.compile("(?:들)?퍼레(?:야|아)?");
    private static final Pattern BYE = Pattern.compile("(?:이제)?(?:그만하자|그만할래|그만해|끝|끝내자|됐어고마워|바이바이|잘자|이따봐|이따보자)(?:요)?");
    /** 그만 alone: music playing, the music stops; else the talk ends. */
    private static final Pattern STOP = Pattern.compile("(?:이제)?그만(?:요)?");
    private static final Pattern REPEAT = Pattern.compile("뭐라고(?:했어|요)?|다시말해줘(?:요)?|다시말해주세요|한번더(?:말해줘|말해주세요)?(?:요)?|잘못들었어(?:요)?");
    private static final Pattern HUSH = Pattern.compile("잠깐(?:만)?(?:요)?|쉿|멈춰(?:요)?|스톱|조용(?:히)?(?:해)?(?:요)?");
    private static final Pattern NO = Pattern.compile("아니(?:야|요|에요)?|아뇨|싫어(?:요)?|됐어(?:요)?|취소(?:해)?(?:줘)?|하지마(?:요)?|괜찮아(?:요)?");
    private VoiceTurns() {}

    /**
     * What heard is. justCut: the user spoke over 퍼레 a moment ago (then 잠깐 is to wait); confirming: an alarm or a timer waits
     * for a yes (then 아니 is no); musicOn: music plays (then a bare 그만 stops it).
     */
    public static Kind of(String heard, boolean justCut, boolean confirming, boolean musicOn) {
        String raw = heard == null ? "" : heard.strip();
        boolean asked = raw.endsWith("?") || raw.endsWith("？");
        String t = raw.replaceAll("[\\s\\p{Punct}·…~。，！？「」『』“”‘’]+", "");
        if (t.isEmpty() || t.codePointCount(0, t.length()) > LONGEST) return Kind.NONE;
        if (CALL.matcher(t).matches()) return Kind.CALL;
        if (confirming && !asked && NO.matcher(t).matches()) return Kind.NO;
        if (justCut && HUSH.matcher(t).matches()) return Kind.HUSH;
        if (REPEAT.matcher(t).matches()) return Kind.REPEAT;
        if (asked) return Kind.NONE;
        if (STOP.matcher(t).matches()) return musicOn ? Kind.PAUSE : Kind.BYE;
        if (BYE.matcher(t).matches()) return Kind.BYE;
        return Kind.NONE;
    }
}
