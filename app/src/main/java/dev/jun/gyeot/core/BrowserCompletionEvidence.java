package dev.jun.gyeot.core;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** The deterministic local completion proof: a quote or state actually observed on the current page.
 * Page text remains untrusted data. This class neither executes nor retries an action.
 */
public final class BrowserCompletionEvidence {
    private BrowserCompletionEvidence() {}

    public record Assessment(boolean eligible, String reason, String kind) {}

    /** Rebind only the identical previously observed quote after a verification read.
     * It remains a candidate requiring semantic verification, never a new success claim. */
    public static JSONObject refresh(JSONObject plan,JSONObject before,JSONObject fresh) {
        if(plan==null || before==null || fresh==null || !fresh.optBoolean("ok") || fresh.optBoolean("requires_user")
                || !fresh.optString("error_code").isBlank() || !fresh.optString("error").isBlank()
                || fresh.optString("document").isBlank() || fresh.optJSONArray("elements")==null)return plan;
        JSONObject evidence=plan.optJSONObject("completion_evidence");
        if(evidence==null || !before.optString("document").equals(evidence.optString("document")))return plan;
        String quote=normalize(evidence.optString("quote"));
        JSONObject old=find(before.optJSONArray("elements"),evidence.optString("ref"));
        boolean wasObserved=!quote.isBlank() && (normalize(before.optString("text")).contains(quote)
            || normalize(before.optString("title")).contains(quote) || old!=null && !editable(old) && normalize(old.optString("label")).contains(quote));
        if(!wasObserved)return plan;
        JSONObject matching=null; int matches=0;
        JSONArray elements=fresh.optJSONArray("elements");
        for(int i=0;old!=null && i<elements.length();i++) {
            JSONObject e=elements.optJSONObject(i);
            if(e!=null && old.optString("label").equals(e.optString("label")) && old.optString("role").equals(e.optString("role"))
                    && old.optString("tag").equals(e.optString("tag")) && old.optString("href").equals(e.optString("href"))) {matching=e;matches++;}
        }
        if(!normalize(fresh.optString("text")).contains(quote) && !normalize(fresh.optString("title")).contains(quote)
                && !(matches==1 && !editable(matching) && normalize(matching.optString("label")).contains(quote)))return plan;
        JSONObject rebound=Jsons.copy(plan),proof=rebound.optJSONObject("completion_evidence");
        Jsons.put(proof,"document",fresh.optString("document"));
        proof.remove("ref");
        if(matches==1)Jsons.put(proof,"ref",matching.optString("ref"));
        return rebound;
    }

    /** goalKind comes from the user's own words ({@link #goalKind}), not the completion model's claim.
     * A caller must additionally bind the observation to its active lease/revision.
     */
    public static Assessment evaluate(String request, String goalKind, JSONObject plan,
            JSONObject observation, String expectedDocument, boolean cancelled, boolean mutationPending) {
        if (cancelled) return reject("cancelled");
        if (mutationPending) return reject("mutation_unresolved");
        if (plan == null || !plan.optBoolean("done") || !"completed".equals(plan.optString("outcome", "completed")))
            return reject("not_completed_candidate");
        if (observation == null || !observation.optBoolean("ok", true) || observation.optBoolean("requires_user")
                || observation.optBoolean("cancelled") || !observation.optString("error_code").isBlank()
                || !observation.optString("error").isBlank()) return reject("observation_blocked");
        String document = observation.optString("document");
        if (document.isBlank() || expectedDocument == null || !document.equals(expectedDocument)
                || observation.optString("url").isBlank() || observation.optJSONArray("elements") == null)
            return reject("observation_unbound");
        JSONObject evidence = plan.optJSONObject("completion_evidence");
        if (evidence == null || !document.equals(evidence.optString("document"))) return reject("evidence_missing_or_stale");
        String kind = evidence.optString("kind");
        if (!kind.equals("read") && !kind.equals("state") && !kind.equals("artifact")) return reject("evidence_kind_invalid");
        boolean generation = requiresArtifact(request, goalKind);
        if (generation && !kind.equals("artifact")) return reject("artifact_required");
        JSONObject activity = observation.optJSONObject("activity");
        if ((generation || kind.equals("artifact")) && activity != null
                && ("busy".equals(activity.optString("state")) || activity.optBoolean("document_loading")))
            return reject("generation_in_progress");
        String quote = normalize(evidence.optString("quote"));
        String body = normalize(observation.optString("text"));
        String title = normalize(observation.optString("title"));
        JSONObject element = find(observation.optJSONArray("elements"), evidence.optString("ref"));
        boolean inBody = !quote.isEmpty() && body.contains(quote);
        boolean inTitle = !quote.isEmpty() && title.contains(quote);
        // An input, a text area or a rich-text composer shows what was typed, not what happened: an unsent message is no proof.
        boolean inElement = element != null && !editable(element) && !quote.isEmpty() && normalize(element.optString("label")).contains(quote);
        if (kind.equals("artifact")) {
            // A submit acknowledgement, a Preview button, an iframe count, a stopped spinner,
            // or a model's answer is not an observed generated result. Require substantial
            // actual page output that was not there before and is not the instruction.
            String type = evidence.optString("artifact_type");
            if (!java.util.Set.of("code", "preview", "download", "generated_text", "media").contains(type))
                return reject("artifact_type_invalid");
            if (quote.length() < 24 || quote.length() > 1600 || !inBody) return reject("artifact_output_unobserved");
            if (normalize(originalInstruction(request)).contains(quote)) return reject("instruction_echo_not_artifact");
            if (pendingOnly(quote)) return reject("submission_is_not_completion");
        } else if (!(inBody || inTitle || inElement)) return reject("evidence_not_observed");
        return new Assessment(true, "grounded_candidate", kind);
    }

