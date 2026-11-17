package dev.jun.gyeot.core;

import java.util.regex.Pattern;

/**
 * Patterns whose \b, \w, \d and \s treat Hangul and every other script as on the phone. Android's regex engine (ICU) always
 * uses Unicode classes, does not support UNICODE_CHARACTER_CLASS, and rejects the embedded (?U) flag: a (?U) pattern throws
 * PatternSyntaxException (U_REGEX_INVALID_FLAG) there, and in a static field it fails its whole class with
 * ExceptionInInitializerError. The JVM that runs the tests needs the flag
 * for the same meaning, so it gets it only where it is supported. Never write (?U) in a pattern.
 */
public final class Regex {
    private Regex() {}
    private static final int UNICODE = unicodeFlag();
    private static int unicodeFlag() {
        try { Pattern.compile("\\w", Pattern.UNICODE_CHARACTER_CLASS); return Pattern.UNICODE_CHARACTER_CLASS; }
        catch (RuntimeException unsupported) { return 0; } // Android: Unicode classes are always used.
    }
    /** As Pattern.compile(regex), with Unicode character classes on every runtime. */
    public static Pattern unicode(String regex) { return Pattern.compile(regex, UNICODE); }
}
