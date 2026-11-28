package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * AI 브라우저's limits (준: "쓸데 없는 제한이 너무 많아"): what was lifted stays lifted, and what took the place of the old
 * 150-step stop still catches a job that only looks busy. A job scrolling a feed forever on one address (a new screen every
 * step, which the circle guards cannot see) stops at the checkpoint and says why; a job that reaches new pages or gathers
 * something goes on with a line about it. Also the tab's task memory letting its oldest turns go instead of refusing, any
 * address the user wrote opened as written, the bookmark cap that says what it lets go, and the Work and voice sizes.
 * Run by tools/build.py.
 */
final class BrowserLimitsRegression {
    private static int checked;
    private static void check(boolean condition, String why) {
        if (!condition) throw new AssertionError(why);
        checked++;
    }
    private static void same(Object want, Object got, String why) { check(java.util.Objects.equals(want, got), why + ": want <" + want + "> got <" + got + ">"); }
    static final long AT = 1_790_000_000_000L;

    public static void main(String[] args) throws Exception {
        endlessScroll();
        newPages();
        gathering();
        circlesStay();
        taskMemory();
        navigation();
        bookmarks();
        kit();
        prompts();
        folders();
        work();
        voice();
        System.out.println("Browser limits regression checks passed: " + checked);
    }

    /** One scroll on a feed whose screen changes every time (a new page key) while the address stays the same. */
    private static BrowserJournal.Verdict scroll(BrowserJournal journal, String url, int n) {
        journal.page(url, "피드");
        return journal.step("아래로 스크롤", "바뀜", false, "scroll:down", "screen-" + n, true, AT + n * 1000L);
    }

    private static void endlessScroll() {
        BrowserJournal.clear();
        BrowserJournal journal = BrowserJournal.open("scroll", "gpt", "피드를 끝까지 봐 줘", AT);
        journal.page("https://feed.example.com/home", "피드"); // the page the job starts on, before its first step
        BrowserJournal.Verdict verdict = BrowserJournal.Verdict.GO; int n = 0;
        while (verdict != BrowserJournal.Verdict.STOP && n < 1000) verdict = scroll(journal, "https://feed.example.com/home#item-" + n, ++n);
        same(BrowserJournal.Verdict.STOP, verdict, "an endless scroll on one address stops");
        same(BrowserJournal.CHECKPOINT, n, "at the first checkpoint, not before (no circle guard fired) and not never");
        check(journal.why().contains(BrowserJournal.CHECKPOINT + "단계 동안 새 페이지로 가지 않았고"), "says it reached no new page: " + journal.why());
        check(journal.why().contains("새로 모은 것"), "and gathered nothing: " + journal.why());
        check(journal.why().contains(BrowserJournal.CHECKPOINT + "단계 전"), "and how long since anything new: " + journal.why());
        same("feed.example.com", journal.site(), "where it stopped");

        // Reaching one new page early buys one window, never forever: the next checkpoint stops it.
        BrowserJournal later = BrowserJournal.open("scroll-2", "gpt", "피드", AT);
        later.page("https://feed.example.com/", "피드");
        verdict = BrowserJournal.Verdict.GO; n = 0;
        while (verdict != BrowserJournal.Verdict.STOP && n < 1000) verdict = scroll(later, n < 3 ? "https://feed.example.com/" : "https://feed.example.com/latest", ++n);
        same(2 * BrowserJournal.CHECKPOINT, n, "a page reached in the first window passes one checkpoint, then the scroll stops at the next");
        check(later.lines(1000).stream().anyMatch(l -> l.contains(BrowserJournal.CHECKPOINT + "단계마다 확인: 새 페이지 1곳 · 모은 자료 0개")), "the passed checkpoint is a line in the journal");
        same(2 * BrowserJournal.CHECKPOINT - 4, Integer.parseInt(later.why().replaceAll(".*새것이 생긴 건 (\\d+)단계 전.*", "$1")), "steps since the last new page (step 4)");
    }

