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
