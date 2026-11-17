package dev.jun.gyeot.core;

import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks secrets and personal data in free text bound for the 오류 진단 보고서 (error messages and status values).
 * The report never takes conversations, keys or account data as input; this is the second line of defence for messages
 * that quote them by accident (an API error echoing a request, a file name, an address). It removes too much rather than
 * too little: a diagnostic report needs the kind of error and where it happened, not its private details.
 *
 * Patterns use explicit ASCII look-arounds instead of \b, so Java (host tests) and ICU (Android) agree next to Hangul.
 */
public final class DiagnosticRedactor {
    /** Longer input is cut before matching; the visible summary is far shorter anyway. */
    static final int INPUT_LIMIT = 4000;
    /** Hosts that help diagnosis and identify nobody: the services the app itself talks to. Any other host is hidden. */
    public static final Set<String> KNOWN_HOSTS = Set.of(
        "api.openai.com", "auth.openai.com", "chatgpt.com", "generativelanguage.googleapis.com", "aistudio.google.com",
        "api.anthropic.com", "api.x.ai", "accounts.x.ai", "auth.x.ai", "integrate.api.nvidia.com",
        "api.github.com", "github.com", "objects.githubusercontent.com", "release-assets.githubusercontent.com");

    private DiagnosticRedactor() {}

    private record Rule(Pattern pattern, String replacement) {
        String apply(String text) { return pattern.matcher(text).replaceAll(replacement); }
    }
    private static Rule rule(String regex, String replacement) { return new Rule(Pattern.compile(regex), replacement); }

