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
 * the second time the job stops and says where. Pure Java; the newest few jobs are kept in memory only.
 */
public final class BrowserJournal {
    public enum Verdict { GO, WARN, STOP }
    public record Step(int number, long at, String site, String what, String result, boolean failed) {}

    /** A job stops after this many steps whatever happens: a long upload with every field takes about 60. */
    public static final int MAX_STEPS = 150;
    /** The same step on a page that did not change, this many times in a row. */
    static final int REPEATS = 2;
    /** Clicks, keys and scrolls in a row after which the page is still the same. */
    static final int UNCHANGED = 8;
    /** Failed steps in a row. */
    static final int FAILURES = 5;
    private static final int KEEP_JOBS = 6, KEEP_STEPS = 80;
    private static final long SHOWN_AFTER_END = 30 * 60_000L;
    private static final LinkedHashMap<String, BrowserJournal> JOBS = new LinkedHashMap<>();

    public final String id;
    public final long started;
    private String brain;
    private final String request;
    private final ArrayDeque<Step> steps = new ArrayDeque<>();
    private int count, repeats, unchanged, failures, warnings;
    private String site = "", title = "", lastSignature = "", lastPage = "", outcome = "", why = "";
    private long ended;

    private BrowserJournal(String id, String brain, String request, long now) {
        this.id = id; this.brain = brain == null ? "" : brain; this.request = clip(request, 160); this.started = now;
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
    /** The page the job is on now: its site (host) and title. */
    public synchronized void page(String url, String title) {
        String host = host(url);
        if (!host.isEmpty()) site = host;
        if (title != null && !title.isBlank()) this.title = clip(title, 60);
    }
    public synchronized String site() { return site; }
    public synchronized int count() { return count; }

    /**
     * One step that ran. signature: what was done to what (the same signature twice is the same step); page: a key of the
     * page after it; counts: whether the page should change after it (a click, a key, a scroll; not typing into a field,
     * which leaves the page's list of controls as it was). Returns WARN the first time the job goes in circles and STOP
     * the next time, or after MAX_STEPS.
     */
    public synchronized Verdict step(String what, String result, boolean failed, String signature, String page, boolean counts, long now) {
        count++;
        steps.addLast(new Step(count, now, site, clip(what, 90), clip(result, 120), failed));
        while (steps.size() > KEEP_STEPS) steps.removeFirst();
        String key = page == null ? "" : page;
        boolean same = !key.isEmpty() && key.equals(lastPage);
        repeats = same && signature != null && signature.equals(lastSignature) ? repeats + 1 : 0;
        unchanged = counts ? (same ? unchanged + 1 : 0) : unchanged;
        failures = failed ? failures + 1 : 0;
        lastSignature = signature == null ? "" : signature; if (!key.isEmpty()) lastPage = key;
        if (count >= MAX_STEPS) { why = count + "단계를 넘겨 멈췄어요"; return Verdict.STOP; }
        String circle = repeats >= REPEATS ? "같은 동작(" + clip(what, 40) + ")을 " + (repeats + 1) + "번 해도 화면이 그대로예요"
            : unchanged >= UNCHANGED ? "동작을 " + unchanged + "번 했는데 화면이 바뀌지 않았어요"
            : failures >= FAILURES ? failures + "번 연달아 실패했어요" : "";
        if (circle.isEmpty()) return Verdict.GO;
        repeats = 0; unchanged = 0; failures = 0; why = circle;
        return ++warnings >= 2 ? Verdict.STOP : Verdict.WARN;
    }
    /** Why step last said WARN or STOP. */
    public synchronized String why() { return why; }
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
    /** Every job still running, or ended in the last 30 minutes, newest last; "" when there is none. */
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
