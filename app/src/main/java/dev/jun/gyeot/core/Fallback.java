package dev.jun.gyeot.core;

import java.util.List;
import java.util.Locale;
import java.util.function.BooleanSupplier;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 막히면 다음 모델로 (두뇌 배분, the plan 준 approved: "막히면 다음 모델로 넘겨요"): which failures another model can help with,
 * the one short note an answer carries when another model answered ("Claude 한도 → GPT-6.1 Terra로 답했어요"), and the one
 * hop itself. A hop happens once, never in a loop, and only for a failure of the model or its account (no key, the account
 * refused, a limit reached, the server failing, a timeout, the model gone); never for the user's own stop, a content
 * refusal, a request the provider found malformed, or anything not recognised. Pure Java.
 */
public final class Fallback {
    private Fallback() {}
    /** Why the chosen model could not answer, as far as another model is concerned. NONE: another model must not be asked. */
    public enum Why {
        NONE(""), NO_KEY("연결 없음"), ACCOUNT("계정 거절"), LIMIT("한도"), SERVER("서버 오류"), TIMEOUT("시간 초과"), GONE("모델 없음");
        public final String label;
        Why(String label) { this.label = label; }
    }

    /** A content refusal: the model or its classifier declined what was asked. Another model is not asked to answer it instead. */
    private static final Pattern REFUSED = Pattern.compile("답하지 않았어요|답변을 거절|답을 거절|거절했어요\\s*\\(안전|안전 정책|콘텐츠 정책|content[ _]policy|content[ _]filter|safety|prohibited|\\brefus(al|ed to answer)\\b|blocked by");
    private static final Pattern HTTP = Pattern.compile("(?i)http\\s*(\\d{3})");
    private static final Pattern LIMITS = Pattern.compile("사용 한도|사용량 한도|한도에 (걸|도달)|한도를 기다|무료 한도|할당량|크레딧이 부족|rate.?limit|usage limit|quota|too many requests|credit balance|insufficient_quota");
    private static final Pattern NO_KEYS = Pattern.compile("키가 없어요|키를 먼저|키를 넣|연결되지 않았|연결을 먼저|no api key|api key (is )?missing");
    private static final Pattern ACCOUNTS = Pattern.compile("키가 올바르지|키 문제|연결이 만료|만료됐어요|로그인이 (풀|만료)|권한이 (없|허용되지)|unauthorized|invalid[ _]api[ _]key|authentication|permission[ _]denied|forbidden");
    private static final Pattern GONES = Pattern.compile("모델을 찾지 못|모델을 쓸 수 없어요|모델을 사용할 수 없어요|model_not_found|model not found|does not exist|no longer (available|supported)|has been (deprecated|retired)");
    private static final Pattern SERVERS = Pattern.compile("붐벼요|서버에 일시적|서버가 답하지|서버 오류|overloaded|service unavailable|internal server error|bad gateway|server_error|api_error|upstream");
    private static final Pattern TIMEOUTS = Pattern.compile("timed? ?out|시간 초과|응답 시간이 지났");

    /** Why error stopped the model, for a hop: NONE for a stop, a refusal, a malformed request, or a failure not recognised. */
    public static Why why(Throwable error) {
        if (error == null || cancelled(error) || refused(error)) return Why.NONE;
        for (Throwable e = error; e != null; e = e.getCause()) {
            // A request the app itself found wrong (an option the engine cannot carry, a file it cannot take): not the model's failure.
            if (e instanceof IllegalArgumentException) return Why.NONE;
            if (e instanceof ModelLimit) return Why.LIMIT;
            if (e instanceof ProviderError provider) return status(provider.status);
            if (e instanceof java.net.SocketTimeoutException) return Why.TIMEOUT;
            // Offline: another company's model is just as far away.
            if (e instanceof java.net.UnknownHostException) return Why.NONE;
            if (e instanceof java.net.ConnectException) return Why.SERVER;
        }
        String said = words(error);
        Matcher http = HTTP.matcher(said);
        if (http.find()) return status(Integer.parseInt(http.group(1)));
        if (LIMITS.matcher(said).find()) return Why.LIMIT;
        if (NO_KEYS.matcher(said).find()) return Why.NO_KEY;
        if (ACCOUNTS.matcher(said).find()) return Why.ACCOUNT;
        if (GONES.matcher(said).find()) return Why.GONE;
        if (SERVERS.matcher(said).find()) return Why.SERVER;
        if (TIMEOUTS.matcher(said).find()) return Why.TIMEOUT;
        return Why.NONE;
    }
    /** What an HTTP status means for a hop: 401/403 the account, 402/429 a limit, 404/410 the model gone, 408/504 a timeout, other 5xx the server. */
    public static Why status(int status) {
        return switch (status) {
            case 401, 403 -> Why.ACCOUNT;
            case 402, 429 -> Why.LIMIT;
            case 404, 410 -> Why.GONE;
            case 408, 504 -> Why.TIMEOUT;
            default -> status >= 500 && status <= 599 ? Why.SERVER : Why.NONE;
        };
    }
    /** The user (or the app on their behalf) stopped it: a CancellationException or an interrupt anywhere in the chain. */
    public static boolean cancelled(Throwable error) {
        for (Throwable e = error; e != null; e = e.getCause())
            if (e instanceof java.util.concurrent.CancellationException || e instanceof InterruptedException) return true;
        return false;
    }
    /** The model declined what was asked (a safety or content refusal), anywhere in the chain. */
    public static boolean refused(Throwable error) { return error != null && REFUSED.matcher(words(error)).find(); }
    /** Every message in the chain, lower case. */
    private static String words(Throwable error) {
        StringBuilder all = new StringBuilder();
        for (Throwable e = error; e != null && all.length() < 4000; e = e.getCause()) if (e.getMessage() != null) all.append(e.getMessage()).append('\n');
        return all.toString().toLowerCase(Locale.ROOT);
    }

