package dev.jun.gyeot.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * The jobs 퍼레 took on and handed to an engine (준, "문 하나": 퍼레가 무슨 일이든 직접 맡는다): work on the screen inside apps
 * (PHONE: the phone agent), on websites in the AI browser (WEB), long work in a 작업창 room (WORK) and a research in 연구실
 * (RESEARCH). Whichever engine runs one, it has one look: a short line ("배민 담는 중 · 3단계") with 중지 on the home screen and
 * in the voice pill, what it is doing now when asked ("어떻게 돼 가?": its steps, site and last step, never "모르겠어"), and
 * one passing word when it ends (준: "흘러가듯이 슬쩍"), which a tap opens (open: what its result is). Pure Java, in memory
 * only: a job is never replayed after the app stops.
 */
public final class BuddyJobs {
    private BuddyJobs() {}
    public static final String PHONE = "phone", WEB = "web", WORK = "work", RESEARCH = "research";
    /** RUNNING while the engine works; then DONE, FAILED, STOPPED (the user stopped it) or WAITING (it needs the user: a login, an approval). */
    public enum State { RUNNING, WAITING, DONE, FAILED, STOPPED }
    /** How long an ended job still shows on its line, how long its passing word may still be said, and how long it is told when asked. */
    public static final long SHOWN_MS = 90_000, TOLD_MS = 30 * 60_000L, ASKED_MS = 15 * 60_000L;
    private static final int KEEP = 12;

    /** One job: what kind, who does it ("배민", "브라우저", "작업창", "연구실"), the words it was asked with, and how it goes. */
    public static final class Job {
        public final String id, kind, who, request, label; public final long started;
        private State state = State.RUNNING; private int step; private String site = "", last = "", result = "", open = "";
        private long ended; private boolean said, stopAsked; private Runnable stopper;
        private Job(String kind, String who, String request, long now) {
            this.id = "j" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
            this.kind = kind; this.who = who == null || who.isBlank() ? whoOf(kind) : clip(who.strip(), 24); this.request = clip(request, 400); this.started = now;
            this.label = labelOf(kind, this.who, request);
        }
        public synchronized State state() { return state; }
        public synchronized boolean running() { return state == State.RUNNING; }
        public synchronized int step() { return step; }
        public synchronized String site() { return site; }
        public synchronized String last() { return last; }
        public synchronized String result() { return result; }
        /** What a tap opens: "work:<room>", "lab:<project>", "browser", "phone" … ("" for nothing). */
        public synchronized String open() { return open; }
        public synchronized long ended() { return ended; }
        public synchronized boolean stoppable() { return state == State.RUNNING && stopper != null && !stopAsked; }
    }

    private static final LinkedHashMap<String, Job> JOBS = new LinkedHashMap<>();
    private static final List<Consumer<Job>> LISTENERS = new CopyOnWriteArrayList<>();

    /** A new job, running (the engine was asked to start it). */
    public static Job start(String kind, String who, String request, long now) {
        Job job = new Job(kind, who, request, now);
        synchronized (JOBS) {
            JOBS.put(job.id, job);
            while (JOBS.size() > KEEP) {
                String drop = null;
                for (Job j : JOBS.values()) if (!j.running()) { drop = j.id; break; }
                if (drop == null) break;
                JOBS.remove(drop);
            }
        }
        changed(job);
        return job;
    }
    /** Who hears of every change: a job started, went a step on, or ended (on the thread that changed it). */
    public static void listen(Consumer<Job> l) { if (l != null) LISTENERS.add(l); }
    public static void unlisten(Consumer<Job> l) { LISTENERS.remove(l); }
    private static void changed(Job job) { for (Consumer<Job> l : LISTENERS) try { l.accept(job); } catch (RuntimeException ignored) { /* one listener never stops another */ } }

