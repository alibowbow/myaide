package dev.jun.gyeot.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicInteger;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 두뇌 배분 (core/BrainRoutes, Fallback, LocalAnswers, Prices): which failures another model may take over and which never
 * (a stubbed 429 goes to the next model, a stop and a content refusal do not, two failures are never a third try), the
 * note the answer carries, the routing table's chains, the answers made on the phone with no model (and that each agrees
 * with 퍼레 자동 점검's scenarios), and what the ledger's tokens cost. Run by tools/build.py.
 */
final class BrainRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    /** Friday 2026-11-27, 15:05 in Seoul. */
    static final ZonedDateTime NOW = ZonedDateTime.of(2026, 11, 27, 15, 5, 0, 0, ZoneId.of("Asia/Seoul"));

    public static void main(String[] args) throws Exception {
        why();
        once();
        notes();
        chains();
        local();
        Path file = args.length > 0 ? Path.of(args[0]) : Path.of("app/src/main/assets/buddy/scenarios.json");
        scenarios(file);
        memory();
        recall();
        corrections();
        promises();
        talks();
        checking();
        chatWords(file);
        prices();
        foot();
        System.out.println("Brain routing regression checks passed: " + checked);
    }

    // ── which failures another model may take over ──
    static void why() {
        same(Fallback.Why.LIMIT, Fallback.why(new ModelLimit(429)), "a 429 the client confirmed");
        same(Fallback.Why.LIMIT, Fallback.why(new ModelLimit(402)), "402 payment required");
        same(Fallback.Why.ACCOUNT, Fallback.why(new ProviderError(401, ClaudeProtocol.explain(401, "authentication_error", ""))), "401 with its status kept");
        same(Fallback.Why.ACCOUNT, Fallback.why(new ProviderError(403, "x")), "403");
        same(Fallback.Why.GONE, Fallback.why(new ProviderError(404, "x")), "404: the model removed");
        same(Fallback.Why.SERVER, Fallback.why(new ProviderError(503, "x")), "503");
        same(Fallback.Why.SERVER, Fallback.why(new ProviderError(529, "x")), "529 overloaded");
        same(Fallback.Why.TIMEOUT, Fallback.why(new ProviderError(504, "x")), "504");
        same(Fallback.Why.NONE, Fallback.why(new ProviderError(400, "x")), "400 is the request's fault, not the model's");
        same(Fallback.Why.TIMEOUT, Fallback.why(new java.net.SocketTimeoutException("Read timed out")), "a read timeout");
        same(Fallback.Why.TIMEOUT, Fallback.why(new IllegalStateException("wrapped", new java.net.SocketTimeoutException())), "a wrapped timeout");
        same(Fallback.Why.NONE, Fallback.why(new java.net.UnknownHostException("api.anthropic.com")), "offline: another company is as far away");
        // The clients' own Korean explanations, without a status: read from their words.
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(429, "rate_limit_error", ""))), "Claude's 429 words");
        same(Fallback.Why.SERVER, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(529, "overloaded_error", ""))), "Claude's 529 words");
        same(Fallback.Why.ACCOUNT, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(401, "authentication_error", ""))), "Claude's 401 words");
        same(Fallback.Why.GONE, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(404, "not_found_error", ""))), "Claude's 404 words");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(ClaudeProtocol.explain(400, "invalid_request_error", "Your credit balance is too low"))), "no credit");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(NimProtocol.error(429))), "NVIDIA's 429 words");
        same(Fallback.Why.SERVER, Fallback.why(new IllegalStateException(NimProtocol.error(503))), "NVIDIA's 503 words");
        same(Fallback.Why.ACCOUNT, Fallback.why(new IllegalStateException(NimProtocol.error(401))), "NVIDIA's 401 words");
        same(Fallback.Why.SERVER, Fallback.why(new java.io.IOException("Gemini 요청 오류 HTTP 503 (The model is overloaded). 도구·모델은 바꾸지 않았어요.")), "Gemini's own line");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException("Groq 무료 한도를 기다리는 중이에요.")), "Groq waiting for its free tier");
        same(Fallback.Why.LIMIT, Fallback.why(new IllegalStateException(OpenAiProtocol.explain(429, ""))), "ChatGPT's limit words");
        same(Fallback.Why.ACCOUNT, Fallback.why(new IllegalStateException(OpenAiProtocol.explain(401, ""))), "ChatGPT's sign-in expired");
        same(Fallback.Why.NO_KEY, Fallback.why(new IllegalStateException("설정 → Gemini API 키에서 키를 먼저 넣어 주세요.")), "no key");
        // Never handed over.
        same(Fallback.Why.NONE, Fallback.why(new CancellationException("stopped")), "the user's stop");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("x", new CancellationException())), "a stop inside");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("HTTP 429", new InterruptedException())), "an interrupt wins over its status");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException(ClaudeProtocol.refusal("cyber"))), "Claude declined the request");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("NIM 모델이 이 요청에 대한 답변을 거절했어요.")), "NVIDIA declined it");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("Response blocked by SAFETY settings")), "a safety block");
        same(Fallback.Why.NONE, Fallback.why(new IllegalArgumentException("메인 대화의 NVIDIA NIM 연결에서는 쓸 수 없어요: 웹 검색.")), "an option the engine cannot carry");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException("모델이 빈 답변을 보냈어요.")), "an empty answer is not recognised");
        same(Fallback.Why.NONE, Fallback.why(new IllegalStateException(ContextWindow.refusal("Claude", "prompt is too long: 250000 tokens > 200000 maximum"))), "too long: the history is cut instead");
        check(Fallback.refused(new IllegalStateException(ClaudeProtocol.refusal(""))), "a refusal is a refusal");
        check(!Fallback.refused(new ProviderError(429, ClaudeProtocol.explain(429, "rate_limit_error", ""))), "a limit is not a refusal");
        check(Fallback.cancelled(new IllegalStateException("x", new CancellationException())), "a stop is found inside");
    }

    // ── the one hop ──
    record Brain(String who, String label) {}
    static final Brain CLAUDE = new Brain("Claude", "Claude Opus 5.5"), GPT = new Brain("ChatGPT", "GPT-6.1 Terra");
    interface Answering { String answer(Brain b) throws Exception; }
    static Fallback.Answer<Brain, String> ask(List<Brain> chain, AtomicInteger tries, Answering answer, boolean fresh, Runnable guard) throws Exception {
        return Fallback.once(chain, Brain::who, Brain::label, b -> { tries.incrementAndGet(); return answer.answer(b); }, () -> fresh, guard);
    }
    static void once() throws Exception {
        List<Brain> both = List.of(CLAUDE, GPT);
        // Fails first: a stubbed 429 on Claude goes to GPT, once, and the note says so.
        AtomicInteger tries = new AtomicInteger();
        Fallback.Answer<Brain, String> a = ask(both, tries, b -> { if (b == CLAUDE) throw new ModelLimit(429); return "GPT의 답"; }, true, () -> {});
        same("GPT의 답", a.value(), "the next model answered"); same(GPT, a.used(), "who answered"); same(2, tries.get(), "two tries");
        same("Claude 한도 → GPT-6.1 Terra로 답했어요", a.note(), "the note"); check(a.hopped(), "hopped");
        // The chosen one answering: no hop, no note.
        tries.set(0); a = ask(both, tries, b -> "Claude의 답", true, () -> {});
        same(CLAUDE, a.used(), "first answered"); same("", a.note(), "no note"); same(1, tries.get(), "one try");
        // A stop is never handed on.
        tries.set(0);
        try { ask(both, tries, b -> { throw new CancellationException("사용자가 멈춤"); }, true, () -> {}); check(false, "a stop must be thrown"); }
        catch (CancellationException stopped) { same(1, tries.get(), "a stop: one try"); }
        // Nor a content refusal.
        tries.set(0);
        try { ask(both, tries, b -> { throw new IllegalStateException(ClaudeProtocol.refusal("bio")); }, true, () -> {}); check(false, "a refusal must be thrown"); }
        catch (IllegalStateException refused) { same(1, tries.get(), "a refusal: one try"); check(refused.getMessage().contains("답하지 않았어요"), "the refusal itself is told"); }
        // Never a loop: both failing is two tries and one error naming both.
        tries.set(0);
        try { ask(List.of(CLAUDE, GPT, new Brain("Grok", "Grok 4.7")), tries, b -> { throw new ModelLimit(429); }, true, () -> {}); check(false, "two failures must be thrown"); }
        catch (IllegalStateException both429) {
            same(2, tries.get(), "no third try"); check(both429.getMessage().startsWith("Claude 한도 → GPT-6.1 Terra도 답하지 못했어요"), "names both: " + both429.getMessage());
            same(1, both429.getSuppressed().length, "the first failure kept");
        }
        // Words already reached the user (or a tool ran): not sent again.
        tries.set(0);
        try { ask(both, tries, b -> { throw new ProviderError(503, "x"); }, false, () -> {}); check(false, "must be thrown"); }
        catch (ProviderError kept) { same(1, tries.get(), "nothing resent once words were shown"); }
        // Stopped between the two: the guard throws and the second is never asked.
        tries.set(0);
        try { ask(both, tries, b -> { throw new ProviderError(503, "x"); }, true, () -> { throw new CancellationException(); }); check(false, "must be thrown"); }
        catch (CancellationException stopped) { same(1, tries.get(), "stopped before the hop"); }
        // Only one model: the failure stands.
        tries.set(0);
        try { ask(List.of(CLAUDE), tries, b -> { throw new ModelLimit(429); }, true, () -> {}); check(false, "must be thrown"); }
        catch (ModelLimit alone) { same(1, tries.get(), "alone: one try"); }
        // The second stopped by the user is a stop, not "both failed".
        tries.set(0);
        try { ask(both, tries, b -> { if (b == CLAUDE) throw new ProviderError(500, "x"); throw new CancellationException(); }, true, () -> {}); check(false, "must be thrown"); }
        catch (CancellationException stopped) { same(2, tries.get(), "the stop of the second"); }
    }
    static void notes() {
        same("Claude 서버 오류 → GPT-6으로 답했어요", Fallback.note(Fallback.Why.SERVER, "Claude", "GPT-6"), "6 is 육: 으로");
        same("ChatGPT 계정 거절 → Claude Sonnet 5.5로 답했어요", Fallback.note(Fallback.Why.ACCOUNT, "ChatGPT", "Claude Sonnet 5.5"), "5 is 오: 로");
        same("Grok 4.7로", Fallback.euro("Grok 4.7"), "7 is 칠: 로");
        same("Grok으로", Fallback.euro("Grok"), "그록: 으로");
        same("Nemotron Ultra로", Fallback.euro("Nemotron Ultra"), "울트라: 로");
        same("GPT OSS 120B로", Fallback.euro("GPT OSS 120B"), "비: 로");
        same("Qwen으로", Fallback.euro("Qwen"), "큐웬: 으로");
        same("Gemini 3으로", Fallback.euro("Gemini 3"), "삼: 으로");
        same("클로드로", Fallback.euro("클로드"), "Hangul without a final");
        same("그록으로", Fallback.euro("그록"), "Hangul with one");
        same("서울로", Fallback.euro("서울"), "ㄹ: 로");
        same("ChatGPT로", Fallback.euro("ChatGPT"), "티: 로");
        same("Claude Sonnet으로", Fallback.euro("Claude Sonnet"), "소넷: 으로");
    }

    // ── the routing table ──
    static void chains() {
        java.util.function.Predicate<String> all = e -> true;
        same(List.of(WorkBook.CLAUDE, WorkBook.CHATGPT), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, true, all), "Claude → ChatGPT");
        same(List.of(WorkBook.CHATGPT, WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CHATGPT, true, all), "ChatGPT → Claude");
        same(List.of(WorkBook.GEMINI, WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.GEMINI, true, all), "a chosen Gemini still answers first");
        same(List.of(WorkBook.GROQ, WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.GROQ, true, all), "a chosen Groq too");
        same(List.of(WorkBook.CLAUDE, WorkBook.NIM), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, true, e -> e.equals(WorkBook.NIM) || e.equals(WorkBook.GEMINI)), "only NVIDIA connected: Gemini never chosen for the user");
        same(List.of(WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, true, e -> e.equals(WorkBook.GEMINI) || e.equals(WorkBook.GROQ) || e.equals(WorkBook.ANTIGRAVITY)), "Gemini, Groq, Antigravity never take over");
        same(List.of(WorkBook.CLAUDE), BrainRoutes.chain(BrainRoutes.Kind.WORK, WorkBook.CLAUDE, false, all), "turned off: no hop");
        same(List.of(WorkBook.GROQ), BrainRoutes.chain(BrainRoutes.Kind.TALK, WorkBook.GROQ, true, all), "TALK keeps its own race");
        same(List.of(), BrainRoutes.chain(BrainRoutes.Kind.WORK, "", true, all), "nothing chosen");
        for (String chosen : List.of(WorkBook.CLAUDE, WorkBook.CHATGPT, WorkBook.GROK, WorkBook.NIM, WorkBook.GEMINI, WorkBook.GROQ, WorkBook.ANTIGRAVITY))
            check(BrainRoutes.chain(BrainRoutes.Kind.WORK, chosen, true, all).size() <= 2, "one hop at most from " + chosen);
        // 메인 대화's decision to hand a failed turn over (MainChatEngine.handoff).
        same(Fallback.Why.LIMIT, BrainRoutes.hop(new ModelLimit(429), true, false, false, false), "a stubbed 429: the next model");
        same(Fallback.Why.NONE, BrainRoutes.hop(new CancellationException(), true, false, false, false), "a stop: never");
        same(Fallback.Why.NONE, BrainRoutes.hop(new IllegalStateException(ClaudeProtocol.refusal("cyber")), true, false, false, false), "a content refusal: never");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), true, false, false, true), "already handed over once: never a loop");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), true, true, false, false), "words already shown: not sent again");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), true, false, true, false), "a function already ran: not sent again");
        same(Fallback.Why.NONE, BrainRoutes.hop(new ModelLimit(429), false, false, false, false), "the user turned it off");
        same(Fallback.Why.SERVER, BrainRoutes.hop(new ProviderError(503, "x"), true, false, false, false), "a 503");
        same(BrainRoutes.Kind.TALK, BrainRoutes.kind("buddy"), "퍼레 is talk");
        same(BrainRoutes.Kind.LIGHT, BrainRoutes.kind("chores"), "잡일 is light");
        same(BrainRoutes.Kind.WORK, BrainRoutes.kind("main"), "메인 대화 is work");
        same(BrainRoutes.Kind.WORK, BrainRoutes.kind("browser"), "the browser is work");
        same(BrainRoutes.Kind.DEVICE, BrainRoutes.kind("device"), "기기 안");
        same(List.of("groq", "gemma", "lite"), BrainRoutes.LIGHT_ORDER, "잡일: Groq first, the user's rule about Gemini's free tier");
        same("퍼레", BrainRoutes.surfaceLabel("buddy"), "a surface in words");
        check(!BrainRoutes.WORK_FALLBACK.contains(WorkBook.GEMINI), "Gemini is never chosen for the user");
    }

    // ── 기기 안 ──
    static LocalAnswers.Answer at(String said) { return LocalAnswers.of(said, NOW, null); }
    static void local() {
        same("지금 오후 3:05야.", at("지금 몇 시야").say(), "the time");
        same("time", at("퍼레야 몇 시야?").rule(), "with the name and a question mark");
        same("time", at("몇시").rule(), "short");
        same("오늘은 11월 27일 금요일이야.", at("오늘 며칠이야").say(), "the date");
        same("date", at("오늘 무슨 요일이야?").rule(), "the weekday");
        JSONObject timer = at("3분 타이머 맞춰 줘").act();
        same("timer", timer.optString("tool"), "a timer"); same(180L, timer.optLong("seconds"), "three minutes");
        same(5400L, at("타이머 1시간 30분 맞춰 줘").act().optLong("seconds"), "an hour and a half");
        same(10L, at("10초 타이머").act().optLong("seconds"), "ten seconds");
        same("coin", at("동전 던져 줘").act().optString("tool"), "a coin");
        same("dice", at("주사위 굴려 줘").act().optString("tool"), "a die");
        check(at("손전등 켜 줘").act().optBoolean("on"), "the torch on");
        check(!at("플래시 꺼 줘").act().optBoolean("on", true), "the torch off");
        JSONObject sum = at("12 곱하기 7은?").act();
        same("calculator", sum.optString("tool"), "a sum goes to the calculator tool"); same("12 곱하기 7", sum.optString("expression"), "its expression");
        same("35000원의 15퍼센트", at("35000원의 15퍼센트 계산해 줘").act().optString("expression"), "a percentage");
        same("(3+4)^2", at("(3+4)^2 얼마야").act().optString("expression"), "brackets");
        same("카카오톡", at("카카오톡 열어 줘").act().optString("app"), "an app by its name");
        same("유튜브", at("유튜브 꺼 줘").act().optString("app"), "an app closed by its name");
        LocalAnswers.Sky fresh = new LocalAnswers.Sky("사천읍", 12, "맑음", 15, 3, 5 * 60_000L);
        same("지금 사천읍 12도, 맑음이야. 오늘은 최고 15도, 최저 3도야.", LocalAnswers.of("오늘 날씨 어때", NOW, fresh).say(), "the weather kept");
        // Not these: the brain answers them.
        for (String talk : List.of("몇 시에 만날까?", "몇 시간 걸려?", "시간 있어?", "뉴욕은 지금 몇 시야", "크리스마스까지 며칠 남았어", "오늘 뭐 했지",
                "3분 타이머 말고 5분", "타이머 꺼 줘", "타이머 화면 보여 줘", "주사위 꺼내 줘", "동전 어디 있어", "손전등 어디 있어",
                "010-1234-5678", "2026-11-27", "3-2", "12시 30분", "1+1은 귀요미", "녹음 앱 켜 줘", "사진첩 열어 줘", "네이버 홈페이지 열어 줘",
                "방해 금지 설정 열어 줘", "지금 켜진 앱 꺼 줘", "음악 꺼 줘", "불 켜 줘", "유튜브 열고 아이유 틀어 줘", "오늘 기분 어때", "심심해",
                "지금 몇 시야 그리고 날씨도 알려 줘", "내일 아침 7시 30분에 알람 맞춰 줘"))
            check(at(talk) == null, "goes to the brain: " + talk + " → " + (at(talk) == null ? "" : at(talk).rule()));
        check(LocalAnswers.of("오늘 날씨 어때", NOW, null) == null, "no weather kept: the brain");
        check(LocalAnswers.of("오늘 날씨 어때", NOW, new LocalAnswers.Sky("", 12, "맑음", null, null, 31 * 60_000L)) == null, "stale weather: the brain");
        same("3-2", at("3-2는 얼마야").act().optString("expression"), "a bare minus asked as a sum");
    }
    /**
     * Every 퍼레 자동 점검 scenario (assets/buddy/scenarios.json): where 기기 안 answers it, it does the very tool expected, or
     * nothing when nothing is to be done. How many it answers is printed (the measure).
     */
    static void scenarios(Path file) throws Exception {
        JSONObject json = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        JSONArray all = json.getJSONArray("scenarios"); int here = 0; List<String> rules = new ArrayList<>();
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.getJSONObject(i), expect = s.getJSONObject("expect");
            if (!expect.has("tool") && !expect.optBoolean("none")) continue;
            LocalAnswers.Answer a = LocalAnswers.of(BuddyMind.nameHeard(s.getString("said")), NOW, null);
            if (a == null) continue;
            here++; rules.add(a.rule());
            if (expect.optBoolean("none")) { check(a.act() == null, "nothing to do, yet 기기 안 does " + a.act() + ": " + s.getString("said")); continue; }
            check(a.act() != null && expect.getString("tool").equals(a.act().optString("tool")), "기기 안 must do " + expect.getString("tool") + ": " + s.getString("said") + " → " + (a.act() == null ? a.rule() : a.act()));
            JSONObject want = expect.optJSONObject("args");
            for (java.util.Iterator<String> k = want == null ? java.util.Collections.<String>emptyIterator() : want.keys(); k.hasNext(); ) {
                String key = k.next(); Object w = want.get(key), g = a.act().opt(key);
                if (w instanceof Number n) check(g instanceof Number gn && gn.doubleValue() == n.doubleValue(), key + " of " + s.getString("said"));
                else if (w instanceof Boolean b) check(b.equals(g), key + " of " + s.getString("said"));
                else { boolean found = false; for (String alt : String.valueOf(w).split("\\|")) found |= String.valueOf(g).replace(" ", "").toLowerCase().contains(alt.replace(" ", "").toLowerCase()); check(found, key + " of " + s.getString("said") + ": " + g); }
            }
        }
        check(here >= 8, "기기 안 answers the scenarios it should: " + here + " " + rules);
        System.out.println("기기 안 answered " + here + " of the scenarios with a tool or nothing to do: " + rules);
        // 퍼레와 대화 sends the whole list of what it can do only when the words may ask for something (준: "퍼레 대화는 간단한 잡담인데
        // 설정 인풋이 많나보네"): every scenario with a thing to do goes with it at once (a miss would cost a second request).
        int acts = 0, plain = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.getJSONObject(i), expect = s.getJSONObject("expect");
            if (!expect.has("tool")) continue;
            acts++; check(BuddyMind.mayAct(BuddyMind.nameHeard(s.getString("said"))), "a thing to do taken for chitchat (the whole list would not go): " + s.getString("said"));
        }
        for (String talk : new String[]{"안녕", "뭐해?", "심심해", "오늘 너무 피곤하다", "고마워", "ㅋㅋㅋ 귀엽다", "잘 자", "사랑해", "배고프다", "오늘 날씨 좋네", "너는 누구야?", "기분이 좀 우울해"}) {
            plain++; check(!BuddyMind.mayAct(talk), "chitchat taken for a thing to do (the whole list would go): " + talk);
        }
        System.out.println("퍼레와 대화: " + acts + " things to do go with the whole list, " + plain + " chitchats with its one line");
    }

    // ── 퍼레와 대화's working memory (core/ChatMemory) ──
    static List<ChatMemory.Turn> talk(int n, int longAt) {
        List<ChatMemory.Turn> out = new ArrayList<>();
        for (int i = 0; i < n; i++) out.add(new ChatMemory.Turn(i % 2 == 0, (i == longAt ? "긴 이야기 ".repeat(200) : "말 " + i + "번째, 오늘 있었던 일 이야기") ));
        return out;
    }
    static void memory() {
        same(10, ChatMemory.tokens("가나다라마바사아자차카타파"), "13 Hangul letters: about 10 tokens");
        same(10, ChatMemory.tokens("abcdefghijklmnopqrstuvwxyz0123"), "30 ASCII characters: 10 tokens");
        same(0, ChatMemory.tokens(""), "nothing");
        // The last 20 turns go whole (before: six, each cut at 1,200): the 20th from the end is there, a 1,000-letter turn uncut.
        List<ChatMemory.Turn> twenty = talk(20, 3);
        ChatMemory.Plan all = ChatMemory.plan(twenty, "", "");
        same(0, all.from(), "twenty turns: all whole"); check(!all.due(), "no summary for twenty");
        String in = ChatMemory.input(twenty, all, "아까 뭐라고 했지?", Integer.MAX_VALUE);
        check(in.contains("말 0번째"), "the oldest of twenty is kept: " + in.substring(0, 80));
        check(in.contains("긴 이야기 ".repeat(200).strip()), "a 1,000-letter turn is not cut");
        check(in.endsWith("사용자의 새 질문: 아까 뭐라고 했지?"), "the question last");
        List<ChatMemory.Turn> huge = List.of(new ChatMemory.Turn(true, "가".repeat(2_000)));
        String cut = ChatMemory.input(huge, ChatMemory.plan(huge, "", ""), "응?", Integer.MAX_VALUE);
        check(cut.contains("가".repeat(1_500) + "…") && !cut.contains("가".repeat(1_501)), "only a turn over 1,500 letters is cut");
        same("오늘 뭐해?", ChatMemory.input(List.of(), ChatMemory.plan(List.of(), "", ""), "오늘 뭐해?", 8_000), "no talk: the question alone");
        // A longer talk: up to 29 whole while no summary is due; at 30 a summary of the first ten is due, the older part left out.
        ChatMemory.Plan p29 = ChatMemory.plan(talk(29, -1), "", "");
        same(0, p29.from(), "29 turns: all whole"); check(!p29.due(), "nine outside the last 20: not yet a block");
        List<ChatMemory.Turn> thirty = talk(30, -1);
        ChatMemory.Plan p30 = ChatMemory.plan(thirty, "", "");
        check(p30.due(), "a block of ten outside the last 20: its summary is due"); same(0, p30.dueFrom(), "from the first"); same(9, p30.dueTo(), "to the tenth");
        same(1, p30.from(), "until it exists, 29 whole and the older part left out"); same("", p30.summary(), "no summary yet");
        check(!ChatMemory.input(thirty, p30, "?", Integer.MAX_VALUE).contains("말 0번째"), "the oldest is left out");
        // Made: the summary first, the 20 after it whole; not due again until ten more turns.
        String summary = "- 사용자는 오늘 발표를 잘 마쳤대.";
        ChatMemory.Plan made = ChatMemory.plan(thirty, p30.dueMark(), summary);
        same(summary, made.summary(), "the kept summary is used"); same(10, made.from(), "the turns after it whole"); check(!made.due(), "not made again every message");
        String withSummary = ChatMemory.input(thirty, made, "그래서?", Integer.MAX_VALUE);
        check(withSummary.startsWith("앞의 대화 요약:\n" + summary), "the summary first"); check(withSummary.contains("말 10번째") && !withSummary.contains("말 9번째"), "the summarized turns are not sent again");
        for (int n = 31; n < 40; n++) check(!ChatMemory.plan(talk(n, -1), p30.dueMark(), summary).due(), "no new summary at " + n + " turns");
        ChatMemory.Plan p40 = ChatMemory.plan(talk(40, -1), p30.dueMark(), summary);
        check(p40.due(), "ten more: the next block"); same(10, p40.dueFrom(), "from where the summary ended"); same(19, p40.dueTo(), "to the next ten"); same(summary, p40.summary(), "built on the kept one");
        check(ChatMemory.summaryInput(summary, talk(40, -1), 10, 19).startsWith("앞의 요약:\n" + summary), "the chores brain gets the summary so far");
        // A kept summary that no longer fits (a turn deleted on the page, another talk): not used.
        List<ChatMemory.Turn> edited = new ArrayList<>(thirty); edited.remove(9);
        same("", ChatMemory.plan(edited, p30.dueMark(), summary).summary(), "a mark not found: no summary");
        // Groq's minute: over the room, the oldest whole turns go first, then the summary; the question always stays.
        String small = ChatMemory.input(thirty, made, "그래서?", 120);
        check(small.contains("말 29번째") && !small.contains("말 10번째"), "the newest kept, the oldest dropped: " + small);
        check(ChatMemory.tokens(small) <= 120, "within the room: " + ChatMemory.tokens(small));
        same("그래서?", ChatMemory.input(thirty, made, "그래서?", 0), "no room: the question alone");
        // The default brain's whole request fits Groq's 8,000 tokens a minute with the whole list of what 퍼레 can do.
        String rules = "규칙 ".repeat(2_000) + "{\"tool\":\"open_app\",\"app\":\"앱 이름\"} ".repeat(60);
        List<ChatMemory.Turn> chatty = new ArrayList<>();
        for (int i = 0; i < 40; i++) chatty.add(new ChatMemory.Turn(i % 2 == 0, "오늘 있었던 일을 길게 이야기해 볼게. ".repeat(12)));
        String fitted = ChatMemory.input(chatty, ChatMemory.plan(chatty, "", ""), "응", ChatMemory.room(ChatMemory.GROQ_MINUTE, rules, false));
        check(ChatMemory.tokens(rules) + ChatMemory.tokens(fitted) + ChatMemory.ANSWER <= ChatMemory.GROQ_MINUTE - ChatMemory.MARGIN, "the request within Groq's minute: " + (ChatMemory.tokens(rules) + ChatMemory.tokens(fitted)));
        check(fitted.contains(chatty.get(39).text()), "the last turn still goes");
        check(ChatMemory.room(ChatMemory.GROQ_MINUTE, rules, true) < ChatMemory.room(ChatMemory.GROQ_MINUTE, rules, false), "a full answer leaves the talk less room");
        same("", ChatMemory.summary("없음"), "nothing to keep"); same("- 발표 잘함", ChatMemory.summary("<think>음</think>요약: - **발표** 잘함"), "thinking and marks out");
        System.out.println("퍼레와 대화: " + ChatMemory.KEEP + " turns whole, a summary every " + ChatMemory.BLOCK + " more; a request of " + (ChatMemory.tokens(rules) + ChatMemory.tokens(fitted) + ChatMemory.ANSWER) + " tokens with the whole list");
    }

    // ── how 퍼레와 대화 reads a question and an answer (core/BuddyChat), and its sentences in scenarios.json ──
    static void chatWords(Path file) throws Exception {
        for (String q : List.of("김치찌개 끓이는 법 알려 줘", "아이폰이랑 갤럭시 차이 비교해 줘", "엄마 생신 축하 편지 써 줘", "제주도 2박 3일 여행 계획 짜 줘", "광합성이 뭔지 자세히 설명해 줘", "집중 잘 되는 방법 5가지"))
            check(BuddyChat.wantsLong(q), "a full answer may be needed: " + q);
        for (String q : List.of("안녕", "뭐해?", "심심해", "고마워", "ㅋㅋㅋ 귀엽다", "오늘 너무 피곤하다", "왜?"))
            check(!BuddyChat.wantsLong(q), "small talk stays short: " + q);
        for (String q : List.of("오늘 환율 얼마야?", "삼성전자 주가 어때?", "오늘 뉴스 뭐 있어?", "어제 한국 축구 경기 결과 알려 줘", "스타벅스 강남점 몇 시까지 해?", "내일 부산 날씨 어때?", "비트코인 지금 얼마야"))
            check(BuddyChat.asksNow(q), "the world now, from the web: " + q);
        for (String q : List.of("오늘 환율 검색해 줘", "구글에서 비트코인 시세 찾아봐 줘", "뉴스 틀어 줘", "비트코인 1억 넘으면 알려 줘", "평일 저녁 6시마다 오늘 뉴스 정리해 줘", "비 와서 우울해",
                "오늘 날씨 좋네", "어제 본 뉴스 뭐였지?", "안녕", "하늘은 왜 파래"))
            check(!BuddyChat.asksNow(q), "not a web answer: " + q);
        check(BuddyChat.opensSearch("오늘 환율 검색해 줘") && !BuddyChat.opensSearch("오늘 환율 얼마야?"), "a search opened only when asked");
        for (String a : List.of("찾아볼게!", "응, 알아볼게 잠깐만", "확인해 볼게!", "유튜브 열어 줄게", "I'll check that for you.", "Let me look it up", "도착하면 알려 줄게"))
            check(BuddyChat.promised(a), "a promise with no 할일 line: " + a);
        for (String a : List.of("찾아볼까?", "오늘 기분 좋아 보여!", "알겠어!\n할일: {\"tool\":\"search\",\"query\":\"환율\"}", "내가 옆에 있어 줄게", "응응 ㅎㅎ"))
            check(!BuddyChat.promised(a), "not a bare promise: " + a);
        for (String t : List.of("day_recall", "calculator", "world_time", "count_days", "coin", "dice", "pick"))
            check(BuddyChat.informs(new JSONObject().put("tool", t)), "its result is said: " + t);
        check(BuddyChat.informs(new JSONObject().put("tool", "stopwatch").put("command", "check")) && !BuddyChat.informs(new JSONObject().put("tool", "stopwatch").put("command", "start")), "a stopwatch read, not started");
        check(BuddyChat.informs(new JSONObject().put("tool", "when_event").put("command", "list")) && !BuddyChat.informs(new JSONObject().put("tool", "when_event").put("when", "집")), "the conditions listed, not one set");
        for (String t : List.of("open_app", "alarm", "timer", "torch", "volume", "calendar_view", "memo", "search", "navigate", "play"))
            check(!BuddyChat.informs(new JSONObject().put("tool", t)), "only a short confirmation: " + t);
        check(BuddyChat.faithful("35000의 15%는 5250이야!", "35000원의 15퍼센트 계산해 줘", "5,250원이야! 쇼핑할 때 딱 좋겠다"), "the number kept (its comma read)");
        check(!BuddyChat.faithful("35000의 15%는 5250이야!", "35000원의 15퍼센트 계산해 줘", "5300원쯤이야!"), "a number made up");
        check(!BuddyChat.faithful("크리스마스까지 27일 남았어!", "크리스마스까지 며칠 남았어", "곧 크리스마스야, 두근두근!"), "the answer's number dropped");
        check(BuddyChat.faithful("앞면 나왔어!", "동전 던져 줘", "짠, 앞면이야!") && !BuddyChat.faithful("앞면 나왔어!", "동전 던져 줘", "뒷면이네!"), "the side as it fell");
        check(BuddyChat.faithful("‘짬뽕’으로 가자!", "짜장면이랑 짬뽕 중에 골라 줘", "오늘은 짬뽕 먹자!"), "no numbers: words kept");
        // Every 퍼레 자동 점검 sentence with a thing to do still goes to the brain (the web answer never takes one), and its
        // search sentence was asked to open one; the "chat" sentences say what 퍼레와 대화 makes of them.
        JSONObject json = new JSONObject(new String(Files.readAllBytes(file), StandardCharsets.UTF_8));
        JSONArray all = json.getJSONArray("scenarios"); int tools = 0;
        for (int i = 0; i < all.length(); i++) {
            JSONObject s = all.getJSONObject(i), expect = s.getJSONObject("expect"); String said = BuddyMind.nameHeard(s.getString("said"));
            if (expect.has("tool")) { tools++; check(!BuddyChat.asksNow(said), "a thing to do taken for a web answer: " + said); if ("search".equals(expect.getString("tool"))) check(BuddyChat.opensSearch(said), "the search asked to be opened: " + said); }
            if (expect.optBoolean("none")) check(!BuddyChat.asksNow(said), "talk taken for a web answer: " + said);
        }
        JSONArray chat = json.getJSONArray("chat"); int web = 0, longs = 0, promises = 0;
        for (int i = 0; i < chat.length(); i++) {
            JSONObject s = chat.getJSONObject(i);
            if (s.has("web")) { web++; same(s.getBoolean("web"), BuddyChat.asksNow(s.getString("said")), "web for “" + s.getString("said") + "”"); }
            if (s.has("long")) { longs++; same(s.getBoolean("long"), BuddyChat.wantsLong(s.getString("said")), "a full answer for “" + s.getString("said") + "”"); }
            if (s.has("promise")) { promises++; same(s.getBoolean("promise"), BuddyChat.promised(s.getString("answer")), "a promise in “" + s.getString("answer") + "”"); }
        }
        check(web >= 8 && longs >= 6 && promises >= 6, "the chat sentences: " + web + " web, " + longs + " long, " + promises + " promises");
        System.out.println("퍼레와 대화's words: " + tools + " things to do never taken for the web; chat sentences " + web + " web, " + longs + " long, " + promises + " promises");
    }

    // ── 기억 2.0: memory found by meaning and by what is asked (core/BuddyRecall) ──
    static final long DAY = 86_400_000L, T = NOW.toInstant().toEpochMilli();
    static float[] vec(double... v) { float[] f = new float[v.length]; double s = 0; for (int i = 0; i < v.length; i++) { f[i] = (float) v[i]; s += v[i] * v[i]; } for (int i = 0; i < f.length; i++) f[i] /= (float) Math.sqrt(s); return f; }
    static void recall() throws Exception {
        // The kinds, from the words: a plan has a time and an event (or is soon), a liking says so; a birthday's date is a fact.
        same(BuddyRecall.PLAN, BuddyRecall.sort("사용자는 토요일 오후 2시에 친구 결혼식에 간대"), "a wedding on Saturday at two");
        same(BuddyRecall.LIKE, BuddyRecall.sort("사용자는 커피를 좋아한대"), "a liking");
        same(BuddyRecall.LIKE, BuddyRecall.sort("사용자는 새 운동화를 사고 싶대"), "a wish is a liking");
        same(BuddyRecall.FACT, BuddyRecall.sort("사용자의 생일은 3월 5일이래"), "a birthday is a fact, not a plan");
        same(BuddyRecall.FACT, BuddyRecall.sort("사용자는 차를 지하 2층에 세웠대"), "where the car is");
        // 준's case: "몇 시에 출발할까" brought up the sneakers (newer, told twice) instead of the wedding.
        BuddyMemory.Entry sneakers = new BuddyMemory.Entry(T - DAY, "fact", "사용자는 새 운동화를 사고 싶대", 2, false, 2, T - DAY);
        BuddyMemory.Entry wedding = new BuddyMemory.Entry(T - 5 * DAY, "fact", "사용자는 토요일 오후 2시에 친구 결혼식에 간대", 2, false);
        BuddyMemory.Entry cat = new BuddyMemory.Entry(T - 2 * DAY, "fact", "사용자는 고양이 두 마리를 키운대", 2, false);
        List<BuddyMemory.Entry> mine = List.of(sneakers, wedding, cat);
        same(wedding, BuddyMind.recall(mine, "몇 시에 출발할까", T, null, false, 1).get(0), "a question about when to leave asks for a plan");
        same(sneakers, BuddyMind.recall(mine, "뭐 사고 싶다고 했지", T, null, false, 1).get(0), "a wish asked for");
        same(BuddyRecall.wants("몇 시에 출발할까"), java.util.Set.of(BuddyRecall.PLAN), "what it asks for");
        check(BuddyRecall.wants("심심해").isEmpty(), "small talk asks for no kind");
        // By meaning: no word in common, the vectors decide; without them the words and the weights do.
        BuddyMemory.Entry cold = new BuddyMemory.Entry(T - 9 * DAY, "fact", "사용자는 지난주에 감기에 걸렸대", 2, false);
        BuddyMemory.Entry coffee = new BuddyMemory.Entry(T - DAY, "fact", "사용자는 커피를 좋아한대", 2, false, 3, T - DAY);
        java.util.Map<String, float[]> vectors = new java.util.HashMap<>();
        vectors.put(BuddyRecall.key(cold), vec(0.9, 0.1, 0.1)); vectors.put(BuddyRecall.key(coffee), vec(0.1, 0.9, 0.2)); vectors.put(BuddyRecall.key(cat), vec(0.1, 0.2, 0.9));
        float[] unwell = vec(0.85, 0.15, 0.05);
        same(coffee, BuddyMind.recall(List.of(cold, coffee, cat), "요즘 몸이 안 좋아", T, null, false, 1).get(0), "words alone: the one told most lately");
        same(cold, BuddyMind.recall(List.of(cold, coffee, cat), "요즘 몸이 안 좋아", T, null, false, 1, vectors, unwell).get(0), "by meaning: the cold");
        same(cold, BuddyMind.recall(List.of(cold, coffee, cat, wedding), "요즘 몸이 안 좋아", T, null, false, 1, vectors, unwell).get(0), "one with no vector yet does not get in the way");
        // A memory said anew has a new key (its old vector no longer counts); private ones never leave the phone.
        check(!BuddyRecall.key(cold).equals(BuddyRecall.key(BuddyMemory.replace(List.of(cold), cold.at(), "사용자는 감기가 다 나았대", T, false).get(0))), "said anew: a new key");
        same("", BuddyRecall.outgoing("사용자의 현관 비밀번호는 1234래", false), "a password never goes");
        same("", BuddyRecall.outgoing("사용자는 이직 고민이 많대", true), "a private memory never goes");
        String out = BuddyRecall.outgoing("엄마 번호는 010-1234-5678이고 메일은 mom@example.com, 계좌 123-456-789012", false);
        check(!out.matches(".*\\d{4}.*") && !out.contains("@"), "no phone, e-mail or account number: " + out);
        // The request and the answer: vectors in the order asked, their first DIMS, unit length.
        JSONObject req = BuddyRecall.request(List.of("가", "나"), true);
        same("query", req.getString("input_type"), "a query"); same(BuddyRecall.EMBED_MODEL, req.getString("model"), "NVIDIA's model");
        JSONArray big = new JSONArray(); for (int i = 0; i < 2048; i++) big.put(i == 0 ? 3.0 : i == 1 ? 4.0 : 0.0);
        JSONArray other = new JSONArray(); for (int i = 0; i < 2048; i++) other.put(i == 2 ? 1.0 : 0.0);
        JSONObject answer = new JSONObject().put("data", new JSONArray().put(new JSONObject().put("index", 1).put("embedding", other)).put(new JSONObject().put("index", 0).put("embedding", big)));
        List<float[]> got = BuddyRecall.vectors(answer, 2);
        same(BuddyRecall.DIMS, got.get(0).length, "cut to DIMS");
        check(Math.abs(got.get(0)[0] - 0.6f) < 1e-6 && Math.abs(got.get(0)[1] - 0.8f) < 1e-6 && got.get(1)[2] == 1f, "by index, unit length");
        check(BuddyRecall.vectors(new JSONObject().put("data", new JSONArray()), 2) == null, "one missing: none");
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        BuddyRecall.write(new java.io.DataOutputStream(bytes), BuddyRecall.EMBED_MODEL, vectors);
        java.util.Map<String, float[]> back = BuddyRecall.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())), BuddyRecall.EMBED_MODEL);
        check(back.size() == 3 && java.util.Arrays.equals(back.get(BuddyRecall.key(cold)), vectors.get(BuddyRecall.key(cold))), "the vector file read back");
        check(BuddyRecall.read(new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes.toByteArray())), "another/model").isEmpty(), "another model's file: made again");
        // Three told in one millisecond are three (the third was dropped: its neighbour's time was a millisecond ahead).
        List<BuddyMemory.Entry> quick = List.of();
        for (int i = 0; i < 3; i++) quick = BuddyMemory.add(quick, T, "fact", "사용자는 기억 " + i + "번을 말했대", 0, 2, false);
        same(3, quick.size(), "told in one millisecond, all kept");
        // What a talk teaches is kept as its kind.
        List<BuddyMemory.Entry> learned = BuddyMind.learned(List.of(), List.of(), BuddyMind.parseOps("새로: 사용자는 토요일 오후 2시에 결혼식에 간대\n새로: 사용자는 매운 떡볶이를 좋아한대\n약속: 퍼레가 내일 날씨를 알려 주기로 했어"), "결혼식 떡볶이", T, false);
        same(List.of(BuddyRecall.PLAN, BuddyRecall.LIKE, BuddyRecall.PROMISE), learned.stream().map(BuddyMemory.Entry::kind).toList(), "a plan, a liking and a promise kept apart");
        check(BuddyMemory.told(BuddyRecall.PLAN) && BuddyMemory.told(BuddyRecall.LIKE) && !BuddyMemory.told(BuddyRecall.LESSON), "plans and likings are told; a lesson is not");
        System.out.println("기억 2.0 recall: kinds, the asked-for kind, meaning by vectors (" + BuddyRecall.EMBED_MODEL + ", " + BuddyRecall.DIMS + " dims)");
    }

    // ── 기억 2.0: a correction applied at once, and kept as a lesson (core/BuddyCorrect) ──
    static void corrections() {
        BuddyMemory.Entry birthday = new BuddyMemory.Entry(T - 20 * DAY, "fact", "사용자의 생일은 5월이래", 2, false);
        BuddyCorrect.Fix f = BuddyCorrect.fix(List.of(birthday), "내 생일은 3월이야", "", false);
        check(f != null && birthday.equals(f.target()), "the birthday is the one set right");
        same("사용자의 생일은 3월이래", f.text(), "the month given anew, the rest kept");
        check(f.lesson().contains("5월") && f.lesson().contains("3월"), "the lesson says what was wrong: " + f.lesson());
        List<BuddyMemory.Entry> after = BuddyCorrect.apply(List.of(birthday), f, T);
        same(2, after.size(), "the memory and its lesson");
        same("사용자의 생일은 3월이래", after.get(0).text(), "changed at once"); same(birthday.at(), after.get(0).at(), "the same memory (its time kept)");
        same(BuddyRecall.LESSON, after.get(1).kind(), "kept as a lesson");
        same(after, BuddyCorrect.apply(after, BuddyCorrect.fix(after, "내 생일은 3월이야", "", false), T + 1), "said again: nothing changes");
        same(after.get(1), BuddyRecall.lessons(after, "내 생일 언제였지?", null, null, false, 2).get(0), "the lesson comes back for the same subject");
        check(BuddyRecall.lessons(after, "오늘 뭐 먹지", null, null, false, 2).isEmpty(), "and not for another");
        check(BuddyCorrect.words(BuddyRecall.lessons(after, "생일", null, null, false, 2)).startsWith("전에 사용자가 고쳐 준 것(같은 실수 하지 마)"), "its words for the brain");
        // "아니 일요일이야" after 퍼레 said the wedding was on Saturday: the day changed in the memory the last answer was about.
        BuddyMemory.Entry wedding = new BuddyMemory.Entry(T - 5 * DAY, "plan", "사용자는 토요일 오후 2시에 친구 결혼식에 간대", 2, false);
        BuddyMemory.Entry coffee = new BuddyMemory.Entry(T - 3 * DAY, "like", "사용자는 커피를 좋아한대", 2, false);
        BuddyCorrect.Fix day = BuddyCorrect.fix(List.of(coffee, wedding), "아니 일요일이야", "결혼식은 토요일 오후 2시지? 잘 다녀와!", false);
        check(day != null && wedding.equals(day.target()), "the wedding, by 퍼레's last answer");
        same("사용자는 일요일 오후 2시에 친구 결혼식에 간대", day.text(), "only the day changed");
        same("사용자는 일요일 오후 3시에 친구 결혼식에 간대", BuddyCorrect.fix(List.of(wedding), "아니 그거 아니고 일요일 3시야", "결혼식 토요일 2시였지?", false).text(), "the day and the hour");
        // The opposite, said as a correction.
        BuddyCorrect.Fix quit = BuddyCorrect.fix(List.of(coffee, wedding), "아니 나 커피 끊었어", "커피 한잔 어때?", false);
        check(quit != null && coffee.equals(quit.target()), "the coffee");
        same("사용자는 커피 끊었대", quit.text(), "said anew as a memory");
        // Not corrections: a choice, a request, a question, a feeling, plain talk.
        check(BuddyCorrect.fix(List.of(coffee), "아니 커피 말고 차 마실래", "커피 마실래?", false) == null, "a choice");
        check(BuddyCorrect.fix(List.of(wedding), "아니 결혼식 몇 시였지?", "", false) == null, "a question");
        check(BuddyCorrect.fix(List.of(coffee), "아니야 괜찮아", "괜찮아? 많이 힘들었지", false) == null, "a feeling");
        check(BuddyCorrect.fix(List.of(coffee), "커피 마시고 싶다", "", false) == null, "plain talk");
        check(BuddyCorrect.fix(List.of(coffee), "아니 오늘은 산책할래", "", false) == null, "what they will do now");
        // Nothing known: 퍼레's last answer had it wrong; the fact kept at once and the lesson with it.
        BuddyCorrect.Fix fresh = BuddyCorrect.fix(List.of(), "아니 그거 아니고 내 생일은 3월이야", "생일이 5월이었지? 곧이네!", false);
        check(fresh != null && fresh.target() == null && fresh.added(), "a new memory and a lesson");
        same("사용자의 생일은 3월이래", fresh.text(), "kept as told");
        List<BuddyMemory.Entry> kept = BuddyCorrect.apply(List.of(), fresh, T);
        same(List.of(BuddyRecall.FACT, BuddyRecall.LESSON), kept.stream().map(BuddyMemory.Entry::kind).toList(), "a fact and its lesson");
        BuddyCorrect.Fix onlyLesson = BuddyCorrect.fix(List.of(), "아니 일요일이야", "결혼식은 토요일이지?", false);
        check(onlyLesson != null && onlyLesson.text().isEmpty() && onlyLesson.lesson().contains("토요일"), "a day given anew with nothing known: the lesson alone");
        // A private memory is set right only after the fingerprint.
        BuddyMemory.Entry worry = new BuddyMemory.Entry(T, "fact", "사용자는 5월에 이직 고민이 많대", 2, true);
        check(BuddyCorrect.fix(List.of(worry), "내 이직은 3월이야", "", false) == null && BuddyCorrect.fix(List.of(worry), "내 이직은 3월이야", "", true) != null, "private: unlocked only");
        same("사용자는 커피를 좋아한대", BuddyCorrect.statement("나 커피를 좋아해"), "좋아해 → 좋아한대");
        check(BuddyCorrect.statement("우리 집 비번은 482913이야") == null, "a code is never kept");
        System.out.println("기억 2.0 corrections: a month, a day and an hour set right in place, the opposite, a lesson; choices and questions left alone");
    }

    // ── 기억 2.0: a promise is a 조건 알림 or a 예약, or it is not made (core/BuddyPromises) ──
    static void promises() {
        same(BuddyPromises.Kind.TIME, BuddyPromises.find("응! 내일 아침에 알려 줄게!").kind(), "a time");
        same(BuddyPromises.Kind.EVENT, BuddyPromises.find("도착하면 알려 줄게").kind(), "an event");
        same(BuddyPromises.Kind.WEATHER, BuddyPromises.find("오늘 흐리네. 비 오면 말해 줄게~").kind(), "the weather");
        same(BuddyPromises.Kind.TIME, BuddyPromises.find("나중에 알려 줄게!").kind(), "a vague time is still a promise");
        for (String a : List.of("알려 줄까?", "내가 알려 줄게: 김치찌개는 신 김치가 맛있어.", "오늘 기분 좋아 보여!", "알겠어!\n할일: {\"tool\":\"when_event\",\"when\":\"집에 도착하면\",\"then\":\"tell\"}"))
            check(BuddyPromises.find(a) == null, "no time-bound promise: " + a);
        check(BuddyPromises.backs(new JSONObject().put("tool", "when_event").put("when", "집에 도착하면")), "a 조건 알림 keeps it");
        check(!BuddyPromises.backs(new JSONObject().put("tool", "when_event").put("command", "list")), "listing them keeps nothing");
        check(BuddyPromises.backs(new JSONObject().put("tool", "schedule_job")) && BuddyPromises.backs(new JSONObject().put("tool", "alarm")), "a 예약, an alarm");
        check(!BuddyPromises.backs(new JSONObject().put("tool", "open_app")), "opening an app keeps no promise");
        check(BuddyPromises.asked("내일 아침 8시에 우산 챙기라고 알려 줘") && BuddyPromises.asked("집에 도착하면 빨래 걷으라고 말해 줘") && BuddyPromises.asked("비 오면 알려 줘"), "asked to be told later");
        check(!BuddyPromises.asked("오늘 날씨 알려 줘") && !BuddyPromises.asked("김치찌개 끓이는 법 알려 줘"), "asked to be told now");
        // Made from the user's words: a 예약 at the day and the hour (Friday 15:05 now), a 조건 알림, none for the weather.
        JSONObject morning = BuddyPromises.backing("내일 아침 8시에 우산 챙기라고 알려 줘", BuddyPromises.find("내일 아침에 알려 줄게!"), NOW);
        same("schedule_job", morning.getString("tool"), "a 예약"); same("2026-11-28", morning.getString("date"), "tomorrow"); same("08:00", morning.getString("time"), "at eight");
        same("once", morning.getString("repeat"), "once"); check(morning.getString("request").contains("우산 챙기라고"), "the reminder in the user's words");
        same("20:00", BuddyPromises.backing("8시에 알려 줘", BuddyPromises.find("8시에 알려 줄게"), NOW).getString("time"), "8시 said at three in the afternoon: eight tonight");
        same("15:35", BuddyPromises.backing("30분 뒤에 알려 줘", BuddyPromises.find("30분 뒤에 알려 줄게"), NOW).getString("time"), "half an hour from now");
        same("08:00", BuddyPromises.backing("내일 아침에 깨워 줘", BuddyPromises.find("내일 아침에 깨워 줄게"), NOW).getString("time"), "the morning: eight");
        JSONObject home = BuddyPromises.backing("집에 도착하면 빨래 걷으라고 알려 줘", BuddyPromises.find("도착하면 알려 줄게"), NOW);
        same("when_event", home.getString("tool"), "a 조건 알림"); same("집에 도착하면", home.getString("when"), "its condition"); same("tell", home.getString("then"), "told");
        check(BuddyPromises.backing("비 오면 알려 줘", BuddyPromises.find("비 오면 말해 줄게"), NOW) == null, "no 조건 알림 watches the weather");
        check(BuddyPromises.backing("나중에 알려 줘", BuddyPromises.find("나중에 알려 줄게"), NOW) == null, "too vague to set");
        // Not kept: the promise taken out, the rest said; asked for, what can be done instead.
        BuddyPromises.Promise rain = BuddyPromises.find("응응! 비 오면 말해 줄게~ 오늘은 따뜻하게 입어.");
        String rest = BuddyPromises.without("응응! 비 오면 말해 줄게~ 오늘은 따뜻하게 입어.", rain);
        check(!rest.contains("말해 줄게") && rest.contains("따뜻하게 입어"), "the promise out, the rest kept: " + rest);
        check(BuddyPromises.honest(rain, true).contains("약속은 못 해") && BuddyPromises.honest(rain, false).isEmpty(), "honest when asked, quiet when not");
        System.out.println("기억 2.0 promises: a time → 예약, an event → 조건 알림, the weather and vague times not promised");
    }

    // ── 기억 2.0: every talk kept, and searched (core/TalkLog) ──
    static void talks() {
        List<String[]> turns = List.of(new String[]{"user", "토요일 2시에 친구 결혼식 가"}, new String[]{"assistant", "우와 좋겠다!"}, new String[]{"user", "누구세요?", "g"}, new String[]{"user", "  "});
        List<TalkLog.Turn> first = TalkLog.added(turns, 0, "c1", T - 3 * DAY);
        same(2, first.size(), "a guest's turn and an empty one are not kept");
        check(first.get(0).user() && !first.get(1).user() && first.get(1).at() > first.get(0).at(), "in order, each its own moment");
        List<TalkLog.Turn> all = TalkLog.kept(List.of(), first, T);
        all = TalkLog.kept(all, TalkLog.added(List.of(new String[]{"user", "새 운동화 사고 싶어"}, new String[]{"assistant", "어떤 거?"}), 0, "c2", T - DAY), T);
        all = TalkLog.kept(all, TalkLog.added(List.<String[]>of(new String[]{"user", "오늘 점심은 김치찌개 먹었어"}), 0, TalkLog.VOICE, T - 60_000), T);
        same(all, TalkLog.read(TalkLog.save(all)), "kept and read back");
        check(TalkLog.asks("지난번에 내가 뭐라고 했지?") && TalkLog.asks("어제 결혼식 얘기했던 거 뭐였지") && TalkLog.asks("아까 말한 거 다시 알려 줘"), "asks what was said");
        check(!TalkLog.asks("뭐 먹지") && !TalkLog.asks("결혼식 몇 시야?"), "not a question about the talks");
        ZoneId zone = NOW.getZone();
        List<TalkLog.Turn> wed = TalkLog.find(all, "지난번에 결혼식 얘기 내가 뭐라고 했지?", null, null, null, null, zone, T, 3);
        same(1, wed.size(), "the wedding, by its word"); same("토요일 2시에 친구 결혼식 가", wed.get(0).text(), "the user's own words");
        check(TalkLog.answer(wed, NOW).contains("‘토요일 2시에 친구 결혼식 가’라고 했어"), "said back with when: " + TalkLog.answer(wed, NOW));
        // By meaning: no word in common.
        java.util.Map<String, float[]> vectors = new java.util.HashMap<>();
        for (TalkLog.Turn t : all) if (t.user()) vectors.put(t.key(), t.text().contains("운동화") ? vec(0.9, 0.1) : vec(0.1, 0.9));
        List<TalkLog.Turn> shoes = TalkLog.find(all, "신발 얘기 내가 뭐라고 했지?", vectors, vec(0.95, 0.05), null, null, zone, T, 1);
        same("새 운동화 사고 싶어", shoes.isEmpty() ? null : shoes.get(0).text(), "the sneakers by meaning");
        check(TalkLog.find(all, "신발 얘기 내가 뭐라고 했지?", null, null, null, null, zone, T, 1).isEmpty(), "words alone: nothing, said as such");
        // Nothing to look for: the talk before this one.
        List<TalkLog.Turn> last = TalkLog.find(all, "지난번에 내가 뭐라고 했지?", null, null, null, null, zone, T, 3);
        same(List.of("새 운동화 사고 싶어"), last.stream().map(TalkLog.Turn::text).toList(), "the talk before the voice talk now");
        // On the days said.
        check(TalkLog.find(all, "결혼식 얘기 뭐라고 했지", null, null, NOW.toLocalDate(), NOW.toLocalDate(), zone, T, 3).isEmpty(), "not today");
        // A talk deleted on the page goes; "*" takes every typed one, the voice talk stays.
        same(3, TalkLog.without(all, "c1").size(), "c1's two turns gone");
        same(List.of(TalkLog.VOICE), TalkLog.without(all, "*").stream().map(TalkLog.Turn::conv).toList(), "the voice talk stays");
        check(TalkLog.notFound(List.of("결혼식"), 12, "").contains("우리 대화 12개를 찾아봤는데 ‘결혼식’ 얘기는 없었어"), "what was looked through");
        System.out.println("기억 2.0 talks: kept by talk, found by words, by meaning and as the talk before");
    }

    // ── 두뇌 2.0: an important answer checked once more (core/AnswerCheck) ──
    static void checking() {
        same(AnswerCheck.Kind.DATE, AnswerCheck.important("크리스마스까지 며칠 남았어?", "27일 남았어!"), "a date worked out");
        same(AnswerCheck.Kind.HEALTH, AnswerCheck.important("타이레놀이랑 감기약 같이 먹어도 돼?", "응, 괜찮아!"), "health");
        same(AnswerCheck.Kind.SCHEDULE, AnswerCheck.important("결혼식 가려면 몇 시에 출발해야 돼?", "1시에 출발하면 돼!"), "a schedule");
        same(AnswerCheck.Kind.ACT, AnswerCheck.important("전자레인지에 알루미늄 호일 넣어도 돼?", "조금은 괜찮아, 짧게만 돌려!"), "a thing they will do");
        for (String[] qa : new String[][]{{"안녕", "안녕! 반가워"}, {"오늘 기분 좋아", "나도 좋아!"}, {"비트코인 지금 얼마야?", "1억이야"}, {"크리스마스까지 며칠 남았어?", "곧이야!"}})
            check(AnswerCheck.important(qa[0], qa[1]) == null, "not checked: " + qa[0] + " / " + qa[1]);
        same(AnswerCheck.Verdict.OK, AnswerCheck.verdict("맞음"), "OK"); same(AnswerCheck.Verdict.OK, AnswerCheck.verdict("판정: 맞음 - 계산이 맞아"), "OK with words");
        AnswerCheck.Verdict doubt = AnswerCheck.verdict("의심: 오늘이 11월 27일이면 크리스마스까지 28일 남았어\n더 쓸 말");
        check(doubt.doubt() && doubt.why().equals("오늘이 11월 27일이면 크리스마스까지 28일 남았어"), "a doubt, one line: " + doubt);
        check(!AnswerCheck.verdict("모름").doubt() && !AnswerCheck.verdict("의심: 비트코인 가격이 다를 수 있어").doubt() && !AnswerCheck.verdict("이 답은 좋아 보여요. 다만 …").doubt(), "nothing it could say, money, or not its form");
        String shown = AnswerCheck.merged("27일 남았어!", doubt, AnswerCheck.Kind.DATE);
        check(shown.startsWith("27일 남았어!") && shown.contains("확실하지 않은 데가 있어: 오늘이 11월 27일이면 크리스마스까지 28일 남았어"), "the answer with what is uncertain: " + shown);
        check(AnswerCheck.merged("괜찮아", new AnswerCheck.Verdict(true, "함께 먹으면 성분이 겹칠 수 있어"), AnswerCheck.Kind.HEALTH).contains("약사나 의사"), "health: ask a pharmacist");
        same("27일 남았어!", AnswerCheck.merged("27일 남았어!", AnswerCheck.Verdict.OK, AnswerCheck.Kind.DATE), "agreed: as it was");
        check(AnswerCheck.input("2026년 11월 27일 금요일 오후 3:05", "크리스마스까지 며칠?", "27일", "").contains("지금: 2026년 11월 27일"), "the checker knows the time");
        System.out.println("두뇌 2.0 check: dates, health, schedules and things to do checked by another model; small talk and money never");
    }

    // ── what it costs ──
    static Prices.Tokens tokens(String surface, String company, String model, String route, long requests, long priced, long in, long read, long write, long out) {
        return new Prices.Tokens(surface, company, model, route, requests, priced, in, read, write, out);
    }
    static void prices() {
        Prices.Price opus = Prices.of("claude-opus-5-5");
        check(Math.abs(Prices.usd(opus, 1_000_000, 1_000_000, 1_000_000, 1_000_000) - 29.2) < 1e-9, "Opus 5.5: $4 + $0.20 + $5 + $20");
        check(Math.abs(Prices.usd(Prices.of("gpt-6.1-terra"), 2_000_000, 0, 0, 1_000_000) - 7.0) < 1e-9, "GPT-6.1 Terra: 2 × $1 + $5");
        check(Math.abs(Prices.usd(Prices.of("models/gemini-3.5-flash-lite"), 1_000_000, 0, 0, 1_000_000) - 2.8) < 1e-9, "Flash-Lite, its models/ prefix read");
        check(Math.abs(Prices.usd(Prices.of("grok-4.7"), 0, 0, 500, 0) - 0.001) < 1e-9, "a cache write where none is priced counts as input");
        check(Prices.of("claude-mystery-9") == null, "an unknown model is 모름, never a guess");
        check(Prices.of("gemini-flash-latest") == null, "a -latest alias may point anywhere: 모름");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.CHATGPT, "plan", "gpt-6.1-terra", false, false), "ChatGPT sign-in: 구독");
        same(Prices.Billing.PAID, Prices.billing(WorkBook.CHATGPT, "key", "gpt-6.1-terra", false, false), "ChatGPT key: paid");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.GROK, "plan", "grok-4.7", false, false), "Grok sign-in: 구독");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.ANTIGRAVITY, "plan", "x", false, false), "Antigravity: 구독");
        same(Prices.Billing.PLAN, Prices.billing(WorkBook.CHATGPT, "codex", "x", false, false), "Codex sign-in: 구독");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.NIM, "key", "nvidia/nemotron-3-ultra-550b-a55b", false, false), "NVIDIA: 무료");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.GROQ, "key", "openai/gpt-oss-120b", false, false), "Groq free tier: 무료");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.GEMINI, "key", "gemma-4-31b-it", false, false), "Gemma: 무료");
        same(Prices.Billing.FREE, Prices.billing(WorkBook.GEMINI, "key", "gemini-3.5-flash-lite", true, true), "a 무료 Gemini key");
        same(Prices.Billing.UNKNOWN, Prices.billing(WorkBook.GEMINI, "key", "gemini-3.5-flash-lite", false, false), "a Gemini key of unknown tier: 모름");
        same(Prices.Billing.PAID, Prices.billing(WorkBook.GEMINI, "key", "gemini-3.5-flash-lite", false, true), "a paid Gemini key");
        same(Prices.Billing.UNKNOWN, Prices.billing(WorkBook.CLAUDE, "key", "claude-mystery-9", false, false), "unknown Claude model");
        same(Prices.Billing.DEVICE, Prices.billing(BrainRoutes.DEVICE, BrainRoutes.LOCAL, "time", false, false), "기기 안");
        List<Prices.Tokens> groups = List.of(
            tokens("main", WorkBook.CLAUDE, "claude-opus-5-5", "key", 3, 2, 1_000_000, 1_000_000, 1_000_000, 1_000_000),
            tokens("buddy", WorkBook.CLAUDE, "claude-sonnet-5-5", "key", 1, 1, 1_000_000, 0, 0, 0),
            tokens("main", WorkBook.CHATGPT, "gpt-6.1-terra", "plan", 5, 5, 9, 9, 0, 9),
            tokens("buddy", WorkBook.GROQ, "qwen/qwen3-32b", "key", 4, 0, 0, 0, 0, 0),
            tokens("buddy", BrainRoutes.DEVICE, "time", BrainRoutes.LOCAL, 6, 6, 0, 0, 0, 0),
            tokens("chores", WorkBook.GEMINI, "gemini-flash-latest", "key", 2, 2, 100, 0, 0, 100));
        List<Prices.Spend> companies = Prices.spend(groups, true, false, true);
        Prices.Spend claude = companies.stream().filter(s -> s.key().equals(WorkBook.CLAUDE)).findFirst().orElseThrow();
        check(Math.abs(claude.usd() - 31.2) < 1e-9, "Claude: $29.20 + $2.00 = " + claude.usd());
        same(3L, claude.paid(), "Claude: three priced"); same(1L, claude.unknown(), "Claude: one told no tokens");
        same("$31.20 · 약 43,680원 · 1번은 모름", Prices.words(claude), "in words");
        same(WorkBook.CLAUDE, companies.get(0).key(), "the most money first");
        same(BrainRoutes.DEVICE, companies.get(1).key(), "then the most requests");
        Prices.Spend gpt = companies.stream().filter(s -> s.key().equals(WorkBook.CHATGPT)).findFirst().orElseThrow();
        same("구독", Prices.words(gpt), "a subscription is not money"); same(0.0, gpt.usd(), "no dollars for it");
        same("무료", Prices.words(companies.stream().filter(s -> s.key().equals(WorkBook.GROQ)).findFirst().orElseThrow()), "Groq free");
        same("모름", Prices.words(companies.stream().filter(s -> s.key().equals(WorkBook.GEMINI)).findFirst().orElseThrow()), "an alias: 모름");
        List<Prices.Spend> surfaces = Prices.spend(groups, false, false, true);
        Prices.Spend buddy = surfaces.stream().filter(s -> s.key().equals("buddy")).findFirst().orElseThrow();
        same("$2.00 · 약 2,800원 + 무료 10번", Prices.words(buddy), "퍼레: one paid answer and ten free (Groq and 기기 안)");
        same(11L, buddy.requests(), "퍼레's requests");
        same("$0.004 · 약 6원", Prices.dollars(0.004), "under a cent");
        same("$0", Prices.dollars(0), "nothing");
        same("1 미만", Prices.won(0.0001), "under a won");
        same("100만 토큰 $4/$20", Prices.tag(WorkBook.CLAUDE, "key", "claude-opus-5-5", false, true), "the picker's line");
        same("100만 토큰 $0.1/$0.5", Prices.tag(WorkBook.CLAUDE, "key", "claude-haiku-5-5", false, true), "a small price");
        same("구독", Prices.tag(WorkBook.CHATGPT, "plan", "gpt-6.1-terra", false, true), "a subscription");
        same("무료", Prices.tag(WorkBook.NIM, "key", "nvidia/nemotron-3-ultra-550b-a55b", false, true), "NVIDIA");
        same("요금 모름", Prices.tag(WorkBook.CLAUDE, "key", "claude-mystery-9", false, true), "unknown");
        same("무료 키 0원 · 유료 $0.3/$2.5", Prices.geminiTag("gemini-3.5-flash-lite"), "Gemini on the picker");
        same("요금 모름", Prices.geminiTag("gemini-flash-latest"), "an alias on the picker");
        for (Prices.Price p : Prices.table().values()) check(!p.source().isBlank() && Prices.CHECKED.equals(p.checked()), "every price says where and when it was read");
    }
    static void foot() {
        JSONObject saved = new Usage(10L, null, null, 5L, null, 15L).json(1100);
        Jsons.put(saved, "via", "Claude 한도 → GPT-6.1 Terra로 답했어요");
        same("Claude 한도 → GPT-6.1 Terra로 답했어요 · 첫 글자 1.1초 · 15 토큰", Usage.foot(saved), "the note leads the foot");
        same("Claude 한도 → GPT-6.1 Terra로 답했어요", Usage.via(saved.toString()), "read back from the column");
        same("", Usage.via("{\"first\":5}"), "no note");
        same("첫 글자 1.1초 · 15 토큰", Usage.foot(new Usage(10L, null, null, 5L, null, 15L).json(1100)), "an answer without one is as before");
    }
}
