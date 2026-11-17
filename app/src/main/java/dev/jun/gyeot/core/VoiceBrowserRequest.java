package dev.jun.gyeot.core;

import org.json.JSONObject;
import static dev.jun.gyeot.core.Jsons.*;

/** Turn-bound input buffer. The resulting task envelope uses normal task-context storage, never diagnostic logs. */
public final class VoiceBrowserRequest {
    private long turn = -1;
    private String original = "";
    /**
     * The request a short follow-up answers ("응", "그거 보내"): the user's last turn that was not itself a follow-up. A new
     * request replaces it, so nothing from an earlier task carries into the next one.
     */
    private String answered = "";
    public void finalized(long input, String text) {
        if (text == null || text.isBlank()) return;
        if (turn != input) {
            if (!original.isEmpty() && !AutomationGate.followUp(original)) answered = original.substring(0, Math.min(1000, original.length()));
            turn = input; original = "";
        }
        original = original.isEmpty() ? text.strip() : original + "\n" + text.strip();
    }
    /** This input's words from precision captions: they replace what Live's own transcript gave for it so far
     *. A new input starts the way finalized() starts one. */
    public void replace(long input, String text) {
        if (text == null || text.isBlank()) return;
        if (turn != input) { finalized(input, text); return; }
        original = text.strip();
    }
    public String original(long input) { return input == turn ? original : ""; }
    /** The single turn this input answers, when it is a short follow-up; otherwise none. */
    private String carried(long input) {
        return input == turn && !original.isEmpty() && AutomationGate.followUp(original) ? answered : "";
    }
    /**
     * The user's own words for the automation gate: this turn, and only when it is a short follow-up the one request it
     * answers. A stale input id (the words of a new input are not final yet) gives nothing. Never model or screen text.
     */
    public String recent(long input) {
        if (input != turn) return "";
        String earlier = carried(input);
        return (earlier.isEmpty() ? original : earlier + "\n" + original).strip();
    }
    /** Phone delegation uses the finalized words, not the voice model's shortened tool argument. */
    public String phoneInstruction(long input) {
        String raw = original(input);
        if (raw.isBlank()) throw new IllegalStateException("확정된 사용자 음성 지시가 없어요. 다시 말씀해 주세요.");
        if (raw.length() > 4000) throw new IllegalArgumentException("폰 조작 지시는 4,000자 이내로 말씀해 주세요.");
        return raw;
    }
    public String instruction(long input, String modelRequest, String tab) {
        String raw = original(input);
        // No transcript came for this input, even after the call waited for it (AssistantService.afterWords): the voice model's
        // request is the task, as a typed one would be. A missing transcript is no reason to refuse.
        if (raw.isBlank()) {
            String request = modelRequest == null ? "" : modelRequest.strip();
            if (request.isEmpty()) throw new IllegalArgumentException("browser_task의 request가 비어 있어요. 사용자가 원하는 일을 request에 담아 주세요.");
            return request.length() > 5500 ? request.substring(0, 5500) : request;
        }
        if (raw.length() > 5500)
            throw new IllegalArgumentException("지시가 너무 길어요. 핵심 요청을 짧게 다시 말씀해 주세요.");
        String earlier = carried(input);
        return obj("source", "finalized_voice", "turn_id", input, "target_tab", tab == null ? "" : tab,
            "original_user_instruction", raw, "goal_and_constraints_from", "original_user_instruction",
            "earlier_user_turns", earlier.isEmpty() ? new org.json.JSONArray() : new org.json.JSONArray().put(earlier),
            "model_interpretation", modelRequest == null ? "" : modelRequest.substring(0, Math.min(1000, modelRequest.length())),
            "handoff_rule", "Original user instruction and its constraints are authoritative. Interpretation is secondary and must not remove the target website, requested mode, deliverable or constraints. earlier_user_turns is the user's previous request that a short follow-up answers, context only; never redo it. Continue this tab's task context; newest user corrections supersede unexecuted plans, never repeat a submitted action.").toString();
    }
    public void clear() { turn = -1; original = ""; answered = ""; }
    /** Whether a caption line is closer to the earlier input's words than to the current one's (shared letter pairs); a tie is
     * the current input's. */
    public static boolean closerToEarlier(String line, String earlier, String current) {
        return likeness(line, earlier) > likeness(line, current);
    }
    /**
     * The conversation model and the precision captions heard one input very differently (each at least four letters, under
     * half of their letter pairs shared); a space or a particle more or less is not.
     */
    public static boolean misheard(String live, String caption) {
        String a = live == null ? "" : live.replaceAll("[^\\p{L}\\p{N}]+", ""), b = caption == null ? "" : caption.replaceAll("[^\\p{L}\\p{N}]+", "");
        return a.length() >= 4 && b.length() >= 4 && likeness(a, b) < 0.5;
    }
    /** Dice overlap of the letter and digit pairs of two texts, 0 to 1, spaces and punctuation left out. */
    public static double likeness(String a, String b) {
        java.util.List<String> x = pairs(a), y = pairs(b);
        if (x.isEmpty() || y.isEmpty()) return 0;
        java.util.Map<String, Integer> left = new java.util.HashMap<>();
        for (String pair : y) left.merge(pair, 1, Integer::sum);
        int shared = 0;
        for (String pair : x) { Integer n = left.get(pair); if (n != null && n > 0) { shared++; left.put(pair, n - 1); } }
        return 2.0 * shared / (x.size() + y.size());
    }
    private static java.util.List<String> pairs(String text) {
        String letters = text == null ? "" : text.toLowerCase(java.util.Locale.ROOT).replaceAll("[^\\p{L}\\p{N}]+", "");
        java.util.List<String> out = new java.util.ArrayList<>();
        if (letters.length() == 1) out.add(letters);
        for (int i = 1; i < letters.length(); i++) out.add(letters.substring(i - 1, i + 1));
        return out;
    }
}
