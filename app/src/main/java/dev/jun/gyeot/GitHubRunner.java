package dev.jun.gyeot;

import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.GitHubTools;
import dev.jun.gyeot.core.MergeTask;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * Runs the github_ functions (core/GitHubFunctions) for 작업창 with the user's token, straight to GitHub's REST API: the
 * token stays on the phone. Changes need the user's permission (GitHubTools.write). Direct file edits never touch the
 * default branch; an explicitly requested PR merge uses GitHub's merge endpoint and its repository rules.
 * Every call answers with a JSON object for the engine, {ok:false, error} when it failed. Blocking; network thread only.
 */
final class GitHubRunner extends WorkTools {
    private final GitHubTools github;
    private final AtomicReference<Runnable> cancel;
    /** The user stopped the turn: no more GitHub calls. */
    private final java.util.function.BooleanSupplier stopped;
    /** Default branches looked up in this turn, by repository. */
    private final Map<String, String> defaults = new LinkedHashMap<>();

    private MergeTask mergeTask = MergeTask.from(null, "");
    private String reviewedHead = "", reviewedBase = "";
    private boolean mergeAttempted;
    private JSONObject mergeOutcome;
    /** A terminal PR/check observation, derived from GitHub data rather than model prose. */
    private JSONObject mergeInspectionOutcome;
    /** Several PRs in one request (MergeTask.numbers): which one is current, and those merged before it. */
    private int mergeIndex;
    private final JSONArray mergedSoFar = new JSONArray();

    GitHubRunner(GitHubTools github, AtomicReference<Runnable> cancel, java.util.function.BooleanSupplier stopped) { this.github = github; this.cancel = cancel; this.stopped = stopped; }
    boolean write() { return github.write; }
    @Override JSONArray declarations() { return GitHubFunctions.declarations(github.write); }
    boolean inspectMerge(JSONArray turns) {
        MergeTask next = MergeTask.from(turns, github.repo);
        if (!mergeTask.repo.equalsIgnoreCase(next.repo) || !mergeTask.numbers.equals(next.numbers) || !mergeTask.base.equals(next.base)) {
            reviewedHead = ""; reviewedBase = ""; mergeInspectionOutcome = null; mergeIndex = 0;
            while (mergedSoFar.length() > 0) mergedSoFar.remove(0);
        }
        mergeTask = next;
        return github.write && mergeTask.requested;
    }
    String instructions() { return GitHubFunctions.instructions(github.write, github.repo) + mergeTask.guidance(); }
    boolean mergeNeedsAction() { return github.write && mergeTask.exact() && !mergeAttempted && mergeOutcome == null && mergeInspectionOutcome == null; }
    JSONObject mergeInspectionResult() { return mergeInspectionOutcome; }

