package dev.jun.gyeot;

import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import dev.jun.gyeot.core.GeminiQuota;
import dev.jun.gyeot.core.SpareQuota;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.BooleanSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 남는 한도로 한 일 (core/SpareQuota): in the last hours before the free day's counts start again (midnight Pacific), while the
 * phone charges on an unmetered network with its screen off, what is left of Groq's free day does work the app would do
 * anyway, so it is ready before it is wanted: a title for each recent voice talk that has none, the 기억 정리 plan (only made
 * and kept: 설정 → 기억 → 기억 정리 shows it at once, and nothing changes until the user presses 적용), and 나에 대한 이해 and
 * 나에 대한 질문 when 먼저 챙기기 would make them soon anyway. Groq alone (Chores.spare): never Gemini's free tier, never a
 * paid key, never a message sent, a purchase or a deletion. Each job is skipped once done, the run stops when its job is
 * stopped, and all of it stays within what SpareQuota.budget leaves (DAY_BUDGET tokens a day at most, FLOOR of each model's
 * day left for the user). A JobScheduler job (SpareJob) waits for the window and the conditions.
 */
final class SpareWork {
    private SpareWork() {}
    static final int JOB = 4301;
    static final String LOG = "spareLog", TIDY = "spareTidy", PLANNED = "spareJobAt", RAN = "spareRanDay";
    static final String TITLES = "titles", MEMORY = "memory", PROFILE = "profile", QUESTIONS = "questions";
    /** Voice talks titled in one run at most, and how far back one is looked for. */
    static final int TITLES_AT_ONCE = 5;
    static final long TITLES_BACK = 30L * 24 * 3_600_000L;
    /** A big 잡일 (Chores.forGroq's 8,500 characters and its answer) in tokens, for the budget before it is sent. */
    static final int BIG = 7_500;
    /** 나에 대한 이해 is made again here when it would be due within this long (Proactive.PROFILE_EVERY). */
    static final long PROFILE_SOON = 8 * 3_600_000L;
    /** A 기억 정리 plan made here is shown for the same notes this long. */
    static final long TIDY_FRESH = 7L * 24 * 3_600_000L;

    /** What spare work did (core/SpareQuota.Done, two weeks). */
    static JSONArray log(AppStore store) { try { return new JSONArray(store.settings.getString(LOG, "[]")); } catch (Exception broken) { return new JSONArray(); } }
    private static synchronized void done(AppStore store, String job, String words, long tokens, String model) {
        long now = System.currentTimeMillis();
        store.settings.edit().putString(LOG, SpareQuota.add(log(store), new SpareQuota.Done(now, job, words, tokens, model), now).toString()).apply();
        store.changed();
    }

