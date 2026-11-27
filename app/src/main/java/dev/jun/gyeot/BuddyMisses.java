package dev.jun.gyeot;

import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.provider.Settings;
import java.util.ArrayList;
import java.util.List;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * What 퍼레 was asked to do and could not (준: "1번 진행" — 실패한 동작을 앱이 스스로 모아 보여 주기): when, the words, what it
 * tried, why not, and the one tap that clears the way when there is one (the overlay permission, a place to save, the app
 * to install, the system-settings permission, the contacts permission). The last thirty, on this phone only; shown in 화면 꾸미기 → 퍼레가 못 한 일.
 */
final class BuddyMisses {
    private BuddyMisses() {}
    private static final String PREFS = "buddyTalk", KEY = "misses";
    static final int MOST = 30;
    /**
     * fix: "overlay", "writeSettings", "place:<words>", "store:<app>", "contacts", "accessibility", "memory", "settings:<page>", or "" for none.
     * kind: what went wrong (FAIL: it could not; NOT_SHOWN: the app it opened did not come up; NOT_PLAYING: the music app
     * it asked did not play; PROMISED: words said it would and no tool came; CORRECTED: the user said it was the wrong thing).
     */
    record Miss(long at, String said, String what, String why, String fix, String kind) {}
    static final String FAIL = "fail", NOT_SHOWN = "not_shown", NOT_PLAYING = "not_playing", PROMISED = "promised", CORRECTED = "corrected";

    static void add(Context c, String said, String what, String why, String fix) { add(c, said, what, why, fix, FAIL); }
    static synchronized void add(Context c, String said, String what, String why, String fix, String kind) {
        try {
            SharedPreferences p = c.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
            JSONArray old = new JSONArray(p.getString(KEY, "[]")), now = new JSONArray();
            now.put(new JSONObject().put("at", System.currentTimeMillis()).put("said", clip(said)).put("what", clip(what)).put("why", clip(why)).put("fix", fix == null ? "" : fix)
                .put("kind", kind == null || kind.isBlank() ? FAIL : kind));
            for (int i = 0; i < old.length() && now.length() < MOST; i++) now.put(old.get(i));
            p.edit().putString(KEY, now.toString()).apply();
        } catch (Exception unreadable) { /* not kept */ }
    }
    static List<Miss> list(Context c) {
        List<Miss> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) { JSONObject m = a.optJSONObject(i); if (m != null) out.add(new Miss(m.optLong("at"), m.optString("said"), m.optString("what"), m.optString("why"), m.optString("fix"), m.optString("kind", FAIL))); }
        } catch (Exception unreadable) { /* none */ }
        return out;
    }
    static void clear(Context c) { c.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().remove(KEY).apply(); }
    private static String clip(String s) { String v = s == null ? "" : s.strip(); return v.length() > 120 ? v.substring(0, 120) + "…" : v; }

    /** The tap that clears the way, as its button's words; "" when there is none. */
    static String fixLabel(String fix) {
        if (fix == null || fix.isEmpty()) return "";
        if (fix.equals("overlay")) return "위에 보이기 허용";
        if (fix.equals("writeSettings")) return "설정 변경 허용";
        if (fix.startsWith("place:")) return "장소 저장";
        if (fix.startsWith("store:")) return "앱 찾기";
        if (fix.equals("contacts")) return "연락처 허용";
        if (fix.equals("accessibility")) return "폰 조작 켜기";
        if (fix.equals("memory")) return "퍼레의 기억 열기";
        if (fix.startsWith("settings:")) return "설정 열기";
        // 조건 알림 (사건 트리거): what a trigger needs to be heard.
        if (fix.equals("allow:location") || fix.equals("allow:bluetooth")) return "권한 허용";
        if (fix.startsWith("pick:")) return "기기 고르기";
        if (fix.equals("notices")) return "알림 접근 허용";
        return "";
    }
    /** That tap's screen, or null. */
    static Intent fixIntent(Context c, String fix) {
        if (fix == null || fix.isEmpty()) return null;
        if (fix.equals("overlay")) return new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + c.getPackageName()));
        if (fix.equals("writeSettings")) return new Intent(Settings.ACTION_MANAGE_WRITE_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        if (fix.startsWith("place:")) return new Intent(c, NavigationActivity.class).putExtra(NavigationActivity.ADD, fix.substring(6));
        if (fix.startsWith("store:")) return new Intent(Intent.ACTION_VIEW, Uri.parse("market://search?q=" + Uri.encode(fix.substring(6))));
        if (fix.equals("contacts")) return new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + c.getPackageName()));
        if (fix.equals("accessibility")) return new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS);
        // 퍼레의 기억 (opened after the fingerprint): where memories are wiped all at once or picked one by one.
        if (fix.equals("memory")) return new Intent(c, DecorActivity.class).putExtra(DecorActivity.MEMORY, true);
        // A page of the app's settings (폰 조작, AI 연결 …): what a job 퍼레 could not take needs (BuddyHands).
        if (fix.startsWith("settings:")) return new Intent(c, MainActivity.class).putExtra("tab", 2).putExtra(MainActivity.SETTINGS_PAGE, fix.substring(9)).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP);
        // 조건 알림: Android's own permission screens after one line why, the paired devices to pick from, 알림 접근.
        if (fix.equals("allow:location")) return TriggerActivity.allow(c, "location");
        if (fix.equals("allow:bluetooth")) return TriggerActivity.allow(c, "bluetooth");
        if (fix.startsWith("pick:")) return TriggerActivity.pick(c, fix.substring(5));
        if (fix.equals("notices")) return new Intent(Settings.ACTION_NOTIFICATION_LISTENER_DETAIL_SETTINGS).putExtra(Settings.EXTRA_NOTIFICATION_LISTENER_COMPONENT_NAME, new android.content.ComponentName(c, NoticeListener.class).flattenToString());
        return null;
    }
}
