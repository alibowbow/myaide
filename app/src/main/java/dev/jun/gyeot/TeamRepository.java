package dev.jun.gyeot;

import dev.jun.gyeot.core.*;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Real repository reads for workers. An empty default repo does not remove their tools. */
final class TeamRepository extends WorkTools {
    private final GitHubRunner reader;
    private final GenerationPool.Control control;
    private final String repository;
    private int calls, successes, sourceReads;
    private String lastError = "";
    TeamRepository(GitHubTools configured, String assignedRepository, GenerationPool.Control control) {
        repository = assignedRepository == null || assignedRepository.isBlank() ? configured.repo : assignedRepository;
        this.control = control;
        reader = new GitHubRunner(new GitHubTools(configured.token, false, repository), control.request, () -> control.stopped);
    }
    JSONArray declarations() { return reader.declarations(); }
    String instructions() {
        return reader.instructions() + " You have github_list, github_read, github_search and github_get repository tools. "
            + "Use github_read to inspect the actual source before proposing code. Do not claim these tools are absent without attempting them. "
            + "Use the exact repository and ref from the assignment. The saved default repository may be empty; supply owner/repo explicitly in each call. "
            + "Report actual tool errors. Only the lead can write, merge or run build/test commands. "
            + "There is no task-wide repository read quota. Read the source you need in bounded excerpts (up to 400 lines per github_read). "
            + "Avoid repeating unchanged reads; report access, rate-limit or context errors honestly.";
    }
    JSONObject run(String name, Object arguments) {
        control.check();
        if (!supports(name) || GitHubFunctions.changes(name)) return GitHubFunctions.failure("서브에이전트는 저장소를 읽을 수만 있어요.");
        JSONObject args;
        try { args = arguments instanceof JSONObject a ? a : new JSONObject(String.valueOf(arguments)); }
        catch (Exception e) { return GitHubFunctions.failure("읽을 저장소와 파일 인수를 확인해 주세요."); }
        try {
            String target;
            if (GitHubFunctions.GET.equals(name)) {
                String path = GitHubFunctions.getPath(args.optString("path"));
                String route = path.substring((GitHubFunctions.API + "/").length()).split("\\?", 2)[0];
                String[] parts = route.split("/", 4);
                if (parts.length < 3 || !parts[0].equals("repos") || route.contains("%")) return GitHubFunctions.failure("저장소 조회 경로만 사용할 수 있어요.");
                target = GitHubFunctions.repo(obj("repo", parts[1] + "/" + parts[2]));
            } else target = GitHubFunctions.repo(args);
            if (!repository.isBlank() && !repository.equalsIgnoreCase(target)) return GitHubFunctions.failure("이 작업에 지정한 저장소 범위만 읽을 수 있어요: " + repository);
        } catch (Exception e) { return GitHubFunctions.failure("읽을 저장소 주소를 확인해 주세요."); }
        synchronized (this) { calls++; } // Evidence only, never a task-wide read quota.
        JSONObject result = reader.run(name, args);
        synchronized (this) {
        if (result.optBoolean("ok")) { successes++; if (GitHubFunctions.READ.equals(name) || GitHubFunctions.SEARCH.equals(name) && "direct_file".equals(result.optString("source"))) sourceReads++; }
        else lastError = TeamPlan.clip(result.optString("error", "저장소 조회 실패"), 500);
        }
        return result;
    }
    /** Immutable receipt-only snapshot: counts are calls, not distinct files; no arguments or source content. */
    synchronized String partialEvidence() {
        return "앱 확인: 저장소 조회 시도 " + calls + "회 · 성공 " + successes + "회 · 소스 읽기 성공 " + sourceReads
            + "회 (중복 포함, 고유 파일 수 아님). 파일 수정·빌드·테스트는 실행하지 않았어요.";
    }
    String evidence(boolean sourceRequired) {
        if (sourceRequired && sourceReads == 0 || calls > 0 && successes == 0)
            throw new IllegalStateException(lastError.isBlank() ? "서브에이전트가 저장소 소스를 읽지 않아 코드 검토를 완료하지 못했어요." : "저장소를 읽지 못했어요: " + lastError);
        return successes == 0 ? "" : "\n\n앱 확인: GitHub 조회 " + successes + "회 성공 · 소스 파일 읽기 " + sourceReads + "회. 파일 수정·빌드·테스트는 실행하지 않았어요.";
    }
}