    private static void newPages() {
        BrowserJournal journal = BrowserJournal.open("pages", "claude", "기사 500개를 차례로 읽어 정리해 줘", AT);
        journal.page("https://news.example.com/", "뉴스");
        BrowserJournal.Verdict verdict = BrowserJournal.Verdict.GO; List<String> notes = new ArrayList<>();
        for (int n = 1; n <= 4 * BrowserJournal.CHECKPOINT && verdict != BrowserJournal.Verdict.STOP; n++) {
            journal.page("https://news.example.com/article/" + (n / 10), "기사");
            verdict = journal.step("다음 기사", "바뀜", false, "click:next", "screen-" + n, true, AT + n * 1000L);
            String note = journal.takeNote(); if (!note.isEmpty()) notes.add(note);
        }
        check(verdict != BrowserJournal.Verdict.STOP, "a job reaching a new page every ten steps never stops at a checkpoint");
        same(600, journal.count(), "it ran past every checkpoint: no step cap any more");
        same(4, notes.size(), "one line at each checkpoint passed");
        same(BrowserJournal.CHECKPOINT + "단계마다 확인: 새 페이지 15곳 · 모은 자료 0개", notes.get(1), "the checkpoint line counts the new pages since the last one");
        same("", journal.takeNote(), "the line is given once");
    }

    private static void gathering() {
        // Same address and the same feed, but the job types into a field once in each window: that is work, it goes on.
        BrowserJournal journal = BrowserJournal.open("typing", "gemini", "댓글 200개에 답글", AT);
        journal.page("https://feed.example.com/", "피드");
        BrowserJournal.Verdict verdict = BrowserJournal.Verdict.GO;
        for (int n = 1; n <= 3 * BrowserJournal.CHECKPOINT && verdict != BrowserJournal.Verdict.STOP; n++) {
            if (n % 100 == 0) journal.gathered();
            verdict = scroll(journal, "https://feed.example.com/", n);
        }
        check(verdict != BrowserJournal.Verdict.STOP, "something gathered in each window keeps it going: " + journal.why());
        check(journal.lines(1000).stream().anyMatch(l -> l.contains("모은 자료 1개")), "and the line says what was gathered");
        // One thing gathered then nothing: the window after the next stops it.
        BrowserJournal once = BrowserJournal.open("once", "gemini", "x", AT);
        once.page("https://feed.example.com/", "피드");
        verdict = BrowserJournal.Verdict.GO; int n = 0;
        while (verdict != BrowserJournal.Verdict.STOP && n < 1000) { if (n == 10) once.gathered(); verdict = scroll(once, "https://feed.example.com/", ++n); }
        same(2 * BrowserJournal.CHECKPOINT, n, "gathered once: one more window, then it stops");
    }

    private static void circlesStay() {
        // The circle guards are as they were: the same step on an unchanged page warns, then stops within WARNING_SPAN.
        BrowserJournal journal = BrowserJournal.open("circle", "gpt", "x", AT);
        journal.page("https://site.example.com/", "s");
        List<BrowserJournal.Verdict> verdicts = new ArrayList<>();
        for (int n = 1; n <= 8; n++) verdicts.add(journal.step("「다음」 누름", "그대로", false, "click:e1", "same", true, AT + n));
        same(BrowserJournal.Verdict.WARN, verdicts.get(BrowserJournal.REPEATS), "the fourth same step warns");
        same(BrowserJournal.Verdict.STOP, verdicts.get(2 * BrowserJournal.REPEATS), "the next circle stops");
        // Reads only look: never stopped, however many.
        BrowserJournal reads = BrowserJournal.open("reads", "gpt", "x", AT);
        boolean stopped = false;
        for (int n = 1; n <= 40; n++) stopped |= reads.step("페이지 읽기", "✓", false, "read", "same", false, true, AT + n) == BrowserJournal.Verdict.STOP;
        check(!stopped, "reads are never stopped by the circle guards");
        // Jobs and steps kept: far more than the six jobs and eighty steps of before.
        BrowserJournal.clear();
        for (int i = 0; i < 60; i++) BrowserJournal.open("job" + i, "gpt", "x", AT).end("완료", AT + 1);
        BrowserJournal big = BrowserJournal.open("big", "gpt", "가".repeat(2000), AT);
        for (int n = 1; n <= 1200; n++) { big.page("https://site.example.com/p" + n, "p"); big.step("다음", "바뀜", false, "c" + n, "k" + n, true, AT + n); }
        same(1000, big.lines(5000).size(), "1000 steps kept");
        check(big.report(1, AT).contains("가".repeat(900)), "the request is kept up to 1000 characters");
        check(BrowserJournal.live(AT + 23 * 3_600_000L).contains("브라우저 작업"), "a job is shown for a day after");
    }