    /** One call: the function's name and its arguments as the engine sent them (a JSON object or its text). Throws CancellationException once the user stopped the turn. */
    JSONObject run(String name, Object arguments) {
        if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException("작업을 멈췄어요.");
        try {
            JSONObject args = arguments instanceof JSONObject object ? object : new JSONObject(arguments == null || String.valueOf(arguments).isBlank() ? "{}" : String.valueOf(arguments));
            if (GitHubFunctions.changes(name) && !github.write) return GitHubFunctions.failure("changes are not allowed: the user turns them on in 설정 → 커넥터 → GitHub");
            return switch (name) {
                case GitHubFunctions.LIST -> list(args);
                case GitHubFunctions.READ -> read(args);
                case GitHubFunctions.SEARCH -> search(args);
                case GitHubFunctions.GET -> get(args);
                case GitHubFunctions.EDIT -> edit(args);
                case GitHubFunctions.WRITE -> writeFile(args);
                case GitHubFunctions.PULL -> pull(args);
                case GitHubFunctions.MERGE -> { JSONObject result = merge(args); mergeOutcome = result; yield advance(result); }
                default -> GitHubFunctions.failure("unknown function " + name);
            };
        } catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception e) { return GitHubFunctions.failure(e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage()); }
    }

    private JSONObject list(JSONObject args) throws Exception {
        String repo = GitHubFunctions.repo(args), path = GitHubFunctions.path(args.optString("path"), false);
        Reply reply = call("GET", GitHubFunctions.contents(repo, path, args.optString("ref")), null, null);
        if (reply.json instanceof JSONArray items) return ok().put("path", path).put("entries", GitHubFunctions.listing(items));
        return GitHubFunctions.failure(path + " is a file; read it with github_read");
    }
    private JSONObject read(JSONObject args) throws Exception {
        String repo = GitHubFunctions.repo(args), path = GitHubFunctions.path(args.optString("path"), true);
        String content = raw(repo, path, args.optString("ref"));
        JSONObject part = GitHubFunctions.lines(content, args.optInt("start_line", 1), args.optInt("end_line", 0));
        return ok().put("path", path).put("total_lines", part.getInt("total_lines")).put("start_line", part.getInt("start")).put("end_line", part.getInt("end"))
            .put("more_lines", part.getBoolean("more")).put("text", part.getString("text"));
    }
    private JSONObject search(JSONObject args) throws Exception {
        String repo = GitHubFunctions.repo(args), query = args.optString("query").strip();
        if (query.isEmpty()) return GitHubFunctions.failure("query is empty");
        if (!args.optString("path").isBlank()) {
            String path = GitHubFunctions.path(args.optString("path"), true), content = raw(repo, path, args.optString("ref"));
            return ok().put("source", "direct_file").put("path", path).put("matches", GitHubFunctions.findLines(content, query));
        }
        Reply reply = call("GET", GitHubFunctions.API + "/search/code?per_page=30&q=" + java.net.URLEncoder.encode(query + " repo:" + repo, StandardCharsets.UTF_8), null, null);
        JSONArray matches = GitHubFunctions.matches((JSONObject) reply.json);
        return ok().put("matches", matches).put("source", "github_index").put("note", matches.length() == 0 ? "GitHub 검색 인덱스에 없을 수 있어요. 파일 경로를 알면 path와 ref를 넣어 실제 파일에서 query 문자열을 검색하세요. 0건이 소스에 없다는 뜻은 아닙니다." : "");
    }
    private JSONObject get(JSONObject args) throws Exception {
        String path = GitHubFunctions.getPath(args.optString("path"));
        String route = path.split("\\?", 2)[0], repo = GitHubFunctions.API + "/repos/" + mergeTask.repo;
        boolean prRead = mergeTask.exact() && route.equalsIgnoreCase(repo + "/pulls/" + current());
        boolean statusRead = mergeTask.exact() && !reviewedHead.isEmpty() && route.equalsIgnoreCase(repo + "/commits/" + reviewedHead + "/status");
        boolean checksRead = mergeTask.exact() && !reviewedHead.isEmpty() && route.equalsIgnoreCase(repo + "/commits/" + reviewedHead + "/check-runs");
        Reply reply;
        try { reply = call("GET", path, null, null); }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception e) {
            if (prRead || statusRead || checksRead) mergeInspectionOutcome = GitHubFunctions.failure("병합 확인 중 GitHub 조회에 실패했어요. " + e.getMessage());
            throw e;
        }
        String already = "";
        if (prRead) {
            observeMergePr(reply.json instanceof JSONObject pr ? pr : new JSONObject());
            // Already merged, with more to go: on to the next one in the list.
            if (mergeInspectionOutcome != null && mergeInspectionOutcome.optBoolean("merged") && mergeIndex + 1 < mergeTask.numbers.size())
                already = advance(mergeInspectionOutcome).optString("note");
        }
        if (statusRead && reply.json instanceof JSONObject status && java.util.Set.of("pending", "failure", "error").contains(status.optString("state"))
                && (status.optInt("total_count") > 0 || status.optJSONArray("statuses") != null && status.getJSONArray("statuses").length() > 0))
            mergeInspectionOutcome = GitHubFunctions.failure("PR의 커밋 검사가 아직 성공하지 않았어요. GitHub 검사 상태: " + status.optString("state"));
        if (checksRead && reply.json instanceof JSONObject checks && checks.optJSONArray("check_runs") != null) {
            JSONArray runs = checks.getJSONArray("check_runs");
            for (int i = 0; i < runs.length(); i++) {
                JSONObject run = runs.optJSONObject(i);
                if (run != null && (!"completed".equals(run.optString("status")) || !java.util.Set.of("success", "neutral", "skipped").contains(run.optString("conclusion"))))
                    mergeInspectionOutcome = GitHubFunctions.failure("PR의 GitHub 검사가 진행 중이거나 성공하지 않았어요. 검사 결과를 확인한 뒤 다시 요청해 주세요.");
            }
        }
        JSONObject answer = ok().put("data", GitHubFunctions.clip(String.valueOf(GitHubFunctions.compactGet(route, reply.json))));
        return already.isEmpty() ? answer : answer.put("note", already);
    }
    /** The PR of the request now being merged (the first, then each next one after a merge). */
    private int current() { return mergeTask.numbers.isEmpty() ? 0 : mergeTask.numbers.get(Math.min(mergeIndex, mergeTask.numbers.size() - 1)); }
    /**
     * One PR of a list is done. Merged with more to go: it is kept, the next becomes current with a fresh review, and the
     * result names it. Not merged (or the last one): the result says which were merged before and which were not.
     */
    private JSONObject advance(JSONObject result) {
        if (mergeTask.numbers.size() < 2) return result;
        try {
            int done = current();
            if (result.optBoolean("merged") && mergeIndex + 1 < mergeTask.numbers.size()) {
                mergedSoFar.put(done); mergeIndex++;
                reviewedHead = ""; reviewedBase = ""; mergeAttempted = false; mergeOutcome = null; mergeInspectionOutcome = null;
                return result.put("next_pr", current()).put("note", "PR #" + done + " 병합 완료. 다음 PR #" + current() + "도 같은 방법으로: 먼저 repos/" + mergeTask.repo + "/pulls/" + current() + "를 읽고 확인한 뒤 병합하세요.");
            }
            JSONArray merged = new JSONArray(mergedSoFar.toString()), left = new JSONArray();
            if (result.optBoolean("merged")) merged.put(done);
            for (int i = mergeIndex + (result.optBoolean("merged") ? 1 : 0); i < mergeTask.numbers.size(); i++) left.put(mergeTask.numbers.get(i));
            result.put("merged_prs", merged).put("not_merged_prs", left);
            if (left.length() > 0) result.put("note", "PR #" + done + "에서 멈췄어요. 그 뒤의 PR은 병합하지 않았어요: " + left);
            return result;
        } catch (org.json.JSONException impossible) { return result; }
    }
    private void observeMergePr(JSONObject pr) throws Exception {
        JSONObject head = pr.optJSONObject("head"), base = pr.optJSONObject("base");
        if (head == null || base == null || !head.optString("sha").matches("[0-9a-fA-F]{40}") || base.optString("ref").isBlank()) {
            mergeInspectionOutcome = GitHubFunctions.failure("PR의 커밋과 대상 브랜치를 확인하지 못했어요."); return;
        }
        String sha = head.optString("sha").toLowerCase(java.util.Locale.ROOT), target = base.optString("ref");
        if ((!mergeTask.base.isEmpty() && !mergeTask.base.equals(target)) || (!reviewedHead.isEmpty() && (!reviewedHead.equals(sha) || !reviewedBase.equals(target)))) {
            mergeInspectionOutcome = GitHubFunctions.failure("PR의 커밋 또는 대상 브랜치가 승인한 대상과 달라요. 변경 내용을 확인한 뒤 다시 요청해 주세요."); return;
        }
        if (reviewedHead.isEmpty()) { reviewedHead = sha; reviewedBase = target; }
        if (pr.optBoolean("merged")) {
            mergeInspectionOutcome = pr.optString("merge_commit_sha").matches("[0-9a-fA-F]{40}")
                ? merged(pr, current(), sha, target, "", true)
                : GitHubFunctions.failure("PR은 병합됨으로 표시되지만 병합 커밋을 확인하지 못했어요.").put("outcome", "unknown");
        } else if (!"open".equals(pr.optString("state"))) mergeInspectionOutcome = GitHubFunctions.failure("닫힌 PR은 병합할 수 없어요. PR 상태를 확인하세요.");
        else if (pr.optBoolean("draft")) mergeInspectionOutcome = GitHubFunctions.failure("아직 초안인 PR이에요. 검토 준비 상태로 바꾼 뒤 병합하세요.");
        else if (Boolean.FALSE.equals(pr.opt("mergeable")) || "dirty".equals(pr.optString("mergeable_state"))) mergeInspectionOutcome = GitHubFunctions.failure("병합 충돌이 있어요. 작업 브랜치에서 충돌을 해결한 뒤 다시 요청해 주세요.");
        else if ("blocked".equals(pr.optString("mergeable_state"))) mergeInspectionOutcome = GitHubFunctions.failure("PR의 검사·리뷰 조건을 먼저 확인해야 해요. GitHub 병합 상태: blocked");
    }
    private JSONObject edit(JSONObject args) throws Exception {
        String repo = GitHubFunctions.repo(args), path = GitHubFunctions.path(args.optString("path"), true), branch = working(repo, args.optString("branch"), args.optString("base"));
        String madeFrom = createdFrom;
        JSONObject meta = (JSONObject) call("GET", GitHubFunctions.contents(repo, path, branch), null, null).json;
        String before = raw(repo, path, branch);
        String after = GitHubFunctions.apply(before, args.optJSONArray("edits"));
        if (after.equals(before)) return GitHubFunctions.failure("the edits change nothing");
        JSONObject commit = put(repo, path, branch, after, meta.optString("sha"), args.optString("message", "Update " + path));
        JSONObject done = ok().put("branch", branch).put("path", path).put("commit", commit.optJSONObject("commit") == null ? "" : commit.optJSONObject("commit").optString("html_url"))
            .put("note", "Committed. Open a pull request with github_pull_request when the work is done.");
        if (!madeFrom.isEmpty()) done.put("created_from", madeFrom);
        return done;
    }
    private JSONObject writeFile(JSONObject args) throws Exception {
        String repo = GitHubFunctions.repo(args), path = GitHubFunctions.path(args.optString("path"), true), branch = working(repo, args.optString("branch"), args.optString("base"));
        String madeFrom = createdFrom, sha = "";
        Reply existing = call("GET", GitHubFunctions.contents(repo, path, branch), null, null, true);
        if (existing.status < 300 && existing.json instanceof JSONObject meta) sha = meta.optString("sha");
        JSONObject commit = put(repo, path, branch, args.optString("content"), sha, args.optString("message", (sha.isEmpty() ? "Add " : "Update ") + path));
        JSONObject done = ok().put("branch", branch).put("path", path).put("created", sha.isEmpty()).put("commit", commit.optJSONObject("commit") == null ? "" : commit.optJSONObject("commit").optString("html_url"));
        if (!madeFrom.isEmpty()) done.put("created_from", madeFrom);
        return done;
    }
    private JSONObject pull(JSONObject args) throws Exception {
        String repo = GitHubFunctions.repo(args), head = GitHubFunctions.branch(args.optString("head"));
        String base = args.optString("base").isBlank() ? defaultBranch(repo) : GitHubFunctions.branch(args.optString("base"));
        JSONObject body = new JSONObject().put("title", args.optString("title", head)).put("head", head).put("base", base).put("body", args.optString("body"));
        JSONObject made = (JSONObject) call("POST", GitHubFunctions.API + "/repos/" + repo + "/pulls", body.toString(), "application/json").json;
        return ok().put("number", made.optInt("number")).put("url", made.optString("html_url"));
    }
    /** App-owned proposal application: one new branch/ref, never an update to an existing branch. */
    JSONObject commitFiles(String repo, String branch, JSONArray files, String message) throws Exception {
        if (!github.write) return GitHubFunctions.failure("GitHub 쓰기 허용이 필요해요.");
        repo = GitHubFunctions.repo(new JSONObject().put("repo", repo)); branch = GitHubFunctions.branch(branch);
        if (!branch.matches("team/[a-f0-9]{32}") || files.length() == 0 || files.length() > 64) return GitHubFunctions.failure("팀 작업 브랜치와 파일 목록을 확인해 주세요.");
        String base = defaultBranch(repo), api = GitHubFunctions.API + "/repos/" + repo;
        JSONObject reference = (JSONObject)call("GET", api + "/git/ref/heads/" + GitHubFunctions.encodePath(base), null, null).json;
        String parent = reference.getJSONObject("object").getString("sha");
        if (!parent.matches("[a-fA-F0-9]{40}")) throw new IllegalStateException("기준 커밋을 확인하지 못했어요.");
        JSONObject commit = (JSONObject)call("GET", api + "/git/commits/" + parent, null, null).json;
        String baseTree = commit.getJSONObject("tree").getString("sha"); JSONArray entries = new JSONArray();
        if (!baseTree.matches("[a-fA-F0-9]{40}")) throw new IllegalStateException("기준 파일 트리를 확인하지 못했어요.");
        JSONObject treeListing = (JSONObject)call("GET", api + "/git/trees/" + baseTree + "?recursive=1", null, null).json;
        Map<String,String> modes = new LinkedHashMap<>(); JSONArray nodes = treeListing.optJSONArray("tree");
        for (int i = 0; nodes != null && i < nodes.length(); i++) { JSONObject node = nodes.optJSONObject(i); if (node != null) modes.put(node.optString("path"), node.optString("mode")); }
        java.util.Set<String> paths = new java.util.HashSet<>();
        for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.getJSONObject(i); String path = GitHubFunctions.path(file.optString("path"), true), content;
            if (!paths.add(path)) throw new IllegalArgumentException("같은 파일의 제안이 겹쳐요.");
            String mode = "100644";
            if ("edit".equals(file.optString("mode"))) {
                mode = modes.getOrDefault(path, "");
                if (!mode.equals("100644") && !mode.equals("100755")) throw new IllegalStateException("편집할 일반 파일의 권한을 확인하지 못했어요: " + path);
                content = GitHubFunctions.apply(raw(repo, path, parent), file.getJSONArray("edits"));
            }
            else if ("create".equals(file.optString("mode"))) {
                Reply exists = call("GET", GitHubFunctions.contents(repo, path, parent), null, null, true);
                if (exists.status != 404) throw new IllegalStateException(exists.status < 300 ? "이미 있는 파일을 새 파일 제안으로 덮어쓸 수 없어요: " + path : exists.error);
                content = file.getString("content");
            } else throw new IllegalArgumentException("파일 제안 방식을 확인해 주세요.");
            entries.put(new JSONObject().put("path", path).put("mode", mode).put("type", "blob").put("content", content));
        }
        JSONObject tree = (JSONObject)call("POST", api + "/git/trees", new JSONObject().put("base_tree", baseTree).put("tree", entries).toString(), "application/json").json;
        JSONObject made = (JSONObject)call("POST", api + "/git/commits", new JSONObject().put("message", message).put("tree", tree.getString("sha")).put("parents", new JSONArray().put(parent)).toString(), "application/json").json;
        String sha = made.getString("sha");
        if (!sha.matches("[a-fA-F0-9]{40}")) throw new IllegalStateException("반영 커밋을 확인하지 못했어요.");
        try { call("POST", api + "/git/refs", new JSONObject().put("ref", "refs/heads/" + branch).put("sha", sha).toString(), "application/json"); }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception failure) {
            Reply check = call("GET", api + "/git/ref/heads/" + GitHubFunctions.encodePath(branch), null, null, true);
            if (check.status >= 300 || !(check.json instanceof JSONObject value) || !sha.equals(value.optJSONObject("object") == null ? "" : value.getJSONObject("object").optString("sha")))
                return GitHubFunctions.failure("팀 브랜치 생성 결과를 확인하지 못했어요. 중복 반영하지 말고 브랜치를 조회해 주세요.").put("outcome", "unknown").put("branch", branch).put("commit_sha", sha);
        }
        return ok().put("repo", repo).put("branch", branch).put("base", base).put("base_sha", parent).put("commit_sha", sha)
            .put("commit", "https://github.com/" + repo + "/commit/" + sha).put("files", files.length()).put("merged", false)
            .put("note", "팀 제안을 새 브랜치의 한 커밋으로 반영했습니다. PR을 열고 검사 결과를 확인하세요. main은 변경하지 않았습니다.");
    }

    /** Merge only the reviewed head into the requested base; GitHub enforces protection, permissions and required checks. */
    private JSONObject merge(JSONObject args) throws Exception {
        if (!mergeTask.exact()) return GitHubFunctions.failure("현재 요청에서 병합할 저장소와 PR 번호를 명확히 승인받지 못했어요. 정확한 저장소와 PR 번호로 병합을 요청해 주세요.");
        String repo = GitHubFunctions.repo(args), numberText = String.valueOf(args.opt("number"));
        if (!numberText.matches("[1-9][0-9]{0,8}")) return GitHubFunctions.failure("number must be a positive pull request number");
        int number = Integer.parseInt(numberText);
        String expected = args.optString("expected_head_sha").strip().toLowerCase(java.util.Locale.ROOT);
        if (!expected.matches("[0-9a-f]{40}")) return GitHubFunctions.failure("expected_head_sha must be the full head.sha read from the current PR");
        String base = GitHubFunctions.branch(args.optString("base")), method = args.optString("merge_method").strip();
        if (!method.isEmpty() && !java.util.Set.of("merge", "squash", "rebase").contains(method)) return GitHubFunctions.failure("merge_method must be merge, squash or rebase");
        if (!repo.equalsIgnoreCase(mergeTask.repo) || number != current() || !expected.equals(reviewedHead) || !base.equals(reviewedBase))
            return GitHubFunctions.failure("승인한 PR의 실제 조회 결과와 병합 대상이 일치하지 않아요. 정확한 PR을 github_get으로 먼저 읽어 주세요. 변경된 커밋이나 다른 대상은 새 승인이 필요해요.");
        String url = GitHubFunctions.API + "/repos/" + repo + "/pulls/" + number;
        // A second model tool call, even with a new call ID, must never replay a mutation.
        if (mergeAttempted) {
            if (mergeOutcome != null && !"unknown".equals(mergeOutcome.optString("outcome"))) return mergeOutcome;
            JSONObject result = verifyMerge(url, new JSONObject(), number, expected, base, method);
            if (result.optBoolean("merged")) mergeOutcome = result;
            return result;
        }
        JSONObject pr = (JSONObject) call("GET", url, null, null).json;
        if (!matches(pr, expected, base)) return GitHubFunctions.failure("PR의 커밋 또는 대상 브랜치가 바뀌었어요. github_get으로 PR을 다시 읽고 변경 내용을 확인하세요.");
        if (pr.optBoolean("merged")) {
            if (!pr.optString("merge_commit_sha").matches("[0-9a-fA-F]{40}")) return GitHubFunctions.failure("PR은 병합됨으로 표시되지만 병합 커밋을 확인하지 못했어요.").put("outcome", "unknown");
            return merged(pr, number, expected, base, "", true);
        }
        if (!pr.optString("state").equals("open")) return GitHubFunctions.failure("닫힌 PR은 병합할 수 없어요. PR 상태를 확인하세요.");
        if (pr.optBoolean("draft")) return GitHubFunctions.failure("아직 초안인 PR이에요. 검토 준비 상태로 바꾼 뒤 병합하세요.");
        if (Boolean.FALSE.equals(pr.opt("mergeable")) || pr.optString("mergeable_state").equals("dirty"))
            return GitHubFunctions.failure("병합 충돌이 있어요. 작업 브랜치에서 충돌을 해결한 뒤 PR을 다시 확인하세요.");
        if (method.isEmpty()) {
            JSONObject info = (JSONObject) call("GET", GitHubFunctions.API + "/repos/" + repo, null, null).json;
            method = info.optBoolean("allow_merge_commit") ? "merge" : info.optBoolean("allow_squash_merge") ? "squash" : info.optBoolean("allow_rebase_merge") ? "rebase" : "";
            if (method.isEmpty()) return GitHubFunctions.failure("저장소에서 허용한 병합 방식을 확인하지 못했어요. 저장소 설정을 읽고 merge_method를 지정하세요.");
        }
        JSONObject body = new JSONObject().put("sha", expected).put("merge_method", method);
        Reply reply;
        mergeAttempted = true;
        try { reply = call("PUT", url + "/merge", body.toString(), "application/json", true); }
        catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception e) {
            return verifyMerge(url, pr, number, expected, base, method);
        }
        if (reply.status >= 500) return verifyMerge(url, pr, number, expected, base, method).put("http_error", reply.error);
        if (reply.status >= 300) {
            String hint = switch (reply.status) {
                case 401, 403 -> " 토큰의 저장소 접근 및 Contents·Pull requests 쓰기 권한, GitHub의 거절 사유를 확인하세요.";
                case 405 -> " 필수 검사·리뷰·병합 규칙 또는 충돌 때문에 GitHub가 병합을 허용하지 않았어요. PR 상태를 확인하세요.";
                case 409 -> " PR에 새 커밋이 추가됐어요. PR을 다시 읽고 검토한 뒤 새 head.sha로 요청하세요.";
                case 422 -> " PR 상태와 저장소에서 허용하는 병합 방식을 확인하세요.";
                default -> " PR 상태와 GitHub가 반환한 이유를 확인하세요.";
            };
            return GitHubFunctions.failure(reply.error + hint).put("url", pr.optString("html_url"));
        }
        if (!(reply.json instanceof JSONObject)) return verifyMerge(url, pr, number, expected, base, method);
        JSONObject result = (JSONObject) reply.json;
        if (!(result.opt("merged") instanceof Boolean)) return verifyMerge(url, pr, number, expected, base, method);
        if (!result.optBoolean("merged")) return GitHubFunctions.failure("GitHub가 병합 완료를 확인하지 않았어요: " + result.optString("message")).put("url", pr.optString("html_url"));
        if (!result.optString("sha").matches("[0-9a-fA-F]{40}")) return verifyMerge(url, pr, number, expected, base, method);
        return merged(pr, number, expected, base, method, false).put("merge_sha", result.optString("sha")).put("message", result.optString("message"));
    }
    /** A lost/malformed response or server error can hide a completed merge. Read once; never replay PUT. */
    private JSONObject verifyMerge(String url, JSONObject pr, int number, String head, String base, String method) throws Exception {
        if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException("작업을 멈췄어요.");
        try {
            JSONObject after = (JSONObject) call("GET", url, null, null).json;
            if (matches(after, head, base) && after.optBoolean("merged") && after.optString("merge_commit_sha").matches("[0-9a-fA-F]{40}"))
                return merged(after, number, head, base, method, true).put("verified_after_connection_error", true);
        } catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception ignored) {}
        return GitHubFunctions.failure("병합 결과를 확정할 수 없어요. PR의 merged 상태를 다시 확인하세요. 병합 요청을 자동으로 반복하지 마세요.")
            .put("outcome", "unknown").put("url", pr.optString("html_url"));
    }
    private static boolean matches(JSONObject pr, String sha, String base) {
        return pr.optJSONObject("head") != null && pr.optJSONObject("base") != null
            && sha.equalsIgnoreCase(pr.optJSONObject("head").optString("sha")) && base.equals(pr.optJSONObject("base").optString("ref"));
    }
    private static JSONObject merged(JSONObject pr, int number, String head, String base, String method, boolean already) throws Exception {
        return ok().put("merged", true).put("already_merged", already).put("number", number).put("url", pr.optString("html_url"))
            .put("base", base).put("head_sha", head).put("merge_sha", pr.optString("merge_commit_sha")).put("merge_method", method);
    }

    /** Where the last working() call made a new branch from ("" when the branch already existed). */
    private String createdFrom = "";
    /**
     * The branch to change: checked, never the default one; when it does not exist yet it is made from base (a branch,
     * tag or commit), or from the default branch when base is empty.
     */
    private String working(String repo, String requested, String base) throws Exception {
        createdFrom = "";
        String branch = GitHubFunctions.branch(requested), main = defaultBranch(repo);
        if (branch.equals(main)) throw new IllegalArgumentException("never change the default branch (" + main + "): use a new branch and open a pull request");
        Reply found = call("GET", GitHubFunctions.API + "/repos/" + repo + "/branches/" + GitHubFunctions.encodePath(branch), null, null, true);
        if (found.status == 404) {
            String from = base == null || base.isBlank() ? main : base.strip(), sha = startPoint(repo, from);
            call("POST", GitHubFunctions.API + "/repos/" + repo + "/git/refs", new JSONObject().put("ref", "refs/heads/" + branch).put("sha", sha).toString(), "application/json");
            createdFrom = from + " @ " + sha;
        } else if (found.status >= 300) throw new IllegalStateException(found.error);
        return branch;
    }
    /** The commit a new branch starts at: a full sha as given, else the branch head, else what GitHub resolves (a tag or short sha). */
    private String startPoint(String repo, String ref) throws Exception {
        if (ref.matches("[0-9a-fA-F]{40}")) return ref.toLowerCase(java.util.Locale.ROOT);
        String name = GitHubFunctions.branch(ref);
        Reply head = call("GET", GitHubFunctions.API + "/repos/" + repo + "/git/ref/heads/" + GitHubFunctions.encodePath(name), null, null, true);
        if (head.status < 300 && head.json instanceof JSONObject headRef && headRef.optJSONObject("object") != null
                && headRef.getJSONObject("object").optString("sha").matches("[0-9a-fA-F]{40}")) return headRef.getJSONObject("object").optString("sha");
        Reply commit = call("GET", GitHubFunctions.API + "/repos/" + repo + "/commits/" + GitHubFunctions.encodePath(name), null, null, true);
        if (commit.status < 300 && commit.json instanceof JSONObject resolved && resolved.optString("sha").matches("[0-9a-fA-F]{40}")) return resolved.optString("sha");
        throw new IllegalArgumentException("base not found: " + name + " (use an existing branch, tag or commit sha)");
    }
    private String defaultBranch(String repo) throws Exception {
        String known = defaults.get(repo);
        if (known != null) return known;
        String branch = ((JSONObject) call("GET", GitHubFunctions.API + "/repos/" + repo, null, null).json).optString("default_branch", "main");
        defaults.put(repo, branch);
        return branch;
    }
    private JSONObject put(String repo, String path, String branch, String content, String sha, String message) throws Exception {
        JSONObject body = new JSONObject().put("message", message).put("branch", branch).put("content", Base64.getEncoder().encodeToString(content.getBytes(StandardCharsets.UTF_8)));
        if (sha != null && !sha.isBlank()) body.put("sha", sha);
        return (JSONObject) call("PUT", GitHubFunctions.contents(repo, path, null), body.toString(), "application/json").json;
    }
    /** A file's text at a ref, any size up to 10 MB (the raw media type). */
    private String raw(String repo, String path, String ref) throws Exception {
        if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException("작업을 멈췄어요.");
        Map<String, String> headers = headers("application/vnd.github.raw+json");
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open("GET", GitHubFunctions.contents(repo, path, ref), headers, null, 60_000, cancel)) {
            String text = OpenAiAccount.Http.read(response.body, 10 * 1024 * 1024);
            if (response.status >= 300) throw new IllegalStateException(problem(response.status, text));
            return text;
        } finally { if (cancel != null) cancel.set(null); }
    }

    private static final class Reply { int status; Object json; String error = ""; }
    private Reply call(String method, String url, String body, String type) throws Exception { return call(method, url, body, type, false); }
    /** A REST call; a failed one throws unless soft, when its status and error come back instead. */
    private Reply call(String method, String url, String body, String type, boolean soft) throws Exception {
        if (stopped.getAsBoolean()) throw new java.util.concurrent.CancellationException("작업을 멈췄어요.");
        Map<String, String> headers = headers("application/vnd.github+json");
        if (type != null) headers.put("Content-Type", type);
        try (OpenAiAccount.Http.Exchange response = OpenAiAccount.Http.open(method, url, headers, body == null ? null : body.getBytes(StandardCharsets.UTF_8), 60_000, cancel)) {
            String text = OpenAiAccount.Http.read(response.body, 4 * 1024 * 1024);
            Reply reply = new Reply(); reply.status = response.status;
            try { reply.json = text.strip().startsWith("[") ? new JSONArray(text) : new JSONObject(text.isBlank() ? "{}" : text); } catch (Exception e) { reply.json = new JSONObject(); }
            if (response.status >= 300) {
                reply.error = problem(response.status, text);
                if (!soft) throw new IllegalStateException(reply.error);
            }
            return reply;
        } finally { if (cancel != null) cancel.set(null); }
    }
    private Map<String, String> headers(String accept) {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Authorization", "Bearer " + github.token); headers.put("Accept", accept);
        headers.put("X-GitHub-Api-Version", "2022-11-28"); headers.put("User-Agent", "gyeot-app");
        return headers;
    }
    /** GitHub's own words for a failure, with the status; never the token. */
    private static String problem(int status, String text) {
        String message = "";
        try { message = new JSONObject(text).optString("message"); } catch (Exception ignored) {}
        String hint = status == 401 ? " (the GitHub token is wrong or expired)" : status == 403 ? " (the token may lack permission for this repository or action)" : status == 404 ? " (not found, or the token cannot see it)" : status == 409 ? " (the resource changed meanwhile; read it again)" : status == 422 ? " (GitHub refused the values)" : "";
        return "GitHub " + status + (message.isBlank() ? "" : ": " + message) + hint;
    }
    private static JSONObject ok() { try { return new JSONObject().put("ok", true); } catch (Exception e) { throw new IllegalStateException(e); } }
}

