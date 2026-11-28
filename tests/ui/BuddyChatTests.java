package dev.jun.gyeot;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

/**
 * 퍼레와 대화's brain (BuddyTalk.chatText, toolAnswer, webAnswer) with its brains played by the test (BuddyTalk.send: nothing
 * goes on the network): the talk it remembers (20 turns whole, a summary of the older part made once per ten, kept per
 * conversation), the brain chosen before sending (Groq's free tier spent: NVIDIA answers, nothing sent to Groq), an answer
 * that only promised asked once more, a tool's result said in 퍼레's words within three seconds, the world now from the web,
 * and a full answer when asked to explain.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class BuddyChatTests {
    /** One request as the brain got it: who, its model, the rules and the input. */
    record Asked(String who, String model, String rules, String input) {}
    /** The brains played: each request kept; answer gives the text (null: an error). */
    static final class Brains implements BuddyTalk.Send {
        final List<Asked> asked = new CopyOnWriteArrayList<>();
        java.util.function.Function<Asked, String> answer = a -> "응응!";
        long sleep;
        @Override public String text(AppStore store, String who, String model, String rules, String input, String reasoning, int timeoutMs,
                AtomicReference<Runnable> cancel, java.util.function.Consumer<String> onDelta, Runnable guard) throws Exception {
            Asked a = new Asked(who, model, rules, input); asked.add(a);
            if (sleep > 0) Thread.sleep(sleep);
            String text = answer.apply(a);
            if (text == null) throw new dev.jun.gyeot.core.ProviderError(503, "HTTP 503");
            return text;
        }
        List<String> who() { return asked.stream().map(Asked::who).toList(); }
    }
    private GyeotApp app;
    private Brains brains;
    private BuddyTalk.Send was;
    private BuddyTalk.Summarizer wasSummary;

    @Before public void setUp() {
        app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        // Groq connected (the default brain, Qwen); the key itself is never read: the brains are played.
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.GROQ, "iv:sealed").commit();
        BuddyTalk.brain(app, BuddyTalk.QWEN);
        was = BuddyTalk.send; wasSummary = BuddyTalk.summarizer;
        brains = new Brains(); BuddyTalk.send = brains;
    }
    @After public void tearDown() { BuddyTalk.send = was; BuddyTalk.summarizer = wasSummary; BuddyTalk.CHAT_BRAIN.remove(); }

    private String chat(String question, String history, String conversation) throws Exception {
        return BuddyTalk.chatText(app, question, history, conversation, new AtomicReference<>(), () -> true, null);
    }
    private static String history(int n) throws Exception {
        JSONArray h = new JSONArray();
        for (int i = 0; i < n; i++) h.put(new JSONObject().put("who", i % 2 == 0 ? "me" : "ai").put("text", "대화 " + i + "번: 오늘 있었던 일 이야기"));
        return h.toString();
    }

    /** 준's ask, 1: the last 20 turns whole (before: six, each cut short), a turn longer than ~1,500 cut. */
    @Test public void keepsTwentyTurnsWhole() throws Exception {
        String in = BuddyPrompt.chatInput(history(20), "아까 뭐라고 했지?");
        assertTrue("the oldest of twenty is there: " + in, in.contains("대화 0번:"));
        assertTrue(in.contains("대화 19번:"));
        JSONArray h = new JSONArray(history(4)); h.put(new JSONObject().put("who", "ai").put("text", "가".repeat(1_400)));
        assertTrue("a 1,400-letter turn whole (before: cut at 1,200)", BuddyPrompt.chatInput(h.toString(), "응").contains("가".repeat(1_400)));
        // What the brain was sent, as chatText sends it.
        chat("그래서 어떻게 됐어?", history(20), "");
        assertTrue(brains.asked.get(0).input().contains("대화 0번:"));
        assertEquals(BuddyTalk.QWEN, brains.asked.get(0).who());
    }

    /** 1: a longer talk's older part as a summary, made once per ten new turns by the chores brain, kept per conversation. */
    @Test public void summarizesTheOlderPartOnce() throws Exception {
        List<String> summarized = new CopyOnWriteArrayList<>();
        java.util.concurrent.CountDownLatch made = new java.util.concurrent.CountDownLatch(1);
        BuddyTalk.summarizer = (store, rules, input) -> { summarized.add(input); made.countDown(); return "- 사용자는 오늘 발표를 잘 마쳤대."; };
        BuddyTalk.forgetSummary(app, "*");
        chat("그래서?", history(30), "c1");
        assertTrue("the summary is made apart", made.await(5, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, summarized.size());
        assertTrue("the first ten turns: " + summarized.get(0), summarized.get(0).contains("대화 0번:") && summarized.get(0).contains("대화 9번:") && !summarized.get(0).contains("대화 10번:"));
        String first = brains.asked.get(0).input();
        assertFalse("until it exists the older part is left out", first.contains("대화 0번:"));
        for (int i = 0; i < 50 && BuddyTalk.summaryOf(app, "c1").isEmpty(); i++) Thread.sleep(50);
        assertEquals("- 사용자는 오늘 발표를 잘 마쳤대.", BuddyTalk.summaryOf(app, "c1"));
        // The next turns: the summary first, the turns after it whole, no new summary until ten more.
        chat("그다음엔?", history(32), "c1");
        String next = brains.asked.get(brains.asked.size() - 1).input();
        assertTrue(next, next.startsWith("앞의 대화 요약:\n- 사용자는 오늘 발표를 잘 마쳤대."));
        assertTrue(next.contains("대화 10번:") && !next.contains("대화 9번:"));
        Thread.sleep(200);
        assertEquals("made once, not every message", 1, summarized.size());
        // Another talk has its own; a deleted talk's summary goes.
        chat("안녕", history(32), "c2");
        assertFalse(brains.asked.get(brains.asked.size() - 1).input().contains("요약"));
        BuddyTalk.forgetSummary(app, "c1");
        assertEquals("", BuddyTalk.summaryOf(app, "c1"));
    }

    /** 4: Groq's free tier spent: NVIDIA answers at once, nothing is sent to Groq, and chatBy says why. */
    @Test public void choosesTheBrainBeforeSending() throws Exception {
        String qwen = BuddyTalk.qwen(app.store());
        // The AI picked for 퍼레 is one not connected here (Claude): it cannot stand in.
        app.store().settings.edit().putString("buddyModels", "{\"engine\":\"claude\",\"models\":{},\"efforts\":{}}").commit();
        assertFalse(BuddyTalk.costsNothing(app.store()));
        GeminiQuotas.used(qwen, 200_001);
        try {
            app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.NIM, "iv:sealed").commit();
            chat("안녕 퍼레야", "[]", "");
            assertEquals("straight to NVIDIA", List.of(BuddyTalk.NIM), brains.who());
            assertTrue(BuddyTalk.chatBy, BuddyTalk.chatBy.startsWith("NVIDIA") && BuddyTalk.chatBy.contains("Groq 한도"));
            // Neither NVIDIA nor another AI: told why, still nothing sent.
            app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().remove(SecretStore.NIM).commit();
            brains.asked.clear();
            try { chat("안녕", "[]", ""); fail("Groq's tier is spent and no other brain is there"); }
            catch (IllegalStateException why) { assertTrue(why.getMessage(), why.getMessage().contains("Groq 무료 한도")); }
            assertTrue("nothing sent", brains.asked.isEmpty());
        } finally { app.store().changeGeminiQuota(state -> { state.remove(qwen); return null; }, false); app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().remove(SecretStore.NIM).commit(); }
        // A request estimated within Groq's 8,000 tokens a minute.
        chat("안녕", history(40), "");
        assertTrue("estimated " + BuddyTalk.lastNeed, BuddyTalk.lastNeed > 0 && BuddyTalk.lastNeed <= dev.jun.gyeot.core.ChatMemory.GROQ_MINUTE);
    }

    /** 3: "찾아볼게!" and no 할일 line: asked once more with the whole list and "now", and its thing is done. */
    @Test public void asksAgainWhenItOnlyPromised() throws Exception {
        brains.answer = a -> a.rules().contains(BuddyPrompt.DO_IT_NOW) ? "알겠어!\n할일: {\"tool\":\"map_search\",\"query\":\"근처 약국\"}" : "찾아볼게!";
        String said = chat("근처 약국 좀", "[]", "");
        assertEquals(2, brains.asked.size());
        assertTrue("the retry has the whole list", brains.asked.get(1).rules().contains(BuddyPrompt.ACTS));
        assertEquals("map_search", BuddyTalk.act(said).optString("tool"));
        // A retry that promises again: the first answer stands, no third request.
        brains.asked.clear(); brains.answer = a -> "찾아볼게!";
        assertEquals("찾아볼게!", chat("근처 약국 좀", "[]", ""));
        assertEquals(2, brains.asked.size());
        // Plain talk is not asked twice.
        brains.asked.clear(); brains.answer = a -> "나도 오늘 기분 최고야 ㅎㅎ";
        chat("오늘 기분 좋아", "[]", "");
        assertEquals(1, brains.asked.size());
    }

    /** 2: a tool's result said in 퍼레's own words by the brain that chose it, once, within three seconds; else its own words. */
    @Test public void saysAToolsResultInItsOwnWords() throws Exception {
        JSONObject coin = BuddyTalk.loose("{\"tool\":\"coin\"}");
        BuddyTalk.CHAT_BRAIN.set(BuddyTalk.QWEN);
        brains.answer = a -> "짠! 앞면이 나왔어, 오늘 운 좋은데?";
        assertEquals("짠! 앞면이 나왔어, 오늘 운 좋은데?", BuddyTalk.toolAnswer(app, "동전 던져 줘", "[]", coin, "앞면 나왔어!"));
        Asked sent = brains.asked.get(0);
        assertEquals(BuddyTalk.QWEN, sent.who());
        assertTrue("the result goes as data", sent.input().contains("할일 결과: 앞면 나왔어!"));
        assertFalse("short rules, not the whole list", sent.rules().contains("open_app"));
        // Facts changed, another thing to do, too slow, or no brain chose it: the result's own words (null).
        brains.answer = a -> "뒷면이네!";
        assertNull(BuddyTalk.toolAnswer(app, "동전 던져 줘", "[]", coin, "앞면 나왔어!"));
        brains.answer = a -> "또 던질게!\n할일: {\"tool\":\"coin\"}";
        assertNull(BuddyTalk.toolAnswer(app, "동전 던져 줘", "[]", coin, "앞면 나왔어!"));
        brains.answer = a -> "앞면!"; brains.sleep = 4_000;
        long begun = System.currentTimeMillis();
        assertNull(BuddyTalk.toolAnswer(app, "동전 던져 줘", "[]", coin, "앞면 나왔어!"));
        assertTrue("given up at three seconds", System.currentTimeMillis() - begun < 3_800);
        brains.sleep = 0; brains.asked.clear();
        BuddyTalk.CHAT_BRAIN.set(BuddyTalk.LOCAL);
        assertNull("기기 안 answered: no brain to ask", BuddyTalk.toolAnswer(app, "동전 던져 줘", "[]", coin, "앞면 나왔어!"));
        BuddyTalk.CHAT_BRAIN.set(BuddyTalk.QWEN);
        assertNull("a thing that only does something keeps its confirmation", BuddyTalk.toolAnswer(app, "손전등 켜 줘", "[]", BuddyTalk.loose("{\"tool\":\"torch\",\"on\":true}"), "켰어!"));
        assertTrue(brains.asked.isEmpty());
    }

    /** 6: the world now from Groq's search model, 1–3 sentences with where it came from; "검색해 줘" opens the search as before. */
    @Test public void answersTheWorldNowFromTheWeb() throws Exception {
        brains.answer = a -> BuddyTalk.WEB.equals(a.who()) ? "오늘 원·달러 환율은 1,382원쯤이야 [1]. https://example.com (출처: 네이버 금융)" : "알겠어!\n할일: {\"tool\":\"search\",\"query\":\"환율\"}";
        String said = chat("오늘 환율 얼마야?", "[]", "");
        assertEquals(List.of(BuddyTalk.WEB), brains.who());
        assertEquals(dev.jun.gyeot.GroqWork.SEARCH, brains.asked.get(0).model());
        assertEquals("오늘 원·달러 환율은 1,382원쯤이야. (출처: 네이버 금융)", said);
        assertFalse("never 퍼레's memories or notes", brains.asked.get(0).rules().contains("비서 노트") || brains.asked.get(0).input().contains("비서 노트"));
        assertTrue(BuddyTalk.chatBy, BuddyTalk.chatBy.contains("웹 검색"));
        brains.asked.clear();
        assertEquals("search", BuddyTalk.act(chat("오늘 환율 검색해 줘", "[]", "")).optString("tool"));
        assertEquals("asked to open a search: the brain, not the web", List.of(BuddyTalk.QWEN), brains.who());
        // The web failing: the brain answers as before.
        brains.asked.clear(); brains.answer = a -> BuddyTalk.WEB.equals(a.who()) ? null : "음, 지금은 잘 모르겠어.";
        assertEquals("음, 지금은 잘 모르겠어.", chat("오늘 환율 얼마야?", "[]", ""));
        assertEquals(List.of(BuddyTalk.WEB, BuddyTalk.QWEN), brains.who());
    }

    /** 5: explaining, listing, comparing or writing gets a full answer; small talk keeps its short one. */
    @Test public void aFullAnswerWhenAskedToExplain() throws Exception {
        chat("김치찌개 끓이는 법 알려 줘", "[]", "");
        String rules = brains.asked.get(0).rules();
        assertFalse("no count of sentences", rules.contains("2~6문장"));
        assertTrue(rules.contains("필요한 만큼"));
        brains.asked.clear();
        chat("심심해", "[]", "");
        assertTrue(brains.asked.get(0).rules().contains("2~6문장"));
    }
}