    private static void taskMemory() {
        BrowserTaskContext context = new BrowserTaskContext();
        String big = "가".repeat(90_000);
        check(context.canAccept(big), "a 90,000-character request is taken");
        check(!context.canAccept("가".repeat(BrowserPrompts.REQUEST_LIMIT + 1)), "past REQUEST_LIMIT it is not");
        int dropped = 0;
        for (int i = 0; i < 8; i++) {
            check(context.canAccept(i + big), "turn " + i + " is never refused for a full memory");
            dropped += context.accept(i + big);
            context.finish("결과 " + i, "done");
        }
        check(dropped > 0, "the oldest turns were let go");
        String snapshot = context.snapshot();
        JSONObject saved = new JSONObject(snapshot);
        JSONArray requests = saved.getJSONArray("requests"), outcomes = saved.getJSONArray("outcomes");
        int size = 0; for (int i = 0; i < requests.length(); i++) size += requests.getString(i).length();
        check(size <= BrowserTaskContext.USER_BUDGET, "the memory stays within its budget: " + size);
        same(7 + big, context.lastRequest(), "the newest request stays");
        same(8 - dropped, requests.length(), "dropped turns are gone");
        for (int i = 0; i < outcomes.length(); i++) {
            JSONObject row = outcomes.getJSONObject(i);
            same("결과 " + (dropped + row.getInt("request_index")), row.getString("result"), "each result still points at its own request");
        }
        BrowserTaskContext back = new BrowserTaskContext();
        check(back.restore(snapshot), "the trimmed memory reads back");
        check(back.canAccept("다음"), "and takes the next request");
        // A memory saved larger than the budget (an older build, a bigger budget) is read back, its oldest turns let go.
        JSONArray huge = new JSONArray(); for (int i = 0; i < 6; i++) huge.put(i + big);
        BrowserTaskContext old = new BrowserTaskContext();
        check(old.restore(new JSONObject().put("version", 1).put("requests", huge).put("outcomes", new JSONArray()).put("result", "").put("state", "done").toString()), "an oversized memory is not unreadable");
        check(old.canAccept("이어서"), "and does not refuse the next request");
        same(5 + big, old.lastRequest(), "its newest request kept");
    }

    private static void navigation() {
        String[] asked = {
            "https://www.youtube.com/watch?v=abc123&t=42s 열어줘",
            "https://docs.example.com/guide#install 로 이동해 줘",
            "http://192.168.0.10:8080/admin 열어",
            "https://login.example.com/ 접속해",
            "https://accounts.google.com/ServiceLogin?continue=x 열어줘",
            "open https://sso.example.org/auth?next=%2Fhome please",
        };
        String[] url = {
            "https://www.youtube.com/watch?v=abc123&t=42s",
            "https://docs.example.com/guide#install",
            "http://192.168.0.10:8080/admin",
            "https://login.example.com/",
            "https://accounts.google.com/ServiceLogin?continue=x",
            "https://sso.example.org/auth?next=%2Fhome",
        };
        for (int i = 0; i < asked.length; i++) {
            JSONObject open = BrowserTools.explicitNavigation(asked[i]);
            check(open != null && "open".equals(open.optString("action")), "opened as asked: " + asked[i] + " → " + open);
            same(url[i], open.optString("url"), "the address exactly as written");
        }
        JSONObject userinfo = BrowserTools.explicitNavigation("https://user:pw@example.com/ 열어줘");
        check(userinfo != null && !userinfo.has("action") && userinfo.optString("error").contains("비밀번호"), "an address carrying a password is not opened, and says why");
        same(null, BrowserTools.explicitNavigation("유튜브에서 고양이 영상 찾아 줘"), "a task, not an address: the planner");
        same("back", BrowserTools.explicitNavigation("뒤로 가 줘").optString("action"), "back is still back");
    }

