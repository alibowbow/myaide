package dev.jun.gyeot.core;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;

/**
 * What one browser job did, step by step (time, site, what it did to which control, how it came out), so that the person
 * and the AI they talk to can see it while it runs: the job's status line, 퍼레대화 when asked how it is going, and the
 * result when it stops. It also notices a job going in circles (BrowserAgent asks it after every step): the same step on
 * an unchanged page, steps that change nothing, failure after failure. The first time the planner is told to change course;
 * the second time the job stops and says where. There is no cap on steps: instead, every CHECKPOINT steps it looks back, and a
 * job that in all those steps reached no page it had not been on and gathered nothing (a document read, a file received, text
 * typed, a file attached, a new tab) stops and says so; a job that did either goes on with one line about it. Endless scrolling
 * of a feed changes the screen every step, which the circle guards cannot catch. Pure Java; the newest jobs are kept in memory only.
 */
public final class BrowserJournal {
    public enum Verdict { GO, WARN, STOP }
    public record Step(int number, long at, String site, String what, String result, boolean failed) {}

    /**
     * Every this many steps the job is looked back on: no new page and nothing gathered since the last look stops it (준: "작업단계
     * 바꾼 뒤에 무한루프 돌지는 않겠지?"). A long upload with every field takes about 60 steps, so honest work passes on its way.
     */
    public static final int CHECKPOINT = 150;
    /** The same step on a page that did not change, this many times again in a row (four tries in all). */
    static final int REPEATS = 3;
    /** Clicks, keys and scrolls in a row after which the page is still the same. */
    static final int UNCHANGED = 10;
    /** Failed steps in a row. */
    static final int FAILURES = 5;
    /**
     * Reads in a row on an unchanged page: the planner is nudged, never stopped. A read changes nothing, and the planner
     * often reads while a site works (an upload's checks): 준's Osprey upload stopped at step 19 on three reads.
     */
    static final int READS = 4;
    /** A second circle stops the job only this many steps after the first; later the first is forgotten. */
    static final int WARNING_SPAN = 12;
    private static final int KEEP_JOBS = 50, KEEP_STEPS = 1000, REQUEST_CLIP = 1000;
    private static final long SHOWN_AFTER_END = 24 * 60 * 60_000L;
    private static final LinkedHashMap<String, BrowserJournal> JOBS = new LinkedHashMap<>();

    public final String id;
    public final long started;
    private String brain;
    private final String request;
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int count, repeats, unchanged, failures, warnings, reads, warnedAt;
    /** Page addresses this job has been on; new ones and things gathered since the last checkpoint; the step anything new came. */
    private final java.util.Set<String> visited = new java.util.HashSet<>();
    private int newPages, gathered, lastNewAt;
    private boolean begun;
    private String site = "", title = "", lastSignature = "", lastPage = "", outcome = "", why = "", note = "";
    private long ended;

    private BrowserJournal(String id, String brain, String request, long now) {
        this.id = id; this.brain = brain == null ? "" : brain; this.request = clip(request, REQUEST_CLIP); this.started = now;
    }
    /** A new job's journal; an older one under the same id is replaced. */
    public static BrowserJournal open(String id, String brain, String request, long now) {
        BrowserJournal journal = new BrowserJournal(id, brain, request, now);
        synchronized (JOBS) {
            JOBS.remove(id); JOBS.put(id, journal);
            while (JOBS.size() > KEEP_JOBS) JOBS.remove(JOBS.keySet().iterator().next());
        }
        return journal;
    }
    public synchronized void brain(String brain) { this.brain = brain == null ? "" : brain; }
    /**
     * The page the job is on now: its site (host) and title, told before the step that brought it is counted. An address the
     * job has not been on counts as new for the next checkpoint; the first page told (the one the job starts on) does not.
     */
    public synchronized void page(String url, String title) {
        String host = host(url);
        if (!host.isEmpty()) site = host;
        if (title != null && !title.isBlank()) this.title = clip(title, 60);
        String address = address(url);
        boolean start = !begun; begun = true;
        if (!address.isEmpty() && visited.add(address) && !start) { newPages++; lastNewAt = count + 1; }
    }
    /**
     * The job gathered something new in the step about to be counted: a document read or a file received (a new entry in
     * BrowserAgent.materials), a download, text typed, a file attached, a new tab opened.
     */
    public synchronized void gathered() { gathered++; lastNewAt = count + 1; }
    public synchronized String site() { return site; }
    public synchronized int count() { return count; }

