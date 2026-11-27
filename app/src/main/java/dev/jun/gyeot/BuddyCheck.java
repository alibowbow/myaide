package dev.jun.gyeot;

import dev.jun.gyeot.core.BuddyMind;
import dev.jun.gyeot.core.VoiceHints;
import dev.jun.gyeot.core.VoiceTurns;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 퍼레 자동 점검 (준: "내가 자꾸 테스트를 많이 해봐야하는데 자동테스트도 구현해줘 ㅜ"), the part that is plain Java: the things
 * said and what should come of each (assets/buddy/scenarios.json, one file for the phone and for tools/buddy_eval.py), the
 * words 퍼레 answers with no AI (VoiceTurns, its misheard name, what gets through over music, which tools go with a turn,
 * the text form and its names), how a brain's answer is judged, and the report. The phone (BuddyCheckUi) and the dev-side
 * runner (tools/BuddyEval.java) both judge with this, so they never disagree on what passed.
 */
final class BuddyCheck {
    private BuddyCheck() {}
    /** The layers: the words (no AI), the brain (a dry run of the real request), the phone's readiness. */
    static final String WORDS = "말", BRAIN = "두뇌", READY = "준비";
    /** The two ways 퍼레's brain is asked: the voice talk (tools) and 퍼레와 대화 (the text form). */
    static final String VOICE = "음성", CHAT = "글", TOOLS = "도구";
    /** The turns VoiceTurns knows, and CHECK: asked to run this check ("자가 점검해 줘"). */
    static final Set<String> TURNS = Set.of("CALL", "BYE", "PAUSE", "REPEAT", "HUSH", "NO", "CHECK");

    /**
     * One thing said and what should come of it: a tool (with the arguments that matter), a turn answered on the phone, the
     * name heard back, what gets through over music (music: the words, or Boolean.FALSE for nothing), or nothing at all.
     * why: what it is for (a bug 준 found by hand …).
     */
    record Scenario(String said, String tool, JSONObject args, String turn, String name, Object music, boolean none, String why) {
        /** Whether the brain is asked: a tool, or nothing to do. */
        boolean toBrain() { return tool != null || none; }
    }
    /**
     * One check's outcome. layer: WORDS, BRAIN or READY; path: VOICE or CHAT for the brain ("" otherwise); said: the words
     * (or the readiness item's name); fix: the one tap that clears the way ("" for none, BuddyCheckUi.fixIntent); toolMs:
     * until the thing to do was chosen; wordsMs: until the first sentence to say (0: not measured).
     */
    record Result(String layer, String path, String said, boolean pass, String expected, String got, String fix, long toolMs, long wordsMs) {
        static Result of(String layer, String said, boolean pass, String expected, String got, String fix) { return new Result(layer, "", said, pass, expected, got, fix == null ? "" : fix, 0, 0); }
        /** A check of the tools themselves (no words said): path TOOLS. */
        static Result tools(String what, boolean pass, String expected, String got) { return new Result(WORDS, TOOLS, what, pass, expected, got, "", 0, 0); }
        /** A short tag for the report: "말", "말·도구", "두뇌·음성", "준비". */
        String tag() { return path.isEmpty() ? layer : layer + "·" + path; }
        /** Its title: the words said, quoted, or the item checked. */
        String title() { return layer.equals(READY) || path.equals(TOOLS) ? said : "“" + said + "”"; }
    }

    /** The tools' names, in the order the brain gets them. */
    static List<String> toolNames() {
        List<String> out = new ArrayList<>();
        for (int i = 0; i < BuddyTools.TOOLS.length(); i++) out.add(BuddyTools.TOOLS.optJSONObject(i).optJSONObject("function").optString("name"));
        return out;
    }

