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
    private static final Pattern NAMED = Pattern.compile("^(?:들)?퍼레(?:야|아)?|(?:들)?퍼레(?:야|아)?$");
    // The end of the talk in the user's own words (준: "음성종료, 들어가, 이제 쉬어 등의 말에 엉뚱한 반응을 해", "난 음성종료하란건데").
    private static final Pattern BYE = Pattern.compile("(?:이제|그럼|오케이|응|알았어|고마워)?(?:그만하자|그만할래|그만해|그만해도돼|끝|끝내|끝내자|끝내줘|끝났어|됐어고마워|이제됐어|바이바이|바이|잘자|잘가|이따봐|이따보자|나중에봐|나중에얘기해"
        + "|(?:음성|음성대화|대화|퍼레)?종료(?:해|해줘|할게|하자)?|(?:음성|대화|마이크)(?:꺼|꺼줘|끝)|대화끝|들어가(?:봐|있어|도돼|서쉬어)?|(?:이제)?쉬어(?:도돼)?|쉬고있어|가있어|가봐|그만듣고|그만들어|수고했어)(?:요)?");
    /** 그만 alone: music playing, the music stops; else the talk ends. */
    private static final Pattern STOP = Pattern.compile("(?:이제)?그만(?:요)?");
    private static final Pattern REPEAT = Pattern.compile("뭐라고(?:했어|요)?|다시말해줘(?:요)?|다시말해주세요|한번더(?:말해줘|말해주세요)?(?:요)?|잘못들었어(?:요)?");
    private static final Pattern HUSH = Pattern.compile("잠깐(?:만)?(?:요)?|쉿|멈춰(?:요)?|스톱|조용(?:히)?(?:해)?(?:요)?");
    private static final Pattern NO = Pattern.compile("아니(?:야|요|에요)?|아뇨|싫어(?:요)?|됐어(?:요)?|취소(?:해)?(?:줘)?|하지마(?:요)?|괜찮아(?:요)?");
    /** What is said to the music itself (다음 곡, 멈춰, 소리 좀 줄여 줘): heard over music without 퍼레's name (VoiceHints.musicGate). */
    private static final Pattern MEDIA = Pattern.compile("(?:다음|이전)(?:곡|노래)(?:틀어(?:줘)?)?(?:요)?|멈춰(?:줘)?(?:요)?|(?:일시)?정지(?:해)?(?:줘)?(?:요)?|그만(?:요)?|스톱"
        + "|(?:다시|계속)틀어(?:줘)?(?:요)?|(?:소리|볼륨)(?:좀)?(?:줄여|키워|낮춰|높여|작게|크게)(?:줘|주세요)?(?:요)?|(?:노래|음악)(?:좀)?(?:꺼|멈춰|그만)(?:줘)?(?:요)?");
    private VoiceTurns() {}

    /** Whether heard, as a whole and short, is said to the music playing (다음 곡, 정지, 소리 좀 줄여 줘). */
    public static boolean media(String heard) {
        String t = bare(heard);
        return !t.isEmpty() && t.codePointCount(0, t.length()) <= LONGEST && MEDIA.matcher(t).matches();
    }
    /** A thing 퍼레 was about to do, or just did, said to be the wrong one: 아니 멜론으로, 그거 말고, 다른 거, 잠깐, 취소. */
    private static final Pattern CORRECTS = Pattern.compile("^(?:아니|아냐|아뇨)|그게아니라|말고|다른거|잠깐|취소|하지마");
    /** Whether heard corrects what 퍼레 was about to do or just did (a no, a "not that", a wait), at any length. */
    public static boolean corrects(String heard) {
        String t = bare(heard);
        return !t.isEmpty() && CORRECTS.matcher(t).find();
    }
    /** What was said without spaces and punctuation. */
    static String bare(String heard) { return heard == null ? "" : heard.strip().replaceAll("[\\s\\p{Punct}·…~。，！？「」『』“”‘’]+", ""); }

    /**
     * What heard is. justCut: the user spoke over 퍼레 a moment ago (then 잠깐 is to wait); confirming: an alarm or a timer waits
     * for a yes (then 아니 is no); musicOn: music plays (then a bare 그만 stops it).
     */
    public static Kind of(String heard, boolean justCut, boolean confirming, boolean musicOn) {
        String raw = heard == null ? "" : heard.strip();
        boolean asked = raw.endsWith("?") || raw.endsWith("？");
        String t = bare(raw);
        if (t.isEmpty() || t.codePointCount(0, t.length()) > LONGEST + 4) return Kind.NONE;
        if (CALL.matcher(t).matches()) return Kind.CALL;
        // The name before or after the words ("퍼레야 음성 종료", "이제 쉬어 퍼레야") is a call, not part of them.
        String named = NAMED.matcher(t).replaceAll("");
        if (!named.isEmpty()) t = named;
        if (t.codePointCount(0, t.length()) > LONGEST) return Kind.NONE;
        if (confirming && !asked && NO.matcher(t).matches()) return Kind.NO;
        if (justCut && HUSH.matcher(t).matches()) return Kind.HUSH;
        if (REPEAT.matcher(t).matches()) return Kind.REPEAT;
        if (asked) return Kind.NONE;
        if (STOP.matcher(t).matches()) return musicOn ? Kind.PAUSE : Kind.BYE;
        if (BYE.matcher(t).matches()) return Kind.BYE;
        return Kind.NONE;
    }
}