    /**
     * The job, set for the window (now while it is open, unless this day's run already got through: then the next one),
     * waiting for the charger, an unmetered network and the phone left alone (its screen off). Cancelled without Groq.
     */
    static void schedule(Context c, AppStore store) { schedule(c, store, false, 0); }
    /** A run stopped, or the phone not as it needs: tried again in the window no sooner than this. */
    static final long RETRY = 20 * 60_000L;
    /** finished: this window's run got through, so the next window; else not before later (ms from now). */
    static void schedule(Context c, AppStore store, boolean finished, long later) {
        JobScheduler jobs = c.getSystemService(JobScheduler.class); if (jobs == null) return;
        try {
            if (!store.groq.connected()) { if (jobs.getPendingJob(JOB) != null) jobs.cancel(JOB); return; }
            long now = System.currentTimeMillis();
            if (finished) store.settings.edit().putString(RAN, GeminiQuota.day(now)).apply();
            boolean ran = GeminiQuota.day(now).equals(store.settings.getString(RAN, ""));
            long at = ran ? SpareQuota.windowStart(GeminiQuota.resetAt(now)) : Math.max(SpareQuota.windowStart(now), now + Math.max(0, later)), wait = Math.max(0, at - now);
            if (store.settings.getLong(PLANNED, 0) == at && jobs.getPendingJob(JOB) != null) return;
            JobInfo job = new JobInfo.Builder(JOB, new ComponentName(c, SpareJob.class)).setMinimumLatency(wait).setRequiresCharging(true)
                .setRequiresDeviceIdle(true).setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED).setPersisted(true).build();
            if (jobs.schedule(job) == JobScheduler.RESULT_SUCCESS) store.settings.edit().putLong(PLANNED, at).apply();
        } catch (RuntimeException refused) { /* The next app start sets it. */ }
    }

    /** As spare work needs the phone: charging, on an unmetered network (Wi-Fi), its screen off. */
    static boolean conditions(Context c) {
        try {
            android.os.BatteryManager battery = c.getSystemService(android.os.BatteryManager.class);
            android.net.ConnectivityManager net = c.getSystemService(android.net.ConnectivityManager.class);
            android.os.PowerManager power = c.getSystemService(android.os.PowerManager.class);
            android.net.NetworkCapabilities caps = net == null || net.getActiveNetwork() == null ? null : net.getNetworkCapabilities(net.getActiveNetwork());
            return battery != null && battery.isCharging() && caps != null && caps.hasCapability(android.net.NetworkCapabilities.NET_CAPABILITY_NOT_METERED)
                && power != null && !power.isInteractive() && !power.isPowerSaveMode();
        } catch (RuntimeException unknown) { return false; }
    }

    /** One piece of spare work: its words for the log when it did something, null when there was nothing to do. */
    interface Job { String run() throws Exception; }
    /** A job's key and the tokens it is guessed at before anything is sent (the budget must hold them). */
    record Step(String key, int tokens) {}
    static final List<Step> STEPS = List.of(new Step(TITLES, 1_500), new Step(MEMORY, BIG), new Step(PROFILE, BIG), new Step(QUESTIONS, 3_000));

    /** How a run ended: it got through this window (THROUGH), it is to be tried again in it (AGAIN), or it was not in one (OUTSIDE). */
    enum Outcome { THROUGH, AGAIN, OUTSIDE }
    /**
     * What is due, in order, while the window, the conditions and the budget last; stopped is the job's stop. AGAIN when it was
     * stopped or the phone was not as it needs; a job that fails is left for the next window. Blocking: never on the main thread.
     */
    static Outcome run(Context c, AppStore store, BooleanSupplier stopped) {
        if (!SpareQuota.window(System.currentTimeMillis()) || !store.groq.connected()) return Outcome.OUTSIDE;
        for (Step step : STEPS) {
            long now = System.currentTimeMillis();
            if (!SpareQuota.window(now)) return Outcome.OUTSIDE;
            if (stopped.getAsBoolean() || !conditions(c) || store.voiceActive) return Outcome.AGAIN;
            if (budget(store, now) < step.tokens()) break;
            long before = groqTokens(store, now);
            GroqWork.SERVED.remove();
            String words;
            try { words = job(c, store, step.key(), stopped).run(); } catch (Exception failed) { words = null; }
            if (words != null) done(store, step.key(), words, Math.max(0, groqTokens(store, System.currentTimeMillis()) - before), GroqWork.SERVED.get());
        }
        return stopped.getAsBoolean() ? Outcome.AGAIN : Outcome.THROUGH;
    }
    /** What spare work may still spend now (SpareQuota.budget over the key's Groq chat models, less what it spent this day). */
    static long budget(AppStore store, long now) {
        return SpareQuota.budget(store.geminiQuota(), SpareQuotas.groq(store, GroqWork.CHORES), now, SpareQuota.spent(log(store), now));
    }
    /** The tokens the key's Groq chat models counted today, together (what a job took, as Groq told it). */
    private static long groqTokens(AppStore store, long now) {
        JSONObject state = store.geminiQuota(); long sum = 0;
        for (String model : SpareQuotas.groq(store, GroqWork.CHORES)) sum += GeminiQuota.tokens(state, model, now);
        return sum;
    }
    private static Job job(Context c, AppStore store, String key, BooleanSupplier stopped) {
        return switch (key) {
            case TITLES -> () -> titles(store, stopped);
            case MEMORY -> () -> tidy(store);
            case PROFILE -> () -> {
                if (!Briefing.on(store) || System.currentTimeMillis() - Briefing.noteAt(store) < dev.jun.gyeot.core.Proactive.PROFILE_EVERY - PROFILE_SOON) return null;
                return Chores.spare(() -> Briefing.remake(c, store, false)).isBlank() ? null : "나에 대한 이해 정리";
            };
            default -> () -> {
                if (!Briefing.questionsDue(store)) return null;
                List<String> made = Chores.spare(() -> Briefing.makeQuestions(store));
                return made.isEmpty() ? null : "질문 " + made.size() + "개";
            };
        };
    }

    // ── titles for voice talks that have none (AssistantService.nameSession, when it could not) ──
    private static String titles(AppStore store, BooleanSupplier stopped) throws Exception {
        long now = System.currentTimeMillis(); int named = 0;
        JSONArray all = store.allSessions();
        for (int i = all.length() - 1; i >= 0 && named < TITLES_AT_ONCE && !stopped.getAsBoolean(); i--) {
            JSONObject session = all.optJSONObject(i);
            if (session == null || !session.optString("title").isBlank() || session.optLong("endedAt") <= 0 || now - session.optLong("endedAt") > TITLES_BACK) continue;
            String transcript = AppStore.sessionText(session); if (transcript.isBlank()) continue;
            if (transcript.length() > 8_000) transcript = transcript.substring(transcript.length() - 8_000);
            if (budget(store, System.currentTimeMillis()) < transcript.length() * 2 / 3 + 1_500) break;
            String input = transcript;
            dev.jun.gyeot.core.SessionTitles.Title title = dev.jun.gyeot.core.SessionTitles.parse(Chores.spare(() -> Chores.run(store, dev.jun.gyeot.core.SessionTitles.PROMPT, input, false)));
            if (title == null) continue;
            store.nameSession(session.optString("id"), title.title(), title.summary()); named++;
        }
        return named == 0 ? null : "대화 제목 " + named + "개";
    }

    // ── 기억 정리's plan, made ahead and shown at once (MainActivity.tidyMemory) ──
    /** The plan made for these notes (as Memory.prompt lists them) within TIDY_FRESH, or null. */
    static String tidy(AppStore store, String listed) {
        try {
            JSONObject kept = new JSONObject(store.settings.getString(TIDY, "{}"));
            return listed != null && hash(listed).equals(kept.optString("hash")) && System.currentTimeMillis() - kept.optLong("at") < TIDY_FRESH && !kept.optString("answer").isBlank() ? kept.optString("answer") : null;
        } catch (Exception broken) { return null; }
    }
    private static String tidy(AppStore store) throws Exception {
        String listed = dev.jun.gyeot.core.Memory.prompt(store.memories(), false);
        if (listed.isEmpty() || tidy(store, listed) != null) return null;
        String answer = Chores.spare(() -> Chores.run(store, dev.jun.gyeot.core.Memory.cleanup(listed), "기억을 정리해 주세요.", false));
        int changes = dev.jun.gyeot.core.Memory.preview(store.memories(), dev.jun.gyeot.core.Memory.withoutAdd(answer)).size();
        store.settings.edit().putString(TIDY, new JSONObject().put("hash", hash(listed)).put("answer", answer).put("at", System.currentTimeMillis()).put("changes", changes).toString()).apply();
        return changes == 0 ? "기억 점검 이상 없음" : "기억 정리할 것 " + changes + "가지";
    }
    private static String hash(String text) {
        try {
            byte[] digest = java.security.MessageDigest.getInstance("SHA-256").digest(text.getBytes(StandardCharsets.UTF_8));
            StringBuilder out = new StringBuilder(); for (int i = 0; i < 12; i++) out.append(String.format("%02x", digest[i]));
            return out.toString();
        } catch (java.security.NoSuchAlgorithmException unavailable) { return Integer.toHexString(text.hashCode()); }
    }
}
