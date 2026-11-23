package dev.jun.gyeot;

import dev.jun.gyeot.core.GitHubFunctions;
import dev.jun.gyeot.core.TeamPlan;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local work tools, independent of the model that plans the next action. */
abstract class WorkTools {
    abstract JSONArray declarations();
    abstract JSONObject run(String name, Object arguments);
    String instructions() { return ""; }
    /** The function rounds a main-chat reply may take with these tools; 0 for the usual (OpenAiClient.MAIN_ROUNDS). */
    int rounds() { return 0; }
    boolean write() { return false; }
    boolean inspectMerge(JSONArray turns) { return false; }
    boolean mergeNeedsAction() { return false; }
    JSONObject mergeInspectionResult() { return null; }
    /** A tool the model must still call before it may finish (a declared name or WorkPlans.ANY_TOOL), or null for none. */
    String requiredTool() { return null; }
    /**
     * The files the model's own code saved so far this turn (Claude code execution: [{file}]), told before each round of the
     * app's tools so a tool can use a chart or picture the code just made. Most tools ignore it.
     */
    void providerFiles(JSONArray files) {}
    final boolean supports(String name) {
        JSONArray offered = declarations();
        for (int i = 0; i < offered.length(); i++) if (name.equals(offered.optJSONObject(i).optString("name"))) return true;
        return false;
    }
    final JSONArray openAi() { return GitHubFunctions.openAi(declarations()); }
    final JSONArray claude() { return GitHubFunctions.claude(declarations()); }
    final JSONObject gemini() { return GitHubFunctions.gemini(declarations()); }
    static String stage(String name) { if (dev.jun.gyeot.core.ProjectTools.is(name)) return dev.jun.gyeot.core.ProjectTools.stage(name); if (dev.jun.gyeot.core.FolderTools.is(name)) return dev.jun.gyeot.core.FolderTools.stage(name); if (dev.jun.gyeot.core.PageTools.is(name)) return dev.jun.gyeot.core.PageTools.stage(name); if (dev.jun.gyeot.core.MediaTools.is(name)) return dev.jun.gyeot.core.MediaTools.stage(name); if (dev.jun.gyeot.core.GoogleDrive.is(name)) return dev.jun.gyeot.core.GoogleDrive.stage(name); if (dev.jun.gyeot.core.CalendarQuery.is(name)) return dev.jun.gyeot.core.CalendarQuery.stage(name); return dev.jun.gyeot.core.BrowserWorkJobs.RUN.equals(name) ? "브라우저에서 작업하는 중" : TeamPlan.TOOL.equals(name) ? "에이전트 팀에 작업을 나누는 중" : TeamPlan.APPLY_FILES.equals(name) ? "팀 파일 제안을 브랜치에 반영하는 중" : TeamPlan.SUBMIT_FILES.equals(name) ? "팀 파일 제안을 보관하는 중" : GitHubFunctions.stage(name); }
    /** Tools with effects outside the phone (and the user's usage), and changes to the user's 페이지: journaled before they run and never repeated in a turn. */
    static boolean startsWork(String name) { return dev.jun.gyeot.core.PageTools.effect(name) || dev.jun.gyeot.core.ProjectTools.effect(name) || dev.jun.gyeot.core.MediaTools.is(name) || dev.jun.gyeot.core.GoogleDrive.effect(name) || dev.jun.gyeot.core.CalendarQuery.ADD.equals(name) || dev.jun.gyeot.core.BrowserWorkJobs.RUN.equals(name) || TeamPlan.TOOL.equals(name) || TeamPlan.APPLY_FILES.equals(name) || TeamPlan.SUBMIT_FILES.equals(name) || GitHubFunctions.changes(name); }
    static WorkTools combine(WorkTools primary, WorkTools extra) {
        if (extra == null) return primary;
        if (primary == null) return extra;
        return new WorkTools() {
            JSONArray declarations() {
                JSONArray result = new JSONArray();
                for (WorkTools tools : new WorkTools[]{primary, extra}) {
                    JSONArray list = tools.declarations();
                    for (int i = 0; i < list.length(); i++) result.put(list.opt(i));
                }
                return result;
            }
            JSONObject run(String name, Object args) {
                if (primary.supports(name)) return primary.run(name, args);
                if (extra.supports(name)) return extra.run(name, args);
                return GitHubFunctions.failure("현재 팀에 허용되지 않은 도구입니다.");
            }
            String instructions() { return primary.instructions() + extra.instructions(); }
            int rounds() { return Math.max(primary.rounds(), extra.rounds()); }
            boolean write() { return primary.write(); }
            boolean inspectMerge(JSONArray turns) { return primary.inspectMerge(turns); }
            boolean mergeNeedsAction() { return primary.mergeNeedsAction(); }
            JSONObject mergeInspectionResult() { return primary.mergeInspectionResult(); }
            String requiredTool() { String required = primary.requiredTool(); return required != null ? required : extra.requiredTool(); }
            void providerFiles(JSONArray files) { primary.providerFiles(files); extra.providerFiles(files); }
        };
    }
}