    public static Job find(String id) { synchronized (JOBS) { return id == null ? null : JOBS.get(id); } }
    /** Every job kept, oldest first. */
    public static List<Job> all() { synchronized (JOBS) { return new ArrayList<>(JOBS.values()); } }
    /** The jobs that run now. */
    public static List<Job> running() { List<Job> out = new ArrayList<>(); for (Job j : all()) if (j.running()) out.add(j); return out; }
    /** The jobs on the line now: running or waiting, or ended in the last SHOWN_MS. */
    public static List<Job> shown(long now) {
        List<Job> out = new ArrayList<>();
        for (Job j : all()) if (j.running() || now - j.ended() < SHOWN_MS) out.add(j);
        return out;
    }
    /** Whether there is a job to tell of: running, or ended in the last ASKED_MS. */
    public static boolean any(long now) { for (Job j : all()) if (j.running() || now - j.ended() < ASKED_MS) return true; return false; }
    public static void clear() { synchronized (JOBS) { JOBS.clear(); } }
    /** A job the engine would not take after all: gone, without a line or a word. */
    public static void drop(Job job) { if (job == null) return; synchronized (job) { job.said = true; job.state = State.STOPPED; job.ended = 1; } synchronized (JOBS) { JOBS.remove(job.id); } changed(job); }

    // ── the engine tells how it goes ──
    /** The step it is at, where (an app or a site), and what it did last; "" or a negative step leave that as it was. */
    public static void progress(Job job, int step, String site, String last) {
        boolean moved;
        synchronized (job) {
            if (job.state != State.RUNNING) return;
            String s = site == null || site.isBlank() ? job.site : clip(site.strip(), 40), l = last == null || last.isBlank() ? job.last : clip(last.strip(), 70);
            int n = step < 0 ? job.step : step;
            moved = n != job.step || !s.equals(job.site) || !l.equals(job.last);
            job.step = n; job.site = s; job.last = l;
        }
        if (moved) changed(job);
    }
    /** What a tap on it opens (its result). */
    public static void opens(Job job, String what) { synchronized (job) { job.open = what == null ? "" : what; } changed(job); }
    /** How it is stopped (the engine's own stop); null: it cannot be. */
    public static void stopper(Job job, Runnable stop) { synchronized (job) { job.stopper = stop; } changed(job); }
    /** It ended: done, failed, waiting for the user or stopped (a stop the user asked for is STOPPED whatever came back), with its result in a few words. */
    public static void end(Job job, State how, String result, long now) {
        synchronized (job) {
            if (job.state != State.RUNNING) return;
            job.state = job.stopAsked ? State.STOPPED : how == State.RUNNING ? State.DONE : how;
            job.result = clip(result, 300); job.ended = now; job.stopper = null;
        }
        changed(job);
    }
    /** 중지 (the home screen's line, the voice pill, "그거 멈춰"): the engine's stop, the same everywhere; false when it does not run. */
    public static boolean stop(String id) {
        Job job = find(id); if (job == null) return false;
        Runnable stop;
        synchronized (job) { if (job.state != State.RUNNING || job.stopAsked) return false; job.stopAsked = true; stop = job.stopper; }
        changed(job);
        if (stop != null) try { stop.run(); } catch (RuntimeException ignored) { /* ended below */ }
        // An engine that cannot stop itself is let go here: nothing waits on it.
        if (stop == null) end(job, State.STOPPED, "멈췄어요.", System.currentTimeMillis());
        return true;
    }
    /** The newest job that runs, or null. */
    public static Job latestRunning() { Job out = null; for (Job j : all()) if (j.running()) out = j; return out; }
    /** Its passing word to say, once: true the first time it is claimed after it ended (the voice talk or the home screen says it). */
    public static boolean claim(Job job) {
        synchronized (job) { if (job.state == State.RUNNING || job.said) return false; job.said = true; return true; }
    }
    /** A claimed word that was not said after all (the voice talk ended first): another may say it. */
    public static void unclaim(Job job) { if (job != null) synchronized (job) { job.said = false; } }
    /** The ended jobs whose passing word was not said yet (ended in the last TOLD_MS, not stopped by the user). */
    public static List<Job> untold(long now) {
        List<Job> out = new ArrayList<>();
        for (Job j : all()) synchronized (j) { if (!j.said && j.ended > 0 && now - j.ended < TOLD_MS && j.state != State.STOPPED) out.add(j); }
        return out;
    }