    /** Production entry point for creation tasks. The baseline must be captured before
     * the task's first action (and retained unchanged across follow-up observations).
     * No baseline, or output already visible at that point, cannot establish fresh output.
     */
    public static Assessment evaluate(String request, String goalKind, JSONObject plan,
            JSONObject observation, String expectedDocument, boolean cancelled, boolean mutationPending,
            JSONObject initialObservation) {
        Assessment grounded = evaluate(request, goalKind, plan, observation, expectedDocument, cancelled, mutationPending);
        if (!grounded.eligible() || !grounded.kind().equals("artifact") || inspectsExisting(request, goalKind)) return grounded;
        if (initialObservation == null || initialObservation.optString("document").isBlank()) return reject("artifact_baseline_missing");
        JSONObject evidence = plan.optJSONObject("completion_evidence");
        if (evidence == null) return reject("evidence_missing");
        String quote = normalize(evidence.optString("quote"));
        if (normalize(initialObservation.optString("text")).contains(quote)) return reject("artifact_preexisting");
        return grounded;
    }

    private static final String CHANGE_WORDS = "(?s).*(삭제|구매|결제|송금|등록|댓글|전송|수정|저장|delete|purchase|payment|submit|send|write|create|build).*";
    private static final String READ_WORDS = "(?s).*(읽|탐색|검색|뉴스|요약|확인|read|search|find|news|summar|inspect).*";
    /** Words only: a goal that reads and neither changes nor creates anything. Not permission; callers keep their own guards. */
    public static boolean readGoal(String request, String goalKind) { return readWords(request, goalKind, READ_WORDS); }
    // Everyday requests to act on a page ('보내기 눌러줘', '정리해서 보내 줘'): such a goal is not read-only.
    private static final String OFF_ACTION_WORDS = "(?s).*((?:보내|올려|눌러|적어|써|지워|열어|닫아|켜|꺼|바꿔|옮겨|넣어|채워|들어가|클릭해|입력해|작성해|선택해|이동해"
        + "|예약해|신청해|주문해|가입해|로그인해|구독해|팔로우해|취소해|변경해|추가해|설치해|업로드해|다운로드해|다운받아|공유해|복사해|붙여넣어)\\s*(?:줘|주|줄|서|봐|라)"
        + "|(?:누르|보내|열|켜|닫|지우|올리)고|보내기|\\b(?:tap|click|press|fill|open|close|log ?in|sign ?(?:in|up)|subscribe|download|upload|install|reply|cancel)\\b).*";
    /** One user instruction that only reads, summarizes, translates, explains or asks about the page. */
    public static boolean readOnlyInstruction(String instruction) {
        String said = originalInstruction(instruction);
        if (said.toLowerCase(Locale.ROOT).matches(OFF_ACTION_WORDS)) return false;
        return readWords(said, "", "(?s).*(읽|탐색|검색|뉴스|요약|확인|번역|설명|알려|정리|간추|핵심|요점|내용|뭐|무엇|무슨|어떤|얼마|어때|몇|누구|언제|어디|왜|말해|보여"
            + "|read|search|find|news|summar|inspect|translate|explain|what|how|who|when|where|which|why|tell|show|list).*");
    }
    private static boolean readWords(String request, String goalKind, String words) {
        String goal = request == null ? "" : request.toLowerCase(Locale.ROOT);
        if (requiresArtifact(goal, goalKind) || goal.matches(CHANGE_WORDS)) return false;
        return "read".equals(goalKind) || goal.matches(words);
    }

