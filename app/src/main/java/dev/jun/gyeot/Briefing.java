package dev.jun.gyeot;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.job.JobInfo;
import android.app.job.JobScheduler;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import dev.jun.gyeot.core.Proactive;
import dev.jun.gyeot.core.Proactive.Card;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * 먼저 챙기기 on the phone (core/Proactive): 나에 대한 이해 and 오늘의 제안, while the switch is on. The note is made again about
 * once a day when the app comes to the front; the morning cards are made by a job at the chosen time (BriefingJob, network
 * needed) with a notification, or when the app is opened after that time if the job has not run. Both are light work, Gemma
 * 4 first (GeminiAudioClient.light), on the Gemini key. Turning it off stops the job and the note no longer goes anywhere.
 */
final class Briefing {
    private Briefing() {}
    static final int JOB = 4107, NOTIFICATION = 4108;
    private static final String CHANNEL = "suggestions";
    private static volatile boolean running;

    static boolean on(AppStore store) { return store.settings.getBoolean("proactive", false); }
    /** Turned on: the note and today's cards are made soon (when due), and the job is set. Off: the job goes. */
    static void turn(Context context, AppStore store, boolean on) {
        store.settings.edit().putBoolean("proactive", on).apply();
        if (on) { schedule(context, store); catchUp(context, store); }
        else { JobScheduler jobs = context.getSystemService(JobScheduler.class); if (jobs != null) jobs.cancel(JOB); }
        store.changed();
    }
    /** 나에 대한 이해 as it goes to the AIs: "" while 먼저 챙기기 is off. */
    static String profile(AppStore store) { return on(store) ? store.settings.getString("aboutAi", "").strip() : ""; }
    /** The note as kept (shown on its page even while off), and when it was made. */
    static String note(AppStore store) { return store.settings.getString("aboutAi", ""); }
    static long noteAt(AppStore store) { return store.settings.getLong("aboutAiAt", 0); }
    /** The user changed the note: kept as written, and the next one keeps their changes. */
    static void edit(AppStore store, String text) {
        String value = text == null ? "" : text.strip();
        if (value.length() > Proactive.PROFILE_LIMIT) value = value.substring(0, Proactive.PROFILE_LIMIT);
        store.settings.edit().putString("aboutAi", value).putBoolean("aboutAiEdited", true).apply(); store.changed();
    }
    /** When the morning cards come, in minutes after midnight. */
    static int minutes(AppStore store) { return store.settings.getInt("briefMinutes", Proactive.MORNING); }
    static void minutes(Context context, AppStore store, int value) { store.settings.edit().putInt("briefMinutes", value).apply(); if (on(store)) schedule(context, store); store.changed(); }
    static boolean busy() { return running; }

    /** The app came to the front: the job is set, and what is due (the note, today's cards) is made in the background. */
    static void catchUp(Context context, AppStore store) {
        if (!on(store) || running) return;
        long now = System.currentTimeMillis();
        boolean noteDue = now - noteAt(store) >= Proactive.PROFILE_EVERY;
        boolean cardsDue = Proactive.morningDue(now, ZoneId.systemDefault(), minutes(store), store.settings.getString("briefDay", ""));
        schedule(context, store);
        if (!noteDue && !cardsDue) return;
        Context app = context.getApplicationContext();
        running = true;
        new Thread(() -> {
            try { if (noteDue) remake(app, store); } catch (Exception quiet) { /* Tried again next time. */ }
            try { if (cardsDue) morning(app, store, false); } catch (Exception quiet) { /* Tried again next time. */ }
            finally { running = false; store.changed(); }
        }, "briefing").start();
    }
    /** Runs what is due now (the job): the note when old, and the cards with a notification. Blocking. */
    static void due(Context context, AppStore store) {
        if (!on(store)) return;
        running = true;
        try {
            if (System.currentTimeMillis() - noteAt(store) >= Proactive.PROFILE_EVERY) try { remake(context, store); } catch (Exception quiet) {}
            if (Proactive.morningDue(System.currentTimeMillis(), ZoneId.systemDefault(), minutes(store), store.settings.getString("briefDay", "")))
                try { morning(context, store, true); } catch (Exception quiet) {}
        } finally { running = false; store.changed(); schedule(context, store); }
    }

