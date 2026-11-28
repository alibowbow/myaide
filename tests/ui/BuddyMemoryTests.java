package dev.jun.gyeot;

import java.util.ArrayList;
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
 * 퍼레 두뇌 2.0, stage 2 (기억 2.0 and the second look), with its brains and the embedding model played by the test
 * (BuddyTalk.send, BuddyMeaning.embed: nothing goes on the network): memories found by meaning and by what is asked, a
 * correction taken in before the answer and kept as a lesson, a promise bound to a time or an event made into a 예약 or a
 * 조건 알림 or taken out, every talk kept and found ("지난번에 내가 뭐라고 했지?"), and an important answer checked once more by
 * another free model, with what is uncertain said.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
public class BuddyMemoryTests {
    record Asked(String who, String model, String rules, String input) {}
    static final class Brains implements BuddyTalk.Send {
        final List<Asked> asked = new CopyOnWriteArrayList<>();
        java.util.function.Function<Asked, String> answer = a -> "응응!";
        @Override public String text(AppStore store, String who, String model, String rules, String input, String reasoning, int timeoutMs,
                AtomicReference<Runnable> cancel, java.util.function.Consumer<String> onDelta, Runnable guard) {
            Asked a = new Asked(who, model, rules, input); asked.add(a);
            return answer.apply(a);
        }
        List<String> who() { return asked.stream().map(Asked::who).toList(); }
    }
    /** Vectors by subject: health, drinks, pets, shoes, weddings; what has none of them points nowhere in particular. */
    static final String[][] SUBJECTS = {{"감기", "몸", "아파", "열", "병원"}, {"커피", "차", "마시"}, {"고양이", "강아지"}, {"운동화", "신발", "구두"}, {"결혼식", "예식"}};
    static float[] vector(String text) {
        float[] v = new float[SUBJECTS.length + 1]; v[SUBJECTS.length] = 0.2f;
        for (int i = 0; i < SUBJECTS.length; i++) for (String w : SUBJECTS[i]) if (text.contains(w)) v[i] = 1f;
        double s = 0; for (float f : v) s += f * f; for (int i = 0; i < v.length; i++) v[i] /= (float) Math.sqrt(s);
        return v;
    }
    private GyeotApp app;
    private Brains brains;
    private BuddyTalk.Send was;
    private BuddyMeaning.Embed wasEmbed;
    private final List<String> embedded = new CopyOnWriteArrayList<>();

