package dev.jun.gyeot.core;

import java.util.List;
import java.util.regex.Pattern;

/**
 * How 퍼레's voice says an answer (준: "음성상호작용을 더 강화해줘"): Typecast's emotion, how strong, and how quick, chosen from the
 * line itself and from what the user said, rather than the same bright voice for everything. A crying face or ㅠㅠ in the
 * line is said low, a 헉 or a surprised face high; a user who is down or hurting gets a soft voice (and calm sounds while
 * the answer is made: calm), good news a brighter one, a plain question (the time, the weather) a plain one; else 퍼레's usual
 * bright voice. Late at night it is never quick or high. Only what the voice can do: another emotion becomes plain. The words
 * are never changed. Pure Java.
 */
public final class VoiceTone {
    private VoiceTone() {}

    /** preset: Typecast's emotion; intensity: how strong; tempo: how quick (1 as it is); calm: the user is down, so 퍼레's short sounds are calm too. */
    public record Tone(String preset, double intensity, double tempo, boolean calm) {}
    /** Every emotion a Typecast voice may have, for a voice not limited by one (the phone's own). */
    public static final List<String> ALL = List.of("normal", "happy", "sad", "angry", "toneup", "tonedown");

    /** Crying faces (😢 🥲 🥺) and ㅠㅠ ㅜㅜ in the line. */
    private static final Pattern CRY = Pattern.compile("[\\x{1F622}\\x{1F972}\\x{1F97A}]|[ㅠㅜ]{2,}");
    /** Astonished faces (😮 😲) and 헉. */
    private static final Pattern WOW = Pattern.compile("[\\x{1F62E}\\x{1F632}]|헉");
    /** The user down or hurting (not 아파트). */
    private static final Pattern DOWN = Pattern.compile("(우울|슬퍼|속상|힘들|아파(?!트)|무서|짜증|차였|떨어졌)");
    /** The user's good news. */
    private static final Pattern UP = Pattern.compile("(합격|생일|대박|신나)");
    /** A plain question (the time, the weather, the battery …): a plain answer. */
    private static final Pattern FACT = Pattern.compile("(몇 ?시|날씨|배터리|저장 ?공간|얼마나|언제)");

    /**
     * The tone for an answer: userSaid, what it answers; line, its first sentence as written (emoji and all, before the voice
     * leaves them out); hour, the hour now (0–23); emotions, the voice's own (Typecast's list; none known: plain only). The
     * first rule that fits wins: the line's faces, the user's words, a plain question, else bright (happy, 1.1, a touch quick).
     */
    public static Tone of(String userSaid, String line, int hour, List<String> emotions) {
        String said = userSaid == null ? "" : userSaid, l = line == null ? "" : line;
        Tone t;
        if (CRY.matcher(l).find()) t = new Tone("sad", 0.8, 1.05, true);
        else if (WOW.matcher(l).find()) t = new Tone("toneup", 1.0, 1.05, false);
        else if (DOWN.matcher(said).find()) t = new Tone("sad", 0.8, 1.05, true);
        else if (UP.matcher(said).find()) t = new Tone("happy", 1.3, 1.05, false);
        else if (FACT.matcher(said).find()) t = new Tone("normal", 1.0, 1.05, false);
        else t = new Tone("happy", 1.1, 1.05, false);
        // Late at night (23–6) never quick or high.
        boolean night = hour >= 23 || hour < 6;
        String preset = night && "toneup".equals(t.preset()) ? "normal" : t.preset();
        // Only what the voice can do: sad as tonedown when it has no sad, else plain.
        List<String> can = emotions == null || emotions.isEmpty() ? List.of("normal") : emotions;
        if ("sad".equals(preset) && !can.contains("sad")) preset = can.contains("tonedown") ? "tonedown" : "normal";
        if (!can.contains(preset)) preset = "normal";
        return new Tone(preset, t.intensity(), night ? 1.0 : t.tempo(), t.calm());
    }
    /** Whether the user's words call for calm sounds while the answer is made (VoicePresets' calm "think" words). */
    public static boolean calm(String userSaid, int hour) { return of(userSaid, "", hour, ALL).calm(); }
}