    // ── its words ──
    /** The line: "배민 담는 중 · 3단계", "작업창 · 자료 찾는 중", "배민 · 끝났어", "브라우저 · 확인 필요". */
    public static String line(Job j, long now) {
        State s = j.state();
        return switch (s) {
            case RUNNING -> (WORK.equals(j.kind) || RESEARCH.equals(j.kind)) && !j.last().isEmpty() ? j.who + " · " + clip(j.last(), 18)
                : j.label + (j.step() > 0 ? " · " + j.step() + "단계" : "");
            case WAITING -> j.who + " · 확인 필요";
            case DONE -> j.who + " · 끝났어";
            case FAILED -> j.who + " · 막혔어";
            case STOPPED -> j.who + " · 멈췄어";
        };
    }
    /** The jobs on the line, for the home screen: [{id, line, state, stop, open}]. */
    public static JSONArray json(long now) {
        JSONArray out = new JSONArray();
        for (Job j : shown(now)) out.put(Jsons.obj("id", j.id, "kind", j.kind, "line", line(j, now), "state", j.state().name().toLowerCase(Locale.ROOT), "stop", j.stoppable(), "open", !j.open().isEmpty()));
        return out;
    }
    /** The one word said in passing as it ends ("배민 일 끝났어!"); "" for one the user stopped. */
    public static String passing(Job j) {
        String who = subject(j);
        return switch (j.state()) {
            case DONE -> who + " 끝났어!" + (j.open().isEmpty() ? "" : " 눌러 봐");
            case FAILED -> Brand.josa(who, "은", "는") + " 막혔어." + (j.open().isEmpty() ? "" : " 눌러서 봐 줘");
            case WAITING -> who + ", 확인이 필요해!" + (j.open().isEmpty() ? "" : " 눌러 봐");
            default -> "";
        };
    }
    /** "작업창 일은", "연구실 연구는", "배민 일은". */
    private static String topic(Job j) { return Brand.josa(subject(j), "은", "는"); }
    private static String subject(Job j) {
        return switch (j.kind) { case WORK -> "작업창 일"; case RESEARCH -> "연구실 연구"; default -> j.who + " 일"; };
    }
    /** What 퍼레 says when asked how it goes, from the jobs' own status: never "모르겠어". */
    public static String answer(long now) {
        List<Job> live = new ArrayList<>(), ended = new ArrayList<>();
        for (Job j : all()) { if (j.running()) live.add(j); else if (now - j.ended() < ASKED_MS) ended.add(j); }
        if (live.isEmpty() && ended.isEmpty()) return "지금 맡아서 하는 일은 없어. 시킬 거 있으면 말해 줘!";
        List<String> parts = new ArrayList<>();
        for (int i = live.size() - 1; i >= 0 && parts.size() < 2; i--) parts.add(status(live.get(i), now));
        for (int i = ended.size() - 1; i >= 0 && parts.size() < 2; i--) parts.add(status(ended.get(i), now));
        return String.join(" ", parts);
    }
    private static String status(Job j, long now) {
        String step = j.step() > 0 ? "지금 " + j.step() + "단계" : "막 시작했어", site = j.site().isEmpty() ? "" : ", " + j.site() + "에서", last = j.last().isEmpty() ? "" : ", 마지막으로 " + j.last();
        return switch (j.state()) {
            case RUNNING -> switch (j.kind) {
                case WORK -> "작업창에서 하고 있어" + (j.last().isEmpty() ? "" : ", 지금 " + j.last()) + ". " + minutes(now - j.started) + ".";
                case RESEARCH -> "연구실에서 연구 중이야" + (j.last().isEmpty() ? "" : ", 지금 " + j.last()) + ". " + minutes(now - j.started) + ".";
                default -> j.label + "이야. " + step + site + last + ".";
            };
            case WAITING -> topic(j) + " " + ago(now - j.ended()) + " 멈췄어, 너의 확인이 필요해" + (j.result().isEmpty() ? "." : ": " + clip(j.result(), 70));
            case DONE -> topic(j) + " " + ago(now - j.ended()) + " 끝났어" + (j.result().isEmpty() ? "." : ": " + clip(j.result(), 70));
            case FAILED -> topic(j) + " " + ago(now - j.ended()) + " 막혔어" + (j.result().isEmpty() ? "." : ": " + clip(j.result(), 70));
            case STOPPED -> topic(j) + " " + ago(now - j.ended()) + " 멈췄어.";
        };
    }
    /** The jobs for the brain, so it answers from them (BuddyTalk adds this to the voice talk's input and 퍼레와 대화's); "" for none. */
    public static String report(long now) {
        if (!any(now)) return "";
        StringBuilder b = new StringBuilder("맡아서 하는 일(진행을 물으면 이 기록의 단계·사이트·마지막 동작으로 답해. 모른다고 하지 마):\n");
        for (Job j : all()) {
            boolean live = j.running();
            if (!live && now - j.ended() >= ASKED_MS) continue;
            b.append("- ").append(line(j, now)).append(" · 요청 ‘").append(clip(j.request, 60)).append("’");
            if (!j.site().isEmpty()) b.append(" · ").append(j.site());
            if (!j.last().isEmpty()) b.append(" · 마지막: ").append(j.last());
            if (!j.result().isEmpty()) b.append(" · 결과: ").append(clip(j.result(), 120));
            b.append(" · ").append(live ? minutes(now - j.started) : ago(now - j.ended()) + " 끝남").append('\n');
        }
        return b.toString();
    }

