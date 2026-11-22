package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conservative, structured merge scope derived only from original user turns, never model/tool text.
 * History is replayed in order: a question, cancellation, unrelated task or terminal answer closes the
 * scope. A short continuation can retain a still-active scope but cannot create or revive one.
 * Missing targets permit inspection/clarification, never a write. Runtime head/base pins come from GET.
 * Several PRs named as one plain list ("#76이랑 #77", "#76, #77", "PR 76 and 77") are merged in that order, one at a time;
 * numbers with other words between them ("PR 27 머지해 줘 (fixes #12)") stay ambiguous.
 */
public final class MergeTask {
    public final boolean requested;
    public final String repo, base;
    /** The first PR to merge (0 for none); numbers: all of them, in the order named. */
    public final int number;
    public final List<Integer> numbers;
    /** The most PRs one request may name. */
    static final int MOST = 10;
    /** What may stand between the numbers of one list: spaces, commas, "이랑", "과", "and", "번", another "PR" or "#" before the next. */
    private static final Pattern LIST_GAP = Pattern.compile("(?i)(?:[\\s,，·/&+]|번|이랑|랑|하고|그리고|및|와|과|and)*");
    /** "머지하고 진행", "병합한 뒤 다음 단계로 진행해 줘": merge first, then continue the current work. */
    private static final String NEXT_STEP = "(?s).*(?:머지|병합)\\s*(?:하고|한\\s*(?:뒤|후|다음)(?:에)?|후(?:에)?)\\s*(?:바로\\s*)?(?:다음\\s*(?:단계|작업)(?:으로|로)?\\s*)?(?:진행|계속)\\s*(?:해\\s*줘|해\\s*주세요|해|하자)?[.!~ ]*";
    /** One standalone number such as "27" or "27번", only inside an explicit merge command; never "3분", "2개", "0.4.77" or "01:30". */
    private static final String BARE_NUMBER = "(?<![0-9A-Za-z가-힣._#/:-])([1-9][0-9]{0,8})(?:\\s*번)?(?:이랑|랑|하고|과|와|을|를|은|는|이|가)?(?![0-9A-Za-z가-힣.%/:_-])(?!,\\d)";
    private MergeTask(boolean requested, String repo, List<Integer> numbers, String base) {
        this.requested = requested; this.repo = repo; this.numbers = List.copyOf(numbers); this.number = numbers.isEmpty() ? 0 : numbers.get(0); this.base = base;
    }
    public boolean exact() { return requested && !repo.isEmpty() && number > 0; }
    public String guidance() {
        if (!requested) return " No active explicit merge authorization exists in this turn. Read-only questions and check requests do not authorize merging. Do not call github_merge_pull_request.";
        if (!exact()) return " The user requested a merge but its exact repository and PR number are missing or ambiguous. Inspect read-only if useful, then ask for the exact target; do not select or merge a candidate yourself.";
        if (numbers.size() > 1) {
            StringBuilder list = new StringBuilder(); for (int n : numbers) list.append(list.length() == 0 ? "#" : ", #").append(n);
            return " Active user-authorized merge scope: " + repo + " PRs " + list + ", in this order" + (base.isEmpty() ? ", each into its existing base" : ", into " + base)
                + ". Merge them one at a time, starting with #" + number + ": read exactly repos/" + repo + "/pulls/{number} for the current one, pin its head SHA and base, check it, merge it;"
                + " the merge result names the next PR (next_pr). Stop at the first that cannot be merged and report which were merged and which were not."
                + " Never switch targets or refresh a changed head and merge it automatically. Report blockers honestly.";
        }
        return " Active user-authorized merge scope: " + repo + " PR #" + number + (base.isEmpty() ? " into this PR's existing base" : " into " + base)
            + ". First read exactly repos/" + repo + "/pulls/" + number + ". Pin the observed head SHA and base. Never switch targets or refresh a changed head and merge it automatically. Report blockers honestly.";
    }
    public static MergeTask from(JSONArray turns, String configuredRepo) {
        MergeTask active = empty();
        String contextRepo = GitHubTools.repo(configuredRepo), contextBase = "";
        List<Integer> contextNumbers = List.of();
        for (int i = 0; turns != null && i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i);
            if (turn == null) continue;
            if (turn.optBoolean("merge_scope_closed")) { active = empty(); contextRepo = GitHubTools.repo(configuredRepo); contextNumbers = List.of(); contextBase = ""; continue; }
            String role = turn.optString("role"), text = clean(turn.optString("content"));
            if (role.equals("assistant")) {
                // Model text may only revoke, never grant authorization or supply a target.
                if (active.requested && !GitHubFunctions.promisesMerge(text)) { active = empty(); contextNumbers = List.of(); contextBase = ""; }
                continue;
            }
            if (!role.equals("user")) continue;
            if (continuation(text)) { if (!active.requested) { contextNumbers = List.of(); contextBase = ""; } continue; }
            boolean direct = direct(text);
            Target target = target(text, direct);
            if (!direct) {
                active = empty(); contextNumbers = List.of(); contextBase = "";
                // A user-named PR can supply context to the very next explicit merge instruction.
                // A later bare continuation still cannot authorize a merge.
                if (safe(text) && !target.numbers.isEmpty() && !target.ambiguous) {
                    contextRepo = target.repo.isEmpty() ? GitHubTools.repo(configuredRepo) : target.repo;
                    contextNumbers = target.numbers; contextBase = target.base;
                } else contextRepo = GitHubTools.repo(configuredRepo);
                continue;
            }
            String repo = target.repo.isEmpty() ? contextRepo : target.repo;
            boolean sameRepo = target.repo.isEmpty() || target.repo.equalsIgnoreCase(contextRepo);
            List<Integer> numbers = !target.numbers.isEmpty() ? target.numbers : sameRepo ? contextNumbers : List.of();
            String base = target.base.isEmpty() ? (sameRepo ? contextBase : "") : target.base;
            if (target.ambiguous) { repo = ""; numbers = List.of(); }
            active = new MergeTask(true, repo, numbers, base);
            contextRepo = repo; contextNumbers = numbers; contextBase = base;
        }
        return active;
    }
    private static MergeTask empty() { return new MergeTask(false, "", List.of(), ""); }
    private static String clean(String text) {
        return text.replaceFirst("\\s*\\[지금 시각: [^\\]]*\\]\\s*$", "").strip();
    }
    private static boolean safe(String text) {
        return text.length() <= 500 && !text.contains("\n") && !text.matches("(?s).*[\"'`“”‘’].*")
            && !text.matches("(?is).*(말고|하지|마세요|취소|중지|멈춰|설명|방법|어떻게|번역|예시|테스트|통과하면|이미지|사진|그림|영상|셀|표를|문서|don.t|do not|cancel|stop|explain|how to|if |after |when |unless |until |only |provided |assuming |condition|경우|조건|통과.*면).*");
    }
    static boolean direct(String text) {
        text = text.toLowerCase(Locale.ROOT);
        if (text.matches("(?s).*(?:\\bor\\b|또는|아니면).*") ) return false;
        if (!safe(text) || text.matches("(?is).*(가능|할 수|[?？]|can you|could you|whether).*") ) return false;
        return text.matches("(?s).*(?:머지|병합)(?:을)?\\s*(?:해\\s*줘|해\\s*주세요|해\\s*주세용|해주세요|해|하자|(?:진행|짓행)\\s*(?:해\\s*줘)?)[.!~ ]*")
            || text.matches(NEXT_STEP)
            || text.matches("(?:please\\s+)?merge\\s+(?:this\\s+|the\\s+)?(?:pr\\b|pull request\\b|https://github\\.com/).*");
    }
    private static boolean continuation(String text) {
        text = text.toLowerCase(Locale.ROOT);
        return text.matches("(?:ㄱㄱ|고고|진행|진행해|바로\\s*진행(?:해)?|아니\\s*그냥\\s*바로\\s*진행|계속해|진행해\\s*줘|go ahead|continue)[.!~ ]*");
    }
    private static final class Target { String repo = "", base = ""; final List<Integer> numbers = new ArrayList<>(); boolean ambiguous; int lastEnd = -1; }
    private static Target target(String text, boolean command) {
        Target result = new Target();
        Matcher repos = Pattern.compile("(?i)(?<![a-z0-9._/-])(?:https://github\\.com/)?([a-z0-9-]{1,39}/[a-z0-9._-]{1,100})(?:/pull/([1-9][0-9]{0,8}))?").matcher(text);
        while (repos.find()) {
            String repo = repos.group(1).toLowerCase(Locale.ROOT);
            if (!result.repo.isEmpty() && !result.repo.equals(repo)) result.ambiguous = true;
            result.repo = repo;
            if (repos.group(2) != null) addNumber(result, text, repos.start(), repos.end(), Integer.parseInt(repos.group(2)));
        }
        Matcher numbers = Pattern.compile("(?i)(?:\\bpr\\s*#?\\s*|pull request\\s*#?\\s*|#)([1-9][0-9]{0,8})(?![0-9])(?!\\s*(?:개|건))").matcher(text);
        while (numbers.find()) addNumber(result, text, numbers.start(), numbers.end(), Integer.parseInt(numbers.group(1)));
        if (command && result.numbers.isEmpty()) {
            Matcher bare = Pattern.compile(BARE_NUMBER).matcher(text);
            while (bare.find()) addNumber(result, text, bare.start(), bare.end(), Integer.parseInt(bare.group(1)));
        }
        if (text.contains("메인")) result.base = "main";
        Matcher defaultBase = Pattern.compile("(?i)\\b(main|master)\\b").matcher(text);
        if (defaultBase.find()) result.base = defaultBase.group(1);
        Matcher base = Pattern.compile("(?i)(?:into|to)\\s+([a-z0-9._/-]+)\\s*$").matcher(text);
        if (base.find()) result.base = base.group(1);
        Matcher koreanBase = Pattern.compile("(?i)(?<![a-z0-9._/-])([a-z0-9._/-]+)(?:\\s*브랜치)?(?:에|으로|로)\\s*(?:병합|머지)").matcher(text);
        if (koreanBase.find()) result.base = koreanBase.group(1);
        return result;
    }
    /**
     * One PR number found at [start, end): a further one joins the list only when nothing but a list's joining words stands
     * between it and the one before; otherwise the numbers are ambiguous. The same number again changes nothing.
     */
    private static void addNumber(Target target, String text, int start, int end, int number) {
        if (target.numbers.contains(number)) { target.lastEnd = Math.max(target.lastEnd, end); return; }
        if (!target.numbers.isEmpty() && !(start >= target.lastEnd && LIST_GAP.matcher(text.substring(target.lastEnd, start)).matches())) target.ambiguous = true;
        if (target.numbers.size() >= MOST) target.ambiguous = true; else target.numbers.add(number);
        target.lastEnd = end;
    }
}