    @Before public void setUp() {
        app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication();
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.GROQ, "iv:sealed").commit();
        BuddyTalk.brain(app, BuddyTalk.QWEN);
        was = BuddyTalk.send; wasEmbed = BuddyMeaning.embed;
        brains = new Brains(); BuddyTalk.send = brains;
        BuddyMeaning.embed = (store, texts, query, timeoutMs) -> { List<float[]> out = new ArrayList<>(); for (String t : texts) { embedded.add(t); out.add(vector(t)); } return out; };
        BuddyMeaning.reset(); BuddyTalks.reset();
        BuddyTalk.forgetAll(app); BuddyMeaning.settle();
    }
    @After public void tearDown() {
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().remove(SecretStore.NIM).commit();
        BuddyTalk.forgetAll(app); BuddyTalks.forget(app, "*"); BuddyTalks.forget(app, dev.jun.gyeot.core.TalkLog.VOICE); BuddyTalks.settle(); BuddyMeaning.settle();
        BuddyTalk.send = was; BuddyMeaning.embed = wasEmbed; BuddyTalk.CHAT_BRAIN.remove();
    }
    private final java.util.concurrent.ExecutorService worker = java.util.concurrent.Executors.newSingleThreadExecutor();
    /** As the app runs them: off the main thread (a question's vector is never waited for on it). */
    private <T> T off(java.util.concurrent.Callable<T> job) throws Exception { return worker.submit(job).get(30, java.util.concurrent.TimeUnit.SECONDS); }
    private String chat(String question, String history) throws Exception {
        return off(() -> BuddyTalk.chatText(app, question, history, "c-test", new AtomicReference<>(), () -> true, null));
    }
    private String recall(JSONObject act, String heard) throws Exception { return off(() -> BuddyActs.run(app, act, heard, false).say()); }
    /** The lines of the rules' "너의 추억" part, in order. */
    private static List<String> recalled(String rules) {
        List<String> out = new ArrayList<>(); int at = rules.indexOf("너의 추억(지금 떠오르는 것):\n"); if (at < 0) return out;
        for (String line : rules.substring(at).split("\n")) { if (line.startsWith("- ")) out.add(line); else if (!out.isEmpty()) break; }
        return out;
    }

    /** 1: "몇 시에 출발할까" brings the wedding to mind (it brought the newer sneakers); by meaning when NVIDIA is there. */
    @Test public void findsMemoriesByWhatIsAskedAndByMeaning() throws Exception {
        BuddyTalk.rememberStrong(app, "fact", "사용자는 토요일 오후 2시에 친구 결혼식에 간대", false);
        for (int i = 0; i < 6; i++) BuddyTalk.rememberStrong(app, "fact", "사용자는 새 운동화를 사고 싶대 " + "ㅎ".repeat(i + 1), false);
        BuddyTalk.rememberStrong(app, "fact", "사용자는 지난주에 감기에 걸렸대", false);
        BuddyTalk.rememberStrong(app, "fact", "사용자는 아침마다 커피를 마신대", false);
        chat("내일 몇 시에 출발할까?", "[]");
        List<String> words = recalled(brains.asked.get(0).rules());
        assertTrue("the wedding first: " + words, !words.isEmpty() && words.get(0).contains("결혼식"));
        // NVIDIA connected: each memory gets its vector in the background, the question one too, and the meaning decides.
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.NIM, "iv:sealed").commit();
        BuddyMeaning.memoriesChanged(app); BuddyMeaning.settle();
        assertEquals("every memory has its vector", 9, BuddyMeaning.memoryVectors(app).size());
        brains.asked.clear();
        chat("요즘 몸이 안 좋아", "[]");
        words = recalled(brains.asked.get(0).rules());
        assertTrue("the cold, by meaning: " + words, !words.isEmpty() && words.get(0).contains("감기"));
        assertTrue("the question went to the embedding model", embedded.contains("요즘 몸이 안 좋아"));
        // A private memory never leaves the phone.
        BuddyTalk.rememberStrong(app, "fact", "사용자는 요즘 이직 고민이 많대", true);
        BuddyMeaning.settle();
        assertFalse(embedded.stream().anyMatch(t -> t.contains("이직")));
        assertEquals(9, BuddyMeaning.memoryVectors(app).size());
    }

    /** 2: a correction changes the memory before the brain answers, is said to it, and is kept as a lesson for next time. */
    @Test public void takesACorrectionInAtOnce() throws Exception {
        BuddyTalk.rememberStrong(app, "fact", "사용자의 생일은 5월이래", false);
        List<String> seen = new CopyOnWriteArrayList<>();
        brains.answer = a -> { for (dev.jun.gyeot.core.BuddyMemory.Entry e : BuddyTalk.memories(app)) seen.add(e.text()); return "아 3월이구나! 기억할게."; };
        chat("아니 그거 아니고 내 생일은 3월이야", "[{\"who\":\"me\",\"text\":\"내 생일 언제게?\"},{\"who\":\"ai\",\"text\":\"5월이지?\"}]");
        assertTrue("changed before the answer: " + seen, seen.contains("사용자의 생일은 3월이래") && !seen.contains("사용자의 생일은 5월이래"));
        String rules = brains.asked.get(0).rules();
        assertTrue(rules, rules.contains("예전: ‘사용자의 생일은 5월이래’ → 지금: ‘사용자의 생일은 3월이래’"));
        List<dev.jun.gyeot.core.BuddyMemory.Entry> m = BuddyTalk.memories(app);
        assertEquals(2, m.size());
        assertEquals("lesson", m.get(1).kind());
        // The next time the subject comes up, the lesson goes with the answer.
        brains.asked.clear(); brains.answer = a -> "3월이야!";
        chat("내 생일 언제였지?", "[]");
        assertTrue(brains.asked.get(0).rules().contains("전에 사용자가 고쳐 준 것(같은 실수 하지 마)"));
        // Not a correction: nothing changes.
        brains.asked.clear();
        chat("아니 오늘은 커피 말고 차 마실래", "[]");
        assertEquals(2, BuddyTalk.memories(app).size());
    }

    /** 3: a promise bound to a time or an event is a 예약 or a 조건 알림 made from the user's words, or it is taken out. */
    @Test public void makesPromisesReal() throws Exception {
        // Asked, and the brain only promised (twice): the 예약 is made from the user's words.
        brains.answer = a -> "응! 내일 아침에 알려 줄게!";
        String said = chat("내일 아침 8시에 우산 챙기라고 알려 줘", "[]");
        JSONObject act = BuddyTalk.act(said);
        assertNotNull(said, act);
        assertEquals("schedule_job", act.optString("tool"));
        assertEquals(java.time.LocalDate.now().plusDays(1).toString(), act.optString("date"));
        assertEquals("08:00", act.optString("time"));
        assertTrue(BuddyTalk.chatBy, BuddyTalk.chatBy.contains("약속을 schedule_job로 걸어 둠"));
        // An event: a 조건 알림.
        brains.answer = a -> "집에 도착하면 알려 줄게!";
        assertEquals("when_event", BuddyTalk.act(chat("집에 도착하면 빨래 걷으라고 알려 줘", "[]")).optString("tool"));
        // The brain wrote the line itself: it stands as written.
        brains.answer = a -> "알겠어!\n할일: {\"tool\":\"when_event\",\"when\":\"집에 도착하면\",\"then\":\"tell\"}";
        assertEquals("집에 도착하면", BuddyTalk.act(chat("집에 도착하면 알려 줘", "[]")).optString("when"));
        // The weather: no 조건 알림 watches it, so no promise; what can be done is said.
        brains.answer = a -> "응, 비 오면 알려 줄게!";
        said = chat("비 오면 알려 줘", "[]");
        assertNull(BuddyTalk.act(said));
        assertFalse(said, said.contains("알려 줄게"));
        assertTrue(said, said.contains("약속은 못 해"));
        // Not asked for: the promise is taken out, the rest stays.
        brains.answer = a -> "그러게, 하늘이 꾸물꾸물하네. 비 오면 말해 줄게~ 오늘은 따뜻하게 입어!";
        said = chat("오늘 좀 흐리네", "[]");
        assertFalse(said, said.contains("말해 줄게"));
        assertTrue(said, said.contains("따뜻하게 입어"));
    }

    /** 4: every talk kept by its id, found by its words and by meaning, gone with the talk deleted on the page. */
    @Test public void findsWhatWasSaidInPastTalks() throws Exception {
        BuddyTalks.add(app, List.of(new String[]{"user", "토요일 2시에 친구 결혼식 가"}, new String[]{"assistant", "우와 좋겠다!"}), 0, "c1");
        BuddyTalks.add(app, List.of(new String[]{"user", "새 운동화 사고 싶어"}, new String[]{"assistant", "어떤 거?"}, new String[]{"user", "누구세요?", "g"}), 0, "c2");
        BuddyTalks.settle();
        assertEquals("a guest's turn is not kept", 4, BuddyTalks.all(app).size());
        JSONObject recall = BuddyTalk.loose("{\"tool\":\"day_recall\",\"query\":\"결혼식\"}");
        String say = recall(recall, "지난번에 결혼식 얘기 내가 뭐라고 했지?");
        assertTrue(say, say.contains("‘토요일 2시에 친구 결혼식 가’라고 했어"));
        // By meaning (NVIDIA connected): "신발" holds no word of "운동화".
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.NIM, "iv:sealed").commit();
        BuddyMeaning.talksChanged(app); BuddyMeaning.settle();
        say = recall(recall, "내가 신발 얘기 뭐라고 했지?");
        assertTrue(say, say.contains("새 운동화 사고 싶어"));
        // 퍼레와 대화 has the turns found as data for its answer.
        chat("지난번에 결혼식 얘기 내가 뭐라고 했지?", "[]");
        assertTrue(brains.asked.get(0).rules().contains("지난 대화에서 찾은 것"));
        // A talk deleted on the page: gone here too.
        BuddyTalks.forget(app, "c1"); BuddyTalks.settle();
        say = recall(recall, "지난번에 결혼식 얘기 내가 뭐라고 했지?");
        assertTrue(say, say.contains("없었어") && !say.contains("토요일 2시"));
    }

    /** 5: an important answer read once more by another free model (GPT OSS for Qwen's), its doubt said; small talk never. */
    @Test public void checksImportantAnswersOnce() throws Exception {
        brains.answer = a -> BuddyTalk.CHECK.equals(a.who()) ? "의심: 타이레놀과 종합 감기약은 아세트아미노펜이 겹칠 수 있어" : "응, 같이 먹어도 괜찮아!";
        String said = chat("타이레놀이랑 종합 감기약 같이 먹어도 돼?", "[]");
        assertEquals(List.of(BuddyTalk.QWEN, BuddyTalk.CHECK), brains.who());
        assertEquals("another free model checks", GroqWork.CHORES, brains.asked.get(1).model());
        assertTrue("the checker reads the answer", brains.asked.get(1).input().contains("퍼레의 답: 응, 같이 먹어도 괜찮아!"));
        assertTrue(said, said.startsWith("응, 같이 먹어도 괜찮아!") && said.contains("확실하지 않은 데가 있어: 타이레놀과 종합 감기약은 아세트아미노펜이 겹칠 수 있어") && said.contains("약사나 의사"));
        assertTrue(BuddyTalk.chatBy, BuddyTalk.chatBy.contains("다시 확인: 의심"));
        // Agreed: as it was.
        brains.asked.clear(); brains.answer = a -> BuddyTalk.CHECK.equals(a.who()) ? "맞음" : "결혼식이 2시면 1시엔 출발해야 해!";
        assertEquals("결혼식이 2시면 1시엔 출발해야 해!", chat("결혼식 2시인데 몇 시에 출발해야 돼?", "[]"));
        // Small talk is never checked.
        brains.asked.clear(); brains.answer = a -> "나도 기분 좋아!";
        chat("오늘 기분 좋아", "[]");
        assertEquals(List.of(BuddyTalk.QWEN), brains.who());
        // GPT OSS's free tier spent and no NVIDIA: shown as it is, said why in chatBy, nothing more sent.
        GeminiQuotas.used(GroqWork.CHORES, 300_001);
        try {
            brains.asked.clear(); brains.answer = a -> BuddyTalk.CHECK.equals(a.who()) ? "의심: x" : "응, 같이 먹어도 괜찮아!";
            assertEquals("응, 같이 먹어도 괜찮아!", chat("타이레놀이랑 종합 감기약 같이 먹어도 돼?", "[]"));
            assertFalse(brains.who().contains(BuddyTalk.CHECK));
            assertTrue(BuddyTalk.chatBy, BuddyTalk.chatBy.contains("다시 확인 못 함"));
        } finally { app.store().changeGeminiQuota(state -> { state.remove(GroqWork.CHORES); return null; }, false); }
    }
}