    // ── what the user said about them ──
    private static final Pattern ASKS = Pattern.compile("어떻게(?:돼|되|되어|됐)(?:가|가고|가는|가요|갔)|잘(?:돼|되)(?:가|고있)|진행(?:상황|중|돼|되|은|이|어때)|얼마나(?:남|됐|걸|했)|다(?:됐|했)(?:어|니|나|냐|지)"
        + "|끝났(?:어|니|나|냐)|아직(?:이야|멀었|안끝|이니)|몇단계|어디까지(?:했|됐|왔|갔)|(?:하던|맡긴|시킨)(?:거|일|것)(?:은|는)?(?:어때|어떻게|됐|다)");
    private static final Pattern STOPS = Pattern.compile("(?:작업|하던거|하던일|맡긴거|맡긴일|시킨거|시킨일|그거|그일|담는거|올리는거|받는거|연구|브라우저작업|폰작업|그작업)(?:좀|은|는|을|를)?"
        + "(?:그만|멈춰|멈춰줘|멈추|중지|중단|취소|스톱|끝내|꺼)");
    /** Whether said asks how a job goes ("어떻게 돼 가?", "진행 상황 알려 줘", "다 했어?"). */
    public static boolean asksStatus(String said) { String b = bare(said); return !b.isEmpty() && b.length() <= 30 && ASKS.matcher(b).find(); }
    /** Whether said asks to stop a job ("하던 거 멈춰", "작업 중지"); a bare "그만" is not. */
    public static boolean asksStop(String said) { String b = bare(said); return !b.isEmpty() && b.length() <= 24 && STOPS.matcher(b).find(); }
    private static String bare(String s) { return s == null ? "" : s.replaceAll("[\\s\\p{Punct}·…~]+", ""); }

