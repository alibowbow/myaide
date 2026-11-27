package dev.jun.gyeot;

import android.app.Notification;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import dev.jun.gyeot.core.Deliveries;
import dev.jun.gyeot.core.NoticeActions;
import dev.jun.gyeot.core.Notices;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * 알림 비서: the notifications other apps post, kept on the phone (core/Notices) while the user has it on and has allowed
 * 알림 접근 for this app. Not this app's own, not ongoing ones (music, navigation, downloads), not group summaries, never codes.
 */
public final class NoticeListener extends NotificationListenerService {
    private static final Object LOCK = new Object();
    private static List<Notices.Notice> kept;
    static boolean on(Context context) { return ((GyeotApp) context.getApplicationContext()).store().settings.getBoolean("noticeOn", false); }
    /** 알림 접근 is allowed for this app. */
    static boolean allowed(Context context) {
        String enabled = android.provider.Settings.Secure.getString(context.getContentResolver(), "enabled_notification_listeners");
        return enabled != null && enabled.contains(context.getPackageName());
    }
    private static File file(Context context) { return new File(context.getFilesDir(), "notices.json"); }
    /** The kept notices, oldest first (a day at most). */
    static List<Notices.Notice> list(Context context) {
        synchronized (LOCK) {
            if (kept == null) {
                String json = "[]";
                try { File f = file(context); if (f.isFile()) json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); } catch (Exception ignored) {}
                kept = Notices.load(json, System.currentTimeMillis());
            }
            return new java.util.ArrayList<>(kept);
        }
    }
    /** Forgets every kept notice (turned off, or 모두 지우기). */
    static void clear(Context context) {
        synchronized (LOCK) { kept = new java.util.ArrayList<>(); file(context).delete(); }
        // The parcels, bookings and plans read from them go too (알림을 행동으로).
        forget(); context.getSharedPreferences(ACTS_PREFS, Context.MODE_PRIVATE).edit().clear().apply();
    }

    // ── the home screen's badges (HomeActivity) ─────────────────────
    private static volatile NoticeListener live;
    /** Told when the phone's notifications change, while the home screen is shown. */
    static volatile Runnable changed;
    @Override public void onListenerConnected() { super.onListenerConnected(); live = this; tell(); }
    @Override public void onListenerDisconnected() { if (live == this) live = null; super.onListenerDisconnected(); }
    @Override public void onNotificationRemoved(StatusBarNotification removed) { tell(); }
    private static void tell() { Runnable r = changed; if (r != null) try { r.run(); } catch (RuntimeException ignored) {} }
    /**
     * The notifications showing now, counted per app (only counts: no words are read): what a home screen puts on its icons.
     * Empty when 알림 접근 is off. Ongoing ones (music, downloads) and group summaries are not counted.
     */
    static java.util.Map<String, Integer> badges() {
        java.util.Map<String, Integer> out = new java.util.HashMap<>();
        NoticeListener l = live; if (l == null) return out;
        try {
            StatusBarNotification[] all = l.getActiveNotifications();
            for (StatusBarNotification n : all == null ? new StatusBarNotification[0] : all) {
                Notification note = n.getNotification();
                if (note == null || n.isOngoing() || (note.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (note.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) continue;
                out.merge(n.getPackageName(), Math.max(1, Math.min(99, note.number)), Integer::sum);
            }
        } catch (RuntimeException unavailable) { out.clear(); }
        return out;
    }

    // ── food on its way (core/Deliveries; 준: "배민에서 배달한걸 못알아채네") ──
    // 배민's tracker is an ongoing live notification (the status bar chip "조리완료"), left out of the notices below: read here
    // whatever kind it is, the newest kept so 퍼레 still knows after it is gone (배달 완료).
    private static volatile Deliveries.Delivery lastDelivery;
    /** Told when the food's stage changes, while the home screen is shown (it draws its board again at once). */
    static volatile Runnable deliveryChanged;
    private static final String DELIVERY_PREFS = "gyeot_delivery";
    /** A delivery app's notification as words: every short text in it (Samsung's live notifications keep theirs under their own keys), and its chip. */
    static String[] deliveryWords(StatusBarNotification posted) {
        Notification n = posted.getNotification(); if (n == null) return null;
        Bundle extras = n.extras; StringBuilder all = new StringBuilder(); String chip = "";
        java.util.LinkedHashSet<String> seen = new java.util.LinkedHashSet<>();
        if (extras != null) for (String key : extras.keySet()) {
            Object v; try { v = extras.get(key); } catch (RuntimeException unreadable) { continue; }
            java.util.List<CharSequence> values = new java.util.ArrayList<>();
            if (v instanceof CharSequence cs) values.add(cs); else if (v instanceof CharSequence[] many) values.addAll(java.util.Arrays.asList(many));
            for (CharSequence cs : values) {
                if (cs == null) continue; String t = cs.toString().strip();
                if (t.isEmpty() || t.length() > 300 || t.matches("[\\w.$]+\\.[\\w.$]+") || !seen.add(t)) continue;
                String k = key.toLowerCase(java.util.Locale.ROOT);
                if (chip.isEmpty() && (k.contains("chip") || k.contains("shortcritical"))) chip = t;
                all.append(t).append(' ');
            }
        }
        if (n.tickerText != null && seen.add(n.tickerText.toString().strip())) all.append(n.tickerText).append(' ');
        return new String[]{all.toString().strip(), chip};
    }
    static Deliveries.Delivery deliveryOf(Context c, StatusBarNotification posted) {
        String pkg = posted.getPackageName(), label = pkg;
        try { PackageManager pm = c.getPackageManager(); label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
        if (!Deliveries.app(pkg, label)) return null;
        String[] words = deliveryWords(posted); if (words == null) return null;
        return Deliveries.read(pkg, label, "", words[0], words[1], posted.getPostTime(), java.time.ZoneId.systemDefault());
    }
    /** The food on its way now, or null: the delivery apps' notifications showing now, else the last one read (3 hours at most). */
    static Deliveries.Delivery delivery(Context c) {
        long now = System.currentTimeMillis();
        Deliveries.Delivery best = lastDelivery;
        if (best == null) try {
            String json = c.getSharedPreferences(DELIVERY_PREFS, Context.MODE_PRIVATE).getString("last", "");
            if (!json.isEmpty()) { org.json.JSONObject o = new org.json.JSONObject(json); best = lastDelivery = Deliveries.of(o); }
        } catch (Exception broken) { best = null; }
        NoticeListener l = live;
        if (l != null) try {
            StatusBarNotification[] all = l.getActiveNotifications();
            for (StatusBarNotification n : all == null ? new StatusBarNotification[0] : all) {
                if (n == null || c.getPackageName().equals(n.getPackageName())) continue;
                best = Deliveries.newer(best, deliveryOf(c, n));
            }
        } catch (RuntimeException unavailable) { /* the last one read */ }
        return Deliveries.current(best, now);
    }
    private void readDelivery(StatusBarNotification posted) {
        if (posted == null || getPackageName().equals(posted.getPackageName()) || !on(this)) return;
        String pkg = posted.getPackageName(), label = pkg;
        try { PackageManager pm = getPackageManager(); label = pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception ignored) {}
        if (!Deliveries.app(pkg, label)) return;
        Deliveries.Delivery d = deliveryOf(this, posted);
        AppStore store = ((GyeotApp) getApplicationContext()).store();
        if (d == null) {
            // What the app said that was not read as an order (for 오류 진단 보고서, to teach the reader new words).
            String[] words = deliveryWords(posted); String w = words == null ? "" : words[0];
            store.phoneLog("● 배달 앱 알림(단계 못 읽음): " + label + " · " + (w.length() > 80 ? w.substring(0, 80) + "…" : w));
            return;
        }
        Deliveries.Delivery before = lastDelivery;
        lastDelivery = Deliveries.newer(before, d);
        getSharedPreferences(DELIVERY_PREFS, MODE_PRIVATE).edit().putString("last", lastDelivery.json().toString()).apply();
        if (before == null || before.stage() != d.stage()) {
            // 하루 기록: the order once, and when it came (DayLog.delivery).
            DayLogs.delivery(before, d);
            store.phoneLog("● 배달: " + Deliveries.words(d, d.at(), java.time.ZoneId.systemDefault()));
            Runnable r = deliveryChanged; if (r != null) try { r.run(); } catch (RuntimeException ignored) {}
        }
    }

    // ── 알림을 행동으로 (core/NoticeActions): parcels, bookings, messenger plans, each one tap of action ──
    // Read like the food above, ongoing trackers included; kept (a few of each kind) so 퍼레 still knows once the notice is
    // gone, and what a tap needs from a notice still showing (its own open and reply actions) by the act's key.
    private static final String ACTS_PREFS = "gyeot_notice_acts";
    private static final Object ACTS_LOCK = new Object();
    private static List<NoticeActions.Act> acts;
    /** What a tap can use of a notice: its app, what it opens, its reply action (free-form RemoteInput), its key. */
    record Live(String pkg, android.app.PendingIntent open, Notification.Action reply, String nkey) {}
    private static final java.util.Map<String, Live> LIVE = new java.util.concurrent.ConcurrentHashMap<>();
    /** Reads already made of the notices showing (by key and post time), so the board's refresh does not read them again. */
    private static final java.util.Map<String, java.util.Optional<NoticeActions.Act>> READ = new java.util.concurrent.ConcurrentHashMap<>();
    /** The notices told to 오류 진단 보고서 as not read, once each. */
    private static final java.util.Set<String> TOLD = java.util.concurrent.ConcurrentHashMap.newKeySet();
    private static final java.util.concurrent.ExecutorService ACT_IO = java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gyeot-notice-acts"); t.setDaemon(true); return t; });
    private static String labelOf(Context c, String pkg) {
        try { PackageManager pm = c.getPackageManager(); return pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString(); } catch (Exception gone) { return pkg; }
    }
    /** {title, text} to read: a messenger's sender and its newest message; any other app's every short text (as for food). */
    static String[] actWords(StatusBarNotification posted, boolean chat) {
        Notification n = posted.getNotification(); if (n == null || n.extras == null) return null;
        if (!chat) { String[] w = deliveryWords(posted); return w == null ? null : new String[]{"", w[0]}; }
        Bundle x = n.extras;
        CharSequence title = x.getCharSequence(Notification.EXTRA_TITLE), text = x.getCharSequence(Notification.EXTRA_BIG_TEXT);
        if (text == null) text = x.getCharSequence(Notification.EXTRA_TEXT);
        try {
            android.os.Parcelable[] all = x.getParcelableArray(Notification.EXTRA_MESSAGES);
            java.util.List<Notification.MessagingStyle.Message> messages = all == null ? java.util.List.of() : Notification.MessagingStyle.Message.getMessagesFromBundleArray(all);
            if (!messages.isEmpty()) {
                Notification.MessagingStyle.Message last = messages.get(messages.size() - 1);
                if (last.getText() != null) text = last.getText();
                if (last.getSenderPerson() != null && last.getSenderPerson().getName() != null) title = last.getSenderPerson().getName();
            }
        } catch (RuntimeException unreadable) { /* the plain title and text */ }
        return new String[]{title == null ? "" : title.toString().strip(), text == null ? "" : text.toString().strip()};
    }
    /** A notice's act, or null (read once per key and post time). */
    static NoticeActions.Act actOf(Context c, StatusBarNotification posted) {
        if (posted == null || c.getPackageName().equals(posted.getPackageName())) return null;
        Notification n = posted.getNotification(); if (n == null || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0) return null;
        String id = posted.getKey() + "|" + posted.getPostTime();
        java.util.Optional<NoticeActions.Act> was = READ.get(id); if (was != null) return was.orElse(null);
        String pkg = posted.getPackageName(), label = labelOf(c, pkg);
        String[] words = actWords(posted, NoticeActions.messenger(pkg, label));
        NoticeActions.Act a = words == null ? null : NoticeActions.read(pkg, label, words[0], words[1], posted.getPostTime(), java.time.ZoneId.systemDefault());
        if (a != null) a = a.withNotice(posted.getKey());
        if (READ.size() > 300) READ.clear();
        READ.put(id, java.util.Optional.ofNullable(a));
        if (a != null) { if (LIVE.size() > 200) LIVE.clear(); LIVE.put(a.key(), new Live(pkg, n.contentIntent, NoticeReply.replyAction(n), posted.getKey())); }
        return a;
    }
    private void readAct(StatusBarNotification posted) {
        if (posted == null || getPackageName().equals(posted.getPackageName()) || !on(this)) return;
        NoticeActions.Act a = actOf(this, posted);
        AppStore store = ((GyeotApp) getApplicationContext()).store();
        if (a == null) {
            // What came that was not read (for 오류 진단 보고서, to teach the readers): a courier's or a booking's words with long
            // numbers masked; of a messenger only that a plan was not read and how long it was, never its words.
            String pkg = posted.getPackageName(), label = labelOf(this, pkg);
            boolean chat = NoticeActions.messenger(pkg, label);
            String[] words = actWords(posted, chat); if (words == null) return;
            String kind = NoticeActions.unread(pkg, label, words[0], words[1]);
            if (kind.isEmpty() || !TOLD.add(posted.getKey() + "|" + (words[0] + words[1]).hashCode())) return;
            String w = dev.jun.gyeot.core.NoticeActions.masked(words[1]).replaceAll("\\s+", " ");
            store.phoneLog(kind.equals("약속") ? "● 메신저 약속(시각 못 읽음): " + label + " · " + words[1].length() + "자"
                : "● " + kind + " 알림(" + (kind.equals("택배") ? "단계" : "시각") + " 못 읽음): " + label + " · " + (w.length() > 80 ? w.substring(0, 80) + "…" : w));
            return;
        }
        boolean news;
        String json;
        synchronized (ACTS_LOCK) {
            List<NoticeActions.Act> before = keptActs(this);
            news = before.stream().noneMatch(b -> b.key().equals(a.key()) && b.stage().equals(a.stage()));
            acts = NoticeActions.merge(before, a, System.currentTimeMillis(), java.time.ZoneId.systemDefault());
            json = NoticeActions.save(acts);
        }
        getSharedPreferences(ACTS_PREFS, MODE_PRIVATE).edit().putString("acts", json).apply();
        if (news) {
            // 하루 기록: the parcel, the booking (with its place) or the plan.
            DayLogs.noticeAct(a);
            store.phoneLog("● " + NoticeActions.logLine(a, System.currentTimeMillis(), java.time.ZoneId.systemDefault()));
            Runnable r = deliveryChanged; if (r != null) try { r.run(); } catch (RuntimeException ignored) {}
        }
    }
    /** The acts kept (read once from this app's own preferences). Call off the main thread. */
    private static List<NoticeActions.Act> keptActs(Context c) {
        synchronized (ACTS_LOCK) {
            if (acts == null) acts = NoticeActions.load(c.getSharedPreferences(ACTS_PREFS, Context.MODE_PRIVATE).getString("acts", ""), System.currentTimeMillis(), java.time.ZoneId.systemDefault());
            return new java.util.ArrayList<>(acts);
        }
    }
    /** The acts worth showing now: those kept, with the notices showing now read too (ongoing trackers). Off the main thread. */
    static List<NoticeActions.Act> acts(Context c) {
        long now = System.currentTimeMillis(); java.time.ZoneId zone = java.time.ZoneId.systemDefault();
        List<NoticeActions.Act> all = keptActs(c);
        NoticeListener l = live;
        if (l != null) try {
            StatusBarNotification[] showing = l.getActiveNotifications();
            for (StatusBarNotification n : showing == null ? new StatusBarNotification[0] : showing) { NoticeActions.Act a = actOf(c, n); if (a != null) all = NoticeActions.merge(all, a, now, zone); }
        } catch (RuntimeException unavailable) { /* the kept ones */ }
        List<NoticeActions.Act> out = new java.util.ArrayList<>();
        for (NoticeActions.Act a : all) if (NoticeActions.current(a, now, zone) != null) out.add(a);
        return out;
    }
    /** The act with this key, or null. Off the main thread. */
    static NoticeActions.Act act(Context c, String key) {
        for (NoticeActions.Act a : acts(c)) if (a.key().equals(key)) return a;
        return null;
    }
    /** What a tap can use of the act's notice: kept since it came, else found among the notices showing now (after a restart); null when gone. */
    static Live live(Context c, NoticeActions.Act a) {
        if (a == null) return null;
        Live kept = LIVE.get(a.key()); if (kept != null) return kept;
        NoticeListener l = live;
        if (l != null && !a.nkey().isEmpty()) try {
            StatusBarNotification[] showing = l.getActiveNotifications(new String[]{a.nkey()});
            for (StatusBarNotification n : showing == null ? new StatusBarNotification[0] : showing) {
                Notification note = n == null ? null : n.getNotification(); if (note == null) continue;
                Live found = new Live(n.getPackageName(), note.contentIntent, NoticeReply.replyAction(note), n.getKey()); LIVE.put(a.key(), found); return found;
            }
        } catch (RuntimeException unavailable) { /* gone */ }
        return null;
    }
    /** For tests: what a tap would use for this act. */
    static void remember(String key, Live l) { if (key != null && l != null) LIVE.put(key, l); }
    /** For tests: nothing kept in memory (the preferences are read again). */
    static void forget() { synchronized (ACTS_LOCK) { acts = null; } LIVE.clear(); READ.clear(); TOLD.clear(); }
    /** Whether the act's notice can be answered right here (its own reply action). */
    static boolean canReply(Context c, NoticeActions.Act a) { Live l = live(c, a); return l != null && l.reply() != null; }

    @Override public void onNotificationPosted(StatusBarNotification posted) {
        try { readDelivery(posted); } catch (RuntimeException unreadable) { /* not an order after all */ }
        // Off the main thread: the words read, the acts kept (preferences) and the app's name looked up.
        try { ACT_IO.execute(() -> { try { readAct(posted); } catch (RuntimeException unreadable) { /* not an act after all */ } }); } catch (RuntimeException stopped) { /* none */ }
        // 조건 알림 (사건 트리거): the same notice shown to the notice triggers (배달 출발, 택배, 엄마한테 카톡 …), also off the main thread.
        try { ACT_IO.execute(() -> { try { TriggerWatch.notice(this, posted); } catch (RuntimeException unreadable) { /* no trigger heard it */ } }); } catch (RuntimeException stopped) { /* none */ }
        tell();
        try {
            if (posted == null || !on(this) || getPackageName().equals(posted.getPackageName())) return;
            Notification n = posted.getNotification(); if (n == null) return;
            if (posted.isOngoing() || (n.flags & Notification.FLAG_GROUP_SUMMARY) != 0 || (n.flags & Notification.FLAG_FOREGROUND_SERVICE) != 0) return;
            if (Notification.CATEGORY_TRANSPORT.equals(n.category) || Notification.CATEGORY_PROGRESS.equals(n.category) || Notification.CATEGORY_SERVICE.equals(n.category)) return;
            Bundle extras = n.extras;
            CharSequence title = extras.getCharSequence(Notification.EXTRA_TITLE), big = extras.getCharSequence(Notification.EXTRA_BIG_TEXT), text = extras.getCharSequence(Notification.EXTRA_TEXT);
            String label = posted.getPackageName();
            try { PackageManager pm = getPackageManager(); label = pm.getApplicationLabel(pm.getApplicationInfo(posted.getPackageName(), 0)).toString(); } catch (Exception ignored) {}
            Notices.Notice notice = new Notices.Notice(posted.getPackageName(), label, title == null ? "" : title.toString().strip(),
                (big != null ? big : text == null ? "" : text).toString().strip(), System.currentTimeMillis());
            if (!Notices.keep(notice)) return;
            // 하루 기록: the notice as a short line (a chat's sender and the start of it; a bank's only "알림"), off this thread.
            DayLogs.notice(notice.app(), notice.label(), notice.title(), notice.text(), notice.at());
            String json;
            synchronized (LOCK) { list(this); kept = Notices.add(kept, notice, notice.at()); json = Notices.save(kept); }
            File f = file(this), next = new File(f.getPath() + ".new");
            Files.write(next.toPath(), json.getBytes(StandardCharsets.UTF_8)); if (!next.renameTo(f)) { f.delete(); next.renameTo(f); }
        } catch (Exception quiet) { /* A notification that cannot be read is left out. */ }
    }
}