    /** Makes 나에 대한 이해 again now. Blocking; throws why it could not. */
    static String remake(Context context, AppStore store) throws Exception {
        String key = store.secrets.workKey();
        if (key.isBlank()) throw new IllegalStateException("설정 → Gemini API 키를 먼저 넣어 주세요.");
        String input = Proactive.profileInput(note(store), store.settings.getBoolean("aboutAiEdited", false), store.persona(), memories(store), chat(store, 80), works(store, 12, false), records(store));
        String made = Proactive.profile(GeminiAudioClient.light(key, Proactive.PROFILE, input));
        if (made.isEmpty()) throw new IllegalStateException("노트를 만들지 못했어요.");
        store.settings.edit().putString("aboutAi", made).putLong("aboutAiAt", System.currentTimeMillis()).apply(); store.changed();
        return made;
    }
    /** Makes today's cards now (tell: with a notification). Blocking; throws why it could not. */
    static List<Card> morning(Context context, AppStore store, boolean tell) throws Exception {
        String key = store.secrets.workKey();
        if (key.isBlank()) throw new IllegalStateException("설정 → Gemini API 키를 먼저 넣어 주세요.");
        long now = System.currentTimeMillis(); ZoneId zone = ZoneId.systemDefault();
        String schedule = "";
        try { schedule = MainChatCalendar.context(context, store, "오늘 일정 알려줘", now); } catch (RuntimeException unreadable) { /* No calendar. */ }
        String input = Proactive.briefInput(now, zone, note(store), schedule, chat(store, 60), works(store, 10, true), records(store), feedback(store));
        List<Card> cards = Proactive.cards(GeminiAudioClient.light(key, Proactive.BRIEF, input));
        if (cards.isEmpty()) throw new IllegalStateException("오늘은 드릴 제안을 만들지 못했어요.");
        JSONArray saved = new JSONArray(); for (Card card : cards) saved.put(card.json());
        store.settings.edit().putString("briefCards", saved.toString()).putString("briefDay", Proactive.day(now, zone)).apply(); store.changed();
        if (tell) tell(context, cards);
        return cards;
    }
    /** Today's cards still open (none from another day). */
    static List<Card> cards(AppStore store) {
        List<Card> out = new ArrayList<>();
        if (!on(store) || !Proactive.day(System.currentTimeMillis(), ZoneId.systemDefault()).equals(store.settings.getString("briefDay", ""))) return out;
        try {
            JSONArray saved = new JSONArray(store.settings.getString("briefCards", "[]"));
            for (int i = 0; i < saved.length(); i++) { Card card = Card.of(saved.optJSONObject(i)); if (card != null && !card.title().isEmpty()) out.add(card); }
        } catch (Exception broken) { /* None. */ }
        return out;
    }
    /** The user took a card (used) or put it away: it leaves today's list, and the next mornings learn from it. */
    static void react(AppStore store, Card card, boolean used) {
        JSONArray left = new JSONArray();
        for (Card each : cards(store)) if (!each.title().equals(card.title())) left.put(each.json());
        store.settings.edit().putString("briefCards", left.toString()).putString("briefFeedback", Proactive.react(feedback(store), card, used, System.currentTimeMillis()).toString()).apply();
        store.changed();
    }