    public static boolean requiresArtifact(String request, String goalKind) {
        if ("artifact".equals(goalKind) || "existing_artifact".equals(goalKind) || "interactive_generation".equals(goalKind) || "generation".equals(goalKind)) return true;
        if (inspectsExisting(request, goalKind)) return false;
        String text = normalize(originalInstruction(request)).toLowerCase(Locale.ROOT);
        // An explicit request to create an app, canvas, game or site needs generated output as proof.
        boolean create = text.matches("(?s).*(만들|제작|생성|구현|개발|build|create|generate|implement).*" );
        boolean output = text.matches("(?s).*(캔버스|canvas|앱|어플|웹사이트|게임|app\\b|website|game|artifact).*" );
        return create && output;
    }

    /** The task kind from the user's own instructions: artifact (create an app, canvas, game or site), read (every
     * instruction only reads), else state. It replaces a remote intent vote; completion still needs observed proof. */
    public static String goalKind(JSONArray instructions) {
        boolean read = instructions != null && instructions.length() > 0;
        for (int i = 0; instructions != null && i < instructions.length(); i++) {
            String said = instructions.optString(i);
            if (requiresArtifact(said, "")) return "artifact";
            if (!BrowserPrompts.selectionRead(said) && !readOnlyInstruction(said)) read = false;
        }
        return read ? "read" : "state";
    }

    private static String originalInstruction(String request) {
        if (request == null) return "";
        try {
            JSONObject envelope = new JSONObject(request);
            if ("finalized_voice".equals(envelope.optString("source")) && envelope.has("original_user_instruction"))
                return envelope.optString("original_user_instruction");
        } catch (Exception ignored) { /* Android JSONException is checked; plain panel instruction. */ }
        return request;
    }

    private static boolean inspectsExisting(String request, String goalKind) {
        String text = normalize(originalInstruction(request)).toLowerCase(Locale.ROOT);
        boolean fresh = text.matches("(?s).*(\\b(?:create|build|generate|new|again)\\b|다시 만들|새로|새 앱|새로운).*" );
        if (fresh) return false;
        if ("existing_artifact".equals(goalKind)) return true;
        boolean existing = text.matches("(?s).*(existing|previous|already|created|built|만든|만들어진|기존|이전|이미).*" );
        boolean inspect = text.matches("(?s).*(show|read|inspect|review|examine|open|보여|읽|검토|열어|확인).*" );
        return existing && inspect;
    }

    private static boolean pendingOnly(String quote) {
        String text = quote.toLowerCase(Locale.ROOT);
        return text.matches("(?s)^(?:your |the |요청.{0,8})?(?:request (?:has been )?(?:submitted|accepted|queued)|generating|building|working on|생성 중|제작 중|만드는 중|요청.{0,8}(?:접수|전달)).*")
                && !text.contains("```");
    }

    /** A field whose label may be its own typed text: an input, a text area, a rich-text editor, a text box. */
    static boolean editable(JSONObject element) {
        return element != null && (element.optBoolean("editable") || java.util.Set.of("input", "textarea", "select").contains(element.optString("tag").toLowerCase(Locale.ROOT))
            || java.util.Set.of("textbox", "searchbox", "combobox").contains(element.optString("role")));
    }
    private static JSONObject find(JSONArray elements, String ref) {
        if (ref == null || ref.isBlank() || elements == null) return null;
        for (int i = 0; i < elements.length(); i++) {
            JSONObject element = elements.optJSONObject(i);
            if (element != null && ref.equals(element.optString("ref"))) return element;
        }
        return null;
    }
    private static String normalize(String value) { return value == null ? "" : value.replaceAll("\\s+", " ").strip(); }
    private static Assessment reject(String reason) { return new Assessment(false, reason, ""); }
}