    /** The scenarios in json ([{said, expect, why?}]); throws with the entry's number when one does not read. */
    static List<Scenario> parse(String json) {
        JSONArray all;
        try { all = json.strip().startsWith("{") ? new JSONObject(json).getJSONArray("scenarios") : new JSONArray(json); }
        catch (Exception e) { throw new IllegalArgumentException("점검 문장 파일을 읽지 못했어요: " + e.getMessage()); }
        Set<String> tools = new LinkedHashSet<>(toolNames());
        List<Scenario> out = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject one = all.optJSONObject(i), expect = one == null ? null : one.optJSONObject("expect");
            String where = (i + 1) + "번째";
            if (one == null || one.optString("said").isBlank() || expect == null) throw new IllegalArgumentException(where + " 문장에 said와 expect가 있어야 해요.");
            String said = one.optString("said").strip(), why = one.optString("why").strip();
            int kinds = 0;
            String tool = null, turn = null, name = null; JSONObject args = null; Object music = null; boolean none = false;
            if (expect.has("tool")) { kinds++; tool = expect.optString("tool").strip(); if (!tools.contains(tool)) throw new IllegalArgumentException(where + " “" + said + "”: 없는 도구 " + tool); args = expect.optJSONObject("args"); }
            if (expect.has("turn")) { kinds++; turn = expect.optString("turn").strip().toUpperCase(Locale.ROOT); if (!TURNS.contains(turn)) throw new IllegalArgumentException(where + " “" + said + "”: 없는 턴 " + turn); }
            if (expect.has("name")) { kinds++; name = expect.optString("name"); }
            if (expect.has("music")) { kinds++; Object m = expect.opt("music"); music = m instanceof String text ? text : Boolean.FALSE; }
            if (expect.optBoolean("none")) { kinds++; none = true; }
            if (kinds != 1) throw new IllegalArgumentException(where + " “" + said + "”: expect에는 tool·turn·name·music·none 중 하나만 써요.");
            out.add(new Scenario(said, tool, args == null ? new JSONObject() : args, turn, name, music, none, why));
        }
        if (out.isEmpty()) throw new IllegalArgumentException("점검 문장이 없어요.");
        return out;
    }

    // ── the words, no AI (as BuddyVoice hears them: the name put back, then what 퍼레 answers itself) ──
    /** Asked to run this check (준: "자가 점검해 줘"): the whole of what was said, short, with or without 퍼레's name. */
    private static final Pattern ASKED = Pattern.compile("(?:들)?(?:퍼레(?:야|아)?)?(?:너|니가|네가)?(?:지금)?(?:자가|자동|스스로|셀프|혼자)(?:점검|진단|테스트|검사)(?:좀)?(?:해|해줘|해봐|하자|해볼래|해줄래|시작|시작해|돌려|돌려줘|해주세요|해줘요|할래)?(?:요)?");
    static boolean asked(String said) {
        String bare = said == null ? "" : said.strip().replaceAll("[\\s\\p{Punct}·…~]+", "");
        return !bare.isEmpty() && bare.codePointCount(0, bare.length()) <= 22 && ASKED.matcher(bare).matches();
    }
    /** What the voice talk hears for said: 퍼레's name put back where the recognizer took it for 벌레, 버려 … */
    static String heard(String said) { return BuddyMind.nameHeard(said); }

    /** The words layer for every scenario, and the checks of the tools themselves (their text form, their names, their scenarios). */
    static List<Result> words(List<Scenario> all) {
        List<Result> out = new ArrayList<>();
        for (Scenario s : all) out.add(word(s));
        out.addAll(tools(all));
        return out;
    }
    static Result word(Scenario s) {
        String heard = heard(s.said());
        if (s.name() != null) {
            String got = heard == null ? "" : heard;
            return Result.of(WORDS, s.said(), s.name().equals(got), "이름 " + s.name(), got, "");
        }
        if (s.music() != null) {
            String got = VoiceHints.musicGate(heard, true);
            String want = s.music() instanceof String w ? w : null;
            boolean pass = want == null ? got == null : want.equals(got);
            return Result.of(WORDS, s.said(), pass, want == null ? "음악 중엔 흘려들음" : "음악 중에 ‘" + want + "’", got == null ? "흘려들음" : "‘" + got + "’", "");
        }
        if (s.turn() != null) {
            if (s.turn().equals("CHECK")) return Result.of(WORDS, s.said(), asked(heard), "자동 점검 열기", asked(heard) ? "자동 점검 열기" : "두뇌로 감", "");
            VoiceTurns.Kind got = VoiceTurns.of(heard, s.turn().equals("HUSH"), s.turn().equals("NO"), s.turn().equals("PAUSE"));
            return Result.of(WORDS, s.said(), got.name().equals(s.turn()), turnLabel(s.turn()), turnLabel(got.name()), "");
        }
        // A thing to do, or nothing: the words layer must let it through to the brain, with the tool among those sent.
        VoiceTurns.Kind got = VoiceTurns.of(heard, false, false, false);
        if (got != VoiceTurns.Kind.NONE) return Result.of(WORDS, s.said(), false, "두뇌로 감", "말 단계에서 가로챔: " + turnLabel(got.name()), "");
        if (asked(heard)) return Result.of(WORDS, s.said(), false, "두뇌로 감", "말 단계에서 가로챔: 자동 점검 열기", "");
        if (s.tool() != null && !names(BuddyTools.forTurn(heard)).contains(s.tool()))
            return Result.of(WORDS, s.said(), false, s.tool() + " 도구를 보냄", s.tool() + "가 보내는 도구 목록에 없음(퍼레 자신의 말로 안 들림)", "");
        return Result.of(WORDS, s.said(), true, "두뇌로 감", "두뇌로 감", "");
    }
    static String turnLabel(String kind) {
        return switch (kind) {
            case "CALL" -> "부름(CALL)"; case "BYE" -> "대화 끝(BYE)"; case "PAUSE" -> "음악 멈춤(PAUSE)"; case "REPEAT" -> "다시 말하기(REPEAT)";
            case "HUSH" -> "잠깐 듣기(HUSH)"; case "NO" -> "취소(NO)"; case "CHECK" -> "자동 점검 열기"; default -> "두뇌로 감(NONE)";
        };
    }
    private static Set<String> names(JSONArray tools) {
        Set<String> out = new LinkedHashSet<>();
        for (int i = 0; tools != null && i < tools.length(); i++) { JSONObject f = tools.optJSONObject(i) == null ? null : tools.optJSONObject(i).optJSONObject("function"); if (f != null) out.add(f.optString("name")); }
        return out;
    }
    /** The tools themselves: each in the text form, read back as itself, every other name pointing at one, a well-formed schema, and a scenario for each. */
    static List<Result> tools(List<Scenario> all) {
        List<Result> out = new ArrayList<>();
        List<String> names = toolNames(), notInText = new ArrayList<>(), misread = new ArrayList<>(), badSchema = new ArrayList<>();
        for (String n : names) {
            if (!BuddyPrompt.ACTS.contains("\"tool\":\"" + n + "\"")) notInText.add(n);
            JSONObject a = BuddyPrompt.act("알겠어!\n할일: {\"tool\":\"" + n + "\"}");
            if (a == null || !n.equals(a.optString("tool"))) misread.add(n + "→" + (a == null ? "없음" : a.optString("tool")));
        }
        for (int i = 0; i < BuddyTools.TOOLS.length(); i++) {
            JSONObject f = BuddyTools.TOOLS.optJSONObject(i).optJSONObject("function"), p = f.optJSONObject("parameters");
            JSONObject props = p == null ? null : p.optJSONObject("properties"); JSONArray req = p == null ? null : p.optJSONArray("required");
            boolean ok = p != null && "object".equals(p.optString("type")) && props != null && !f.optString("description").isBlank();
            for (int r = 0; ok && req != null && r < req.length(); r++) ok = props.has(req.optString(r));
            if (!ok) badSchema.add(f.optString("name"));
        }
        List<String> aliasWrong = new ArrayList<>();
        for (java.util.Map.Entry<String, String> e : BuddyPrompt.ALIASES.entrySet()) {
            JSONObject a = BuddyPrompt.loose("{\"tool\":\"" + e.getKey() + "\"}");
            if (!names.contains(e.getValue()) || a == null || !e.getValue().equals(a.optString("tool"))) aliasWrong.add(e.getKey() + "→" + e.getValue());
        }
        Set<String> covered = new LinkedHashSet<>(); for (Scenario s : all) if (s.tool() != null) covered.add(s.tool());
        List<String> uncovered = new ArrayList<>(); for (String n : names) if (!covered.contains(n)) uncovered.add(n);
        out.add(Result.tools("도구 " + names.size() + "개가 글 형식(할일:)에 있어요", notInText.isEmpty(), "모두 있음", notInText.isEmpty() ? "모두 있음" : "빠짐: " + String.join(", ", notInText)));
        out.add(Result.tools("글 형식의 도구 이름을 그대로 읽어요", misread.isEmpty(), "모두 그대로", misread.isEmpty() ? "모두 그대로" : String.join(", ", misread)));
        out.add(Result.tools("도구 별명 " + BuddyPrompt.ALIASES.size() + "개가 진짜 도구로 가요", aliasWrong.isEmpty(), "모두 진짜 도구", aliasWrong.isEmpty() ? "모두 진짜 도구" : String.join(", ", aliasWrong)));
        out.add(Result.tools("도구 설명·인자 형식", badSchema.isEmpty(), "모두 맞음", badSchema.isEmpty() ? "모두 맞음" : "틀림: " + String.join(", ", badSchema)));
        out.add(Result.tools("도구마다 점검 문장이 있어요", uncovered.isEmpty(), "모두 있음", uncovered.isEmpty() ? "모두 있음" : "없음: " + String.join(", ", uncovered)));
        return out;
    }

    // ── the brain's answer, judged ──
    /**
     * A brain's answer to s: act the thing it would do (null: none), answer what it said; failed why no brain answered (null:
     * it did). A tool passes with the same tool and the arguments that matter; nothing passes with no act.
     */
    static Result judge(Scenario s, String path, JSONObject act, String answer, String failed, long toolMs, long wordsMs) {
        String want = s.none() ? "할 일 없음" : describe(s.tool(), s.args());
        if (failed != null) return new Result(BRAIN, path, s.said(), false, want, "답 없음: " + failed, "", toolMs, wordsMs);
        boolean pass = s.none() ? act == null : act != null && s.tool().equals(act.optString("tool")) && argsMatch(s.args(), act);
        String got = act == null ? (s.none() ? "할 일 없음" : "도구 안 부름" + (answer == null || answer.isBlank() ? "" : ": “" + BuddyPrompt.cut(answer.strip(), 40) + "”")) : describe(act.optString("tool"), act);
        return new Result(BRAIN, path, s.said(), pass, want, got, "", toolMs, wordsMs);
    }
    /** "alarm · hour=15 · minute=0": a tool and its arguments (the tool key left out), short. */
    static String describe(String tool, JSONObject args) {
        StringBuilder out = new StringBuilder(tool == null ? "" : tool);
        if (args != null) for (java.util.Iterator<String> k = args.keys(); k.hasNext(); ) {
            String key = k.next(); if (key.equals("tool")) continue;
            Object v = args.opt(key); String text = v instanceof JSONArray a ? join(a, ",") : String.valueOf(v);
            if (text.isBlank()) continue;
            out.append(" · ").append(key).append('=').append(BuddyPrompt.cut(text, 24));
        }
        return BuddyPrompt.cut(out.toString(), 90);
    }
    /**
     * Whether act has what want says: a word (any of "a|b") found in its value, the same number or yes/no, each of a list
     * found in its list. Spaces, marks and case do not count.
     */
    static boolean argsMatch(JSONObject want, JSONObject act) {
        if (want == null) return true;
        for (java.util.Iterator<String> k = want.keys(); k.hasNext(); ) {
            String key = k.next(); Object w = want.opt(key), g = act.opt(key);
            if (w instanceof Boolean b) { if (!(g instanceof Boolean gb ? gb == b : String.valueOf(g).equalsIgnoreCase(String.valueOf(b)))) return false; continue; }
            if (w instanceof Number n) { Double d = number(g); if (d == null || Math.abs(d - n.doubleValue()) > 1e-6) return false; continue; }
            String gotText = g == null || g == JSONObject.NULL ? "" : g instanceof JSONArray a ? join(a, " ") : String.valueOf(g);
            if (w instanceof JSONArray list) { for (int i = 0; i < list.length(); i++) if (!found(gotText, list.optString(i))) return false; continue; }
            if (!found(gotText, String.valueOf(w))) return false;
        }
        return true;
    }
    /** A list's items joined (Android's JSONArray.join throws and quotes them). */
    private static String join(JSONArray list, String between) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < list.length(); i++) { if (i > 0) out.append(between); out.append(list.opt(i)); }
        return out.toString();
    }
    private static boolean found(String got, String want) {
        String g = norm(got);
        for (String alt : want.split("\\|")) { String a = norm(alt); if (!a.isEmpty() && g.contains(a)) return true; }
        return false;
    }
    private static String norm(String s) { return s == null ? "" : s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{Punct}·…~]+", ""); }
    private static Double number(Object g) {
        if (g instanceof Number n) return n.doubleValue();
        try { return g == null ? null : Double.parseDouble(String.valueOf(g).strip()); } catch (NumberFormatException no) { return null; }
    }

    // ── the report ──
    /** Passed and checked in a layer (null: all layers). */
    static int[] count(List<Result> results, String layer) {
        int pass = 0, all = 0;
        for (Result r : results) if (layer == null || r.layer().equals(layer)) { all++; if (r.pass()) pass++; }
        return new int[]{pass, all};
    }
    static List<Result> failures(List<Result> results) { List<Result> out = new ArrayList<>(); for (Result r : results) if (!r.pass()) out.add(r); return out; }
    /** "통과 92 · 실패 4" and each layer's "말 50/50 · 두뇌 40/44 · 준비 10/12" (a layer not run left out). */
    static String summary(List<Result> results) {
        int[] all = count(results, null);
        StringBuilder out = new StringBuilder("통과 " + all[0] + " · 실패 " + (all[1] - all[0]));
        List<String> parts = new ArrayList<>();
        for (String layer : List.of(WORDS, BRAIN, READY)) { int[] c = count(results, layer); if (c[1] > 0) parts.add(layer + " " + c[0] + "/" + c[1]); }
        return parts.isEmpty() ? out.toString() : out + " (" + String.join(" · ", parts) + ")";
    }
    /** "도구까지 중간 0.9초 · 가장 느림 2.1초 · 첫 말 중간 0.7초" for the brain's answers ("" when none was timed). */
    static String speed(List<Result> results) {
        List<Long> tool = new ArrayList<>(), words = new ArrayList<>();
        for (Result r : results) if (r.layer().equals(BRAIN)) { if (r.toolMs() > 0) tool.add(r.toolMs()); if (r.wordsMs() > 0) words.add(r.wordsMs()); }
        if (tool.isEmpty() && words.isEmpty()) return "";
        List<String> out = new ArrayList<>();
        if (!tool.isEmpty()) { tool.sort(null); out.add("도구까지 중간 " + secs(tool.get(tool.size() / 2)) + " · 가장 느림 " + secs(tool.get(tool.size() - 1))); }
        if (!words.isEmpty()) { words.sort(null); out.add("첫 말 중간 " + secs(words.get(words.size() / 2))); }
        return String.join(" · ", out);
    }
    static String secs(long ms) { return String.format(Locale.ROOT, "%.1f초", ms / 1000.0); }
    /** One failure on a line: "[두뇌·음성] “음악 만들어서 틀어 줘” 기대 make_music · 실제 play · query=음악". */
    static String line(Result r) {
        return "[" + r.tag() + "] " + r.title() + " 기대 " + r.expected() + " · 실제 " + r.got();
    }
    /** The whole report as text (for sharing and the diagnostic report): when, the counts, the brain and its speed, each failure. */
    static String report(List<Result> results, String brain, String when, String stopped) {
        StringBuilder out = new StringBuilder("퍼레 자동 점검").append(when == null || when.isBlank() ? "" : " · " + when).append('\n');
        out.append(summary(results)).append('\n');
        if (brain != null && !brain.isBlank()) out.append("두뇌: ").append(brain).append('\n');
        String speed = speed(results); if (!speed.isEmpty()) out.append("빠르기: ").append(speed).append('\n');
        if (stopped != null && !stopped.isBlank()) out.append("멈춤: ").append(stopped).append('\n');
        List<Result> failed = failures(results);
        if (failed.isEmpty()) out.append("실패 없음\n");
        else { out.append("실패 ").append(failed.size()).append("개:\n"); for (Result r : failed) out.append("- ").append(line(r)).append('\n'); }
        return out.toString().strip();
    }
    /** The report as the diagnostic report's lines (name, value): the counts, the brain, its speed, then the failures, at most most. */
    static List<String[]> lines(List<Result> results, String brain, String when, String stopped, int most) {
        List<String[]> out = new ArrayList<>();
        out.add(new String[]{"결과", summary(results)});
        if (when != null && !when.isBlank()) out.add(new String[]{"점검 시각", when});
        if (brain != null && !brain.isBlank()) out.add(new String[]{"두뇌", brain});
        String speed = speed(results); if (!speed.isEmpty()) out.add(new String[]{"빠르기", speed});
        if (stopped != null && !stopped.isBlank()) out.add(new String[]{"멈춤", stopped});
        int n = 0;
        for (Result r : failures(results)) { if (out.size() >= most) break; out.add(new String[]{"실패 " + (++n), line(r)}); }
        return out;
    }
}