    /**
     * One step that ran. signature: what was done to what (the same signature twice is the same step); page: a key of the
     * page after it; counts: whether the page should change after it (a click, a key, a scroll; not typing into a field,
     * which leaves the page's list of controls as it was). Returns WARN the first time the job goes in circles and STOP
     * the next time within WARNING_SPAN steps, or at a CHECKPOINT with no new page and nothing gathered since the last one.
     */
    public synchronized Verdict step(String what, String result, boolean failed, String signature, String page, boolean counts, long now) {
        return step(what, result, failed, signature, page, counts, false, now);
    }
    /** observe: the step only looked (a read, the tab list): it can earn a nudge, never a stop. */
    public synchronized Verdict step(String what, String result, boolean failed, String signature, String page, boolean counts, boolean observe, long now) {
        count++;
        steps.addLast(new Step(count, now, site, clip(what, 90), clip(result, 120), failed));
        while (steps.size() > KEEP_STEPS) steps.removeFirst();
        String key = page == null ? "" : page;
        boolean same = !key.isEmpty() && key.equals(lastPage);
        if (count % CHECKPOINT == 0) {
            if (newPages == 0 && gathered == 0) {
                why = CHECKPOINT + "단계 동안 새 페이지로 가지 않았고, 새로 모은 것(읽은 문서·받은 파일·입력한 글·첨부·새 탭)도 없어요"
                    + " · 마지막으로 새것이 생긴 건 " + (count - lastNewAt) + "단계 전이에요";
                return Verdict.STOP;
            }
            note = CHECKPOINT + "단계마다 확인: 새 페이지 " + newPages + "곳 · 모은 자료 " + gathered + "개";
            // The line goes just before this step's own, so last() is still the step that ran.
            Step ran = steps.removeLast(); steps.addLast(new Step(count, now, site, note, "", false)); steps.addLast(ran);
            while (steps.size() > KEEP_STEPS) steps.removeFirst();
            newPages = 0; gathered = 0;
        }
        if (observe) {
            reads = same || key.isEmpty() ? reads + 1 : 0;
            if (!key.isEmpty()) lastPage = key;
            if (failed) failures++;
            if (reads < READS) return Verdict.GO;
            reads = 0; why = "페이지를 " + READS + "번 읽기만 하고 아무것도 하지 않았어요";
            return Verdict.WARN;
        }
        reads = 0;
        repeats = same && signature != null && signature.equals(lastSignature) ? repeats + 1 : 0;
        unchanged = counts ? (same ? unchanged + 1 : 0) : unchanged;
        failures = failed ? failures + 1 : 0;
        lastSignature = signature == null ? "" : signature; if (!key.isEmpty()) lastPage = key;
        String circle = repeats >= REPEATS ? "같은 동작(" + clip(what, 40) + ")을 " + (repeats + 1) + "번 해도 화면이 그대로예요"
            : unchanged >= UNCHANGED ? "동작을 " + unchanged + "번 했는데 화면이 바뀌지 않았어요"
            : failures >= FAILURES ? failures + "번 연달아 실패했어요" : "";
        if (circle.isEmpty()) return Verdict.GO;
        repeats = 0; unchanged = 0; failures = 0; why = circle;
        if (warnings > 0 && count - warnedAt > WARNING_SPAN) warnings = 0;
        warnedAt = count;
        return ++warnings >= 2 ? Verdict.STOP : Verdict.WARN;
    }
    /** Why step last said WARN or STOP. */
    public synchronized String why() { return why; }
    /** The checkpoint line the last step passed ("150단계마다 확인: 새 페이지 3곳 · 모은 자료 2개"), once; "" otherwise. */
    public synchronized String takeNote() { String line = note; note = ""; return line; }
    public synchronized void end(String outcome, long now) { this.outcome = outcome == null ? "" : outcome; ended = now; }
    public synchronized boolean running() { return ended == 0; }
    public synchronized Step last() { return steps.peekLast(); }

