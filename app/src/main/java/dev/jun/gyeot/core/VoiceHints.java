package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * What 퍼레's ears are told before they hear, and what of the heard is let through while music plays (준: "음성상호작용을 더
 * 강화해줘"). Over a song 퍼레 kept answering its lyrics: with music on only words with its name (퍼레야, 다음 곡), or a short
 * thing said to the music itself (다음 곡, 소리 좀 줄여 줘), get through. Both ears (the phone's recognizer, Whisper) get this
 * phone's own words: 퍼레's names, the saved places' names (집, 회사), the apps just used, 퍼레's toys and clothes, and the
 * names in what it said last (아이유, 좋은날), so they are written as the user means them. Pure Java.
 */
public final class VoiceHints {
    /** The most hints the phone's recognizer is given. */
    public static final int MOST = 100;
    /** Whisper reads only a short prompt (224 tokens, and Korean takes many): its length in all, and of 퍼레's last words in it. */
    public static final int PROMPT = 120, PROMPT_LINE = 60, PROMPT_HINTS = 12;
    /** A clip shorter than this whose words are only the prompt's: Whisper wrote its prompt back for a cough or a click. */
    public static final long ECHO_MS = 1200;
    private VoiceHints() {}

    /** 퍼레 called, at the start ("퍼레야, 다음 곡", "퍼레 그만"): the rest is what was asked. */
    private static final Pattern NAMED = Pattern.compile("^(?:들)?퍼레(?:야|아)?(?:$|[\\s,.!?~]+(.*))$", Pattern.DOTALL);

    /**
     * What of text gets through while music plays (musicOn), else text unchanged: words that start with 퍼레's name give the
     * rest ("퍼레야" alone when there is none), a short thing said to the music as it is (VoiceTurns.media), anything else
     * null (the song's words, or talk in the room, not said to 퍼레).
     */
    public static String musicGate(String text, boolean musicOn) {
        if (!musicOn || text == null) return text;
        String t = text.strip();
        Matcher named = NAMED.matcher(t);
        if (named.matches()) { String rest = named.group(1) == null ? "" : named.group(1).strip(); return rest.isEmpty() ? "퍼레야" : rest; }
        return VoiceTurns.media(t) ? t : null;
    }

    /**
     * The words the ears are given, at most MOST, in this order: 퍼레's names, the saved places' names, the apps just used,
     * 퍼레's own things, and the names in its last words (two Hangul syllables or more). Each kind has its share, so the last
     * words always find room.
     */
    public static List<String> build(List<String> names, List<String> places, List<String> labels, List<String> buddy, String lastLine) {
        Set<String> out = new LinkedHashSet<>();
        add(out, names, 5); add(out, places, 20); add(out, labels, 20); add(out, buddy, 30); add(out, nouns(lastLine), 25);
        List<String> list = new ArrayList<>(out);
        return list.size() > MOST ? List.copyOf(list.subList(0, MOST)) : List.copyOf(list);
    }
    private static void add(Set<String> out, List<String> words, int most) {
        if (words == null) return;
        int n = 0;
        for (String w : words) {
            if (n >= most) return;
            String one = w == null ? "" : w.strip().replaceAll("\\s+", " ");
            if (one.isEmpty() || one.codePointCount(0, one.length()) > 20) continue;
            if (out.add(one)) n++;
        }
    }

    /** What a line says aloud: no emoji, marks or extra spaces. */
    static String plain(String line) {
        if (line == null) return "";
        return BuddyMind.withoutEmoticons(line).replaceAll("[\\p{So}\\p{Cs}\\x{FE0F}\\x{200D}\\x{20E3}]", "").replaceAll("\\s+", " ").strip();
    }
    /** Endings of a word that is not a name (줄게, 틀어, 좋아, 했지, 할까 …): left out of the hints. */
    private static final Pattern NOT_A_NAME = Pattern.compile(".*(?:게|요|다|어|아|줘|지|까|래|네|고|서|면|니|야|데|해|돼|봐|와|자|죠|며|든|던|는|은|러|려|할|한|된|운)$");
    /** Words of every line (이제, 정말 …) that tell an ear nothing. */
    private static final Set<String> COMMON = Set.of("이제", "정말", "진짜", "너무", "조금", "아주", "같이", "그럼", "그냥", "혹시", "우리", "오늘", "지금", "내일", "어제",
        "그거", "이거", "저거", "여기", "거기", "뭔가", "벌써", "아직", "계속", "다시", "바로", "먼저", "나중", "응응", "그래", "알았어", "있잖아");
    /** A few words' endings (를, 의, 에서, 으로 …) that leave a name of two syllables or more when taken off. */
    private static final Pattern PARTICLE = Pattern.compile("^(.{2,}?)(?:에서|으로|한테|까지|부터|처럼|이랑|랑|의|에|로|와|과|도|만|은|는|을|를)$");
    /** The names in a line (아이유, 좋은날 of "아이유 좋은날 틀어 줄게!"): Hangul words of two syllables or more that do not end as a verb does. */
    static List<String> nouns(String line) {
        List<String> out = new ArrayList<>();
        for (String w : plain(line).split("[^가-힣]+")) {
            if (w.length() < 2) continue;
            Matcher p = PARTICLE.matcher(w); if (w.length() >= 3 && p.matches()) w = p.group(1);
            if (w.length() < 2 || COMMON.contains(w) || NOT_A_NAME.matcher(w).matches() || out.contains(w)) continue;
            out.add(w);
        }
        return out;
    }

    /**
     * Whisper's prompt: 퍼레's name as the user calls it, what it said last (PROMPT_LINE at most), then a few hints not in it
     * yet (PROMPT_HINTS at most), PROMPT characters in all.
     */
    public static String prompt(String lastLine, List<String> hints) {
        StringBuilder p = new StringBuilder("퍼레야,");
        String line = plain(lastLine);
        if (line.codePointCount(0, line.length()) > PROMPT_LINE) line = line.substring(0, line.offsetByCodePoints(0, PROMPT_LINE)).strip();
        if (!line.isEmpty()) p.append(' ').append(line);
        int n = 0;
        for (String h : hints == null ? List.<String>of() : hints) {
            if (n >= PROMPT_HINTS) break;
            if (h == null || h.isBlank() || p.indexOf(h) >= 0) continue;
            String more = (n == 0 ? " " : ", ") + h;
            if (p.codePointCount(0, p.length()) + more.codePointCount(0, more.length()) > PROMPT) continue;
            p.append(more); n++;
        }
        return p.toString();
    }

    /** Whisper wrote its prompt back: words all inside the prompt, from a clip shorter than ECHO_MS. */
    public static boolean echo(String heard, String prompt, long ms) {
        String h = VoiceTurns.bare(heard), p = VoiceTurns.bare(prompt);
        return ms < ECHO_MS && !h.isEmpty() && p.contains(h);
    }
}