    // ── the words for what it does ──
    /** What a request does, as words: {matches (on the words without spaces), "~하는 중", "~해 볼게!"}. */
    private static final String[][] VERBS = {
        {"담아|담기|담을|다시담|장바구니", "담는 중", "담아 볼게!"},
        {"올려|올리|업로드|게시|포스팅", "올리는 중", "올려 볼게!"},
        {"받아|받기|다운로드|다운받|내려받", "받는 중", "받아 볼게!"},
        {"신청|양식|채워|채우|입력해", "채우는 중", "채워 볼게!"},
        {"읽어|읽기|읽을|확인해|봐줘|알려줘", "읽는 중", "읽어 볼게!"},
        {"찾아|찾기|검색", "찾는 중", "찾아볼게!"},
        {"보내|전송", "보내는 중", "보내 볼게!"},
        {"정리|요약", "정리하는 중", "정리해 볼게!"},
        {"분석", "분석하는 중", "분석해 볼게!"},
        {"코딩|코드|프로그램|짜줘|만들어", "만드는 중", "만들어 볼게!"},
        {"써줘|써서|쓰기|작성|보고서|글을|글써", "쓰는 중", "써 볼게!"},
        {"연구|조사|검증|실험", "조사하는 중", "조사해 볼게!"},
        {"예약", "예약하는 중", "예약해 볼게!"}};
    /** "담는 중", "읽는 중" … for a request; "하는 중" when none fits. */
    public static String doing(String request) { String[] v = verb(request); return v == null ? "하는 중" : v[1]; }
    /** "담아 볼게!", "읽어 볼게!" … for a request; "해 볼게!" when none fits. */
    public static String trying(String request) { String[] v = verb(request); return v == null ? "해 볼게!" : v[2]; }
    private static String[] verb(String request) {
        String b = bare(request);
        if (b.isEmpty()) return null;
        int best = Integer.MAX_VALUE; String[] found = null;
        // The verb said first wins ("공지 읽고 정리해 줘" reads first).
        for (String[] v : VERBS) { java.util.regex.Matcher m = Pattern.compile(v[0]).matcher(b); if (m.find() && m.start() < best) { best = m.start(); found = v; } }
        return found;
    }
    private static String whoOf(String kind) {
        return switch (kind == null ? "" : kind) { case WEB -> "브라우저"; case WORK -> "작업창"; case RESEARCH -> "연구실"; default -> "폰 작업"; };
    }
    private static String labelOf(String kind, String who, String request) {
        return switch (kind == null ? "" : kind) {
            case WORK -> "작업창에서 " + doing(request);
            case RESEARCH -> "연구실에서 연구 중";
            default -> who + " " + doing(request);
        };
    }
    /** A site as said ("studio.youtube.com/…", "naver.com"): its host, without www. */
    public static String host(String url) {
        if (url == null || url.isBlank()) return "";
        // A site by its name ("유튜브 스튜디오", "학교 홈페이지 주소"), not an address: the name.
        if (!address(url)) return clip(url.strip().replaceAll("\\s*(?:의)?\\s*(?:주소|사이트 주소|링크)$", ""), 30);
        String h = BrowserJournal.host(url.contains("://") ? url.strip() : "https://" + url.strip());
        return h.isEmpty() ? clip(url, 30) : h;
    }
    /** Whether it is a web address ("studio.youtube.com", "https://…"), not a site's name. */
    public static boolean address(String url) { return url != null && url.strip().matches("(?i)(?:https?://)?[a-z0-9.-]+\\.[a-z]{2,}(?:[/:?#].*)?"); }
    static String minutes(long ms) { long m = Math.max(0, ms) / 60_000; return m < 1 ? "방금 시작했어" : m + "분째야"; }
    static String ago(long ms) { long s = Math.max(0, ms) / 1000; return s < 60 ? "방금" : s < 3600 ? s / 60 + "분 전에" : s / 3600 + "시간 전에"; }
    static String clip(String s, int most) { String v = s == null ? "" : s.replaceAll("\\s+", " ").strip(); return v.length() <= most ? v : v.substring(0, most - 1) + "…"; }
}