    /** The status line's tail: "23단계 · studio.youtube.com · 7분째". */
    public synchronized String line(long now) {
        List<String> parts = new ArrayList<>();
        if (count > 0) parts.add(count + "단계");
        if (!site.isEmpty()) parts.add(site);
        parts.add(minutes(now - started));
        return String.join(" · ", parts);
    }
    /** The last steps, oldest first, one line each: "23:31 · 38 · studio.youtube.com · 「다음」 누름 → ✓". */
    public synchronized List<String> lines(int last) {
        List<String> out = new ArrayList<>();
        int skip = Math.max(0, steps.size() - last), i = 0;
        for (Step s : steps) {
            if (i++ < skip) continue;
            out.add(clock(s.at()) + " · " + s.number() + " · " + (s.site().isEmpty() ? "" : s.site() + " · ") + s.what() + (s.result().isEmpty() ? "" : " → " + s.result()));
        }
        return out;
    }
    /** This job in a few lines for an AI reading it: when it started, with which AI, where it is now, its last steps. */
    public synchronized String report(int last, long now) {
        StringBuilder b = new StringBuilder();
        b.append("브라우저 작업 ").append(running() ? "진행 중" : "끝남(" + (outcome.isEmpty() ? "결과 미상" : outcome) + ", " + clock(ended) + ")")
            .append(" · ").append(brain.isEmpty() ? "" : brain + " · ").append(clock(started)).append(" 시작 · ")
            .append(running() ? minutes(now - started) : took(ended - started)).append(" · ").append(count).append("단계");
        if (!site.isEmpty()) b.append(" · 지금 ").append(site).append(title.isEmpty() ? "" : " 「" + title + "」");
        if (!request.isEmpty()) b.append("\n  요청: ").append(request);
        if (!why.isEmpty()) b.append("\n  막힌 점: ").append(why);
        for (String line : lines(last)) b.append("\n  ").append(line);
        return b.toString();
    }
    /** Every job still running, or ended in the last 24 hours, newest last; "" when there is none. */
    public static String live(long now) {
        List<BrowserJournal> shown = new ArrayList<>();
        synchronized (JOBS) { for (BrowserJournal j : JOBS.values()) if (j.running() || now - j.endedAt() < SHOWN_AFTER_END) shown.add(j); }
        if (shown.isEmpty()) return "";
        StringBuilder b = new StringBuilder("[앱이 적은 브라우저 작업 기록: 진행을 물으면 이 기록으로 단계·사이트·최근 동작을 말해 주세요]");
        int each = shown.size() > 1 ? 4 : 7;
        for (int i = Math.max(0, shown.size() - 2); i < shown.size(); i++) b.append('\n').append(shown.get(i).report(each, now));
        return b.toString();
    }
    private synchronized long endedAt() { return ended; }
    static void clear() { synchronized (JOBS) { JOBS.clear(); } }

    /** A page's address for telling pages apart: scheme, host, path and query, without the #fragment; "" when unreadable. */
    static String address(String url) {
        if (url == null || url.isBlank()) return "";
        try {
            java.net.URI u = java.net.URI.create(url.strip());
            if (u.getHost() == null) return "";
            String path = u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath();
            String value = (u.getScheme() == null ? "" : u.getScheme().toLowerCase(java.util.Locale.ROOT) + "://") + u.getHost().toLowerCase(java.util.Locale.ROOT)
                + (u.getPort() < 0 ? "" : ":" + u.getPort()) + path + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            return value.length() > 600 ? value.substring(0, 600) : value;
        } catch (RuntimeException unreadable) { return ""; }
    }
    public static String host(String url) {
        if (url == null || url.isBlank()) return "";
        try { String host = java.net.URI.create(url.strip()).getHost(); return host == null ? "" : host.toLowerCase(java.util.Locale.ROOT).replaceFirst("^www\\.", ""); }
        catch (RuntimeException unreadable) { return ""; }
    }
    private static String minutes(long elapsed) { long m = Math.max(0, elapsed) / 60_000; return m < 1 ? "방금 시작" : m + "분째"; }
    private static String took(long elapsed) { long m = Math.max(0, elapsed) / 60_000; return m < 1 ? "1분 안에 끝남" : m + "분 걸림"; }
    private static String clock(long at) {
        java.time.LocalTime t = java.time.Instant.ofEpochMilli(at).atZone(java.time.ZoneId.systemDefault()).toLocalTime();
        return String.format(java.util.Locale.ROOT, "%02d:%02d", t.getHour(), t.getMinute());
    }
    static String clip(String text, int max) {
        String s = text == null ? "" : text.replaceAll("\\s+", " ").strip();
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
