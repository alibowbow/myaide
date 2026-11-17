package dev.jun.gyeot;

import dev.jun.gyeot.core.*;
import java.util.*;
import java.util.function.Supplier;
import org.json.*;
import static dev.jun.gyeot.core.Jsons.*;

/** Turn-local file proposals. Only a completed task's reviewed IDs can be committed by the lead. */
final class TeamChanges extends WorkTools {
    static final String SUBMIT = TeamPlan.SUBMIT_FILES, INSPECT = TeamPlan.INSPECT_FILES, APPLY = TeamPlan.APPLY_FILES;
    private final Map<String, JSONObject> proposals = new LinkedHashMap<>(), receipts = new LinkedHashMap<>();
    private final Set<String> attempted = new HashSet<>();
    private final GitHubTools github;
    private final Supplier<JSONArray> states;
    private final GenerationPool.Control parent;
    TeamChanges(GitHubTools github, Supplier<JSONArray> states, GenerationPool.Control parent) { this.github = github; this.states = states; this.parent = parent; }
    private static JSONObject tool(String name, String description, JSONObject properties, String... required) {
        return obj("name", name, "description", description, "parameters", obj("type", "object", "properties", properties, "required", arr((Object[]) required)));
    }
    private static JSONObject str(String hint) { return obj("type", "string", "description", hint); }
    static JSONObject inspectionTool() {
        return tool(INSPECT, "Inspect a completed worker's stored file proposal. Omit path for metadata; pass path for a bounded excerpt. No write happens.",
            obj("proposal_id", str("ID returned by the worker"), "path", str("file path; omit for metadata"), "start_line", obj("type", "integer", "minimum", 1)), "proposal_id");
    }
    JSONArray declarations() {
        JSONArray tools = arr(inspectionTool());
        if (github != null && github.write) tools.put(tool(APPLY, "Commit reviewed completed file proposals by ID without retyping their contents. "
            + "Creates one new team/* branch and one atomic commit from the current default branch. Does not merge or open a PR. "
            + "Conflicting paths, stale exact edits and replacing an existing file with a create proposal are rejected before publishing the branch.",
            obj("proposal_ids", obj("type", "array", "items", str("completed proposal ID")), "message", str("commit message")), "proposal_ids", "message"));
        return tools;
    }
    WorkTools worker(JSONObject profile, GenerationPool.Control control) {
        String task = TeamPlan.key(profile.optString("task_id")); final String repo = repository(profile);
        return new WorkTools() {
            JSONArray declarations() {
                JSONObject file = obj("type", "object", "properties", obj("path", str("repository file path"), "mode", obj("type", "string", "enum", arr("create", "edit")),
                    "content", str("full UTF-8 content for a new file only"), "edits", obj("type", "array", "items", obj("type", "object", "properties", obj("old", str("exact existing text without line numbers"), "new", str("replacement")), "required", arr("old", "new")))), "required", arr("path", "mode"));
                return arr(tool(SUBMIT, "Save a concrete file proposal in the app. For existing files use exact old/new edits copied from source; for new files use create+content. "
                    + "The lead receives only proposal IDs and can inspect/apply without copying code. This does not modify GitHub. Submit again to replace your unfinished proposal.",
                    obj("summary", str("what changed and evidence/limitations"), "files", obj("type", "array", "items", file)), "summary", "files"), inspectionTool());
            }
            String instructions() { return " For code deliverables submit_team_files with actual content/edits, then report the proposal ID and concise evidence. Do not repeat full code in the final answer. "
                + "Use inspect_team_files to review completed prerequisite proposals. Submitting a proposal is not a GitHub commit or test execution."; }
            JSONObject run(String name, Object arguments) {
                control.check();
                try { JSONObject args = arguments instanceof JSONObject a ? a : new JSONObject(String.valueOf(arguments));
                    if (INSPECT.equals(name)) return inspect(args);
                    if (!SUBMIT.equals(name)) return GitHubFunctions.failure("팀원이 사용할 수 없는 도구예요.");
                    JSONObject result = submit(task, repo, args); control.check(); return result;
                } catch (java.util.concurrent.CancellationException e) { throw e; }
                catch (Exception e) { return GitHubFunctions.failure(e.getMessage()); }
            }
        };
    }
    private String repository(JSONObject profile) {
        String repository = profile.optString("repository", "");
        return repository.isBlank() && github != null ? github.repo : repository;
    }
    /** Stores a proposal the worker wrote as JSON text (TeamPlan.proposal) through the same checks as submit_team_files; null when the text holds none. */
    JSONObject submitText(JSONObject profile, String text) {
        JSONObject args = TeamPlan.proposal(text);
        if (args == null) return null;
        try { return submit(TeamPlan.key(profile.optString("task_id")), repository(profile), args); }
        catch (Exception e) { return GitHubFunctions.failure(e.getMessage()); }
    }
    private synchronized JSONObject submit(String task, String repo, JSONObject args) throws Exception {
        repo = GitHubFunctions.repo(obj("repo", repo)); JSONArray input = args.optJSONArray("files"), files = arr(); Set<String> paths = new HashSet<>();
        if (input == null || input.length() < 1 || input.length() > 8) throw new IllegalArgumentException("제안은 파일 1~8개로 나눠 주세요.");
        String summary = args.optString("summary").strip(); if (summary.isBlank()) throw new IllegalArgumentException("변경 설명이 필요해요.");
        for (int i = 0; i < input.length(); i++) {
            JSONObject raw = input.optJSONObject(i); if (raw == null) throw new IllegalArgumentException("파일 형식을 확인해 주세요.");
            String path = GitHubFunctions.path(raw.optString("path"), true), mode = raw.optString("mode");
            if (path.startsWith(".git/") || path.equals(".git") || !paths.add(path)) throw new IllegalArgumentException("파일 경로가 중복되거나 올바르지 않아요.");
            JSONObject file = obj("path", path, "mode", mode);
            if (mode.equals("create") && raw.opt("content") instanceof String) put(file, "content", raw.getString("content"));
            else if (mode.equals("edit") && raw.optJSONArray("edits") != null) {
                JSONArray edits = raw.getJSONArray("edits"); if (edits.length() == 0 || edits.length() > 30) throw new IllegalArgumentException("파일 편집은 1~30개로 나눠 주세요.");
                JSONArray clean = arr(); for (int j = 0; j < edits.length(); j++) {
                    JSONObject edit = edits.optJSONObject(j);
                    if (edit == null || !(edit.opt("old") instanceof String) || edit.optString("old").isEmpty() || !(edit.opt("new") instanceof String)) throw new IllegalArgumentException("정확한 원문과 새 내용이 필요해요.");
                    clean.put(obj("old", edit.getString("old"), "new", edit.getString("new")));
                } put(file, "edits", clean);
            } else throw new IllegalArgumentException("새 파일은 create와 content, 기존 파일은 edit와 edits를 사용해 주세요.");
            if (file.toString().length() > 120_000) throw new IllegalArgumentException("파일 제안이 너무 커요. 작업을 나눠 주세요."); files.put(file);
        }
        String id = "proposal_" + task; JSONObject proposal = obj("proposal_id", id, "task_id", task, "repository", repo, "summary", TeamPlan.clip(summary, 1500), "files", files);
        int total = proposal.toString().length(); for (Map.Entry<String,JSONObject> entry : proposals.entrySet()) if (!entry.getKey().equals(id)) total += entry.getValue().toString().length();
        if (total > 2_000_000 || proposals.size() >= TeamPlan.MAX_TASKS && !proposals.containsKey(id)) throw new IllegalArgumentException("한 응답의 파일 제안 보관 한도에 도달했어요.");
        proposals.put(id, proposal); return metadata(proposal);
    }
    private boolean completed(String task) {
        JSONArray all = states.get(); for (int i = 0; i < all.length(); i++) { JSONObject state = all.optJSONObject(i); if (state != null && task.equals(state.optString("id"))) return "completed".equals(state.optString("state")); } return false;
    }
    private static JSONObject metadata(JSONObject p) {
        JSONArray files = arr(), source = p.optJSONArray("files");
        for (int i = 0; i < source.length(); i++) { JSONObject file = source.optJSONObject(i); files.put(obj("path", file.optString("path"), "mode", file.optString("mode"), "characters", file.toString().length())); }
        return obj("ok", true, "proposal_id", p.optString("proposal_id"), "task_id", p.optString("task_id"), "repository", p.optString("repository"), "summary", p.optString("summary"), "files", files, "committed", false);
    }
    synchronized JSONObject forTask(String task) { JSONObject p = proposals.get("proposal_" + task); return p == null ? null : metadata(p); }
    synchronized void beginAttempt(String task) { proposals.remove("proposal_" + task); }
    private synchronized JSONObject inspect(JSONObject args) throws Exception {
        JSONObject p = proposals.get(args.optString("proposal_id"));
        if (p == null || !completed(p.optString("task_id"))) return GitHubFunctions.failure("완료된 작업의 파일 제안만 확인할 수 있어요.");
        String path = args.optString("path"); if (path.isBlank()) return metadata(p);
        JSONArray files = p.optJSONArray("files"); for (int i = 0; i < files.length(); i++) {
            JSONObject file = files.optJSONObject(i); if (!path.equals(file.optString("path"))) continue;
            String text = file.has("content") ? file.optString("content") : file.getJSONArray("edits").toString(2);
            int start = Math.max(1, args.optInt("start_line", 1)); JSONObject lines = GitHubFunctions.lines(text, start, start + 119);
            put(lines, "text", TeamPlan.clip(lines.optString("text"), 12000)); return obj("ok", true, "proposal_id", p.optString("proposal_id"), "path", path, "mode", file.optString("mode"), "excerpt", lines);
        } return GitHubFunctions.failure("제안에 해당 파일이 없어요.");
    }
    JSONObject run(String name, Object arguments) {
        parent.check();
        try {
            JSONObject args = arguments instanceof JSONObject a ? a : new JSONObject(String.valueOf(arguments));
            if (INSPECT.equals(name)) return inspect(args);
            if (!APPLY.equals(name) || github == null || !github.write) return GitHubFunctions.failure("GitHub 쓰기 허용이 필요해요.");
            JSONArray ids = args.optJSONArray("proposal_ids"); if (ids == null || ids.length() == 0 || ids.length() > 16) return GitHubFunctions.failure("적용할 제안 ID를 선택해 주세요.");
            SortedSet<String> selected = new TreeSet<>(); for (int i = 0; i < ids.length(); i++) selected.add(ids.optString(i)); String key = String.join("|", selected);
            JSONArray files = arr(); String repo = ""; Set<String> paths = new HashSet<>();
            synchronized (this) {
                if (receipts.containsKey(key)) return copy(receipts.get(key));
                for (String id : selected) {
                    if (attempted.contains(id)) return GitHubFunctions.failure("이미 반영을 시도한 제안이에요. 기존 브랜치와 실행 결과를 확인해 주세요.");
                    JSONObject p = proposals.get(id); if (p == null || !completed(p.optString("task_id"))) return GitHubFunctions.failure("완료된 작업의 제안만 적용할 수 있어요.");
                    if (!repo.isBlank() && !repo.equalsIgnoreCase(p.optString("repository"))) return GitHubFunctions.failure("한 번에 한 저장소의 제안만 적용해 주세요."); repo = p.optString("repository");
                    JSONArray changes = p.optJSONArray("files"); for (int i = 0; i < changes.length(); i++) { JSONObject file = changes.optJSONObject(i);
                        if (!paths.add(file.optString("path"))) return GitHubFunctions.failure("여러 제안이 같은 파일을 바꿔요. 검토 에이전트가 하나의 제안으로 정리해 주세요."); files.put(copy(file)); }
                }
                receipts.put(key, obj("ok", false, "outcome", "unknown", "error", "반영 요청을 시작했지만 결과를 확인하지 못했어요. 중복 실행하지 마세요."));
                attempted.addAll(selected);
            }
            String branch = "team/" + UUID.randomUUID().toString().replace("-", "");
            GitHubRunner runner = new GitHubRunner(github, parent.request, () -> parent.stopped);
            JSONObject receipt = runner.commitFiles(repo, branch, files, TeamPlan.clip(args.optString("message", "Apply team file proposals"), 200));
            synchronized (this) { receipts.put(key, copy(receipt)); } return receipt;
        } catch (java.util.concurrent.CancellationException e) { throw e; }
        catch (Exception e) { return GitHubFunctions.failure(e.getMessage() == null ? "파일 제안을 적용하지 못했어요." : e.getMessage()); }
    }
}
