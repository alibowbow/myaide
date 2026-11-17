package dev.jun.gyeot.core;

/** Immutable language pair. A and B are peers; neither side is fixed to Korean. */
public final class InterpreterConfig {
    public static final String AUTO = "auto", A_TO_B = "a_to_b", B_TO_A = "b_to_a";
    public static final String[][] LANGUAGES;
    static {
        LANGUAGES = new String[GeminiProtocol.LANGUAGES.length + 1][];
        LANGUAGES[0] = new String[]{"Korean", "한국어", "ko"};
        for (int i = 0; i < GeminiProtocol.LANGUAGES.length; i++) LANGUAGES[i + 1] = GeminiProtocol.LANGUAGES[i].clone();
    }
    public final String a, b, mode;
    public InterpreterConfig(String a, String b, String mode) {
        this.a = code(a, "ko");
        String other = code(b, "en");
        this.b = other.equals(this.a) ? ("en".equals(this.a) ? "ko" : "en") : other;
        this.mode = A_TO_B.equals(mode) || B_TO_A.equals(mode) ? mode : AUTO;
    }
    public static String code(String value, String fallback) {
        for (String[] item : LANGUAGES) if (item[0].equalsIgnoreCase(value) || item[2].equalsIgnoreCase(value)) return item[2];
        return fallback;
    }
    public static String label(String code) {
        for (String[] item : LANGUAGES) if (item[2].equals(code)) return item[1];
        return "원문";
    }
    public static String name(String code) {
        for (String[] item : LANGUAGES) if (item[2].equals(code)) return item[0];
        return "English";
    }
    public static boolean matches(String expected, String detected) {
        if (detected == null) return false;
        return expected.equalsIgnoreCase(detected) || detected.toLowerCase(java.util.Locale.ROOT).startsWith(expected.toLowerCase(java.util.Locale.ROOT) + "-");
    }
    public boolean automatic() { return AUTO.equals(mode); }
    public String[] targets() { return automatic() ? new String[]{b, a} : new String[]{A_TO_B.equals(mode) ? b : a}; }
    public String source(String target) { return a.equals(target) ? b : a; }
    public String description() {
        return B_TO_A.equals(mode) ? label(b) + " → " + label(a) : label(a) + (automatic() ? " ↔ " : " → ") + label(b);
    }
    public String fingerprint() { return a + "\u0000" + b + "\u0000" + mode; }
    public InterpreterConfig language(boolean first, String selected) {
        String code = code(selected, "");
        if (code.isEmpty() || code.equals(first ? b : a)) throw new IllegalArgumentException("A와 B는 서로 다른 언어를 골라 주세요.");
        return new InterpreterConfig(first ? code : a, first ? b : code, mode);
    }
    public InterpreterConfig swapped() { return new InterpreterConfig(b, a, mode); }
    public InterpreterConfig mode(String mode) { return new InterpreterConfig(a, b, mode); }
}