    private static void bookmarks() {
        String saved = "[]";
        for (int i = 0; i < BrowserKit.MAX_BOOKMARKS; i++) {
            BrowserKit.Added added = BrowserKit.add(saved, List.of(new BrowserKit.Bookmark("https://site" + i + ".example.com/", "사이트 " + i, "", AT + i)));
            same("", added.note(), "under the cap nothing goes");
            saved = added.saved();
        }
        BrowserKit.Added more = BrowserKit.add(saved, List.of(new BrowserKit.Bookmark("https://new.example.com/", "새 사이트", "", AT + 1000)));
        same(BrowserKit.MAX_BOOKMARKS, BrowserKit.bookmarks(more.saved()).size(), "the 101st keeps the count at the cap");
        same(1, more.dropped().size(), "one goes");
        same("https://site0.example.com/", more.dropped().get(0).url(), "the oldest goes");
        same("북마크는 100개까지라 가장 오래된 ‘사이트 0’를 뺐어요", more.note(), "and the user is told which, never silently");
        check(BrowserKit.bookmarked(more.saved(), "https://new.example.com/"), "the new one is kept");
        List<BrowserKit.Bookmark> file = new ArrayList<>();
        for (int i = 0; i < 150; i++) file.add(new BrowserKit.Bookmark("https://import" + i + ".example.com/", "가져온 " + i, "폴더", AT - 100_000 + i));
        BrowserKit.Added imported = BrowserKit.add(more.saved(), file);
        same(BrowserKit.MAX_BOOKMARKS, BrowserKit.bookmarks(imported.saved()).size(), "an import stays within the cap");
        same(150, imported.dropped().size(), "the 150 oldest (all older than those kept) are left out");
        same(0, imported.added(), "none of the file's older pages stayed");
        same("북마크는 100개까지라 가장 오래된 150개를 뺐어요", imported.note(), "the import says how many were left out");
        List<BrowserKit.Bookmark> parsed = new ArrayList<>();
        StringBuilder html = new StringBuilder("<DL>");
        for (int i = 0; i < 300; i++) html.append("<DT><A HREF=\"https://p").append(i).append(".example.com/\" ADD_DATE=\"").append(1_700_000_000 + i).append("\">P").append(i).append("</A>");
        parsed.addAll(BrowserKit.parseExport(html.append("</DL>").toString(), AT));
        same(300, parsed.size(), "the whole export file is read, so an import knows what it leaves out");
    }

    private static void kit() {
        List<BrowserKit.Bookmark> tabs = new ArrayList<>();
        for (int i = 0; i < 40; i++) tabs.add(new BrowserKit.Bookmark("https://tab" + i + ".example.com/", "탭 " + i, "", AT));
        String projects = BrowserKit.saveProject("", "여행 준비", tabs, AT);
        same(40, BrowserKit.projects(projects).get(0).pages().size(), "a project keeps every tab (was 6)");
        for (int i = 0; i < 40; i++) projects = BrowserKit.saveProject(projects, "프로젝트 " + i, tabs.subList(0, 1), AT + i);
        same(41, BrowserKit.projects(projects).size(), "far more than 30 projects");
        String skills = "";
        for (int i = 0; i < 120; i++) skills = BrowserKit.saveSkill(skills, "", "스킬 " + i, "할 일 " + i, "", AT + i);
        same(120, BrowserKit.skills(skills).size(), "far more than 50 skills");
        String longSkill = BrowserKit.saveSkill("", "", "이".repeat(BrowserKit.SKILL_NAME), "할".repeat(BrowserKit.SKILL_PROMPT), "https://example.com/" + "a".repeat(5000), AT);
        same(BrowserKit.SKILL_PROMPT, BrowserKit.skills(longSkill).get(0).prompt().length(), "a 20,000-character skill is kept whole");
        same(BrowserKit.SKILL_NAME, BrowserKit.skills(longSkill).get(0).name().length(), "a 200-character name too");
    }

    private static void prompts() {
        String stored = "";
        for (int i = 0; i < 40; i++) stored = BrowserPrompts.remember(stored, "요청 " + i);
        same(BrowserPrompts.RECENT_LIMIT, BrowserPrompts.recents(stored).size(), "30 recent requests remembered");
        String long4000 = "이".repeat(4000);
        check(BrowserPrompts.recents(BrowserPrompts.remember(stored, long4000)).get(0).text().equals(long4000), "a 4000-character request is remembered");
        same(BrowserPrompts.QUOTE_LIMIT, BrowserPrompts.quote("가".repeat(50_000)).length(), "a selection quoted up to 20,000 characters");
        check(BrowserPrompts.selectionRead(BrowserPrompts.translate("가".repeat(15_000))), "a long selection is still the app's own read");
    }

