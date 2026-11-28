package dev.jun.gyeot;

import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextView;
import dev.jun.gyeot.core.BuddyJobs;
import java.io.File;
import java.io.FileOutputStream;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

/**
 * 문 하나 (준, stage 1): 퍼레 takes any job itself. Each job tool goes to the engine that already does it (the phone agent, the
 * AI browser, a 작업창 room, 예약 작업, 연구실) with the user's request; the guards come first; every running job has one look
 * (its line, 중지, its status when asked, one passing word when it ends, a tap opening its result), from whichever engine.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk=35, application=GyeotApp.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
public class BuddyJobsTests {
    /** The engines played by the test: each call kept as "kind|request|app or url|heard", each job given a stop and a result. */
    static final class Engines implements BuddyHands.Engines {
        final List<String> calls = new CopyOnWriteArrayList<>(); final List<String> stopped = new CopyOnWriteArrayList<>();
        BuddyHands.Refused refuse;
        private void take(BuddyJobs.Job job, String open) throws BuddyHands.Refused {
            if (refuse != null) throw refuse;
            BuddyJobs.opens(job, open); BuddyJobs.stopper(job, () -> stopped.add(job.id));
        }
        @Override public void phone(android.content.Context c, BuddyJobs.Job job, String request, String app, String heard) throws BuddyHands.Refused { calls.add("phone|" + request + "|" + app + "|" + heard); take(job, "app:com.sampleapp.baemin"); }
        @Override public void web(android.content.Context c, BuddyJobs.Job job, String request, String url, String heard) throws BuddyHands.Refused { calls.add("web|" + request + "|" + url + "|" + heard); take(job, "browser"); }
        @Override public void work(android.content.Context c, BuddyJobs.Job job, String request, String heard) throws BuddyHands.Refused { calls.add("work|" + request + "||" + heard); take(job, "work:room1"); }
        @Override public JSONObject schedule(android.content.Context c, JSONObject input) throws BuddyHands.Refused {
            if (refuse != null) throw refuse;
            calls.add("schedule|" + input.optString("goal") + "|" + input.optString("repeat") + " " + input.optString("time") + "|");
            return dev.jun.gyeot.core.WorkSchedules.save(new JSONArray(), input, new JSONObject(), System.currentTimeMillis(), java.time.ZoneId.systemDefault());
        }
        @Override public void research(android.content.Context c, BuddyJobs.Job job, String title, String question) throws BuddyHands.Refused { calls.add("research|" + question + "|" + title + "|"); take(job, "lab:p1"); }
    }
    private BuddyHands.Engines was;
    private Engines engines;
    private GyeotApp app;
    @Before public void setUp() { app = (GyeotApp) org.robolectric.RuntimeEnvironment.getApplication(); BuddyJobs.clear(); was = BuddyHands.engines; engines = new Engines(); BuddyHands.engines = engines; BuddyMisses.clear(app); }
    @After public void tearDown() { BuddyHands.engines = was; BuddyJobs.clear(); }

    private static JSONObject act(String json) { return BuddyTalk.loose(json); }

    @Test public void eachJobGoesToTheEngineThatDoesIt() throws Exception {
        // phone_job: the phone agent, with the brain's request, the app and the user's own words (its authority).
        JSONObject baemin = act("{\"tool\":\"phone_job\",\"request\":\"지난번 메뉴 다시 담기\",\"app\":\"배민\"}");
        assertEquals("배민에서 담아 볼게!", BuddyTools.say(baemin));
        BuddyVoice.Outcome o = BuddyActs.run(app, baemin, "배민에서 지난번 메뉴 다시 담아 줘", true);
        assertFalse("taken", o.missed()); assertEquals("", o.say());
        assertEquals(List.of("phone|지난번 메뉴 다시 담기|배민|배민에서 지난번 메뉴 다시 담아 줘"), engines.calls);
        BuddyJobs.Job phone = BuddyJobs.latestRunning();
        assertEquals(BuddyJobs.PHONE, phone.kind); assertEquals("배민 담는 중", phone.label); assertEquals("app:com.sampleapp.baemin", phone.open());

        // web_job: the AI browser, from the site said.
        JSONObject upload = act("{\"tool\":\"web_job\",\"request\":\"어제 만든 영상 올리기\",\"url\":\"studio.youtube.com\"}");
        assertEquals("studio.youtube.com에서 올려 볼게!", BuddyTools.say(upload));
        assertFalse(BuddyActs.run(app, upload, "유튜브 스튜디오에 어제 만든 영상 올려 줘", false).missed());
        assertEquals("web|어제 만든 영상 올리기|studio.youtube.com|유튜브 스튜디오에 어제 만든 영상 올려 줘", engines.calls.get(1));
        assertEquals("studio.youtube.com", BuddyJobs.latestRunning().who);

        // work_job: a 작업창 room; research_job: 연구실 with 자율 연구.
        assertEquals("작업창에 맡길게!", BuddyTools.say(act("{\"tool\":\"work_job\",\"request\":\"보고서\"}")));
        assertFalse(BuddyActs.run(app, act("{\"tool\":\"work_job\",\"request\":\"이번 달 지출 분석 보고서 쓰기\"}"), "이번 달 카드 지출 분석해서 보고서 써 줘", false).missed());
        assertEquals("work|이번 달 지출 분석 보고서 쓰기||이번 달 카드 지출 분석해서 보고서 써 줘", engines.calls.get(2));
        assertFalse(BuddyActs.run(app, act("{\"tool\":\"research\",\"topic\":\"양자 오류 정정\"}"), "양자 오류 정정 연구해 줘", false).missed());
        assertEquals("an alias and its words read as research_job", "research|양자 오류 정정||", engines.calls.get(3));
        assertEquals("lab:p1", BuddyJobs.latestRunning().open());

        // schedule_job: 예약 작업, said back clearly; nothing runs now.
        BuddyVoice.Outcome planned = BuddyActs.run(app, act("{\"tool\":\"schedule_job\",\"request\":\"새 채용 공고 찾기\",\"repeat\":\"daily\",\"time\":\"07:00\"}"), "매일 아침 7시에 새 채용 공고 찾아 줘", false);
        assertEquals("schedule|새 채용 공고 찾기|daily 07:00|", engines.calls.get(4));
        assertTrue(planned.say(), planned.say().startsWith("매일 07:00에 ‘새 채용 공고 찾기’ 예약했어. 다음은 "));
        assertEquals("four jobs run; the schedule is not one", 4, BuddyJobs.running().size());

        // The typed talk passes its question as the words (HomeActivity.chatAnswer); a bare yes is not a request.
        engines.calls.clear(); BuddyJobs.clear();
        assertFalse(BuddyActs.run(app, act("{\"tool\":\"phone_job\",\"request\":\"카톡 단톡방 공지 읽기\",\"app\":\"카카오톡\"}"), "네", false).missed());
        assertEquals("phone|카톡 단톡방 공지 읽기|카카오톡|카톡 단톡방 공지 읽기", engines.calls.get(0));
    }

    @Test public void theGuardsComeFirstAndARefusalSaysTheWayThrough() throws Exception {
        // A payment the user asked for, a banking app: never handed to the phone agent (AutomationGate's own lines).
        BuddyVoice.Outcome pay = BuddyActs.run(app, act("{\"tool\":\"phone_job\",\"request\":\"쿠팡에서 결제하기\",\"app\":\"쿠팡\"}"), "쿠팡에서 결제해 줘", false);
        assertTrue(pay.missed()); assertTrue(pay.say(), pay.say().contains("결제·송금·삭제"));
        BuddyVoice.Outcome bank = BuddyActs.run(app, act("{\"tool\":\"phone_job\",\"request\":\"잔액 보기\",\"app\":\"KB국민은행\"}"), "국민은행 앱에서 잔액 봐 줘", false);
        assertTrue(bank.missed()); assertTrue(bank.say(), bank.say().contains("은행"));
        BuddyVoice.Outcome bankSite = BuddyActs.run(app, act("{\"tool\":\"web_job\",\"request\":\"잔액 조회\",\"url\":\"obank.kbstar.com\"}"), "국민은행 사이트에서 잔액 조회해 줘", false);
        assertTrue(bankSite.missed()); assertTrue(bankSite.say(), bankSite.say().contains("은행"));
        assertTrue("nothing was handed over", engines.calls.isEmpty());
        assertTrue("and nothing shows", BuddyJobs.shown(System.currentTimeMillis()).isEmpty());

        // One phone job at a time (one screen): the second is told what runs and how to stop it.
        assertFalse(BuddyActs.run(app, act("{\"tool\":\"phone_job\",\"request\":\"지난번 메뉴 다시 담기\",\"app\":\"배민\"}"), "배민에서 지난번 메뉴 다시 담아 줘", false).missed());
        BuddyVoice.Outcome second = BuddyActs.run(app, act("{\"tool\":\"phone_job\",\"request\":\"공지 읽기\",\"app\":\"카카오톡\"}"), "카톡 공지 읽어 줘", false);
        assertTrue(second.missed()); assertTrue(second.say(), second.say().contains("‘배민 담는 중’") && second.say().contains("하던 거 멈춰"));

        // An engine that would not take it: no job left on the line, why said, its one tap kept (퍼레가 못 한 일).
        BuddyJobs.clear();
        engines.refuse = new BuddyHands.Refused("‘음성 비서의 폰 조작 허용’이 꺼져 있어. 켜 주면 바로 할게.", "settings:phone");
        BuddyVoice.Outcome off = BuddyActs.run(app, act("{\"tool\":\"phone_job\",\"request\":\"지난번 메뉴 다시 담기\",\"app\":\"배민\"}"), "배민에서 지난번 메뉴 다시 담아 줘", false);
        assertTrue(off.missed()); assertTrue(off.say().contains("폰 조작 허용"));
        assertTrue(BuddyJobs.shown(System.currentTimeMillis()).isEmpty());
        assertEquals("settings:phone", BuddyMisses.list(app).get(0).fix());
        assertEquals("설정 열기", BuddyMisses.fixLabel("settings:phone"));
        Intent fix = BuddyMisses.fixIntent(app, "settings:phone");
        assertEquals(MainActivity.class.getName(), fix.getComponent().getClassName()); assertEquals("phone", fix.getStringExtra(MainActivity.SETTINGS_PAGE));

        // The real engines say what is missing on this phone, with the tap to it, before anything starts.
        BuddyHands.engines = new BuddyHands.Real();
        BuddyVoice.Outcome noAccess = BuddyActs.run(app, act("{\"tool\":\"phone_job\",\"request\":\"지난번 메뉴 다시 담기\",\"app\":\"배민\"}"), "배민에서 지난번 메뉴 다시 담아 줘", false);
        assertTrue(noAccess.say(), noAccess.say().contains("접근성")); assertEquals("accessibility", BuddyMisses.list(app).get(0).fix());
        BuddyVoice.Outcome noAi = BuddyActs.run(app, act("{\"tool\":\"work_job\",\"request\":\"보고서 쓰기\"}"), "보고서 써 줘", false);
        assertTrue(noAi.say(), noAi.say().contains("작업창에서 쓸 AI")); assertEquals("settings:ai", BuddyMisses.list(app).get(0).fix());
        assertTrue(BuddyActs.run(app, act("{\"tool\":\"schedule_job\",\"request\":\"공고 찾기\",\"time\":\"07:00\"}"), "매일 7시에 공고 찾아 줘", false).missed());
        assertTrue(BuddyActs.run(app, act("{\"tool\":\"research_job\",\"question\":\"양자 오류 정정\"}"), "양자 오류 정정 연구해 줘", false).missed());
        assertTrue(BuddyJobs.shown(System.currentTimeMillis()).isEmpty());
    }

    /** The real 작업창 handoff: the very intent the spoken 작업창 sends (WORK_SEND, a new room, the user's words its authority). */
    @Test public void aWorkJobIsSentToANewWorkRoom() throws Exception {
        app.getSharedPreferences("secret", android.content.Context.MODE_PRIVATE).edit().putString(SecretStore.NIM, "iv:sealed").commit();
        app.store().settings.edit().putString(ScopedModels.DRAFT, "{\"engine\":\"nim\",\"models\":{\"nim\":\"" + dev.jun.gyeot.core.NimProtocol.MODEL + "\"},\"efforts\":{}}").commit();
        BuddyHands.engines = new BuddyHands.Real();
        BuddyVoice.Outcome o = BuddyActs.run(app, act("{\"tool\":\"work_job\",\"request\":\"이번 달 지출 분석 보고서 쓰기\"}"), "이번 달 카드 지출 분석해서 보고서 써 줘", false);
        assertFalse(o.say(), o.missed());
        Intent sent = shadowOf(app).getNextStartedService();
        assertNotNull("sent to the work service", sent);
        assertEquals(AssistantService.WORK_SEND, sent.getAction());
        assertEquals("", sent.getStringExtra("thread")); assertEquals("이번 달 지출 분석 보고서 쓰기", sent.getStringExtra("text"));
        assertEquals("nim", sent.getStringExtra("engine"));
        assertTrue(sent.getBooleanExtra(dev.jun.gyeot.core.WorkBook.VOICE, false));
        assertEquals("the user's own words are the run's authority", "이번 달 카드 지출 분석해서 보고서 써 줘", sent.getStringExtra(dev.jun.gyeot.core.WorkBook.HEARD));
        BuddyJobs.Job job = BuddyJobs.latestRunning();
        assertEquals(BuddyJobs.WORK, job.kind); assertEquals("the verb said first", "작업창에서 분석하는 중", job.label);
        // The room the service makes: found, its stage on the line, its answer the job's result when its run ends.
        long t0 = System.currentTimeMillis() + 1; String[] room = {""};
        app.store().changeWorks(list -> { JSONObject r = dev.jun.gyeot.core.WorkBook.create(list, "이번 달 지출 분석 보고서 쓰기", t0); room[0] = r.optString("id"); return null; });
        app.store().workProgress.begin(room[0], android.os.SystemClock.elapsedRealtime());
        app.store().workStages.put(room[0], "자료 찾는 중");
        waitFor(() -> ("work:" + room[0]).equals(job.open()) && "자료 찾는 중".equals(job.last()));
        assertEquals("work:" + room[0], job.open()); assertEquals("작업창 · 자료 찾는 중", BuddyJobs.line(job, System.currentTimeMillis()));
        assertTrue("its 중지 is the room's", job.stoppable());
        app.store().changeWorks(list -> { dev.jun.gyeot.core.WorkBook.answer(dev.jun.gyeot.core.WorkBook.find(list, room[0]), "보고서 초안: 식비가 지난달보다 12% 늘었어요.", new JSONArray(), new JSONArray(), "", "", t0 + 5); return null; });
        app.store().workProgress.finish(room[0], dev.jun.gyeot.core.WorkRunState.FINISHED, android.os.SystemClock.elapsedRealtime());
        waitFor(() -> !job.running());
        assertEquals(BuddyJobs.State.DONE, job.state()); assertTrue(job.result(), job.result().startsWith("보고서 초안"));
        assertEquals("작업창 일 끝났어! 눌러 봐", BuddyJobs.passing(job));
        assertEquals(room[0], BuddyHands.opening(app, job).getStringExtra("work"));
    }
    /** The job watchers run on their own thread every BuddyHands.WATCH_MS: waited for in real time, ten seconds at most. */
    private static void waitFor(java.util.function.BooleanSupplier done) throws InterruptedException {
        for (long until = System.currentTimeMillis() + 10_000; !done.getAsBoolean() && System.currentTimeMillis() < until; ) { Thread.sleep(100); shadowOf(Looper.getMainLooper()).idle(); }
    }

    @Test public void oneLookForEveryJob() throws Exception {
        long now = System.currentTimeMillis();
        BuddyJobs.Job job = BuddyJobs.start(BuddyJobs.PHONE, "배민", "지난번 메뉴 다시 담기", now);
        List<String> stops = new CopyOnWriteArrayList<>();
        BuddyJobs.stopper(job, () -> stops.add(job.id)); BuddyJobs.opens(job, "app:com.sampleapp.baemin");
        assertEquals("배민 담는 중", BuddyJobs.line(job, now));
        BuddyJobs.progress(job, 3, "배달의민족", "‘장바구니’ 누름");
        assertEquals("배민 담는 중 · 3단계", BuddyJobs.line(job, now));
        JSONObject shown = BuddyJobs.json(now).getJSONObject(0);
        assertEquals("running", shown.getString("state")); assertTrue("중지 while it runs", shown.getBoolean("stop")); assertTrue(shown.getBoolean("open"));

        // Asked how it goes: from its own record, never "모르겠어".
        for (String asked : new String[]{"어떻게 돼 가?", "진행 상황 알려 줘", "다 했어?", "어디까지 했어"}) assertTrue(asked, BuddyJobs.asksStatus(asked));
        for (String other : new String[]{"오늘 날씨 어떻게 돼", "그만", "내일 일정 뭐야"}) assertFalse(other, BuddyJobs.asksStatus(other));
        String answer = BuddyJobs.answer(now + 90_000);
        assertTrue(answer, answer.contains("배민 담는 중이야") && answer.contains("3단계") && answer.contains("배달의민족") && answer.contains("‘장바구니’ 누름"));
        assertFalse(answer.contains("모르"));
        assertTrue("the brain reads the same record", BuddyJobs.report(now).contains("배민 담는 중 · 3단계 · 요청 ‘지난번 메뉴 다시 담기’ · 배달의민족 · 마지막: ‘장바구니’ 누름"));
        assertTrue(HomeActivity.jobWords("어떻게 돼 가?").contains("3단계"));
        assertNull("other words go to the brain", HomeActivity.jobWords("심심해"));

        // 중지: the engine's own stop; whatever comes back after, the job is stopped, and says no passing word.
        assertTrue(BuddyJobs.asksStop("하던 거 멈춰")); assertTrue(BuddyJobs.asksStop("작업 중지")); assertFalse("bare 그만 is the music's", BuddyJobs.asksStop("그만"));
        assertTrue(BuddyJobs.stop(job.id)); assertEquals(List.of(job.id), stops);
        assertFalse("once", BuddyJobs.stop(job.id));
        BuddyJobs.end(job, BuddyJobs.State.DONE, "담았어요", now + 1000);
        assertEquals(BuddyJobs.State.STOPPED, job.state()); assertEquals("배민 · 멈췄어", BuddyJobs.line(job, now)); assertEquals("", BuddyJobs.passing(job));
        assertTrue(BuddyJobs.untold(now + 2000).isEmpty());

        // Ended: one passing word, claimed once (the voice or the home screen); a tap opens its result; the line goes after a while.
        BuddyJobs.Job web = BuddyJobs.start(BuddyJobs.WEB, "studio.youtube.com", "영상 올리기", now);
        BuddyJobs.opens(web, "browser");
        BuddyJobs.end(web, BuddyJobs.State.DONE, "초안으로 올렸어요", now + 5000);
        assertEquals("studio.youtube.com · 끝났어", BuddyJobs.line(web, now + 6000));
        assertEquals("studio.youtube.com 일 끝났어! 눌러 봐", BuddyJobs.passing(web));
        assertEquals(List.of(web), BuddyJobs.untold(now + 6000));
        assertTrue(BuddyJobs.claim(web)); assertFalse("said once", BuddyJobs.claim(web));
        assertTrue(BuddyJobs.answer(now + 65_000), BuddyJobs.answer(now + 65_000).contains("1분 전에 끝났어: 초안으로 올렸어요"));
        assertEquals(2, BuddyJobs.shown(now + 6000).size()); assertTrue("gone from the line later", BuddyJobs.shown(now + 5000 + BuddyJobs.SHOWN_MS + 1).isEmpty());
        BuddyJobs.Job lab = BuddyJobs.start(BuddyJobs.RESEARCH, "연구실", "양자 오류 정정", now);
        BuddyJobs.progress(lab, 4, "", "2/2라운드 · 토론 중");
        assertEquals("연구실 · 2/2라운드 · 토론 중", BuddyJobs.line(lab, now));
        BuddyJobs.end(lab, BuddyJobs.State.WAITING, "연구원이 승인이나 답을 기다려요", now);
        assertEquals("연구실 · 확인 필요", BuddyJobs.line(lab, now)); assertEquals("연구실 연구, 확인이 필요해!", BuddyJobs.passing(lab));
        BuddyJobs.Job failed = BuddyJobs.start(BuddyJobs.WORK, "작업창", "보고서", now); BuddyJobs.end(failed, BuddyJobs.State.FAILED, "연결이 끊겼어요", now);
        assertEquals("작업창 · 막혔어", BuddyJobs.line(failed, now)); assertEquals("작업창 일은 막혔어.", BuddyJobs.passing(failed));

        // What a tap opens, the same from the line, the passing word and the voice pill.
        BuddyJobs.Job room = BuddyJobs.start(BuddyJobs.WORK, "작업창", "보고서", now); BuddyJobs.opens(room, "work:room7");
        Intent work = BuddyHands.opening(app, room);
        assertEquals(MainActivity.class.getName(), work.getComponent().getClassName()); assertEquals("room7", work.getStringExtra("work")); assertEquals(3, work.getIntExtra("tab", 0));
        BuddyJobs.opens(room, "lab:p9"); assertEquals("p9", BuddyHands.opening(app, room).getStringExtra(LabActivity.OPEN));
        BuddyJobs.opens(room, "browser"); assertEquals(BrowserActivity.class.getName(), BuddyHands.opening(app, room).getComponent().getClassName());
        BuddyJobs.opens(room, "app:not.installed.app"); assertEquals("an app not here: 폰 조작's record", "phone", BuddyHands.opening(app, room).getStringExtra(MainActivity.SETTINGS_PAGE));
        BuddyJobs.opens(room, ""); assertNull(BuddyHands.opening(app, room));
    }

    /** 예약 작업's fields from what the brain gave, the user's words filling what it left out. */
    @Test public void aScheduleIsReadFromTheWords() throws Exception {
        java.time.LocalDate today = java.time.LocalDate.of(2026, 11, 27);
        JSONObject daily = BuddyHands.scheduleInput(act("{\"tool\":\"schedule_job\",\"request\":\"새 공고 찾기\",\"time\":\"7:00\"}"), "매일 아침 7시에 새 공고 찾아 줘", "새 공고 찾기", today);
        assertEquals("daily", daily.getString("repeat")); assertEquals("07:00", daily.getString("time"));
        JSONObject weekdays = BuddyHands.scheduleInput(act("{\"tool\":\"schedule_job\",\"request\":\"뉴스 정리\"}"), "평일 저녁 6시 반에 뉴스 정리해 줘", "뉴스 정리", today);
        assertEquals("weekdays", weekdays.getString("repeat")); assertEquals("18:30", weekdays.getString("time"));
        JSONObject every = BuddyHands.scheduleInput(act("{\"tool\":\"schedule_job\",\"request\":\"가격 확인\",\"time\":\"09:00\"}"), "3시간마다 가격 확인해 줘", "가격 확인", today);
        assertEquals("interval", every.getString("repeat")); assertEquals(3, every.getInt("every")); assertEquals("hour", every.getString("unit"));
        JSONObject friday = BuddyHands.scheduleInput(act("{\"tool\":\"schedule_job\",\"request\":\"주간 보고\",\"repeat\":\"weekly\",\"time\":\"17:00\"}"), "매주 금요일 5시에 주간 보고 정리해 줘", "주간 보고", today);
        assertEquals(5, friday.getInt("day"));
        JSONObject once = BuddyHands.scheduleInput(act("{\"tool\":\"schedule_job\",\"request\":\"티켓 확인\",\"repeat\":\"once\",\"time\":\"10:00\",\"date\":\"내일\"}"), "내일 10시에 티켓 확인해 줘", "티켓 확인", today);
        assertEquals("2026-11-28", once.getString("date"));
        JSONObject site = BuddyHands.scheduleInput(act("{\"tool\":\"schedule_job\",\"request\":\"공지 확인\",\"time\":\"08:00\",\"url\":\"school.example.kr\"}"), "매일 8시에 학교 공지 확인해 줘", "공지 확인", today);
        assertEquals("school.example.kr", site.getJSONObject("browser").getString("url"));
        // As saved by 예약 작업 itself, with the words to say back.
        JSONObject saved = dev.jun.gyeot.core.WorkSchedules.save(new JSONArray(), daily, new JSONObject(), System.currentTimeMillis(), java.time.ZoneId.systemDefault());
        assertTrue(BuddyHands.scheduled(saved, System.currentTimeMillis()).startsWith("매일 07:00에 ‘새 공고 찾기’ 예약했어."));
    }

    /** The voice talk: "어떻게 돼 가?" answered from the job with no brain, "하던 거 멈춰" stops it, and its end is said once. */
    @Test public void theVoiceTalkAnswersFromTheJobAndSaysItsEndOnce() throws Exception {
        List<SpeechInput.Listener> ears = new java.util.ArrayList<>();
        List<BuddyTalk.Ask> asks = new CopyOnWriteArrayList<>();
        List<String> shown = new CopyOnWriteArrayList<>();
        SpeechInput.Ears before = BuddyVoice.ears; java.util.function.BiConsumer<android.content.Context, BuddyTalk.Ask> brain = BuddyVoice.brain;
        BuddyVoice.ears = new SpeechInput.Ears() {
            @Override public SpeechInput.Ear open(android.content.Context c) { return new SpeechInput.Ear() { @Override public void start(SpeechInput.Listener l) { ears.add(l); } @Override public void finish() {} @Override public void close() {} }; }
            @Override public boolean available(android.content.Context c) { return true; }
        };
        BuddyVoice.brain = (c, ask) -> asks.add(ask);
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(false);
        org.robolectric.shadows.ShadowTextToSpeech.addLanguageAvailability(java.util.Locale.KOREAN);
        BuddyVoice talk = new BuddyVoice(app, app.store());
        java.lang.reflect.Field turnsField = BuddyVoice.class.getDeclaredField("turns"); turnsField.setAccessible(true);
        @SuppressWarnings("unchecked") List<String[]> turns = (List<String[]>) turnsField.get(talk);
        java.util.function.Predicate<String> said = words -> turns.stream().anyMatch(t -> "assistant".equals(t[0]) && t[1].contains(words));
        long now = System.currentTimeMillis();
        BuddyJobs.Job job = BuddyJobs.start(BuddyJobs.PHONE, "배민", "지난번 메뉴 다시 담기", now);
        List<String> stops = new CopyOnWriteArrayList<>(); BuddyJobs.stopper(job, () -> stops.add(job.id)); BuddyJobs.opens(job, "app:com.sampleapp.baemin");
        BuddyJobs.progress(job, 3, "배달의민족", "‘장바구니’ 누름");
        try {
            talk.attach(new BuddyVoice.Show() {
                @Override public void state(String state, String text) { shown.add(state + ":" + text); }
                @Override public void state(String state, String text, int ms, String go) { shown.add(state + ":" + text + (go == null || go.isEmpty() ? "" : "|" + go)); }
            }, then -> then.run());
            talk.start(); settle(() -> ears.size() == 1);
            ears.get(0).ready(); ears.get(0).heard("어떻게 돼 가?");
            settle(() -> said.test("3단계"));
            assertTrue("from the job's record", said.test("배민 담는 중이야. 지금 3단계, 배달의민족에서, 마지막으로 ‘장바구니’ 누름."));
            assertTrue("no brain asked", asks.isEmpty());
            // The phone's voice comes up (Robolectric never says so itself), the words are said, and it listens again.
            android.speech.tts.TextToSpeech tts = org.robolectric.shadows.ShadowTextToSpeech.getLastTextToSpeechInstance();
            assertNotNull("said aloud", tts); shadowOf(tts).getOnInitListener().onInit(android.speech.tts.TextToSpeech.SUCCESS);
            settle(() -> ears.size() == 2);
            assertTrue(shadowOf(tts).getSpokenTextList().toString(), shadowOf(tts).getSpokenTextList().stream().anyMatch(t -> t.contains("3단계")));
            // It ends while 퍼레 listens: one passing word, a tap on it opening its result; never again.
            ears.get(1).ready();
            BuddyJobs.end(job, BuddyJobs.State.DONE, "장바구니에 2개 담았어요. 결제 전에 멈췄어요.", System.currentTimeMillis());
            settle(() -> said.test("배민 일 끝났어!"));
            assertTrue(shown.toString(), shown.stream().anyMatch(s -> s.startsWith("speaking:배민 일 끝났어!") && s.endsWith("|job:" + job.id)));
            assertFalse("claimed", BuddyJobs.claim(job));
            assertEquals(1, turns.stream().filter(t -> t[1].contains("배민 일 끝났어!")).count());
            assertTrue(asks.isEmpty());
            // "하던 거 멈춰" with another job running: its stop, no brain.
            BuddyJobs.Job web = BuddyJobs.start(BuddyJobs.WEB, "브라우저", "가정통신문 받기", System.currentTimeMillis());
            BuddyJobs.stopper(web, () -> stops.add(web.id));
            settle(() -> ears.size() == 3);
            ears.get(2).ready(); ears.get(2).heard("하던 거 멈춰");
            settle(() -> stops.contains(web.id));
            assertTrue(said.test("‘브라우저 받는 중’ 멈출게."));
            assertTrue(asks.isEmpty());
        } finally { talk.stop(); BuddyVoice.ears = before; BuddyVoice.brain = brain; }
    }

    /** The voice pill over other apps: the newest job on a line under the words, 중지 beside it, as small as a caption. */
    @Test @Config(qualifiers="w411dp-h891dp-xxhdpi") public void theVoicePillShowsTheJobWithItsStop() throws Exception {
        org.robolectric.shadows.ShadowSettings.setCanDrawOverlays(true);
        BuddyOverlay overlay = new BuddyOverlay(app, () -> {});
        overlay.show();
        assertTrue("shown", overlay.shown());
        assertEquals("", overlay.jobShown());
        long now = System.currentTimeMillis();
        BuddyJobs.Job job = BuddyJobs.start(BuddyJobs.PHONE, "배민", "지난번 메뉴 다시 담기", now);
        List<String> stops = new CopyOnWriteArrayList<>(); BuddyJobs.stopper(job, () -> stops.add(job.id));
        BuddyJobs.progress(job, 3, "배달의민족", "‘장바구니’ 누름");
        overlay.state("listening", "듣고 있어…");
        overlay.jobs(BuddyJobs.json(now));
        assertEquals("● 배민 담는 중 · 3단계", overlay.jobShown());
        View card = card(overlay);
        View stop = find(card, v -> v instanceof TextView t && "중지".contentEquals(t.getText()));
        assertNotNull("중지", stop); assertEquals(View.VISIBLE, stop.getVisibility());
        shot(card, "buddy_jobs_voice_pill");
        float d = app.getResources().getDisplayMetrics().density;
        settle(card);
        assertTrue("compact: " + card.getHeight() / d + "dp", card.getHeight() / d < 72);
        assertTrue("one tap stops it", stop.performClick()); settle(() -> !stops.isEmpty()); assertEquals(List.of(job.id), stops);
        assertEquals("멈추는 중", ((TextView) stop).getText().toString());
        BuddyJobs.end(job, BuddyJobs.State.STOPPED, "", now);
        overlay.jobs(BuddyJobs.json(now));
        assertEquals("배민 · 멈췄어", overlay.jobShown()); assertEquals(View.GONE, stop.getVisibility());
        overlay.jobs(new JSONArray()); assertEquals("", overlay.jobShown());
        overlay.hide();
    }

    // ── helpers ──
    private static View card(BuddyOverlay overlay) throws Exception { var f = BuddyOverlay.class.getDeclaredField("card"); f.setAccessible(true); return (View) f.get(overlay); }
    private static View find(View view, java.util.function.Predicate<View> match) {
        if (match.test(view)) return view;
        if (view instanceof ViewGroup g) for (int i = 0; i < g.getChildCount(); i++) { View c = find(g.getChildAt(i), match); if (c != null) return c; }
        return null;
    }
    private static void settle(View v) {
        var m = v.getResources().getDisplayMetrics();
        v.measure(View.MeasureSpec.makeMeasureSpec(m.widthPixels, View.MeasureSpec.AT_MOST), View.MeasureSpec.makeMeasureSpec(m.heightPixels, View.MeasureSpec.AT_MOST));
        v.layout(0, 0, v.getMeasuredWidth(), v.getMeasuredHeight()); shadowOf(Looper.getMainLooper()).idle();
    }
    private static void shot(View v, String name) throws Exception {
        settle(v);
        Bitmap b = Bitmap.createBitmap(Math.max(1, v.getWidth() + 48), Math.max(1, v.getHeight() + 48), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b); c.drawColor(0xff2b2d3a); c.translate(24, 24); v.draw(c);
        File dir = new File(System.getProperty("ui.output", "build/ui-verification")); dir.mkdirs();
        try (FileOutputStream out = new FileOutputStream(new File(dir, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, out); }
    }
    /** The main looper run (and the talk's threads let run) until done says so, a few virtual seconds at most. */
    private static void settle(java.util.function.BooleanSupplier done) {
        for (int i = 0; i < 300 && !done.getAsBoolean(); i++) {
            try { Thread.sleep(10); } catch (InterruptedException e) { return; }
            shadowOf(Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(20));
        }
    }
}
