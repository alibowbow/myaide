package dev.jun.gyeot.core;

import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Conservative, structured merge scope derived only from original user turns, never model/tool text.
 * History is replayed in order: a question, cancellation, unrelated task or terminal answer closes the
 * scope. A short continuation can retain a still-active scope but cannot create or revive one.
 * Missing targets permit inspection/clarification, never a write. Runtime head/base pins come from GET.
 */
public final class MergeTask {
    public final boolean requested;
    public final String repo, base;
    public final int number;
    /** "머지하고 진행", "병합한 뒤 다음 단계로 진행해 줘": merge first, then continue the current work. */
    private static final String NEXT_STEP = "(?s).*(?:머지|병합)\\s*(?:하고|한\\s*(?:뒤|후|다음)(?:에)?|후(?:에)?)\\s*(?:바로\\s*)?(?:다음\\s*(?:단계|작업)(?:으로|로)?\\s*)?(?:진행|계속)\\s*(?:해\\s*줘|해\\s*주세요|해|하자)?[.!~ ]*";
    /** One standalone number such as "27" or "27번", only inside an explicit merge command; never "3분", "2개", "0.4.77" or "01:30". */
    private static final String BARE_NUMBER = "(?<![0-9A-Za-z가-힣._#/:-])([1-9][0-9]{0,8})(?:\\s*번)?(?:을|를|은|는|이|가)?(?![0-9A-Za-z가-힣.%/:,_-])";
    private MergeTask(boolean requested, String repo, int number, String base) {
        this.requested = requested; this.repo = repo; this.number = number; this.base = base;
    }
    public boolean exact() { return requested && !repo.isEmpty() && number > 0; }
    public String guidance() {
        if (!requested) return " No active explicit merge authorization exists in this turn. Read-only questions and check requests do not authorize merging. Do not call github_merge_pull_request.";
        if (!exact()) return " The user requested a merge but its exact repository and PR number are missing or ambiguous. Inspect read-only if useful, then ask for the exact target; do not select or merge a candidate yourself.";
        return " Active user-authorized merge scope: " + repo + " PR #" + number + (base.isEmpty() ? " into this PR's existing base" : " into " + base)
            + ". First read exactly repos/" + repo + "/pulls/" + number + ". Pin the observed head SHA and base. Never switch targets or refresh a changed head and merge it automatically. Report blockers honestly.";
    }
    public static MergeTask from(JSONArray turns, String configuredRepo) {
        MergeTask active = empty();
        String contextRepo = GitHubTools.repo(configuredRepo), contextBase = "";
        int contextNumber = 0;
        for (int i = 0; turns != null && i < turns.length(); i++) {
            JSONObject turn = turns.optJSONObject(i);
            if (turn == null) continue;
            if (turn.optBoolean("merge_scope_closed")) { active = empty(); contextRepo = GitHubTools.repo(configuredRepo); contextNumber = 0; contextBase = ""; continue; }
            String role = turn.optString("role"), text = clean(turn.optString("content"));
            if (role.equals("assistant")) {
                // Model text may only revoke, never grant authorization or supply a target.
                if (active.requested && !GitHubFunctions.promisesMerge(text)) { active = empty(); contextNumber = 0; contextBase = ""; }
                continue;
            }
            if (!role.equals("user")) continue;
            if (continuation(text)) { if (!active.requested) { contextNumber = 0; contextBase = ""; } continue; }
            boolean direct = direct(text);
            Target target = target(text, direct);
            if (!direct) {
                active = empty(); contextNumber = 0; contextBase = "";
                // A user-named PR can supply context to the very next explicit merge instruction.
                // A later bare continuation still cannot authorize a merge.
                if (safe(text) && target.number > 0 && !target.ambiguous) {
                    contextRepo = target.repo.isEmpty() ? GitHubTools.repo(configuredRepo) : target.repo;
                    contextNumber = target.number; contextBase = target.base;
                } else contextRepo = GitHubTools.repo(configuredRepo);
                continue;
            }
            String repo = target.repo.isEmpty() ? contextRepo : target.repo;
            boolean sameRepo = target.repo.isEmpty() || target.repo.equalsIgnoreCase(contextRepo);
            int number = target.number > 0 ? target.number : sameRepo ? contextNumber : 0;
            String base = target.base.isEmpty() ? (sameRepo ? contextBase : "") : target.base;
            if (target.ambiguous) { repo = ""; number = 0; }
            active = new MergeTask(true, repo, number, base);
            contextRepo = repo; contextNumber = number; contextBase = base;
        }
        return active;
    }
    private static MergeTask empty() { return new MergeTask(false, "", 0, ""); }
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
    private static final class Target { String repo = "", base = ""; int number; boolean ambiguous; }
    private static Target target(String text, boolean command) {
        Target result = new Target();
        Matcher repos = Pattern.compile("(?i)(?<![a-z0-9._/-])(?:https://github\\.com/)?([a-z0-9-]{1,39}/[a-z0-9._-]{1,100})(?:/pull/([1-9][0-9]{0,8}))?").matcher(text);
        while (repos.find()) {
            String repo = repos.group(1).toLowerCase(Locale.ROOT);
            if (!result.repo.isEmpty() && !result.repo.equals(repo)) result.ambiguous = true;
            result.repo = repo;
            if (repos.group(2) != null) setNumber(result, Integer.parseInt(repos.group(2)));
        }
        Matcher numbers = Pattern.compile("(?i)(?:\\bpr\\s*#?\\s*|pull request\\s*#?\\s*|#)([1-9][0-9]{0,8})(?![0-9])(?!\\s*(?:개|건))").matcher(text);
        while (numbers.find()) setNumber(result, Integer.parseInt(numbers.group(1)));
        if (command && result.number == 0) {
            Matcher bare = Pattern.compile(BARE_NUMBER).matcher(text);
            while (bare.find()) setNumber(result, Integer.parseInt(bare.group(1)));
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
    private static void setNumber(Target target, int number) {
        if (target.number != 0 && target.number != number) target.ambiguous = true;
        target.number = number;
    }
}
