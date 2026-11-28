package dev.jun.gyeot.core;

/**
 * The assistant's name, in one place. Korean particles depend on whether the name ends in a final
 * consonant, so the particle forms are derived here too: renaming means changing NAME (and app_name in strings.xml).
 * Package names, file names and stored data keep the old "gyeot" id so updates and saved data carry over.
 */
public final class Brand {
    public static final String NAME = "들퍼레";
    /** NAME with 이/가, 을/를, 은/는, 과/와 and 으로/로. */
    public static final String I = josa(NAME, "이", "가"), EUL = josa(NAME, "을", "를"), EUN = josa(NAME, "은", "는"), GWA = josa(NAME, "과", "와"), EURO = euro(NAME);
    private Brand() {}

    /** The assistant's name as the user set it in its profile (AssistantProfile), or NAME when it is unset. */
    public static String assistant(String name) { String clean = name == null ? "" : name.strip(); return clean.isEmpty() ? NAME : clean; }
    /**
     * Who the assistant is, for its instructions: the profile name, the only name it calls itself by. A name the user gave
     * keeps the app's name beside it.
     */
    public static String called(String name) {
        String clean = assistant(name);
        return clean.equals(NAME) ? NAME + " (call yourself only by this name)"
            : clean + " (the name the user gave you; the app is " + NAME + "; call yourself only by " + clean + ")";
    }
    /**
     * How the user calls the assistant, for its instructions: a short name is often misheard (퍼레야 heard as 거래야, put, 팔에),
     * and a misheard call must not become a topic or turn the answer into another language. who: the name, or called()'s text.
     */
    public static String hearing(String who) {
        String clean = assistant(who == null ? "" : who.split(" \\(")[0]);
        boolean ours = clean.equals(NAME) || clean.contains("퍼레");
        return " The user speaks Korean and calls you by name (" + (ours ? "퍼레, 퍼레야, 들퍼레" : clean + ", " + josa(clean, "아", "야")) + ")."
            + " Speech recognition often mishears a short name: a word or two said on its own that sounds like your name"
            + (ours ? " (such as 거래야, 거래, 버레, 페레, 펄레, 파레, 팔에, put, put it up, pere)" : "")
            + " is the user calling you, so answer as called (네, 말씀하세요) and never take it as a topic or answer in another language because of it."
            + " Answer in Korean unless the user clearly speaks whole sentences in another language.";
    }
    /** The word with the particle form that follows a final consonant, or the one that follows a vowel. */
    public static String josa(String word, String afterConsonant, String afterVowel) {
        return word + (finalConsonant(word) > 0 ? afterConsonant : afterVowel);
    }
    /** 으로 after a final consonant other than ㄹ, otherwise 로. */
    public static String euro(String word) {
        int last = finalConsonant(word);
        return word + (last > 0 && last != 8 ? "으로" : "로");
    }
    /** Index of the last syllable's final consonant (0 none, 8 ㄹ); 0 for anything but a Hangul syllable. */
    private static int finalConsonant(String word) {
        if (word == null || word.isEmpty()) return 0;
        char last = word.charAt(word.length() - 1);
        return last >= 0xAC00 && last <= 0xD7A3 ? (last - 0xAC00) % 28 : 0;
    }
}