    /** The job, at the chosen time next (today when still ahead and not made, else tomorrow); it waits for a network. */
    static void schedule(Context context, AppStore store) {
        JobScheduler jobs = context.getSystemService(JobScheduler.class); if (jobs == null) return;
        long now = System.currentTimeMillis();
        boolean made = Proactive.day(now, ZoneId.systemDefault()).equals(store.settings.getString("briefDay", ""));
        long at = Proactive.nextMorning(now, ZoneId.systemDefault(), minutes(store), made), wait = Math.max(0, at - now);
        // The same time already set: left alone, so opening the app does not push it back.
        long planned = store.settings.getLong("briefJobAt", 0);
        if (planned == at && jobs.getPendingJob(JOB) != null) return;
        JobInfo job = new JobInfo.Builder(JOB, new ComponentName(context, BriefingJob.class)).setMinimumLatency(wait).setOverrideDeadline(wait + 3 * 3_600_000L)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_ANY).setPersisted(true).build();
        try { if (jobs.schedule(job) == JobScheduler.RESULT_SUCCESS) store.settings.edit().putLong("briefJobAt", at).apply(); } catch (RuntimeException refused) { /* Opening the app makes them. */ }
    }

    private static void tell(Context context, List<Card> cards) {
        NotificationManager notifications = context.getSystemService(NotificationManager.class); if (notifications == null) return;
        if (notifications.getNotificationChannel(CHANNEL) == null) notifications.createNotificationChannel(new NotificationChannel(CHANNEL, "오늘의 제안", NotificationManager.IMPORTANCE_DEFAULT));
        StringBuilder lines = new StringBuilder(); for (Card card : cards) lines.append("• ").append(card.title()).append('\n');
        Intent open = new Intent(context, MainActivity.class).putExtra(MainNavigation.TARGET, "chat").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        PendingIntent tap = PendingIntent.getActivity(context, NOTIFICATION, open, PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification notification = new Notification.Builder(context, CHANNEL).setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("오늘의 제안 " + cards.size() + "개").setContentText(cards.get(0).title())
            .setStyle(new Notification.BigTextStyle().bigText(lines.toString().strip())).setContentIntent(tap).setAutoCancel(true).build();
        try { notifications.notify(NOTIFICATION, notification); } catch (SecurityException noPermission) { /* Notifications are off: the cards wait in 메인 대화. */ }
    }

    // ── the material ─────────────────────────────────────────────────────
    private static JSONArray feedback(AppStore store) { try { return new JSONArray(store.settings.getString("briefFeedback", "[]")); } catch (Exception e) { return new JSONArray(); } }
    private static List<String> memories(AppStore store) {
        List<String> out = new ArrayList<>(); JSONArray list = store.memories();
        for (int i = list.length() - 1; i >= 0 && out.size() < 80; i--) {
            JSONObject note = list.optJSONObject(i);
            if (note != null && !dev.jun.gyeot.core.Memory.PHONE.equals(note.optString("kind"))) out.add(note.optString("text"));
        }
        return out;
    }
    /** The main chat's newest messages, oldest first, each cut. */
    private static String chat(AppStore store, int most) {
        List<String> lines = new ArrayList<>();
        try {
            long before = 0;
            while (lines.size() < most) {
                JSONArray rows = store.mainChat.page(before, 40); if (rows.length() == 0) break;
                List<String> page = new ArrayList<>();
                for (int i = 0; i < rows.length(); i++) {
                    JSONObject row = rows.optJSONObject(i); if (row == null) continue;
                    String role = row.optString("role"), text = row.optString("text").strip().replaceAll("\\s+", " ");
                    if (text.isEmpty() || !("user".equals(role) || "assistant".equals(role))) continue;
                    page.add(("user".equals(role) ? "나: " : "답: ") + (text.length() > 400 ? text.substring(0, 400) + "…" : text));
                }
                lines.addAll(0, page);
                long first = rows.optJSONObject(0) == null ? 0 : rows.optJSONObject(0).optLong("id");
                if (first <= 1 || first == before) break; before = first;
            }
        } catch (RuntimeException closed) { /* The main chat is not open. */ }
        if (lines.size() > most) lines = lines.subList(lines.size() - most, lines.size());
        return String.join("\n", lines);
    }
    /** The newest work rooms: title and the last request (with: their id first, for a card that continues one). */
    private static List<String> works(AppStore store, int most, boolean withId) {
        List<JSONObject> threads = new ArrayList<>(); JSONArray all = store.works();
        for (int i = 0; i < all.length(); i++) if (all.optJSONObject(i) != null) threads.add(all.optJSONObject(i));
        threads.sort((a, b) -> Long.compare(Math.max(b.optLong("updatedAt"), b.optLong("createdAt")), Math.max(a.optLong("updatedAt"), a.optLong("createdAt"))));
        List<String> out = new ArrayList<>();
        for (JSONObject thread : threads) {
            if (out.size() >= most) break;
            String last = "";
            JSONArray messages = thread.optJSONArray("messages");
            for (int k = messages == null ? -1 : messages.length() - 1; k >= 0; k--) {
                JSONObject message = messages.optJSONObject(k);
                if (message != null && "user".equals(message.optString("role")) && !message.optString("text").isBlank()) { last = message.optString("text").strip(); break; }
            }
            if (last.length() > 160) last = last.substring(0, 160) + "…";
            out.add((withId ? thread.optString("id") + " · " : "") + thread.optString("title") + (last.isEmpty() ? "" : " · " + last));
        }
        return out;
    }
    /** The newest voice records (their titles and summaries) and recordings. */
    private static List<String> records(AppStore store) {
        List<String> out = new ArrayList<>(); JSONArray sessions = store.allSessions();
        for (int i = sessions.length() - 1; i >= 0 && out.size() < 8; i--) {
            JSONObject session = sessions.optJSONObject(i); if (session == null || session.optString("title").isBlank()) continue;
            out.add("음성: " + session.optString("title") + (session.optString("summary").isBlank() ? "" : " — " + session.optString("summary")));
        }
        JSONArray recordings = store.recordings();
        for (int i = recordings.length() - 1, n = 0; i >= 0 && n < 5; i--) {
            JSONObject recording = recordings.optJSONObject(i); if (recording == null) continue;
            String summary = recording.optString("summary").strip().replaceAll("\\s+", " ");
            out.add("녹음: " + recording.optString("title") + (summary.isEmpty() ? "" : " — " + (summary.length() > 200 ? summary.substring(0, 200) + "…" : summary))); n++;
        }
        return out;
    }
}