    private static void folders() {
        same("자료.7z", FolderTools.name("자료.7z", "x.7z", ""), "any kind of file is kept (7z)");
        same("책.epub", FolderTools.name("책.epub", "x.epub", "zip"), "a zip-based format under its own name");
        same("notes", FolderTools.name("", "notes", ""), "a file without an extension too");
        same(null, FolderTools.name("앱.apk", "x.apk", "zip"), "never an installer");
        same(null, FolderTools.name("setup.exe", "setup.exe", ""), "never an executable");
        same(null, FolderTools.name("tool.dmg", "tool.dmg", ""), "never another system's installer");
        same(null, FolderTools.name("보고서.pdf", "x.html", ""), "a page is still not kept as a PDF");
        String longName = FolderTools.name("가".repeat(200) + ".pdf", "x.pdf", "pdf");
        check(longName != null && longName.endsWith(".pdf") && longName.getBytes(java.nio.charset.StandardCharsets.UTF_8).length <= FolderTools.NAME, "a long Korean name fits 255 bytes: " + (longName == null ? 0 : longName.length()));
        StringBuilder deep = new StringBuilder(); for (int i = 0; i < 20; i++) deep.append(i == 0 ? "" : "/").append("f").append(i);
        same(deep.toString(), FolderTools.folder(deep.toString()), "20 folders deep");
        same(null, FolderTools.folder(deep + "/f20"), "21 is not");
    }

    private static void work() throws Exception {
        JSONArray tasks = new JSONArray();
        for (int i = 0; i < BrowserWorkJobs.BATCH; i++) tasks.put(new JSONObject().put("request", "다".repeat(BrowserWorkJobs.REQUEST)).put("url", "https://example.com/").put("expected_downloads", BrowserWorkJobs.DOWNLOADS));
        same(10, BrowserWorkJobs.requests(new JSONObject().put("tasks", tasks)).length(), "ten tasks of 50,000 characters, fifty files each");
        tasks.put(new JSONObject().put("request", "x").put("url", "https://example.com/").put("expected_downloads", 0));
        try { BrowserWorkJobs.requests(new JSONObject().put("tasks", tasks)); check(false, "eleven"); }
        catch (IllegalArgumentException many) { check(many.getMessage().contains("1~10"), "says how many: " + many.getMessage()); }
        BrowserWorkJobs receipts = new BrowserWorkJobs("room", "run");
        JSONObject last = null;
        for (int i = 0; i < 150; i++) last = receipts.add(new JSONObject().put("request", "r").put("url", "https://example.com/").put("expected_downloads", 0));
        same("queued", receipts.status(last.getString("job_id")).optString("state"), "150 queued receipts are kept (was 30)");
        BrowserArtifactPolicy.Info pdf = BrowserArtifactPolicy.classify("https://example.com/a.pdf", Map.of("Content-Type", "application/pdf"));
        BrowserArtifactPolicy.Info picture = BrowserArtifactPolicy.classify("https://example.com/a.png", Map.of("Content-Type", "image/png"));
        same(4L << 30, pdf.limit(), "a PDF up to 4GB");
        same(4L << 30, picture.limit(), "a picture up to 4GB");
        try { BrowserArtifactPolicy.classify("https://example.com/a.apk", Map.of("Content-Type", "application/vnd.android.package-archive")); check(false, "apk"); }
        catch (java.io.IOException refused) { check(refused.getMessage().contains("실행 파일"), "an installer is still refused"); }
        same(200, MainBrowser.ROUNDS, "200 rounds a browsing reply");
        same(4L << 30, MusicDownload.LIMIT, "an audio file up to 4GB");
    }

    private static void voice() {
        BrowserJobs jobs = new BrowserJobs();
        JSONObject accepted = jobs.submit("call", "나".repeat(BrowserPrompts.REQUEST_LIMIT), (request, done) -> {}, value -> {});
        check(accepted.optBoolean("accepted"), "a 100,000-character browser job is taken: " + accepted);
        for (int i = 0; i < 63; i++) check(jobs.submit("c" + i, "r" + i, (request, done) -> {}, value -> {}).optBoolean("accepted"), "64 running jobs at once");
        check(jobs.submit("c64", "r", (request, done) -> {}, value -> {}).optBoolean("busy"), "the 65th waits for one to finish");
        VoiceBrowserRequest words = new VoiceBrowserRequest();
        words.finalized(7, "라".repeat(VoiceBrowserRequest.TASK_LIMIT));
        JSONObject envelope = new JSONObject(words.instruction(7, "마".repeat(9000), "tab"));
        same(VoiceBrowserRequest.TASK_LIMIT, envelope.getString("original_user_instruction").length(), "a 50,000-character spoken task");
        same(VoiceBrowserRequest.INTERPRETATION_LIMIT, envelope.getString("model_interpretation").length(), "with 8000 characters of the voice model's reading");
        check(envelope.toString().length() <= BrowserPrompts.REQUEST_LIMIT, "and the whole fits one browser request");
    }
}