    /** Before addresses: whole private keys, and request fields that carry what the user wrote. */
    private static final List<Rule> FIRST = List.of(
        rule("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(?:-----END [A-Z ]*PRIVATE KEY-----|$)", "[비공개 키 제거]"),
        rule("(?im)(?<![A-Za-z0-9_-])(?:set-cookie|cookie)\\s*:\\s*[^\\r\\n]+", "Cookie: [제거]")
    );
    private static final Pattern FIELD = Pattern.compile("(?i)([\"'])(?:prompt|input|content|contents|parts|text|messages|query|instructions|transcript|request_body)\\1\\s*:\\s*");
    /** Web addresses: only the host of a known service is kept, never a path or query (they carry codes and tokens). */
    private static final Pattern URL = Pattern.compile("(?i)(?<![A-Za-z0-9_])(?:https?|wss?)://(\\[[0-9a-f:.%\\w-]+\\]|[A-Za-z0-9.\\-]+)(?::[0-9]{1,5})?([^\\s\"'<>)\\]]*)");
    /** Local addresses, credentials in known formats, and name=value pairs whose names say they are secret. */
    private static final List<Rule> SECRETS = List.of(
        rule("(?i)(?<![A-Za-z0-9_])(?:content|file)://[^\\s\"'<>)\\]]+", "[파일 주소 제거]"),
        rule("(?<![A-Za-z0-9_.])/(?:data|storage|sdcard|mnt|home|Users|tmp|var)/[^\\s\"'<>:;,)\\]]+", "[경로 제거]"),
        rule("(?i)(?<![A-Za-z0-9_])[A-Z]:[\\\\/][^\\s\"'<>;,)\\]]+", "[경로 제거]"),
        rule("(?i)(?<![A-Za-z0-9_])(bearer|basic)\\s+[A-Za-z0-9._~+/=\\-]{8,}", "$1 [토큰 제거]"),
        rule("(?<![A-Za-z0-9_\\-])eyJ[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{4,}", "[토큰 제거]"),
        rule("(?<![A-Za-z0-9_\\-])(?:sk|rk|pk)-[A-Za-z0-9_\\-]{8,}", "[키 제거]"),
        rule("(?<![A-Za-z0-9_\\-])AIza[0-9A-Za-z_\\-]{20,}", "[키 제거]"),
        rule("(?<![A-Za-z0-9_\\-])gh[pousr]_[A-Za-z0-9]{16,}", "[토큰 제거]"),
        rule("(?<![A-Za-z0-9_\\-])github_pat_[A-Za-z0-9_]{16,}", "[토큰 제거]"),
        rule("(?<![A-Za-z0-9_\\-])xai-[A-Za-z0-9_\\-]{16,}", "[키 제거]"),
        rule("(?<![A-Za-z0-9_\\-])nvapi-[A-Za-z0-9_\\-]{16,}", "[키 제거]"),
        rule("(?<![A-Za-z0-9_\\-])ya29\\.[A-Za-z0-9._\\-]{10,}", "[토큰 제거]"),
        rule("(?<![A-Za-z0-9_\\-])mak_[A-Za-z0-9_\\-]{6,}", "[키 제거]"),
        rule("(?<![A-Za-z0-9_\\-])AKIA[0-9A-Z]{16}(?![A-Za-z0-9])", "[키 제거]"),
        rule("(?<![A-Za-z0-9_\\-])xox[abprs]-[A-Za-z0-9\\-]{10,}", "[토큰 제거]"),
        rule("(?i)(?<![A-Za-z0-9_\\-])(api[_\\-]?key|x-goog-api-key|access[_\\-]?token|refresh[_\\-]?token|id[_\\-]?token|auth[_\\-]?code|client[_\\-]?secret|secret|password|passwd|pwd|token|authorization|set-cookie|cookie|session[_\\-]?id|key)(\"?\\s*[:=]\\s*\"?)(?!\\[)[^\\s\"\\&,;\\}]+", "$1$2[제거]")
    );
    /** Card-like digit runs; only those passing the Luhn check are masked, so dates and counts stay. */
    private static final Pattern CARD = Pattern.compile("(?<![0-9])(?:[0-9][ \\-]?){12,18}[0-9](?![0-9])");
    /** Personal data, opaque identifiers, quoted names, then one line. */
    private static final List<Rule> PERSONAL = List.of(
        rule("[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}", "[이메일 제거]"),
        rule("(?i)(?<![0-9a-z])[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}(?![0-9a-z])", "[ID 제거]"),
        rule("(?<![0-9])[0-9]{6}[\\- ]?[1-8][0-9]{6}(?![0-9])", "[주민등록번호 제거]"),
        rule("(?<![0-9+])01[016789][\\- .]?[0-9]{3,4}[\\- .]?[0-9]{4}(?![0-9])", "[전화번호 제거]"),
        rule("(?<![0-9+.])0(?:2|[3-6][1-5]|70)[\\- .)]?[0-9]{3,4}[\\- .][0-9]{4}(?![0-9])", "[전화번호 제거]"),
        rule("(?<![0-9A-Za-z])\\+[0-9]{1,3}[\\- .]?\\(?[0-9]{1,4}\\)?(?:[\\- .]?[0-9]{2,4}){2,4}(?![0-9])", "[전화번호 제거]"),
        rule("(?<![0-9.])(?:(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])\\.){3}(?:25[0-5]|2[0-4][0-9]|1?[0-9]?[0-9])(?![0-9.])", "[IP 제거]"),
        rule("(?i)(?<![A-Za-z0-9_])(?:[0-9a-f]{0,4}:){2,}[0-9a-f:.]*(?:%[A-Za-z0-9_-]+)?", "[IP 제거]"),
        rule("(?i)(?<![0-9a-z])[0-9a-f]{32,}(?![0-9a-z])", "[긴 식별값 제거]"),
        rule("(?<![A-Za-z0-9_+/=\\-])(?=[A-Za-z0-9_+/=\\-]*[0-9])(?=[A-Za-z0-9_+/=\\-]*[a-z])(?=[A-Za-z0-9_+/=\\-]*[A-Z])[A-Za-z0-9_+/=\\-]{32,}", "[긴 식별값 제거]"),
        rule("‘[^’\\n]{1,80}’", "‘…’"),
        rule("“[^”\\n]{1,120}”", "“…”"),
        rule("「[^」\\n]{1,80}」", "「…」"),
        rule("[\\x00-\\x1f\\x7f\\s]+", " ")
    );
    /** What must never survive masking; the report checks its whole text with it before handing it out. */
    private static final Pattern LEAK = Pattern.compile("(?i)(?<![A-Za-z0-9_\\-])(?:sk-[A-Za-z0-9_\\-]{8,}|AIza[0-9A-Za-z_\\-]{20,}|gh[pousr]_[A-Za-z0-9]{16,}|github_pat_[A-Za-z0-9_]{16,}|xai-[A-Za-z0-9_\\-]{16,}|nvapi-[A-Za-z0-9_\\-]{16,}|ya29\\.[A-Za-z0-9._\\-]{10,}|eyJ[A-Za-z0-9_\\-]{4,}\\.[A-Za-z0-9_\\-]{4,}\\.)|bearer\\s+[A-Za-z0-9._~+/=\\-]{8,}|-----BEGIN [A-Z ]*PRIVATE KEY|[A-Za-z0-9._%+\\-]+@[A-Za-z0-9.\\-]+\\.[A-Za-z]{2,}");