    /** "Claude 한도 → GPT-6.1 Terra로 답했어요": who could not, why, and who answered instead. */
    public static String note(Why why, String from, String to) {
        return (from == null ? "" : from.strip()) + (why == null || why.label.isEmpty() ? "" : " " + why.label) + " → " + euro(to == null ? "" : to.strip()) + " 답했어요";
    }
    /** The word with 으로 or 로 as it is read aloud: Hangul by its last consonant, digits and Latin letters as Koreans read them. */
    public static String euro(String word) {
        if (word == null || word.isEmpty()) return "";
        String trimmed = word.replaceAll("[\\s)\\]]+$", "");
        if (trimmed.isEmpty()) return word + "로";
        char last = trimmed.charAt(trimmed.length() - 1);
        if (last >= 0xAC00 && last <= 0xD7A3) { int f = (last - 0xAC00) % 28; return word + (f > 0 && f != 8 ? "으로" : "로"); }
        // 0 영, 3 삼, 6 육 end in a consonant other than ㄹ; 1 일, 7 칠, 8 팔 end in ㄹ; the rest in a vowel.
        if (last >= '0' && last <= '9') return word + ("036".indexOf(last) >= 0 ? "으로" : "로");
        if (Character.isLetter(last) && last < 0x80) {
            // A lone capital or an acronym is read by its letters' names: only 엠 (M) and 엔 (N) end in a consonant other than ㄹ.
            boolean letters = trimmed.matches(".*(?:^|[^a-z])[A-Z]{1,5}$");
            char l = Character.toLowerCase(last);
            if (letters) return word + (l == 'm' || l == 'n' ? "으로" : "로");
            String tail = trimmed.toLowerCase(Locale.ROOT);
            if (tail.endsWith("ng") || l == 'm' || l == 'n' || l == 'k' || l == 'p' || l == 't' && !tail.endsWith("te")) return word + "으로";
            return word + "로";
        }
        return word + "로";
    }

    /** One answer and who gave it: the first choice, or the one after it with the note that says why. */
    public record Answer<C, T>(T value, C used, String note) {
        public boolean hopped() { return note != null && !note.isEmpty(); }
    }
    public interface Attempt<C, T> { T run(C choice) throws Exception; }
    /**
     * Asks chain's first choice; when it fails in a way another can help with (why() is not NONE), while fresh says nothing
     * of it reached the user or ran (no words streamed, no tool run), and guard lets it go on (it throws when the user
     * stopped), asks the second choice once. Never a third: a second failure is thrown, naming both. who names a choice as
     * the one that could not ("Claude"); label as the one that answered ("GPT-6.1 Terra").
     */
    public static <C, T> Answer<C, T> once(List<C> chain, Function<C, String> who, Function<C, String> label, Attempt<C, T> attempt, BooleanSupplier fresh, Runnable guard) throws Exception {
        if (chain == null || chain.isEmpty()) throw new IllegalStateException("답할 모델이 없어요.");
        C first = chain.get(0);
        try { return new Answer<>(attempt.run(first), first, ""); }
        catch (Exception failed) {
            Why why = why(failed);
            if (chain.size() < 2 || why == Why.NONE || fresh != null && !fresh.getAsBoolean()) throw failed;
            if (guard != null) guard.run();
            C next = chain.get(1);
            String note = note(why, who.apply(first), label.apply(next));
            try { return new Answer<>(attempt.run(next), next, note); }
            catch (Exception again) {
                if (cancelled(again)) throw again;
                IllegalStateException both = new IllegalStateException(who.apply(first) + " " + why.label + " → " + label.apply(next) + "도 답하지 못했어요. " + (again.getMessage() == null ? "" : again.getMessage()), again);
                both.addSuppressed(failed);
                throw both;
            }
        }
    }
}
