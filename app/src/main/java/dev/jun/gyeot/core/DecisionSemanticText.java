package dev.jun.gyeot.core;
/** Semantic provider evidence, not diagnostics: retain ordinary quoted instructions while removing credentials. */
public final class DecisionSemanticText {
    private DecisionSemanticText() {}
    public static String redact(String value) {
        if (value == null) return "";
        return value
            .replaceAll("(?is)-----BEGIN [A-Z ]*(?:PRIVATE KEY|CERTIFICATE)-----.*?(?:-----END [A-Z ]*(?:PRIVATE KEY|CERTIFICATE)-----|$)", "[credential removed]")
            .replaceAll("(?im)(?:authorization|proxy-authorization|cookie|set-cookie)\\s*:\\s*[^\\r\\n]+", "[credential removed]")
            .replaceAll("(?i)(?:bearer|basic)\\s+[^\\s,;]+", "[credential removed]")
            .replaceAll("(?i)(?:https?|wss?|file|content)://[^\\s]+", "[address removed]")
            .replaceAll("(?i)(?:api[_ -]?key|access[_ -]?token|refresh[_ -]?token|id[_ -]?token|client[_ -]?secret|session[_ -]?id|password|passwd|pwd|token|secret|authorization|비밀번호|비번|인증번호|인증코드|일회용\\s*코드|otp|pin)(?:은|는|이|가)?\\s*(?:[:=]|\\s)\\s*(?:\"[^\"]*\"|'[^']*'|[^\\s,;]+)", "[credential removed]")
            .replaceAll("(?:eyJ[A-Za-z0-9_-]+\\.){1}[A-Za-z0-9_-]+\\.[A-Za-z0-9_-]+", "[credential removed]")
            .replaceAll("(?i)(?:sk[-_]|rk-|pk-|gh[pousr]_|github_pat_|xai-|nvapi-|AIza|ya29\\.)[A-Za-z0-9._-]+", "[credential removed]")
            .replaceAll("[A-Za-z0-9_+/=-]{32,}", "[opaque value removed]");
    }
    // A password, PIN, one-time or verification code, card number or secret, by input type, autofill hint or a whole-word label.
    private static final java.util.regex.Pattern CREDENTIAL = Regex.unicode(
        "(?i)\\b(?:password|passwd|passcode|pass\\s?code|pwd|pin|pin\\s?code|otp|one[- ]time\\s+(?:code|password|passcode)|verification\\s+code|security\\s+code|"
        + "auth(?:entication)?\\s+code|2fa|mfa|cvv|cvc|csc|card\\s+number|credit\\s+card|api\\s?key|access\\s+token|refresh\\s+token|secret|client\\s+secret|credentials?|token|"
        + "one-time-code|current-password|new-password|cc-(?:number|csc|exp\\w*)|webauthn)\\b"
        + "|비밀번호|비번|암호|인증\\s*번호|인증\\s*코드|보안\\s*코드|보안\\s*문자|일회용\\s*(?:비밀번호|코드|번호)|핀\\s*번호|pin\\s*번호|카드\\s*번호|공동\\s*인증서|공인\\s*인증서");
    /** A credential field: its label, type or autofill hint names a password, PIN, code or card number. Digits alone (a zip code,
     * a year, an amount) never make a field a credential, and neither does a word that merely contains "pin" (Shipping). */
    public static boolean sensitiveInput(String label, String proposed) {
        return label != null && CREDENTIAL.matcher(label).find();
    }
}