    /** The text with secrets and personal data masked, on one line. Never null. */
    public static String scrub(String text) {
        if (text == null || text.isEmpty()) return "";
        String value = text.length() > INPUT_LIMIT ? cut(text) : text;
        for (Rule rule : FIRST) value = rule.apply(value);
        value = fields(value);
        value = urls(value);
        for (Rule rule : SECRETS) value = rule.apply(value);
        value = cards(value);
        for (Rule rule : PERSONAL) value = rule.apply(value);
        value = quoted(value);
        return value.strip();
    }
    /** scrub(), at most `limit` characters (code points), marked with … when cut. */
    public static String line(String text, int limit) {
        if (limit <= 0) return "";
        String value = scrub(text);
        if (value.codePointCount(0, value.length()) <= limit) return value;
        return value.substring(0, value.offsetByCodePoints(0, limit - 1)) + "…";
    }
    /** Every line scrubbed on its own; the report's fallback when its assembled text still looks like it holds a secret. */
    public static String scrubLines(String text) {
        if (text == null) return "";
        String[] lines = text.split("\n", -1);
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < lines.length; i++) { if (i > 0) out.append('\n'); out.append(scrub(lines[i])); }
        return out.toString();
    }
    /** Whether the text still contains something shaped like a key, token, private key or e-mail address. */
    public static boolean leaks(String text) { return text != null && LEAK.matcher(text).find(); }

    /** Consume entire nested request values, including malformed/truncated ones, without recursive regular expressions. */
    private static String fields(String text) {
        StringBuilder out = new StringBuilder();
        Matcher match = FIELD.matcher(text); int cursor = 0;
        while (match.find(cursor)) {
            out.append(text, cursor, match.end()).append("\"[내용 제거]\"");
            cursor = valueEnd(text, match.end());
            if (cursor >= text.length()) break;
        }
        return out.append(text, cursor, text.length()).toString();
    }
    private static int valueEnd(String text, int at) {
        if (at >= text.length()) return at;
        char first = text.charAt(at);
        if (first == '"' || first == '\'') return quoteEnd(text, at, first);
        if (first != '{' && first != '[') {
            int end = at;
            while (end < text.length() && ",}\r\n".indexOf(text.charAt(end)) < 0) end++;
            return end;
        }
        int depth = 0;
        for (int i = at; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c == '"' || c == '\'') { i = quoteEnd(text, i, c) - 1; continue; }
            if (c == '{' || c == '[') depth++;
            else if ((c == '}' || c == ']') && --depth <= 0) return i + 1;
        }
        return text.length();
    }
    private static int quoteEnd(String text, int at, char close) {
        for (int i = at + 1; i < text.length(); i++) {
            if (text.charAt(i) == '\\') { i++; continue; }
            if (text.charAt(i) == close) return i + 1;
        }
        return text.length();
    }
    /** Quoted filenames, personal names and request strings are unnecessary to diagnose an error. */
    private static String quoted(String text) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < text.length();) {
            char c = text.charAt(i);
            char close = switch (c) { case '"', '\'' -> c; case '‘' -> '’'; case '“' -> '”'; case '「' -> '」'; default -> 0; };
            // Preserve ordinary English contractions such as couldn't.
            if (close == 0 || c == '\'' && i > 0 && Character.isLetterOrDigit(text.charAt(i - 1))) { out.append(c); i++; continue; }
            int end = quoteEnd(text, i, close);
            out.append(c).append('…').append(close); i = end;
        }
        return out.toString();
    }
    /** Cuts long input, then drops a token-like run at the cut so no partial key survives the boundary. */
    private static String cut(String text) {
        String value = text.substring(0, INPUT_LIMIT);
        if (Character.isHighSurrogate(value.charAt(value.length() - 1))) value = value.substring(0, value.length() - 1);
        return value.replaceAll("[A-Za-z0-9_+/=.\\-]+$", "");
    }
    private static String urls(String text) {
        Matcher matcher = URL.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) {
            String host = matcher.group(1).toLowerCase(Locale.ROOT);
            while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
            String kept = KNOWN_HOSTS.contains(host) ? "https://" + host + (matcher.group(2).isEmpty() ? "" : "/…") : "[주소 제거]";
            matcher.appendReplacement(out, Matcher.quoteReplacement(kept));
        }
        matcher.appendTail(out);
        return out.toString();
    }
    private static String cards(String text) {
        Matcher matcher = CARD.matcher(text);
        StringBuffer out = new StringBuffer();
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(luhn(matcher.group()) ? "[카드번호 제거]" : matcher.group()));
        matcher.appendTail(out);
        return out.toString();
    }
    static boolean luhn(String candidate) {
        String digits = candidate.replaceAll("[^0-9]", "");
        if (digits.length() < 13 || digits.length() > 19) return false;
        int sum = 0; boolean twice = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int digit = digits.charAt(i) - '0';
            if (twice) { digit *= 2; if (digit > 9) digit -= 9; }
            sum += digit; twice = !twice;
        }
        return sum % 10 == 0;
    }
}
